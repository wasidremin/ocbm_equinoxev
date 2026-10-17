# evinsights

Self-hosted EV telemetry evidence workbench. This is an isolated subproject; no
Android projection or USB code is changed. Python 3.12 / FastAPI / SQLAlchemy 2 /
PostgreSQL 16 + TimescaleDB / React + TypeScript + ECharts + Leaflet.

## Start

```sh
cd host/evinsights
cp .env.example .env
# Replace DB_PASSWORD, KEY_PEPPER, ADMIN_TOKEN with independent random hex
# (e.g. openssl rand -hex 32). DB_PASSWORD must be URL-safe.
chmod 600 .env
make up
```

Open http://127.0.0.1:8088. For private LAN access deliberately set
`HTTP_BIND=0.0.0.0` and firewall the host; this is not an Internet-facing service.
Caddy serves same-origin API/UI. Configure TLS before sending keys over an
untrusted network. The default bind is loopback. No commercial service, remote
fonts, remote tiles, or weather network requests. Leaflet shows route geometry
on a local background so precise GPS never leaves the LAN.

`make up` builds, migrates, waits for DB/API/web/worker readiness. Named volumes
retain data across restarts. `make down` does not remove volumes. No automatic
retention/deletion is installed. Never use `down -v` on a database you need.

### Provision a car

```sh
# Set ADMIN_TOKEN in this shell from your private .env (do not put it in the car).
curl -fsS http://127.0.0.1:8088/api/v1/admin/devices \
 -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
 -d '{"device_id":"my-aaos","vehicle_id":"my-equinox","name":"Equinox EV"}'
```

The response returns a device key ONCE. Store it securely, use it to sign into
this UI, and later provision it into the app uploader. Each key can access only
its device's vehicle. Admin actions require the separate token. Rotate/revoke
keys in Admin or the API. Keys are HMAC-SHA256 hashed with KEY_PEPPER. Do not
change the pepper without reissuing every key. Browser keys are memory-only.
Per-device PostgreSQL rate limiting is shared across API processes (120/min).
Authentication lives in `security.py` so mTLS can replace it later.

### Synthetic demo (explicitly opt-in)

Set `ALLOW_SYNTHETIC=true` in .env, then `make up`. Provision a *separate* vehicle:

```sh
curl -fsS http://127.0.0.1:8088/api/v1/admin/devices \
 -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
 -d '{"device_id":"synthetic-device","vehicle_id":"synthetic-equinox","name":"SYNTHETIC demo","synthetic":true}'
# Set DEVICE_KEY to the returned api_key.
python3 samples/generate.py --days 60 --upload http://127.0.0.1:8088 --key "$DEVICE_KEY"
```

For a repeatable one-command demo, run `python3 tools/demo.py --days 60`. It
provisions or rotates the synthetic device key, stores it privately in `.env.demo`,
loads the generated batches through the HTTP API and reprocesses. Never paste that
key into a public report.

Sign into `synthetic-equinox` with that device key. The worker refreshes every
15s; Admin offers immediate reprocess. Generation is seeded and labeled
SYNTHETIC; files are only written under samples/. Ingest rejects mixing synthetic
and real vehicles. 60 days generate approximately 40,000 raw events. The UI shows
an unmissable synthetic banner. Nothing here establishes actual Equinox access.

## Contract FIRST

[docs/ingest-contract.md](docs/ingest-contract.md) and its JSON Schema define the
app integration interface. `/docs` on the API is FastAPI OpenAPI (internal API
container only). Main endpoints:

- POST `/api/v1/ingest/batch` (gzip; per-event validation; global ID dedup)
- GET `/api/v1/ingest/capabilities`, `/api/v1/health`
- GET `/api/v1/summary/incar?vehicle_id=...` (precomputed, cacheable)
- GET `/api/v1/trips`, `/trips/{id}`, `/charging`, `/charging/{id}`, `/raw`
- GET `/api/v1/export/trips?vehicle_id=...&format=csv|json`
- GET/POST `/api/v1/views`, `/analysis`
- POST `/api/v1/admin/devices`, `/devices/{id}/rotate`, `/keys/{id}/revoke`,
  `/reprocess/{vehicle_id}`; GET `/api/v1/admin/status`

