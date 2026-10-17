"""Pure deterministic derivation. No DB, wall clock, defaults for missing sensors, or random IDs."""
import hashlib
import json
import math
from collections import defaultdict
from datetime import datetime, timezone
from statistics import mean, median
from uuid import NAMESPACE_URL, uuid5

from pydantic import BaseModel, ConfigDict, Field, model_validator

VERSION = 'evinsights-1.3.0'
DISTANCE_SOURCES = ('distance_odometer_m', 'distance_wheel_m', 'distance_speed_m', 'distance_gps_m')


class DerivationConfig(BaseModel):
    model_config = ConfigDict(extra='forbid', allow_inf_nan=False)
    stop_timeout_s: float = Field(default=180, ge=1, le=3600)
    max_gap_s: float = Field(default=90, ge=1, le=600)
    min_movement_m: float = Field(default=100, ge=0, le=10000)
    distance_priority: list[str] = Field(default_factory=lambda: list(DISTANCE_SOURCES))

    @model_validator(mode='after')
    def priority(self):
        if len(self.distance_priority) != len(DISTANCE_SOURCES) or set(self.distance_priority) != set(DISTANCE_SOURCES):
            raise ValueError('distance_priority must contain each supported distance source exactly once')
        return self


DEFAULT_CONFIG = DerivationConfig().model_dump()


def metric(value=None, reason=None, quality='derived'):
    return {'value': value, 'reason': reason if value is None else None,
            'quality': quality, 'algorithm_version': VERSION}


def ts(e):
    return datetime.fromisoformat(e['source_ts'].replace('Z', '+00:00')).timestamp()


def value(e):
    if e['status'] != 'available': return None
    for k in ('value_num', 'value_int', 'value_bool', 'value_str', 'value_arr'):
        if k in e and e[k] is not None: return e[k]
    return None


def ordered(events):
    return sorted(events, key=lambda e: (ts(e), e['source_elapsed_ns'], e['seq'], str(e['event_id'])))


def numeric(e):
    v = value(e)
    return float(v) if isinstance(v, (int, float)) and not isinstance(v, bool) else None


def series(events, name, include_invalid=False):
    # Different areas must not be silently combined for a scalar metric.
    data = [e for e in events if e['property_name'] == name and (include_invalid or numeric(e) is not None)]
    if len({e['area_id'] for e in data}) > 1: return []
    return data


def pairs(data, max_gap):
    for a, b in zip(data, data[1:]):
        dt = ts(b)-ts(a)
        if 0 < dt <= max_gap and (a['device_id'], a['meta']['boot_id']) == (b['device_id'], b['meta']['boot_id']):
            yield a, b, dt


def integral(data, max_gap, transform=lambda x: x):
    if any(numeric(e) is None for e in data): return metric(reason='UNAVAILABLE_OR_NONNUMERIC_SAMPLE')
    ps = list(pairs(data, max_gap))
    if not ps: return metric(reason='INSUFFICIENT_CONTIGUOUS_SAMPLES')
    if len(ps) != len(data)-1: return metric(reason='GAP_OR_BOOT_BOUNDARY')
    return metric(sum((transform(numeric(a))+transform(numeric(b)))*dt/2 for a,b,dt in ps))


def delta(data, increasing=True):
    if len(data) < 2: return metric(reason='MISSING_ENDPOINTS')
    if any(numeric(e) is None for e in data): return metric(reason='UNAVAILABLE_OR_NONNUMERIC_SAMPLE')
    result = numeric(data[-1])-numeric(data[0])
    if increasing and any(numeric(b)<numeric(a) for a,b in zip(data,data[1:])):
        return metric(reason='COUNTER_RESET')
    return metric(result)


def haversine(a,b):
    rad = math.pi/180
    dlat = (b['lat']-a['lat'])*rad; dlon = (b['lon']-a['lon'])*rad
    x = math.sin(dlat/2)**2+math.cos(a['lat']*rad)*math.cos(b['lat']*rad)*math.sin(dlon/2)**2
    return 6371000*2*math.asin(min(1,math.sqrt(x)))


