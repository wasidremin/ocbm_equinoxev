#!/bin/sh
########################
# radio_ap_up.sh - the OWNED SoftAP layer. Chipset-neutral by construction: it loads no driver,
# names no module, reads no firmware tree. It configures hostapd + udhcpd on whatever interface
# the seam hands it, and returns only once the AP is actually up.
#
# WHY THIS FILE EXISTS UNDER THIS NAME. Every CCPA already ships a VENDOR file at
# /script/start_bluetooth_wifi.sh, so testing that path for existence proves the file is there,
# not that it is ours - and running the vendor copy on a stripped box is destructive:
#
#   * it reads config through `riddleBoxCfg`, which the OCBM base install removes, and the calls
#     are NOT existence-guarded. With it gone `passwd` comes back empty and the script seds an
#     EMPTY wpa_passphrase into /etc/hostapd.conf. That is persistent, on flash, and hostapd
#     refuses to start with a zero-length WPA passphrase - so the AP is broken for every future
#     bring-up, not just this one.
#   * its WLANIP default is 192.168.50.2 - the box's OWN NCM address - and it then repoints the
#     DHCP pool at 192.168.50.100-200, which is the pool the NCM link is handing out. On a unit
#     with no UART that is the control channel you are standing on.
#   * its teardown (`close_bluetooth_wifi.sh`) does `EnsureProcessKill udhcpd`, a killall that
#     takes the NCM DHCP server down with it.
#
# An unambiguous name is the guard. radio_hal.sh calls THIS path and explicitly refuses to fall
# back to the vendor one. Do not "simplify" that by pointing the seam at start_bluetooth_wifi.sh.
#
# WHAT IS CHIPSET-NEUTRAL HERE. The interface name arrives in RADIO_WLAN_IF (radio_hal.sh
# enumerates it - it is an insmod parameter, not a constant: Realtek if2name=sta0, Broadcom
# iface_name=sta, and on Broadcom wlan0 does not exist until something creates it on top of
# sta0). The device-unique SSID is derived from that interface's own MAC in sysfs rather than
# from the vendor's `set_wifi_mac` output, so no vendor helper is required.
#
# USAGE  RADIO_WLAN_IF=<iface> sh radio_ap_up.sh [AP]
# EXIT   0 AP converged   1 failed to converge
########################
set -u
export PATH=/usr/sbin:/usr/bin:/sbin:/bin:/tmp/bin:$PATH
L="[radio_ap_up]"

