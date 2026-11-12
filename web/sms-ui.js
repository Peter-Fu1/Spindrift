(()=>{
 let id=null,sending=false,generation=0;
 const recoveryTimer=setInterval(()=>{if(id&&!sending&&document.visibilityState!=='hidden')status().catch(()=>{});},2000);
 window.addEventListener('pagehide',()=>clearInterval(recoveryTimer));
 api('/api/auth/sms/status').then(async value=>{if(value.completed){await finish(value.result,value.smsId);return;}if(value.smsId&&!value.expired){id=value.smsId;$('#login').showModal();$('#smsTab').click();}}).catch(()=>{});
 async function finish(result,operation){id=null;$('#smsCode').value='';$('#login').close();await loginCompleted(result);await api('/api/auth/sms/ack',{smsId:operation});}
 async function status(){
  if($('#smsPanel').hidden||!$('#login').open)return;
  const value=await api('/api/auth/sms/status');if(value.completed){await finish(value.result,value.smsId);return;}id=value.smsId||null;$('#smsVerify').hidden=!value.challenge;$('#smsLogin').textContent=value.authenticated?'继续安全保存账号':'确认登录';
  $('#smsStatus').textContent=value.expired?'请发送验证码':value.error?'当前阶段 '+({send:'发送验证码',login_confirm:'确认登录',ltoken_exchange:'交换登录凭据',cookie_exchange:'交换账号凭据',role_discovery:'读取角色/安全保存'}[value.phase]||'认证')+' 未完成：'+value.error+(Number.isInteger(value.retcode)?'（'+value.retcode+'）':''):value.sent?'官方已接受发码请求；收到后输入验证码。':'请本人完成图形验证；尚未确认发码成功。';
 }
 $('#smsTab').onclick=()=>{cancelQr();$('#qrPanel').hidden=true;$('#smsPanel').hidden=false;$('#smsTab').setAttribute('aria-pressed','true');$('#qrTab').setAttribute('aria-pressed','false');status().catch(()=>{});};
 $('#qrTab').onclick=()=>{$('#qrPanel').hidden=false;$('#smsPanel').hidden=true;$('#smsTab').setAttribute('aria-pressed','false');$('#qrTab').setAttribute('aria-pressed','true');generation++;api('/api/auth/sms/cancel',{smsId:id,trigger:'user'}).catch(()=>{});id=null;$('#smsCode').value='';};
 $('#sendSms').onclick=async()=>{
  if(sending)return;sending=true;const gen=++generation;$('#sendSms').disabled=true;
  try{const result=await api('/api/auth/sms/send',{phone:$('#phone').value.trim(),areaCode:$('#areaCode').value});
   if(gen!==generation||!$('#login').open){api('/api/auth/sms/cancel',{smsId:result.smsId,trigger:$('#login').open?'navigation':'user'}).catch(()=>{});return;}id=result.smsId;await status();
   if(result.challenge)await api('/api/auth/sms/challenge',{smsId:id});
  }catch(e){if(gen===generation)$('#smsStatus').textContent=e.message+(Number.isInteger(e.upstreamRetcode)?'（'+e.upstreamRetcode+'）':'');}
  finally{sending=false;$('#sendSms').disabled=false;}
 };
 $('#smsVerify').onclick=()=>api('/api/auth/sms/challenge',{smsId:id}).catch(e=>toast(e.message));
 $('#smsLogin').onclick=async()=>{
  if(sending||!id)return;const gen=generation;sending=true;$('#smsLogin').disabled=true;
  try{const result=await api('/api/auth/sms/login',{smsId:id,code:$('#smsCode').value});
   if(gen!==generation||!$('#login').open)return;await finish(result,id);
  }catch(e){if(gen===generation){$('#smsStatus').textContent='登录/换票/角色读取未完成：'+e.message+(Number.isInteger(e.upstreamRetcode)?'（'+e.upstreamRetcode+'）':'');await status().catch(()=>{});}}
  finally{sending=false;$('#smsLogin').disabled=false;}
 };
 window.addEventListener('hoyo-sms-update',()=>status().catch(()=>{}));
 $('#login').addEventListener('close',()=>{generation++;$('#smsCode').value='';$('#phone').value='';if(id)api('/api/auth/sms/cancel',{smsId:id,trigger:'user'}).catch(()=>{});id=null;});
})();