def duration_fraction(events, name, start, end, gap):
    data = series(events, name, include_invalid=True)
    if not data: return metric(reason='MISSING_HVAC')
    if any(not isinstance(value(e), bool) for e in data):
        return metric(reason='UNAVAILABLE_OR_NONBOOLEAN_SAMPLE')
    if end <= start: return metric(reason='ZERO_DURATION')
    if ts(data[0]) != start or ts(data[-1]) != end:
        return metric(reason='HVAC_ENDPOINT_COVERAGE_MISSING')
    intervals = list(pairs(data, gap))
    if len(intervals) != len(data)-1: return metric(reason='GAP_OR_BOOT_BOUNDARY')
    # Left-continuous state: a change takes effect at its observed timestamp.
    return metric(sum(dt for a, b, dt in intervals if value(a)) / (end-start))


def wheel_distance(events, gap):
    data = series(events, 'WHEEL_TICK', include_invalid=True)
    if len(data) < 2: return metric(reason='MISSING_WHEEL_SCALE_OR_SAMPLES')
    arrays = [value(e) for e in data]
    if any(not isinstance(a, list) or len(a) != 5 or
           any(not isinstance(v, (int, float)) or isinstance(v, bool) or not math.isfinite(v) for v in a)
           for a in arrays): return metric(reason='INVALID_WHEEL_VECTOR')
    if len({a[0] for a in arrays}) > 1: return metric(reason='WHEEL_RESET')
    scales = [e['meta'].get('wheel_m_per_tick') for e in data]
    def valid(scale):
        # null marks an unsupported wheel; zero is not an invented scale.
        return isinstance(scale, list) and len(scale) == 4 and any(s is not None for s in scale) and all(
            s is None or (isinstance(s, (int, float)) and not isinstance(s, bool) and math.isfinite(s) and s > 0)
            for s in scale)
    if not all(valid(s) for s in scales): return metric(reason='MISSING_OR_INVALID_WHEEL_SCALE')
    if any(s != scales[0] for s in scales): return metric(reason='WHEEL_SCALE_CHANGED')
    if len(list(pairs(data, gap))) != len(data)-1: return metric(reason='GAP_OR_BOOT_BOUNDARY')
    return metric(mean(sum(abs(b[i+1]-a[i+1])*scale for a,b in zip(arrays,arrays[1:]))
                       for i,scale in enumerate(scales[0]) if scale is not None))


