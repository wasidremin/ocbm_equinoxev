"""Avoid repeatedly scanning raw history while rebuilding memberships."""
from alembic import op
revision='002'
down_revision='001'


def upgrade():
    op.execute('CREATE INDEX IF NOT EXISTS telemetry_event_id_lookup ON telemetry_samples(event_id)')


def downgrade():
    op.execute('DROP INDEX IF EXISTS telemetry_event_id_lookup')
