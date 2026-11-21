#!/bin/sh
# Tests for radio_ap_up.sh's DHCP server and hostapd helpers, and radio_hal.sh's use of them: the
# last AP's DHCP server stopped and seen gone, this AP's own server and hostapd judged by their /proc
# stat, hostapd's AP-ENABLED read after this start's own line. Ported in spirit from enigy/ocbm's
# tools/radio_ap_up_test.sh (Unlicense; NOTICE.md). Runs under the host's shell and the adapter's
# BusyBox alike.
#   sh tools/tests/radio_ap_test.sh
set -u
HERE="$(cd "$(dirname "$0")/../.." && pwd)"
AP="$HERE/ccpa/rootfs/script/radio_ap_up.sh"
HAL="$HERE/ccpa/rootfs/script/radio_hal.sh"
T=$(mktemp -d)
STANDINS=""
cleanup() { for _p in $STANDINS; do kill -9 "$_p" 2>/dev/null; done; rm -rf "$T"; }
trap cleanup EXIT
fails=0
is() { if [ "$2" = "$3" ]; then echo "ok   $1"; else echo "FAIL $1: got '$2' want '$3'"; fails=$((fails+1)); fi; }
yes_no() { if eval "$1"; then echo yes; else echo no; fi; }

# Every process, pid first, whole command lines: the host's ps lists only its terminal's processes
# without -e, and BusyBox's cuts each line at 80 columns without -w (the stand-ins' paths are long;
# the AP's real server line, about 68, fits).
if command ps -eo pid=,args= >/dev/null 2>&1; then ps() { command ps -eo pid=,args=; }
elif command ps -w >/dev/null 2>&1; then ps() { command ps -w; }; fi
L="[radio_ap_up]"
# The AP's DHCP servers in this test are stand-ins under $T, never a real one.
AP_DHCP_MATCH="udhcpd .*$T/etc/udhcpd.conf"
AP_PROC="$T/proc"
AP_BOXLOG="$T/box.log"
eval "$(sed -n '/^ap_dhcp_pids()/p; /^ap_pids_live()/p; /^ap_dhcp_stop() {/,/^}/p; /^ap_dhcp_serving() {/,/^}/p;
  /^hapd_exited() {/,/^}/p; /^ap_enabled_seen() {/,/^}/p; /^dhcp_conf_key() {/,/^}/p' "$AP")"

# A stand-in DHCP server, detached so it is reaped as it exits. $1: slow (exits about 1 s after
# SIGTERM, as one writing its lease file to slow flash) or deaf (only SIGKILL ends it); $2 its config.
mkdir -p "$T/bin" "$T/etc" "$T/tmp" "$T/proc"
cat > "$T/bin/udhcpd" <<'EOF'
case "$1" in slow) trap 'sleep 1; exit 0' TERM ;; deaf) trap '' TERM ;; esac
while :; do sleep 0.1; done
EOF
standin() { sh -c 'sh "$1" "$2" "$3" </dev/null >/dev/null 2>&1 & echo $!' sh "$T/bin/udhcpd" "$1" "$2"; }
gone() { ! kill -0 "$1" 2>/dev/null; }

is "the default pattern finds the AP's server" \
  "$(yes_no 'echo "  321 root 1408 S  busybox udhcpd -f -a 250 /etc/udhcpd.conf" | grep -q "udhcpd .*etc/udhcpd.conf"')" yes
is "the default pattern never finds the NCM one" \
  "$(yes_no 'echo "  322 root 1408 S  busybox udhcpd -f /tmp/udhcpd_ncm.conf" | grep -q "udhcpd .*etc/udhcpd.conf"')" no

