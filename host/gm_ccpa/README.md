# gm_ccpa

**Wireless CarPlay on a Chevrolet Equinox EV, as an ordinary Play app** (`wasidremin.gmccpa`).

On the Equinox the Carlinkit CPC200-CCPA adapter raises its own Wi-Fi access point and runs the
CarPlay receiver (`carplayd`). After the Bluetooth / iAP2 handoff the iPhone joins that access point,
and the adapter forwards the still-encrypted video and audio to this app over USB
([OCBM](../../docs/README.md)). The app decrypts, decodes and renders, sends touch and the car's
microphone back, and owns the session's configuration. The adapter is also the Bluetooth radio and
the MFi coprocessor.

```
iPhone ──Wi-Fi, the adapter's own AP──▶ CCPA adapter (carplayd) ──USB / OCBM, encrypted A/V──▶ this app
   └────Bluetooth / iAP2 / MFi────────▶ CCPA adapter
```

Why the adapter runs the Wi-Fi: the Equinox's GM firewall drops the phone's inbound connection to the
head unit (measured September 2026, [`docs/14_LESSONS_LEARNED.md`](docs/14_LESSONS_LEARNED.md) §7). The
app still carries upstream's Silverado design, in which the iPhone streams over the vehicle's own
hotspot straight to the app and the adapter is only the Bluetooth radio and MFi chip; it sits behind
Advanced ▸ Adapter Wi-Fi (on for the Equinox). Docs 01, 03–06, 11 and 12 below describe that design
and its truck bring-up.

| | |
|---|---|
| **Head unit** | Equinox EV, GM VCU, AAOS 14 (`burmese_orange`), user 12. No adb: the app reaches the car only through Play |
| **Package** | `wasidremin.gmccpa`, Play `automotive:internal`. High-water mark: doc 14 §6 |
| **Video** | HEVC; CarPlay negotiates 2400x960 on the main screen, and Apple's cluster map 2000x810 on the driver display |
| **Audio** | AAC-LC media, AAC-ELD voice, the car's microphone for Siri and calls |
| **Core** | Rust receiver / pairing / MFi via JNI, built from this tree's crates |

## Status (Play 247, 2026-11-12)

