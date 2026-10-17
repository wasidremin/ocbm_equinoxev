# EV telemetry parity and data audit

Status: source/code audit, 2026-10-11. **Feature parity is NOT achieved.**
No physical-car capture was available for this audit. Competitor support for a
signal does not establish availability on the Equinox, this AAOS build or our UID.
Marketing claims below are documented product claims, not independently tested.

## Official sources reviewed

- [TeslaMate repository](https://github.com/teslamate-org/teslamate): bundled
  drive/charge details, net/gross efficiency, battery health, projected range,
  temperatures, states, vampire drain, visited map, geofences, costs, multiple
  vehicles, imports and MQTT integrations.
- [TeslaMate integrations](https://docs.teslamate.org/docs/projects/): custom
  charging curves, smart tariffs and ABRP integration are **add-ons**, not all
  bundled TeslaMate functionality.
- [Tessie charging](https://help.tessie.com/article/143-charge-tracking): unattended
  cloud charge logging; charge/cost CSV import/export.
- [Tessie battery health](https://help.tessie.com/article/39-battery-health): BMS
  range/capacity trends after charges over 5 kWh, charge-cycle tracking, configurable
  original capacity/battery replacement date. This is not a controlled capacity test.
- [TRONITY overview](https://www.tronity.io/cockpit-en/understanding-electric-vehicle-data-insights-from-tronity):
  trip logging, battery health, energy/cost/efficiency, carbon footprint, comparative
  recommendations through manufacturer integrations. Equinox support is not established.
- [ABRP OBD guide](https://abetterrouteplanner.com/resources/article/2024-10-10_obd-connection):
  supported-model BLE live SOC, adaptive charging stops, consumption calibration;
  premium traffic/weather/charger-availability forecasts. Warns about insecure dongles.
- [Car Scanner recording](https://www.carscanner.info/records/): screen-selected
  numeric signals/dependencies, linked/separate charts, CSV/BRC export, BRC import.
  It does not promise universal continuous recording of every ECU signal.
- [Tesla telemetry catalog](https://developer.tesla.com/docs/fleet-api/fleet-telemetry/available-data):
  illustrates SOC, pack energy, AC input versus battery-side energy, charge limits,
  temperatures, cell-voltage extrema, climate, driving, location and vehicle-state
  data. Tesla names/permissions are NOT GM PID definitions.
- [Android property manager](https://developer.android.com/reference/android/car/hardware/property/CarPropertyManager):
  property list is limited to properties the application may access.
- [Android property contracts](https://developer.android.com/reference/android/car/VehiclePropertyIds),
  [gear enums](https://developer.android.com/reference/android/car/VehicleGear):
  SI conversions/areas/configuration and public enum meanings. Current online
  catalogs may contain properties absent from API 35 or the vehicle's older build.

## Feature parity matrix

Implemented means code + synthetic verification, not actual vehicle access.

| Feature family / reference | evinsights status | Missing acceptance requirement |
|---|---|---|
| Drive/charge history, energy, efficiency, temperature (TeslaMate/TRONITY) | Partial: derivation, details, charts, SI exports | Actual-car validation; continuous collection and delivery |
| 24/7 unattended logging (Tessie/TeslaMate/TRONITY) | Missing: manually started, resource/lifecycle-bounded probe; manual import | Opt-in lifecycle-safe capture; durable queue; gzip uploader; retries/dedup; charging while projection absent; no battery-drain regression |
| SOC/charging curves (ABRP/TeslaMate) | Partial: battery energy and power curves | Distinguish displayed SOC, BMS SOC and energy/capacity estimate; plot SOC versus power/time; no fabricated capacity |
| Costs, geofences, tariffs (TeslaMate/TRONITY/Tessie) | Missing; placeholder tables only | Local fixed/time-of-use tariffs, DST-aware timezone, user charge edits, currency, wall/battery energy distinction, CSV export |
| Battery health/cycles/degradation (Tessie/TeslaMate) | Missing | Measured usable capacity/SOH or explicitly labeled estimate; baseline provenance, replacement date, uncertainty, cycles from observed energy only |
| Parked drain and preconditioning (TeslaMate) | Missing metrics | Stationary/noncharging evidence, contiguous pack energy, cabin versus battery heat signals; no attribution from HVAC alone |
| Route/visited map, automatic places (TeslaMate) | Partial: local route map; explicit route IDs | Geometric route matching, local geofences, lifetime map, speed/energy coloring; address lookup opt-in |
| Adaptive routing/weather/charger forecasts (ABRP) | Missing; different product scope | Owner decision on navigation scope, map/provider licensing and consent before external coordinate transfer |
| Logbook annotations/business reimbursement (TRONITY/Tessie) | Partial: trip CSV | Editable tags/notes, split/merge audit, charge CSV/import and reimbursement summaries |
| OBD live diagnostics / cell data (Car Scanner) | Missing | Approved read-only adapter/GM profile, documented PIDs, polling/resource budget; no vehicle writes or clearing DTCs |
| Live dashboards/in-car summary (ABRP/TeslaMate) | Partial: server summary; no car consumer | Staleness/coverage visible, distraction-safe consumer, offline behavior |
| Automation/notifications/MQTT (TeslaMate/Tessie) | Missing | Local read-only events and notifications; remote control is not this project's telemetry objective |
| Fleet/multiple cars | Partial: per-vehicle keys/backend; UI opens one vehicle | Vehicle picker, scoped accounts, aggregate views without leaking other cars |
| Export/backup/data quality | Implemented core raw/trip export, immutable store, reasons, restore check | Bounded full raw export, charging export, scheduled verified backups, retention/privacy controls |

## Collection inventory and actual availability gates

The probe enumerates **all returned configurations**, reads each area once, and
attempts callbacks for all read-accessible changing properties, including vendor
IDs. Headline lists are not subscription whitelists. Continuous subscription
requests target 1 Hz and refuse requirements above 5 Hz; refused registrations,
invalid rates, permission failures and unavailable statuses must remain visible.
ON_CHANGE silence is not proof of failure. Static reads are snapshots, not live
signals. Capture stops at 50 MiB events, low disk, disconnect or service teardown.
No automatic app-to-server upload exists. No sleeping-car coverage is proven.

| Data family | Existing collection/ingest | Evidence needed / outstanding work |
|---|---|---|
| Make/model/year, connector types, nominal capacity, dimensions | Census report static initial reads; bridge repair imports snapshots | Keep census receipt time separate from sensor time; identity is sensitive |
| Speed/display speed, odometer, wheel counters, gear/ignition/brake | Dynamic properties if granted; scalar/area/vector raw preserved | API enum normalization; supported-wheel mask and micrometers/tick; actual reset/scaling validation |
| Battery energy/power/range, port state | Dynamic if granted; Wh→J, mW→W, positive into pack | Modern usable capacity, displayed SOC, charge limits/state/remaining time may be absent or privileged |
| HVAC/ambient/cabin temperatures, fan/seat/defrost | All visible changing properties, not a climate-control feature | Permissions may exclude HVAC; area-specific metrics and thermal attribution remain incomplete |
| Tire pressure, steering/dynamics | Raw if exposed/granted; pressure kPa→Pa | Area-aware UI and documented units for each added standard signal |
| Location/speed | Explicit GPS opt-in only | Separate valid position from missing speed; preserve provider, location time, accuracy; altitude/bearing not currently exported by Android probe |
| Pack current/voltage/temp, cell extrema, SOH, cycle counters | Unknown vendor raw accepted if visible; not claimed decoded | OEM permission or approved OBD profile; cannot invent PID maps or infer SOH from nominal capacity |
| AC wall energy, DC battery energy, charger phases/voltage/current | No meter integration; raw unknown signals possible | Authorized EVSE/manual meter; provenance and separate measurement boundary |
| Charging site/tariff/weather/elevation/traffic | GPS altitude only if supplied; no external enrichment | Local user entries or consented services; enrichment must be labeled, never measured |
| UX, subscription failures/provider transitions, build identity | Probe reports/events; bridge repair retains diagnostics | Not motion evidence; availability report must distinguish visible/readable/subscribed/observed |

## Repair scope and no-loss rules

Repair the **existing** report importer, not declare an uploader finished:
known typed-unit normalization; API-35 gear/ignition enum decoding with raw value
preserved; wheel mask/scales conversion; actual `fingerprint` field; static initial
read snapshots when a report receipt timestamp exists; unknown diagnostic kinds;
valid GPS position even when speed is absent; original event/config metadata.
Importer output UUIDs use a versioned namespace. Reimporting previously imported
captures using the new namespace is NOT an automatic migration: it would create
new rows. Use a new capture/vehicle for validation and keep old immutable rows.

Do not auto-enable GPS, expand permissions, collect VIN/media/occupant data, poll
sleeping ECUs or call cloud providers simply to match a competitor. Preserve all
*authorized returned observations*, then explicitly select privacy-sensitive
production signals and retention policy with the owner.

## Prioritized implementation / parity sign-off

1. **P0: collection correctness** — validate repaired report CLI against schema
   and ingest/raw API; every input kind accounted for; raw values/config preserved.
2. **P0: actual-car capability evidence** — owner-provided opted-in report for
   parked, driving, short stop and charge. Record build/UID/permissions/property
   area, subscription outcome, observed cadence/status, supported scale and gaps.
   Unknown/denied/absent must never be marked collected.
3. **P1: durable opt-in uploader** — offline/restart queue, bounded resources,
   gzip chunking, acknowledgement/retry/key rotation, timezone/boot identity;
   physical-car battery/resource and charging lifecycle tests.
4. **P1: charging parity** — charge export, SOC provenance/curves, locally edited
   tariffs/sites and time-of-use costs, measured wall energy when supplied.
5. **P1: analysis parity** — local places/routes/lifetime map, logbook edits and
   parked drain; history coverage and metric uncertainty shown prominently.
6. **P2: battery/OBD parity** — only with documented accessible sensors and approved
   hardware; distinguish BMS reports from estimates and controlled capacity tests.
7. **P2: navigation/remote automation** — explicit scope decision, not silently
   bundled with the telemetry workbench.

## Verification of this audit's repairs

42 final-source backend tests passed, including subprocess CLI conversion and
immutable ingest/raw API equality. The rebuilt running-image CLI validated all
11 synthetic report observations (10 live events plus one census snapshot).
Unfiltered and filtered raw reads passed through Caddy HTTP. Production browser
smoke passed; API/worker are healthy and API importer/query hashes match source.
Existing demo summary remains 60 trips/60 charging sessions. Documentation links
and whitespace checks passed. A third-party AnyIO deprecation and Compose's
missing optional buildx warning remain. Android was not changed/released, remote
CI was not run, and no real-car collection or competitor app was physically tested.

No overall percentage is assigned: features differ by vehicle, tier and platform.
Parity sign-off requires acceptance tests for each chosen row and real-car source
availability, not an empty table, advertised property name or successful build.
