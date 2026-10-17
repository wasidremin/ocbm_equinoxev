import copy
import gzip
import json
import os
from pathlib import Path
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import text
from app.contracts import Event
from app.derive import derive, trip_metrics, DEFAULT_CONFIG, VERSION
from app.import_probe import convert
from app.main import app
from app.db import engine


from samples.test_fixtures import event, drive, probe_report, charge, metric_edge_cases, boundary_cases, lifecycle, comprehensive_probe_report


def test_exactly_one_value():
    e=event(); e['value_bool']=False
    with pytest.raises(ValueError): Event.model_validate(e)
    e=event(); e['value_num']=None
    with pytest.raises(ValueError): Event.model_validate(e)


def test_utc_and_units():
    e=event(); e['source_ts']='2026-01-01T00:00:00-05:00'
    with pytest.raises(ValueError): Event.model_validate(e)
    e=event(); e['unit_canonical']='mph'
    with pytest.raises(ValueError): Event.model_validate(e)


def test_unknown_vendor_and_nonfinite():
    e=event(name='UNKNOWN_GM_VENDOR',unit='unknown'); assert Event.model_validate(e)
    e['meta']['bad']=float('nan')
    with pytest.raises(ValueError): Event.model_validate(e)


def test_short_stop_merges_and_preserves_segments():
    r=derive(drive()); assert len(r['trips'])==1
    t=r['trips'][0]; assert t['state']=='COMPLETE'
    assert [s['state'] for s in t['segments']]==['DRIVING','SHORT_STOP','DRIVING','SHORT_STOP','COMPLETING']


def test_out_of_order_exact_recompute():
    data=drive(); assert derive(data)==derive(list(reversed(data)))
    assert derive(data)==derive(data)


def test_gap_partial_and_single_signal_review():
    assert derive([event(),event(300)])['trips'][0]['state']=='NEEDS_REVIEW'
    assert derive([event(t) for t in range(0,600,30)])['trips'][0]['state']=='NEEDS_REVIEW'


def test_null_missing_metrics_and_counter_reset():
    m=trip_metrics([event(),event(30)],DEFAULT_CONFIG)
    assert m['net_energy_j']['value'] is None and m['net_energy_j']['reason']=='MISSING_ENDPOINTS'
    assert m['distance_wheel_m']['reason']=='MISSING_WHEEL_SCALE_OR_SAMPLES'
    events=[event(0,'PERF_ODOMETER',100,'m'),event(30,'PERF_ODOMETER',10,'m')]
    assert trip_metrics(events,DEFAULT_CONFIG)['distance_odometer_m']['reason']=='COUNTER_RESET'


def test_boot_gap_not_integrated():
    a,b=event(),event(30); b['meta']['boot_id']='new-boot'
    assert trip_metrics([a,b],DEFAULT_CONFIG)['distance_speed_m']['reason']=='INSUFFICIENT_CONTIGUOUS_SAMPLES'


def test_probe_conversion_no_guessed_timezone():
    report=probe_report()
    e=convert(report,'d','v','boot',-300)[0]['events'][0]
    assert e['value_num']==180000000 and e['unit_canonical']=='J' and e['tz_offset_min']==-300
    Event.model_validate(e)


def test_probe_bridge_preserves_all_observations_and_normalizes():
    report=comprehensive_probe_report(); batches=convert(report,'d','v','boot',-300)
    events=batches[0]['events']
    assert len(events)==len(report['rawLiveEvents'])+1
    assert batches==convert(report,'d','v','boot',-300)
    assert len({e['event_id'] for e in events})==len(events)
    for e in events:
        Event.model_validate(e)
        assert e['source_ts']=='2026-01-01T05:00:00Z'
        assert e['build_fingerprint']==report['fingerprint']
    live=events[1:]
    assert [e['meta']['probe_raw_event'] for e in live]==report['rawLiveEvents']
    assert live[0]['value_str']=='DRIVE' and live[0]['meta']['probe_raw_event']['data']['value']==8
    assert live[1]['value_str']=='ON'
    assert live[2]['value_bool'] is True and live[2]['unit_canonical']=='bool'
    assert live[3]['unit_canonical']=='bool'
    assert live[4]['meta']['wheel_m_per_tick']==[.1,.2,.3,None]
    assert live[5]['value_arr']==[1,'raw',None] and live[5]['unit_canonical']=='unknown'
    assert live[6]['property_name']=='GPS_POSITION' and live[6]['status']=='available'
    assert live[6]['lat']==40. and live[6]['meta']['probe_raw_event']['data']['provider']=='gps'
    assert live[7]['property_name']=='PROBE_DIAGNOSTIC'
    assert live[8]['status']=='error' and live[8]['area_id']==2
    assert live[9]['value_int']==999  # Unknown enums are not guessed.
    assert events[0]['value_num']==216000000
    assert events[0]['meta']['census_receipt_not_sensor_utc'] is True