def trip_metrics(events, config):
    events = ordered(events)
    config = DerivationConfig.model_validate(config).model_dump()
    gap = config['max_gap_s']; duration = max(0, ts(events[-1])-ts(events[0]))
    out = {'duration_s': metric(duration)}
    speeds = series(events,'PERF_VEHICLE_SPEED') or series(events,'GPS_SPEED')
    odometer = series(events,'PERF_ODOMETER')
    battery = series(events,'EV_BATTERY_LEVEL')
    power = series(events,'EV_BATTERY_INSTANTANEOUS_CHARGE_RATE')
    temps = series(events,'ENV_OUTSIDE_TEMPERATURE')
    gps = [e for e in events if e.get('lat') is not None and e['status']=='available']
    # Multiple signals can carry the same fix; deduplicate fixes by timestamp/position.
    gps = list({(e['source_ts'], e['lat'], e['lon']): e for e in gps}.values())
    speed_name = 'PERF_VEHICLE_SPEED' if any(e['property_name']=='PERF_VEHICLE_SPEED' for e in events) else 'GPS_SPEED'
    speed_observations = series(events, speed_name, include_invalid=True)
    out['distance_speed_m'] = integral(speed_observations,gap,abs)
    out['distance_odometer_m'] = delta(series(events,'PERF_ODOMETER',include_invalid=True))
    if len(gps)<2: out['distance_gps_m'] = metric(reason='GPS_NOT_OPTED_IN_OR_MISSING')
    elif any(e.get('accuracy_m') is None or e['accuracy_m']>50 for e in gps):
        out['distance_gps_m'] = metric(reason='GPS_ACCURACY_UNKNOWN_OR_POOR')
    elif len(list(pairs(gps,gap))) != len(gps)-1: out['distance_gps_m'] = metric(reason='GAP_OR_BOOT_BOUNDARY')
    else: out['distance_gps_m'] = metric(sum(haversine(a,b) for a,b,_ in pairs(gps,gap)))
    out['distance_wheel_m'] = wheel_distance(events, gap)
    sources={k:out[k]['value'] for k in ('distance_wheel_m','distance_speed_m','distance_gps_m','distance_odometer_m') if out[k]['value'] is not None}
    out['distance_divergence']=metric({f'{a}:{b}':abs(v-w)/max(v,w) if max(v,w)>0 else 0 for a,v in sources.items() for b,w in sources.items() if a<b})
    # No vehicle source is confirmed: a transparent configurable preference, not a capability claim.
    preferred=next((k for k in config['distance_priority'] if k in sources),None)
    out['distance_m']=metric(sources.get(preferred), 'NO_USABLE_DISTANCE_SOURCE')
    out['preferred_distance']=metric(preferred,'NO_USABLE_DISTANCE_SOURCE')
    out['preferred_distance_reason']=metric('Configured priority: ' + ', '.join(config['distance_priority']) if preferred else None,'NO_USABLE_DISTANCE_SOURCE')
    out['start_energy_j']=metric(numeric(battery[0]) if battery else None,'MISSING_BATTERY_ENERGY')
    out['end_energy_j']=metric(numeric(battery[-1]) if battery else None,'MISSING_BATTERY_ENERGY')
    energy=delta(series(events,'EV_BATTERY_LEVEL',include_invalid=True),False)
    if battery and (ts(battery[0])-ts(events[0]) > gap or ts(events[-1])-ts(battery[-1]) > gap):
        energy=metric(reason='BATTERY_ENDPOINT_COVERAGE_MISSING')
    out['net_energy_j']=metric(-energy['value'] if energy['value'] is not None else None,energy['reason'])
    power_observations = series(events,'EV_BATTERY_INSTANTANEOUS_CHARGE_RATE',include_invalid=True)
    out['gross_discharge_j']=integral(power_observations,gap,lambda p:max(-p,0))
    out['gross_regen_j']=integral(power_observations,gap,lambda p:max(p,0))
    out['avg_power_w']=metric(mean(numeric(e) for e in power) if power else None,'MISSING_POWER')
    out['peak_discharge_w']=metric(max(-numeric(e) for e in power) if power else None,'MISSING_POWER')
    d=out['distance_m']['value']; n=out['net_energy_j']['value']
    out['energy_per_distance_j_m']=metric(n/d if n is not None and d is not None and d>0 else None,'MISSING_ENERGY_OR_DISTANCE')
    out['parked_energy_j']=metric(reason='PARKED_ENERGY_NOT_SEPARATELY_IDENTIFIED')
    out['preconditioning_energy_j']=metric(reason='PRECONDITIONING_NOT_SEPARATELY_IDENTIFIED')
    sv=[abs(numeric(e)) for e in speeds]; moving=[v for v in sv if v>0.5]
    out['avg_speed_m_s']=metric(mean(moving) if moving else None,'MISSING_MOVING_SPEED')
    out['median_speed_m_s']=metric(median(moving) if moving else None,'MISSING_MOVING_SPEED')
    out['max_speed_m_s']=metric(max(sv) if sv else None,'MISSING_SPEED')
    bands={'0-5':{'time_s':0.,'distance_m':0.},'5-15':{'time_s':0.,'distance_m':0.},'15-25':{'time_s':0.,'distance_m':0.},'25+':{'time_s':0.,'distance_m':0.}}
    accelerations=[]
    for a,b,dt in pairs(speeds,gap):
        s=(abs(numeric(a))+abs(numeric(b)))/2
        band='0-5' if s<5 else '5-15' if s<15 else '15-25' if s<25 else '25+'
        bands[band]['time_s']+=dt; bands[band]['distance_m']+=s*dt
        accelerations.append((numeric(b)-numeric(a))/dt)
    out['speed_bands']=metric(bands if speeds else None,'MISSING_SPEED')
    out['acceleration_m_s2']=metric(accelerations if accelerations else None,'MISSING_CONTIGUOUS_SPEED')
    out['stop_count']=metric(sum(x>0.5 and y<=0.5 for x,y in zip(sv,sv[1:])) if sv else None,'MISSING_SPEED')
    for name,fn in [('mean',mean),('min',min),('max',max)]:
        out[f'temp_{name}_k']=metric(fn(numeric(e) for e in temps) if temps else None,'MISSING_TEMPERATURE')
    out['temp_start_k']=metric(numeric(temps[0]) if temps else None,'MISSING_TEMPERATURE')
    out['temp_end_k']=metric(numeric(temps[-1]) if temps else None,'MISSING_TEMPERATURE')
    alts=[e['alt_m'] for e in gps if e.get('alt_m') is not None]
    out['elevation_gain_m']=metric(sum(max(b-a,0) for a,b in zip(alts,alts[1:])) if len(alts)>=2 else None,'MISSING_ALTITUDE')
    out['elevation_loss_m']=metric(sum(max(a-b,0) for a,b in zip(alts,alts[1:])) if len(alts)>=2 else None,'MISSING_ALTITUDE')
    out['hvac_on_fraction']=duration_fraction(events,'HVAC_POWER_ON',ts(events[0]),ts(events[-1]),gap)
    ranges=series(events,'RANGE_REMAINING'); rd=delta(ranges,False)
    out['range_error_m']=metric(-rd['value']-d if rd['value'] is not None and d is not None else None,'MISSING_RANGE_OR_DISTANCE')
    for name,reason in [('precipitation','WEATHER_ENRICHMENT_DISABLED'),('headwind_m_s','WEATHER_ENRICHMENT_DISABLED'),('cost','CHARGE_ENERGY_ALLOCATION_NOT_ESTABLISHED')]: out[name]=metric(reason=reason)
    groups=defaultdict(list)
    for e in events: groups[e['property_name']].append(e)
    coverage={}
    for name,data in groups.items():
        covered=sum(dt for a,b,dt in pairs(data,gap) if a['status']==b['status']=='available')
        coverage[name]={'coverage_fraction':min(1,covered/duration) if duration else None,
                       'largest_gap_s':max((ts(b)-ts(a) for a,b in zip(data,data[1:])),default=None)}
    out['coverage']=metric(coverage)
    out['local_hour']=metric((datetime.fromtimestamp(ts(events[0]), timezone.utc).hour+events[0]['tz_offset_min']/60)%24)
    out['exclusions']=metric([k+':'+m['reason'] for k,m in out.items() if m['value'] is None])
    return out


