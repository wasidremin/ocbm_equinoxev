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
- `versionCode` is **not** `git rev-list --count HEAD` on this shallow mirror (that is 8). Play has already consumed 8-14 and 20-62, plus 82 (found live on `automotive:internal` 2026-09-30; published outside this repo record). **High-water mark: versionCode 212** (`4.0+tall-tiles`, 2026-10-23, `automotive:internal` COMPLETED; PUBLISH_OK): taller top-bar tiles, rail mic capsule fills the gap, Home/Settings dropped from the rail when the bar is on. Previous high-water mark: versionCode 211 (`4.0+bar-tiles`, 2026-10-22, `automotive:internal` COMPLETED; PUBLISH_OK; see §15 and §14.9). Nine CarPlay-style top-bar tiles, the bar on the settings screen, CarPlay-style call card, and the 2026-10-22 audit fixes. Previous high-water mark: versionCode 210 (`4.0+log-range`, 2026-10-22, `automotive:internal` COMPLETED; PUBLISH_OK). Log upload range (1 hour / 6 hours / Today / All, default 1 hour), streamed upload batches instead of whole-capture buffering, and the `np: sync` music-sync instrumentation (§14.8). Previous high-water mark: versionCode 209 (`4.0+insights-workbench`, 2026-10-20, `automotive:internal` COMPLETED; PUBLISH_OK). Insights EV telemetry workbench (§13). Previous high-water mark: versionCode 208 (`4.0+topbar-slope-glass`, 2026-10-19, `automotive:internal` COMPLETED; PUBLISH_OK). Bar content hangs from the panel-slope clearance (glass line m = 167/2629 from the vehicle cutout wedge `Rect(0,0-2629,167)`): left icons form a level row clearing the slope at the last icon, the smaller right cluster's edge is solved to where its content height fits above the bar bottom (near x 1600, not x 2540 which has 7px of glass), and the width fit-scale is the backstop. Centering had put the whole cluster on missing glass. Previous high-water mark: versionCode 207 (`4.0+topbar-right-fit`, 2026-10-19, `automotive:internal` COMPLETED; PUBLISH_OK). Top-bar right cluster runs smaller than the left icons with a 0.55h end indent (like the car's own bar) and shrinks uniformly via a measured fit scale when the slot is narrow, so it never overlaps the left icons or spills under the cutout. Previous high-water mark: versionCode 206 (`4.0+traced-rect-tick-gate`, 2026-10-18, `automotive:internal` COMPLETED; PUBLISH_OK). Traced rect sizes advertised video before latch (true visible aspect ratio, zero dp squish), top-bar right cluster honours max(safeInsetRight, traced rightPx), live solved-rect preview in the trace activity, tick fires only on elapsedMs advance, non-negative queueId, rail/top-bar play state follows seeks. Previous high-water mark: versionCode 205 (`4.0+metadata-trace`, 2026-10-18, `automotive:internal` COMPLETED; PUBLISH_OK). Tick-deduped PlaybackState (push on change/seek only), single-item queue, SEEK rate fix, rail follows ticks, art clears on id-less titles (§12.1); edge-trace calibration replaces dp sliders + margin presets (§12.2). Previous high-water mark: versionCode 204 (`4.0+topbar-fitment`, 2026-10-18, `automotive:internal` COMPLETED; PUBLISH_OK). Non-rectangular-panel fitment: top-bar clock/temp cluster starts left of the live `safeInsetRight` (285) instead of on missing glass, larger right-cluster text/icons, Right margin stops to 320 with nearest-stop mapping, Right inset (dp) slider to 320. Previous high-water mark: versionCode 203 (`4.0+insights-np-logs`, 2026-10-18, `automotive:internal` COMPLETED; PUBLISH_OK). Insights settings tab, in-call overlay pill, failure-screen restart (d2e2d5d) plus now-playing/artwork silence diagnostics (a5c6906). Previous high-water mark: versionCode 202 (`4.0+fullscreen-capture`, 2026-10-17, `automotive:internal` COMPLETED; PUBLISH_OK, commit a55576a). Whole-window Capture Full Screen (top bar + rail + video composite) alongside video-only capture; sidecar gains windowWidth/Height. NOTE: the outside-repo publisher is far ahead of this file's old record — on 2026-10-17 `automotive:internal` was already live at **201** (`4.0+startup-fix-r201`), and 100/101 were already consumed, so a blind +1 from 99 fails twice over (reuse at upload, then does-not-allow-upgrade at commit for anything ≤ live). To find the true max, open an edit with the SA key and list edit-scoped bundles/tracks (app-scoped tracks/bundles reads 404 for this SA; edits.insert works). Previous high-water mark: versionCode 99 (`4.0+vision-launch`, 2026-10-15, `automotive:internal` COMPLETED; PUBLISH_OK). Strip-first vision launcher (`ScreenModel` fixed 1/4 scale + `VisionLauncher` + `CalibrationUi`): templates cut from saved captures, strip → grid → Home-toggle-then-grid, never taps an app without a 0.85 match; top-bar NAV/MUSIC/PHONE route through it with the old behaviour as the uncalibrated fallback; 24-unit outline glyphs for car/music/nav/phone/energy. Previous high-water mark: versionCode 98 (`4.0+calib-ui`, 2026-10-15, `automotive:internal` COMPLETED; PUBLISH_OK). Icon Templates & App Launch card in the launcher calibration section operates the matcher/launcher engine: template list/delete/capture from a normalized rect of a live stable frame, tap-verify-upload app launches with records to `/api/launches`; dock-row hint (y 0.72..0.88 of 2754x966) measured from the day's HOME_GRID captures — RETIRED in v99 (that region was Spotify podcast artwork, not a dock row; see §9). Previous high-water mark: versionCode 97 (`4.0+warm-relink`, 2026-10-15, `automotive:internal` COMPLETED; PUBLISH_OK). Warm-start relink: `maybeRelinkOnResume` reclaims when the app is opened with USB_CLAIM FAILED on the board, no attempt in flight, and `1314:2d00` on the bus (remote warm-up powers USB with the display off, the dialog defers, the claim aborts, resume never re-ran); per-episode display-off-deferral flag reset; `OcbmProbe.linkAttemptInFlight` funnel flag; `Board.isFailed` reader. Previous high-water mark: versionCode 96 (`4.0+capture-launch`, 2026-10-15, `automotive:internal` COMPLETED; PUBLISH_OK). Calibration capture + launcher record uploads with matching `/api/captures` + `/api/launches` endpoints on the log receiver. Previous high-water mark: versionCode 95 (`4.0+probe-upload`, 2026-10-14, `automotive:internal` COMPLETED; PUBLISH_OK). Adds the probe report upload: `POST /probe` file endpoint on the log receiver plus an `Upload latest report to log server` button in the probe activity reusing the explicit-tap HTTPS-only contract (report.json already embeds `rawLiveEvents`, ZIP/CSV stay local). Codes 93 and 94 were found already consumed on Play (published outside this repo record). Previous high-water mark: versionCode 92 (`4.0+topbar-space`, 2026-10-03, `automotive:internal` COMPLETED; upload and Play edit commit succeeded). Reserves the custom top strip from the laid-out window's stable status / cutout top insets (fraction only as fallback) instead of from a hardcoded 960, applies the sister app's `LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS` policy in `DisplayPrefs.applyWindow`, persists per-mode geometry, centers on a root/latched mismatch, and hides (not moves) the bar when GM's status bar is transiently visible. Verified on the AAOS emulator with a synthetic top+left cutout: four screen-mode previews PASS, four surface corners normalize in-range, a live resize logs the mismatch, and a transient status bar does not move the video; 155 self-tests pass. Actual GM-region recovery on the vehicle is unverified. Previous high-water mark: versionCode 91 (`4.0+topbar-sidebar-fix`, 2026-10-03): reserves geometry before SUBSCRIBE and simplifies the sidebar to five metadata-driven controls. Previous high-water mark: versionCode 86 (`4.0+7532873-topbar-test`, 2026-10-02, `automotive:internal` COMPLETED; publisher confirmed upload and committed the edit). Includes the opt-in top bar, 153 passing emulator self-tests, and the current USB announce/permission fixes. Prior recorded release: 83 (`4.0+usb-defer`, 2026-09-30). Next Play upload must be **> 91**. A lower-but-unused code is refused at commit with `403` does-not-allow-upgrade (seen 2026-09-30 with 63 < live 82); a reuse is refused with `403 Version code N has already been used.`
- **Publishing is one command, no Play Console needed:** `AAB_VERSION_CODE=<n> AAB_VERSION_NAME=<name> host/gm_ccpa/tools/build_aab.sh --publish`. It builds the JNI libs, packages and signs the `playRelease` bundle, then runs Gradle Play Publisher's `:app:publishPlayReleaseBundle` against the service-account key at `<repo root>/play-service-account.json` (gitignored; override with `PLAY_SERVICE_ACCOUNT`). The `play {}` block in `netprobe_app/app/build.gradle` targets track **`automotive:internal`** with status **COMPLETED**, so the build is live for internal testers as soon as the log reads `Committing changes` / `PUBLISH_OK`. Same mechanism as the sister apps (`~/Code/tmp_carlink_native_ivalen91_update/documents/Play_Store_Upload.md` has the one-time Console setup: API enabled on the Cloud project, SA invited with release rights, dedicated AAOS form-factor track).
- Do not commit `play-service-account.json`.
- AAOS emulator with `-usb-passthrough vendorid=0x1314,productid=0x2d00,hostbus=…,hostport=…` **does** deliver the adapter to a 3rd-party app (permission dialog, then full OCBM + HCI). That is a Linux-host USB-host proof, not an Equinox proof. Keep the emulator parent attached; detached qemu dies. Stale AVD lock files from a killed qemu block the next launch.
- **2026-10-18 session publish (this workstream): versionCode 103 (`4.0+insights-callpill-restart`) went to `automotive:internal` with PUBLISH_OK (commit `d2e2d5d`).** Contents: Insights settings tab (§10.1), in-call overlay pill (§10.2), failure-screen restart (§10.3), oversize audit fixes (§10.4). This does NOT advance the versionCode high-water recorded above; it is the latest session publish, verified by full `:app:testPlayReleaseUnitTest` green (22 new tests) plus an AAOS-emulator smoke run with zero `PAST_SAFE_RIGHT` overflows on all four tabs.

