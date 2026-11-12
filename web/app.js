const $=s=>document.querySelector(s);
const esc=v=>String(v??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
function roleNickname(a,r){return a.roleProfiles?.[r.game+':'+r.uid+':'+(r.serverId||r.server)]?.nickname||r.nickname||'';}
let state={accounts:[],selectedId:null},csrf='',busy=false,qrId=null,qrTimer=null,toastTimer;
let refreshStarting=false,localRefreshResult='';
const statusSizingObserver=typeof ResizeObserver==='undefined'?null:new ResizeObserver(entries=>{for(const {target} of entries)target.fitStatusResult?.();});
function statusLines(id,title,result,alternatives=[],description=result){
 const el=$('#'+id),key=JSON.stringify([title,result,alternatives,description]);if(el.dataset.statusKey===key){el.fitStatusResult?.();return;}el.dataset.statusKey=key;
 const heading=document.createElement('span');heading.className='status-heading';heading.textContent=title;
 const outcome=document.createElement('span');outcome.className='status-result';outcome.textContent=result;outcome.hidden=!result;
 outcome.setAttribute('title',description);outcome.setAttribute('aria-label',description);
 el.replaceChildren(...(title?[heading,outcome]:[outcome]));
 let fittedKey='';
 const sizeKey=()=>{const style=getComputedStyle(outcome);return [outcome.clientWidth,el.clientHeight,style.font,style.letterSpacing,style.wordSpacing].join('|');};
 el.fitStatusResult=()=>{
  if(!outcome.clientWidth)return;const key=sizeKey();if(key===fittedKey)return;
  for(const text of [result,...alternatives]){
   if(outcome.textContent!==text)outcome.textContent=text;
   const range=document.createRange();range.selectNodeContents(outcome);
   if(range.getClientRects().length<=1)break;
  }
  fittedKey=sizeKey();
 };
 statusSizingObserver?.observe(el);el.fitStatusResult();
}
function resourceStatus(count){
 let result=preferences.mode==='local'?'本地估算 · 将更新 '+count+' 个角色':'联网 · 本次将刷新 '+count+' 个经过筛选的角色';
 if(preferences.mode==='local'&&localRefreshResult)result=localRefreshResult;
 else if(preferences.mode==='server'&&refreshJob){
  const rows=refreshJob.roles||[],success=rows.filter(x=>x.state==='success').length,failed=rows.filter(x=>x.state==='failed').length,cancelled=rows.filter(x=>x.state==='cancelled').length,done=success+failed+cancelled;
  result='联网 · '+(refreshJob.status==='running'?'正在刷新 '+done+'/'+rows.length+' 个角色':refreshJob.status==='cancelled'?'已取消 · 成功'+success:refreshJob.status==='failed'?'刷新失败 · 成功'+success:'检查结束 · 已刷新 '+success+' 个角色')+(failed?' 失败'+failed:'')+(cancelled?' 取消'+cancelled:'');
 }
 const compact=result.replace('本次将刷新','待刷新').replace(' 个经过筛选的角色','个角色').replace(' 个筛选角色','个角色').replace(' 个角色','个角色');
 statusLines('viewStatus','',result,[compact],modeText()+' · '+result);
}

let qrGeneration=0,qrStarted=0;
let refreshJob=null,refreshTimer=null,refreshStarted=0,refreshPolling=false,verifyAccountId=null;

// The HTML hint is the editable source for both initial and pending QR states.
const QR_LOGIN_HINT = $('#qrStatus').textContent.trim() || '请使用米游社App扫码并确认登录。';
const PLATFORM_STATUS_LABEL = $('#platformStatus').textContent.trim() || 'Android 原生后端';

const ACCOUNT_RISK_TEXT =
  '实时便笺页面未能打开（错误码:5003，账号存在风险），请在米游社App检查同一角色的访问状态。';
const ACCOUNT_RESTRICTED_TEXT =
  '官方限制此请求（错误码:10041），获取普通验证码路径失败';
const OFFICIAL_VERIFICATION_UNAVAILABLE_TEXT =
  '本次调用人机验证页面失败，请导出本次诊断；可在米游社App检查同一角色战绩信息。';
const RISK_CONTROL_REQUIRED_TEXT =
  '官方限制此请求，请按页面指引操作';

const phaseLabels={preparing_device:'正在申请当前账号设备指纹',idle:'排队中',refreshing:'正在获取新数据',verification_required:'等待验证窗口',verification_opening:'正在打开官方页面',waiting_for_user:'请在官方页面继续操作',verifying_result:'正在校验便笺',saving_session:'正在保存会话',success:'刷新成功',failed:'刷新失败',cancelled:'已取消'};
const refreshErrors={verification_rejected:'本次人机验证未被通过，请查看错误代码',verification_component_failed:'官方验证组件未就绪，请导出诊断',official_verification_unavailable:OFFICIAL_VERIFICATION_UNAVAILABLE_TEXT,login_ui_not_ready:'登录窗口尚未结束或页面已断开，请关闭登录弹窗后手动刷新',account_restricted:ACCOUNT_RESTRICTED_TEXT ,account_risk:ACCOUNT_RISK_TEXT,official_verification_required:'请手动完成人机验证',risk_control_required:RISK_CONTROL_REQUIRED_TEXT,verification_timeout:'验证超时，稍后重试',session_persist_failed:'会话保存失败，未认定刷新成功',browser_crashed:'浏览器进程已退出',network_error:'网络请求失败',verification_page_unavailable:'未取得该角色的官方页面入口',verification_already_pending:'该账号已有独立查看窗口，请先完成当前操作，或关闭窗口',verification_pending:'该账号的独立窗口仍在使用中',response_invalid:'便笺数据未通过校验',cancelled:'已取消',user_cancelled:'用户取消'};
function refreshing(){return refreshJob?.status==='running';}
const afterPaint=()=>typeof window.requestAnimationFrame==='function'?new Promise(resolve=>{const timer=setTimeout(resolve,250);window.requestAnimationFrame(()=>window.requestAnimationFrame(()=>{clearTimeout(timer);resolve();}));}):Promise.resolve();
function progressFor(g,a){const row=refreshJob?.roles.find(r=>r.accountId===a.id&&r.game===g.id&&(!g.serverId||r.server===g.serverId)&&r.roleUid===String(g.roleUid||a.roles.find(x=>x.game===g.id)?.uid));if(!row)return '';return `<p class="refresh-state ${esc(row.state)}" role="status">${esc(phaseLabels[row.state]||row.state)}${Number.isInteger(row.retcode)?'（'+row.retcode+'）':''}${row.error?' · '+esc(refreshErrors[row.error]||labels[row.error]||'请检查官方页面或稍后重试'):''}</p>`;}
const expanded=new Set();
let preferences=HoYoUi.normalize(),prefsLoaded=false,prefsVersion=0,prefsQueue=Promise.resolve(),sortTarget=null;
function roleKey(a,r){return HoYoUi.roleKey(a,r);}
function targets(){return HoYoUi.targets(state.accounts,preferences);}
function orderedAccounts(){return preferences.order.map(id=>state.accounts.find(a=>a.id===id)).filter(Boolean);}
function persistPreferences(){
 preferences.expanded=[...expanded];state.uiPreferences=structuredClone(preferences);
 const value=structuredClone(preferences),version=++prefsVersion;
 prefsQueue=prefsQueue.catch(()=>{}).then(()=>api('/api/ui/preferences',value)).then(result=>{
  if(version===prefsVersion&&result.uiPreferences)state.uiPreferences=result.uiPreferences;
 }).catch(e=>toast('显示设置未保存：'+e.message));
 return prefsQueue;
}
function modeText(){return preferences.mode==='local'?'本地估算刷新 · 计时器':'联网刷新 · 米游社服务器';}
function imageMarkup(a,r){
 const source=r?a.roleProfiles?.[r.game+':'+r.uid+':'+(r.serverId||r.server)]:{avatar:a.avatar};
 if(!source?.avatar){const label=!r&&a.avatarState==='not_set'?'未设置通行证头像':r?(source?.state==='not_provided'?'接口未提供角色头像':source?.state==='unavailable'?'角色头像地址不可用':'尚未获取角色头像'):'尚未获取通行证头像';return '<span class="avatar-placeholder" role="img" aria-label="'+label+'" title="'+label+'">◉</span>';}
 const url=window.HoYoPlatform.imageUrl(a,r);
 return '<img class="avatar" data-avatar-owner="'+esc(a.id)+'" data-avatar-role="'+esc(r?r.game+':'+r.uid+':'+(r.serverId||r.server):'')+'" src="'+esc(url)+'" alt="头像未缓存" referrerpolicy="no-referrer">';
}

const names={genshin:'原神',starrail:'崩坏：星穹铁道',zenless:'绝区零'};
const labels={device_fp_unavailable:'登录状态已保存，官方设备指纹申请未完成；请在设置中查看并重试',account_restricted:ACCOUNT_RESTRICTED_TEXT,official_verification_unavailable:OFFICIAL_VERIFICATION_UNAVAILABLE_TEXT,account_risk:ACCOUNT_RISK_TEXT,verification_pending:'验证窗口仍在使用当前账号，请关闭后刷新',request_invalid:'请求未被接受，请检查版本、签名及请求参数兼容性',risk_control_required:RISK_CONTROL_REQUIRED_TEXT,auth_expired:'登录已失效，请重新登录',network_error:'网络请求失败',notes_not_enabled:'请先在米游社开启实时便笺',notes_access_denied:'无法读取实时便笺',upstream_rate_limited:'请求过于频繁，请稍后刷新',unavailable:'暂时无法获取数据',role_mapping_error:'角色区服映射异常'};
async function api(url,body,method){return window.HoYoPlatform.request(url,body,method);}
function toast(message){$('#notice').textContent=message;$('#notice').hidden=false;clearTimeout(toastTimer);toastTimer=setTimeout(()=>$('#notice').hidden=true,5500);}
function avatar(a){return a?imageMarkup(a):'<span aria-hidden="true">◉</span>';}
function pair(c,m){return c==null||m==null?'—':`${c} / ${m}`;}
function task(label,c,m,remaining=false){return `<div class="task"><span>${label}</span><b class="${c==null?'':(remaining?c===0:c>=m)?'done':'pending'}">${pair(c,m)}</b></div>`;}
function card(g,a){
 const role=a.roles.find(r=>r.game===g.id&&(!g.roleUid||String(r.uid)===String(g.roleUid))&&(!g.serverId||r.serverId===g.serverId))||{};
 const valid=g.status==='ok',now=Date.now(),presentation=HoYoResource.presentation(g,now),{estimate,value}=presentation;
 const time=g.lastSuccessfulFetchAt||g.updated;
 const style=/^#[0-9a-f]{6}$/i.test(g.accent)?g.accent:'#1976dc';
 const footer=valid?'<p class="muted" data-resource-source title="'+esc(new Date(time).toLocaleString('zh-CN'))+'">'+
  (g.source==='miyoushe_webview'&&estimate.estimatedValue==null?'米游社页面采集 · ':'')+(g.cached&&estimate.estimatedValue==null?'本地缓存 · ':'')+(estimate.estimatedValue!=null?'本地估算 · 基于 ':'服务器数据 · ')+esc(new Date(time).toLocaleString('zh-CN',{month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit'}))+
  (estimate.estimatedValue==null?' · 暂无法估算':' 服务器数据')+'</p>':'<p class="error">'+esc(labels[g.status]||'等待首次刷新')+(Number.isInteger(g.retcode)?'（'+g.retcode+'）':'')+'</p>';
 return '<article class="card" data-role-key="'+esc(roleKey(a,role))+'" style="--accent:'+style+'"><div class="card-heading">'+imageMarkup(a,role)+
  '<h3>'+esc(names[g.id])+'<small>'+esc(roleNickname(a,role)||g.nickname)+' · '+esc(g.server||role.server)+(g.level||role.level?' · '+esc(g.level||role.level)+'级':'')+' · UID '+esc(g.roleUid||role.uid)+'</small></h3>'+
  '<button class="card-refresh" data-refresh-role="'+esc(roleKey(a,role))+'" aria-label="'+(preferences.mode==='local'?'本地估算':'刷新')+' '+esc(names[g.id])+' '+esc(role.uid)+'" '+(refreshing()&&preferences.mode==='server'?'disabled':'')+'>↻</button></div>'+
  '<div class="metrics"><div class="resource"><div>'+esc(g.resource||{genshin:'树脂',starrail:'开拓力',zenless:'电量'}[g.id])+'</div>'+
  '<strong data-resource-value>'+(value==null?'—':value)+'<small> / '+(g.max??'—')+'</small></strong><div class="bar"><i style="width:'+(g.max?Math.max(0,Math.min(100,value/g.max*100)):0)+'%"></i></div>'+
  (g.reserve!=null?'<p>后备开拓力 '+g.reserve+'</p>':'')+'</div><div class="tasks">'+
  task(g.id==='genshin'?'每日委托':'每日活跃',valid?g.dailyCurrent:null,valid?g.dailyMax:null)+
  (g.id==='genshin'?'<div class="task"><span>委托奖励</span><b class="'+(valid?g.taskState:'')+'">'+(valid?esc(g.task):'—')+'</b></div>':'')+
  task(g.id==='genshin'?'周本折扣剩余':'周本奖励剩余',valid?g.weeklyRemaining:null,valid?g.weeklyMax:null,true)+
  (g.id!=='genshin'?task(g.id==='starrail'?'周期积分':'悬赏委托',valid?g.cycleCurrent:null,valid?g.cycleMax:null):'')+'<div class="task checkin-outcome" data-card-checkin hidden></div></div><p class="resource-times"><span data-resource-next>'+esc(presentation.next)+'</span><span data-resource-eta title="'+esc(presentation.etaTitle)+'">'+esc(presentation.eta)+'</span></p></div>'+
  progressFor(g,a)+(g.stale?'<p class="error">上次成功数据 · 本轮未取得新数据'+(g.lastError?' · '+esc(labels[g.lastError]||refreshErrors[g.lastError]||g.lastError):'')+(Number.isInteger(g.retcode)?'（'+g.retcode+'）':'')+'</p>':'')+footer+'</article>';
}
function render(){
 preferences=HoYoUi.normalize(prefsLoaded?preferences:(state.uiPreferences||preferences),state.accounts);
 for(const id of expanded)if(!state.accounts.some(a=>a.id===id))expanded.delete(id);
 if(!prefsLoaded){preferences.expanded.forEach(id=>expanded.add(id));prefsLoaded=true;}
 const selected=state.accounts.find(a=>a.id===state.selectedId),keys=new Set(targets());
 $('#avatar').innerHTML=avatar(selected);$('#verify').disabled=busy||!selected;
 $('#refresh').disabled=busy||refreshStarting||!keys.size||(preferences.mode==='server'&&refreshing());
 $('#refresh').title=preferences.mode==='local'?'更新筛选角色的本地估算':'从服务器刷新筛选角色';
 resourceStatus(keys.size);
 renderProgress();
 $('#groups').innerHTML=orderedAccounts().map(a=>{
  const roles=a.roles.filter(r=>keys.has(roleKey(a,r)));if(!roles.length)return '';
  const games=roles.map(r=>a.games.find(g=>g.id===r.game&&(!g.roleUid||String(g.roleUid)===String(r.uid))&&(!g.serverId||g.serverId===r.serverId))||({id:r.game,roleUid:r.uid,server:r.server,serverId:r.serverId,status:'unknown'}));
  return '<section class="group" data-account-id="'+esc(a.id)+'"><div class="group-bar"><button class="group-heading" data-expand="'+esc(a.id)+'" aria-expanded="'+expanded.has(a.id)+'"><span class="chevron">▶</span>'+avatar(a)+'<strong>'+esc(a.label||'未获取昵称')+'</strong><small>通行证 UID '+esc(a.id)+'</small></button>'+(a.roles.length?'<button class="account-checkin" data-checkin-account="'+esc(a.id)+'" aria-label="每日签到该通行证" title="查看目标角色并签到">✓<span>每日签到</span></button>':'')+'<button class="sort-handle" data-sort="'+esc(a.id)+'" aria-label="长按拖动或点击排序 '+esc(a.label||'账号')+'">↕</button></div><div class="cards" '+(expanded.has(a.id)?'':'hidden')+'>'+games.map(g=>card(g,a)).join('')+'</div></section>';
 }).join('')||(state.accounts.length?'<p class="empty-filter">没有符合筛选的角色，可打开筛选恢复默认。</p>':'');
 $('#accountMenu').innerHTML=orderedAccounts().map(a=>'<div class="account-row"><button class="account-select '+(a.id===state.selectedId?'selected':'')+'" data-select="'+esc(a.id)+'" aria-pressed="'+(a.id===state.selectedId)+'">'+avatar(a)+'<span>'+esc(a.label||'未获取昵称')+'</span></button><button class="remove" data-remove="'+esc(a.id)+'" aria-label="移除账号">×</button></div>').join('')+'<button id="addAccount">＋ 登录新账号</button>';
 $('#addAccount').onclick=()=>{$('#accountMenu').hidden=true;$('#login').showModal();};
 window.HoYoCheckin?.render();
}
let stateRevision=0;
async function load(){const revision=++stateRevision,selected=state.selectedId,value=await api('/api/accounts');if(revision!==stateRevision)return;if(state.selectedId!==selected&&value.accounts.some(a=>a.id===state.selectedId))value.selectedId=state.selectedId;state=value;render();}
let profilePolls=0;const profilePoll=setInterval(()=>{if(busy||refreshing()||refreshStarting)return;if(document.visibilityState==='hidden'||profilePolls++>=18){if(profilePolls>=18)clearInterval(profilePoll);return;}load().catch(()=>{});},3500);
window.addEventListener('pagehide',()=>clearInterval(profilePoll));
function renderProgress(){const box=$('#refreshProgress');box.hidden=!refreshJob;if(!refreshJob)return;const done=refreshJob.roles.filter(r=>['success','failed','cancelled'].includes(r.state)).length;$('#refreshSummary').textContent=`${refreshing()?'正在刷新':'本轮结束'} · ${done}/${refreshJob.roles.length} · ${(refreshJob.elapsedMs/1000).toFixed(1)} 秒`;$('#cancelRefresh').hidden=!refreshing();$('#focusVerification').hidden=!(refreshJob.roles.some(r=>r.state==='waiting_for_user')||(refreshJob.trigger==='login'&&!refreshJob.interactionReady&&refreshJob.roles.some(r=>r.state==='verification_required')));}
async function pollRefresh(){
 if(refreshPolling)return;refreshPolling=true;
 try{const data=await api('/api/accounts/refresh/status');const selected=state.selectedId;state=data;if(state.accounts.some(a=>a.id===selected))state.selectedId=selected;refreshJob=data.refresh||null;render();
  if(refreshing())refreshTimer=setTimeout(pollRefresh,500);
  else if(refreshJob&&refreshStarted){window.HoYoCheckin?.read().catch(()=>{});await afterPaint();const durationMs=Date.now()-refreshStarted;refreshStarted=0;await api('/api/accounts/refresh/rendered',{refreshId:refreshJob.refreshId,durationMs}).catch(()=>{});toast(refreshJob.trigger==='login'?(refreshJob.roles.some(r=>r.state!=='success')?'账号已登录，部分实时便笺尚未更新，请查看角色卡片':'账号已登录，首次便笺已更新'):'本轮刷新结束；失败或取消的角色已保留上次成功数据');}
 }catch(e){toast('刷新进度暂不可用：'+e.message);if(refreshing())refreshTimer=setTimeout(pollRefresh,2000);}
 finally{refreshPolling=false;}
}
async function beginRefresh(singleKey){
 const roleKeys=typeof singleKey==='string'?[singleKey]:targets().slice();
 if(!roleKeys.length){toast('没有筛选角色');return;}
 if(preferences.mode==='local'){
  window.HoYoCheckin?.localAutoSkipped();
  try{await api('/api/resources/estimate',{roleKeys});updateEstimates(new Set(roleKeys));
   const missing=roleKeys.filter(key=>{for(const a of state.accounts){const r=a.roles.find(r=>roleKey(a,r)===key);if(r){const g=a.games.find(g=>g.id===r.game&&String(g.roleUid)===String(r.uid));return HoYoResource.estimateResource(g?.resourceSnapshot,Date.now(),g?.max).estimatedValue==null;}}return true;}).length;
   localRefreshResult='本地估算 · 更新'+roleKeys.length+' 缺基线'+missing;resourceStatus(roleKeys.length);
   toast(missing?'本地估算完成；'+missing+' 个角色暂无可估算的服务器基线':'本地估算已更新；任务和官方错误未改变');}catch(e){toast(e.message);}return;
 }
 if(busy||refreshing()||refreshStarting)return;stateRevision++;refreshStarting=true;render();refreshStarted=Date.now();try{const refreshId=(window.crypto?.randomUUID?.().replaceAll('-','')||[...Array(32)].map(()=>Math.floor(Math.random()*16).toString(16)).join(''));const data=await api('/api/accounts/refresh/start',{refreshId,clientStartedAt:refreshStarted,roleKeys,mode:"server"});state=data;refreshJob=data.refresh;if(!refreshJob)throw Error('原生后端未返回刷新任务');window.HoYoCheckin?.startAuto(typeof singleKey==='string'?'resource_card_refresh':'resource_manual_refresh',refreshId,roleKeys);render();await pollRefresh();}catch(e){refreshStarted=0;window.HoYoCheckin?.stopFollowing();toast(e.message);}finally{refreshStarting=false;render();}}
async function operation(fn){if(busy)return;stateRevision++;busy=true;render();try{await fn();}catch(e){toast(e.message);}finally{busy=false;render();}}
$('#groups').onclick=e=>{
 const sign=e.target.closest('[data-checkin-account]');if(sign){window.HoYoCheckin?.openAccount(sign.dataset.checkinAccount);return;}
 const refresh=e.target.closest('[data-refresh-role]');if(refresh){beginRefresh(refresh.dataset.refreshRole);return;}
 const sort=e.target.closest('[data-sort]');if(sort){if(suppressSortClick)return;sortTarget=sort.dataset.sort;$('#sortName').textContent=state.accounts.find(a=>a.id===sortTarget)?.label||'账号排序';$('#sortDialog').showModal();return;}
 const b=e.target.closest('[data-expand]');if(!b)return;
 expanded.has(b.dataset.expand)?expanded.delete(b.dataset.expand):expanded.add(b.dataset.expand);render();persistPreferences();
};
$('#accountMenu').onclick=e=>{const b=e.target.closest('[data-select],[data-remove]');if(!b)return;operation(async()=>{if(b.dataset.remove){if(!confirm('移除此账号及本机保存的登录状态？'))return;state=await api('/api/accounts/remove',{accountId:b.dataset.remove});expanded.delete(b.dataset.remove);}else state=await api('/api/accounts/select',{accountId:b.dataset.select});});};
$('#account').onclick=()=>{$('#accountMenu').hidden=!$('#accountMenu').hidden;$('#account').setAttribute('aria-expanded',String(!$('#accountMenu').hidden));};
document.addEventListener('click',e=>{if(!e.target.closest('#accountMenu,#account')){$('#accountMenu').hidden=true;$('#account').setAttribute('aria-expanded','false');}});
$('#refresh').onclick=beginRefresh;
$('#cancelRefresh').onclick=async()=>{if(!refreshJob)return;window.HoYoCheckin?.stopFollowing();try{await api('/api/accounts/refresh/action',{refreshId:refreshJob.refreshId,action:'cancel'});clearTimeout(refreshTimer);await pollRefresh();}catch(e){toast(e.message);}};
$('#focusVerification').onclick=async()=>{if(refreshJob)try{await api('/api/accounts/refresh/action',{refreshId:refreshJob.refreshId,action:'focus'});}catch(e){toast(e.message);}};
let fpTarget=null,fpBusy=false,fpGeneration=0;
function showFingerprint(){
 showProfileStatus();
 const a=state.accounts.find(a=>a.id===state.selectedId),f=a?.fingerprint;
 $('#platformStatus').textContent=PLATFORM_STATUS_LABEL+' · APP '+(state.runtimeVersion||'未知')+' · '+(state.backendVersion||'Android 原生后端');
 $('#fpAccount').textContent=a?'当前账号：'+a.label+' · UID '+a.id:'请先登录并选择一个账号';
 $('#fpStatus').textContent=f?(f.present?'正式 FP 已存在（'+f.source+'）':'尚无正式 FP')+
  ' · 正式模型 '+f.profileVersion+(f.updatedAt?' · 签发保存时间 '+new Date(f.updatedAt).toLocaleString():'')+
  (f.pending?' · 已保留未签发申请上下文（模型 '+f.pendingProfileVersion+'）':'')+(f.refreshRecommended?' · 建议主动申请或更新':''):'';
 $('#repairFingerprint').disabled=!a||fpBusy||refreshing();
 $('#cancelFingerprint').hidden=!fpBusy;
}
$('#settings').onclick=async()=>{$('#fpResult').textContent='';showFingerprint();$('#settingsDialog').showModal();try{await load();showFingerprint();}catch(e){toast(e.message);}};
$('#repairFingerprint').onclick=async()=>{
 if(fpBusy)return;
 const a=state.accounts.find(a=>a.id===state.selectedId);if(!a)return;
 const target=a.id,generation=++fpGeneration;fpTarget=target;fpBusy=true;showFingerprint();
 $('#fpResult').textContent='正在向官方申请，目标账号 '+a.label+'；尚未取得正式结果。';
 try{
  const result=await api('/api/accounts/fingerprint/repair',{accountId:target});
  if(generation!==fpGeneration)return;state=result;render();showFingerprint();
  $('#fpResult').textContent='账号 '+a.label+'：'+(result.fingerprintResult?.same?'官方返回相同 FP，已保存。':'官方签发新 FP，已保存。')+'请手动刷新一次，以便笺新数据为准。';
 }catch(e){
  if(generation===fpGeneration)$('#fpResult').textContent=e.message+
   (Number.isInteger(e.upstreamRetcode)?'（外层码 '+e.upstreamRetcode+'）':'')+
   (Number.isInteger(e.fpResultCode)?'（签发码 '+e.fpResultCode+'）':'');
 }finally{if(generation===fpGeneration){fpBusy=false;fpTarget=null;showFingerprint();}}
};
async function cancelFingerprint(){
 if(!fpTarget)return;const target=fpTarget;++fpGeneration;
 try{await api('/api/accounts/fingerprint/cancel',{accountId:target});$('#fpResult').textContent='申请已取消；旧正式 FP 与稳定请求上下文保留。';}
 catch(e){toast(e.message);}finally{fpBusy=false;fpTarget=null;await load().catch(()=>{});showFingerprint();}
}
$('#cancelFingerprint').onclick=cancelFingerprint;
$('#settingsDialog').addEventListener('close',()=>{if(fpBusy)cancelFingerprint();});

function loginTrace(id,ui_state){if(id)api('/api/auth/diagnostic',{login_session_id:id,ui_state,duration_ms:Math.max(0,Date.now()-qrStarted)}).catch(()=>{});}
async function cancelQr(){clearTimeout(qrTimer);qrGeneration++;const id=qrId;qrId=null;if(id){loginTrace(id,'cancelled');api('/api/auth/qr/'+id,undefined,'DELETE').catch(()=>{});}}
async function loginCompleted(result){await load();if(result.initialRefresh?.status==='scheduled'){refreshStarted=Date.now();if(!$('#login').open)await api('/api/accounts/refresh/action',{refreshId:result.initialRefresh.refreshId,action:'ready'}).catch(()=>toast('账号已登录，可点击继续验证恢复首次验证队列'));pollRefresh();}toast(result.code==='ALREADY_LOGGED_IN'?'当前米游社账号已经登录，已保留原有数据':result.code==='ACCOUNT_UPDATED'?'登录凭据已更新，已保留原有数据':result.code==='LOGIN_SUCCESS_REFRESH_FAILED'?'账号已登录，首次便笺刷新未启动，请手动刷新':'账号已登录，首次便笺正在更新；需要时请本人完成官方验证');}
async function poll(id,generation=qrGeneration){if(qrId!==id||generation!==qrGeneration||!$('#login').open)return;loginTrace(id,'polling');try{const result=await api('/api/auth/qr/'+id);if(qrId!==id||generation!==qrGeneration||!$('#login').open)return;if(result.status==='confirmed'){loginTrace(id,'confirmed');qrId=null;$('#login').close();await loginCompleted(result);return;}$('#qrStatus').textContent=result.error||{pending:QR_LOGIN_HINT,scanned:'已扫码，请在米游社确认',expired:'二维码已过期，请重新生成',failed:'登录失败，请重新生成'}[result.status]||'等待确认';if(['pending','scanned'].includes(result.status))qrTimer=setTimeout(()=>poll(id,generation),1800);else loginTrace(id,result.status==='expired'?'expired':'failed');}catch(e){if(qrId===id&&generation===qrGeneration){$('#qrStatus').textContent=e.message;loginTrace(id,'failed');}}}
$('#newQr').onclick=async()=>{await cancelQr();const generation=qrGeneration;qrStarted=Date.now();$('#newQr').disabled=true;try{const data=await api('/api/auth/qr/start',{});if(generation!==qrGeneration||!$('#login').open){api('/api/auth/qr/'+data.sessionId,undefined,'DELETE').catch(()=>{});return;}qrId=data.sessionId;const img=new Image();img.alt='米游社登录二维码';img.src=data.qrImageDataUrl;$('#qrImage').replaceChildren(img);$('#qrStatus').textContent=QR_LOGIN_HINT;loginTrace(qrId,'opening');poll(qrId,generation);}catch(e){if(generation===qrGeneration)toast(e.message);}finally{if(generation===qrGeneration)$('#newQr').disabled=false;}};
$('#login').addEventListener('close',()=>{cancelQr();$('#newQr').disabled=false;});
function showVerificationState(){
 const a=state.accounts.find(a=>a.id===(verifyAccountId||state.selectedId)),v=a?.verification;
 const messages={official_verification_unavailable:OFFICIAL_VERIFICATION_UNAVAILABLE_TEXT,account_risk:ACCOUNT_RISK_TEXT,cancelled:'本次验证已取消，原便笺保留。',verification_timeout:'验证处理超时，未认定便笺更新成功。',opening_record:'正在打开官方战绩页面；页面加载不等于便笺更新。',closed_without_data:'官方页面已关闭。若已完成本人操作，请手动刷新；原有数据保留。',record_promotion:'官方入口显示推广内容，尚未取得战绩。请导出诊断。',record_page_error:'官方页面显示限制或错误；原便笺保留，请导出诊断。',opened:'正在加载官方验证界面，请本人操作；组件就绪不代表便笺成功。',notes_received:'已取得并保存当前角色的实时便笺。',risk_control_required:RISK_CONTROL_REQUIRED_TEXT,request_invalid:'请求未被接受，请检查版本、签名及请求参数兼容性',auth_expired:'登录已失效，请重新登录。',notes_not_enabled:'请先在米游社开启实时便笺。',http_error:'页面请求失败，请检查网络。',upstream_rejected:'当前请求未被接受。',response_invalid:'未收到可识别的便笺数据。'};
 $('#verificationState').textContent=(messages[v?.state]||'选择角色查询可用的人机验证。')+(Number.isInteger(v?.retcode)?`（返回码 ${v.retcode}）`:'');
}
$('#verify').onclick=()=>{const a=state.accounts.find(a=>a.id===state.selectedId);if(!a)return;verifyAccountId=a.id;$('#verifyName').textContent=a.label;$('#verifyGames').innerHTML=a.roles.map(r=>`<div class="verify-role"><button data-record="${esc(r.game)}" data-role-uid="${esc(r.uid)}">打开官方战绩 · ${esc(names[r.game])} · ${esc(r.nickname)} · ${esc(r.uid)}</button>${a.games.some(g=>g.id===r.game&&String(g.roleUid)===String(r.uid)&&[1034,10035,10041,5003].includes(g.retcode))?'<button data-webview="'+esc(r.game)+'" data-role-uid="'+esc(r.uid)+'">处理官方人机验证</button>':''}</div>`).join('')||'<p>该账号尚无可验证的游戏角色</p>';showVerificationState();$('#verifyDialog').showModal();};
$('#verifyGames').onclick=e=>{const b=e.target.closest('[data-record],[data-webview]');if(b)operation(async()=>{const browse=!!b.dataset.record;const result=await api(browse?'/api/accounts/record/open':'/api/accounts/verify',{accountId:verifyAccountId,game:b.dataset.record||b.dataset.webview,roleUid:b.dataset.roleUid});await load();showVerificationState();toast(result.opened?(browse?'正在打开官方战绩，请核对内容；关闭页面后请手动刷新资源总览':'已打开官方人机验证，请手动操作；结果以便笺请求为准'):result.alreadyPending?'此账号已有验证窗口，已尝试聚焦':refreshErrors[result.reason]||'当前无法打开验证入口');});};
$('#checkVerification').onclick=()=>operation(async()=>{await load();showVerificationState();});
(async()=>{try{const session=await api('/api/session');csrf=session.csrfToken;await load();if(session.progressiveRefresh)await pollRefresh();}catch(e){toast('原生服务未就绪：'+e.message);}finally{$('#groups').setAttribute('aria-busy','false');}})();

window.addEventListener('hoyo-account-update',()=>{load().then(()=>{if($('#verifyDialog').open)showVerificationState();}).catch(e=>toast(e.message));});

// Native container exclusively owns system insets; toolbar can wrap under font scaling.
if(typeof ResizeObserver!=='undefined')new ResizeObserver(()=>document.documentElement.style.setProperty('--toolbar-bottom',document.querySelector('header').getBoundingClientRect().bottom+'px')).observe(document.querySelector('header'));
document.querySelector('#showDiagnostics')?.addEventListener('click',()=>operation(async()=>{document.querySelector('#diagnosticsOutput').textContent=JSON.stringify(await api('/api/diagnostics'),null,2);}));

document.querySelector("#exportDiagnostics")?.addEventListener("click",()=>operation(async()=>{await api("/api/diagnostics/export",{});}));

document.querySelector("#exportRefreshDiagnostics")?.addEventListener("click",()=>operation(async()=>{await api("/api/diagnostics/export",{latestRefresh:true});}));

$('#verifyDialog').addEventListener('close',()=>{api('/api/accounts/verification/cancel',{}).catch(()=>{});});


// A single visible-page timer never alters the original snapshot or calls native/network.
function updateEstimates(only){
 window.HoYoCheckin?.tick?.();const now=Date.now();
 for(const element of document.querySelectorAll('.card[data-role-key]')){
  const key=element.dataset.roleKey;if(only&&!only.has(key))continue;
  let note;for(const a of state.accounts){const r=a.roles.find(r=>roleKey(a,r)===key);if(r)note=a.games.find(g=>g.id===r.game&&String(g.roleUid)===String(r.uid)&&(!g.serverId||g.serverId===r.serverId));}
  if(!note)continue;const times=HoYoResource.presentation(note,now),value=times.estimate;
  element.querySelector('[data-resource-next]').textContent=times.next;element.querySelector('[data-resource-eta]').textContent=times.eta;element.querySelector('[data-resource-eta]').title=times.etaTitle;
  if(value.estimatedValue==null){
   element.querySelector('[data-resource-value]').firstChild.textContent=note.current??'—';
   element.querySelector('.bar i').style.width=(note.max?100*(note.current||0)/note.max:0)+'%';

   const label=element.querySelector('[data-resource-source]');if(label)label.textContent='服务器数据 · '+new Date(note.updated).toLocaleString('zh-CN',{month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit'})+' · 暂无法估算';
   continue;
  }
  const sourceLabel=element.querySelector('[data-resource-source]');
  if(sourceLabel)sourceLabel.textContent='本地估算 · 基于 '+new Date(note.lastSuccessfulFetchAt||note.updated).toLocaleString('zh-CN',{month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit'})+' 服务器数据';
  element.querySelector('[data-resource-value]').firstChild.textContent=value.estimatedValue;
  element.querySelector('.bar i').style.width=(100*value.estimatedValue/note.max)+'%';
 }
}
let estimateTimer=null;
function resumeEstimates(){clearInterval(estimateTimer);estimateTimer=null;updateEstimates();if(!document.hidden)estimateTimer=setInterval(updateEstimates,1000);}
document.addEventListener('visibilitychange',resumeEstimates);window.addEventListener('hoyo-resume',resumeEstimates);window.addEventListener('pageshow',resumeEstimates);resumeEstimates();
document.addEventListener('error',e=>{if(e.target.matches?.('img.avatar')){reportAvatar(e,false);const fallback=document.createElement('span');fallback.className='avatar-placeholder';fallback.textContent='◉';fallback.title='头像未缓存或加载失败';e.target.replaceWith(fallback);}},true);
function renderFilters(){
 const accountIds=preferences.accountIds===null?state.accounts.map(a=>a.id):preferences.accountIds;
 const roleKeys=preferences.roleKeys===null?state.accounts.flatMap(a=>a.roles.map(r=>roleKey(a,r))):preferences.roleKeys;
 $('#accountFilters').innerHTML=orderedAccounts().map(a=>'<label>'+avatar(a)+'<input type="checkbox" data-filter-account="'+esc(a.id)+'" '+(accountIds.includes(a.id)?'checked':'')+'>'+esc(a.label||'未获取昵称')+' · '+esc(a.id)+'</label>').join('');
 $('#roleFilters').innerHTML=orderedAccounts().filter(a=>accountIds.includes(a.id)).map(a=>a.roles.map(r=>'<label>'+imageMarkup(a,r)+'<input type="checkbox" data-filter-role="'+esc(roleKey(a,r))+'" '+(roleKeys.includes(roleKey(a,r))?'checked':'')+'>'+esc(a.label||'未获取昵称')+' · '+esc(names[r.game])+' · '+esc(r.uid)+' · '+esc(r.server)+'</label>').join('')).join('');
 $('#filterCount').textContent='已选择 '+accountIds.length+' 个账号、'+targets().length+' 个角色';
 document.querySelectorAll('input[name="refreshMode"]').forEach(r=>r.checked=r.value===preferences.mode);
}
$('#filter').onclick=()=>{renderFilters();window.HoYoCheckin?.renderAuto();$('#filterDialog').showModal();};
$('#filterDialog').addEventListener('change',e=>{
 const a=e.target.dataset.filterAccount,r=e.target.dataset.filterRole;
 if(a){const set=new Set(preferences.accountIds??state.accounts.map(a=>a.id));e.target.checked?set.add(a):set.delete(a);preferences.accountIds=[...set];}
 if(r){const set=new Set(preferences.roleKeys??state.accounts.flatMap(a=>a.roles.map(r=>roleKey(a,r))));e.target.checked?set.add(r):set.delete(r);preferences.roleKeys=[...set];}
 if(e.target.name==='refreshMode')preferences.mode=e.target.value;
 state.uiPreferences=structuredClone(preferences);render();renderFilters();persistPreferences();
});
for(const [id,kind] of [['filterAll','all'],['filterClear','clear'],['filterReset','reset']])$('#'+id).onclick=()=>{
 preferences.accountIds=kind==='clear'?[]:null;preferences.roleKeys=kind==='clear'?[]:null;
 if(kind==='reset')preferences.mode='server';
 state.uiPreferences=structuredClone(preferences);render();renderFilters();persistPreferences();
};
function moveAccount(delta){
 const from=preferences.order.indexOf(sortTarget);if(from<0)return;
 preferences.order=HoYoUi.move(preferences.order,sortTarget,from+delta);state.uiPreferences=structuredClone(preferences);render();persistPreferences();
}
$('#sortUp').onclick=()=>moveAccount(-1);$('#sortDown').onclick=()=>moveAccount(1);
let drag=null,suppressSortClick=false;
document.addEventListener('pointerdown',e=>{
 const handle=e.target.closest('[data-sort]');if(!handle)return;
 drag={id:handle.dataset.sort,target:null,ready:false,timer:setTimeout(()=>{if(drag){drag.ready=true;handle.classList.add('drag-ready');}},300)};
});
document.addEventListener('pointermove',e=>{
 if(!drag?.ready)return;
 const group=document.elementFromPoint(e.clientX,e.clientY)?.closest('[data-account-id]');
 if(group){drag.target=group.dataset.accountId;document.querySelectorAll('.drop-target').forEach(el=>el.classList.remove('drop-target'));group.classList.add('drop-target');}
});
function finishDrag(cancel){
 if(!drag)return;clearTimeout(drag.timer);
 if(!cancel&&drag.ready&&drag.target&&drag.target!==drag.id){
  preferences.order=HoYoUi.move(preferences.order,drag.id,preferences.order.indexOf(drag.target));
  state.uiPreferences=structuredClone(preferences);render();persistPreferences();suppressSortClick=true;setTimeout(()=>suppressSortClick=false,100);
 }
 drag=null;document.querySelectorAll('.drag-ready,.drop-target').forEach(el=>el.classList.remove('drag-ready','drop-target'));
}
document.addEventListener('pointerup',()=>finishDrag(false));document.addEventListener('pointercancel',()=>finishDrag(true));
function showProfileStatus(){
 const a=state.accounts.find(a=>a.id===state.selectedId);
 $('#profileStatus').textContent=!a?'请先登录账号':(a.profileState==='loaded'?'已读取官方通行证资料':'通行证资料尚未获取')+' · '+(a.avatarState==='not_set'?'账号未设置头像':a.avatarState==='loaded'?'头像地址已获取；加载失败时显示占位':a.avatarState==='not_provided'?'官方未提供头像地址，未确认是否设置':'头像尚未获取')+(a.profileError?' · 最近一次补全未成功，保留旧资料':'');
}
$('#settingsDialog').addEventListener('toggle',showProfileStatus);
let profileJob=null,profileTimer=null,profilePolling=false,profileFailures=0;
function showProfileBatch(){
 const total=profileJob?.total??state.accounts.length,roles=profileJob?.roles??state.accounts.reduce((n,a)=>n+a.roles.length,0),running=profileJob?.status==='running';
 $('#completeProfile').disabled=running||!state.accounts.length;$('#cancelProfileBatch').hidden=!running;
 const outcomes=profileJob?.accounts||[],counts={cached:0,completed:0,partial:0,failed:0,cancelled:0};outcomes.forEach(r=>{if(r.state in counts)counts[r.state]++;});
 $('#profileBatchStatus').textContent='全部 '+total+' 个通行证 / '+roles+' 个角色'+(profileJob?' · '+(running?'处理中 '+profileJob.done+'/'+total:profileJob.status==='cancelled'?'已取消':'本轮结束')+' · 已缓存 '+counts.cached+' · 已补全 '+counts.completed+' · 部分未取得 '+counts.partial+' · 失败 '+counts.failed:'');
}
function profileState(value){
 if(!value?.accounts)return;const selected=state.selectedId;state=value;if(state.accounts.some(a=>a.id===selected))state.selectedId=selected;stateRevision++;render();showProfileStatus();
}
async function pollProfileBatch(){
 if(profilePolling)return;profilePolling=true;
 try{const value=await api('/api/accounts/profile/batch/status');profileFailures=0;profileJob=value.job||null;profileState(value.state);showProfileBatch();if(profileJob?.status==='running')profileTimer=setTimeout(pollProfileBatch,1000);}
 catch(e){if(profileJob&&++profileFailures<3)profileTimer=setTimeout(pollProfileBatch,2000);else if(profileJob){toast('资料进度暂不可用，请稍后重新打开设置核对；不代表补全成功');}}
 finally{profilePolling=false;}
}
$('#completeProfile').onclick=async()=>{
 if(profileJob?.status==='running')return;$('#completeProfile').disabled=true;
 try{const value=await api('/api/accounts/profile/batch/start',{});profileJob=value.job;profileState(value.state);showProfileBatch();await pollProfileBatch();}
 catch(e){toast(e.message);showProfileBatch();}
};
$('#cancelProfileBatch').onclick=async()=>{if(!profileJob)return;try{await api('/api/accounts/profile/batch/cancel',{operationId:profileJob.operationId});clearTimeout(profileTimer);await pollProfileBatch();}catch(e){toast(e.message);}};
$('#settings').addEventListener('click',()=>pollProfileBatch());
window.addEventListener('pagehide',()=>{clearTimeout(profileTimer);if(profileJob?.status==='running')api('/api/accounts/profile/batch/cancel',{operationId:profileJob.operationId}).catch(()=>{});});

let avatarReports=0;
function reportAvatar(event,success){
 const el=event.target;if(!el.matches?.('img.avatar')||!el.dataset.avatarOwner||avatarReports++>=60)return;
 api('/api/avatar/render',{accountId:el.dataset.avatarOwner,roleKey:el.dataset.avatarRole||'account',success}).catch(()=>{});
}
document.addEventListener('load',e=>reportAvatar(e,true),true);
document.addEventListener('error',e=>reportAvatar(e,false),true);