# ---- the AP's DHCP server and hostapd's own word (tools/tests/radio_ap_test.sh) ----------------
# PORTED from enigy/ocbm (github.com/enigy/ocbm, Unlicense; 2.0.0 a009f90, 2.5.0 c4679c0, 2.6.5
# d590834 and 39f7a13; NOTICE.md), adapted to this script, which always starts hostapd fresh.
# AP_DHCP_MATCH, AP_PROC and AP_BOXLOG are test hooks.
#
# The AP's DHCP servers are found by CONFIG PATH, never by name: the NCM server,
# `busybox udhcpd -f /tmp/udhcpd_ncm.conf`, is never one of them.
AP_DHCP_MATCH=${AP_DHCP_MATCH:-udhcpd .*etc/udhcpd.conf}
AP_PROC=${AP_PROC:-/proc}
AP_BOXLOG=${AP_BOXLOG:-/tmp/box.log}
ap_dhcp_pids() { ps 2>/dev/null | grep -v grep | grep "$AP_DHCP_MATCH" | awk '{print $1}'; }
ap_pids_live() { for _apl in "$@"; do kill -0 "$_apl" 2>/dev/null && return 0; done; return 1; }
# Stop every AP DHCP server and see it GONE: SIGTERM, up to $1 x 0.2 s for it to go, then SIGKILL
# and up to 1 s more. 1 when one survived even that (a kernel wait): a server started now may not
# bind. BusyBox's udhcpd writes its lease file (on the jffs2 root) as it exits on SIGTERM, and with
# flash slow the last AP's server was still listed at the next AP start, which took it for its own
# and started none; once it had gone the phone was on the Wi-Fi with no address (enigy/ocbm's bench,
# 2026-11-16). This fork's teardown waited for hostapd only, the same exposure. Its pidfile is not
# killed blind: a stale pidfile can name any process.
ap_dhcp_stop() {
  _ads=$(ap_dhcp_pids)
  [ -n "$_ads" ] || return 0
  for _adp in $_ads; do kill "$_adp" 2>/dev/null; done
  _adn=0; while [ "$_adn" -lt "${1:-15}" ] && ap_pids_live $_ads; do _adn=$((_adn + 1)); sleep 0.2; done
  ap_pids_live $_ads || return 0
  echo "$L the last AP's DHCP server is still there after SIGTERM (its lease file on flash?) - SIGKILL"
  for _adp in $_ads; do kill -9 "$_adp" 2>/dev/null; done
  _adn=0; while [ "$_adn" -lt 10 ] && ap_pids_live $_ads; do _adn=$((_adn + 1)); sleep 0.1; done
  ap_pids_live $_ads || return 0
  echo "$L the last AP's DHCP server survived SIGKILL (a kernel wait?): this AP's may not bind"
  return 1
}
# This AP's DHCP server serving: the one this start launched ($_dhcpd), alive (its stat not a zombie
# this shell has not reaped); or, with none launched here, any found by config path. $! is the
# server's own pid: setsid does not fork for a job that leads no process group, which a
# non-interactive shell's never do. Were it setsid's, the config path answers instead.
ap_dhcp_serving() {
  if [ -n "${_dhcpd:-}" ]; then
    _adx=""; _adx_name=""
    read -r _adx_pid _adx_name _adx _adx_rest 2>/dev/null <"$AP_PROC/$_dhcpd/stat"
    [ "$_adx_name" = "(setsid)" ] && { [ -n "$(ap_dhcp_pids)" ]; return; }
    [ -n "$_adx" ] && [ "$_adx" != Z ]
  else
    [ -n "$(ap_dhcp_pids)" ]
  fi
}
# The hostapd launched here ($_hapd) has exited: a zombie this shell has not reaped, which ps still
# lists by name. Anything else, its being there included, is not "exited".
hapd_exited() {
  _hx_name=""; _hx_state=""
  read -r _hx_pid _hx_name _hx_state _hx_rest 2>/dev/null <"$AP_PROC/${_hapd:-0}/stat"
  [ "$_hx_name" = "(hostapd)" ] && [ "$_hx_state" = Z ]
}
# hostapd's AP-ENABLED after this start's own line ($_ae_mark) in the box log, which hostapd writes
# itself. By that line, not by a count: ocbmd empties the box log at its cap in place, so with the
# line gone the log was emptied after the launch, and every AP-ENABLED left in it is this hostapd's.
ap_enabled_seen() {
  [ -n "${_ae_mark:-}" ] || return 1
  _ae_out=$(grep -F -e "$_ae_mark" -e "AP-ENABLED" "$AP_BOXLOG" 2>/dev/null)
  case "$_ae_out" in *"$_ae_mark"*) _ae_out=${_ae_out##*"$_ae_mark"} ;; esac
  case "$_ae_out" in *AP-ENABLED*) return 0 ;; esac
  return 1
}
# Set udhcpd.conf key $2 to $3 in file $1: the line replaced where there is one, else appended.
dhcp_conf_key() {
  if grep -q "^[[:space:]]*$2[[:space:]]" "$1" 2>/dev/null; then
    sed -i "s|^[[:space:]]*$2[[:space:]].*|$2	$3|" "$1"
  else
    printf '%s\t%s\n' "$2" "$3" >> "$1"
  fi
}

# `sh radio_ap_up.sh dhcp_stop`: radio_hal.sh's Wi-Fi teardown, through the one copy above. Up to 3 s
# for the lease file, before anything here touches the radio.
if [ "${1:-}" = dhcp_stop ]; then ap_dhcp_stop 15; exit $?; fi

