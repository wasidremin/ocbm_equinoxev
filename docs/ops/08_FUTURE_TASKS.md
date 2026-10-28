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

## T1–T5. Removed with their components (2026-10-27)

T1 was the macOS app's Settings redesign. T2–T5 were Android Auto work: metadata services, mSBC
wideband HFP, non-standard display sizes, and a cluster video stream. The macOS app and Android Auto
were removed from this fork on 2026-10-27, and these tasks went with them. Git history has the text.
Later tasks keep their numbers.

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

## T7. EV vehicle status — range and charge (raised 2026-09-22; BUILT 2026-10-27, not yet on the car)

**Why.** The Equinox is electric. Maps will not treat it as one until Identify says so and a status
update follows.

**What was built (2026-10-27).** One app setting, Settings ▸ Navigation ▸ "EV battery for Apple
Maps", off by default.

- **App.** `ev/EvForCarPlay.kt` writes the `iapConfig:` block into the adapter subscribe document:
  `engineTypes: [electric]`, the car's connectors from `INFO_EV_CONNECTOR_TYPE` (J1772 + CCS1 when
  the car does not say), and the capabilities `range, outsideTemperature, rangeWarning,
  rangeElectric, electricChargeInfo`. Only the unified `rangeWarning`: Apple's spec forbids it
  together with a per-engine `rangeWarning*`, and `iap2_core::config` drops the per-engine one. While
  connected, `ev/CarPlayEv.kt` reads `RANGE_REMAINING`, `EV_BATTERY_LEVEL`,
  `INFO_EV_BATTERY_CAPACITY`, `EV_CHARGE_STATE` (or port + charge rate), `ENV_OUTSIDE_TEMPERATURE`
  and `FUEL_LEVEL_LOW` every 15 s and pushes them, when changed, to the adapter's
  `/tmp/vehicle_status` over `CH_FILE`.
- **Identify (`iap2_core::message`).** Only on the AirPlayTunnel arm: param 20 carries the pushed
  identity, and param 21 is declared together with 0xA101 in param 6 and 0xA100/0xA102 in param 7
  (the inverted direction; C-4 in `04_OPEN_ITEMS.md`). The wired and BT-time arms carry none of it. A
  `0x1D03` retry naming 6, 7, 20 or 21 drops vehicle status wholesale, so a rejected first Identify
  falls back to the proven declaration.
- **Answer (`receiver::vehicle_status`).** On 0xA100 the tunnel records the phone's field
  selection and starts a sender thread that reads the status file every 2 s and sends 0xA101 when the
  body changes (and at least once a minute). A field goes out only if it was declared and, for fields
  3..=20, selected. The body is built by `iap2_core::vehicle_status::build_update`.
- **Not sent:** `ChargingParameter`/`ConsumptionParameter` (Apple's EV-routing model JSON, schema
  unknown), `MaxRangeElectric`, `ActiveConnector`, alerts. Whether Apple Maps needs the model JSON
  for EV routing is unknown, so the expected gain is that Maps treats the car as electric (charger
  search, low-battery prompts, battery level), not necessarily full EV route planning.

**Failure mode.** If the phone rejects the new Identify, only that session's iAP2 tunnel is lost:
now-playing, cluster turn-by-turn and call metadata. Video, audio and touch keep running. The box
logs the rejected param ids (`RX 0x1D03 decoded`). Turn the setting off and reconnect to undo it.

**Needs to reach the car.** Box firmware via `ocbm_install.sh --full` (the Identify and the answer
both live in `carplayd`), and an app build ≥ 223.

**Done means.** With the setting on, the box log shows `Identify carries the app-pushed vehicle
identity`, then `RX 0xA100 StartVehicleStatusUpdates` and `TX 0xA101 VehicleStatusUpdate`, with
no `0x1D03`; and now-playing and cluster turn-by-turn still work.

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

On the Equinox today, "Hey Siri" depends on the iPhone's own mic hearing the wake word over the car's
audio, and it misses (owner, 2026-10-26). Siri then listens through the car mic over MainAudio
(`host/gm_ccpa/docs/13_AUDIO_ROUTING.md` §4). The `/info` shape of `enhancedSiriInfo` and the
`setEnhancedSiriParams` handshake are still unrecovered. They come from `CarPlaySDK.framework` on
the Mac, so that recovery is the first step.

Split, when this is picked up:
- **The app** runs the always-on capture, echo and noise reduction, the keyword and voice-activity
  detectors, and the ring buffer. The head unit has the CPU and the mic; the CCPA has neither.
- **The box** advertises the feature, serves AuxIn/AuxOut, and sends
  `requestSiri{siriAction:4, siriTriggerTimestamp}`. The ring buffer reaches it over new OCBM frames.

**Done means.** With the toggle on, a wake word on the car mic starts Siri, the reply plays on
AuxOut, and media keeps running. With the toggle off, button Siri is unchanged.

## T12. GM app: pick which iPhone connects, and in what order (raised 2026-10-25)

