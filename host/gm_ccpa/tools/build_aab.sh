#!/bin/bash
# Play-uploadable App Bundle build for wasidremin.gmccpa.
# APK production remains tools/build_apk.sh; this path uses Gradle only for AAB packaging.
#
# Usage:
#   AAB_VERSION_CODE=<n> AAB_VERSION_NAME=<name> tools/build_aab.sh            # build + sign only
#   AAB_VERSION_CODE=<n> AAB_VERSION_NAME=<name> tools/build_aab.sh --publish  # ... then upload to Play
#
# --publish runs Gradle Play Publisher's :app:publishPlayReleaseBundle, which uploads the bundle to
# the track configured in app/build.gradle (`automotive:internal`, status COMPLETED) and commits the
# edit — testers on the AAOS internal track get it without any Play Console clicking. Credentials:
# $PLAY_SERVICE_ACCOUNT, defaulting to <repo root>/play-service-account.json (gitignored, never
# commit it). AAB_VERSION_CODE must be strictly greater than every versionCode Play has ever seen
# for this package, drafts included — Play answers a reuse with `403 Version code N has already
# been used.` (see docs/14_LESSONS_LEARNED.md for the running high-water mark).
GM_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")/.." && pwd)"
CCPA_ROOT="$(cd "$GM_ROOT/../.." && pwd)"
PUBLISH=0
case "${1:-}" in
  --publish) PUBLISH=1;;
  '') ;;
  *) echo "usage: $0 [--publish]" >&2; exit 2;;
esac
export PLAY_SERVICE_ACCOUNT="${PLAY_SERVICE_ACCOUNT:-$CCPA_ROOT/play-service-account.json}"
APP_ROOT="$GM_ROOT/netprobe_app"
GRADLE="${GRADLE_BIN:-$HOME/.gradle/wrapper/dists/gradle-8.9-bin/90cnw93cvbtalezasaz0blq0a/gradle-8.9/bin/gradle}"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"

set -euo pipefail
export PATH="$HOME/.cargo/bin:$PATH"
[ -x "$GRADLE" ] || { echo "FATAL: Gradle not found at $GRADLE" >&2; exit 1; }

# Rust build scripts/procedural macros need a Linux host linker on this minimal server.
if ! command -v cc >/dev/null 2>&1; then
    ZIG="${ZIG:-$HOME/.local/opt/zig/zig}"
    [ -x "$ZIG" ] || { echo "FATAL: host cc is missing and Zig was not found at $ZIG" >&2; exit 1; }
    HOST_LINKER="$(mktemp -t gmccpa-zig-cc.XXXXXX)"
    trap 'rm -f "$HOST_LINKER"' EXIT
    printf '#!/bin/sh\nexec "%s" cc "$@"\n' "$ZIG" > "$HOST_LINKER"
    chmod 700 "$HOST_LINKER"
    export CC="$HOST_LINKER"
    export CARGO_TARGET_X86_64_UNKNOWN_LINUX_GNU_LINKER="$HOST_LINKER"
fi

export FDK_AAC_PREFIX="${FDK_AAC_PREFIX:-$CCPA_ROOT/scratchpad/fdk/install-android-x86_64}"
[ -f "$FDK_AAC_PREFIX/lib/libfdk-aac.a" ] || { echo "FATAL: missing libfdk-aac at $FDK_AAC_PREFIX" >&2; exit 1; }
NDK_VERSION="${ANDROID_NDK_VERSION:-30.0.15729638}"
NDK_ROOT="${ANDROID_NDK_HOME:-$SDK/ndk/$NDK_VERSION}"
NDK_PREBUILT="$(find "$NDK_ROOT/toolchains/llvm/prebuilt" -mindepth 1 -maxdepth 1 -type d -print -quit 2>/dev/null || true)"
[ -n "$NDK_PREBUILT" ] || { echo "FATAL: no NDK toolchain found under $NDK_ROOT" >&2; exit 1; }
NDK_BIN="$NDK_PREBUILT/bin"
NATIVE_DIR="$GM_ROOT/native/carplay-jni"
CARGO_TARGET_DIR="${CARGO_TARGET_DIR:-$HOME/.cache/cargo-targets/gm_ccpa-carplay-jni}"
export CARGO_TARGET_DIR

