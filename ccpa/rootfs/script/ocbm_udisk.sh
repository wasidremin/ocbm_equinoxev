#!/bin/sh
########################
# ocbm_udisk.sh — OPT-IN GM-EV experiment: present accessory + mass_storage
# while keeping OCBM PID 0x2d00 and /dev/usb_accessory for ocbmd.
#
# WHY: GM VCU radios (Bosch VCUNH1 — Equinox EV, Silverado EV, Sierra EV, Escalade IQ;
# AAOS 14) toast "USB not supported" and never surface a pure AOA/vendor gadget to
# UsbManager. Stock Carlinkit's UdiskMode=1 (functions=accessory,mass_storage, an 8 MiB
# RAM FAT image on /dev/loop1) is device-proven on an Equinox EV to make the same jack
# enumerate the dongle and raise the Allow dialog (XDA carlink thread p.25, Razorfin,
# 2026-07-01; RWerksman on Silverado EV). This script is that recipe, byte-for-byte
# from stock start_accessory_mass_storage.sh, with idProduct held at 2d00.
#
# LESSONS (2026-09-20 bench, host/gm_ccpa/docs/14_LESSONS_LEARNED.md §5):
#   * The LUN is NOT under /sys/class/android_usb_accessory. It is the UDC's own node:
#       /sys/devices/soc0/soc.N/2100000.aips-bus/2184200.usb/ci_hdrc.1/gadget/lun0/file
#     Writing anywhere else leaves mass_storage without a backing store.
#   * `enable=0` tears down the bulk link, which kills ocbmd, which kills the OCBM
#     console shell that invoked us -> SIGHUP mid-sequence with the gadget left DISABLED.
#     `on`/`off` therefore re-exec themselves detached (setsid, no tty) and return at once.
#   * ocbmd is respawned by inittab (/script/run_ocbmd.sh) within ~3 s of dying; we do not
#     race it, we wait for it.
#   * The legacy gadget raises D+ at insmod with ZERO configurations and keeps it there until
#     the first `enable=1`. A pure boot writes that within milliseconds, so no host notices. The
#     first composite boot spent ~3 s building the FAT image inside that window: the Linux host
#     read "no configurations" x4, power-cycled the port (rebooting the bus-powered box), and
#     abandoned the port for good. Hence `prepare` (image + loop, no sysfs) runs BEFORE insmod
#     and `apply` is sysfs-only and sleep-free.
#
# Persistent arm:   /script/ocbm_udisk flag (survives reboot; ocbm_boot.sh runs prepare + apply)
#
# Usage:
#   ocbm_udisk.sh status
#   ocbm_udisk.sh on      # set flag, re-arm gadget detached, ocbmd respawns
#   ocbm_udisk.sh off     # clear flag, pure accessory detached, ocbmd respawns
#   ocbm_udisk.sh prepare # build /tmp/ram_fat32.img on /dev/loop1 (ocbm_boot.sh, BEFORE insmod)
#   ocbm_udisk.sh apply   # apply whatever the flag says (ocbm_boot.sh, AFTER insmod, before ocbmd)
########################
set -u

FLAG=/script/ocbm_udisk
A=/sys/class/android_usb_accessory/android0
FMS=/sys/class/android_usb_accessory/f_mass_storage
IMG=/tmp/ram_fat32.img
LOOP=/dev/loop1
L=/tmp/ocbm_udisk.log
export PATH=/usr/sbin:/usr/bin:/sbin:/bin:/tmp/bin:$PATH

# Always returns 0: several helpers end in a log call and their exit status is what the caller
# branches on. (`[ -t 1 ] && echo` as the last statement returned 1 whenever detached — the
# 2026-09-20 false REVERT.)
log() { echo "$(date '+%H:%M:%S') [ocbm-udisk] $*" >> "$L"; if [ -t 1 ]; then echo "[ocbm-udisk] $*"; fi; return 0; }

