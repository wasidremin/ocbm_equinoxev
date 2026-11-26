# GM AAOS field reference — platforms, vehicles, uDisk, and what the community has already proven

> **STATUS:** CURRENT · single owner for external GM-platform evidence (XDA thread, lvalen91 repos, per-vehicle reports). Created 2026-09-20. Correct this file in place — do not add a sibling.

**Purpose.** Everything we had to dig out of the XDA *Carlink* thread and Tachi91's GitHub on
2026-09-20, in one place, with links, so nobody has to re-read 26 forum pages or re-clone five repos
to answer "does the dongle work on vehicle X" or "why does GM say USB not supported". Correct this
file in place. Our own implementation write-up is
[`../../host/gm_ccpa/docs/14_LESSONS_LEARNED.md`](../../host/gm_ccpa/docs/14_LESSONS_LEARNED.md) §5;
this file is the *external* evidence and the platform map that write-up rests on.

---

## Current Equinox field behavior (2026-10-04)

- Boot announce is controlled by `/script/ocbm.conf`: `usb_announce=1`,
  `usb_announce_medium=image|none` (default image), `usb_announce_dwell_ms=1500`,
  `usb_announce_retry_ms=75000`, and **`usb_announce_retries=0` by default**.
  The announce briefly presents storage before the OCBM composite. Retries are disabled because
  an unsolicited gadget rebind tears down the host claim and can restart permission/startup while
  the driver is already using the app. An absent HELLO is not proof another announce will help.
  Whether storage-only identity is specifically what GM checks remains **open**, not established
  by a successful composite. The app exposes `usb_announce` and a separate retry setting.
- On Equinox **VCUUM-274.4**, `android.car.usb.handler` is absent. The app skips the otherwise
  8-second wait for system dispatch and asks for permission itself. Its `requestPermission`
  dialog is **not** the system USB app dispatch: selecting “Always” there does not persist a
  default attach handler. Seen attach candidates: `com.gm.isaplugin`,
  `com.seekingthescenery.carlinknative`, and `wasidremin.gmccpa`.
- The 2026-10-03 log distinguishes **`com.gm.car.media.gmcarmediaservice`**, GM's real media UI,
  from **`com.android.car`**, which sends source-change pause/stop probes. The wheel's track skip
  is **`com.gm.rhmi`** (uid 1201000): it sends `KEYCODE_F7` to the focused window, then skip-next on
  the media session 5–19 ms later (all 9 presses, 2026-11-08 to 11-14). Which caller carries a wheel
  play/pause, if any, is not proven. The wheel's phone keys: `docs/host/01_ANDROID_AND_AAOS.md`, "The
  steering-wheel call key". The focus-pause implementation forwards real commands even in
  the initial five-second arbitration window. Only `com.android.car` pause/stop callbacks or an
  opposite pause+stop callback pair within 100 ms are dropped; explicit media keys bypass filtering.
  A lone callback pause waits 100 ms to rule out a pair, then travels over CarPlay media HID.
  A live session with no HID channel retries that pause for up to three seconds.
- Media permanent focus LOSS mutes locally and pauses the phone once; it does **not** auto-resume
  on release or stream restart. User PLAY/source-selection PLAY requests focus before phone PLAY.
  Transient LOSS pauses and resumes on GAIN only if the user has not paused in between.
  Focus-driven HID commands are spaced by two seconds and honor the phone's PLAY/PAUSE/STOP status.
  Siri, call and navigation voice sinks are exempt; CAN_DUCK only lowers media gain. The card
  publishes PAUSED immediately on a successful focus pause, freezes its position, and retains
  known state rather than reverting to BUFFERING after content. Emulator verification is separate
  from a real-car/phone confirmation.
- The car's own Bluetooth **media** connection to the iPhone can steal the audio intended for
  CarPlay. Workaround: configure that link as **calls-only**, or turn it off. Do not mistake
  streaming at local gain zero for an AAC decode failure.

## 1. Sources — who is who, where things live