printf '[1/5] cargo JNI release builds (x86_64 + arm64)\n'
(
  cd "$NATIVE_DIR"
  CC_x86_64_linux_android="$NDK_BIN/x86_64-linux-android32-clang" \
  AR_x86_64_linux_android="$NDK_BIN/llvm-ar" \
  CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER="$NDK_BIN/x86_64-linux-android32-clang" \
  cargo build --release --target x86_64-linux-android
  # The ARM64 build omits mic-uplink-eld because the only local libfdk-aac prefix is x86_64.
  CC_aarch64_linux_android="$NDK_BIN/aarch64-linux-android32-clang" \
  AR_aarch64_linux_android="$NDK_BIN/llvm-ar" \
  CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$NDK_BIN/aarch64-linux-android32-clang" \
  cargo build --release --target aarch64-linux-android --no-default-features
)
X86_JNILIB="$CARGO_TARGET_DIR/x86_64-linux-android/release/libcarplayjni.so"
ARM64_JNILIB="$CARGO_TARGET_DIR/aarch64-linux-android/release/libcarplayjni.so"
[ -f "$X86_JNILIB" ] || { echo "FATAL: x86_64 JNI library missing: $X86_JNILIB" >&2; exit 1; }
[ -f "$ARM64_JNILIB" ] || { echo "FATAL: arm64 JNI library missing: $ARM64_JNILIB" >&2; exit 1; }
rm -rf "$APP_ROOT/build/jniLibs"
mkdir -p "$APP_ROOT/build/jniLibs/x86_64" "$APP_ROOT/build/jniLibs/arm64-v8a"
cp "$X86_JNILIB" "$APP_ROOT/build/jniLibs/x86_64/libcarplayjni.so"
cp "$ARM64_JNILIB" "$APP_ROOT/build/jniLibs/arm64-v8a/libcarplayjni.so"

STAMP_SHA="$(cd "$GM_ROOT" && git rev-parse --short HEAD 2>/dev/null || echo nogit)"
(cd "$GM_ROOT" && git diff --quiet HEAD 2>/dev/null) || STAMP_SHA="$STAMP_SHA-dirty"
export AAB_VERSION_CODE="${AAB_VERSION_CODE:-$(cd "$GM_ROOT" && git rev-list --count HEAD 2>/dev/null || echo 1)}"
export AAB_VERSION_NAME="${AAB_VERSION_NAME:-4.0+$STAMP_SHA}"
case "$AAB_VERSION_CODE" in ''|*[!0-9]*) echo "FATAL: AAB_VERSION_CODE must be numeric: $AAB_VERSION_CODE" >&2; exit 1;; esac
[ "$AAB_VERSION_CODE" -gt 0 ] || { echo "FATAL: versionCode must be > 0" >&2; exit 1; }

printf '[2/5] Gradle bundlePlayRelease\n'
(
  cd "$APP_ROOT"
  "$GRADLE" --offline --no-daemon --stacktrace :app:bundlePlayRelease
)

AAB="$APP_ROOT/app/build/outputs/bundle/playRelease/app-play-release.aab"
[ -f "$AAB" ] || { echo "FATAL: Gradle did not produce $AAB" >&2; exit 1; }

OUT="$GM_ROOT/apk/gmccpa-play-$STAMP_SHA.aab"
mkdir -p "$GM_ROOT/apk"
cp "$AAB" "$OUT"

printf '[3/5] verify AAB signature\n'
jarsigner -verify -verbose -certs "$OUT" >/tmp/gmccpa-aab-verify.txt
 grep -q 'jar verified' /tmp/gmccpa-aab-verify.txt

printf '[4/5] inspect bundle contents\n'
  unzip -l "$OUT" | grep -E 'base/manifest/AndroidManifest.xml|base/dex/classes.dex|base/lib/(x86_64|arm64-v8a)/libcarplayjni.so' >/tmp/gmccpa-aab-contents.txt
cat /tmp/gmccpa-aab-contents.txt

printf '[5/5] release summary\n'
printf 'AAB=%s\n' "$OUT"
printf 'package=wasidremin.gmccpa\nversionCode=%s\nversionName=%s\n' "$AAB_VERSION_CODE" "$AAB_VERSION_NAME"
printf 'uploadCertificateSha256='
keytool -list -v \
  -keystore "${PLAY_KEYSTORE:-$HOME/CodeSigning/wasidremin-gmccpa/upload-key.jks}" \
  -alias "${PLAY_KEY_ALIAS:-upload}" \
  -storepass:file "${PLAY_KEY_PASSWORD_FILE:-$HOME/CodeSigning/wasidremin-gmccpa/keystore-password.txt}" 2>/dev/null | awk -F': ' '/SHA256:/{print $2; exit}'
printf '\n'
rm -f /tmp/gmccpa-aab-verify.txt /tmp/gmccpa-aab-contents.txt

if [ "$PUBLISH" = 1 ]; then
  [ -f "$PLAY_SERVICE_ACCOUNT" ] || { echo "FATAL: no Play service-account key at $PLAY_SERVICE_ACCOUNT" >&2; exit 1; }
  printf '[publish] Gradle publishPlayReleaseBundle -> automotive:internal (versionCode %s)\n' "$AAB_VERSION_CODE"
  (
    cd "$APP_ROOT"
    "$GRADLE" --offline --no-daemon --stacktrace :app:publishPlayReleaseBundle
  )
  printf 'PUBLISH_OK versionCode=%s versionName=%s\n' "$AAB_VERSION_CODE" "$AAB_VERSION_NAME"
fi