def test_probe_invalid_wheel_config_and_census_timestamp():
    from app.import_probe import wheel_scales
    for cfg in (None,[],[3,0,1,0,0],[16,1,1,1,1],[3,True,1,0,0],[3,1.5,1,0,0]):
        assert wheel_scales(cfg) is None
    assert wheel_scales([4,0,0,0,100000])==[None,None,None,.1]
    report=comprehensive_probe_report(); report.pop('startWallTime')
    with pytest.raises(ValueError,match='no guessed timestamp'): convert(report,'d','v','boot',0)
    report=probe_report(); report['rawLiveEvents'][0]['wallTime']='2026-01-01T00:00:00'
    with pytest.raises(ValueError,match='timezone offset'): convert(report,'d','v','boot',0)


def test_probe_bridge_cli(tmp_path):
    import subprocess
    import sys
    report=comprehensive_probe_report(); path=tmp_path/'report.json'; path.write_text(json.dumps(report))
    cmd=[sys.executable,'-m','app.import_probe',str(path),'--device-id','d','--vehicle-id','v','--boot-id','boot','--tz-offset-min=-300']
    result=subprocess.run(cmd,capture_output=True,text=True,check=True)
    assert [json.loads(line) for line in result.stdout.splitlines()]==convert(report,'d','v','boot',-300)


def test_interrupted_charging_keeps_plugged_session():
    r=derive(charge())
    assert len(r['charging_sessions'])==1
    m=r['charging_sessions'][0]['metrics']
    assert m['plugged_duration_s']['value']==600
    assert 0<m['charging_duration_s']['value']<600
    assert m['efficiency']['value'] is None and m['efficiency']['reason']=='MISSING_WALL_METER_OR_BATTERY'


def test_clock_drift_and_elapsed_boot_epochs():
    a,b=event(),event(30); b['source_elapsed_ns']=10**9
    assert any(i['code']=='CLOCK_DRIFT' for i in derive([a,b])['issues'])
    b['meta']['boot_id']='other-boot'
    assert not any(i['code']=='CLOCK_DRIFT' for i in derive([a,b])['issues'])


def test_long_stop_splits_trips():
    r=derive(drive(stop=240))
    assert len(r['trips'])==2
    assert r['trips'][0]['state']=='COMPLETE'


def test_duration_weighted_hvac_and_missing_endpoints():
    data=metric_edge_cases()['hvac']
    assert trip_metrics(data,DEFAULT_CONFIG)['hvac_on_fraction']['value']==pytest.approx(1/6)
    extended=data+[event(90)]
    assert trip_metrics(extended,DEFAULT_CONFIG)['hvac_on_fraction']['reason']=='HVAC_ENDPOINT_COVERAGE_MISSING'
    data[1]['status']='unavailable'
    assert trip_metrics(data,DEFAULT_CONFIG)['hvac_on_fraction']['reason']=='UNAVAILABLE_OR_NONBOOLEAN_SAMPLE'


