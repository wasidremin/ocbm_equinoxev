# ocbm_equinoxev

Wireless CarPlay for a **Chevrolet Equinox EV**: open Rust firmware for the Carlinkit **CPC200-CCPA**
adapter, and the Android Automotive app (`wasidremin.gmccpa`, Google Play internal track) that drives
it. A fork of lvalen91/ocbm, cut down on 2026-10-27 to what the owner uses: one iPhone, one Equinox
EV, **no Mac**. The Mac app, the Silverado app, Android Auto, the Pi/C2Air ports and the remote MFi
path are gone; do not bring them back or plan work for them.

How it runs on the car: the adapter raises its own Wi-Fi access point and runs the CarPlay receiver
(`carplayd`); the iPhone joins it after the Bluetooth/iAP2 handoff; the adapter forwards the
still-encrypted audio and video over USB (OCBM) to the app, which decrypts, decodes, renders, and
owns the session's configuration. The car has no adb: the app reaches it only through Play.

## Where things are

| Path | What |
|---|---|
| `ccpa/` | Adapter daemons (`ocbmd`, `iap2d`, `carplayd`) and its root filesystem scripts (`rootfs/script/`) |
| `crates/` | `ocbm-proto` (the wire contract, shared with the app), `box-common`, `bt-common`, and `vendor/` (receiver, iap2-core, metadata, mfi, pairing, rtsp, wireless, eld-codec) |
| `host/gm_ccpa/` | The app: `netprobe_app/` (Kotlin), `native/carplay-jni` (the Rust receiver core through JNI), `tools/` (build, publish, the lyrics package builder), `docs/` (app lessons and the settings audit) |
| `host/ocbm-host/` | Bench client: talks OCBM to the adapter from this server |
| `host/evinsights/` | Self-hosted EV telemetry workbench (FastAPI, TimescaleDB, React). Separate from the app's on-car EV Insights |
| `tools/` | Adapter install, deploy and recovery scripts; `run_tests.sh`; `docs_check.py`; `tests/` |
| `docs/` | The documentation: four categories, see below |

## Start here

- **Picking up work:** [docs/ops/04_OPEN_ITEMS.md](docs/ops/04_OPEN_ITEMS.md) (what is open) and
  [docs/ops/08_FUTURE_TASKS.md](docs/ops/08_FUTURE_TASKS.md) (planned tasks `T1`…, each with its
  state and what "done" means).
- **The map of every document:** [docs/README.md](docs/README.md).

Read the owning document **before** touching the code it covers:

| Before you touch | Read |
|---|---|
| Anything about a protocol question | [docs/ops/03_REFERENCE_INDEX.md](docs/ops/03_REFERENCE_INDEX.md): which source answers which question |
| OCBM channels, frames, new commands (`crates/ocbm-proto`, `ocbm/OcbmProto.kt`) | [docs/carplay/01_OCBM_PROTOCOL.md](docs/carplay/01_OCBM_PROTOCOL.md) |
| Session start, SETUP, teardown | [docs/carplay/02_SESSION_LIFECYCLE.md](docs/carplay/02_SESSION_LIFECYCLE.md) |
| What CarPlay is told about the car (`/info`, the YAML config, any new capability) | [docs/carplay/04_CAPABILITIES_AND_CONFIG.md](docs/carplay/04_CAPABILITIES_AND_CONFIG.md) |
| Identify params 6/7, a subscribe, `crates/vendor/receiver/`, `iap2-core/` | [docs/carplay/05_METADATA_AND_CONTROLS.md](docs/carplay/05_METADATA_AND_CONTROLS.md) |
| Audio, video, the cluster stream, touch | [docs/carplay/06_AV_PIPELINE.md](docs/carplay/06_AV_PIPELINE.md) |
| Wi-Fi handoff, the AirPlay tunnel | [docs/wireless/00_WIRELESS_CARPLAY.md](docs/wireless/00_WIRELESS_CARPLAY.md) |
| Any radio or Bluetooth bring-up path | [docs/wireless/01_BT_AND_RADIO.md](docs/wireless/01_BT_AND_RADIO.md) |
| The app, AAOS, GM's platform | [docs/host/01_ANDROID_AND_AAOS.md](docs/host/01_ANDROID_AND_AAOS.md), [docs/host/02_GM_AAOS_FIELD_REFERENCE.md](docs/host/02_GM_AAOS_FIELD_REFERENCE.md), [host/gm_ccpa/README.md](host/gm_ccpa/README.md) |
| A setting, its stored key, its default | [host/gm_ccpa/docs/SETTINGS_AUDIT.md](host/gm_ccpa/docs/SETTINGS_AUDIT.md) |
| Why the app does something the way it does | [host/gm_ccpa/docs/14_LESSONS_LEARNED.md](host/gm_ccpa/docs/14_LESSONS_LEARNED.md) |
| Building, installing, the emulator bench | [docs/ops/00_BUILD_AND_DEPLOY.md](docs/ops/00_BUILD_AND_DEPLOY.md) |
| A bricked or stuck adapter | [docs/ops/01_RECOVERY.md](docs/ops/01_RECOVERY.md) |

