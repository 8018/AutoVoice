import fs from 'node:fs';
import {randomUUID} from 'node:crypto';
import {performance} from 'node:perf_hooks';
export function readBenchmarkToken(){
const suppliedToken=process.env.NAVIGATION_BENCHMARK_TOKEN||(process.env.NAVIGATION_BENCHMARK_TOKEN_FILE?fs.readFileSync(process.env.NAVIGATION_BENCHMARK_TOKEN_FILE,'utf8').trim():null);
const config=suppliedToken?'':fs.readFileSync(new URL('../../AutoVoice/local.properties',import.meta.url),'utf8');
const token=suppliedToken||config.split(/\r?\n/).find(x=>x.startsWith('gateway.authToken='))?.slice(18).trim();
if(!token) throw Error('Missing local gateway token');
return token;
}
export async function connect({token,endpoint}){
 const ws=new WebSocket(endpoint);const pending=[];let active=null;
 ws.onmessage=e=>{const m=JSON.parse(e.data);if(active && ['reply_partial','pending','reply'].includes(m.type) && [m.payload?.requestId,m.payload?.utteranceId,m.payload?.segmentId].includes(active.id)){active.firstMs??=performance.now()-active.start;}
 for(const p of [...pending])if(p.match(m)){pending.splice(pending.indexOf(p),1);clearTimeout(p.timer);p.resolve(m);}};
 function wait(match,ms=60000){return new Promise((resolve,reject)=>{const p={match,resolve,timer:setTimeout(()=>{pending.splice(pending.indexOf(p),1);reject(Error('response_timeout'));},ms)};pending.push(p);});}
 const send=(type,payload)=>ws.send(JSON.stringify({type,payload}));
 await new Promise((resolve,reject)=>{const timer=setTimeout(()=>{ws.close();reject(Error('connection_timeout'));},15000);ws.onopen=()=>{clearTimeout(timer);resolve()};ws.onerror=()=>{clearTimeout(timer);ws.close();reject(Error('connection_failed'))};});
 const future=wait(m=>m.type==='ready'||m.type==='error',15000);send('hello',{client:'autovoice-android',protocolVersion:'1.2',capabilities:['navigation_route_preview'],deviceId:'demo-1',authToken:token});
 let ready;try{ready=await future;if(ready.type!=='ready')throw Error('handshake_rejected');}catch(e){ws.close();for(const p of pending)clearTimeout(p.timer);throw e;}
 return {async route(input){
 const operationId=randomUUID();const result=wait(m=>m.type==='navigation_route_preview_result'&&m.payload?.operationId===operationId,15000);
 const start=performance.now();send('navigation_route_preview',{sessionId:ready.payload.sessionId,operationId,...input});
 const message=await result;return {operationId,elapsedMs:Math.round(performance.now()-start),payload:message.payload};
 },async text(input){const id=randomUUID();const r=wait(m=>['reply','error'].includes(m.type)&&[m.payload?.requestId,m.payload?.utteranceId,m.payload?.segmentId].includes(id));active={id,start:performance.now()};send('text_request',{sessionId:ready.payload.sessionId,requestId:id,utteranceId:id,segmentId:id,text:input,language:ready.payload.language,inputSource:'text',contextVersion:1,context:{}});let m;try{m=await r;}catch(e){return{input,elapsedMs:Math.round(performance.now()-active.start),error:e.message};}
 const result={input,elapsedMs:Math.round(performance.now()-active.start),firstResponseMs:active.firstMs==null?null:Math.round(active.firstMs),type:m.type,text:m.payload.text??m.payload.speakText??null,intent:m.payload.intent??null};
 const selectionId=m.payload.intent?.slots?.selectionId?.value;if(selectionId)send('navigation_selection_start',{sessionId:ready.payload.sessionId,selectionId});return result;},close(){ws.close();for(const p of pending)clearTimeout(p.timer);}};
}
