import csv
import gzip
import hashlib
import io
import json
import os
from datetime import datetime, timezone, timedelta
from uuid import UUID, uuid4

from fastapi import Depends, FastAPI, HTTPException, Query, Request, Response
from starlette.concurrency import run_in_threadpool
from pydantic import BaseModel, Field, ValidationError
from sqlalchemy import text

from app.contracts import Batch, Event, SIGNALS
from app.db import engine, rows, one
from app.security import device, admin, issue_key
from app.derive import VERSION, DerivationConfig
from app.worker import process
from app.analysis import regression

app=FastAPI(title='evinsights',version='1.0.0')
LIMIT=4*1024*1024


@app.get('/api/v1/health')
def health():
    try:
        with engine.connect() as c: c.execute(text('SELECT 1'))
    except Exception:
        raise HTTPException(503,'Database unavailable')
    return {'status':'ok'}


@app.get('/api/v1/ingest/capabilities')
def capabilities(scope=Depends(device)):
    return {'schema_version':1,'max_events':5000,'max_bytes':LIMIT,'signals':SIGNALS,
            'algorithm_version':VERSION,'synthetic':scope['synthetic']}


@app.post('/api/v1/ingest/batch')
async def ingest(request:Request, scope=Depends(device)):
    body=bytearray()
    async for chunk in request.stream():
        body.extend(chunk)
        if len(body)>LIMIT: raise HTTPException(413,'Compressed request too large')
    encoding=request.headers.get('content-encoding','identity')
    if encoding not in ('identity','gzip'): raise HTTPException(415,'Unsupported content encoding')
    try:
        if encoding=='gzip':
            with gzip.GzipFile(fileobj=io.BytesIO(body)) as f: body=f.read(LIMIT+1)
        if len(body)>LIMIT: raise HTTPException(413,'Decompressed request too large')
        raw=json.loads(body)
        batch=Batch.model_validate(raw)
    except ValidationError as e: raise HTTPException(422,e.errors(include_context=False))
    except (ValueError,OSError,EOFError): raise HTTPException(400,'Invalid JSON or gzip')
    return await run_in_threadpool(persist_batch,batch,scope)


def persist_batch(batch,scope):
    result={'accepted':0,'duplicate':0,'rejected':0,'errors':[]}
    with engine.begin() as c:
        # Serialize this vehicle against reprocessing and concurrent deliveries.
        c.execute(text('SELECT vehicle_id FROM derivation_state WHERE vehicle_id=:v FOR UPDATE'),{'v':scope['vehicle_id']})
        for i,item in enumerate(batch.events):
            try:
                event=Event.model_validate(item)
                if event.batch_id!=batch.batch_id: raise ValueError('batch_id differs from envelope')
                if event.device_id!=scope['device_id'] or event.vehicle_id!=scope['vehicle_id']:
                    raise ValueError('Device/vehicle does not match authenticated key')
                if bool(event.meta.get('synthetic'))!=scope['synthetic']:
                    raise ValueError('Synthetic label must match provisioned vehicle')
                payload=event.payload(); digest=hashlib.sha256(json.dumps(payload,sort_keys=True,separators=(',',':')).encode()).hexdigest()
                inserted=one(c,'''INSERT INTO event_ids VALUES (:id,:d,:h,:t) ON CONFLICT DO NOTHING RETURNING event_id''',
                             {'id':str(event.event_id),'d':scope['device_id'],'h':digest,'t':event.source_ts})
                if not inserted:
                    previous=one(c,'SELECT device_id,payload_hash FROM event_ids WHERE event_id=:id',{'id':str(event.event_id)})
                    if previous['device_id']!=scope['device_id'] or previous['payload_hash']!=digest:
                        result['rejected']+=1; result['errors'].append({'index':i,'event_id':str(event.event_id),'code':'EVENT_ID_CONFLICT','detail':'ID already stored with different immutable payload'}); continue
                    result['duplicate']+=1; continue
                numeric=event.value_num if event.value_num is not None else event.value_int
                c.execute(text('''INSERT INTO telemetry_samples(source_ts,event_id,vehicle_id,device_id,property_name,property_id,area_id,status,value_num,payload)
                VALUES (:t,:id,:v,:d,:name,:p,:area,:status,:num,CAST(:payload AS jsonb))'''),
                          {'t':event.source_ts,'id':str(event.event_id),'v':event.vehicle_id,'d':event.device_id,'name':event.property_name,
                           'p':event.property_id,'area':event.area_id,'status':event.status,'num':numeric,'payload':json.dumps(payload)})
                c.execute(text('INSERT INTO signal_definitions VALUES (:n,:u,CAST(:m AS jsonb)) ON CONFLICT DO NOTHING'),
                          {'n':event.property_name,'u':event.unit_canonical,'m':json.dumps({'property_id':event.property_id})})
                result['accepted']+=1
            except (ValidationError,ValueError) as e:
                result['rejected']+=1
                result['errors'].append({'index':i,'event_id':str(item.get('event_id','')),'code':'VALIDATION_ERROR','detail':str(e)[:2000]})
        c.execute(text('INSERT INTO ingest_batches(batch_id,device_id,accepted,duplicate,rejected,errors) VALUES (:b,:d,:a,:du,:r,CAST(:e AS jsonb))'),
                  {'b':str(batch.batch_id),'d':scope['device_id'],'a':result['accepted'],'du':result['duplicate'],'r':result['rejected'],'e':json.dumps(result['errors'])})
        if result['accepted']:
            c.execute(text('UPDATE derivation_state SET generation=generation+1 WHERE vehicle_id=:v'),{'v':scope['vehicle_id']})
    return result