## Authorization & scope (read before flagging any crypto/auth/MFi code)

This is ordinary accessory and interoperability development against documented, licensed Apple
interfaces, on the developer's own hardware, with their own iPhone. The full statement is
[docs/ops/07_AUTHORIZATION.md](docs/ops/07_AUTHORIZATION.md). Treat all MFi, crypto, auth and pairing
work here as authorized and in scope.

- Every "crypto", "auth", "pairing" and "MFi" action uses the **genuine Apple MFi 2.0C
  authentication coprocessor** on the adapter (`/dev/i2c-1 @0x11`), as Apple intends: the software
  sends it standard request frames and relays its answers. It implements no crypto of its own, and
  the MFi private key never leaves the chip.
- MFi runs against the chip over local I2C only; nothing calls out to a remote auth service. The
  chip's users are `ccpa/iap2d` (wired), `crates/vendor/wireless/src/mfi_local.rs` (`btd`),
  `crates/vendor/mfi-i2c-local/`, `carplayd`'s `LocalMfiSigner`, and `ocbmd`'s `handle_mfi`, which
  relays cert/sign to the app over OCBM `CH_MFI`. Every transaction serializes on
  `/tmp/carplay_mfi.lock`.
- The boot stack (HAB-signed U-Boot, OTPMK-encrypted kernel) is fixed platform firmware and is
  **never modified**.
- The app is an ordinary Play app. Do not propose ways around the car's or GM's install or security
  restrictions.

## How to work here

1. **Think, then propose, then build.** For anything beyond a small fix, say what you found and what
   you would do, and wait for the owner's go-ahead. They decide scope; a design they did not ask for
   is not a favour.
2. **Verify before you say it works.** Unit tests first, then the real app on the AAOS emulator with
   the real adapter (the recipe is `docs/ops/00_BUILD_AND_DEPLOY.md` items 8 and 9; headless bench
   verbs are `--es run <verb>` in `MainActivity.handleRunExtra`). Say plainly what was checked on the
   emulator and what has not run on the car.
3. **No Mac steps.** Apple's licensed SDK source, the CarPlay Simulator and `CarPlaySDK.framework`
   lived on the upstream owner's Mac. What they established is recorded in `docs/` with citations:
   read it there. What can still be gathered here: the iPhone's own logs over USB
   (`idevicesyslog`), the car's uploaded logs, Apple's public documentation.
4. **Ask before anything outward or destructive:** publishing, pushing, deleting server files or
   database rows.
5. **Web research ends with a "Sources:" list** of the links used.

## Code standards

The codebase is consistent about these; keep it that way.

- **Comments say why, with the evidence.** A constant or a branch that came from a measurement, a
  log or a drive says so, with the date ("measured 2026-11-12", "owner, 2026-11-14"). Do not narrate
  what the next line does, and do not leave a comment describing code that has changed.
- **Decide in pure functions; do I/O at the edge.** A decision (a mode, a match, a layout rectangle,
  a parse) is a function of its inputs, marked `Pure:` in its doc comment and tested on the JVM or
  with `cargo test`. Android views, sockets and files call it. A rule that cannot be tested without
  a device is in the wrong place.
