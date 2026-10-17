import json
import sys
import time
from datetime import datetime, timezone, timedelta
from apscheduler.schedulers.blocking import BlockingScheduler
from sqlalchemy import text
from app.db import engine, rows, one
from app.derive import derive, VERSION, metric


def process(vehicle_id, force=False):
    with engine.begin() as c:
        # Per-vehicle transaction lock also synchronizes generation updates and manual reprocess.
        lock_query='SELECT * FROM derivation_state WHERE vehicle_id=:v FOR UPDATE'+('' if force else ' SKIP LOCKED')
        state=one(c,lock_query,{'v':vehicle_id})
        if not state or (not force and state['generation']==state['processed_generation'] and
                          (state['summary'] or {}).get('algorithm_version')==VERSION): return None
        events=[r['payload'] for r in rows(c,'SELECT payload FROM telemetry_samples WHERE vehicle_id=:v ORDER BY source_ts,event_id',{'v':vehicle_id})]
        result=derive(events,state['config'])
        synthetic=one(c,'SELECT synthetic FROM vehicles WHERE id=:v',{'v':vehicle_id})['synthetic']
        c.execute(text('DELETE FROM trips WHERE vehicle_id=:v'),{'v':vehicle_id})
        c.execute(text('DELETE FROM charging_sessions WHERE vehicle_id=:v'),{'v':vehicle_id})
        c.execute(text('DELETE FROM data_quality_issues WHERE vehicle_id=:v'),{'v':vehicle_id})
        for trip in result['trips']:
            params={**trip,'synthetic':synthetic,'boundary_reasons':json.dumps(trip['boundary_reasons']),'metadata':json.dumps(trip['metadata'])}
            c.execute(text('''INSERT INTO trips(id,vehicle_id,start_ts,end_ts,state,confidence,boundary_reasons,algorithm_version,input_hash,route_id,synthetic,metadata)
            VALUES (:id,:vehicle_id,:start_ts,:end_ts,:state,:confidence,CAST(:boundary_reasons AS jsonb),:algorithm_version,:input_hash,:route_id,:synthetic,CAST(:metadata AS jsonb))'''),params)
            c.execute(text('INSERT INTO trip_metrics VALUES (:id,:version,CAST(:m AS jsonb))'),{'id':trip['id'],'version':VERSION,'m':json.dumps(trip['metrics'])})
            for seg in trip['segments']:
                c.execute(text('INSERT INTO trip_segments(trip_id,start_ts,end_ts,state) VALUES (:id,:start_ts,:end_ts,:state)'),{'id':trip['id'],**seg})
            c.execute(text('''INSERT INTO trip_samples SELECT :id,event_id,source_ts FROM telemetry_samples
            WHERE vehicle_id=:v AND event_id=ANY(CAST(:ids AS uuid[]))'''),{'id':trip['id'],'v':vehicle_id,'ids':trip['event_ids']})
            if trip['route_id']:
                c.execute(text("INSERT INTO routes VALUES (:id,:v,'{}') ON CONFLICT DO NOTHING"),{'id':trip['route_id'],'v':vehicle_id})
                c.execute(text('INSERT INTO route_matches VALUES (:t,:r,1)'),{'t':trip['id'],'r':trip['route_id']})
        for charge in result['charging_sessions']:
            c.execute(text('''INSERT INTO charging_sessions VALUES (:id,:vehicle_id,:start_ts,:end_ts,:algorithm_version,CAST(:metadata AS jsonb))'''),{**charge,'metadata':json.dumps(charge['metadata'])})
            c.execute(text('INSERT INTO charging_metrics VALUES (:id,CAST(:m AS jsonb),:v)'),{'id':charge['id'],'m':json.dumps(charge['metrics']),'v':VERSION})
            c.execute(text('''INSERT INTO charging_samples SELECT :id,event_id,source_ts FROM telemetry_samples
            WHERE vehicle_id=:v AND event_id=ANY(CAST(:ids AS uuid[]))'''),{'id':charge['id'],'v':vehicle_id,'ids':charge['event_ids']})
        for issue in result['issues']:
            c.execute(text('INSERT INTO data_quality_issues(vehicle_id,algorithm_version,definition) VALUES (:v,:a,CAST(:d AS jsonb))'),{'v':vehicle_id,'a':VERSION,'d':json.dumps(issue)})
        # Latest observation anchors rolling windows; no synthetic clock or invented freshness.
        anchor=max((datetime.fromisoformat(e['source_ts'].replace('Z','+00:00')) for e in events),default=None)
        complete=[t for t in result['trips'] if t['state']=='COMPLETE']
        recent=[t for t in complete if anchor and datetime.fromisoformat(t['start_ts'].replace('Z','+00:00'))>=anchor-timedelta(days=30)]
        def efficiency(data):
            usable=[t['metrics'] for t in data if t['metrics']['energy_per_distance_j_m']['value'] is not None]
            d=sum(m['distance_m']['value'] for m in usable)
            return metric(sum(m['net_energy_j']['value'] for m in usable)/d if d>0 else None,'NO_COMPLETE_TRIPS_WITH_ENERGY_AND_DISTANCE')
        curve=[]
        for low in range(250,311,5):
            group=[t for t in recent if t['metrics']['temp_mean_k']['value'] is not None and low<=t['metrics']['temp_mean_k']['value']<low+5]
            if group: curve.append({'temperature_k':low+2.5,'n':len(group),'energy_per_distance_j_m':efficiency(group)})
        summary={'vehicle_id':vehicle_id,'synthetic':synthetic,'algorithm_version':VERSION,
                 'as_of':anchor.isoformat() if anchor else None,'trip_count':len(result['trips']),
                 'complete_trip_count':len(complete),'charging_count':len(result['charging_sessions']),
                 'total_distance_m':sum(t['metrics']['distance_m']['value'] or 0 for t in complete),
                 'rolling_efficiency':efficiency(complete[-7:]),'baseline_30d':efficiency(recent),
                 'temperature_curve':curve,'last_trips':[{**{k:t[k] for k in ('id','start_ts','end_ts','state','route_id')},
                     'metrics':{k:t['metrics'][k] for k in ('distance_m','energy_per_distance_j_m','temp_mean_k','duration_s')}}
                     for t in result['trips'][-10:][::-1]],
                 'quality_issue_count':len(result['issues'])}
        c.execute(text('''UPDATE derivation_state SET processed_generation=:g,updated_at=now(),summary=CAST(:s AS jsonb) WHERE vehicle_id=:v'''),{'g':state['generation'],'s':json.dumps(summary),'v':vehicle_id})
        return result


def tick():
    with engine.begin() as c:
        pending=rows(c,"SELECT vehicle_id FROM derivation_state WHERE generation<>processed_generation OR summary->>'algorithm_version' IS DISTINCT FROM :version",{'version':VERSION})
    for row in pending: process(row['vehicle_id'])
    with engine.begin() as c:
        c.execute(text('INSERT INTO worker_heartbeat VALUES (1,now()) ON CONFLICT(id) DO UPDATE SET updated_at=now()'))


if __name__=='__main__':
    if '--health' in sys.argv:
        with engine.connect() as c:
            healthy=one(c,"SELECT updated_at>now()-interval '90 seconds' AS ok FROM worker_heartbeat WHERE id=1")
        sys.exit(0 if healthy and healthy['ok'] else 1)
    tick()
    scheduler=BlockingScheduler(timezone='UTC')
    scheduler.add_job(tick,'interval',seconds=15,max_instances=1,coalesce=True)
    scheduler.start()
