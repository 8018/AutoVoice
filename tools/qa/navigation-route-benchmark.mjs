// Public coordinates only; read-only official conversion and route API coverage.
import fs from 'node:fs';
import {execFileSync} from 'node:child_process';
import {connect,readBenchmarkToken} from './navigation-cloud-client.mjs';
import {judgeRoute} from './navigation-route-judge.mjs';
const revision=execFileSync('git',['rev-parse','HEAD'],{cwd:new URL('../../',import.meta.url),encoding:'utf8'}).trim();
const release=process.argv.find(a=>a.startsWith('--release='))?.slice(10)??'unverified';
const gps={latitude:39.907291,longitude:116.391317,coordinateSystem:'WGS84'};
const fixed={latitude:39.865246,longitude:116.378517,coordinateSystem:'GCJ02'};
const waypointGps={latitude:39.894,longitude:116.315,coordinateSystem:'WGS84'};
const cases=[
 {id:'D02-route-mixed',input:{origin:gps,destination:fixed,waypoints:[waypointGps],preference:'DEFAULT'}},
 {id:'D02-route-mixed-order',input:{origin:gps,destination:waypointGps,waypoints:[fixed,{latitude:39.902753,longitude:116.410886,coordinateSystem:'GCJ02'}],preference:'AVOID_HIGHWAYS'}},
 {id:'I02-route-missing-origin',input:{origin:null,destination:fixed,waypoints:[],preference:'DEFAULT'}},
];
const directory=new URL('../../docs/qa/navigation-route-runs/',import.meta.url);fs.mkdirSync(directory,{recursive:true});
const output=new URL(`${new Date().toISOString().replaceAll(':','-')}-${revision.slice(0,7)}.json`,directory);
const report={suite:'navigation-route-v1',revision,deployedRelease:release,readOnly:true,measurement:'client send-to-result including network, official coordinate conversion and route service',results:[]};
let client;
try{
 client=await connect({token:readBenchmarkToken(),endpoint:process.env.NAVIGATION_BENCHMARK_ENDPOINT||'ws://8.153.153.77:8090/ws'});
 for(const test of cases){
  try{const actual=await client.route(test.input);const judged=judgeRoute(test.input,actual.payload);
   const row={...test,...actual,...judged};if(actual.elapsedMs>10000){row.passed=false;row.failures.push('路线请求超过10秒门槛');}
   report.results.push(row);console.log(JSON.stringify({id:test.id,elapsedMs:row.elapsedMs,passed:row.passed,failures:row.failures}));
  }catch{report.results.push({...test,passed:false,failures:['request_failed_or_timeout']});}
 }
}catch{report.error='connection_failed';}finally{client?.close();}
report.passed=report.results.length===cases.length&&report.results.every(r=>r.passed);
fs.writeFileSync(output,JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify({output:output.pathname,passed:report.passed}));
if(!report.passed)process.exitCode=1;