def test_unavailable_speed_does_not_bridge_and_devices_do_not_share_epochs():
    data=metric_edge_cases()
    assert trip_metrics(data['speed'],DEFAULT_CONFIG)['distance_speed_m']['reason']=='UNAVAILABLE_OR_NONNUMERIC_SAMPLE'
    assert trip_metrics(data['mixed'],DEFAULT_CONFIG)['distance_speed_m']['value'] is None
    power=data['speed']
    for e in power: e.update(property_name='EV_BATTERY_INSTANTANEOUS_CHARGE_RATE',unit_canonical='W',unit_original='W')
    assert trip_metrics(power,DEFAULT_CONFIG)['gross_discharge_j']['value'] is None


def test_wheel_reversal_scales_resets_and_gaps():
    data=metric_edge_cases()['wheel']
    assert trip_metrics(data,DEFAULT_CONFIG)['distance_wheel_m']['value']==20
    changed=copy.deepcopy(data); changed[-1]['meta']['wheel_m_per_tick']=[.2,.2,None,None]
    assert trip_metrics(changed,DEFAULT_CONFIG)['distance_wheel_m']['reason']=='WHEEL_SCALE_CHANGED'
    reset=copy.deepcopy(data); reset[-1]['value_arr'][0]=1
    assert trip_metrics(reset,DEFAULT_CONFIG)['distance_wheel_m']['reason']=='WHEEL_RESET'
    missing=copy.deepcopy(data); missing[-1]['meta'].pop('wheel_m_per_tick')
    assert trip_metrics(missing,DEFAULT_CONFIG)['distance_wheel_m']['reason']=='MISSING_OR_INVALID_WHEEL_SCALE'
    assert trip_metrics(data, {**DEFAULT_CONFIG,'max_gap_s':10})['distance_wheel_m']['reason']=='GAP_OR_BOOT_BOUNDARY'


def test_distance_priority_is_explicit_and_validated():
    from app.derive import DerivationConfig
    data=metric_edge_cases()['distance']
    assert trip_metrics(data,DEFAULT_CONFIG)['distance_m']['value']==200
    config={**DEFAULT_CONFIG,'distance_priority':['distance_speed_m','distance_odometer_m','distance_wheel_m','distance_gps_m']}
    result=trip_metrics(data,config)
    assert result['distance_m']['value']==300 and result['preferred_distance']['value']=='distance_speed_m'
    assert 'distance_speed_m' in result['preferred_distance_reason']['value']
    with pytest.raises(ValueError): DerivationConfig(distance_priority=['distance_speed_m']*4)
    with pytest.raises(ValueError): derive(data,{'max_gap_s':-1})


@pytest.mark.parametrize('kind,reason', [('wheel','WHEEL_MOTION'),('gps','GPS_POSITION_MOTION')])
def test_displacement_only_boundaries_preserve_anchor_and_recompute(kind,reason):
    data=boundary_cases(kind)
    result=derive(data)
    assert result==derive(list(reversed(data)))
    assert len(result['trips'])==1
    trip=result['trips'][0]
    assert trip['state']==('COMPLETE' if kind=='wheel' else 'NEEDS_REVIEW') and reason in trip['boundary_reasons']
    assert 'SPEED_MOTION' not in trip['boundary_reasons']
    assert trip['start_ts']==data[0]['source_ts']
    assert len(trip['event_ids'])==len(data)
    assert trip['metrics']['distance_m']['value']==pytest.approx(900,rel=.001)
    if kind=='wheel':
        assert [s['state'] for s in trip['segments']]==['DRIVING','SHORT_STOP','DRIVING','SHORT_STOP','COMPLETING']
    else:
        assert 'UNKNOWN' in [s['state'] for s in trip['segments']]


def test_invalid_wheel_and_uncertain_gps_do_not_start():
    for mode in ('scale','reset','unavailable','area'):
        data=boundary_cases()
        for e in data:
            if e['property_name']=='WHEEL_TICK':
                if mode=='scale': e['meta'].pop('wheel_m_per_tick')
                if mode=='reset': e['value_arr'][0]=e['seq']
                if mode=='unavailable': e['status']='unavailable'
        if mode=='area': data+= [dict(copy.deepcopy(e),event_id=str(uuid4()),area_id=1) for e in data if e['property_name']=='WHEEL_TICK']
        assert derive(data)['trips']==[],mode
    data=boundary_cases('gps')
    for e in data:
        if e.get('lat') is not None: e['lon']*=.01; e['accuracy_m']=50.
    assert derive(data)['trips']==[]
    for e in data:
        if e.get('lat') is not None: e['accuracy_m']=None
    assert derive(data)['trips']==[]