def check_vehicle(vehicle_id,scope):
    if vehicle_id!=scope['vehicle_id']: raise HTTPException(403,'Vehicle outside key scope')


def get_trips(c,v):
    return rows(c,'SELECT t.*,m.metrics FROM trips t JOIN trip_metrics m ON m.trip_id=t.id WHERE t.vehicle_id=:v ORDER BY t.start_ts DESC',{'v':v})


@app.get('/api/v1/summary/incar')
def summary(vehicle_id:str, response:Response, scope=Depends(device)):
    check_vehicle(vehicle_id,scope)
    with engine.connect() as c: state=one(c,'SELECT summary,processed_generation,generation FROM derivation_state WHERE vehicle_id=:v',{'v':vehicle_id})
    response.headers['Cache-Control']='private, max-age=30'
    response.headers['ETag']=f'"{state["processed_generation"]}"'
    return {**(state['summary'] or {'vehicle_id':vehicle_id,'synthetic':scope['synthetic'],'trip_count':0,'last_trips':[]}),
            'pending_derivation':state['generation']!=state['processed_generation']}


@app.get('/api/v1/trips')
def trips(vehicle_id:str,scope=Depends(device)):
    check_vehicle(vehicle_id,scope)
    with engine.connect() as c: return get_trips(c,vehicle_id)


@app.get('/api/v1/trips/{trip_id}')
def trip_detail(trip_id:UUID,scope=Depends(device)):
    with engine.connect() as c:
        t=one(c,'SELECT t.*,m.metrics FROM trips t JOIN trip_metrics m ON m.trip_id=t.id WHERE t.id=:id AND t.vehicle_id=:v',{'id':str(trip_id),'v':scope['vehicle_id']})
        if not t: raise HTTPException(404,'Trip not found')
        samples=rows(c,'''SELECT s.payload,s.received_ts FROM telemetry_samples s JOIN trip_samples m ON m.event_id=s.event_id AND m.source_ts=s.source_ts
        WHERE m.trip_id=:id ORDER BY s.source_ts,s.event_id''',{'id':str(trip_id)})
        baseline=rows(c,'''SELECT t.id,m.metrics FROM trips t JOIN trip_metrics m ON m.trip_id=t.id
        WHERE t.vehicle_id=:v AND t.route_id=:r AND t.state='COMPLETE' AND t.id<>:id''',{'v':scope['vehicle_id'],'r':t['route_id'],'id':str(trip_id)})
        return {**dict(t),'samples':[{**s['payload'],'received_ts':s['received_ts']} for s in samples],
                'segments':rows(c,'SELECT start_ts,end_ts,state FROM trip_segments WHERE trip_id=:id ORDER BY start_ts',{'id':str(trip_id)}),'route_baseline':baseline}


@app.get('/api/v1/charging')
def charging(vehicle_id:str,scope=Depends(device)):
    check_vehicle(vehicle_id,scope)
    with engine.connect() as c:
        return rows(c,'''SELECT s.*,m.metrics FROM charging_sessions s JOIN charging_metrics m ON m.charging_session_id=s.id WHERE s.vehicle_id=:v ORDER BY start_ts DESC''',{'v':vehicle_id})


