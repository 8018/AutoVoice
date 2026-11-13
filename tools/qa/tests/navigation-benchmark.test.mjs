import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { evaluate, summarize } from '../navigation-benchmark.mjs';
const original=JSON.parse(fs.readFileSync(new URL('../../../docs/qa/navigation-cloud-text-results.json',import.meta.url)));
test('frozen live failures must remain red; safe boundaries count separately',()=>{
 const rows=original.results.map((r,i)=>({...r,...evaluate(r,original.results.slice(0,i))}));
 const s=summarize(rows,23);assert.equal(s.fail,4);assert.equal(s.boundary,4);assert.equal(s.pass,15);assert.equal(s.complete,true);assert.equal(s.passed,false);
});
test('matching words without coordinates are not valid search results',()=>{
 const r={id:'numeric_city-1',type:'reply',text:'三亚凤凰国际机场',intent:{intent:'choose_destination',slots:{candidates:{value:JSON.stringify([{poiname:'三亚凤凰国际机场'}])}}}};
 assert.equal(evaluate(r,[]).verdict,'fail');
});
test('readonly selection cannot silently become navigate',()=>{
 const r=structuredClone(original.results.find(x=>x.id==='readonly-2'));r.intent.intent='navigate';assert.equal(evaluate(r,original.results).verdict,'fail');
});
test('new IDs do not make duplicate places a new batch',()=>{
 const first=original.results.find(x=>x.id==='next_batch-1');const r=structuredClone(first);r.id='next_batch-2';assert.equal(evaluate(r,[first]).verdict,'fail');
});
test('missing requests, errors and slow runs fail the gate',()=>{
 assert.equal(summarize([{verdict:'pass',elapsedMs:30}],23).passed,false);
 assert.equal(summarize([{verdict:'pass',elapsedMs:6000}],1).passed,false);
 assert.equal(evaluate({id:'name-1',error:'timeout'},[]).verdict,'fail');
});
