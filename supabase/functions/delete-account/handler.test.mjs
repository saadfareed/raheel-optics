import test from 'node:test';
import assert from 'node:assert/strict';
import { deletionHandler } from './handler.mjs';
const request = password => new Request('https://example.test', { method: 'POST', headers: {Authorization:'Bearer session'}, body:JSON.stringify({current_password:password, user_id:'other'}) });
function setup(responses, options = {}) {
  const calls=[];
  const handler=deletionHandler({url:'https://project.test',publicKey:'public',adminKey:'private',...options,fetcher:async(url,options)=>{
    calls.push({url,...options}); const [status,body]=responses.shift(); return new Response(JSON.stringify(body),{status});
  }}); return {handler,calls};
}
test('deletes only verified caller, never supplied target',async()=>{
  const {handler,calls}=setup([[200,{id:'owner',email:'a@b.test'}],[200,{user:{id:'owner'},access_token:'proof'}],[200,{}]]);
  assert.equal((await handler(request('password'))).status,200);
  assert.equal(calls[2].url,'https://project.test/auth/v1/admin/users/owner');
  assert.equal(calls[0].headers.apikey,'public'); assert.equal(calls[2].headers.apikey,'private');
});
test('wrong password cannot delete',async()=>{
  const {handler,calls}=setup([[200,{id:'owner',email:'a@b.test'}],[400,{}]]);
  assert.equal((await handler(request('wrong'))).status,403); assert.equal(calls.length,2);
});
test('different verified account cannot delete',async()=>{
  const {handler,calls}=setup([[200,{id:'owner',email:'a@b.test'}],[200,{user:{id:'other'},access_token:'proof'}],[200,{}]]);
  assert.equal((await handler(request('password'))).status,403); assert.equal(calls.length,3);
  assert.equal(calls[2].url,'https://project.test/auth/v1/logout?scope=local');
  assert.equal(calls[2].headers.Authorization,'Bearer proof');
});
test('invalid session cannot delete',async()=>{
  const {handler,calls}=setup([[401,{}]]); assert.equal((await handler(request('password'))).status,401); assert.equal(calls.length,1);
});
test('admin failure is never reported as success',async()=>{
  const {handler,calls}=setup([[200,{id:'owner',email:'a@b.test'}],[200,{user:{id:'owner'},access_token:'proof'}],[500,{}],[200,{}]]);
  assert.equal((await handler(request('password'))).status,503);
  assert.equal(calls.length,4);assert.equal(calls[3].url,'https://project.test/auth/v1/logout?scope=local');
  assert.equal(calls[3].headers.apikey,'public');assert.equal(calls[3].headers.Authorization,'Bearer proof');
});
test('missing password makes no network requests',async()=>{
  const {handler,calls}=setup([]); assert.equal((await handler(request(''))).status,400); assert.equal(calls.length,0);
});
test('public key is not sent as a user bearer token during password verification',async()=>{
  const {handler,calls}=setup([[200,{id:'owner',email:'a@b.test'}],[400,{}]]);
  await handler(request('password')); assert.equal(calls[1].headers.Authorization,undefined);
});
test('oversized streamed UTF-8 body is cancelled before authentication',async()=>{
  const {handler,calls}=setup([]); let cancelled=false;
  const stream=new ReadableStream({pull(controller) {controller.enqueue(new TextEncoder().encode('界'.repeat(3000)));},cancel() {cancelled=true;}});
  const req=new Request('https://example.test',{method:'POST',headers:{Authorization:'Bearer session'},body:stream,duplex:'half'});
  assert.equal((await handler(req)).status,413); assert.equal(calls.length,0); assert.equal(cancelled,true);
});
test('malformed JSON is a client error without authentication requests',async()=>{
  const {handler,calls}=setup([]);
  const req=new Request('https://example.test',{method:'POST',headers:{Authorization:'Bearer session'},body:'{'});
  assert.equal((await handler(req)).status,400);assert.equal(calls.length,0);
});
test('declared oversized body is rejected before reading',async()=>{
  const {handler,calls}=setup([]);
  const req=new Request('https://example.test',{method:'POST',headers:{Authorization:'Bearer session','Content-Length':'9000'},body:'{}'});
  assert.equal((await handler(req)).status,413);assert.equal(calls.length,0);
});
test('slow body times out and cancels without authenticating',async()=>{
  const {handler,calls}=setup([],{bodyTimeoutMs:10});let cancelled=false;
  const stream=new ReadableStream({start() {},cancel() {cancelled=true;}});
  const req=new Request('https://example.test',{method:'POST',headers:{Authorization:'Bearer session'},body:stream,duplex:'half'});
  assert.equal((await handler(req)).status,408);assert.equal(calls.length,0);assert.equal(cancelled,true);
});

test('proof cleanup failure does not conceal deletion failure',async()=>{
  const {handler,calls}=setup([[200,{id:'owner',email:'a@b.test'}],[200,{user:{id:'owner'},access_token:'proof'}],[500,{}]]);
  assert.equal((await handler(request('password'))).status,503);
  assert.equal(calls.length,4);assert.equal(calls[3].url,'https://project.test/auth/v1/logout?scope=local');
});
