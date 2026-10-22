# SETTINGS_AUDIT — actual controls on the v100 tree (2026-10-15)

Source of truth for the settings cleanup (v101 base, v103 deltas at the end).
Every row below was read from the named builder/function — nothing is invented
from the task prompt. Rail pages come from `Ui.kt fillSettings()` and are now:
`Settings | Troubleshooting | Advanced | Insights` (v101's Connection/Display/
Audio/Logs split shipped; v103 added Insights — see “v103 deltas”).

Conventions: "action only" = no persisted key. Preference files:
`carplay_screen` (DisplayPrefs), `hotspot_credentials` (Ui.kt CRED_PREFS),
`adapter_wifi` (AdapterWifi.FILE), `audio_route` (AudioRoute.PREFS),
`capture` (CapturePrefs.FILE), `upload_center_prefs` (UploadCenter.PREFS),
`display_geometry` (VideoFrame.GEOMETRY_PREFS), `vehicle_probe_permissions`,
`usb_identity` (UsbIdentity.PREFS). Dirs (not prefs): `icon_templates/`,
`launches/`, `captures/`, `launch_evidence/` (new in v101).

## Connection page (`Ui.kt buildConnection`)

| Current label | Source | Persisted key | Actual behavior | Proposed location | Verdict | Reason |
|---|---|---|---|---|---|---|
| Pairing code (big digits) | `buildConnection`, `pairRow` | action only (display) | Shows `pairingCode` from box `CT_PAIRING_CODE`; hidden when empty | Settings | keep | Status, not a preference |
| Phone / detail | `phoneRow` | `carplay_screen/last_phone_name` (display only) | Shows last phone name + session detail | Settings | keep | Status |
| Restart CarPlay | `buildConnection` restart row | action only | `onRestart()` (~15 s reconnect) | Troubleshooting | move | Support action, not a preference |
| Stop CarPlay | `buildConnection` stop row | action only | `onStop()` | Troubleshooting | move | Support action |
| Start | `startRow`, shown only when IDLE/STOPPED | action only | `onStart()` | Troubleshooting | move | Support action |
| Forget pairing | `buildConnection`, `confirmForget()` | action only (clears box bond + app Ed25519 store) | Destructive, confirmed | Advanced | move | Destructive radio/pairing op; keep confirm |

## Display page (`Ui.kt buildDisplay`)

| Current label | Source | Persisted key | Actual behavior | Proposed location | Verdict | Reason |
|---|---|---|---|---|---|---|
| Top/Bottom/Left/Right inset sliders | `buildDisplay slider()` | `carplay_screen/inset_{top,bottom,left,right}_dp` (draft until Save) | Pending preview, applied on Save & Reconnect | Advanced | move | Geometry override, not everyday |
| CarPlay UI Smaller↔Larger | `buildDisplay` (adapter-WiFi-gated) | `carplay_screen/carplay_ui_size_percent` (draft) | Physical-size calibration via `/info` | Advanced | move | Numeric override; unavailable row stays informative |
| CarPlay UI calibration (unavailable note) | `buildDisplay` else-branch | none | Explains legacy-host limitation | Advanced | move | Keep text, it is accurate |
| Video sizing | `buildDisplay` | `carplay_screen/video_fit` (draft) | Stretch vs fit | Advanced | move | Geometry override |
| Floating stats | `buildDisplay` | `carplay_screen/stats_overlay` (draft) | Pill overlay; position via drag (`stats_x/y_fraction`) | Advanced | move | Debug overlay |
| Save & Reconnect | `buildDisplay` | action (commits draft via `saveControls().commit()`) | Restarts projection session | Advanced | move | Goes with the overrides it saves |
| Legacy video resolution scale | `buildDisplay` card | `carplay_screen/ui_scale` (immediate) | Coded resolution presets | Advanced | move | Numeric/debug; separate from UI calibration |
| Right margin | `buildDisplay` card | `carplay_screen/safe_right` (immediate) | Now-Playing keep-out, next connection | Advanced | move | Numeric override |
| Screen layout | `buildDisplay` card | `carplay_screen/mode` (immediate) | SYSTEM_UI/STATUS_HIDDEN/NAV_HIDDEN/FULLSCREEN | Settings | keep | Normal presentation preference |
| Sidebar | `buildDisplay` card | `carplay_screen/sidebar` (immediate) | Rail beside CarPlay, restarts CarPlay | Settings | keep | Normal presentation preference |
| Custom top bar | `buildDisplay` card | `carplay_screen/custom_top_bar` (immediate) | Strip in freed status area: nine CarPlay-style tiles (doc 14 §15), also shown on the settings screen | Settings | keep + relabel subtitle | Normal preference; subtitle must state consequence |
| Apply size now | `applyRow` (visible when `sizePending()`) | action | `onRestart()` | Advanced | move | Companion to legacy scale controls |
| Restart app | `buildDisplay` bottom row | action | `onRestartApp()` | Advanced | move | Support escape hatch, not a preference |