### Current Android probe integration

The Play app currently EXPORTS reports. It does not upload automatically and
this work does not republish it. Transform an actual exported report:

```sh
docker compose run --rm -v /private/reports:/reports:ro api \
 python -m app.import_probe /reports/report.json --device-id my-aaos \
 --vehicle-id my-equinox --boot-id actual-unique-capture-id --tz-offset-min=-240 \
 > /private/reports/batches.ndjson
```

Provide the actual capture-time offset. The probe lacks it; no guessed offset
is allowed. Upload each output line as a batch using the device key. The bridge
preserves unknown properties, original events and full property configurations,
normalizes documented units/API-35 gear and ignition enums, converts valid wheel
scales, retains diagnostic kinds, and imports timestamped census snapshots. Location only comes from explicit raw-GPS probe opt-in. Probe callback
wallTime is not represented as an exact reconstructed sensor timestamp.

## Derivation and evidence rules

Raw events and ID ledger have UPDATE/DELETE-blocking DB triggers. Dedup uses a
separate globally unique UUID ledger because Timescale unique indexes must
include the time partition. Conflicting reuse rejects rather than overwrites.
Derived membership tables avoid editing raw rows to attach trips or charges.
Continuous aggregates exist at 1s/1m/15m/1h/1d; policies refresh seven days, older
raw remains available and older aggregates require an explicit refresh.

Pure `derive.py` uses source time, monotonic boot epochs, seq and ID ordering;
inputs/config/version are hashed and results have stable IDs. Out-of-order
arrivals increment a generation counter; worker rebuilds a vehicle transactionally
and cannot mark an arrival processed accidentally. It takes a per-vehicle row
lock, which prioritizes correctness over ingestion throughput for this LAN scale.

**APScheduler choice:** one standalone process, no Redis or cloud dependency;
PostgreSQL is the durable dirty-vehicle queue, so restarts lose no pending work.
Multiple workers serialize per vehicle. Every 15 seconds it rebuilds dirty
vehicles and stores a cheap summary, avoiding analysis on a car request.

A trip starts on observed speed, valid wheel/GPS displacement or an explicit
client marker, never UX or preparation alone.
Gear/UX/GPS corroborate; short stops are preserved and merged; gaps, insufficient
evidence and unfinished capture boundaries become NEEDS_REVIEW. No signal is
claimed available on a real car. Distances remain separate, with divergence and
an explicitly documented configurable-priority policy (odometer/wheel/speed/GPS).
The initial implementation's scalar numeric metrics reject multi-area series.
No integration across device/boot/gap boundaries; missing inputs yield null+reason.
Unavailable observations invalidate cached boundary evidence and numerical integration;
no unavailable sample is silently removed to bridge two valid readings.
Wheel vectors require consistent scales and unchanged reset count. `null` in a
four-element scale vector explicitly marks an unsupported wheel; supported wheels
must have finite positive meters-per-tick scales. Distance sums absolute per-interval
wheel movement (including reversals), rather than taking a net endpoint delta. Charging preserves
port-connected interruptions; wall loss/efficiency stay null without wall energy.

## UI

Overview, virtualized Trips/filter/compare/saved views/export, Trip Detail with
linked time charts, local-only map and raw browser, Explore scatter/heatmap/route
box/histogram, Charging, gated regression and Admin. Charts share export/data
controls. UI unit toggle affects display only; raw exports remain SI. Scatter
click/brush drills into a trip; aggregate charts expose underlying trips.
Analyses record formula, input hashes, coefficients/CIs, R², VIF warning,
residuals, algorithm version and exclusions. Nonlinear forms are opt-in; at least
10 trips per actual design parameter are required. Associations are not causes.

## Competitor parity and collection audit

[Source-backed audit and prioritized roadmap](docs/parity-and-data-audit.md)
compares TeslaMate, Tessie, TRONITY, ABRP and Car Scanner against the actual code.
**Parity is not achieved and all available vehicle data is not proven collected.**
The probe attempts all visible read-accessible changing properties, not only its
headlines, but permissions, sample-rate/resource limits, manual capture and the
missing uploader restrict coverage. Battery/cell/charger measurements available
through other OEM APIs or OBD do not establish availability through GM AAOS.