On the car: wireless CarPlay end to end (pairing and reconnect, the Wi-Fi handoff, video, media and
voice audio on GM's volume groups, touch); now playing on the AAOS media card and in the cluster;
calls; CarPlay turn-by-turn in GM's cluster (Car App Library); the steering-wheel voice button for
Siri; EV range and charge sent to CarPlay; the media pane with lyrics beside CarPlay; and CarPlay's
own map on the driver display, with the app's turn, now-playing and call cards over it.

Built, not yet confirmed on the car: the lane highlight, the turn after beside the lanes and route
signs (246); the data budget and Send on Wi-Fi (246); the Drive card and the drive-history import
(247). EV Insights keeps a permanent drive log on the head unit (245) that has not yet been checked
over several drives.

What is verified where, item by item: [`../../docs/ops/04_OPEN_ITEMS.md`](../../docs/ops/04_OPEN_ITEMS.md)
("GM app and the car"). Every setting with its key, default and state:
[`docs/SETTINGS_AUDIT.md`](docs/SETTINGS_AUDIT.md).

## Documentation

Start at **[`docs/00_HANDOFF.md`](docs/00_HANDOFF.md)**, then the open items above.

| Doc | |
|---|---|
| [`14_LESSONS_LEARNED.md`](docs/14_LESSONS_LEARNED.md) | **The Equinox line's record**: USB and Wi-Fi bring-up, every Play release's findings, EV Insights (§13: drive log, data budget, Drive card), the Play high-water mark (§6) |
| [`SETTINGS_AUDIT.md`](docs/SETTINGS_AUDIT.md) | Every control: where it lives, its preference key and default, how far it is verified |
| [`04_SYSTEM_MODEL.md`](docs/04_SYSTEM_MODEL.md) | Architecture and the app/box state machine. Written for the Silverado; its Equinox exceptions are marked |
| [`05_SESSION_FLOW.md`](docs/05_SESSION_FLOW.md) | Wire-level buildup + the ordering rules that kill sessions (read §8); §9 has the Equinox startup timings |
| [`13_AUDIO_ROUTING.md`](docs/13_AUDIO_ROUTING.md) | Audio routing, volume groups, mic uplink |
| [`06_BRINGUP_RUNBOOK.md`](docs/06_BRINGUP_RUNBOOK.md) · [`12_OBSERVED_FLOW.md`](docs/12_OBSERVED_FLOW.md) | Device-proven commands and phase-by-phase flow, from the Silverado |
| [`01_FINDINGS.md`](docs/01_FINDINGS.md) · [`03_BUILD_PLAN.md`](docs/03_BUILD_PLAN.md) · [`11_HARDENING_PLAN.md`](docs/11_HARDENING_PLAN.md) | Silverado feasibility, build map and hardening ledger (historical; open work now lives in `docs/ops/04_OPEN_ITEMS.md`) |

## Build and run

**To the car (the only route):** from the repository root, on a clean tree,

```bash
AAB_VERSION_CODE=<next> AAB_VERSION_NAME=4.0+<tag> bash host/gm_ccpa/tools/build_aab.sh --publish
```

builds the `playRelease` bundle and commits it to `automotive:internal`. Use a versionCode above the
high-water mark in doc 14 §6, and record the new one there and in `docs/ops/04_OPEN_ITEMS.md`.
The script stamps a dirty tree `-dirty` rather than refusing it, and shares the Gradle build
directory with bench builds, so do not edit sources while it runs.

**Bench (tests and the AAOS emulator):**

```bash
cd host/gm_ccpa/netprobe_app
GM_BUILD_DIR=$HOME/.cache/carlink-artifacts/gm_ccpa/gradle gradle :app:testPlayDebugUnitTest :app:assembleSideloadDebug
adb install -r --user 10 $HOME/.cache/carlink-artifacts/gm_ccpa/gradle/app/outputs/apk/sideload/debug/app-sideload-debug.apk
adb shell am start --user 10 -n wasidremin.gmccpa/.MainActivity --es run driver_display --ez enabled true
```

`MainActivity` takes `--es run <action>` bench actions (`driver_display`, `upload_log`, …; see
`MainActivity.kt` and `SETTINGS_AUDIT.md`). The emulator crashes about a minute after the
driver-display window opens; see `docs/ops/04_OPEN_ITEMS.md` "Tests, tooling & install".
`tools/test.sh` is the Tier-0 gate (host cargo tests + OCBM proto conformance).
`tools/build_apk.sh` is the older Gradle-free path (`kotlinc → d8 → aapt2`); it has no AndroidX, so
it cannot build the Car App Library service.

### On upstream's Silverado (vehicle-hotspot mode, adb available)

Gradle-free: `kotlinc → d8 → aapt2 → zipalign → apksigner`, plus a cargo cross-build for the JNI core.
Needs the Android SDK + NDK. Build output goes to `~/.cache`, never into this tree.

```bash
bash tools/test.sh                 # Tier-0 gate: host cargo tests + OCBM proto conformance
bash tools/build_apk.sh            # -> apk/gmccpa-debug-<sha>.apk, and prints the install command

# Install the sha-stamped path the build just printed — there is deliberately no "latest" symlink,
# because versionCode is pinned and `-r` would happily install a stale one after a failed build.
# Play-attributed install keeps the in-motion path eligible; -g grants runtime perms; user 10 is foreground
adb install -i com.android.vending -r -g --user 10 apk/gmccpa-debug-<sha>.apk
adb shell appops set wasidremin.gmccpa SYSTEM_ALERT_WINDOW allow

# Logging boots effectively dead on this unit, and silence looks like a dead app
adb shell "setprop persist.log.tag V" && adb logcat -G 16M

adb shell "am start -n wasidremin.gmccpa/.MainActivity --es run full \
           --es ssid '<ssid>' --es pass '<passphrase>'"
```

Expect the standard USB permission dialog once per device ("always open" retires it). The hotspot
passphrase cannot be read programmatically here — take it from Settings ▸ Hotspot and pass it in. If iOS
reports `No matching endpoint found`, forget the vehicle on the iPhone and re-pair; that, not GM's
`:7000`, is the usual cause.

Full procedure, expected log lines and the troubleshooting table:
[`docs/06_BRINGUP_RUNBOOK.md`](docs/06_BRINGUP_RUNBOOK.md) §5g.

## Logs — where uploads land and how to read them

Logs and screenshots leave the head unit only on an explicit tap. EV Insights telemetry is the
one background upload: with "EV Insights auto-upload" on it sends each driven minute and a
heartbeat while parked, counted against a monthly limit when the car is not on Wi-Fi
(`docs/14_LESSONS_LEARNED.md` §13.5).

| Action | Endpoint | Server location |
|---|---|---|
| **Upload Logs** (launcher) | `POST https://wasidremin.ddnsgeek.com/cloudbridge/logs` (`{device: gmccpa, lines: [...]}`, gzipped) | `/opt/stacks/cloudbridge-log/logs.txt` |
| **EV Insights telemetry** (automatic, or "Upload latest report" in the probe activity) | `POST .../cloudbridge/probe` (gzipped report without its static part, which is sent once and named by `staticId`; headers `X-Device`/`X-Report-Name`; 409 `need_static` asks for it again) | rebuilt full report in `/opt/stacks/cloudbridge-log/probes/`, static parts in `probe_static/` |
| **Drive history import** (once, after a telemetry upload succeeds; `telemetry/HistoryImport.kt`) | `GET .../cloudbridge/history?car=<staticId>` (ndjson, one drive or charge a line, no coordinates; 404 unless the id names a static part the receiver holds) | `/opt/stacks/cloudbridge-log/history/trips.ndjson`, written by `HistoryReplayTest` |
| **Capture CarPlay Home/Current Screen** (launcher) | `POST .../cloudbridge/api/captures` (multipart `metadata` JSON + `image` PNG) | `/opt/stacks/cloudbridge-log/captures/` |
| **Icon-tap launch record** (`CarPlayLauncher.uploadLaunch`) | `POST .../cloudbridge/api/launches` (record JSON with `id`) | `/opt/stacks/cloudbridge-log/launches/` |

Contract: `netprobe_app/.../logging/LogExport.kt` (`DEFAULT_REMOTE_BASE_URL`) and
`telemetry/ProbeUpload.kt`; capture/launch senders: `upload/UploadFeedback.kt`
(`UploadCenter`) and `av/CarPlayLauncher.kt`. Receiver:
`/opt/stacks/cloudbridge-log/log_server.py` (running as the `cloudbridge-log`
container with `/opt/stacks/cloudbridge-log:/app`, so the live store is `/app/logs.txt`
in-container = `logs.txt` on host; tail page at `.../cloudbridge/`, raw at
`.../logs/raw`, probe list at `.../probes`, capture/launch lists at
`.../api/captures` and `.../api/launches`).
Each `/logs` line is stored as `<server-stamp>  <event line>`; lines are redacted
on-device before upload. Local reproductions: `car-logs-delta-20261002/regenerate.sh`
shows the exact extraction from `logs.txt`.

## Calibration — icon templates & test app launch (launcher card)

Below the capture rows, **Icon Templates** and **Test app launch** operate the vision
launcher (`match/ScreenModel.kt` + `match/IconMatcher.kt` +
`match/IconTemplateStore.kt`, `av/VisionLauncher.kt`, `CalibrationUi.kt`):

1. **Capture Current Screen** on the Home grid, on the Dashboard (and optionally inside an app).
2. **Icon templates → Calibrate from capture** — tap an icon in the saved PNG, check the blue
   box, press its name. Strip icons and the bottom glyph pin to the strip's centre column;
   everything else cuts a grid template. Templates live in
   `filesDir/icon_templates/<name>/template.json`.
3. **Self-check** — every template against every saved capture: expect `strip.*` MATCH on
   every shot and `grid.*` MATCH only on Home grid shots.
4. **Test app launch** (Maps / Music / Spotify / Phone) — searches the recent-apps strip,
   then the grid, then taps the strip's Home/Dashboard toggle and searches the grid again.
   It never taps an app without a match of 0.85 or higher, saves the record
   (`filesDir/launches/<id>.json`, kept win or lose), and uploads it to `/api/launches`.

AC Home is dropped by the box while `dPadSupport=false` (2026-10-15 log), which is why the
launcher taps the strip's Home/Dashboard toggle instead of sending Home.

Built-in Equinox 2754x966 templates (cut from the real captures, `assets/vision/equinox_2754x966/`)
are used automatically, so a fresh install can launch with no calibration.
Calibrate-from-capture now only creates optional overrides — a user-saved template always wins.

## Layout

```
netprobe_app/     the Kotlin app — ocbm/ (adapter link) · pair/ · av/ (decode, audio, now playing) ·
                  nav/ (route guidance, cluster turns, driver display and its cards) · pane/ (media pane) ·
                  telemetry/ (EV Insights recording, drive log, Drive card estimate) · upload/ (uploads,
                  data meter) · match/ (vision launcher) · Ui.kt (settings) · MainActivity
native/           the Rust core: JNI over ccpa_custom's receiver/pairing/mfi crates
tools/            build_apk.sh · test.sh · info_plist_*.py · ADB recon · capture
docs/             see above
audit/            adversarial review, cited as the authority for doc 11's R6 section
```

## Archive: evidence and golden APKs

This project was merged into `ccpa_custom` on 2026-09-08 (`git subtree add`, history preserved). Two
things were deliberately left behind, taking the import from 368 MB to 876 KB:

- **`evidence/`** — the on-device capture corpus backing every "device-proven" claim.
- **`apk/`** — sha-stamped builds and the golden `netprobe-debug-v4.0.apk` rollback target.

Both stay in the **standalone `gm_ccpa` checkout**, along with the `baseline-2026-08-05-working` tag,
which the subtree hash rewrite did not carry across. That checkout is the archive of record — do not
delete it. `evidence/...` citations in `docs/` are **provenance, not live paths**: they name real files
there so every claim stays traceable to the capture that proved it. `apk/` here is gitignored build
output.

**Rolling back:** `adb install -r ../../../gm_ccpa/apk/netprobe-debug-v4.0.apk`. `versionCode` is held
constant across releases, so this is a plain reinstall that preserves `carplay_peers.bin` — and therefore
the existing pairing.

## Scope

Interoperability and accessory development on the owner's own licensed hardware. The ADB recon scripts
are strictly read-only — no state changes, no i2c bus I/O.
