#!/bin/sh
########################
# radio_hal.sh - the chipset-neutral radio seam. Everything above this line (session_supervisor,
# the Rust daemons) asks for "wifi up" / "bt up" and never names a chip, a module, a firmware
# path or an attach helper.
#
# VERBS   probe | status | wifi_ap_on | wifi_ap_off | bt_prewarm | bt_on | bt_off | sco_on
# EXIT    0 converged now   1 ran and failed   2 already converged   3 unsupported on this unit
#
# ---------------------------------------------------------------------------------------------
# WHY THE VERB AND SCRIPT NAMES LOOK LIKE THIS. session_supervisor's teardown runs inside a
# detached `sh -c` whose own argv is visible to its own pkill patterns. That is a real bug this
# project already paid for once (2026-08-01: a plain `pkill -f` SIGKILLed the teardown subshell
# before it reached wlan_off, orphaning hostapd, which kept beaconing). So the name of this
# script and every verb MUST avoid the substrings the supervisor pattern-matches on:
# carplayd, btd, ocbmd, hostapd. Do not rename a verb without
# re-checking every pgrep/pkill pattern in session_supervisor.sh.
#
# THREE CONTRACT CLAUSES, each bought with a real failure:
#
#   1. CONVERGENT ON RETURN, NEVER FORK-AND-RETURN. When an `_on` verb returns 0, the radio is
#      up AND RESPONSIVE, and no work this verb started is still in flight. The vendor's own
#      dispatcher violates this (`/script/attach_bluetooth.sh &`, and the Broadcom branch
#      backgrounds brcm_patchram_plus) - that is precisely the uncoordinated double-bring-up
#      docs/wireless/01_BT_AND_RADIO.md recorded fighting itself for 7+ minutes on real hardware, because the caller
#      exec'd its advertiser while attach was still mid-retry. Callers rely on the return.
#
#   2. RESPONSIVENESS, NOT EXISTENCE. `hci0` can appear ~100ms after attach with firmware that
#      never loaded, and every later command then times out (docs/wireless/01_BT_AND_RADIO.md). Convergence is a real HCI
#      round-trip - read the local name back under a timeout - not a node existence test. The
#      vendor's check is `hciconfig hci0` existence, which a dead chip passes.
#
#   3. NEVER REBOOT, NEVER TOUCH SESSION DAEMONS. The vendor SD8987 branch can reboot the box
#      from inside a radio bring-up; that would bypass session_supervisor's escalation ladder
#      and its persistent reboot budget. A backend that believes only a reboot can recover says
#      so with an exit code and lets the layer that owns reboot policy decide. Likewise this
#      script never signals carplayd / btd / ocbmd: it cannot know
#      whether a live session is wired-owned, and the supervisor's conditional-reap logic
#      (docs/wireless/00_WIRELESS_CARPLAY.md #1.4) exists exactly to make that distinction.
#
# WHAT WE TAKE FROM CARLINKIT AND WHAT WE DO NOT. radio_detect.sh extracts this unit's own
# insmod/attach command lines from its own vendor dispatcher. Those lines are the vendor's
# OBSERVATIONS - fleet-deployed, working, and not re-derivable by us for parts we do not own -
# so we run them verbatim. Their surrounding control flow is their CHOICE, and it carries the
# defects above, so we supply our own. We never execute init_bluetooth_wifi.sh wholesale.
#
# THE AP LAYER IS ALWAYS OURS. Never delegate it. On a stripped box the vendor's
# start_bluetooth_wifi.sh reads config through riddleBoxCfg, which the OCBM base removes; with
# it gone that script seds an EMPTY wpa_passphrase into /etc/hostapd.conf - persistent, on
# flash - and falls back to WLANIP=192.168.50.2, which is NCM's own address, then repoints the
# DHCP pool onto the NCM subnet. Its teardown killalls every udhcpd, including NCM's. That is
# the control channel you are standing on.
########################
set -u
export PATH=/usr/sbin:/usr/bin:/sbin:/bin:/tmp/bin:$PATH

VERB="${1:-}"
CAPS=/tmp/radio_caps
WLAN_STATE=/tmp/.radio_wlan_state
BT_STATE=/tmp/.radio_bt_state
LOGP="[radio_hal]"

log() { echo "$LOGP $*"; }

# Single-flight per subsystem. mkdir is the portable atomic test-and-set on busybox (no flock
# dependency). A concurrent second bring-up must not run - the supervisor can manufacture
# overlapping edges (a respawn synthesizes a host-present edge), and stacking two insmod/rmmod
# sequences on one module is the driver-wedge class this guards against.
#
# A lock MUST be reclaimable. An earlier version had no staleness handling and no trap: a killed
# invocation (an ssh session timing out mid-bring-up is enough) left the directory behind and
# every later call refused to run, so wireless could never come up again until reboot. A lock is
# an optimisation against stacking, never a way to permanently disable a radio.
# RECLAIM ON LIVENESS, NOT ON AGE. The first version aged locks out after 2 minutes, which is
# SHORTER THAN A LEGITIMATE bt_on: against a present-but-wedged controller the convergence poll
# calls a `timeout 5` probe up to 30 times per attempt, across 4 attempts, so a genuine bring-up
# can hold the lock for many minutes. A concurrent caller would then declare the LIVE holder
# stale, delete its lock, and start a second attach against the same UART - which is exactly the
# OP_H5_SYNC collision the lock exists to prevent, manufactured by the lock itself.
#
# So the holder records its PID and reclaim requires the holder to be GONE. The age check stays
# only as a backstop for a PID that died and was recycled onto an unrelated process, and it is
# now generous enough that it can never fire against a working bring-up.
lock_take() {  # $1 = subsystem
  _lk="/tmp/.radio_lock_$1"; _n=0
  while ! mkdir "$_lk" 2>/dev/null; do
    _holder=$(cat "$_lk/pid" 2>/dev/null)
    if [ -n "$_holder" ] && [ ! -d "/proc/$_holder" ]; then
      log "lock $1 held by dead pid $_holder - reclaiming"
      rm -rf "$_lk"; continue
    fi
    if [ -z "$_holder" ] && [ -n "$(find "$_lk" -maxdepth 0 -mmin +20 2>/dev/null)" ]; then
      log "lock $1 has no holder and is >20min old - reclaiming"
      rm -rf "$_lk"; continue
    fi
    _n=$((_n+1)); [ "$_n" -ge 60 ] && {
      log "lock $1 busy >30s (held by pid ${_holder:-unknown}) - refusing to stack bring-ups"; return 1; }
    sleep 0.5
  done
  echo $$ > "$_lk/pid" 2>/dev/null
  # TWO traps, deliberately. In ash a trapped signal runs the handler and then RESUMES the
  # script: a single combined trap would release the lock on SIGTERM while the bring-up carried
  # on running, so the next caller would take the lock and stack a second attach on top of a
  # still-live one. The signal trap must therefore also exit.
  trap 'rm -rf "/tmp/.radio_lock_'"$1"'" 2>/dev/null' EXIT
  trap 'rm -rf "/tmp/.radio_lock_'"$1"'" 2>/dev/null; trap - EXIT; exit 143' INT TERM HUP
  return 0
}
lock_drop() { trap - EXIT INT TERM HUP; rm -rf "/tmp/.radio_lock_$1" 2>/dev/null; }

# Atomic so a reader can never see a half-written descriptor. Consumers (the Rust daemons) must
# treat a missing or malformed file as "absent" and fall back to their compiled defaults - this
# file is an override channel, never a hard dependency.
publish() {  # $1 = state file, rest = KEY=VALUE...
  _f=$1; shift
  { for kv in "$@"; do echo "$kv"; done; } > "$_f.new" 2>/dev/null && mv "$_f.new" "$_f"
}

caps() {
  # DERIVE THE DESCRIPTOR ONLY WHEN WE ACTUALLY NEED TO (2026-10-02).
  #
  # This used to re-run detection on every single call, on the reasoning that it is "read-only
  # and cheap". Measured on a CCPA it is NEITHER: `radio_detect.sh` walks /sys, /proc and the
  # module list, and cost ~2.05 s per bring-up. That made it the single largest item in
  # subscribe -> discoverable — larger than every hciconfig step in btd's bring-up put together,
  # and it was paid on the wireless path where BT was ALREADY converged and the whole answer was
  # "skipping preload".
  #
  # Why skipping is safe: $CAPS lives on /tmp, which is tmpfs, so an existing descriptor was
  # necessarily written during THIS boot — and a unit's chipset cannot change without a reboot
  # (docs/wireless/01_BT_AND_RADIO.md: only the driver set for a unit's own chip is in its
  # rootfs). Re-deriving it mid-session therefore cannot produce a different answer, only a
  # slower one.
  #
  # The force-redetect escape hatch exists because detection is the thing that REPAIRS a stale or
  # half-written descriptor, and a failed bring-up is exactly when you want a second opinion.
  # Callers set RADIO_REDETECT=1 on that path (see do_bt_on's failure branches).
  if [ ! -s "$CAPS" ] || [ "${RADIO_REDETECT:-0}" = 1 ]; then
    [ -x /script/radio_detect.sh ] && sh /script/radio_detect.sh -q >/dev/null 2>&1
  fi
  [ -f "$CAPS" ] || { log "no descriptor at $CAPS and radio_detect.sh is missing"; return 1; }

  # A MALFORMED DESCRIPTOR MUST NEVER ABORT THIS SCRIPT. Measured: an extracted vendor line
  # carrying an unexpanded shell variable - e.g. the NXP branch's
  #     insmod /tmp/moal.ko "mod_para=$nxpWiFiConfig"
  # - makes `.` fail under `set -u` with status **2** in ash/dash, and 2 is this script's
  # "already converged" code. The verb would exit before the backend switch, reporting SUCCESS,
  # and the caller would start its advertiser over radios that were never brought up. That is
  # the silent-failure class this whole seam exists to eliminate, so it is not acceptable to
  # merely "usually" avoid it: source defensively and then require what we depend on.
  set +u
  . "$CAPS" 2>/dev/null
  set -u
  # Every key this script consumes gets a defined default, so a descriptor written by an older
  # or partially-failed detect can degrade to "unsupported" instead of killing the shell.
  : "${RADIO_BACKEND:=none}"   "${RADIO_WIRELESS:=no}"      "${RADIO_CHIP:=unknown}"
  : "${RADIO_SDIO_DEVICE:=}"   "${RADIO_SDIO_VENDOR:=}"     "${RADIO_FW_TREE:=}"
  : "${RADIO_KO_TARBALL:=}"    "${RADIO_WLAN_MODULES:=}"    "${RADIO_WLAN_INSMOD:=}"
  : "${RADIO_BT_LDISC_KO:=}"   "${RADIO_BT_ATTACH_CMD:=}"   "${RADIO_BT_ATTACH:=}"
  : "${RADIO_BT_PRELOAD:=}"    "${RADIO_BT_PRELOAD_CMD:=}"  "${RADIO_BT_AFTER_WLAN:=0}"
  : "${RADIO_BT_SCO_MTU_CMD:=}"  "${RADIO_BT_SCO_ROUTE_CMD:=}"
  : "${RADIO_BT_UART:=/dev/ttymxc2}"
  return 0
}

