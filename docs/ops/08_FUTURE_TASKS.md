# Future tasks — planned work that is not a defect

> **STATUS:** CURRENT · single owner for planned, owner-directed work. Created 2026-09-04. Defects and
> verification gaps stay in `04_OPEN_ITEMS.md`; a task that lands is replaced here by a pointer.

Owner-directed work items that are neither an open defect nor a verification gap (those live in
[`04_OPEN_ITEMS.md`](04_OPEN_ITEMS.md)). One entry per task, dated when raised, with the current
state it starts from and what "done" means. When a task lands, replace its body with a one-line
pointer to the doc that now owns the result — do not leave a stale plan beside a shipped feature.

**CarPlay SDK plan (2026-09-22):** T6–T11 are the Communication Plug-in features this tree will
implement itself. The measured session of 2026-08-02 already negotiated `hevc`, `altScreen`,
`viewAreas`, `cornerMasks`, `iAPChannel`, and `sessionManagement`
(`host/gm_ccpa/docs/05_SESSION_FLOW.md`). These six are the ones that did not, and that an Equinox
session would actually gain. They are receiver work (`crates/`, `ccpa/`): the GM app freeze
(`host/gm_ccpa/docs/11_HARDENING_PLAN.md`) still means the Kotlin app adapts to the box, so none of
these land as an app-only patch. They are visible on a drive only when `carplayd` is the session.
Adapter Wi-Fi terminates CarPlay inside the dongle firmware, and that firmware is outside this list.

## T1. Settings redesign — projection-aware, vehicle-centric (raised 2026-09-04; IMPLEMENTED 2026-09-04)

> **State (2026-09-04):** built the same day under the design contract
> `host/MacHost/carlink_macOS/App/Settings/DESIGN.md`; the result is described in
> `../host/00_MACOS_HOST_APP.md` §"Settings window" and the AA side in
> `../androidauto/00_ARCHITECTURE.md` §4. The shape that landed differs from the plan below in two
> ways worth knowing: (1) the tabs are **Vehicle / Adapter / Diagnostics**, not Vehicle / CarPlay /
> Android Auto / Transport — protocol-exclusive settings are badged sub-groups INSIDE the feature they
> belong to, never a protocol tab; (2) the AA engine no longer snapshots the CarPlay model
> (`AACapability.init(config:)` is retired) — it renders the neutral `VehicleProfile` through
> `AACapability(profile:adapter:warn:)`, exactly as the CarPlay YAML is rendered from the same profile.
> Kept below for the record of what was asked; the plan text is no longer the spec.

**Why.** The app supports two projection protocols, Apple CarPlay and Android Auto, each wired and
wireless. The Settings window does not reflect that: its Configuration tab is one long list built
around the CarPlay `VehicleConfig` YAML (`host/MacHost/carlink_macOS/App/SettingsWindow.swift`,
three tabs: Configuration, CCPA, Diagnostics), and everything Android Auto specific is an
environment lever read at launch (`AA_FORCE_RES`, `AA_NO_TOUCH`, `AA_DRIVER_POSITION`,
`AA_TELEPHONY_SINK`, `AA_LEGACY_VIDEO`, `AA_SKIP_AUDIO_ACK`, `AA_TRACE_UNHANDLED`, `AA_P12`,
`AA_P12_PASS`, …) with no UI at all. Some vehicle facts are shared but only half-wired: driver side
reaches Android Auto's `driver_position`, and its CarPlay consumer — the `/info rightHandDrive`
boolean, R14G17 `AirPlayCommon.h:1103` — is landing 2026-09-05, unverified on a device (until then this
sentence said it "has no CarPlay consumer", which conflated no-consumer-on-the-box with no-key;
`docs/carplay/04_CAPABILITIES_AND_CONFIG.md` §rightHandDrive); night mode is a CarPlay runtime command and
an AA sensor but its config-field form is stored and not pushed. (Corrected 2026-09-04: this sentence said "the `rightHandDrive` toggle
drives `driver_position`". After the reorganisation the source is the neutral `driverPosition`
ternary — `left` / `right` / `center` → wire 2 / 1 / 3 — and `rightHandDrive` / `nightMode` survive
only as write-only UserDefaults values derived from `driverPosition` / `theme` for an app downgrade;
neither is in the pushed YAML since 2026-09-02.)