def test_unavailable_position_without_coordinates_breaks_pair():
    data=[e for e in boundary_cases('gps') if e['property_name']=='GPS_POSITION'][:2]
    bad=event(15,'GPS_POSITION',unit='unknown'); bad['status']='unavailable'
    assert derive([data[0],bad,data[1]])['trips']==[]


def test_simultaneous_epochs_are_not_fused():
    first=drive(); second=copy.deepcopy(first)
    for e in second: e['event_id']=str(uuid4()); e['device_id']='second-device'
    result=derive(first+second)
    lookup={e['event_id']:e for e in first+second}
    assert result['issues']
    for trip in result['trips']:
        assert trip['state']=='NEEDS_REVIEW'
        assert len({lookup[i]['device_id'] for i in trip['event_ids']})==1


def test_unknown_motion_is_not_a_stop_and_ux_alone_is_not_movement():
    data=drive()
    for e in data:
        if e['property_name']=='PERF_VEHICLE_SPEED' and e['seq']>=180: e['status']='unavailable'
    trips=derive(data)['trips']
    assert len(trips)==1 and trips[0]['state']=='NEEDS_REVIEW'
    assert 'STOP_TIMEOUT' not in trips[0]['boundary_reasons']
    assert [s['state'] for s in trips[0]['segments']]==['DRIVING','UNKNOWN']
    ux=[]
    for t in (0,30,60):
        e=event(t,'UX_RESTRICTIONS',unit='bool'); e.pop('value_num'); e['value_bool']=True; ux.append(e)
    assert derive(ux)['trips']==[]


@pytest.mark.parametrize('field', ['boot','device'])
def test_epoch_changes_split_trip_and_charge_without_cached_evidence(field):
    data=drive()+charge()
    for e in data:
        if e['seq']>=300:
            if field=='boot': e['meta']['boot_id']='second-boot'
            else: e['device_id']='second-device'
            if e['property_name']=='GEAR_SELECTION': e['status']='unavailable'
    result=derive(data)
    assert len(result['trips'])==2 and len(result['charging_sessions'])==2
    assert all(t['state']=='NEEDS_REVIEW' for t in result['trips'])
    assert 'DEVICE_OR_BOOT_BOUNDARY' in result['trips'][0]['boundary_reasons']
    assert 'GEAR_DRIVING' not in result['trips'][1]['boundary_reasons']
    assert result['charging_sessions'][0]['metadata']['boundary_reason']=='DEVICE_OR_BOOT_BOUNDARY'
    lookup={e['event_id']:e for e in data}
    for segment in result['trips']+result['charging_sessions']:
        assert len({(lookup[i]['device_id'],lookup[i]['meta']['boot_id']) for i in segment['event_ids']})==1


def test_lifecycle_preparation_completion_membership_and_order():
    data=lifecycle(); result=derive(data)
    assert result==derive(list(reversed(data)))
    trip=result['trips'][0]
    assert trip['state']=='COMPLETE'
    assert [s['state'] for s in trip['segments']]==['PREPARING','DRIVING','SHORT_STOP','COMPLETING']
    assert trip['start_ts']==data[0]['source_ts']
    assert len(trip['event_ids'])==len(data)==len(set(trip['event_ids']))
    assert trip['metrics']['distance_m']['value']==600
    assert 'OBSERVED_PREPARATION' in trip['boundary_reasons']


def test_preparation_is_bounded_and_never_creates_stationary_trip():
    data=lifecycle()
    for e in data:
        if e['property_name']=='PERF_VEHICLE_SPEED': e['value_num']=0.
    assert derive(data)['trips']==[]
    for e in data:
        if e['property_name']=='GEAR_SELECTION': e['value_str']='DRIVE'
        if e['property_name']=='IGNITION_STATE': e['value_str']='ON'
        if e['property_name']=='PERF_VEHICLE_SPEED' and e['seq']>=240: e['value_num']=10.
    trip=derive(data)['trips'][0]
    assert trip['start_ts']==next(e['source_ts'] for e in data if e['seq']==150)
    assert trip['segments'][0]['state']=='PREPARING'
    assert trip['state']=='NEEDS_REVIEW'


