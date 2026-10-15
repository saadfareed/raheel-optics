'use strict';
const form=document.querySelector('#deletion'), message=document.querySelector('#message');
form.addEventListener('submit',async event=>{
  event.preventDefault(); const button=form.querySelector('button'),config=window.DAYMARK;
  if(!config.supabaseUrl?.startsWith('https://') || !config.publicKey){message.textContent='This page is not configured yet.';return;}
  button.disabled=true; message.textContent='Confirming account…';
  const email=form.elements.email.value.trim(),password=form.elements.password.value;
  let temporarySession,deleted=false;
  form.elements.password.value='';
  try {
    const response=await fetch(config.supabaseUrl+'/auth/v1/token?grant_type=password',{method:'POST',headers:{apikey:config.publicKey,'Content-Type':'application/json'},body:JSON.stringify({email,password}),cache:'no-store',credentials:'omit',signal:AbortSignal.timeout(20000)});
    if(!response.ok) throw new Error('Sign-in failed. Check your email and password.');
    const session=await response.json(); temporarySession=session.access_token;
    const deletion=await fetch(config.supabaseUrl+'/functions/v1/delete-account',{method:'POST',headers:{apikey:config.publicKey,Authorization:'Bearer '+session.access_token,'Content-Type':'application/json'},body:JSON.stringify({current_password:password}),cache:'no-store',credentials:'omit',signal:AbortSignal.timeout(45000)});
    if(!deletion.ok) throw new Error('Deletion could not be confirmed. Please try again or contact support.');
    deleted=true; form.reset(); form.hidden=true; message.textContent='Your account and associated records were removed from the live service. Clear Daymark app storage on other devices to remove their offline copies. Provider backups follow the retention arrangements described in Privacy.';
  } catch(error){message.textContent=error.message;} finally {
    form.elements.password.value='';
    if(temporarySession && !deleted) {
      try {await fetch(config.supabaseUrl+'/auth/v1/logout?scope=local',{method:'POST',headers:{apikey:config.publicKey,Authorization:'Bearer '+temporarySession},cache:'no-store',credentials:'omit',signal:AbortSignal.timeout(5000)});}
      catch(_) { /* Best effort when offline; no session is persisted in the browser. */ }
    }
    temporarySession=undefined;button.disabled=false;
  }
});
