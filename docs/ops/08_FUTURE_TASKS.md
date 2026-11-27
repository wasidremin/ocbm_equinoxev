# Future tasks — planned work that is not a defect

> **STATUS:** CURRENT · single owner for planned, owner-directed work. Created 2026-09-04. Defects and
> verification gaps stay in `04_OPEN_ITEMS.md`; a task that lands is replaced here by a pointer.

Owner-directed work items that are neither an open defect nor a verification gap (those live in
[`04_OPEN_ITEMS.md`](04_OPEN_ITEMS.md)). One entry per task, dated when raised, with the current
state it starts from and what "done" means. When a task lands, replace its body with a one-line
pointer to the doc that now owns the result — do not leave a stale plan beside a shipped feature.

**What the owner would feel most (2026-11-14).** Asked what still annoys on an ordinary drive: "just
time to connect and the pop up." So **T25 (faster connect)** and **T15 (no Allow tap)** come before
any new feature. The app is for the owner alone; nothing here is planned for other owners or cars.

**Where each task stands (2026-11-13),** by what it takes to ship. "App only" is a Play update;
"needs adapter firmware" also needs new adapter firmware: from app 251 on, Settings ▸ Advanced ▸
Adapter firmware installs it (T20); before that, or for the boot path, a bench install
(`tools/ocbm_install.sh --full`).

*App only*