# ---- ap_dhcp_stop -------------------------------------------------------------------------------
slow=$(standin slow "$T/etc/udhcpd.conf"); ncm=$(standin slow "$T/tmp/udhcpd_ncm.conf")
STANDINS="$STANDINS $slow $ncm"; sleep 0.3
out=$(ap_dhcp_stop 15)
is "one slow over its lease file is waited for and gone" "$(yes_no 'gone "$slow"')" yes
is "  without a SIGKILL" "$(yes_no 'echo "$out" | grep -q SIGKILL')" no
is "the NCM server (its own config) is never touched" "$(yes_no 'gone "$ncm"')" no
deaf=$(standin deaf "$T/etc/udhcpd.conf"); STANDINS="$STANDINS $deaf"; sleep 0.3
out=$(ap_dhcp_stop 5)
is "one deaf to SIGTERM is SIGKILLed after the bound, and gone" "$(yes_no 'gone "$deaf"')" yes
is "  and the log says so" "$(yes_no 'echo "$out" | grep -q "still there after SIGTERM"')" yes
is "with none listed it returns at once" "$(yes_no 'ap_dhcp_stop 15')" yes
# The mode radio_hal.sh's teardown runs: before anything in the script touches the radio.
slow=$(standin slow "$T/etc/udhcpd.conf"); STANDINS="$STANDINS $slow"; sleep 0.3
# Sourced under the same ps (above), which a child shell would not have; its `exit` ends that shell.
AP_DHCP_MATCH="$AP_DHCP_MATCH" sh -c 'if command ps -eo pid=,args= >/dev/null 2>&1; then ps() { command ps -eo pid=,args=; }
  elif command ps -w >/dev/null 2>&1; then ps() { command ps -w; }; fi
  f=$1; set -- dhcp_stop; . "$f"' sh "$AP" >/dev/null 2>&1; rc=$?
is "radio_ap_up.sh dhcp_stop stops it and exits 0" "$(yes_no 'gone "$slow"')/$rc" yes/0
is "  and the NCM server is still there" "$(yes_no 'gone "$ncm"')" no
kill -9 "$ncm" 2>/dev/null

# ---- ap_dhcp_serving and hapd_exited, against a scratch /proc -----------------------------------
mkdir -p "$T/proc/4242" "$T/proc/4343" "$T/proc/777"
echo "4242 (busybox) S 1 4242" > "$T/proc/4242/stat"
is "this start's server, running, serves" "$(_dhcpd=4242 yes_no ap_dhcp_serving)" yes
echo "4242 (busybox) Z 1 4242" > "$T/proc/4242/stat"
is "this start's server, a zombie, does not" "$(_dhcpd=4242 yes_no ap_dhcp_serving)" no
is "this start's server, gone, does not" "$(_dhcpd=4141 yes_no ap_dhcp_serving)" no
alive=$(standin slow "$T/etc/udhcpd.conf"); STANDINS="$STANDINS $alive"; sleep 0.3
echo "777 (setsid) S 1 777" > "$T/proc/777/stat"
is "a pid that is setsid's asks by config path (one there)" "$(_dhcpd=777 yes_no ap_dhcp_serving)" yes
is "none launched here: any by config path" "$(_dhcpd= yes_no ap_dhcp_serving)" yes
for _p in $STANDINS; do kill -9 "$_p" 2>/dev/null; done; sleep 0.3
is "  and with none there, it does not serve" "$(_dhcpd= yes_no ap_dhcp_serving)" no
echo "4343 (hostapd) S 1 4343" > "$T/proc/4343/stat"
is "a running hostapd has not exited" "$(_hapd=4343 yes_no hapd_exited)" no
echo "4343 (hostapd) Z 1 4343" > "$T/proc/4343/stat"
is "a zombie hostapd has exited" "$(_hapd=4343 yes_no hapd_exited)" yes
is "a reaped one is not called exited (ps answers that)" "$(_hapd=4141 yes_no hapd_exited)" no

