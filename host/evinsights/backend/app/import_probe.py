import argparse
import json
import math
from datetime import datetime, timezone
from pathlib import Path
from uuid import NAMESPACE_URL, uuid5

from app.contracts import Event, SIGNALS

# Android public property contracts; source links and availability caveats in docs/parity-and-data-audit.md.
MAPPING={'PERF_VEHICLE_SPEED':('m/s','m/s',1,0),'PERF_VEHICLE_SPEED_DISPLAY':('m/s','m/s',1,0),
         'PERF_ODOMETER':('m','km',1000,0),
         'EV_BATTERY_LEVEL':('J','Wh',3600,0),'INFO_EV_BATTERY_CAPACITY':('J','Wh',3600,0),
         'EV_BATTERY_INSTANTANEOUS_CHARGE_RATE':('W','mW',.001,0),
         'ENV_OUTSIDE_TEMPERATURE':('K','Celsius',1,273.15),'RANGE_REMAINING':('m','m',1,0),
         'TIRE_PRESSURE':('Pa','kPa',1000,0),'WHEEL_TICK':('ticks','ticks',1,0),
         'HVAC_TEMPERATURE_CURRENT':('K','Celsius',1,273.15),'HVAC_TEMPERATURE_SET':('K','Celsius',1,273.15),
         'INFO_EXTERIOR_DIMENSIONS':('m','mm',.001,0)}
# Confirmed against API-35 android.car.jar and Android VehicleGear documentation.
ENUMS={'GEAR_SELECTION':{0:'UNKNOWN',1:'NEUTRAL',2:'REVERSE',4:'PARK',8:'DRIVE',
                         16:'FIRST',32:'SECOND',64:'THIRD',128:'FOURTH',256:'FIFTH',512:'SIXTH',1024:'SEVENTH',2048:'EIGHTH',4096:'NINTH'},
       'IGNITION_STATE':{0:'UNDEFINED',1:'LOCK',2:'OFF',3:'ACC',4:'ON',5:'START'}}
ENUMS['CURRENT_GEAR']=ENUMS['GEAR_SELECTION']
IMPORT_VERSION='probe-bridge-2'


def wheel_scales(config):
    if not isinstance(config,list) or len(config)!=5: return None
    if any(not isinstance(x,int) or isinstance(x,bool) for x in config): return None
    mask=config[0]
    if mask<=0 or mask & ~15: return None
    # Counter/config order: FL, FR, RR, RL; mask bits are FL=1, FR=2, RR=8, RL=4.
    scales=[config[i+1]/1e6 if mask & bit else None for i,bit in enumerate((1,2,8,4))]
    if any(s is not None and (not math.isfinite(s) or s<=0) for s in scales): return None
    return scales


