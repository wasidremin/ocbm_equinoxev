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
| Forget all iPhones (was "Forget pairing") | `buildAdvanced`, `confirmForget()` | action only (clears every box bond + app Ed25519 store) | Destructive, confirmed | Advanced | keep | Destructive radio/pairing op; keep confirm |
| Phones (2026-10-25) | `buildSettings`, `setPhones()`, `PhoneBook` | `phone_book/<MAC>` (name, model from `CT_PHONE_IDENT`) | Lists the adapter's bonds from `MGMT_GET_INFO` in its connect order, marks the live one; Forget per phone sends `MGMT_FORGET_DEVICE` (box restarts wireless). App peer store untouched. With adapter firmware that reports `phone_order` (2026-11-05): ↑/↓ send `MGMT_SET_ORDER` (kept on the box's flash), Connect sends `MGMT_CONNECT_DEVICE` after a confirm; older firmware shows a note instead | Settings | keep | `docs/ops/08_FUTURE_TASKS.md` T12; built 2026-11-05, unverified on the car |

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
| Custom top bar | `buildDisplay` card | `carplay_screen/custom_top_bar` (immediate) | Strip in freed status area: ten CarPlay-style tiles (doc 14 §15), also shown on the settings screen | Settings | keep + relabel subtitle | Normal preference; subtitle must state consequence |
| Apply size now | `applyRow` (visible when `sizePending()`) | action | `onRestart()` | Advanced | move | Companion to legacy scale controls |
| Restart app | `buildDisplay` bottom row | action | `onRestartApp()` | Advanced | move | Support escape hatch, not a preference |

Note: `sizePending()` compares legacy `ui_scale`/`safe_right` against the latched
frame only — the new-Controls draft has its own Save row. Both stay functional
wherever the rows live; no key changes.

## Audio page (`Ui.kt buildAudio`)

