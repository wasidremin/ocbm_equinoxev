# Future tasks — planned work that is not a defect

> **STATUS:** CURRENT · single owner for planned, owner-directed work. Created 2026-09-04. Defects and
> verification gaps stay in `04_OPEN_ITEMS.md`; a task that lands is replaced here by a pointer.

Owner-directed work items that are neither an open defect nor a verification gap (those live in
[`04_OPEN_ITEMS.md`](04_OPEN_ITEMS.md)). One entry per task, dated when raised, with the current
state it starts from and what "done" means. When a task lands, replace its body with a one-line
pointer to the doc that now owns the result — do not leave a stale plan beside a shipped feature.

**Where each task stands (2026-11-13),** by what it takes to ship. "App only" is a Play update;
"needs adapter firmware" also needs a bench reflash of the adapter (`tools/ocbm_install.sh --full`)
until T20 exists.

*App only*

| Task | State |
|---|---|
| T16 Battery health | New, not started |
| T17 Arrival estimate by road type | New; waits for one more drive's `gap=` lines |
| T18 "Will I make it" at route start | New, not started |
| T19 Spotify hand-back | New, not started |
| T23 Apple's own turn card on the driver display | Step 1 built 2026-11-13: a "Driver display shows" setting (Map, Turn card, Apple's view) using commands the adapter already had. Needs one drive to see what Apple draws. Map and card together would need a second stream and the adapter |
| Log redaction lets a second Bluetooth address through | A defect, so it lives in `04_OPEN_ITEMS.md`; tiny |

*Needs adapter firmware*

| Task | State |
|---|---|
| T6 Car GPS into Maps | Built 2026-11-13 on T7's pattern, off by default. Waits for the adapter reflash; not yet tried on a phone |
| T7 EV status for Apple Maps | Built and accepted by the phone; Maps shows nothing because it wants the two battery models. Their format and a working example on Hyundai head units were found 2026-11-13, and the fix was built that day. It needs the adapter reflashed, an app update, and the iPhone paired again (see T7) |
| T8 MainBuffered audio, phase B | Open, not started |
| T9 UI context | Open, not started, and **probably cannot do what it was raised for** (see T9) |
| T10 Focus transfer | Open, not started |
| T11 Enhanced Siri | Open, not started; needs T8 first |
| T12 Choose which iPhone connects | Built and on the car since 2026-11-11; not yet used |
| T15 No Allow tap | The USB bridge is built but **blocked** (the Equinox cannot install a sideloaded app). A second route, the adapter link over the car's hotspot, is recorded in T15 (2026-11-13) and not started; its first step is app only |
| T20 Update the adapter from the app | New; would remove the bench reflash from every task in this table |
| T21 The car's name in Apple Maps | New (openautolink; held since 2026-11-08) |
| T22 CarPlay's status bar on the driver's side | New (openautolink; held since 2026-11-08) |

*Done*

| Task | Where it went |
|---|---|
| T13 Cluster turn-by-turn | `host/gm_ccpa/docs/14_LESSONS_LEARNED.md` §16 |
| T14 Voice button starts Siri | `docs/host/01_ANDROID_AND_AAOS.md` |

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

## T6. Car GPS into Maps — iAP2 LocationInformation (raised 2026-09-22; BUILT 2026-11-13, waiting for an adapter reflash; not yet tried on a phone)

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

**Done means.** With the setting on, the Identify is accepted with param 22, the phone sends
`0xFFFA`, the adapter's log counts `0xFFFB` updates with no coordinates in it, and Maps holds its
position through a place where the phone alone loses it.

## T7. EV vehicle status — range and charge (raised 2026-09-22; BUILT 2026-10-27; Identify and status ACCEPTED by the phone on the car 2026-10-28; why Maps shows nothing FOUND 2026-11-13; the fix BUILT the same day and waiting for an adapter reflash)

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

## T13. GM app: CarPlay turn-by-turn in the instrument cluster — DONE (works on the car since 2026-10-27)

Moved to `host/gm_ccpa/docs/14_LESSONS_LEARNED.md` §16: the Car App Library route, the album art in
the cluster, the car results and the cases of missing art. The driver-display route that followed
(CarPlay's own cluster map with the app's cards, Play 236 onward) is `04_OPEN_ITEMS.md` "CarPlay map
on the driver display". Still open from this task: `0x5200` selectors 4–5, so the time zone and EV
fields of route guidance arrive (adapter firmware; `docs/carplay/03_SDK_GROUND_TRUTH.md` selector gap).

## T14. GM app: the steering-wheel voice button starts Siri — DONE (works on the car since 2026-10-27, versionCode 221)

Moved to `docs/host/01_ANDROID_AND_AAOS.md` "The steering-wheel voice button starts Siri". Still
open from it, tracked in `04_OPEN_ITEMS.md`: whether calls echo for the far end while the app holds
the assistant role, and the voice button answering a ringing call (built in 233, never yet seen in a
car log).