def convert(report,device_id,vehicle_id,boot_id,tz_offset_min):
    configs={p['id']:p for p in report.get('properties',[])}
    namespace=IMPORT_VERSION+':'+device_id+':'+boot_id
    batch=str(uuid5(NAMESPACE_URL,namespace)); result=[]
    observations=[]
    # A census receipt timestamp is not a reconstructed sensor UTC timestamp.
    for cfg in report.get('properties',[]):
        for area in cfg.get('areas',[]):
            read=area.get('initialRead')
            if read is None: continue
            wall=report.get('startWallTime')
            elapsed=report.get('startElapsedRealtimeNanos')
            if wall is None or elapsed is None:
                raise ValueError('Census initial reads require startWallTime and startElapsedRealtimeNanos; no guessed timestamp')
            observations.append(('census:'+str(cfg['id'])+':'+str(area['areaId']),
                {'wallTime':wall,'elapsedRealtimeNanos':elapsed,'kind':'PROPERTY_INITIAL_READ',
                 'data':{'id':cfg['id'],'areaId':area['areaId'],**read}}))
    observations.extend(('live:'+str(seq),raw) for seq,raw in enumerate(report.get('rawLiveEvents',[])))
    for seq,(identity,raw) in enumerate(observations):
        kind=raw['kind']; d=raw['data']; pid=d.get('id',0); cfg=configs.get(pid,{})
        if kind in ('PROPERTY','PROPERTY_INITIAL_READ'):
            name=cfg.get('name',f'VENDOR_{pid}'); v=d.get('value')
            status={0:'available',1:'unavailable'}.get(d.get('status'),'error')
        elif kind=='UX_RESTRICTIONS': name='UX_RESTRICTIONS'; v=d.get('requiresDistractionOptimization'); status='available'
        elif kind=='LOCATION_RAW_PRIVACY_SENSITIVE':
            if d.get('speedMps') is None:
                name='GPS_POSITION'; v=[d['latitude'],d['longitude']]
            else: name='GPS_SPEED'; v=d['speedMps']
            status='available'
        elif kind=='PROPERTY_ERROR':
            name=cfg.get('name',f'VENDOR_{pid}'); v=d.get('message','callback error'); status='error'
        else:
            name='PROBE_DIAGNOSTIC'; v=[d]; status='available'
        unit,original,factor,offset=MAPPING.get(name,(SIGNALS.get(name,'enum' if name in ENUMS else 'unknown'),
                                                    SIGNALS.get(name,'enum' if name in ENUMS else 'unknown'),1,0))
        if v is None: v=[d]; status='unavailable' if status=='available' else status
        elif name in ENUMS and isinstance(v,int) and not isinstance(v,bool):
            v=ENUMS[name].get(v,v)
        elif isinstance(v,(int,float)) and not isinstance(v,bool) and name not in ENUMS:
            v=float(v*factor+offset)
        # Conversion only for documented uniform numeric vectors; original retained below.
        if isinstance(v,list) and name!='WHEEL_TICK' and (factor!=1 or offset!=0):
            v=[x*factor+offset if isinstance(x,(int,float)) and not isinstance(x,bool) else x for x in v]
        field='value_bool' if isinstance(v,bool) else 'value_arr' if isinstance(v,list) else 'value_int' if isinstance(v,int) else 'value_num' if isinstance(v,float) else 'value_str'
        source=datetime.fromisoformat(raw['wallTime'].replace('Z','+00:00'))
        if source.utcoffset() is None: raise ValueError('Probe wallTime must have a timezone offset')
        e={'event_id':str(uuid5(NAMESPACE_URL,namespace+':'+identity)),
           'batch_id':batch,'schema_version':1,'device_id':device_id,'vehicle_id':vehicle_id,
           'source_ts':source.astimezone(timezone.utc).isoformat(),'source_elapsed_ns':raw['elapsedRealtimeNanos'],
           'tz_offset_min':tz_offset_min,'property_id':pid,'property_name':name,'area_id':d.get('areaId',0),
           'status':status,field:v,'unit_canonical':unit,'unit_original':original,'quality':'measured',
           'app_version':report.get('versionName','unknown'),
           'build_fingerprint':report.get('fingerprint',report.get('buildFingerprint','unknown')),
           'seq':seq,'meta':{'boot_id':boot_id,'probe_kind':kind,'sensor_timestamp_ns':d.get('timestampNanos'),
                            'probe_wall_time_is_callback_receipt':kind!='PROPERTY_INITIAL_READ',
                            'census_receipt_not_sensor_utc':kind=='PROPERTY_INITIAL_READ',
                            'import_version':IMPORT_VERSION,'probe_raw_event':raw,
                            'config_array':cfg.get('configurationArray'),'property_config':cfg}}
        if name=='WHEEL_TICK':
            scales=wheel_scales(cfg.get('configurationArray'))
            if scales is not None: e['meta']['wheel_m_per_tick']=scales
        if kind=='LOCATION_RAW_PRIVACY_SENSITIVE':
            e.update(lat=d['latitude'],lon=d['longitude'],accuracy_m=d.get('accuracyMeters'),
                     alt_m=d.get('altitudeMeters'),bearing_deg=d.get('bearingDegrees'))
        result.append(Event.model_validate(e).payload())
    return [{'batch_id':batch,'events':result[i:i+5000]} for i in range(0,len(result),5000)]


if __name__=='__main__':
    p=argparse.ArgumentParser(description='Convert actual probe report to validated batch NDJSON on stdout (contains location if captured)')
    p.add_argument('report'); p.add_argument('--device-id',required=True); p.add_argument('--vehicle-id',required=True)
    p.add_argument('--boot-id',required=True); p.add_argument('--tz-offset-min',type=int,required=True)
    a=p.parse_args()
    if not -840<=a.tz_offset_min<=840: p.error('Invalid timezone offset')
    for b in convert(json.loads(Path(a.report).read_text()),a.device_id,a.vehicle_id,a.boot_id,a.tz_offset_min): print(json.dumps(b))