**Why.** The Carlink app let the driver choose among paired phones (targeted
`AutoConnect_By_BtAddress`) and set who connects first. With two iPhones in the car, the GM app
takes whichever phone reaches the adapter first, and nothing lets the driver change that.

**State.** The adapter can do it already. `btd`'s loopback control port
(`crates/vendor/wireless/src/control.rs`, `127.0.0.1:9115`) answers `list`, `connect`
(one address, or none for first-to-connect), `policy` (`autoConnect`, `order`) and `forget`, and
reconnect walks `ordered_bonds()`. OCBM carries only `MGMT_GET_INFO` (bond list) and
`MGMT_FORGET_DEVICE`/`MGMT_FORGET_ALL`, so the app cannot reach `connect` or `policy`. The GM app's
Settings ▸ Phones (2026-10-25) lists bonds by name (`PhoneBook`, names joined from
`CT_PHONE_IDENT.deviceID`) and forgets one; that is step 1, app-only.

**Work.** New `CH_MGMT` verbs in `ccpa/ocbm-proto` mapped by `ocbmd` onto the `btd` control port.
Suggested: `MGMT_CONNECT_DEVICE [ascii MAC]` and `MGMT_SET_POLICY [utf8 JSON]`. Then a Connect
button per phone and a priority order (up/down) in Settings ▸ Phones, pushed as `policy`. This
needs an adapter firmware update (`tools/ocbm_install.sh`), not only a Play release. Decide whether
`connect` to one phone ends a live session of the other, and say so in the button's dialog.

**Done means.** With two paired iPhones in range, the one at the top of the list connects. Connect
on the other one moves CarPlay to it. Both survive an adapter reboot.

## T13. GM app: CarPlay turn-by-turn in the instrument cluster (raised 2026-10-26; WORKS on the car 2026-10-27)

**Why.** Apple Maps' next turn, distance and ETA belong in the Equinox cluster. The box already
delivers them: the Proven tier declares and subscribes iAP2 route guidance (`features.rs`), and
`metadata.rs` emits `routeGuidance` (`0x5201`) and `maneuver` (`0x5202`) JSON on `:9004`. Until
2026-10-26 the app dropped both (`ignoredKinds=60 lastIgnored=routeGuidance`,
`host/gm_ccpa/docs/14_LESSONS_LEARNED.md` §14.8).

**How (app-only, no adapter change).** The only third-party route into a GM cluster is the Car App
Library: a navigation `CarAppService` calls `NavigationManager.updateTrip()`. The Templates Host,
which holds the privileged `CAR_NAVIGATION_MANAGER`, forwards it to CarService, GM's VMSPlugin and
the cluster. On the VCU, VMSPlugin forwards only the maneuver TYPE enum and the cluster draws GM's
own glyph (lvalen91 `gminfo_resources` `projection/cluster_navigation.md`, firmware-verified on a
CT5). Ported from lvalen91/carlink (Unlicense): the maneuver table, the trip shape and the
primary-session logic. Code: `host/gm_ccpa/netprobe_app/app/src/main/java/wasidremin/gmccpa/nav/`
(`RouteGuidance` merge, `ClusterNav` toggle and bootstrap) and `app/src/carapp/` (Gradle builds only,
since `tools/build_apk.sh` has no AndroidX). It is off by default, under Settings ▸ Navigation.
The Templates Host binds only after the app launches a hidden `CarAppActivity`, so the first route
after the app starts flashes the screen for about a second.

**Verify on a drive.** Turn it on, start an Apple Maps route, then grep the capture for `nav:`.
Expect, in order:
1. `nav: route state - -> 1`;
2. `nav: bootstrap — launched CarAppActivity`;
3. `nav: session bound`;
4. `nav: navigationStarted`;
5. `nav: updateTrip #1 … -> TYPE n`.

Then check the cluster's turn view.

These lines mean something specific when they appear:
- `EXPECTED-MISSING session` means the host never bound. Read the `CarApp.*` lines for the reason,
  for example a validator rejection.
- `host stopped navigation` means another navigation app took the cluster.

The beat line now reads `nav=N`.

**Car result (2026-10-27, versionCode 220).** The owner reports the cluster shows the turns. In the
log, the toggle went on with a route already running (11:03:09). The bootstrap launched
`CarAppActivity`, the host bound a session 0.5 s later, and `navigationStarted` and `updateTrip #1`
followed. MainActivity was back in front at +1.1 s. `updateTrip` then ran at about 1 Hz through two
reroutes. One open check remains: whether the cluster clears when a route ends at the destination.

**Watch (2026-10-27 evening, versionCode 221): the cluster went without album art for a while.**
The owner reports that title and artist showed the whole time, with only the artwork missing.
The artwork came back when they switched playlists. The navigation toggle was not touched. The
app side is unchanged. Every track still publishes a 300×300 bitmap under both `ALBUM_ART` and
`ART` (`card: metadata "Mexico" art=300x300 …`), the same as before T13. The cluster's own process
(`com.gm.rhmi`) is not in an `OWN_PROCESS` capture. The earlier suspect was stale navigation state
left by the morning route, which the 11:40 update cut off before `navigationEnded`. It is unlikely
now, since a playlist change would not clear it. One lead, unproven: each track change publishes
twice. A first `art=none` publish goes out under the new `queueId`, then the art follows 20–80 ms
later. A consumer that latches the first publish for a queue item would miss the art until the next
track. If it recurs, note which track it starts on.