**Target shape.**

1. **Vehicle** — facts about the car that apply to both protocols, each a real control (toggle,
   segmented picker, stepper), never a free-text field where a bounded choice exists:
   name; drive side (Left / Right, and Center for AA); display geometry (resolution, fps, density)
   with the per-protocol consequence shown inline (CarPlay takes any size, AA snaps to 800×480 /
   1280×720 / 1920×1080 — `AACapability.Resolution.nearest`); night mode source; driving-restriction
   policy; microphone and telephony (HFP for AA, in-band for CarPlay); instrument-cluster / second
   display when it exists.
2. **CarPlay** — what only iOS consumes: the iAP2 feature tier (`proven`/`extended`/`all` and skips),
   audio format matrix, enhancedSiri, ETC, EV fields, wireless (BT/Wi-Fi) options, the SSP pairing
   answer lever.
3. **Android Auto** — what only gearhead consumes: touchscreen vs controller (`AA_NO_TOUCH`),
   declared keycodes, video/legacy-video experiments, the metadata services once T2 lands, the
   head-unit certificate source. Every current `AA_*` lever that is a real setting becomes a
   control here; every one that is a bench-only experiment moves to a clearly labelled Experiments
   group, off by default, with the guard-rail text from `03_WIRELESS.md` §6.
4. **Transport** — wired / wireless per protocol, shown as state, with the box-side actions the
   CCPA tab already has (restart wireless stack, forget bonds, enter NCM).

**Rules that bind the redesign.**

- App-driven doctrine stays: the app is the single source of truth and pushes; the box presents
  (`docs/carplay/04_CAPABILITIES_AND_CONFIG.md`). Nothing here adds a box-side default.
- A setting the box or the phone does not consume must say so where it is shown (the existing ⚠️
  inline convention), or not be shown. Settings that take effect only on the next session say so.
- Apple HIG for macOS: switches for booleans, segmented controls for small enumerations, steppers
  or pickers for numbers, sentence-case labels, help text in the inspector style already used.
- The generated YAML preview and the "unsaved changes" flow are kept; the YAML schema may grow but
  must not break `tools/proto_check.py` or the existing config push.
- Model split follows the UI: a shared vehicle model, plus a per-protocol model, so the AA engine
  keeps taking a `Sendable` snapshot and never reads the observable model from the session thread.
  (As landed: the snapshot is `AACapability(profile:adapter:warn:)` over the neutral `VehicleProfile`;
  `init(config:)` over the CarPlay model is gone.)

**Done means.** No `AA_*` environment variable is required for a normal session; the drive-side
toggle changes both protocols (CarPlay half needs the box consumer wired — see the open item); the
Configuration tab is three groups the owner can scan in one screen each; `docs/host/00_MACOS_HOST_APP.md`
and `docs/carplay/04_CAPABILITIES_AND_CONFIG.md` describe the new layout in place.

## T2. Android Auto metadata services — LANDED 2026-09-04

Shipped the same day it was raised; the result and the wire table live in
[`../androidauto/01_SESSION_AND_AV.md`](../androidauto/01_SESSION_AND_AV.md) §"Metadata services".
Left open there: the Media Browser and Generic Notification services (not declared), and the
head-unit → phone `MediaPlaybackInput` control path.

## T3. Wideband HFP audio (mSBC) — LANDED 2026-09-04

