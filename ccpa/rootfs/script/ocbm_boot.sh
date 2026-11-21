#!/bin/sh
########################
# ocbm_boot.sh - optionally announce as storage-only to GM AAOS, then bind OCBM, launch ocbmd,
# and start the session supervisor (IDLE). Runs from start_main_service.sh when neither ncm_only nor
# ncm_wifi is set (the OCBM appliance default). Backgrounds all work and exits 0 so it cannot block
# boot. The announce phase briefly presents a storage identity before OCBM; UART remains the recovery
# path. Device class is 0/0/0 (not 239/IAD). Binaries are FHS-installed (/usr/sbin).
########################
# SYNCHRONOUS, and deliberately OUTSIDE the subshell below: runMainProcess skips ARMadb only if
# this flag exists by the time start_main_service.sh reaches it (~line 74). We are invoked at its
# line 19 and return immediately, so anything we background is racing those ~55 lines. It used to
# be set inside the subshell *after* a `tar -xzf`, i.e. the race could be lost on a slow unpack --
# and losing it means ARMadb starts, clobbers our accessory gadget, and the host sees a SECOND
# enumeration with a DIFFERENT identity. Hosts that key a USB permission grant on the descriptors
# (Android) treat that as a different device. One touch, one identity, one enumeration.
touch /tmp/UDiskPassThroughMode
(
  L=/tmp/ocbm_boot.log
  export PATH=/usr/sbin:/usr/bin:/sbin:/bin:/tmp/bin:$PATH
  echo "[ocbm-boot] start uptime=$(cut -d. -f1 /proc/uptime)s" > "$L"
  echo "[ocbm-boot] start uptime=$(cut -d. -f1 /proc/uptime)s" >> /tmp/box.log
  rm -f /tmp/ocbm_hello_seen

  # Optional key=value config (not sourced as shell). Defaults are set before serial discovery so
  # an explicit usb_announce=0 remains authoritative after identity checks.
  USB_ANNOUNCE=1
  USB_ANNOUNCE_DWELL_MS=1500
  USB_ANNOUNCE_MEDIUM=image
  USB_ANNOUNCE_PID=2d06
  # The car permission dialog has run out the app's 30 s wait. Retrying the
  # announce at that same mark disconnects the device the driver is granting.
  USB_ANNOUNCE_RETRY_MS=75000
  # k × retry_ms after the first OCBM bind. 0 disables the watcher.
  # Default 0: GM admits the adapter on the boot announce, and a retry re-enumerates
  # the gadget under an open permission dialog, killing the grant the driver is
  # tapping. The bench can still set usb_announce_retries in /script/ocbm.conf.
  USB_ANNOUNCE_RETRIES=0
  # The network function beside OCBM (docs/ops/08_FUTURE_TASKS.md T15, 2026-11-15): none (the
  # default: this script then does exactly what it did before), eth or usb. A car's own system
  # claims a USB network device, as it does a USB drive, so an app that reaches the adapter over it
  # needs no USB permission and no Allow tap. `eth` gives the car a globally-administered address,
  # which makes Linux name its end eth<N> (what Android brings up as Ethernet); `usb` leaves the
  # driver's own locally-administered one, named usb<N>. `usb_net=eth-once` / `usb-once` in
  # /script/ocbm.conf present it for ONE start: the key is written back to 0 before the gadget
  # is armed, so a car that refuses the three-function device gets the usual device at the next
  # power-on with nobody touching the adapter.
  USB_NET=none
  USB_NET_ONCE=0
  SERIAL_PER_DEVICE=0
  bootlog() { echo "$*" >> "$L"; echo "$*" >> /tmp/box.log; }
  trim_ws() { printf '%s' "$1" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//'; }

  # The per-device serial is shared by every gadget phase. Prefer the box serial already used by
  # ocbmd, then the SoC serial, WLAN MAC, and /proc/cpuinfo; never accept the module placeholder.
  USB_SERIAL=$(trim_ws "$(cat /etc/serial_number 2>/dev/null)")
  [ "$USB_SERIAL" = 0123456789FEDCBA ] && USB_SERIAL=
  [ -n "$USB_SERIAL" ] && SERIAL_PER_DEVICE=1
  if [ -z "$USB_SERIAL" ]; then
    USB_SERIAL=$(trim_ws "$(cat /sys/devices/soc0/serial_number 2>/dev/null)")
    [ "$USB_SERIAL" = 0123456789FEDCBA ] && USB_SERIAL=
    [ -n "$USB_SERIAL" ] && SERIAL_PER_DEVICE=1
  fi
  if [ -z "$USB_SERIAL" ]; then
    for _net in /sys/class/net/*; do
      [ -d "$_net/wireless" ] || continue
      USB_SERIAL=$(trim_ws "$(cat "$_net/address" 2>/dev/null)")
      [ "$USB_SERIAL" = 0123456789FEDCBA ] && USB_SERIAL=
      [ -n "$USB_SERIAL" ] && { SERIAL_PER_DEVICE=1; break; }
    done
  fi
  if [ -z "$USB_SERIAL" ]; then
    USB_SERIAL=$(trim_ws "$(awk '/^Serial[[:space:]]*:/ { print $3; exit }' /proc/cpuinfo 2>/dev/null)")
    [ "$USB_SERIAL" = 0123456789FEDCBA ] && USB_SERIAL=
    [ -n "$USB_SERIAL" ] && SERIAL_PER_DEVICE=1
  fi
  if [ -z "$USB_SERIAL" ]; then
    echo "[ocbm-boot] E no per-device serial source; announce disabled to avoid matching the vendor-generic iSerial" >> "$L"
    USB_SERIAL=$(cat /sys/class/android_usb_accessory/android0/iSerial 2>/dev/null)
  fi
  echo "[ocbm-boot] serial=$USB_SERIAL" >> "$L"
  echo "[ocbm-boot] serial=$USB_SERIAL" >> /tmp/box.log
  printf '%s\n' "$USB_SERIAL" > /tmp/ocbm_usb_serial

  if [ -r /script/ocbm.conf ]; then
    while IFS= read -r cfgline || [ -n "$cfgline" ]; do
      case "$cfgline" in *=*) cfgkey=$(trim_ws "${cfgline%%=*}"); cfgval=$(trim_ws "${cfgline#*=}");; *) continue;; esac
      case "$cfgkey" in
        usb_announce) case "$cfgval" in 0|1) USB_ANNOUNCE=$cfgval;; *) echo "[ocbm-boot] W invalid usb_announce=$cfgval in /script/ocbm.conf; keeping $USB_ANNOUNCE" >> "$L";; esac ;;
        usb_announce_dwell_ms) case "$cfgval" in ''|*[!0-9]*) echo "[ocbm-boot] W invalid usb_announce_dwell_ms=$cfgval; keeping $USB_ANNOUNCE_DWELL_MS" >> "$L";; *) USB_ANNOUNCE_DWELL_MS=$cfgval;; esac ;;
        usb_announce_medium) case "$cfgval" in image|none) USB_ANNOUNCE_MEDIUM=$cfgval;; *) echo "[ocbm-boot] W invalid usb_announce_medium=$cfgval; keeping $USB_ANNOUNCE_MEDIUM" >> "$L";; esac ;;
        usb_announce_retry_ms) case "$cfgval" in ''|*[!0-9]*) echo "[ocbm-boot] W invalid usb_announce_retry_ms=$cfgval; keeping $USB_ANNOUNCE_RETRY_MS" >> "$L";; *) USB_ANNOUNCE_RETRY_MS=$cfgval;; esac ;;
        usb_announce_retries) case "$cfgval" in ''|*[!0-9]*) echo "[ocbm-boot] W invalid usb_announce_retries=$cfgval; keeping $USB_ANNOUNCE_RETRIES" >> "$L";; *) USB_ANNOUNCE_RETRIES=$cfgval;; esac ;;
        usb_net) case "$cfgval" in
            0) USB_NET=none; USB_NET_ONCE=0 ;;
            eth|usb) USB_NET=$cfgval; USB_NET_ONCE=0 ;;
            eth-once) USB_NET=eth; USB_NET_ONCE=1 ;;
            usb-once) USB_NET=usb; USB_NET_ONCE=1 ;;
            *) echo "[ocbm-boot] W invalid usb_net=$cfgval in /script/ocbm.conf; network function off" >> "$L" ;;
          esac ;;
      esac
    done < /script/ocbm.conf
  fi
  # Bound accidental config typos while retaining a generous bench dwell.
  [ "$USB_ANNOUNCE_DWELL_MS" -le 60000 ] 2>/dev/null || USB_ANNOUNCE_DWELL_MS=60000
  [ "$USB_ANNOUNCE_RETRY_MS" -ge 15000 ] 2>/dev/null || USB_ANNOUNCE_RETRY_MS=15000
  [ "$USB_ANNOUNCE_RETRY_MS" -le 300000 ] 2>/dev/null || USB_ANNOUNCE_RETRY_MS=300000
  [ "$USB_ANNOUNCE_RETRIES" -ge 0 ] 2>/dev/null || USB_ANNOUNCE_RETRIES=0
  [ "$USB_ANNOUNCE_RETRIES" -le 3 ] 2>/dev/null || USB_ANNOUNCE_RETRIES=3
  # One start only: put the key back BEFORE anything is presented, and present nothing if that
  # cannot be made to stick. A trial that could repeat by itself would strand a car that refuses it.
  if [ "$USB_NET_ONCE" = 1 ]; then
    sed -i 's/^[[:space:]]*usb_net[[:space:]]*=.*/usb_net=0/' /script/ocbm.conf 2>/dev/null
    sync
    if grep -q '^usb_net=0$' /script/ocbm.conf 2>/dev/null && ! grep -q 'usb_net=.*once' /script/ocbm.conf 2>/dev/null; then
      bootlog "[ocbm-boot] usb_net=$USB_NET for this start only; /script/ocbm.conf is back to usb_net=0"
    else
      bootlog "[ocbm-boot] W could not write usb_net=0 back; network function NOT presented"
      USB_NET=none
    fi
  fi
  # The address the car sees for the network function, for `eth`: six bytes from this unit's
  # serial, with the multicast and locally-administered bits of the first cleared.
  USB_NET_HOST_MAC=
  if [ "$USB_NET" = eth ]; then
    _h=$(printf '%s' "$USB_SERIAL" | md5sum 2>/dev/null | cut -c1-12)
    case "$_h" in
      [0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f])
        _o=$(printf '%02x' $(( 0x$(printf '%s' "$_h" | cut -c1-2) & 0xfc )))
        USB_NET_HOST_MAC="$_o:$(printf '%s' "$_h" | cut -c3-4):$(printf '%s' "$_h" | cut -c5-6):$(printf '%s' "$_h" | cut -c7-8):$(printf '%s' "$_h" | cut -c9-10):$(printf '%s' "$_h" | cut -c11-12)" ;;
      *) bootlog "[ocbm-boot] W no address could be made for usb_net=eth; network function off"; USB_NET=none ;;
    esac
  fi
  [ "$SERIAL_PER_DEVICE" = 1 ] || { [ "$USB_ANNOUNCE" = 1 ] && bootlog "[ocbm-boot] E announce disabled — no per-device serial (would match the vendor-generic iSerial)"; USB_ANNOUNCE=0; }
  uptime_ms() { awk '{ printf "%.0f", $1 * 1000 }' /proc/uptime; }
  sleep_ms() { _s=$(( $1 / 1000 )); _ms=$(( $1 % 1000 )); sleep "$(printf '%d.%03d' "$_s" "$_ms")"; }
  set_usb_identity() {
    [ "${2:-}" = no_unbind ] || echo 0 > "$A/enable"
    echo 0 > "$A/bDeviceClass"; echo 0 > "$A/bDeviceSubClass"; echo 0 > "$A/bDeviceProtocol"
    echo 1314 > "$A/idVendor"
    echo "$1" > "$A/idProduct"
    echo "$USB_SERIAL" > "$A/iSerial" 2>/dev/null || return 1
    [ "$(cat "$A/iSerial" 2>/dev/null)" = "$USB_SERIAL" ]
  }
  lun_root() {
    # quiet: clear_lun runs on a pure-accessory teardown, where lun0 may not exist.
    # A warning there is noise, not a failed announce.
    _quiet=${1:-}
    for _d in /sys/devices/soc0/soc.*/2100000.aips-bus/2184200.usb/ci_hdrc.1/gadget/lun0; do
      [ -d "$_d" ] && { echo "$_d"; return 0; }
    done
    [ "$_quiet" = quiet ] || bootlog "[ocbm-boot] W no ci_hdrc.1/gadget/lun0 node"
    return 1
  }
  clear_lun() { _lr=$(lun_root quiet); [ -n "$_lr" ] && [ -e "$_lr/file" ] && echo "" > "$_lr/file" 2>/dev/null; }
  arm_ocbm() {
    _composite=0
    if [ "${ANNOUNCE_RELEASED:-0}" = 1 ]; then
      set_usb_identity 2d00 no_unbind || bootlog "[ocbm-boot] W could not set/verify USB iSerial=$USB_SERIAL"
    else
      set_usb_identity 2d00 || bootlog "[ocbm-boot] W could not set/verify USB iSerial=$USB_SERIAL"
    fi
    if [ -e /script/ocbm_udisk ]; then
      _lr=$(lun_root)
      if [ -n "$_lr" ] && losetup /dev/loop1 >/dev/null 2>&1; then
        echo /dev/loop1 > "$_lr/file" 2>/dev/null && _composite=1
        # Stock inquiry_string value is 1; it is a selector, not a boolean to normalize.
        [ "$_composite" = 1 ] && echo 1 > /sys/class/android_usb_accessory/f_mass_storage/inquiry_string 2>/dev/null
      else
        bootlog "[ocbm-boot] W uDisk OCBM backing image unavailable; clearing /script/ocbm_udisk and using pure accessory"
        rm -f /script/ocbm_udisk
      fi
    else
      clear_lun
      losetup -d /dev/loop1 2>/dev/null
      rm -f /tmp/ram_fat32.img
    fi
    if [ "$_composite" = 1 ]; then _functions=accessory,mass_storage; else _functions=accessory; fi
    USB_NET_BOUND=0
    if [ "$USB_NET" != none ]; then
      # The network function goes between the two: OCBM stays interface 0, where every host looks.
      if [ "$_composite" = 1 ]; then _with_net=accessory,ncm,mass_storage; else _with_net=accessory,ncm; fi
      echo "$_with_net" > "$A/functions" 2>/dev/null
      if [ "$(cat "$A/functions" 2>/dev/null)" = "$_with_net" ]; then
        USB_NET_BOUND=1
        # The host's address is this driver's f_ncm/ethaddr, read at bind. Writing the function
        # list resets it, so it is set after; the module's host_addr parameter is ignored
        # (both measured 2026-11-15). All zeros leaves the driver's own choice.
        if [ -n "$USB_NET_HOST_MAC" ]; then
          echo "$USB_NET_HOST_MAC" > /sys/class/android_usb_accessory/f_ncm/ethaddr 2>/dev/null \
            || bootlog "[ocbm-boot] W f_ncm/ethaddr refused; the host names the link usb<N>"
        fi
      else
        bootlog "[ocbm-boot] W functions=$_with_net rejected (got \"$(cat "$A/functions" 2>/dev/null)\"); network function off"
        echo "$_functions" > "$A/functions"
      fi
    else
      echo "$_functions" > "$A/functions"
    fi
    echo 1 > "$A/enable"
    OCBM_BOUND_MS=$(uptime_ms)
    _rebind_detail=""
    if [ -n "${ANNOUNCE_RELEASED_MS:-}" ]; then
      _rebind_detail=" announce_to_ocbm_ms=$((OCBM_BOUND_MS - ANNOUNCE_RELEASED_MS))"
    fi
    bootlog "[ocbm-boot] ocbm bound uptime_ms=$OCBM_BOUND_MS${_rebind_detail} functions=$(cat "$A/functions" 2>/dev/null) pid=$(cat "$A/idProduct" 2>/dev/null)"
    if [ -n "${ANNOUNCE_RELEASED_MS:-}" ] && [ "$((OCBM_BOUND_MS - ANNOUNCE_RELEASED_MS))" -gt 300 ]; then
      bootlog "[ocbm-boot] W announce-to-OCBM rebind took $((OCBM_BOUND_MS - ANNOUNCE_RELEASED_MS))ms (target <=300ms)"
    fi
  }
  announce_then_ocbm() {
    if [ "$USB_ANNOUNCE" = 1 ]; then
      # Before the first enable=0. The failover watchdog treats this file, and the
      # 15 s after it is removed, as an expected ocbmd gap. Skip paths still reach
      # arm_ocbm, so the flag stays up until that returns.
      touch /tmp/ocbm_announce_active
      bootlog "[ocbm-boot] announce medium=$USB_ANNOUNCE_MEDIUM"
      if set_usb_identity "$USB_ANNOUNCE_PID"; then
        _lun_ready=1
        _lr=$(lun_root)
        # ocbm_udisk.sh — the device-proven stock recipe — never writes removable,
        # only lun0/file and inquiry_string. The attribute is not part of what makes
        # GM admit the device, so a missing removable node is logged, not a gate.
        if [ -z "$_lr" ] || [ ! -e "$_lr/file" ]; then
          _lun_ready=0
          bootlog "[ocbm-boot] W mass-storage LUN sysfs unavailable; skipping announce phase"
        elif [ "$USB_ANNOUNCE_MEDIUM" = image ]; then
          if [ "${ANNOUNCE_IMAGE_READY:-0}" = 1 ] && losetup /dev/loop1 >/dev/null 2>&1; then
            echo /dev/loop1 > "$_lr/file" 2>/dev/null
            [ "$(cat "$_lr/file" 2>/dev/null)" = /dev/loop1 ] || _lun_ready=0
          else
            _lun_ready=0
            bootlog "[ocbm-boot] W announce FAT image was not prepared before gadget load; skipping announce phase"
          fi
        else
          echo "" > "$_lr/file" 2>/dev/null || _lun_ready=0
          if [ -n "$(cat "$_lr/file" 2>/dev/null)" ]; then _lun_ready=0; fi
          [ "$_lun_ready" = 1 ] || bootlog "[ocbm-boot] W could not clear announce LUN backing file; skipping announce phase"
        fi
        if [ "$_lun_ready" = 1 ]; then
          echo 1 > "$_lr/removable" 2>/dev/null
          _rem=$(cat "$_lr/removable" 2>/dev/null)
          [ -n "$_rem" ] || _rem=absent
          bootlog "[ocbm-boot] announce removable=$_rem"
          # Stock inquiry_string value is 1; it is a selector, not a boolean to normalize.
          echo 1 > /sys/class/android_usb_accessory/f_mass_storage/inquiry_string 2>/dev/null
        fi
        if [ "$_lun_ready" = 1 ]; then
          echo mass_storage > "$A/functions" 2>/dev/null
          _announce_functions=$(cat "$A/functions" 2>/dev/null)
          if [ "$_announce_functions" != mass_storage ]; then
            bootlog "[ocbm-boot] W storage-only functions rejected (got \"$_announce_functions\") — trying accessory,mass_storage"
            echo accessory,mass_storage > "$A/functions" 2>/dev/null
            _announce_functions=$(cat "$A/functions" 2>/dev/null)
          fi
          if [ "$_announce_functions" != mass_storage ] && [ "$_announce_functions" != accessory,mass_storage ]; then
            bootlog "[ocbm-boot] W storage function lists rejected (got \"$_announce_functions\"); skipping announce phase"
            _lun_ready=0
          fi
        fi
        if [ "$_lun_ready" = 1 ]; then
          bootlog "[ocbm-boot] announce functions=$_announce_functions"
          _announce_start=$(uptime_ms)
          echo 1 > "$A/enable"
          bootlog "[ocbm-boot] announce bound uptime_ms=$_announce_start pid=$USB_ANNOUNCE_PID functions=$_announce_functions"
          _announce_configured=0
          while [ "$(( $(uptime_ms) - _announce_start ))" -lt 5000 ]; do
            if [ "$(cat "$A/state" 2>/dev/null)" = CONFIGURED ]; then _announce_configured=1; break; fi
            sleep 0.1
          done
          if [ "$_announce_configured" = 1 ]; then
            _announce_now=$(uptime_ms)
            bootlog "[ocbm-boot] announce configured +$((_announce_now - _announce_start))ms uptime_ms=$_announce_now"
            [ "$USB_ANNOUNCE_DWELL_MS" -eq 0 ] || sleep_ms "$USB_ANNOUNCE_DWELL_MS"
          else
            bootlog "[ocbm-boot] W announce not CONFIGURED within 5000ms; continuing to OCBM"
          fi
          echo 0 > "$A/enable"
          _announce_release=$(uptime_ms)
          bootlog "[ocbm-boot] announce released uptime_ms=$_announce_release"
          clear_lun
          ANNOUNCE_RELEASED=1
          ANNOUNCE_RELEASED_MS=$_announce_release
        fi
      else
        bootlog "[ocbm-boot] W could not set/verify announce USB identity; skipping announce phase"
      fi
    fi
    arm_ocbm
    if [ -e /tmp/ocbm_announce_active ]; then
      uptime_ms > /tmp/ocbm_announce_ended
      rm -f /tmp/ocbm_announce_active
    fi
  }
  echo "[ocbm-boot] usb_announce=$USB_ANNOUNCE usb_announce_dwell_ms=$USB_ANNOUNCE_DWELL_MS usb_announce_medium=$USB_ANNOUNCE_MEDIUM usb_announce_retry_ms=$USB_ANNOUNCE_RETRY_MS usb_announce_retries=$USB_ANNOUNCE_RETRIES pid=$USB_ANNOUNCE_PID" >> "$L"
  echo "[ocbm-boot] usb_announce=$USB_ANNOUNCE usb_announce_dwell_ms=$USB_ANNOUNCE_DWELL_MS usb_announce_medium=$USB_ANNOUNCE_MEDIUM usb_announce_retry_ms=$USB_ANNOUNCE_RETRY_MS usb_announce_retries=$USB_ANNOUNCE_RETRIES pid=$USB_ANNOUNCE_PID" >> /tmp/box.log

  # A self-reboot (supervisor L3) leaves the previous boot's universal log on jffs2 so the host
  # app's CH_LOG backfill streams the post-mortem; /tmp did not survive the reboot, this did.
  if [ -s /script/box_crash.log ]; then
    echo "[ocbm-boot] ---- previous boot's log (self-reboot post-mortem) ----" >> /tmp/box.log
    cat /script/box_crash.log >> /tmp/box.log
    echo "[ocbm-boot] ---- end of previous boot's log ----" >> /tmp/box.log
    rm -f /script/box_crash.log
  fi
  # stage + load the gadget modules (copy_to_tmp may not have run yet)
  [ -e /tmp/g_android_accessory.ko ] || { [ -e /script/ko.tar.gz ] && tar -xzf /script/ko.tar.gz -C /tmp 2>/dev/null; }
  # Stage any required FAT image BEFORE the gadget module loads. insmod raises D+ with zero
  # configurations and the host starts reading descriptors within ~100 ms; do not build an image
  # in that window (measured 2026-09-20, ocbm_udisk.sh header).
  ANNOUNCE_IMAGE_READY=0
  if [ "$USB_ANNOUNCE" = 1 ] && [ "$USB_ANNOUNCE_MEDIUM" = image ]; then
    if [ -x /script/ocbm_udisk.sh ] && /script/ocbm_udisk.sh prepare >> "$L" 2>&1 && losetup /dev/loop1 >/dev/null 2>&1; then
      ANNOUNCE_IMAGE_READY=1
    else
      bootlog "[ocbm-boot] W announce image preparation failed; image-mode announce will be skipped"
    fi
  elif [ -e /script/ocbm_udisk ] && [ -x /script/ocbm_udisk.sh ]; then
    /script/ocbm_udisk.sh prepare >> "$L" 2>&1
  fi
  grep -q storage_common /proc/modules || insmod /tmp/storage_common.ko 2>/dev/null
  if [ -n "$USB_NET_HOST_MAC" ] && ! grep -q g_android_accessory /proc/modules; then
    # u_ether takes the host's address from the module's host_addr at load and copies it into
    # f_ncm/ethaddr at every bind, so it is set here or not at all. A refused parameter falls back
    # to the plain load: no gadget would mean no way back in.
    insmod /tmp/g_android_accessory.ko host_addr="$USB_NET_HOST_MAC" 2>/dev/null \
      || { bootlog "[ocbm-boot] W insmod refused host_addr; the host names the link usb<N>"; insmod /tmp/g_android_accessory.ko 2>/dev/null; }
    bootlog "[ocbm-boot] usb_net: gadget loaded with host_addr=$(cat /sys/module/g_android_accessory/parameters/host_addr 2>/dev/null | cut -c1-2)…"
  else
    grep -q g_android_accessory /proc/modules || insmod /tmp/g_android_accessory.ko 2>/dev/null
    [ -n "$USB_NET_HOST_MAC" ] && bootlog "[ocbm-boot] W usb_net: gadget already loaded; host_addr cannot be set"
  fi
  # ZLP after a wMaxPacketSize-multiple accessory write. Off by default (accZLP=N, measured
  # 2026-09-02); without it a 512-multiple frame followed by idle never completes the host's
  # bulk read and is discarded on its timeout (macOS app and ocbm-host alike).
  echo Y > /sys/module/g_android_accessory/parameters/accZLP 2>/dev/null
  A=/sys/class/android_usb_accessory/android0
  i=0; while [ ! -e "$A/enable" ] && [ "$i" -lt 50 ]; do i=$((i+1)); sleep 0.1; done
  [ -e "$A/enable" ] || { echo "[ocbm-boot] gadget sysfs never appeared" >> "$L"; echo "[ocbm-boot] gadget sysfs never appeared" >> /tmp/box.log; exit 0; }
  # Device-level class remains 0/0/0 in both the storage announcement and OCBM phase.
  # PID 0x2d00 marks a DIFFERENT APPLICATION PROTOCOL on an otherwise identical pipe: stock/NCM is
  # 0x1520, and the endpoints + interface class are the same either way, so the PID is the only thing
  # in the descriptors telling a host this no longer speaks the Carlinkit protocol. That is what keeps
  # a native-protocol app (carlink_native filters 0x1520/0x1521 only — correct by design) from
  # claiming a converted box. Host apps that DO speak OCBM must list 0x1314:0x2d00 in their
  # usb_device_filter.xml + runtime allowlist, or Android never matches USB_DEVICE_ATTACHED and they
  # lose the implicit permission grant. See docs/carplay/00_ARCHITECTURE.md.
  # Boot starts with either the GM storage-only admission announcement or direct OCBM when disabled.
  # /script/ocbm_udisk still affects only the later OCBM composite (PID 2d00), not the announcement.
  announce_then_ocbm
  rm -f /tmp/ocbm_usb_net
  if [ "${USB_NET_BOUND:-0}" = 1 ]; then
    # Off the boot's critical path: ocbmd must not wait on a netdev. The adapter's end of the
    # link takes the maintenance mode's address and lease pool (custom_init.sh), which the access
    # point layer already refuses to use; no router and no DNS are offered, so a car that takes
    # a lease gains a link to the adapter and nothing it would route its own traffic through.
    (
      i=0; while [ ! -e /sys/class/net/ncm0 ] && [ "$i" -lt 50 ]; do i=$((i+1)); sleep 0.1; done
      if [ ! -e /sys/class/net/ncm0 ]; then bootlog "[ocbm-boot] W usb_net: ncm0 never appeared"; exit 0; fi
      ifconfig ncm0 192.168.50.2 netmask 255.255.255.0 mtu 1500 up
      cat > /tmp/udhcpd_ncm.conf <<CFG
start 192.168.50.100
end 192.168.50.150
interface ncm0
opt subnet 255.255.255.0
opt lease 86400
lease_file /tmp/udhcpd_ncm.leases
pidfile /tmp/udhcpd_ncm.pid
max_leases 20
CFG
      touch /tmp/udhcpd_ncm.leases
      busybox udhcpd -f /tmp/udhcpd_ncm.conf >/tmp/udhcpd_ncm.log 2>&1 &
      # The link-local address appears once duplicate detection is done.
      i=0; _ll=
      while [ "$i" -lt 30 ]; do
        _ll=$(ifconfig ncm0 2>/dev/null | sed -n 's/.*inet6 addr: \(fe80:[0-9a-f:]*\)\/64.*/\1/p' | head -1)
        [ -n "$_ll" ] && break
        i=$((i+1)); sleep 0.1
      done
      # What ocbmd tells the app (MGMT_INFO "usb_net") and where it listens. Written last and whole.
      printf 'style=%s\nv4=192.168.50.2\nll=%s\nport=5010\n' "$USB_NET" "$_ll" > /tmp/ocbm_usb_net.new
      mv /tmp/ocbm_usb_net.new /tmp/ocbm_usb_net
      bootlog "[ocbm-boot] usb_net=$USB_NET up: ncm0 192.168.50.2 link-local=${_ll:-none} leases 192.168.50.100-150"
    ) &
  fi
  i=0; while [ ! -e /dev/usb_accessory ] && [ "$i" -lt 50 ]; do i=$((i+1)); sleep 0.1; done
  if [ ! -e /dev/usb_accessory ]; then
    bootlog "[ocbm-boot] W /dev/usb_accessory missing after OCBM bind"
  fi
  /usr/sbin/ocbmd >> /tmp/box.log 2>&1 &
  OCBMD=$!
  # Retries are timed from the FIRST OCBM bind (k × retry_ms). ocbmd marks the first
  # received CT_HELLO in /tmp without changing the OCBM protocol; a HELLO suppresses
  # every remaining retry this boot. Gadget CONFIGURED is not a skip: the car holds
  # that state while it is still refusing the device.
  if [ "$USB_ANNOUNCE" = 1 ] && [ "$USB_ANNOUNCE_RETRIES" -gt 0 ]; then
    (
      _first_ocbm_ms=$OCBM_BOUND_MS
      _k=1
      while [ "$_k" -le "$USB_ANNOUNCE_RETRIES" ]; do
        _retry_delay=$((_k * USB_ANNOUNCE_RETRY_MS - ($(uptime_ms) - _first_ocbm_ms)))
        [ "$_retry_delay" -gt 0 ] && sleep_ms "$_retry_delay"
        [ -e /tmp/ocbm_hello_seen ] && exit 0
        if [ "$USB_ANNOUNCE_MEDIUM" = image ] && ! losetup /dev/loop1 >/dev/null 2>&1; then
          if [ -x /script/ocbm_udisk.sh ] && /script/ocbm_udisk.sh prepare >> "$L" 2>&1 && losetup /dev/loop1 >/dev/null 2>&1; then
            ANNOUNCE_IMAGE_READY=1
          else
            ANNOUNCE_IMAGE_READY=0
            bootlog "[ocbm-boot] W announce retry image preparation failed"
          fi
        fi
        [ -e /tmp/ocbm_hello_seen ] && exit 0
        bootlog "[ocbm-boot] announce retry — ocbmd will be respawned by inittab"
        bootlog "[ocbm-boot] announce retry $_k/$USB_ANNOUNCE_RETRIES (no HELLO in $((_k * USB_ANNOUNCE_RETRY_MS / 1000)) s) uptime_ms=$(uptime_ms)"
        _before_ocbmd=$(pidof ocbmd 2>/dev/null)
        announce_then_ocbm
        # inittab respawn window: the watchdog must not count these 10 s.
        _now=$(uptime_ms)
        printf '%s\n' "$((_now + 10000))" > /tmp/ocbm_announce_respawn_until
        i=0
        while [ ! -e /dev/usb_accessory ] && [ "$i" -lt 100 ]; do
          i=$((i+1))
          sleep 0.1
        done
        if [ -e /dev/usb_accessory ]; then
          bootlog "[ocbm-boot] announce retry accessory node present after ${i} tenths"
        else
          bootlog "[ocbm-boot] W announce retry accessory node missing after 10 s"
        fi
        i=0
        while :; do
          _retry_ocbmd_pid=$(pidof ocbmd 2>/dev/null)
          [ -n "$_retry_ocbmd_pid" ] && [ "$_retry_ocbmd_pid" != "$_before_ocbmd" ] && break
          [ "$i" -ge 20 ] && break
          i=$((i+1))
          sleep 0.5
        done
        if [ -n "$_retry_ocbmd_pid" ] && [ "$_retry_ocbmd_pid" != "$OCBMD" ]; then
          bootlog "[ocbm-boot] announce retry ocbmd respawned pid=$_retry_ocbmd_pid after $((i / 2))s"
        else
          bootlog "[ocbm-boot] W announce retry ocbmd not respawned within 10 s (pid=${_retry_ocbmd_pid:-none})"
        fi
        _k=$((_k + 1))
      done
    ) >/dev/null 2>&1 &
  fi

  # The car-hotspot client's keeper (radio_hal.sh, THE KEEPER): with a join configured, the adapter
  # joins the car's hotspot now and stays on it, so the app can reach ocbmd over Wi-Fi before any
  # session (ocbmd src/wifi.rs). Backgrounded; without a join configured nothing starts.
  if grep -q '^enabled=1' /script/sta_uplink.conf 2>/dev/null && grep -q '^psk=.' /script/sta_uplink.conf 2>/dev/null; then
    echo "[ocbm-boot] car hotspot: the client's keeper starting" >> /tmp/box.log
    setsid sh /script/radio_hal.sh sta_keep </dev/null >/dev/null 2>&1 &
  fi

  # Session supervisor: idle-waits on host presence; a host-app SUBSCRIBE drives projection + ARM

  # (docs/carplay/02_SESSION_LIFECYCLE.md). Backgrounded, so it can never block boot. Box stays IDLE (phone unswitched) until a host.
  [ -x /script/session_supervisor.sh ] && setsid /script/session_supervisor.sh >> /tmp/box.log 2>&1 &
  echo "[ocbm-boot] armed functions=$(cat $A/functions) class=$(cat $A/bDeviceClass) pid=$(cat $A/idProduct) state=$(cat $A/state) acc=$([ -e /dev/usb_accessory ] && echo yes) ocbmd=$OCBMD sup=$(pgrep -f session_supervisor)" >> "$L"
  echo "[ocbm-boot] armed functions=$(cat $A/functions) class=$(cat $A/bDeviceClass) pid=$(cat $A/idProduct) state=$(cat $A/state) acc=$([ -e /dev/usb_accessory ] && echo yes) ocbmd=$OCBMD sup=$(pgrep -f session_supervisor)" >> /tmp/box.log

  # ---- FIRST-BOOT DEAD-MAN (armed by ocbm_install.sh finalize: /script/ocbm_trial) --------
  # The installer tells the operator: "if this host does not confirm over the OCBM link within
  # 240s, the box restores ncm_only and reboots itself back to NCM." That promise was made by
  # the host and implemented NOWHERE - the flag was written and cleared host-side and no box
  # code ever read it. On a unit with no UART, a first boot where the OCBM stack does not come
  # up therefore had no way back at all: no NCM, no OCBM, and a finalize message telling the
  # operator to wait for a rescue that could never arrive. This is that rescue.
  #
  # It is deliberately SEPARATE from the failover watchdog below. That one asks "is our stack
  # healthy?" and only runs when opted into; this one asks "did a human confirm they can still
  # reach this box?" and runs exactly once, on the boot that flipped the default. A stack can
  # look perfectly healthy from the inside while being unreachable from the outside - wrong
  # gadget descriptors, a host that cannot claim the interface, a cable that never enumerates -
  # and only an outside acknowledgement can distinguish those.
  #
  # One-shot by construction: the confirm removes the flag, and a revert creates ncm_only, so
  # the box boots NCM and stays there until a human decides otherwise. A timed-out trial can
  # never become a reboot loop.
  if [ -e /script/ocbm_trial ]; then
  (
    T=/script/ocbm_trial.log
    echo "$(date) trial armed - waiting up to 240s for a host to confirm" >> "$T"
    i=0
    while [ "$i" -lt 240 ]; do
      [ -e /script/ocbm_trial ] || {
        echo "$(date) CONFIRMED by host at ${i}s - OCBM stays the default" >> "$T"; exit 0; }
      i=$((i+1)); sleep 1
    done
    echo "$(date) NO CONFIRMATION in ${i}s - restoring ncm_only and rebooting to NCM" >> "$T"
    touch /script/ncm_only || echo "$(date) WARNING: could not create ncm_only (rootfs full?)" >> "$T"
    rm -f /script/ocbm_trial
    sync; sleep 1; reboot
  ) &
  fi

  # ---- APP UPDATE TRIAL (T20: /script/fw_pending, the journal fw_apply.sh apply writes) ------
  # An update from the app swapped files and rebooted. fw_apply.sh boot undoes a swap a power cut
  # interrupted, rolls back on the third unconfirmed start or when ocbmd will not run, and once a
  # host has said HELLO gives the app five minutes to confirm. With no host it waits: a car switched
  # off straight after an update keeps it. The boot path itself is never part of an app update, so
  # this hook always runs as installed on the bench.
  if [ -e /script/fw_pending ] && [ -f /script/fw_apply.sh ]; then
    setsid sh /script/fw_apply.sh boot >/dev/null 2>&1 &
  fi

  # ---- NCM FAILOVER WATCHDOG (opt-in: /script/ocbm_failover) ------------------------------
  # On a unit with no UART, OCBM-as-default is the only door. If the OCBM stack cannot come up
  # at all, this drops /script/ncm_only and reboots, so the box returns to NCM with ssh/telnet
  # on 192.168.50.2 instead of being unreachable.
  #
  # WHAT COUNTS AS FAILURE — both triggers mean OUR OWN STACK is broken:
  #   1. /dev/usb_accessory never appears: the accessory gadget never bound, so ocbmd has
  #      nothing to open. Nothing downstream can work.
  #   2. ocbmd will not stay running: repeatedly dead across the observation window, i.e. the
  #      inittab respawn is churning rather than holding.
  #
  # WHAT DELIBERATELY DOES NOT COUNT: no host talking to us. The gadget sitting at CONNECTED,
  # or CONFIGURED with silence on the wire, is the NORMAL state of an appliance in a car or on
  # a bench. Treating that as failure would make the box flap between modes every time it is
  # powered without a host, which is worse than either mode on its own. ocbmd exiting ONCE on
  # a host disconnect is also normal — that is what the respawn wrapper is for — hence a count,
  # not a single miss. An announce unbinds the gadget on purpose: while
  # /tmp/ocbm_announce_active exists, for 15 s after it is removed, and for 10 s after a
  # retry's announce returns, misses stay at 0.
  #
  # It is one-shot by construction: once /script/ncm_only exists the box boots NCM and stays
  # there until a human removes it, so a persistent fault cannot become a reboot loop.
  if [ -e /script/ocbm_failover ]; then
  (
    W=/script/ocbm_failover.log
    fail() {
      echo "$(date) FAILOVER: $1 -> arming ncm_only and rebooting" >> "$W"
      touch /script/ncm_only; sync; sleep 1; reboot
    }
    i=0; while [ ! -e /dev/usb_accessory ] && [ "$i" -lt 60 ]; do i=$((i+1)); sleep 1; done
    [ -e /dev/usb_accessory ] || fail "/dev/usb_accessory never appeared in ${i}s"
    misses=0
    t=0
    _ignore_logged=0
    while [ "$t" -lt 120 ]; do
      _ignore=1
      if [ -e /tmp/ocbm_announce_active ]; then
        :
      else
        _now=$(uptime_ms)
        _ended=$(cat /tmp/ocbm_announce_ended 2>/dev/null)
        _until=$(cat /tmp/ocbm_announce_respawn_until 2>/dev/null)
        _ignore=0
        if [ -n "$_now" ] && [ -n "$_ended" ]; then
          [ $((_now - _ended)) -lt 15000 ] && _ignore=1
        fi
        if [ "$_ignore" = 0 ] && [ -n "$_now" ] && [ -n "$_until" ]; then
          [ "$_now" -lt "$_until" ] && _ignore=1
        fi
      fi
      if [ "$_ignore" = 1 ]; then
        misses=0
        if [ "$_ignore_logged" = 0 ]; then
          echo "$(date) failover: ignoring ocbmd gap during announce" >> "$W"
          bootlog "[ocbm-boot] failover: ignoring ocbmd gap during announce"
          _ignore_logged=1
        fi
      else
        _ignore_logged=0
        if pidof ocbmd >/dev/null 2>&1; then
          misses=0
        else
          misses=$((misses+1))
          [ "$misses" -ge 4 ] && fail "ocbmd not running on $misses consecutive checks by ${t}s"
        fi
      fi
      sleep 5
      t=$((t+5))
    done
    echo "$(date) OCBM healthy at ${t}s (ocbmd pid=$(pidof ocbmd))" >> "$W"
  ) &
  fi
) &
exit 0
