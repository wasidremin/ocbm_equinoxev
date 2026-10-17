# evinsights ingest contract — v1

Status: implemented contract; no automatic uploader exists in the Android probe yet.
Base URL: the private-LAN server. All `/api/v1` endpoints except health require
`Authorization: Bearer <device-key>`. Keys bind to one device and vehicle. Admin
operations instead require the separate admin token. Never use the admin token in a car.

## POST /api/v1/ingest/batch

Content-Type: application/json; optional Content-Encoding: gzip. Limit: 4 MiB
compressed AND decompressed; 5,000 events per batch. The envelope is
`{"batch_id":"UUID","events":[...]}`. Envelope errors return 422; malformed
JSON/gzip 400; over-size 413; authentication 401; rate limit 429.
Events are validated separately: HTTP 200 returns
`{"accepted":1,"duplicate":0,"rejected":1,"errors":[{"index":1,"event_id":"...","code":"VALIDATION_ERROR","detail":"..."}]}`.
Events with a valid UUID already stored are duplicates only when their normalized
payload is identical; changed payload returns EVENT_ID_CONFLICT. Deduplication is
GLOBAL on event_id, not scoped to a Timescale time partition. Raw events never
change. Retrying is safe. Batch IDs identify delivery envelopes, not transactions:
retry rejected events after correction with new event IDs; accepted IDs are immutable.
`received_ts` is assigned by the server and cannot be supplied by a client.
Every event's batch_id must equal the envelope batch_id.

The JSON Schema in [ingest.schema.json](ingest.schema.json) is generated from the
same Pydantic models used at runtime. Exactly one non-null typed value field is
required. Unavailable/error observations should carry the original diagnostic
string/object in value_str/value_arr plus status, not a fabricated numeric zero.
Finite JSON numbers only. Unknown vendor IDs and arbitrary JSON metadata are
accepted; their canonical unit must be `unknown` unless documented.

## Event field rules

Required: event_id, batch_id (UUID), schema_version=1, device_id, vehicle_id,
source_ts (UTC RFC3339 ending Z or +00:00), source_elapsed_ns (nonnegative),
tz_offset_min (-840..840), property_id (signed 64-bit), property_name, area_id,
status (available/unavailable/error), unit_canonical, unit_original,
quality (measured/derived/enriched/user-entered), app_version,
build_fingerprint, seq (nonnegative), meta (object), exactly one of
value_num / value_int / value_bool / value_str / value_arr.
Optional: lat, lon (paired), alt_m, accuracy_m, bearing_deg. No location is
inferred; location requires explicit opt-in in the car. `meta.boot_id` is required
for every event so monotonic times are never compared across app/device restarts.
`meta.synthetic=true` requires a vehicle explicitly provisioned as synthetic.
Property arrays may contain scalar values/null but must not contain NaN/Infinity.

Canonical units: m, s, m/s, J, W, K, Pa, rad or explicit dimensionless units
`bool`, `enum`, `ticks`, `1`, `unknown`. Battery energy is J, NOT Wh or percent.
Power is positive into the battery (charging/regen), negative discharge.
Temperature is Kelvin. UI conversion happens only at display/export.
Numeric known properties must match their declared canonical unit.

## Android Vehicle Telemetry Probe bridge

The shipping probe exports `report.json`, `events.ndjson`, and CSV, not this
schema. The provided `python -m app.import_probe` CLI validates every emitted
event, converts PROPERTY/UX/opted-in LOCATION events, retains other event kinds as
PROBE_DIAGNOSTIC, and imports census initial-read snapshots without inventing
sensor timestamps. The full original event and property configuration remain in
metadata. Census snapshots require report startWallTime/startElapsedRealtimeNanos;
otherwise conversion fails rather than silently losing observations.
Provide device/vehicle IDs, boot/capture ID and the actual historical timezone
offset explicitly: the current probe does NOT record timezone offsets. Unknown
vendor arrays/units are preserved; they do not automatically become metrics.

| Probe property | Probe unit | Server unit / conversion |
|---|---|---|
| PERF_VEHICLE_SPEED | m/s | m/s |
| PERF_ODOMETER | km | m (×1000) |
| EV_BATTERY_LEVEL, INFO_EV_BATTERY_CAPACITY | Wh | J (×3600) |
| EV_BATTERY_INSTANTANEOUS_CHARGE_RATE | mW, + charging | W (÷1000) |
| ENV_OUTSIDE_TEMPERATURE | °C | K (+273.15) |
| RANGE_REMAINING | m | m |
| TIRE_PRESSURE | kPa | Pa (×1000) |
| WHEEL_TICK | ticks | ticks; validated supported-wheel mask and micrometers/tick → meters/tick metadata, no default scale |
| GEAR_SELECTION, CURRENT_GEAR, IGNITION_STATE | API-35 integer enum | documented strings; original value retained; unknown integers not guessed |
| HVAC_POWER_ON, EV_CHARGE_PORT_CONNECTED | boolean | bool |
| HVAC_TEMPERATURE_CURRENT/SET | °C | K (+273.15) |
| INFO_EXTERIOR_DIMENSIONS | mm | m (×0.001) |
| UX_RESTRICTIONS | indirect UI restriction | bool; NOT a gear signal |

Callback wallTime is receipt time on the car; property timestampNanos is the
sensor monotonic timestamp. Preserve both in meta; don't claim an exact sensor
UTC reconstruction. PROPERTY_ERROR is a diagnostic string, not zero.

Importer version `probe-bridge-2` uses a new deterministic UUID namespace.
Do NOT reimport a previously imported capture into the same vehicle as a migration:
new IDs would create duplicate observations alongside immutable old rows. Validate
with a new capture or isolated synthetic vehicle. Non-UTC probe wallTime is
normalized to UTC while the explicitly supplied capture offset is retained.
Location without measured speed becomes an available GPS_POSITION observation,
not a fabricated zero-speed reading. The original fix/provider/location clocks
remain in metadata. Optional altitude/bearing are retained when supplied, but the
current Android exporter does not emit them.

## GET /api/v1/ingest/capabilities

Returns schema version, limits, understood signal names/canonical units,
algorithm_version, and whether device is synthetic. Being understood by the
server does NOT establish permission/access on an Equinox.

## GET /api/v1/health

Public health only: status and DB connectivity; no telemetry or identities.

## Lifecycle / data interpretation

The server orders derivation by source_ts, then elapsed time, seq, event_id.
It flags large gaps, clock drift within boot epochs, unavailable signals and
ambiguous boundaries. Reprocess uses the same versioned algorithm and input
configuration. Derived metrics carry `{value, reason, quality, algorithm_version}`;
missing values are null with a stable reason. No extrapolation across gaps.
Device/boot epoch changes split active derived sessions for review; a capture
ID alone does not establish a complete trip. Raw records are immutable
in PostgreSQL by trigger. Separate membership tables attach events to derived
trips/charges, rather than mutating raw rows.

Keys: admin provision, rotate, revoke; hashed HMAC-SHA256 with a server pepper.
Rate limit is PostgreSQL-backed per device/minute, shared across API processes.
The authentication dependency is the replacement seam for future mTLS/identity.
LAN HTTP is for trusted networks only; use TLS before exposing outside that LAN.
Nothing is deleted by default. Backups contain precise location and must be
protected. Synthetic fixtures and generated synthetic files exist only in samples/;
synthetic ingestion is opt-in and visibly labeled throughout the UI.