# Enumerate the wireless interface rather than assuming a name. The name is an insmod PARAMETER
# that differs per chip (Realtek if2name=sta0, Broadcom iface_name=sta), and on Broadcom wlan0
# does not exist until something explicitly creates it on top of sta0. A cfg80211/wext netdev is
# identifiable without knowing the driver: it carries wireless/ or phy80211/ in sysfs.
#
# NOTE THE VARIABLE NAMES. These helpers are called from inside caller loops, and sh has no
# function scope, so a bare or short loop variable here silently overwrites the caller's. That is
# not theoretical: wlan_iface() once used `_n`, the BT wait loop used `_n` as its counter, and
# calling the helper replaced the counter with a sysfs path — `_n=$((_n+1))` then failed with an
# arithmetic syntax error. It only ever reached that line when NO interface existed (otherwise
# the caller's `&& break` fired first), i.e. exclusively in the BT-only bridge role. Keep these
# names distinctive.
# =============================================================================================
# THE CAR'S HOTSPOT (T15 route B). Off unless /script/sta_uplink.conf says enabled=1. The file is
# pushed by the app (key=value, mode 600): enabled, sta_iface, ssid, and psk when the adapter is to
# join; without psk it is the scan-only diagnostic of 2026-11-16.
#
# The scan (no passphrase): once per boot, BEFORE the access point starts, wpa_supplicant scans both
# bands, the result is logged (numbers and flags only: no network name, no address), and the client
# is stopped completely before hostapd starts. In the car it found the hotspot WPA2 on 5765 MHz,
# channel 153, and on 2437 MHz (2026-11-16, Play 258).
#
# The join (passphrase), also BEFORE the access point starts:
#  - the phone may be handed over before the access point is up, and it is told the channel then
#    (iAP2 0x5703 reads /etc/hostapd.conf). So the channel the join last landed on is remembered on
#    flash (UPLINK_MEMORY) and written into hostapd.conf before the join starts. Bench 2026-11-16:
#    without it the handoff named the app's channel 149, the access point came up on 48, and the
#    phone never arrived;
#  - the client tries for UPLINK_JOIN_S on the remembered channel only, or with none remembered on
#    any 5 GHz channel an access point may simply start on (not DFS: freq_list), so the car's
#    2.4 GHz side is never taken. Not joined and not seen on the remembered channel, but seen on
#    another usable one: that one is remembered for the next start. Seen where it was looked for and
#    still not joined is a password question, and changes nothing (a wrong password once moved the
#    memory to the home network's other access point, bench 2026-11-16);
#  - the access point starts on the driver's other wireless interface (IW416 drv_mode=3: wlan0
#    access point, sta0 client, bench 2026-11-15), joined or not: on sta0 after a failed join no
#    phone could join it (bench 2026-11-16). Joined, on the joined channel: one radio, one channel.
#    Not joined, the client is stopped completely first, and the access point uses the remembered
#    channel, which the handoff has already named;
#  - after that the client never searches: no address, an address clash, or the link lost, and it is
#    stopped until the next Wi-Fi start.
# Why before: this driver's client is the wext one, which cannot scan a single channel. A join made
# while the access point was up scanned every channel for seconds while the phone was being handed
# over: on the bench (2026-11-16) one of three sessions never connected and one took 37 s to its
# first picture. In the car on 2026-11-15 a client left searching after a failed join kept the
# phone off the access point for good. The nl80211 client, which can scan one channel, is the
# fallback in docs/ops/08_FUTURE_TASKS.md T15.
# The lines go to /tmp/box.log, which ocbmd streams live: /tmp/wlan.log is overwritten at every
# Wi-Fi start and only pulled when the app stops (car, 2026-11-16).
# =============================================================================================
UPLINK_CONF=/script/sta_uplink.conf
UPLINK_MEMORY=/script/sta_uplink.channel
UPLINK_AP_IF_FILE=/tmp/.radio_ap_iface
UPLINK_AP_CH_FILE=/tmp/.radio_uplink_ap_channel
UPLINK_JOINED_AT=/tmp/.radio_uplink_joined_at
UPLINK_DIR=/tmp/wpa_uplink
UPLINK_JOIN_S=10
UPLINK_WPA=$(command -v wpa_supplicant 2>/dev/null || true)
UPLINK_CLI=$(command -v wpa_cli 2>/dev/null || true)
UPLINK_SCANNED=/tmp/.radio_hotspot_scanned

uplink_key() {  # $1 = key; the value from the conf file, without surrounding space
  sed -n "s/^[[:space:]]*$1[[:space:]]*=[[:space:]]*//p" "$UPLINK_CONF" 2>/dev/null | head -1 | tr -d '\r'
}

# Pure: an interface name this script will hand to ifconfig.
uplink_ifname_ok() { case "$1" in ''|*[!a-z0-9]*) return 1 ;; *) return 0 ;; esac; }

# Pure: a WPA passphrase that can be written inside quotes: 8-63 printable, no quote or backslash.
uplink_psk_ok() {
  case "$1" in *'"'*|*'\'*) return 1 ;; esac
  [ "${#1}" -ge 8 ] && [ "${#1}" -le 63 ] || return 1
  [ "$(printf '%s' "$1" | tr -d '\040-\176' | wc -c)" -eq 0 ]
}

# Pure: a frequency in MHz as a channel number; empty for anything else.
freq_to_channel() {
  case "$1" in
    2484) echo 14 ;;
    24[0-9][0-9]) echo $(( ($1 - 2407) / 5 )) ;;
    5[0-9][0-9][0-9]) echo $(( ($1 - 5000) / 5 )) ;;
    *) echo "" ;;
  esac
}

# Pure: a 5 GHz channel as its frequency in MHz; empty for anything else.
channel_to_freq() {
  case "$1" in ''|*[!0-9]*) echo "" ;; *) [ "$1" -ge 32 ] && [ "$1" -le 177 ] && echo $(( 5000 + $1 * 5 )) || echo "" ;; esac
}

# Pure: what an access point may not simply start on: radar-checked (DFS) 5 GHz channels.
channel_note() {
  case "$1" in
    5[0-9]|6[0-4]|10[0-9]|11[0-9]|12[0-9]|13[0-9]|14[0-4]) echo "DFS" ;;
    1|2|3|4|5|6|7|8|9|10|11|12|13|14) echo "2.4GHz" ;;
    *) echo "5GHz" ;;
  esac
}

# Pure: a channel the access point may move to for the car: 5 GHz, not DFS.
uplink_channel_ok() {
  case "$1" in ''|*[!0-9]*) return 1 ;; esac
  [ "$(channel_note "$1")" = 5GHz ] && [ -n "$(channel_to_freq "$1")" ]
}

# Pure: the frequencies the client may join on: every 5 GHz channel uplink_channel_ok accepts.
uplink_freq_list() {
  for _ufl_c in 36 40 44 48 149 153 157 161 165; do uplink_channel_ok "$_ufl_c" && printf '%s ' "$(channel_to_freq "$_ufl_c")"; done
}

# Pure: from `wpa_cli scan_results` rows (tab-separated: bssid, freq, signal, flags, ssid), the
# strongest usable channel the network named $1 is on; empty if none.
uplink_pick_channel() {
  awk -F'\t' -v n="$1" '$5==n && $2>=5000 && $2<5900 {print $3" "$2}' | sort -rn | while read -r _upc_s _upc_f; do
    _upc_c=$(freq_to_channel "$_upc_f")
    uplink_channel_ok "$_upc_c" && { echo "$_upc_c"; break; }
  done
}

# Pure: why a join failed, from `wpa_cli scan_results` rows on stdin. $1 = network name, $2 = the
# frequencies looked on, $3 = the remembered channel (may be empty). Prints one of
#   password <MHz>    seen where it was looked for: not a channel question
#   moved <channel>   not on the remembered channel, but on another usable one
#   elsewhere <MHz…>  seen only where it cannot be joined (2.4 GHz, DFS)
#   unseen
uplink_why_not() {
  _uwn_rows=$(cat)
  _uwn_seen=$(printf '%s\n' "$_uwn_rows" | awk -F'\t' -v n="$1" '$5==n {print $2}' | sort -u | tr '\n' ' ')
  _uwn_tried=$(printf '%s\n' "$_uwn_rows" | awk -F'\t' -v n="$1" -v l=" $2 " '$5==n && index(l, " "$2" ") {print $2; exit}')
  _uwn_pick=$(printf '%s\n' "$_uwn_rows" | uplink_pick_channel "$1")
  if [ -n "$_uwn_tried" ]; then echo "password $_uwn_tried"
  elif [ -n "$3" ] && [ -n "$_uwn_pick" ]; then echo "moved $_uwn_pick"
  elif [ -n "$_uwn_seen" ]; then echo "elsewhere ${_uwn_seen% }"
  else echo unseen; fi
}

# Pure: how far a wpa_supplicant state is along a join (wpa_cli status, wpa_state=).
uplink_state_rank() {
  case "$1" in
    AUTHENTICATING) echo 2 ;; ASSOCIATING) echo 3 ;; ASSOCIATED) echo 4 ;;
    4WAY_HANDSHAKE) echo 5 ;; GROUP_HANDSHAKE) echo 6 ;; COMPLETED) echo 7 ;;
    SCANNING) echo 1 ;; *) echo 0 ;;
  esac
}

# Pure: what the furthest state a failed join reached says about why. The car's hotspot refused
# every join of Play 259 (2026-11-17) and the old log could not tell a wrong password from a
# refusal: this is the line that tells them apart. ASSOCIATED with no 4WAY_HANDSHAKE after it means
# the hotspot never began the exchange (the car with Play 260, 2026-11-18): not a password question.
uplink_state_note() {
  case "$(uplink_state_rank "$1")" in
    0|1) echo "never tried to connect" ;;
    2|3) echo "the hotspot did not accept the connection" ;;
    4) echo "connected, then dropped before the password exchange began" ;;
    5) echo "the password exchange failed" ;;
    6) echo "failed after the password exchange" ;;
    *) echo "joined" ;;
  esac
}