def test_preparation_does_not_cross_gap_or_boot():
    data=lifecycle()
    for e in data:
        if e['seq']>=60: e['meta']['boot_id']='new-boot'
    assert derive(data)['trips'][0]['segments'][0]['state']=='DRIVING'
    data=[e for e in lifecycle() if e['seq']==0 or e['seq']>=120]
    for e in data:
        if e['seq']>=120 and e['property_name']=='PERF_VEHICLE_SPEED': e['value_num']=10.
    assert derive(data)['trips'][0]['segments'][0]['state']=='DRIVING'


def test_end_marker_while_moving_requires_review():
    data=drive()[:6]
    marker=event(60,'CLIENT_MARKER',unit='enum'); marker.pop('value_num'); marker['value_str']='TRIP_END'
    trip=derive(data+[marker])['trips'][0]
    assert trip['state']=='NEEDS_REVIEW'
    assert 'END_MARKER_WHILE_MOVING' in trip['boundary_reasons']
    assert trip['segments'][-1]['state']=='COMPLETING'


def test_unknown_interval_cannot_recover_complete_status():
    data=lifecycle()
    for e in data:
        if e['seq']==90 and e['property_name']=='PERF_VEHICLE_SPEED': e['status']='unavailable'
    trip=derive(data)['trips'][0]
    assert trip['state']=='NEEDS_REVIEW' and 'UNKNOWN_MOTION_INTERVAL' in trip['boundary_reasons']
    assert 'UNKNOWN' in [s['state'] for s in trip['segments']]


def test_marker_only_stationary_trip_has_no_observed_movement():
    data=[]
    for sec,marker in ((0,'TRIP_START'),(30,'TRIP_END')):
        data.append(event(sec,v=0))
        e=event(sec,'CLIENT_MARKER',unit='enum'); e.pop('value_num'); e['value_str']=marker; data.append(e)
    trip=derive(data,{'min_movement_m':0})['trips'][0]
    assert trip['state']=='NEEDS_REVIEW' and 'NO_OBSERVED_MOVEMENT' in trip['boundary_reasons']


def test_schema_matches_runtime():
    import jsonschema
    schema=json.loads(Path('/contracts/ingest.schema.json').read_text()) if Path('/contracts').exists() else Event.model_json_schema()
    jsonschema.validate(event(),schema)
    bad=event(); bad['value_bool']=False
    with pytest.raises(jsonschema.ValidationError): jsonschema.validate(bad,schema)
    assert schema==Event.model_json_schema()


@pytest.fixture
def client_scope():
    client=TestClient(app); suffix=str(uuid4())[:8]; d='test-'+suffix; v='test-'+suffix
    admin={'Authorization':'Bearer '+os.environ['ADMIN_TOKEN']}
    response=client.post('/api/v1/admin/devices',headers=admin,json={'device_id':d,'vehicle_id':v,'name':'SYNTHETIC integration test','synthetic':True})
    assert response.status_code==200,response.text
    headers={'Authorization':'Bearer '+response.json()['api_key']}
    return client,d,v,headers,admin


def test_real_api_gzip_duplicate_conflict_and_immutable(client_scope):
    client,d,v,h,admin=client_scope; e=event(); e.update(device_id=d,vehicle_id=v)
    batch={'batch_id':e['batch_id'],'events':[e]}
    r=client.post('/api/v1/ingest/batch',headers={**h,'Content-Encoding':'gzip'},content=gzip.compress(json.dumps(batch).encode()))
    assert r.status_code==200 and r.json()['accepted']==1
    assert client.post('/api/v1/ingest/batch',headers=h,json=batch).json()['duplicate']==1
    batch['events'][0]['value_num']=99.
    assert client.post('/api/v1/ingest/batch',headers=h,json=batch).json()['errors'][0]['code']=='EVENT_ID_CONFLICT'
    with pytest.raises(Exception,match='immutable'):
        with engine.begin() as c: c.execute(text('UPDATE telemetry_samples SET value_num=0 WHERE event_id=:e'),{'e':e['event_id']})
    assert client.get('/api/v1/trips',headers=h,params={'vehicle_id':'other'}).status_code==403


