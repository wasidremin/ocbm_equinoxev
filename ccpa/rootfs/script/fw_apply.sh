#!/bin/sh
# fw_apply.sh — the adapter's half of "update the adapter from the app" (docs/ops/08_FUTURE_TASKS.md
# T20). ocbmd runs it detached for MGMT_FW; ocbm_boot.sh runs `boot` when an update is in its trial.
#
#   check     hash every file the manifest names; write $D/check.json (which differ, free space)
#   apply     1 checks   refuse, changing nothing: the manifest's paths and modes, every staged file's
#                        SHA-256, no update already in its trial, not older than what is installed
#                        (seq), and flash room for the new files
#             2 place    each new file as <path>.new, checked against its sum again
#             3 journal  $PEND lists every file (replace/add) BEFORE anything is renamed
#             4 swap     renames only: old -> <path>.prev, <path>.new -> <path>; then "placed"
#             5 restart  into the update's trial
#   confirm   the app saw the adapter come back healthy: keep the update (the .prev files stay, for a
#             manual rollback, until the next update)
#   rollback  put the previous files back and reboot
#   boot      the trial, from ocbm_boot.sh: a journal without "placed" (power lost in the swap) is
#             undone at once; so is a third start without a confirmation; ocbmd not running for
#             60 s rolls back; and once a host has said HELLO, no confirmation within $WINDOW_S s
#             rolls back. With no host at all the trial stays open: a car switched off right after
#             an update must not undo a good one.
#
# Manifest lines, staged with the files in $D (tmpfs) by the app over OCBM CH_FILE:
#   version <hex id>
#   nonce <hex>          echoed in check.json / apply.json, so the app never reads a stale answer
#   seq <n>              optional: the app build's number; an adapter on a higher one refuses
#   <sha256> <mode> <absolute path>
#
# NEVER written from here, so a bad update can always be undone: the boot path (ocbm_boot.sh,
# run_ocbmd.sh, ocbm_udisk.sh) and this script. Those are bench-installed (tools/ocbm_install.sh).
#
# The journal, the place-then-rename order, the boot-time undo, starting the trial's clock at the
# first host and the 0.7 flash ratio follow enigy/ocbm's ccpa/installer/ocbm_update.sh and
# ocbm_dispatch.sh (Unlicense), adapted to this tree's per-file staging and app confirmation.
#
# Test hooks (tools/tests/fw_apply_test.sh): FW_ROOT, FW_NO_REBOOT=1, FW_WINDOW_S, FW_FREE_KB,
# FW_OCBMD_CHECK (a command; default `pidof ocbmd`), FW_OCBMD_GRACE_S, FW_STOP_AT=swap-mid (exit 99
# between the two renames of the first file, as a power cut would).

R="${FW_ROOT:-}"
D="$R/tmp/fwup"
M="$D/manifest"
LOG="$R/tmp/box.log"
PEND="$R/script/fw_pending"
PREVLIST="$R/script/fw_prev.list"
VER="$R/script/fw_version"
SEQ="$R/script/fw_seq"
HELLO="$R/tmp/ocbm_hello_seen"
LOCK="$R/tmp/fw_apply.lock"
RESERVE_KB=512
WINDOW_S="${FW_WINDOW_S:-300}"
OCBMD_GRACE_S="${FW_OCBMD_GRACE_S:-60}"

log() { echo "[fw] $*" >> "$LOG"; }

# Atomic, so a reader polling over CH_FILE never sees half a file.
put_json() { printf '%s\n' "$2" > "$1.part" && mv "$1.part" "$1"; }

staged() { echo "$D/$(echo "$1" | tr / _)"; }
sum_of() { sha256sum "$1" 2>/dev/null | cut -c1-64; }
field() { awk -v k="$1" '$1==k {print $2}' "$M" 2>/dev/null | head -1; }
nonce() { field nonce | tr -cd '0-9a-f' | cut -c1-32; }
num() { case "$1" in ''|*[!0-9]*) echo "" ;; *) echo "$1" ;; esac; }