uplink_remembered() { _upr=$(head -1 "$UPLINK_MEMORY" 2>/dev/null | tr -d '\r '); uplink_channel_ok "$_upr" && echo "$_upr"; }

uplink_remember() {  # $1 = channel; written to flash only when it changes
  uplink_channel_ok "$1" || return 0
  [ "$(uplink_remembered)" = "$1" ] && return 0
  echo "$1" > "$UPLINK_MEMORY" 2>/dev/null && sync
}

is_wireless() { [ -e "/sys/class/net/$1/wireless" ] || [ -e "/sys/class/net/$1/phy80211" ]; }

# The scan's lines also go to /tmp/box.log, which ocbmd streams to the app live. Its only other copy
# is /tmp/wlan.log, which every later Wi-Fi start overwrites and the app pulls only when it stops:
# in the car the scan ran, then a radio restart rewrote the file before the app ever read it
# (2026-11-16). Numbers and flags only, as everywhere in these lines.
scan_log() { log "$*"; echo "$LOGP $*" >> /tmp/box.log 2>/dev/null; }

# Stop the client this scan started, and wait until it is gone: hostapd must not share the radio
# with it (see above). Returns 1 only if it could not be stopped.
hotspot_scan_stop() {
  _hs_pid=$(cat /tmp/wpa_hotspot_scan.pid 2>/dev/null)
  [ -n "$_hs_pid" ] && kill "$_hs_pid" 2>/dev/null
  _hs_n=0
  while [ "$_hs_n" -lt 20 ] && [ -n "$_hs_pid" ] && kill -0 "$_hs_pid" 2>/dev/null; do _hs_n=$((_hs_n+1)); sleep 0.1; done
  [ -n "$_hs_pid" ] && kill -0 "$_hs_pid" 2>/dev/null && kill -9 "$_hs_pid" 2>/dev/null
  rm -f /tmp/wpa_hotspot_scan.pid /tmp/wpa_hotspot_scan.conf
  rm -rf /tmp/wpa_hotspot_scan
  ! { [ -n "$_hs_pid" ] && kill -0 "$_hs_pid" 2>/dev/null; }
}

# Before the access point starts: look for the car's hotspot and log what it is. Once per boot, and
# only without a passphrase: with one, the join does the looking.
hotspot_scan() {
  [ -s "$UPLINK_CONF" ] && [ "$(uplink_key enabled)" = 1 ] || return 0
  [ -e "$UPLINK_SCANNED" ] && return 0
  [ -z "$(uplink_key psk)" ] || return 0
  [ -n "$UPLINK_WPA" ] && [ -n "$UPLINK_CLI" ] || { scan_log "hotspot scan: no wpa_supplicant on this adapter"; return 0; }
  touch "$UPLINK_SCANNED"
  _hs_if=$(uplink_key sta_iface)
  uplink_ifname_ok "$_hs_if" && is_wireless "$_hs_if" \
    || { scan_log "hotspot scan: interface '${_hs_if}' is not a wireless interface here; skipped"; return 0; }
  _hs_ssid=$(uplink_key ssid)
  [ -n "$_hs_ssid" ] || { scan_log "hotspot scan: no hotspot name configured; skipped"; return 0; }
  ifconfig "$_hs_if" up 2>/dev/null
  printf 'ctrl_interface=/tmp/wpa_hotspot_scan\n' > /tmp/wpa_hotspot_scan.conf
  "$UPLINK_WPA" -B -D wext -i "$_hs_if" -c /tmp/wpa_hotspot_scan.conf -P /tmp/wpa_hotspot_scan.pid >/dev/null 2>&1 \
    || { scan_log "hotspot scan: wpa_supplicant did not start"; hotspot_scan_stop; return 0; }
  _hs_t0=$(cut -d. -f1 /proc/uptime)
  "$UPLINK_CLI" -p /tmp/wpa_hotspot_scan -i "$_hs_if" scan >/dev/null 2>&1
  # A full scan of both bands takes 2-4 s on this radio; read twice, keep the later.
  _hs_n=0; _hs_rows=0
  while [ "$_hs_n" -lt 25 ]; do
    sleep 0.2; _hs_n=$((_hs_n+1))
    _hs_now=$("$UPLINK_CLI" -p /tmp/wpa_hotspot_scan -i "$_hs_if" scan_results 2>/dev/null | tail -n +2 | grep -c .)
    [ "$_hs_now" -gt 0 ] && [ "$_hs_now" = "$_hs_rows" ] && [ "$_hs_n" -ge 15 ] && break
    _hs_rows=$_hs_now
  done
  _hs_res=$("$UPLINK_CLI" -p /tmp/wpa_hotspot_scan -i "$_hs_if" scan_results 2>/dev/null | tail -n +2)
  hotspot_scan_stop || scan_log "hotspot scan: W the client did not stop"
  _hs_total=$(printf '%s\n' "$_hs_res" | grep -c .)
  _hs_24=$(printf '%s\n' "$_hs_res" | awk -F'\t' '$2>=2400 && $2<2500' | grep -c .)
  _hs_5=$(printf '%s\n' "$_hs_res" | awk -F'\t' '$2>=4900 && $2<5900' | grep -c .)
  # Exact name match on the SSID column. The app refuses names with quotes, backslashes or controls.
  _hs_match=$(printf '%s\n' "$_hs_res" | awk -F'\t' -v n="$_hs_ssid" '$5==n {print $2" "$3" "$4}')
  scan_log "hotspot scan: ${_hs_total} networks seen (${_hs_24} on 2.4 GHz, ${_hs_5} on 5 GHz) in $(( $(cut -d. -f1 /proc/uptime) - _hs_t0 )) s"
  if [ -z "$_hs_match" ]; then
    scan_log "hotspot scan: the car's hotspot was NOT seen"
  else
    printf '%s\n' "$_hs_match" | while read -r _hs_f _hs_s _hs_fl; do
      _hs_c=$(freq_to_channel "$_hs_f")
      scan_log "hotspot scan: the car's hotspot seen: ${_hs_f} MHz channel ${_hs_c:-?} ($(channel_note "$_hs_c")) signal ${_hs_s} dBm security ${_hs_fl}"
    done
  fi
}