IF=${RADIO_WLAN_IF:-wlan0}
[ -e "/sys/class/net/$IF" ] || { echo "$L interface $IF does not exist - the driver must be up first"; exit 1; }
# AND IT MUST ACTUALLY BE A WIRELESS INTERFACE. Existence alone is not enough: this script
# unconditionally reconfigures whatever it is handed, so `RADIO_WLAN_IF=ncm0` would sail through
# the check above and then `ifconfig ncm0 192.168.43.1` - re-addressing the NCM management link
# out from under the only way into a unit with no UART, before hostapd ever gets a chance to
# fail. The seam always passes an enumerated wireless interface, but "the caller is careful" is
# exactly the guarantee this file refuses to accept from anyone else, so it does not accept it
# for itself either. A cfg80211/wext netdev is identifiable without knowing the driver.
if [ ! -e "/sys/class/net/$IF/wireless" ] && [ ! -e "/sys/class/net/$IF/phy80211" ]; then
  echo "$L $IF is not a wireless interface - refusing to reconfigure it"; exit 1
fi

# Single-flight. NOT the original's `[ -f lock ] && exit 0`: a crash between touch and rm left a
# stale file that suppressed every later bring-up until reboot. mkdir is atomic, and a lock older
# than 120s is treated as abandoned.
# Same discipline as radio_hal.sh's lock, and for the same reasons. Reclaim on the holder being
# GONE rather than on age; check the reclaim mkdir actually succeeded, because two contenders can
# both observe one stale lock and the loser would otherwise proceed unlocked and then delete the
# winner's lock on its way out; and make the signal trap EXIT, because in ash a trapped signal
# runs the handler and then RESUMES - releasing the lock while the bring-up carries on is worse
# than never having taken it.
LOCK=/tmp/.radio_ap_lock
if ! mkdir "$LOCK" 2>/dev/null; then
  _holder=$(cat "$LOCK/pid" 2>/dev/null)
  if { [ -n "$_holder" ] && [ ! -d "/proc/$_holder" ]; } \
     || { [ -z "$_holder" ] && [ -n "$(find "$LOCK" -maxdepth 0 -mmin +20 2>/dev/null)" ]; }; then
    echo "$L lock held by ${_holder:-no live process} - reclaiming"
    rm -rf "$LOCK"
    mkdir "$LOCK" 2>/dev/null || { echo "$L lost the reclaim race - refusing to stack"; exit 1; }
  else
    echo "$L another AP bring-up is in flight (pid ${_holder:-unknown}) - refusing to stack"; exit 1
  fi
fi
echo $$ > "$LOCK/pid" 2>/dev/null
trap 'rm -rf "$LOCK"' EXIT
trap 'rm -rf "$LOCK"; trap - EXIT; exit 143' INT TERM HUP

[ -f /etc/hostapd.conf ] || { echo "$L /etc/hostapd.conf missing"; exit 1; }

# ---- device-unique name ---------------------------------------------------------------------
# ccpa-<last 4 hex of the AP interface MAC>, matching the BT name so one box presents one
# identity on both radios. Falls back to the serial when the MAC is unreadable. Read from sysfs,
# not from `set_wifi_mac`, so this works on a unit where that vendor helper was stripped.
#
# THIS OVERWRITES ssid= IN /etc/hostapd.conf, AND THAT IS ONLY SAFE BECAUSE OF WHO CALLS US.
# In the BT-only bridge role (wifi_ap:false — the GM head-unit deployment, where the adapter is
# just the Bluetooth radio and the MFi coprocessor) that same file holds the VEHICLE's ssid,
# written by the supervisor's apply_host_wifi_creds(), and it is what the box hands the phone in
# the iAP2 0x5703 handoff. Clobbering it there would point the phone at an AP that is never
# raised — a documented, session-killing failure that also poisons the next attempt.
# We are safe only because session_supervisor skips wifi_ap_on entirely when BOX_WIFI_AP=0.
# So: do NOT make radio_hal call this verb unconditionally, and do NOT "fix" the supervisor's
# wifi_ap:false branch to fall through to here.
# The seam resolves the identity once and passes it in, so the SSID and the Bluetooth name come
# from ONE decision. The local derivation below is only for a direct/bench invocation; it reads
# the same persisted /etc/carplay_ident first, so the two paths cannot diverge.
NAME="${RADIO_BOX_NAME:-}"
if [ -z "$NAME" ] && [ -s /etc/carplay_ident ]; then NAME=$(head -1 /etc/carplay_ident); fi
if [ -z "$NAME" ]; then
  SUF=$(cat "/sys/class/net/$IF/address" 2>/dev/null | tr -d ':' | tr 'A-F' 'a-f' | sed 's/.*\(....\)$/\1/')
  case "$SUF" in ''|*[!0-9a-f]*) SUF="" ;; esac
  [ -n "$SUF" ] || SUF=$(cat /etc/serial_number 2>/dev/null | tr -cd '0-9a-fA-F' | tr 'A-F' 'a-f' | sed 's/.*\(....\)$/\1/')
  if [ -n "$SUF" ]; then NAME="ccpa-$SUF"; echo "$NAME" > /etc/carplay_ident 2>/dev/null
  else NAME="ccpa-0000"; fi
