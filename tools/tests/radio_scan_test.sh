#!/bin/sh
# Tests for the pure helpers of radio_hal.sh's car-hotspot scan and join (T15 route B).
#   sh tools/tests/radio_scan_test.sh
set -u
HERE="$(cd "$(dirname "$0")/../.." && pwd)"
F="$HERE/ccpa/rootfs/script/radio_hal.sh"
eval "$(sed -n '/^freq_to_channel() {/,/^}/p; /^channel_note() {/,/^}/p; /^uplink_ifname_ok()/p;
  /^channel_to_freq() {/,/^}/p; /^uplink_channel_ok() {/,/^}/p; /^uplink_freq_list() {/,/^}/p; /^uplink_pick_channel() {/,/^}/p; /^uplink_why_not() {/,/^}/p; /^uplink_state_rank() {/,/^}/p; /^uplink_state_note() {/,/^}/p; /^uplink_log_reason() {/,/^}/p;
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
# How far a failed join got (the car refused every join of Play 259, 2026-11-17).
is "stuck scanning never tried" "$(uplink_state_note SCANNING)" "never tried to connect"
is "no state at all never tried" "$(uplink_state_note '')" "never tried to connect"
is "associating is the hotspot not accepting" "$(uplink_state_note ASSOCIATING)" "the hotspot did not accept the connection"
is "authenticating too" "$(uplink_state_note AUTHENTICATING)" "the hotspot did not accept the connection"
is "the 4-way handshake is the password" "$(uplink_state_note 4WAY_HANDSHAKE)" "the password exchange failed"
is "associated is a drop, not a claim about the exchange the sampling may have missed" "$(uplink_state_note ASSOCIATED)" "connected, then dropped"
is "group handshake is after the password" "$(uplink_state_note GROUP_HANDSHAKE)" "failed after the password exchange"
is "the handshake ranks above associating" "$( [ "$(uplink_state_rank 4WAY_HANDSHAKE)" -gt "$(uplink_state_rank ASSOCIATING)" ] && echo yes)" yes
is "disconnected ranks below scanning" "$( [ "$(uplink_state_rank DISCONNECTED)" -lt "$(uplink_state_rank SCANNING)" ] && echo yes)" yes
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
# The client's own log, read for the reason a join failed. The lines are wpa_supplicant 2.11's own
# formats (events.c, wpa.c, wpa_supplicant.c); the name and the address must never come out.
LOGF=$(mktemp)
cat > "$LOGF" <<'L'
wlan: Trying to associate with SSID 'My reason=99 Car'
wlan: Associated with 02:11:22:33:44:55
WPA: RSNXE mismatch between Beacon/ProbeResp and EAPOL-Key msg 3/4
RSNXE in Beacon/ProbeResp - hexdump(len=3): f4 01 20
wlan: CTRL-EVENT-DISCONNECTED bssid=02:11:22:33:44:55 reason=17 locally_generated=1
wlan: WPA: 4-Way Handshake failed - pre-shared key may be incorrect
wlan: CTRL-EVENT-SSID-TEMP-DISABLED id=0 ssid="My reason=99 Car" auth_failures=1 duration=10 reason=WRONG_KEY
wlan: CTRL-EVENT-ASSOC-REJECT bssid=02:11:22:33:44:55 status_code=17
wlan: CTRL-EVENT-DISCONNECTED bssid=02:11:22:33:44:55 reason=15
L
r=$(uplink_log_reason < "$LOGF")
is "the reason line names the RSNXE mismatch, the handshake, the codes (PR #2's failure)" "$r" \
  "RSNXE mismatch at message 3/4; 4-way handshake failed; disabled WRONG_KEY; association rejected, status 17; disconnect reason 17L,15"
