"""Sync stored GM probe reports into evinsights through the device ingest API.

Each report is converted with app.import_probe.convert and posted as gzip batches.
Converted events carry deterministic IDs, so re-sending a report is accepted as
duplicates. A local state file (outside the repo) skips reports already delivered
with the same content hash.

Run once:      python3 tools/sync_probes.py PROBES_DIR --device-id D --vehicle-id V --key-file K --state S
Run as daemon: add --watch 300
"""
import argparse, gzip, hashlib, json, sys, time, urllib.error, urllib.request
from datetime import datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'backend'))
from app.import_probe import convert  # noqa: E402


def tz_minutes(wall):
    offset = datetime.fromisoformat(wall.replace('Z', '+00:00')).utcoffset()
    return int(offset.total_seconds() // 60)


def post_batch(url, key, batch, retries=3):
    body = gzip.compress(json.dumps(batch).encode())
    for attempt in range(retries + 1):
        req = urllib.request.Request(url, data=body, method='POST', headers={
            'Authorization': 'Bearer ' + key, 'Content-Type': 'application/json', 'Content-Encoding': 'gzip'})
        try:
            with urllib.request.urlopen(req, timeout=120) as r:
                return json.loads(r.read())
        except urllib.error.HTTPError as e:
            if e.code == 429 and attempt < retries:
                time.sleep(int(e.headers.get('Retry-After', '60')))
                continue
            raise


def sync_once(a, state):
    key = Path(a.key_file).read_text().strip()
    url = a.api.rstrip('/') + '/ingest/batch'
    totals = {'accepted': 0, 'duplicate': 0, 'rejected': 0}
    for path in sorted(Path(a.probes_dir).glob('*.json')):
        raw = path.read_bytes()
        digest = hashlib.sha256(raw).hexdigest()
        if state.get(path.name) == digest:
            continue
        try:
            report = json.loads(raw)
            batches = convert(report, a.device_id, a.vehicle_id, path.stem, tz_minutes(report['startWallTime']))
            for batch in batches:
                result = post_batch(url, key, batch)
                for k in totals:
                    totals[k] += result.get(k, 0)
        except Exception as e:  # keep syncing other reports; this one is retried next pass
            print(f'FAILED {path.name}: {type(e).__name__}: {str(e)[:300]}', file=sys.stderr)
            continue
        state[path.name] = digest
        print(f'synced {path.name}: {len(batches)} batch(es)')
    return totals


def main():
    p = argparse.ArgumentParser(description='Sync stored probe reports into evinsights.')
    p.add_argument('probes_dir')
    p.add_argument('--api', default='http://127.0.0.1:8088/api/v1')
    p.add_argument('--device-id', required=True)
    p.add_argument('--vehicle-id', required=True)
    p.add_argument('--key-file', required=True, help='file holding the device API key (outside the repo)')
    p.add_argument('--state', required=True, help='JSON state file of delivered report hashes')
    p.add_argument('--watch', type=int, default=0, help='seconds between passes; 0 runs once')
    a = p.parse_args()
    state_path = Path(a.state)
    while True:
        state = json.loads(state_path.read_text()) if state_path.exists() else {}
        totals = sync_once(a, state)
        state_path.parent.mkdir(parents=True, exist_ok=True)
        state_path.write_text(json.dumps(state, indent=2))
        print('totals:', totals)
        if a.watch <= 0:
            return
        time.sleep(a.watch)


if __name__ == '__main__':
    main()
