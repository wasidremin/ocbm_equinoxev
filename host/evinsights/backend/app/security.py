import hashlib
import hmac
import os
import secrets
from uuid import uuid4

from fastapi import Header, HTTPException
from sqlalchemy import text
from app.db import engine, one


def key_hash(key):
    pepper = os.environ.get('KEY_PEPPER', '')
    if len(pepper) < 32:
        raise RuntimeError('KEY_PEPPER must contain at least 32 characters')
    return hmac.new(pepper.encode(), key.encode(), hashlib.sha256).hexdigest()


def admin(authorization: str = Header(default='')):
    token = os.environ.get('ADMIN_TOKEN', '')
    if len(token) < 32 or not hmac.compare_digest(authorization, 'Bearer ' + token):
        raise HTTPException(401, 'Admin authentication required')
    return True


def device(authorization: str = Header(default='')):
    if not authorization.startswith('Bearer '):
        raise HTTPException(401, 'Device authentication required')
    with engine.begin() as c:
        row = one(c, '''SELECT d.id AS device_id,d.vehicle_id,v.synthetic FROM api_keys k
        JOIN devices d ON d.id=k.device_id JOIN vehicles v ON v.id=d.vehicle_id
        WHERE k.key_hash=:hash AND NOT k.revoked''', {'hash': key_hash(authorization[7:])})
        if not row:
            raise HTTPException(401, 'Invalid or revoked key')
        limit = one(c, '''INSERT INTO rate_limits VALUES (:d,date_trunc('minute',now()),1)
        ON CONFLICT(device_id,minute) DO UPDATE SET requests=rate_limits.requests+1 RETURNING requests''', {'d': row['device_id']})
        # Old rate-limit counters are operational data, never raw telemetry.
        c.execute(text("DELETE FROM rate_limits WHERE minute < now()-interval '2 minutes'"))
        if limit['requests'] > 120:
            raise HTTPException(429, 'Device rate limit: 120 requests/minute', headers={'Retry-After': '60'})
        return dict(row)


def issue_key(c, device_id):
    key = secrets.token_urlsafe(36)
    kid = str(uuid4())
    c.execute(text('INSERT INTO api_keys(id,device_id,key_hash) VALUES (:id,:d,:h)'), {'id': kid, 'd': device_id, 'h': key_hash(key)})
    return {'id': kid, 'api_key': key, 'device_id': device_id}