# Stock writes both soc.0 and soc.1 blindly; only one exists per unit.
lun_files() { ls /sys/devices/soc0/soc.*/2100000.aips-bus/2184200.usb/ci_hdrc.1/gadget/lun0/file 2>/dev/null; }

gadget_wait() {
  i=0
  while [ ! -e "$A/enable" ] && [ "$i" -lt 50 ]; do i=$((i + 1)); sleep 0.1; done
  [ -e "$A/enable" ]
}

# Stock recipe: 8 MiB zeroed file, FAT with 128-sector clusters, label APK, on /dev/loop1.
# Stock drops BoxHelper.apk in it; ours is stripped, so a README marks the volume instead.
make_img() {
  [ -s "$IMG" ] && losetup "$LOOP" >/dev/null 2>&1 && return 0
  losetup -d "$LOOP" 2>/dev/null
  rm -f "$IMG"
  dd if=/dev/zero of="$IMG" bs=8M count=1 2>/dev/null || dd if=/dev/zero of="$IMG" bs=1M count=8 2>/dev/null || return 1
  mkfs.fat -s 128 -n APK "$IMG" >/dev/null 2>&1 || mkfs.vfat -s 128 -n APK "$IMG" >/dev/null 2>&1 || log "WARN: mkfs failed, raw image"
  losetup "$LOOP" "$IMG" || return 1
  if mkdir -p /tmp/UPAN && mount "$LOOP" /tmp/UPAN -t vfat -o utf8=1 2>/dev/null; then
    echo "OCBM adapter. This volume exists so GM AAOS enumerates the device." > /tmp/UPAN/README.txt
    sync; umount /tmp/UPAN
  fi
  echo 3 > /proc/sys/vm/drop_caches 2>/dev/null
  return 0
}

bind_lun() {
  luns=$(lun_files)
  [ -n "$luns" ] || { log "FAIL: no ci_hdrc.1/gadget/lun0/file node"; return 1; }
  make_img || { log "FAIL: could not build $IMG on $LOOP"; return 1; }
  ok=0
  for f in $luns; do echo "$LOOP" > "$f" 2>/dev/null && ok=1; done
  [ "$ok" = 1 ] || { log "FAIL: lun0/file rejected $LOOP"; return 1; }
  echo 1 > "$FMS/inquiry_string" 2>/dev/null
  log "lun bound: $(for f in $luns; do cat "$f" 2>/dev/null; done | tr '\n' ' ')"
}

unbind_lun() {
  for f in $(lun_files); do echo "" > "$f" 2>/dev/null; done
  losetup -d "$LOOP" 2>/dev/null
  rm -f "$IMG"
}

set_ids() {
  echo 0 > "$A/bDeviceClass"
  echo 0 > "$A/bDeviceSubClass"
  echo 0 > "$A/bDeviceProtocol"
  echo 2d00 > "$A/idProduct"
}

# RUNTIME=1 (the detached on/off path) inserts the stock 1 s settle after the disconnect so the
# host sees a clean detach/attach. At boot the gadget was never enabled, enable=0 is a no-op,
# and every sleep here widens the zero-config window described in the header — so none.
RUNTIME=${RUNTIME:-0}
settle() { [ "$RUNTIME" = 1 ] && sleep 1; return 0; }

arm_pure() {
  echo 0 > "$A/enable"
  settle
  unbind_lun
  set_ids
  echo accessory > "$A/functions"
  echo 1 > "$A/enable"
}

arm_udisk() {
  echo 0 > "$A/enable"
  settle
  set_ids
  bind_lun || return 1
  # Comma-separated ONLY — a space-separated list silently empties functions.
  echo accessory,mass_storage > "$A/functions"
  echo 1 > "$A/enable"
}

