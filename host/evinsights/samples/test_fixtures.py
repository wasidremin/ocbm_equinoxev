"""SYNTHETIC ONLY — NOT VEHICLE EVIDENCE. Pure test factories, no files outside samples."""
from datetime import datetime,timedelta,timezone
from uuid import uuid4


def event(sec=0,name='PERF_VEHICLE_SPEED',v=10,unit='m/s'):
    return {'event_id':str(uuid4()),'batch_id':str(uuid4()),'schema_version':1,'device_id':'synthetic-test-device',
            'vehicle_id':'synthetic-test-vehicle','source_ts':(datetime(2026,1,1,tzinfo=timezone.utc)+timedelta(seconds=sec)).isoformat(),
            'source_elapsed_ns':sec*10**9,'tz_offset_min':-300,'property_id':1,'property_name':name,'area_id':0,
            'status':'available','value_num':float(v),'unit_canonical':unit,'unit_original':unit,'quality':'measured',
            'app_version':'synthetic-test','build_fingerprint':'SYNTHETIC_NOT_VEHICLE_EVIDENCE','seq':sec,
            'meta':{'boot_id':'synthetic-test-boot','synthetic':True}}


def drive(stop=60):
    result=[]
    for t in range(0,601,30):
        s=0 if 180<=t<180+stop or t>=360+stop else 10
        result.append(event(t,v=s))
        g=event(t,name='GEAR_SELECTION',unit='enum'); g.pop('value_num'); g['value_str']='DRIVE' if s else 'PARK'; result.append(g)
    return result


def probe_report():
    return {'properties':[{'id':123,'name':'EV_BATTERY_LEVEL'}],
            'rawLiveEvents':[{'wallTime':'2026-01-01T00:00:00Z','elapsedRealtimeNanos':123,
                             'kind':'PROPERTY','data':{'id':123,'value':50000,'status':0}}]}


def comprehensive_probe_report():
    """SYNTHETIC report matching the Android export names, enums and timezone."""
    properties=[{'id':1,'name':'GEAR_SELECTION'}, {'id':2,'name':'IGNITION_STATE'},
                {'id':3,'name':'HVAC_POWER_ON'}, {'id':4,'name':'EV_CHARGE_PORT_CONNECTED'},
                {'id':5,'name':'WHEEL_TICK','configurationArray':[11,100000,200000,300000,0]},
                {'id':6,'name':'UNKNOWN_GM_VENDOR','configurationArray':[17]},
                {'id':7,'name':'INFO_EV_BATTERY_CAPACITY','changeMode':0,
                 'areas':[{'areaId':0,'initialRead':{'result':'READABLE','status':0,'timestampNanos':42,'value':60000}}]}]
    data=[]
    for pid,v in ((1,8),(2,4),(3,True),(4,True),(5,[0,10,20,30,0]),(6,[1,'raw',None])):
        data.append({'kind':'PROPERTY','data':{'id':pid,'areaId':0,'status':0,'timestampNanos':123,'value':v}})
    data.extend([{'kind':'LOCATION_RAW_PRIVACY_SENSITIVE','data':{'latitude':40.,'longitude':-70.,'accuracyMeters':4.,
                  'speedMps':None,'provider':'gps','locationElapsedRealtimeNanos':999,'locationWallTimeMillis':12345}},
                 {'kind':'LOCATION_PROVIDER_DISABLED','data':{'provider':'gps'}},
                 {'kind':'PROPERTY_ERROR','data':{'id':3,'areaId':2,'message':'exact diagnostic'}},
                 {'kind':'PROPERTY','data':{'id':1,'areaId':0,'status':0,'value':999}}])
    for raw in data: raw.update(wallTime='2026-01-01T00:00:00-05:00',elapsedRealtimeNanos=1000)
    return {'fingerprint':'SYNTHETIC_EXPORT_FINGERPRINT','versionName':'synthetic-bridge-test',
            'startWallTime':'2026-01-01T00:00:00-05:00','startElapsedRealtimeNanos':1000,
            'properties':properties,'rawLiveEvents':data}


def metric_edge_cases():
    """Synthetic irregular cadence, dropout, reversing wheels and mixed-device epochs."""
    hvac=[]
    for sec,on in ((0,True),(10,False),(60,False)):
        e=event(sec,'HVAC_POWER_ON',unit='bool'); e.pop('value_num'); e['value_bool']=on; hvac.append(e)
    speed=[event(0),event(30),event(60)]
    speed[1]['status']='unavailable'
    wheel=[]
    for sec,count in ((0,0),(30,100),(60,0)):
        e=event(sec,'WHEEL_TICK',unit='ticks'); e.pop('value_num'); e['value_arr']=[0,count,count,0,0]
        e['meta']['wheel_m_per_tick']=[.1,.1,None,None]; wheel.append(e)
    mixed=[event(0),event(30)]; mixed[1]['device_id']='synthetic-other-device'
    distance=[event(0),event(30),event(0,'PERF_ODOMETER',0,'m'),event(30,'PERF_ODOMETER',200,'m')]
    return {'hvac':hvac,'speed':speed,'wheel':wheel,'mixed':mixed,'distance':distance}


def boundary_cases(kind='wheel'):
    """SYNTHETIC displacement-only drive, with parked stops and explicit end."""
    result=[]
    for sec,position in ((0,0),(30,300),(60,600),(90,600),(120,900),(150,900)):
        if kind=='wheel':
            e=event(sec,'WHEEL_TICK',unit='ticks'); e.pop('value_num')
            e['value_arr']=[0,position*10,position*10,0,0]
            e['meta']['wheel_m_per_tick']=[.1,.1,None,None]
        else:
            e=event(sec,'GPS_SPEED',unit='m/s'); e.pop('value_num')
            e.update(status='unavailable',value_str='speed not measured',lat=0.,lon=position/111195,accuracy_m=3.)
            # Position is valid independently of unavailable GPS speed.
            e.update(property_name='GPS_POSITION',unit_canonical='unknown',unit_original='unknown',status='available')
        result.append(e)
        gear=event(sec,'GEAR_SELECTION',unit='enum'); gear.pop('value_num'); gear['value_str']='DRIVE' if sec<150 else 'PARK'
        result.append(gear)
    marker=event(150,'CLIENT_MARKER',unit='enum'); marker.pop('value_num'); marker['value_str']='TRIP_END'; result.append(marker)
    return result


def lifecycle():
    """SYNTHETIC stationary readiness, movement, parked completion."""
    data=[]
    for t in range(0,301,30):
        data.append(event(t,v=10 if 60<=t<120 else 0))
        gear=event(t,'GEAR_SELECTION',unit='enum'); gear.pop('value_num')
        gear['value_str']='DRIVE' if t<120 else 'PARK'; data.append(gear)
        ignition=event(t,'IGNITION_STATE',unit='enum'); ignition.pop('value_num')
        ignition['value_str']='ON' if t<120 else 'OFF'; data.append(ignition)
    return data


def charge():
    result=[]
    for t in range(0,601,30):
        port=event(t,'EV_CHARGE_PORT_CONNECTED',unit='bool'); port.pop('value_num'); port['value_bool']=t<600; result.append(port)
        result.append(event(t,'EV_BATTERY_INSTANTANEOUS_CHARGE_RATE',0 if 180<=t<240 or t==600 else 7200,'W'))
        result.append(event(t,'EV_BATTERY_LEVEL',100000000+t*6000,'J'))
    return result
