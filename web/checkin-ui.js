/* Shared UI contract; native adapters own credentials, transport, storage and scheduling. */
(()=>{
 let ledger={roles:{}},page='resources',selectedKey=null,accountTarget=null,inFlight=false,loaded=false,loading=null,revision=0;
 const expandedRewards=new Map(),expandedAccounts=new Map();let quote=null,quoteGeneration=0;
 let selection={mode:'all'},selectionEpoch=0,activeBatch=null,batchTimer=null,batchPolling=false;
 let followKeys=new Map(),followUntil=0,nextLocalRead=0,followFailures=0;
 const status={unknown:'尚未查询',querying:'查询中',queued:'等待查询',cancelled:'已取消',not_signed:'今日未签到',already_signed:'今日已签到',signed:'今日已签到',blocked:'需本人验证',login_expired:'登录失效',failed:'检查失败',disabled:'自动已关闭',cooldown:'冷却中',throttled:'检查限频',rate_limited:'服务器限流',skipped_before_0001:'尚未到00:01',server_day_mismatch:'服务器日期未到今日',unconfirmed:'签到结果未确认',signing:'正在签到',verifying_result:'正在核实',unsupported:'不支持'};
 const roleRows=()=>orderedAccounts().flatMap(a=>a.roles.filter(r=>names[r.game]&&r.uid&&(r.serverId||r.server)).map(r=>({a,r:{...r,nickname:roleNickname(a,r)},key:roleKey(a,r)})));
 const selectedKeys=()=>HoYoUi.checkinKeys(state.accounts,selection);
 function changeSelection(next){selection=next;selectionEpoch++;discardQuote();if($('#resignDialog').open)$('#resignDialog').close();paint();}
 function selectKeys(keys){changeSelection({mode:'subset',roleKeys:keys});}
 const serverDay=()=>new Date(Date.now()+8*3600000).toISOString().slice(0,10);
 let lastDay=serverDay();
 const confirmedFor=row=>{const c=row.confirmed;return c&&c.day===serverDay()?c:null;};
 const rowFor=key=>{const row=ledger.roles?.[key+'|CN']||{};let state=row.state;
  if(['signed','already_signed','not_signed'].includes(state)&&row.info?.today!==serverDay())state='unknown';
  const c=confirmedFor(row);if(c&&c.isSigned)state='already_signed';
  else if(!c&&['signed','already_signed'].includes(state))state=row.info?.today===serverDay()?state:'unknown';
  const check=row.check&&row.check.day===serverDay()?row.check:null;
  return {...row,state,check,confirmed:c};};
 const retryReasons={transient:'临时网络失败',server_unavailable:'服务暂不可用',server_throttle:'服务器限流',risk:'需人工处理',auth:'需重新登录',pending:'提交待确认',invalid:'响应不可用',server_rejected:'官方拒绝',legacy_unknown:'历史原因不明'};
 const text=row=>{
  let label=status[row.state]||'尚未查询';const p=row.retry,now=Date.now();
  if(row.state==='unknown'&&row.confirmed&&row.confirmed.day!==serverDay())label='今日状态待确认';
  if(row.state==='throttled'){label+=' · '+Math.max(0,Math.ceil(((row.lastAutoAt||0)+60000-now)/1000))+'秒后可检查';return row.check?label+' · 本次检查'+status[row.check.state]:label;}
  if(p&&['cooldown','rate_limited','failed','blocked','login_expired','unconfirmed'].includes(row.state)){
   label+=' · '+(retryReasons[p.kind]||'等待重试');
   if(!['risk','auth','pending'].includes(p.kind)){const seconds=Math.max(0,Math.ceil((p.until-now)/1000));label+=seconds?' · '+Math.ceil(seconds/60)+'分钟后可重试':' · 已到重试时间';}
   if(p.kind==='pending')label+=' · 仅查询核实';
   if(p.autoPaused&&p.businessDay===serverDay())label+=' · 下次允许检查需手动刷新';
   if(Number.isInteger(p.retcode)&&!Number.isInteger(row.code))label+=' · 上次阻塞码'+p.retcode;
  }
  // A confirmed check-in is a fact for the business day; a later limited check is shown as this run's result only.
  if(row.check&&['signed','already_signed'].includes(row.state))label+=' · 本次检查'+status[row.check.state];
  return label;
 };
 const checked=()=>new Set([...document.querySelectorAll('[data-checkin-target]:checked')].map(x=>x.dataset.checkinTarget));
 async function read(){const current=revision;loading ||= api('/api/checkin').then(x=>{if(current===revision){applyLedger(x);}}).finally(()=>loading=null);await loading;paint();}
 function resultMarkup(rows){return rows.map(({a,r,key})=>{const row=rowFor(key);return '<p>'+esc(names[r.game])+' · '+esc(r.uid)+'：'+esc(text(row))+(Number.isInteger(row.code)?'（'+row.code+'）':'')+'</p>';}).join('');}
 function renderSelection(){
  const rows=roleRows(),keys=new Set(selectedKeys());
  if(selection.mode==='subset')selection={mode:'subset',roleKeys:[...keys]};
  const focus=document.activeElement?.dataset,focused=focus?.checkinSelect||focus?.checkinOwner||null;
  $('#checkinChoices').innerHTML='<h3>通行证与角色</h3><div class="checkin-selection-tools"><label><input type="checkbox" data-checkin-all>全部角色</label><span>已选 '+keys.size+' / 共 '+rows.length+'</span><button data-checkin-clear>清除选择</button></div>'+orderedAccounts().map(a=>{
   const own=rows.filter(x=>x.a.id===a.id),open=expandedAccounts.get(a.id)!==false;
   const trusted=own.filter(x=>{const row=rowFor(x.key);return row.info?.today===serverDay()&&typeof row.info.isSign==='boolean'&&['signed','already_signed','not_signed'].includes(row.state);});
   const done=trusted.filter(x=>rowFor(x.key).info.isSign).length;
   return '<section class="checkin-group"><div class="checkin-group-heading">'+avatar(a)+'<label><input type="checkbox" data-checkin-owner="'+esc(a.id)+'" aria-label="选择通行证 '+esc(a.label||a.id)+' 全部角色" '+(!own.length?'disabled':'')+'><strong>'+esc(a.label||'未获取昵称')+'</strong></label><small>通行证 UID '+esc(a.id)+' · 已签 '+done+' / 可信 '+trusted.length+' · 未确认 '+(own.length-trusted.length)+'</small><button data-checkin-collapse="'+esc(a.id)+'" aria-expanded="'+open+'" aria-label="'+(open?'收起':'展开')+' '+esc(a.label||a.id)+'">'+(open?'⌃':'⌄')+'</button></div><div '+(open?'':'hidden')+'>'+own.map(({r,key})=>{
    const row=rowFor(key),progress=activeBatch?.status==='running'?activeBatch.roles?.find(x=>x.roleKey===key):null;
    const historic=row.info?.today&&row.info.today!==serverDay();
    const stateText=progress&&['queued','querying'].includes(progress.state)?status[progress.state]:historic?'历史缓存 · '+row.info.today:row.info?.fresh===false?'上次成功缓存 · '+text(row):text(row);
    return '<div class="checkin-role-row">'+imageMarkup(a,r)+'<label><input type="checkbox" data-checkin-select="'+esc(key)+'" '+(keys.has(key)?'checked':'')+' aria-label="选择 '+esc(a.label||a.id)+' '+esc(names[r.game])+' '+esc(r.nickname||r.uid)+' '+esc(r.server||r.serverId)+'"><span><strong>'+esc(names[r.game])+'</strong><small>'+esc(r.nickname||'—')+' · '+esc(r.server||r.serverId||'—')+' · '+esc(r.level??'—')+'级 · UID '+esc(r.uid)+'</small></span></label><div class="checkin-role-status" role="status">'+esc(stateText)+(Number.isInteger(row.code)?'（'+row.code+'）':'')+'<small>'+(row.checkedAt?'上次查询 '+esc(new Date(row.checkedAt).toLocaleString()):'尚未联网查询')+'</small></div><button data-checkin-only="'+esc(key)+'" aria-label="仅选择 '+esc(names[r.game])+' '+esc(r.uid)+'">仅选</button></div>';
   }).join('')+(own.length?'':'<p>未发现支持的游戏角色</p>')+'</div></section>';
  }).join('');
  const all=$('[data-checkin-all]');all.checked=rows.length>0&&keys.size===rows.length;all.indeterminate=keys.size>0&&keys.size<rows.length;
  document.querySelectorAll('[data-checkin-owner]').forEach(el=>{const own=rows.filter(x=>x.a.id===el.dataset.checkinOwner),n=own.filter(x=>keys.has(x.key)).length;el.checked=!!n&&n===own.length;el.indeterminate=n>0&&n<own.length;});
  for(const [id,action] of [['checkinRefresh','info'],['checkinSign','sign'],['checkinResign','resign']])$('#'+id).disabled=inFlight||!keys.size;
  $('#cancelCheckinBatch').hidden=activeBatch?.status!=='running';
  $('#checkinBatchStatus').textContent=activeBatch?'本次操作按点击时选择执行 · '+activeBatch.done+'/'+activeBatch.total+' · '+({running:'执行中',completed:'已结束',cancelled:'已取消',failed:'执行失败'}[activeBatch.status]||'')+(activeBatch.error?' · '+activeBatch.error:''):'';
  if(focused)document.querySelectorAll('[data-checkin-select],[data-checkin-owner]').forEach(el=>{if((el.dataset.checkinSelect||el.dataset.checkinOwner)===focused)el.focus({preventScroll:true});});
 }
 function detail(){
  renderSelection();const keys=new Set(selectedKeys());
  $('#checkinDetail').innerHTML=roleRows().filter(x=>keys.has(x.key)).map(({a,r,key})=>{
   const row=rowFor(key),home=row.home,info=row.info,total=info?.total,date=info?.today;
   const valid=/^[0-9]{4}-[0-9]{2}-[0-9]{2}$/.test(date||'');
   const estimate=!!(home&&valid&&home.month===date.slice(0,7)&&Number.isInteger(total)&&total>=0&&total<=home.days&&total<=Number(date.slice(-2))&&!(info.isSign&&total===0));
   const awards=home?Array.from({length:home.days},(_,i)=>home.awards?.[i]||{}).map((item,i)=>'<div class="reward '+(estimate&&i<total?'estimated':'')+'"><span class="reward-day">第 '+(i+1)+' 档奖励</span>'+(item.icon?'<img alt="" loading="lazy" referrerpolicy="no-referrer" src="'+esc(HoYoPlatform.rewardUrl(a.id,r.game+':'+r.uid+':'+(r.serverId||r.server),i))+'">':'')+'<span>'+esc(item.name||'奖励名称未知')+'</span><b>× '+esc(item.count??'—')+'</b><small>'+(estimate&&i<total?'推算已领取':'领取状态未知')+'</small></div>').join(''):'<p>尚未取得本月官方奖励列表。</p>';
   return '<details class="checkin-card checkin-history" data-rewards-key="'+esc(key)+'" '+(expandedRewards.get(key)?'open':'')+'><summary>'+esc(a.label||a.id)+' · '+esc(names[r.game])+' · '+esc(r.nickname||r.uid)+' · 本月累计签到 '+(Number.isInteger(total)?total:'—')+' 天 · '+esc(text(row))+' · 奖励明细</summary><p>角色 UID '+esc(r.uid)+' · '+esc(r.server||r.serverId)+' · '+(date?'官方日期 '+esc(date):'尚未查询')+'</p><p class="muted">依据本月累计签到数量推算奖励档位；不是官方自然日签到记录。'+(!estimate?'当前字段不足、跨月或矛盾，不作推算。':'')+'</p><p><label><input type="checkbox" data-auto-checkin="'+esc(key)+'" '+(row.enabled?'checked':'')+'>该角色自动签到</label></p><p>补签：'+esc(({confirmed:'服务端回读已确认',unconfirmed:'结果不确定，禁止自动重扣',submitting:'正在提交',blocked:'需本人验证',failed:'官方拒绝'}[row.resignState]||'尚未操作'))+'</p><div class="reward-grid">'+awards+'</div></details>';
  }).join('')||'<p>请先选择角色；零选择不会发起官方请求。</p>';
 }
 let modeQueue=Promise.resolve(),modePending=0;
 function applyLedger(value){
  if(!value?.roles)return;
  if((value.statusRevision||0)<(ledger.statusRevision||0))return;

  if((value.configurationRevision||0)<(ledger.configurationRevision||0)){
   value=structuredClone(value);value.configurationRevision=ledger.configurationRevision;
   for(const [key,row] of Object.entries(ledger.roles))value.roles[key]={...value.roles[key],enabled:row.enabled===true};
  }
  ledger=value;loaded=true;
 }
 function renderAuto(){
  const rows=roleRows(),enabled=rows.filter(x=>rowFor(x.key).enabled).length;
  const html='<label class="checkin-mode-all"><input type="checkbox" data-auto-checkin-all>全部自动签到</label><p class="muted">当前全部 '+rows.length+' 个角色；新增角色默认手动，与签到查询选择无关。</p>'+orderedAccounts().map(a=>'<fieldset><legend>'+esc(a.label||a.id)+'</legend>'+rows.filter(x=>x.a.id===a.id).map(({r,key})=>'<label class="checkin-mode-row"><input type="checkbox" data-auto-checkin="'+esc(key)+'" '+(rowFor(key).enabled?'checked':'')+'>'+esc(names[r.game])+' · '+esc(r.nickname||'—')+' · '+esc(r.server||r.serverId)+' · '+esc(r.uid)+'</label>').join('')+'</fieldset>').join('');
  for(const id of ['autoCheckinOptions','checkinModeOptions']){
   const box=$('#'+id);if(!box)continue;
   const focused=box.contains(document.activeElement)?document.activeElement.dataset.autoCheckin||'all':null;
   box.innerHTML=loaded?html:'正在读取本机签到设置…';
   const all=box.querySelector('[data-auto-checkin-all]');if(all){all.checked=rows.length>0&&enabled===rows.length;all.indeterminate=enabled>0&&enabled<rows.length;}
   box.querySelectorAll('input').forEach(el=>{el.disabled=modePending>0||!rows.length;});
   if(focused)box.querySelectorAll('input').forEach(el=>{if((el.dataset.autoCheckin||'all')===focused)el.focus({preventScroll:true});});
  }
  $('#autoCheckinCount').textContent='自动开启 '+enabled+' / '+rows.length+' 个角色；仅在刷新资源总览或签到明细时自动签到，不会自动补签。';
 }
 function updateMode(keys,enabled){
  const frozen=[...new Set(keys)];if(!frozen.length)return;modePending++;revision++;renderAuto();
  modeQueue=modeQueue.catch(()=>{}).then(async()=>{revision++;const result=await api('/api/checkin/preferences',{roleKeys:frozen,enabled});applyLedger(result);}).catch(e=>toast('签到模式未保存：'+e.message)).finally(()=>{modePending--;paint();renderAuto();});
  return modeQueue;
 }
 function pageTitle(){
  const status=$('#autoCheckinStatus');if(page==='resources')$('#resourcePage').append(status);else $('#checkinPage').before(status);
  const nav=document.querySelector('[data-page="'+page+'"]'),title=$('#pageTitle');if(!nav||!title)return;
  const icon=nav.querySelector('svg')?.cloneNode(true)||document.createElement('span');if(!nav.querySelector('svg'))icon.textContent='✓';icon.setAttribute('aria-hidden','true');
  const label=document.createElement('span');label.textContent=nav.querySelector('span')?.textContent|| (page==='checkin'?'签到明细':'资源总览');title.replaceChildren(icon,label);title.setAttribute('aria-label',label.textContent);
 }
 $('#chooseCheckinMode').onclick=async()=>{renderAuto();$('#checkinModeDialog').showModal();await read().catch(e=>toast(e.message));renderAuto();};
 pageTitle();
 function paint(){
  document.querySelectorAll('.card[data-role-key]').forEach(card=>{const row=rowFor(card.dataset.roleKey),target=card.querySelector('[data-card-checkin]');if(target){target.hidden=!row.enabled&&!row.state;target.textContent='签到：'+text(row)+(Number.isInteger(row.code)?'（'+row.code+'）':'');}});
  roleRows();document.querySelectorAll('[data-checkin-account]').forEach(el=>{
   const rows=roleRows().filter(x=>x.a.id===el.dataset.checkinAccount),all=rows.length&&rows.every(x=>['signed','already_signed'].includes(rowFor(x.key).state));
   el.classList.toggle('done',!!all);el.title=all?'服务端已确认今日全部签到':'查看该通行证目标并签到';el.disabled=inFlight;
  });
  if(page==='checkin')detail();
  if($('#filterDialog').open||$('#checkinModeDialog').open)renderAuto();
  if(accountTarget)$('#checkinAccountResults').innerHTML=resultMarkup(roleRows().filter(x=>x.a.id===accountTarget));
 }
 async function pollBatch(){
  if(batchPolling)return;batchPolling=true;
  try{const expected=revision,value=await api('/api/checkin/batch/status');if(expected!==revision&&!activeBatch)return;if(value.ledger){applyLedger(value.ledger);}activeBatch=value.job||null;acceptAuto(value);inFlight=activeBatch?.status==='running';paint();if(inFlight)batchTimer=setTimeout(pollBatch,500);}
  catch(e){if(activeBatch)toast(e.message);if(activeBatch?.status==='running')batchTimer=setTimeout(pollBatch,1500);else{inFlight=false;paint();}}
  finally{batchPolling=false;}
 }
 let autoLocalNotice='',autoJob=null,autoTimer=null,autoPolling=false,autoUntil=0,autoFailures=0,nextAutoRead=0;
 function acceptAuto(value){
  if(!value?.autoJob||(value.ledger?.statusRevision||0)<(ledger.statusRevision||0))return;autoJob=value.autoJob;autoLocalNotice='';autoUntil=Date.now()+600000;autoFailures=0;clearTimeout(autoTimer);paintAuto();
  if(['queued','running'].includes(autoJob.status))autoTimer=setTimeout(pollAuto,500);
 }
 function paintAuto(){
  const el=$('#autoCheckinStatus');if(!autoJob&&!autoLocalNotice)return;el.hidden=false;
  if(!autoJob){statusLines('autoCheckinStatus','自动签到',autoLocalNotice);return;}
  const c=autoJob.counts||{};let phase={queued:'排队中',running:'检查中',completed:'检查结束',cancelled:'已取消',failed:'执行失败'}[autoJob.status]||'未知';
  const queryOnly=autoJob.queryOnly===true;
  const title=(queryOnly?'签到状态检查：':'自动签到：')+autoJob.total+(queryOnly?' 个角色':' 个已启用角色')+' · '+autoJob.done+'/'+autoJob.total+(autoLocalNotice?' · '+autoLocalNotice:'');
  const signed=c.already_signed||0,newSign=c.signed||0,blocked=(c.blocked||0)+(c.login_expired||0),failed=(c.failed||0)+(c.unconfirmed||0);
  const skipped=(c.not_signed||0)+(c.disabled||0)+(c.cancelled||0)+(c.unsupported||0),cooldown=(c.cooldown||0)+(c.rate_limited||0)+(c.throttled||0),early=c.skipped_before_0001||0,oldDay=c.server_day_mismatch||0;
  if(autoJob.status==='completed'&&autoJob.total>0){if(blocked===autoJob.total)phase='需人工处理';else if((c.unconfirmed||0)===autoJob.total)phase='等待核实';else if(!signed&&!newSign)phase=oldDay===autoJob.total?'等待服务器日期':early===autoJob.total?'尚未到00:01':cooldown?'等待重试':'检查失败';else if(blocked||failed||cooldown||early||oldDay)phase='部分未完成';}
  const result=phase+' · 已签'+signed+' 新签'+newSign+' 阻塞'+blocked+' 失败'+failed+' 跳过'+skipped+' 冷却'+cooldown+(early?' 早'+early:'')+(oldDay?' 旧'+oldDay:'');
  const compact=phase+' 已'+signed+' 新'+newSign+' 阻'+blocked+' 败'+failed+' 跳'+skipped+' 冷'+cooldown+(early?' 早'+early:'')+(oldDay?' 旧'+oldDay:'');
  const narrow=phase+' 完'+(signed+newSign)+' 败'+(blocked+failed)+' 跳'+(skipped+cooldown+early+oldDay);
  const description=phase+' · 已签 '+signed+' · 新签到 '+newSign+' · 阻塞 '+blocked+' · 失败/未确认 '+failed+' · 未签/跳过 '+skipped+' · 冷却 '+cooldown+(early?' · 尚未到00:01 '+early:'')+(oldDay?' · 服务器日期未到今日 '+oldDay:'');
  statusLines('autoCheckinStatus',title,result,[compact,narrow],description);
 }
 async function pollAuto(){
  if(autoPolling)return;autoPolling=true;
  try{const value=await api('/api/checkin/auto/status');if((value.ledger?.statusRevision||0)<(ledger.statusRevision||0)){if(autoJob&&['queued','running'].includes(autoJob.status)&&Date.now()<autoUntil)autoTimer=setTimeout(pollAuto,500);return;}autoJob=value.job;if(value.ledger)applyLedger(value.ledger);paintAuto();paint();autoFailures=0;
   if(autoJob&&['queued','running'].includes(autoJob.status)&&Date.now()<autoUntil)autoTimer=setTimeout(pollAuto,1000);
  }catch(e){if(++autoFailures<3&&Date.now()<autoUntil)autoTimer=setTimeout(pollAuto,2000);else toast('自动签到进度暂不可用：'+e.message);}
  finally{autoPolling=false;}
 }
 async function startAuto(trigger,id,roleKeys){
  autoLocalNotice='';
  try{const value=await api('/api/checkin/auto/start',{trigger,requestId:id.replaceAll('-',''),roleKeys:[...(roleKeys||[])]});autoJob=value.job;if(value.ledger)applyLedger(value.ledger);autoUntil=Date.now()+600000;autoFailures=0;clearTimeout(autoTimer);paintAuto();paint();if(autoJob&&['queued','running'].includes(autoJob.status))autoTimer=setTimeout(pollAuto,500);}
  catch(e){const el=$('#autoCheckinStatus');el.hidden=false;statusLines('autoCheckinStatus','自动签到','检查未启动：'+e.message);}
 }
 window.addEventListener('pagehide',()=>clearTimeout(autoTimer));
 async function run(keys,op){
  const known=new Set(roleRows().map(x=>x.key)),frozen=Object.freeze([...new Set(keys)].filter(k=>known.has(k)));
  if(inFlight||!frozen.length){if(!frozen.length)toast('请先选择角色');return;}
  inFlight=true;revision++;paint();
  try{const result=await api('/api/checkin/batch/start',{roleKeys:[...frozen],operation:op,selectionEpoch});activeBatch=result.job;if(op==='info')acceptAuto(result);if(result.ledger)applyLedger(result.ledger);if(!activeBatch)throw Error('签到批次未创建');await pollBatch();}
  catch(e){inFlight=false;toast(e.message);paint();}
 }
 $('#cancelCheckinBatch').onclick=async()=>{if(activeBatch)try{await api('/api/checkin/batch/cancel',{operationId:activeBatch.operationId});await pollBatch();}catch(e){toast(e.message);}};
 $('#checkinRefresh').onclick=()=>run(selectedKeys(),'info');$('#checkinSign').onclick=()=>run(selectedKeys(),'sign');$('#checkinResign').onclick=()=>prepareResign();
 const narrow=matchMedia('(max-width:600px)');
 const drawer=$('#pageNav'),menu=$('#menuToggle'),backdrop=$('#navBackdrop');let desktopOpen=true;
 try{desktopOpen=localStorage.getItem('hoyo-nav-open')!=='false';}catch{}
 function setDrawer(open,focus=false){
  document.body.classList.toggle('nav-open',open);menu.setAttribute('aria-expanded',String(open));menu.setAttribute('aria-label',open?'收起导航':'展开导航');drawer.inert=!open;drawer.setAttribute('aria-hidden',String(!open));backdrop.hidden=!open||!narrow.matches;
  if(!narrow.matches){desktopOpen=open;try{localStorage.setItem('hoyo-nav-open',String(open));}catch{}}
  if(focus)(open?drawer.querySelector('[aria-current=page]')||drawer.querySelector('button'):menu).focus();
 }
 function closeDrawer(){if(!document.body.classList.contains('nav-open'))return false;setDrawer(false,true);return true;}
 menu.onclick=()=>setDrawer(!document.body.classList.contains('nav-open'),true);backdrop.onclick=()=>setDrawer(false,true);
 narrow.addEventListener('change',()=>setDrawer(narrow.matches?false:desktopOpen));setDrawer(narrow.matches?false:desktopOpen);
 document.addEventListener('keydown',e=>{
  if(e.key==='Escape'&&!document.querySelector('dialog[open]')&&closeDrawer())e.preventDefault();
  if(e.key==='Tab'&&narrow.matches&&document.body.classList.contains('nav-open')&&!document.querySelector('dialog[open]')){
   const nodes=[menu,...drawer.querySelectorAll('button')],i=nodes.indexOf(document.activeElement);if(e.shiftKey&&i<=0){nodes.at(-1).focus();e.preventDefault();}else if(!e.shiftKey&&(i<0||i===nodes.length-1)){nodes[0].focus();e.preventDefault();}
  }
 });
 document.addEventListener('toggle',e=>{if(e.target.matches?.('[data-rewards-key]')&&e.target.isConnected)expandedRewards.set(e.target.dataset.rewardsKey,e.target.open);},true);
 async function discardQuote(){const old=quote;quote=null;quoteGeneration++;if(old)await api('/api/checkin/resign/cancel',{confirmationId:old.confirmationId}).catch(()=>{});}
 async function prepareResign(){
  const chosen=selectedKeys();if(inFlight||!chosen.length)return;if(chosen.length!==1){toast('为避免多笔消费，请仅选择一个角色，逐角色确认补签；每次150米游币，每月最多3次。');return;}const key=chosen[0];selectedKey=key;const epoch=selectionEpoch,gen=++quoteGeneration;inFlight=true;paint();
  try{
   const value=await api('/api/checkin/resign/prepare',{roleKey:key});
   if(gen!==quoteGeneration||selectionEpoch!==epoch||selectedKeys().length!==1||selectedKeys()[0]!==key){if(value.confirmationId)api('/api/checkin/resign/cancel',{confirmationId:value.confirmationId}).catch(()=>{});return;}
   if(!value.eligible||value.currency!=='miyou_coin'||value.currencyVerified!==true||!Number.isInteger(value.cost)||Number.isInteger(value.balance)&&value.balance<value.cost||!value.confirmationId){
    const messages={official_explicit_conflict:'官方明确返回不同费用，暂不消费',missing_required_evidence:'尚缺可靠的补签资格信息，请导出本次诊断；未提交消费',quota_exhausted:'今日或本月补签次数已用尽',risk_control:'当前补签请求需本人验证',network_error:'补签资格查询网络失败，未提交消费',qualification_unknown:'尚未取得必要补签资格，暂不消费',rule_changed:'官方费用或额度与标准规则不一致，暂不消费',unsupported:'该活动不支持补签',currency_unconfirmed:'官方规则未能明确确认米游币单位和费用，暂不消费',inconsistent_info:'官方签到字段相互矛盾，暂不消费',blocked:'当前补签请求需本人验证',login_expired:'登录已失效，请重新登录',failed:'官方未接受补签资格查询',fee_or_balance_unknown:'费用或余额尚无法确认',sign_today_first:'请先完成今日签到',no_missed_days:'当前不需补签',limit_reached:'补签次数已用尽',insufficient_balance:'米游币余额不足',server_day_mismatch:'服务器日期尚无法确认',previous_result_unconfirmed:'上次补签结果不确定，请先在官方核实；不会重复扣费'};
    toast((messages[value.reason]||'官方资格尚无法确认')+(Number.isInteger(value.code)?'（'+value.code+'）':''));return;
   }
   quote={...value,roleKey:key};const target=roleRows().find(x=>x.key===key);
   $('#resignOwner').textContent=target.a.label+' · '+names[target.r.game]+' · '+target.r.uid;
   $('#resignQuote').textContent='将为「'+(target.r.nickname||target.r.uid)+'」补签 1 次，预计消耗 '+value.cost+' 米游币。每月最多补签 3 次，是否继续？'+(Number.isInteger(value.balance)?' 当前米游币 '+value.balance:'');
   $('#resignDialog').showModal();$('#cancelResign').focus();
  }catch(e){toast(e.message);}finally{inFlight=false;paint();}
 }
 $('#cancelResign').onclick=()=>$('#resignDialog').close();$('#resignDialog').addEventListener('close',()=>discardQuote());
 $('#confirmResign').onclick=async()=>{
  const intent=quote;if(!intent||inFlight||selectedKeys().length!==1||selectedKeys()[0]!==intent.roleKey)return;quote=null;quoteGeneration++;$('#resignDialog').close();inFlight=true;revision++;paint();
  try{applyLedger(await api('/api/checkin/resign/confirm',{roleKey:intent.roleKey,confirmationId:intent.confirmationId,confirmed:true}));toast('补签操作已处理，请查看服务端回读结果');}
  catch(e){toast(e.message);await read().catch(()=>{});}finally{inFlight=false;paint();}
 };
 window.HoYoCheckin={startAuto,localAutoSkipped(){autoLocalNotice='本地资源估算不联网，自动签到本次未检查；请使用联网刷新或签到状态刷新。';paintAuto();},render:paint,renderAuto,read,closeDrawer,
 async follow(keys){if(!loaded)await read().catch(()=>{});followKeys=new Map(keys.filter(key=>rowFor(key).enabled).map(key=>[key,rowFor(key).checkedAt||0]));followUntil=Date.now()+180000;nextLocalRead=0;followFailures=0;},
 stopFollowing(){followKeys.clear();},
 tick(){
  if(state.accounts.length&&Date.now()>=nextAutoRead&&!autoPolling){nextAutoRead=Date.now()+5000;pollAuto();}
  if(lastDay!==serverDay()){lastDay=serverDay();paint();}
  const now=Date.now();if(now>followUntil){followKeys.clear();return;}
  if(!followKeys.size||loading||inFlight||now<nextLocalRead)return;nextLocalRead=now+2000;
  read().then(()=>{const known=new Set(roleRows().map(x=>x.key));for(const [key,before] of followKeys){const row=rowFor(key);if(!known.has(key)||!row.enabled||row.checkedAt!==before&&!['signing','verifying_result','submitting'].includes(row.state))followKeys.delete(key);}}).catch(()=>{if(++followFailures>=3)followKeys.clear();});
 },openAccount:async id=>{
  accountTarget=id;const a=state.accounts.find(x=>x.id===id);if(!a)return;
  $('#checkinAccountName').textContent=(a.label||'米游社')+' · 通行证 UID '+a.id;
  $('#checkinAccountTargets').innerHTML=a.roles.map(r=>'<label><input type="checkbox" data-checkin-target="'+esc(roleKey(a,r))+'" checked>'+esc(names[r.game])+' · '+esc(r.uid)+' · '+esc(r.server)+'</label>').join('');
  $('#accountCheckinDialog').showModal();await read().catch(e=>toast(e.message));
 }};
 document.addEventListener('click',e=>{
  const nav=e.target.closest('[data-page]');if(nav){page=nav.dataset.page;pageTitle();$('#resourcePage').hidden=page!=='resources';$('#checkinPage').hidden=page!=='checkin';document.querySelectorAll('[data-page]').forEach(b=>b.setAttribute('aria-current',b===nav?'page':'false'));discardQuote();if(narrow.matches)setDrawer(false,true);if(page==='checkin'){detail();read().catch(e=>toast(e.message));}return;}
  const collapse=e.target.closest('[data-checkin-collapse]');if(collapse){const id=collapse.dataset.checkinCollapse;expandedAccounts.set(id,expandedAccounts.get(id)===false);paint();}
  if(e.target.closest('[data-checkin-clear]'))selectKeys([]);
  const only=e.target.closest('[data-checkin-only]');if(only)selectKeys([only.dataset.checkinOnly]);
 });
 $('#confirmAccountCheckin').onclick=()=>{const own=new Set(roleRows().filter(x=>x.a.id===accountTarget).map(x=>x.key));run([...checked()].filter(x=>own.has(x)),'sign');};
 document.addEventListener('change',async e=>{
  if(e.target.matches('[data-checkin-all]')){changeSelection(e.target.checked?{mode:'all'}:{mode:'subset',roleKeys:[]});return;}
  if(e.target.matches('[data-checkin-select],[data-checkin-owner]')){const chosen=new Set(selectedKeys()),keys=e.target.dataset.checkinSelect?[e.target.dataset.checkinSelect]:roleRows().filter(x=>x.a.id===e.target.dataset.checkinOwner).map(x=>x.key);for(const k of keys)e.target.checked?chosen.add(k):chosen.delete(k);selectKeys([...chosen]);return;}
  if(e.target.matches('[data-auto-checkin-all]')){updateMode(roleRows().map(x=>x.key),e.target.checked);return;}
  if(e.target.dataset.autoCheckin)updateMode([e.target.dataset.autoCheckin],e.target.checked);

 });
 window.addEventListener('hoyo-account-update',()=>{discardQuote();loaded=false;read().catch(()=>{});});
 window.addEventListener('pagehide',()=>{discardQuote();clearTimeout(batchTimer);if(activeBatch?.status==='running')api('/api/checkin/batch/cancel',{operationId:activeBatch.operationId}).catch(()=>{});});
 window.addEventListener('hoyo-resume',()=>read().catch(()=>{})); // local ledger only, no official request
 pollBatch().catch(()=>{});
 read().catch(()=>{setTimeout(()=>read().catch(()=>{}),1000);});
})();
