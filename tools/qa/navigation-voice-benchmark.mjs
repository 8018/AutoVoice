// Dev-only synthetic public speech. Never sends device/private recordings or opens a map.
import fs from 'node:fs';
import {createHash} from 'node:crypto';
import {execFileSync} from 'node:child_process';
import {connect,readBenchmarkToken} from './navigation-cloud-client.mjs';
import {decodeSpeechAudio,judgeVoiceCandidates,judgeVoiceSelection} from './navigation-voice-judge.mjs';

const root=new URL('../../',import.meta.url);
const revision=execFileSync('git',['rev-parse','HEAD'],{cwd:root,encoding:'utf8'}).trim();
const release=process.argv.find(a=>a.startsWith('--release='))?.slice(10);
if(!release||release==='unverified')throw Error('An actual deployed --release revision is required');
const fingerprint=createHash('sha256');
for(const file of ['navigation-voice-benchmark.mjs','navigation-voice-judge.mjs','navigation-cloud-client.mjs'])fingerprint.update(fs.readFileSync(new URL(file,import.meta.url)));
const startedAt=new Date().toISOString();
const directory=new URL('../../docs/qa/navigation-voice-runs/',import.meta.url);
fs.mkdirSync(directory,{recursive:true});
const output=new URL(`${startedAt.replaceAll(':','-')}-${revision.slice(0,7)}.json`,directory);
const report={suite:'navigation-cloud-voice-v1',suiteVersion:1,suiteFingerprint:fingerprint.digest('hex'),revision,deployedRelease:release,
 sourceDirty:execFileSync('git',['status','--porcelain'],{cwd:root,encoding:'utf8'}).trim().length>0,
 startedAt,environment:'dev',channel:'synthetic-cloud-voice',protocol:'1.2',readOnly:true,
 measurement:'Voice audio_start to final reply, including paced 16 kHz upload, cloud ASR, model/search and network. Fixture TTS and candidate-reply TTS measured separately.',
 limitations:['Synthetic speech only; not microphone, device playback or driving acceptance.','TTS response text is an echo; audio duration does not prove all candidate names are audible.','Navigation selection is inspected only; no action is executed.'],
 results:[]};
const save=()=>fs.writeFileSync(output,JSON.stringify(report,null,2)+'\n');
const record=row=>{report.results.push(row);save();console.log(JSON.stringify({id:row.id,passed:row.passed,elapsedMs:row.elapsedMs,failures:row.failures}));};
let client,offer;
try {
 client=await connect({token:readBenchmarkToken(),endpoint:process.env.NAVIGATION_BENCHMARK_ENDPOINT||'ws://8.153.153.77:8090/ws'});
 for(const [id,input,judge] of [['voice-candidates','找北京的博物馆',judgeVoiceCandidates],['voice-select-second','第二个',r=>judgeVoiceSelection(r,offer)]]){
  try {
   const fixture=await client.tts(input),decoded=decodeSpeechAudio(fixture);
   if(decoded.durationMs<200)throw Error('fixture_audio_too_short');
   const result=await client.voice(decoded.pcm);
   const row={id,productIds:id==='voice-candidates'?['A03','B01']:['B02'],input,fixtureTtsMs:fixture.elapsedMs,fixtureDurationMs:decoded.durationMs,...result,...judge(result)};
   record(row);if(id==='voice-candidates')offer=result;
  }catch{record({id,productIds:id==='voice-candidates'?['A03','B01']:['B02'],passed:false,failures:['voice_or_fixture_request_failed']});}
 }
 try {
  if(!offer||!judgeVoiceCandidates(offer).passed)throw Error('no_valid_candidate_offer');
  const audio=await client.tts(offer.text),decoded=decodeSpeechAudio(audio);
  record({id:'candidate-reply-tts',productIds:['B01'],channel:'tts',requestedText:offer.text,elapsedMs:audio.elapsedMs,audioDurationMs:decoded.durationMs,audioBytes:audio.audio.length,mime:audio.mime,
   passed:decoded.durationMs>=1000&&audio.elapsedMs<=15000,failures:[...(decoded.durationMs<1000?['candidate_audio_too_short']:[]),...(audio.elapsedMs>15000?['tts_latency_over_15s']:[])]});
 }catch{record({id:'candidate-reply-tts',productIds:['B01'],channel:'tts',passed:false,failures:['candidate_tts_failed_or_missing_offer']});}
}catch{report.error='connection_or_credentials_failed';}finally{client?.close();}
report.finishedAt=new Date().toISOString();
report.passed=report.results.length===3&&report.results.every(r=>r.passed);
report.coveredProductIds=[...new Set(report.results.flatMap(r=>r.productIds))].sort();
report.byChannel=Object.fromEntries(['synthetic-cloud-voice','tts'].map(channel=>{
 const rows=report.results.filter(r=>(r.channel??'synthetic-cloud-voice')===channel);
 return [channel,{total:rows.length,passed:rows.filter(r=>r.passed).length,elapsedMs:rows.map(r=>r.elapsedMs??null)}];
}));
save();console.log(JSON.stringify({output:output.pathname,passed:report.passed,byChannel:report.byChannel}));
if(!report.passed)process.exitCode=1;