def test_real_api_partial_rejections_out_of_order_and_reprocess(client_scope):
    client,d,v,h,admin=client_scope; data=drive(); split=len(data)//2
    for chunk in (data[split:],data[:split]):
        bid=str(uuid4())
        for e in chunk: e.update(device_id=d,vehicle_id=v,batch_id=bid)
        bad=copy.deepcopy(chunk[0]); bad['event_id']=str(uuid4()); bad['value_bool']=True
        r=client.post('/api/v1/ingest/batch',headers=h,json={'batch_id':bid,'events':chunk+[bad]})
        assert r.json()['accepted']==len(chunk) and r.json()['rejected']==1,r.text
    url='/api/v1/admin/reprocess/'+v
    assert client.post(url,headers=admin).status_code==200
    first=client.get('/api/v1/trips',headers=h,params={'vehicle_id':v}).json()
    client.post(url,headers=admin)
    second=client.get('/api/v1/trips',headers=h,params={'vehicle_id':v}).json()
    assert first==second and first[0]['state']=='COMPLETE'
    detail=client.get('/api/v1/trips/'+first[0]['id'],headers=h).json()
    assert len(detail['samples'])==len(data)


def test_config_api_reprocess_and_raw_immutability(client_scope):
    client,d,v,h,admin=client_scope
    config_url='/api/v1/admin/derivation/'+v
    assert client.get(config_url,headers=h).status_code==401
    initial=client.get(config_url,headers=admin).json()
    assert initial['config']['distance_priority'][0]=='distance_odometer_m'
    assert client.post(config_url,headers=admin,json={'max_gap_s':-1}).status_code==422
    assert client.post(config_url,headers=admin,json={'distance_priority':['distance_speed_m']}).status_code==422
    data=drive(); bid=str(uuid4())
    for e in data: e.update(device_id=d,vehicle_id=v,batch_id=bid)
    assert client.post('/api/v1/ingest/batch',headers=h,json={'batch_id':bid,'events':data}).json()['accepted']==len(data)
    config={**DEFAULT_CONFIG,'min_movement_m':10000}
    assert client.post(config_url,headers=admin,json=config).status_code==200
    assert client.post('/api/v1/admin/reprocess/'+v,headers=admin).status_code==200
    trips=client.get('/api/v1/trips',headers=h,params={'vehicle_id':v}).json()
    assert trips[0]['state']=='NEEDS_REVIEW'
    assert trips[0]['metadata']['config']['min_movement_m']==10000
    assert trips[0]['algorithm_version']==VERSION
    with engine.connect() as c:
        stored=list(c.execute(text('SELECT payload FROM telemetry_samples WHERE vehicle_id=:v'),{'v':v}).scalars())
    assert sorted(stored,key=lambda e:e['event_id'])==sorted([Event.model_validate(e).payload() for e in data],key=lambda e:e['event_id'])
    client.post('/api/v1/admin/reprocess/'+v,headers=admin)
    assert client.get('/api/v1/trips',headers=h,params={'vehicle_id':v}).json()==trips


@pytest.mark.parametrize('kind', ['wheel','gps'])
def test_displacement_ingest_reprocess_api(client_scope,kind):
    client,d,v,h,admin=client_scope; data=boundary_cases(kind); bid=str(uuid4())
    for e in data: e.update(device_id=d,vehicle_id=v,batch_id=bid)
    response=client.post('/api/v1/ingest/batch',headers=h,json={'batch_id':bid,'events':list(reversed(data))})
    assert response.status_code==200 and response.json()['accepted']==len(data),response.text
    url='/api/v1/admin/reprocess/'+v
    assert client.post(url,headers=admin).status_code==200
    trips=client.get('/api/v1/trips',headers=h,params={'vehicle_id':v}).json()
    assert len(trips)==1 and trips[0]['state']==('COMPLETE' if kind=='wheel' else 'NEEDS_REVIEW') and trips[0]['algorithm_version']==VERSION
    detail=client.get('/api/v1/trips/'+trips[0]['id'],headers=h).json()
    assert {e['event_id'] for e in detail['samples']}=={e['event_id'] for e in data}
    assert client.post(url,headers=admin).status_code==200
    assert client.get('/api/v1/trips',headers=h,params={'vehicle_id':v}).json()==trips


