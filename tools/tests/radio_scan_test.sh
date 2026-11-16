#!/bin/sh
# Tests for the pure helpers of radio_hal.sh's car-hotspot scan and join (T15 route B).
#   sh tools/tests/radio_scan_test.sh
set -u
HERE="$(cd "$(dirname "$0")/../.." && pwd)"
F="$HERE/ccpa/rootfs/script/radio_hal.sh"
eval "$(sed -n '/^freq_to_channel() {/,/^}/p; /^channel_note() {/,/^}/p; /^uplink_ifname_ok()/p;
  /^channel_to_freq() {/,/^}/p; /^uplink_channel_ok() {/,/^}/p; /^uplink_pick_channel() {/,/^}/p;
  /^uplink_psk_ok() {/,/^}/p; /^same_24()/p' "$F")"
fails=0
is() { if [ "$2" = "$3" ]; then echo "ok   $1"; else echo "FAIL $1: got '$2' want '$3'"; fails=$((fails+1)); fi; }
is "2.4 GHz channel 1" "$(freq_to_channel 2412)" 1
is "2.4 GHz channel 11" "$(freq_to_channel 2462)" 11
is "channel 14" "$(freq_to_channel 2484)" 14
is "5 GHz channel 48" "$(freq_to_channel 5240)" 48
is "5 GHz channel 149" "$(freq_to_channel 5745)" 149
is "not a Wi-Fi frequency" "$(freq_to_channel 900)" ""
is "36 is plain 5 GHz" "$(channel_note 36)" 5GHz
is "52 is DFS" "$(channel_note 52)" DFS
is "100 is DFS" "$(channel_note 100)" DFS
is "144 is DFS" "$(channel_note 144)" DFS
is "149 is plain 5 GHz" "$(channel_note 149)" 5GHz
is "6 is 2.4 GHz" "$(channel_note 6)" 2.4GHz
uplink_ifname_ok sta0 && is "sta0 is a usable name" ok ok || is "sta0 is a usable name" no ok
uplink_ifname_ok 'sta0;reboot' && is "a name with a semicolon is refused" no ok || is "a name with a semicolon is refused" ok ok
uplink_ifname_ok '' && is "an empty name is refused" no ok || is "an empty name is refused" ok ok
# The scan's lines reach the streamed box log, not only wlan.log (lost in the car 2026-11-16).
T=$(mktemp -d)
eval "LOGP='[radio_hal]'; log() { :; }; $(sed -n '/^scan_log() /p' "$F" | sed "s#/tmp/box.log#$T/box.log#")"
scan_log "hotspot scan: the car's hotspot was NOT seen"
is "a scan line is written to the box log" "$(cat "$T/box.log")" "[radio_hal] hotspot scan: the car's hotspot was NOT seen"
rm -rf "$T"
n=$(sed -n '/^hotspot_scan() {/,/^}/p' "$F" | grep -c ' log "hotspot scan')
is "every scan line in hotspot_scan goes through scan_log" "$n" 0
# The join (2026-11-16).
is "channel 153 is 5765 MHz" "$(channel_to_freq 153)" 5765
is "channel 36 is 5180 MHz" "$(channel_to_freq 36)" 5180
is "a 2.4 GHz channel has no 5 GHz frequency" "$(channel_to_freq 6)" ""
is "garbage has no frequency" "$(channel_to_freq '1;x')" ""
ok() { if "$@"; then echo yes; else echo no; fi; }
is "153 is a channel the access point may move to" "$(ok uplink_channel_ok 153)" yes
is "36 too" "$(ok uplink_channel_ok 36)" yes
is "not DFS 100" "$(ok uplink_channel_ok 100)" no
is "not 2.4 GHz 6" "$(ok uplink_channel_ok 6)" no
is "not empty" "$(ok uplink_channel_ok '')" no
TAB=$(printf '\t')
# What the car showed on 2026-11-16: one dual-band hotspot (rows are bssid, freq, signal, flags, name).
rows="aa${TAB}5765${TAB}-25${TAB}[WPA2-PSK-CCMP][ESS]${TAB}Car
bb${TAB}2437${TAB}-22${TAB}[WPA2-PSK-CCMP][ESS]${TAB}Car
cc${TAB}5180${TAB}-40${TAB}[WPA2-PSK-CCMP][ESS]${TAB}Other"
is "the car's 5 GHz channel is picked, not its stronger 2.4 GHz one" "$(printf '%s\n' "$rows" | uplink_pick_channel Car)" 153
is "another network's channel is never picked" "$(printf '%s\n' "$rows" | uplink_pick_channel Nope)" ""
rows2="aa${TAB}5500${TAB}-30${TAB}[WPA2]${TAB}Car
bb${TAB}5745${TAB}-60${TAB}[WPA2]${TAB}Car"
is "a DFS channel is passed over for a usable one, though stronger" "$(printf '%s\n' "$rows2" | uplink_pick_channel Car)" 149
is "only DFS or 2.4 GHz: nothing to pick" "$(printf '%s\n' "aa${TAB}5500${TAB}-30${TAB}[WPA2]${TAB}Car" | uplink_pick_channel Car)" ""
is "a passphrase of 8 is usable" "$(ok uplink_psk_ok 12345678)" yes
is "7 is too short" "$(ok uplink_psk_ok 1234567)" no
is "64 is too long" "$(ok uplink_psk_ok "$(printf '%064d' 0)")" no
is "a quote is refused" "$(ok uplink_psk_ok 'abcd"efgh')" no
is "a backslash is refused" "$(ok uplink_psk_ok 'abcd\efgh')" no
is "a newline is refused" "$(ok uplink_psk_ok "$(printf 'abcd\nefgh')")" no
is "spaces are fine" "$(ok uplink_psk_ok 'my car pass')" yes
is "the access point's own /24 is a clash" "$(ok same_24 192.168.43.20 192.168.43.1)" yes
is "another /24 is not" "$(ok same_24 192.168.44.20 192.168.43.1)" no
is "no address is not a clash" "$(ok same_24 '' 192.168.43.1)" no
# A join that fails or loses its link must leave nothing running (car, 2026-11-15).
n=$(sed -n '/^uplink_run() {/,/^}/p' "$F" | grep -w return | grep -vc "uplink_stop guard; return")
is "every way out of a join stops the client first" "$n" 0
n=$(sed -n '/^uplink_run() {/,/^}/p' "$F" | grep -c "uplink_stop guard; return")
is "five ways out: no start, not joined, no address, a clash, the link lost" "$n" 5
n=$(sed -n '/^do_wifi_ap_off() {/,/^}/p' "$F" | grep -c '^  uplink_stop$')
is "Wi-Fi off stops the client" "$n" 1
[ "$fails" -eq 0 ] && echo "radio_scan: all passed" || { echo "radio_scan: $fails failed"; exit 1; }