Negotiated and streamed on device the same day (`+BCS: 2`, transparent eSCO, 60 B packets, 134
frames/s decoded, mic at 16 kHz mSBC); the pure-Swift codec and the kernel-3.14 socket detail are
in `../androidauto/01_SESSION_AND_AV.md` §telephony and `../host/00_MACOS_HOST_APP.md`. Still
behind the box lever (`/script/hfp_wbs`) until a few real calls have been heard. Follow-ons:
super-wideband (LC3-SWB, 32 kHz — the Pixel advertises it; needs an LC3 codec in the app and
`AT+BAC=1,2,3`), and an app-side dump of the decoded telephony lane for an objective bandwidth
measurement against the Harvard reference.

## T4. Non-standard Android Auto display sizes — LANDED 2026-09-04

Shipped and owner-confirmed the day it was raised (2400×960 panel → tier 2560×1440 H.265 with
`height_margin 416`; window 2.5:1, UI edge to edge, touch accurate). Mechanism, tier table and the
phone-side citations live in [`../androidauto/01_SESSION_AND_AV.md`](../androidauto/01_SESSION_AND_AV.md)
§1 "Video". Left open, none blocking:

- **Density as a real setting.** `density` defaults to 160 with an `AA_DENSITY` bench lever
  (2026-09-04: 240 on the 2400×960 panel scaled the UI ×1.5 with the rail 80→120 px, same visible
  rect). gearhead sizes everything from it, so the profile needs either a direct DPI field or a
  physical panel size (`density = px diagonal / inch diagonal` of the VISIBLE rect). T1's home.
- **`ui_config.margins`** (four-sided) for asymmetric placement — codec margins are always split
  evenly by the phone, which is what the app's centre-crop assumes. Only needed if a panel wants
  the visible rect off-centre.
- **Priority-ordered configuration list.** `MediaSinkService.video_configs` is repeated and gearhead
  takes the first it allows; declaring e.g. [tier+margins, 1920×1080+margins] would make the
  session survive a phone whose encoder refuses the first choice. The Config reply's
  `configuration_indices` would need to list them.
- Fullscreen: the crop assumes the view keeps the visible aspect; a fullscreen display of a different
  aspect will letterbox around the (correctly cropped) frame.

## T5. Android Auto cluster / auxiliary display — a second projected video stream (raised 2026-09-04)

**What it is (from gearhead 17.5, not from any public doc).** `MediaSinkService` carries
`display_id` (field 6) and `display_type` (field 7, gal `DisplayType`: 0 MAIN, 1 CLUSTER, 2
AUXILIARY). A head unit that declares a SECOND video sink with `display_type = CLUSTER` (or
AUXILIARY) gets a second H.264/H.265 stream, exactly like CarPlay's alternate video: gearhead
creates a `CarDisplayId` per video sink (`ivc.java:155-170`) and renders a dedicated component on
it — for the cluster that is Google Maps' `GmmCarAuxiliaryProjectionService` ("auxiliary map"),
the only component in the `MultiDisplay__cluster_display_supported_components` flag (Maps, its
dev/dogfood/fishfood builds). Other flags: `cluster_display_default_configuration = 2`,
`cluster_launcher_enabled = false` (no app launcher on the cluster), `reject_clusters_for_
unsupported_nav_apps` = {hyundai, kia, genesis} (an OEM reject list keyed on our declared
manufacturer — not us), `cluster_rotary_window_navigation = true`. The DHU has the matching keys
`instrumentcluster`, `navcluster`, `phonecluster`, `displaytype = main|cluster|auxiliary`.
This is distinct from the **data** path already landed (T2: `NavigationStatusService`, where the
head unit draws its own cluster from maneuver/distance/ETA messages); the video path shows the
phone's own map on the cluster.

**Plan.** Declare a second `MediaSinkService` (new channel id, `display_id 1`, `display_type 1`,
its own `VideoConfiguration` — tier/margins/density computed for the cluster panel) behind a lever;
run the channel-open / SETUP / CONFIG / START / CODEC_CONFIG / DATA / VideoFocus dance on that
channel a second time, feeding the app's existing alternate decoder (`altDecoder`, the CarPlay
alt-video path) into a second window. Read gearhead's `CAR.WM "Configuring display: %s, %s"` and
`GH.DisplayLayout` lines for what it chose; watch for the cluster component appearing when a
route is active. Unknowns: whether a session without a route shows anything on the cluster,
whether input is expected on that display (`InstrumentClusterInput`), and focus behaviour between
the two displays. **Done means** the phone's map renders in a second window while the main display
keeps projecting, recorded with the phone-side lines.