## T15. GM app: no Allow tap for the adapter (raised 2026-11-06; the sideloaded USB bridge is BUILT and BLOCKED on the stock Equinox; a second route over the car's hotspot recorded 2026-11-13, not started)

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

### Second route: the adapter link over the car's hotspot, with no USB claim (raised 2026-11-13; recorded, not started)

The owner asked for this to be recorded and not built (2026-11-13). Everything below is what was
known that day.

**The idea.** From metheos on the XDA thread. The adapter joins the car's hotspot as a client and
keeps its own access point for the phone, both on one channel. The app reaches the adapter over the
network and never asks for the USB device, so there is no Allow dialog; USB only powers the
adapter. His proof of concept for stock firmware is
[configure_sta_uplink.sh](https://github.com/metheos/CPC200-CCPA-Firmware-Dump/blob/96caaa1359b5499c9334fa82200f244cc9d08a4c/configure_sta_uplink.sh)
(MIT). It scans for the car's network, moves the adapter's access point to that channel, joins, and
takes a DHCP lease. He saw both links up together and never measured throughput. The script cannot
be dropped in (item 1 below).

**What this tree's own logs already settle.**

- The Equinox hosts its hotspot on the head unit (`ap_br_swlan0`, members `swlan0`/`swlan1`), so the
  app sits on the hotspot's host side. `CarPlayRx.hotspotInterface()` and `hotspotIpv4()` still
  find it.
- The app can open TCP connections to a hotspot client. In September 2026 (from 2026-09-21) it made
  20+ `GET /ctrl-int/1/connect` → `200 OK` calls to the phone, on IPv4 and on IPv6 link-local.
  Connections from a client *into* the app are dropped by GM's `netfilterd`. That ended the design
  where the phone joined the car's hotspot (`host/gm_ccpa/docs/14_LESSONS_LEARNED.md`, versionCodes
  21–32; `host/gm_ccpa/docs/01_FINDINGS.md` §2). On this route the app dials out, so that wall is
  not in the way. Not shown: a sustained stream at video rates on such a connection.
- The adapter in the car has the radio the script targets: `RADIO_CHIP=nxp_iw416` (SDIO
  `0x02df:0x9159`), `moal` loaded with `nxp/wifi_mod_para.conf`.
- The app's link sits behind `ocbm/Transport.kt` `RawBulkTransport` (four methods;
  `UsbBulkTransport` and the bridge relay implement it), so a network transport slots in beside
  USB.
- The app still has the car hotspot's name, passphrase and channel from the hotspot design
  (Settings ▸ Advanced ▸ Vehicle hotspot, prefs `hotspot_credentials`). They can tell the adapter
  which network to join.

**What it does not settle.**

1. *Interface roles.* Stock firmware runs the access point through `wpa_supplicant` (as a P2P group
   owner) on `wlan0` and leaves `sta0` for the client link. This tree runs `hostapd` on `sta0`
   (`[radio] hostapd starting on sta0` at every boot in the car logs), which leaves `wlan0`.
   Whether `moal` accepts a client link on `wlan0` beside an access point on `sta0` is the first
   bench question. Whatever is built goes through the radio seam (`radio_hal.sh`), names no chip,
   and reports `unsupported` where it is not proven (`docs/wireless/01_BT_AND_RADIO.md`).
2. *Throughput and delay.* The IW416 is single-stream 802.11n, and every byte crosses the same
   channel twice (phone → adapter, adapter → car). An estimate, not a measurement: 20–45 Mbps usable
   for video, against CarPlay's 8–30 Mbps (`docs/carplay/00_ARCHITECTURE.md`) plus the
   driver-display stream. USB measured 339 Mbps adapter → host. This is the risk that could end the
   route.
3. *Channel.* The adapter's access point must sit on the car's channel. It is on 149 today. The
   car's channel has never been measured (the "36" in the September logs is what the app sent, not a
   reading), and it may move when the car's own Wi-Fi client joins home Wi-Fi or a phone hotspot.
   The adapter's `hostapd` has no automatic channel selection (`radio_ap_up.sh`), and a channel
   move drops the phone for seconds.
4. *The car's hotspot.* It must be on at every start. How soon after the car wakes it comes up is
   unknown, and so is whether it stays on without a data plan (asked of the owner 2026-11-13, not
   yet answered). The logs last show it on 2026-09-27, in the last build that looked.
5. *The adapter's side of the link.* `ocbmd` owns `/dev/usb_accessory` and nothing else
   (`ccpa/ocbmd/src/main.rs`, `ACC_DEV`). It needs a TCP listener on the client interface, a way
   for the app to find it on the hotspot's subnet, and a key shared once over USB, because this
   link carries the video keys and any hotspot client could otherwise connect.
6. *Time to CarPlay.* Today the median is 10.7 s from the app starting the adapter to the first
   picture, and that clock starts after the Allow tap (3.4 s in the one logged case, 2026-11-05).
   This route trades the tap for the adapter's scan, join and lease.

**Plan when resumed, with a stop at each step.**

