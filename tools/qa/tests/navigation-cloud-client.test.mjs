import test from 'node:test';
import assert from 'node:assert/strict';
import {connect} from '../navigation-cloud-client.mjs';

class FakeSocket {
 static instances=[];
 constructor(){this.sent=[];FakeSocket.instances.push(this);queueMicrotask(()=>this.onopen());}
 emit(type,payload){queueMicrotask(()=>this.onmessage({data:JSON.stringify({type,payload})}));}
 send(data){
  if(Buffer.isBuffer(data)){this.sent.push(Buffer.from(data));return;}
  const m=JSON.parse(data);this.sent.push(m);const p=m.payload;
  switch(m.type){
   case 'hello':this.emit('ready',{sessionId:'session',language:'zh-CN'});break;
   case 'tts_request':this.emit('tts_response',{segmentId:p.segmentId,text:p.text,mime:'audio/wav',dataBase64:FakeSocket.ttsBase64??Buffer.from('fixture').toString('base64')});break;
   case 'audio_start':this.turn=p;break;
   case 'audio_end':this.emit('reply',{segmentId:this.turn.segmentId,asrText:'找北京的博物馆',speakText:'第1个，博物馆',intent:{domain:'navigation',intent:'choose_destination',slots:{selectionId:{value:'selection'}}}});break;
   case 'text_request':this.emit('reply',{requestId:p.requestId,text:'text reply'});break;
   case 'navigation_route_preview':this.emit('navigation_route_preview_result',{operationId:p.operationId,status:'ok'});break;
  }
 }
 close(){}
}

test('voice frames use valid session, 640-byte PCM chunks, IDs and selection adoption; text/route remain usable',async()=>{
 const original=globalThis.WebSocket;globalThis.WebSocket=FakeSocket;let client;
 try{
  client=await connect({token:'local-test-token',endpoint:'ws://unused'});
  const fixture=await client.tts('公开测试');assert.equal(fixture.audio.toString(),'fixture');assert.equal(fixture.text,'公开测试');
  const result=await client.voice(Buffer.alloc(1280));
  assert.equal(result.asrText,'找北京的博物馆');assert.equal(result.text,'第1个，博物馆');assert.equal(result.audioDurationMs,40);
  const socket=FakeSocket.instances.at(-1),start=socket.sent.find(m=>m.type==='audio_start'),end=socket.sent.find(m=>m.type==='audio_end');
  assert.equal(start.payload.sessionId,'session');assert.equal(start.payload.sampleRate,16000);assert.equal(start.payload.segmentId,start.payload.utteranceId);
  assert.deepEqual(socket.sent.filter(Buffer.isBuffer).map(b=>b.length),[640,640]);assert.equal(end.payload.durationMs,40);
  assert.equal(socket.sent.find(m=>m.type==='navigation_selection_start').payload.selectionId,'selection');
  assert.equal((await client.text('文本')).text,'text reply');assert.equal((await client.route({})).payload.status,'ok');
 }finally{client?.close();globalThis.WebSocket=original;}
});
test('voice rejects empty/odd PCM and permits disabling selection adoption',async()=>{
 const original=globalThis.WebSocket;globalThis.WebSocket=FakeSocket;let client;
 try{
  client=await connect({token:'local-test-token',endpoint:'ws://unused'});
  await assert.rejects(client.voice(Buffer.alloc(0)),/invalid_voice_pcm/);await assert.rejects(client.voice(Buffer.alloc(3)),/invalid_voice_pcm/);
  await client.voice(Buffer.alloc(640),{adoptSelection:false});
  assert.equal(FakeSocket.instances.at(-1).sent.some(m=>m.type==='navigation_selection_start'),false);
 }finally{client?.close();globalThis.WebSocket=original;}
});

test('TTS validates large base64 without recursion and rejects misplaced padding',async()=>{
 const original=globalThis.WebSocket;globalThis.WebSocket=FakeSocket;let client;
 try{
  client=await connect({token:'local-test-token',endpoint:'ws://unused'});
  FakeSocket.ttsBase64=Buffer.alloc(2*1024*1024,42).toString('base64');
  assert.equal((await client.tts('公开测试')).audio.length,2*1024*1024);
  for(const invalid of ['YQ=','Y=Q=','YQ===','YQ==AAAA','!!!!','AAAA\n']){
   FakeSocket.ttsBase64=invalid;await assert.rejects(client.tts('公开测试'),/invalid_tts_response/);
  }
  for(const valid of ['YQ==','YWE=','YWFh']){
   FakeSocket.ttsBase64=valid;assert.equal((await client.tts('公开测试')).audio.length,Buffer.from(valid,'base64').length);
  }
 }finally{client?.close();delete FakeSocket.ttsBase64;globalThis.WebSocket=original;}
});
