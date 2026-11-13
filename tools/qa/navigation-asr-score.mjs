import fs from 'node:fs';
import path from 'node:path';
import {createHash} from 'node:crypto';
import {execFileSync} from 'node:child_process';
import {scoreAsrComparison} from './navigation-asr-judge.mjs';
const [directory,resultFile,outputFile]=process.argv.slice(2);
if(!directory||!resultFile||!outputFile)throw Error('Usage: node tools/qa/navigation-asr-score.mjs INPUT_DIR RAW_RESULT OUTPUT_JSON');
const read=name=>fs.readFileSync(path.join(directory,name));
const suiteBytes=read('suite.json'),modelBytes=read('models.json'),raw=JSON.parse(fs.readFileSync(resultFile,'utf8'));
const hash=bytes=>createHash('sha256').update(bytes).digest('hex');
// Approved v1 manifests come from the repository, never from the provisioned inputs.
const approvedSuiteBytes=fs.readFileSync(new URL('../../docs/qa/navigation-asr-fixtures-v1.json',import.meta.url));
const approvedModelBytes=fs.readFileSync(new URL('../../docs/qa/navigation-asr-models-v1.json',import.meta.url));
const suite=JSON.parse(approvedSuiteBytes),models=JSON.parse(approvedModelBytes);
const approvedVadBytes=fs.readFileSync(new URL('../../docs/qa/navigation-asr-vad-v1.json',import.meta.url));
const approvedVad=JSON.parse(approvedVadBytes);
const sourceFingerprint=createHash('sha256');
for(const file of ['navigation-asr-score.mjs','navigation-asr-judge.mjs'])sourceFingerprint.update(fs.readFileSync(new URL(file,import.meta.url)));
const report={createdAt:new Date().toISOString(),scoringSourceRevision:execFileSync('git',['rev-parse','HEAD'],{encoding:'utf8'}).trim(),scoringSourceDirty:execFileSync('git',['status','--porcelain'],{encoding:'utf8'}).trim().length>0,scoringSourceFingerprint:sourceFingerprint.digest('hex'),suiteSha256:hash(suiteBytes),modelsSha256:hash(modelBytes),executedBuild:raw.runtime,rawResult:raw,rawResultSha256:hash(fs.readFileSync(resultFile)),device:raw.device,runtime:raw.runtime,...scoreAsrComparison(suite,models,raw,{suiteSha256:hash(approvedSuiteBytes),modelsSha256:hash(approvedModelBytes),vadSha256:hash(approvedVadBytes),vadModelSha256:approvedVad.asset.sha256})};
if(hash(suiteBytes)!==hash(approvedSuiteBytes)||hash(modelBytes)!==hash(approvedModelBytes)){report.passed=false;report.failures.push('supplied_manifests_differ_from_approved_v1');}
if(raw.runtime?.pipeline==='silero'&&(!fs.existsSync(path.join(directory,'vad.json'))||hash(read('vad.json'))!==hash(approvedVadBytes))){report.passed=false;report.failures.push('supplied_vad_manifest_differs_from_approved_v1');}
fs.mkdirSync(path.dirname(outputFile),{recursive:true});
fs.writeFileSync(outputFile,JSON.stringify(report,null,2)+'\n',{flag:'wx'});
console.log(JSON.stringify({outputFile,passed:report.passed,byModel:Object.fromEntries(Object.entries(report.byModel).map(([id,m])=>[id,{total:m.total,passed:m.passed,exactRate:m.exactRate,decodeP50Ms:m.decodeP50Ms,decodeP95Ms:m.decodeP95Ms}]))}));
if(!report.passed)process.exitCode=1;
