#!/bin/sh
########################
# radio_detect.sh - resolve THIS unit's WLAN/BT platform, read-only, and write a sourceable
# descriptor to /tmp/radio_caps. Run at boot before anything wants a radio, and again after a
# driver load (the post-load facts are the ones nobody can predict).
#
# WHY THIS EXISTS. CCPA ships at least six WLAN/BT parts (RTL8822BS/CS, RTL8733BS, BCM4354/4335,
# BCM4358, NXP SD8987, NXP IW416) and - per the resources repo's
# 01_Firmware_Architecture/device_variants_and_conversion.md - ONLY the driver tarball for a
# unit's own chip ships in its rootfs. There is no fallback set. So every layer above this one
# has to stop hardcoding one chip's names and paths, and instead ask.
#
# THE RULE THIS SCRIPT OBEYS (same as tools/ncm_base_install.sh): never branch on a chipset
# whitelist for BEHAVIOUR - an unlisted variant falls off the end of one and gets nothing. The
# chip-name table below is for HUMANS and logs only; nothing acts on it. Behaviour is derived
# from what the unit itself carries:
#
#   the SDIO id          - hardware ground truth, readable before any driver loads
#   /lib/firmware/<tree> - exactly one tree per unit, so the tree names the vendor
#   the attach helper    - rtk_hciattach XOR brcm_patchram_plus XOR hciattach+fw_loader_linux
#   /script/init_bluetooth_wifi.sh - the VENDOR's own per-SDIO-id dispatcher, which every unit
#                          ships and which knows that unit's exact insmod/attach lines
#
# The vendor dispatcher is the authority for the bottom half (load the driver, attach the
# controller) precisely because it encodes per-chip knowledge we cannot re-derive - e.g. on
# 0xc822 and SD8987 the BT attach must wait for wlan0 or the chip wedges. We own everything
# ABOVE the driver; see radio_hal.sh for why delegating the vendor's AP/network half is not
# merely inadequate but destructive on a stripped box.
#
# DO NOT make this script mutate anything. It must be safe to run at any time, in any state,
# including from a diagnostic shell mid-session. Loading a driver to "find out" is exactly the
# side effect callers rely on not happening.
########################
set -u
OUT=${RADIO_CAPS:-/tmp/radio_caps}

# ---- 1. hardware identity -----------------------------------------------------------------
# GLOB the sdio device node. The vendor dispatcher hardcodes mmc0:0001:1 (init_bluetooth_wifi.sh
# line 21); that path is an enumeration accident, not a guarantee, and a unit that enumerates
# differently would silently read nothing and fall into the "unknown chip" branch.
SDIO_NODE=""
for d in /sys/bus/sdio/devices/*/; do [ -e "$d/device" ] && { SDIO_NODE="${d%/}"; break; }; done
SDIO_DEV=""; SDIO_VEN=""; SDIO_CLS=""
if [ -n "$SDIO_NODE" ]; then
  SDIO_DEV=$(cat "$SDIO_NODE/device" 2>/dev/null)
  SDIO_VEN=$(cat "$SDIO_NODE/vendor" 2>/dev/null)
  SDIO_CLS=$(cat "$SDIO_NODE/class"  2>/dev/null)
fi

# Human-readable only. NOTHING below branches on CHIP - if this says "unknown" the unit still
# works, because every decision after this point comes from the filesystem, not this table.
case "$SDIO_DEV" in
  0xb822) CHIP=realtek_rtl8822bs ;;
  0xc822) CHIP=realtek_rtl8822cs ;;
  0xb733) CHIP=realtek_rtl8733bs ;;
  0x4354|0x4335) CHIP=broadcom_bcm4354 ;;
  0x4358|0xaa31) CHIP=broadcom_bcm4358 ;;
  0x9149|0x9141) CHIP=nxp_sd8987 ;;
  0x9159) CHIP=nxp_iw416 ;;
  "")     CHIP=no_sdio_radio ;;
  *)      CHIP=unknown ;;
esac

# ---- 2. the unit's own support set ---------------------------------------------------------
# Exactly one firmware tree ships per unit, so its name identifies the vendor without a table.
FW_TREE=""
for t in /lib/firmware/*/; do
  [ -d "$t" ] || continue
  case "${t%/}" in */rtlbt|*/nxp|*/bcm) FW_TREE="${t%/}"; break ;; esac