The `probe-bridge-2` importer repairs unit validation, enum/wheel normalization,
static snapshot and diagnostic losses, and fingerprint/UTC handling. Its output
uses a new deterministic ID namespace: do not reimport old captures into the same
vehicle as a migration. See the ingest contract for safe usage. 42 backend tests
include CLI conversion, schema validation, complete fixture event preservation,
immutable raw API round trip, duplicate retry and optional signal filtering.
Native enum normalization applies to the probe importer only; arbitrary ingest
clients must still use documented values. No Android code or permission policy
was changed during this audit.

## Signals collection coverage

Open **Signals** to inspect received evidence, grouped separately by device,
boot, property name/ID, area and canonical unit. The page shows live versus
census-snapshot counts, available/unavailable/error counts, interval cadence,
largest live gap, latest source timestamp and source-time age. Known server
signals not received in the window are listed without claiming VHAL absence.
Refresh, 24-hour/7-day/31-day windows, 100-group paging and current-page JSON
export are supported. This does not expand collection or create an uploader.

`GET /api/v1/signals/coverage?vehicle_id=...&window_hours=168&offset=0&limit=100`
requires a vehicle-scoped device key. Window bounds: 1–744 hours; limit: 1–500;
offset must be nonnegative. Optional `end` must be a timezone-aware timestamp.
Without `end`, the window ends at the vehicle's latest stored source timestamp,
not today, so historical captures remain inspectable. Empty vehicles return an
empty inventory. Source-clock age/future warnings compare source time to server
time, not monotonic sensor freshness. No coordinates or raw values are returned.

Cadence is positive live interval count divided by positive live interval time
inside a group; duplicate timestamps do not count and census snapshots are
excluded. It is not the requested sample rate, a coverage fraction or proof that
ON_CHANGE subscription works. No cadence is returned for a singleton. Available
status is reported as received, not proof of a correctly typed/usable metric.
Inventory is window-bounded and paginated but SQL scans/aggregates the window;
very high event volume still requires benchmarking. It does not include unseen
permission-denied properties or subscriptions absent from imported events.

Verification: 44 backend tests, 3 frontend tests, TypeScript/production build and
Chrome smoke passed. The browser checked the Signals table, window selection
and downloaded JSON content. A 31-day demo query reported 20,919 samples across
10 groups, matching an independent database count exactly; one local HTTP request
took 0.57 seconds. This is not a scalability guarantee. Existing bundle-size,
AnyIO deprecation and optional Compose buildx warnings remain.

## Honest scope / remaining work

This is a working vertical slice across the requested phases, **not every
advanced feature in the supplied specification**. In particular:

- No app batch uploader, on-car summary consumer or actual vehicle validation.
- No manual split/merge UI or annotation/tag editing.
- Preconditioning classification and vehicle-specific ignition/gear normalization
  remain unfinished. Lifecycle segments include UNKNOWN, PREPARING, DRIVING,
  SHORT_STOP and COMPLETING; trip outcomes remain COMPLETE or NEEDS_REVIEW.
- No automatic geometric route matching (explicit client route IDs only),
  weather enrichment, tariff evaluation, wall-meter editing, or charging-energy
  allocation to trips. Relational tables reserve these concepts, not features.
- HVAC fraction is duration-weighted using left-continuous observed state,
  with exact endpoint observations and a bounded gap required; otherwise it is
  null with a reason. Temperatures and power means remain sample means;
  coverage is conservative contiguous coverage,
  not proof of event-driven signal completeness. Energy endpoint checks still
  require vehicle-specific policy before trusting partial-series trips.
- Some frontend requested filters (season/hour/day-of-week), simultaneous facet
  grids, all-selected-point brushing, speed/energy map coloring, cumulative
  energy/elevation/state overlay charts and SOC charging curves remain pending.
- Raw detail currently loads an entire trip; `/raw` is paginated. Full-vehicle
  derivation and trip listing are not bounded/incremental; scale beyond LAN use
  requires benchmarking, pagination and incremental reprocessing.
- Backup UI gives commands, not a browser-based job runner. DB growth reports
  24-hour raw payload arrivals, not exact total-database growth history.
