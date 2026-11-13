import test from 'node:test';
import assert from 'node:assert/strict';
import {normalizeAsr,characterErrorRate,scoreAsrComparison} from '../navigation-asr-judge.mjs';
const hash='a'.repeat(64);
const suite={schemaVersion:1,suiteVersion:'1',cases:[{id:'second',group:'selection',expected:['第二个'],audio:{sha256:hash}}]};
const models={schemaVersion:1,models:[{id:'zipformer'},{id:'sensevoice'}]};
const row=()=>({id:'second',audioSha256:hash,transcript:'第2个。',audioMs:1000,decodeMs:100,rtf:0.1});
const run=()=>({schemaVersion:1,runtime:{targetApkSha256:hash,instrumentationApkSha256:hash},status:'complete',suiteVersion:'1',modelsSha256:hash,suiteSha256:hash,models:models.models.map(m=>({...m,loadMs:10,results:[row()]}))});
const score=r=>scoreAsrComparison(suite,models,r,{suiteSha256:hash,modelsSha256:hash});
test('normalizes punctuation and ordinals without erasing meaningful words',()=>{assert.equal(normalizeAsr('第２个。'),'第二个');assert.notEqual(normalizeAsr('不要去第二个'),normalizeAsr('第二个'));});
test('CER uses edit distance and counts insertions on empty reference',()=>{assert.equal(characterErrorRate('北京南站','北京站'),0.25);assert.equal(characterErrorRate('','取消'),2);});
test('complete two-model run scores correctly',()=>{assert.equal(score(run()).passed,true);assert.equal(score(run()).byModel.zipformer.exactRate,1);});
test('missing model or case cannot pass',()=>{let r=run();r.models.pop();assert.equal(score(r).passed,false);r=run();r.models[0].results=[];assert.equal(score(r).passed,false);});
test('duplicate results and unknown cases cannot inflate rate',()=>{const r=run();r.models[0].results.push(row());assert.equal(score(r).passed,false);r.models[0].results[1].id='unknown';assert.equal(score(r).passed,false);});
test('mismatched inputs, incomplete runs and bad timing fail',()=>{for(const mutate of [r=>r.status='running',r=>r.suiteVersion='2',r=>r.suiteSha256='b'.repeat(64),r=>r.models[0].results[0].audioSha256='b'.repeat(64),r=>r.models[0].results[0].decodeMs=-1,r=>r.models[0].results[0].rtf=Infinity]){const r=run();mutate(r);assert.equal(score(r).passed,false);}});
test('incorrect transcription fails even if fast',()=>{const r=run();r.models[0].results[0].transcript='第一个';assert.equal(score(r).passed,false);});
test('stress results remain explicit and never imply acoustic acceptance',()=>{const s={...suite,cases:[{...suite.cases[0],group:'synthetic-overlap',diagnosticOnly:true}]};const r=run();r.models[0].results[0].transcript='播报第二个';const out=scoreAsrComparison(s,models,r,{suiteSha256:hash,modelsSha256:hash});assert.equal(out.acousticAcceptance,'not-tested');assert.equal(out.byModel.zipformer.groups['synthetic-overlap'].passed,0);assert.equal(out.byModel.zipformer.groups['synthetic-overlap'].total,1);});
test('rejects wrong schema and inconsistent reported real-time factor',()=>{for(const mutate of [r=>r.schemaVersion=99,r=>r.models[0].results[0].rtf=0.0001]){const r={schemaVersion:1,...run()};mutate(r);assert.equal(score(r).passed,false);}});
test('CLI rejects a reduced suite even when device hashes match the reduced input', async()=>{
 const {default:fs}=await import('node:fs');const {default:os}=await import('node:os');const {default:path}=await import('node:path');const {createHash}=await import('node:crypto');const {spawnSync}=await import('node:child_process');
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'asr-coverage-'));
 try{const sb=JSON.stringify(suite),mb=JSON.stringify(models),hash=b=>createHash('sha256').update(b).digest('hex');
 fs.writeFileSync(path.join(dir,'suite.json'),sb);fs.writeFileSync(path.join(dir,'models.json'),mb);const r=run();r.suiteSha256=hash(sb);r.modelsSha256=hash(mb);fs.writeFileSync(path.join(dir,'raw.json'),JSON.stringify(r));
 const output=path.join(dir,'score.json');const proc=spawnSync(process.execPath,['tools/qa/navigation-asr-score.mjs',dir,path.join(dir,'raw.json'),output]);assert.notEqual(proc.status,0);assert.equal(JSON.parse(fs.readFileSync(output)).passed,false);
 }finally{fs.rmSync(dir,{recursive:true,force:true});}
});
test('versioned public fixtures preserve business cases and match recorded hashes', async()=>{
 const {default:fs}=await import('node:fs');const {createHash}=await import('node:crypto');
 const spec=JSON.parse(fs.readFileSync('docs/qa/navigation-asr-cases-v1.json')),fixtures=JSON.parse(fs.readFileSync('docs/qa/navigation-asr-fixtures-v1.json'));
 assert.equal(fixtures.suiteVersion,spec.suiteVersion);assert.equal(fixtures.cases.length,spec.cases.length);
 for(const expected of spec.cases){const c=fixtures.cases.find(c=>c.id===expected.id);assert.ok(c);for(const key of Object.keys(expected))assert.deepEqual(c[key],expected[key]);const bytes=fs.readFileSync(`tools/qa/fixtures/navigation-asr-v1/${c.audio.path}`);assert.equal(bytes.length,c.audio.bytes);assert.equal(createHash('sha256').update(bytes).digest('hex'),c.audio.sha256);}
});
test('missing executed APK identities cannot pass',()=>{const r=run();r.runtime={};assert.equal(score(r).passed,false);});
test('silero mode cannot pass without pinned gate identity',()=>{const r=run();r.runtime.pipeline='silero';assert.equal(score(r).passed,false);});
test('invalid pipeline cannot masquerade as raw recognizer output',()=>{const r=run();r.runtime.pipeline='unknown';assert.equal(score(r).passed,false);});
test('valid silero admission passes while inconsistent suppression is rejected',()=>{
 const r=run();r.runtime.pipeline='silero';r.runtime.vad={manifestSha256:hash,fileSha256:hash,threshold:.5,minSpeechMs:64,minSilenceMs:960,frameSamples:512,contextSamples:64,loadMs:5};
 for(const m of r.models)Object.assign(m.results[0],{speechAdmitted:true,asrSkipped:false,vadComputeMs:10,asrComputeMs:90});
 const judge=()=>scoreAsrComparison(suite,models,r,{suiteSha256:hash,modelsSha256:hash,vadSha256:hash,vadModelSha256:hash});assert.equal(judge().passed,true);
 r.models[0].results[0].asrSkipped=true;assert.equal(judge().passed,false);
});
test('missing or raw pipeline cannot hide VAD suppression evidence',()=>{
 for(const mutate of [r=>r.runtime.vad={fileSha256:hash},r=>{r.runtime.pipeline='raw';r.models[0].results[0].asrSkipped=true;},r=>r.models[0].results[0].vadComputeMs=0]){const r=run();mutate(r);assert.equal(score(r).passed,false);}
});