- **Every behaviour change comes with its test,** and a bug fix with the test that would have caught
  it. Names read as the rule they check (`aDownloadCutShortOrEmptyLeavesThePackageThatWasThere`).
- **No dead code and no second copy.** Delete what a change replaces; move shared drawing or parsing
  into one helper (`nav/CardParts.kt` is the pattern) instead of pasting it.
- **Logs carry numbers only.** Logs are uploaded: no song names, road names, destinations, caller
  names, coordinates or MAC addresses. Lists of names the feature needs live in files on the head
  unit, not in the log.
- **Stored settings carry over.** When a setting changes shape, read the old key as the default for
  the new one, and say so in `SETTINGS_AUDIT.md`.
- **App-driven configuration.** Anything configurable about CarPlay is decided by the app and pushed
  to the adapter; the adapter presents what it is given. A box-side default is an interim floor, to
  be earned by a measured failure and the owner's approval
  (`docs/carplay/04_CAPABILITIES_AND_CONFIG.md`).
- **The adapter is small.** About 12.5 MB of root filesystem: size-first release profile, no new
  dependency without a reason, and never a chipset whitelist (see Radios).
- **A new adapter capability is announced, not assumed.** The adapter lists what it can do in
  `MGMT_INFO`; the app uses a feature only when the flag is there, so a new app works with old
  firmware.
- **Licences.** This repository is Unlicense. Code may come only from Unlicense or public-domain
  sources, credited in the file header and in [NOTICE.md](NOTICE.md). From GPL projects take facts
  and ideas, never code.

## Documentation standards

`docs/` is four categories (`carplay/`, `wireless/`, `host/`, `ops/`), **at most 10 documents each**,
enforced by `python3 tools/docs_check.py`. One topic has one owner.

- **Correct the owning document in place,** at the claim, with the date and the evidence. Never add
  a `*_CORRECTIONS`, `*_V2` or dated sibling; if a topic needs a new file, merge something first.
  The 66-file corpus this replaced had readers acting on refuted claims they met in the wrong file.
- **Finish the change in the docs in the same commit:** the owning protocol or app document, the
  task in `08_FUTURE_TASKS.md` (state, what was verified, what was not), `04_OPEN_ITEMS.md` if
  something is left to check on the car, `SETTINGS_AUDIT.md` for any setting, and `NOTICE.md` for
  any outside source.
- **Separate what was verified from what was not,** and where (unit test, emulator, bench adapter,
  the car). "Not yet on the car" is a normal and useful sentence.
- **When the source conflicts with a project document, the source wins,** and the document is
  corrected.

## Build, test, ship

```sh
./build.sh                         # adapter binaries (armv7 musl, nightly + zig) and ocbm-host; never a bare cargo zigbuild
sh tools/run_tests.sh              # every adapter-side suite; plain `cargo test --workspace` skips most of them
python3 tools/docs_check.py        # the documentation rules
# The app's unit tests. Gradle is not on PATH: use the binary host/gm_ccpa/tools/build_aab.sh names
# (GRADLE_BIN, else the wrapper's copy under ~/.gradle), with the three directories it exports:
cd host/gm_ccpa/netprobe_app
export GM_BUILD_DIR=~/.cache/carlink-artifacts/gm_ccpa/gradle
export GM_JNI_DIR=$GM_BUILD_DIR/root/jniLibs GM_ASSETS_DIR=$GM_BUILD_DIR/root/assets
"$GRADLE" --offline -q :app:testSideloadDebugUnitTest      # then :app:assembleSideloadDebug for the emulator
```

- **Build output lives outside the tree** (`~/.cache/cargo-targets/`, `~/.cache/carlink-artifacts/`,
  `mktemp -d`), and is also in `.gitignore`. Ignore patterns for it are slashless (`apk`, not
  `apk/`): the paths are often symlinks, and a trailing slash matches directories only.
- **The adapter's firmware ships inside the app.** `build_aab.sh` stages it
  (`host/gm_ccpa/tools/stage_adapter_fw.sh`) and the app updates the adapter from Settings ▸ Advanced, with a
  trial and a rollback (`ccpa/rootfs/script/fw_apply.sh`, task T20). The boot path and
  `fw_apply.sh` itself are installed by hand once (`tools/ocbm_install.sh`) and never by an update.