fi
SUF=${NAME#ccpa-}
sed -i "s/^ssid=.*/ssid=$NAME/" /etc/hostapd.conf
echo "$NAME" > /etc/wifi_name

# ---- passphrase: set only if MISSING, never blanked ------------------------------------------
# The vendor bug this guards against is unconditional rewriting from a config oracle that no
# longer exists. We only ever fill in an absent or too-short key, and never overwrite a good one.
PSK=$(sed -n 's/^wpa_passphrase=//p' /etc/hostapd.conf | head -1)
if [ "${#PSK}" -lt 8 ]; then
  PSK=$(cat /etc/wifi_password 2>/dev/null | tr -cd '[:print:]' | head -c 63)
  [ "${#PSK}" -lt 8 ] && PSK="carplay$SUF"
  if grep -q '^wpa_passphrase=' /etc/hostapd.conf; then
    sed -i "s/^wpa_passphrase=.*/wpa_passphrase=$PSK/" /etc/hostapd.conf
  else
    echo "wpa_passphrase=$PSK" >> /etc/hostapd.conf
  fi
  echo "$L wpa_passphrase was absent/too short - set a device-derived one"
fi

# ---- channel + band ---------------------------------------------------------------------------
ch=$(sed -n 's/^channel=//p' /etc/hostapd.conf | head -1)
[ -e /etc/wifi_use_24G ] && ch=6
# The client link's channel (radio_hal.sh, T15) wins over both: one radio serves the two networks.
case "${RADIO_AP_CHANNEL:-}" in ''|*[!0-9]*) ;; *) ch=$RADIO_AP_CHANNEL; echo "$L channel $ch, the client link's" ;; esac
case "$ch" in ''|*[!0-9]*) ch=36 ;; esac
# hostapd of this vintage has no ACS and refuses to start with no channel, so it is always pinned.
if [ "$ch" -ge 1 ] && [ "$ch" -le 14 ]; then
  grep -q "^hw_mode=a" /etc/hostapd.conf && sed -i "s/^hw_mode=a/hw_mode=g/" /etc/hostapd.conf
else
  grep -q "^hw_mode=g" /etc/hostapd.conf && sed -i "s/^hw_mode=g/hw_mode=a/" /etc/hostapd.conf
fi
sed -i "s/^channel=.*/channel=${ch}/" /etc/hostapd.conf
# A 40 MHz pair goes up from some channels and down from others; the file carried HT40+ for 149,
# which hostapd refuses on, say, 48 ("Unable to setup interface", measured 2026-11-15). Follow
# the channel actually used; on a channel with no pair, 20 MHz.
ht40=""
if [ "$ch" -ge 36 ] && [ "$ch" -le 144 ]; then
  [ $(( (ch - 36) / 4 % 2 )) = 0 ] && ht40="HT40+" || ht40="HT40-"
elif [ "$ch" -ge 149 ] && [ "$ch" -le 161 ]; then
  [ $(( (ch - 149) / 4 % 2 )) = 0 ] && ht40="HT40+" || ht40="HT40-"
