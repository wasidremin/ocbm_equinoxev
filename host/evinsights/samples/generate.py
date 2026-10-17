"""Synthetic fixture generator, never writes outside samples/. stdlib only."""
import argparse
import json
import math
import random
import urllib.request
from datetime import datetime, timedelta, timezone
from pathlib import Path
from uuid import NAMESPACE_URL, uuid5

ROOT=Path(__file__).resolve().parent
UNITS={'PERF_VEHICLE_SPEED':'m/s','PERF_ODOMETER':'m','EV_BATTERY_LEVEL':'J','EV_BATTERY_INSTANTANEOUS_CHARGE_RATE':'W','ENV_OUTSIDE_TEMPERATURE':'K','RANGE_REMAINING':'m','GEAR_SELECTION':'enum','HVAC_POWER_ON':'bool','EV_CHARGE_PORT_CONNECTED':'bool','CHARGE_TYPE':'enum','CLIENT_MARKER':'enum'}


def generate(days=60):
    rng=random.Random(8271); events=[]; batches=[]; seq=0; odometer=1000000.; battery=200000000.
    start=datetime(2026,7,1,tzinfo=timezone.utc)
    for day in range(days):
        batch_id=str(uuid5(NAMESPACE_URL,f'synthetic-batch-{day}')); group=[]
        temp=273.15+(-5+day*.6)+rng.gauss(0,2); speed=10+day%5*3
        length=900+day%4*300; route=f'synthetic-route-{day%3}'
        efficiency=430+(.55*(temp-293)**2)+.8*(speed-15)**2+rng.gauss(0,20)
        base=start+timedelta(days=day,hours=12+day%3)
        def add(t,name,v,lat=None,lon=None,extra=None):
            nonlocal seq
            seq+=1
            e={'event_id':str(uuid5(NAMESPACE_URL,f'synthetic-{day}-{seq}')),'batch_id':batch_id,
               'schema_version':1,'device_id':'synthetic-device','vehicle_id':'synthetic-equinox',
               'source_ts':t.isoformat(),'source_elapsed_ns':int((t-start).total_seconds()*1e9),
               'tz_offset_min':-240,'property_id':list(UNITS).index(name)+1,'property_name':name,'area_id':0,
               'status':'available','unit_canonical':UNITS[name],'unit_original':UNITS[name],
               'quality':'measured','app_version':'synthetic-fixture-1','build_fingerprint':'SYNTHETIC_NOT_VEHICLE_EVIDENCE',
               'seq':seq,'meta':{'boot_id':'synthetic-boot','synthetic':True,'route_id':route,**(extra or {})}}
            e['value_bool' if isinstance(v,bool) else 'value_str' if isinstance(v,str) else 'value_num']=v
            if lat is not None: e.update(lat=lat,lon=lon,accuracy_m=3.,alt_m=100+10*math.sin(seq/200))
            group.append(e)
        for sec in range(0,length+241,30):
            t=base+timedelta(seconds=sec); moving=sec<length and not (300<=sec<360)
            s=speed if moving else 0.; distance=s*30 if sec else 0.
            odometer+=distance; battery-=distance*efficiency
            add(t,'PERF_VEHICLE_SPEED',s,40+day%3*.02+min(sec,length)*speed/111320,-83.)
            add(t,'GEAR_SELECTION','DRIVE' if moving else 'PARK')
            add(t,'PERF_ODOMETER',odometer)
            add(t,'EV_BATTERY_LEVEL',battery)
            add(t,'EV_BATTERY_INSTANTANEOUS_CHARGE_RATE',-s*efficiency)
            if day%11!=0: add(t,'ENV_OUTSIDE_TEMPERATURE',temp)
            add(t,'HVAC_POWER_ON',temp<285 or temp>299)
            add(t,'RANGE_REMAINING',max(0,battery/500))
        # Realistic interrupted charge power while port remains connected.
        cb=base+timedelta(hours=3)
        for sec in range(0,1801,30):
            t=cb+timedelta(seconds=sec); p=0. if 600<=sec<720 or sec==1800 else 7200.
            battery+=p*30
            add(t,'EV_CHARGE_PORT_CONNECTED',sec<1800)
            add(t,'EV_BATTERY_INSTANTANEOUS_CHARGE_RATE',p)
            add(t,'EV_BATTERY_LEVEL',battery)
            add(t,'CHARGE_TYPE','AC')
        # Battery is reset between synthetic days; never interpret across day gaps.
        battery=200000000.
        events.extend(group); batches.append({'batch_id':batch_id,'events':group})
    return batches


def call(url,key,data):
    req=urllib.request.Request(url,data=json.dumps(data).encode(),headers={'Authorization':'Bearer '+key,'Content-Type':'application/json'})
    with urllib.request.urlopen(req) as r: return json.load(r)


if __name__=='__main__':
    p=argparse.ArgumentParser(); p.add_argument('--days',type=int,default=60); p.add_argument('--upload'); p.add_argument('--key'); args=p.parse_args()
    if not 1<=args.days<=365: p.error('days must be 1..365')
    data=generate(args.days)
    target=ROOT/'generated-batches.ndjson'
    target.write_text(''.join(json.dumps(b)+'\n' for b in data))
    print(f'SYNTHETIC ONLY: {sum(len(b["events"]) for b in data)} events in {target}')
    if args.upload:
        if not args.key: p.error('--key required for upload')
        for b in reversed(data): print(call(args.upload.rstrip('/')+'/api/v1/ingest/batch',args.key,b))