# ---- ap_enabled_seen ------------------------------------------------------------------------------
_ae_mark="[radio] hostapd starting on wlan0 ssid=ccpa-0000"
printf '%s\n' "wlan0: AP-ENABLED" "$_ae_mark" "wlan0: interface state UNINITIALIZED->COUNTRY_UPDATE" > "$AP_BOXLOG"
is "an AP-ENABLED before this start's line is the last AP's" "$(yes_no ap_enabled_seen)" no
echo "wlan0: AP-ENABLED" >> "$AP_BOXLOG"
is "one after it is this hostapd's" "$(yes_no ap_enabled_seen)" yes
echo "wlan0: AP-ENABLED" > "$AP_BOXLOG"
is "the log emptied at its cap after the launch: every one left is this hostapd's" "$(yes_no ap_enabled_seen)" yes
is "no line of its own (the write failed): never seen" "$(_ae_mark= yes_no ap_enabled_seen)" no

# ---- udhcpd.conf keys ---------------------------------------------------------------------------
printf '%s\n' "start		192.168.43.100" "lease_file	/tmp/old.leases" "option	lease	864000" > "$T/etc/udhcpd.conf"
for _r in 1 2; do
  dhcp_conf_key "$T/etc/udhcpd.conf" lease_file /var/lib/udhcpd.leases
  dhcp_conf_key "$T/etc/udhcpd.conf" pidfile /var/run/udhcpd.pid
  dhcp_conf_key "$T/etc/udhcpd.conf" auto_time 60
done
is "keys set twice give one line each" \
  "$(grep -c -E '^(lease_file|pidfile|auto_time)' "$T/etc/udhcpd.conf")" 3
is "  the lease file replaced in place" "$(sed -n 's/^lease_file[[:space:]]*//p' "$T/etc/udhcpd.conf")" /var/lib/udhcpd.leases
is "  the others appended" "$(tail -2 "$T/etc/udhcpd.conf" | tr '\t\n' ' ')" "pidfile /var/run/udhcpd.pid auto_time 60 "
is "  the rest untouched" "$(grep -c -E '^(start|option)' "$T/etc/udhcpd.conf")" 2

# ---- the start and the teardown use them ----------------------------------------------------------
is "hostapd runs in the foreground under setsid, its pid kept" \
  "$(yes_no 'grep -q "^setsid hostapd /etc/hostapd.conf </dev/null >>\"\$AP_BOXLOG\" 2>&1 &$" "$AP" && grep -q "^_hapd=\$!$" "$AP"')" yes
is "  and never with -B" "$(grep -c 'hostapd.*-B' "$AP")" 0
is "BusyBox's udhcpd, its 250 ms ARP probe, its pid kept" \
  "$(yes_no 'grep -q "^setsid busybox udhcpd -f -a 250 /etc/udhcpd.conf " "$AP" && grep -q "^_dhcpd=\$!$" "$AP"')" yes
s=$(grep -n '^ap_dhcp_stop 5$' "$AP" | cut -d: -f1); u=$(grep -n '^setsid busybox udhcpd' "$AP" | cut -d: -f1)
is "the last AP's server is stopped before this AP's starts" "$(yes_no '[ -n "$s" ] && [ -n "$u" ] && [ "$s" -lt "$u" ]')" yes
d=$(grep -n '= dhcp_stop ]' "$AP" | cut -d: -f1); i=$(grep -n 'does not exist - the driver must be up first' "$AP" | cut -d: -f1)
is "the dhcp_stop mode comes before anything touches the radio" "$(yes_no '[ -n "$d" ] && [ -n "$i" ] && [ "$d" -lt "$i" ]')" yes
off=$(sed -n '/^do_wifi_ap_off() {/,/^}/p' "$HAL")
is "radio_hal.sh's Wi-Fi teardown waits through radio_ap_up.sh dhcp_stop" \
  "$(printf '%s\n' "$off" | grep -c 'sh /script/radio_ap_up.sh dhcp_stop')" 1
is "  and no longer kills the pidfile's pid blind" "$(printf '%s\n' "$off" | grep -c 'udhcpd.pid')" 0

[ "$fails" -eq 0 ] && echo "radio_ap: all passed" || { echo "radio_ap: $fails failed"; exit 1; }