# Whether this Wi-Fi start tries to join the car: sets UPLINK_STA_IF and UPLINK_AP_IF. Every
# condition missing means the access point starts exactly as it does without this feature.
uplink_plan() {
  [ -s "$UPLINK_CONF" ] && [ "$(uplink_key enabled)" = 1 ] || return 1
  [ -n "$(uplink_key psk)" ] || return 1
  [ -n "$UPLINK_WPA" ] && [ -n "$UPLINK_CLI" ] || return 1
  uplink_psk_ok "$(uplink_key psk)" || { scan_log "uplink: the passphrase cannot be used; not joining"; return 1; }
  [ -n "$(uplink_key ssid)" ] || return 1
  UPLINK_STA_IF=$(uplink_key sta_iface)
  uplink_ifname_ok "$UPLINK_STA_IF" && is_wireless "$UPLINK_STA_IF" || return 1
  UPLINK_AP_IF=""
  for _upl_d in /sys/class/net/*/; do
    _upl_n=$(basename "$_upl_d")
    [ "$_upl_n" != "$UPLINK_STA_IF" ] && is_wireless "$_upl_n" && { UPLINK_AP_IF=$_upl_n; break; }
  done
  [ -n "$UPLINK_AP_IF" ] || { scan_log "uplink: no second wireless interface for the access point; not joining"; return 1; }
}

uplink_log() { echo "$LOGP uplink: $*" >> /tmp/box.log 2>/dev/null; }
uplink_cli() { "$UPLINK_CLI" -p "$UPLINK_DIR" -i "$UPLINK_STA_IF" "$@" 2>/dev/null; }
uplink_joined() { uplink_cli status | grep -q '^wpa_state=COMPLETED'; }

# Stop the client completely: guard, DHCP client, wpa_supplicant, address. $1 = "guard" when the
# guard itself calls it (it must not kill itself).
uplink_stop() {
  [ "${1:-}" = guard ] || { _ups_g=$(cat /tmp/wpa_uplink.guard 2>/dev/null); [ -n "$_ups_g" ] && kill "$_ups_g" 2>/dev/null; }
  _ups_d=$(cat /tmp/udhcpc_uplink.pid 2>/dev/null); [ -n "$_ups_d" ] && kill "$_ups_d" 2>/dev/null
  _ups_p=$(cat /tmp/wpa_uplink.pid 2>/dev/null)
  [ -n "$_ups_p" ] && kill "$_ups_p" 2>/dev/null
  _ups_n=0
  while [ "$_ups_n" -lt 20 ] && [ -n "$_ups_p" ] && kill -0 "$_ups_p" 2>/dev/null; do _ups_n=$((_ups_n+1)); sleep 0.1; done
  [ -n "$_ups_p" ] && kill -0 "$_ups_p" 2>/dev/null && kill -9 "$_ups_p" 2>/dev/null
  if [ -s "$UPLINK_JOINED_AT" ]; then
    uplink_log "client stopped after $(( $(cut -d. -f1 /proc/uptime) - $(cat "$UPLINK_JOINED_AT") )) s joined"
  fi
  _ups_if=$(cat /tmp/wpa_uplink.if 2>/dev/null)
  uplink_ifname_ok "$_ups_if" && ifconfig "$_ups_if" 0.0.0.0 down 2>/dev/null
  rm -rf "$UPLINK_DIR" /tmp/wpa_uplink.conf /tmp/wpa_uplink.pid /tmp/wpa_uplink.guard /tmp/wpa_uplink.if \
         /tmp/udhcpc_uplink.pid "$UPLINK_JOINED_AT"
}

# Pure: whether two dotted quads share their first three octets (the access point serves a /24).
same_24() { [ -n "$1" ] && [ "${1%.*}" = "${2%.*}" ]; }

# Join, before the access point starts. Joined: UPLINK_CH is the channel, 0 returned. Not joined:
# the client is already stopped completely, 1 returned.
uplink_join() {
  _upj_t0=$(cut -d. -f1 /proc/uptime)
  _upj_m=$(uplink_remembered)
  if [ -n "$_upj_m" ]; then _upj_fl=$(channel_to_freq "$_upj_m"); else _upj_fl=$(uplink_freq_list); fi
  echo "$UPLINK_STA_IF" > /tmp/wpa_uplink.if
  ( umask 077
    { echo "ctrl_interface=$UPLINK_DIR"
      echo "network={"
      printf '  ssid="%s"\n' "$(uplink_key ssid)"
      printf '  psk="%s"\n' "$(uplink_key psk)"
      echo "  freq_list=$_upj_fl"
      echo "}"; } > /tmp/wpa_uplink.conf )
  ifconfig "$UPLINK_STA_IF" 0.0.0.0 up 2>/dev/null
  "$UPLINK_WPA" -B -D wext -i "$UPLINK_STA_IF" -c /tmp/wpa_uplink.conf -P /tmp/wpa_uplink.pid >/dev/null 2>&1 \
    || { scan_log "uplink: wpa_supplicant did not start"; uplink_stop guard; return 1; }
  # Follow the client's state: the furthest it got, and the order of states (names only).
  _upj_n=0; _upj_far=""; _upj_path=""; _upj_last=""; _upj_hops=0
  while [ "$_upj_n" -lt $((UPLINK_JOIN_S * 5)) ]; do
    _upj_st=$(uplink_cli status | sed -n 's/^wpa_state=//p')
    [ "$_upj_st" = COMPLETED ] && break
    if [ -n "$_upj_st" ] && [ "$_upj_st" != "$_upj_last" ]; then
      [ "$_upj_hops" -lt 16 ] && _upj_path="${_upj_path:+$_upj_path>}$_upj_st"
      _upj_last=$_upj_st; _upj_hops=$((_upj_hops+1))
      [ "$(uplink_state_rank "$_upj_st")" -gt "$(uplink_state_rank "$_upj_far")" ] && _upj_far=$_upj_st
    fi
    _upj_n=$((_upj_n+1)); sleep 0.2
  done
  _upj_res=$(uplink_cli scan_results | tail -n +2)
  _upj_b=$(uplink_cli status | sed -n 's/^bssid=//p')
  _upj_f=$(printf '%s\n' "$_upj_res" | awk -F'\t' -v b="$_upj_b" 'tolower($1)==tolower(b){print $2; exit}')
  UPLINK_CH=$(freq_to_channel "$_upj_f")
  if ! uplink_joined || ! uplink_channel_ok "$UPLINK_CH"; then
    _upj_why=$(printf '%s\n' "$_upj_res" | uplink_why_not "$(uplink_key ssid)" "$_upj_fl" "$_upj_m")
    case "$_upj_why" in
      password*) scan_log "uplink: the car's hotspot was seen on ${_upj_why#* } MHz but not joined within ${UPLINK_JOIN_S} s (see the furthest step); client stopped" ;;
      moved*)    uplink_remember "${_upj_why#* }"
                 scan_log "uplink: the car's hotspot is not on channel $_upj_m but on ${_upj_why#* }; remembered for the next Wi-Fi start; client stopped" ;;
      elsewhere*) scan_log "uplink: not joined: the car's hotspot was seen only on ${_upj_why#* } MHz (joins only on 5 GHz, not DFS); client stopped" ;;
      *)         scan_log "uplink: not joined within ${UPLINK_JOIN_S} s: the car's hotspot was not seen; client stopped" ;;
    esac
    scan_log "uplink: furthest step ${_upj_far:-none} ($(uplink_state_note "$_upj_far")); states ${_upj_path:-none} (${_upj_hops} changes)"
    uplink_stop guard; return 1
  fi
  _upj_sig=$(printf '%s\n' "$_upj_res" | awk -F'\t' -v b="$_upj_b" 'tolower($1)==tolower(b){print $3; exit}')
  uplink_remember "$UPLINK_CH"
  cut -d. -f1 /proc/uptime > "$UPLINK_JOINED_AT"
  scan_log "uplink: joined on channel $UPLINK_CH in $(( $(cut -d. -f1 /proc/uptime) - _upj_t0 )) s, signal ${_upj_sig:-?} dBm"
}

# The background half, once the access point is up: an address, then watch the link. Its output
# goes nowhere: callers of this script may read its output to the end, and a child left holding
# that pipe would keep them waiting. Every way out stops the client: once joined it must never
# search again under a session (see the section header).
uplink_watch() {
  _upw_t0=$(cut -d. -f1 /proc/uptime)
  udhcpc -i "$UPLINK_STA_IF" -b -t 5 -T 2 -p /tmp/udhcpc_uplink.pid >/dev/null 2>&1 \
    || uplink_log "W the DHCP client did not start"
  _upw_n=0; _upw_ip=""
  while [ "$_upw_n" -lt 25 ] && [ -z "$_upw_ip" ] && uplink_joined; do _upw_n=$((_upw_n+1)); sleep 0.4; _upw_ip=$(ap_ip_of "$UPLINK_STA_IF"); done
  if [ -z "$_upw_ip" ]; then uplink_log "no address within 10 s; client stopped"; uplink_stop guard; return; fi
  # The car's network on the access point's own /24 would leave two routes to one subnet.
  if same_24 "$_upw_ip" "$(ap_ip_of "$UPLINK_AP_IF")"; then
    uplink_log "the car's network uses the access point's own subnet; client stopped"; uplink_stop guard; return
  fi
  uplink_log "address: yes, in $(( $(cut -d. -f1 /proc/uptime) - _upw_t0 )) s"
  # wpa_supplicant searches the moment the link drops: one missed 2 s check is enough to stop it.
  while kill -0 "$(cat /tmp/wpa_uplink.pid 2>/dev/null)" 2>/dev/null; do
    sleep 2
    uplink_joined || { uplink_log "link lost; client stopped until the next Wi-Fi start"; uplink_stop guard; return; }
  done
}

uplink_watch_start() {
  ( uplink_watch ) </dev/null >/dev/null 2>&1 &
  echo $! > /tmp/wpa_uplink.guard
}

# The access point's interface: the one this boot's Wi-Fi start chose (the join puts it on the
# driver's other interface), else the first wireless interface.
wlan_iface() {
  _wif_r=$(cat "$UPLINK_AP_IF_FILE" 2>/dev/null)
  uplink_ifname_ok "$_wif_r" && is_wireless "$_wif_r" && { echo "$_wif_r"; return 0; }
  for _wif_d in /sys/class/net/*/; do
    [ -e "$_wif_d/wireless" ] || [ -e "$_wif_d/phy80211" ] || continue
    basename "$_wif_d"; return 0
  done
  return 1
}

hci_dev() {
  for _hcid_d in /sys/class/bluetooth/hci*/; do
    [ -d "$_hcid_d" ] && { basename "${_hcid_d%/}"; return 0; }
  done
  return 1
}

# Clause 2: a real HCI round-trip under a timeout. `hciconfig <dev>` merely existing proves
# nothing - the whole point. Single-shot: used inside the convergence poll, where a fast
# negative is what we want because we are about to poll again anyway.
bt_responsive() {  # $1 = hci dev
  timeout 5 hciconfig "$1" name 2>/dev/null | grep -q "Name:"
}
# Kernel flags only (HCIGETDEVINFO, what `hciconfig <dev>` prints): never reaches the chip, so it
# answers instantly even against a wedged controller. Says nothing about responsiveness - that is
# still bt_responsive's job - only whether a name read can possibly succeed.
bt_powered_up() {  # $1 = hci dev
  timeout 5 hciconfig "$1" 2>/dev/null | grep -q "UP RUNNING"
}
# `hciconfig <dev> up|down` at SCHED_FIFO 1 where chrt exists. Both wait in the kernel's
# __hci_req_sync, which on the CCPA's single-core 3.14 kernel can lose its wake-up and sleep the
# full 10 s timeout; a real-time caller is always waiting before the HCI workqueue can even send
# the command (btd's bt_bringup::run_rt has the details; enigy/ocbm measured it). A chrt that
# exists but fails (applet missing, EPERM) never runs hciconfig, so any failure there is retried
# plain; repeating up or down is harmless (EALREADY, which hciconfig exits 0 on).
hci_rt() {
  if command -v chrt >/dev/null 2>&1 && chrt -f 1 hciconfig "$@"; then return 0; fi
  hciconfig "$@"
}
# Page and inquiry scan off with a raw Write_Scan_Enable(0x00), not `hciconfig noscan`: that
# ioctl can sleep its full 10 s timeout on the CCPA's 3.14 kernel (lost wake-up, btd's
# hci::write_scan_enable), and both callers hold the bt lock the next session's bt_on waits on.
hci_scan_off() {  # $1 = hci dev
  timeout 5 hcitool -i "$1" cmd 0x03 0x001a 0x00 >/dev/null 2>&1
}
# The peer address of every ACL on $1, one per line.
hci_acl_peers() {  # $1 = hci dev
  timeout 3 hcitool -i "$1" con 2>/dev/null | awk '/ACL/ {print $3}'
}
# End every ACL on $1 with a real HCI Disconnect before a power-down. `down` resets the controller,
# and a reset is not a disconnect: the phone is not told the link ended and may only learn it from
# its supervision timeout (enigy/ocbm saw the next session's first page fail in 3 of 8 reconnects,
# each costing the reconnect loop's backoff). `hcitool dc` exits 0 whether or not the controller
# took the command, so the result is read back from the connection list (up to 1 s). Address tail
# only in the log, as ssp_agent does: box.log is uploaded.
hci_drop_acls() {  # $1 = hci dev
  for _a in $(hci_acl_peers "$1"); do
    timeout 3 hcitool -i "$1" dc "$_a" >/dev/null 2>&1
    _n=0
    while hci_acl_peers "$1" | grep -qi "^$_a\$" && [ "$_n" -lt 10 ]; do _n=$((_n+1)); sleep 0.1; done
    if hci_acl_peers "$1" | grep -qi "^$_a\$"; then
      log "BT ..${_a#*:*:*:*:} still connected after HCI Disconnect - powering down anyway"
    else
      log "BT disconnected ..${_a#*:*:*:*:} before power-down"
    fi
  done
}

# Confirmed variant - used for DECISIONS, never inside a poll loop.
#
# MEASURED on an RTL8822CS: a healthy, attached controller intermittently times out a name read
# - about one attempt in four, with the next attempts a second later succeeding. So the probe is
# not a clean oracle, and the two questions we ask it deserve DIFFERENT bars:
#
#   "did my attach succeed?"        -> strict. One real round-trip, poll until it answers.
#   "is this radio already up?"     -> reluctant. A false negative here does not merely misreport;
#                                      it sends us into rmmod + GPIO reset + re-attach against a
#                                      working chip, which during a live session drops the phone.
#
# Hence: be strict to declare success, reluctant to destroy. Five spaced attempts cost up to a
# few tens of seconds in the rare all-miss case, and only on the decision path.
bt_responsive_confirmed() {  # $1 = hci dev
  _t=0
  while [ "$_t" -lt 5 ]; do
    bt_responsive "$1" && return 0
    _t=$((_t+1)); [ "$_t" -lt 5 ] && sleep 2
  done
  return 1
}