done
[ -n "$FW_TREE" ] || { for t in /lib/firmware/*/; do [ -d "$t" ] && { FW_TREE="${t%/}"; break; }; done; }
KO_TARBALL=$(ls "$FW_TREE"/*_ko.tar.gz 2>/dev/null | head -1)

# ---- 3. BT attach helper -------------------------------------------------------------------
# Most-specific first. Helper PRESENCE is discriminating; general binary presence is NOT - this
# Realtek unit ships /usr/sbin/wl (a Broadcom-flavoured tool) and supplicant configs with no
# supplicant binary, so never infer the chip from a non-attach binary.
BT_ATTACH=""
for h in rtk_hciattach brcm_patchram_plus hciattach; do
  [ -x "/usr/sbin/$h" ] && { BT_ATTACH=$h; break; }
done
BT_PRELOAD=""
[ -x /usr/sbin/fw_loader_linux ] && BT_PRELOAD=fw_loader_linux

# ---- 4. which bring-up path this unit actually carries -------------------------------------
# Resolution order, and the reason for it:
#   owned  - this unit got the project's own scripts (the IW416 baseline). Proven, keep using.
#   vendor - the unit's factory dispatcher survived the strip (ncm_base_install.sh's is_radio()
#            guard protects it by explicit basename). It is the only on-unit-correct source for
#            five of the six variants.
#   none   - no path. Report it loudly; wireless is unavailable. WIRED projection is unaffected,
#            it never touches a radio.
BACKEND=none
if [ -f /script/wlan_on.sh ] && [ -f /script/bt_on.sh ]; then
  BACKEND=owned
elif [ -f /script/init_bluetooth_wifi.sh ] && [ -f /script/attach_bluetooth.sh ]; then
  BACKEND=mapped
fi
# load_bluetooth_wifi.sh is deliberately NOT accepted as a dispatcher: despite the generic name
# it is Realtek-only (hardcodes rtk_hciattach and 88x2bs.ko), so treating it as one would hand a
# Broadcom unit a Realtek bring-up.

WIRELESS=no
[ "$BACKEND" != none ] && [ -n "$KO_TARBALL" ] && WIRELESS=yes

# ---- 4b. ADOPT THE VENDOR'S MAPPING, NOT THEIR MECHANISM -----------------------------------
# BACKEND=mapped means: this unit's own dispatcher is the authority for WHAT to run, and we
# extract those command lines here. It does NOT mean we execute the dispatcher. Running it
# wholesale would re-import the very defects the owned scripts exist to fix:
#   - it forks BT attach and returns (`/script/attach_bluetooth.sh &`), which is exactly the
#     uncoordinated double-bring-up docs/wireless/01_BT_AND_RADIO.md recorded fighting itself for 7+ minutes;
#   - the Broadcom branches background brcm_patchram_plus the same way;
#   - the SD8987 branch can `reboot` the box from inside a radio bring-up, bypassing the
#     supervisor's reboot budget;
#   - it untars overlays into `/` and its BT health check is object-existence, which a dead
#     chip passes (docs/wireless/01_BT_AND_RADIO.md again).
# The per-chip command lines are the vendor's OBSERVATIONS - fleet-deployed and working - so we
# take them verbatim. The surrounding control flow is their CHOICE, and we do not.
#
# The extraction carries no chipset table: we intersect the modules in THIS unit's own tarball
# with the dispatcher's own insmod lines. A part nobody anticipated still resolves, because the
# unit ships a dispatcher that knows its own silicon and a tarball that names its own modules.
# REFUSE TO EMIT WHAT WE CANNOT FAITHFULLY EXECUTE. A trailing `&` is stripped rather than
# rejected: backgrounding is the vendor's control-flow choice, and radio_hal supplies its own
# detached-and-converged discipline in its place.
#
# Closed-form lines (Realtek insmod, hciattach) are taken verbatim. Lines that still carry `$`,
# backticks or quotes AFTER the step below are refused: writing those into /tmp/radio_caps makes
# `. "$CAPS"` abort under `set -u` with status 2, which radio_hal defines as already converged
# (docs/wireless/01_BT_AND_RADIO.md §6d). A mapping we cannot execute must become NO mapping.
#
# NXP and Broadcom insmod lines are not closed-form — they carry variables assigned earlier in
# the SAME sdioCardID branch (`nxpWiFiConfig=nxp/wifi_mod_para.conf`, `bcmWiFiFirmware=...`).
# Capturing them verbatim was the half-mapping that loaded mlan.ko and dropped moal.ko, so the
# WLAN interface never appeared. The documented fix is to slice THIS unit's own branch and
# resolve that branch's literal `var=value` assignments (no chipset table, no executing the
# dispatcher). Leftover command substitution (`bcmBTMac=\`set_wifi_mac ...\``) still refuses,
# which is why Broadcom BT attach stays unsupported until that observation has a literal form.
safe_cmd() {  # stdin -> stdout, empty if the line cannot be executed as captured
  _c=$(cat)
  _c=$(echo "$_c" | sed 's/[[:space:]]*&[[:space:]]*$//')          # vendor's backgrounding
  case "$_c" in
    *'$'*|*'`'*|*'"'*|*"'"*) echo "" ;;   # unresolved substitution or quoting we cannot honour
    *) echo "$_c" ;;
  esac
}

# After $var expansion, vendor quotes around a single token (`"mod_para=nxp/wifi_mod_para.conf"`)
# are shell syntax, not part of the argument. radio_hal runs the mapping with unquoted `$_cmd`,
# so a leftover quote character would reach insmod as a literal. Strip only simple quoted
# tokens that contain no whitespace / `$` / backticks; anything else stays for safe_cmd to refuse.
unquote_simple() {
  sed 's/"\([^"$`'"'"'[:space:]]*\)"/\1/g; s/'"'"'\([^"$`'"'"'[:space:]]*\)'"'"'/\1/g'
}

# Slice a vendor dispatcher to THIS unit's own sdioCardID branch. Nesting-aware: the branch
# ends at the next elif/else/fi AT ITS OWN LEVEL. Counting matters -- the NXP attach branches
# close an inner `if [ $configBurned -eq 0 ]` BEFORE their scomtu line, so "stop at the first
# fi" drops the lines we came for. A file with no sdioCardID dispatch (the owned IW416 rewrite)
# is its own branch. An id the dispatcher never mentions yields nothing.
sdio_branch() {  # $1 = file
  [ -f "$1" ] || return 0
  if ! grep -q 'sdioCardID' "$1" 2>/dev/null; then
    cat "$1"; return 0
  fi
  [ -n "$SDIO_DEV" ] || return 0
  awk -v id="$SDIO_DEV" '
    !inb {
      if ($0 ~ /sdioCardID/ && index($0, "\"" id "\"") > 0 && $0 ~ /^[ \t]*(el)?if[ \t]/) { inb=1; depth=0 }
      next
    }
    {
      if (depth == 0 && $0 ~ /^[ \t]*(elif|else|fi)([ \t]|$)/) exit
      if ($0 ~ /^[ \t]*if[ \t]/) depth++
      else if ($0 ~ /^[ \t]*fi([ \t]|$)/) depth--
      print
    }' "$1"
}

# The vendor picks STA vs AP firmware with `test -e /usr/sbin/wpa_supplicant`. That is a
# file-existence observation on THIS box, not a chipset table. Honour the same test so the
# `if [ "$supportStaMode" -eq 1 ]` assignment inside the sliced branch resolves the way the
# dispatcher would have, without executing the dispatcher.
test -e /usr/sbin/wpa_supplicant && _SSM=1 || _SSM=0

# Expand $var / ${var} in a command using literal assignments from a sliced dispatcher branch.
# Assignments inside the vendor's supportStaMode if-blocks apply only when _SSM matches; other
# ifs (tarball overlays, ant_num) are not executed. Values that still contain `$` or backticks
# are skipped, so a later safe_cmd refusal stays honest.
expand_from_branch() {  # $1 = branch text, stdin = command
  _line=$(cat)
  # Must EXPORT: a `VAR=value printf | awk` prefix applies only to printf, so awk would
  # see an empty command and emit nothing. Unique names, unset on the way out.
  RADIO_EXPAND_CMD="$_line"
  RADIO_EXPAND_SSM="$_SSM"
  export RADIO_EXPAND_CMD RADIO_EXPAND_SSM
  printf '%s\n' "$1" | awk '
    BEGIN { ssm = ENVIRON["RADIO_EXPAND_SSM"] + 0; cmd = ENVIRON["RADIO_EXPAND_CMD"] }
    function maybe_assign(line,    n, v, eq) {
      sub(/^[ \t]+/, "", line)
      if (line !~ /^[A-Za-z_][A-Za-z0-9_]*=/) return
      eq = index(line, "="); n = substr(line, 1, eq - 1); v = substr(line, eq + 1)
      sub(/^[ \t]+/, "", v); sub(/[ \t]+$/, "", v)
      if (v ~ /\$|`/) return
      if (v ~ /^".*"$/ || v ~ /^'\''.*'\''$/) v = substr(v, 2, length(v) - 2)
      if (v ~ /\$|`/) return
      as[n] = v
    }
    function expand(s,    i, n, c, rest, name, out) {
      n = length(s); out = ""
      for (i = 1; i <= n; i++) {
        c = substr(s, i, 1)
        if (c == "$") {
          if (substr(s, i + 1, 1) == "{") {
            rest = substr(s, i + 2)
            if (match(rest, /^[A-Za-z_][A-Za-z0-9_]*}/)) {
              name = substr(rest, 1, RLENGTH - 1)
              if (name in as) { out = out as[name]; i = i + 1 + RLENGTH; continue }
            }
          } else {
            rest = substr(s, i + 1)
            if (match(rest, /^[A-Za-z_][A-Za-z0-9_]*/)) {
              name = substr(rest, 1, RLENGTH)
              if (name in as) { out = out as[name]; i = i + RLENGTH; continue }
            }
          }
        }
        out = out c
      }
      return out
    }
    {
      if ($0 ~ /^[ \t]*if[ \t].*supportStaMode/) {
        in_ssm = 1; ssm_depth = 0
        if ($0 ~ /-eq[ \t]*1/) ssm_skip = (ssm != 1)
        else if ($0 ~ /-eq[ \t]*0/) ssm_skip = (ssm != 0)
        else ssm_skip = 1
        next
      }
      if (in_ssm) {
        if ($0 ~ /^[ \t]*if[ \t]/) ssm_depth++
        else if ($0 ~ /^[ \t]*else([ \t]|$)/) { if (ssm_depth == 0) { ssm_skip = !ssm_skip; next } }
        else if ($0 ~ /^[ \t]*fi([ \t]|$)/) {
          if (ssm_depth == 0) { in_ssm = 0; ssm_skip = 0; next }
          ssm_depth--
        }
        if (ssm_skip) next
      }
      maybe_assign($0)
    }
    END { print expand(cmd) }
  '
  unset RADIO_EXPAND_CMD RADIO_EXPAND_SSM
}