- **Publishing to Play, only when the owner asks:** from the repository root,
  `AAB_VERSION_CODE=<n> AAB_VERSION_NAME=4.0+<name> bash host/gm_ccpa/tools/build_aab.sh --publish`.
  The tree must be committed and must not be edited while it runs. Then record the new high-water
  mark in three places and commit: `host/gm_ccpa/docs/14_LESSONS_LEARNED.md` §6,
  `docs/ops/04_OPEN_ITEMS.md` and `host/gm_ccpa/docs/SETTINGS_AUDIT.md` ("Version high-water"). The
  next version code is one above the mark recorded there.

## Repository rules

- **One branch: `main`.** Commit to it; do not create or park work on another branch. The repository
  is published by mirror, not by pull request, so a side branch is work the next session has to
  rediscover.
- **Never push to `origin`** (lvalen91/ocbm), never open a pull request there, never retry a refused
  push with another credential. Fetching and reading it are fine.
- **This fork publishes to the `equinox` remote** (`wasidremin/ocbm_equinoxev`), and only when the
  owner asks.
- **Never commit or print:** `play-service-account.json`, any `.env`, signing keys, the adapter's
  `carplay_cfg.yaml` or `split_cfg.yaml` (they hold the Wi-Fi passphrase), car or CarPlay captures,
  uploaded logs, or anything from the owner's personal exports. Probe data holds raw GPS: never print
  coordinates or describe places. The iPhone's syslog holds personal data: search it for specific
  terms, never dump it.

## Protocol rules that were paid for

These cost sessions to learn. The owning documents have the evidence; do not re-derive them.

**Metadata and Identify** (`docs/carplay/05_METADATA_AND_CONTROLS.md`)

- `iap2-core/src/features.rs` is the table Identify params 6/7 and the subscribe sequence are
  **generated** from. Never hand-edit one of the three. TLV ids come from Apple's parameter tables
  (`iap2-core/src/spec.rs`), never from inference.
- iOS enforces three declaration rules (§5.6): a `Start*` must be declared with its `Stop*`; a
  receive must not be declared without its send; a subscribe for an id that param 6 does not
  declare is silently ignored.
- **When an Identify is rejected, ask the phone; do not bisect.** `accessoryd` names the parameter,
  the message id and the reason: `idevicesyslog -u <udid> -p accessoryd -o <file>`, then
  `grep -E "iapreject|Identification info rejected" <file>`. A `0x1D03` cannot be recovered within
  a session.
- **The metadata tier is pushed by the app** (`VehicleConfigYaml.kt`), armed once per process:
  `carplayd` picks up a change on the next session, the long-lived `iap2d` only when the phone is
  unplugged and replugged. `/tmp/carplay_metadata` and `CARPLAY_METADATA` are bench levers for an
  adapter with no app attached.
- **The Bluetooth-time Identify is byte-pinned** and never consults the tier. iOS rejected growth
  there twice and broke the Wi-Fi handoff. Do not "fix the inconsistency".
- **Wireless iAP2 rides a RemoteControlSession DataStream, SETUP stream type 130,** with its own
  header and ChaCha20-Poly1305 framing, not `iAPSendMessage` inside `POST /command`.

**Radios** (`docs/wireless/01_BT_AND_RADIO.md`)

- CCPA ships at least six WLAN/BT chips, and only a unit's own driver set is in its root
  filesystem. **Never branch on a chipset list, and never install chip-specific bring-up, drivers or
  firmware from the repository.**
- The seam (`radio_detect.sh`, `radio_hal.sh`, `radio_ap_up.sh`) is the sanctioned exception and is
  installed: it holds no firmware path or module name, and resolves a unit's bring-up at run time
  from that unit's own dispatcher. Do not remove it from an install list.
- A mapping the seam cannot extract in closed form is refused (`unsupported`), never emitted raw.
- The access-point layer is always ours (`/script/radio_ap_up.sh`); the vendor's script damages the
  configuration on a stripped unit. `hci0` reading `UP RUNNING` proves nothing: convergence is a
  real HCI round trip. The Wi-Fi interface name is an insmod parameter, not `wlan0`; the Rust side
  still hardcodes it in places, tracked in that document.