ap_running() { pgrep -x hostapd >/dev/null 2>&1 || ps | grep -v grep | grep -qw hostapd; }

# THE BOX IDENTITY. Wi-Fi and Bluetooth MUST advertise the same ccpa-<4hex>, and the only way to
# guarantee that is to decide it ONCE and persist it.
#
# Deriving it per-radio does not work, and the failure is not hypothetical. The natural sources
# disagree: on the validated unit the WLAN MAC and the BT controller address share no octets at
# all (Realtek sets no bd_addr, so BT uses its own chip efuse), giving suffixes like ccpa-AABB
# from Wi-Fi and ccpa-CCDD from Bluetooth. So which name a radio gets would depend on which radio
# happened to be up when it was asked — and in the BT-only bridge role (wifi_ap:false) the WLAN
# driver is never loaded at all, so BT would silently take the other name. Worse, the answer
# could change between boots, and the phone stores the name in its bonded record.
#
# So: first successful derivation wins and is written to /etc/carplay_ident; everything after
# reads that file. One small flash write, once per box.
IDENT_FILE=/etc/carplay_ident
box_name() {  # $1 = optional hci dev, used only as a late fallback source
  if [ -s "$IDENT_FILE" ]; then head -1 "$IDENT_FILE"; return 0; fi
  _sfx=""
  # Preference order is about STABILITY, not availability: the Wi-Fi MAC is what the SSID has
  # historically been derived from, so a box that already has a name keeps it.
  _wi=$(wlan_iface 2>/dev/null) && _sfx=$(cat "/sys/class/net/$_wi/address" 2>/dev/null | tr -d ':' | tr 'A-F' 'a-f' | sed 's/.*\(....\)$/\1/')
  case "$_sfx" in ''|*[!0-9a-f]*) _sfx="" ;; esac
  # The vendor helper reports the Wi-Fi MAC without the interface being up, which is exactly the
  # bridge-role case. Kept by the base install's radio guard where it exists.
  if [ -z "$_sfx" ] && command -v set_wifi_mac >/dev/null 2>&1; then
    _sfx=$(set_wifi_mac 2>/dev/null | grep -oE '([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}' | head -1 \
           | tr -d ':' | tr 'A-F' 'a-f' | sed 's/.*\(....\)$/\1/')
    case "$_sfx" in ''|*[!0-9a-f]*) _sfx="" ;; esac
  fi
  if [ -z "$_sfx" ] && [ -n "${1:-}" ]; then
    _sfx=$(cat "/sys/class/bluetooth/$1/address" 2>/dev/null | tr -d ':' | tr 'A-F' 'a-f' | sed 's/.*\(....\)$/\1/')
    case "$_sfx" in ''|*[!0-9a-f]*) _sfx="" ;; esac
  fi
  [ -n "$_sfx" ] || _sfx=$(cat /etc/serial_number 2>/dev/null | tr -cd '0-9a-fA-F' | tr 'A-F' 'a-f' | sed 's/.*\(....\)$/\1/')
  # Do NOT persist a placeholder: if every source failed we want the next call to try again
  # rather than freezing ccpa-0000 into flash forever.
  [ -n "$_sfx" ] || { echo "ccpa-0000"; return 0; }
  echo "ccpa-$_sfx" > "$IDENT_FILE" 2>/dev/null
  echo "ccpa-$_sfx"
}

# Name the controller. The owned bt_on.sh has always done this; the mapped path did not, which
# left a non-IW416 unit advertising its chip default (an RTL8822CS came up as "RTK_BT_4.2") while
# its SoftAP was correctly named ccpa-<4hex> — one box, two identities.
#
# Two mechanisms, both needed: /etc/.custom_bluetooth_name is the flag bluetoothDaemon honours
# when it applies a custom name (without it the daemon BLANKS the adapter name during init), and
# the direct hciconfig set covers the case where the daemon is not managing us. The retry exists
# because the daemon can blank the name AFTER our first set — assert until it sticks.
bt_set_name() {  # $1 = hci dev
  _bn=$(box_name "$1")
  echo "$_bn" > /etc/.custom_bluetooth_name 2>/dev/null
  echo "$_bn" > /etc/bluetooth_name 2>/dev/null
  _n=0
  while [ "$_n" -lt 10 ]; do
    hciconfig "$1" name "$_bn" 2>/dev/null
    sleep 0.5
    _cur=$(timeout 5 hciconfig "$1" name 2>/dev/null | sed -n "s/.*Name: '\(.*\)'.*/\1/p")
    [ "$_cur" = "$_bn" ] && break
    _n=$((_n+1))
  done
  log "BT name=$_bn (asserted after $_n retries, now '${_cur:-unknown}')"
}

# Load THIS unit's WLAN driver and nothing else - no AP, no addressing. Factored out of
# wifi_ap_on because bt_on needs it too: on the parts whose vendor script makes BT attach wait
# for the WLAN interface, the BT-only bridge role (wifi_ap:false) never calls wifi_ap_on, so
# waiting for an interface is waiting for something nobody will ever create. See do_bt_on.
# Returns 0 if the driver is loaded (or was already), 1 if there is nothing to load.
wlan_driver_up() {
  [ -n "$RADIO_WLAN_INSMOD" ] || { log "no WLAN insmod mapping for this unit"; return 1; }
  [ -e "/tmp/${RADIO_WLAN_MODULES%% *}" ] || tar -xzf "$RADIO_KO_TARBALL" -C /tmp 2>/dev/null \
                                          || tar -xf "$RADIO_KO_TARBALL" -C /tmp 2>/dev/null
  log "wlan driver: $RADIO_WLAN_INSMOD"
  _old=$IFS; IFS=';'
  for _cmd in $RADIO_WLAN_INSMOD; do
    [ -n "$_cmd" ] || continue
    IFS=$_old
    # Idempotent: a module already in /proc/modules is success, not the "File exists" error
    # insmod would print. Both verbs must be safe to call repeatedly - the supervisor can invoke
    # them on any host-presence edge, and a driver already loaded is the common case.
    _mod=$(echo "$_cmd" | sed 's|.*/tmp/||; s/ .*//; s/\.ko$//')
    if [ -n "$_mod" ] && grep -q "^$_mod " /proc/modules 2>/dev/null; then
      log "  $_mod already loaded"
    else
      $_cmd 2>&1 | sed "s|^|$LOGP   |"
    fi
    IFS=';'
  done
  IFS=$_old
  # set_wifi_mac is the vendor's per-unit MAC applier and is kept by the base install's radio
  # guard on every variant. Harmless where absent.
  command -v set_wifi_mac >/dev/null 2>&1 && set_wifi_mac >/dev/null 2>&1
  return 0
}

# ---------------------------------------------------------------------------------------------
# SCO (HFP call audio). Re-apply THIS UNIT'S OWN post-attach SCO setup.
#
# WHY A SEPARATE VERB. `btd`'s `bt_bringup::bring_up` forces a DOWN->UP cycle on the
# controller (it is the only way the kernel re-sends its Set_Event_Mask, without which SSP pairing
# silently never reaches the host). This controller is hciattach'd over UART, so it carries
# HCI_QUIRK_RESET_ON_CLOSE and that `down` issues a real HCI_Reset -- discarding everything
# attach_bluetooth.sh set after ITS attach, the SCO setup included. For as long as nothing carried
# audio over SCO that was a documented, harmless side effect. Wireless Android Auto carries call
# and Assistant audio over HFP/SCO, so it is now a real fault, and the daemon calls this verb after
# every bring-up.
#
# The commands are the VENDOR'S, extracted per-branch by radio_detect.sh -- never composed here.
# On this unit's Realtek branch the vendor sets ONLY the SCO MTU and no routing command at all
# (Realtek routes SCO over HCI by default); on NXP and BCM4358 there is also a vendor-opaque
# routing command. Emitting one chip's routing command on another chip is precisely the failure
# the no-chipset-whitelist rule exists to prevent, so a branch we cannot resolve yields NO mapping
# and this verb reports `unsupported` (3) rather than guessing.
#
# The vendor writes `hci0` literally. Retarget it at the device we actually enumerated rather than
# firing a command at a node that may not exist -- the ONLY edit made to a captured line, and it is
# logged when it happens.
apply_sco() {  # $1 = hci dev; echoes what it did. 0 = applied, 3 = nothing to apply
  _sd=$1; _did=0
  for _sc in sco_mtu sco_route; do
    case "$_sc" in
      sco_mtu)   _cmd=$RADIO_BT_SCO_MTU_CMD ;;
      sco_route) _cmd=$RADIO_BT_SCO_ROUTE_CMD ;;
    esac
    [ -n "$_cmd" ] || continue
    case "$_cmd" in
      *" hci0"*|*" hci0")
        [ "$_sd" = hci0 ] || { log "retargeting the vendor $_sc line from hci0 to $_sd"
                               _cmd=$(echo "$_cmd" | sed "s/ hci0/ $_sd/g"); } ;;
    esac
    log "$_sc: $_cmd"
    $_cmd >/dev/null 2>&1 || log "  $_sc returned non-zero (non-fatal)"
    _did=1
  done
  [ "$_did" = 1 ] || return 3
  return 0
}

do_sco_on() {
  caps || { log "no descriptor - cannot restore SCO setup"; return 3; }
  _d=$(hci_dev 2>/dev/null) || {
    log "no HCI device - nothing to apply SCO setup to"; return 3; }
  if ! apply_sco "$_d"; then
    log "no SCO mapping could be extracted for chipset ${RADIO_SDIO_DEVICE:-unknown} - HFP call audio will not be restored after a controller reset"
    return 3
  fi
  # Read the controller back. `hciconfig <dev>` prints `SCO MTU: 240:32` and `Voice: 0x0060`; those
  # are the two facts an HFP call depends on, and a readback is the only thing that distinguishes
  # "applied" from "the command exited 0 and the controller ignored it".
  _rb=$(timeout 5 hciconfig "$_d" 2>/dev/null | tr -s ' ' | grep -o 'SCO MTU: [0-9]*:[0-9]*')
  log "SCO setup applied to $_d (${_rb:-SCO MTU unreadable})"
  return 0
}