---

## 7. What is proven vs what is blocked (this vehicle)

| Layer | Server / emulator | Equinox EV |
|---|---|---|
| Box enumerates `1314:2d00`, OCBM HELLO/MFi/SUBSCRIBE | yes | pure accessory: never appears in `UsbManager`. **Composite: yes (2026-09-21)** |
| `accessory,mass_storage` composite, `2d00`, HELLO + FAT volume, across reboot | yes (2026-09-20) | **yes (2026-09-21)** — `iface=0 class=0xff`, HELLO_ACK 2 ms, MFi proven |
| IW416 `hci0` after subscribe | yes (`BOX_HEALTH 0x51`) | **yes** — `0x51` 10 s after SUBSCRIBE, `CarLink-6754` discoverable |
| Allow / Always-open dialog | emulator: yes | **Allow: yes** (device-confirmed 2026-10-22, dialog via attach + `requestPermission()`). **"Always-open" CANNOT persist on this stock car — closed, see `../../../docs/host/01_ANDROID_AND_AAOS.md` "VERDICT (2026-10-23)".** One Allow tap per drive is the floor: `android.car.usb.handler` is absent (no default-handler grant) and `usb_permissions.xml` is a SELinux `neverallow` for a `/data` app. Only a `/system/priv-app` + `MANAGE_USB` install escapes it, which the locked VCU does not allow. Attach intent now delivered on the composite |
| BT pair → iAP2 auth → Identify → `0x5703` hotspot handoff | Silverado: proven | **yes (2026-09-21 11:05 UTC)** — Just-Works pair, cert/sign, `IdentifyAccept`, `0x5702` → `0x5703` (`myChevrolet 32D4` ch 36 WPA2/3), `carplayd` up, `BOX_HEALTH 0x59`. Box drove the reconnect itself after rung 1 (bonded phone, `RFCOMM connected OUT`) |
| Phone joins the vehicle hotspot, advertises `_carplay-ctrl._tcp` | Silverado: proven (`br0`) | **yes (12:51 UTC) — after the driver joined manually.** Root cause of the morning's misses: the app was still sending its compiled-in defaults (`Ui.kt` `ssid = "myChevrolet 32D4"`, the Silverado's hotspot), a network that does not exist in this car. With the Equinox SSID entered, `Justin's iPhone._carplay-ctrl._tcp` resolved on `ap_br_swlan0` and `GET /ctrl-int/1/connect` → `200 OK` on attempt 1 (×5). iOS still did **not** auto-join — the SSID/passphrase in the app does not yet match the HU's hotspot page exactly, or the VCU hotspot is WPA3-only |
| Phone dials back to `:7011` (pair-verify onward) | Silverado: proven — **over IPv6 link-local** | **NOT reached — diagnosed.** With auto-join fixed (12:29 UTC) 20+ accepted connect-outs across three sessions, phone resolved our `A`, never dialled. Safari from the phone to `http://gmccpa-rx.local:7011/info` **hangs to timeout**: GM's IPv4 `INPUT` default-DROP (`01_FINDINGS.md` §2). Our self-hosted advert was A-only + "no AAAA" NSEC, so iOS had only the dropped path. `MdnsResponder` now publishes the interface `fe80::` AAAA too (versionCode 21, `4.0+ipv6-advert`) |

**versionCode 21 drive (16:56 UTC) — inconclusive on AAAA, and it found the real regression.** The Play update relaunched the app, which reset the hotspot fields to the compiled Silverado default `myChevrolet 32D4`; the Equinox SSID that auto-joined at 16:29 is `myChevrolet32D4` — one space apart. Timeline: phone already on the hotspot from the prior session, connect-out `200 OK` at +0.0 s, phone resolved us (`A AAAA`, twice) — then the app's CT_SUBSCRIBE carried the reverted SSID as a config *change*, and the box did a `CLEAN bring-up of wireless stack`: hci0 down/up, `PHONE_ABSENT`, BT link to the phone gone at +5 s. The phone's ctrl port went unreachable then `ECONNREFUSED` (iOS abandoned the attempt). The restarted 16:58 session handed the phone the wrong SSID and it never joined at all. **Credentials are now persisted in SharedPreferences and ship BLANK** (versionCode 22, `4.0+persist-creds`); the probe's existing empty-SSID refusal names the missing field. Enter `myChevrolet32D4` + passphrase once after installing 22.

**versionCode 22 drive (21:00 UTC) — the clean test, and AAAA alone is NOT enough.** Blank fields refused the SUBSCRIBE twice as designed; SSID entered once → persisted → `myChevrolet32D4` pushed. Fresh BT pair → `WIFI_HANDOFF` → phone on the hotspot **1 s later** (auto-join solid). Seven `GET /ctrl-int/1/connect` → `200 OK` over 77 s, every phone browse answered `PTR SRV TXT A AAAA`, and the platform NSD advert (`gm-ccpa` → `Android_….local`) independently carried the same `fe80::` — so the phone held our IPv6 the whole time. **No inbound.** Remaining explanation: the VCU's `ip6tables` `INPUT` accept names `br0` (gminfo37's bridge) and this car's bridge is `ap_br_swlan0`, so the phone's link-local SYN is dropped exactly like the IPv4 one. versionCode 23 (`4.0+fw-dump`) reads `/system/etc/iptables.rules` + `ip6tables.rules` at RX start and logs every `INPUT` line plus a verdict (`firewall (advisory)` check: `-i ap_br_swlan0:yes/NO`, `fe80-accept:yes/NO`, `dport7011`).

**versionCode 23 (21:27 UTC): `/system/etc/iptables.rules` and `ip6tables.rules` do NOT exist on the VCU** (`exists=false`, not a permission denial). gminfo37's layout does not carry over. Session otherwise identical to 22 (phone on hotspot, connect-outs accepted over IPv6, no inbound). versionCode 24 (`4.0+fw-scan`) walks `etc/` on every partition for anything named `*iptables*`/`*firewall*`/`*netfilter*`, greps `*.rc` there for `iptables` invocations, and dumps whatever it finds.

**versionCode 24 (21:38 UTC): the VCU firewall is a DAEMON.** Scan of every partition's `etc/` found exactly one netfilter artefact: `/system/etc/init/netfilterd.rc`. No `.rules` file exists anywhere; a GM `netfilterd` service programs the tables at boot. **And the hotspot topology changed:** six sessions on `ap_br_swlan0`, this one on plain `wlan0` (`fe80::7c99:…`) — on `wlan0` our IPv6 connect-out to the phone **timed out** both times while IPv4 got `200 OK`. Same no-inbound result. versionCode 25 (`4.0+fw-scan2`) dumps the `.rc`, string-scans the `netfilterd` binary for embedded rule text, and logs the interface inventory at every RX start.