allowed() {
  case "$1" in /usr/sbin/*|/usr/bin/*|/script/*) ;; *) return 1 ;; esac
  case "$1" in *..*|*" "*|*'"'*|*\\*) return 1 ;; esac
  case "$1" in
    /script/ocbm_boot.sh|/script/run_ocbmd.sh|/script/ocbm_udisk.sh|/script/fw_apply.sh) return 1 ;;
  esac
  return 0
}

free_kb() { if [ -n "${FW_FREE_KB:-}" ]; then echo "$FW_FREE_KB"; else df -k "$R/" | awk 'NR==2 {print $4}'; fi; }

# What a file takes on the flash: jffs2 holds these at about 0.62-0.8 of their size (enigy/ocbm,
# measured on this adapter model), so 0.7 of du's figure, rounded up.
kb_on_flash() { s=$(du -k "$1" 2>/dev/null | cut -f1); echo $(( (${s:-0} * 7 + 9) / 10 )); }

version_of() { tr -cd '0-9a-f' < "$1" 2>/dev/null | cut -c1-16; }

reboot_soon() {
  [ "${FW_NO_REBOOT:-0}" = 1 ] && return 0
  ( sleep 2; sync; reboot ) >/dev/null 2>&1 &
}

lock() { mkdir -p "$R/tmp"; mkdir "$LOCK" 2>/dev/null; }
unlock() { rmdir "$LOCK" 2>/dev/null; }

# Put back every file a journal lists ($1: the pending journal or the kept list): replaced files from
# their .prev, added files removed, stray .new files removed.
restore_from() {
  [ -s "$1" ] || return 0
  awk '$1=="replace" || $1=="add" {print $1, $2}' "$1" | while read -r kind p; do
    if [ "$kind" = replace ]; then [ -e "$R$p.prev" ] && mv -f "$R$p.prev" "$R$p"
    else rm -f "$R$p"; fi
    rm -f "$R$p.new"
  done
  [ -f "$VER.prev" ] && mv -f "$VER.prev" "$VER"
  [ -f "$SEQ.prev" ] && mv -f "$SEQ.prev" "$SEQ"
  sync
}

check() {
  mkdir -p "$D"
  rm -f "$D/check.json"
  [ -f "$M" ] || { put_json "$D/check.json" '{"ok":false,"error":"no manifest"}'; return 1; }
  differs=""
  while read -r h m p; do
    case "$h" in version|nonce|seq) continue ;; esac
    [ -n "$p" ] || continue
    allowed "$p" || { put_json "$D/check.json" "{\"ok\":false,\"nonce\":\"$(nonce)\",\"error\":\"path not allowed: $p\"}"; return 1; }
    [ "$(sum_of "$R$p")" = "$h" ] || differs="$differs${differs:+,}\"$p\""
  done < "$M"
  pending=false; [ -e "$PEND" ] && pending=true
  prev=false; [ -s "$PREVLIST" ] && prev=true
  put_json "$D/check.json" "{\"ok\":true,\"nonce\":\"$(nonce)\",\"differs\":[$differs],\"free_kb\":$(free_kb),\"pending\":$pending,\"prev\":$prev,\"version\":\"$(version_of "$VER")\",\"seq\":\"$(num "$(cat "$SEQ" 2>/dev/null)")\"}"
}

apply_fail() {
  log "update refused: $1"
  put_json "$D/apply.json" "{\"ok\":false,\"nonce\":\"$(nonce)\",\"error\":\"$1\"}"
  # Only what this run placed: with no journal, every .new beside an allowed path is ours.
  [ -f "$PEND" ] || find "$R/script" "$R/usr/sbin" "$R/usr/bin" -name '*.new' -type f 2>/dev/null | while read -r f; do rm -f "$f"; done
  unlock
  exit 1
}

apply() {
  mkdir -p "$D"
  rm -f "$D/apply.json"
  lock || { put_json "$D/apply.json" "{\"ok\":false,\"nonce\":\"$(nonce)\",\"error\":\"another update or rollback is running\"}"; exit 1; }
  # 1 checks
  [ -f "$M" ] || apply_fail "no manifest"
  [ -e "$PEND" ] && apply_fail "the last update is still in its trial, waiting for the app to confirm it"
  newver=$(field version | tr -cd '0-9a-f' | cut -c1-16)
  newseq=$(num "$(field seq)"); haveseq=$(num "$(cat "$SEQ" 2>/dev/null)")
  if [ -n "$newseq" ] && [ -n "$haveseq" ] && [ "$newseq" -lt "$haveseq" ]; then
    apply_fail "the adapter has newer firmware (from app build $haveseq) than this app (build $newseq)"
  fi
  need=0; list=""
  while read -r h m p; do
    case "$h" in version|nonce|seq) continue ;; esac
    [ -n "$p" ] || continue
    allowed "$p" || apply_fail "path not allowed: $p"
    s=$(staged "$p")
    [ -f "$s" ] || continue        # unchanged files are not staged
    case "$m" in 755|644) ;; *) apply_fail "mode not allowed: $m for $p" ;; esac
    [ "$(sum_of "$s")" = "$h" ] || apply_fail "hash mismatch: $p"
    need=$((need + $(kb_on_flash "$s")))
    list="$list $p"
  done < "$M"
  [ -n "$list" ] || apply_fail "nothing staged"
  # The previous update's .prev copies go now: their room counts toward this one.
  if [ -s "$PREVLIST" ]; then
    awk '$1=="replace" {print $2}' "$PREVLIST" | while read -r p; do rm -f "$R$p.prev"; done
    rm -f "$PREVLIST" "$VER.prev" "$SEQ.prev"
    sync
  fi
  free=$(free_kb)
  [ "$free" -ge $((need + RESERVE_KB)) ] || apply_fail "not enough room on the adapter: about ${need} KB needed plus ${RESERVE_KB} KB spare, ${free} KB free"
  # 2 place: every new file beside its old one, checked again after the copy. Running out of flash
  # here changes nothing: the .new files go.
  for p in $list; do
    s=$(staged "$p"); m=$(awk -v p="$p" '$3==p {print $2}' "$M"); h=$(awk -v p="$p" '$3==p {print $1}' "$M")
    { cp "$s" "$R$p.new" && chmod "$m" "$R$p.new" && [ "$(sum_of "$R$p.new")" = "$h" ]; } 2>/dev/null \
      || apply_fail "could not write $p (the flash may be full); nothing changed"
  done
  sync
  # 3 journal, before any rename
  {
    echo "boots 0"
    echo "from $(version_of "$VER")"; echo "to $newver"
    for p in $list; do if [ -e "$R$p" ]; then echo "replace $p"; else echo "add $p"; fi; done
  } > "$PEND.new" && mv -f "$PEND.new" "$PEND" && sync || apply_fail "could not write the update journal"
  # 4 swap: renames only
  first=1
  for p in $list; do
    [ -e "$R$p" ] && mv -f "$R$p" "$R$p.prev"
    if [ "${FW_STOP_AT:-}" = swap-mid ] && [ "$first" = 1 ]; then exit 99; fi
    first=0
    mv -f "$R$p.new" "$R$p"
  done
  rm -f "$VER.prev" "$SEQ.prev"
  [ -f "$VER" ] && cp "$VER" "$VER.prev"
  [ -f "$SEQ" ] && cp "$SEQ" "$SEQ.prev"
  echo "$newver" > "$VER"
  [ -n "$newseq" ] && echo "$newseq" > "$SEQ"
  echo placed >> "$PEND"
  sync
  # 5 restart
  put_json "$D/apply.json" "{\"ok\":true,\"nonce\":\"$(nonce)\",\"version\":\"$newver\",\"files\":$(echo "$list" | wc -w)}"
  log "update ${newver} placed:$list — restarting into its trial; it is undone unless the app confirms it once a host is back"
  unlock
  reboot_soon
}

confirm() {
  [ -e "$PEND" ] || { log "confirm: nothing pending"; return 0; }
  grep -qx placed "$PEND" || { log "confirm refused: the journal was never placed"; return 1; }
  # The list of what changed stays, with the .prev files, for a manual rollback.
  grep -E '^(replace|add) ' "$PEND" > "$PREVLIST.new" && mv -f "$PREVLIST.new" "$PREVLIST"
  rm -f "$PEND"
  sync
  log "update $(version_of "$VER") confirmed by the app — kept (the previous files stay for a manual rollback)"
}

rollback() {
  lock || { log "rollback: another update or rollback is running"; return 1; }
  if [ -e "$PEND" ]; then src="$PEND"; elif [ -s "$PREVLIST" ]; then src="$PREVLIST"
  else log "rollback: no previous files to restore"; unlock; return 1; fi
  log "rolling back to $(version_of "$VER.prev") — $1"
  restore_from "$src"
  rm -f "$PEND" "$PREVLIST"
  sync
  unlock
  reboot_soon
}

boot() {
  [ -e "$PEND" ] || return 0
  grep -qx placed "$PEND" || { rollback "power was lost while the update was being swapped in"; return; }
  n=$(awk '$1=="boots" {print $2}' "$PEND" 2>/dev/null); n=$(( ${n:-0} + 1 ))
  { sed "s/^boots .*/boots $n/" "$PEND" > "$PEND.new" && mv -f "$PEND.new" "$PEND"; sync; } 2>/dev/null
  [ "$n" -ge 3 ] && { rollback "start $n of the update without a confirmation"; return; }
  log "update $(version_of "$VER") in its trial (start $n): waiting for the app; the clock starts when a host connects"
  no_ocbmd=0; since_host=-1
  while [ -e "$PEND" ]; do
    if ${FW_OCBMD_CHECK:-pidof ocbmd} >/dev/null 2>&1; then no_ocbmd=0; else no_ocbmd=$((no_ocbmd + 1)); fi
    [ "$no_ocbmd" -ge "$OCBMD_GRACE_S" ] && { rollback "ocbmd has not run for ${OCBMD_GRACE_S}s"; return; }
    if [ "$since_host" -ge 0 ]; then
      since_host=$((since_host + 1))
      [ "$since_host" -ge "$WINDOW_S" ] && { rollback "a host connected but did not confirm within ${WINDOW_S}s"; return; }
    elif [ -e "$HELLO" ]; then
      since_host=0; log "update trial: a host connected; ${WINDOW_S}s to confirm"
    fi
    sleep 1
  done
  log "update confirmed"
}

case "$1" in
  check) check ;;
  apply) apply ;;
  confirm) confirm ;;
  rollback) rollback "asked for by the app" ;;
  boot) boot ;;
  *) echo "usage: $0 check|apply|confirm|rollback|boot" >&2; exit 2 ;;
esac