Note: `sizePending()` compares legacy `ui_scale`/`safe_right` against the latched
frame only — the new-Controls draft has its own Save row. Both stay functional
wherever the rows live; no key changes.

## Audio page (`Ui.kt buildAudio`)

| Current label | Source | Persisted key | Actual behavior | Proposed location | Verdict | Reason |
|---|---|---|---|---|---|---|
| Audio source (Adapter/Bluetooth) | `buildAudio`, `audioRow` | `audio_route/bluetooth` (immediate) | Car plays iPhone over BT vs app audio path | Settings | keep | Useful playback preference |

## Logs page (`Ui.kt buildLogs`) — becomes Troubleshooting + Advanced

| Current label | Source | Persisted key | Actual behavior | Proposed location | Verdict | Reason |
|---|---|---|---|---|---|---|
| Upload logs | `buildLogs` + `MainActivity.onLogAction(UPLOAD)` | action only | Redacted batch POST to `<base>/logs` | Troubleshooting | move (as Export/share diagnostics) | User support action |
| Export logs | `buildLogs` + `exportLogs(redact=true)` | action only | SAF picker → USB volume → MediaStore → filesDir ladder | Troubleshooting | move (as Export/share diagnostics) | User support action |
| Log scope (This app/Whole car) | `scopeRow`, `onScopePicked` | `capture/scope` | WHOLE_OS gated on adb-granted READ_LOGS | Advanced | move | Probe config, not everyday |
| Log status | `logStatusRow` | action only (display) | `LogCapture.status()` snapshot | Troubleshooting | move (as connection/session status) | Status |
| USB probe | `buildLogs` | action only | `UsbDiagnostics.runProbe()` deep dump | Advanced | move | Vehicle/network probe |
| Vehicle Telemetry Probe | `buildLogs` | action only + `vehicle_probe_permissions` grants | Opens `ProbeActivity` (read-only, parked-only) | Advanced | move | Vehicle probe |
| Clear logs | `buildLogs`, `confirmClearLogs()` | action only (deletes captures, restarts) | Destructive, confirmed | Advanced | move | Keep confirm; destructive |
| Capture CarPlay Home Screen | `captureHomeRow`, CAPTURE_HOME | action only | **MISLEADING: sends AC Home, which the adapter drops when D-Pad is not advertised** (`VisionLauncher` header, §9 of doc 14). A later stable frame does not prove Home navigation | — | remove row | Task §Capture Home cleanup. Keep `CaptureButtonKind.CAPTURE_HOME` enum + protocol constants + unrelated callers untouched |
| Capture Current Screen | `captureCurrentRow`, CAPTURE_CURRENT | action only | Stable-frame capture + PNG/JSON sidecar + `/api/captures` upload, no navigation | Troubleshooting | keep + relabel "Capture current CarPlay screen" | The honest capture |
| Pending capture queue / Retry Queued | `queueStatusRow` | action only (reads `captures/*/.pending_upload`) | Count + drain | Advanced | move + relabel "Pending uploads" | Queue management |
| Test Connection | `testConnectionRow` | action only | POST probe to `<base>/logs`, then drain queue | Advanced | move (under Server connection) | Connection test |
| Upload server & state demo | `baseUrlRow` | `upload_center_prefs/base_url` (via dialog) | Endpoint config + demo dialog | Advanced | move + relabel "Server connection" | Endpoint configuration |
| Icon templates | `iconTemplateRow` → `CalibrationUi.showTemplates` | action (edits `icon_templates/`) | List/delete/self-check/calibrate-from-capture | Advanced | move + relabel "Template overrides" | Optional recognition overrides |
| Test app launch | `appLaunchRow` → `CalibrationUi.showTestLaunch` | action (writes `launches/`, POSTs `/api/launches`) | VisionLauncher strip→grid→toggle launch | Troubleshooting | move + relabel "Test app launch" | Diagnostic launch action |

