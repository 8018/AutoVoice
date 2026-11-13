// Creates only public synthetic speech. Reuse the generated suite/audio unchanged for comparisons.
import fs from 'node:fs';
import path from 'node:path';
import {createHash} from 'node:crypto';
import {connect,readBenchmarkToken} from './navigation-cloud-client.mjs';
import {decodeSpeechAudio} from './navigation-voice-judge.mjs';
const output=process.argv[2];
if(!output)throw Error('Usage: node tools/qa/navigation-asr-prepare.mjs /absolute/output/directory');
if(!path.isAbsolute(output))throw Error('Output must be absolute');
const spec=JSON.parse(fs.readFileSync(new URL('../../docs/qa/navigation-asr-cases-v1.json',import.meta.url),'utf8'));
const suitePath=path.join(output,'suite.json');
if(fs.existsSync(suitePath))throw Error('Refusing to replace an existing fixture suite; reuse it or choose a new directory');
fs.mkdirSync(path.join(output,'audio'),{recursive:true});
function wav(pcm){const h=Buffer.alloc(44);h.write('RIFF');h.writeUInt32LE(36+pcm.length,4);h.write('WAVEfmt ',8);h.writeUInt32LE(16,16);h.writeUInt16LE(1,20);h.writeUInt16LE(1,22);h.writeUInt32LE(16000,24);h.writeUInt32LE(32000,28);h.writeUInt16LE(2,32);h.writeUInt16LE(16,34);h.write('data',36);h.writeUInt32LE(pcm.length,40);return Buffer.concat([h,pcm]);}
function noise(bytes,seed=20261114){const b=Buffer.alloc(bytes);let state=seed;for(let i=0;i<bytes;i+=2){state=(Math.imul(state,1664525)+1013904223)>>>0;b.writeInt16LE(Math.round((state/4294967296*2-1)*1800),i);}return b;}
function mix(a,b,gain){const out=Buffer.alloc(Math.max(a.length,b.length));for(let i=0;i<out.length;i+=2){const v=(i<a.length?a.readInt16LE(i):0)+(i<b.length?b.readInt16LE(i)*gain:0);out.writeInt16LE(Math.max(-32768,Math.min(32767,Math.round(v))),i);}return out;}
const pcmById=new Map(),rows=[];let client;
try{
 client=await connect({token:readBenchmarkToken(),endpoint:process.env.NAVIGATION_BENCHMARK_ENDPOINT||'ws://8.153.153.77:8090/ws'});
 const playbackText='找到三个地点，第一个是北京南站，第二个是北京站，第三个是北京西站，您要去第几个？';
 const playback=decodeSpeechAudio(await client.tts(playbackText)).pcm;
 fs.writeFileSync(path.join(output,'audio','playback-reference.wav'),wav(playback));
 for(const c of spec.cases){
  let pcm,provenance;
  if(c.kind==='tts'){const speech=await client.tts(c.text),decoded=decodeSpeechAudio(speech);pcm=decoded.pcm;provenance={source:'dev-gateway-tts',text:c.text,sourceSampleRate:decoded.sourceSampleRate,mime:speech.mime};}
  else if(c.kind==='silence'){pcm=Buffer.alloc(64000);provenance={source:'generated-zero-pcm',durationMs:2000};}
  else if(c.kind==='noise'){pcm=noise(64000);provenance={source:'deterministic-uniform-noise',seed:20261114,amplitude:1800};}
  else {const source=pcmById.get(c.source);if(!source)throw Error('Missing source case');pcm=c.kind==='overlap'?mix(source,playback.subarray(0,source.length),.25):mix(source,noise(source.length),1);provenance={source:c.source,derivative:c.kind,seed:20261114,noiseAmplitude:c.kind==='noise-mix'?1800:null,playbackGain:c.kind==='overlap'?.25:null,playbackText:c.kind==='overlap'?playbackText:null};}
  pcmById.set(c.id,pcm);const bytes=wav(pcm),file=`audio/${c.id}.wav`;fs.writeFileSync(path.join(output,file),bytes);
  rows.push({...c,audio:{path:file,bytes:bytes.length,sha256:createHash('sha256').update(bytes).digest('hex')},provenance});
  console.log(JSON.stringify({id:c.id,audioMs:pcm.length/32}));
 }
 fs.writeFileSync(suitePath,JSON.stringify({...spec,createdAt:new Date().toISOString(),measurement:'Public synthetic PCM16 mono 16k; no device microphone or acoustic AEC.',cases:rows},null,2)+'\n');
 console.log(JSON.stringify({suite:suitePath,cases:rows.length}));
}finally{client?.close();}
