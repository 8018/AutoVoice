import test from 'node:test';
import assert from 'node:assert/strict';
import {judgeRoute} from '../navigation-route-judge.mjs';
const gps={latitude:39.9,longitude:116.3,coordinateSystem:'WGS84'};
const gcj={latitude:39.901,longitude:116.306,coordinateSystem:'GCJ02'};
const fixed={latitude:39.8,longitude:116.4,coordinateSystem:'GCJ02'};
const input={origin:gps,destination:fixed,waypoints:[gps,fixed]};
const good={status:'OK',source:'AMAP_WEB',origin:gcj,destination:fixed,waypoints:[gcj,fixed],routes:[{id:'r',distanceMeters:100,durationSeconds:60}]};
test('mixed points require actual conversion and preserve existing GCJ coordinates and order',()=>{
 assert.equal(judgeRoute(input,good).passed,true);
 for(const bad of [{...good,origin:{...gps,coordinateSystem:'GCJ02'}},{...good,waypoints:[fixed,gcj]},{...good,waypoints:[gcj]},{...good,destination:gcj}])assert.equal(judgeRoute(input,bad).passed,false);
});
test('missing origin is a refusal, never a fake successful route',()=>{
 assert.equal(judgeRoute({...input,origin:null},{status:'ORIGIN_REQUIRED',routes:[]}).passed,true);
 assert.equal(judgeRoute({...input,origin:null},good).passed,false);
});