`missingSummary()` ("Missing: …" / "Ready: …") is the source of the
mandatory-calibration wording: it lists glyph/strip/grid gaps against
user-only templates. v101 keeps the function for the overrides screen but the
normal launcher status must NOT call a device "uncalibrated" for having no user
overrides — built-ins are the default.

## Advanced page (`Ui.kt buildAdvanced`)

| Current label | Source | Persisted key | Actual behavior | Proposed location | Verdict | Reason |
|---|---|---|---|---|---|---|
| Adapter Wi-Fi | `buildAdvanced` toggle | `adapter_wifi/on` (restarts connection) | iPhone joins adapter vs car hotspot | Advanced | keep | Radio topology; reconnect-required, keep confirm |
| Vehicle hotspot | `hotspotRow` + dialog | `hotspot_credentials/ssid,pass,chan` | Pushed at SUBSCRIBE; blank-by-design forces entry | Advanced | keep | Needed for vehicle hotspot; keep dialog |
| Adapter info | `infoRow` | action only | `BoxAction.INFO` | Advanced | keep | Probe |
| USB announce / Announce retries | `announceRow(s)` | box-side `/script/ocbm.conf` (display only) | Shown on `onAdvancedOpened`; disabled until linked | Advanced | keep | Probe display |
| Restart adapter Wi-Fi | `buildAdvanced`, `confirmRestartWifi()` | action only | Radio cycle, drops live session | Advanced | keep | Keep confirm |
| Reboot adapter | `buildAdvanced`, `confirmReboot()` | action only | Box reboot, drops session | Advanced | keep | Keep confirm |
| Recover | `buildAdvanced` | action only | Automatic recovery steps | Troubleshooting | move | User support action |
| Close app | `buildAdvanced` | action only | Confirmed `finishAffinity()` | Advanced | keep | Keep confirm |

## Removed rows in v101 (record)

1. `Capture CarPlay Home Screen` (`Ui.kt captureHomeRow`) — removed because the
   AC Home send is dropped without D-Pad advertisement; the row implied
   navigation it cannot perform. Implementation kept: `CaptureButtonKind`
   enum value, `sendAcHome()`, `CarPlayLauncher.launchBlocking` home step and
   all protocol constants/callers remain. No key removed (there was none).
2. Mandatory-calibration wording ("Missing: …" as launcher status) — removed
   from the normal path; `missingSummary()` itself stays for the overrides
   screen. No key removed.

Orphan sweep: no UI-only fields/functions became orphaned. `captureHomeButton`
/ `captureHomeRow` fields are deleted with the row; `triggerCalibrationCapture`
stays (serves Capture Current).

## Launch-record format and export flow (unchanged)

- `CarPlayLauncher.LaunchRecord` JSON: `{id, started, target, nx, ny,
  verifyTemplate, steps[{t, step, detail, score|null, passed|null}], outcome,
  error|null}`. `Outcome`: SUCCESS, VERIFY_FAILED, FAILED, NOT_FOUND,
  UNVERIFIED. Saved `filesDir/launches/<id>.json` win or lose
  (`saveRecord`); uploaded to `{baseUrl}/api/launches` (`uploadLaunch`).
- `VisionLauncher` reuses the same record type; its steps are
  `templates/frame0/strip/grid/tap/toggle/verify/error`. v100 log showed 6
  SUCCESS + 1 VERIFY_FAILED, all `strip=built-in`.
- Export today: `LogExport.export` (SAF → USB volume → MediaStore → filesDir
  ladder, text artifact) for logs; `UploadCenter` HTTPS POSTs for logs,
  captures, launches. No FileProvider exists in the manifest (verified in the
  source and merged release manifests) and v101 adds none — the app has no
  AndroidX, so launch-diagnostics export reuses the MediaStore + share-sheet
  path instead.

## Bitmap ownership and locking (`CarPlayFrameGrabber`)

- `bitmapLock` is private; `reusableBitmap` is the single PixelCopy target.
- `captureBlocking()` returns `Success(bitmap=reusableBitmap)` — the caller
  gets a BORROWED reference valid only until the next capture. Same for
  `captureStableFrameBlocking()`. Thumbnailing and `classifyScreen()` run
  under the lock inside the grabber.
