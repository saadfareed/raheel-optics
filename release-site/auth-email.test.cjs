const {test}=require('node:test');
const assert=require('node:assert/strict');
const vm=require('node:vm');
const fs=require('node:fs');
const source=fs.readFileSync(__dirname+'/auth-email.js','utf8');
function setup(fragment,responses){
 const button={},password={value:'new-password-123'},confirmation={value:'new-password-123'},message={},title={},fields={};
 let submit, calls=[];
 const form={elements:{password,confirmation},querySelector:()=>button,querySelectorAll:()=>[password,confirmation],addEventListener:(_,callback)=>submit=callback,reset:()=>{password.value='';confirmation.value='';}};
 const context={URLSearchParams,AbortSignal,location:{hash:fragment,pathname:'/auth-email.html'},history:{replaceState(){}},document:{querySelector:selector=>({'#action':form,'#message':message,'#title':title,'#password-fields':fields})[selector]},window:{DAYMARK:{supabaseUrl:'https://backend.example',publicKey:'public'}},fetch:async(url,options)=>{calls.push({url,options});const response=responses.shift();if(response instanceof Error)throw response;return response;}};
 vm.runInNewContext(source,context);
 return {form,message,button,calls,submit:()=>submit({preventDefault(){}})};
}
test('recovery does not consume token on load; verifies then changes password',async()=>{
 const app=setup('#token_hash=hash&type=recovery',[{ok:true,json:async()=>({access_token:'session'})},{ok:true,json:async()=>({})}]);
 assert.equal(app.calls.length,0);
 await app.submit();
 assert.equal(app.calls.length,3);
 assert.equal(app.calls[0].url,'https://backend.example/auth/v1/verify');
 assert.equal(app.calls[1].options.method,'PUT');
 assert.equal(app.calls[1].options.headers.Authorization,'Bearer session');
 assert.equal(app.calls[2].url,'https://backend.example/auth/v1/logout?scope=local');
 assert.equal(app.calls[2].options.headers.Authorization,'Bearer session');
 assert.equal(app.form.hidden,true);
 assert.match(app.message.textContent,/updated/);
});
test('password mismatch does not send network request',async()=>{
 const app=setup('#token_hash=hash&type=recovery',[]);
 app.form.elements.confirmation.value='different';
 await app.submit();assert.equal(app.calls.length,0);assert.match(app.message.textContent,/same password/);
});
test('expired link cannot change a password',async()=>{
 const app=setup('#token_hash=hash&type=recovery',[{ok:false}]);
 await app.submit();assert.equal(app.calls.length,1);assert.match(app.message.textContent,/expired/);assert.equal(app.button.disabled,false);
});
test('password update retry retains verified session in memory',async()=>{
 const app=setup('#token_hash=hash&type=recovery',[{ok:true,json:async()=>({access_token:'session'})},{ok:false},{ok:true,json:async()=>({})}]);
 await app.submit();await app.submit();
 assert.equal(app.calls.filter(call=>call.url.endsWith('/verify')).length,1);
 assert.equal(app.form.hidden,true);
});
test('confirmation verifies once and asks user to return to app',async()=>{
 const app=setup('#token_hash=hash&type=signup',[{ok:true,json:async()=>({access_token:'session'})}]);
 await app.submit();assert.equal(app.calls.length,2);assert.match(app.message.textContent,/Confirmed/);
 assert.match(app.calls[1].url,/scope=local/);
});
test('signout network failure cannot undo a completed password update',async()=>{
 const app=setup('#token_hash=hash&type=recovery',[
  {ok:true,json:async()=>({access_token:'session'})},
  {ok:true,json:async()=>({})},new Error('network unavailable')
 ]);
 await app.submit();assert.equal(app.form.hidden,true);assert.match(app.message.textContent,/updated/);
});
test('invalid link hides submission',()=>{
 const app=setup('#type=unknown',[]);assert.equal(app.form.hidden,true);assert.equal(app.calls.length,0);
});
