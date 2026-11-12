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
  book.configurationRevision=0;let staleNextRead=false,profileJob=null,profileHold=false;
  let pendingSmsSend=null,batch=null,hold=false;
  function response(p,b){
   calls.push({path:p,body:b});
   if(p==='/api/auth/sms/send')return new Promise(resolve=>pendingSmsSend=resolve);
   if(p==='/api/auth/sms/status')return {expired:true};
   if(p==='/api/checkin/batch/cancel'){batch.status='cancelled';return {job:batch,ledger:book};}
   if(p==='/api/checkin/batch/status')return {job:batch,ledger:book};
   if(p==='/api/checkin/batch/start'){response('/api/checkin/run',b);batch={operationId:'fixture-batch',status:hold?'running':'completed',total:b.roleKeys.length,done:hold?0:b.roleKeys.length,roles:[]};return {job:batch,ledger:book};}
   if(p==='/api/session')return {csrfToken:'fixture'};
   if(p==='/api/checkin'){if(staleNextRead){staleNextRead=false;return {configurationRevision:0,roles:{}};}return book;}
   if(p==='/api/accounts/profile/batch/start'){profileJob={operationId:'profile-fixture',status:profileHold?'running':'completed',total:3,roles:7,done:profileHold?0:3,accounts:state.accounts.map(a=>({accountId:a.id,state:a.id==='222'?'partial':'completed'}))};return {job:profileJob,state};}
   if(p==='/api/accounts/profile/batch/status')return {job:profileJob,state};
   if(p==='/api/accounts/profile/batch/cancel'){profileJob.status='cancelled';return {job:profileJob,state};}
   if(p==='/api/checkin/resign/prepare')return {eligible:true,currency:'miyou_coin',currencyVerified:true,cost:150,balance:500,confirmationId:'fixture-quote',expiresAt:Date.now()+60000};
   if(p==='/api/checkin/resign/confirm'){book.roles[b.roleKey+'|CN'].resignState='confirmed';book.roles[b.roleKey+'|CN'].info.total=15;return book;}
   if(p==='/api/checkin/resign/cancel')return {ok:true};
   if(p==='/api/checkin/preferences'){book.configurationRevision++;for(const key of b.roleKeys)book.roles[key+'|CN']={...book.roles[key+'|CN'],enabled:b.enabled};return book;}
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

  assert.equal(await page.locator('#pageTitle').innerText(),'资源总览');assert.equal(await page.locator('#pageTitle svg').count(),1);
  const keys=state.accounts.flatMap(a=>a.roles.map(r=>a.id+'|'+r.game+':'+r.uid+':'+r.serverId));
  if(width<760&&await page.locator('#menuToggle').getAttribute('aria-expanded')!=='true')await page.locator('#menuToggle').click();await page.locator('[data-page="checkin"]').click();
  assert.equal(await page.locator('#pageTitle').getAttribute('aria-label'),'签到明细');assert.equal(await page.locator('#checkinResign + #chooseCheckinMode').count(),1);
  await page.locator('#chooseCheckinMode').click();await page.waitForFunction(()=>document.querySelectorAll('#checkinModeOptions [data-auto-checkin]').length===7);
  const modes=page.locator('#checkinModeOptions'),all=modes.locator('[data-auto-checkin-all]');
  assert.equal(await modes.locator('fieldset').count(),3);assert.equal(await modes.locator('input:checked').count(),0);
  await modes.locator('[data-auto-checkin]').first().check();await page.waitForFunction(()=>!document.querySelector('#checkinModeOptions input').disabled);
  assert.equal(await all.evaluate(x=>x.indeterminate),true);assert.equal(await page.locator('[data-checkin-select]:checked').count(),7);
  await all.check();await page.waitForFunction(()=>!document.querySelector('#checkinModeOptions input').disabled);assert.equal(await modes.locator('[data-auto-checkin]:checked').count(),7);
  assert.deepEqual(calls.filter(x=>x.path==='/api/checkin/preferences').at(-1).body.roleKeys.slice().sort(),keys.slice().sort());
  await page.locator('#checkinModeDialog .close').click();await page.locator('#filter').click();assert.equal(await page.locator('#autoCheckinOptions [data-auto-checkin]:checked').count(),7);
  await page.locator('#autoCheckinOptions [data-auto-checkin-all]').uncheck();await page.waitForFunction(()=>!document.querySelector('#autoCheckinOptions input').disabled);
  assert.equal(await page.locator('#autoCheckinOptions input:checked').count(),0);await page.locator('#filterClear').click();await page.locator('#filterDialog .close').click();assert.equal(await page.locator('[data-checkin-select]:checked').count(),7);
  await page.locator('#chooseCheckinMode').click();await all.check();await page.waitForFunction(()=>!document.querySelector('#checkinModeOptions input').disabled);
  await page.locator('#checkinModeDialog .close').click();staleNextRead=true;await page.locator('#chooseCheckinMode').click();await page.waitForFunction(()=>document.querySelectorAll('#checkinModeOptions [data-auto-checkin]:checked').length===7);
  await page.locator('#checkinModeDialog .close').click();assert.equal(await page.locator('#autoCheckinOptions [data-auto-checkin]:checked').count(),7);
  await page.evaluate(()=>{state.accounts[1].roles.push({game:'starrail',uid:'100000002',serverId:'prod_gf_cn',server:'新增区服'});render();});
  await page.locator('#chooseCheckinMode').click();assert.equal(await modes.locator('[data-auto-checkin]').count(),8);assert.equal(await modes.locator('[data-auto-checkin]:checked').count(),7);assert.equal(await all.evaluate(x=>x.indeterminate),true);
  await page.locator('#checkinModeDialog .close').click();await page.evaluate(()=>{state.accounts[1].roles.pop();state.accounts=state.accounts.filter(a=>a.id!=='333');render();});
  await page.locator('#chooseCheckinMode').click();assert.equal(await modes.locator('[data-auto-checkin]').count(),4);await page.locator('#checkinModeDialog .close').click();
  assert.equal(calls.filter(x=>x.path==='/api/checkin/batch/start'||x.path==='/api/checkin/run'||x.path==='/api/checkin/resign/prepare'||x.path==='/api/checkin/resign/confirm').length,0);
  await page.locator('#settings').click();await page.locator('#completeProfile').click();await page.waitForFunction(()=>document.querySelector('#profileBatchStatus').textContent.includes('本轮结束'));
  assert.match(await page.locator('#profileBatchStatus').innerText(),/3 个通行证 \/ 7 个角色/);assert.match(await page.locator('#profileBatchStatus').innerText(),/部分未取得 1/);assert.deepEqual(calls.find(x=>x.path==='/api/accounts/profile/batch/start').body,{});
  profileHold=true;await page.locator('#completeProfile').click();await page.locator('#cancelProfileBatch').waitFor();await page.locator('#cancelProfileBatch').click();await page.waitForFunction(()=>document.querySelector('#profileBatchStatus').textContent.includes('已取消'));
  await page.locator('#settingsDialog .close').click();await page.locator('#chooseCheckinMode').focus();assert.equal(await page.locator('#chooseCheckinMode').evaluate(x=>x===document.activeElement),true);
  const dimensions=await page.evaluate(()=>({width:innerWidth,scroll:document.documentElement.scrollWidth}));assert.ok(dimensions.scroll<=width+1,JSON.stringify(dimensions));
  assert.deepEqual(errors,[]);fs.mkdirSync('work/stage40',{recursive:true});await page.screenshot({path:'work/stage40/'+platform+'-mode-'+width+'.png',fullPage:true});evidence.push({platform,width,height,modes:7,groups:3,lateLedgerIgnored:true,selectionIndependent:true,newRoleManual:true,deletedAccountIgnored:true,profileProgressAndCancel:true,officialPosts:0,dimensions,errors});await page.close();
 }
 fs.writeFileSync('work/stage40/ui-results.json',JSON.stringify(evidence,null,2));console.log('PASS stage40: 10 platform/viewports, two mode entry points, all/none/mixed, stale ledger, account/role changes, titles, profile progress/cancel, zero sign/resign');
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