elif [ "$ch" -ge 1 ] && [ "$ch" -le 7 ]; then ht40="HT40+"
elif [ "$ch" -ge 8 ] && [ "$ch" -le 13 ]; then ht40="HT40-"
fi
if grep -q '^ht_capab=' /etc/hostapd.conf; then
  if [ -n "$ht40" ]; then sed -i "s/\[HT40[+-]\]/[$ht40]/" /etc/hostapd.conf
  else sed -i "s/\[HT40[+-]\]//" /etc/hostapd.conf; fi
fi
# The AP must serve the interface we were handed, whatever it is called.
if grep -q '^interface=' /etc/hostapd.conf; then
  sed -i "s/^interface=.*/interface=$IF/" /etc/hostapd.conf
else
  sed -i "1i interface=$IF" /etc/hostapd.conf
fi

# ---- AP address -------------------------------------------------------------------------------
# HARD RULE: never 192.168.50.x. That is the NCM management subnet; colliding with it takes out
# the only way into a unit that has no UART. An override that lands there is refused, not obeyed.
# CANONICALISE BEFORE COMPARING. The first version tested `case $_w in 192.168.50.*) refuse`
# against an accept pattern of `[0-9]*.[0-9]*.[0-9]*.[0-9]*`. In shell globbing `[0-9]*` is "one
# digit followed by ANYTHING", so that accept pattern admits almost anything - and, worse, the
# refusal is a literal string match while the kernel parses the address numerically. inet_aton()
# reads a leading-zero octet as OCTAL and an 0x octet as HEX, so `192.168.062.1` and
# `192.168.0x32.1` both sail past the refusal and both resolve to 192.168.50.1 - the NCM
# management subnet, on a unit that may have no other way in. Comparing the text was never going
# to be sound; parse it into canonical octets first and compare THAT.
_ip_canon() {  # echoes the canonical dotted quad, or nothing if it is not a plain IPv4 literal
  case "$1" in *.*.*.*) ;; *) return 1 ;; esac
  IFS=. read -r _a _b _c _d <<EOF
$1
EOF
  for _o in "$_a" "$_b" "$_c" "$_d"; do
    case "$_o" in
      ''|*[!0-9]*) return 1 ;;          # empty, hex, octal-with-x, or trailing junk
      0|[1-9]|[1-9][0-9]|[1-9][0-9][0-9]) ;;   # no leading zeros => no octal reinterpretation
      *) return 1 ;;
    esac
    [ "$_o" -le 255 ] 2>/dev/null || return 1
  done
  echo "$_a.$_b.$_c.$_d"
}

WLANIP=192.168.43.1
if [ -e /etc/wifi_ip ]; then
  _w=$(_ip_canon "$(cat /etc/wifi_ip 2>/dev/null)")
  if [ -z "$_w" ]; then
    echo "$L /etc/wifi_ip is not a plain IPv4 address - ignoring it"
  else
    case "$_w" in
      192.168.50.*) echo "$L /etc/wifi_ip resolves to $_w - that is NCM's subnet; ignoring it" ;;
      *) WLANIP=$_w ;;
    esac
  fi
fi
base=${WLANIP%.*}
sed -i "s/192\.168\.[0-9]*\.100/${base}.100/; s/192\.168\.[0-9]*\.200/${base}.200/" /etc/udhcpd.conf 2>/dev/null
# Point udhcpd at the interface we are actually serving.
grep -q '^interface' /etc/udhcpd.conf 2>/dev/null && sed -i "s/^interface.*/interface	$IF/" /etc/udhcpd.conf
# THE AP'S DHCP SERVER IS BUSYBOX'S udhcpd (1.32.1, the one serving NCM), NOT THE VENDOR'S v0.9.9-pre
# (enigy/ocbm 2.0.0, a009f90). An iPhone rejoining a network it knows keeps its old address and asks
# for it again (INIT-REBOOT). The vendor's server stays silent on such a request from a client it has
# no lease for (upstream udhcp: "else remain silent"), so the phone ran CarPlay on its old address for
# about 5 s, then asked afresh, and the session died when it was handed another. The lease file, kept
# since 2026-11-07, covers AP restarts and power cuts, but not a phone with no lease yet. BusyBox's
# udhcpd NAKs that request, and the phone takes a fresh address within a second, before CarPlay
# starts. The vendor's also refused line 76 of the stock file (option 0x2b in hex) at every start.
#
# Same file, pool, lease time and interface. BusyBox writes leases on auto_time and as it exits, where
# the vendor's wrote them at every ACK, and a drive ends in a power cut, so auto_time is 60 s. Its
# lease file has another format: a vendor-format file reads as a timestamp far outside its window, is
# ignored, and is rewritten in BusyBox's (the NAK covers that one start).
dhcp_conf_key /etc/udhcpd.conf lease_file /var/lib/udhcpd.leases
dhcp_conf_key /etc/udhcpd.conf pidfile /var/run/udhcpd.pid
dhcp_conf_key /etc/udhcpd.conf auto_time 60

