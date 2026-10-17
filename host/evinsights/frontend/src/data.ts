export type Metric={value:unknown;reason:string|null;quality:string;algorithm_version:string};
export type Trip={id:string;vehicle_id:string;start_ts:string;end_ts:string;state:string;confidence:number;route_id:string|null;synthetic:boolean;metrics:Record<string,Metric>;input_hash:string;metadata:{tz_offset_min:number}};
export type Sample={event_id:string;source_ts:string;property_name:string;status:string;unit_canonical:string;quality:string;value_num?:number;value_int?:number;value_bool?:boolean;value_str?:string;value_arr?:unknown[];lat?:number;lon?:number;alt_m?:number;meta:Record<string,unknown>};
export type Settings={vehicle:string;key:string;units:'metric'|'us'};
export const metrics=['energy_per_distance_j_m','temp_mean_k','distance_m','avg_speed_m_s','duration_s','elevation_gain_m','hvac_on_fraction','local_hour','net_energy_j','confidence'];
export function num(t:Trip,k:string):number|null { const v=k==='confidence'?t.confidence:t.metrics[k]?.value; return typeof v==='number'?v:null; }
export function display(v:number|null,k:string,units:'metric'|'us'):number|null {
 if(v===null)return null;
 if(k.includes('temp_'))return units==='us'?(v-273.15)*9/5+32:v-273.15;
 if(k==='energy_per_distance_j_m')return units==='us'?v*1609.344/3600:v/36;
 if(k.endsWith('_m_s'))return v*(units==='us'?2.2369362921:3.6);
 if(k.includes('distance')&&k.endsWith('_m'))return v/(units==='us'?1609.344:1000);
 if(k.endsWith('_j'))return v/3600000;
 if(k.endsWith('_s'))return v/60;
 return v;
}
export function label(k:string,u:'metric'|'us'):string {
 const suffix=k.includes('temp_')?(u==='us'?'°F':'°C'):k==='energy_per_distance_j_m'?(u==='us'?'Wh/mi':'kWh/100km'):k.endsWith('_m_s')?(u==='us'?'mph':'km/h'):k.includes('distance')&&k.endsWith('_m')?(u==='us'?'mi':'km'):k.endsWith('_j')?'kWh':k.endsWith('_s')?'min':'';
 return k.replaceAll('_',' ')+(suffix?' · '+suffix:'');
}
export function fmt(v:unknown):string {return typeof v==='number'?v.toLocaleString(undefined,{maximumFractionDigits:2}):v==null?'—':typeof v==='object'?JSON.stringify(v):String(v);}
export function download(name:string,content:string,type='application/json') {const url=URL.createObjectURL(new Blob([content],{type})); const a=document.createElement('a');a.href=url;a.download=name;a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);}
export async function api<T>(path:string,s:Settings,body?:unknown,adminKey?:string):Promise<T> {
 const r=await fetch('/api/v1'+path,{method:body===undefined?'GET':'POST',headers:{Authorization:'Bearer '+(adminKey||s.key),'Content-Type':'application/json'},body:body===undefined?undefined:JSON.stringify(body)});
 if(!r.ok)throw new Error(`${r.status}: ${await r.text()}`);return r.json() as Promise<T>;
}
export type Filters={text:string;from:string;to:string;route:string;minDistance:string;minTemp:string;maxTemp:string;minConfidence:string;state:string};
export const emptyFilters:Filters={text:'',from:'',to:'',route:'',minDistance:'',minTemp:'',maxTemp:'',minConfidence:'',state:''};
export function filterTrips(trips:Trip[],f:Filters,u:'metric'|'us') {
 return trips.filter(t=>{
 const distance=display(num(t,'distance_m'),'distance_m',u),temp=display(num(t,'temp_mean_k'),'temp_mean_k',u);
 return (!f.text||JSON.stringify(t).toLowerCase().includes(f.text.toLowerCase()))&&(!f.from||t.start_ts.slice(0,10)>=f.from)&&(!f.to||t.start_ts.slice(0,10)<=f.to)&&(!f.route||t.route_id===f.route)&&(!f.minDistance||(distance!==null&&distance>=+f.minDistance))&&(!f.minTemp||(temp!==null&&temp>=+f.minTemp))&&(!f.maxTemp||(temp!==null&&temp<=+f.maxTemp))&&(!f.minConfidence||t.confidence>=+f.minConfidence)&&(!f.state||t.state===f.state);
 });
}
