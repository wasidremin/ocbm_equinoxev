#!/bin/sh
# Host test for ccpa/rootfs/script/fw_apply.sh (T20): check, apply, confirm, rollback, the boot
# dead-man, and every refusal, in a throwaway root. Run: sh tools/tests/fw_apply_test.sh
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
  export FW_ROOT="$R" FW_NO_REBOOT=1 FW_WINDOW_S=2
}
sha() { printf '%s' "$1" | sha256sum | cut -d' ' -f1; }
stage() { printf '%s' "$2" > "$R/tmp/fwup/$(echo "$1" | tr / _)"; }
manifest() { # pairs of path content
  { echo "version def456"; echo "nonce 0a1b"
    while [ $# -ge 2 ]; do echo "$(sha "$2") 755 $1"; shift 2; done; } > "$R/tmp/fwup/manifest"
}
run() { ${FW_SH:-sh} "$FW" "$1" >/dev/null 2>&1; }
json() { cat "$R/tmp/fwup/$1.json" 2>/dev/null; }

# check names exactly the files whose hash differs
new_root
manifest /usr/sbin/carplayd new-carplayd /usr/sbin/btd old-btd
run check
expect "check lists only the changed file" 'json check | grep -q "\"differs\":\[\"/usr/sbin/carplayd\"\]"'
expect "check echoes the nonce" 'json check | grep -q "\"nonce\":\"0a1b\""'
expect "check reports the installed version" 'json check | grep -q "\"version\":\"abc123\""'

# apply swaps the staged file in, keeps the old one, marks the update pending
stage /usr/sbin/carplayd new-carplayd
run apply
expect "apply succeeds" 'json apply | grep -q "\"ok\":true"'
expect "new file in place" '[ "$(cat $R/usr/sbin/carplayd)" = new-carplayd ]'
expect "old file kept as .prev" '[ "$(cat $R/usr/sbin/carplayd.prev)" = old-carplayd ]'
expect "unchanged file untouched" '[ "$(cat $R/usr/sbin/btd)" = old-btd ] && [ ! -e $R/usr/sbin/btd.prev ]'
expect "mode applied" '[ -x $R/usr/sbin/carplayd ]'
expect "version recorded" '[ "$(cat $R/script/fw_version)" = def456 ]'
expect "update pending" '[ -e $R/script/fw_pending ]'

# a second apply waits for the first to be confirmed
run apply
expect "apply refused while pending" 'json apply | grep -q "still waiting"'

# confirm keeps it; rollback afterwards still restores
run confirm
expect "confirm clears pending" '[ ! -e $R/script/fw_pending ]'
run rollback
expect "rollback restores the old file" '[ "$(cat $R/usr/sbin/carplayd)" = old-carplayd ] && [ ! -e $R/usr/sbin/carplayd.prev ]'
expect "rollback restores the version" '[ "$(cat $R/script/fw_version)" = abc123 ]'

# the boot dead-man: no confirmation in the window rolls back
new_root
manifest /usr/sbin/carplayd new-carplayd
stage /usr/sbin/carplayd new-carplayd
run apply
run boot
expect "unconfirmed boot rolls back" '[ "$(cat $R/usr/sbin/carplayd)" = old-carplayd ] && [ ! -e $R/script/fw_pending ]'

# ... and a confirmation inside the window keeps the update
new_root
manifest /usr/sbin/carplayd new-carplayd
stage /usr/sbin/carplayd new-carplayd
run apply
( sleep 1; ${FW_SH:-sh} "$FW" confirm >/dev/null 2>&1 ) &
FW_WINDOW_S=4 run boot
wait
expect "confirmed boot keeps the update" '[ "$(cat $R/usr/sbin/carplayd)" = new-carplayd ]'

# the third unconfirmed boot rolls back at once
new_root
manifest /usr/sbin/carplayd new-carplayd
stage /usr/sbin/carplayd new-carplayd
run apply
echo "boots 2" > "$R/script/fw_pending"
FW_WINDOW_S=60 run boot
expect "third boot rolls back at once" '[ "$(cat $R/usr/sbin/carplayd)" = old-carplayd ]'

# refusals change nothing
new_root
manifest /usr/sbin/carplayd new-carplayd
stage /usr/sbin/carplayd tampered
run apply
expect "a bad hash is refused" 'json apply | grep -q "hash mismatch"'
expect "and nothing changed" '[ "$(cat $R/usr/sbin/carplayd)" = old-carplayd ] && [ ! -e $R/script/fw_pending ]'

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
expect "and nothing changed either" '[ "$(cat $R/usr/sbin/carplayd)" = old-carplayd ] && [ ! -e $R/script/fw_pending ]'

new_root
manifest /usr/sbin/carplayd new-carplayd
run apply
expect "nothing staged is refused" 'json apply | grep -q "nothing staged"'

# a file that did not exist before is removed again by rollback
new_root
manifest /usr/sbin/newtool hello
stage /usr/sbin/newtool hello
run apply
run rollback
expect "rollback removes a file the update added" '[ ! -e $R/usr/sbin/newtool ]'

[ "$fails" -eq 0 ] && echo "fw_apply: all passed" || { echo "fw_apply: $fails failed"; exit 1; }
