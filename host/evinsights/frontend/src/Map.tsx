import {useEffect,useRef} from 'react';
import L from 'leaflet';
import 'leaflet/dist/leaflet.css';
import {Sample} from './data';
export function RouteMap({samples,cursor}:{samples:Sample[];cursor:string|null}) {
 const el=useRef<HTMLDivElement>(null);
 useEffect(()=>{
 const map=L.map(el.current!).setView([0,0],2);
 // No remote tiles by default: GPS never leaves the private LAN. A local tile provider can be configured later.
 const fixes=samples.filter(s=>s.lat!=null&&s.lon!=null);const points=fixes.map(s=>[s.lat!,s.lon!] as L.LatLngTuple);
 if(points.length){const line=L.polyline(points,{color:'#46d8b0',weight:4}).addTo(map);map.fitBounds(line.getBounds(),{padding:[25,25]});
 const selected=cursor?fixes.reduce((a,b)=>Math.abs(Date.parse(a.source_ts)-Date.parse(cursor))<Math.abs(Date.parse(b.source_ts)-Date.parse(cursor))?a:b):fixes[0];
 L.circleMarker([selected.lat!,selected.lon!],{radius:7,color:'#ffb66d'}).addTo(map).bindTooltip(selected.source_ts);
 }
 return()=>{map.remove();};
 },[samples,cursor]);
 return <section className="chart"><h3>Route · local-only coordinates</h3><div ref={el} style={{height:320,background:'#1a3040'}}/><p className="muted">No external tile service. Missing GPS stays missing. Click a chart point to move the map cursor.</p></section>;
}
