#!/bin/sh
# Tests for the pure helpers of radio_hal.sh's car-hotspot scan and join (T15 route B).
#   sh tools/tests/radio_scan_test.sh
set -u
HERE="$(cd "$(dirname "$0")/../.." && pwd)"
F="$HERE/ccpa/rootfs/script/radio_hal.sh"
eval "$(sed -n '/^freq_to_channel() {/,/^}/p; /^channel_note() {/,/^}/p; /^uplink_ifname_ok()/p;
  /^channel_to_freq() {/,/^}/p; /^uplink_channel_ok() {/,/^}/p; /^uplink_freq_list() {/,/^}/p; /^uplink_pick_channel() {/,/^}/p; /^uplink_why_not() {/,/^}/p;
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
is "the client may join only on usable 5 GHz channels" "$(uplink_freq_list)" "5180 5200 5220 5240 5745 5765 5785 5805 5825 "
is "the car's channel 153 is among them" "$(uplink_freq_list | grep -c 5765)" 1
is "its 2.4 GHz side is not" "$(uplink_freq_list | grep -c 2437)" 0
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
# Why a join failed. The home network on 2026-11-16: one name on 5240 and 5805 MHz and on 2.4 GHz.
home="a${TAB}5240${TAB}-57${TAB}[WPA2]${TAB}Home
b${TAB}5805${TAB}-50${TAB}[WPA2]${TAB}Home
c${TAB}2437${TAB}-40${TAB}[WPA2]${TAB}Home"
is "seen on the remembered channel and not joined is a password question, not a move" "$(printf '%s\n' "$home" | uplink_why_not Home 5240 48)" "password 5240"
is "not on the remembered channel but on a usable one is a move" "$(printf '%s\n' "$rows" | uplink_why_not Car 5745 149)" "moved 153"
is "with nothing remembered, seen on a looked-at channel is a password question" "$(printf '%s\n' "$rows" | uplink_why_not Car "5180 5765" '')" "password 5765"
is "seen only on 2.4 GHz is elsewhere" "$(printf '%s\n' "c${TAB}2437${TAB}-40${TAB}[WPA2]${TAB}Car" | uplink_why_not Car 5765 153)" "elsewhere 2437"
is "not seen at all" "$(printf '%s\n' "$rows" | uplink_why_not Nope 5765 153)" unseen
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
for fn in uplink_join uplink_watch; do
  n=$(sed -n "/^$fn() {/,/^}/p" "$F" | grep -w return | grep -vc "uplink_stop guard; return")
  is "every way out of $fn but success stops the client first" "$n" 0
done
n=$(sed -n '/^uplink_join() {/,/^}/p' "$F" | grep -c "uplink_stop guard; return 1")
is "uplink_join's two failures (no start, not joined)" "$n" 2
n=$(sed -n '/^uplink_watch() {/,/^}/p' "$F" | grep -c "uplink_stop guard; return")
is "uplink_watch's three (no address, a clash, the link lost)" "$n" 3
# The join runs before the access point starts (bench 2026-11-16: joining after it hurt CarPlay).
body=$(sed -n '/^do_wifi_ap_on() {/,/^}/p' "$F")
j=$(printf '%s\n' "$body" | grep -n 'uplink_join' | head -1 | cut -d: -f1)
a=$(printf '%s\n' "$body" | grep -n 'radio_ap_up.sh AP' | head -1 | cut -d: -f1)
[ -n "$j" ] && [ -n "$a" ] && [ "$j" -lt "$a" ] && r=yes || r=no
is "the join comes before the access point starts" "$r" yes
# The phone can be handed over before the join is done, and is told the channel hostapd.conf names
# (bench 2026-11-16: it named 149, the access point came up on 48, the phone never arrived).
w=$(printf '%s\n' "$body" | grep -n 'sed -i "s/^channel=.*/channel=$_ap_ch/"' | head -1 | cut -d: -f1)
[ -n "$w" ] && [ -n "$j" ] && [ "$w" -lt "$j" ] && r=yes || r=no
is "the remembered channel is in hostapd.conf before the join starts" "$r" yes
n=$(sed -n '/^do_wifi_ap_off() {/,/^}/p' "$F" | grep -c '^  uplink_stop$')
is "Wi-Fi off stops the client" "$n" 1
# radio_hal.sh runs under set -u, and uplink_stop runs at every Wi-Fi start and stop, the setting on
# or off: an unset name in it took the access point down on the bench (2026-11-16, `$1` unset).
T=$(mktemp -d)
out=$( set -u; LOGP=x; UPLINK_DIR=$T/d; UPLINK_JOINED_AT=$T/j
  eval "$(sed -n '/^uplink_ifname_ok()/p; /^uplink_log()/p; /^uplink_stop() {/,/^}/p' "$F" | sed "s#/tmp/#$T/#g")"
  uplink_stop && uplink_stop guard && echo survived 2>&1 )
is "uplink_stop with nothing running, and no argument, under set -u" "$out" survived
rm -rf "$T"
[ "$fails" -eq 0 ] && echo "radio_scan: all passed" || { echo "radio_scan: $fails failed"; exit 1; }