apply_now() {
  gadget_wait || { log "FAIL: gadget sysfs missing"; return 1; }
  if [ -e "$FLAG" ]; then
    log "arming accessory,mass_storage (flag present)"
    arm_udisk || { log "REVERT: composite arm failed — pure accessory, flag cleared"; rm -f "$FLAG"; sync; arm_pure; return 1; }
  else
    log "arming pure accessory (no flag)"
    arm_pure
  fi
  i=0; while [ ! -e /dev/usb_accessory ] && [ "$i" -lt 50 ]; do i=$((i + 1)); sleep 0.1; done
  funcs=$(cat "$A/functions" 2>/dev/null || echo '?')
  state=$(cat "$A/state" 2>/dev/null || echo '?')
  pid=$(cat "$A/idProduct" 2>/dev/null || echo '?')
  acc=no; [ -e /dev/usb_accessory ] && acc=yes
  log "result functions=$funcs state=$state pid=$pid acc=$acc"
  if [ "$acc" = no ] && [ -e "$FLAG" ]; then
    log "REVERT: composite left no /dev/usb_accessory — pure accessory, flag cleared"
    rm -f "$FLAG"; sync
    arm_pure
    i=0; while [ ! -e /dev/usb_accessory ] && [ "$i" -lt 50 ]; do i=$((i + 1)); sleep 0.1; done
    [ -e /dev/usb_accessory ] || { log "REVERT failed — accessory node still missing"; return 1; }
    log "reverted functions=$(cat "$A/functions") acc=yes"
    return 1
  fi
  return 0
}

# Detached path: gadget bounce kills ocbmd; inittab respawns it. Wait for that, launch if it does not.
ocbmd_settle() {
  killall ocbmd 2>/dev/null
  i=0; while ! pidof ocbmd >/dev/null 2>&1 && [ "$i" -lt 30 ]; do i=$((i + 1)); sleep 0.5; done
  if ! pidof ocbmd >/dev/null 2>&1; then
    /usr/sbin/ocbmd >> /tmp/box.log 2>&1 &
    log "ocbmd not respawned by init — launched pid=$!"
  fi
  log "ocbmd pid=$(pidof ocbmd 2>/dev/null || echo none)"
}

cmd_status() {
  echo "flag=$([ -e "$FLAG" ] && echo ARMED || echo off)"
  if [ -e "$A/functions" ]; then
    echo "functions=$(cat "$A/functions")"
    echo "state=$(cat "$A/state")"
    echo "class=$(cat "$A/bDeviceClass") pid=$(cat "$A/idProduct")"
  else
    echo "gadget=MISSING"
  fi
  echo "lun=$(for f in $(lun_files); do cat "$f"; done 2>/dev/null | tr '\n' ' ')"
  echo "loop=$(losetup "$LOOP" 2>/dev/null || echo none)"
  echo "usb_accessory=$([ -e /dev/usb_accessory ] && echo yes || echo NO)"
  echo "ocbmd=$(pidof ocbmd 2>/dev/null || echo none)"
  [ -e "$L" ] && echo "---- $L ----" && tail -20 "$L"
}

# `on`/`off` from the OCBM console MUST detach: the console dies with ocbmd on enable=0.
detach() {
  setsid sh "$0" "_$1" </dev/null >>"$L" 2>&1 &
  echo "[ocbm-udisk] $1 scheduled detached (pid $!) — link will drop ~3 s and re-enumerate; then: $0 status"
}

case "${1:-status}" in
  status)  cmd_status ;;
  prepare) [ -e "$FLAG" ] || exit 0; make_img && log "prepared $IMG on $LOOP" || log "WARN: prepare failed" ;;
  apply)   apply_now ;;
  on)      touch "$FLAG"; sync; detach on ;;
  off)     rm -f "$FLAG"; sync; detach off ;;
  _on|_off)
    sleep 1                       # let the console echo return before we pull the link
    RUNTIME=1
    apply_now; rc=$?
    ocbmd_settle
    log "$1 done rc=$rc"
    ;;
  *) echo "usage: $0 status|on|off|apply" >&2; exit 2 ;;
esac
