// Live dev text evaluation. Credentials are read locally and never written to results.
import fs from 'node:fs';
import { randomUUID, createHash } from 'node:crypto';
import { performance } from 'node:perf_hooks';
import { execFileSync } from 'node:child_process';
import { evaluate, summarize, productCoverage } from './navigation-benchmark.mjs';
import { scenarios } from './navigation-scenarios.mjs';
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
const suiteFingerprint=createHash('sha256').update(fs.readFileSync(new URL('./navigation-cloud-text.mjs',import.meta.url))).update(fs.readFileSync(new URL('./navigation-benchmark.mjs',import.meta.url))).update(fs.readFileSync(new URL('./navigation-scenarios.mjs',import.meta.url))).digest('hex');
const sourceDirty=execFileSync('git',['status','--porcelain','--untracked-files=no'],{cwd:new URL('../../',import.meta.url),encoding:'utf8'}).trim().length>0;
const report={suiteVersion:2,suiteFingerprint,sourceDirty,revision,deployedRelease:release,repetitions,focus:focus??null,startedAt:new Date().toISOString(),environment:'dev',endpoint,protocol:'1.2',measurement:'Client monotonic send-to-final-reply; includes network, model and search. Excludes handshake, ASR, TTS and device execution.',results:[]};

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
    const r={id:`${id}-${turn+1}`,scenario:id,productIds:productCoverage[id]??[],expected,...await c.text(inputs[turn]),repetition};
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
report.coveredProductIds=[...new Set(report.results.flatMap(r=>r.productIds??[]))].sort();
report.byScenario=Object.fromEntries(selected.map(([id,,inputs])=>[id,summarize(report.results.filter(r=>r.scenario===id),inputs.length*repetitions)]));
const baseline=JSON.parse(fs.readFileSync(new URL('../../docs/qa/navigation-cloud-text-results.json',import.meta.url)));
const baselineIds=new Set(baseline.results.map(r=>r.id));
const baselineRows=report.results.filter(r=>baselineIds.has(r.id));
const addedRows=report.results.filter(r=>!baselineIds.has(r.id));
report.groups={baseline:summarize(baselineRows,selected.reduce((n,[id,,inputs])=>n+(baselineIds.has(`${id}-1`)?inputs.length:0),0)*repetitions),added:summarize(addedRows,selected.reduce((n,[id,,inputs])=>n+(!baselineIds.has(`${id}-1`)?inputs.length:0),0)*repetitions)};
if(report.fullSuite && (!report.groups.baseline.passed || !report.groups.added.passed)) report.summary.passed=false;
report.comparison=report.results.map(r=>{const old=baseline.results.find(b=>b.id===r.id);return{id:r.id,repetition:r.repetition,baselineMs:old?.elapsedMs??null,elapsedMs:r.elapsedMs??null,deltaMs:old&&r.elapsedMs!=null?r.elapsedMs-old.elapsedMs:null,baselineVerdict:old?evaluate(old,baseline.results).verdict:null,verdict:r.verdict};});
fs.writeFileSync(output,JSON.stringify(report,null,2)+'\n');
console.log(JSON.stringify({output:output.pathname,fullSuite:report.fullSuite,summary:report.summary}));
if(!report.summary.passed)process.exitCode=1;