- HTTP loopback is the secure default, not a fully hardened Internet deployment.

These gaps are not disguised with fabricated values. Missing metrics state why.

## Phase 2 hardening — algorithm 1.1.0

This phase tightens metric evidence rather than adding the unfinished advanced UI.
HVAC fraction is duration-weighted; unavailable numerical samples cannot bridge
speed/power integrals or energy/odometer deltas. Device identity is part of every
monotonic epoch. Wheel scales must be present and consistent on every observation,
and reversing counts accumulate actual travel rather than canceling it.
Charging duration is null when the underlying power intervals are unusable.

Admin-only `GET /api/v1/admin/derivation/{vehicle_id}` returns the effective
configuration and generations. `POST` to that same URL replaces the configuration
and queues reprocessing. Request body fields (omitted fields reset to defaults):

```json
{
  "stop_timeout_s": 180,
  "max_gap_s": 90,
  "min_movement_m": 100,
  "distance_priority": ["distance_odometer_m", "distance_wheel_m", "distance_speed_m", "distance_gps_m"]
}
```

The four sources must appear exactly once. Bounds are 1–3600 seconds for stop
timeout, 1–600 seconds for max gap, and 0–10000 meters for minimum movement.
Configuration is included in each trip's input hash and metadata. Priority is a
user policy, not a claim that a sensor is proven accurate. Invalid requests return
422, unknown vehicles 404, and device keys cannot administer configuration.
Use the existing reprocess endpoint for immediate application. There is not yet
a browser configuration editor. Worker version checks automatically rebuild
vehicles after algorithm upgrades, without changing raw samples or saved analyses.

Phase-specific verification: 21 backend tests including irregular HVAC cadence,
missing observations, supported/unsupported wheels, reversal/reset/scales/gaps,
configuration validation/authentication, and exact reprocessing of normalized
immutable payloads. Synthetic fixtures remain under `samples/` only.

## Phase 3 boundary evidence — algorithm 1.2.0

Displacement-only trips now start from contiguous scaled wheel-tick pairs or
GPS-position pairs. Wheel movement must exceed 0.5 m/s, with unchanged reset
counter/scales and one area. GPS movement must exceed 0.5 m/s and displacement
must exceed both 1 meter and the sum of the two reported accuracy radii; each
radius must be at most 50 meters. These are conservative software thresholds,
not vehicle-proven sensor calibration. The preceding observation is retained
as the trip's boundary anchor so its distance is not discarded.

Coordinates must be attached to an **available** event to be used as position
evidence. An unavailable GPS-speed event with coordinates is not promoted to
valid position evidence; clients can report an independent available position
observation (unknown-property support already exists in the ingest schema).
GPS jitter, missing accuracy, wheel resets, missing/changing scales, conflicting
fixes and multi-area wheels cannot establish movement. Unavailable observations
break displacement pairs. UX alone still cannot start a trip.

Absent motion evidence produces an `UNKNOWN` segment, not a presumed short stop,
and resets the stop timer. Observed zero speed or unchanged valid wheel counters
can establish a short stop; GPS jitter alone cannot prove parking. Conflicting
moving evidence wins over a stationary reading. Device/boot changes split active
trips and charging sessions, mark the old boundary for review and clear cached
signals, including when epochs share a timestamp. Multiple interleaved devices
are not fused; this conservative behavior may over-segment them.

No new endpoints, ingest schema or DB migration are required. Worker version
checks rebuild existing data. Phase-specific verification: 31 backend tests,
including displacement-only ingest/reprocess, boundary-anchor membership,
per-epoch trip/charge membership, unknown-state handling and exact recomputation.
Final-source production browser smoke passed. The existing 40,236-event demo
rebuilt to 60 trips and 60 charging sessions at 1.2.0; repeating the admin HTTP
reprocess reproduced both lists exactly and took 5.96 seconds in one local run.
This is not a speedup claim or scalability guarantee. API and worker derivation
source hashes match the tested checkout.

Full UNKNOWN/PREPARING/COMPLETING lifecycle, ignition-based preparation and
preconditioning energy remain unfinished; this is not the full state machine.

## Phase 4 lifecycle extension — algorithm 1.3.0

