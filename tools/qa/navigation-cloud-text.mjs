// Live dev text evaluation. Credentials are read locally and never written to results.
import fs from 'node:fs';
import { randomUUID } from 'node:crypto';
import { performance } from 'node:perf_hooks';
import { execFileSync } from 'node:child_process';
import { evaluate, summarize } from './navigation-benchmark.mjs';
const suppliedToken=process.env.NAVIGATION_BENCHMARK_TOKEN||(process.env.NAVIGATION_BENCHMARK_TOKEN_FILE?fs.readFileSync(process.env.NAVIGATION_BENCHMARK_TOKEN_FILE,'utf8').trim():null);
const config=suppliedToken?'':fs.readFileSync(new URL('../../AutoVoice/local.properties',import.meta.url),'utf8');
const token=suppliedToken||config.split(/\r?\n/).find(x=>x.startsWith('gateway.authToken='))?.slice(18).trim();
if(!token) throw Error('Missing local gateway token');
const focus=process.argv.find(x=>x.startsWith('--focus='))?.slice(8).split(',');
const revision=execFileSync('git',['rev-parse','HEAD'],{cwd:new URL('../../',import.meta.url),encoding:'utf8'}).trim();
const release=process.argv.find(x=>x.startsWith('--release='))?.slice(10)??'unverified';
const repetitions=Number(process.argv.find(x=>x.startsWith('--repetitions='))?.slice(14)??1);
if(!Number.isInteger(repetitions)||repetitions<1||repetitions>10)throw Error('repetitions must be 1..10');
const directory=new URL('../../docs/qa/navigation-benchmark-runs/',import.meta.url);fs.mkdirSync(directory,{recursive:true});
const output=new URL(`${new Date().toISOString().replaceAll(':','-')}-${revision.slice(0,7)}.json`,directory);
const endpoint=process.env.NAVIGATION_BENCHMARK_ENDPOINT||'ws://8.153.153.77:8090/ws';
const report={suiteVersion:1,revision,deployedRelease:release,repetitions,focus:focus??null,startedAt:new Date().toISOString(),environment:'dev',endpoint,protocol:'1.2',measurement:'Client monotonic send-to-final-reply; includes network, model and search. Excludes handshake, ASR, TTS and device execution.',results:[]};
const scenarios=[
 ['name','按名称返回有效候选，选择前不执行',['导航到北京南站']],
 ['city','候选限定在上海',['带我去上海博物馆']],
 ['entrance','保留航站楼限定',['导航到上海虹桥机场T2航站楼']],
 ['numeric_city','三亚地名不能改成数字',['导航到三亚凤凰国际机场']],
 ['brand','保留北京及星巴克品牌条件',['找北京的星巴克']],
 ['opening','不能核验营业时明确未知，不伪称满足',['找北京现在营业的咖啡店']],
 ['charging','返回真实充电站，不编造空闲桩',['找北京南站附近的充电站']],
 ['energy','缺车辆数据不得生成保证可达的补能方案',['到北京南站至少留两成电，帮我规划补能']],
 ['trip','有序两站草稿，先南站后西站',['先去北京南站，再去北京西站']],
 ['home','委派端侧常用地址，不编造家庭地址',['回家']],
 ['live_eta','没有活动路线不能编造剩余时间',['还有多久到']],
 ['readonly','询问耗时只预览；选中后仍不启动',['去北京南站要多久，只查询路线，不开始导航','第一个']],
 ['negative','否定选择不执行；明确选择才输出导航动作',['导航到北京南站','不要去第二个','第一个']],
 ['correction','修正城市后仅保留新城市候选',['导航到北京的博物馆','不是北京，是上海']],
 ['next_batch','换批返回新候选，不重复原候选',['找北京的咖啡店','换一批']],
 ['cancel','取消候选后序号不得触发原目标',['导航到北京南站','取消','第一个']],
];
async function connect(){
 const ws=new WebSocket(endpoint);const pending=[];let active=null;
 ws.onmessage=e=>{const m=JSON.parse(e.data);if(active && ['reply_partial','pending','reply'].includes(m.type) && [m.payload?.requestId,m.payload?.utteranceId,m.payload?.segmentId].includes(active.id)){active.firstMs??=performance.now()-active.start;}
 for(const p of [...pending])if(p.match(m)){pending.splice(pending.indexOf(p),1);clearTimeout(p.timer);p.resolve(m);}};
 function wait(match,ms=60000){return new Promise((resolve,reject)=>{const p={match,resolve,timer:setTimeout(()=>{pending.splice(pending.indexOf(p),1);reject(Error('response_timeout'));},ms)};pending.push(p);});}
 const send=(type,payload)=>ws.send(JSON.stringify({type,payload}));
 await new Promise((resolve,reject)=>{const timer=setTimeout(()=>{ws.close();reject(Error('connection_timeout'));},15000);ws.onopen=()=>{clearTimeout(timer);resolve()};ws.onerror=()=>{clearTimeout(timer);ws.close();reject(Error('connection_failed'))};});
 const future=wait(m=>m.type==='ready'||m.type==='error',15000);send('hello',{client:'autovoice-android',protocolVersion:'1.2',capabilities:['navigation_route_preview'],deviceId:'demo-1',authToken:token});
 const ready=await future;if(ready.type!=='ready'){ws.close();throw Error('handshake_rejected');}
 return {async text(input){const id=randomUUID();const r=wait(m=>['reply','error'].includes(m.type)&&[m.payload?.requestId,m.payload?.utteranceId,m.payload?.segmentId].includes(id));active={id,start:performance.now()};send('text_request',{sessionId:ready.payload.sessionId,requestId:id,utteranceId:id,segmentId:id,text:input,language:ready.payload.language,inputSource:'text',contextVersion:1,context:{}});let m;try{m=await r;}catch(e){return{input,elapsedMs:Math.round(performance.now()-active.start),error:e.message};}
 const result={input,elapsedMs:Math.round(performance.now()-active.start),firstResponseMs:active.firstMs==null?null:Math.round(active.firstMs),type:m.type,text:m.payload.text??m.payload.speakText??null,intent:m.payload.intent??null};
 const selectionId=m.payload.intent?.slots?.selectionId?.value;if(selectionId)send('navigation_selection_start',{sessionId:ready.payload.sessionId,selectionId});return result;},close(){ws.close();for(const p of pending)clearTimeout(p.timer);}};
}
const selected=scenarios.filter(s=>!focus||focus.includes(s[0]));
if(!selected.length||focus?.some(id=>!selected.some(s=>s[0]===id)))throw Error('Unknown or empty focus');
for(let repetition=1;repetition<=repetitions;repetition++){
 for(const [id,expected,inputs]of selected){
  let c;const history=[];
  try {
   c=await connect();
   for(let turn=0;turn<inputs.length;turn++){
    const r={id:`${id}-${turn+1}`,scenario:id,expected,...await c.text(inputs[turn]),repetition};
    Object.assign(r,evaluate(r,history));history.push(r);report.results.push(r);
    fs.writeFileSync(output,JSON.stringify(report,null,2)+'\n');
    console.log(JSON.stringify({id:r.id,repetition,elapsedMs:r.elapsedMs,verdict:r.verdict,checks:r.checks,text:r.text,intent:r.intent?.intent,error:r.error}));
   }
  }catch(e){report.results.push({id,expected,repetition,error:e.message,verdict:'fail'});}
  finally{c?.close();}
 }
}
report.finishedAt=new Date().toISOString();
report.summary=summarize(report.results,selected.reduce((n,s)=>n+s[2].length,0)*repetitions);
report.fullSuite=!focus;
const baseline=JSON.parse(fs.readFileSync(new URL('../../docs/qa/navigation-cloud-text-results.json',import.meta.url)));
report.comparison=report.results.map(r=>{const old=baseline.results.find(b=>b.id===r.id);return{id:r.id,repetition:r.repetition,baselineMs:old?.elapsedMs??null,elapsedMs:r.elapsedMs??null,deltaMs:old&&r.elapsedMs!=null?r.elapsedMs-old.elapsedMs:null,baselineVerdict:old?evaluate(old,baseline.results).verdict:null,verdict:r.verdict};});
fs.writeFileSync(output,JSON.stringify(report,null,2)+'\n');
console.log(JSON.stringify({output:output.pathname,fullSuite:report.fullSuite,summary:report.summary}));
if(!report.summary.passed)process.exitCode=1;
