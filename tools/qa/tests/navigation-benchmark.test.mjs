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
test('expanded terminal expression uses the same exact terminal contract',()=>{
 const r={id:'entrance_alias-1',type:'reply',text:'找到机场',intent:{intent:'choose_destination',slots:{selectionId:{value:'s'},candidates:{value:JSON.stringify([{poiname:'上海虹桥机场',lat:31.19,lon:121.33}])}}}};
 assert.equal(evaluate(r,[]).verdict,'fail');
 r.intent.slots.candidates.value=JSON.stringify([{poiname:'上海虹桥国际机场2号航站楼',lat:31.19,lon:121.33}]);
 assert.equal(evaluate(r,[]).verdict,'pass');
});
test('suite expansion preserves every original input and ordering',async()=>{
 const {scenarios}=await import('../navigation-scenarios.mjs');
 for(const old of original.results){const scenario=scenarios.find(s=>s[0]===old.scenario);assert.ok(scenario,old.id);const turn=Number(old.id.split('-').at(-1))-1;assert.equal(scenario[2][turn],old.input,old.id);}
});

test('exact business cases reject unrelated fillers and retain generic categories',()=>{
 const row=(id,names)=>({id,type:'reply',text:'请选择',intent:{intent:'choose_destination',slots:{selectionId:{value:'s'},candidates:{value:JSON.stringify(names.map((poiname,i)=>({candidateId:`c${i}`,poiname,lat:id.includes('museum')||id.includes('airport')?31.2:39.87,lon:id.includes('museum')||id.includes('airport')?121.4:116.38})))}}}});
 assert.equal(evaluate(row('exact_station-1',['北京南站'])).verdict,'pass');
 for(const extra of ['北海南站(公交站)','北京南站(地铁站)','北京南站停车场']) assert.equal(evaluate(row('exact_station-1',['北京南站',extra])).verdict,'fail');
 assert.equal(evaluate(row('exact_museum-1',['上海博物馆(人民广场馆)','上海博物馆(东馆)'])).verdict,'pass');
 assert.equal(evaluate(row('exact_museum-1',['上海博物馆','上海自然博物馆'])).verdict,'fail');
 assert.equal(evaluate(row('generic_museum-1',['上海自然博物馆'])).verdict,'pass');
 assert.equal(evaluate(row('airport_short-1',['上海虹桥国际机场'])).verdict,'pass');
 assert.equal(evaluate(row('airport_short-1',['上海虹桥国际机场2号航站楼'])).verdict,'fail');
 const first=row('exact_next-1',['北京南站']);
 assert.equal(evaluate({...row('exact_next-2',['北京南站'])},[first]).verdict,'fail');
 assert.equal(evaluate({id:'exact_next-2',type:'reply',text:'没有更多符合条件的地点'},[first]).verdict,'pass');
 assert.equal(evaluate({id:'exact_next-2',type:'reply',text:'服务暂时不可用'},[first]).verdict,'fail');
});

test('versioned business inventory matches the executable suite and preserves v2 additions',async()=>{
 const {scenarios,introducedVersion}=await import('../navigation-scenarios.mjs');
 const {productCoverage}=await import('../navigation-benchmark.mjs');
 const manifest=JSON.parse(fs.readFileSync(new URL('../../../docs/qa/navigation-business-benchmark.json',import.meta.url)));
 assert.equal(manifest.cloudTextSuite.scenarios,scenarios.length);
 assert.equal(manifest.cloudTextSuite.requests,scenarios.reduce((n,[,,inputs])=>n+inputs.length,0));
 assert.equal(new Set(scenarios.map(([id])=>id)).size,scenarios.length);
 const retained=[['entrance_alias','去上海虹桥机场2号航站楼'],['airport_city','导航到三亚市凤凰国际机场'],['brand_named','找北京的星巴克咖啡店'],['reference_named','查找北京市北京南站周边的充电站']];
 for(const [id,input] of retained){assert.deepEqual(scenarios.find(s=>s[0]===id)[2],[input]);assert.equal(introducedVersion[id],2);}
 for(const [id] of scenarios){assert.ok(productCoverage[id]?.length,id);assert.ok([1,2,3].includes(introducedVersion[id]),id);}
});