Stationary observations with gear `DRIVE`/`REVERSE` or normalized ignition
`ON`/`RUN` are buffered as `PREPARING` for at most `max_gap_s` before motion.
Preparation alone never emits a trip. Missing readiness, gaps and device/boot
changes discard the buffer. The ingest schema does not normalize native integer
enums: callers must supply documented strings; integers are not guessed.
An explicit start marker can open a preparation segment but cannot establish
observed movement or complete a stationary trip, even with minimum distance zero.

Trips retain preparation samples and mark `COMPLETING` at the observed end
marker or stop-timeout decision. This is a zero-duration decision point when all
frame timestamps coincide, not an invented completion tail. Short-stop movement
resumes the existing trip before timeout. Persistent `UNKNOWN` evidence during
an active trip adds `UNKNOWN_MOTION_INTERVAL` and prevents `COMPLETE`, even if
valid signals return. An end marker while moving adds `END_MARKER_WHILE_MOVING`
and requires review. Thus the GPS-only jitter/unknown-stop fixture from Phase 3
now correctly remains NEEDS_REVIEW. Raw records are never rewritten.

No new endpoints, schema or migration are required. Existing trip detail returns
the lifecycle segments through `GET /api/v1/trips/{id}`. Version-triggered worker
reprocessing applies the change to existing derived data. 38 backend tests cover
bounded preparation, epoch/gap reset, completion, marker contradictions, restored
motion after uncertainty and immutable membership through ingest/reprocess.
Final verification: 38 backend tests passed; TypeScript checking and production
browser smoke passed, including a visible COMPLETING segment in Trip Detail.
The 40,236-event demo rebuilt to 60 complete trips and 60 charging sessions;
repeated HTTP reprocessing reproduced both lists exactly. One local reprocess
took 8.75 seconds, not a speedup claim or scalability guarantee. Running API and
worker derivation source hashes match the tested checkout.

This is a conservative lifecycle extension, not vehicle-proven preconditioning
classification or a promise that native ignition enums are already normalized.

## Verification on this server

Verified with 40,236 synthetic raw events across 60 trips and 60 interrupted AC
charging sessions. Initial-version pytest: 16 tests; Vitest: 3 tests; strict
TypeScript/Vite build; real Chrome/Playwright UI smoke; PostgreSQL immutable-row
and global dedup tests; exact full-dataset trip reprocess equality. Timescale
backup restored into a separate temporary database using pre/post-restore hooks.

The approximately 8 KB precomputed in-car summary measured 10.8–27.0 ms over
local Caddy HTTP (10 sequential requests, idle ingest). Reprocess measured
73.27 seconds before the sample-ID index and 7.44 seconds after, with identical
trip results on the same 40,236-event dataset; these are local single-run timings,
not a scalability guarantee. Ingest DB work is dispatched to the API threadpool
so it does not block the async HTTP event loop. Full-vehicle reprocessing still
serializes ingestion for that vehicle.

Build warning: the ECharts bundle is large (~1.5 MB uncompressed); code splitting
remains a frontend optimization. Pytest reports a third-party AnyIO deprecation
warning. CI is supplied but has not run remotely. Browser-download tooling did
not support Ubuntu 26.04 here; the smoke test ran with installed Google Chrome.

## Test and backup

```sh
make test
# Browser smoke uses the actual running Caddy/API/Timescale stack:
cd frontend
npx playwright install chromium
EVINSIGHTS_TEST_KEY="$DEVICE_KEY" npm run smoke
# If Playwright does not support your Linux release, use installed Chrome:
# CHROME_PATH=/usr/bin/google-chrome EVINSIGHTS_TEST_KEY="$DEVICE_KEY" npm run smoke
```

Backend integration tests create separate synthetic test vehicles in the running
DB; ALLOW_SYNTHETIC=true is required. Use an isolated stack for CI/tests.

```sh
make backup OUT=/private/backup/evinsights.dump
make restore-check OUT=/private/backup/evinsights.dump
```

Backups are PostgreSQL custom dumps, including Timescale raw/derived structures.
Protect precise coordinates and keys/pepper separately; restore verification
uses a temporary database and never overwrites the running database. Test the
restore on your installed Timescale version before relying on a backup.