BT_LDISC=""; WLAN_MODS=""
for m in $(tar tzf "$KO_TARBALL" 2>/dev/null | sed 's|.*/||' | grep '\.ko$'); do
  case "$m" in *hci_uart*) BT_LDISC=$m ;; *) WLAN_MODS="$WLAN_MODS $m" ;; esac
done
WLAN_MODS=${WLAN_MODS# }

# Slice once. Insmod, attach, preload and the WLAN-before-BT wait all live in several
# chipset branches with different contents -- a whole-file `head -1` (or a whole-file wait-loop
# grep) would hand this unit another chip's observation. Same rule as the SCO extraction below.
_WIFI_BRANCH=$(sdio_branch /script/init_bluetooth_wifi.sh)
_ATTACH_BRANCH=$(sdio_branch /script/attach_bluetooth.sh)

# Anchor on "insmod /tmp/" - the dispatcher's success/failure echo strings also contain the word
# insmod followed by the module name, and a greedy sed without this anchor picks the echo.
# If the branch names an insmod we cannot faithfully execute, drop the WHOLE WLAN mapping: a
# partial list (mlan without moal) is how this seam reported mapped success while the interface
# never appeared.
WLAN_INSMOD=""
_wlan_incomplete=0
for m in $WLAN_MODS; do
  _raw=$(printf '%s\n' "$_WIFI_BRANCH" \
      | grep -e "insmod /tmp/$m" | grep -v '^[[:space:]]*#' | head -1)
  [ -n "$_raw" ] || continue
  l=$(printf '%s\n' "$_raw" \
      | sed 's|.*\(insmod /tmp/[^&|;}]*\).*|\1|' | sed 's/[[:space:]]*$//' \
      | expand_from_branch "$_WIFI_BRANCH" | unquote_simple | safe_cmd)
  if [ -z "$l" ]; then
    _wlan_incomplete=1
    WLAN_INSMOD=""
    break
  fi
  WLAN_INSMOD="$WLAN_INSMOD$l
"
done
[ "$_wlan_incomplete" -eq 0 ] || WLAN_INSMOD=""

# Attach helper invocation, anchored at line start so `if [ -e ... ]` tests and echo lines can
# never be mistaken for the command itself. Taken from THIS unit's attach branch.
BT_ATTACH_CMD=""
[ -n "$BT_ATTACH" ] && BT_ATTACH_CMD=$(printf '%s\n' "$_ATTACH_BRANCH" \
                       | grep -E "^[[:space:]]*$BT_ATTACH " \
                       | head -1 | sed 's/^[[:space:]]*//' \
                       | expand_from_branch "$_ATTACH_BRANCH" | unquote_simple | safe_cmd)

# Firmware preload (IW416 `fw_loader_linux ...`). radio_hal.sh consumes RADIO_BT_PRELOAD_CMD
# but detection used to emit only the helper name, so mapped-path IW416 never ran it.
BT_PRELOAD_CMD=""
[ -n "$BT_PRELOAD" ] && BT_PRELOAD_CMD=$(printf '%s\n' "$_ATTACH_BRANCH" \
                       | grep -E "^[[:space:]]*$BT_PRELOAD " \
                       | head -1 | sed 's/^[[:space:]]*//' \
                       | expand_from_branch "$_ATTACH_BRANCH" | unquote_simple | safe_cmd)

# Ordering constraint: some parts wedge if BT attaches before the WLAN driver is up. The vendor
# encodes this as a wait loop inside the attach script; its presence in THIS unit's branch is
# the signal. A whole-file grep is wrong: the 0xc822 and SD8987 wait loops made every NXP/Realtek
# unit look like it needed WLAN first, including IW416 whose own branch attaches in parallel.
BT_AFTER_WLAN=0
printf '%s\n' "$_ATTACH_BRANCH" | grep -qE 'while \[ ! -e /sys/class/net/' && BT_AFTER_WLAN=1

# ---- 4c. THE SCO / HFP VOICE SETUP THIS UNIT'S OWN DISPATCHER APPLIES ------------------------
# The bring-up in btd forces a DOWN->UP cycle on the controller, and this controller
# is hciattach'd over UART, so it carries HCI_QUIRK_RESET_ON_CLOSE: the `down` makes the kernel
# issue a real HCI_Reset, which DISCARDS everything attach_bluetooth.sh set after its own attach --
# including the SCO setup HFP call audio needs (docs/wireless/01_BT_AND_RADIO.md "Accepted side
# effect"). Those lines are the vendor's OBSERVATIONS, per-chip and not re-derivable by us, so they
# are extracted here exactly like the insmod/attach mapping and re-applied by `radio_hal.sh sco_on`.
#
# SCOPE THE EXTRACTION TO THIS UNIT'S OWN BRANCH. Unlike the insmod and attach lines -- which are
# discriminated by the module tarball and by attach-helper presence -- the SCO lines appear in
# SEVERAL of the dispatcher's chipset branches with DIFFERENT contents:
#     0xb822/0xc822/0xb733 (Realtek):  scomtu only, no routing command at all
#     0x4358/0xaa31 (BCM4358):         hcitool -i hci0 cmd 0x3f 0x1c 0x01 0x02 0x00 0x00 0x00
#     0x9149/0x9141/0x9159 (NXP):      hcitool -i hci0 cmd 0x3f 0x1d 0x00
# A whole-file `head -1` would hand one chip another chip's vendor-opaque HCI command -- exactly
# the failure the no-chipset-whitelist rule exists to prevent, arrived at from the other side. So
# `sdio_branch` (above) selects the branch by THIS UNIT'S OWN SDIO id against the dispatcher's
# own if/elif chain: no table, no whitelist, and an id the dispatcher does not mention simply
# yields NOTHING (the seam then reports `unsupported`, honestly). A single-branch attach script
# -- the owned IW416 rewrite, which has no sdioCardID dispatch at all -- is its own branch.
attach_branch() { sdio_branch /script/attach_bluetooth.sh; }

# Strip a TRAILING comment before anything else. The owned rewrite annotates these lines inline
# (`hcitool -i hci0 cmd 0x3f 0x1d 0x00              # route SCO to HCI`) while the vendor puts the
# comment on the line above. `#` is only a comment marker in the SHELL'S INPUT -- the result of a
# variable expansion is never re-scanned for one -- so a captured trailing comment would reach
# hcitool as four extra positional arguments, not as a comment.
decomment() { sed 's/[[:space:]]*#.*$//; s/[[:space:]]*$//'; }

_SCO_BRANCH=$(attach_branch)
BT_SCO_MTU_CMD=$(echo "$_SCO_BRANCH" \
  | grep -E '^[[:space:]]*hciconfig[[:space:]]+[^[:space:]]+[[:space:]]+scomtu[[:space:]]' \
  | head -1 | sed 's/^[[:space:]]*//' | decomment | safe_cmd)
# The routing command is identified by the VENDOR'S OWN annotation ("route sco data to hci"),
# same line or the line above -- never by matching raw hcitool opcodes, which would be a chipset
# table in disguise. An unannotated variant yields nothing and the seam says so.
BT_SCO_ROUTE_CMD=$(echo "$_SCO_BRANCH" | awk '
  {
    if ($0 ~ /^[ \t]*hcitool[ \t].*[ \t]cmd[ \t]/ \
        && (tolower($0) ~ /#.*sco/ || tolower(prev) ~ /^[ \t]*#.*sco/)) {
      sub(/^[ \t]+/, ""); print; exit
    }
    prev = $0
  }' | decomment | safe_cmd)

# ---- 5. post-load facts: enumerate, never assume -------------------------------------------
# The WLAN interface name is an INSMOD PARAMETER that differs per chip - Realtek passes
# if2name=sta0, Broadcom iface_name=sta, and on Broadcom STA units wlan0 does not exist until
# `iw dev sta0 interface add wlan0` runs. So the name is discovered here, not declared upstream.
# A cfg80211/wext netdev is identifiable without knowing the driver: it has wireless/ or
# phy80211/ under its sysfs node.
WLAN_IF=""; WLAN_IFS=""
for n in /sys/class/net/*/; do
  [ -e "$n/wireless" ] || [ -e "$n/phy80211" ] || continue
  i=$(basename "$n"); WLAN_IFS="$WLAN_IFS $i"
  [ -n "$WLAN_IF" ] || WLAN_IF=$i
done
WLAN_IFS=${WLAN_IFS# }
WLAN_MAC=""
[ -n "$WLAN_IF" ] && WLAN_MAC=$(cat "/sys/class/net/$WLAN_IF/address" 2>/dev/null)

HCI_DEV=""
for b in /sys/class/bluetooth/hci*/; do [ -d "$b" ] && { HCI_DEV=$(basename "${b%/}"); break; }; done
BT_MAC=""
[ -n "$HCI_DEV" ] && BT_MAC=$(cat "/sys/class/bluetooth/$HCI_DEV/address" 2>/dev/null)

# ---- 6. emit ------------------------------------------------------------------------------
# Written via tmp+mv so a reader can never see a half-file. Consumers MUST treat a missing or
# malformed descriptor as "absent" and fall back to their compiled defaults, never crash on it.
{
  echo "RADIO_CHIP=$CHIP"
  echo "RADIO_SDIO_NODE=$SDIO_NODE"
  echo "RADIO_SDIO_VENDOR=$SDIO_VEN"
  echo "RADIO_SDIO_DEVICE=$SDIO_DEV"
  echo "RADIO_SDIO_CLASS=$SDIO_CLS"
  echo "RADIO_FW_TREE=$FW_TREE"
  echo "RADIO_KO_TARBALL=$KO_TARBALL"
  echo "RADIO_BT_ATTACH=$BT_ATTACH"
  echo "RADIO_BT_PRELOAD=$BT_PRELOAD"
  echo "RADIO_BT_PRELOAD_CMD=\"$BT_PRELOAD_CMD\""
  echo "RADIO_BT_UART=/dev/ttymxc2"
  echo "RADIO_BACKEND=$BACKEND"
  echo "RADIO_WLAN_MODULES=\"$WLAN_MODS\""
  echo "RADIO_BT_LDISC_KO=$BT_LDISC"
  echo "RADIO_BT_ATTACH_CMD=\"$BT_ATTACH_CMD\""
  echo "RADIO_BT_AFTER_WLAN=$BT_AFTER_WLAN"
  echo "RADIO_BT_SCO_MTU_CMD=\"$BT_SCO_MTU_CMD\""
  echo "RADIO_BT_SCO_ROUTE_CMD=\"$BT_SCO_ROUTE_CMD\""
  echo "RADIO_WLAN_INSMOD=\"$(echo "$WLAN_INSMOD" | sed '/^$/d' | tr '\n' ';')\""
  echo "RADIO_WIRELESS=$WIRELESS"
  echo "RADIO_WLAN_IF=$WLAN_IF"
  echo "RADIO_WLAN_IFS=\"$WLAN_IFS\""
  echo "RADIO_WLAN_MAC=$WLAN_MAC"
  echo "RADIO_HCI_DEV=$HCI_DEV"
  echo "RADIO_BT_MAC=$BT_MAC"
  echo "RADIO_DETECTED_AT=$(cut -d. -f1 /proc/uptime 2>/dev/null)"
} > "$OUT.new" 2>/dev/null && mv "$OUT.new" "$OUT"

[ "${1:-}" = -q ] || cat "$OUT"
# Exit 0 always: detection is diagnostic. "No radio" is a finding, not a failure, and this runs
# on the boot path where a non-zero exit would be mistaken for something worth reacting to.
exit 0
