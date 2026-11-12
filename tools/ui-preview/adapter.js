/* Browser UI Preview boundary. APK keeps its original platform-adapter.js. */
(()=>{
 'use strict';
 let host;
 try{host=parent.location;if(parent===window||frameElement?.id!=='uiPreview'||parent.document.documentElement.dataset.preview!=='active'||!['http:','https:'].includes(host.protocol)||!['localhost','127.0.0.1','[::1]'].includes(host.hostname)||window.HoYoNative)throw Error();}
 catch{throw Error('UI Preview 仅允许独立的本地开发入口');}
 const fixture=HoYoPreviewFixtures.create(document.documentElement.dataset.previewScenario);
 let current=fixture.state,ledger=fixture.ledger,quote=null,batch=null,autoJob=null;
 const clone=value=>structuredClone(value),blocked=()=>Promise.reject(Error('UI Preview：此操作已禁用；不会登录、申请 FP、打开官方页面或导出真实数据'));
 const ownRoles=()=>new Set(current.accounts.flatMap(a=>a.roles.map(r=>HoYoPreviewFixtures.key(a,r))));
 // Separate from localStorage: even the drawer preference remains in this frame's memory.
 const storage=new Map();
 Object.defineProperty(window,'localStorage',{value:{getItem:k=>storage.get(String(k))??null,setItem:(k,v)=>storage.set(String(k),String(v)),removeItem:k=>storage.delete(String(k)),clear:()=>storage.clear(),key:i=>[...storage.keys()][i]??null,get length(){return storage.size;}}});
 window.open=()=>null;
 document.addEventListener('click',e=>{
  if(e.target.closest?.('a[href],#newQr,#sendSms,#smsLogin,#smsVerify,#repairFingerprint,#cancelFingerprint,#completeProfile,#exportDiagnostics,#exportRefreshDiagnostics')){
   e.preventDefault();e.stopImmediatePropagation();const notice=document.querySelector('#notice');if(notice){notice.textContent='UI Preview：真实登录、FP 和官方联网操作已禁用';notice.hidden=false;}
  }
 },true);
 document.addEventListener('submit',e=>{if(e.target.method!=='dialog'){e.preventDefault();e.stopImmediatePropagation();}},true);
 async function request(path,body,method){
  method=method||(body===undefined?'GET':'POST');
  // Exact allowlist; everything else (especially auth, FP, profile, verification and exports) fails closed.
  if(path==='/api/session'&&method==='GET')return {csrfToken:'preview-memory-only',progressiveRefresh:false};
  if(path==='/api/accounts'&&method==='GET')return clone(current);
  if(path==='/api/checkin/auto/status'&&method==='GET')return {job:clone(autoJob),ledger:clone(ledger)};
  if(path==='/api/checkin/auto/start'&&method==='POST'){const enabled=Object.values(ledger.roles).filter(r=>r.enabled);autoJob={operationId:'a'.repeat(32),trigger:body.trigger,status:'completed',total:enabled.length,done:enabled.length,counts:{already_signed:enabled.length},synthetic:true};return {job:clone(autoJob),ledger:clone(ledger)};}
  if(path==='/api/checkin'&&method==='GET')return clone(ledger);
  if(path==='/api/auth/sms/status'&&method==='GET')return {expired:true};
  if(['/api/avatar/render','/api/auth/sms/cancel','/api/auth/diagnostic','/api/accounts/verification/cancel'].includes(path)&&method==='POST')return {ok:true};
  if(path==='/api/ui/preferences'&&method==='POST'){current.uiPreferences=clone(body);return clone(current);}
  if(path==='/api/accounts/select'&&method==='POST'&&current.accounts.some(a=>a.id===body?.accountId)){current.selectedId=body.accountId;return clone(current);}
  if(path==='/api/accounts/remove'&&method==='POST'){current.accounts=current.accounts.filter(a=>a.id!==body?.accountId);if(!current.accounts.some(a=>a.id===current.selectedId))current.selectedId=current.accounts[0]?.id||null;return clone(current);}
  if(path==='/api/resources/estimate'&&method==='POST')return {ok:true};
  if(path==='/api/accounts/refresh/start'&&method==='POST'){
   const keys=(body?.roleKeys||[]).filter(k=>ownRoles().has(k));
   current.refresh={refreshId:body.refreshId,trigger:'manual',status:'completed',elapsedMs:16,roles:keys.map(k=>{const [accountId,role]=k.split('|'),[game,roleUid,server]=role.split(':');const g=current.accounts.find(a=>a.id===accountId).games.find(x=>x.id===game&&x.roleUid===roleUid);return {accountId,game,roleUid,server,state:g.status==='ok'?'success':'failed',retcode:g.retcode,error:g.status==='ok'?null:g.status};})};return clone(current);
  }
  if(path==='/api/accounts/refresh/status'&&method==='GET')return clone(current);
  if(['/api/accounts/refresh/action','/api/accounts/refresh/rendered'].includes(path)&&method==='POST')return {ok:true};
  if(path==='/api/checkin/batch/status'&&method==='GET')return {job:clone(batch),ledger:clone(ledger)};
  if(path==='/api/checkin/batch/cancel'&&method==='POST'){if(batch&&batch.operationId===body?.operationId)batch.status='cancelled';return {job:clone(batch),ledger:clone(ledger)};}
  if(path==='/api/checkin/batch/start'&&method==='POST'){
   const keys=(body?.roleKeys||[]).filter(k=>ownRoles().has(k));await request('/api/checkin/run',{...body,roleKeys:keys});
   batch={operationId:'preview-memory-batch',status:'completed',operation:body.operation,total:keys.length,done:keys.length,roles:keys.map(k=>({roleKey:k,state:ledger.roles[k+'|CN'].state}))};if(body.operation==='info'){await request('/api/checkin/auto/start',{trigger:'checkin_status_manual_refresh'});autoJob.operationId=batch.operationId;}return {job:clone(batch),autoJob:clone(autoJob),ledger:clone(ledger)};
  }
  if(path==='/api/accounts/profile/batch/status'&&method==='GET')return {job:null,state:clone(current)};
  if(path==='/api/checkin/preferences'&&method==='POST'){for(const k of body.roleKeys||[])if(ownRoles().has(k))ledger.roles[k+'|CN'].enabled=body.enabled===true;ledger.configurationRevision=(ledger.configurationRevision||0)+1;return clone(ledger);}
  if(path==='/api/checkin/run'&&method==='POST'&&['info','sign'].includes(body?.operation)){
   for(const k of body.roleKeys||[])if(ownRoles().has(k)){const row=ledger.roles[k+'|CN'];row.checkedAt=Date.now();if(body.operation==='sign'&&row.state==='not_signed'){row.state='signed';row.info.isSign=true;row.info.total++;}}
   return clone(ledger);
  }
  if(path==='/api/checkin/resign/prepare'&&method==='POST'&&ownRoles().has(body?.roleKey)){quote={eligible:true,currency:'miyou_coin',currencyVerified:true,cost:150,balance:500,confirmationId:'preview-synthetic-quote',expiresAt:Date.now()+60000,roleKey:body.roleKey};return clone(quote);}
  if(path==='/api/checkin/resign/cancel'&&method==='POST'){quote=null;return {ok:true};}
  if(path==='/api/checkin/resign/confirm'&&method==='POST'&&quote&&body?.confirmationId===quote.confirmationId&&body.roleKey===quote.roleKey&&body.confirmed===true&&Date.now()<quote.expiresAt){ledger.roles[quote.roleKey+'|CN'].resignState='confirmed';quote=null;return clone(ledger);}
  if(path==='/api/diagnostics'&&method==='GET')return {mode:'UI Preview',synthetic:true,network:'blocked',persistence:'memory only'};
  return blocked();
 }
 window.HoYoPlatform={request,imageUrl(a,r){return HoYoPreviewFixtures.avatar(r?'游':a.id.slice(-1));},rewardUrl(){return HoYoPreviewFixtures.avatar('奖','#947248');},back(){const dialogs=[...document.querySelectorAll('dialog[open]')];if(dialogs.length){dialogs.at(-1).close();return true;}if(window.HoYoCheckin?.closeDrawer())return true;const menu=document.querySelector('#accountMenu');if(menu&&!menu.hidden){menu.hidden=true;return true;}return false;}};
 window.addEventListener('DOMContentLoaded',()=>{
  const note=document.createElement('p');note.textContent='UI Preview：全部为虚拟数据；登录、设备指纹、官方验证与联网补全已禁用。';note.setAttribute('role','note');note.style.cssText='margin:0;padding:4px 10px;font-size:12px;background:#fff3d5;color:#634700';document.querySelector('header').after(note);
  for(const id of ['phone','smsCode','sendSms','smsLogin','smsVerify','newQr','repairFingerprint','cancelFingerprint','completeProfile','exportDiagnostics','exportRefreshDiagnostics']){const el=document.getElementById(id);if(el){el.disabled=true;el.title='UI Preview 中禁止真实操作';}}
  // Render functions may toggle disabled flags. The adapter still rejects all sensitive routes.
  if(fixture.scenario==='checkin')document.querySelector('[data-page="checkin"]').click();
  if(fixture.scenario==='login')document.querySelector('#login').showModal();
 });
})();