@app.get('/api/v1/charging/{session_id}')
def charge_detail(session_id:UUID,scope=Depends(device)):
    with engine.connect() as c:
        s=one(c,'SELECT * FROM charging_sessions WHERE id=:id AND vehicle_id=:v',{'id':str(session_id),'v':scope['vehicle_id']})
        if not s: raise HTTPException(404,'Session not found')
        return rows(c,'''SELECT s.payload FROM telemetry_samples s JOIN charging_samples m ON m.event_id=s.event_id AND m.source_ts=s.source_ts WHERE m.charging_session_id=:id ORDER BY s.source_ts''',{'id':str(session_id)})


@app.get('/api/v1/raw')
def raw_samples(vehicle_id:str,offset:int=0,limit:int=500,signal:str|None=None,scope=Depends(device)):
    check_vehicle(vehicle_id,scope)
    if offset<0 or not 1<=limit<=5000: raise HTTPException(422,'Invalid pagination')
    with engine.connect() as c:
        return rows(c,'''SELECT payload,received_ts FROM telemetry_samples WHERE vehicle_id=:v AND (CAST(:s AS text) IS NULL OR property_name=CAST(:s AS text))
        ORDER BY source_ts,event_id OFFSET :o LIMIT :l''',{'v':vehicle_id,'s':signal,'o':offset,'l':limit})


@app.get('/api/v1/signals/coverage')
def signal_coverage(vehicle_id:str, window_hours:int=Query(default=168,ge=1,le=744),
                    end:datetime|None=None, offset:int=Query(default=0,ge=0),
                    limit:int=Query(default=100,ge=1,le=500), scope=Depends(device)):
    check_vehicle(vehicle_id,scope)
    if end is not None and end.utcoffset() is None:
        raise HTTPException(422,'end must include a timezone offset')
    with engine.connect() as c:
        # One statement gives a consistent window/count/page without loading raw payloads.
        result=one(c,'''WITH anchor AS (
          SELECT coalesce(CAST(:end AS timestamptz),max(source_ts)) AS end_ts
          FROM telemetry_samples WHERE vehicle_id=:v
        ), windowed AS (
          SELECT s.*, s.payload->>'unit_canonical' AS unit,
            s.payload->'meta'->>'boot_id' AS boot_id,
            coalesce(s.payload->'meta'->>'probe_kind'='PROPERTY_INITIAL_READ',false) AS snapshot
          FROM telemetry_samples s CROSS JOIN anchor a
          WHERE s.vehicle_id=:v AND s.source_ts>=a.end_ts-make_interval(hours=>:hours) AND s.source_ts<=a.end_ts
        ), ordered AS (
          SELECT *, CASE WHEN NOT snapshot THEN
            source_ts-lag(source_ts) OVER (
              PARTITION BY device_id,boot_id,property_name,property_id,area_id,unit,snapshot
              ORDER BY source_ts,event_id) END AS gap
          FROM windowed
        ), grouped AS (
          SELECT device_id,boot_id,property_name,property_id,area_id,unit AS unit_canonical,
            count(*) AS samples, count(*) FILTER (WHERE status='available') AS available_samples,
            count(*) FILTER (WHERE status='unavailable') AS unavailable_samples,
            count(*) FILTER (WHERE status='error') AS error_samples,
            count(*) FILTER (WHERE snapshot) AS snapshot_samples,
            count(*) FILTER (WHERE NOT snapshot) AS live_samples,
            count(*) FILTER (WHERE NOT snapshot AND status='available') AS live_available_samples,
            min(source_ts) AS first_ts,max(source_ts) AS last_ts,max(received_ts) AS last_received_ts,
            min(source_ts) FILTER (WHERE NOT snapshot) AS first_live_ts,
            max(source_ts) FILTER (WHERE NOT snapshot) AS last_live_ts,
            max(extract(epoch FROM gap)) AS largest_live_gap_s,
            count(*) FILTER (WHERE gap>interval '0 seconds') AS positive_intervals,
            sum(extract(epoch FROM gap)) FILTER (WHERE gap>interval '0 seconds') AS interval_seconds
          FROM ordered GROUP BY device_id,boot_id,property_name,property_id,area_id,unit
        ), page AS (
          SELECT *, CASE WHEN interval_seconds>0 THEN positive_intervals/interval_seconds END AS observed_interval_hz
          FROM grouped ORDER BY property_name,property_id,area_id,device_id,boot_id,unit_canonical
          OFFSET :offset LIMIT :limit
        ) SELECT (SELECT end_ts FROM anchor) AS window_end,
          (SELECT end_ts-make_interval(hours=>:hours) FROM anchor) AS window_start,
          (SELECT count(*) FROM windowed) AS sample_count,
          (SELECT count(*) FROM grouped) AS total_groups,
          (SELECT coalesce(jsonb_agg(to_jsonb(page)),'[]'::jsonb) FROM page) AS signals,
          (SELECT coalesce(jsonb_agg(DISTINCT property_name),'[]'::jsonb) FROM windowed) AS observed_names''',
          {'v':vehicle_id,'end':end,'hours':window_hours,'offset':offset,'limit':limit})
    now=datetime.now(timezone.utc)
    for signal in result['signals']:
        latest=datetime.fromisoformat(signal['last_ts'])
        signal['age_s']=max(0,(now-latest).total_seconds())
        signal['source_clock_in_future']=latest>now
        signal['evidence']='LIVE_AVAILABLE_OBSERVED' if signal['live_available_samples'] else 'SNAPSHOT_ONLY' if not signal['live_samples'] else 'NO_AVAILABLE_LIVE_SAMPLES'
        signal.pop('interval_seconds'); signal.pop('positive_intervals')
    return {'vehicle_id':vehicle_id,'synthetic':scope['synthetic'],
            'generated_at':now.isoformat(),'window_hours':window_hours,'offset':offset,'limit':limit,
            **dict(result),'unobserved_known_signals':sorted(set(SIGNALS)-set(result['observed_names'])),
            'caveat':'Received observations only. Not proof of VHAL absence, permissions, subscription success or continuous coverage. Cadence uses positive live source-time intervals within each device/boot/property/area/unit group; snapshots are excluded.'}


