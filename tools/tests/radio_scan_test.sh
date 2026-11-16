#!/bin/sh
# Tests for the pure helpers of radio_hal.sh's car-hotspot scan (T15 diagnostic).
#   sh tools/tests/radio_scan_test.sh
set -u
HERE="$(cd "$(dirname "$0")/../.." && pwd)"
F="$HERE/ccpa/rootfs/script/radio_hal.sh"
eval "$(sed -n '/^freq_to_channel() {/,/^}/p; /^channel_note() {/,/^}/p; /^uplink_ifname_ok()/p' "$F")"
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
[ "$fails" -eq 0 ] && echo "radio_scan: all passed" || { echo "radio_scan: $fails failed"; exit 1; }