def test_lifecycle_api_segments_and_immutable_membership(client_scope):
    client,d,v,h,admin=client_scope; data=lifecycle(); bid=str(uuid4())
    for e in data: e.update(device_id=d,vehicle_id=v,batch_id=bid)
    response=client.post('/api/v1/ingest/batch',headers=h,json={'batch_id':bid,'events':list(reversed(data))})
    assert response.status_code==200 and response.json()['accepted']==len(data),response.text
    url='/api/v1/admin/reprocess/'+v
    assert client.post(url,headers=admin).status_code==200
    trips=client.get('/api/v1/trips',headers=h,params={'vehicle_id':v}).json()
    assert len(trips)==1 and trips[0]['state']=='COMPLETE'
    detail=client.get('/api/v1/trips/'+trips[0]['id'],headers=h).json()
    assert [s['state'] for s in detail['segments']]==['PREPARING','DRIVING','SHORT_STOP','COMPLETING']
    assert {e['event_id'] for e in detail['samples']}=={e['event_id'] for e in data}
    with engine.connect() as c:
        stored=list(c.execute(text('SELECT payload FROM telemetry_samples WHERE vehicle_id=:v'),{'v':v}).scalars())
    assert sorted(stored,key=lambda e:e['event_id'])==sorted([Event.model_validate(e).payload() for e in data],key=lambda e:e['event_id'])
    assert client.post(url,headers=admin).status_code==200
    assert client.get('/api/v1/trips/'+trips[0]['id'],headers=h).json()==detail


def test_probe_bridge_ingest_and_raw_api(client_scope):
    client,d,v,h,admin=client_scope; report=comprehensive_probe_report()
    batch=convert(report,d,v,'bridge-test-boot',-300)[0]
    for e in batch['events']: e['meta']['synthetic']=True
    response=client.post('/api/v1/ingest/batch',headers=h,json=batch)
    assert response.status_code==200 and response.json()['accepted']==len(batch['events']),response.text
    stored=client.get('/api/v1/raw',headers=h,params={'vehicle_id':v}).json()
    assert sorted([r['payload'] for r in stored],key=lambda e:e['event_id'])==sorted(batch['events'],key=lambda e:e['event_id'])
    filtered=client.get('/api/v1/raw',headers=h,params={'vehicle_id':v,'signal':'WHEEL_TICK'}).json()
    assert len(filtered)==1 and filtered[0]['payload']['meta']['wheel_m_per_tick']==[.1,.2,.3,None]
    assert client.get('/api/v1/raw',headers=h,params={'vehicle_id':'other'}).status_code==403
    response=client.post('/api/v1/ingest/batch',headers=h,json=batch)
    assert response.json()['duplicate']==len(batch['events']) and response.json()['rejected']==0