@app.get('/api/v1/export/trips')
def export_trips(vehicle_id:str,format:str='json',scope=Depends(device)):
    check_vehicle(vehicle_id,scope)
    with engine.connect() as c: data=get_trips(c,vehicle_id)
    if format=='json': return data
    if format!='csv': raise HTTPException(422,'Use json or csv')
    out=io.StringIO(); w=csv.writer(out)
    names=sorted({k for t in data for k,m in t['metrics'].items() if not isinstance(m['value'],(dict,list))})
    w.writerow(['id','start_ts','state','route_id','synthetic']+[f'{n}.{f}' for n in names for f in ('value','reason')])
    for t in data: w.writerow([t[k] for k in ('id','start_ts','state','route_id','synthetic')]+[t['metrics'].get(n,{}).get(f) for n in names for f in ('value','reason')])
    return Response(out.getvalue(),media_type='text/csv',headers={'Content-Disposition':'attachment; filename=trips-si.csv'})


class View(BaseModel):
    name:str=Field(min_length=1,max_length=128)
    filters:dict


@app.get('/api/v1/views')
def views(scope=Depends(device)):
    with engine.connect() as c: return rows(c,'SELECT id,definition FROM saved_views WHERE vehicle_id=:v',{'v':scope['vehicle_id']})


@app.post('/api/v1/views')
def save_view(view:View,scope=Depends(device)):
    vid=str(uuid4())
    with engine.begin() as c: c.execute(text('INSERT INTO saved_views VALUES (:id,:v,CAST(:d AS jsonb))'),{'id':vid,'v':scope['vehicle_id'],'d':view.model_dump_json()})
    return {'id':vid}


@app.post('/api/v1/analysis')
def analyze(spec:dict,scope=Depends(device)):
    with engine.begin() as c:
        data=get_trips(c,scope['vehicle_id'])
        try: result=regression(data,spec)
        except ValueError as e: raise HTTPException(422,str(e))
        aid=str(uuid4()); result['algorithm_version']=VERSION
        result['input_hashes']={str(t['id']):t['input_hash'] for t in data}
        c.execute(text('INSERT INTO analysis_runs(id,vehicle_id,definition,result) VALUES (:id,:v,CAST(:d AS jsonb),CAST(:r AS jsonb))'),{'id':aid,'v':scope['vehicle_id'],'d':json.dumps(spec),'r':json.dumps(result)})
    return {'id':aid,**result}


@app.get('/api/v1/analysis')
def analyses(scope=Depends(device)):
    with engine.connect() as c: return rows(c,'SELECT * FROM analysis_runs WHERE vehicle_id=:v ORDER BY created_at DESC',{'v':scope['vehicle_id']})


class Provision(BaseModel):
    device_id:str=Field(min_length=1,max_length=128)
    vehicle_id:str=Field(min_length=1,max_length=128)
    name:str=Field(min_length=1,max_length=128)
    synthetic:bool=False


