const {test}=require('node:test');
const assert=require('node:assert/strict');
const vm=require('node:vm');
const fs=require('node:fs');
const source=fs.readFileSync(__dirname+'/deletion.js','utf8');
function setup(responses){
 const button={},password={value:'fixture-password'},email={value:'fixture@example.invalid'},message={};
 let submit;const calls=[];
 const form={elements:{password,email},querySelector:()=>button,addEventListener:(_,callback)=>submit=callback,reset:()=>{password.value='';email.value='';}};
 const context={AbortSignal,document:{querySelector:selector=>({'#deletion':form,'#message':message})[selector]},window:{DAYMARK:{supabaseUrl:'https://backend.example',publicKey:'public'}},fetch:async(url,options)=>{
  assert.equal(password.value,'');calls.push({url,options});const result=responses.shift();if(result instanceof Error)throw result;return result;
 }};
 vm.runInNewContext(source,context);
 return {form,message,button,calls,submit:()=>submit({preventDefault(){}})};
}
test('successful deletion needs no extra session cleanup',async()=>{
 const app=setup([{ok:true,json:async()=>({access_token:'temporary'})},{ok:true}]);await app.submit();
 assert.equal(app.calls.length,2);assert.equal(app.form.hidden,true);assert.equal(app.button.disabled,false);
 assert.match(app.message.textContent,/live service/);assert.match(app.message.textContent,/offline copies/);
});
test('failed deletion revokes only the temporary browser session',async()=>{
 const app=setup([{ok:true,json:async()=>({access_token:'temporary'})},{ok:false},{ok:true}]);await app.submit();
 assert.equal(app.calls.length,3);assert.equal(app.calls[2].url,'https://backend.example/auth/v1/logout?scope=local');
 assert.equal(app.calls[2].options.headers.Authorization,'Bearer temporary');assert.equal(app.form.hidden,undefined);
 assert.match(app.message.textContent,/could not be confirmed/);assert.equal(app.button.disabled,false);
});
test('failed sign-in clears password without attempting logout',async()=>{
 const app=setup([{ok:false}]);await app.submit();assert.equal(app.calls.length,1);assert.equal(app.form.elements.password.value,'');
 assert.match(app.message.textContent,/Sign-in failed/);assert.equal(app.button.disabled,false);
});
test('cleanup network failure preserves the actionable deletion error',async()=>{
 const app=setup([{ok:true,json:async()=>({access_token:'temporary'})},{ok:false},new Error('Offline')]);await app.submit();
 assert.match(app.message.textContent,/could not be confirmed/);assert.equal(app.button.disabled,false);assert.equal(app.form.elements.password.value,'');
});
