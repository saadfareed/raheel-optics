'use strict';
const privacyConfig=window.RAHEEL_OPTICS || {},contact=document.querySelector('#contact');
if(contact && privacyConfig.developerName?.trim() && privacyConfig.supportEmail?.trim()) {
  const address=document.createElement('a');
  address.textContent=privacyConfig.supportEmail.trim();
  address.href='mailto:'+encodeURIComponent(privacyConfig.supportEmail.trim());
  contact.replaceChildren(document.createTextNode(privacyConfig.developerName.trim()+'. Support: '),address);
}