@app.post('/api/v1/admin/devices')
def provision(p:Provision,_=Depends(admin)):
    if p.synthetic and os.environ.get('ALLOW_SYNTHETIC')!='true': raise HTTPException(403,'Synthetic ingestion disabled')
    with engine.begin() as c:
        existing=one(c,'SELECT synthetic FROM vehicles WHERE id=:v',{'v':p.vehicle_id})
        if existing and existing['synthetic']!=p.synthetic: raise HTTPException(409,'Vehicle synthetic flag cannot change')
        if one(c,'SELECT id FROM devices WHERE id=:d',{'d':p.device_id}): raise HTTPException(409,'Device exists; rotate its key instead')
        c.execute(text('INSERT INTO vehicles VALUES (:v,:n,:s) ON CONFLICT DO NOTHING'),{'v':p.vehicle_id,'n':p.name,'s':p.synthetic})
        c.execute(text('INSERT INTO devices VALUES (:d,:v,:n)'),{'d':p.device_id,'v':p.vehicle_id,'n':p.name})
        c.execute(text('INSERT INTO derivation_state(vehicle_id) VALUES (:v) ON CONFLICT DO NOTHING'),{'v':p.vehicle_id})
        return issue_key(c,p.device_id)


@app.post('/api/v1/admin/keys/{key_id}/revoke')
def revoke(key_id:UUID,_=Depends(admin)):
    with engine.begin() as c: c.execute(text('UPDATE api_keys SET revoked=true WHERE id=:id'),{'id':str(key_id)})
    return {'revoked':True}


@app.post('/api/v1/admin/devices/{device_id}/rotate')
def rotate(device_id:str,_=Depends(admin)):
    with engine.begin() as c:
        if not one(c,'SELECT id FROM devices WHERE id=:d',{'d':device_id}): raise HTTPException(404,'Device not found')
        c.execute(text('UPDATE api_keys SET revoked=true WHERE device_id=:d'),{'d':device_id})
        return issue_key(c,device_id)


@app.get('/api/v1/admin/derivation/{vehicle_id}')
def derivation_config(vehicle_id:str,_=Depends(admin)):
    with engine.connect() as c:
        state=one(c,'SELECT config,generation,processed_generation FROM derivation_state WHERE vehicle_id=:v',{'v':vehicle_id})
        if not state: raise HTTPException(404,'Vehicle not found')
        return {**dict(state),'config':DerivationConfig.model_validate(state['config']).model_dump(),'algorithm_version':VERSION}


@app.post('/api/v1/admin/derivation/{vehicle_id}')
def configure_derivation(vehicle_id:str,config:DerivationConfig,_=Depends(admin)):
    with engine.begin() as c:
        updated=one(c,'''UPDATE derivation_state SET config=CAST(:config AS jsonb),generation=generation+1
        WHERE vehicle_id=:v RETURNING generation''',{'v':vehicle_id,'config':config.model_dump_json()})
        if not updated: raise HTTPException(404,'Vehicle not found')
    return {'config':config.model_dump(),'generation':updated['generation'],'pending_derivation':True,'algorithm_version':VERSION}


@app.post('/api/v1/admin/reprocess/{vehicle_id}')
def reprocess(vehicle_id:str,_=Depends(admin)):
    result=process(vehicle_id,True)
    if result is None: raise HTTPException(404,'Vehicle not found')
    return {'trips':len(result['trips']),'charging_sessions':len(result['charging_sessions']),'algorithm_version':VERSION}


@app.get('/api/v1/admin/status')
def admin_status(_=Depends(admin)):
    with engine.connect() as c:
        size=one(c,'SELECT pg_database_size(current_database()) AS bytes')['bytes']
        growth=one(c,"SELECT count(*) AS samples_24h,coalesce(sum(pg_column_size(payload)),0) AS raw_payload_bytes_24h FROM telemetry_samples WHERE received_ts>now()-interval '24 hours'")
        return {'database_bytes':size,'growth':dict(growth),'retention':'forever; deletion disabled',
                'devices':rows(c,'SELECT d.*,v.synthetic FROM devices d JOIN vehicles v ON v.id=d.vehicle_id'),
                'keys':rows(c,'SELECT id,device_id,revoked,created_at FROM api_keys'),
                'batches':rows(c,'SELECT * FROM ingest_batches ORDER BY id DESC LIMIT 100'),
                'quality_issues':rows(c,'SELECT * FROM data_quality_issues ORDER BY id DESC LIMIT 100'),
                'worker':rows(c,'SELECT * FROM worker_heartbeat'),
                'backup_instructions':'make backup OUT=/absolute/private/path/backup.dump; verify using make restore-check'}