| Task | State |
|---|---|
| T16 Battery health | New, not started |
| T17 Arrival estimate by road type | New; waits for one more drive's `gap=` lines |
| T18 "Will I make it" at route start | New, not started |
| T19 Spotify hand-back | New, not started |
| T23 Apple's own turn card on the driver display | Built and tried on the car 2026-11-14: Apple's card replaces the map on the one feed. Map and card together (a second feed) declined by the owner; settings reworked so Apple's card is a Turn card choice and the bottom-right panel can hold another card beside it |
| T26 "Try this drive" card | New 2026-11-14, not started |
| T27 "Why didn't CarPlay connect?" | Built 2026-11-22, app only; not yet on the car |
| T28 After-drive summary | Built 2026-11-24, app only; emulator-checked, not yet on the car |
| T29 Charging card | New 2026-11-14, not started |
| T30 Weather-aware arrival estimate | New 2026-11-14, not started |
| T31 Mileage log | New 2026-11-14, not started; also needs the server |
| T32 Context docking | New 2026-11-14, not started |
| T33 Remove the Silverado hotspot mode | Done in code 2026-11-26 (about 7,000 lines, the app's native library with them); unit tests; not yet driven. The app's older documents still describe the mode |
| T36 Loudness by speed | Built 2026-11-26 (Screen & Sound ▸ Sound, default Gentle); unit tests only, not yet heard in the car |
| T37 Equalizer | Built 2026-11-26 (Screen & Sound ▸ Sound, default Flat); unit tests and the emulator's drawing, not yet heard in the car |
| T38 Turn alert on the driver display | New 2026-11-26, not started |
| T39 Settings per iPhone | Built 2026-11-26 (the sound, the media pane, the screen layout); unit tests; not yet in the car |

*Needs adapter firmware*

| Task | State |
|---|---|
| T6 Car GPS into Maps | Built 2026-11-13 on T7's pattern, off by default. Waits for the adapter reflash; not yet tried on a phone |
| T8 MainBuffered audio, phase B | Probed twice 2026-11-14: buffered audio is stream type 103; with the `103 / media` format the phone builds the stream and Apple Music asks for it (dictionary recorded, own 32-byte key `shk`). Helps Apple's player (Apple Music), not Spotify; parked. Next if resumed: the receiver's type-103 arm |
| T9 UI context | Open, not started, and **probably cannot do what it was raised for** (see T9) |
| T10 Focus transfer | Open, not started |
| T11 Enhanced Siri | Open, not started; needs T8 first |
| T12 Choose which iPhone connects | Built and on the car since 2026-11-11; not yet used |
| T15 No Allow tap | The USB bridge is built but **blocked** (the Equinox cannot install a sideloaded app). The adapter link over the car's hotspot worked in the car but took about 15 s longer than tapping Allow: **removed 2026-11-25** (owner), not yet published. The USB network trial (route A) stays, off |
| T20 Update the adapter from the app | Built and benched 2026-11-14 (update, confirm, manual and automatic rollback). Needs one last bench install for the boot hook; app updates after that |
| T21 The car's name in Apple Maps | Built and on the adapter 2026-11-13 (the name goes in `DisplayName` too); not yet seen in Maps |
| T22 CarPlay's status bar on the driver's side | Built and on the adapter; all three choices seen working on the bench 2026-11-14 (Driver is the left edge, as Auto). Ships in the next app build |
| T24 Lyrics with no connection | Built 2026-11-14: a package of timed lyrics (year-end top 100s since 1980, the owner's listening history, their Spotify library once signed in) built weekly on the home server and downloaded on Wi-Fi only. Not yet on the car |
| T25 Faster connect | **The owner's first annoyance, and the focus from 2026-11-25.** Measured on the car: a good start is about 18 s from the adapter's power-on to the picture (5 s to its USB device, 2–4 s the Allow tap, 10–11 s the adapter's own part; 12.3 s median). Step 2a (approved 2026-11-25): the radios at power-on and the supervisor's quicker look benched 2026-11-26 (paging 1.6 s after the app arrives, was 4.4 s), bench-installed on the car's adapter, not yet in the car; enigy's 2.7.1 page changes weighed against the car's logs and not ported; next 2b, the phone connected during the tap |
| T34 Video from the iPhone while parked | BUILT 2026-11-21, both halves (ported from enigy); benched in parts, not yet on the car |
| T35 Which iPhone first, by schedule | **Built 2026-11-22**, Play 268 (firmware ee0e29addd5c). The weekday-morning side works on the car (2026-11-23: the owner's phone first, page to ACL 0.6 to 0.8 s; a missed phone costs 3.2 s, not 5.1); the evening switch not yet seen |

*Done*

| Task | Where it went |
|---|---|
| T13 Cluster turn-by-turn | `host/gm_ccpa/docs/14_LESSONS_LEARNED.md` §16 |
| T14 Voice button starts Siri | `docs/host/01_ANDROID_AND_AAOS.md` |
| T7 EV status for Apple Maps | Done 2026-11-23 (charging stops planned on a long drive); the record stays in T7 below |

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

## T6. Car GPS into Maps — iAP2 LocationInformation (raised 2026-09-22; BUILT 2026-11-13; ON and accepted by the phone in the car, 2026-11-18 log)

**Why.** Maps is using the phone's own fix. The car has a better one: a roof antenna, and wheel
speed that keeps the position moving where satellites drop out. The phone asks for it with `0xFFFA`
StartLocationInformation and the accessory answers with `0xFFFB` LocationInformation, which carries
NMEA-0183 sentences (`GPGGA`, `GPRMC`, `GPHDT`) plus Apple's `PASCD` when the car has speed.

**Built (2026-11-13; adapter firmware and app, neither on the car yet).** It copies T7's shape.

- *Carrier.* A fix goes out about once a second, too often for the `CH_FILE` push T7 uses. It
  rides a new `CH_INPUT` sub-frame, `INPUT_LOCATION` (`0x09`): the sentences as text, one per line.
  `ocbmd` relays every `CH_INPUT` sub-frame unread, so only `carplayd` learned it.
- *Adapter.* `iapConfig.locationInformation.sentences` in `iap2_core::config`. On the AirPlay tunnel
  Identify only, `message.rs` adds param 22 with `0xFFFB` in param 6 and `0xFFFA`/`0xFFFC` in param
  7. A `0x1D03` retry naming 22 drops the feed and keeps vehicle status; one naming 6 or 7 drops
  both. `receiver::location` records what the phone asked for and forwards a sentence only when it
  is well formed, declared, selected, and past the phone's minimum interval for its type.
  `carplay_metadata::location` gained the encoder. `MGMT_INFO` says `"car_gps":true`.
- *App.* `gps/Nmea.kt` builds `GPGGA`, `GPRMC`, `GPHDT` (only while moving) and Apple's `PASCD`
  from the Android fix, `PERF_VEHICLE_SPEED` and `GEAR_SELECTION`. `gps/CarGps.kt` is the feeder and
  the setting, Settings ▸ CarPlay ▸ "Car GPS for Apple Maps", off by default. It declares and sends
  only when the adapter says `"car_gps":true` and the app holds the location permission.
- *`PASCD` layout,* taken as a fact from a public head-unit project (luka-dev/mib2q-carplay-rgi,
  which cites the accessory spec's table 41-4; GPL-3.0, no code used):
  `$PASCD,<sensor seconds>,C,<D|R|N|P|U>,0,<count>,<offset>,<m/s>*CS`.
- *Privacy.* A sentence is a coordinate. Neither side logs one. The adapter logs the phone's
  selection and counts (`TX 0xFFFB LocationInformation: 300 updates in the last 300 s`); the app
  logs counts.
- *Tests.* Adapter suites and the app's 512 unit tests pass; both sides pin the same block
  (`CarGpsTest.block`, `the_gm_apps_location_block_resolves`).

**Bench, 2026-11-13 (the real app on the AAOS emulator, the adapter on its USB, the owner's
iPhone).** The Identify with param 22 beside T7's 20/21 was accepted. The phone sent `0xFFFA`
selecting `GPGGA`, `GPRMC`, `GPHDT` (each at most every 250 ms) and `PASCD` (every 20 ms), and the
adapter answered `TX 0xFFFB LocationInformation: first update, 3 sentences, 195 B` from test
fixes (`adb emu geo fix`, the NMEA textbook position). No sentence reached a log. The first run
found a firmware defect: `carplayd`'s input reader refused any `CH_INPUT` sub-frame over 64 bytes
as a desync and dropped the connection, so each fix (~170 B) also knocked touch input out until
`ocbmd` reconnected (39 times in two minutes). The limit is now `ocbm_proto::INPUT_FRAME_MAX`
(1025 B). Whether Maps prefers the car's fix is still unknown; the emulator's fix does not move.

**Still to do, on the car.** Turn the setting on after T7 has been seen working, so a rejected
Identify can be pinned on one of them, and look for the `TX 0xFFFB` counts with no `0x1D03`.

**Known limits.** Android gives the app fixes while it is in front; if it withholds them when
another app covers CarPlay, the counts in the log will show the gap. The receiver's accuracy stands
in for a dilution figure, and the satellite count is the receiver's own or 8.

**Risks.** The Identify grows again, so a reject costs that session its metadata until the setting
is off (the existing fallback). Whether Maps uses the car's fix over wireless CarPlay is not known
until it is tried; the visible test is a tunnel or a parking structure. iOS may ask for sentences
the car cannot source (gyro, accelerometer); those stay undeclared.

**In the car (log of 2026-11-18, 22:11 UTC, adapter firmware 547d0db9c8e7).** The setting is on
(`car_gps:true`). The app declared `[gpgga, gprmc, gphdt, pascd]` and sent its first fix 0.6 s after
the receiver started; no `0x1D03` in the evening's sessions. The phone sent `0xFFFA`
StartLocationInformation (fix data and recommended-minimum selected), the adapter answered with
`0xFFFB` (first update 4 sentences, 223 B), and after 7 updates the phone sent `0xFFFC`
StopLocationInformation. The app kept feeding the adapter (276 fixes in 300 s). Not known: why the
phone stopped after 7 (it may stop when its own fix is good enough), and whether Maps used the car's
position. The owner reports everything but the hotspot join working.

**Done means.** With the setting on, the Identify is accepted with param 22, the phone sends
`0xFFFA`, the adapter's log counts `0xFFFB` updates with no coordinates in it, and Maps holds its
position through a place where the phone alone loses it.

## T7. EV vehicle status — range and charge (raised 2026-09-22; BUILT 2026-10-27; Identify and status ACCEPTED by the phone on the car 2026-10-28; why Maps shows nothing FOUND 2026-11-13; the fix BUILT the same day; EV routing WORKS on the car, owner 2026-11-18; DONE 2026-11-23: a long drive planned charging stops)

**Why.** The Equinox is electric. Maps will not treat it as one until Identify says so and a status
update follows.

**What was built (2026-10-27).** One app setting, Settings ▸ Navigation ▸ "EV battery for Apple
Maps", off by default.

- **App.** `ev/EvForCarPlay.kt` writes the `iapConfig:` block into the adapter subscribe document:
  `engineTypes: [electric]`, the car's connectors from `INFO_EV_CONNECTOR_TYPE` (see "Connector
  bug" below; J1772 + CCS1 when
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
- **Not sent:** `ChargingParameter`/`ConsumptionParameter` (0xA101 ids 26/27, the EV-routing
  models), `MaxRangeElectric`, `ActiveConnector`, alerts. The expected gain is that Maps treats the
  car as electric (charger search, low-battery prompts, battery level), not necessarily full EV
  route planning with charging stops.
- **Why the two models were not sent in October.** Apple publishes neither the model ids nor their
  keys. Its only public description is the iPhone-side twin of these fields,
  `INGetCarPowerLevelStatusIntentResponse.consumptionFormulaArguments` / `chargingFormulaArguments`,
  and a developer's request for the rest on Apple's forums (thread 772132, Jan 2025) went
  unanswered. Two public projects have since shown both; see "Cause found (2026-11-13)".
  `carplay_metadata::vehicle` parsed both fields then and has encoded them since 2026-11-13.

**Failure mode.** If the phone rejects the new Identify, only that session's iAP2 tunnel is lost:
now-playing, cluster turn-by-turn and call metadata. Video, audio and touch keep running. The box
logs the rejected param ids (`RX 0x1D03 decoded`). Turn the setting off and reconnect to undo it.

**Needs to reach the car.** Box firmware via `ocbm_install.sh --full` (the Identify and the answer
both live in `carplayd`), and app build 223 or later (`4.0+ev-maps`, on the internal track 2026-10-28).
The firmware is installed on the Equinox's adapter: reinstalled and finalized 2026-10-28 from
commit 78d89cb (carplayd md5 `a978deab…`).

**Car result (2026-10-28, versionCode 223, iOS 27.2).** The protocol half works. In all three
sessions after the setting went on, the box logged
`Identify carries the app-pushed vehicle identity: engines=[2] connectors=[(1, None), (2, None)]`
and then `IdentifyAccept` with no `0x1D03`. The phone sent `RX 0xA100 StartVehicleStatusUpdates`
(range, outside temperature, range warning), and the box answered `TX 0xA101` with 347 km and 18 °C.
Now-playing and artwork kept working.

One app-side push to `/tmp/vehicle_status` collided with the session-start file pulls
(`CH_FILE push: no OPEN ack`) and succeeded on its 15 s retry.

**Car result (2026-11-03, versionCode 228).** Cluster turn-by-turn ran the whole route with the
setting on. The phone again accepted the Identify and asked for range, outside temperature, range
warning and electric range. The box sent 121 `TX 0xA101` updates, each also carrying battery energy
(61,680 of 85,900 Wh), the displayed 71.8 % and `is_charging`. The owner sees nothing about the
battery in Apple Maps (reported 2026-11-04). The reason was found on 2026-11-13: Maps does not
count the car as electric until both models arrive (below).

**Earlier prior art (searched 2026-11-04), now explained.** DiPlay (CarPlay for BYD head units,
github.com/shihabal3amri/DiPlay, PR #38) sends what T7 sends, with no ids 26/27. Maps showed no
battery there either. Its only reaction was offering to find a charger when the range warning was
set (tested with the threshold at 30 %). DiPlay issue #1098 (closed 2026-10-23) replayed a real
Mustang Mach-E's params 20/21, captured from a SYNC 4 unit, under DiPlay's own MFi identity, and
sent 75 % every 30 s. Maps still showed no arrival percentage, and its vehicle list held only the
real car. The author suspected a check on the accessory's maker or certificate. Neither test sent
ids 26/27, which is enough to explain both results. On the iPhone side, apps implementing the
SiriKit car intents never got Maps to call them (Apple DTS called it a bug in 2020, FB8760513,
developer forums thread 653328). That is the bundle-id list described below.

**Connector bug (found 2026-11-04, fixed in 229).** The phone was told J1772 + CCS2.
CarPropertyManager returns `EvChargingConnectorType` numbers, where 5 is IEC_TYPE_1_CCS_DC (CCS1).
`EvForCarPlay.connectorNames` read them as the VHAL's `EvConnectorType`, where 5 is CCS2. The car
reports `[1, 5]`, J1772 + CCS1, in the probe of 2026-10-14. Maps' charger choices would have been
wrong because of it. It does not explain the missing battery reading. Confirmed on the car 2026-11-04 on 229: the Identify carried `connectors=[(0, None), (2, None)]`, which is CCS1 + J1772.

**Cause found (2026-11-13): Maps wants the two models, and their format is now public.**

*What Apple's code does.* Maps keeps cars in a private framework, VirtualGarage. Its strings and
method names are public in two symbol dumps (blacktop/ipsw-diffs, iOS 26.5 against 27.0, file
`VirtualGarage.md`; hack-different/apple-knowledge, `symbols/VirtualGarage.txt`). For a CarPlay
accessory it runs two checks.

1. `_isConnectedToElectricVehicle` fails with `chargingArguments is empty`,
   `consumptionArguments is empty` or `not connected to CarPlay accessory`, and
   `_currentStatePassesEVRoutingRequirements` then logs `not connected to electric vehicle`. Those
   two arguments are 0xA101 ids 26 and 27. T7 sends neither, so Maps never counted the Equinox as
   electric. The same holds for both DiPlay tests.
2. `VGExternalAccessoryModelFilter allowsVehicleWithModelId:firmwareId:year:model:` checks the
   `model_id` inside those arguments against a list Apple serves, with the keys `iap2_allowlist`
   (model ids) and `iap2_denylist` (`model_id`, `firmware_id`, year, model). A third key,
   `siri_allowlist` (`app_bundle_id`, `model_ids`), covers car makers' iPhone apps. Nothing in the
   names checks the car maker or the accessory's certificate. That is a reading of names, not a
   test. Apple's list itself was not read.

*The format.* Both fields are UTF-8 JSON. Shapes and ids are from
[isaacwisdom/egmp-carplay-ev-routing](https://github.com/isaacwisdom/egmp-carplay-ev-routing)
(`docs/CONTRACTS.md`), which took the dictionaries from
[augard/evmaps](https://github.com/augard/evmaps) (`KiaMaps/Core/Vehicle/VehicleParameters.swift`).

| 0xA101 id | `model_id` | `vehicle_parameters` |
|---|---|---|
| 26 ChargingParameter | 12582922 (0x00C0000A) | `vehicle_energy_axis_wh` (11 numbers, Wh, rising), `vehicle_charge_axis_w` (11 numbers, W, paired by position), `energy_w_per_h` (21 numbers), `efficiency_factor` (0.95) |
| 27 ConsumptionParameter | 12582921 (0x00C00009) | `vehicle_auxiliary_power_w`, `vehicle_consumption_values_wh_per_m` (10 numbers), `vehicle_altitude_gain_consumption_wh_per_m`, `vehicle_altitude_loss_consumption_wh_per_m` |

The ids name the formula, not the car: the Hyundai and Kia profiles share them. They are the pair
that works from a CarPlay accessory. The Porsche iPhone app's pair (12582912 and 12582916) belongs
to the `siri_allowlist` route. Not known to anyone public: what each of the ten consumption
positions stands for, and what `energy_w_per_h` is (it looks like the energy at each 5 % of charge).

*Proof that it works without Apple's blessing.* egmp-carplay-ev-routing patches stock Hyundai
Ioniq 5 and Kia EV6 head units to send exactly this. Apple Maps then lists the car under Vehicles
and plans routes with charging stops and a battery figure at each stop (their screenshot: a 383
mile route, two stops, 70 % at the start and 13 % on arrival). Several owners have it running
(first release 2026-11-03). What they learned:

- The iPhone must forget the head unit and pair again before Maps offers the car.
- Maps recalculated routes when status arrived every 5 s with a changing auxiliary power. They now
  send every 10 s and change auxiliary power at most once a minute, rounded to 25 W.
- One owner reports the battery level in Maps stopped following the car after 15 minutes (their
  issue #1, open).

Ford's own instructions agree that no car maker's app is involved on this route: connect CarPlay,
set a destination, accept the pop-up, tap Connect.

**What T7 sends today against what is needed.**

| Piece | Today | Needed | Side |
|---|---|---|---|
| 0xA101 ids 26 and 27 | Not sent; the encoder lacks them | The two JSON documents above | Adapter and app |
| 0xA101 id 21 `MinBatteryCharge` | Encoder has it; no status-file key | 0 | Adapter and app |
| 0xA101 id 30 `MaxRangeElectric` | Encoder has it; `maxRangeInfo` not declared | Declared and sent | App (and a status-file key on the adapter) |
| Identify param 20 sub 8 `MapsDisplayName` | Not emitted | The car's name (T21 wants the same sub) | Adapter |
| Identify param 20 connector power (subs 12 to 20) | Not sent | CCS1 and J1772 power; the config reader already takes `powerWatts` | App |
| How often status goes out | When the file changes, checked every 2 s | No more often than every 10 s | Adapter |
| The phone | Paired since October | Forgotten and paired again, then Apple Maps ▸ profile ▸ Vehicles | Owner |

**The Equinox's numbers.** Battery size and level already go out from the car's own readings.

- *Consumption.* Start from the published ten values (172 to 258 Wh/km). They began in Porsche's
  app, and the Hyundai project kept them for an Ioniq 5 and reports 1 to 2 % accuracy. This car's
  own fit (`telemetry/DriveOutlook`: 95 + 0.138 v² Wh/km above 18 °C, v in m/s) gives 181 Wh/km at
  90 km/h, where the list has 183, and 275 at 130 km/h, where the list tops out at 258. So the upper
  entries need raising. Since nobody knows what each position means, adjust by comparing Maps'
  predictions with what happened.
- *Climbing.* 2,400 kg is 6.5 Wh per metre climbed at the wheels. Gain about 7.0; loss about 4.6
  with the app's 0.7 regeneration factor.
- *Auxiliary power.* A base of about 500 W plus heating from the outside temperature (the fit's
  3.55 Wh/km per °C below 18 °C), changed at most once a minute.
- *Charging curve.* Eleven points of energy against power, 150 kW at the peak. The drive log keeps
  one power figure per charge, so the first curve comes from published charging tests. A
  per-level record would let the owner's own fast charges correct it.

**Built (2026-11-13; adapter firmware and app, neither on the car yet).**

- *Adapter.* `carplay_metadata::vehicle::encode_vehicle_status_update` encodes ids 26 and 27.
  `iap2_core::vehicle_status` reads four more keys from `/tmp/vehicle_status` (`min_wh`,
  `max_range_km`, `charging_json`, `consumption_json`); 21, 26 and 27 ride `electricChargeInfo` and
  30 rides `maxRangeInfo`. The box relays the JSON unparsed and refuses only a string it could not
  frame. `iap2_core::config` takes `iapConfig.vehicleInfo.mapsDisplayName` and `message.rs` emits it
  as param 20 sub 8 (subs 1 and 6 stay the box's name; T21). `receiver::vehicle_status` never sends
  two updates closer than 10 s and logs the models by length. `ocbmd` says `"ev_models":true` in
  `MGMT_INFO`.
- *App.* `ev/EvRoutingModel.kt` builds the two JSON documents from the numbers above (the charging
  curve is fractions of the car's reported capacity, in 500 Wh steps). `ev/EvForCarPlay.kt` adds the
  four keys, `powerWatts` for each connector (150 kW DC, 11.5 kW AC), `mapsDisplayName` (the car's
  own make and model) and `maxRangeInfo`. Capacity, auxiliary power and full-charge range each hold
  for a minute once sent.
- *Nothing changes until the reflash.* At each connect `OcbmProbe.evModelsOnAdapter` asks the
  adapter's info for `"ev_models":true`. Without it the app sends the Identify block and status
  file it has sent since 223, byte for byte, so the app can ship first. The setting's text says
  which case applies.
- *Tests.* Adapter suites pass (`tools/run_tests.sh`); the app's 499 unit tests pass. Both sides pin
  the same block (`EvForCarPlayTest.routingBlock`, `the_gm_apps_ev_routing_block_resolves`).

**Tesla (NACS) adapter (2026-11-13, app only).** The car reports only its own ports (J1772 and
CCS1), so Maps never offered Tesla stations although the owner charges at Superchargers with an
adapter. Settings ▸ CarPlay ▸ Apple Maps ▸ "Tesla (NACS) adapter": None, Superchargers (`nacsdc`,
150 kW) or All Tesla (adds `nacsac`, 11.5 kW). The connectors join the car's own in
`vehicleInfo.chargingConnectors`, which every adapter firmware since 223 already reads
(`the_gm_apps_nacs_connectors_resolve`). The phone accepted the Identify with J1772 and NACS DC on
the bench, 2026-11-14. Maps may need the car removed and added again to see them.

**Bench, 2026-11-13 (the real app on the AAOS emulator, the adapter on its USB, the owner's
iPhone).** Three connects, each `Identify carries the app-pushed vehicle identity … status_caps=
Some([3, 4, 6, 11, 21, 30])` then `IdentifyAccept`, no `0x1D03`. The phone sent `0xA100` and the
adapter answered `TX 0xA101 VehicleStatusUpdate (780 B)` carrying both models. The emulator's car
calls itself "Toy Vehicle Speedy Model", which is the name Maps would list. Whether Maps offers it
under Vehicles is the owner's check.

**Still to do, on the car.**

1. The adapter was updated on the bench 2026-11-13 (`ocbm-host push`) and the app published as 250.
   On the bench Apple Maps ▸ Vehicles listed the car, as "CarLink": Maps takes the name from
   param 20 sub 6 `DisplayName`, which is still the box's name (T21).
2. Check the log for `EV identity for CarPlay: … with the EV-routing models` and
   `TX 0xA101 … models: charging N B, consumption N B`, with no `0x1D03`.
3. On the iPhone: forget the adapter under Bluetooth and under CarPlay, pair again, then Apple
   Maps ▸ your picture ▸ Vehicles and add the car.
4. Plan a route longer than the remaining range and compare Maps' battery-on-arrival with the app's
   own. Then adjust the ten consumption values and the curve; that is an app update only.

**Risks.**

- It is proven only on head units that carry their car maker's own identity. This adapter
  identifies as itself. Nothing read so far says Maps cares; only step 3 settles it.
- Apple can change its list at any time, including denying a firmware id, a model or a year.
- A wrong model misleads Maps' charging plan. The app's own arrival estimate on the turn card stays
  as the cross-check.
- If the phone rejects the changed Identify, the existing fallback applies (see "Failure mode").

**If step 3 fails, read the iPhone's own log.** Install Apple's "CarPlay" and "iAP" logging
profiles from Safari (developer.apple.com/bug-reporting/profiles-and-logs), then capture with
`idevicesyslog` on a Linux laptop over USB while wireless CarPlay runs, parked. The lines that say
why are VirtualGarage's: `_isConnectedToElectricVehicle`, `_currentStatePassesEVRoutingRequirements`,
`_onboardVehicle`. A sysdiagnose read with Mandiant's `macos-UnifiedLogs` parser
(github.com/mandiant/macos-UnifiedLogs) is the route with no laptop; it may have gaps on iOS 27.

**Ruled out: the iPhone-app route.** augard/evmaps gets Maps to call its own app by building it
under an app id inside `com.porsche.*`, which is on Apple's `siri_allowlist`. That needs Xcode on a
Mac and a paid developer account, and it borrows Porsche's identifier. This fork has no Mac.

**Licences.** egmp-carplay-ev-routing is GPL-3.0-or-later and augard/evmaps states no licence. Use
their facts and ideas only, never their code, and credit both in `NOTICE.md` when this is built.

**On the car (owner, 2026-11-18): EV routing works as expected** in Apple Maps, with the adapter
firmware the app has delivered since Play 256 (the 2026-11-13 fix included). This is the owner's
report from driving; no log was read for it, so the `TX 0xA101` ids 26/27 line below is still the
check to run if it ever regresses.

**Done (owner, 2026-11-23).** On a long drive on 2026-11-22 Apple Maps planned the route with charging
stops. Reported from driving; that drive's log was not uploaded. The `TX 0xA101` check below stays the test
if it ever regresses.

**Done means.** Apple Maps ▸ Vehicles lists the car. A route longer than the remaining range shows
charging stops and the battery on arrival. During a drive the percentage in Maps follows the
car's. The box log shows `TX 0xA101` carrying ids 26 and 27 with no `0x1D03`, and now-playing and
cluster turn-by-turn still work.

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

**Prepared (2026-11-13).** The step that removes the main unknown is to ask the phone:
- *Adapter.* `receiver::session` logs every SETUP stream dictionary of a type it does not serve,
  nested dictionaries and arrays included, data values by length only
  (`describe_plist`, so a stream key is never printed). Line: `[session] >>> SETUP stream dict
  (type N): {…}`. On the adapter since 2026-11-13.
- *App.* A bench switch with no UI: `shared_prefs/bench.xml` boolean `main_buffered_probe` makes the
  subscribe document say `enablesMainBufferedAudio: true` and logs a warning. Media may go silent
  for that session, because no buffered stream is served.
- *The probe.* Phone at the bench, emulator with the adapter (`docs/ops/00_BUILD_AND_DEPLOY.md`
  item 9), switch on, one connect, then read the `SETUP negotiated … features=` line (whether
  `mainBuffered` survived) and the stream dictionary. If the phone does not ask for the stream, the
  iPhone's own log says why (`idevicesyslog` on this server with the phone on USB; CLAUDE.md).

**Probe result (2026-11-14, the owner's iPhone, iOS 27.2.1).** With buffered audio advertised the
phone KEPT the feature: `SETUP negotiated … features=["hevc", "mainBuffered", "iAPChannel",
"sessionManagement"]` (it had negotiated it away in the 2026-08 captures). It then set up the screen
and the iAP channel, took `mainAudio` and played (`modesChanged`, now-playing state 3), and never
sent a SETUP for any audio stream: no buffered stream and no 102 either. Media was silent until the
switch went off, when the next session set up 102 and played at once. So the phone wants something
our `/info` does not give it before it opens the buffered stream; `mainBufferedInfo` is still
presence-only.

**What the phone said (2026-11-14, `idevicesyslog` with the iPhone on this server's USB, the tools
unpacked from `libimobiledevice-utils` without installing).** airplayd logged `CarPlay bufferedAudio:
enabled`, then `APCarPlayAudioFormatsCopyFormatsForAudioType:572: false condition` and **`Error in
creating formats for type 103 / media`** (kParamErr up through `APCarPlayAudioFormatsCreate`), and
set up no audio stream. So MainBuffered is **stream type 103**, and the phone needs an `audioFormats`
entry for `103 / media`. `info.rs` now adds one, with the `102 / media` formats, whenever the
mainBuffered lever is on (`buffered_audio_adds_a_type_103_media_entry`); without the lever the
bytes are unchanged. The same log also said `viewAreas … found but feature not
declared in setup response` for a session with the split pane off (the main view area is still
described); harmless today.

**Second probe (2026-11-14, 692638d on the adapter through the app's own update).** With the
`103 / media` entry the phone builds the stream: airplayd logs `APEndpointStreamBufferedAudio
created … streamType=BufferedAudio subType=AudioBuffered supportedAudioFormats=[AAC-LC/48000/2]` and
audiomxd picks CarPlay as the buffered endpoint. Which app asks matters: the earlier plays, with Spotify the active player, stayed on 102.
Apple Music (`clientID="com.apple.Music"`) switched "from Local to Buffered" and sent 38 SETUPs for
type 103 in about a minute. The receiver answered each with `streams=[]` (`SETUP phase2 stream type
103 NOT IMPLEMENTED — omitted`), so on the phone `apsession_setupStreamsCreatingResponse` failed,
`audioStream_setupAudioStream` returned -6727 kNotFoundErr, and Music logged `FailedToPlayToEnd`
and dropped the item. **So with the probe on, Apple Music cannot play**; the switch is back off.
The stream request, as the adapter logged it (the key by length):

    {type=103, audioType="media", audioMode="default", isMedia=true, clientID="com.apple.Music",
     audioFormat=8388608, audioFormatIndex=23, ct=4, spf=1024, packetFormatAPAP=false,
     supportsDynamicStreamID=true, streamConnectionID=<u64>, shk=<data 32 B>}

`audioFormat` 0x800000 is AAC-LC 48 kHz stereo, the same as 102; the phone transcodes (it logged a
44.1 kHz source and `transportASBD = AAC, 48000 Hz, Stereo, 1024 samples/packet`). Unlike 102 it
carries its own 32-byte key `shk` and no `controlPort`, so the stream is not keyed from the session
secret the way `derive_stream_keys(&shared, scid)` keys 102. The phase-B work is therefore a type-103
arm in `setup_phase2`: answer with the stream's `type`, a data port (and whatever else the phone's
`apsession_setupStreamsCreatingResponse` checks), receive the buffered AAC over TCP decrypted with
`shk`, and hand the frames to the existing media path. What the response must hold and the packet
framing are the open questions; the phone's airplayd errors name the check that fails, so each
attempt can be read off its log as this one was.

**Which apps it helps (2026-11-14, same log).** Buffered audio is offered by Apple's system player
(mediaplaybackd), which can send ahead because it owns the queue. All three `Switching from Local to
Buffered` lines came from it, for Apple Music. Spotify renders its own audio: the phone recorded an
empty buffered format for it and it never tried the route, staying on 102. So the gain (music that
rides out a short Wi-Fi loss) reaches Apple Music and, untested, other apps on Apple's player
(Podcasts, Books, many podcast and audiobook apps), not Spotify. Worth building only if the owner
listens through those apps; parked on that basis.

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

**Probably the wrong feature for the "why" (checked 2026-11-12).** Every piece of UI context in
the SDK findings runs from the car to the iPhone: the two `/info` URL lists are things the car
advertises, and `changeUIContext` is an outbound command (`docs/carplay/03_SDK_GROUND_TRUTH.md` §9,
"Outbound (accessory→iPhone)"). None of the inbound commands carries the app the iPhone is showing,
so building this would not tell the car Maps from Now Playing, and the second half of "Done means"
has no wire to arrive on. Our reading, not verified against the SDK: it lets the car ask CarPlay to
open or restore an app context, for example the app the driver last had after a reconnect, which
the app can already do by URL on an adapter reporting `CAP_UI_URL`. The need behind this task is met
another way: the app reads the CarPlay screen for Maps' turn card (`av/MapsWatch`,
`host/gm_ccpa/docs/14_LESSONS_LEARNED.md` §16), and iOS's RouteGuidanceVisibleInApp flag does not
track Maps in front, so it cannot replace that. Keep T9 only if restoring the last CarPlay app on
reconnect is wanted; it needs adapter work either way (the receiver ignores `enablesUIContext`,
`vehicle_config.rs` `EMITTED_BUT_UNREAD`).

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

## T12. GM app: pick which iPhone connects, and in what order (raised 2026-10-25; BUILT 2026-11-05; on the car since 2026-11-11, not yet used)

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

**Built 2026-11-05 (adapter firmware and app, neither on the car yet).**
- *Order.* `MGMT_SET_ORDER [MACs joined by ","]`. `ocbmd` checks each address and forwards
  `{"cmd":"policy","order":[…]}` to btd's control port. It leaves out `autoConnect`, so btd keeps its
  own setting. It reads the answer back, bounded at 250 ms to connect and 500 ms to read, and acks
  0 only on `"ok":true`. btd stores the policy in `/etc/carplay/projection_policy.json` (flash), which
  survives a reboot. `MGMT_INFO` now lists `devices` in the order btd tries them (`ordered_bonds`,
  the same rule as `control::order_bonds`) and says `"phone_order":true`.
- *Connect.* btd cannot end one session (no cancellation hook in `bt_driver::run`, and the BT iAP2
  session stays up for the whole drive), so `MGMT_CONNECT_DEVICE [MAC]` reuses the proven restart
  path instead. `ocbmd` refuses an address that is not bonded. It writes the address to
  `/tmp/bt_connect_next` and raises `/tmp/wireless_restart`, so the supervisor runs
  `wireless_down`, waits 4 s and runs `wireless_up`. The new btd takes the file at start
  (`control::take_connect_next`, ignored after 60 s) and queues it as an explicit request. The
  reconnect loop then tries that phone alone in its first round. If the phone is out of reach, the
  usual order resumes about 10 s later, so the previous phone comes back.
- *Answer to "does Connect end the other session":* yes. The dialog says so, names the phone that
  drops and says it reconnects if the new one is out of range. Connect is one-shot. The list order
  decides the next drive.
- *App.* Settings ▸ Phones shows ↑/↓ and Connect only when `MGMT_INFO` has `phone_order`. On older
  firmware it shows a note that choosing needs the adapter update. The top phone reads "connects
  first". Connect goes through the supervisor's restart guard, as Forget does.
- *Open.* A phone that dials the box itself (the accept path) can still win the race against the
  queued one. On the car the box has always connected out (`[reconnect] RFCOMM connected OUT`), so
  this is expected to be rare. Only one phone has been paired so far, so neither half is car-tested.
  Reaching the car needs `tools/ocbm_install.sh --full` (ocbmd and btd); the app side is in 232
  (published 2026-11-06).

**On the car (checked 2026-11-12).** The adapter has the firmware: from 2026-11-11 18:32 UTC every
`MGMT_INFO` in the car's uploads carries `"phone_order":true`, two iPhones are bonded, and the app
logs `phones: 2 paired, … choose=true`, so Settings ▸ CarPlay ▸ Phones shows ↑/↓ and Connect. No
upload yet has an order change or a Connect, so "Done means" is still unchecked: reorder the two
phones, then try Connect on the one not connected, with both in the car.

**enigy/ocbm's two-phone fix (issue #3 on wasidremin/ocbm_equinoxev; assessed 2026-11-20, not
ported).**
- *What enigy's fix repairs.* The issue asks for enigy/ocbm PR #4's fix (`2769b69`, `8787548`,
  `711e584`: "a change of phone in about 5 s, instead of 40–90 s"). That fix repairs enigy's own
  **phone gate**: `carplayd` serves only the phone `btd` names in `/tmp/carplay_expect_phone`
  (`b31b7bc`, `b2c92d9`, `d73a098`, 1–2 Nov). The name was written only at the 0x5703, so a phone
  already on the access point before its 0x5703 was refused, and waited 40–90 s.
- *What this fork has.* It never had the gate. Its `carplayd` dials every `_carplay-ctrl` it
  resolves (`discovery.rs`), so it cannot have that delay.
- *What it is exposed to instead.* The older problem the gate was built for (enigy's two-iPhone
  bench, 2026-11-01): the phone a switch dropped stays on the access point, the new `carplayd`
  dials it first, and the two phones take the session from each other.
- *Whether that happens here.* Our Connect restarts the whole wireless stack, access point
  included, so the dropped phone must rejoin first. Unknown: Connect has never been used in the car.
- *What to do.*
  1. First the test above with both phones: does Connect land on the chosen phone and stay there?
  2. Only if the dropped phone takes the session back, port the gate in its smallest form for this
     design. `carplayd` starts at the 0x5703 here, after `btd` knows the phone, so the name can be
     written before `carplayd` exists. The early-naming and re-query machinery the issue describes
     is then not needed. The gate's parts:
     - `btd` writes the phone's address before spawning `carplayd`;
     - discovery dials only a `_carplay-ctrl` whose TXT `id` is that address, and dials a record
       without an `id` as before;
     - a SETUP whose `deviceID` is another phone is refused.
- *Why not port it now.* It cannot be checked without two iPhones at the bench. A gate that
  misreads a phone's identity would keep that phone off CarPlay, which is the one-phone drive's
  failure. enigy/ocbm's code (Unlicense) is the reference when it is built.

## T13. GM app: CarPlay turn-by-turn in the instrument cluster — DONE (works on the car since 2026-10-27)

Moved to `host/gm_ccpa/docs/14_LESSONS_LEARNED.md` §16: the Car App Library route, the album art in
the cluster, the car results and the cases of missing art. The driver-display route that followed
(CarPlay's own cluster map with the app's cards, Play 236 onward) is `04_OPEN_ITEMS.md` "CarPlay map
on the driver display". Still open from this task: `0x5200` selectors 4–5, so the time zone and EV
fields of route guidance arrive (adapter firmware; `docs/carplay/03_SDK_GROUND_TRUTH.md` selector gap).

## T14. GM app: the steering-wheel voice button starts Siri — DONE (works on the car since 2026-10-27, versionCode 221)

Moved to `docs/host/01_ANDROID_AND_AAOS.md` "The steering-wheel voice button starts Siri". Still
open from it, tracked in `04_OPEN_ITEMS.md`: whether calls echo for the far end while the app holds
the assistant role, and the voice button ending a live call (263). Its answering a ringing call (233)
was seen on the car on 2026-11-21.

## T15. GM app: no Allow tap for the adapter (raised 2026-11-06; the sideloaded USB bridge is BUILT and BLOCKED on the stock Equinox; the link over the car's hotspot built 2026-11-21 and REMOVED 2026-11-25)

**Priority (2026-11-14).** One of the two things the owner still finds annoying on every drive (the
other is the time to connect, T25).

**The ask.** The driver taps Allow for the adapter on every drive. The owner found the XDA post
(enigy's Carlink thread, page 28) saying a sideloaded bridge removes it for a Play app.

**What was built (2026-11-06).** `host/gm_ccpa/netprobe_app/bridge`, adapted from
[enigy/carlink](https://github.com/enigy/carlink) `bridge/`: an app named `android.car.usb.handler`
that GM's fixed USB handler config grants every attached device with no dialog. It claims the
adapter's OCBM interface and relays it to GM CCPA over a binder service and a socket. GM CCPA uses it
whenever it holds the grant and the app does not, and otherwise claims the adapter itself as before.
Settings ▸ Advanced ▸ USB bridge shows its state and opens the hidden Files app. The app side is in
232 (published 2026-11-06). Design, evidence and limits: `docs/host/01_ANDROID_AND_AAOS.md` "The USB
bridge (2026-11-06)".

**Done when.** On the Equinox, the bridge installs from a USB drive through Files, and a drive start
logs `claimed … through the USB bridge … no dialog` with no Allow tap.

**Answered on the car (2026-11-12).** Open item 1 below is no. Since 232 every connect logs the
car's unknown-sources screen, and on the Equinox it is the same "not supported" stub as on the
emulator: `usb bridge: not installed, unknown-sources screen
com.android.car.frameworkpackagestubs/…Stubs$ManageExternalSourcesActivityStub` (256 times in the
uploads to 11-12). Files cannot install the bridge on this car's software, so the task stops here
unless GM ships a real install screen; the app keeps checking and Settings ▸ Advanced ▸
USB bridge would show the change. Items 2 and 3 cannot be reached until then.

**Open.**

1. Whether the Equinox lets Files install an app. The AAOS 14 emulator does not: its
   `ACTION_MANAGE_UNKNOWN_APP_SOURCES` answer is a "not supported" stub. Other Equinox EV owners
   report APK installs refused as "unknown sources blocked" on current software, and no working
   route since GM's 2026 Play Store update (searched 2026-11-06; sources in
   `docs/host/01_ANDROID_AND_AAOS.md` "The USB bridge"). Expect no. The USB bridge row says which
   screen the car has before anyone carries a USB drive out.
2. Whether the adapter attaches while user 12 is in front. An attach under another user gets no grant
   for the bridge, and the app falls back to its own dialog.
3. The relay has not carried a session. The emulator has no adapter.

### Route A: a USB network link beside OCBM (raised, built and benched 2026-11-15)

**The idea.** Android's own system takes a USB network device the way it takes a USB drive, so an
app that reaches the adapter over a network link needs no USB permission and no Allow tap. The
adapter's gadget driver can present a network function (`f_ncm`) beside OCBM.

**Built (off by default).** `ccpa/rootfs/script/ocbm_boot.sh`: `usb_net=eth-once` (or `usb-once`) in
`/script/ocbm.conf` presents `accessory,ncm,mass_storage` for one start; the key is written back to
`usb_net=0` before anything is presented, so a car that refuses the device gets the usual one at the
next power-on. The adapter's end is `ncm0` 192.168.50.2 with a lease pool, no router and no DNS.
`ocbmd` says so in `MGMT_INFO` (`"usb_net":{…}`, `ocbm_proto::usb_net_json`) and answers
`OCBM-NET 1` on TCP 5010 to that link only (`usb_net_peer_ok`). The app (`ocbm/UsbNet.kt`) logs, on
every connect where the adapter presents the link, the interfaces Android shows it and whether the
test port answers on each; Settings ▸ Advanced ▸ USB network trial (bench verb `usb_net_trial`)
arms one start and restarts the adapter.

**Measured on the bench.**

- The three-function device enumerates on Linux with OCBM still interface 0 and still working; the
  link carries 106 Mbps adapter to host (`nc` from `/dev/zero`), 2 ms ping. OCBM over the bulk pipe
  measured 339 Mbps.
- **Stock Android leaves the link down.** On the AAOS 14 emulator with the adapter passed through,
  the kernel creates `usb0` (its `cdc_ncm` is built in) and `EthernetTracker` does not track it:
  it takes interfaces matching `eth\d` only. The app cannot raise an interface itself, so the probe
  logged `adapter not reached` on every interface.
- **The name cannot be changed from the adapter.** Linux names the host's end `usb<N>` when the
  device's address is locally administered and `eth<N>` otherwise. This adapter's gadget driver
  (`g_android_accessory`) writes its own locally-administered address into `f_ncm/ethaddr` at
  every bind: the module's `host_addr` parameter is accepted and ignored, and an address written
  to `ethaddr` between unbind and rebind is replaced at the rebind (read back each step,
  2026-11-15). The driver is a kernel module file on the adapter, not the boot stack, but changing
  it is not proposed.

**What is left to learn.** Whether GM's build is like stock: its Ethernet service could be
configured to take `usb\d` (`config_ethernet_iface_regex`), which nothing here shows either way. One
car start with the trial answers it at no risk: the log line `usb-net: REACHED the adapter` or
`adapter not reached`, with the interfaces the car showed. Expect not reached.

**Done means (this route).** The car's log shows `REACHED` on a trial start; then the network
transport is built behind `Transport.kt` with USB kept as the fallback.

### Second route: the adapter link over the car's hotspot (2026-11-13 to 2026-11-25): removed

**What it was.** From metheos's idea on the XDA thread and mammut3's enigy/ocbm and enigy/carlink PR #2:
the adapter joined the car's hotspot as a client beside its own access point (`wpa_supplicant` shipped
in the firmware from 2026-11-15, a keeper holding the join from boot from 2026-11-21), and the app
reached `ocbmd` over TCP on the hotspot (OCBW v1, a pre-shared key given once over USB), so it never
claimed the USB device and the driver never tapped Allow. USB stayed the fallback. Settings ▸ Advanced ▸
Join the car's hotspot switched it. The code is in git history at `1b25f52`.

**What the car showed.**
- 2026-11-17/18 (Play 259, 260): every join failed with the wext client; nl80211 (Play 261) joined.
- 2026-11-22 (265 to 267): it linked when the adapter was already on the hotspot and CarPlay ran over
  it, but a cold start's link came no sooner than a quick tap: the adapter was on the hotspot about 22 s
  after its kernel started. The owner went back to USB with the tap.
- 2026-11-25 (269, on again for a day): the 8:13 AM and 6:18 PM starts linked with no tap, first picture
  32 s and 29 s after the adapter's USB device, against 13 s over USB with the tap that morning. At
  5:00 PM the hotspot was not up for the app's 32 s look, and the adapter, configured to join, held its
  access point and so Bluetooth back 15 s; picture 28 s after the screen came on. At 6:55 PM the adapter
  looked on the channel it remembered (153) while the hotspot was back on 149; with a restart tap
  landing on a fresh USB link, the adapter then refused Wi-Fi (USB had won that start) and started no
  access point until it restarted: 2 min 27 s to the picture. The hotspot's channel and band moves are in
  `docs/wireless/01_BT_AND_RADIO.md` §6e.

**Removed 2026-11-25** (owner: "Let's just remove the feature. And let's focus on the startup time").
Even working, the link only traded the tap for the adapter's boot and join, about 15 s more than tapping
Allow. Removed: the app's search and transport (`WifiLink`, `OcbwLink`, `WifiBulkTransport`,
`StaUplink`) and the setting; `ocbmd`'s listener and advert (`wifi.rs`, `mdns.rs`), the `ocbm-link`
crate and `ocbm-host`'s `OCBM_TCP`; `radio_hal.sh`'s client and keeper, the supervisor's wait for the
join and `radio_ap_up.sh`'s client channel; `wpa_supplicant` from the build and the firmware.
`MGMT_WIFI_LINK` (`0x0E`) is retired, not reused (`docs/carplay/01_OCBM_PROTOCOL.md`). Kept for
adapters on older firmware: the app turns their join off and clears their key at each connect
(`ocbm/RetiredWifiLink.kt`), and the next adapter update deletes those files at start
(`session_supervisor.sh`; the passphrase was in one of them). The adapter keeps the `wpa_supplicant`
binary, which no script deletes (other units carry the vendor's own there); on the car's adapter it was
removed by hand on the bench, 2026-11-26.

*Verified:* unit tests (624, among them `RetiredWifiLinkTest`), every adapter suite
(`tools/run_tests.sh`, `radio_hal_test.sh` pinning the removal), the adapter build. *Bench, 2026-11-26* (the emulator
with the car's adapter and the owner's iPhone): on the old firmware the first connect turned the join off
and cleared the key, the next connect undid nothing, CarPlay came up; the app's update (`ocbmd` and three
scripts) was confirmed in its trial and the supervisor removed the files at start. *Not yet:* the car.

## App only: T16–T19 (raised 2026-11-12)

These need a Play update and nothing on the adapter.

## T16. Battery health (raised 2026-11-12)

**Why.** The owner would like to see how the pack ages, which is what Recurrent sells. The drive log
already has every charge.

**State.** The car's own figures can show it. Over 2026-10-13 to 11-12 the car reported its
capacity (`INFO_EV_BATTERY_CAPACITY`) anywhere from 83.9 to 86.5 kWh, so it is a live estimate from
the battery management system, not a constant; it probably shifts with temperature. The battery
level (`EV_BATTERY_LEVEL`) moves in 10 Wh steps (1,497 of the steps sampled), so energy in and out
is measured finely, not as whole percentages. Each charge record carries the capacity the car
reported (`capWh`).

**Work.** In EV Insights: the car's capacity reading over time, adjusted for the outside
temperature at the time (a fit like `TripStats.tempFit`), against the pack's nominal 85 kWh; and a
cross-check from charges, the energy added over the change in percentage. Months of data are needed
before a trend means anything; the first view should say how many readings it rests on.

**Done means.** Insights shows usable capacity as a figure and a trend line, with the number of
readings and the temperature correction stated, and the figure moves with the car's own reading.

## T17. Arrival estimate by road type (raised 2026-11-12)

**Why.** The battery-on-arrival estimate (`telemetry/DriveOutlook`) takes one average speed for the
rest of the route, the phone's distance over its time remaining. Energy grows with the square of
speed, so a route that is half motorway and half town is under-costed.

**State.** The phone sends the route's whole turn list at the start (22 to 31 steps in the owner's
log), each with a distance and the road it leads onto, and `nav/RouteShield` already classes roads as
Interstate, US, state or county. Which leg a step's distance describes is not yet settled: the
2026-11-12 drive leans to "the leg leading into this step", with one leg that did not fit
(`docs/carplay/05_METADATA_AND_CONTROLS.md`). One more drive's `gap=`/`nextGap=` lines settle it.

**Work.** Split the distance ahead into legs; give Interstate and US legs a motorway speed and the
rest a town speed, scaled so their times add up to the phone's time remaining; cost each leg with
`DriveOutlook.cruise` at its own speed. Check it with `HistoryReplayTest` as the current model was.

**Done means.** On replayed drives with a motorway stretch, the energy ahead is off by less than the
current model's median (7.3% at the start), and the car's log shows the per-class split.

## T18. "Will I make it" at route start (raised 2026-11-12)

**Why.** The arrival estimate is on the driver display, but a low one deserves a plain answer before
the drive, where it can still change the plan.

**State.** `DriveCard.arrival` has the figure and its low and short flags. The centre screen already
draws overlays over CarPlay (the call pill, the media pane).

**Work.** When a route starts, or the estimate first drops below 10%, show one card over CarPlay on
the centre screen for a few seconds: the arrival figure, and either how much charge is missing or
"plan a stop". It shows once per route, and a setting turns it off.

**Done means.** Starting an Apple Maps route that the estimate says ends under 10% brings the card up
once, and the log has the line with its numbers.

## T19. Spotify hand-back (raised 2026-11-12)

**Why.** `host/gm_ccpa/docs/14_LESSONS_LEARNED.md` §14.5, still open: GM's source-change probe takes
media focus, Spotify Connect moves playback to the car's own Spotify, and nothing the app does to the
iPhone brings it back. Only a Connect transfer back to the iPhone does (Spotify Web API
`PUT /v1/me/player`, which the Cloud-Bridge project used with a remembered device id).

**State.** Nothing built. It needs the owner to sign in to Spotify once (OAuth); the token would stay
on the head unit. Spotify has no setting to stop the automatic switching.

**Work.** A Settings ▸ Screen & Sound row to connect Spotify; on the silence pattern §14.5 records
(the probe pair, then focus lost while CarPlay plays), transfer playback to the iPhone's device id
and log it.

**Done means.** On a drive where the probe fires, music comes back on the iPhone within a few
seconds without a touch, and the log shows the transfer.

## Needs adapter firmware: T20–T23 (raised 2026-11-12)

Each needs a firmware change on the adapter as well as the app. Until T20 exists, each also needs the
adapter carried to the bench for `tools/ocbm_install.sh --full`.

## T20. Update the adapter from the app (raised 2026-11-12; BUILT 2026-11-13, benched 2026-11-14)

**Why.** Every task in the "needs adapter firmware" table ended with a bench reflash. enigy's fork
(github.com/enigy/ocbm) updates the adapter over the USB link instead.

**How it works.**
- *The bundle.* `host/gm_ccpa/tools/stage_adapter_fw.sh` (run by `build_aab.sh`) builds the box
  binaries and writes `assets/adapter_fw/`: twelve files (the four daemons, `iap_role_switch`, the
  radio seam and the supervisor scripts; 3.9 MB) under their staged names, and a manifest of
  `<sha256> <mode> <path>` lines headed by `version <id>`, the first 12 hex of the SHA-256 of those
  lines. Every path must also be in `tools/ocbm_install.sh`'s manifest.
- *Never in the bundle:* the boot path (`ocbm_boot.sh`, `run_ocbmd.sh`, `ocbm_udisk.sh`) and
  `fw_apply.sh`. A bad update can therefore always be undone; those stay bench-installed.
- *The adapter.* `ocbmd` takes `MGMT_FW` `[op]` (check, apply, confirm, rollback), acks at once and
  runs `/script/fw_apply.sh <op>` detached; the answer is `/tmp/fwup/check.json` or `apply.json`,
  which the app pulls over `CH_FILE`, matched by a nonce. `MGMT_INFO` says `"fw_update":true`,
  `"fw"` (the installed id, `/script/fw_version`), `"fw_pending"` and `"fw_prev"`. `ocbmd` creates
  `/tmp/fwup` at start, since a file push does not create folders.
- *Apply* (2026-11-14, after enigy/ocbm's `ocbm_update.sh`): refuses, changing nothing, on a bad
  path, mode or SHA-256, an update already in its trial, a build older than the adapter's (the
  manifest's `seq` is the app's versionCode; `/script/fw_seq`), or too little flash (0.7 of each
  file's size, enigy's measurement of jffs2 on this adapter, plus 512 KB). Then it places every new
  file as `<path>.new` and checks its sum again, writes the journal `/script/fw_pending` listing
  every file, and only then renames (old to `<path>.prev`, new into place), marks the journal
  `placed` and reboots. A lock keeps an update and a rollback from overlapping.
- *Confirm.* `AdapterFirmware.noteHealth`: the first `CT_BOX_HEALTH` with Bluetooth, btd and the root
  filesystem healthy makes the app ask `MGMT_INFO`, and if an update is pending, confirm it.
- *The trial.* `ocbm_boot.sh` starts `fw_apply.sh boot` when `fw_pending` exists. A journal never
  marked `placed` (power lost in the swap) is undone at once; so is the third start without a
  confirmation; ocbmd not running for 60 s rolls back; and once a host has said HELLO
  (`/tmp/ocbm_hello_seen`) the app has 300 s to confirm. With no host the trial stays open, so a car
  switched off straight after a good update keeps it (enigy's `ocbm_dispatch.sh` does the same).
  The previous files stay after a confirm, for Settings ▸ Advanced ▸ Adapter firmware ▸ Roll back.
- *App.* Settings ▸ Advanced ▸ Adapter firmware: Check (which files differ, the installed and
  carried ids), Update (Park only, with a confirmation), Roll back. Bench verbs `fw_check`,
  `fw_update`, `fw_rollback` run the same code without the UI.

**Tests.** `tools/tests/fw_apply_test.sh` (35 checks, under busybox `sh` in `tools/run_tests.sh`):
check, apply, confirm, rollback, every way the trial ends (no host, a host that never confirms, a
confirmation, ocbmd down, the third start), a power cut between two renames, and refusals for a bad
hash, the boot path, a foreign path, too little room, nothing staged, an older build and an update
already running. `AdapterFirmwareTest`: manifest parsing and
refusals, the content id, nonce matching, the confirm condition.

**Bench, 2026-11-14 (emulator with the adapter, `fw_*` verbs).** One file made to differ on the
adapter. `fw_check` named exactly it, with 4,284 KB free. `fw_update` sent it, the adapter applied it
and rebooted, and the app confirmed 9 s after boot (`[fw] update 577b9da9706c confirmed by the app`).
`fw_rollback` put the old file and id back. Stopping the app straight after an update did not test
the timeout: the adapter's USB re-attach relaunched the app, which confirmed it, as it should.

**On the adapter, through its console (2026-11-14).** A swap stopped between the two renames of
`/script/phone_reset.sh` and a reboot, as a power cut would leave it: the next boot put the original
back (same md5), removed `.new` and `.prev`, kept the version, and rebooted clean. An update applied,
then a host connected and never confirmed: the trial logged `a host connected; 300s to confirm`, and
after the window the adapter restored the original file (same md5) and version and rebooted clean.

**Before it works in the car.** The adapter needs one more bench install for the boot hook and
`fw_apply.sh`, and app 251 must carry the bundle. Done on 2026-11-13 for the owner's adapter.

**Done means.** From Settings, with the car parked, the adapter updates and reconnects; a bundle with
one bad hash is refused with nothing changed; and a forced failed start rolls back to the previous
set.

## T21. The car's name in Apple Maps (raised 2026-11-12; openautolink, held since 2026-11-08; BUILT and on the adapter 2026-11-13, not yet seen in Maps)

**Why.** Apple Maps names the car after the adapter. openautolink sends the car's make, model and
year instead.

**State.** Identify param 20 subs 1 and 6 are the box's constant `name` (`message.rs` ~:695), and
sub 8, `MapsDisplayName`, is not emitted at all. T7's fix needs sub 8 as well (the Hyundai project
that got EV routing working sends the car's name in subs 6 and 8), so build the two together. The
app's `accessoryName` bound (C-6) is not wired into the tunnel Identify,
where a rejected change costs the whole session's metadata (`0x1D03`) until the setting is off, the
same risk T7 took and passed. `docs/host/02_GM_AAOS_FIELD_REFERENCE.md` §9, "Held for adapter firmware".

**Built (2026-11-13).** On the bench Apple Maps ▸ Vehicles listed the car as "CarLink" while sub
8 carried the car's name, so Maps reads sub 6 `DisplayName`. When the app pushes
`mapsDisplayName` (T7, only to firmware saying `"ev_models":true`), `message.rs` now writes it into
sub 6 as well as sub 8; sub 1 `Name` stays the box's, and with no pushed name the bytes are as
before (`display_name_follows_the_pushed_maps_name`). On the adapter as `carplayd` md5 `ed5d4312…`.
Maps may keep the old name until the car is removed under Vehicles and added again.

**Done means.** With the setting on, Maps shows "Chevrolet Equinox EV" (or the name the app pushes),
and the Identify is accepted with no `0x1D03`.

## T22. CarPlay's status bar on the driver's side (raised 2026-11-12; openautolink, held since 2026-11-08; BUILT 2026-11-13)

**Why.** CarPlay can put its status bar (clock, signal, the app strip) on the edge nearest the
driver. openautolink offers Auto, Bottom or Driver side.

**Built.** Settings ▸ Screen & Sound ▸ CarPlay status bar: Auto (default), Bottom, Driver
(`DisplayPrefs.statusBarEdge`). The app writes `statusBarEdge: bottom|driver` on the main stream's
first view area, and nothing for Auto, so the default document is unchanged. The adapter
(`vehicle_config::status_bar_edge_ordinal`, `DeviceConfig::main_status_bar_edge`) declares Apple's
raw value (Auto 0, Bottom 1, Driver 2) as `viewAreaStatusBarEdge` on every main view area and never
on the cluster. Older firmware ignores the key. On the owner's adapter since 2026-11-13.

**Seen on the bench (2026-11-14, the owner's iPhone).** Auto and Driver both put CarPlay's dock on the
left edge, the driver's side in this car. Bottom moved it to a bar along the bottom, with the media
pane unaffected.

**Done means.** Each choice in the setting moves CarPlay's status bar at the next connect, and the
media pane and view-area switching still work.

## T23. Apple's own turn card on the driver display (raised 2026-11-12; step 1 BUILT 2026-11-13, app only; step 2 built; Apple's turn card WORKS on the car, owner 2026-11-18)

**Why.** The driver display shows Apple's map with the app's own turn card over it. Apple draws
its own maneuver card, with its lane and junction graphics, as a different kind of cluster content.

**State (checked 2026-11-13). Most of this already exists on the adapter and the app never asks
for it.**

- The instruction card is not a third stream. It is a *content type* the one cluster stream can
  show (`docs/carplay/06_AV_PIPELINE.md`, "How many video streams"). The adapter already advertises
  all three cluster URLs in `altScreenURLs` (`receiver::info`): `maps:/car/instrumentcluster/map`,
  `…/instructioncard` and the bare `maps:/car/instrumentcluster`.
- `carplayd` already switches between them on the existing stream: `CMD_NAV_CARD` sends
  `showUI(instructioncard)`, `CMD_NAV_APP` sends `showUI` for the bare cluster URL, `CMD_NAV_START`
  goes back to the map, and `CMD_NAV_APPEARANCE` turns the speed limit, compass and ETA on the map
  on or off (`ccpa/carplayd/src/main.rs`, `cluster_url`). The app has the constants in
  `ocbm/OcbmProto.kt` and `AdapterSession.sendCommand`, and uses only `CMD_NAV_START` and
  `CMD_NAV_STOP`.
- So the first test needs no adapter change: ask for the card, or for the bare cluster view, and
  look at what the iPhone draws at 2000×810.
- Map and card *at the same time* is the only case that needs Apple's second cluster stream (type
  112, `VideoStream.Alt2`). That has never been seen from a CCPA adapter, needs a new OCBM video
  channel and a second decoder in the app, and declaring a third display is the highest teardown
  risk in that doc.
- Every `showUI` URL the adapter builds ends in an empty `maneuverLayout=`. Apple's Simulator
  appends that key only when a layout is chosen; its values are not recorded. It may be how the map
  view is told to include the maneuver.

**Built (2026-11-13).**

1. *App only, works on the firmware in the car today.* Settings ▸ Cluster ▸ "Driver display
   shows": Map (the default), Turn card, Apple's view (`nav/ClusterContent.kt`). The app sends the
   matching command when the cluster stream opens, and at once if it is already up, then asks for a
   keyframe. The app's own turn card hides while Apple's card is chosen.
2. *Adapter, in the next reflash.* `CMD_NAV_URL` (`0x13`): the app sends the cluster URL itself,
   and `carplayd` shows it only if `ocbm_proto::cluster_url_ok` accepts it (one of the three
   advertised surfaces, with or without a query). `MGMT_INFO` says `"nav_url":true`. The app has
   the sender (`OcbmClient.sendNavUrl`) and no setting uses it yet; it is there so that trying a
   `maneuverLayout` value later is an app update.

**Not built.** Step 3, the second stream: only if one drive shows the card cannot share the stream
with the map and the owner wants both at once. Declare `DisplayPanel.Alt2` / `VideoStream.Alt2`
with the instruction card as its first URL, bench it first, then a new channel and a second decoder.

**Bench, 2026-11-13.** With "Turn card" chosen the app logged `cluster epoch open 1600x600 —
Apple's turn card requested sent=true` and the adapter `navCard showUI(instructioncard)`. With no
route, Apple drew its suggested destinations (Home, Work) on a plain background, not a turn; the
first frame came about two minutes after the request. The app's now-playing card drew over it.

**On the car, 2026-11-14 (Play 251).** The owner switched between Apple's view and Apple's turn card
twice during a route (14:06 to 14:08 UTC): each switch logged `navApp showUI(instrumentcluster)` or
`navCard showUI(instructioncard)` and the one feed changed what it carried, with no new stream and no
restart. So the card is separate *content* but not a separate *feed*: Apple's map and Apple's card
cannot both show. The capture taken at 14:07:22 stayed queued in the car (its network could not resolve
the receiver), so how much of the 2000×810 frame the card fills is still unseen.

**Decision (owner, 2026-11-14).** Without both at once, no more work on placing Apple's card: step 3 (a
second feed) is declined. Instead the settings were reworked (`host/gm_ccpa/docs/SETTINGS_AUDIT.md`
"2026-11-14 deltas"): one switch for CarPlay on the driver display and "Feed shows" Map / Apple's view;
the Turn card row chooses Off / This app's / Apple's; and the bottom-right panel can hold the turn card,
now playing or the drive card, remembered per turn card, so with Apple's card on the feed the drive card
or now playing can be docked in that corner.

**To see on the car (earlier note).** What the turn card and Apple's view each look like at 2000×810 during a route (the
"Capture driver display" button saves it), and whether the log shows
`cluster epoch open … Apple's turn card requested sent=true`.

**Risks.** Step 1 has little: these are commands the adapter already implements, and Map stays the
default. The card may be drawn for a small cluster and look lost at 2000×810; the bare cluster view
may be the same picture as the map. Step 3's risks are in the doc cited above.

**Done means.** On the car, Apple's instruction card shows on the driver display during a route,
beside or instead of the map as the owner chooses, and the app's own card steps aside while it does.

## T24. Lyrics with no connection: the lyrics package (raised and built 2026-11-14)

**Why.** The car's own data is short, and the app looked every song up on lrclib.net as it played.
The owner asked for a large package of lyrics taken while on Wi-Fi, ideally from their Spotify
account, so the app can stay offline and still show lyrics.

**Built.** The home server makes the package and the car takes it only on Wi-Fi or a hotspot.

- *Builder* (`host/gm_ccpa/tools/lyrics_pack.py`, stdlib Python, weekly from the owner's crontab).
  Three lists: Billboard's Year-End Hot 100 for 1980 to 2025 from Wikipedia (4,255 songs; 4,172 have
  timed lyrics on lrclib, up to three lengths each); the owner's Spotify listening-history export
  (3,419 songs played 30 s or more, the length taken from full plays); and their Spotify library
  (liked songs and the playlists they own), once signed in with their own developer app (PKCE, no
  secret). Spotify gives a development-mode app nothing inside anyone else's playlist, its own mixes
  included, so those songs come only through the history or the catch-up below. lrclib is asked once
  per song, slowly, and every answer is kept, so a weekly run asks only about new songs.
- *Receiver* (`/opt/stacks/cloudbridge-log/log_server.py`): `GET /lyrics?car=<id>[&have=<id>]`, behind
  the gate `/history` uses, 304 when the car already holds that package.
- *App* (`lyrics/LyricsPack.kt`): the download (Wi-Fi only; by itself at most daily after an upload,
  or Settings ▸ Screen & Sound ▸ Lyrics ▸ Download now), a store of one data file and an index, and
  the lookup order: songs heard before, then the package, then lrclib if *Look up online* (Always /
  Wi-Fi only / Never) allows. A song is matched by its bare title (what a release adds after " - "
  or in brackets dropped), one word of the artist in common, and a length within 3 s: lyrics timed
  for another cut would run early or late, so no match is better than a wrong one. A song that goes
  without is listed and looked up the next time the car is on Wi-Fi.

**Verified.** The builder's naming rules against the app's (the same test vectors in
`tools/tests/lyrics_pack_test.py` and `LyricsPackTest`), and on the emulator against the real
receiver: download, "up to date" on a repeat, a song found under its remaster's name, one not in the
package refused. **Not verified:** on the car.

**Spotify sign-in done (2026-11-14):** 339 liked songs and 17 of the owner's 30 playlists readable,
487 songs not already known from the history or the charts; 8,130 songs in all. In Play 253.

**Open.** *Look up online* still defaults to Always, so nothing changed unasked; Wi-Fi only is the
setting for this car. The first download on the car has not been seen.

## T25. Faster connect (raised 2026-11-14; the owner's first annoyance; step 1, the connect timeline, built 2026-11-20)

**Why.** From starting the adapter to the first CarPlay picture is a median 10.7 s on the car (44
sessions, `[startup]` lines; fastest 8.1 s). The owner named it first when asked what still annoys.

**Work.** Measure before changing anything: the app already logs each stage (attach, permission,
hello, subscribe, discoverable, Bluetooth connected, Wi-Fi handoff, first picture). Take the last
month's lines, find which stage holds most of the time and how much it varies, and attack that one.
The Allow tap (T15) sits inside this time too.

**Done means.** The median on the car drops by a stated amount, measured by the same lines.

**Measured 2026-11-20** (the app's `[startup]` lines in the uploaded logs, 20 Oct–14 Nov: 29
sessions that reached a picture, before the hotspot-join builds). Medians, with the 75th percentile
in brackets:

| Stage | Median (p75) |
|---|---|
| attach → permission (the Allow tap, T15) | 2.7 s (7.0 s) |
| subscribe → discoverable (radios up, `btd` started) | 3.1 s (6.4 s) |
| discoverable → Bluetooth connected | 1.0 s (5.4 s) |
| Bluetooth connected → Wi-Fi handoff | 3.0 s (3.0 s): nearly constant |
| handoff → auth-setup OK | 2.8 s (3.8 s) |
| auth-setup → first picture | under 1 s |

- *Attach to picture:* median 20.7 s, fastest 8.6 s.
- *With the hotspot join on (15–18 Nov, 13 sessions):* subscribe → discoverable rose to 16.8 s.
  That is the join's own cost (T15).
- *Why the two 3 s stages are nearly fixed:* each holds one MFi signature. enigy/ocbm measured
  1.64–1.65 s for every signature on this chip, and 0.11–0.17 s per certificate read (release
  v2.6.1). The chip cannot be made faster.
- *Why this is not finer:* the app reads the adapter's events from a batched log, ±0.5 s.

**Step 1, built 2026-11-20 (issue #2 on wasidremin/ocbm_equinoxev): the `[timeline] v1` line**
(`docs/carplay/02_SESSION_LIFECYCLE.md`, "The connect timeline").
- It is enigy/ocbm's 2.6.1 instrumentation with the same keys: one line per wireless connect, every
  step in adapter ms since the app arrived, plus the chip's own times.
- It changes no timing, order or protocol byte.
- *Not ported:* the `hostapd`/`udhcpd` watcher (`ap_enabled`, `sta_assoc`, `dhcp_ack` read `-`).
- *Verified:*
  - unit tests: `box-common`, `btd`, `ocbmd`, and `carplayd`, whose tests now run in
    `tools/run_tests.sh`;
  - the adapter build;
  - *bench adapter, 2026-11-20/21*, via the emulator and the app:
    - `btd`'s record held the origin and every radio and `btd` stamp, in order, on one clock:
      `sup_radios` 1,354, `bt_power` 2,074, `btd_start` 2,200, `ap_start` 2,294, `ap_up` 2,644,
      `bt_up` 3,154, `page_start` 3,159 (ms after the app arrived);
    - this chip took **1,645 ms** to sign and 150 ms to read its certificate, matching enigy's
      figures.
  - *a live connect, bench, 2026-11-21* (the iPhone beside the server): the line printed once per
    connect under `[box:airplayd-wl]`, every stamped key in order, both chips at 1,644–1,656 ms
    (`docs/carplay/02_SESSION_LIFECYCLE.md` has the line and its reading). The first line read
    `bt_power=-`: two of `bt_on`'s four success paths, Bluetooth already up among them, did not
    stamp. Fixed, with a test (`radio_hal_test.sh`, then `radio_scan_test.sh`), and the next run read `bt_power=735`.
- *Bench numbers, with the hotspot join on:* 24.0 s and 32.6 s from the app's arrival to the first
  frame. The join held back the access point, and so `btd`, which started at 10.0 s and 17.6 s (the
  second run found the router on another channel); the phone took 6.2 s and 8.1 s to answer the page; the two
  signatures took 3.3 s. The car's numbers will differ: its page median is 1.0 s.
- *Not yet seen:* a line from the car.

**Step 2, enigy's speedups, not started.** What each would buy here, from enigy's measurements:
- *Armed Bluetooth (A1):* the page about 0.1 s after the app arrives instead of about 1.2 s.
- *Access point ahead (A3):* about 0.8 s.
- *Early `carplayd` (A2):* not yet measured.
- *Certificate cache (A4):* 0.2–0.3 s. It needs a checked cache: this adapter once read a
  40,113-byte certificate (`docs/wireless/01_BT_AND_RADIO.md`), and a cache would keep such a read.

Choose with the timeline from the car, not from enigy's bench: its line says which of these stages
is long on this car.

**The car's timeline with 265 (2026-11-22, Wi-Fi link, three connects).** Subscribe 0.3 s after the app
starts; radios and `btd` up 3.4 to 3.7 s after the subscribe; then the adapter pages the phones and the
Bluetooth link arrives 6.4 to 12.5 s after `page_start`, where a present phone answers in about a second.
The box log says why: `CONNECT_FAILED … status=0x04` (page timeout) and `Host is down` for one bonded
phone, then `DEVICE_CONNECTED` for the owner's. The stored order (`/etc/carplay/projection_policy.json`,
two entries, set from Settings ▸ CarPlay ▸ Phones on 2026-11-08, 11-15 and 11-16) puts the other bonded
iPhone first, the owner's wife's (owner, 2026-11-22), which was not in the car. Moving the owner's phone to the top is worth about 6 s of the 18 s;
the owner was told (2026-11-22). The rest: handoff to auth-setup about 2 s and the picture under 2 s
after, the two MFi signatures fixed at about 1.7 s each.

**The keeper's first miss (2026-11-22).** Built: a missed hotspot is retried at once on every channel,
then the 10 s steps (`keeper_wait`, `docs/wireless/01_BT_AND_RADIO.md` §6e); benched on the adapter with
no hotspot in range (the 4th to 5th miss 46 s apart, the new spacing), the adapter's script put back
after. *Not on the car.*

**In the car with 266 (2026-11-22, 19:34 to 19:41 UTC).** The owner moved their phone first at 19:39:34
(`phone order: 2 phone(s) set`). Before, each connect had `CONNECT_FAILED … status=0x04` and 11.1 s from
subscribe to Bluetooth connected; after, none, and 4.7 and 5.3 s. From the adapter's OCBM device to the
picture on a cold start: 37.2 s at 19:38:46, 27.9 s at 19:40:14. Both started with the Allow tap after the
hotspot search had missed (T15); the adapter joined the car's hotspot about 22 s after its kernel started
(`joined on channel 149 in 8 s`), which on that path is the longest wait left. The keeper's at-once retry
was not exercised.

**The car's adapter timeline, 2026-11-22 to 11-25 (the `[timeline] v1` lines, 14 cold starts; ms after
the app arrives at the adapter).** First frame at 10.1 to 11.5 s on a good start, 12.3 s median, worst
29.3 s. The good start of 2026-11-25 7:02 AM, by step:

| Step | Time |
|---|---|
| radios started by the app's subscribe, Bluetooth up (`sup_radios` → `bt_up`) | 2.3 s |
| the phone answers the page (`page_start` → `acl`) | 1.3 s |
| Bluetooth, iAP2 and the first MFi signature (→ `ident`) | 2.5 s, 1.65 s of it the chip |
| Wi-Fi handoff, `carplayd` started, the phone seen (→ `svc_seen`) | 0.9 s |
| pair-verify and the second MFi signature (→ `auth_out`) | 2.0 s, 1.65 s of it the chip |
| screen set up, first frame | 1.0 s |

The slow starts were the phone answering late (`acl` 8.8 s and 17.5 s after the page), `bt_power` late
(4.2 to 5.6 s), and the phone seen late once (`svc_seen` 6.5 s after `carplayd` started). Before the
timeline: about 5 s from power-on to the adapter's USB device (`ocbm bound uptime_ms=5000`), and the Allow
tap. Every start on 2026-11-25 was a cold boot (`ocbm-boot start uptime=2s`): the Equinox cuts the
adapter's power while parked.

**enigy's "about 6 s" (owner, 2026-11-25) is a wake, not a cold start.** OCBM 2.6.5: "a wake reached
the picture in 5.2 to 6.4 s" with the adapter already powered between sessions, Bluetooth kept on but
hidden, the access point and receiver started early. From power-on, 2.7.0's bench figure is 13.8 to
16.9 s. With the tap and the two signatures, about 10 to 11 s from power-on is the floor here.

**Step 2, approved 2026-11-25** (owner: "Yes please", to this order):
- *2a, radios at power-on:* port enigy/ocbm 2.7.0 and 2.7.1's start (both radios loaded together and
  early, Bluetooth armed, the access point and `carplayd` ahead of the app; the longer first page, the
  1.5 s stall cut, the same phone paged again). Expected: 2 to 3 s off a good start, more off the slow
  ones. A port, not a copy: `radio_hal.sh` has diverged. Benched by power-cycling the adapter.
- *2b, after 2a is seen on the car:* the adapter starts connecting the phone before the app arrives,
  with the configuration the app sent last, so the page, the handoff and the first signature overlap
  the tap. Up to about 5 s more. It changes the rule that the app's subscribe wakes the radios, and a
  car that wakes with its screen off (twice on 2026-11-25) would connect the phone too.

**The bench's baseline for step 2a (2026-11-26, the car's adapter rebooted, `ocbm-host` as the host
arriving 9.1 s into the boot, uptime ms from `/tmp/startup_marks`).** The boot's Bluetooth prewarm, started
by the supervisor after the USB gadget (bound at 5.1 s), finished at 12.3 s; so after the host arrived the
supervisor took 1.3 s to start the radios, Bluetooth was powered at +3.6 s and paging at +4.4 s, the
access point up at +5.2 s, the phone answered at +5.8 s and was identified at +8.4 s. In the car the app
arrives about 8 to 10 s into the boot (the gadget, then the Allow tap), still before that prewarm ends.
The same run found a race in the module unpack (fixed; `docs/wireless/01_BT_AND_RADIO.md` §6e).

**Step 2a, items 1 and 2, built and benched 2026-11-26** (owner: "Go ahead with your next steps", to:
1, the radios at power-on; 2, the supervisor reacting at once; 3, enigy's 2.7.1 page changes). Not a
copy of enigy's 2.7.x: this adapter already had a boot prewarm, its attach does not wait for Wi-Fi, so
2.7.0's "together" order does not apply. What was built (`docs/wireless/01_BT_AND_RADIO.md`, "The boot
preload"): `radio_hal.sh boot_preload`, started by `start_main_service.sh` ahead of the USB gadget after
the stock GPIO set-up; Bluetooth ready at about 9 s instead of 12.3 s, the Wi-Fi driver loaded after it;
the supervisor looks for the host every 0.1 s; the radio lock is polled every 0.1 s; the name's suffix
kept (`/etc/ocbm/name_suffix`). With the host at 9.1 s: paging 1.6 s after it arrives (was 4.4 s).
The first build hung the adapter twice and was reworked (same document). *Verified:* `radio_hal_test.sh`
(the preload's order, one try, the guard), `iap2-core`'s tests (the name), 8 cold boots and 5 host
sessions on the bench by reboot, not by power cut. *Not yet:* the car; a power cut (the car's own
start); item 3. `start_main_service.sh` is the boot path: bench-installed on the car's adapter
2026-11-26, never by an app update.

**The hardware watchdog (2026-11-26, owner: "start on the next step with the hw watchdog").** Fed from the
top of the boot, so a preload that hangs the kernel costs about a minute instead of a drive, and two such
boots switch the preload off (`docs/ops/01_RECOVERY.md`, "A frozen adapter reboots itself"). Benched.

**Item 3, enigy's 2.7.1 page changes, weighed against the car and not ported (2026-11-26).** From the
car's uploaded logs (31 timelines; page outcomes per start since the 3.2 s page timeout):
- *the 1.5 s cut of a stalled RFCOMM open:* SDP to the open channel took 0.2 to 1.6 s in all 31 connects,
  never near the 8 s wait; the bench once took 2.4 s, which the cut would have broken;
- *the first call lasting 5.12 s:* no start where the owner's phone missed a 3.2 s page and answered a
  later one; the misses are the absent phone paged first (T35's order) or a car with no phone in it;
- *calling the same phone again sooner:* after "answered and reset the link" the car reached the
  phone's channel 2.5 to 3 s later in 22 of 26 starts, 2 s of it `RESET_RETRY_SECS`. Tried at 0.5 s on
  the bench: the one reset took 1.9 s to answer the earlier page and reached the channel 3.6 s after the
  reset, no sooner; the phone is not ready earlier, and the shorter pause ends the page window at 3.7 s
  instead of 5.2 s after the reset, where four of the car's connects came at 3.6 to 4.2 s. Kept at 2 s.
- *status `0x07` ("controller still holds a link") paged again:* 13 in the car's logs, one with a
  connect after it (17 s). Not worth 2.7.1's SDP changes yet; recorded.

**enigy/ocbm 2.7.0 (2026-11-22, not ported).** Both radios loaded together and earlier in the boot, the
radio description kept across boots: 13.8 to 16.9 s from power-on to picture on enigy's bench against
16.2 and 17.4 s. About 4,000 changed lines against a `radio_hal.sh` that has diverged from this one, and
`ocbm_boot.sh`, which only a bench install changes. Held for its own session (owner's call); that session is step 2a above (2026-11-25).

## T26. "Try this drive" card (raised 2026-11-14)

**Why.** The car has no adb and the owner is the only tester, so a feature is "not yet on the car"
until a drive happens to exercise it and the logs happen to show it. About a dozen items wait that way.

**What.** The build carries a short list of things to check. On Park the app shows them, one tap
each: worked, did not, did not try. The answers upload with the logs, so the next session knows.

**Done means.** A published build's checks come back answered without the owner writing anything.

## T27. "Why didn't CarPlay connect?" (raised 2026-11-14)

**Why.** A slow or failed connect is explained today by reading logs afterwards.

**What.** After a connect that fails or takes much longer than usual, one plain sentence on the
centre screen saying which stage stalled (the phone did not answer, the handoff did not finish, the
adapter was not there), with the fix that fits: wait, restart the adapter, forget the phone.

**Done means.** The owner can say why a bad connect happened without a log.

**Built 2026-11-22 (owner: "Let's do 1 and 2 now"), app only, not yet on the car.** `ocbm/ConnectStall.kt`
(pure): from the steps `StartupClock` stamps, the last step reached and how long ago, against an allowance
of about three times its usual time in the car (attach 45 s, permission and hello 15 s, subscribe 30 s,
discoverable 30 s, Bluetooth connected 15 s, the Wi-Fi handoff 20 s, auth-setup and keyed 15 s); over it,
one sentence naming what to do: the adapter is not answering (unplug it for ten seconds), its Bluetooth
did not come up, the iPhone has not answered (its Bluetooth, or Settings ▸ CarPlay ▸ Phones), it has not
joined the adapter's Wi-Fi, CarPlay has not started (forget the adapter on the iPhone), or no picture
(restart the app). MainActivity looks every 3 s while it is in front and puts the sentence on the
startup screen's detail line (after the Always hint and a failure); it clears at the picture. The log
says `startup: stalled after <step> for <n> s` once per step, no names. *Verified:* `ConnectStallTest` (the
car's 15:08 connect on 2026-11-22 says nothing at any step; a phone that never answers names Bluetooth and
the Phones order; the adapter gets longer than the hotspot look; `bt-converged` is not progress) and the
app's 618 unit tests. *Not verified:* on the emulator or in the car.

## T28. After-drive summary (raised 2026-11-14; BUILT 2026-11-24, app only, not yet on the car)

**What.** On Park, one screen for the drive just finished: distance, energy, mi/kWh against the
usual, and where the energy went (speed, the cold start, standing) as `DriveOutlook` already splits
it. Numbers the Drive card cannot show while moving.

**Done means.** The summary shows on Park after a drive of a mile or more and matches the drive log.

**Built 2026-11-24.** The owner chose from four mockups (full card, compact strip, driver display,
card and month) the **card and month** layout, shown on Park for 30 s ("Park, 30 s").

- *When.* `VehicleStateWatcher`'s gear goes to PARK from a gear known not to be PARK (an app started in
  Park shows nothing; an unknown gear says nothing), Settings ▸ Screen & Sound ▸ After-drive summary
  is on (the default), and the recorder's open drive (`DriveLive`) has gone a mile. At that moment the
  drive is still open: it ends at its last moving sample and closes only 4 min later, so it is not yet
  in the drive log. One summary per drive (by its start time). It closes after 30 s, on a tap on the
  card, or on leaving Park (`telemetry/AfterDrive.kt`).
- *What* (`telemetry/DriveSummary.kt`, pure). The rate and how it compares with the usual
  (`TripStats.expected`: this route at today's temperature, else this route's median, else drives of
  the same length; the drive itself excluded); distance, net energy, time, temperature, battery
  percent at start and end. Where it went is `TripStats.split`, the split the Insights trip view uses:
  driving, warm-up (how much more the opening 3 km used than the same distance later), stopped, adding
  up to the net, with regeneration as a line of its own (it is already inside driving). The split
  has no "speed" part: the energy model's speed term is not separated per drive, and a part that
  does not add up would not match the drive log. Then bars for the last 10 drives (this one last),
  the month (rate, miles, kWh, this drive counted once even if the recorder closed it while the log
  was read) and all time.
- *Where* (`pane/AfterDriveView.kt`). Over the CarPlay picture, centred on it, under the call pill
  and the parked video. Only a touch on the card is taken.

*Verified.* `DriveSummaryTest` (7: only a move into Park from a known gear; under a mile or already
shown is skipped; the route's usual and the month counted once; no history still shows the drive;
the parts add up; the sample's words; the clock range in the car's offset) and the app's 641 unit
tests. On the AAOS emulator: the card drawn from the sample (`--es run topbar_preview --ez
summary_preview true`), placed on the picture under the top bar after two fixes (the card first ran
under the bar, then under the emulator's own bars); it closed itself after 30 s (`drive summary:
closed (30 s)`); a VHAL gear injection to Drive closed it (`closed (left Park)`) and back to Park
logged `not shown (no drive)`. *Not verified:* the tap (the headless emulator's system server
crashed in `libinput` on every injected tap, twice), and a real drive's summary: not yet on the car.

## T29. Charging card (raised 2026-11-14)

**What.** While plugged in with the car on: charging power, time to the charge limit and the cost so
far, from the readings the charge log already uses. The head unit sleeps while the car is off, so
this is for sitting in the car at a charger; the rest of the session still comes from the log.

**Done means.** At a charger the card's power and time to the limit agree with the car's own screen.

## T30. Weather-aware arrival estimate (raised 2026-11-14)

**Why.** The arrival estimate knows speed and temperature. Wind and rain are what it cannot see,
and they are most of its misses on a long motorway drive.

**What.** A forecast for the route, fetched on Wi-Fi or the hotspot before leaving (never on the
car's data), and a wind and rain term in `DriveOutlook`, fitted on the owner's drives as the rest
was. Needs a forecast source that is free and keyless, to be chosen.

**Done means.** On replayed drives with known weather the estimate's error falls; if it does not,
the term is left out.

## T31. Mileage log (raised 2026-11-14)

**What.** Each drive tagged business or personal (a default by route, one tap to change), and a
monthly report from the home server: miles, cost and the list of drives. The drive log has the rest.

**Done means.** A month's report can be produced without touching the car.

## T32. Context docking (raised 2026-11-14)

**Why.** The bottom-right panel holds one card, chosen in Settings (2026-11-14). The card worth
having there changes with what is happening.

**What.** A fourth choice, "Automatic": the turn card during a route, the drive card otherwise, the
call card while a call is up. Each switch eased, and never while a turn is close.

**Done means.** A drive with a route, a call and a stretch with neither shows the right card in the
panel at each, with no setting touched.

## T33. Remove the Silverado hotspot mode (raised 2026-11-14)

**Why.** The app still carries upstream's design in which the iPhone streams over the vehicle's
hotspot straight to the app, behind Advanced ▸ Adapter Wi-Fi. The Equinox cannot use it (GM's
firewall drops the phone's connection), and every connect path that exists is one that can go wrong.

**What.** Delete the mode, its setting and the code only it reaches, and the documents that
describe the truck bring-up. A removal with no new behaviour; T15's hotspot route is a different
thing (the adapter's link to the app, not the phone's) and is not affected.

**Done means.** The app builds and connects as before with the mode gone, and its tests pass.

**Done in code 2026-11-26** (owner: "Do T39 and t33 now"). Removed: the app's own CarPlay receiver
(`CarPlayRx`, its mDNS responder and inspector, pairing in `pair/`), the Adapter Wi-Fi switch and every
branch on it (`CarPlayActivity`'s in-process seams on 9001 to 9004 and their players, `OcbmProbe`'s
`wifi_ap: false` document and its credential check, `MainActivity`'s receiver start, health check,
callbacks and bench verbs `carplay_rx`, `carplay_stop`, `mdns_self`), the Vehicle hotspot setting and
its dialog, `SessionSupervisor`'s receiver hooks (discovery pause, re-announce, dial-back) and the three
phases only the receiver reached (receiver unhealthy, receiver ready, awaiting dial-back; rung 0 keeps its
slot as a no-op so the ladder's spacing is unchanged). With it the app's only native code: the Rust
receiver core (`native/carplay-jni`), whose JNI names had not matched the Kotlin class since the package
rename, so the mode could not have run anyway; `assets/info.bplist` and the four `tools/info_plist_*.py`
that edited it; the NDK and FDK AAC steps in `build_aab.sh`, `build_apk.sh` and `tools/test.sh`.
`build_aab.sh` now empties its asset staging copy first, or the deleted plist would have kept shipping.
`structuralError` lost its always-true flag. The stored hotspot name, password and channel and the app's
pairing store are deleted at start (`SETTINGS_AUDIT.md` 2026-11-26). The FDK AAC codec stays in
`carplayd` on the adapter. *Verified:* the app's 648 unit tests; the debug APK carries no `.so` and no
`info.bplist`. *Not verified:* a connect: the adapter and phone had left the bench before the run, so
the first drive on the next build is the check. *Left:* the app's older documents
(`host/gm_ccpa/docs/00_HANDOFF`, `01_FINDINGS`, `06_BRINGUP_RUNBOOK`, `11_HARDENING_PLAN`,
`12_OBSERVED_FLOW` and parts of others) still describe the truck bring-up; pruning them is its own pass.

## T34. Video from the iPhone while parked (raised 2026-11-21; BUILT 2026-11-21, both halves; not yet on the car)

**Why.** iOS 26 and later let a CarPlay head unit play the phone's AirPlay video. enigy/ocbm shipped
it in OCBM 2.0.0 with Carlink 3.00 (2026-11-12) as "route A": a video sent from the iPhone (Safari,
for example) plays full screen on the car's screen while the car is parked. The owner asked for it on
2026-11-21, to be built once the features just added have been tested in the car.

**Order (owner, 2026-11-21).** Not before the car has exercised Play 261 (the nl80211 hotspot join,
the connect timeline, the headset key) and Play 262's adapter fixes (BusyBox DHCP server, its teardown
wait, hostapd's station events, the re-attach re-key); see `04_OPEN_ITEMS.md`.

**What enigy built** (`docs/carplay/01_OCBM_PROTOCOL.md` "Route A" in enigy/ocbm at 801074f,
Unlicense, so it may be ported with credit):
- *The offer.* `/info` carries `videoPlaybackInfo` and `playbackCapabilities` (no FairPlay secure
  stop), and the phase-1 SETUP echoes `videoPlayback` when the phone proposes it.
- *The parked rule is the host's.* The app tells the box whether video may play
  (`CMD_VIDEO_ALLOWED`), and carplayd sends the phone `setVideoPlaybackAllowed` at RECORD and on
  each change. Carlink allows it only while the car is known to be parked.
- *The play queue* rides the phone's shared DataStream (the Playback client). The box relays its
  messages to the host as metadata records and back (`INPUT_DATASTREAM`).
- *The phone's network, never the car's.* The phone's `setProxyParameters` reaches the host; the host
  runs TLS 1.3 with the phone's pre-shared key and the proxy's CONNECT, and the box carries the bytes
  on a new channel (`CH_TUNNEL 0x0025`, `INPUT_TUNNEL`), to the phone's own address only. Carlink
  added Bouncy Castle for that TLS.
- *What is declined.* FairPlay-protected video (the TV app's) stays on the iPhone.
- *Fixes found on the way:* a partial TEARDOWN must stop only the DataStream it names, never the
  iAP2 one (a86e552), and the proxy parameters are replayed to a host that re-attaches (c05d066).

**What it takes here.** Adapter: the receiver's offer, playback and tunnel modules and the two new
OCBM channels, announced in `MGMT_INFO` so an older app ignores them. App: the tunnel's TLS client,
a video player, the play queue, and the parked gate from the car's gear property the app already
reads. Two things to check first: that the Equinox's GM build lets a Play app play video while parked
(a distraction-optimised restriction may apply), and that the extra dependencies fit.

**Done means.** Parked, a video sent from the iPhone plays on the centre screen; shifting out of Park
stops it; CarPlay carries on normally afterwards.

**State (2026-11-21): both halves built; not yet on the car.** Started the day the car tested 261 and
262 (owner, 2026-11-21).
- *Built, ported from enigy/ocbm* (`NOTICE.md`; the wire is `docs/carplay/01_OCBM_PROTOCOL.md`
  "Route A"): the offer, the Playback DataStream relay, the tunnel, `CH_TUNNEL 0x0025`,
  `INPUT_DATASTREAM 0x0A`, `INPUT_TUNNEL 0x0B`, `CMD_VIDEO_ALLOWED 0x14` (enigy's `0x13` is
  `CMD_NAV_URL` here) and `MGMT_INFO` `"video":true`; a partial TEARDOWN that stops only the
  DataStream it names.
- *Changed from enigy's:* the offer waits for the app's `enablesVideoPlayback` (the app pushed `true`
  through 262 and pushes `false` from 263 until it has the player); `setProxyParameters` goes to the app
  by a sender that waits for the seam; the log shows a URL's scheme and size, never its host; and at
  RECORD the box tells the phone video may not play when the app hasn't said.
- *Why the RECORD word (bench, 2026-11-21, the owner's second iPhone on iOS 27.2.1, the AAOS emulator
  with the adapter passed through):* with the offer on and nothing said, two sessions in a row ended
  60 s after RECORD, one with the phone going silent and one with it leaving the adapter's Wi-Fi. With
  "may not play" sent at RECORD, the next session ran past 6 minutes. The bench was noisy (the server
  at load 20; a 262 session with no offer also ended after about 6 minutes, and the emulator missed
  heartbeats), so this is likely, not proven.
- *Verified:* unit tests (ocbm-proto, receiver, carplayd, ocbmd; `tools/run_tests.sh`), and on the
  bench: the phone proposed `videoPlayback`, took the echo, opened its SenderSettingsData DataStream and
  sent `setProxyParameters` (350 B), which reached the app; `setVideoPlaybackAllowed(false, idle)` went
  out at RECORD. carplayd grew 101 KB (2.25 to 2.35 MB); the adapter had 3.4 MB free.
- *The app's half (2026-11-21), ported from enigy/carlink `com.carlink.video` (NOTICE.md):* the play
  queue, the Media3 player, the phone's proxy offer, the TLS-PSK handshake, the tunnels, the HTTP/1.1
  client and the origin's TLS, in `video/`; the wiring (`video/RouteA.kt`) and the screen
  (`video/VideoOverlayView.kt`, a View over the CarPlay picture with a Close button, below the call pill)
  are this app's. The owner chose the two libraries enigy uses (2026-11-21, "option 1": accept the
  larger app): Media3 1.8.0, the newest that builds against compileSdk 35 (enigy's 1.10.1 needs 36), and
  Bouncy Castle 1.80. The debug APK went from 10.7 to 16.1 MB. Setting: CarPlay ▸ Video, on by default
  (`SETTINGS_AUDIT.md`). Parked is the gear reading PARK (`VehicleStateWatcher`); unknown is not parked.
  The subscribe asks for the offer only with the setting on and firmware that says `"video":true`.
  Logs carry no part of a video's address.
- *Verified:* unit tests (31 for route A: the play queue's 19, the proxy's 7, a real TLS 1.3 PSK
  handshake against Bouncy Castle's server set up as the phone's, and the screen's fit, the record test
  and the FairPlay check). On the AAOS emulator with the bench adapter and the owner's second iPhone:
  the app saw `"video":true` and asked for the offer, the box echoed it, the app told the phone video may
  play (the emulator's gear reads PARK), the phone's proxy offer reached the app, and the adapter took
  the app's tunnel open and connected to the phone's proxy. Over this server's USB with `ocbm-host` as
  the host (`OCBM_SESSION_TUNNEL=1 ocbm-host session 1314 2d00 150 <cfg>`), the adapter's answer came
  back on `CH_TUNNEL` 34 ms after the open.
- *Not verified:* on the emulator the adapter's answer never reached the app. The emulator decodes
  CarPlay's HEVC in software on this server (load 15 to 190, swap full), the app's read waits on the
  decoder, and ocbmd's queues to it stood at 115 to 466 KB, so nothing behind them got through
  (`00_BUILD_AND_DEPLOY.md` item 9). So the app's TLS through the phone's real proxy, a video playing,
  the screen, and Park/Drive changes have not run. `--es run video_net_test` fetches a small page over
  the phone's network as the player does, for the next bench or a car log.

**In the car with 263 (2026-11-21).** The offer was negotiated, the phone sent
`setVideoPlaybackParameters` (111 B, three times) and never opened the play queue, so nothing played.
The command was logged by size only. From the next build the adapter logs its shape (keys and types,
no values: `[video] phone's setVideoPlaybackParameters: {…}`). The owner tried YouTube in Safari
(owner, 2026-11-21). enigy found that only Safari's own player hands video over, with Apple's example
HLS stream, and that YouTube offers CarPlay and then backs out; this drive matches the second. Next
try: Apple's example stream in Safari
(`devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_ts/master.m3u8`, from
developer.apple.com/streaming/examples), which separates "the phone never hands this player over"
from "this adapter or app fails".

**In the car with 264 (2026-11-22).** The update to firmware 44d032bbc5a5 was confirmed, so the adapter
offered video (`"video":true`, `enablesVideoPlayback: true`). The owner played Apple's example stream in
Safari, parked: the phone moved Safari's audio onto CarPlay (a partial TEARDOWN and a new SETUP of media
stream 102, 13:57:43 UTC) and kept the picture on the phone; its AirPlay sheet listed CarPlay as the
current route (owner's screenshot). It never opened the Playback DataStream (no `A6B27562` SETUP in
either session), so nothing reached the app. `setVideoPlaybackParameters` carries one key,
`fadeDuration` (an integer). The `/info` offer is enigy's to the key, `features` included
(`0x6144440B80`, the same `featuresEx` as enigy's `gAtERGE=`), and so is `setVideoPlaybackAllowed`.
The one difference found: each session's phone heard "may not play" at RECORD, because the app speaks
only once the session is keyed, and "may play" after it (5.5 minutes later in the first session, which
began in Drive; 1.1 s later in the second, which began in Park). enigy's adapter says nothing until the
host has spoken, and LIVI sends "may play" at RECORD when parked. Whether the phone ignores a later
"may play" is not known: the adapter does not log the phone's reply bodies to its commands, only a
non-2xx status, and there was none.

**For 265 (2026-11-22, owner: "Go ahead with your recommendations").** (1) The phone's first word is the
right one: the app puts the gear at subscribe in its document (`video_allowed`), carplayd tells the phone
that at RECORD unless the app has spoken since, and the app sends each gear change from the subscribe on,
not only once the session is keyed. A session that starts parked now hears "may play" at RECORD and
never "may not play" first. (2) carplayd logs the phone's answer to each `setVideoPlaybackAllowed` and
each DataStream message, by `CSeq`: the code and the body's `status`. *Verified:* unit tests (the
document's key and its absence without the offer; the config's parse; a subscribe word fills in only a
word not yet said; an answer's text and its `CSeq` match), all adapter suites, the app's unit tests.
*Not verified:* anything on the adapter or in the car, and the app's send on a gear change before the
session (RouteA needs a Context; no JVM test). Not benched: the adapter was in the car.

**In the car with 265 (2026-11-22, 15:05 to 15:08 UTC).** The first word worked: `the app's subscribe says
video may play: kept for RECORD`, then `setVideoPlaybackAllowed(true, idle)` at RECORD, in both sessions.
The owner saw the phone offer to play on CarPlay and nothing on the car's screen (owner, 2026-11-22). The
phone again moved only Safari's audio: five times between 15:06:06 and 15:07:02 a `modesChanged` (287 B), a
partial TEARDOWN of media stream 102 and its SETUP again. It never set up the UrlFling DataStream
(`A6B27562`, Apple's name for the Playback client, `03_SDK_GROUND_TRUTH.md`), never asked for the video
screen, and sent no other command. No answer to any of the adapter's commands was logged, here or in any
upload: the phone appears not to answer them on the event channel. One thing is new since video was
offered (first seen 2026-11-21): at each session's start the phone sends a 200 B `'sync'` on the
SenderSettingsData DataStream that expects a `'rply'`, and carplayd logs `NOT IMPLEMENTED` and sends none
(`05_METADATA_AND_CONTROLS.md` §5 item 1 holds why; enigy's adapter does the same, and its bench video
played). What the `modesChanged` and the `'sync'` carry is not logged.

**The phone's own reason (bench, 2026-11-22, the owner's iPhone on iOS 27.2.1, `ocbm-host` as the host, the
iPhone's log over USB searched for named terms only).** With "may play" at RECORD the phone logged `CarPlay
videoPlayback supported: yes`, opened `CarPlayVideoSettings` (the SenderSettingsData stream, `err = noErr`) and
`videoPlayback now changed to allowed`, then `carEndpoint_checkAndUpdateVideoOverlayActivation: false
condition` and **`CarPlay Video Overlay NOT supported on Bonjour discovery based accessories`**. Safari saw
`Playback Target Supports AirPlay Video = false`. The phone showed "Video will play on iPhone / Show Video in
Car"; the owner's tap (`showCarPlayVideoActiveBanner: user response: 2`) changed nothing, and no UrlFling
stream was set up. The phone had found the adapter by Bonjour on its access point (`StartBonjourForWiFi …
IsCarPlay: Y`). So route A needs the simplified connection flow, the adapter's address sent over iAP2 instead
of Bonjour (`docs/wireless/00_WIRELESS_CARPLAY.md` §2.3, `02_SESSION_LIFECYCLE.md`, WWDC 2023-10150). How
enigy's bench (iOS 27.2) played without it is not known. Not started: it touches the wireless handoff, whose
Bluetooth-time Identify is byte-pinned (CLAUDE.md), so it needs its own plan and the owner's go-ahead.

**On hold (owner, 2026-11-22: "I guess that is fine. The car has a browser that can be used in park").** No
work on the simplified connection flow for video. The offer stays as built.

## T35. Which iPhone connects first, by schedule (raised 2026-11-22; BUILT 2026-11-22, Play 268; morning side WORKS on the car 2026-11-23)

**Why.** Two people drive the car with their own iPhones, both bonded. Owner, 2026-11-22: "I want my phone to
be first priority all workdays until 6 and then weekends and evenings my wife's phone is first priority."
T12's list order is fixed until someone changes it, and a phone first in the order but not in the car costs a
page timeout at every connect (T25: about 6 s), while with both in the car the first one gets CarPlay.

**Why the adapter had to change.** btd reads the order once, when it starts (`control::Control::new`,
`load_policy`), and it starts on the session's host-present edge, right after the app's `CT_SUBSCRIBE`.
`MGMT_SET_ORDER` reached only a running btd (its control port), so an order sent at connect time missed that
start's first page. Now, with no btd to answer, ocbmd writes the order into btd's policy file itself
(`store_order`: btd's layout, `autoConnect` kept as btd reads it, written only when it changes, atomically),
and `MGMT_INFO` says `"order_at_start":true` (`docs/carplay/01_OCBM_PROTOCOL.md`).

**Built 2026-11-22 (owner: "Yes and yes" to the schedule and the shorter page wait).**
- *App.* `PhoneSchedule.kt`: each phone may have one window (days, from, to; past midnight belongs to the day
  it starts; equal times are the whole day). The order for a moment is the phones whose window covers it, then
  the rest, each in the driver's list order (`orderNow`, pure). The list (↑/↓) is now kept by the app
  (`phone_schedule/base_order`), since the adapter's stored order becomes the last connect's; the first window
  saved fixes it from the adapter's order. At every connect, before `CT_SUBSCRIBE`, the app sends the order for
  that moment when it differs from the adapter's (`atConnect`; logs list positions and how many windows cover
  now, never a name or address). Settings ▸ CarPlay ▸ Phones: a Schedule button per phone (seven day buttons,
  From and To with the system time picker, Clear), each row says its window, and "connects first now" follows
  the schedule. Shown only with firmware that says `order_at_start`. Nothing switches mid-drive.
- *Adapter.* ocbmd as above. btd sets a 3.2 s page timeout at every Bluetooth bring-up
  (`hci::PAGE_TIMEOUT_SLOTS`, raw Write_Page_Timeout), from the controller's 5.12 s: in 17 car connects
  (2026-11-21 and 22) a present iPhone answered 0.4 to 2.2 s after the page began, and an absent one cost the
  whole 5.12 s before the next phone was called.
- *Verified.* `cargo test` (ocbmd: the order kept with no btd, `autoConnect` kept, no rewrite of the same
  order; bt-common: the parameter and opcode), `tools/run_tests.sh`; the app's unit tests with the owner's
  example (the owner's phone Monday 7:30 and Friday 17:59, the wife's Friday 18:00, Saturday and Sunday), windows past
  midnight and across Sunday into Monday, overlaps, the list against changed bonds, the row's words.
- *On the car, 2026-11-23 (Play 268, firmware ee0e29addd5c, the log uploaded 13:17 UTC).* The owner updated
  the adapter from the app at 12:02 UTC (confirmed 12:03, health 0x71) and set both phones' schedules
  (12:06, 12:09). `[btd] page timeout 3200 ms` at every bring-up. With the absent phone still first (12:03),
  its `CONNECT_FAILED … 0x04` came 3.2 s after `page_start` and the present phone's ACL 1.0 s later: page to
  ACL 4.1 s, against 8.8 s on the old firmware at 11:59. After the schedules, a Monday-morning connect said
  `phones: list phone 1 first, as the adapter has it (1 of 2 schedule(s) cover now)`, nothing was refused,
  and the present phone answered 0.64 s (12:10, a restart the owner asked for) and 0.76 s (13:10, a cold
  start) after `page_start`. Attach to first picture: 21.4 s at 11:59 (old firmware), 10.1 s at 12:10, and
  16.1 s at 13:10, of which 4.4 s was the Allow tap. The owner set both schedules with the dialog on the car.
- *Not verified.* The evening and weekend side: the app sending a changed order before Bluetooth starts
  (`phones: list phone N first for this start … the adapter took it` and, in the box log, `phone order kept
  for Bluetooth's next start`), then `[reconnect]` calling the wife's phone first.

**Done means.** On a weekday morning the owner's phone connects first with both in the car, and on an evening
or a weekend their wife's does; with only one phone in the car, it connects about 3 s after the other's page
starts instead of 5.

## The 2026-11-26 feature check (T36 to T39)

Owner, 2026-11-26: "lets think of some new features to put in the backlog. 1 that I have seen was an
equalizer. Use the internet to do a feature check of other CarPlay apps." What the others offer,
against this app:

| Feature | Seen in | Here |
|---|---|---|
| Equalizer, sound settings (graphic EQ, time alignment, loudness) | every aftermarket head unit; a separate EQ app on ZLink-style units | no: T36, T37 |
| Turn alert (the cluster arrow flashes before the turn) | enigy/carlink | no: T38 |
| Settings per iPhone | enigy/carlink | the order and schedule only (T12, T35): T39 |
| "A phone was plugged in: switch, or stay?" | mossyhub/openautolink | no; wireless only here, so little use |
| Pinch-to-zoom | openautolink | yes: `touchScreenSupportsMultiTouch` is declared |
| Netflix and YouTube on the screen, 4K | Carlinkit and Ottocast AI boxes | no, on purpose (Left off) |
| Spatial Audio | Pioneer, CES 2026 | no (Left off) |
| Dashcam | aftermarket head units | no (Left off) |

Why sound first: Electrek (2026-05) calls the sound system arguably the biggest complaint among Equinox EV
owners, and Bose is an extra-cost package. This app decodes CarPlay's audio and plays it itself, so it can
shape the sound before the car gets it; the car's own controls cannot do that per source. Sources:
electrek.co 2026-05-20 and 2026-05-29, insideevs.com "Equinox EV missing key features", macrumors.com
2026-01-05 (Pioneer), github.com/enigy/carlink, github.com/mossyhub/openautolink.

## T36. Loudness by speed (raised 2026-11-26)

**Why.** At speed an EV's cabin is tyre and wind noise, which buries the bass and the treble before the
middle; the driver turns the volume up and down instead. The app reads the car's speed already.

**What.** Lift the low and high ends of CarPlay's media audio as speed rises, a little and smoothly,
instead of raising the volume. Media only, not calls or Siri or navigation prompts. One setting: off,
gentle, strong. **Done means.** On a drive the log shows the lift following the speed, and the owner
hears the music hold up on the highway without reaching for the volume.

**Built 2026-11-26, app only** (`host/gm_ccpa/docs/13_AUDIO_ROUTING.md`, "Media shaping"): the app's own
shelves on the media PCM, not `android.media.audiofx`, so it does not depend on GM's audio HAL. Nothing
to 40 km/h, full lift from 110 km/h, Gentle +4 / +2.5 dB, Strong +7 / +4 dB, at most 0.5 dB a second.
The log says `loudness by speed (<level>): <km/h>, bass +x dB, treble +y dB` at each 1 dB of movement.
*Verified:* `ToneShaperTest` (sine waves: the shelves' gains within 0.5 dB, untouched bytes with no lift,
no wrap at full scale), `SpeedLoudnessTest`, the app's 641 tests. *Not yet:* heard anywhere: the bench
plays no CarPlay audio aloud, so the car is the first listen. The CPU cost (two biquads a sample, 96,000
samples a second) is unmeasured on the head unit.

## T37. Equalizer (raised 2026-11-26)

**What.** Presets rather than sliders to fiddle with while driving: "this car's speakers", voice
(podcasts, calls), music; a custom one set while parked. Built on T36's processing. A different preset per
phone falls out of T39. **Done means.** The owner picks a preset once and the car sounds better to them.

**Built 2026-11-26, app only** (`host/gm_ccpa/docs/13_AUDIO_ROUTING.md`, "The equalizer";
`SETTINGS_AUDIT.md` 2026-11-26): ten octave bands, ±12 dB; Flat (default), Bass, Vocal, Treble, Lively and
Custom; the bands dragged in Park only. *Verified:* `EqualizerTest` (the bands' response with sine waves,
the presets, the stored curve, the editor's finger-to-gain mapping), the app's 648 tests, and on the AAOS
emulator the Sound section drawing a stored Custom curve (+3, −1.5, +2.5, −12, +12, +0.5 in their bands).
*Not verified:* a drag on the emulator (its input injection aborts there, `04_OPEN_ITEMS.md`), and any
listening.

## T38. Turn alert on the driver display (raised 2026-11-26)

**What.** enigy/carlink's idea: the turn card on the driver display pulses or flashes in the last seconds
before a turn. Builds on `nav/TurnCard`. **Done means.** The owner notices the turn without reading the
distance.

## T39. Settings per iPhone (raised 2026-11-26)

**What.** The media pane, the layout and the sound preset follow whichever phone connects (the owner's or
their wife's), from the phone identity the adapter already reports. **Done means.** Each driver's car is
set up for them without touching Settings.

**Built 2026-11-26, app only** (`PhoneProfiles.kt`; owner: "Do T39 and t33 now"). Follows the phone: the
equalizer's preset and curve, loudness by speed, the media pane (side, size, shown) and the screen layout.
No setting is hooked: each stays where it lives, and when a different phone takes over the values in place
are kept as the previous phone's and the new phone's are put in place; a phone's first visit keeps the
values as they are. It runs before `CT_SUBSCRIBE` for the phone the adapter will page first
(`PhoneSchedule.expectedFirst`: T35's order where the app sends one, else the adapter's own), so the
layout and the pane's side the subscribe declares are that phone's; and at `CT_PHONE_IDENT` for the phone
that came, where the other phone's sound follows at once and its layout and pane's side from its next
connect, not a restart of a working session. Forget a phone and its settings go. The log says `phone
settings (expected first|connected): phone N of M drives — …`, no name or address. *Verified:*
`PhoneProfilesTest` (the rule, a week of two drivers, the stored form), the app's 653 tests. *Not yet:*
the car, and the emulator (the adapter and phone had left the bench).

## Left off this plan

Kept here so a later session does not promote them into tasks.

- **From the 2026-11-26 feature check.** Video streaming on the screen (the AI boxes' headline feature:
  parity, parked-only, and blocked here anyway, T34); Spatial Audio (needs speakers the car does not
  have); a dashcam (no camera access for an ordinary app).
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
- **enigy/carlink features that cannot show on this cluster (checked 2026-11-07).** Junction-shaped
  turn icons, lane guidance (both the lane strip image and asking the iPhone for lanes, which needs the
  `extended` tier) and the flashing turn alert all work by sending icons, which the Equinox's VCU
  cluster ignores (`docs/host/02_GM_AAOS_FIELD_REFERENCE.md`). The exit label was taken instead.
  (2026-11-10: lane guidance did arrive later by another route, drawn by the app on its own
  driver-display window rather than in GM's cluster; see T13.)
- **For other owners or other cars (owner, 2026-11-14: "this is just for me").** A first-run setup
  pass (pairing, screen outline and adapter update in one guided flow), display profiles for other GM
  EVs (Blazer EV, Silverado EV), and a layout per iPhone. Raised in a brainstorm and declined: one
  owner, one phone, one Equinox.
- **enigy's per-iPhone settings, adapter updates over OCBM, stock conversion.** Not asked for; per-phone
  settings matter little with this app's few per-phone choices, and the adapter is updated on the server.
- **New enigy commits are reviewed weekly (from 2026-11-09).** A scheduled check on the owner's Claude
  account, run Mondays on the laptop's `ocbm` bridge, reads enigy/ocbm after `317b3eb` and enigy/carlink
  after `0766d12` (both reviewed 2026-11-07) and reports each change as port, maybe, skip or already
  have. It changes nothing; what is taken lands in `04_OPEN_ITEMS.md` like the 2026-11-07 ports.
- **Wrong panel.** Knob, d-pad, and touchpad descriptors are for other head units. The Equinox is
  a touchscreen.
- **No surface yet — superseded 2026-11-08.** The app no longer waits for GM to hand it a cluster
  surface: it opens its own window on the driver display when Android shows that display to it
  (openautolink's route; Settings ▸ Navigation ▸ CarPlay map on the driver display,
  `docs/ops/04_OPEN_ITEMS.md`).

