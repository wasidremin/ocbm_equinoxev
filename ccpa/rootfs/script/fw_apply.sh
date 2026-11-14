#!/bin/sh
# fw_apply.sh — the adapter's half of "update the adapter from the app" (docs/ops/08_FUTURE_TASKS.md
# T20). ocbmd runs it detached for MGMT_FW; ocbm_boot.sh runs `boot` when an update is unconfirmed.
#
#   check     hash every file the manifest names; write $D/check.json (which differ, free space)
#   apply     verify every staged file's SHA-256 and the free space, then swap them in, keeping each
#             old file as <path>.prev, mark the update pending, and reboot
#   confirm   the app saw the adapter come back healthy: keep the new files
#   rollback  put every .prev back and reboot
#   boot      at boot with an update pending: roll back unless confirmed within $WINDOW_S seconds,
#             or at once on the third unconfirmed boot
#
# The app stages files and the manifest in $D (tmpfs) over OCBM CH_FILE. Manifest lines:
#   version <hex id>
#   nonce <hex>          (echoed in check.json / apply.json, so the app never reads a stale answer)
#   <sha256> <mode> <absolute path>
#
# NEVER touched from here, so a bad update can always be undone: the boot path (ocbm_boot.sh,
# run_ocbmd.sh, ocbm_udisk.sh) and this script. Those are bench-installed (tools/ocbm_install.sh).
#
# FW_ROOT prefixes every path, for tools/tests/fw_apply_test.sh; FW_NO_REBOOT=1 skips the reboot.

R="${FW_ROOT:-}"
D="$R/tmp/fwup"
M="$D/manifest"
LOG="$R/tmp/box.log"
PEND="$R/script/fw_pending"
PREVLIST="$R/script/fw_prev.list"
VER="$R/script/fw_version"
RESERVE_KB=512
WINDOW_S="${FW_WINDOW_S:-300}"

log() { echo "[fw] $*" >> "$LOG"; }

# Atomic, so a reader polling over CH_FILE never sees half a file.
put_json() { printf '%s\n' "$2" > "$1.part" && mv "$1.part" "$1"; }

staged() { echo "$D/$(echo "$1" | tr / _)"; }

nonce() { awk '$1=="nonce" {print $2}' "$M" 2>/dev/null | tr -cd '0-9a-f' | cut -c1-32; }

