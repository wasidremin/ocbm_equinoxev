import os
from sqlalchemy import create_engine, text

engine = create_engine(os.environ.get('DATABASE_URL', 'postgresql+psycopg://evinsights:invalid@localhost/evinsights'), pool_pre_ping=True)


def rows(conn, query, params=None):
    return [dict(r) for r in conn.execute(text(query), params or {}).mappings()]


def one(conn, query, params=None):
    return conn.execute(text(query), params or {}).mappings().first()
