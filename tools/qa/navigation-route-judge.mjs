export function judgeRoute(input,result){
 const failures=[];const check=(value,message)=>{if(!value)failures.push(message)};
 if(!input.origin){check(result.status==='ORIGIN_REQUIRED','缺起点必须明确拒绝');check((result.routes??[]).length===0,'缺起点不得伪造路线');return{passed:failures.length===0,failures};}
 check(result.status==='OK'&&result.source==='AMAP_WEB','须取得真实供应方路线结果');
 const before=[input.origin,...input.waypoints,input.destination];
 const after=[result.origin,...(result.waypoints??[]),result.destination];
 check(before.length===after.length,'转换前后站点数量必须一致');
 before.forEach((p,i)=>{const q=after[i];if(!q){check(false,'缺少转换后的站点');return;}
  check(q.coordinateSystem==='GCJ02'&&Number.isFinite(q.latitude)&&Number.isFinite(q.longitude),'结果必须是有效GCJ坐标');
  const delta=Math.hypot(q.latitude-p.latitude,q.longitude-p.longitude);
  check(p.coordinateSystem==='GCJ02'?delta<1e-7:delta>1e-5&&delta<0.03,'已有GCJ不得改动，GPS应实际转换且保持地点和顺序');
 });
 check(Array.isArray(result.routes)&&result.routes.length>0,'须返回路线');
 check((result.routes??[]).every(r=>r.id&&Number.isFinite(r.distanceMeters)&&r.distanceMeters>0&&Number.isFinite(r.durationSeconds)&&r.durationSeconds>0),'路线须含有效距离和耗时');
 if(input.preference)check(result.preference===input.preference,'路线偏好不能丢失');
 return{passed:failures.length===0,failures};
}