allowed() {
  case "$1" in /usr/sbin/*|/usr/bin/*|/script/*) ;; *) return 1 ;; esac
  case "$1" in *..*|*" "*|*'"'*|*\\*) return 1 ;; esac
  case "$1" in
    /script/ocbm_boot.sh|/script/run_ocbmd.sh|/script/ocbm_udisk.sh|/script/fw_apply.sh) return 1 ;;
  esac
  return 0
}

free_kb() { if [ -n "${FW_FREE_KB:-}" ]; then echo "$FW_FREE_KB"; else df -k "$R/" | awk 'NR==2 {print $4}'; fi; }

# What a file will take on the flash. jffs2 compresses each 4 KB node with zlib, a little worse than
# gzip of the whole file, so: gzip size plus 30 %. The binaries gzip to about half (2026-11-13), so
# budgeting their raw size refused an update of all four that fits with room to spare.
flash_kb() {
  if command -v gzip >/dev/null 2>&1; then
    echo $(( $(gzip -c "$1" | wc -c) * 13 / 10 / 1024 + 1 ))
  else
    echo $(( $(wc -c < "$1") / 1024 + 1 ))
  fi
}

version_of() { tr -cd '0-9a-f' < "$1" 2>/dev/null | cut -c1-16; }

reboot_soon() {
  [ "${FW_NO_REBOOT:-0}" = 1 ] && return 0
  ( sleep 2; sync; reboot ) >/dev/null 2>&1 &
}

# Put back every file the last apply replaced. A file that did not exist before (marked "+") is
# removed. Used by rollback and by a failed apply.
restore_prev() {
  [ -s "$PREVLIST" ] || return 0
  while read -r e; do
    case "$e" in
      +*) rm -f "$R${e#+}" ;;
      *) [ -f "$R$e.prev" ] && mv "$R$e.prev" "$R$e" ;;
    esac
  done < "$PREVLIST"
  rm -f "$PREVLIST"
  [ -f "$VER.prev" ] && mv "$VER.prev" "$VER"
  sync
}

check() {
  mkdir -p "$D"
  rm -f "$D/check.json"
  [ -f "$M" ] || { put_json "$D/check.json" '{"ok":false,"error":"no manifest"}'; return 1; }
  differs=""
  while read -r h m p; do
    [ "$h" = version ] || [ "$h" = nonce ] && continue
    [ -n "$p" ] || continue
    allowed "$p" || { put_json "$D/check.json" "{\"ok\":false,\"error\":\"path not allowed: $p\"}"; return 1; }
    cur=""
    [ -f "$R$p" ] && cur=$(sha256sum "$R$p" | cut -d' ' -f1)
    [ "$cur" = "$h" ] || differs="$differs${differs:+,}\"$p\""
  done < "$M"
  pending=false; [ -e "$PEND" ] && pending=true
  prev=false; [ -s "$PREVLIST" ] && prev=true
  put_json "$D/check.json" "{\"ok\":true,\"nonce\":\"$(nonce)\",\"differs\":[$differs],\"free_kb\":$(free_kb),\"pending\":$pending,\"prev\":$prev,\"version\":\"$(version_of "$VER")\"}"
}

apply_fail() {
  log "update refused: $1"
  put_json "$D/apply.json" "{\"ok\":false,\"nonce\":\"$(nonce)\",\"error\":\"$1\"}"
  exit 1
}

apply() {
  mkdir -p "$D"
  rm -f "$D/apply.json"
  [ -f "$M" ] || apply_fail "no manifest"
  [ -e "$PEND" ] && apply_fail "the last update is still waiting for the app to confirm it"
  newver=$(awk '$1=="version" {print $2}' "$M" | tr -cd '0-9a-f' | cut -c1-16)
  # Pass 1: everything is checked before anything changes.
  need=0; list=""
  while read -r h m p; do
    [ "$h" = version ] || [ "$h" = nonce ] && continue
    [ -n "$p" ] || continue
    allowed "$p" || apply_fail "path not allowed: $p"
    s=$(staged "$p")
    [ -f "$s" ] || continue        # unchanged files are not staged
    case "$m" in 755|644) ;; *) apply_fail "mode not allowed: $m for $p" ;; esac
    [ "$(sha256sum "$s" | cut -d' ' -f1)" = "$h" ] || apply_fail "hash mismatch: $p"
    need=$((need + $(flash_kb "$s")))
    list="$list $p"
  done < "$M"
  [ -n "$list" ] || apply_fail "nothing staged"
  # The previous update's rollback copies go now; their space counts toward this one.
  if [ -s "$PREVLIST" ]; then
    while read -r e; do case "$e" in +*) ;; *) rm -f "$R$e.prev" ;; esac; done < "$PREVLIST"
    rm -f "$PREVLIST"
    sync
  fi
  free=$(free_kb)
  [ "$free" -gt $((need + RESERVE_KB)) ] || apply_fail "not enough room on the adapter: ${need} KB needed plus ${RESERVE_KB} KB reserve, ${free} KB free"
  # Pass 2: each new file is written beside the old one, then renamed over it; the old one is kept
  # as .prev (a rename, no copy). A running binary keeps its inode until the reboot.
  rm -f "$VER.prev"; [ -f "$VER" ] && cp "$VER" "$VER.prev"
  for p in $list; do
    s=$(staged "$p")
    m=$(awk -v p="$p" '$3==p {print $2}' "$M")
    if ! cp "$s" "$R$p.new" || ! chmod "$m" "$R$p.new"; then
      rm -f "$R$p.new"
      restore_prev
      apply_fail "could not write $p — every file put back"
    fi
    if [ -f "$R$p" ]; then mv "$R$p" "$R$p.prev"; echo "$p" >> "$PREVLIST"
    else echo "+$p" >> "$PREVLIST"; fi
    mv "$R$p.new" "$R$p"
  done
  echo "$newver" > "$VER"
  echo "boots 0" > "$PEND"
  sync
  put_json "$D/apply.json" "{\"ok\":true,\"nonce\":\"$(nonce)\",\"version\":\"$newver\",\"files\":$(echo "$list" | wc -w)}"
  log "update ${newver} applied:$list — rebooting; it rolls back unless the app confirms within ${WINDOW_S}s"
  reboot_soon
}

confirm() {
  [ -e "$PEND" ] || { log "confirm: nothing pending"; return 0; }
  rm -f "$PEND"
  sync
  log "update $(version_of "$VER") confirmed by the app — kept (the previous files stay for a manual rollback)"
}

rollback() {
  [ -s "$PREVLIST" ] || { log "rollback: no previous files to restore"; return 1; }
  log "rolling back to $(version_of "$VER.prev") — $1"
  restore_prev
  rm -f "$PEND"
  sync
  reboot_soon
}

boot() {
  [ -e "$PEND" ] || return 0
  n=$(awk '$1=="boots" {print $2}' "$PEND" 2>/dev/null); n=$(( ${n:-0} + 1 ))
  echo "boots $n" > "$PEND"; sync
  if [ "$n" -ge 3 ]; then
    rollback "boot $n with the update unconfirmed"
    return
  fi
  log "update $(version_of "$VER") unconfirmed (boot $n) — waiting ${WINDOW_S}s for the app"
  i=0
  while [ "$i" -lt "$WINDOW_S" ]; do
    [ -e "$PEND" ] || { log "update confirmed ${i}s after boot"; return 0; }
    i=$((i + 1)); sleep 1
  done
  rollback "no confirmation within ${WINDOW_S}s of boot $n"
}

case "$1" in
  check) check ;;
  apply) apply ;;
  confirm) confirm ;;
  rollback) rollback "asked for by the app" ;;
  boot) boot ;;
  *) echo "usage: $0 check|apply|confirm|rollback|boot" >&2; exit 2 ;;
esac