def derive(events, config=None):
    config=DerivationConfig.model_validate(config or {}).model_dump(); events=ordered(events)
    trips=[]; charges=[]; issues=[]; frames=defaultdict(list)
    # Never combine simultaneous observations from different device/boot epochs.
    for e in events: frames[(ts(e), e['device_id'], e['meta']['boot_id'])].append(e)
    last={}; previous_wheel=None; previous_gps=None; previous_frame=[]; prior_epoch=None
    active=[]; segments=[]; stop_at=None; prior_t=None; state='UNKNOWN'; evidence=set()
    preparation=[]; uncertainty=False; motion_seen=False
    charge=[]; charge_start=None; charge_evidence=set()
    boot_clocks={}
    for e in events:
        boot=(e['device_id'],e['meta']['boot_id']); t=ts(e); mono=e['source_elapsed_ns']/1e9
        if boot in boot_clocks:
            oldt,oldmono=boot_clocks[boot]
            if abs((t-oldt)-(mono-oldmono))>5:
                issues.append({'code':'CLOCK_DRIFT','event_id':str(e['event_id'])})
        boot_clocks[boot]=(t,mono)

    def finish(reason, review=False):
        nonlocal active,segments,stop_at,evidence,state,uncertainty,motion_seen,preparation
        if not active: return
        m=trip_metrics(active,config)
        d=m['distance_m']['value']; reasons=sorted(evidence|{reason})
        drift_ids={i['event_id'] for i in issues if i['code']=='CLOCK_DRIFT'}
        clock_drift=any(str(e['event_id']) in drift_ids for e in active)
        if clock_drift: reasons.append('CLOCK_DRIFT')
        if uncertainty: reasons.append('UNKNOWN_MOTION_INTERVAL')
        if not motion_seen: reasons.append('NO_OBSERVED_MOVEMENT')
        complete=not review and not uncertainty and motion_seen and not clock_drift and len(evidence)>=2 and d is not None and d>=config['min_movement_m']
        payload_hash=hashlib.sha256(json.dumps({'events':active,'config':config,'version':VERSION},sort_keys=True).encode()).hexdigest()
        tid=str(uuid5(NAMESPACE_URL,'trip:'+active[0]['vehicle_id']+':'+str(active[0]['event_id'])))
        route=next((e['meta'].get('route_id') for e in active if e['meta'].get('route_id')),None)
        trips.append({'id':tid,'vehicle_id':active[0]['vehicle_id'],'start_ts':active[0]['source_ts'],
                      'end_ts':active[-1]['source_ts'],'state':'COMPLETE' if complete else 'NEEDS_REVIEW',
                      'confidence': min(.95,.35+.15*len(evidence)) if complete else .3,
                      'boundary_reasons':reasons,'algorithm_version':VERSION,'input_hash':payload_hash,
                      'route_id':route,'metrics':m,'segments':segments,'event_ids':[str(e['event_id']) for e in active],
                      'metadata':{'tz_offset_min':active[0]['tz_offset_min'],'config':config}})
        active=[]; segments=[]; stop_at=None; evidence=set(); state='PARKED'
        preparation=[]; uncertainty=False; motion_seen=False

    def finish_charge(reason):
        nonlocal charge,charge_start,charge_evidence
        if not charge: return
        p=series(charge,'EV_BATTERY_INSTANTANEOUS_CHARGE_RATE'); b=series(charge,'EV_BATTERY_LEVEL')
        power_observations=series(charge,'EV_BATTERY_INSTANTANEOUS_CHARGE_RATE',include_invalid=True)
        energy=integral(power_observations,config['max_gap_s'],lambda x:max(x,0))
        bd=delta(series(charge,'EV_BATTERY_LEVEL',include_invalid=True),False); walls=series(charge,'WALL_ENERGY')
        wall=numeric(walls[-1]) if walls else None
        actual=sum(dt for a,z,dt in pairs(p,config['max_gap_s']) if numeric(a)>0 and numeric(z)>0)
        dur=ts(charge[-1])-ts(charge[0]); gained=bd['value']
        port_seen=any(e['property_name']=='EV_CHARGE_PORT_CONNECTED' and value(e) is True for e in charge)
        types=[value(e) for e in charge if e['property_name']=='CHARGE_TYPE']
        cm={'plugged_duration_s':metric(dur if port_seen else None,'PORT_SIGNAL_MISSING'),
            'charging_duration_s':metric(actual if energy['value'] is not None else None,energy['reason']),
            'energy_in_j':energy,'battery_gain_j':bd,'wall_energy_j':metric(wall,'MISSING_WALL_METER'),
            'efficiency':metric(gained/wall if gained is not None and wall is not None and wall>0 else None,'MISSING_WALL_METER_OR_BATTERY'),
            'loss_j':metric(wall-gained if gained is not None and wall is not None else None,'MISSING_WALL_METER_OR_BATTERY'),
            'charge_type':metric(types[-1] if types else None,'CHARGE_TYPE_UNKNOWN'),
            'cost':metric(reason='NO_RATE_ATTACHED')}
        charges.append({'id':str(uuid5(NAMESPACE_URL,'charge:'+str(charge[0]['event_id']))),
                        'vehicle_id':charge[0]['vehicle_id'],'start_ts':charge[0]['source_ts'],'end_ts':charge[-1]['source_ts'],
                        'algorithm_version':VERSION,'metrics':cm,'event_ids':[str(e['event_id']) for e in charge],
                        'metadata':{'boundary_reason':reason,'evidence':sorted(charge_evidence),'needs_review':len(charge_evidence)<2 or reason!='PORT_DISCONNECTED'}})
        charge=[]; charge_start=None; charge_evidence=set()

    for (t,device,boot),frame in sorted(frames.items()):
        epoch=(device,boot)
        if prior_epoch is not None and epoch != prior_epoch:
            if active: finish('DEVICE_OR_BOOT_BOUNDARY',True)
            if charge: finish_charge('DEVICE_OR_BOOT_BOUNDARY')
            last={}; previous_wheel=None; previous_gps=None; previous_frame=[]; state='UNKNOWN'; preparation=[]
            issues.append({'code':'DEVICE_OR_BOOT_BOUNDARY','event_id':str(frame[0]['event_id'])})
        prior_epoch=epoch
        if prior_t is not None and t-prior_t>config['max_gap_s']:
            if active: finish('DROPPED_INTERVAL',True)
            if charge: finish_charge('DROPPED_INTERVAL')
            last={}; previous_wheel=None; previous_gps=None; previous_frame=[]; state='UNKNOWN'; preparation=[]
            issues.append({'code':'DROPPED_INTERVAL','start_ts':frame[0]['source_ts'],'gap_s':t-prior_t})
        prior_t=t
        for e in frame:
            # An unavailable observation invalidates prior evidence immediately.
            last[(e['property_name'],e['area_id'])]=(value(e),t)
        def current(name):
            items=[item for (prop,area),item in last.items() if prop==name and t-item[1]<=config['max_gap_s']]
            return items[0][0] if len(items)==1 else None
        def number(v):
            return isinstance(v,(int,float)) and not isinstance(v,bool) and math.isfinite(v)
        speed=current('PERF_VEHICLE_SPEED'); speed=speed if number(speed) else current('GPS_SPEED')
        speed_motion=number(speed) and abs(speed)>.5
        stationary=number(speed) and abs(speed)<=.5
        wheel_motion=False; gps_motion=False; boundary_anchor=None
        wheels=[e for e in frame if e['property_name']=='WHEEL_TICK']
        if wheels:
            wheel=wheels[0] if len(wheels)==1 else None
            if wheel is not None and previous_wheel is not None:
                distance=wheel_distance([previous_wheel,wheel],config['max_gap_s'])['value']
                dt=t-ts(previous_wheel)
                if distance is not None and dt>0:
                    wheel_motion=distance/dt>.5
                    stationary=stationary or distance==0
                    if wheel_motion: boundary_anchor=previous_wheel
            previous_wheel=wheel
        if previous_gps is not None and any(e['property_name']==previous_gps['property_name'] and
                                               e['status']!='available' for e in frame):
            previous_gps=None
        fixes=[e for e in frame if e.get('lat') is not None]
        if fixes:
            # Duplicate signals may carry one fix; conflicting fixes are not fused.
            unique={(e['lat'],e['lon'],e.get('accuracy_m'),e['status']) for e in fixes}
            fix=fixes[0] if len(unique)==1 else None
            valid_fix=fix is not None and fix['status']=='available' and fix.get('accuracy_m') is not None and fix['accuracy_m']<=50
            if valid_fix and previous_gps is not None and list(pairs([previous_gps,fix],config['max_gap_s'])):
                distance=haversine(previous_gps,fix); dt=t-ts(previous_gps)
                gps_motion=distance>max(1,previous_gps['accuracy_m']+fix['accuracy_m']) and distance/dt>.5
                if gps_motion and boundary_anchor is None: boundary_anchor=previous_gps
            previous_gps=fix if valid_fix else None
        motion=speed_motion or wheel_motion or gps_motion
        if motion: stationary=False
        marker=next((value(e) for e in frame if e['property_name']=='CLIENT_MARKER'), None); gear=current('GEAR_SELECTION'); ux=current('UX_RESTRICTIONS')
        sources=set()
        if speed_motion: sources.add('SPEED_MOTION')
        if wheel_motion: sources.add('WHEEL_MOTION')
        if gps_motion: sources.add('GPS_POSITION_MOTION')
        if gear in ('DRIVE','REVERSE'): sources.add('GEAR_DRIVING')
        if ux is True: sources.add('UX_RESTRICTED_INDIRECT')
        if marker=='TRIP_START': sources.add('CLIENT_START')
        ignition=current('IGNITION_STATE')
        ready=stationary and (gear in ('DRIVE','REVERSE') or ignition in ('ON','RUN'))
        # Preparation is bounded and never emits a trip without motion or a marker.
        if not active and not motion and marker!='TRIP_START':
            if ready:
                preparation.extend(frame)
                preparation=[e for e in preparation if t-ts(e)<=config['max_gap_s']]
                state='PREPARING'
            else:
                preparation=[]; state='PARKED' if stationary and gear=='PARK' else 'UNKNOWN'
        # GPS speed corroborates but is not counted twice as independent evidence.
        if motion or marker=='TRIP_START':
            if not active:
                state='DRIVING' if motion else 'PREPARING'
                preparation=[e for e in preparation if t-ts(e)<=config['max_gap_s']]
                if preparation:
                    active.extend(preparation)
                    segments.append({'start_ts':active[0]['source_ts'],'end_ts':active[-1]['source_ts'],'state':'PREPARING'})
                    evidence.add('OBSERVED_PREPARATION')
                if boundary_anchor is not None:
                    anchors=previous_frame if boundary_anchor in previous_frame else [boundary_anchor]
                    ids={str(e['event_id']) for e in active}
                    active.extend(e for e in anchors if str(e['event_id']) not in ids)
                    active=ordered(active)
                segments.append({'start_ts':frame[0]['source_ts'] if preparation else active[0]['source_ts'] if active else frame[0]['source_ts'],
                                 'end_ts':frame[0]['source_ts'],'state':state})
                preparation=[]
            elif state!='DRIVING':
                segments.append({'start_ts':frame[0]['source_ts'],'end_ts':frame[0]['source_ts'],'state':'DRIVING'}); state='DRIVING'
            stop_at=None; evidence|=sources; motion_seen=motion_seen or motion
        elif active:
            next_state='PREPARING' if not motion_seen and ready else 'SHORT_STOP' if stationary else 'UNKNOWN'
            if next_state=='UNKNOWN': uncertainty=True
            if next_state!=state:
                state=next_state; segments.append({'start_ts':frame[0]['source_ts'],'end_ts':frame[0]['source_ts'],'state':state})
            if stationary:
                if stop_at is None: stop_at=t
            else:
                stop_at=None
        if active or motion or marker=='TRIP_START':
            active.extend(frame); segments[-1]['end_ts']=frame[-1]['source_ts']
            if marker=='TRIP_END' or (stop_at is not None and t-stop_at>=config['stop_timeout_s']):
                # Completion is an observed decision point, not an invented tail interval.
                segments.append({'start_ts':frame[0]['source_ts'],'end_ts':frame[-1]['source_ts'],'state':'COMPLETING'})
                contradictory_end=marker=='TRIP_END' and motion
                if contradictory_end: evidence.add('END_MARKER_WHILE_MOVING')
                finish('CLIENT_END' if marker=='TRIP_END' else 'STOP_TIMEOUT',
                       review=contradictory_end or (marker!='TRIP_END' and gear!='PARK'))
        port=current('EV_CHARGE_PORT_CONNECTED'); cp=current('EV_BATTERY_INSTANTANEOUS_CHARGE_RATE')
        charging=(isinstance(cp,(int,float)) and cp>0 and not motion) or current('EV_CHARGE_STATE')=='CHARGING'
        if port is True or charging or marker=='CHARGE_START':
            if charge_start is None: charge_start=t
            if port is True: charge_evidence.add('PORT_CONNECTED')
            if charging: charge_evidence.add('CHARGE_POWER_OR_STATE')
            if marker=='CHARGE_START': charge_evidence.add('CLIENT_START')
        if charge_start is not None:
            charge.extend(frame)
            if port is False or marker=='CHARGE_END': finish_charge('PORT_DISCONNECTED' if port is False else 'CLIENT_END')
        previous_frame=frame
    finish('CAPTURE_END_PARTIAL',True)
    finish_charge('CAPTURE_END_PARTIAL')
    return {'trips':trips,'charging_sessions':charges,'issues':issues,'algorithm_version':VERSION,'config':config}
