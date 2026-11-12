const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
(async()=>{
 const browser=await chromium.launch({headless:true,executablePath:process.env.CHROME_PATH});
 const evidence=[];const today=new Date(Date.now()+8*3600000).toISOString().slice(0,10);
 try{
 for(const platform of ['android','windows'])for(const [width,height] of [[360,740],[393,780],[412,820],[1280,900],[1024,720]]){
  const root=platform==='android'?path.resolve(process.cwd(),'web'):path.resolve(process.env.HOYO_WINDOWS_UI_ROOT||'../HoYoReminder/src/frontend/shared-ui');
  const page=await browser.newPage({viewport:{width,height},deviceScaleFactor:width===1024?1.25:1,isMobile:width<600,hasTouch:width<600});
  page.setDefaultTimeout(8000);const calls=[],errors=[];page.on('pageerror',e=>errors.push(e.message));
  const t=Date.now()-40000;
  const account=id=>{const roles=(id==='222'?['genshin']:['genshin','starrail','zenless']).map(g=>({game:g,uid:g==='zenless'?'10000001':'100000001',serverId:g==='genshin'?'cn_gf01':'prod_gf_cn',server:'天空岛 / 新艾利都',nickname:'fixture nickname',level:60}));
   return {id,label:'测试通行证'+id,roles,games:roles.map(r=>({id:r.game,roleUid:r.uid,serverId:r.serverId,status:'ok',current:100,max:200,updated:t,lastSuccessfulFetchAt:t,dailyCurrent:4,dailyMax:4,weeklyRemaining:3,weeklyMax:3,cycleCurrent:100,cycleMax:100,task:'已完成',resourceSnapshot:{version:1,source:'server_snapshot',serverResourceValue:100,resourceMax:200,observedAtUtc:t,periodSeconds:480,remainingSeconds:47540,phaseAvailable:true}}))};};
  let state={accounts:['111','222','333'].map(account),selectedId:'333',uiPreferences:{order:['111','222','333'],expanded:['111']}},book={version:1,roles:{}};
  const autoKeys=['111|starrail:100000001:prod_gf_cn','222|genshin:100000001:cn_gf01','333|zenless:10000001:prod_gf_cn'];for(const key of autoKeys)book.roles[key+'|CN']={enabled:true,state:'unknown'};state.uiPreferences={...state.uiPreferences,accountIds:['111'],roleKeys:['111|genshin:100000001:cn_gf01']};let autoJob=null,resourceFails=false;
  let pendingSmsSend=null,batch=null,hold=false;
  function response(p,b){
   calls.push({path:p,body:b});
   if(p==='/api/auth/sms/send')return new Promise(resolve=>pendingSmsSend=resolve);
   if(p==='/api/auth/sms/status')return {expired:true};
   if(p==='/api/checkin/batch/cancel'){batch.status='cancelled';return {job:batch,ledger:book};}
   if(p==='/api/checkin/batch/status')return {job:batch,ledger:book};
   if(p==='/api/checkin/batch/start'){response('/api/checkin/run',b);batch={operationId:'fixture-batch',status:hold?'running':'completed',total:b.roleKeys.length,done:hold?0:b.roleKeys.length,roles:[]};if(b.operation==='info'){const enabled=autoKeys.filter(k=>book.roles[k+'|CN']?.enabled);autoJob={operationId:batch.operationId,trigger:'checkin_status_manual_refresh',status:'completed',total:enabled.length,done:enabled.length,counts:{already_signed:enabled.length}};}return {job:batch,autoJob,ledger:book};}
   if(p==='/api/session')return {csrfToken:'fixture'};
   if(p==='/api/checkin/auto/start'){const keys=autoKeys.filter(k=>book.roles[k+'|CN']?.enabled);autoJob={operationId:'a'.repeat(32),trigger:b.trigger,status:'completed',total:keys.length,done:keys.length,counts:{already_signed:keys.length}};return {job:autoJob,ledger:book};}
   if(p==='/api/checkin/auto/status')return {job:autoJob,ledger:book};
   if(p==='/api/accounts/refresh/start'){state.refresh={refreshId:b.refreshId,status:'completed',elapsedMs:12,trigger:'manual',roles:b.roleKeys.map(k=>({accountId:k.split('|')[0],game:k.split('|')[1].split(':')[0],roleUid:k.split(':')[1],server:k.split(':')[2],state:resourceFails?'failed':'success'}))};return state;}
   if(p==='/api/accounts/refresh/status')return state;
   if(p==='/api/resources/estimate')return {ok:true};
   if(p==='/api/checkin')return book;
   if(p==='/api/checkin/resign/prepare')return {eligible:true,currency:'miyou_coin',currencyVerified:true,cost:150,balance:500,confirmationId:'fixture-quote',expiresAt:Date.now()+60000};
   if(p==='/api/checkin/resign/confirm'){book.roles[b.roleKey+'|CN'].resignState='confirmed';book.roles[b.roleKey+'|CN'].info.total=15;return book;}
   if(p==='/api/checkin/resign/cancel')return {ok:true};
   if(p==='/api/checkin/preferences'){for(const key of b.roleKeys)book.roles[key+'|CN']={...book.roles[key+'|CN'],enabled:b.enabled};return book;}
   if(p==='/api/checkin/run'){for(const key of b.roleKeys)book.roles[key+'|CN']={...book.roles[key+'|CN'],state:'already_signed',info:{today,isSign:true,total:14},checkedAt:t,home:{month:'2026-10',days:31,awards:Array.from({length:31},(_,i)=>({name:'很长的测试奖励名称 '+(i+1),count:i+1,icon:''}))}};return book;}
   if(p==='/api/ui/preferences'){state.uiPreferences=b;return state;}
   return state;
  }
  const host=platform==='android'?'appassets.androidplatform.net':'127.0.0.1:18671',scheme=platform==='android'?'https':'http';
  await page.route('**/*',route=>{
   const u=new URL(route.request().url());if(u.host!==host)return route.abort();
   if(u.pathname.startsWith('/api/'))return route.fulfill({json:response(u.pathname,route.request().postDataJSON?.()||{})});
   let name=path.basename(u.pathname)||'index.html';if(name==='profile-image'||name==='reward-image')return route.abort();
   const f=path.join(root,name);if(!fs.existsSync(f))return route.abort();
   return route.fulfill({body:fs.readFileSync(f),contentType:name.endsWith('.html')?'text/html':name.endsWith('.css')?'text/css':'text/javascript'});
  });
  if(platform==='android'){
   await page.exposeFunction('__stage37Request',response);
   await page.addInitScript(()=>window.HoYoNative={postMessage(raw){const r=JSON.parse(raw);window.__stage37Request(r.path,r.body).then(result=>HoYoNative.onmessage({data:JSON.stringify({id:r.id,ok:true,result})}));}});
  }
  await page.goto(scheme+'://'+host+(platform==='android'?'/assets/web/index.html':'/index.html'));
  await page.waitForFunction(()=>document.querySelector('#groups').getAttribute('aria-busy')==='false');


  await page.evaluate(()=>{clearInterval(profilePoll);stateRevision++;window.__now=Date.now();Date.now=()=>__now;preferences.accountIds=['111'];preferences.roleKeys=null;expanded.add('111');for(const a of state.accounts)for(const g of a.games){const max={genshin:200,starrail:300,zenless:320}[g.id],p=g.id==='genshin'?480:360;g.max=max;g.current=max-2;g.resourceSnapshot={version:1,source:'server_snapshot',serverResourceValue:max-2,resourceMax:max,observedAtUtc:Date.now(),periodSeconds:p,remainingSeconds:p+60,phaseAvailable:true};}render();});
  assert.equal(await page.locator('.card').count(),3);assert.equal(await page.locator('#viewStatus .status-heading').count(),0);assert.doesNotMatch(await page.locator('#viewStatus').innerText(),/资源总览刷新方式/);
  const mutations=()=>calls.filter(x=>['/api/checkin/auto/start','/api/accounts/refresh/start','/api/checkin/run'].includes(x.path)).length;
  const first=await page.locator('[data-resource-eta]').allTextContents(),n=mutations();await page.evaluate(()=>{__now+=3000;updateEstimates();updateEstimates();});assert.deepEqual(await page.locator('[data-resource-eta]').allTextContents(),first);assert.equal(mutations(),n);
  await page.locator('#refresh').click();await page.waitForFunction(()=>!document.querySelector('#refresh').disabled);assert.match(await page.locator('#viewStatus').innerText(),/已刷新 3 个角色/);assert.equal(await page.locator('#autoCheckinStatus').evaluate(e=>e.parentElement.id),'resourcePage');
  resourceFails=true;await page.locator('#refresh').click();await page.waitForFunction(()=>!document.querySelector('#refresh').disabled);assert.match(await page.locator('#viewStatus').innerText(),/已刷新 0 个角色.*失败3/);resourceFails=false;
  await page.evaluate(()=>{refreshJob={status:'cancelled',roles:[{state:'success'},{state:'cancelled'}]};resourceStatus(3);});assert.match(await page.locator('#viewStatus').innerText(),/已取消.*成功1.*取消1/);
  await page.evaluate(()=>{refreshJob=null;preferences.mode='local';localRefreshResult='';resourceStatus(targets().length);});const before=calls.filter(x=>x.path==='/api/checkin/auto/start').length;await page.locator('#refresh').click();await page.waitForFunction(()=>document.querySelector('#viewStatus').textContent.includes('本地估算 · 更新3'));assert.equal(calls.filter(x=>x.path==='/api/checkin/auto/start').length,before);
  await page.evaluate(()=>{preferences.accountIds=['111','222'];expanded.add('222');const other=state.accounts.find(x=>x.id==='222').games[0];other.current=50;other.resourceSnapshot.serverResourceValue=50;other.resourceSnapshot.observedAtUtc=Date.now();other.resourceSnapshot.remainingSeconds=(other.max-51)*other.resourceSnapshot.periodSeconds+20;render();});assert.equal(await page.locator('.card').count(),4);assert.match(await page.locator('.card[data-role-key^="222|"] [data-resource-value]').innerText(),/^50 /);
  await page.evaluate(()=>{preferences.accountIds=['111'];preferences.mode='server';refreshJob=null;localRefreshResult='';for(const g of state.accounts[0].games){const max={genshin:200,starrail:300,zenless:320}[g.id],p=g.id==='genshin'?480:360;g.max=max;g.current=max-2;g.resourceSnapshot={version:1,source:'server_snapshot',serverResourceValue:max-2,resourceMax:max,observedAtUtc:Date.now(),periodSeconds:p,remainingSeconds:p+60,phaseAvailable:true};}render();});
  const fonts=await page.locator('.card *,#viewStatus,#viewStatus *').evaluateAll(xs=>xs.map(el=>({el,size:parseFloat(getComputedStyle(el).fontSize),line:getComputedStyle(el).lineHeight})).map(({el,size,line},i)=>{el.dataset.scaleId=String(i);return {id:i,size,line};}));const geometries=[];
  for(const scale of [1,1.25,2]){
   await page.evaluate(({fonts,scale})=>{for(const f of fonts){const el=document.querySelector('[data-scale-id="'+f.id+'"]');el.style.fontSize=f.size*scale+'px';if(f.line!=='normal')el.style.lineHeight=parseFloat(f.line)*scale+'px';}document.querySelector('#viewStatus').fitStatusResult?.();document.querySelector('#notice').hidden=true;},{fonts,scale});await page.emulateMedia({colorScheme:scale===1?'light':'dark'});
   const geometry=await page.evaluate(()=>[...document.querySelectorAll('.card')].map(c=>{const n=c.querySelector('[data-resource-next]'),e=c.querySelector('[data-resource-eta]'),nr=n.getBoundingClientRect(),er=e.getBoundingClientRect(),tr=c.querySelector('.tasks').getBoundingClientRect(),range=document.createRange();range.selectNodeContents(document.querySelector('#viewStatus .status-result'));return {next:n.textContent,eta:e.textContent,sameLine:Math.abs(nr.top-er.top)<1,visible:nr.width>0&&er.width>0,overlap:!(nr.bottom<=tr.top+1||nr.top>=tr.bottom-1||nr.right<=tr.left+1||nr.left>=tr.right-1),overflow:c.scrollWidth>c.clientWidth+1,hintLines:range.getClientRects().length};}));
   for(const g of geometry){assert.ok(g.visible);assert.equal(g.overlap,false,JSON.stringify({platform,width,scale,g}));assert.equal(g.overflow,false,JSON.stringify({platform,width,scale,g}));assert.match(g.next,/后回复1点/);assert.match(g.eta,/回满/);if(scale<=1.25){assert.ok(g.sameLine,JSON.stringify({platform,width,scale,g}));assert.equal(g.hintLines,1);}}assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1));geometries.push({scale,cards:geometry});fs.mkdirSync('work/stage44',{recursive:true});if([393,1280].includes(width))await page.screenshot({path:'work/stage44/'+platform+'-'+width+'-font'+scale+'.png',fullPage:true});
  }
  await page.evaluate(()=>{const g=state.accounts[0].games;g[0].current=g[0].max;g[0].resourceSnapshot.serverResourceValue=g[0].max;g[1].resourceSnapshot.phaseAvailable=false;render();});assert.equal(await page.locator('[data-resource-next]').first().innerText(),'已回满');assert.equal(await page.locator('[data-resource-eta]').first().innerText(),'无需恢复');assert.equal(await page.locator('[data-resource-eta]').nth(1).innerText(),'回满时间不可用');
  assert.ok(await page.evaluate(()=>document.querySelector('#autoCheckinStatus').getBoundingClientRect().top>=document.querySelector('#groups').getBoundingClientRect().bottom-1));
  if(width<600&&await page.locator('#menuToggle').getAttribute('aria-expanded')!=='true')await page.locator('#menuToggle').click();await page.waitForTimeout(200);await page.waitForFunction(()=>Math.abs(document.querySelector('#pageNav').getBoundingClientRect().top-document.querySelector('header').getBoundingClientRect().bottom)<1);await page.locator('[data-page="checkin"]').click();assert.equal(await page.locator('#autoCheckinStatus').evaluate(e=>e.parentElement.id),'');if(width<600)await page.locator('#menuToggle').click();await page.locator('[data-page="resources"]').click();assert.equal(await page.locator('#autoCheckinStatus').evaluate(e=>e.parentElement.id),'resourcePage');assert.equal(await page.locator('#autoCheckinStatus').count(),1);
  assert.deepEqual(errors,[]);evidence.push({platform,width,height,geometries,noTimerMutationRequests:true,singleHint:true,footerPreserved:true,errors});await page.close();
 }
 fs.writeFileSync('work/stage44/ui-results.json',JSON.stringify(evidence,null,2));console.log('PASS stage44 10 platform/viewports x 3 font sizes: immutable dual timers, single-line resource states, footer/drawer, no timer mutation requests');
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
