#!/bin/bash
# stage_adapter_fw.sh — pack the adapter firmware into the app's assets (docs/ops/08_FUTURE_TASKS.md
# T20). Builds the box binaries (./build.sh, incremental), then writes $GM_ASSETS_DIR/adapter_fw:
# each file under its staged name (the path with / turned into _) and a manifest of
# `<sha256> <mode> <path>` lines headed by `version <id>`, the first 12 hex of the SHA-256 of
# those lines. The app's Settings ▸ Advanced ▸ Adapter firmware sends what differs.
#
# The set is tools/ocbm_install.sh --full's manifest minus what an app update must never write:
# the boot path (ocbm_boot.sh, run_ocbmd.sh, ocbm_udisk.sh) and fw_apply.sh, which undoes a bad
# update. Those stay bench-installed.
#
#   GM_ASSETS_DIR=<dir> host/gm_ccpa/tools/stage_adapter_fw.sh
set -euo pipefail
GM_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO="$(cd "$GM_ROOT/../.." && pwd)"
ARM="$REPO/target/armv7-unknown-linux-musleabihf/release"
: "${GM_ASSETS_DIR:?set GM_ASSETS_DIR to the assets directory of the app}"
OUT="$GM_ASSETS_DIR/adapter_fw"

# local path | box path | mode
FILES=(
  "$ARM/ocbmd|/usr/sbin/ocbmd|755"
  "$ARM/iap2d|/usr/sbin/iap2d|755"
  "$ARM/carplayd|/usr/sbin/carplayd|755"
  "$ARM/btd|/usr/sbin/btd|755"
  "$REPO/accessory_init/iap_role_switch.armv7|/usr/bin/iap_role_switch|755"
  "$REPO/ccpa/rootfs/script/radio_detect.sh|/script/radio_detect.sh|755"
  "$REPO/ccpa/rootfs/script/radio_hal.sh|/script/radio_hal.sh|755"
  "$REPO/ccpa/rootfs/script/radio_ap_up.sh|/script/radio_ap_up.sh|755"
  "$REPO/tools/session_supervisor.sh|/script/session_supervisor.sh|755"
  "$REPO/tools/projection_up.sh|/script/projection_up.sh|755"
  "$REPO/tools/phone_reset.sh|/script/phone_reset.sh|755"
  "$REPO/tools/run_supervisor.sh|/script/run_supervisor.sh|755"
)

( cd "$REPO" && sh build.sh >/dev/null ) || { echo "FATAL: adapter firmware build failed" >&2; exit 1; }

# Every file here must also be one the bench installer ships, so the two sets cannot drift apart.
for f in "${FILES[@]}"; do
  IFS='|' read -r local_f box_f mode <<< "$f"
  [ -f "$local_f" ] || { echo "FATAL: missing $local_f (is the fdk-aac prefix there for carplayd?)" >&2; exit 1; }
  grep -q "|$box_f|" "$REPO/tools/ocbm_install.sh" || { echo "FATAL: $box_f is not in tools/ocbm_install.sh's manifest" >&2; exit 1; }
done

rm -rf "$OUT"
mkdir -p "$OUT"
lines=""
for f in "${FILES[@]}"; do
  IFS='|' read -r local_f box_f mode <<< "$f"
  cp "$local_f" "$OUT/$(echo "$box_f" | tr / _)"
  lines+="$(sha256sum "$local_f" | cut -d' ' -f1) $mode $box_f"$'\n'
done
version="$(printf '%s' "$lines" | sha256sum | cut -c1-12)"
{ echo "version $version"; printf '%s' "$lines"; } > "$OUT/manifest"
echo "[adapter-fw] staged $(echo -n "$lines" | grep -c .) files, version $version, $(du -sk "$OUT" | cut -f1) KB -> $OUT"