**Later (adapter firmware, `tools/ocbm_install.sh`).**
- Parse `0x5202` params 10/11 (junction and exit angles) for the `_WITH_ANGLE` roundabout types.
- Add `0x5200` selectors 3–5 so ExitInfo, time zone and EV fields arrive (doc 03 selector gap).
- `0x5204` lane guidance is Extended-tier only. Moving it into Proven changes a proven Identify and
  risks an unrecoverable `0x1D03`, so prove it at the Extended tier first.

**Done means.** On the Equinox, a CarPlay route shows its next turn with the right GM arrow,
distance counting down, and the ETA. The cluster clears when the route ends.

## T14. GM app: the steering-wheel voice button starts Siri (raised 2026-10-26; WORKS on the car 2026-10-27, versionCode 221)

**Why.** The owner keeps the iPhone off the car's own Bluetooth, because the car then takes the
calls. On AAOS the wheel's push-to-talk is routed by `CarInputService`:
- A short press goes to the selected digital assistant.
- A long press goes to projection receivers (system-only), then a Bluetooth phone, then the
  assistant (source.android.com automotive voice "Integration flows").

With no Bluetooth phone, both presses end at the assistant, which is Google Assistant.

**State.** The app declares itself an assistant candidate (`voice/SiriAssistant.kt`, ported from
`host/CarlinkAndroid/.../voice/`, emulator-proven there). It is inert until the driver picks it under
Settings ▸ Apps ▸ Default apps. Selected, a press reaches `SiriAssistantSession.onShow`, which sends
the same Siri press/release pair as the sidebar button. Settings ▸ Voice shows the state and a Choose
button when the car has a picker.

At every start the app logs
`voice: assistant role available=… heldByUs=… current=… picker=…`. That line answers whether GM
exposes the choice at all; the VCU has no adb to set it otherwise. Cost of selecting: Google
Assistant ("Hey Google", car voice commands) is off, and the default `SpeechRecognizer` is a stub
that refuses.

**Car result (2026-10-27, versionCode 220).**
- **The picker exists.** The log shows
  `picker=com.android.car.settings/…AssistantAndVoiceSettingsActivity`, `current=com.google.android.carassistant`.
- **Selection works.** At 11:05:21 the app logged "selected as the car's assistant".
- **The button works.** Four wheel presses each logged `wheel button (assistant session,
  flags=0x23) -> Siri press sent=true`. Each time, iOS opened the `speechRecognition` input stream
  and Siri spoke.
- **Siri heard nothing.** Holding the role makes Android silence the app's `VOICE_COMMUNICATION`
  mic: zeros, with no error. See `host/gm_ccpa/docs/13_AUDIO_ROUTING.md` §4.

The fix records with `VOICE_RECOGNITION` while the role is held.

**Car result (2026-10-27 evening, versionCode 221).** Siri heard the driver. The 21:28:50 Siri turn
logged `capture #2 heard: VOICE_RECOGNITION peak 5746 (-15 dBFS), 5/266 chunks all-zero,
silenced=false`, and placed the call that followed. The two calls also captured real speech
(`peak 9541 (-11 dBFS)` and `peak 12633 (-8 dBFS)`). The owner confirms the far end heard them on
the first call.

**Done means.** The `voice:` line shows a picker, GM CCPA can be selected, and a wheel press during
CarPlay logs `voice: wheel button … -> Siri press sent=true` and brings up Siri. Siri hears the
request: `capture #N heard: VOICE_RECOGNITION …` shows a real peak, with no all-zero `E`. All of it
was met on 2026-10-27. One question remains open: whether calls echo for the far end while the role
is held (`host/gm_ccpa/docs/13_AUDIO_ROUTING.md` §4). No echo was reported on that drive.

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
- **No contract yet.** Full-screen video playback (`enablesVideoPlayback`, `lunaConfig`) was
  parked here as needing Apple to grant `com.apple.developer.carplay-video`. That is the iPhone
  app's entitlement, not the car's (WWDC26 session 212, iOS 27). What the car needs is the
  receiver feature plus a parked signal. The app already has that signal in `VehicleStateWatcher`
  (`GEAR_SELECTION`). It stays parked: the sources that would show the wire contract (the CarPlay
  Simulator, `CarPlaySDK.framework`) are macOS-only and this fork has no Mac. The route left is an
  advertise-only build read through the iPhone's own logs (`idevicesyslog`).
- **Wrong panel.** Knob, d-pad, and touchpad descriptors are for other head units. The Equinox is
  a touchscreen.
- **No surface yet.** A second CarPlay video stream on the Equinox cluster waits until the VCU
  gives this app a cluster surface. Alt screen already renders in the Mac app.