1. *App only.* Log at every start whether the hotspot interface is up, how long after the car woke,
   and how long the Allow tap took. Stop if the hotspot is not dependably up.
2. *Bench, adapter out of the car.* Bring up the client link beside the access point, with home
   Wi-Fi standing in for the car's hotspot, and push 30 Mbps through both hops with a phone
   connected. Stop if it stalls.
3. *Car.* A network transport in the app with USB kept as the fallback, one-time setup over USB,
   then a week compared with today's time to CarPlay and stall count.

**Ruled out.** The reverse arrangement, where the car joins the adapter's network. The car has one
Wi-Fi client link (`wlan0`), which would be taken from home Wi-Fi and the phone hotspot that
uploads use, and Android does not dependably stay on a network with no internet.

**Done means (this route).** A drive starts CarPlay with no Allow tap and no USB claim, the time to
the first picture is no worse than today's including the tap, and video stalls are no more frequent
than over USB.

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

## T20. Update the adapter from the app (raised 2026-11-12)

**Why.** Every task in the "needs adapter firmware" table ends with a bench reflash. enigy's
fork of this project (github.com/enigy/ocbm) updates the adapter over the USB link instead; the
2026-11-07 review left it out as "not asked for", and with this many adapter tasks it now pays.

**State.** The pieces exist: OCBM pushes files to the box (`CH_FILE`, used for `/tmp/vehicle_status`),
and `tools/ocbm_install.sh`'s `manifest()` is the authoritative file set. The signed boot stack is
never touched, and `docs/ops/01_RECOVERY.md` covers a wedged box.

**Work.** A firmware bundle (the manifest's files and a SHA-256 of each) built with the app or
fetched from the owner's server; the app pushes it while parked and CarPlay is idle; the box checks
every hash, keeps the previous set, swaps, and rolls back by itself if its health does not come back
within a time limit. Settings ▸ Advanced shows the adapter's version and offers the update.

**Done means.** From Settings, with the car parked, the adapter updates and reconnects; a bundle with
one bad hash is refused with nothing changed; and a forced failed start rolls back to the previous
set.

## T21. The car's name in Apple Maps (raised 2026-11-12; openautolink, held since 2026-11-08)

**Why.** Apple Maps names the car after the adapter. openautolink sends the car's make, model and
year instead.

**State.** Identify param 20 subs 1 and 6 are the box's constant `name` (`message.rs` ~:695), and
sub 8, `MapsDisplayName`, is not emitted at all. T7's fix needs sub 8 as well (the Hyundai project
that got EV routing working sends the car's name in subs 6 and 8), so build the two together. The
app's `accessoryName` bound (C-6) is not wired into the tunnel Identify,
where a rejected change costs the whole session's metadata (`0x1D03`) until the setting is off, the
same risk T7 took and passed. `04_OPEN_ITEMS.md` "Held for adapter firmware".

**Done means.** With the setting on, Maps shows "Chevrolet Equinox EV" (or the name the app pushes),
and the Identify is accepted with no `0x1D03`.

## T22. CarPlay's status bar on the driver's side (raised 2026-11-12; openautolink, held since 2026-11-08)

**Why.** CarPlay can put its status bar (clock, signal, the app strip) on the edge nearest the
driver. openautolink offers Auto, Bottom or Driver side.

**State.** `info.rs::view_areas` hardcodes `viewAreaStatusBarEdge: 0` on both main view areas; it
needs a parsed `statusBarEdge` on `ViewAreaEntry` and an app setting (Settings ▸ Screen & Sound).
`04_OPEN_ITEMS.md` "Held for adapter firmware".

**Done means.** Each choice in the setting moves CarPlay's status bar at the next connect, and the
media pane and view-area switching still work.

## T23. Apple's own turn card on the driver display (raised 2026-11-12; step 1 BUILT 2026-11-13, app only; step 2 built and waiting for the reflash)

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

**To see on the car.** What the turn card and Apple's view each look like at 2000×810 during a route (the
"Capture driver display" button saves it), and whether the log shows
`cluster epoch open … Apple's turn card requested sent=true`.

**Risks.** Step 1 has little: these are commands the adapter already implements, and Map stays the
default. The card may be drawn for a small cluster and look lost at 2000×810; the bare cluster view
may be the same picture as the map. Step 3's risks are in the doc cited above.

**Done means.** On the car, Apple's instruction card shows on the driver display during a route,
beside or instead of the map as the owner chooses, and the app's own card steps aside while it does.

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
- **enigy/carlink features that cannot show on this cluster (checked 2026-11-07).** Junction-shaped
  turn icons, lane guidance (both the lane strip image and asking the iPhone for lanes, which needs the
  `extended` tier) and the flashing turn alert all work by sending icons, which the Equinox's VCU
  cluster ignores (`docs/host/02_GM_AAOS_FIELD_REFERENCE.md`). The exit label was taken instead.
  (2026-11-10: lane guidance did arrive later by another route, drawn by the app on its own
  driver-display window rather than in GM's cluster; see T13.)
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

