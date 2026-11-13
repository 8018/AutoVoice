import {candidates} from './navigation-benchmark.mjs';

// Decode only explicitly supported PCM formats. Never interpret a WAV header as speech.
export function decodeSpeechAudio({audio,mime,sampleRate,channels,encoding}) {
  const bytes=Buffer.from(audio??[]);
  let pcm=bytes,rate=sampleRate;
  if(['audio/wav','audio/x-wav','audio/wave'].includes(mime?.split(';')[0])) {
    if(bytes.length<12||bytes.toString('ascii',0,4)!=='RIFF'||bytes.toString('ascii',8,12)!=='WAVE'||bytes.readUInt32LE(4)+8!==bytes.length)throw Error('invalid_wav_container');
    let format=null,data=null;
    for(let offset=12;offset<bytes.length;) {
      if(offset+8>bytes.length)throw Error('truncated_wav_chunk');
      const id=bytes.toString('ascii',offset,offset+4),size=bytes.readUInt32LE(offset+4),end=offset+8+size;
      if(end>bytes.length)throw Error('truncated_wav_data');
      if(id==='fmt ') {
        if(format||size<16)throw Error('invalid_wav_format');
        format={code:bytes.readUInt16LE(offset+8),channels:bytes.readUInt16LE(offset+10),rate:bytes.readUInt32LE(offset+12),byteRate:bytes.readUInt32LE(offset+16),align:bytes.readUInt16LE(offset+20),bits:bytes.readUInt16LE(offset+22)};
      }
      if(id==='data'){if(data)throw Error('duplicate_wav_data');data=bytes.subarray(offset+8,end);}
      offset=end+(size%2);
      if(offset>bytes.length)throw Error('missing_wav_padding');
    }
    if(!format||!data||format.code!==1||format.channels!==1||format.bits!==16||format.align!==2||format.byteRate!==format.rate*2)throw Error('unsupported_wav_format');
    pcm=data;rate=format.rate;
  } else if(mime?.split(';')[0]!=='audio/pcm'||channels!==1||encoding!=='pcm_s16le')throw Error('unsupported_audio_format');
  if(!Number.isInteger(rate)||rate<8000||rate>48000||pcm.length===0||pcm.length%2)throw Error('invalid_pcm_format');
  const durationMs=pcm.length/2/rate*1000;
  if(durationMs>60000)throw Error('audio_too_long');
  if(rate!==16000) {
    const source=pcm,count=Math.floor(source.length/2*16000/rate);pcm=Buffer.alloc(count*2);
    for(let i=0;i<count;i++){
      const at=i*rate/16000,left=Math.floor(at),right=Math.min(left+1,source.length/2-1),fraction=at-left;
      pcm.writeInt16LE(Math.round(source.readInt16LE(left*2)*(1-fraction)+source.readInt16LE(right*2)*fraction),i*2);
    }
  }
  return {pcm,durationMs:Math.round(durationMs),sourceSampleRate:rate};
}
const slot=(row,key)=>row?.intent?.slots?.[key]?.value;
function base(row,pattern){const failures=[];if(row?.type!=='reply'||row?.error)failures.push('missing_reply');if(!pattern.test(row?.asrText??''))failures.push('asr_mismatch');if(!Number.isFinite(row?.elapsedMs)||row.elapsedMs>15000)failures.push('voice_latency_over_15s');return failures;}
export function judgeVoiceCandidates(row){
  const failures=base(row,/北京.*博物馆/),list=candidates(row??{});
  if(row?.intent?.domain!=='navigation'||row.intent.intent!=='choose_destination')failures.push('not_candidate_choice');
  if(list.length<2||list.length>3)failures.push('requires_two_to_three_candidates');
  if(!slot(row,'selectionId'))failures.push('missing_selection_identity');
  if(new Set(list.map(c=>c.candidateId)).size!==list.length||list.some(c=>!c.candidateId))failures.push('invalid_candidate_identity');
  if(list.some(c=>!c.poiname||!Number.isFinite(c.lat)||!Number.isFinite(c.lon)||c.lat<39.4||c.lat>41.1||c.lon<115.4||c.lon>117.6))failures.push('invalid_beijing_candidates');
  let after=0;for(let i=0;i<list.length;i++){
    const text=row.text??'',ordinal=text.indexOf(`第${i+1}个`,after),name=text.indexOf(list[i].poiname,ordinal);
    if(ordinal<0||name<ordinal)failures.push(`missing_spoken_candidate_${i+1}`);else after=name+list[i].poiname.length;
  }
  return {passed:failures.length===0,failures};
}
export function judgeVoiceSelection(row,offer){
  const failures=base(row,/第[二2]个/),candidate=candidates(offer??{})[1];
  if(row?.intent?.domain!=='navigation'||row.intent.intent!=='navigate')failures.push('not_navigation_selection');
  if(!candidate||!slot(offer,'selectionId')||slot(row,'selectionId')!==slot(offer,'selectionId')||['candidateId','poiname','lat','lon'].some(k=>slot(row,k)!==candidate[k]))failures.push('second_candidate_identity_mismatch');
  return {passed:failures.length===0,failures};
}
