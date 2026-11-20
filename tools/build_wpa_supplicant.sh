#!/bin/sh
# build_wpa_supplicant.sh: the Wi-Fi client the adapter needs to join the car's hotspot
# (docs/ops/08_FUTURE_TASKS.md T15 route B). The stripped firmware carries hostapd and no client.
#
# Builds wpa_supplicant and wpa_cli from the pinned upstream release (BSD licence, NOTICE.md),
# verified by SHA-256 (of the release as downloaded from w1.fi over HTTPS, 2026-11-15), static for
# armv7 musl with the same zig toolchain as build.sh, into the box target directory. Internal
# crypto, no D-Bus, no config writing.
#
# Two driver interfaces, nl80211 first, wext second (radio_hal.sh picks one per start and logs it).
# wext alone (Play 252-260) does not pass a beacon's RSNXE up from the scan, so against an access
# point that advertises one the 4-way handshake fails at message 3/4 ("RSNXE mismatch"): measured on
# IW416 against a router by enigy/ocbm PR #2 (github.com/enigy/ocbm/pull/2, bench 2026-11-11), whose
# build this follows. Every join in the car with wext failed (Play 259, 260, 2026-11-17/18). nl80211
# needs libnl: libnl-3 and libnl-genl-3 only, from the pinned release (LGPL-2.1, linked statically and
# unmodified; NOTICE.md). CONFIG_DEBUG_FILE gives `-f`, the per-join log radio_hal.sh reads the
# failure reason from.
#
# Sizes: wext only 578 KB + 131 KB unstripped (2026-11-15); this build is measured in T15.
# Cached: the source tarballs and the build stay under ~/.cache until a version changes.
set -eu
VER=2.11
SHA256=912ea06f74e30a8e36fbb68064d6cdff218d8d591db0fc5d75dee6c81ac7fc0a
# libnl 3.12.0 as released on GitHub (thom311/libnl, tag libnl3_12_0); the same hash PR #2 checked.
NL_VER=3.12.0
NL_SHA256=fc51ca7196f1a3f5fdf6ffd3864b50f4f9c02333be28be4eeca057e103c0dd18
STAMP="built-$VER-nl$NL_VER"
REPO=$(cd "$(dirname "$0")/.." && pwd)
OUT=${BOX_OUT:-$REPO/target/armv7-unknown-linux-musleabihf/release}
CACHE=${XDG_CACHE_HOME:-$HOME/.cache}/ocbm-wpa
mkdir -p "$CACHE" "$OUT"
if [ -x "$OUT/wpa_supplicant" ] && [ -x "$OUT/wpa_cli" ] && [ -f "$CACHE/$STAMP" ]; then
  echo "built  $OUT/wpa_supplicant (cached, $VER, libnl $NL_VER)"; exit 0
fi

fetch() {  # $1 = file in $CACHE, $2 = URL, $3 = SHA-256
  [ -f "$CACHE/$1" ] || { curl -sSfL -o "$CACHE/$1.part" "$2" && mv "$CACHE/$1.part" "$CACHE/$1"; }
  echo "$3  $CACHE/$1" | sha256sum -c --quiet - || { rm -f "$CACHE/$1"; echo "FATAL: $1 does not match its pinned SHA-256" >&2; exit 1; }
}
fetch "wpa_supplicant-$VER.tar.gz" "https://w1.fi/releases/wpa_supplicant-$VER.tar.gz" "$SHA256"
fetch "libnl-$NL_VER.tar.gz" "https://github.com/thom311/libnl/releases/download/libnl3_$(echo "$NL_VER" | tr . _)/libnl-$NL_VER.tar.gz" "$NL_SHA256"

SRC=$CACHE/src-$VER-nl$NL_VER
rm -rf "$SRC"; mkdir -p "$SRC"
tar -xzf "$CACHE/wpa_supplicant-$VER.tar.gz" -C "$SRC"
tar -xzf "$CACHE/libnl-$NL_VER.tar.gz" -C "$SRC"
NL=$SRC/libnl-$NL_VER
ZIG=$(command -v zig || echo "$HOME/.local/opt/zig/zig")
CC_ARM="$ZIG cc -target arm-linux-musleabihf -mcpu=cortex_a7"

# bison and flex only regenerate libnl-route's grammars, which wpa_supplicant does not link; configure
# refuses to run without them, so it is given a stand-in that is never called.
( cd "$NL"
  YACC=false FLEX=false CC="$CC_ARM" AR="$ZIG ar" RANLIB="$ZIG ranlib" CFLAGS="-Os -w -ffunction-sections -fdata-sections" \
    ./configure --host=arm-linux-musleabihf --disable-shared --enable-static --disable-cli --disable-debug >/dev/null
  make -s -j4 lib/libnl-3.la lib/libnl-genl-3.la >/dev/null )

cd "$SRC/wpa_supplicant-$VER/wpa_supplicant"
cat > .config <<CFG
CONFIG_DRIVER_NL80211=y
CONFIG_LIBNL32=y
LIBNL_INC=$NL/include
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
CONFIG_DEBUG_FILE=y
CFG
# Flags through the environment, not make's command line: a command-line CFLAGS replaces the
# Makefile's own -I additions (LIBNL_INC among them) instead of extending them (PR #2).
CC="$CC_ARM" CFLAGS="-Os -MMD -ffunction-sections -fdata-sections" \
  LDFLAGS="-static -Wl,--gc-sections -s -L$NL/lib/.libs" \
  make -s -j4 wpa_supplicant wpa_cli >/dev/null
cp wpa_supplicant wpa_cli "$OUT/"
rm -f "$CACHE"/built-*
touch "$CACHE/$STAMP"
echo "built  $OUT/wpa_supplicant ($(wc -c < "$OUT/wpa_supplicant") bytes), wpa_cli ($(wc -c < "$OUT/wpa_cli") bytes)"