def test_coverage_window_groups_cadence_and_scope(client_scope):
    client,d,v,h,admin=client_scope; url='/api/v1/signals/coverage'
    params={'vehicle_id':v}
    assert client.get(url,params=params).status_code==401
    assert client.get(url,headers=h,params={'vehicle_id':'other'}).status_code==403
    empty=client.get(url,headers=h,params=params).json()
    assert empty['total_groups']==0 and empty['sample_count']==0 and empty['window_end'] is None
    assert 'PERF_VEHICLE_SPEED' in empty['unobserved_known_signals']
    for extra in ({'window_hours':0},{'window_hours':745},{'limit':501},{'offset':-1},{'end':'2026-01-01T00:00:00'}):
        assert client.get(url,headers=h,params={**params,**extra}).status_code==422
    data=[event(t) for t in (0,10,30)]
    data[1]['status']='unavailable'; data[2]['status']='error'
    snapshot=event(5); snapshot['meta']['probe_kind']='PROPERTY_INITIAL_READ'; data.append(snapshot)
    area=event(20); area['area_id']=2; data.append(area)
    boot=event(40); boot['meta']['boot_id']='other-boot'; data.append(boot)
    older=event(-7200); older['source_elapsed_ns']=0; older['seq']=0; data.append(older)
    vendor=event(25,'VENDOR_TEST',unit='unknown'); data.append(vendor)
    bid=str(uuid4())
    for e in data: e.update(device_id=d,vehicle_id=v,batch_id=bid)
    response=client.post('/api/v1/ingest/batch',headers=h,json={'batch_id':bid,'events':list(reversed(data))})
    assert response.status_code==200 and response.json()['accepted']==len(data),response.text
    r=client.get(url,headers=h,params={**params,'window_hours':1}).json()
    assert r['sample_count']==7 and r['total_groups']==4
    assert r['window_end'].startswith('2026-01-01T00:00:40')
    base=next(s for s in r['signals'] if s['property_name']=='PERF_VEHICLE_SPEED' and s['area_id']==0 and s['boot_id']=='synthetic-test-boot')
    assert (base['available_samples'],base['unavailable_samples'],base['error_samples'])==(2,1,1)
    assert (base['live_samples'],base['snapshot_samples'],base['live_available_samples'])==(3,1,1)
    assert base['largest_live_gap_s']==20 and base['observed_interval_hz']==pytest.approx(2/30)
    assert base['evidence']=='LIVE_AVAILABLE_OBSERVED'
    assert all(s['observed_interval_hz'] is None for s in r['signals'] if s is not base)
    assert 'PERF_VEHICLE_SPEED' not in r['unobserved_known_signals']
    assert 'EV_BATTERY_LEVEL' in r['unobserved_known_signals']
    pages=[client.get(url,headers=h,params={**params,'window_hours':1,'limit':1,'offset':i}).json()['signals'][0] for i in range(4)]
    assert [{k:x[k] for k in ('property_name','area_id','boot_id')} for x in pages]==[{k:x[k] for k in ('property_name','area_id','boot_id')} for x in r['signals']]
    historical=client.get(url,headers=h,params={**params,'window_hours':1,'end':'2026-01-01T00:00:15Z'}).json()
    assert historical['sample_count']==3


def test_coverage_snapshot_and_unavailable_are_not_live_availability(client_scope):
    client,d,v,h,admin=client_scope; data=[event(0,'STATIC_TEST',unit='unknown'),event(10,'ERROR_TEST',unit='unknown')]
    data[0]['meta']['probe_kind']='PROPERTY_INITIAL_READ'; data[1]['status']='unavailable'
    bid=str(uuid4())
    for e in data: e.update(device_id=d,vehicle_id=v,batch_id=bid)
    assert client.post('/api/v1/ingest/batch',headers=h,json={'batch_id':bid,'events':data}).json()['accepted']==2
    r=client.get('/api/v1/signals/coverage',headers=h,params={'vehicle_id':v}).json()
    assert {s['property_name']:s['evidence'] for s in r['signals']}=={'STATIC_TEST':'SNAPSHOT_ONLY','ERROR_TEST':'NO_AVAILABLE_LIVE_SAMPLES'}
    assert all(s['observed_interval_hz'] is None for s in r['signals'])


def test_key_revoke_and_limits(client_scope):
    client,d,v,h,admin=client_scope
    key=client.post('/api/v1/admin/devices/'+d+'/rotate',headers=admin).json()
    assert client.get('/api/v1/ingest/capabilities',headers=h).status_code==401
    new={'Authorization':'Bearer '+key['api_key']}
    assert client.get('/api/v1/ingest/capabilities',headers=new).status_code==200
    client.post('/api/v1/admin/keys/'+key['id']+'/revoke',headers=admin)
    assert client.get('/api/v1/ingest/capabilities',headers=new).status_code==401
