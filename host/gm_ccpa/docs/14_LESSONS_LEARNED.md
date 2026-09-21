# 14 — Lessons from the Equinox EV / Play-app bring-up (2026-09-12 … 2026-09-15)

**Pickup for this workstream.** The Silverado (`gminfo37`) proof in [`12_OBSERVED_FLOW.md`](12_OBSERVED_FLOW.md) still stands. This file is what the **Equinox EV** (`burmese_orange`, Android 14 / SDK 34, user 12) taught us when the same app and the same OCBM box were taken there. Correct this file in place; do not add a dated sibling.

Authority on conflict: [`04_SYSTEM_MODEL.md`](04_SYSTEM_MODEL.md) still describes what the system *is*. This file describes what that system *does and does not do* on this second GM image.

External evidence (XDA thread, Tachi91's repos, per-vehicle reports, stock uDisk scripts) is collected once in [`../../../docs/host/02_GM_AAOS_FIELD_REFERENCE.md`](../../../docs/host/02_GM_AAOS_FIELD_REFERENCE.md) — read that before going back to the forum.

---

## 1. Two GM head units are not one USB story

The docs in [`../../docs/host/01_ANDROID_AND_AAOS.md`](../../docs/host/01_ANDROID_AND_AAOS.md) were written against the 2024 Silverado ICE (`gminfo37`, Android 12L). On that unit:

- `UsbManager.getDeviceList()` **does** contain `0x1314:0x2d00`.
- The Allow dialog can appear.
- The broken `config_UsbDeviceConnectionHandling_component` (dangling `android.car.usb.handler`) is why “always allow” does not stick.

The Equinox EV is a different image (`gm Premium Infotainment System`, `burmese_orange-user 14 VCUUM-274.4 53 release-keys`). Play-installed `wasidremin.gmccpa` on **user 12** saw:

```text
FEATURE_USB_HOST=true
UsbManager deviceList (5):
  1d6b:0002  xHCI Host Controller
  0424:4915  USB24915P
  0424:49a0  USB2 Controller Hub
  0424:4911  USB249XX NCM/IAP Bridge
  1d6b:0003  xHCI Host Controller
```

No `1314:2d00`, `1520`, or `1521` at any point. `USB_DEVICE_ATTACHED` never reached `UsbAttachActivity`. `requestPermission()` never ran, so there is no Allow button. The toast **“USB not supported”** is GM’s stock handler, not this app.

The one “data” port on that vehicle is behind a Microchip **USB24915P** automotive CarPlay multi-host bridge (`0424:4911` is the NCM/IAP function). Do not debug Equinox USB as if it were Silverado USB.

**Refuted 2026-09-20 — the bridge is not “iPhone-only”.** The XDA carlink thread has an Equinox EV owner (Razorfin, p.24–25, Jun–Jul 2026) running the stock CFW with `riddleBoxCfg -s UdiskMode 1`: GM toasts “USB device not recognized” once, then **raises the Allow dialog and the session runs**. RWerksman documents the same on a Silverado EV, others on Sierra EV and Escalade IQ. The USB249xx passes a `1314:1520` **`accessory,mass_storage` composite** through to `UsbManager`; what it refuses to surface is a *pure* AOA/vendor gadget — which is exactly what OCBM presents by default. Tachi91 (thread p.24): “the dam UDisk EV nonsense that I can’t mess with in person” — he knows EVs need it and has no EV to test on. The GM VCU platform (Bosch VCUNH1, AAOS 14, EVs + newer ICE) is the one that needs the composite; `gminfo37` (Silverado ICE) does not. **This is §5, not a non-USB redesign.**

Remote log upload (`LogExport` → `https://wasidremin.ddnsgeek.com/cloudbridge/logs`, device `gmccpa`) is how this was learned: `READ_LOGS` is not grantable without adb, so `OWN_PROCESS` capture plus an explicit in-app upload is the Equinox instrument. Version 20 (`4.0+usb-inventory`) logs the full `deviceList` on the wait; keep that.

---

## 2. The box was not the USB fault — and a dark LED is not “no power”

On the Linux server the same dongle enumerates as `1314:2d00`, gadget `CONFIGURED`, `ocbmd` alive, OCBM `HELLO_ACK v1 caps=0x0000003f`.

Owned `init_gpio.sh` had the stock power-LED block **commented out**. Stock turns `gpio2` active-low from `init_bluetooth_wifi.sh` after Wi-Fi load. OCBM never runs that dispatcher at boot (radios on demand), so a converted box can be fully up with **no LEDs**. Dark LEDs on the Equinox therefore do not prove the port is unpowered.

The LED path is restored in `ccpa/rootfs/script/init_gpio.sh` and was pushed live (`gpio2` exported, `direction=out`, `value=0`). Linux usbfs nodes for `1314:2d00` are `root:root` unless a udev rule exists; `/etc/udev/rules.d/99-ocbm-ccpa.rules` (`MODE=0660`, `TAG+=uaccess`) was added on the operator’s server so replugs do not require sudo.

---

## 3. IW416 mapped-path radio was a real box bug — and it is fixed

This unit is NXP IW416 (`SDIO 0x9159`). After NCM conversion it has the radio seam and **not** the IW416-only `wlan_on.sh`/`bt_on.sh` (those must not be installed from the overlay — [CPC200-CCPA_resources/ocbm](https://github.com/lvalen91/CPC200-CCPA_resources/tree/main/ocbm) and [`../../docs/wireless/01_BT_AND_RADIO.md`](../../docs/wireless/01_BT_AND_RADIO.md)).

Published `radio_detect.sh` still refuses NXP lines that contain `$nxpWiFiConfig`. Extraction then emitted only `insmod /tmp/mlan.ko;`, `moal` never loaded, no WLAN interface appeared, and `RADIO_BT_AFTER_WLAN=1` was a **whole-file** false positive from other chips’ wait loops. IW416’s own attach branch does **not** wait. Symptom from the emulator: `BOX_HEALTH 0x50 [btd|rootfs-ok]` with the HCI bit clear, app timeout at 45 s.

The seam now slices **this unit’s** `sdioCardID` branch, resolves that branch’s literal assignments, emits `RADIO_BT_PRELOAD_CMD`, and takes the WLAN-before-BT wait from the attach branch only. Live result on this box:

```text
RADIO_WLAN_INSMOD="insmod /tmp/mlan.ko;insmod /tmp/moal.ko mod_para=nxp/wifi_mod_para.conf;"
RADIO_BT_PRELOAD_CMD="fw_loader_linux /dev/ttymxc2 115200 1 /lib/firmware/nxp/uartiw416_bt_v0.bin 3000000"
RADIO_BT_AFTER_WLAN=0
```

`moal` loaded, `wlan0`/`sta0` appeared, `radio_hal.sh bt_on` rc=0, `hci0` UP. Through the AAOS emulator with USB passthrough, GM CCPA then got `BOX_HEALTH 0x51 [HCI|btd|rootfs-ok]` and `HCI bring-up OK`. That path is **not** blocked. The Equinox never reaches it because USB host never presents the adapter.

Do not rebuild the Android app to chase a radio failure the box logs have already named. Do not install `wlan_on.sh`/`bt_on.sh` from the overlay onto an IW416 unit that already has the seam.

Bluetooth while the dongle is on the **server** USB is allowed by the hardware and off by policy: radios stay down until `CT_SUBSCRIBE`. `ocbm-host hello` / console is not a subscribe.

---

## 4. We did not stray from the resources conversion guide on the gadget

[CPC200-CCPA_resources/ocbm](https://github.com/lvalen91/CPC200-CCPA_resources/tree/main/ocbm) is the conversion landing page; this repo is the implementation. The live box matches that guide:

| Spec | This dongle |
|---|---|
| `functions=ncm` **or** `accessory`, never both | `functions=accessory` |
| `bDeviceClass=0` (not 239 IAD) | `class=0` |
| OCBM PID `0x2d00` | `pid=2d00` `vid=1314` |
| `state=CONFIGURED` | yes |
| No `/script/ncm_only` after finalize | absent |
| IW416 `wlan_on.sh`/`bt_on.sh` not installed from overlay | absent; `radio_hal.sh` present |

Pure accessory / `2d00` **is** OCBM. The Equinox rejection is a host-port mismatch, not a failed conversion.

Where we *did* differ from the **published** resources `radio_detect.sh`: this tree’s copy is the IW416 branch-local resolution (lesson 3). The published 12 KB file would still drop `moal`. That is an improvement, not a gadget-descriptor drift.

---

## 5. Stock “uDisk mode” is the GM-EV workaround — re-implemented for OCBM, bench-proven

The sibling tree `Code/tmp_carlink_native_ivalen91_update` documents why original Carlinkit firmware got the same “USB not supported” toast until the **USB mass storage** setting was toggled.

Stock `UdiskMode=1` (web UI `Udisk`) runs `start_accessory_mass_storage.sh` (firmware dump, `custom/scripts/live_snapshot_2026-06-29/`): `enable=0`; `dd` 8 MiB → `mkfs.fat -s 128 -n APK` → `losetup /dev/loop1`; write `/dev/loop1` to **`/sys/devices/soc0/soc.N/2100000.aips-bus/2184200.usb/ci_hdrc.1/gadget/lun0/file`** (the UDC’s own node — *not* under `/sys/class/android_usb_accessory`); `echo 1 > f_mass_storage/inquiry_string`; `functions=accessory,mass_storage`; `sleep 1`; `enable=1`.

GM then sees a **USB disk + accessory** composite and enumerates it. Device-proven on Equinox EV / Silverado EV / Sierra EV / Escalade IQ by stock-firmware users (§1).

OCBM conversion **removed** that on purpose (`ocbm_boot.sh` forces `functions=accessory`; `ncm_base_install.sh` deletes the stock scripts; `riddleBoxCfg` is gone). It is now back as a **flag-gated** gadget mode that keeps everything OCBM needs:

| Piece | Role |
|---|---|
| `/script/ocbm_udisk.sh` | `status` / `on` / `off` / `prepare` / `apply`. Stock recipe byte-for-byte with `idProduct=2d00`, `bDeviceClass=0`. `on`/`off` re-exec **detached** (`setsid`, no tty) — see failure 2 below. Reverts to pure accessory and clears the flag if the composite fails or `/dev/usb_accessory` disappears. |
| `/script/ocbm_udisk` | Persistent arm flag (jffs2). |
| `ocbm_boot.sh` | With the flag: `prepare` (image + loop) **before** `insmod g_android_accessory`, then `apply` (sysfs only) right after — see failure 4. |
| Install | Shipped by `tools/ocbm_install.sh` `place`. |

**Bench result (2026-09-20, Linux host, across a cold boot):** `lsusb` `1314:2d00`, `bNumInterfaces 2` — IF0 class `0xFF` bulk `0x81/0x01` (OCBM, `HELLO_ACK caps=0x3f`), IF1 class `0x08` bulk `0x82/0x02` (mass storage; host creates `/dev/sdX`, 8 M vfat `APK`, mounts and reads the README). `/dev/usb_accessory` present, `ocbmd` up, state `CONFIGURED`. **`accessory,mass_storage` coexists with OCBM on this kernel.** Only `accessory,adb` does not ([`../../docs/carplay/00_ARCHITECTURE.md`](../../docs/carplay/00_ARCHITECTURE.md)).

**The same-day “FAILED / CLOSED” verdict was wrong. Four bugs, all ours, in order found:**

1. **Wrong LUN path.** The first script wrote to `/sys/class/android_usb_accessory/f_mass_storage/lun0/file`, which does not exist. The LUN is the UDC node above. Without a backing store `enable=1` fails and the gadget stays disabled — the “device vanished, never came back” symptom.
2. **Script killed by its own console.** `on` was run from `ocbm-host console`. `enable=0` drops the bulk link → the host disconnects → `ocbmd`’s console child gets SIGHUP mid-sequence. Any gadget re-arm from the OCBM console must detach itself first.
3. **`log()` returned 1 when detached.** `[ -t 1 ] && echo` as the last statement; `bind_lun` ended in `log`, so `arm_udisk` “failed” and the REVERT path ran with a perfectly bound LUN. Helpers whose status is branched on must `return 0` explicitly.
4. **Zero-configuration window at boot.** The legacy gadget raises D+ at `insmod` with 0 configurations and holds it until the first `enable=1`. Pure boot writes that within milliseconds. The composite boot spent ~3 s building the image in between: the Linux host logged `no configurations` ×4, `attempt power cycle` (rebooting the bus-powered box), then `unable to enumerate USB device` and stopped retrying — while the box sat fully `CONFIGURED`. Recovery without touching the box: `USBDEVFS_RESET` on the parent hub. Fix: `prepare` before `insmod`, no sleeps in the boot-time `apply`. Verified: one clean enumeration 4 s after reset. **A GM host will have the same window; keep everything slow ahead of insmod.**

Host-side consequences, fixed the same day: anything that “walks interfaces for the bulk pair” now finds **two** pairs. `ocbm-host` and `ocbm-probe` took the *last* one and spoke HELLO at the SCSI endpoints (`no HELLO_ACK`); the app’s `UsbBulkTransport` took the *first* (right by ordering luck). All three now prefer class `0xFF` and never claim class `0x08` — the kernel’s `usb-storage` owns it, and yanking it would remove the very disk GM enumerated us for.

**Equinox result (2026-09-21 01:02 UTC, first in-car try, app `4.0+usb-inventory` versionCode 20) — the composite enumerates and OCBM runs end to end on the VCU radio.** Box booted with the car (`[ocbm-boot] armed functions=accessory,mass_storage class=0 pid=2d00 state=CONFIGURED`, `uptime_s=36` at HELLO). App found `/dev/bus/usb/001/006 vid=0x1314 pid=0x2d00`, `requestPermission()` → Allow dialog → permission held after **4.0 s**, claimed **`iface=0 class=0xff IN=0x81 OUT=0x01 mps=512`** (GM orders the vendor interface first, so the first-pair build worked by the same ordering luck as on Linux), `HELLO_ACK` in 2 ms, MFi cert 945 B + RSA-1024 signature in 1.7 s, `CT_SUBSCRIBE` → `SEV_HOST_PRESENT` in 9 ms, `BOX_HEALTH 0x40 → 0x50 → 0x51 [HCI|btd|rootfs-ok]` in 10 s, `HCI bring-up OK dev=hci0 name=CarLink-6754`, discoverable, SDP serving Wireless iAPv2 / HFP / HSP. Vehicle hotspot creds (`myChevrolet 32D4`, ch 36) pushed in the SUBSCRIBE. The capture ends before any phone paired, so Bluetooth pairing → hotspot handoff → CarPlay connect on this vehicle is the next thing to prove, not USB.

Two things the same capture exposed, both ours:

1. **`USB_DEVICE_ATTACHED` still did not reach `UsbAttachActivity`** (no `attach ctx` line; session `origin=launch perm_trampoline=none`). The app had been up since 17:24 UTC, its 10 min attach wait had aborted at 20:20 (`usb-claim FAILED … last state: absent`), and when the dongle booted at 01:01:42 nothing re-linked until the driver pressed **Restart Session** at 01:02:03. Until the app re-polls `deviceList` after that abort (or GM delivers the attach intent), **every drive needs a Restart Session press** once the app has been open more than 10 min. Open item, see §7.
2. **False STALLED 45 s later.** `SEV_HOST_PRESENT` moved the phase to ARMED at +9 ms, then the command thread's `onBoxLinked()` landed 4 ms after and regressed it to BOX_LINKED; the restart deadline then reported “the adapter never confirmed the host present — no radio bring-up” against a box that was discoverable. UI showed FAILED with everything healthy. Fixed in `SessionSupervisor.onBoxLinked` (never regress a phase the box already advanced) and the ARMED verdict now says “pair (first time) or reconnect” rather than assuming a prior session. Ships in the next build (> versionCode 20).

GM did **not** toast “USB device not recognized” in this capture (no evidence either way from the app side; the driver should say). Whether the 8 MB `APK` volume shows as a USB-media source on the HU is likewise a driver observation. `ocbm_udisk.sh off` restores pure accessory.

---

## 6. Play distribution and the emulator

- Package is `wasidremin.gmccpa` (Play). The `android.car.usb.handler` squat is reverted and **must not** return: Play will not ship that `applicationId`, and the owner already found it did not buy a silent grant.
- `versionCode` is **not** `git rev-list --count HEAD` on this shallow mirror (that is 8). Play has already consumed 8–14. The USB-inventory build is **versionCode 20**, track `automotive:internal`, name `4.0+usb-inventory`. Next Play upload must be **> 20**.
- Do not commit `play-service-account.json`.
- AAOS emulator with `-usb-passthrough vendorid=0x1314,productid=0x2d00,hostbus=…,hostport=…` **does** deliver the adapter to a 3rd-party app (permission dialog, then full OCBM + HCI). That is a Linux-host USB-host proof, not an Equinox proof. Keep the emulator parent attached; detached qemu dies. Stale AVD lock files from a killed qemu block the next launch.

---

## 7. What is proven vs what is blocked (this vehicle)

| Layer | Server / emulator | Equinox EV |
|---|---|---|
| Box enumerates `1314:2d00`, OCBM HELLO/MFi/SUBSCRIBE | yes | pure accessory: never appears in `UsbManager`. **Composite: yes (2026-09-21)** |
| `accessory,mass_storage` composite, `2d00`, HELLO + FAT volume, across reboot | yes (2026-09-20) | **yes (2026-09-21)** — `iface=0 class=0xff`, HELLO_ACK 2 ms, MFi proven |
| IW416 `hci0` after subscribe | yes (`BOX_HEALTH 0x51`) | **yes** — `0x51` 10 s after SUBSCRIBE, `CarLink-6754` discoverable |
| Allow / Always-open dialog | emulator: yes | **Allow: yes** (4 s, via `requestPermission()` from Restart Session). Attach intent: still never delivered |
| Vehicle hotspot + inverted receiver | Silverado: proven | creds pushed (`myChevrolet 32D4` ch 36); receiver up on `wlan0`/`br0`; **no phone paired yet in the capture** |

**Still open on Equinox, in order:**

1. **Pair the iPhone.** With the app past `box linked`/`armed` (Restart Session if needed), pair to `CarLink-6754` from the iPhone's Bluetooth settings, then upload. Expect `BT_PHASE` progression → `BTP_WIFI_HANDOFF` → inbound `_carplay-ctrl` connect. This is the Silverado flow on a VCU radio; nothing about it has been tried here.
2. **Auto-relink after the 10 min attach abort.** GM never delivers `USB_DEVICE_ATTACHED` to us and the dongle boots ~35 s after the car does, so an app opened earlier sits on `usb-claim FAILED` until the driver presses Restart Session. Poll `deviceList` for `1314:2d00` at a low rate after the abort (or never abort while the activity is foreground) and re-run the link when it appears. Not implemented.
3. Ship the `UsbBulkTransport` vendor-interface preference + the `onBoxLinked` no-regress fix (versionCode > 20). GM happened to order the vendor interface first, so the shipped build worked; do not rely on it.
4. ~~Power from that jack, control over TCP~~ — **retired**: USB bulk is reachable on the VCU with the composite.