**versionCode 25 (22:26 UTC): the interface inventory explains the `wlan0` flip — and invalidates two sessions.** The VCU has BOTH `ap_br_swlan0` (`fe80::3058:10ff:fed1:fc0c`, members `swlan0`/`swlan1`) and `wlan0` (`fe80::7c99:…`) up with IPv4. `wlan0` is the STATION interface — it only holds an IPv4 when the car has joined a Wi-Fi network (the owner's home network, in the driveway). `hotspotScore` gave `ap*` and `wlan*` the same 900 and the alphabetical tie-break picked `wlan0`, so the 21:38 and 22:26 sessions advertised the home network's addresses to a phone on the hotspot (and our `%wlan0`-scoped IPv6 connect-out timed out for the same reason). Fixed: AP-role names (`ap*`, `swlan*`, `br*`) now outrank `wlan*` (versionCode 26, `4.0+ap-iface`). The six `ap_br_swlan0` sessions stand. `netfilterd.rc` says `service netfilterd /system/bin/netfilterd`, `seclabel u:r:netutils_wrapper:s0`, no args — `bin/` dirs are not listable, so 26 probes the binary by path and string-scans it, and dumps `init.gmnetwork.rc`. Also: the launcher's six log pills overran the display and **Clear Logs was the one clipped** — now two rows of three.

**versionCode 26 (23:03 UTC): interface fix CONFIRMED, daemon unreadable, session still stalls.** `hotspot bridge: ap_br_swlan0` even with `wlan0` up and holding the home-Wi-Fi addresses (v4 + two global v6 prefixes) — the ranking fix works. Phone on the hotspot at +43 s, `200 OK` × 8 over 90 s, every browse answered `A AAAA`, no inbound. `/system/bin/netfilterd` is unreadable (`size=0` — execute-only under its SELinux label); the `iptables`/`ip6tables` binaries ARE readable (494848 B) but their strings are the tool's own help text, not GM's rules. `init.gmnetwork.rc` is IPv6 sysctl + static ARP for the `vt*` VLANs, nothing about the hotspot. versionCode 27 (`4.0+fw-live`) runs `iptables -S` / `ip6tables -S` (filter, nat, mangle — read-only) and a TCP loopback probe: a socket bound to the hotspot address connects to that same address:7011, so the SYN traverses INPUT on the hotspot interface exactly as the phone's would. `[fw] loopback probe via ap_br_swlan0: RECEIVED` = the firewall admits it and the stall is the phone not dialling; `DROPPED` = the firewall is the cause and the rules above say why.

**versionCode 27 (10:59 UTC): the loopback probe is NOT an INPUT test, and the rule dump lost a lock race.** `iptables -S` / `ip6tables -S` all returned rc=4 `Another app is currently holding the xtables lock` — six calls in 80 ms, none waited. The probe reported `RECEIVED` on `ap_br_swlan0` while Safari from the phone to that same address:7011 hangs: a same-host connect is delivered via `lo` and never traverses the hotspot interface's INPUT. Session otherwise identical (phone on hotspot, 8× `200 OK`, no inbound). versionCode 28 (`4.0+fw-lock`) passes `-w` so the listing waits for the lock, dumps `nft list ruleset` if present, and relabels the probe as a listener sanity check.

**versionCode 28 (11:12 UTC): the xtables lock is held continuously.** `iptables -w 8 -S` and `ip6tables -w 8 -S` each waited the full 8 s and exited rc=4 `Stopped waiting after 8s` — not a race. No `nft` binary. Session unchanged: phone already on the hotspot, connect-outs accepted over IPv6, no inbound. versionCode 29 (`4.0+fw-holder`) identifies the lock holder from `/proc/locks` (inode + pid + cmdline) and dumps conntrack entries for port 7011 at start and at the stall, which distinguishes "the phone's SYN arrived" from "it never left the phone".

**versionCode 29 (11:41 UTC): both kernel views are denied to the app.** `/proc/locks` unreadable; `/proc/net/nf_conntrack` exists but `readable=false`. The iptables binary's own strings name the lock file `/system/etc/xtables.lock`, which the holder check never stated because it returned on the `/proc/locks` denial. The upload ended at the 5th connect-out, before the stall snapshot. Lock still held for the full wait. versionCode 30 (`4.0+tcp-table`) stats `/system/etc/xtables.lock` regardless, cuts the futile wait to 1 s, and reads `/proc/net/tcp` + `tcp6` for local port 7011 (`1B63`) at start and at the stall — a `SYN_RECV` row means the phone's SYN reached the listener.

**OS-scope capture (18:07 UTC) did not add a system view.** `requested WHOLE_OS, READ_LOGS granted=false`, so the file is still `--pid=307`. What it did add: the stall banner at 18:05:10 (six `200 OK`, no inbound), and a live lookup of the platform advert — `gm-ccpa._airplay._tcp.local` → `Android_….local` port 7011 with an IPv4 address **and** `fe80::3058:10ff:fed1:fc0c`. That is the record version 31 left in place.

**versionCode 32 (`4.0+no-nsd-advert`) drive (18:14 UTC, pid 6337): withholding the platform advert did not produce a dial-back.** `platform _airplay advert withheld`, `advert-nsd` DOWN, live lookup `no SRV answer for gm-ccpa._airplay._tcp.local`. Self-hosted responder `AAAA-only [fe80::3058:10ff:fed1:fc0c] (ap_br_swlan0)`. Six connect-outs accepted, `STALLED` at 18:15:11, no `INBOUND CONTROL CONNECTION`. The advisory lookup of `gmccpa-rx.local` printed an IPv4 beside the link-local because an A query is answered with a TTL-0 withdrawal and the inspector counts that rdata; the positive record is the AAAA. The phone-dials-the-head-unit path has no remaining advert experiment.

**versionCode 33 (`4.0+adapter-wifi`) drive (21:58 UTC, pid 22006): the adapter came up, and the phone was told the wrong network.** Subscribe was `wifi_ap: true`, channel 149, HEVC. Bluetooth completed and `0x5703` answered `ssid="ccpa"` channel 149. The beacon was `ccpa-fe01` (the radio script's name, asserted on the controller a few seconds earlier). `ccpa` is the placeholder the app sends so the channel and passphrase get written. The app had scored the new link's first health tick (`0x40`, Bluetooth not attached yet) against the previous link's `0x5b`, cycled the radios, and the second credential write put `ccpa` back into `hostapd.conf`. `wifi_ap_on` then saw `hostapd` already running and skipped the rename. No video, no audio; the phone stayed on `myChevrolet32D4`. The health mask is now cleared at each subscribe, and the box-AP credential write uses `/etc/carplay_ident` instead of the placeholder. The script half is on the dongle only after the next `ocbm_install`.

**versionCode 34 (`4.0+ap-name`) drive (22:36 UTC, pid 2214): the phone joined `ccpa-fe01` and CarPlay came up.** Health was a baseline (`0x40` not scored as a missing controller). `0x5703` answered `ssid="ccpa-fe01"` channel 149. The phone took `192.168.43.100`, pair-verify completed, and the screen stream negotiated 2400×960. The app received the video key at 22:36:41 and logged `CarPlay live`, then the process ended. No `adapter session` line, no `video epoch open`. Reopening the app at 22:39 (pid 4481) subscribed again. The dongle still has the old supervisor, so that write put `ssid=ccpa` back into `hostapd.conf` while hostapd was already running, and the next `0x5703` told the phone to join `ccpa`. No audio, no video; Stop at 22:41:34. The app now reads `/etc/carplay_ident` before an adapter subscribe and sends that name.

**versionCode 35 (`4.0+ap-ssid`) drive (22:57 UTC, pid 10548): the right SSID, then the same force-close.** Subscribe and `0x5703` both said `ccpa-fe01` channel 149. The phone joined, pair-verify completed, and the screen stream negotiated 2400×960. The last app line is the voice `modesChanged` at 22:57:30; the process was gone by 22:57:43, when a new launch (pid 11011) logged `dropped VIDEO — no session armed`. `OWN_PROCESS` capture has no `AndroidRuntime` stack: the capture thread dies with the process. The death is the activity launch that follows the video key. `CarPlaySessionService` declared only `foregroundServiceType="microphone"` and called `startForeground` with no type argument. On API 34 that requires a granted `RECORD_AUDIO`. A Play install does not grant it. The `SecurityException` is thrown on the main thread from `onStartCommand` and the system force-closes the app. The service now pins the process with `mediaPlayback|connectedDevice`, and adds `microphone` only when the permission is held. The launcher asks for the microphone once. High-water is versionCode 36 (`4.0+fgs-pin`, 2026-09-22, `automotive:internal` COMPLETED). Next upload must be greater than 36.

**versionCode 31 (`4.0+aaaa-only`) drive (18:03 UTC, pid 307): the phone was given only the link-local address and still did not dial.** Startup logged `AAAA-only [fe80::3058:10ff:fed1:fc0c] (IPv4 withheld) (ap_br_swlan0)` and `announced PTR + SRV + TXT + AAAA (A withheld)`. Handoff `0x5703` ssid `myChevrolet32D4` ch 36, phone on the hotspot ~2 s later, four `GET /ctrl-int/1/connect` → `200 OK` via `fe80::188d:2578:38bb:1b6c%ap_br_swlan0:58877` (18:04:03, :19, :32, :44). Every query answer was `PTR SRV TXT AAAA NSEC/no-A` — the phone never sent an A question (`A/0` does not appear). No `INBOUND CONTROL CONNECTION`. The upload at 18:04:55 cut the capture off before the six-dial stall banner; the shape is the same as the longer stalls. The remaining confound — `NsdManager` still publishing `gm-ccpa` on `Android.local` — is what versionCode 32 removes, and that drive stalled the same way.

**Still open on Equinox, in order:**

1. **The Silverado Bluetooth-reconnect unstick does not apply here (11:50 UTC).** BT dropped to IDLE, the phone re-authenticated 12 s later, Identify completed, `0x5703` was sent again, and connect-outs kept being accepted for another 75 s. No `INBOUND CONTROL CONNECTION` at any point in the session. Combined with versionCode 30 (`/proc/net/tcp` and `tcp6` exist, `readable=false`; lock file is `/system/etc/xtables.lock` inode 1370; `/proc/locks` denied), the head unit can neither see nor change GM's firewall, and the one recovery that cleared this exact stall on gminfo37 did not clear it on the VCU. IPv4 inbound from the phone is already proven dropped (Safari, 2026-09-21), versionCode 31 withheld that address, and versionCode 32 withheld the platform advert that still published one. Neither produced a dial-back. **Box-AP (`wifi_ap:true`) is no longer held for lack of a head-unit experiment; it is still a different program from this app** (see the 2026-09-22 analysis). lvalen91/ocbm `91d8d455` (2026-09-18, "CarlinkAndroid 3P AAOS integration") does not touch `host/gm_ccpa` or the hotspot path. It records that `CarProjectionManager` is `signature|privileged` and unavailable to a third-party app, and it keeps CarlinkAndroid on the adapter-Wi-Fi / USB-media architecture. `gminfo_resources` `30d4eac0` (2026-09-20) is Silverado 2500HD harness and Ethernet-bus documentation. Box-AP is the topology the sister Carlink app already runs (phone joins the dongle, pixels cross USB) and the one `04_SYSTEM_MODEL.md` §4a deliberately left: the phone must join the *vehicle* SoftAP so the receiver can live in this app and land audio on GM's volume groups. Flipping the flag while `CarPlayRx` is still the endpoint strands the phone on a network where `:7011` is not reachable. versionCode 33 adds that role as a switch that is **off by default** (the Silverado stays on the vehicle hotspot). On, the app does not advertise `:7011`; the adapter's AP comes up on channel 149 and the phone joins `ccpa-<4hex>` from `0x5703`. This app decrypts the USB lanes into the existing `HevcRenderer` / `AacPlayer` / `VoiceRouter` players. It is not the sister app's `0x55AA55AA` path, and it does not collapse audio onto one `USAGE_MEDIA` track.
2. Ship the Wi-Fi fields **blank** so the probe's empty-SSID refusal forces entry — the Silverado defaults cost the first three drives. Auto-join now works with the Equinox SSID entered.
2. **Auto-relink after the 10 min attach abort — resume-relink landed, vehicle proof pending.** 2026-10-15 pid 4683 showed the full warm-start shape: remote warm-up powers USB with the display off, so the reclaim's permission dialog is deferred (`deferring permission dialog — display off`), the claim loop aborts after 10 min (`usb-claim FAILED … last state: present-no-permission`), and opening the app only resumes MainActivity — `dispatchIntent`/`autoStart` never re-run, so it sat FAILED with the adapter on the bus. `MainActivity.maybeRelinkOnResume` now reclaims when resumed with USB_CLAIM FAILED on the board, no attempt in flight (`OcbmProbe.linkAttemptInFlight`), and `1314:2d00` in `deviceList`. An explicit Stop resets the board so it is never mistaken for a fault; the 20 s reclaim debounce still applies. The display-off deferral flag also resets per episode in `armPermissionWatch` (it had stuck since 22:17, silencing later deferrals). No background timer: re-enumeration is still covered by the usbdiag ATTACH watcher; this closes only the already-present-but-dead gap. Ships in the next build; warm-up → get-in recovery unverified on the vehicle.
3. Ship the `UsbBulkTransport` vendor-interface preference + the `onBoxLinked` no-regress fix (versionCode > 20). GM happened to order the vendor interface first, so the shipped build worked; do not rely on it.
4. ~~Power from that jack, control over TCP~~ — **retired**: USB bulk is reachable on the VCU with the composite.

---

## 8. Storage-only USB admission trampoline (implementation, not vehicle-proven)

The owner's 2026-09-26–28 AAOS 14 captures describe `UsbHostManager` admitting a device with class/subclass/protocol `0/0/0` when its trimmed serial matches the last removed admitted USB device. The firmware change is explicitly an experiment based on those observations; it does not establish that GM admits a storage-only device with no medium.

`ccpa/rootfs/script/ocbm_boot.sh` now defaults to a mass-storage-only phase and then re-enumerates as OCBM. The announce PID is **`0x2d06`** (VID `0x1314`, class `0/0/0`), distinct from OCBM `0x2d00` and stock/NCM `0x1520`/`0x1521`. The announce PID is intentionally not in the GM app filter; only `0x2d00` speaks OCBM. Both phases use the same trimmed per-unit serial. The announce LUN is removable and defaults to an 8 MiB FAT image (`usb_announce_medium=image`). `usb_announce_medium=none` clears the backing file; that is the comparison if the image produces a USB-drive popup, a media-source switch, or the car's media indexer mounts it. No-medium admission remains unverified.

Configuration is read as key/value data from `/script/ocbm.conf`: `usb_announce=1` and `usb_announce_dwell_ms=1500` are the defaults; `usb_announce=0` skips the announce. If no unique per-unit serial source can be found, the announce is disabled rather than using the vendor-generic `0123456789FEDCBA` value. The log records each bind/release with uptime. If no OCBM `CT_HELLO` arrives, the boot watcher repeats the announce `usb_announce_retries` times (default **0**, clamped 0..3; updated 2026-10-04) at k × `usb_announce_retry_ms` after the first OCBM bind (`usb_announce_retry_ms` default 75000, clamped to 15000..300000), so explicitly enabling two retries uses 75 s and 150 s. Defaults schedule no retries: re-enumerating under the driver's open permission dialog kills the grant. The 30 s mark matches the app's permission-dialog timeout, so a retry there disconnects the device while the driver is still granting it. The retry does not look at gadget `CONFIGURED`: that state is normal while the car is refusing the device. The NCM failover watchdog ignores the ocbmd gap while that announce holds the gadget, for 15 s after it releases, and for 10 s after a retry returns, and logs `failover: ignoring ocbmd gap during announce` once per announce. `ocbmd` records the first HELLO in `/tmp/ocbm_hello_seen`; this is an internal tmpfs signal and does not change any OCBM frame. Later gadget rebinds go straight to OCBM.

The previously proven `/script/ocbm_udisk` composite (storage plus OCBM, PID `0x2d00`) is a separate mode and does not prove this storage-only trampoline works. Confirm storage-only admission, detach, and OCBM reconnect with matching serial on a GM vehicle before describing the admission strategy as proven.

---

## 9. v99 vision launcher (2026-10-15, versionCode 99 `4.0+vision-launch`)

Three findings from the day's bundle (`gmccpa-20261015.log` + three 2754x966 captures) that the new launcher is built on:

1. **Templates and search images must share one scale.** v98 matched a 32x32 canonical template against a 192x72 working image — a ~2x scale mismatch that scored 0.33 against its own source frame. v99 box-averages the full frame at 1/4 scale and cuts every template at that same scale (strip 18 px, grid 38 px, glyph 15 px); strip templates match on all three captures at 0.999–1.000 and grid templates only on the Home grid (best false score 0.547).
2. **AC Home is dropped while D-Pad isn't advertised.** The log shows `nav 0x06 dropped — D-Pad not advertised (CARPLAY_DPAD off)` for both the launcher and the top-bar NAV button, so the launcher never sends AC Home: it taps the strip's Home/Dashboard toggle instead (the only blind tap it ever sends), and the strip's bottom glyph separates Dashboard from everything else at 1.00 vs 0.29–0.33.
3. **The saturation `classifyScreen` labelled the Spotify app as HOME_GRID, so treat it as unreliable.** cap1 is the Spotify app, not the Home grid — the "dock row y 0.72–0.88" hint was podcast artwork. The classifier and the capture sidecar format are unchanged (out of scope); the launcher trusts only template matches ≥ 0.85 and the glyph surface check, and after every tap it waits for the screen to CHANGE before waiting for it to settle (a plain stable-frame wait can return the pre-tap frame).

---

## 10. v103: Insights tab, in-call pill, failure-screen restart (2026-10-18, versionCode 103 `4.0+insights-callpill-restart`)

### 10.1 Insights tab renders the head unit's own probe segments like the evinsights website

New fourth rail tab (`Ui.kt`, `InsightsPage` in `InsightsUi.kt`, data in `telemetry/InsightsData.kt`): latest-value tiles (Battery Wh, Range km, Speed km/h, Outside °C, Odometer km, Charge ±kW — the website's units), three canvas charts (Battery & range dual-normalised, Speed, per-segment battery trend), and the last 10 segments with per-row tap details. The website plots full-fidelity event streams; here the within-segment charts are the latest segment's live-callback tail (stride-sampled, ≤2000 pts) and the trend is per-segment last values — the page subtitle says exactly that. Live callbacks win over census reads; bad-status and non-numeric samples never poison a value; malformed segments are skipped, never fatal. Multi-user trap found during verification: on this AAOS build the app runs as user 10, so `getExternalFilesDir` resolves under `/storage/emulated/10/…` while `adb push /sdcard/…` lands on user 0 — seeds pushed to `/sdcard` are invisible to the app. Unit tests: `InsightsDataTest` (5), `InsightsChartTest` (4).

### 10.2 In-call overlay pill (answer / hang up / mute), draggable, auto-hiding

`av/CallPillView.kt` on the CarPlay screen, driven by `NowPlayingState.callPhase()` (`callPhaseOf`: empty → NONE/hidden, any state-2 → RINGING, else ACTIVE). Answer is offered while RINGING only; Hang up sends `TEL_END`; Mute toggles `TEL_MUTE` (existing `Ocbm` opcode, same path as the rail phone button, which stays as the at-a-glance indicator). Dragging on pill background repositions it within the parent; the position persists as parent-relative fractions and clamps into smaller windows on restore. Unit tests: `CallPhaseTest` (10: phase mapping, clamp, first-run top-center park, stale-fraction clamp).

### 10.3 Connect-failure recovery is one tap away (the STALLED complaint)

The log case was `RxPhase.STALLED` → `LinkState.FAILED` with the only Restart buried on Troubleshooting behind the intro layer. Now: the status strip carries a primary **Restart** while FAILED on every tab (`stripActionFor`: live → Back, FAILED → Restart, else Done — unit-tested in `UiStripTest`); the strip detail allows two lines so the "press Restart Session again" instruction is never ellipsized away; and the intro failure screen gains a circular-arrow restart button bottom-right, visible only while FAILED (`syncIntroRestart`, driven from `refreshDynamic`). `setRestartBusy` refreshes the strip label too.

### 10.4 Oversize audit: one real fix, the rest retired as content-height

The audit (`auditLayout`, `PAST_SAFE_RIGHT` marker) found **zero horizontal overflows on all four tabs** — Settings/Display cards, Troubleshooting status/launch/capture/log cards, Advanced adapter/display/picture/logging/upload/recognition/app cards, and the 2705 px Insights page all end at x=988 against a 1024 safe edge (long vertical pages scroll; that is correct). One real fix: the pairing-code row at 40sp rendered ~54sp under the 1.35x panel scale cap and pushed the Status card — and its Restart button — off screen; now 30sp. Strip detail went 1 → 2 lines (see §10.3) with no width change. `SETTINGS_AUDIT.md` was stale (it still lists the v100 `Connection | Display | Audio | Logs | Advanced` rail); its rail line and page map are corrected to the shipped `Settings | Troubleshooting | Advanced | Insights` structure with a v103 deltas section — corrected in place, no new file (docs budget).

---

## 11. 2026-10-17 drive-log audit: midday song/artwork staleness was "nothing playing"

Midday sessions (12:01–12:10, four short sessions around an operator Restart) showed the
complaint verbatim: one `card: state=1 pos=0` line per session, never a `card: metadata` line,
artworkIds advancing (-1 → 128 → 129) with zero `art … accepted` transfers. Evening (21:30+)
was healthy: track changes with metadata + 300x300 art decodes, position advancing via
`source=tick`.

Evidence it is phone-side silence, not a box/app drop:

| Signal | Midday (4 sessions) | Evening (working) |
|---|---|---|
| `0x5001` msgs | 4 per session (54/22/27/9 B open burst), then silence for minutes | 50+, incl. 451× 12 B ~2 Hz elapsed ticks |
| `TX session-2 reply` | **0** — the two 10 B Setup fragments per session were never answered | 8 — Accept sent, then 65525 B + 18449 B JPEG fragments |
| Audio frames played (`aac stopping`) | **0 in every session** | normal playback ("drop dead" on the card) |
| `card: metadata` lines | 0 | every track change |

The phone sent its session-open burst, announced artworkIds for a queue it never described
(no titles → the merge correctly published nothing), never played audio (no ticks), and offered
art only as size-0 Setup probes — which the box correctly ignores without an Accept, since
answering would stall the phone waiting for data that never comes. `enablesFileTransfer: false`
in the OCBM subscribe block is a red herring: it reads false in the working evening sessions too.

Two diagnosability gaps made this cost a full audit, both now closed (no behaviour change):

1. Box `iap2-core/metadata.rs::on_session2` logged only the Accept path. Every silent ignore
   now says why on stderr: truncated datagram/setup, size-0 probe ("no image offered"),
   oversize setup, data for an unknown id, Cancel of an in-flight transfer. Rust test
   `artwork_ignores_are_silent_on_the_wire_but_decisive` pins the wire behaviour (still None,
   no residue — a later valid Setup still Accepts).
2. App `NowPlayingState.onNowPlaying` logged only artworkId transitions. Track changes now log
   a one-line summary (`np: track "…" artist=yes/no status=… artworkId=…`, consistent with the
   existing `mbs` card title logs), and title-less records naming an artworkId log once per id
   (`titlelessRecordEvent`, pure and unit-tested in `NowPlayingLogTest`). The next audit of this
   shape ends at the first grep: probes vs real offers, titled vs untitled records.

---

## 12. 2026-10-18: position/playlist publishing fixes + edge-trace calibration (v205)

### 12.1 The phone splits a track change across frames — the merge did not know

Evening 2026-10-18 logs (healthy playback, ticks at ~2 Hz, `state=3 rate=1.0`) showed every
track-change record as `status=? artworkId=?`: the title/ artist frame carries NO
PlaybackAttributes and NO artwork id; the id follows in a title-less frame ~2 ms later, then
the JPEG transfer. Four app-side defects followed from assuming one complete record:

1. **Wrong cover flash.** `ArtworkKeeper` kept the previous id across a title frame with no id,
   so each new track published once with the OLD cover (`Blank Space art=300x300`), then
   `art=none`, then the right art — three `setMetadata` rebuilds per track. A title without an
   id is now a new item with unknown art (cleared, matching the sibling `NowPlayingState`), and
   the id follow-up / adopt path restores it. Pinned by `ArtworkKeeperTest` (5).
2. **Card seconds stutter.** Every ~450 ms tick called `setPlaybackState`, re-baselining AAOS
   interpolation on jittered phone timestamps — the designed path is interpolate-between-pushes
   (the sibling Media3 `MediaSessionManager` pushes only on change/seek/duration for the same
   reason). Ticks now publish only on state/rate change or a >2 s seek deviation; explicit
   paths (status, focus, replay) always push. Tick log spam (1850 lines/window) goes with it.
3. **Seeks froze.** Rate was `status == PLAY`; SEEK_FWD/BACK publish PLAYING with rate 0.
   Rate now follows the published state.
4. **Stale rail icon.** The rail play/pause glyph refreshed only on metadata change while
   status arrives in sparse separate frames — it now refreshes on ticks too (change-gated).

Also new: a single-item `setQueue` (current track) so the car's queue UI moves with track
changes, and duration/status/queueId on the `card: metadata` line. What is NOT fixed, by
wire-protocol design: real playlist *contents* need iAP2 `MediaLibraryUpdate` (`Tier::Capability`,
`0x4C04`), which iOS refuted on the wireless tunnel with an unrecoverable `0x1D03` — and
queue/track numbers are parsed but deliberately NOT subscribed (the proven 40 B baseline;
a 2026-07-12 superset subscribe degraded to zero titles). The queue carries the current track,
never the phone's list.

### 12.2 Edge-trace calibration replaces the dp inset sliders

Manual per-edge dp sliders cannot express this panel (straight left/bottom, top sloping down,
right swooping to the bottom), and the right-margin presets duplicated the YAML safe area.
Settings ▸ Display now offers **Trace edges**: a full-panel activity where the driver paints
the DEAD glass; `ScribbleSolver` (pure, `ScribbleSolverTest` (4) on a synthetic 2914x1134
panel) takes per-band dead-paint extremes, median-smooths, seeds untraced bands from the live
cutout, gates votes to central ranges (a full-width top stroke must not vote the right
profile), derives T as the deepest dead paint spanned and searches R for max area, and applies
a fingertip safety margin only where paint contributed. At capture time the solved rect sizes
the advertised `viewWidth`×`viewHeight` directly (with presentation dp insets forced to 0, so
iOS encodes at the true visible aspect ratio rather than squishing horizontally after decode),
the strip above it becomes the top bar's space (bar auto-armed on save, and its right cluster
insets by the greater of `safeInsetRight` and `VideoFrame.rightPx`), and the YAML safe inset
is forced to 0. Sliders and margin presets are removed; untraced sessions honour the measured
cutout bounds plus the last saved margin pref. Takes effect at the next connection.

---

## 13. 2026-10-20: Insights becomes an in-car EV telemetry workbench (v209)

### 13.1 What the server data proved before a line of UI was written

The 494 server segments (21 `bootId` groups) settled every open derivation question. WHEEL_TICK
is a validated distance source on the Equinox EV: `configArray [15, 24145×4]` (24.145 mm/tick),
reset count stable at 0 across all 4,573 callbacks, four wheels agreeing to 34.5 km ±0.01 % in a
single boot session — so wheel distance is the primary source, speed integral second, GPS third,
odometer absent (PERF_ODOMETER is `ABSENT_FROM_PROPERTY_LIST`: CAR_MILEAGE is
signature|privileged, denied to third-party apps — five other permissions share that fate:
CAR_TIRES, CAR_DYNAMICS_STATE, READ_CAR_STEERING, CAR_DRIVING_STATE, CAR_IDENTIFICATION).
EV_BATTERY_LEVEL is energy in Wh against INFO_EV_BATTERY_CAPACITY 84,590 Wh (SoC% is derived,
never sensed); EV_BATTERY_INSTANTANEOUS_CHARGE_RATE is signed mW (negative = discharge) and
integrates to gross discharge/regen Wh; RANGE_REMAINING is the GOM in meters. The car reports
DISTANCE_DISPLAY_UNITS=36 (MILES), so US Imperial is the default unit system with a Metric
toggle. `report.json` already embeds `rawLiveEvents`, so the loader accepts segments with or
without `events.ndjson` — the 106-segment emulator seed set is straight server files renamed to
segment directories (no conversion step to rot).

### 13.2 Four sub-tabs, one persisted unit switch

Overview (SoC gauge with 80 % target tick from EV_CHARGE_PERCENT_LIMIT, live state KPIs, fleet
rollup, speed-band distribution bar, distance-source cross-validation card, SoC%/range trend),
Drives & Charging (sessions grouped by `bootId`, classified DRIVE/CHARGE/PARKED, each with
efficiency in Wh/km + mi/kWh, regen recovery %, GOM accuracy ratio, and stitched session charts
including a GPS route trace coloured by speed), Signals (all 37 VHAL properties with callbacks,
observed Hz, min/max, live value + the 15-permission census), Segments (per-chunk metrics with
upload state and tap-to-inspect high-resolution charts). Charts gained area gradients,
gridlines, and axis labels; `routeGeom` is pure and unit-tested like `chartGeom`. Unit tests:
`InsightsDataTest` (7: wheel distance, session efficiency/regen/GOM, embedded events, Metric/US
formatting), `InsightsChartTest` (5: + route projection bounds). Full Gradle suite green.

### 13.3 The emulator is a memory-limited resource — verify early, commit the evidence

This host's swap filled 100 % and QEMU died five consecutive launches with identical
"QEMU2 main loop / CPU0 thread hanging" signatures; the sixth (1 GB guest, `-no-boot-anim`)
survived 8+ minutes without producing an adb device before its qemu child also quietly died.
The proximate cause was build-daemon residue (a 1.1 GB Kotlin daemon + 600 MB Gradle daemon
from the test run); the structural cause is other tenants' dev stacks (evinsights postgres
~4 GB, immich) that are not ours to kill. Lesson: run emulator verification BEFORE the Gradle
suite (daemons linger), or `--no-daemon` the test run, and treat screenshots as perishable
evidence — this v209 went out with JVM-green tests and a clean kotlinc compile but no fresh
screenshots, which is recorded here rather than implied otherwise.

---

## 14. 2026-10-21: now-playing sync — what the 2026-10-20 drive (v209) actually shows

Complaint: the AAOS media card's **time remaining**, **artwork** and **play/pause glyph** do not match
the audio. The 2026-10-15 capture could not settle it because that build's card line had no
`dur=/status=` fields; `bb51175` (v209, `4.0+insights-workbench`) does. This section is that capture,
analysed field by field. Two defects are fixed by it (§14.3, §14.4); one is real, reproduced twice, and
deliberately **not** touched without hardware (§14.5).

### 14.1 Capture

Upload of 2026-10-20 (`logs.txt`, **718 MB**; this analysis used the last 60 MB, which carries four
builds end to end). Target: `pid 28313`, `v=4.0+insights-workbench build=bb51175`,
**162,451 lines, 21:28:49 → 22:29:49 UTC**. Volume is not the story: 307 × `0x5001`, of which **266
are the 12 B elapsed-only tick** and only 9 are full records. That ratio is why the merge rules matter.

| Signal | Count |
|---|---|
| `np: track "…"` (title changes) | 9 |
| `np: record without title (artworkId=…)` (id follow-ups) | 15 |
| `card: metadata` publishes | 49 — **9 of them `dur=0`** |
| `card: state=` pushes | 20 (13 `source=status`, 5 `source=tick`, 2 `source=focus`) |
| `np: art … accepted` / `card: art decode` | 11 / 11 |
| `art dropped on title change` | 6 |
| `media playing at gain 0 with no focus held` | 4 |

The card line is exactly the shape the previous build lacked:
`card: metadata "Last Night" art=300x300 dur=163854 status=1 queueId=129 (sessionUp=true)`.

### 14.2 The phone splits a track change into three frames (timings now measured)

`22:21:38.058` title only (no PlaybackAttributes, no artworkId), `.069` title-less record naming
`artworkId=130`, `.189` the 160,674 B JPEG, `.194` `card: art decode`. §12.1 holds: title → id → JPEG,
11 ms then 120 ms. Two consequences, both confirmed here:

- a track change publishes **three** `MediaMetadata` rebuilds (`art=none`, then `queueId`, then
  `art=300x300`) — 49 publishes for 9 track changes, and the card shows ~100–150 ms of no art;
- the **title frame carries no PlaybackAttributes at all** — no `elapsedMs`, no `playbackStatus`.
  That is §14.4.

### 14.3 FIXED: a `durationMs: 0` from the phone erased a known duration

`22:21:37.536` publishes `dur=163854` for "Last Night"; **3 ms later** `.539` publishes the same title,
same artwork id and same `queueId` with **`dur=0`**. `21:59:16.277` is the same shape on "230. What
Happens When the Trump Loyalty Test Goes Wrong?" (`dur=2438400` → `dur=0`), restored to `2438400`
0.5 s later at `.804`. The only code path that can change a published duration is a record carrying
`durationMs`, so iOS stated **0** — a duration it had not resolved — and the merge took it literally
(`json.optLongOrNull("durationMs") ?: base.durationMs`; `0` is not null). `publishNow` then omits
`METADATA_KEY_DURATION` (`if (s.durationMs > 0)`), so the card loses its remaining-time input until
some later frame restores it — and for a track that never gets one ("Call It What You Want",
`22:26:48`, `dur=0` for the whole item) it never comes back.

Fix: `mergeDuration(prev, incoming)` in `NowPlayingState.kt` — a **non-positive** `durationMs` is "not
stated" and leaves the previous value standing. A track change still starts at 0 (its base is blank),
so a new item never inherits the old length. Pinned by `zeroDurationNeverErasesAKnownOne`.

### 14.4 FIXED: a track-change frame published a fabricated `pos=0 rate=1.0`

`22:21:42.043` publishes the new title "The Fate of Ophelia" (from 549 ms on the previous item) and
`.045` publishes `card: state=3 pos=0 rate=1.0 source=status` — position 0, rate 1, from a frame that
stated no `elapsedMs`. `elapsedMs` fell through to the blank track-change base, so 0 was published as a
real position. The next tick corrects it only when the true playhead is more than `SEEK_DEDUP_MS`
(2 s) away; otherwise AAOS interpolates from a baseline that is off by whatever elapsed the phone had
on the new item — and a boundary received while the phone is **paused** has no tick coming at all, so
the card sits at 0:00 with `remaining = full length` for the whole paused item.

Fix: `Snapshot.elapsedKnown` (excluded from `equals`, like `elapsedMs`, so it never triggers a
metadata rebuild) is cleared by a track-change record that omits the field and set by any record that
states it. `publishedPosition()` then publishes `PlaybackState.PLAYBACK_POSITION_UNKNOWN` instead of a
fabricated 0, and `publishState` treats a push that left the position unknown as owing AAOS the first
real one — otherwise extrapolating from `-1` makes every later tick look "within 2 s of the line" and
the card would sit with no progress indefinitely. The card log prints `pos=?` for it. Pinned by
`unknownPositionIsNotZero` and `snapshotIgnoresElapsedKnownForMetadataEquality`.

### 14.5 OPEN — the silence chain: GM's source-change probe takes focus, and nothing recovers

This is the "play/pause doesn't match the audio" half, and it is **reproduced identically twice**:

```text
21:37:07.400  transport: first caller package=com.android.car uid=1000 — candidate steering-wheel source
21:37:07.401  transport: pause caller=com.android.car uid=1000 dropped — source-change probe
21:37:07.411  transport: stop  caller=com.android.car uid=1000 dropped — source-change probe
21:37:07.795  media focus LOSS — request cleared; waiting for user play        (+0.38 s)
21:37:07.797  focus: LOSS(permanent) → phone PAUSE sent=true (status was 1)
21:37:07.945  [input] media-btn index=2 sent=true
22:22:45.289/.302  the same pause+stop probe pair
22:22:45.858  media focus LOSS — request cleared; waiting for user play        (+0.56 s)
```

The app does the right thing at the edge: gain 0, phone PAUSE, card PAUSED. What it cannot do is
recover, because `permanentFocusLoss` is latched for the session and `onStreamStart` returns early:

- `22:03:21` — a new media stream (26 min after the previous one ended, so a phone-side play): decoded
  at gain 0 for 34 minutes, **23,194 frames played, 23,089 of them at gain 0**, until the lanes retired;
- `22:21:37` — recovered only because the driver pressed play in GM's UI at `22:20:13`
  (`transport: play … forwarded` → `media audio focus: GRANTED`);
- `22:22:45` — same probe → same loss → `22:24:28` another phone-side stream, playing at gain 0 again.

So the phone streams, the card says PAUSED, and the cabin is silent — while the *other* common
presentation is a card frozen on the last track. The discriminating line in any future capture is
`media playing at gain 0 with no focus held — driver hears nothing` (4 occurrences here).

**Not changed yet, on purpose.** The comment on `AacPlayer.requestFocus` records a device experiment
(`4.0+mirror`) where re-requesting inside the loss callback made the phone tear the media stream down
after 630 frames. A fix has to pick one of two contracts and prove it on the truck:

1. treat the `com.android.car` pause+stop **probe pair** as bookkeeping rather than a displacement —
   do not latch `permanentFocusLoss` (and do not send PAUSE) when the LOSS lands within ~1 s of that
   pair, and re-request focus once; or
2. keep the latch for a real LOSS but let a **fresh media stream with the phone's own PLAY** reclaim
   it, and on refusal stop decoding rather than burning 34 minutes of muted audio.

Option 2 is the smaller change, but the log cannot confirm what the phone thought at `22:03:21`: the
only `0x5001` frames there are 12 B ticks — **status frames are not re-sent on resume**, so the card can
hold PAUSE while the phone plays. That is why this needs `dumpsys audio` alongside the next drive
(§14.7) rather than a guess.

### 14.6 Not the cause (log-refuted in this capture)

- **Artwork transfer:** 11 transfers, 11 `accepted`, 11 decodes, zero `rejected`, zero `setMetadata
  failed`. The blank frame is the structural three-publish flash of §14.2, not a fetch failure.
- **Subscription:** the tunnel identified and sent `3 subscribes (now_playing, route_guidance,
  call_state)` + `TX 0x5000`; 307 `0x5001` messages arrived.
- **Tick dedup:** 266 incoming ticks produced **5** `source=tick` pushes — the gate works as designed;
  a card whose seconds freeze between pushes is AAOS interpolation, not a dropped tick.
- **Buttons:** `transport: play … -> HID media 1 sent=true`, `next … -> HID media 4 sent=true`, and a
  wheel-key caller (`com.gm.rhmi`) is a real transport source rather than a source-change probe.
- **The `pos=0` at `22:21:37.538`** (as opposed to `22:21:42.045`) is the phone's own data — that frame
  carried `elapsedMs=0` with `status=1`, i.e. the item really did restart from 0:00 after the pause.

### 14.7 Still to capture (updated 2026-10-22)

1. `dumpsys audio` + `dumpsys media_session` alongside a drive, so "who holds focus when the card says
   PAUSED" is answered by the framework rather than inferred (§14.5).
2. One session paused by the driver and resumed **on the phone**, to see whether iOS ever sends
   `playbackStatus=1` on resume — that decides option 1 vs 2 in §14.5.
3. Whether the three track-change publishes can be coalesced without delaying the title; that is the
   only remaining candidate for the visible art flash.

### 14.8 The capture that answers §14.7 (v210, 2026-10-22)

§14.1 cost **718 MB** to ask a question that needed the last hour, and answering §14.5 on the truck
needs two kinds of evidence this build now writes. Both changes are instrumentation, not behaviour —
nothing about the card or the audio path moved.

**Upload range.** The Logs page gained `Upload range` immediately above the Upload Logs button:
**1 hour / 6 hours / Today / All**, defaulting to 1 hour (`UploadWindow.DEFAULT`, persisted in
`upload_center_prefs/log_window`). The range governs both the button and its own subtitle, because a
window nobody can see is how someone uploads an hour expecting the whole day. Filtering is by each
line's own logcat stamp — `LogTimeFilter` compares 19 characters of the fixed-width `-v year -v UTC`
prefix rather than parsing a date per line (~1.4 M lines/h), keeps lines with no stamp (capture
headers, seam notices) because they are the provenance of the artifact, and falls back to the bare
`MM-DD`/local form when the device rejected `-v year`/`-v UTC`. "Today" is **local** midnight, not a
rolling 24 h and not UTC midnight (`uploadCutoffMs`), because it means the driver's day.

Two consequences worth knowing before reading a future capture:

- an upload now **fails with a reason** when every line predates the window ("oldest capture on disk
  is …; choose a longer range") instead of reporting a successful one-line upload;
- the previous implementation accumulated every batch in memory before posting, so its peak was the
  whole capture. It now counts the window, then **streams** one 500-line batch at a time — a 768 MB
directory no longer means 768 MB of heap on the head unit.

**Music-sync instrumentation.** The card can be wrong two ways and §14.1 could distinguish neither:
whether the phone sent metadata at all, and whether anyone in the car bound the browser service. Now:

- `np: sync <n>s records=N (titleChanges= metadata= ticks=) artwork= accepted= ignoredKinds=` — one
  line per 30 s, printed only when something arrived. `records=0 ignoredKinds=60 lastIgnored=routeGuidance`
  says the `:9004` seam is alive but carrying only navigation, which a per-record log cannot show
  without drowning the capture;
- one `np: sync SILENT <n>s` per gap when **nothing** arrived (no records, no art, no ignored kinds),
  and `np: sync first nowPlaying record <n>s after the seam opened` on the session's first record;
- `np: sync session records=… artwork=… firstRecordAfter=never|<n>s` at session end — the one number
  that separates "the phone never sent music" from "the card showed the wrong track";
- `browser: bind #n package=… uid=… (sessionUp=… sourceSwitchable=…)` per `onGetRoot`, answering
  "was this app even in the source switcher".

Read together with §14.5's discriminator (`media playing at gain 0 with no focus held`), a 1-hour
upload after the next drive should be enough to settle option 1 vs option 2 without another 718 MB.

### 14.9 The first v210 drive (2026-10-22, 1-hour upload) — what it showed, what was fixed

The upload worked as designed: a 1-hour window, 2,219 stamped lines of pid 6573. Music timing was
correct (position 20004 ms at 21:10:02 → 97515 ms at 21:11:20 is 77.5 s over 78 s), no `dur=0`
publishes, no fabricated `pos=0`, 3/3 artwork accepted, `0 played at gain 0`; the §14.5 chain did not
trigger in this window. What it did show, and what changed (v211):

- **`np: sync first nowPlaying record 7769s after the seam opened` was wrong.** `NowPlayingState` is
  process-wide, so its clock started at app start. `beginSession()` now re-bases it when a session
  starts (`MainActivity.launchCarPlayUi`).
- **JNI exports named for the wrong package.** All nine `Java_zeno_gmccpa_pair_NativeCore_*` exports in
  `native/carplay-jni/src/lib.rs` never matched `wasidremin.gmccpa.pair.NativeCore`, so
  `nativeSetLimitedUI` / `nativeSetNightMode` died with `No implementation found` (428 + 65 hits in
  the server log): the limited-UI-while-driving and night-mode levers never reached the phone. The
  receiver that owns the control session runs on the box (`carplayd`), which already handles
  `CMD_LIMITED_UI_ON/OFF` and `CMD_NIGHT_MODE`, so `VehicleStateWatcher` now sends those over OCBM
  (`AdapterSession.sendCommand` / `sendNightMode`) and the two dead JNI wrappers are gone. The Rust
  exports were deliberately NOT renamed: nothing proves the in-process core is dormant, and renaming
  would silently activate it.
- **`transport: pause … HID media 2 sent=false` was not a failure** — the phone was already paused and
  the duplicate is suppressed on purpose. The line now says so.
- **Double bring-up.** The USB permission grant started a link at 21:08:45.8 and the attach intent the
  same grant produces started a second at 21:08:47.8, which stopped the fresh session and made the
  box's `ocbmd` respawn. `OcbmProbe.runAll` now answers a repeat within 10 s of a healthy, subscribed
  link with that link's result (a real replug kills the transport, so it cannot hit this).
- **Underruns: instrumented, not changed.** 8 `AudioTrack` underruns in ~3 min — 2–3 per stream
  restart (documented, §`TRACK_BUFFER_MS`) plus 3 in steady playback with no logged cause. Each
  underrun now logs the gap since the previous decoded frame (a ~21 ms cadence = the device starved with
  frames on time; a gap near 750 ms = the phone stopped delivering) and the 500-frame summary carries the
  longest frame gap. The buffer depth is untouched; `AacPlayer` documents that it must not be tuned
  without measurement.
- **Left alone:** the phone paused itself 6 s after a driver play (`partial TEARDOWN`, 21:09:55.7) — not
  explained by the log; `CH_FILE pull /tmp/wlan_off.log` / `supervisor.log` `OPEN_FAILED` (files absent
  on the box); 214 `requestLayout() improperly called during layout` warnings (cosmetic).

## 15. The top bar: nine CarPlay-style tiles (v211, regrouped 2026-10-23)

The seven thin outline icons (car, apps, controls, music, nav, phone, energy) are replaced by nine
CarPlay-style app tiles — rounded square, soft gradient, solid glyph. **Regrouped 2026-10-23 into two
families (`BarTarget.group`): our app first, then the car's own apps**, separated by a divider, with
each car-app tile flagged by a small corner **car badge** so "this leaves for the car" reads at a glance.

| # | Family | Tile (`BarTarget`) | Goes to | Mechanism |
|---|---|---|---|---|
| 1 | ours | `CP_HOME` | CarPlay home | vision launcher role `home` (strip Home toggle, surface-verified) |
| 2 | ours | `CP_MAPS` | CarPlay Maps | vision launcher `maps` (template match) |
| 3 | ours | `CP_SPOTIFY` | CarPlay Spotify | vision launcher `spotify` |
| 4 | ours | `CP_PHONE` | CarPlay Phone | telephony action while a call exists, else vision launcher `phone` |
| 5 | ours | `APP_SETTINGS` | this app's settings | `MainActivity`, tab 0 |
| 6 | ours | `INSIGHTS` | this app's EV insights | `MainActivity`, tab 3 |
| — | | *divider* | — | car-vs-ours boundary, drawn in the group gap |
| 7 | car | `AAOS_HOME` | the car's home screen | `CATEGORY_HOME` (GM's launcher) — badged |
| 8 | car | `CHARGING` | GM Charging | resolved: `com.gm.hmi.energy/…MainLauncherActivity` — badged |
| 9 | car | `AAOS_SETTINGS` | car settings | resolved: `com.android.car.settings/…Settings_Launcher_Homepage` — badged |

Rationale for the order: **"our app" is everything this app draws and keeps you inside** — the CarPlay
surfaces it projects (home/maps/Spotify/phone) and its own pages (settings/insights); **"car app" is the
three native AAOS destinations that leave the app** for the car's own UI. CarPlay tiles lead because they
are the app's purpose and the most-tapped; the app's own pages follow; the car destinations are grouped
last behind the divider. The two families must stay contiguous (ours then car) in the enum —
`BarTilesTest.theTwoFamiliesAreContiguousOursFirst` pins it, because the bar draws exactly one divider and
badges a single trailing block.

Destinations on the car are RESOLVED from what the head unit exports (`VehicleLaunch`, ranking in
`VehicleDestinations.kt`, pinned by `VehicleDestinationTest`), never hard-coded; a missing one falls back
to car home. **Where the CarPlay ones lead is only as good as the vision launcher:** in the 2026-10-15/17
logs `maps` and `phone` verified SUCCESS, `spotify` ended `VERIFY_FAILED` both times it ran (the tap fired
and the screen changed; the post-tap surface check disagreed), and `home` is new in v211 and unproven on
the truck. The owner's 2026-10-23 short drive reported all nine resolving as expected. The old
Spotify→Music fallback to a play/pause toggle is gone — an explicit Spotify tile that cannot find Spotify
now sends nothing.

**Fit.** `DisplayGeometry.topBarTiles` sizes one level, bottom-justified row so the LAST tile still
clears the panel slope (`size · (1 + m·spans) ≤ barH − 2.5·margin − m·x0`), capped at 0.66 of the bar
height, floored at 0.34, and shrinks further (×0.94 steps) if the right cluster would fall below 80% of its
natural width. `spans` now includes the extra group gap (`GROUP_GAP_FRAC` = 0.6 tile widths, folded into
the slope solve so the regrouped row still clears the slope), and the divider x is returned as
`TileRow.dividerX`. The sacred gap between the tile row and the right status cluster was widened from 0.5
to **0.8 bar heights** (`topBarLayout` `minGap`) because the clock/temp sat too close to the tiles to read
as a separate zone. On the Equinox (168 px bar, m = 0.0635) tiles are ≈ 81 px ending near x 1040, cluster
right-justified on glass near x 1600. Pinned by `BarTilesTest`/`TopBarFitTest`. Not yet seen on the
vehicle.

**On the settings screen.** `TopBarHost` builds and positions the same bar for both activities;
`LauncherUi.applyCalibrationFrame` reserves the strip (`VideoFrame.topPx` × the latch scale) at the top of
the calibration frame and starts the settings host below it. The tile for the current tab is ringed. A
CarPlay tile tapped there brings `CarPlayActivity` forward with `EXTRA_BAR_TARGET` and acts 900 ms later,
once the picture is up; with no live session it says so (toast + log) instead of doing nothing.

**Call card.** `CallPillView`: near-black glass panel with a faint edge, 76 dp discs in iOS green / red /
grey, solid handset (`phone.fill`), hang-up as the same handset rotated 135° (`phone.down.fill`), mic /
slashed mic, and an engaged mute as a white disc with a dark glyph — the way CarPlay's own call screen
reads. Preview without a phone: `--es run topbar_preview --es call_preview ring|active|muted`.
