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

set -euo pipefail
export PATH="$HOME/.cargo/bin:$PATH"   # stage_adapter_fw.sh runs ./build.sh
[ -x "$GRADLE" ] || { echo "FATAL: Gradle not found at $GRADLE" >&2; exit 1; }

export GM_BUILD_DIR="${GM_BUILD_DIR:-$HOME/.cache/carlink-artifacts/gm_ccpa/gradle}"

STAMP_SHA="$(cd "$GM_ROOT" && git rev-parse --short HEAD 2>/dev/null || echo nogit)"
(cd "$GM_ROOT" && git diff --quiet HEAD 2>/dev/null) || STAMP_SHA="$STAMP_SHA-dirty"
export GM_ASSETS_DIR="$GM_BUILD_DIR/root/assets"
# Emptied first: `cp -R` only adds, so an asset deleted from the tree (info.bplist, 2026-11-26) would
# otherwise ship in every later bundle from this staging copy.
rm -rf "$GM_ASSETS_DIR"
mkdir -p "$GM_ASSETS_DIR"
cp -R "$APP_ROOT/app/src/main/assets/." "$GM_ASSETS_DIR/"
printf '%s\n' "$STAMP_SHA" > "$GM_ASSETS_DIR/build_sha"
export AAB_VERSION_CODE="${AAB_VERSION_CODE:-$(cd "$GM_ROOT" && git rev-list --count HEAD 2>/dev/null || echo 1)}"
export AAB_VERSION_NAME="${AAB_VERSION_NAME:-4.0+$STAMP_SHA}"
case "$AAB_VERSION_CODE" in ''|*[!0-9]*) echo "FATAL: AAB_VERSION_CODE must be numeric: $AAB_VERSION_CODE" >&2; exit 1;; esac
[ "$AAB_VERSION_CODE" -gt 0 ] || { echo "FATAL: versionCode must be > 0" >&2; exit 1; }
# The adapter firmware this app can install (Settings ▸ Advanced ▸ Adapter firmware, T20), stamped
# with this versionCode so an adapter never takes firmware older than it has.
"$GM_ROOT/tools/stage_adapter_fw.sh"

printf '[1/4] Gradle bundlePlayRelease\n'
(
  cd "$APP_ROOT"
  "$GRADLE" --offline --no-daemon --stacktrace :app:bundlePlayRelease
)

AAB="$GM_BUILD_DIR/app/outputs/bundle/playRelease/app-play-release.aab"
[ -f "$AAB" ] || { echo "FATAL: Gradle did not produce $AAB" >&2; exit 1; }

ARTIFACTS="${CARLINK_ARTIFACTS_DIR:-$HOME/.cache/carlink-artifacts/gm_ccpa}"
OUT="$ARTIFACTS/gmccpa-play-$STAMP_SHA.aab"
mkdir -p "$ARTIFACTS"
cp "$AAB" "$OUT"

printf '[2/4] verify AAB signature\n'
jarsigner -verify -verbose -certs "$OUT" >/tmp/gmccpa-aab-verify.txt
 grep -q 'jar verified' /tmp/gmccpa-aab-verify.txt

printf '[3/4] inspect bundle contents\n'
  unzip -l "$OUT" | grep -E 'base/manifest/AndroidManifest.xml|base/dex/classes.dex' >/tmp/gmccpa-aab-contents.txt
cat /tmp/gmccpa-aab-contents.txt

printf '[4/4] release summary\n'
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
