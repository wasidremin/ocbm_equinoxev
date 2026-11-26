"""tools/sync_probes.py's skip of unchanged reports. Run in the api image:
docker run --rm -v "$PWD/tools:/tools:ro" -v "$PWD/backend:/backend:ro" -w /tools --entrypoint python evinsights-api -m pytest -q -p no:cacheprovider test_sync_probes.py
"""
import os
from pathlib import Path
from types import SimpleNamespace

import sync_probes


def test_a_delivered_report_whose_file_is_unchanged_is_not_read_again(tmp_path, monkeypatch):
    probes = tmp_path / 'probes'
    probes.mkdir()
    (probes / 'a.json').write_text('{"fingerprint": "sdk_gcar"}')
    key = tmp_path / 'key'
    key.write_text('k')
    a = SimpleNamespace(key_file=str(key), api='http://127.0.0.1:9/api/v1', probes_dir=str(probes),
                        device_id='d', vehicle_id='v')
    reads = []
    real = Path.read_bytes
    monkeypatch.setattr(Path, 'read_bytes', lambda self: reads.append(self.name) or real(self))

    state, seen = {}, {}
    sync_probes.sync_once(a, state, seen)
    sync_probes.sync_once(a, state, seen)
    assert reads == ['a.json']

    # Rewritten in place (the receiver replaced it): read again.
    (probes / 'a.json').write_text('{"fingerprint": "sdk_gcar", "x": 1}')
    os.utime(probes / 'a.json', ns=(1, 1))
    sync_probes.sync_once(a, state, seen)
    assert reads == ['a.json', 'a.json']


def test_a_fresh_process_hashes_once_and_trusts_the_state_file():
    sig = (10, 5)
    assert sync_probes.needs_read('a.json', sig, {'a.json': 'h'}, {})
    assert not sync_probes.needs_read('a.json', sig, {'a.json': 'h'}, {'a.json': sig})
    # Never delivered (a failed pass): always read, whatever was seen.
    assert sync_probes.needs_read('a.json', sig, {}, {'a.json': sig})
    assert sync_probes.needs_read('a.json', (11, 5), {'a.json': 'h'}, {'a.json': sig})

