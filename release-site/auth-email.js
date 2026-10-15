'use strict';
const params=new URLSearchParams(location.hash.slice(1)), tokenHash=params.get('token_hash'),type=params.get('type');
history.replaceState(null,'',location.pathname);
const form=document.querySelector('#action'),message=document.querySelector('#message'),button=form.querySelector('button');
let accessToken=null;
const recovery=type==='recovery';
const allowed=['signup','recovery','magiclink','invite','email_change','email'];
if(!tokenHash || !allowed.includes(type)){form.hidden=true;message.textContent='This link is invalid. Request a new email from Daymark.';}
if(recovery){
 document.querySelector('#title').textContent='Reset your Daymark password';
 document.querySelector('#password-fields').hidden=false;
 form.querySelectorAll('input').forEach(input=>input.required=true);
 button.textContent='Save new password';
 message.textContent='Choose a password with at least 12 characters.';
}
async function request(path,body,token){
 const config=window.DAYMARK;
 if(!config?.supabaseUrl?.startsWith('https://') || !config.publicKey) throw new Error('This page is not configured yet.');
 const response=await fetch(config.supabaseUrl+'/auth/v1/'+path,{method:token?'PUT':'POST',headers:{apikey:config.publicKey,'Content-Type':'application/json',...(token?{Authorization:'Bearer '+token}:{})},body:JSON.stringify(body),cache:'no-store',credentials:'omit',referrerPolicy:'no-referrer',signal:AbortSignal.timeout(20000)});
 if(!response.ok) throw new Error(token?'Password could not be updated. Try again.':'This link has expired or was already used. Request a new email from Daymark.');
 return response.json();
}
async function closeProofSession(token){
 if(!token)return;
 try{
  const config=window.DAYMARK;
  await fetch(config.supabaseUrl+'/auth/v1/logout?scope=local',{
   method:'POST',headers:{apikey:config.publicKey,Authorization:'Bearer '+token},
   cache:'no-store',credentials:'omit',referrerPolicy:'no-referrer',signal:AbortSignal.timeout(20000)
  });
 }catch(_error){/* The proof token is discarded even when the provider cannot be reached. */}
}
// Verify only on user submission, so link scanners cannot consume a one-time token.
form.addEventListener('submit',async event=>{
 event.preventDefault();button.disabled=true;
 try {
  if(recovery && (form.elements.password.value.length<12 || form.elements.password.value!==form.elements.confirmation.value)) throw new Error('Use at least 12 characters and enter the same password twice.');
  if(!accessToken){const session=await request('verify',{token_hash:tokenHash,type});accessToken=session.access_token;}
  if(recovery){if(!accessToken)throw new Error('Request a new password reset email.');await request('user',{password:form.elements.password.value},accessToken);}
  const proofToken=accessToken;accessToken=null;
  await closeProofSession(proofToken);
  form.reset();form.hidden=true;
  message.textContent=recovery?'Your password has been updated. Return to Daymark and sign in.':'Confirmed. Return to Daymark to sign in.';
 }catch(error){message.textContent=error.message;}finally{button.disabled=false;}
});
