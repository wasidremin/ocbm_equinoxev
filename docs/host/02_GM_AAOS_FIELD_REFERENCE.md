# GM AAOS field reference — platforms, vehicles, uDisk, and what the community has already proven

> **STATUS:** CURRENT · single owner for external GM-platform evidence (XDA thread, lvalen91 repos, per-vehicle reports). Created 2026-09-20. Correct this file in place — do not add a sibling.

**Purpose.** Everything we had to dig out of the XDA *Carlink* thread and Tachi91's GitHub on
2026-09-20, in one place, with links, so nobody has to re-read 26 forum pages or re-clone five repos
to answer "does the dongle work on vehicle X" or "why does GM say USB not supported". Correct this
file in place. Our own implementation write-up is
[`../../host/gm_ccpa/docs/14_LESSONS_LEARNED.md`](../../host/gm_ccpa/docs/14_LESSONS_LEARNED.md) §5;
this file is the *external* evidence and the platform map that write-up rests on.

---

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
| **Lurker1126** / mossyhub | *openautolink* — wireless Android Auto for AAOS, sidesteps USB entirely. Reverse-engineered the AA EV energy model. | [github.com/mossyhub/openautolink](https://github.com/mossyhub/openautolink) |

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
| Cluster maneuver icons | App-supplied icons **do** reach the cluster via the unregistered `ClusterIconContentProvider` authority (Tachi91 owns it on Play — nobody else can claim it) | GM VMSPlugin **ignores** supplied icons, renders from the maneuver enum (`setManeuverType`). Text-only nav for any third-party app. |
| NCM (`functions=ncm`) on the radio | Recognised as NCM-capable, **never brought up** — sits idle, interface locked to system | Same (Tachi91, p.25, Jul 8 2026) |
| "Always allow" for the USB app | broken: dangling `config_UsbDeviceConnectionHandling_component` (`android.car.usb.handler`) | not yet characterised — first get the device to appear |

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
  authority to its first publisher, so **no other Play bundle can claim it** — forks get text-only
  cluster nav. On VCU it does not matter (GM ignores supplied icons anyway).
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
