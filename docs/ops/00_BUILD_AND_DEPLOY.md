# Build, footprint and deployment

> **STATUS:** CURRENT · single owner for this topic. Consolidated 2026-08-31 from pre-consolidation docs 05; the originals are in git history and in the 2026-08-31 backup. Correct this file in place — do not add a sibling.

How the box binaries are built, what they cost in flash/RAM, and how they are pushed.

## Build footprint

<!-- absorbed: ../ops/00_BUILD_AND_DEPLOY.md -->

### Toolchain

- **Box (armv7)** — target `armv7-unknown-linux-musleabihf`, **static (musl)**. The Rust daemons
  cross-build with `cargo zigbuild --release --target armv7-unknown-linux-musleabihf` (zig serves as
  the C cross-linker); `carplayd` additionally needs `FDK_AAC_PREFIX=$PWD/scratchpad/fdk/install` for
  its eld-codec. C probes use `zig cc -target arm-linux-musleabihf -static -Os -s`. Size profile below.
- **Host** — macOS/Linux native, or Android. The two host *apps* are
  `host/MacHost/carlink_macOS` (Swift/Xcode, shipping) and `host/CarlinkAndroid` (Kotlin/Gradle,
  AAOS 12L / API 32 min — **no NDK and no Rust inside the app**; on `main`. (**Corrected 2026-09-18**
  — this line previously said "in-tree on a feature branch, not yet merged to `main`", which
  `docs/ops/04_OPEN_ITEMS.md` had already flagged stale (`git ls-tree -d --name-only main host/` lists
  `host/CarlinkAndroid`), but this file itself was never fixed until now.) `host/ocbm-host` is native
  **Rust** (`rusb`), and "`ocbm-rescue`" is a *role* of that same binary (`ocbm-host console`), not a
  separate build. The only `clang` + `libusb` artifact in the tree is `host/accbench.c` (hand-built,
  see `host/README.md`). The `aarch64-linux-android` NDK target IS used — but for the AAOS/Pi port of
  the **box** daemons (`pi/`), not for any host client.
  - **Gate:** `~/.claude/bin/bt ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:detekt
    :app:ktlintCheck` from `host/CarlinkAndroid`; run `python3 tools/proto_check.py` from the repo
    root whenever `OcbmProto.kt` changes. The box binaries above are unaffected by this gate — it
    is client-only and does not rebuild or redeploy any box daemon.
  - **Emulator constraint:** exactly ONE AVD instance may run at a time. It holds the USB
    passthrough claim on the adapter (vendor id `0x1314`), and a second instance fights it for the
    claim. Reshape the running device with `wm size` / `wm density` instead of booting a second AVD.

Rust size profile (`Cargo.toml`) — size-first everywhere **except** the per-frame hot paths:
```toml
[profile.release]
opt-level = "z"      # optimize for size
lto = true
panic = "abort"      # no unwinding
codegen-units = 1
strip = true

## Five per-package overrides opt OUT of size-first because they sit on the per-frame
## critical path (ChaCha20-Poly1305 on every 4K video + audio frame, ocbm-proto's
## Reassembler, receiver's A/V forwarding). Paired with target-cpu=cortex-a7 in
## .cargo/config.toml. NOTE this is SCALAR: the armv7 musl target spec carries an
## explicit -neon that target-cpu does not override, so nothing we ship is vectorized.
[profile.release.package.chacha20]
opt-level = 2
[profile.release.package.poly1305]
opt-level = 2
[profile.release.package.chacha20poly1305]
opt-level = 2
[profile.release.package.ocbm-proto]
opt-level = 2
[profile.release.package.receiver]
opt-level = 2
```
The size cost of those five lands almost entirely in `carplayd` — the only binary carrying crypto
**and** full A/V forwarding. `Cargo.toml` is the authority; this is a quote of it.

### Storage budget (the box has very little)

- Flash: **16 MB** SPI NOR — `mtd0` uboot 256 K / `mtd1` kernel 3328 K / `mtd2` **rootfs 12800 K
  (jffs2)**. Partition sizes are fixed by the vendor kernel/U-Boot.
- Live free after stripping riddleBox: **~6 MB** on the jffs2 rootfs. jffs2 **compresses on write**,
  so compressible binaries consume less on-media than their raw size.
- RAM: **128 MB**, ~107 MB free (tmpfs) — a real staging option.

### Component footprint

*(The pre-build estimate table that stood here is dropped: its three C rows described helpers that
were never written — every box daemon is Rust — and MEASURED figures follow below.)*

### Fitting the heavy case (stackable)

1. **Rust size profile** (above) — often 2–4× smaller than default release.
2. **UPX** — an *optional manual* shrink, NOT applied by the installer (**3.96**, run in the Lima `ccpa-build` VM; host
   UPX 5.x segfaults the box's 3.14 kernel, so `upx -t`-verify the packed output); ~50% further shrink.
   **`tools/upx_pack.sh <binary>...` is that procedure as one command** (starts the VM if needed, packs
   with 3.96, `upx -t`-verifies, prints the packed path under `/tmp/upxout/` for `ocbm_push.sh`) — added
   2026-08-25 because the "correct" path existed only as prose and sessions kept shipping unpacked
   binaries. Measured on the current pair: ocbmd 454,864 → 230,316 B, aa-bridge 399,568 → 201,952 B.
3. **jffs2 auto-compression** — free on-media reduction.
4. **Run from `/tmp` (RAM)** — persist a small `.tar.xz` of binaries in rootfs, unpack to the 107 MB
   tmpfs at boot via a tiny loader; running binaries never touch flash.
5. **Lean custom `mtd2`** — reflash a rootfs that drops more vendor cruft.

### What the size plan got right, and what it did not

The original recommendation was "keep the low-level glue in C, write OCBM in Rust". **The C half was
not taken** — every box daemon is Rust — and the "hard measured number" it deferred was produced (see
the measured blocks above). What held: the Rust size profile, UPX packing, and the box's scope —
pairing + key-derivation + the RTSP relay, with no decode on the box.

## Field deployment report — CPC200-CCPA / A15W (2026-09-13)

This report records the first Linux-server installation of the full OCBM userspace onto a genuine
A15W/CPC200-CCPA unit. It is hardware evidence, not a substitute for the installer’s gates or for
a live CarPlay/Android Auto session test.

### Starting point and on-ramp

- The unit initially enumerated as stock `1314:1521` with a vendor-specific interface and mass
  storage; it did not expose USB-NCM. `tools/ncm_base_install.sh` correctly refused to be treated
  as a bootstrap tool.
- The required on-ramp came from the CPC200-CCPA resources repository:
  `custom/firmware/2025.10.15.1127/NCM/A15W_NCM_Update.img`, renamed to `A15W_Update.img` and
  applied from a FAT32 drive while the adapter was independently powered. The resources README’s
  firmware and model prerequisites mattered: this was not a generic Carlinkit image.
- Creating `/script/ncm_only` and power-cycling changed the device to `1314:1520`, with USB-NCM and
  the adapter reachable at `192.168.50.2`. The Linux host initially had a DHCP lease at
  `192.168.50.100`; adding `192.168.50.1/24` to the USB-NCM interface and a host route to `.2`
  left the normal Wi-Fi default route untouched.

### NCM-base conversion

The preflight identified `2025.10.15.1127`, kernel `3.14.52+g94d07bb armv7l`, and the NXP IW416
radio variant. `tools/ncm_base_install.sh all --via telnet` then:

1. pulled and verified a full NOR/rootfs backup;
2. installed the owned boot path and early UART console;
3. cold-boot verified NCM before deleting anything;
4. stripped the vendor projection stack while retaining the unit’s WLAN/BT files, gadget modules,
   identity, dropbear/telnetd/udhcpd, and recovery tools; and
5. audited the resulting scripts and cold-booted the stripped base again.

The first OCBM preflight found only 4,152 KiB free. The full manifest was approximately 4,195 KiB,
but the installer deliberately requires more than manifest size plus 512 KiB. The documented
reference-guarded `prunelibs --dry-run` identified unused vendor/toolchain libraries, kept referenced
runtime libraries (including `libtinyalsa`, libc, pthread and GCC support), and reported no radio
files as candidates. Running `prunelibs` raised free space to 6,548 KiB before OCBM placement.

### OCBM placement and proof

The full manifest installed 15 files: `ocbmd`, `iap2d`, `carplayd`, `btd`, `aa-bridge`,
`iap_role_switch`, `ocbm_boot.sh`, the radio seam, projection/supervisor scripts, and respawn
wrappers. Every file passed host-to-box MD5 verification, executable-bit checks, and shell syntax
checks. A reboot then returned the unit to NCM with the files intact.

The first telnet placement attempt exposed an installer/tooling problem: `boxsh.py put` base64
stages a second copy on JFFS2, and the 2.16 MiB `carplayd` transfer left an incomplete
`carplayd.new.b64`. No target file was activated. A direct NCM transfer to `.new`, followed by
MD5 verification and atomic rename, completed successfully. The Linux-compatible large-file path
and `md5sum` handling are now present in the working tree’s `tools/ocbm_install.sh`; the owner
should review and retain that fix rather than routing multi-megabyte binaries through telnet
base64.

The reversible trial proved all of the following on this unit:

- temporary enumeration as `1314:2d00`;
- `ocbm-host` `HELLO_ACK` (`box v1`, caps `0x0000003f`);
- OCBM `CONSOLE` attachment; and
- a root shell responding with `uid=0(root)` and the expected ARMv7 Linux identity.

The trial dead-man expired normally and returned the adapter to NCM. This proved the OCBM-to-NCM
rollback path before finalization.

### Finalization result

Finalization armed `/script/ocbm_trial`, `/script/ocbm_failover`, and PID-1 respawn entries, removed
`/script/ncm_only`, rebooted, and reached OCBM. The first confirmation attempt timed out locally,
leaving stale `ocbm-host console` processes holding the libusb interface; those processes were
terminated locally, and the confirmation was then sent successfully over OCBM. The final confirmed
state was:

- USB `1314:2d00`, OCBM mode active;
- `HELLO_ACK` and root console confirmed;
- `/script/ocbm_trial` cleared after confirmation;
- `/script/ncm_only` absent; and
- no signed U-Boot or kernel region was written.

The live OCBM trial and console were proven. A live projection session with an iPhone/Android phone
and the GM host application was **not** part of this installation report.

### Linux/operator findings for the owner

1. **USB permissions:** Linux created `/dev/bus/usb/...` as `root:root` without a udev rule for
   `1314:2d00`. An unprivileged `ocbm-host` therefore failed with `open: Access denied`; running
   the host client under `sudo` worked. Add/document a project udev rule or make the permission
   prerequisite explicit for Linux operators.
2. **Timeout cleanup:** command-tool timeouts left detached `ocbm-host console` children holding
   the libusb claim, producing `claim IF0: Resource busy` on the next attempt. The installer and
   host wrapper should clean up children on timeout, and status instructions should include a
   stale-client check.
3. **Root-owned report directories:** the original sudo-launched run directory became root-owned,
   so a later `prunelibs` phase failed locally with `Permission denied` before contacting the box.
   Fresh user-owned `--run-dir` paths avoided this; the installer should either normalize ownership
   or fail with a clearer local-path diagnostic.
4. **Space budgeting:** the full uncompressed manifest estimate alone was misleading on this unit;
   the installer’s 512 KiB safety reserve correctly prevented an unsafe placement. The guarded
   library-pruning phase was sufficient, raising free space from 4,152 KiB to 6,548 KiB. After the
   final install the observed free space was approximately 4,124 KiB, so future updates must check
   space before staging temporary files.
5. **Transport choice:** telnet is a useful rescue path, but it is a poor bulk deployment transport.
   The existing NCM `nc` path or OCBM `CH_FILE` path should be preferred for large files, with an
   end-to-end checksum and atomic rename in either case.
6. **Recovery documentation:** the full NOR image must be described as a read/restore artifact,
   not as a USB bootstrap image. The approved scope remained userspace/rootfs work; U-Boot and the
   encrypted kernel were never modified.

### Backup artifact

The verified pre-strip capture contains the individual `mtd0`, `mtd1`, and `mtd2` reads, the
recomposed 16 MiB NOR image, `rootfs.tar.gz`, the state manifest, and checksum metadata. The
operator stored a byte-for-byte copy outside the repository and outside iCloud-synced project
storage at:

```text
~/.local/share/ocbm/backups/cpc200-ccpa_20260912_prestrip_ncm/
```

Important SHA-256 values from that permanent copy:

```text
full_nor_16MB.bin  5957787b470340bd3d0aad777cd9c9c192b01ab8ab0034c7485d81e635bacc93
mtd0.bin           f0aed10083f81dd7c6e27f78d5a477f350411f3f2d71ddd0ae1591760e61ba3f
mtd1.bin           bf9fa85b4b088e4a0b5d47d4a2285974b799b22c3cd07dde84e29c328a0d70cc
mtd2.bin           d2d14b8ad426094cf2319d908e81597744ac0847fc2113a059148cd5433bd858
rootfs.tar.gz      efaf3b781ab2d03a984724951cd2d609802ecdc22d8417b87b1e1db4a7900ff3
```

The permanent path is local to the operator’s server and is intentionally not committed to Git.
The corresponding in-repository evidence remains under `scratchpad/ncmbase_20260912_144148/`.