## T6. Car GPS into Maps — iAP2 LocationInformation (raised 2026-09-22)

**Why.** Maps is using the phone's own fix. The plug-in expects the accessory to send `0xFFFB`
LocationInformation after the phone sends `0xFFFA` / `0xFFFC`. The payload is NMEA-0183 (`GPRMC`,
`GPGGA`, and the rest) plus Apple's `PASCD` when the car has speed.

**State.** `crates/vendor/metadata/src/location.rs` parses only. Identify param 22
(`LocationInformationComponent`) has no caller (`docs/carplay/05_METADATA_AND_CONTROLS.md` §2.1).
The direction is the opposite of Now Playing: param 6 carries `0xFFFB` and param 7 carries the
Start/Stop pair. `features::Feature` cannot express that shape, and Mechanism B step (3) in
`docs/carplay/04_CAPABILITIES_AND_CONFIG.md` has those ids backwards. Correct that paragraph before
any declaration. The wireless Identify stays on its pinned id list until growth there is
re-validated; this arms on the wired receiver Identify only.

**Done means.** A receiver-path session declares param 22 together with `0xFFFB` / `0xFFFA` /
`0xFFFC`, the phone subscribes, a logged `0xFFFB` carries a real fix, and `accessoryd` accepts the
Identify (no `0x1D03`).

## T7. EV vehicle status — range and charge (raised 2026-09-22)

**Why.** The Equinox is electric. Maps will not treat it as one until Identify says so and a status
update follows. `spec.rs` already has `EngineType::Electric` and `SupportedChargingConnectors`.

**State.** The compiled baseline is `EngineType=Gasoline` unless a pushed identity overrides it
(`message.rs`, `VehicleIdentity::baseline()`). Param 21 is reachable only through
`build_ident_info_with` when the app pushes `vehicleStatus:`, and only on a non-wireless arm.
`features.rs` declares none of `0xA100` / `0xA101` / `0xA102`, so enabling the component today
advertises a capability the messages never service. `message.rs` (the param 21/22 block) says to
leave `vehicleStatus:` off on hardware until that declaration lands. Same direction trap as T6:
`0xA101` is accessory-sourced.

**Done means.** Pushed identity is Electric, with the connector the car actually has. Param 21 and
the three message ids are declared together. A `0xA101` VehicleStatusUpdate carries range, and
charge state when the car provides it, and `accessoryd` accepts the Identify.

## T8. MainBuffered audio, phase B (raised 2026-09-22)

**Why.** Music rides MainHighAudio (stream type 102), realtime UDP. MainBuffered is the TCP media
path: the head unit holds up to a two-minute buffer fed faster than realtime, so a short link loss
does not drop playback. The codec does not change (`docs/carplay/04_CAPABILITIES_AND_CONFIG.md`,
`enablesMainBufferedAudio`).

**State.** Phase A is done and wired-device-proven: `/info` can carry `mainBufferedInfo` and SETUP
can echo `"mainBuffered"` when the app sets the toggle. The app default is off, because advertising
a stream the box then refuses silences media. Phase B is the stream itself. SETUP phase 2 still
omits it (`session.rs`, "Still unimplemented and therefore omitted": AuxOutAudio, AuxInAudio,
MainBuffered). Confirm the stream-type number and the `mainBufferedInfo` dictionary against
`CarPlaySDK.framework` before writing that arm. This plane is also what T11 needs.

**Done means.** With the toggle on, iOS opens a MainBuffered stream and music plays through it. With
the toggle off, the session bytes match today.

## T9. UI context — which CarPlay app is showing (raised 2026-09-22)