case "$r" in *Car*|*02:11*|*99*) r=leaked ;; *) r=clean ;; esac
is "no network name or address in the reason line" "$r" clean
printf 'wlan: WPA: IE in 3/4 msg does not match with IE in Beacon/ProbeResp (src=02:11:22:33:44:55)\n' > "$LOGF"
is "an RSN element mismatch" "$(uplink_log_reason < "$LOGF")" "RSN element mismatch at message 3/4"
: > "$LOGF"
is "an empty log says so" "$(uplink_log_reason < "$LOGF")" "nothing logged"
is "no log at all says so" "$(uplink_log_reason < /dev/null)" "nothing logged"
i=0; : > "$LOGF"; while [ "$i" -lt 12 ]; do echo "wlan: CTRL-EVENT-DISCONNECTED bssid=02:11:22:33:44:55 reason=3" >> "$LOGF"; i=$((i+1)); done
is "a long run of disconnects is cut at eight" "$(uplink_log_reason < "$LOGF")" "disconnect reason 3,3,3,3,3,3,3,3"
rm -f "$LOGF"
# nl80211 first: wext alone failed every join in the car (Play 259, 260) and cannot pass RSNXE.
d=$(sed -n '/^uplink_wpa_start() {/,/^}/p' "$F" | sed -n 's/^  for UPLINK_DRV in //p')
is "the client tries nl80211, then wext" "$d" "nl80211 wext; do"
n=$(grep -c '"$UPLINK_WPA" -B' "$F")
is "every wpa_supplicant start goes through uplink_wpa_start" "$n" 1
# A join that fails must leave nothing running (car, 2026-11-15).
n=$(sed -n '/^uplink_join() {/,/^}/p' "$F" | grep -w return | grep -vc "uplink_stop; return")
is "every way out of uplink_join but success stops the client first" "$n" 0
n=$(sed -n '/^uplink_join() {/,/^}/p' "$F" | grep -c "uplink_stop; return 1")
is "uplink_join's two failures (no start, not joined)" "$n" 2
# The join runs before the access point starts (bench 2026-11-16: joining after it hurt CarPlay).
body=$(sed -n '/^do_wifi_ap_on() {/,/^}/p' "$F")
j=$(printf '%s\n' "$body" | grep -nw 'uplink_join' | head -1 | cut -d: -f1)
a=$(printf '%s\n' "$body" | grep -n 'radio_ap_up.sh AP' | head -1 | cut -d: -f1)
[ -n "$j" ] && [ -n "$a" ] && [ "$j" -lt "$a" ] && r=yes || r=no
is "the join comes before the access point starts" "$r" yes
# The phone can be handed over before the join is done, and is told the channel hostapd.conf names
# (bench 2026-11-16: it named 149, the access point came up on 48, the phone never arrived).
m=$(printf '%s\n' "$body" | grep -n '_ap_ch=$(uplink_remembered)' | head -1 | cut -d: -f1)
w=$(printf '%s\n' "$body" | sed -n "${m:-1},\$p" | grep -n 'sed -i "s/^channel=.*/channel=$_ap_ch/"' | head -1 | cut -d: -f1)
[ -n "$m" ] && [ -n "$w" ] && [ -n "$j" ] && [ $((m + w - 1)) -lt "$j" ] && r=yes || r=no
is "the remembered channel is in hostapd.conf before the join starts" "$r" yes
# The keeper (2026-11-21): a Wi-Fi start keeps a joined client and makes no join of its own; one that
# is not joined is stopped before the access point, as before.
k=$(printf '%s\n' "$body" | grep -n 'if uplink_running && uplink_joined' | head -1 | cut -d: -f1)
s1=$(printf '%s\n' "$body" | grep -n '^ *uplink_stop$' | head -1 | cut -d: -f1)
[ -n "$k" ] && [ -n "$s1" ] && [ "$k" -lt "$s1" ] && [ "$s1" -lt "$j" ] && r=yes || r=no
is "a Wi-Fi start keeps a joined client, and stops one that is not before joining" "$r" yes
n=$(printf '%s\n' "$body" | grep -c '^ *uplink_keeper_start$')
is "a Wi-Fi start with a join configured starts the keeper" "$n" 1
n=$(sed -n '/^do_wifi_ap_off() {/,/^}/p' "$F" | grep -cw 'uplink_stop')
is "Wi-Fi off leaves the client to the keeper" "$n" 0
keep=$(sed -n '/^do_sta_keep() {/,/^}/p' "$F")
n=$(sed -n '/^keeper_join() {/,/^}/p' "$F" | sed -n 2p)
is "the keeper never joins while an access point is up" "$n" "  ap_running && return 1"
n=$(printf '%s\n' "$keep" | grep -w 'keeper_join' | grep -vc 'keeper_locked keeper_join')
is "every keeper join holds the wlan lock" "$n" 0
n=$(printf '%s\n' "$keep" | grep -w 'uplink_stop' | grep -v 'keeper_locked uplink_stop || uplink_stop' | grep -vc 'keeper_locked uplink_stop')
is "the keeper stops the client only under the wlan lock" "$n" 0
n=$(printf '%s\n' "$keep" | grep -c 'keeper_locked uplink_stop &&')
is "the keeper's three ways to drop a joined client (settings changed, no address, a clash)" "$n" 3
n=$(printf '%s\n' "$keep" | grep -A1 '^ *if ap_running; then$' | grep -c 'keeper_locked keeper_stop_unjoined')
is "under an access point a client that is not joined is stopped" "$n" 1
n=$(sed -n '/^lock_take() {/,/^}/p' "$F" | grep -c '"${2:-60}"')
is "lock_take takes a number of tries (the keeper tries once)" "$n" 2
# Who starts the keeper. An update never writes ocbm_boot.sh, so the car's copy predates the keeper and
# a start there never ran in the car; the supervisor, which an update does ship, starts it at boot
# (found at 264's publish, 2026-11-21).
S="$HERE/tools/session_supervisor.sh"
k=$(grep -n '^setsid sh /script/radio_hal.sh sta_keep ' "$S" | head -1 | cut -d: -f1)
w=$(grep -n '^while :; do' "$S" | head -1 | cut -d: -f1)
[ -n "$k" ] && [ -n "$w" ] && [ "$k" -lt "$w" ] && r=yes || r=no
is "the supervisor starts the keeper at boot, before it waits for the app" "$r" yes
is "the boot path, which no update writes, does not start the keeper" "$(grep -c 'sta_keep' "$HERE/ccpa/rootfs/script/ocbm_boot.sh")" 0
is "the supervisor ships in the update" "$(grep -c '^ *"$REPO/tools/session_supervisor.sh|/script/session_supervisor.sh|' "$HERE/host/gm_ccpa/tools/stage_adapter_fw.sh")" 1
T=$(mktemp -d)
out=$( UPLINK_CONF=$T/conf UPLINK_KEEPER=$T/keeper UPLINK_WPA=/bin/true UPLINK_CLI=/bin/true
  caps() { return 0; }; uplink_log() { echo "logged: $*"; }
  eval "$(sed -n '/^uplink_key() {/,/^}/p; /^uplink_wanted() {/,/^}/p; /^do_sta_keep() {/,/^}/p' "$F")"
  printf 'enabled=0\nssid=x\npsk=y\n' > "$T/conf"
  do_sta_keep; echo "rc=$? keeper=$([ -e "$T/keeper" ] && echo yes || echo no)" 2>&1 )
is "with the join off the keeper returns at once, logs nothing and holds nothing" "$out" "rc=0 keeper=no"
rm -rf "$T"
# radio_hal.sh runs under set -u, and uplink_stop runs at every Wi-Fi start and stop, the setting on
# or off: an unset name in it took the access point down on the bench (2026-11-16, `$1` unset).
T=$(mktemp -d)
out=$( set -u; LOGP=x; UPLINK_DIR=$T/d; UPLINK_JOINED_AT=$T/j; UPLINK_WPA_LOG=$T/l; UPLINK_CH_NOW=$T/c; UPLINK_CONF_SEEN=$T/s
  eval "$(sed -n '/^uplink_ifname_ok()/p; /^uplink_log()/p; /^uplink_stop() {/,/^}/p' "$F" | sed "s#/tmp/#$T/#g")"
  uplink_stop && uplink_stop && echo survived 2>&1 )
is "uplink_stop with nothing running, and no argument, under set -u" "$out" survived
rm -rf "$T"
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
[ "$fails" -eq 0 ] && echo "radio_scan: all passed" || { echo "radio_scan: $fails failed"; exit 1; }
