#!/bin/sh
# Host test for ccpa/rootfs/script/fw_apply.sh (T20): check, apply, confirm, rollback, the boot-time
# trial, every refusal, and a power cut in the swap, in a throwaway root.
#   sh tools/tests/fw_apply_test.sh            (FW_SH="busybox sh" to run it as the adapter would)
set -u
HERE="$(cd "$(dirname "$0")/../.." && pwd)"
FW="$HERE/ccpa/rootfs/script/fw_apply.sh"
fails=0
ok() { echo "ok   $1"; }
bad() { echo "FAIL $1"; fails=$((fails + 1)); }
expect() { if eval "$2"; then ok "$1"; else bad "$1"; fi; }

new_root() {
  R=$(mktemp -d)
  mkdir -p "$R/usr/sbin" "$R/script" "$R/tmp/fwup"
  printf 'old-carplayd' > "$R/usr/sbin/carplayd"
  printf 'old-btd' > "$R/usr/sbin/btd"
  printf 'abc123' > "$R/script/fw_version"
  export FW_ROOT="$R" FW_NO_REBOOT=1 FW_WINDOW_S=2 FW_OCBMD_CHECK=true FW_OCBMD_GRACE_S=2
  unset FW_STOP_AT FW_FREE_KB
  SEQLINE=""
}
sha() { printf '%s' "$1" | sha256sum | cut -d' ' -f1; }
stage() { printf '%s' "$2" > "$R/tmp/fwup/$(echo "$1" | tr / _)"; }
manifest() { # pairs of path content
  { echo "version def456"; echo "nonce 0a1b"; [ -n "$SEQLINE" ] && echo "$SEQLINE"
    while [ $# -ge 2 ]; do echo "$(sha "$2") 755 $1"; shift 2; done; } > "$R/tmp/fwup/manifest"
}
run() { ${FW_SH:-sh} "$FW" "$1" >/dev/null 2>&1; }
json() { cat "$R/tmp/fwup/$1.json" 2>/dev/null; }
is() { [ "$(cat "$R$1" 2>/dev/null)" = "$2" ]; }
one_update() { new_root; manifest /usr/sbin/carplayd new-carplayd; stage /usr/sbin/carplayd new-carplayd; run apply; }

# check names exactly the files whose hash differs
new_root
manifest /usr/sbin/carplayd new-carplayd /usr/sbin/btd old-btd
run check
expect "check lists only the changed file" 'json check | grep -q "\"differs\":\[\"/usr/sbin/carplayd\"\]"'
expect "check echoes the nonce" 'json check | grep -q "\"nonce\":\"0a1b\""'
expect "check reports the installed version" 'json check | grep -q "\"version\":\"abc123\""'

# apply: placed beside, journaled, swapped
stage /usr/sbin/carplayd new-carplayd
run apply
expect "apply succeeds" 'json apply | grep -q "\"ok\":true"'
expect "new file in place" 'is /usr/sbin/carplayd new-carplayd'
expect "old file kept as .prev" 'is /usr/sbin/carplayd.prev old-carplayd'
expect "no .new left behind" '[ ! -e $R/usr/sbin/carplayd.new ]'
expect "unchanged file untouched" 'is /usr/sbin/btd old-btd && [ ! -e $R/usr/sbin/btd.prev ]'
expect "mode applied" '[ -x $R/usr/sbin/carplayd ]'
expect "version recorded" 'is /script/fw_version def456'
expect "journal lists the file and is placed" 'grep -qx "replace /usr/sbin/carplayd" $R/script/fw_pending && grep -qx placed $R/script/fw_pending'

run apply
expect "a second apply waits for the first to be confirmed" 'json apply | grep -q "still in its trial"'

run confirm
expect "confirm ends the trial" '[ ! -e $R/script/fw_pending ] && grep -qx "replace /usr/sbin/carplayd" $R/script/fw_prev.list'
run rollback
expect "a manual rollback restores the old file" 'is /usr/sbin/carplayd old-carplayd && [ ! -e $R/usr/sbin/carplayd.prev ]'
expect "and the version" 'is /script/fw_version abc123'

# the trial at boot
one_update
touch "$R/tmp/ocbm_hello_seen"
run boot
expect "a host that never confirms: rolled back" 'is /usr/sbin/carplayd old-carplayd && [ ! -e $R/script/fw_pending ]'

one_update
${FW_SH:-sh} "$FW" boot >/dev/null 2>&1 &
bp=$!
sleep 4
expect "no host at all: the trial stays open" 'is /usr/sbin/carplayd new-carplayd && [ -e $R/script/fw_pending ]'
kill "$bp" 2>/dev/null; wait "$bp" 2>/dev/null

one_update
touch "$R/tmp/ocbm_hello_seen"
( sleep 1; ${FW_SH:-sh} "$FW" confirm >/dev/null 2>&1 ) &
FW_WINDOW_S=4 run boot
wait
expect "confirmed in the window: kept" 'is /usr/sbin/carplayd new-carplayd'

one_update
FW_OCBMD_CHECK=false run boot
expect "ocbmd not running: rolled back" 'is /usr/sbin/carplayd old-carplayd'

one_update
sed -i "s/^boots .*/boots 2/" "$R/script/fw_pending"
FW_WINDOW_S=60 run boot
expect "the third unconfirmed start rolls back at once" 'is /usr/sbin/carplayd old-carplayd'

# a power cut between the two renames of a file: the boot undoes it
new_root
manifest /usr/sbin/carplayd new-carplayd /usr/sbin/btd new-btd
stage /usr/sbin/carplayd new-carplayd; stage /usr/sbin/btd new-btd
FW_STOP_AT=swap-mid run apply
expect "the cut leaves a journal that was never placed" '[ -e $R/script/fw_pending ] && ! grep -qx placed $R/script/fw_pending'
rmdir "$R/tmp/fw_apply.lock" 2>/dev/null   # the lock is on tmpfs, which a power cut clears
run boot
expect "boot puts both files back" 'is /usr/sbin/carplayd old-carplayd && is /usr/sbin/btd old-btd'
expect "and removes the leftovers" '[ ! -e $R/usr/sbin/btd.new ] && [ ! -e $R/usr/sbin/carplayd.prev ] && [ ! -e $R/script/fw_pending ]'

# refusals change nothing
new_root
manifest /usr/sbin/carplayd new-carplayd
stage /usr/sbin/carplayd tampered
run apply
expect "a bad hash is refused" 'json apply | grep -q "hash mismatch"'
expect "and nothing changed" 'is /usr/sbin/carplayd old-carplayd && [ ! -e $R/script/fw_pending ]'

new_root
manifest /script/ocbm_boot.sh x
stage /script/ocbm_boot.sh x
run apply
expect "the boot path is never written" 'json apply | grep -q "not allowed" && [ ! -e $R/script/ocbm_boot.sh ]'

new_root
manifest /etc/passwd x
run check
expect "a path outside the allowed folders is refused" 'json check | grep -q "not allowed"'

new_root
manifest /usr/sbin/carplayd new-carplayd
stage /usr/sbin/carplayd new-carplayd
FW_FREE_KB=100 run apply
expect "too little room is refused" 'json apply | grep -q "not enough room"'
expect "and nothing changed either" 'is /usr/sbin/carplayd old-carplayd && [ ! -e $R/script/fw_pending ] && [ ! -e $R/usr/sbin/carplayd.new ]'

new_root
manifest /usr/sbin/carplayd new-carplayd
run apply
expect "nothing staged is refused" 'json apply | grep -q "nothing staged"'

new_root
echo 251 > "$R/script/fw_seq"
SEQLINE="seq 250"; manifest /usr/sbin/carplayd new-carplayd
stage /usr/sbin/carplayd new-carplayd
run apply
expect "an older app's firmware is refused" 'json apply | grep -q "newer firmware" && is /usr/sbin/carplayd old-carplayd'

new_root
echo 250 > "$R/script/fw_seq"
SEQLINE="seq 251"; manifest /usr/sbin/carplayd new-carplayd
stage /usr/sbin/carplayd new-carplayd
run apply
expect "a newer app's firmware is taken and its seq kept" 'json apply | grep -q "\"ok\":true" && is /script/fw_seq 251'
run rollback
expect "rollback puts the seq back" 'is /script/fw_seq 250'

new_root
mkdir "$R/tmp/fw_apply.lock"
manifest /usr/sbin/carplayd new-carplayd
stage /usr/sbin/carplayd new-carplayd
run apply
expect "a second update at the same time is refused" 'json apply | grep -q "another update" && is /usr/sbin/carplayd old-carplayd'

# a file that did not exist before is removed again by rollback
new_root
manifest /usr/sbin/newtool hello
stage /usr/sbin/newtool hello
run apply
run rollback
expect "rollback removes a file the update added" '[ ! -e $R/usr/sbin/newtool ]'

[ "$fails" -eq 0 ] && echo "fw_apply: all passed" || { echo "fw_apply: $fails failed"; exit 1; }