**Why.** The car cannot tell Maps from Now Playing. The wire is `/info`
`uiContextLastOnDisplayURLs` / `uiContextNowOnDisplayURLs` plus the `changeUIContext` command
(`docs/carplay/03_SDK_GROUND_TRUTH.md` §9).

**State.** `enablesUIContext` is not parsed; serde drops it. The 2026-08-02 session did not
negotiate `uiContext`. Build order is the corner-masks arc: parse the key, emit `/info`, echo the
token in SETUP `enabledFeatures`, then handle the command.

**Done means.** A session log shows `uiContext` surviving the feature intersection, and a URL change
reaches the host when the driver switches CarPlay apps.

## T10. Focus transfer between CarPlay and the head unit (raised 2026-09-22)

**Why.** Input focus has to move between CarPlay and the head unit's own UI when both are on screen.
`accessoryAcquireFocus`, `accessoryGiveFocus`, and `deviceOfferFocus` are the commands. Screen
ownership is a different feature and already works (`changeModes` / `modesChanged`).

**State.** `enablesFocusTransfer` is parsed. A per-view-area `viewAreaSupportsFocusTransfer` flag
can be advertised. `"focusTransfer"` is not echoed in `enabledFeatures`, and none of the three
runtime commands are handled (`docs/carplay/04_CAPABILITIES_AND_CONFIG.md` capability row 1). The
2026-08-02 session did not negotiate it.

**Done means.** With the app toggle on, `focusTransfer` survives SETUP, and a focus offer from the
phone is answered with the session still up.

## T11. Enhanced Siri — car mic, always on (raised 2026-09-22)

**Why.** Button Siri already works (`requestSiri`, mic uplink on `CH_MIC`, stream type 100).
Enhanced Siri is the wake-word path: `enhancedSiriInfo` in `/info`, a dedicated downlink AuxOut
(106) and uplink AuxIn (107), an always-on mic, and the two detectors the plug-in requires
(keyword and voice activity) before iOS checks the hit again
(`docs/carplay/03_SDK_GROUND_TRUTH.md` §7).

**State.** `enablesEnhancedSiri` is serde-ignored. `enhancedSiriInfo` is absent from `crates/` and
`ccpa/`. The SETUP arm that omits MainBuffered also omits AuxIn and AuxOut, so T8's audio plane
comes first. The mic DSP and the two detectors do not exist on the box.

**Done means.** With the toggle on, a wake word on the car mic starts Siri, the reply plays on
AuxOut, and media keeps running. With the toggle off, button Siri is unchanged.

## Left off this plan

Kept here so a later session does not promote them into tasks.

- **Already served.** HEVC, touch, corner masks, view areas (including `updateViewArea`,
  device-proven 2026-09-07), alt screen on the Mac host, button Siri, route guidance at the
  `proven` metadata tier, `changeModes`, `uiAppearanceUpdate`, `mapAppearanceUpdate`. Hiding the
  Equinox's GM bars is a window-insets problem, already in the app
  (`host/gm_ccpa/docs/00_HANDOFF.md`). A view area is not that mechanism.
- **Already a defect.** `stopSession` and the session-reason fields stay in
  [`04_OPEN_ITEMS.md`](04_OPEN_ITEMS.md). They are not copied here.
- **No wire to build against.** DCX has no CarPlaySDK feature string. File transfer's `/info`
  dictionary shape is unknown. UI sync needs the CarPlayClusterControl UUID, which is unrecovered,
  and cluster hardware this car has not exposed to the app.
- **Entitlement.** Full-screen video playback (`enablesVideoPlayback`, `lunaConfig`) requires Apple
  to grant `com.apple.developer.carplay-video`.
- **Wrong panel.** Knob, d-pad, and touchpad descriptors are for other head units. The Equinox is
  a touchscreen.
- **No surface yet.** A second CarPlay video stream on the Equinox cluster waits until the VCU
  gives this app a cluster surface. Alt screen already renders in the Mac app.