echo 1 > "/proc/sys/net/ipv6/conf/$IF/disable_ipv6" 2>/dev/null
ifconfig "$IF" "$WLANIP" netmask 255.255.255.0 mtu 1500 up || {
  echo "$L could not configure $IF"; exit 1; }

# ---- bring up ---------------------------------------------------------------------------------
test -e /tmp/bin/hostapd || cp /usr/sbin/hostapd /tmp/bin/hostapd 2>/dev/null
# hostapd reads the conf once. A process left over from an earlier creds write keeps beaconing
# that SSID while 0x5703 reads the file this script just rewrote, so a live hostapd is restarted
# rather than trusted.
if ps | grep -v grep | grep -qw hostapd; then
  killall hostapd 2>/dev/null
  _n=0
  while ps | grep -v grep | grep -qw hostapd && [ "$_n" -lt 25 ]; do _n=$((_n+1)); sleep 0.1; done
fi
# The connect timeline's ap_start and ap_up (crates/box-common/src/timeline.rs): `t <key> <uptime ms>`
# appended to btd's record. One append, no fork; a failed one is ignored. From enigy/ocbm (801074f).
tl_stamp() {
  _tls_up=""
  read -r _tls_up _tls_idle 2>/dev/null </proc/uptime
  case "$_tls_up" in *.*) echo "t $1 ${_tls_up%.*}${_tls_up#*.}0" 2>/dev/null >>/tmp/startup_marks ;; esac
  return 0
}
# IN THE FOREGROUND, NOT -B (enigy/ocbm 2.0.0, c05d066). Daemonizing points hostapd's stdout at
# /dev/null, so the station events after its start (AP-STA-CONNECTED, AP-STA-DISCONNECTED and the
# IEEE 802.11 lines that say why a phone left) never reached the box log; a bench session's link was
# lost on 2026-11-21 with nothing on the box to say why. Under setsid it appends to the box log
# itself (O_APPEND, so ocbmd's in-place truncate at the cap is safe), and the converge step below
# waits for its AP-ENABLED. The line before the launch is the mark that wait reads after.
_ae_mark="[radio] hostapd starting on $IF ssid=$NAME"
echo "$_ae_mark" 2>/dev/null >> "$AP_BOXLOG" || _ae_mark=""
tl_stamp ap_start
setsid hostapd /etc/hostapd.conf </dev/null >>"$AP_BOXLOG" 2>&1 &
_hapd=$!

# The AP's DHCP server, explicitly scoped. The stock script masked this behind a global udhcpd
# check, so the always-running NCM instance satisfied it and the AP's own server never started -
# the phone then associates and never gets an address. Match on the CONFIG PATH, not the process
# name (ap_dhcp_pids, above).
#
# THE LAST AP'S SERVER IS NEVER THIS ONE'S. hostapd was started fresh just above, so any AP DHCP
# server still listed is the last AP's on its way out: radio_hal.sh's teardown waits for it, and this
# covers a caller that skipped the teardown. Up to 1 s here, then SIGKILL.
#
# Redirect and detach. It runs in the foreground (-f, as NCM's does) under setsid, so left alone it
# would inherit the caller's stdout and hold the pipe open - which makes an ssh invocation of the
# seam hang long after the script itself has finished, and would break the HAL's
# convergent-on-return contract for any caller that reads our output. radio_ap_dhcp.log stays a
# small `>`-truncated status file; only the readback below (on failure) is universal-logged.
#
# -a 250: the ARP probe before each offer waits 250 ms, not the default 2 s, which cost 1-1.9 s
# whenever a phone had no lease (enigy/ocbm 2.5.0, c4679c0). A short probe still catches an address
# in use. The adapter's BusyBox 1.32.1 has the udhcpd applet (checked 2026-11-21).
ap_dhcp_stop 5
setsid busybox udhcpd -f -a 250 /etc/udhcpd.conf </dev/null >/tmp/radio_ap_dhcp.log 2>&1 &
_dhcpd=$!

