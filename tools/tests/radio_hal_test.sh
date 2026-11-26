#!/bin/sh
# Tests for radio_hal.sh and the supervisor's radio start that need no radio.
#   sh tools/tests/radio_hal_test.sh
set -u
HERE="$(cd "$(dirname "$0")/../.." && pwd)"
F="$HERE/ccpa/rootfs/script/radio_hal.sh"
S="$HERE/tools/session_supervisor.sh"
fails=0
is() { if [ "$2" = "$3" ]; then echo "ok   $1"; else echo "FAIL $1: got '$2' want '$3'"; fails=$((fails+1)); fi; }
# The connect timeline's bt_power: bt_on stamps it on whichever path brought Bluetooth up. The bench
# run of 2026-11-21 printed bt_power=- because the already-up and owned-backend paths did not stamp.
n_ok=$(sed -n '/^do_bt_on() {/,/^}/p' "$F" | grep -c 'publish "$BT_STATE" "result=ok"')
n_st=$(sed -n '/^do_bt_on() {/,/^}/p' "$F" | grep -c '^ *bt_power_stamp$')
is "every bt_on success stamps bt_power ($n_ok of them)" "$n_st" "$n_ok"
T=$(mktemp -d)
eval "$(sed -n '/^bt_power_stamp() {/,/^}/p' "$F" | sed "s#/tmp/#$T/#g")"
VERB=bt_prewarm bt_power_stamp
is "a prewarm stamps nothing" "$(cat "$T/startup_marks" 2>/dev/null)" ""
VERB=bt_on bt_power_stamp
is "bt_on stamps one line in milliseconds" "$(sed 's/[0-9]\{3,\}$/MS/' "$T/startup_marks")" "t bt_power MS"
rm -rf "$T"
# The Wi-Fi link to the car's hotspot (T15) was removed on 2026-11-25: in the car it made every start
# wait up to 15 s for the hotspot before Bluetooth, and its join was always a start behind the
# hotspot's channel. Nothing may still join, wait for or pin a channel to it.
is "radio_hal has no client for the car's hotspot" "$(grep -c -E 'uplink|sta_keep|wpa_supplicant' "$F")" 0
is "the access point takes no channel from a client link" "$(grep -c RADIO_AP_CHANNEL "$HERE/ccpa/rootfs/script/radio_ap_up.sh")" 0
is "the supervisor neither waits for the hotspot nor starts a keeper" "$(grep -c -E 'grep -q .*sta_uplink|sta_keep|_wifi_pid|radio_uplink' "$S")" 0
# Its files hold the hotspot's passphrase and the link key: the supervisor removes them, once, and says so.
T=$(mktemp -d)
mkdir -p "$T/script" "$T/etc/ocbm" "$T/tmp"
echo 'psk=secret' > "$T/script/sta_uplink.conf"; echo key > "$T/etc/ocbm/wifi_link"; echo keep > "$T/script/other.conf"
loop=$(sed -n '/^for _old in /,/^done/p' "$S" | sed "s#/script/#$T/script/#g; s#/etc/ocbm/#$T/etc/ocbm/#g; s#/tmp/box.log#$T/tmp/box.log#")
eval "$loop"; eval "$loop"
is "the passphrase file is gone" "$([ -e "$T/script/sta_uplink.conf" ] && echo there || echo gone)" gone
is "the link key is gone" "$([ -e "$T/etc/ocbm/wifi_link" ] && echo there || echo gone)" gone
is "nothing else is touched" "$(cat "$T/script/other.conf")" keep
is "each removal is logged once, by path only" "$(grep -c 'removed .* (the retired Wi-Fi link)' "$T/tmp/box.log")" 2
is "the log never carries a file's contents" "$(grep -c secret "$T/tmp/box.log")" 0
rm -rf "$T"
# The driver modules reach /tmp whole (bench, 2026-11-26: wifi_ap_on loaded an mlan.ko that bt_on's untar
# was still writing, "invalid module format", and the phone had no access point).
is "nothing untars straight into /tmp" "$(grep -c -E 'tar -x.* -C /tmp( |$)' "$F")" 0
T=$(mktemp -d)
mkdir "$T/src"; head -c 4096 /dev/zero > "$T/src/mlan.ko"; echo ldisc > "$T/src/hci_uart.ko"
RADIO_KO_TARBALL="$T/ko.tar.gz"; tar -czf "$RADIO_KO_TARBALL" -C "$T/src" mlan.ko hci_uart.ko
eval "$(sed -n '/^ko_unpack() {/,/^}/p' "$F" | sed "s#/tmp/#$T/#g; s#/tmp\([ \"]\)#$T\1#g")"
seen=""
tar() { for _a; do [ "$_prev" = -C ] && seen="$seen $_a $(ls "$T" | grep -c '\.ko$')"; _prev=$_a; done; command tar "$@"; }
_prev=""; ko_unpack mlan.ko; r=$?
unset -f tar
is "ko_unpack says the module is there" "$r" 0
is "the untar writes into a directory of its own, with no module in /tmp yet" "$(echo "$seen" | awk '{print ($1 != "'"$T"'" ? "own" : "tmp"), $2}')" "own 0"
is "every module arrives whole" "$(wc -c < "$T/mlan.ko" | tr -d ' ') $(cat "$T/hci_uart.ko")" "4096 ldisc"
is "its directory is gone" "$(ls -A "$T" | grep -c '^\.ko_unpack')" 0
rm "$T/hci_uart.ko"; ko_unpack hci_uart.ko
is "a module still missing is unpacked again" "$(cat "$T/hci_uart.ko")" ldisc
rm -rf "$T"
[ "$fails" -eq 0 ] && echo "radio_hal: all passed" || { echo "radio_hal: $fails failed"; exit 1; }