- `VisionLauncher.toGray()` reads pixels off-lock after return; safe today
  only because its single worker thread serializes capture→copy. There is NO
  snapshot API — v101 adds `copyStableFrameOwned()`-style copying INSIDE the
  grabber under `bitmapLock` (no lock exposure), and the evidence path copies
  pre-tap + verification frames while protected, recycles owned copies via
  `LaunchEvidence`, never recycles the grabber bitmap, and never holds the
  lock across PNG encode / file write / match / upload / sleep.

## v103 deltas (2026-10-18, versionCode 103 `4.0+insights-callpill-restart`)

- **Insights page** (`Ui.kt buildInsights`, `InsightsUi.kt InsightsPage`,
  `telemetry/InsightsData.kt`): fourth rail tab rendering this head unit's own
  probe segments website-style — latest-value tiles, Battery & range / Speed /
  per-segment-trend canvas charts (`InsightChartView`, dual-normalised),
  last-10 segment list. Loads off the UI thread; refreshes on tab select.
- **Strip Restart while FAILED** (`Ui.kt stripActionFor`, tested in
  `UiStripTest`): Back when live, primary Restart when FAILED, Done otherwise.
  Strip detail 1 → 2 lines (`SettingsKit.kt EqStatusStrip`) so the failure
  instruction survives. Intro failure screen gains a restart button
  (`IntroRestartButton`, bottom-right, FAILED-only via `syncIntroRestart`).
- **Pairing code 40sp → 30sp** (`Ui.kt buildTroubleshooting`): at the 1.35x
  panel scale cap 40sp rendered ~54sp and pushed the Status card's Restart
  button off screen. Only horizontal-overflow-class fix; the full four-tab
  `auditLayout` run shows zero `PAST_SAFE_RIGHT` (long pages scroll vertically
  by design).
- **Call pill** (`av/CallPillView.kt`, `NowPlayingState.callPhase/`
  `callPhaseOf`, tested in `CallPhaseTest`): Answer (RINGING only) / Hang up
  (`TEL_END`) / Mute (`TEL_MUTE`) overlay on `CarPlayActivity`, draggable with
  persisted fractions, hidden when the call table empties.

## v210 deltas (2026-10-22, versionCode 210 `4.0+log-range`)

- **Upload range** (`Ui.kt buildTroubleshooting`, `logging/UploadWindow.kt`,
  `upload/UploadFeedback.kt` `UploadCenter.logWindow`): row above Upload Logs with
  an `EqSegmented` of `1 hour | 6 hours | Today | All`, default **1 hour**,
  persisted in `upload_center_prefs/log_window` (absent → default, so an older
  install gains the shorter range rather than an unbounded upload). The button's
  subtitle names the live range. Whole-OS capture is ~227 MB/h and the directory
  ceiling is 768 MB, so this is the difference between a support action and a
  700 MB hotspot upload.
- **Log upload streaming** (`logging/LogExport.kt`): the window is counted, then
  posted one 500-line batch at a time instead of accumulating every payload in a
  list first (that version's peak memory was the capture size). An upload whose
  window is empty now fails with the oldest capture's name instead of reporting
  one uploaded line.
- **Music-sync diagnostics** (`av/NowPlayingState.kt`, `av/CarPlayMediaBrowserService.kt`):
  `np: sync` 30 s beat (records/title changes/metadata rebuilds/ticks/artwork/
  ignored kinds), `np: sync SILENT` once per silent gap, first-record latency,
  session summary at clear, and `browser: bind #n` per `onGetRoot`. See doc 14
  §14.8.

## Version high-water

- `app/build.gradle`: `versionCode = AAB_VERSION_CODE env ?: 8`,
  `versionName = AAB_VERSION_NAME env ?: "4.0+…"`.
- **Doc 14 owns the running record** (its "versionCode is not
  `git rev-list --count`" bullet); read it there instead of trusting a number in
  this file, which went stale twice. Current: **210 `4.0+log-range`**
  (2026-10-22, PUBLISH_OK).
- Track: `automotive:internal`, status COMPLETED. Publishing only happens via
  `tools/build_aab.sh --publish` (`AAB_VERSION_CODE` env); nothing auto-publishes.