# ---- converge ---------------------------------------------------------------------------------
# Return only when the AP is really serving: the seam's contract is that a 0 means up, so a
# caller may start its advertiser immediately afterwards.
# DHCP IS PART OF CONVERGENCE, NOT A FOOTNOTE. The first version gated only on hostapd and the
# address, and merely COUNTED udhcpd in the closing log line. A DHCP server that died on a config
# error therefore still produced "AP up" and exit 0 - and the caller, trusting that, would start
# its advertiser. The phone then associates and never gets an address, which is verbatim the
# stock-script failure the udhcpd block above exists to fix. Anything the AP needs in order to
# actually serve a client belongs in the predicate.
ap_serving() {
  ps | grep -v grep | grep -qw hostapd || return 1
  ifconfig "$IF" 2>/dev/null | grep -q "inet addr:$WLANIP" || return 1
  ap_dhcp_serving || return 1
  return 0
}
n=0; _served=0
# The first pass is the connect timeline's ap_up: the AP serving.
while [ "$n" -lt 50 ]; do ap_serving && { _served=1; tl_stamp ap_up; break; }; n=$((n+1)); sleep 0.2; done
# CONFIRMED ON HOSTAPD'S OWN WORD (enigy/ocbm 2.6.5, 39f7a13). In the foreground hostapd is in the
# process list before its start-up is through, so the loop above passes one that then refuses its
# configuration, as hostapd refused channel 48 under HT40+ here on 2026-11-16. Its AP-ENABLED, after
# this start's line, says it took the configuration and the driver started the AP: enigy measured it
# about 0.65 s after the serving pass. Up to 2 s; with no AP-ENABLED by then, a hostapd still running
# counts, as -B's return did.
_ae_ms=""
if [ "$_served" = 1 ]; then
  n=0
  while [ "$n" -lt 20 ] && ! hapd_exited; do
    ap_enabled_seen && { _ae_ms=$((n * 100)); break; }
    n=$((n+1)); sleep 0.1
  done
fi
# Re-test the FULL predicate after the loop: falling out of it on the bound is not success, and
# re-checking only hostapd here would let a missing address or a dead DHCP server report as up.
if ! ap_serving || hapd_exited; then
  echo "$L AP did not converge (ssid=$NAME ch=$ch if=$IF):"
  hapd_exited && echo "$L   hostapd exited during its start-up"
  ps | grep -v grep | grep -qw hostapd \
    || echo "$L   hostapd is not running"
  ifconfig "$IF" 2>/dev/null | grep -q "inet addr:$WLANIP" \
    || echo "$L   $IF does not hold $WLANIP"
  ap_dhcp_serving \
    || { echo "$L   the AP DHCP server is not running (the one started here, pid $_dhcpd, exited) - a phone would associate and get no address"
         tail -3 /tmp/radio_ap_dhcp.log 2>/dev/null | sed "s|^|$L     |" | tee -a "$AP_BOXLOG"; }
  exit 1
fi
if [ -n "$_ae_ms" ]; then
  echo "$L AP up: ssid=$NAME ip=$WLANIP ch=$ch if=$IF dhcp=yes (AP-ENABLED ${_ae_ms} ms after it was serving)"
else
  echo "$L AP up: ssid=$NAME ip=$WLANIP ch=$ch if=$IF dhcp=yes (hostapd running; no AP-ENABLED from it in 2 s)"
fi
exit 0
