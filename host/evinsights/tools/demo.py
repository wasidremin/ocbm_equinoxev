"""Provision/rotate an isolated SYNTHETIC demo and upload samples through the real API."""
import argparse
import importlib.util
import json
import time
import urllib.request
import urllib.error
from pathlib import Path

root=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--days',type=int,default=60);p.add_argument('--url',default='http://127.0.0.1:8088');args=p.parse_args()
if not 1<=args.days<=365:p.error('days must be 1..365')
cfg=dict(line.split('=',1) for line in (root/'.env').read_text().splitlines() if '=' in line and not line.startswith('#'))

def request(path,key,body=None):
    req=urllib.request.Request(args.url+'/api/v1'+path,data=json.dumps(body).encode() if body is not None else None,
        headers={'Authorization':'Bearer '+key,'Content-Type':'application/json'})
    for attempt in range(5):
        try:
            with urllib.request.urlopen(req,timeout=180) as r:return json.load(r)
        except urllib.error.HTTPError as e:
            if e.code not in (429,502,503,504) or attempt==4:raise
            time.sleep(5*(attempt+1))

admin=cfg['ADMIN_TOKEN']
try:
    key=request('/admin/devices',admin,{'device_id':'synthetic-device','vehicle_id':'synthetic-equinox','name':'SYNTHETIC demo','synthetic':True})['api_key']
except urllib.error.HTTPError as e:
    if e.code!=409:raise
    key=request('/admin/devices/synthetic-device/rotate',admin,{})['api_key']
private=root/'.env.demo';private.write_text('EVINSIGHTS_TEST_KEY='+key+'\n');private.chmod(0o600)
spec=importlib.util.spec_from_file_location('generate',root/'samples/generate.py');mod=importlib.util.module_from_spec(spec);spec.loader.exec_module(mod)
batches=mod.generate(args.days)
(root/'samples/generated-batches.ndjson').write_text(''.join(json.dumps(b)+'\n' for b in batches))
accepted=duplicates=0
for batch in reversed(batches):
    result=request('/ingest/batch',key,batch)
    if result['rejected']:raise RuntimeError(result)
    accepted+=result['accepted'];duplicates+=result['duplicate']
request('/admin/reprocess/synthetic-equinox',admin,{})
print(f'SYNTHETIC ONLY: {accepted} accepted, {duplicates} duplicate. Device key stored privately in .env.demo.')
