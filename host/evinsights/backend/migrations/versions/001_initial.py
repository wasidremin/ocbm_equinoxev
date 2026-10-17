"""Initial immutable telemetry and recomputable analytics schema."""
from alembic import op

revision = '001'
down_revision = None


def upgrade():
    op.execute('CREATE EXTENSION IF NOT EXISTS timescaledb')
    op.get_bind().exec_driver_sql('''
    CREATE TABLE vehicles (id text PRIMARY KEY, name text NOT NULL, synthetic boolean NOT NULL DEFAULT false);
    CREATE TABLE devices (id text PRIMARY KEY, vehicle_id text NOT NULL REFERENCES vehicles(id), name text NOT NULL);
    CREATE TABLE api_keys (id uuid PRIMARY KEY, device_id text NOT NULL REFERENCES devices(id), key_hash text UNIQUE NOT NULL, revoked boolean NOT NULL DEFAULT false, created_at timestamptz NOT NULL DEFAULT now());
    CREATE TABLE ingest_batches (id bigserial PRIMARY KEY, batch_id uuid NOT NULL, device_id text NOT NULL REFERENCES devices(id), received_ts timestamptz NOT NULL DEFAULT now(), accepted int NOT NULL, duplicate int NOT NULL, rejected int NOT NULL, errors jsonb NOT NULL);
    CREATE TABLE event_ids (event_id uuid PRIMARY KEY, device_id text NOT NULL, payload_hash text NOT NULL, source_ts timestamptz NOT NULL);
    CREATE TABLE telemetry_samples (
      source_ts timestamptz NOT NULL, event_id uuid NOT NULL, vehicle_id text NOT NULL REFERENCES vehicles(id),
      device_id text NOT NULL REFERENCES devices(id), received_ts timestamptz NOT NULL DEFAULT now(),
      property_name text NOT NULL, property_id bigint NOT NULL, area_id int NOT NULL, status text NOT NULL,
      value_num double precision, payload jsonb NOT NULL, PRIMARY KEY(source_ts,event_id));
    SELECT create_hypertable('telemetry_samples','source_ts');
    CREATE INDEX ON telemetry_samples(vehicle_id, source_ts DESC);
    CREATE INDEX ON telemetry_samples(property_name, source_ts DESC);
    CREATE TABLE signal_definitions (name text PRIMARY KEY, unit_canonical text NOT NULL, metadata jsonb NOT NULL DEFAULT '{}');
    CREATE TABLE derivation_state (vehicle_id text PRIMARY KEY REFERENCES vehicles(id), generation bigint NOT NULL DEFAULT 0, processed_generation bigint NOT NULL DEFAULT -1, config jsonb NOT NULL DEFAULT '{"stop_timeout_s":180,"max_gap_s":90,"min_movement_m":100}', updated_at timestamptz, summary jsonb);
    CREATE TABLE trips (id uuid PRIMARY KEY, vehicle_id text NOT NULL REFERENCES vehicles(id), start_ts timestamptz NOT NULL, end_ts timestamptz NOT NULL, state text NOT NULL, confidence double precision NOT NULL, boundary_reasons jsonb NOT NULL, algorithm_version text NOT NULL, input_hash text NOT NULL, route_id text, synthetic boolean NOT NULL, metadata jsonb NOT NULL);
    CREATE INDEX ON trips(vehicle_id,start_ts DESC);
    CREATE TABLE trip_segments (id bigserial PRIMARY KEY, trip_id uuid NOT NULL REFERENCES trips(id) ON DELETE CASCADE, start_ts timestamptz NOT NULL, end_ts timestamptz NOT NULL, state text NOT NULL);
    CREATE TABLE trip_metrics (trip_id uuid PRIMARY KEY REFERENCES trips(id) ON DELETE CASCADE, algorithm_version text NOT NULL, metrics jsonb NOT NULL);
    CREATE TABLE trip_samples (trip_id uuid NOT NULL REFERENCES trips(id) ON DELETE CASCADE, event_id uuid NOT NULL, source_ts timestamptz NOT NULL, PRIMARY KEY(trip_id,event_id));
    CREATE INDEX ON trip_samples(trip_id,source_ts);
    CREATE TABLE charging_sessions (id uuid PRIMARY KEY, vehicle_id text NOT NULL REFERENCES vehicles(id), start_ts timestamptz NOT NULL, end_ts timestamptz NOT NULL, algorithm_version text NOT NULL, metadata jsonb NOT NULL);
    CREATE TABLE charging_metrics (charging_session_id uuid PRIMARY KEY REFERENCES charging_sessions(id) ON DELETE CASCADE, metrics jsonb NOT NULL, algorithm_version text NOT NULL);
    CREATE TABLE charging_samples (charging_session_id uuid NOT NULL REFERENCES charging_sessions(id) ON DELETE CASCADE, event_id uuid NOT NULL, source_ts timestamptz NOT NULL, PRIMARY KEY(charging_session_id,event_id));
    CREATE INDEX ON charging_samples(charging_session_id,source_ts);
    CREATE TABLE rate_limits (device_id text NOT NULL, minute timestamptz NOT NULL, requests int NOT NULL, PRIMARY KEY(device_id,minute));
    CREATE TABLE worker_heartbeat (id int PRIMARY KEY, updated_at timestamptz NOT NULL);
    CREATE TABLE electricity_rates (id uuid PRIMARY KEY, vehicle_id text REFERENCES vehicles(id), definition jsonb NOT NULL);
    CREATE TABLE locations (id uuid PRIMARY KEY, definition jsonb NOT NULL);
    CREATE TABLE geofences (id uuid PRIMARY KEY, definition jsonb NOT NULL);
    CREATE TABLE routes (id text PRIMARY KEY, vehicle_id text REFERENCES vehicles(id), definition jsonb NOT NULL);
    CREATE TABLE route_matches (trip_id uuid REFERENCES trips(id) ON DELETE CASCADE, route_id text REFERENCES routes(id), confidence double precision NOT NULL, PRIMARY KEY(trip_id,route_id));
    CREATE TABLE weather_observations (id uuid PRIMARY KEY, source_ts timestamptz NOT NULL, definition jsonb NOT NULL);
    CREATE TABLE annotations (id uuid PRIMARY KEY, vehicle_id text NOT NULL REFERENCES vehicles(id), definition jsonb NOT NULL);
    CREATE TABLE analysis_runs (id uuid PRIMARY KEY, vehicle_id text NOT NULL REFERENCES vehicles(id), created_at timestamptz NOT NULL DEFAULT now(), definition jsonb NOT NULL, result jsonb NOT NULL);
    CREATE TABLE data_quality_issues (id bigserial PRIMARY KEY, vehicle_id text NOT NULL REFERENCES vehicles(id), algorithm_version text NOT NULL, definition jsonb NOT NULL);
    CREATE TABLE saved_views (id uuid PRIMARY KEY, vehicle_id text NOT NULL REFERENCES vehicles(id), definition jsonb NOT NULL);
    CREATE FUNCTION immutable_raw() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Raw telemetry is immutable'; END $$;
    CREATE TRIGGER immutable_telemetry BEFORE UPDATE OR DELETE ON telemetry_samples FOR EACH ROW EXECUTE FUNCTION immutable_raw();
    CREATE TRIGGER immutable_event_ids BEFORE UPDATE OR DELETE ON event_ids FOR EACH ROW EXECUTE FUNCTION immutable_raw();
    ''')
    for label, interval in [('1s','1 second'),('1m','1 minute'),('15m','15 minutes'),('1h','1 hour'),('1d','1 day')]:
        op.execute(f'''CREATE MATERIALIZED VIEW telemetry_{label} WITH (timescaledb.continuous) AS
        SELECT time_bucket(INTERVAL '{interval}', source_ts) AS bucket, vehicle_id, property_name,
        avg(value_num) AS mean, min(value_num) AS minimum, max(value_num) AS maximum, count(*) AS samples
        FROM telemetry_samples WHERE status='available' GROUP BY bucket, vehicle_id, property_name WITH NO DATA''')
        op.execute(f"SELECT add_continuous_aggregate_policy('telemetry_{label}', start_offset => INTERVAL '7 days', end_offset => INTERVAL '1 minute', schedule_interval => INTERVAL '1 minute')")


def downgrade():
    raise RuntimeError('Destructive downgrade prohibited: export and explicitly drop the isolated database instead')
