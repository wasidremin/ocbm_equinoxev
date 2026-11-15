#!/bin/sh
# build_wpa_supplicant.sh: the Wi-Fi client the adapter needs to join the car's hotspot
# (docs/ops/08_FUTURE_TASKS.md T15 route B). The stripped firmware carries hostapd and no client.
#
# Builds wpa_supplicant and wpa_cli from the pinned upstream release (BSD licence, NOTICE.md),
# verified by SHA-256 (of the release as downloaded from w1.fi over HTTPS, 2026-11-15), static for armv7 musl with the same zig toolchain as build.sh, into the box
# target directory. Small on purpose: the wext driver (moal is built with cfg80211_wext=0xf),
# internal crypto, no D-Bus, no config writing. Measured 578 KB + 131 KB (2026-11-15).
# Cached: the source tarball and the build stay under ~/.cache until the version changes.
set -eu
VER=2.11
SHA256=912ea06f74e30a8e36fbb68064d6cdff218d8d591db0fc5d75dee6c81ac7fc0a
REPO=$(cd "$(dirname "$0")/.." && pwd)
OUT=${BOX_OUT:-$REPO/target/armv7-unknown-linux-musleabihf/release}
CACHE=${XDG_CACHE_HOME:-$HOME/.cache}/ocbm-wpa
mkdir -p "$CACHE" "$OUT"
TAR=$CACHE/wpa_supplicant-$VER.tar.gz
if [ -x "$OUT/wpa_supplicant" ] && [ -x "$OUT/wpa_cli" ] && [ -f "$CACHE/built-$VER" ]; then
  echo "built  $OUT/wpa_supplicant (cached, $VER)"; exit 0
fi
[ -f "$TAR" ] || curl -sSfL -o "$TAR.part" "https://w1.fi/releases/wpa_supplicant-$VER.tar.gz" && { [ -f "$TAR" ] || mv "$TAR.part" "$TAR"; }
echo "$SHA256  $TAR" | sha256sum -c --quiet - || { rm -f "$TAR"; echo "FATAL: wpa_supplicant-$VER.tar.gz does not match its pinned SHA-256" >&2; exit 1; }
SRC=$CACHE/src-$VER
rm -rf "$SRC"; mkdir -p "$SRC"; tar -xzf "$TAR" -C "$SRC"
cd "$SRC/wpa_supplicant-$VER/wpa_supplicant"
cat > .config <<'CFG'
CONFIG_DRIVER_WEXT=y
CONFIG_TLS=internal
CONFIG_INTERNAL_LIBTOMMATH=y
CONFIG_CRYPTO=internal
CONFIG_CTRL_IFACE=y
CONFIG_BACKEND=file
CONFIG_NO_CONFIG_WRITE=y
CONFIG_NO_RANDOM_POOL=y
CONFIG_IEEE80211W=y
CONFIG_SME=y
CONFIG_ELOOP_POLL=y
CONFIG_OS=unix
CFG
ZIG=$(command -v zig || echo "$HOME/.local/opt/zig/zig")
make -s -j4 CC="$ZIG cc -target arm-linux-musleabihf -mcpu=cortex_a7 -Os -static" LDFLAGS="-static" wpa_supplicant wpa_cli >/dev/null
cp wpa_supplicant wpa_cli "$OUT/"
touch "$CACHE/built-$VER"
echo "built  $OUT/wpa_supplicant ($(wc -c < "$OUT/wpa_supplicant") bytes), wpa_cli ($(wc -c < "$OUT/wpa_cli") bytes)"