**Sources** (`docs/ops/03_REFERENCE_INDEX.md`)

- Silence in the 2017 SDK drop is not an answer: it says nothing about what Apple added since.
  Prefer what `docs/` records from the current `CarPlaySDK.framework` and Simulator, then shipping
  head units, then the 2017 source for what it does contain.
- "Carlinkit does X" means X can be made to work, never that X is correct. Separate a vendor's
  choices from what they were observed putting on the wire against a real iPhone.
- Disassembly and on-hardware experiments are the last resort. Read the documents first.

## Other repositories we learn from

[NOTICE.md](NOTICE.md) is the record of what was taken from where; update it with every port or
borrowed fact.

| Repository | Licence | What it is to us | May we take code? |
|---|---|---|---|
| [lvalen91/ocbm](https://github.com/lvalen91/ocbm) | Unlicense | Upstream: this fork's parent (`origin`, read-only) | Yes |
| [lvalen91/carlink](https://github.com/lvalen91/carlink) | Unlicense | The maneuver table, trip shape and cluster turn-by-turn logic (`nav/`) | Yes, credited |
| [enigy/carlink](https://github.com/enigy/carlink) | Unlicense | Media pane, timed lyrics client and cache, call tests, navigation focus, split-screen view areas | Yes, credited |
| [enigy/ocbm](https://github.com/enigy/ocbm) | Unlicense | Adapter startup fixes, route-guidance detail, the power-safe update design. Reviewed weekly for changes worth porting | Yes, credited |
| [mossyhub/openautolink](https://github.com/mossyhub/openautolink) | GPL-3.0 | Showed that GM exposes the driver display to an ordinary app, and how to return to it | **No.** Ideas only |
| [isaacwisdom/egmp-carplay-ev-routing](https://github.com/isaacwisdom/egmp-carplay-ev-routing) | GPL-3.0+ | The charging and consumption models Apple Maps needs for EV routing | **No.** Facts only |
| [augard/evmaps](https://github.com/augard/evmaps) | none stated | Same subject, the model ids Maps accepts | **No.** Facts only |
| [luka-dev/mib2q-carplay-rgi](https://github.com/luka-dev/mib2q-carplay-rgi) | GPL-3.0 | The layout of Apple's `PASCD` wheel-speed sentence | **No.** Facts only |
| [metheos/CPC200-CCPA-Firmware-Dump](https://github.com/metheos/CPC200-CCPA-Firmware-Dump) | check before use | The idea of the adapter joining the car's hotspot (task T15) | Not without checking |
| [lrclib.net](https://lrclib.net) | public service | Timed lyrics, asked at run time and by the lyrics package builder | Not applicable |

Check an idea against these before designing from scratch: several problems here (the adapter
update, lyrics, the driver display) were already solved in one of them.

## On this server, outside the repository

- **The upload receiver** (`/opt/stacks/cloudbridge-log/log_server.py`, the `cloudbridge-log`
  container): logs, probes, captures, and the two downloads the app takes, `/history` and `/lyrics`.
  Described in `host/gm_ccpa/README.md`.
- **The lyrics package** is built weekly from the owner's crontab by
  `host/gm_ccpa/tools/lyrics_pack.py` (task T24). Its song lists, answers and Spotify sign-in live
  under `~/.local/share/gmccpa-lyrics/` and `~/.config/gmccpa-lyrics/`.
- **The emulator bench:** an AAOS emulator with the adapter passed through on USB, and
  `ocbm-host` for pushing a single adapter binary. The adapter and the phone are plugged into this
  server when the owner says so; do not assume either.
- **Bench mode, every time (owner, 2026-11-26).** The server is short of memory: before booting the
  emulator or a long build, run `tools/bench_mode.sh on` (pauses the owner's media stack and Immich,
  stops the Gradle daemons); when the bench session ends, or before you stop work, run
  `tools/bench_mode.sh off`, which resumes only what it paused. Never leave it on at the end of a
  session. It never touches `cloudbridge-log` or EV Insights. Stop the Gradle daemons after every
  app build (`gradle --stop`) even outside bench mode.
