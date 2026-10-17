import {useEffect,useRef} from 'react';
import * as echarts from 'echarts';
import {download} from './data';

export function Chart({option,title,data,onPick,group}:{option:echarts.EChartsOption;title:string;data:unknown[];onPick?:(index:number)=>void;group?:string}) {
 const root=useRef<HTMLDivElement>(null),instance=useRef<echarts.ECharts|null>(null);
 useEffect(()=>{
  const c=echarts.init(root.current!,undefined,{renderer:'canvas'});instance.current=c;
  if(group){c.group=group;echarts.connect(group);}
  const resize=new ResizeObserver(()=>c.resize());resize.observe(root.current!);
  return()=>{resize.disconnect();c.dispose();};
 },[group]);
 useEffect(()=>{const c=instance.current!;c.setOption({backgroundColor:'#12202d',textStyle:{color:'#b6cbdc'},tooltip:{trigger:'item'},grid:{left:65,right:30,bottom:65,top:30},toolbox:{feature:{dataZoom:{},restore:{},saveAsImage:{name:title},brush:{type:['rect','polygon','clear']}}},brush:{toolbox:['rect','polygon','clear'],xAxisIndex:'all'},dataZoom:[{type:'inside'},{type:'slider',height:18}],...option},true);
 c.off('click');c.on('click',p=>{if(typeof p.dataIndex==='number')onPick?.(p.dataIndex);});
 c.off('brushSelected');c.on('brushSelected',(p:unknown)=>{const event=p as {batch:{selected:{dataIndex:number[]}[]}[]}; const indexes=event.batch?.[0]?.selected?.flatMap(x=>x.dataIndex)||[];if(indexes.length)onPick?.(indexes[0]);});
 },[option,title,onPick]);
 return <section className="chart"><header><h3>{title}</h3><div><button onClick={()=>download(title+'.json',JSON.stringify(data,null,2))}>Show data</button><button onClick={()=>download(title+'.csv','json\n'+data.map(d=>'"'+JSON.stringify(d).replaceAll('"','""')+'"').join('\n'),'text/csv')}>CSV</button></div></header><div ref={root} style={{height:330}}/></section>;
}
