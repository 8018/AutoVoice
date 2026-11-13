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
function validAudioBase64(value){
 if(typeof value!=='string'||!value.length||value.length>8*1024*1024||value.length%4!==0)return false;
 const padding=value.endsWith('==')?2:value.endsWith('=')?1:0;
 // A flat character check avoids repeated capture groups on multi-megabyte audio.
 return !/[^A-Za-z0-9+/]/.test(value.slice(0,value.length-padding));
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
 return {async tts(text){
 if(typeof text!=='string'||!text.trim())throw Error('invalid_tts_text');
 const id=randomUUID(),start=performance.now();
 const response=wait(m=>['tts_response','error'].includes(m.type)&&m.payload?.segmentId===id,30000);
 send('tts_request',{sessionId:ready.payload.sessionId,segmentId:id,text});
 const m=await response,p=m.payload;
 if(m.type!=='tts_response'||p.text!==text||!validAudioBase64(p.dataBase64))throw Error('invalid_tts_response');
 return {text:p.text,mime:p.mime,audio:Buffer.from(p.dataBase64,'base64'),sampleRate:p.sampleRate,channels:p.channels,encoding:p.encoding,elapsedMs:Math.round(performance.now()-start)};
 },async voice(pcm,{adoptSelection=true}={}){
 if(!Buffer.isBuffer(pcm)||!pcm.length||pcm.length%2||pcm.length>16000*2*15)throw Error('invalid_voice_pcm');
 const id=randomUUID(),start=performance.now();active={id,start};
 // Attach rejection immediately: a connection may time out while frames are still pacing.
 const response=wait(m=>['reply','error'].includes(m.type)&&[m.payload?.requestId,m.payload?.utteranceId,m.payload?.segmentId].includes(id),20000).then(message=>({message}),()=>({error:'response_timeout'}));
 send('audio_start',{sessionId:ready.payload.sessionId,sampleRate:16000,channels:1,encoding:'pcm_s16le',segmentId:id,utteranceId:id});
 for(let offset=0;offset<pcm.length;offset+=640){
 ws.send(pcm.subarray(offset,Math.min(offset+640,pcm.length)));
 const due=start+Math.min(offset+640,pcm.length)/32;
 await new Promise(resolve=>setTimeout(resolve,Math.max(0,due-performance.now())));
 }
 send('audio_end',{sessionId:ready.payload.sessionId,durationMs:Math.round(pcm.length/32)});
 const {message:m,error}=await response;
 const result={elapsedMs:Math.round(performance.now()-start),firstResponseMs:active.firstMs==null?null:Math.round(active.firstMs),audioDurationMs:Math.round(pcm.length/32)};
 active=null;
 if(error)return {...result,error};
 Object.assign(result,{type:m.type,asrText:m.payload.asrText??'',text:m.payload.text??m.payload.speakText??null,intent:m.payload.intent??null});
 const selectionId=m.payload.intent?.slots?.selectionId?.value;
 if(adoptSelection&&m.payload.intent?.intent==='choose_destination'&&selectionId)send('navigation_selection_start',{sessionId:ready.payload.sessionId,selectionId});
 return result;
 },async route(input){
 const operationId=randomUUID();const result=wait(m=>m.type==='navigation_route_preview_result'&&m.payload?.operationId===operationId,15000);
 const start=performance.now();send('navigation_route_preview',{sessionId:ready.payload.sessionId,operationId,...input});
 const message=await result;return {operationId,elapsedMs:Math.round(performance.now()-start),payload:message.payload};
 },async text(input){const id=randomUUID();const r=wait(m=>['reply','error'].includes(m.type)&&[m.payload?.requestId,m.payload?.utteranceId,m.payload?.segmentId].includes(id));active={id,start:performance.now()};send('text_request',{sessionId:ready.payload.sessionId,requestId:id,utteranceId:id,segmentId:id,text:input,language:ready.payload.language,inputSource:'text',contextVersion:1,context:{}});let m;try{m=await r;}catch(e){return{input,elapsedMs:Math.round(performance.now()-active.start),error:e.message};}
 const result={input,elapsedMs:Math.round(performance.now()-active.start),firstResponseMs:active.firstMs==null?null:Math.round(active.firstMs),type:m.type,text:m.payload.text??m.payload.speakText??null,intent:m.payload.intent??null};
 const selectionId=m.payload.intent?.slots?.selectionId?.value;if(selectionId)send('navigation_selection_start',{sessionId:ready.payload.sessionId,selectionId});return result;},close(){ws.close();for(const p of pending)clearTimeout(p.timer);}};
}