| Current label | Source | Persisted key | Actual behavior | Proposed location | Verdict | Reason |
|---|---|---|---|---|---|---|
| Audio source (Adapter/Bluetooth) | `buildAudio`, `audioRow` | `audio_route/bluetooth` (immediate) | Car plays iPhone over BT vs app audio path | Settings | keep | Useful playback preference |
| Steering-wheel voice button (2026-10-26) | `buildSettings` ▸ Voice, `voiceRow`, `voice/AssistantRole` | none (the car's own assistant role) | Status of the assistant role; "Choose" opens the car's assistant picker (`VOICE_INPUT_SETTINGS` / `MANAGE_DEFAULT_APPS_SETTINGS`) when one resolves. Selecting GM CCPA makes a wheel press send the Siri press/release pair and turns off Google Assistant; while a CarPlay call rings, the press answers it instead (233, `av/WheelCall`) | Settings | keep | `docs/ops/08_FUTURE_TASKS.md` T14; whether GM shows a picker is unverified |
| Turn-by-turn in the cluster (2026-10-26) | `buildSettings` ▸ Navigation, `navRow`, `nav/ClusterNav` | `cluster_nav/enabled` (default off) | Enables `ClusterNavService` and relays CarPlay route guidance to the cluster through the Car App Library; first route after app start flashes the hidden `CarAppActivity` for ~1 s. Subtitle reports support/bound state; "Not in this build" on a `build_apk.sh` APK. Stands down (subtitle says "Paused") while CarPlay map on the driver display is on (2026-11-08, `ClusterNav.active`) | Settings | keep | `docs/ops/08_FUTURE_TASKS.md` T13; unverified on the car |
| Media pane (2026-11-07; tap strip and Over CarPlay 2026-11-08; art backdrop and chip 2026-11-11) | `buildSettings` ▸ Display, `mediaPaneRow`, `pane/SplitPane` + `pane/MediaPaneView` + `pane/PaneStrip` + `pane/ArtBackdrop` + `pane/MediaChip` | `media_pane/side`, `media_pane/percent` (default Over CarPlay), `media_pane/shown` (default hidden) | Cycles Over CarPlay / Right 30% / Right 40% / Left 30% / Left 40% / Off. The pane (art, title, progress, previous / play-pause / next, timed lyrics) shows in any gear. Behind it is the cover blown up, blurred and dimmed (`ArtBackdrop`: its brightness capped at luma 72 of 255, so white text keeps 7:1 or better on any cover; a new song's fades in over the last); with no cover the pane is its plain navy. A tap on the glass right of the picture (`PaneStrip`, under the top bar) shows or hides it, as does its top button; the state is kept for the next drive. Where the traced glass reaches furthest the strip draws a mark: while the pane is hidden, the driver display's round now-playing chip (`MediaChip`: the cover in a ring that fills as the song plays, a note with no cover), and while it is up, or on glass under about 52 dp wide, a faint handle. Over CarPlay: laid over the right 30% of the picture, nothing sent to the phone, Off ↔ Over applies at once. A side: at the next subscribe the config declares CarPlay's side as `viewAreas[1]` (`initial: true` when the pane was last showing) and the pane covers the strip iOS leaves black; showing and hiding send `CMD_VIEW_AREA 1`/`0`, and iOS's own dock toggle is relayed back (`requestViewArea`) | Settings | keep | Owner request: for the passenger; 2026-11-08 the owner asked for the tap strip after a drive with the pane Off |
| CarPlay map on the driver display (2026-11-08) | `buildSettings` ▸ Navigation, `driverRow`, `nav/DriverDisplay` + `nav/DriverDisplayActivity` | `driver_display/enabled` (default off) | At the next subscribe, declares the driver display (the largest non-default public display the app may open a window on) as CarPlay's cluster screen; once the picture is up, opens a window there and shows CarPlay's cluster map (`CH_ALT_VIDEO`, `CMD_NAV_START`). The window closes the moment GM hides it; a long-press on the strip right of CarPlay opens it again. Selecting the driver display's map page again reopens it too (GM page events, `nav/GmClusterPageService`, from 238; needs one head-unit restart). Turn-by-turn in the cluster stands down while this is on. Subtitle says "Experimental" and whether such a display was found. Every connect logs the displays, setting on or off | Settings | keep | Owner request, porting openautolink's idea; on the car 2026-11-08 the map showed then GM's own map took the display back (`docs/ops/04_OPEN_ITEMS.md`) |
| Directions area on the driver display (2026-11-08) | `buildSettings` ▸ Navigation, `directionsAreaCard`, `nav/DriverDisplay.Margins` | `driver_display/directions_margins` (`l,t,r,b` percent; default none = whole display) | Four edge sliders (0–35%), GM layout (28/9/15/17%, openautolink's Blazer default) and Reset. Declared as the cluster stream's `safeArea` at the next subscribe; the map still fills the display. Each change outlines the area on the driver display for 30 s while the map window is open | Settings | keep | openautolink's "Directions area" idea, our code; unverified on the car (`docs/ops/04_OPEN_ITEMS.md`) |
| Driver display ▸ Turn card (2026-11-09; redrawn 2026-11-10) | `driverLayoutCard`, `nav/TurnCard`, `DriverDisplay.turnCard` | `driver_display/turn_card` (default on) | During a route, drawn by the app over CarPlay's map from the route guidance the phone already sends; no GM navigation, so GM's map page stays reachable. A small pill (glyph, distance, road) while the turn is far; inside 800 m a full card with a bar that fills as the turn nears, a green EXIT sign when the phone names one, and a "Then" tab when the turn after follows within 400 m. No arrival time: Apple's map draws its own. Day and night follow the car (`CarNight`). A sample shows during the 30 s preview. Road names are never logged | Settings | keep | emulator-checked on a simulated 1600x600 display, day and night; unverified on the car (`docs/ops/04_OPEN_ITEMS.md`) |
| Driver display ▸ layout editor and Size (2026-11-10) | `driverLayoutCard`, `nav/ClusterLayoutEditor`, `ClusterLayout.place` / `rect` | `driver_display/slot_turn`, `slot_media` (`x,y,scale` percent; defaults 2,6,100 and 70,30,100; the earlier `turn_card_area` corner is carried over) | A miniature of the driver display with the gauges and the directions area drawn in; each card is dragged to its place, and Smaller / Larger (50–200%, steps of 10) act on the card last touched; Reset puts both back. A card and what hangs under it are kept on the display, shrunk when they cannot fit. App-drawn, so it applies at once and is never declared to CarPlay. Places are outlined in amber with the 30 s preview. Replaces the four edge sliders of Play 242 | Settings | keep | Rendering emulator-checked; the drag itself is untested (the emulator bench has no touch) |
| Driver display ▸ Dock the turn card (2026-11-11) | `driverLayoutCard`, `TurnCard.drawDocked`, `ClusterLayout.dock` / `dockRect` / `footprint` | `driver_display/turn_dock` (default off), `slot_turn_dock` (`x,y,scale` of the panel's top-left corner; default 70,54,100) | The turn card as a panel in the bottom-right corner in the look of the Equinox's gauges on the left: black, a slanted inner edge (mirrored, 0.43 across per 1 down), white figure with a small blue-grey unit, orange countdown bar. It runs off the right and bottom edges of the window as the gauges do, so GM's mask trims it; the lanes, or else the turn after, sit inside it under the bar. Same colours day and night. The editor drags and sizes its corner, kept separately from the free card's place. The default mirrors the gauges as measured off one photo of the car | Settings | keep | emulator-checked with the preview's sample; unverified on the car |
| Driver display ▸ Now playing (2026-11-10) | `driverLayoutCard`, `nav/MediaCard`, `ClusterLayout.media` | `driver_display/media_card` (default on) | Cover, title, artist and a progress line on the right side, under the speed-limit sign Apple's map draws. With no route it stays open; during a route it opens for 8 s on a new song, then folds to a pill: the cover inside a progress ring with the title small beside it (2026-11-11; the cover alone on Play 243). No buttons: the driver display takes no touch. Titles are never logged | Settings | keep | emulator-checked with the preview's sample; unverified on the car |
| Driver display ▸ Call status (2026-11-10) | `driverLayoutCard`, `MediaCard.drawCall`, `NowPlayingState.callCard`, `ClusterLayout.call` | `driver_display/call_card` (default on) | A ringing or active call takes the now-playing card's place: the caller's name (or "Phone call"), "Incoming call" or a running timer. Callers are never logged | Settings | keep | unit-tested timer; not seen with a real call |
| Driver display ▸ Lane guidance (2026-11-10) | `driverLayoutCard`, `TurnCard.drawLanes`, `RouteGuidance.Lane`, `ClusterLayout.lanes` / `metadataTier` / `onSilentSession` | `driver_display/lane_guidance` (default off) | Lane arrows under the turn card, inside 800 m, where Apple Maps presents them. On means the subscribe document asks for metadata tier `extended` instead of `proven` (`0x5204` is in `extended` only), from the next connect; with a cable the tier changes only when the phone is unplugged and replugged. If a session of 90 s or more then carries no metadata record at all, the switch turns itself off so the next connect goes back to `proven`. Positive lane angles are drawn to the right; no source on disk records the sign, so `cluster` logs the maneuver type next to the highlighted angles (numbers only) once per junction to settle it | Settings | keep | Experimental: never run against the phone from this app. The sample strip is emulator-checked |
| Look up lyrics (2026-11-07) | `buildSettings` ▸ Playback, `lyricsRows`, `lyrics/BarLyrics` | `lyrics/lookup` (default off) | Looks each song up on lrclib.net (title, artist, album, length; `lyrics/LrcLib`, ported from enigy/carlink) for the media pane and the top bar. Found lyrics are kept on the head unit (`noBackupFilesDir/lyrics`, 100 MB, least played go first); Clear deletes them. Logs counts only, never song names | Settings | keep | Owner request |
| Lyrics in the top bar (2026-11-07) | same, `lyricsRows` | `lyrics/top_bar` (default off) | The line being sung in the custom top bar's free middle, only while the gear is in Park (the top bar is in the driver's eyeline; the pane is not limited) | Settings | keep | Owner request |
| Echo test / Voice test (2026-11-07) | `buildSettings` ▸ Calls, `calltest/CallTestsCard` | none | Parked-only (gear in Park, no CarPlay call). Echo test plays sweeps on a voice-call track and measures each mic path's echo; Voice test records the driver on each path and plays the takes back. Paths: VOICE_COMMUNICATION, VOICE_RECOGNITION (the one calls use while the assistant role is held), VOICE_RECOGNITION with Android AEC/NS/AGC, GM's call bus if listed, raw mic. Lines go to the log under `calltest` | Settings | keep | Answers `13_AUDIO_ROUTING.md` §4's open echo question |
| Album art while Maps is on screen (2026-11-04) | `buildSettings` ▸ Navigation, `mapsArtRow`, `nav/ClusterNav.setArtWhileMaps` | `cluster_nav/art_while_maps` (default on) | While a route runs, `av/MapsWatch` reads the CarPlay screen every 2.5 s for full-screen Maps' turn card; while it is in front the cluster session ends navigation so the cluster shows the album art. Does nothing unless Turn-by-turn in the cluster is on | Settings | keep | `docs/ops/08_FUTURE_TASKS.md` T13; in 230, works on the car 2026-11-05; misread fix in 231 |

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
overrides — built-ins are the default. Since 234 (2026-11-07) the status opens with "Maps, Now
Playing, Spotify and Phone open by command from the adapter" when the adapter reports `CAP_UI_URL`;
those tiles then need no template, and the templates still serve CarPlay Home, Play Spotify on connect
and older adapters (`14_LESSONS_LEARNED.md` §15).

## Advanced page (`Ui.kt buildAdvanced`)

| Current label | Source | Persisted key | Actual behavior | Proposed location | Verdict | Reason |
|---|---|---|---|---|---|---|
| Adapter Wi-Fi | `buildAdvanced` toggle | `adapter_wifi/on` (restarts connection) | iPhone joins adapter vs car hotspot | Advanced | keep | Radio topology; reconnect-required, keep confirm |
| Vehicle hotspot | `hotspotRow` + dialog | `hotspot_credentials/ssid,pass,chan` | Pushed at SUBSCRIBE; blank-by-design forces entry | Advanced | keep | Needed for vehicle hotspot; keep dialog |
| Adapter info | `infoRow` | action only | `BoxAction.INFO` | Advanced | keep | Probe |
| USB bridge (2026-11-06) | `bridgeRow`, `MainActivity.refreshBridge()`, `FilesApp` | action only | Whether the sideloaded USB bridge is installed and whether the last link went through it; when absent, whether this car can let Files install an app. Open Files starts the hidden Files app | Advanced | keep | `docs/ops/08_FUTURE_TASKS.md` T15; unverified on the car |
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
  source and merged release manifests) and v101 adds none — `src/main` has no
  AndroidX (the only AndroidX dependency, the Car App Library, is confined to
  `src/carapp` since 2026-10-26), so launch-diagnostics export reuses the
  MediaStore + share-sheet path instead.

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
  nav records (since 2026-10-26)/ignored kinds), `np: sync SILENT` once per silent gap, first-record latency,
  session summary at clear, and `browser: bind #n` per `onGetRoot`. See doc 14
  §14.8.

## 2026-11-11 deltas (drive log and trip tabs; Play 246)

- **Insights tabs** are now `Last drive | Routes | Charging | Week | Diagnostics`
  (`InsightsUi.kt`, `InsightsTripsUi.kt`). The v209 views (Vehicle state,
  Sessions, Signals, Segments) are the inner control of Diagnostics. Deep link
  `open_tab --ei tab 3 --ei subtab 0..4` follows the new order.
- **New settings, all on the Charging tab** (prefs `ev_insights_ui_prefs`):
  Electricity rate (`rate_per_kwh`, blank until set — cost figures show a dash
  and say what to set), Gas price (`gas_per_gallon`, blank skips the dollar
  comparison; MPGe is always shown), Comparison car (`compare_mpg`, default 30).
  Route names from "Rename this route" are `route_name_<key>` in the same file.
- **Clear drive history** (Diagnostics → Vehicle state, destructive confirm):
  deletes `insights/trips.ndjson` and `insights/open.json`. Clearing probe
  segments does not touch them, and they are never uploaded.
- The drive log is recorded whenever the app runs and holds coordinates only
  with "Include route GPS in EV Insights" on; without GPS, drives are still
  logged but no routes are found. Detail: doc 14 §13.4.

## 2026-11-11 deltas (data budget; Play 246)

- **EV Insights auto-upload** (Advanced ▸ Upload server & EV Insights,
  `vehicle_telemetry_auto_prefs/auto_upload_enabled`, default on) now decides
  only whether telemetry leaves the car. Recording, and with it the drive log,
  runs either way. On, it sends each driven minute and a heartbeat while parked
  or charging; off, segments are marked `.skipped` and pruned.
- **Car data used this month** (new row under it, `Ui.kt dataUsedRow`,
  `upload/DataMeter.kt`, prefs `data_meter_prefs`): month-to-date bytes the app
  sent off Wi-Fi, by stream, with an `EqSegmented` limit
  `50 MB | 100 MB | 250 MB | No limit` (`limit_mb`, default 100, 0 = none).
  At the limit background telemetry pauses until next month or Wi-Fi; uploads
  the driver taps are not blocked. Wi-Fi bytes are shown but not counted.
- **Upload Logs** measures first and asks before sending when the range is
  "All" or the estimate is above 2 MB on the wire; the dialog names the size
  and the connection. Batches are gzipped. Off Wi-Fi the dialog's main button
  is **Send on Wi-Fi**: the request waits (`upload_center_prefs/deferred_log_window`,
  `deferred_log_at`; `upload/LogsOnWifi.kt`) and goes out when the car next has
  Wi-Fi; "Pending uploads" shows it. Scripted run: `--es run upload_log` (asks),
  `--ez yes true` (sends now), `--ez wifi true` (waits for Wi-Fi).
- Detail and measurements: doc 14 §13.5.

## Version high-water

- `app/build.gradle`: `versionCode = AAB_VERSION_CODE env ?: 8`,
  `versionName = AAB_VERSION_NAME env ?: "4.0+…"`.
- **Doc 14 owns the running record** (its "versionCode is not
  `git rev-list --count`" bullet); read it there instead of trusting a number in
  this file, which went stale twice. Current: **210 `4.0+log-range`**
  (2026-10-22, PUBLISH_OK).
- Track: `automotive:internal`, status COMPLETED. Publishing only happens via
  `tools/build_aab.sh --publish` (`AAB_VERSION_CODE` env); nothing auto-publishes.