| Name | Who / what | Where |
|---|---|---|
| **Tachi91** (XDA) = **lvalen91** (GitHub) | Author of the Carlink app, the CCPA custom firmware, the alternate web UI, OCBM. Drives a **2024 Silverado ICE (`gminfo37`, AAOS 12, last Y181 update)**. Has **no EV** — cannot test uDisk himself. | [github.com/lvalen91](https://github.com/lvalen91) |
| XDA thread *Carlink* | The user community; ~26 pages, Jan 2026 →. Vehicle reports, uDisk workarounds, Tachi91's design statements. | [xdaforums.com/t/carlink.4774308](https://xdaforums.com/t/carlink.4774308/) — the useful pages are [24](https://xdaforums.com/t/carlink.4774308/page-24), [25](https://xdaforums.com/t/carlink.4774308/page-25), [26](https://xdaforums.com/t/carlink.4774308/page-26) |
| `lvalen91/carlink` | The Android/AAOS app (Kotlin). Stock Carlinkit protocol + OCBM. README has the GM platform notes (cluster icons, VCU vs gminfo37). | [github.com/lvalen91/carlink](https://github.com/lvalen91/carlink) |
| `lvalen91/ocbm` | The original project. Public history this checkout was cloned from — the open-source CCPA firmware replacement, developed on a Silverado. **Read-only from here.** | [github.com/lvalen91/ocbm](https://github.com/lvalen91/ocbm) |
| `wasidremin/ocbm_equinoxev` | This Equinox EV fork. Same history, plus the GM VCU / Equinox head-unit line (`wasidremin.gmccpa`). This is the remote this checkout pushes. | [github.com/wasidremin/ocbm_equinoxev](https://github.com/wasidremin/ocbm_equinoxev) |
| `lvalen91/CPC200-CCPA_resources` | Conversion landing page, docs, **live stock script snapshots** (`custom/scripts/live_snapshot_2026-06-29/`), custom firmware. | [github.com/lvalen91/CPC200-CCPA_resources](https://github.com/lvalen91/CPC200-CCPA_resources) |
| `lvalen91/CPC200-CCPA-Firmware-Dump` | The stock rootfs, RE notes, the alternate web UI (`web_interface/new_website/`), custom firmware `custom_firmware/2025.10.15.1127` (the telnet-root CFW RWerksman's guide requires). | [github.com/lvalen91/CPC200-CCPA-Firmware-Dump](https://github.com/lvalen91/CPC200-CCPA-Firmware-Dump) |
| `lvalen91/carplayd` | Pi-side wireless CarPlay using a CCPA in NCM mode purely as the MFi oracle (`CARPLAY_MFI_ADDR` path). | [github.com/lvalen91/carplayd](https://github.com/lvalen91/carplayd) |
| `lvalen91/carlink_flutter` | Archived. Old Flutter app. Ignore. | [github.com/lvalen91/carlink_flutter](https://github.com/lvalen91/carlink_flutter) |
| **RWerksman** (XDA) | Silverado EV owner (`'24 SEV RST`). Wrote the uDisk auto-arm guide (§4). | thread p.25/26 |
| **Razorfin** (XDA) | **Equinox EV** owner. The one data point that the Equinox enumerates a uDisk composite (§3). | thread p.24/25 |
| **f-io** (XDA) | Author of *LIVI* — a Pi/CM4 native CarPlay stack; also a custom CCPA rootfs that serves the MFi chip over TCP (`mfid` on `:5000`, mDNS) and L2-bridges USB to network. MFi chip-generation notes (§7). | thread p.25/26 |
| **Lurker1126** / mossyhub | *openautolink* — wireless Android Auto for AAOS, sidesteps USB entirely. Reverse-engineered the AA EV energy model. Since 0.1.575 (2026-11) also CarPlay; shows its cluster map on the driver display of a 2024 Blazer EV (VCU, AAOS 12L, `aegean_orange`, 2000x810) through an ordinary window on a public display, at the cost of GM's own turn icons and HUD directions. GPL-3.0, so this repo takes ideas from it, not code. | [github.com/mossyhub/openautolink](https://github.com/mossyhub/openautolink) |

Local clones (throwaway, re-clone as needed): `git clone --depth 1 https://github.com/lvalen91/<repo>` into `/tmp`.

**This checkout does not push to lvalen91** (2026-09-22). `origin` is `github.com/lvalen91/ocbm`
for history only. The owner of this machine does not publish commits, branches, or pull requests
to any lvalen91 repo — `wasidremin` has no write access, and a push there is the wrong destination
even if a credential someday has it. Local directory names sometimes spell the account `ivalen91`.
Read and clone freely.

The Equinox line is the fork [wasidremin/ocbm_equinoxev](https://github.com/wasidremin/ocbm_equinoxev)
(2026-09-23). It exists because lvalen91's tree is the Silverado (`gminfo37`) project and he has
no Equinox to test on, while this head unit is a GM VCU (Equinox EV, AAOS 14). The fork keeps his
history and points back at the original repo. `main` on that fork is what this checkout pushes.
The GM app also ships on the Play internal track.

---

## 2. Two GM platforms, not one

| | **gminfo3.7 / 3.8** | **GM VCU (Bosch VCUNH1)** |
|---|---|---|
| Vehicles | 2024–2026 ICE Silverado/Sierra, most ICE trucks | **All EVs** (Equinox EV, Blazer EV, Silverado EV, Sierra EV, Escalade IQ, Lyriq, Hummer EV) **and newer ICE** |
| Android | AAOS **12L** (`gminfo37`) | AAOS **14** (`burmese_orange-user 14`, `VCUUM-274.4`) |
| Pure AOA / vendor gadget (`1314:1520` stock, `1314:2d00` OCBM) | **Enumerates.** `UsbManager.getDeviceList()` lists it, Allow dialog appears. | **Refused.** HU toasts *"USB device not supported"*; `deviceList` shows only the Microchip USB249xx bridge (`0424:4915/49a0/4911`) and xHCI roots. No `USB_DEVICE_ATTACHED`. |
| `accessory,mass_storage` composite (stock `UdiskMode=1`) | Not needed | **Enumerates** — Allow dialog, working session. Device-proven Equinox EV, Silverado EV, Sierra EV, Escalade IQ (§3). |
| USB port hardware | direct host port | **USB24915P** automotive CarPlay multi-host bridge in front of the host port. It passes composites through; it is *not* iPhone-only (we believed that for a week — wrong). |
| Cluster maneuver icons | App-supplied icons **do** reach the cluster via the unregistered `ClusterIconContentProvider` authority (Tachi91 owns it on Play — nobody else can claim it) | GM VMSPlugin **ignores** supplied icons and forwards the maneuver enum (`setManeuverType`); the cluster draws **GM's own glyph** for it, next to distance, road name and ETA. So a third-party app gets full turn-by-turn, and the `Maneuver.TYPE_*` mapping is what decides the arrow (lvalen91 `gminfo_resources` `projection/cluster_navigation.md`, firmware-verified on a CT5). The GM app's path: `host/gm_ccpa/.../nav/ClusterNav.kt`. Consequence for enigy/carlink's cluster extras (checked 2026-11-07): junction-shaped turn icons, the lane strip image and the flashing turn alert are all icons, so they cannot show here; the exit label can, in the road text ("Exit 273 · …"), and is what the GM app took. enigy's directional `_WITH_ANGLE` roundabout types were not taken either: the CT5 atlas has no directional roundabouts, and an unmapped type risks a blank arrow. |
| NCM (`functions=ncm`) on the radio | Recognised as NCM-capable, **never brought up** — sits idle, interface locked to system | Same (Tachi91, p.25, Jul 8 2026) |
| "Always allow" for the USB app | broken: dangling `config_UsbDeviceConnectionHandling_component` (`android.car.usb.handler`) | absent `android.car.usb.handler` on VCUUM-274.4; app-requested permission “Always” does not persist |

Tachi91's own statement of the contract (p.26, Sep 2026): *"It's always going to be an accessory that needs to be claimed by an app. The OCBM/Bulk Message is the only way an app can claim it. If left in NCM mode the radio's stock CarPlay service will not bring up the network interface for it. Even in EVs the CarPlay service is running, just not exposed."* — i.e. USB bulk, claimed by the app, is the path on both platforms; the VCU platform just needs the composite to get there.

Standing GM risk he names (p.25): the CCPA only works because GM "hadn't blocked unknown devices entirely, which they can change at any moment via OTA." Alternatives he ruled out for third-party apps on GM: mass storage as a *data* channel (AAOS claims the disk; per-folder permission blocks; writes fail), UAC audio (unknown whether CarAudioService would claim it).

---

## 3. Vehicle reports (what actually happened, by owner)

| Vehicle | Platform | Firmware on dongle | Result | Source |
|---|---|---|---|---|
| 2024 Silverado ICE | gminfo37 | stock / CFW / OCBM | Works, pure accessory. Reference vehicle for Tachi91 and for this repo's Silverado proof. | Tachi91, throughout |
| **2025 Equinox EV** (Razorfin) | VCU | stock CFW 2025.10 + `riddleBoxCfg -s UdiskMode 1` **only** | Works. "USB device not recognized" toast **once** per start, then the HU "asks me to allow the Auto Box" and it runs. RWerksman's full 7-line `custom_init.sh` **broke boot on his Equinox** (no LEDs) — `UdiskMode=1` alone was the fix. iPhone Shortcut automation misfires because keyless entry connects the phone to the dongle Wi-Fi on walk-by. | [p.25](https://xdaforums.com/t/carlink.4774308/page-25), Jul 1 2026; [p.24](https://xdaforums.com/t/carlink.4774308/page-24) Jun 2026 |
| 2025 Equinox EV (BigBro84) | VCU | stock | Could not get the dongle to connect (Jan 2026, before uDisk auto-arm existed). Steering-wheel voice button dead under CarPlay; used "Hey Siri". | p.26, Jan 12 2026 |
| '24 Silverado EV RST (RWerksman) | VCU | CFW + uDisk auto-arm | Works on first plug with §4 "Solution 3". Multiple adapters. | [p.26](https://xdaforums.com/t/carlink.4774308/page-26), Mar 12 2026 |
| 2026 GMC Sierra EV (kossoo) | VCU | CFW | Works on first start every time; **hit-or-miss reconnect after a 5–20 min stop** (sits at "connecting"); fine again after a long off period. Unanswered in thread. | p.26, Sep 2026 |
| 2025 Escalade IQ Sport II (BazTST) | VCU | stock | Worked first time (Siri, Waze, Music, calls), then "USB NOT SUPPORTED" on the second start until uDisk toggled. | p.26, Jan 11 2026 |
| **Our Equinox EV** (`burmese_orange`, user 12) | VCU | **OCBM pure accessory `2d00`** | Never appears in `deviceList`, hours of `usb-claim FAILED`. **Consistent with every other VCU report for a non-composite gadget.** | `14_LESSONS_LEARNED.md` §1 |
| **Our Equinox EV** (same unit) | VCU | **OCBM `accessory,mass_storage` composite, `2d00`** (`ocbm_udisk.sh`) | **Works (2026-09-21).** Enumerates, Allow dialog in 4 s, OCBM `iface=0 class=0xff` HELLO_ACK 2 ms, MFi proven, SUBSCRIBE → HOST_PRESENT, `hci0` up as `CarLink-6754` 10 s later. First OCBM data point on a VCU radio. `USB_DEVICE_ATTACHED` still not delivered — driver used Restart Session. Phone pairing not yet tried. | `14_LESSONS_LEARNED.md` §5 |

Pattern: **every VCU vehicle needed the composite; every gminfo37 vehicle did not.** Nobody in the thread has an OCBM box on a VCU radio yet — we are first.

---

## 4. Stock uDisk mode — exact mechanics (from the firmware dump)

Files: `CPC200-CCPA_resources/custom/scripts/live_snapshot_2026-06-29/` → `start_accessory.sh`,
`start_accessory_mass_storage.sh`, `start_mass_storage.sh`
([tree](https://github.com/lvalen91/CPC200-CCPA_resources/tree/main/custom/scripts/live_snapshot_2026-06-29)).
Same files under `CPC200-CCPA-Firmware-Dump/custom/firmware/2025.10.15.1127/NCM/scripts_changed/`.

**Config keys** (`riddleBoxCfg -g/-s`): `UdiskMode` (0/1 — the one that matters), `UDiskPassThrough`
(dead; zero runtime xrefs per the alt web UI's own comment). Web UI: the alt site exposes
`UdiskMode` as a toggle ("USB mass storage gadget. Exposes 8MB RAM FAT32 with BoxHelper.apk via
f_mass_storage. WARNING: interferes with Android Auto").

**Flag files** read by `start_accessory.sh`:

| Flag | Meaning |
|---|---|
| `/tmp/change_udisk_mode` | "arm composite on this pass". First pass without it: if `UdiskMode=1`, *touch it* and bring up **plain accessory** (→ GM's one-time toast). Second call (ARMadb re-runs `start_accessory.sh`): flag present → run `start_accessory_mass_storage.sh`. |
| `/tmp/.change_udisk_mode_always` | forces `riddleBoxCfg -s UdiskMode 1` each pass |
| `/tmp/update_status` | firmware update in progress → clears `change_udisk_mode` |
| `/tmp/UDiskPassThroughMode` | "do not start the accessory driver at all" (also skips ARMadb in `start_main_service.sh`) — OCBM sets this to keep stock out of the way |

**`start_accessory_mass_storage.sh`, the recipe GM accepts** (once per boot, guarded by `/tmp/ram_fat32.img`):

```sh
echo 0 > /sys/class/android_usb_accessory/android0/enable
dd if=/dev/zero of=/tmp/ram_fat32.img bs=8M count=1
mkfs.fat -s 128 -n APK /tmp/ram_fat32.img
losetup /dev/loop1 /tmp/ram_fat32.img
mount /dev/loop1 /tmp/UPAN -t vfat -o utf8=1; cp /etc/BoxHelper.apk /tmp/UPAN/; sync; umount /tmp/UPAN
echo 3 > /proc/sys/vm/drop_caches
echo /dev/loop1 > /sys/devices/soc0/soc.0/2100000.aips-bus/2184200.usb/ci_hdrc.1/gadget/lun0/file   # one of
echo /dev/loop1 > /sys/devices/soc0/soc.1/2100000.aips-bus/2184200.usb/ci_hdrc.1/gadget/lun0/file   # these exists
echo 1 > /sys/class/android_usb_accessory/f_mass_storage/inquiry_string
echo accessory,mass_storage > /sys/class/android_usb_accessory/android0/functions
sleep 1
echo 1 > /sys/class/android_usb_accessory/android0/enable
```

Two things we got wrong the first time and should never get wrong again:

- The **LUN node is the UDC's** (`…/ci_hdrc.1/gadget/lun0/file`), not anything under
  `/sys/class/android_usb_accessory/`. On our IW416 unit it is `soc.1`.
- `enable=0` **drops the host link**, so a script started from a host-side console (OCBM console,
  adb, telnet over NCM) dies with it unless detached (`setsid … </dev/null &`).

Teardown (in `start_accessory.sh` when going back to plain accessory): `echo 1 > f_mass_storage/uninstall`,
`rmmod g_android_accessory && rmmod storage_common`, `losetup -d /dev/loop1`, `rm /tmp/ram_fat32.img`,
re-insmod. (We do not rmmod; clearing the LUN file and `functions=accessory` suffices.)

**RWerksman's three community solutions** (p.26, Mar 2026) — history, so we know what has been tried:

1. iOS Shortcut on Wi-Fi-join that POSTs the web-UI uDisk toggle — 15–20 s, notification spam, unreliable, misfires on keyless walk-by (Razorfin).
2. CFW + `sleep 10` then toggle in a boot script — always toasts once first.
3. CFW + `riddleBoxCfg -s UdiskMode 1` + a `/script/custom_init.sh` that touches `/tmp/change_udisk_mode`, insmods `storage_common`/`g_android_accessory`, and `echo 0 > enable` so the *first* enumeration is already the composite. Works on his Silverado EV; **broke boot on Razorfin's Equinox**.

Stock requires the telnet-root CFW
([`custom_firmware/2025.10.15.1127`](https://github.com/lvalen91/CPC200-CCPA-Firmware-Dump/tree/main/custom_firmware/2025.10.15.1127))
for any of this; dongle AP is `192.168.43.1`, telnet port 23.

---

## 5. Our OCBM implementation of the same thing (summary; details in `14_LESSONS_LEARNED.md` §5)

**Boot-time admission trampoline (2026-09-28; not yet vehicle-verified):** `ocbm_boot.sh` now defaults
through a storage-only mass-storage phase, then re-enumerates as OCBM. The announce PID is **`0x2d06`**
(VID `0x1314`, class `0/0/0`); it is deliberately distinct from OCBM `0x2d00` and stock/NCM `0x1520` /
`0x1521`, and it does not match the GM app's OCBM attach filter. The box uses the same trimmed
per-device serial in both phases so the OCBM enumeration can be treated as a reconnect to the admitted
storage device. The announce LUN is removable and uses an 8 MiB FAT image by default; `usb_announce_medium=none`
selects an empty/no-medium LUN for comparison. Neither storage-only mode has yet been proven on a GM
AAOS 14 emulator or vehicle; the existing Equinox observation used the composite accessory+storage identity.
Host apps must not match the transient `0x1314:0x2d06` identity.

The persistent configuration is `/script/ocbm.conf` (`usb_announce=1`,
`usb_announce_dwell_ms=1500`, and `usb_announce_medium=image` by default). Set `usb_announce=0` to
bypass the announce. `usb_announce_medium=image` prepares and binds the existing 8 MiB FAT image;
`none` clears the LUN backing file. The image mode is preferred for the first GM emulator test because
the observed Equinox admission used a populated storage function; whether no-medium storage is admitted
remains unverified. The boot watcher retries the announce `usb_announce_retries` times (default 0, clamped 0..3) at
k × `usb_announce_retry_ms` after the first OCBM bind (default 75 s then 150 s) without
OCBM `CT_HELLO`. `usb_announce_retry_ms` defaults to 75000 and is clamped to 15000..300000.
Each retry is suppressed when `/tmp/ocbm_hello_seen` exists. 2026-10-01 car observation: GM admits the adapter on the boot announce (no 'USB not supported' toast; UsbManager lists the device), so retries serve no admission purpose there. Worse, a retry re-enumerates the gadget under an open permission dialog (04:16:18 dialog raised, 04:17:41 retry DETACH 2d00, dialog lost); overnight, 20+ dialogs were raised in a parked car. Keep the default 0 on the car; the bench can still set `usb_announce_retries` in `/script/ocbm.conf`. A `CONFIGURED` gadget is not a reason
to skip the retry: in the car the gadget is `CONFIGURED` while GM refuses it, which is when the
retry must fire. While `/tmp/ocbm_announce_active` exists, for 15 s after it is removed, and for
10 s after a retry's announce returns, the NCM failover watchdog does not count an ocbmd miss and
logs `failover: ignoring ocbmd gap during announce` once per announce. `ocbmd` writes the internal tmpfs
marker without changing OCBM frames. Later `/script/ocbm_udisk.sh` rebinds go directly to OCBM and reuse
the boot serial. The reserved announce PID must stay excluded from OCBM host filters; only `0x2d00`
speaks OCBM.

- `ccpa/rootfs/script/ocbm_udisk.sh` — `status | on | off | prepare | apply`. Stock recipe with
  `idProduct=2d00`, `bDeviceClass=0`, a README instead of `BoxHelper.apk`. Flag `/script/ocbm_udisk`
  (jffs2, survives reboot). `on`/`off` detach themselves; `ocbmd` comes back via the inittab respawn.
- `ccpa/rootfs/script/ocbm_boot.sh` — with the flag: `prepare` (image + loop) **before**
  `insmod g_android_accessory`, `apply` (sysfs only, no sleeps) immediately after. The legacy gadget
  raises D+ with **zero configurations at insmod**; anything slow in that window makes a host read
  `no configurations`, power-cycle the port (rebooting the bus-powered box) and abandon it. A Linux
  host can be made to look again with `USBDEVFS_RESET` on the *parent hub*; a GM radio cannot.
- Result on the bench: `1314:2d00`, IF0 `0xFF` bulk `0x81/0x01` (OCBM, `HELLO_ACK`), IF1 `0x08` bulk
  `0x82/0x02` (8 MiB vfat `APK`), `CONFIGURED`, `/dev/usb_accessory` intact, one clean enumeration on
  cold boot. **This is the descriptor shape every VCU owner in §3 has working, with our PID.**
- Host side: two bulk pairs now. `ocbm-host`, `ocbm-probe`, and the app's `UsbBulkTransport` prefer
  class `0xFF` and never claim class `0x08` (the kernel's `usb-storage` owns it; yanking it removes the
  disk GM enumerated us for). Before the fix `ocbm-host` was sending HELLO to the SCSI endpoints.
- Cost: GM will likely show a USB-media source for the 8 MB volume. Stock users live with it.
- `accessory,adb` still does **not** work on this kernel
  ([`../carplay/00_ARCHITECTURE.md`](../carplay/00_ARCHITECTURE.md)); only mass storage coexists with
  the accessory function.

---

## 6. Carlink app facts worth knowing (from the README and thread)

- Play package is Tachi91's; it claims the GM cluster authority
  `com.google.android.apps.automotive.templates.host.ClusterIconContentProvider`. Google locks an
  authority to its first publisher, so **no other Play bundle can claim it** — on gminfo3.7 a fork's
  cluster turn card has distance and road name but no arrow. On VCU it does not matter: GM ignores
  supplied icons and draws its own glyph from the maneuver type, so a fork gets the same picture.
- His cluster path is the Car App Library (`NavigationManager.updateTrip()` through the Templates Host),
  bootstrapped by launching a hidden `CarAppActivity` — the one-second flash. The GM app ports it
  (Unlicense code: maneuver table, trip shape, session); see `host/gm_ccpa/docs/14_LESSONS_LEARNED.md` §16 (T13).
- AA frames over a hard-coded **512 KB** were dropped → pixelation with satellite view; fixed by
  raising the cap (p.24, Jun 2026). Our pipeline should not have a similar cap.
- USB **ZLP** stall on the stock adapter with certain metadata (album art / three specific Apple Music
  tracks) put the app in a recovery loop; only clearing the queue broke it (p.24). We enabled `accZLP`
  in `ocbm_boot.sh` for this class of problem.
- Stock AA binary has an **NMEA GPS bug** (drops parts of the coordinate → 1 km jitter); the app patches
  the binary in memory at AA start. CarPlay path is clean (iOS ignores vehicle GPS anyway).
- Stock CarPlay binary strips most iAP2 navigation content; his binary patches forward full
  `iAP2` nav messages for maneuver glyphs.
- **HEVC** from the iPhone requires `hevcInfo` advertised in `/info`; the stock dongle cannot
  forward the stream (f-io, p.25). We negotiate HEVC natively.

---

## 7. Hardware tidbits from the thread

- **MFi coprocessor generations** (f-io): device-version reg `0x00` → `0x03` = 2.0B, `0x05` = 2.0C,
  `0x07` = 3.0. Protocol major `0x02` = SHA-1 (2.0B, slow), `0x03` = ECDSA/SHA-256 (2.0C, 3.0). A chip
  marked `3959` (2.0C package) identified as 3.0 — die-in-package variance, so *read the register*,
  don't trust the marking. CP generation does **not** gate CarPlay features (CarPlay Ultra ran on a
  2.0B). Reference: [Blue Oval Labs — Apple Authentication Coprocessor](https://wiki.blueovallabs.com/).
- **BAA / "MFi v4"** (the Simulator's no-chip route) needs an Apple secure enclave — not an option on
  Pi/dongles (f-io, Sep 2026).
- **Carlinkit 2air / "lybox"** = Allwinner **V821B** RISC-V32, AIC8800D80 Wi-Fi, 5.4 kernel. Root via
  web-UI path traversal; FEL mode via BROM = unbrickable; UART console broken (baud) in kernel.
  Not a CCPA — different platform, ignore for OCBM unless we adopt it.
- f-io's LIVI CCPA rootfs: MFi over TCP (`mfid` on `:5000`, mDNS), L2 bridge USB↔network, usbmuxd —
  the same idea as our `CARPLAY_MFI_ADDR` / `ccpa/mfid` path.

---

## 8. Open questions to take back to the thread (after the Equinox drive)

1. ~~Does an OCBM (`2d00`) composite enumerate on a VCU radio the same way stock `1520` does?~~ **Answered
   2026-09-21: yes.** `1314:2d00` with IF0 vendor/OCBM + IF1 mass storage claimed on our Equinox EV, Allow
   dialog, full OCBM handshake, BT up (§5 row). Worth posting: the PID does not have to be `1520`; the
   composite shape is what the VCU wants. Still unknown: whether GM shows the 8 MB `APK` volume as a media source.
2. kossoo's Sierra EV reconnect flakiness after short stops — does our `session_supervisor` show the
   same pattern (radio keeps the old BT bond / hotspot state)?
3. Whether GM's "USB not supported" handler fires on the composite at all when it is armed *before*
   first enumeration (RWerksman: no; Razorfin: once — but his arms on the second pass).

## 9. CarPlay's map on the driver display, in the car (Play 236 to 249, 2026-11-08 to 11-12)

Moved here from `docs/ops/04_OPEN_ITEMS.md` on 2026-11-26, when the feature was closed there as working in
the car; it is the record of what GM's driver display does with an ordinary app's window and page events.
Settings and current behaviour: `host/gm_ccpa/docs/SETTINGS_AUDIT.md` and `host/gm_ccpa/docs/14_LESSONS_LEARNED.md`.

- **CarPlay map on the driver display: works on the car since Play 237 (2026-11-08); the app's own
  cards over it (turn, now playing, call, lanes, Drive) are emulator-checked, partly seen on the car.**
  (Heading corrected 2026-11-12; it read "in Play 236, not yet tried in the car".) The idea is openautolink's
  ("driver-display maps", validated on a 2024 Blazer EV: GM VCU on AAOS 12L), re-implemented here
  without their GPL-3.0 code. Settings ▸ Navigation ▸ CarPlay map on the driver display (off by
  default): at subscribe the config declares Apple's cluster pair (`altDisplayPanels` +
  `altVideoStreams`, `initialURL: maps:/car/instrumentcluster/map`) at the driver display's own size;
  once CarPlay's picture is up the app opens `nav/DriverDisplayActivity` on that display, decodes
  `CH_ALT_VIDEO` into it and sends `CMD_NAV_START`. The box half shipped 2026-07-12
  (`docs/carplay/06_AV_PIPELINE.md` "VALIDATED 2026-07-12"). Emulator-checked on a simulated public
  display (window opens there, the main screen keeps focus); the emulator's own cluster displays are
  private, as the car's may be. Open, in order:
  1. ~~Whether the Equinox's VCU on AAOS 14 shows the driver display to the app at all.~~ **Yes, 2026-11-08
     11:11:** display 2, 2000x810, 220 dpi, flags `0x4083`, `window allowed` (also a private, off
     2103x774 display 11, unused). On the first try the iPhone streamed the cluster map (HEVC 2000x810,
     first frame 0.35 s after the window opened, 0 frames dropped): 62 frames before the window was
     hidden 2.4 s later, then, after a head-unit restart, 640 frames over 33 s before it was hidden
     again. Nothing GM sends the app says why a window is hidden. The first time was 0.6 s after
     Turn-by-turn in the cluster started navigation, the second 12 s after it ended it, so the
     owner's account of the driver display is the missing piece. Same day, a bug: that feature's
     bootstrap took the map window for "the screen in front" and launched the CarAppActivity on the
     driver display over the map (16:15:50); it now tracks main-display screens only.
     **The owner's account (same day):** the CarPlay map showed briefly, with the speedometer still
     visible, then GM's own Google map took the display back; cycling the driver display's pages did
     not bring it back, and worse, the map page itself could not be reached again (a head-unit restart
     followed at ~11:15). The window had been left stopped behind GM's map, and in the second session
     the bootstrap's CarAppActivity too. So from 237: the window closes the moment it is hidden, so the
     display is left exactly as GM had it; a long-press on the strip right of CarPlay opens it again
     (`DriverDisplay.reopen`); and Turn-by-turn in the cluster stands down while this is on
     (`ClusterNav.active`), as GM took the display back 0.6 s after its `navigationStarted`.
     **First drive on 237, 2026-11-08 11:58–12:01:** the window opened 13 s after the app started and
     stayed up for the whole upload (2,100 frames over 2 min, none dropped), including when a route
     started at 12:00:38: Turn-by-turn in the cluster stood down (no `navigationStarted`), and nothing
     took the display back.
     **Evening drives on 237 (19:50–21:12):** the window opened in all four sessions, within a second
     of CarPlay's picture. GM hid it twice: at 19:50:51, 6 s after a steering-wheel track skip, with
     CarPlay still in front on the main screen, so nothing reopened it; and at 20:04:37, together with
     the CarPlay screen itself, when GM's media service took the main screen. From 238
     (`nav/GmClusterPageService`, openautolink's "GM page-return handling" re-implemented): a service in
     GM's cluster category (`com.gm.intent.category.COREAPP`, `SupportedDisplay=IPC`) receives GM's
     `APP_SELECTED`/`FULL_MAP_SELECTED` page events, logs each (`GM page event … state= type=`), and a
     full-map page selected while no map window is open reopens it (at most 3 a minute). GM finds the
     service at boot: one head-unit restart after install. Emulator-checked with synthetic events;
     whether the Equinox sends them is the next upload's question.
     **First drive on 238, 2026-11-08 19:35 local (00:35 UTC 11-09):** the map opened 13 s after the app
     started and stayed up for the whole 5½-minute upload, none dropped; no page event arrived and none
     was needed. The 237 process had run until the update replaced it, so whether the head unit was
     restarted (needed for GM to find the service) is not known from the log.
     **First drive on 239, 2026-11-09 11:58–12:14 UTC:** the head unit had been restarted (GM sent
     `SERVICE_READY` at 11:59:02), and the page events arrive. Leaving the map page sends
     `FULL_MAP_SELECTED state=0` then `ZONE3_SELECTED state=1`, and GM hides the window; selecting it
     again sends `FULL_MAP_SELECTED state=1 type=NATIVE`, and the map came back within 30 ms
     (12:12:16). Two faults, fixed in 240: (a) Turn-by-turn in the cluster stood down for the whole
     drive because it keyed on the setting, so off the map page the cluster had no turns
     (`bound=false` on every turn); it now stands down only while CarPlay's map holds the display
     (`DriverDisplay.holdsDisplay`), binds when GM hides the window (sharing the 3-in-10-min rebind
     budget), and ends navigation before the window is opened again. (b) `ZONE3_SELECTED state=1`
     (GM's side zone) was taken as the map page and reopened the window, which GM hid 1.2 s later;
     only `FULL_MAP_SELECTED`/`APP_SELECTED` return the map now (`DriverDisplay.RETURN_ACTIONS`).
     **First drive on 240, 2026-11-09 13:40–13:41 UTC:** leaving the map page bound the cluster turns
     (bound, `navigationStarted`, the owner saw them). But the bootstrap returned CarPlay to the front,
     whose resume reopened the map over the other page; the turns stood down for it and GM hid it
     1.2 s later (fixed in 241: `DriverDisplay.showOnResume` does not reopen once GM reported the
     map page left). Then GM took navigation focus 12 ms after `navigationStarted`, and while the
     turns ran it never sent `FULL_MAP_SELECTED` again: only `ZONE3_SELECTED` 0/1 every ~2 s as the
     owner looked for the map page, which they could not get back. On 239, with no turns running,
     the map page came back with `FULL_MAP_SELECTED state=1`. So GM seems to drop or replace the map
     page while another app navigates. From 241 every page event also logs its extras' values
     (numbers and enum names), to tell GM's views apart; the long-press on the strip right of
     CarPlay still reopens the map and ends the turns first.
     **Evening drive on 240, 2026-11-09 22:33–22:41 UTC, confirms it:** GM's map page came back
     (`FULL_MAP_SELECTED state=1`, the map then stayed up 8–18 s until the owner left it) three times,
     each after full-screen Apple Maps had ended the cluster turns, and never while they ran, through
     a dozen `ZONE3_SELECTED` flips. Three long-presses ended the turns, but GM covered the window
     ~1 s later (the driver was on another page) and the turns restarted 0.2 s after that, so the
     map page never appeared. Fixed in 241: for 20 s after the long-press a covered window keeps
     the turns off, so the map page can be picked; leaving the map page, or 20 s without picking it,
     brings them back (`DriverDisplay.mapWantedUntil`). The bootstrap's resume reopen recurred at
     22:35:31 (fixed in 134c297). GM never asked for a cluster session: all 16 Templates Host
     sessions on record are `displayType=0`, though the service declares `FEATURE_CLUSTER`, so
     CarPlay's map cannot ride GM's own map page through the Car App Library.
     **Turn card, Play 241 (owner's ask, 2026-11-09):** CarPlay's cluster map stream carries the map
     only (the instruction card is a separate Apple content type, `maps:/car/instrumentcluster/
     instructioncard`, one per stream; a second cluster stream is type 112, never seen on CCPA and
     box work). So the app draws its own card over the map window (`nav/TurnCard`, default on):
     the turn's glyph, distance, road and (until 2026-11-10) arrival time from `ClusterNav.route`. It needs no GM
     navigation, so on the map page the driver gets the map and the turns without losing the page.
     Emulator-checked with the preview's sample card; open: size and place on the car.
     **Turn card area (owner, 2026-11-10, after the first 241 drive):** inside the directions area
     the card covered too much of the map, and the strip left of it, above the gauges (they take
     the bottom third), was empty. Play 242 gave the card an area of four edge sliders.
     **Driver display layout (owner interview, 2026-11-10; Play 243):** the sliders are
     replaced by Settings ▸ Driver display, a miniature on which each card is dragged and sized
     (`nav/ClusterLayout`, `ClusterLayoutEditor`). The turn card (`nav/TurnCard`) is a pill far from
     the turn and a full card inside 800 m, with a countdown bar, a green exit sign and the turn
     after; the arrival line is gone because Apple's map draws one. New: a now-playing card on the
     right (`nav/MediaCard`; open with no route, folded to the cover during one), which a call takes
     over, and lane arrows under the turn card (default off). Cards follow the car's day and night.
     Lane guidance is the first thing in the app to push metadata tier `extended`
     (`VehicleConfigYaml.renderAdapter(metadataTier)`); it switches itself off after a session with
     no metadata. Emulator-checked with the preview's samples. Open, all on the car: whether the
     default places clear the gauges and Apple's speed-limit sign; the drag in the editor (the bench
     has no touch); whether this phone accepts `extended` on the wired arm; and the sign of a lane
     angle (drawn positive = right; the `cluster` log line `lanes: … highlight=` settles it).
     **Play 244 (2026-11-11, from the owner's photo of the car):** Dock the turn card puts
     it in the bottom-right corner as a panel in the look of GM's gauges (`TurnCard.drawDocked`,
     `ClusterLayout.dock`), and the folded now-playing pill keeps the title beside the cover. Open
     on the car: whether the docked default (corner at 70%, 54%, taken from that one photo) lines
     up with the gauges' top edge and clears Apple's arrival strip.
     **Play 246 (2026-11-11, from the owner's drive on 245 and its log):** three things.
     (1) Lanes appeared but none was marked. The log had `status=[0, 0, 2] highlight=[null, null, 45]`,
     so the phone did mark them; the card looked for the highlight among the lane's other angles,
     where the phone does not repeat it, and drew every arrow faint. `TurnCard.laneArrows` now
     draws the highlight itself. The `lanes:` log line also carries `angles=` so the next drive
     shows what the others hold. (2) The turn after was missing when two turns came close
     together: in the docked card the lanes row replaced the "Then" row. They now share the row,
     and the gap falls back to raw metres (`betweenMeters`). The `turn #` log line records
     `gap=`/`nextGap=`, because which entry carries DistanceBetweenManeuver is still unconfirmed
     on this car. (3) A road that starts with a route designator is drawn as its sign
     (`nav/RouteShield`: Interstate, U.S., the default state circle, county), read from the text
     the phone sends. Open on the car: all three by eye (the bench could not render them, the
     emulator kept crashing), what the phone's road text really looks like (`marker=` in the
     `turn #` line says whether one was recognised, never the name), and per-state markers.
     **Black driver display after a quick walk-away (Play 248, 2026-11-12 23:12–23:15 UTC), fixed in 249, not yet seen on the car:**
     GM left its map page as the car went off (`FULL_MAP_SELECTED state=0`, the window closed), the
     phone dropped 17 s later, GM selected the map page again 17 s after that, and CarPlay came back
     at 23:13:45 — but the map never opened, then or at the next reconnect (23:15:23), and GM's
     cluster turns took the display instead. Cause: the session's end called `DriverDisplay.close`,
     which forgets the declared display and size; only a subscribe sets them, and a walk-away
     reconnects on the same adapter link without one, so every `show` returned at once. Now a
     session end only closes the window (`endSession`) and the declaration lives as long as the
     adapter link; the map page picked while CarPlay is down waits for the session's first picture;
     at that picture the map opens unless GM last reported another page (`showAtSessionStart`);
     GM's `SERVICE_READY` resets that. To check on the car: after a walk-away the log has
     `opening the map window … (CarPlay's picture is up …)`.
     **What the 245 drive already answered (recorded 2026-11-12):** the phone accepts the pushed
     `extended` tier on this car's session (lane records arrived; the Equinox runs the AirPlay-tunnel
     arm, not the wired one), so lane guidance needs no box change. The highlight is carried only in
     `laneAngleHighlight`, never repeated in `laneAngles` (`docs/carplay/05_METADATA_AND_CONTROLS.md`).
     **Sign confirmed on the 2026-11-12 drive: negative = left** (a left turn's preferred lane at −90),
     which is how the app draws it.
  2. What the owner sees on 237: the picture, which page of the driver display it needs, whether GM's
     speedometer and gauges stay visible over the window (they must), and whether GM's own turn
     icons and head-up directions give way while it shows (openautolink's finding on the Blazer, so
     it may conflict with Turn-by-turn in the cluster). Their first enable also needed a head-unit
     restart (End Call held ~30 s while parked).
  3. ~~The map's safe area is the whole display for now.~~ **Directions area, Play 239 (2026-11-09,
     not on the car):** Settings ▸ Navigation ▸ Directions area on the driver display sets four edge
     margins (0–35% each) that become the cluster stream's `safeArea` at the next subscribe
     (`DriverDisplay.safeArea`, `VehicleConfigSpec.clusterSafe`); the map still fills the display.
     The box needed nothing: it already carries a real alt inset into `/info` and arms `viewAreas`
     for it (`vehicle_config.rs::the_gm_apps_driver_display_map_arms_the_alt_screen`). "GM layout"
     sets 28/9/15/17%, i.e. 560,72–1700,674, openautolink's Blazer default of 558,70–1694,668; Reset
     returns to the whole display. Each change is outlined on the driver display for 30 s while the
     map window is open (emulator-checked on a simulated 1600x600 display). Open: whether CarPlay
     moves its turn card and arrival time inside it on the car, and which margins clear the Equinox's
     gauges.
  4. **Held for adapter firmware (owner, 2026-11-08):** two more openautolink ideas need the box, so
     they wait with car GPS (the NMEA item below). CarPlay's status-bar edge (Auto / Bottom / Driver
     side): `info.rs::view_areas` hardcodes `viewAreaStatusBarEdge: 0` on both main areas; it needs a
     parsed `statusBarEdge` on `ViewAreaEntry` and an app setting. The car's make, model and year in
     Maps' vehicle name: param 20 subs 1/6 (and 8 `MapsDisplayName`) are the box's constant `name`
     (`message.rs` ~:695), so it needs the C-6 `accessoryName` bound wired into the tunnel Identify,
     where a rejected change costs the whole session (`0x1D03`). Both are now planned as `08_FUTURE_TASKS.md` T21 (the name)
     and T22 (the status bar edge).

## 10. The media pane and the enigy ports, in the car (Play 234 to 245, 2026-11-07 to 11-14)

Moved here from `docs/ops/04_OPEN_ITEMS.md` on 2026-11-26 with the item closed there; the first view-area
switch sent by a host and proven on the car (2026-11-08 12:00:24) is in it.

- **Features ported from enigy/carlink and enigy/ocbm (2026-11-07), in Play 234 (the pane's tap strip in
  235).** On the 2026-11-08 drive the lyrics lookup, the top-bar line and Now Playing by URL were used;
  the rest is not yet tried in the car.
  - *Top-bar Now Playing, Maps, Spotify and Phone open by command:* `CMD_REQUEST_UI_URL` on an adapter
    whose `HELLO_ACK` carries `CAP_UI_URL`, instead of finding and tapping the icon. Benched on the
    owner's iPhone with the recorded picture: `nowplaying:`, `maps:`, `spotify:`, `mobilephone:` (and
    `music:`, `podcasts:`, `messages:`) open their app; the full list is in `01_OCBM_PROTOCOL.md`.
  - *Cluster:* the road text leads with the exit ("Exit 273 · …") now that the adapter asks for
    ExitInfo; the app binds the cluster again by itself (3 times in 10 min at most) if the car ends the
    session mid-route; and it holds Android's NAVIGATION app focus while a route is up with the cluster
    setting on (`nav: focus` lines). What GM does with the focus is unknown.
  - *Settings ▸ Calls ▸ Echo test / Voice test* (parked) for `13_AUDIO_ROUTING.md` §4's echo question.
  - *Settings ▸ Display ▸ Media pane*: a pane with the song, its controls and timed lyrics, in any gear
    (it is for the passenger). On the 2026-11-08 morning drive (app 234) it never showed: the setting was
    Off, its default then (`pane: no split this session`), and the owner saw lyrics only in the top bar.
    So since 2026-11-08 a tap on the glass right of the picture (x 2630–2914 under the top bar) shows or
    hides it, the state is kept for the next drive, and the default is *Over CarPlay*: the pane is laid
    over the right 30% of the picture and nothing is sent to the phone. On the car 2026-11-08 11:13 the
    strip showed and hid the pane three times (Over CarPlay); the owner then chose Right, 40%, and the
    next sessions started split (CarPlay 1482x960, pane 988 px wide). On 237 at 12:00:24 a strip tap
    sent `CMD_VIEW_AREA 0` from the split: the box answered iOS with `updateViewArea` (500 ms), iOS
    sent its burst of unchanged parameter sets, and the main picture carried on (58 fps 10 s later, no
    decoder reset), so a host-sent switch is proven on the car.
    At the owner's request (2026-11-08) the art became a thumbnail beside the title (a sixth of the
    pane's height) and the lyrics show nine lines, three before the sung one and five after, in
    larger type (Play 237). Choosing a side instead declares a second view area (`initial: true`
    only when the pane was last showing), CarPlay on one side and the pane on the other; the strip then
    sends `CMD_VIEW_AREA`. Benched on the owner's iPhone with the app's exact document
    (2400x960, pane right 30%): iOS negotiated `viewAreas`, drew CarPlay in 1680x960 with the right 30%
    black, and put its own area toggle at the top of the dock. The pane's full-screen button
    (`CMD_VIEW_AREA 0`) and back were accepted by the adapter, but the one bench run that sent them
    recorded no picture after the switch; the car answered it on 2026-11-08 (above): the video carries on.
    At the owner's request (2026-11-11, Play 245, not yet seen on the car) the pane has the cover
    blown up, blurred and dimmed behind it, as CarPlay's own Now Playing does, with the lyrics in
    translucent white over it; and while the pane is hidden the strip shows the driver display's round
    now-playing chip (cover in a progress ring) in place of the faint handle. Emulator only so far,
    with drawn covers (white, saturated, near black). To check on the car: the lyrics against real
    covers in daylight, and that the chip sits whole on the glass right of the picture.
  - *Settings ▸ Screen & Sound ▸ Look up lyrics* (off by default; song names go to lrclib.net) feeds the pane;
    *Lyrics in the top bar* shows the current line there, in Park only.
  - **Lyrics package (2026-11-14, `docs/ops/08_FUTURE_TASKS.md` T24).** Built and served; the car has
    not yet downloaded one. To check on the car: that it downloads on home Wi-Fi or the hotspot
    (`lyrics package: N songs … downloaded … over Wi-Fi` in the log), and that a song from the package
    shows lyrics with *Look up online* set to Never. The owner's Spotify sign-in on the
    server is done (2026-11-14).
  - Connection time on the new adapter firmware (startup fixes 5389084/749f72c), bench only: when the
    iPhone answered the first page, subscribe → first picture took 8.8 s and 10.4 s, against the car's
    10.7 s median on the old firmware (44 sessions since 2026-10-15, `[startup]` lines; fastest 8.1 s).
    Stage by stage the Bluetooth part is unchanged: discoverable 0.8 s after start, phone connected
    1.6 s later (car 1.0 s), handoff 2.6 s after that (car 3.0 s). The bench's failed sessions are not
    evidence either way: the phone was carried away from the server during them. Two cases are owed a
    car check: a page timeout of 5.2 s whenever the first bonded iPhone (the box holds two) does not
    answer, and the one split-document session that took 28 s from handoff to picture. App 234 logs the
    same `[startup]` stages, so the car's numbers on the new firmware come straight from its uploads
    (stage table: `host/gm_ccpa/docs/05_SESSION_FLOW.md` §9). First car session on it, 2026-11-08 07:50
    (adapter reporting `UI_URL`): subscribe → first picture 13.1 s, against the 10.7 s median. Bluetooth
    came up 3.0 s after subscribe and was discoverable 1.1 s later (the old median for the two together
    was 3.1 s), the first-paged iPhone answered 1.5 s after that (no page timeout), the handoff took the
    usual 3.0 s, and the picture came 4.5 s after the handoff (median 3.7 s). One session; not a trend.
  - Not taken, and why: `08_FUTURE_TASKS.md` "Left off this plan".
