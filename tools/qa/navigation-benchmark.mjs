// Semantic gate for the fixed cloud-text suite; no live credentials needed.
export function candidates(row) {
  try { const a=JSON.parse(row.intent?.slots?.candidates?.value??'[]');return Array.isArray(a)?a:[]; } catch {return [];}
}
const slot=(r,k)=>r.intent?.slots?.[k]?.value;
const boxes={beijing:[39.4,41.1,115.4,117.6],shanghai:[30.6,31.9,120.8,122],sanya:[18.1,18.6,109.1,109.9]};
const inBox=(p,b)=>Number.isFinite(p.lat)&&Number.isFinite(p.lon)&&p.lat>=b[0]&&p.lat<=b[1]&&p.lon>=b[2]&&p.lon<=b[3];
const key=p=>`${p.poiname}|${Number(p.lat).toFixed(5)}|${Number(p.lon).toFixed(5)}`;
export function evaluate(row,history=[]) {
 const errors=[]; const require=(ok,note)=>{if(!ok)errors.push(note)};
 const id=row.id; const action=row.intent?.intent;const text=row.text??'';
 require(!row.error&&row.type==='reply','未取得正常最终回复');
 const previous=(id)=>history.findLast(r=>r.id===id);
 function choices(city,pattern){const a=candidates(row);require(action==='choose_destination','应返回候选而非拒绝或直接执行');require(a.length>0&&a.length<=3,'候选数量须为1–3');require(a.every(p=>inBox(p,boxes[city])),'候选坐标必须有效且符合城市范围');if(pattern)require(a.some(p=>pattern.test(p.poiname??'')),'候选未包含指定目标');require(Boolean(slot(row,'selectionId')),'缺少候选任务身份');return a;}
 let boundary=false;
 switch(id){
 case 'name-1':case 'negative-1':case 'cancel-1':choices('beijing',/北京南站/);break;
 case 'city-1':choices('shanghai',/上海博物馆/);break;
 case 'entrance-1':choices('shanghai',/虹桥.*(?:T\s*2|2号航站楼|二号航站楼)/i);break;
 case 'numeric_city-1':choices('sanya',/凤凰.*机场/);break;
 case 'brand-1':{const a=choices('beijing',/星巴克|starbucks/i);require(a.every(p=>/星巴克|starbucks/i.test(p.poiname??'')),'品牌候选混入其他品牌');break;}
 case 'charging-1':{const a=choices('beijing',/充电|换电/);require(a.every(p=>Math.hypot((p.lat-39.865246)*111,(p.lon-116.378517)*85)<10),'站点不在参考地点10km内');require(!/空闲\s*\d|保证.*有桩/.test(text),'不能编造实时桩态');break;}
 case 'opening-1':boundary=true;require(!action&&/无法|不能|未知/.test(text)&&/营业/.test(text),'无法核验营业时须说明边界');break;
 case 'energy-1':boundary=true;require(!action&&/缺|无法/.test(text)&&/荷电|SOC|能耗|兼容/.test(text),'缺车辆数据不能生成补能动作');break;
 case 'home-1':boundary=true;require(!action&&/设备|常用地址/.test(text),'无端侧常用地址时不能编造目标');break;
 case 'live_eta-1':boundary=true;require(!action&&/地图|无法|当前没有/.test(text)&&!/[0-9一二三四五六七八九十]+\s*(分钟|小时)/.test(text),'无活动行程不能编造实时耗时');break;
 case 'trip-1':{let stops=[];try{stops=JSON.parse(slot(row,'stops'))}catch{} require(action==='draft_trip'&&stops.length===2,'应为两站有序草稿');require(stops[0]?.candidates?.some(p=>/北京南站/.test(p.poiname)&&inBox(p,boxes.beijing))&&stops[1]?.candidates?.some(p=>/北京西站/.test(p.poiname)&&inBox(p,boxes.beijing)),'两站顺序和坐标不匹配');break;}
 case 'readonly-1':choices('beijing',/北京南站/);require(Boolean(slot(row,'routePreviewPreference')),'缺失只读路线偏好');break;
 case 'readonly-2':case 'negative-3':{const p=previous(id==='readonly-2'?'readonly-1':'negative-1');require(action===(id==='readonly-2'?'preview_routes':'navigate'),'动作与只读/执行目标不一致');const c=candidates(p??{})[0];require(Boolean(c)&&slot(row,'candidateId')===c.candidateId&&slot(row,'selectionId')===slot(p,'selectionId')&&slot(row,'lat')===c.lat&&slot(row,'lon')===c.lon,'所选身份或坐标与当前第一项不一致');break;}
 case 'negative-2':require(!action,'否定选择不得执行');break;
 case 'correction-1':choices('beijing',/博物/);break;
 case 'correction-2':choices('shanghai',/博物/);require(slot(row,'selectionId')!==slot(previous('correction-1')??{},'selectionId'),'城市纠正须更换候选任务');break;
 case 'next_batch-1':choices('beijing',/咖啡/);break;
 case 'next_batch-2':{const a=choices('beijing',/咖啡/);const old=new Set(candidates(previous('next_batch-1')??{}).map(key));require(a.every(p=>!old.has(key(p))),'换批不能重复原地点');break;}
 case 'cancel-2':require(action==='cancel_navigation'&&slot(row,'selectionId')===slot(previous('cancel-1')??{},'selectionId'),'取消必须绑定原任务');break;
 case 'cancel-3':require(!action,'取消后序号不能执行旧目标');break;
 default:require(false,'未配置此用例的判定规则');
 }
 return {verdict:errors.length?'fail':boundary?'boundary_pass':'pass',checks:errors.length?errors:['本步固定规则通过；不代表设备执行或实车验收']};
}
export function summarize(rows,expected){
 const times=rows.filter(r=>Number.isFinite(r.elapsedMs)).map(r=>r.elapsedMs).sort((a,b)=>a-b);
 const mean=times.length?Math.round(times.reduce((a,b)=>a+b,0)/times.length):null;
 const p95=times.length?times[Math.ceil(times.length*.95)-1]:null;
 const result={requests:rows.length,expected,pass:rows.filter(r=>r.verdict==='pass').length,boundary:rows.filter(r=>r.verdict==='boundary_pass').length,fail:rows.filter(r=>!['pass','boundary_pass'].includes(r.verdict)).length,meanMs:mean,p95Ms:p95,maxMs:times.at(-1)??null,complete:rows.length===expected&&times.length===expected};
 result.performancePassed=mean!==null&&mean<=2500&&p95<=5000;
 result.passed=result.complete&&result.fail===0&&result.performancePassed;
 return result;
}