# =============================================================================================
# WLAN
# =============================================================================================
do_wifi_ap_on() {
  caps || { publish "$WLAN_STATE" "result=unsupported" "detail=no-descriptor"; return 3; }

  if ap_running && wlan_iface >/dev/null 2>&1; then
    _i=$(wlan_iface)
    # A running hostapd is not "the right AP". wifi_ap_on used to return here and leave whatever
    # ssid= was last written in hostapd.conf — including the app's placeholder — while the beacon
    # stayed on the name hostapd loaded at start. 0x5703 reads the file. Equinox 2026-09-22: the
    # file said "ccpa" and the beacon was ccpa-fe01, so the phone never left the vehicle hotspot.
    _want=$(box_name)
    _have=$(sed -n 's/^ssid=//p' /etc/hostapd.conf 2>/dev/null | head -1 | tr -d '\r')
    if [ "$_have" = "$_want" ]; then
      log "already converged (AP running on $_i as $_want)"
      publish "$WLAN_STATE" "result=ok" "backend=$RADIO_BACKEND" "chipset=$RADIO_SDIO_DEVICE" \
              "ap_iface=$_i" "ap_ip=$(ap_ip_of "$_i")" "wlan_mac=$(cat /sys/class/net/$_i/address 2>/dev/null)"
      return 2
    fi
    log "AP up on $_i as ssid '${_have:-empty}', box name is '$_want' — restarting so the beacon matches"
    killall hostapd 2>/dev/null
    _n=0
    while ap_running && [ "$_n" -lt 25 ]; do _n=$((_n+1)); sleep 0.1; done
  fi

  case "$RADIO_BACKEND" in
    owned)
      # The IW416 baseline's own scripts. exec keeps the process tree, signals and exit code
      # identical to calling wlan_on.sh directly, so this path is a no-op refactor for that unit.
      log "backend=owned -> /script/wlan_on.sh"
      sh /script/wlan_on.sh; _rc=$?
      _i=$(wlan_iface 2>/dev/null) || _i=""
      [ "$_rc" = 0 ] && ap_running \
        && publish "$WLAN_STATE" "result=ok" "backend=owned" "ap_iface=${_i:-wlan0}" \
                   "ap_ip=$(ap_ip_of "${_i:-wlan0}")" "wlan_mac=$(cat /sys/class/net/${_i:-wlan0}/address 2>/dev/null)" \
        || publish "$WLAN_STATE" "result=fail" "backend=owned" "detail=wlan_on.sh rc=$_rc"
      return $_rc
      ;;
    mapped)
      [ -n "$RADIO_WLAN_INSMOD" ] || {
        log "no insmod mapping could be extracted for chipset $RADIO_SDIO_DEVICE"
        publish "$WLAN_STATE" "result=unsupported" "chipset=$RADIO_SDIO_DEVICE" "detail=no-mapping"
        return 3; }
      # Stage this unit's own modules. The tarball is the unit's, extracted to tmpfs - we never
      # write to flash here (the vendor path untars overlays into / on some variants; that is
      # provisioning, not bring-up, and it does not belong in a per-session call).
      [ -e "/tmp/${RADIO_WLAN_MODULES%% *}" ] || tar -xzf "$RADIO_KO_TARBALL" -C /tmp 2>/dev/null \
                                                || tar -xf "$RADIO_KO_TARBALL" -C /tmp 2>/dev/null
      wlan_driver_up || {
        publish "$WLAN_STATE" "result=fail" "backend=mapped" "chipset=$RADIO_SDIO_DEVICE" "detail=driver-load"
        return 1; }
      # Converge on the interface appearing, discovered by enumeration.
      _n=0; while [ "$_n" -lt 100 ]; do wlan_iface >/dev/null 2>&1 && break; _n=$((_n+1)); sleep 0.1; done
      _i=$(wlan_iface 2>/dev/null) || {
        log "driver loaded but no wireless interface appeared"
        publish "$WLAN_STATE" "result=fail" "backend=mapped" "chipset=$RADIO_SDIO_DEVICE" "detail=no-iface"
        return 1; }
      log "wlan interface $_i up (mac $(cat /sys/class/net/$_i/address 2>/dev/null))"

      # The AP layer is OURS on every variant - see the header. It carries no chipset content;
      # RADIO_WLAN_IF tells it which interface to configure, defaulting to wlan0 so the IW416
      # unit's behaviour is unchanged when this variable is absent.
      #
      # DELIBERATELY /script/radio_ap_up.sh AND NOT /script/start_bluetooth_wifi.sh. Every unit
      # already ships a VENDOR file at that second path, so testing it for existence proves the
      # file is there, not that it is ours - and running the vendor's copy on a stripped box is
      # destructive: with riddleBoxCfg removed it seds an empty wpa_passphrase into
      # /etc/hostapd.conf (persistent, on flash) and defaults WLANIP to 192.168.50.2, which is
      # NCM's own address, then repoints the DHCP pool onto the management subnet. An
      # unambiguous name is the guard: presence at this path means it is the owned layer.
      [ -x /script/radio_ap_up.sh ] || {
        log "driver is up but the owned AP layer (/script/radio_ap_up.sh) is not installed"
        log "  NOT falling back to /script/start_bluetooth_wifi.sh - that is the vendor's copy and"
        log "  on a stripped box it corrupts hostapd.conf and collides with the NCM subnet"
        publish "$WLAN_STATE" "result=fail" "backend=mapped" "ap_iface=$_i" "detail=no-owned-ap-layer"
        return 1; }
      # RADIO_BOX_NAME is resolved HERE, not inside the AP layer, so the SSID and the Bluetooth
      # name provably come from one decision rather than two derivations that can disagree.
      # The car's hotspot (T15, see its section): the scan or the join finishes before hostapd
      # starts; a join that fails leaves nothing running.
      hotspot_scan
      _ap_ch=""
      uplink_stop
      rm -f "$UPLINK_AP_IF_FILE" "$UPLINK_AP_CH_FILE"
      _i=$(wlan_iface)
      if uplink_plan; then
        # The access point takes the driver's other interface whether or not the join succeeds: on
        # the client's own interface, after a failed join, hostapd came up and no phone could join
        # it (bench 2026-11-16).
        _i=$UPLINK_AP_IF; echo "$_i" > "$UPLINK_AP_IF_FILE"
        # The handoff may read hostapd.conf before the join is done: name the remembered channel now.
        _ap_ch=$(uplink_remembered)
        if [ -n "$_ap_ch" ]; then
          echo "$_ap_ch" > "$UPLINK_AP_CH_FILE"
          sed -i "s/^channel=.*/channel=$_ap_ch/" /etc/hostapd.conf
        fi
        if uplink_join; then
          [ -n "$_ap_ch" ] && [ "$_ap_ch" != "$UPLINK_CH" ] && \
            scan_log "uplink: joined on channel $UPLINK_CH, not the remembered $_ap_ch: this handoff may have named the old one"
          _ap_ch=$UPLINK_CH; echo "$_ap_ch" > "$UPLINK_AP_CH_FILE"
          sed -i "s/^channel=.*/channel=$_ap_ch/" /etc/hostapd.conf
        fi
      fi
      ONDEMAND_WIFI=1 RADIO_WLAN_IF="$_i" RADIO_BOX_NAME="$(box_name)" RADIO_AP_CHANNEL="$_ap_ch" \
        sh /script/radio_ap_up.sh AP 2>&1 | sed "s/^/$LOGP   /"
      _n=0; while [ "$_n" -lt 50 ]; do ap_running && break; _n=$((_n+1)); sleep 0.2; done
      if [ -s "$UPLINK_JOINED_AT" ]; then
        if ap_running; then uplink_watch_start; else uplink_stop; fi
      fi
      if ap_running; then
        publish "$WLAN_STATE" "result=ok" "backend=mapped" "chipset=$RADIO_SDIO_DEVICE" \
                "ap_iface=$_i" "ap_ip=$(ap_ip_of "$_i")" "wlan_mac=$(cat /sys/class/net/$_i/address 2>/dev/null)"
        log "AP converged on $_i"
        return 0
      fi
      publish "$WLAN_STATE" "result=fail" "backend=mapped" "ap_iface=$_i" "detail=ap-did-not-start"
      log "AP did not start on $_i"
      return 1
      ;;
    *)
      log "NO BACKEND for wifi on this unit - wireless unavailable (wired projection unaffected)"
      publish "$WLAN_STATE" "result=unsupported" "chipset=${RADIO_SDIO_DEVICE:-unknown}" "detail=no-backend"
      return 3
      ;;
  esac
}

ap_ip_of() { ifconfig "$1" 2>/dev/null | sed -n 's/.*inet addr:\([0-9.]*\).*/\1/p' | head -1; }

do_wifi_ap_off() {
  caps || true
  # Chipset-neutral teardown. Deliberately NO module unload: whether rmmod is safe (and whether
  # it disturbs BT on a combo part) is chipset policy, and the supervisor's proven end-state for
  # BT is "attached but powered down" rather than unloaded.
  if [ "${RADIO_BACKEND:-none}" = owned ] && [ -x /script/wlan_off.sh ]; then
    log "backend=owned -> /script/wlan_off.sh"
    sh /script/wlan_off.sh; _rc=$?
    publish "$WLAN_STATE" "result=ok" "backend=owned" "detail=wlan_off rc=$_rc"
    return 0
  fi
  uplink_stop
  killall hostapd 2>/dev/null
  # Kill ONLY the AP's DHCP server. A killall udhcpd here would take down the NCM one serving
  # the management link - on a box with no UART that is the only way in, and it is exactly what
  # the vendor's close_bluetooth_wifi.sh does. Identify it by its CONFIG PATH, which is
  # unambiguous: the NCM server runs `busybox udhcpd -f /tmp/udhcpd_ncm.conf`.
  for _p in $(ps 2>/dev/null | grep -v grep | grep "udhcpd .*etc/udhcpd.conf" | awk '{print $1}'); do
    kill "$_p" 2>/dev/null
  done
  [ -e /var/run/udhcpd.pid ] && kill "$(cat /var/run/udhcpd.pid 2>/dev/null)" 2>/dev/null
  _i=$(wlan_iface 2>/dev/null) && ifconfig "$_i" down 2>/dev/null
  _n=0; while [ "$_n" -lt 25 ] && ap_running; do _n=$((_n+1)); sleep 0.2; done
  ap_running && { log "hostapd still running after teardown"; publish "$WLAN_STATE" "result=fail" "detail=ap-survived"; return 1; }
  rm -f "$WLAN_STATE"
  log "wifi AP down"
  return 0
}

