// Public synthetic ASR evaluation only. This never certifies acoustic barge-in or business execution.
export function normalizeAsr(text) {
 return text.normalize('NFKC').toLowerCase().replace(/第([1-9])个/g,(_,n)=>`第${'一二三四五六七八九'[Number(n)-1]}个`).replace(/[\p{P}\p{Z}\s]/gu,'');
}
export function characterErrorRate(reference,hypothesis) {
 const a=[...normalizeAsr(reference)],b=[...normalizeAsr(hypothesis)];
 let prev=Array.from({length:b.length+1},(_,i)=>i);
 for(let i=1;i<=a.length;i++){const next=[i];for(let j=1;j<=b.length;j++)next[j]=Math.min(next[j-1]+1,prev[j]+1,prev[j-1]+(a[i-1]===b[j-1]?0:1));prev=next;}
 return prev[b.length]/Math.max(1,a.length);
}
const finiteNonnegative=x=>typeof x==='number'&&Number.isFinite(x)&&x>=0;
const percentile=(values,p)=>values.length?[...values].sort((a,b)=>a-b)[Math.max(0,Math.ceil(values.length*p)-1)]:null;
export function scoreAsrComparison(suite,catalog,run,expectedHashes) {
 const failures=[],byModel={};
 const pipeline=run.runtime?.pipeline??'raw';
 if(pipeline==='raw'&&(run.runtime?.vad!==undefined||(run.models??[]).some(m=>(m.results??[]).some(r=>['speechAdmitted','asrSkipped','vadComputeMs','asrComputeMs'].some(k=>Object.hasOwn(r,k))))))failures.push('raw_pipeline_contains_vad_evidence');
 if(!['raw','silero'].includes(pipeline))failures.push('invalid_pipeline');
 if(pipeline==='silero'){
  const vad=run.runtime?.vad;
  if(!expectedHashes.vadSha256||vad?.manifestSha256!==expectedHashes.vadSha256||vad?.fileSha256!==expectedHashes.vadModelSha256||vad?.threshold!==0.5||vad?.minSpeechMs!==64||vad?.minSilenceMs!==960||vad?.frameSamples!==512||vad?.contextSamples!==64||!finiteNonnegative(vad?.loadMs))failures.push('invalid_vad_identity_or_config');
 }
 const cases=suite.cases??[],models=catalog.models??[],actual=run.models??[];
 if(suite.schemaVersion!==1||catalog.schemaVersion!==1||run.schemaVersion!==1)failures.push('invalid_schema');
 if(!cases.length||new Set(cases.map(c=>c.id)).size!==cases.length)failures.push('invalid_suite');
 if(models.length!==2||!['zipformer','sensevoice'].every(id=>models.some(m=>m.id===id)))failures.push('invalid_model_catalog');
 if(!['targetApkSha256','instrumentationApkSha256'].every(k=>typeof run.runtime?.[k]==='string'&&/^[0-9a-f]{64}$/.test(run.runtime[k])))failures.push('missing_executed_build_identity');
 if(run.status!=='complete'||run.error)failures.push('incomplete_run');
 if(run.suiteVersion!==suite.suiteVersion||run.suiteSha256!==expectedHashes.suiteSha256||run.modelsSha256!==expectedHashes.modelsSha256)failures.push('input_identity_mismatch');
 if(actual.length!==models.length||new Set(actual.map(m=>m.id)).size!==actual.length||actual.some(m=>!models.some(e=>e.id===m.id)))failures.push('model_coverage_mismatch');
 for(const model of models){
  const result=actual.find(m=>m.id===model.id),rows=result?.results??[],groups={},judged=[];
  if(!result||result.error||!finiteNonnegative(result.loadMs))failures.push(`${model.id}:model_failed`);
  if(rows.length!==cases.length||new Set(rows.map(r=>r.id)).size!==rows.length||rows.some(r=>!cases.some(c=>c.id===r.id)))failures.push(`${model.id}:case_coverage_mismatch`);
  for(const c of cases){
   const row=rows.find(r=>r.id===c.id),reasons=[];
   if(!row||row.error)reasons.push('missing_or_failed_result');
   if(row?.audioSha256!==c.audio.sha256)reasons.push('audio_identity_mismatch');
   if(!finiteNonnegative(row?.decodeMs)||!finiteNonnegative(row?.rtf)||!finiteNonnegative(row?.audioMs)||row.audioMs===0)reasons.push('invalid_timing');
   if(row&&finiteNonnegative(row.decodeMs)&&row.audioMs>0&&Math.abs(row.rtf-row.decodeMs/row.audioMs)>0.000001)reasons.push('inconsistent_rtf');
   if(pipeline==='silero'&&row){
    if(typeof row.speechAdmitted!=='boolean'||row.asrSkipped!==!row.speechAdmitted||!finiteNonnegative(row.vadComputeMs)||!finiteNonnegative(row.asrComputeMs)||row.vadComputeMs+row.asrComputeMs>row.decodeMs+0.001||(row.asrSkipped&&(row.transcript!==''||row.asrComputeMs!==0)))reasons.push('invalid_vad_result');
   }
   if(!Array.isArray(c.expected)||!c.expected.length||typeof row?.transcript!=='string')reasons.push('invalid_transcript_contract');
   const exact=typeof row?.transcript==='string'&&c.expected?.some(e=>normalizeAsr(e)===normalizeAsr(row.transcript));
   if(!exact)reasons.push('transcript_mismatch');
   // Compute budget is deliberately separate from human endpoint / acoustic latency.
   if(row?.rtf>1||row?.decodeMs>15000)reasons.push('decode_budget_exceeded');
   const passed=reasons.length===0;
   if(!passed)failures.push(`${model.id}:${c.id}:${reasons.join(',')}`);
   const group=groups[c.group]??={total:0,passed:0,exact:0,decodeMs:[]};group.total++;group.passed+=Number(passed);group.exact+=Number(exact);
   if(finiteNonnegative(row?.decodeMs))group.decodeMs.push(row.decodeMs);
   judged.push({id:c.id,group:c.group,productIds:c.productIds??[],passed,exact,failures:reasons,cer:typeof row?.transcript==='string'&&c.expected?.length?Math.min(...c.expected.map(e=>characterErrorRate(e,row.transcript))):null,decodeMs:row?.decodeMs??null,rtf:row?.rtf??null});
  }
  for(const g of Object.values(groups)){g.exactRate=g.exact/g.total;g.decodeP50Ms=percentile(g.decodeMs,.5);g.decodeP95Ms=percentile(g.decodeMs,.95);delete g.decodeMs;}
  const times=judged.map(r=>r.decodeMs).filter(finiteNonnegative);
  byModel[model.id]={total:cases.length,passed:judged.filter(r=>r.passed).length,exactRate:cases.length?judged.filter(r=>r.exact).length/cases.length:0,loadMs:result?.loadMs??null,decodeP50Ms:percentile(times,.5),decodeP95Ms:percentile(times,.95),groups,results:judged};
 }
 return {suiteVersion:suite.suiteVersion,pipeline,passed:failures.length===0,failures,acousticAcceptance:'not-tested',measurement:'Unpaced synthetic file decode; excludes microphone, VAD endpoint, AEC, network and business execution.',byModel};
}