# =============================================================================================
# BLUETOOTH
# =============================================================================================
do_bt_on() {
  caps || { publish "$BT_STATE" "result=unsupported" "detail=no-descriptor"; return 3; }

  _d=$(hci_dev 2>/dev/null) || _d=""
  # Only a POWERED-UP adapter can pass the confirmation: on a DOWN one every name read fails
  # instantly with ENETDOWN, so the confirmation's 5 tries x 2 s sleeps were 8 s of pure waiting
  # before the power-up branch below, paid on every session that starts from the supervisor's
  # between-sessions state (enigy/ocbm measured bt_on at 9.5 s from DOWN vs 0.4 s for the power-up).
  if [ -n "$_d" ] && bt_powered_up "$_d" && bt_responsive_confirmed "$_d"; then
    log "BT already converged — skipping preload"
    publish "$BT_STATE" "result=ok" "backend=$RADIO_BACKEND" "hci_dev=$_d" \
            "bt_mac=$(cat /sys/class/bluetooth/$_d/address 2>/dev/null)"
    return 2
  fi

  # POWER IT UP BEFORE CONCLUDING IT IS WEDGED. The supervisor's deliberate teardown end state is
  # "attached but powered down" (`hciconfig <dev> down`) - chosen precisely because re-attach is
  # the wedge-prone half. But a name read on a DOWN adapter fails immediately with ENETDOWN: it
  # never reaches the chip. So the check above misses on every teardown, and the code below would
  # then run the full destructive recovery - kill the helper, rmmod the line discipline, toggle
  # the reset GPIO, re-attach - against a perfectly healthy controller, once per session cycle,
  # when a single `hciconfig up` was the entire fix. Today's testing was all cold bring-up, which
  # never exercises this path.
  if [ -n "$_d" ]; then
    if bt_powered_up "$_d"; then
      log "$_d present but not answering - trying to power it up before assuming it is wedged"
    else
      log "$_d powered down (between-sessions state) - powering it up"
    fi
    hci_rt "$_d" up 2>/dev/null
    if bt_responsive_confirmed "$_d"; then
      log "$_d was only powered down - up and responsive, no reset needed"
      publish "$BT_STATE" "result=ok" "backend=$RADIO_BACKEND" "hci_dev=$_d" \
              "bt_mac=$(cat /sys/class/bluetooth/$_d/address 2>/dev/null)"
      return 0
    fi
    log "$_d still unresponsive after power-up - recovery is warranted"
  fi

  # Boot prewarm must never launch the owned vendor advertising daemons. Use only
  # the unit-derived attach mapping; an unresolved mapping falls back at host_present.
  if [ "${BT_PREWARM:-0}" = 1 ]; then
    [ -n "$RADIO_BT_ATTACH_CMD" ] || { log "boot prewarm unavailable — host-present fallback"; return 3; }
    RADIO_BACKEND=mapped
  fi
  case "$RADIO_BACKEND" in
    owned)
      log "backend=owned -> /script/bt_on.sh"
      sh /script/bt_on.sh; _rc=$?
      _d=$(hci_dev 2>/dev/null) || _d=""
      if [ -n "$_d" ] && bt_responsive_confirmed "$_d"; then
        publish "$BT_STATE" "result=ok" "backend=owned" "hci_dev=$_d" \
                "bt_mac=$(cat /sys/class/bluetooth/$_d/address 2>/dev/null)"
        return 0
      fi
      # A failed owned-backend bring-up is the other case where the cached descriptor deserves a
      # second opinion (see caps()): arm the re-detect for the next call.
      RADIO_REDETECT=1
      export RADIO_REDETECT
      publish "$BT_STATE" "result=fail" "backend=owned" "detail=bt_on.sh rc=$_rc, not responsive"
      return 1
      ;;
    mapped)
      [ -n "$RADIO_BT_ATTACH_CMD" ] || {
        log "no attach mapping could be extracted for chipset $RADIO_SDIO_DEVICE"
        publish "$BT_STATE" "result=unsupported" "chipset=$RADIO_SDIO_DEVICE" "detail=no-mapping"
        return 3; }

      # Ordering constraint, discovered not assumed: some parts wedge if BT attaches before the
      # WLAN driver is up (the vendor encodes this as a wait loop inside its attach script, and
      # radio_detect.sh reports its presence). Honour it rather than re-deriving which chips care.
      # LOAD the WLAN driver, do not merely wait for it. The constraint these chips impose is
      # "the WLAN driver must be up before BT attaches" - on 0xc822 because attaching first can
      # make WiFi fail to start, on SD8987 because it hangs the chip outright, which is why the
      # vendor there GUARDS the attach on the interface existing rather than proceeding.
      #
      # The previous version only polled for an interface and then attached anyway, quoting the
      # vendor's own timeout behaviour. That excuse does not survive the bridge role: with
      # wifi_ap:false the supervisor never calls wifi_ap_on, so nothing ever loads the driver and
      # the poll is guaranteed to time out - we would attach, every time, into the exact ordering
      # the constraint exists to forbid, on the deployment where BT is the entire product.
      #
      # Loading the driver is not an AP: no addressing, no hostapd, nothing the bridge role
      # objects to. If it cannot be loaded we fail honestly rather than wedge the chip.
      if [ "${RADIO_BT_AFTER_WLAN:-0}" = 1 ] && ! wlan_iface >/dev/null 2>&1; then
        log "chipset requires the WLAN driver before BT attach - loading it (driver only, no AP)"
        if wlan_driver_up; then
          _n=0; while [ "$_n" -lt 120 ]; do wlan_iface >/dev/null 2>&1 && break; _n=$((_n+1)); sleep 0.1; done
        fi
        if ! wlan_iface >/dev/null 2>&1; then
          log "  WLAN interface never appeared - refusing to attach BT into a documented wedge"
          publish "$BT_STATE" "result=fail" "backend=mapped" "chipset=$RADIO_SDIO_DEVICE" \
                  "detail=wlan-required-but-unavailable"
          return 1
        fi
        log "  WLAN interface $(wlan_iface) up - BT attach may proceed"
      fi

      [ -e "/tmp/$RADIO_BT_LDISC_KO" ] || tar -xzf "$RADIO_KO_TARBALL" -C /tmp 2>/dev/null \
                                       || tar -xf "$RADIO_KO_TARBALL" -C /tmp 2>/dev/null

      # LAST-RESORT LDISC RECOVERY. Everything above depends on the DESCRIPTOR having named the
      # module, and the descriptor lives at /tmp/radio_caps -- on a tmpfs that every power loss
      # wipes. The box is USB-powered, so unplugging it to move it between the bench and the head
      # unit IS a power loss: each move starts from an empty /tmp. `caps()` re-derives the
      # descriptor by running /script/radio_detect.sh, so if that script is absent, unreadable, or
      # its extraction yields nothing, RADIO_BT_LDISC_KO comes back EMPTY -- and this whole block
      # then no-ops. The supervisor invokes us as `sh /script/radio_hal.sh bt_on 2>&1 | tee -a
      # /tmp/box.log >/tmp/bt.log` inside a detached wrapper and never reads the exit status, so
      # the failure is completely
      # silent: OCBM, MFi and CT_SUBSCRIBE all succeed, and Bluetooth simply never exists.
      #
      # Diagnosed on hardware 2026-08-28: no hci0, no pairing, and the only visible symptom was
      # hciattach reporting "Can't set line discipline: Invalid argument" -- three steps downstream
      # of the real cause, and directly beneath a flawless firmware download.
      #
      # So: if the descriptor did not name a line discipline, find one in the tarball ourselves.
      # This is deliberately independent of radio_detect.sh -- a recovery path that depends on the
      # thing that failed is not a recovery path.
      if [ -z "$RADIO_BT_LDISC_KO" ] || [ ! -e "/tmp/$RADIO_BT_LDISC_KO" ]; then
        for _cand in /tmp/*hci_uart*.ko; do
          [ -e "$_cand" ] || continue
          RADIO_BT_LDISC_KO=${_cand##*/}
          log "descriptor named no BT line discipline - recovered $RADIO_BT_LDISC_KO from $RADIO_KO_TARBALL"
          break
        done
      fi

      if [ -n "$RADIO_BT_LDISC_KO" ] && [ -e "/tmp/$RADIO_BT_LDISC_KO" ]; then
        grep -q "^${RADIO_BT_LDISC_KO%.ko} " /proc/modules 2>/dev/null \
          || insmod "/tmp/$RADIO_BT_LDISC_KO" 2>/dev/null
      fi

      # And SAY SO if the discipline still is not registered. hciattach cannot create hci0 without
      # it, so continuing quietly here is what turned a one-line fault into a multi-hour hunt.
      if ! grep -q "^n_hci" /proc/tty/ldiscs 2>/dev/null; then
        log "FATAL: no n_hci line discipline registered (ldisc_ko='${RADIO_BT_LDISC_KO:-<unset>}',"
        log "       tarball='${RADIO_KO_TARBALL:-<unset>}'). hciattach WILL fail EINVAL and no hci0"
        log "       will appear. Check that /script/radio_detect.sh exists and that the tarball"
        log "       contains an hci_uart module."
      fi

      [ -n "$RADIO_BT_PRELOAD" ] && [ -n "${RADIO_BT_PRELOAD_CMD:-}" ] && {
        log "firmware preload: $RADIO_BT_PRELOAD_CMD"; $RADIO_BT_PRELOAD_CMD >/dev/null 2>&1; }

      # NEVER launch an attach while another helper still owns the UART. Learned on hardware:
      # a controller can sit at `UP RUNNING` and still be wedged (name read times out), so we
      # correctly decide to re-attach - but if the previous helper is alive it still holds
      # /dev/ttymxc2, and the second attach's H5 SYNC collides with the first's link state:
      #   Realtek Bluetooth WARN: OP_H5_SYNC Transmission timeout
      #   Realtek Bluetooth ERROR: Retransmission exhausts
      # which leaves the chip deader than it started. A stale helper is not a reason to skip the
      # attach - it is a reason to reclaim the port FIRST. This is the same reclaim the owned
      # attach path does with `killall hciattach` before re-attaching.
      # RECLAIM-RESET-ATTACH, retried. Three lessons, each paid for on hardware:
      #
      #  1. Never attach while another helper owns the UART. A controller can sit at `UP RUNNING`
      #     and still be wedged (name read times out), so re-attaching is right - but the stale
      #     helper still holds /dev/ttymxc2 and the new H5 SYNC collides with the old link state
      #     ("OP_H5_SYNC Transmission timeout" -> "Retransmission exhausts"), leaving the chip
      #     deader than before.
      #  2. Killing the helper is not enough: the line-discipline MODULE holds the stale H5 state.
      #     Both the vendor's reset_bluetooth() and our own reset_bt() rmmod it before the reset,
      #     and a reset without that step does not revive the chip - measured.
      #  3. The reset line is asserted HIGH then released LOW (1 -> 0). Both implementations agree;
      #     driving it the other way is a no-op that looks like a reset in the log.
      #
      # One attempt is not enough either - the owned path loops reset+attach+responsiveness, so
      # this does too. Everything here is derived from the mapping, so no chip name appears.
      _helper=${RADIO_BT_ATTACH_CMD%% *}
      _ldisc=${RADIO_BT_LDISC_KO%.ko}
      bt_reclaim_reset() {
        killall "$_helper" 2>/dev/null
        _k=0; while [ "$_k" -lt 20 ] && { pgrep -x "$_helper" >/dev/null 2>&1 || ps | grep -v grep | grep -qw "$_helper"; }; do
          _k=$((_k+1)); sleep 0.25
        done
        # ESCALATE. A helper that ignored SIGTERM still owns /dev/ttymxc2, and continuing to
        # rmmod the line discipline and re-attach underneath it recreates the very H5 collision
        # this function exists to clear. The owned bt_off path already treats -9 on the helper as
        # safe; only the CONTROLLER must never be SIGKILLed mid-bring-up, not the attach process.
        if pgrep -x "$_helper" >/dev/null 2>&1 || ps | grep -v grep | grep -qw "$_helper"; then
          log "  $_helper ignored SIGTERM - escalating to -9 before touching the line discipline"
          killall -9 "$_helper" 2>/dev/null
          _k=0; while [ "$_k" -lt 12 ] && { pgrep -x "$_helper" >/dev/null 2>&1 || ps | grep -v grep | grep -qw "$_helper"; }; do
            _k=$((_k+1)); sleep 0.25
          done
        fi
        [ -n "$_ldisc" ] && grep -q "^$_ldisc " /proc/modules 2>/dev/null && { sleep 1; rmmod "$_ldisc" 2>/dev/null; }
        if [ -e /sys/class/gpio/gpio1/value ]; then
          echo 1 > /sys/class/gpio/gpio1/value 2>/dev/null; sleep 0.2
          echo 0 > /sys/class/gpio/gpio1/value 2>/dev/null; sleep 0.5
        fi
        [ -n "$RADIO_BT_LDISC_KO" ] && [ -e "/tmp/$RADIO_BT_LDISC_KO" ] \
          && { grep -q "^$_ldisc " /proc/modules 2>/dev/null || insmod "/tmp/$RADIO_BT_LDISC_KO" 2>/dev/null; }
      }

      # Clause 1: the attach runs detached so a helper that blocks cannot hang this verb, but we
      # DO NOT return until it converges. That is the whole difference from the vendor path,
      # which forks the attach and returns immediately.
      _try=0; _d=""
      while [ "$_try" -lt 4 ]; do
        _try=$((_try+1))
        if [ "$_try" -gt 1 ] || pgrep -x "$_helper" >/dev/null 2>&1 || ps | grep -v grep | grep -qw "$_helper"; then
          log "reclaim+reset (attempt $_try)"; bt_reclaim_reset
        fi
        log "attach attempt $_try: $RADIO_BT_ATTACH_CMD"
        setsid $RADIO_BT_ATTACH_CMD </dev/null >/tmp/radio_bt_attach.log 2>&1 &
        _n=0
        while [ "$_n" -lt 30 ]; do
          _d=$(hci_dev 2>/dev/null) && bt_responsive "$_d" && break
          _n=$((_n+1)); sleep 0.5
        done
        _d=$(hci_dev 2>/dev/null) || _d=""
        [ -n "$_d" ] && bt_responsive "$_d" && break
        log "  attempt $_try did not converge"
      done
      if [ -n "$_d" ] && bt_responsive "$_d"; then
        # Vendor SCO tuning where the mapping carried it; non-fatal. Was a HARDCODED
        # `scomtu 240:32` despite this comment -- which is the Realtek/NXP value and nobody
        # else's, and which skipped the routing command the NXP and BCM4358 branches need.
        # Now the extracted per-branch mapping, same as `sco_on`.
        apply_sco "$_d" >/dev/null 2>&1 || log "no SCO mapping for this chipset (HFP call audio unavailable)"
        bt_set_name "$_d"
        publish "$BT_STATE" "result=ok" "backend=mapped" "chipset=$RADIO_SDIO_DEVICE" "hci_dev=$_d" \
                "bt_mac=$(cat /sys/class/bluetooth/$_d/address 2>/dev/null)"
        log "BT converged: $_d responsive on attempt $_try"
        return 0
      fi
      log "BT attach did not converge in 60s (device ${_d:-absent}); last log:"
      # radio_bt_attach.log stays a small `>`-truncated status file: it is written by a
      # setsid-backgrounded attach daemon, and piping that daemon's own stdout would change its
      # lifecycle. Only this readback (the tail below) is universal-logged, via `tee -a`.
      tail -5 /tmp/radio_bt_attach.log 2>/dev/null | sed "s/^/$LOGP   /" | tee -a /tmp/box.log
      # A non-converging bring-up is the one case where the cached descriptor deserves a second
      # opinion: caps() no longer re-detects on the happy path, so a stale or half-written
      # /tmp/radio_caps would otherwise be trusted forever. Arm the re-detect for the NEXT call.
      RADIO_REDETECT=1
      export RADIO_REDETECT
      log "  arming RADIO_REDETECT=1 so the next bring-up re-derives the descriptor"
      publish "$BT_STATE" "result=fail" "backend=mapped" "chipset=$RADIO_SDIO_DEVICE" "detail=no-convergence"
      return 1
      ;;
    *)
      log "NO BACKEND for bt on this unit - wireless unavailable (wired projection unaffected)"
      publish "$BT_STATE" "result=unsupported" "chipset=${RADIO_SDIO_DEVICE:-unknown}" "detail=no-backend"
      return 3
      ;;
  esac
}

do_bt_prewarm() {
  # Checked under the BT lock: an early subscribe may have won the lock before
  # this detached boot job. Never reset a controller now owned by a session.
  [ "$(cat /tmp/host_present 2>/dev/null)" = 1 ] && return 2
  rm -f /tmp/.radio_bt_prewarmed
  BT_PREWARM=1
  do_bt_on
  _warm_rc=$?
  case "$_warm_rc" in
    0|2) ;;
    *) log "boot BT attach failed rc=$_warm_rc — retry on host_present"; return "$_warm_rc" ;;
  esac
  _warm_dev=$(hci_dev 2>/dev/null) || return 1
  # Initialize the kernel event mask here, not on the subscribe critical path.
  # No stock advertising daemons were started by the mapped attach path.
  hci_scan_off "$_warm_dev" || return 1
  hci_rt "$_warm_dev" down 2>/dev/null || return 1
  hci_rt "$_warm_dev" up 2>/dev/null || return 1
  hci_scan_off "$_warm_dev" || return 1
  apply_sco "$_warm_dev" >/dev/null 2>&1 || true
  bt_responsive_confirmed "$_warm_dev" || return 1
  echo "$_warm_dev" > /tmp/.radio_bt_prewarmed
  _warm_ms=$(awk '{printf "%.0f", $1 * 1000}' /proc/uptime)
  log "BT pre-warmed at boot +$_warm_ms"
  return 0
}

do_bt_off() {
  rm -f /tmp/.radio_bt_prewarmed
  # End state: powered down but STILL ATTACHED. Never rmmod the line discipline and never -9 the
  # attach helper - re-attach is the wedge-prone half, which is why the supervisor stops at
  # `hciconfig down` rather than calling bt_off.sh.
  _d=$(hci_dev 2>/dev/null) || { rm -f "$BT_STATE"; return 0; }
  hci_scan_off "$_d"
  hci_drop_acls "$_d"
  hci_rt "$_d" down 2>/dev/null
  rm -f "$BT_STATE"
  log "BT $_d powered down (attach left in place)"
  return 0
}

# =============================================================================================
case "$VERB" in
  probe)
    caps || exit 3
    echo "chipset=$RADIO_SDIO_DEVICE vendor=$RADIO_SDIO_VENDOR chip=$RADIO_CHIP backend=$RADIO_BACKEND wireless=$RADIO_WIRELESS"
    echo "wlan_insmod=\"$RADIO_WLAN_INSMOD\" bt_attach=\"$RADIO_BT_ATTACH_CMD\" bt_after_wlan=$RADIO_BT_AFTER_WLAN"
    [ "$RADIO_WIRELESS" = yes ] && exit 0 || exit 3 ;;
  status)
    caps || exit 3
    _i=$(wlan_iface 2>/dev/null) || _i=""
    _d=$(hci_dev 2>/dev/null)    || _d=""
    # Three-valued per radio: absent (no driver) / down / up. A cold box is "absent", which is
    # normal on this platform - radios load on demand - and must never read as failure.
    echo "wlan=$([ -z "$_i" ] && echo absent || { ap_running && echo up || echo down; }) iface=${_i:-none}"
    echo "bt=$([ -z "$_d" ] && echo absent || { bt_responsive_confirmed "$_d" && echo up || echo down; }) hci=${_d:-none}"
    [ -f "$WLAN_STATE" ] && sed 's/^/wlan_state: /' "$WLAN_STATE"
    [ -f "$BT_STATE" ]   && sed 's/^/bt_state:   /' "$BT_STATE"
    exit 0 ;;
  wifi_ap_on)  lock_take wlan || exit 1; do_wifi_ap_on;  _r=$?; lock_drop wlan; exit $_r ;;
  wifi_ap_off) lock_take wlan || exit 1; do_wifi_ap_off; _r=$?; lock_drop wlan; exit $_r ;;
  bt_prewarm)  lock_take bt   || exit 1; do_bt_prewarm;  _r=$?; lock_drop bt;   exit $_r ;;
  bt_on)       lock_take bt   || exit 1; do_bt_on;       _r=$?; lock_drop bt;   exit $_r ;;
  bt_off)      lock_take bt   || exit 1; do_bt_off;      _r=$?; lock_drop bt;   exit $_r ;;
  # Best-effort and idempotent: the daemon calls it after every bring-up DOWN->UP, and a unit
  # whose branch carries no SCO mapping must report that (3) rather than fail the caller.
  sco_on)      lock_take bt   || exit 1; do_sco_on;      _r=$?; lock_drop bt;   exit $_r ;;
  *) echo "$LOGP usage: radio_hal.sh probe|status|wifi_ap_on|wifi_ap_off|bt_prewarm|bt_on|bt_off|sco_on" >&2; exit 64 ;;
esac
