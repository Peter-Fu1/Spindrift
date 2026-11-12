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
  for(const role of state.accounts[0].roles)book.roles['111|'+role.game+':'+role.uid+':'+role.serverId+'|CN']={enabled:true,state:'unknown'};
  let pendingSmsSend=null,batch=null,hold=false;
  function response(p,b){
   calls.push({path:p,body:b});
   if(p==='/api/auth/sms/send')return new Promise(resolve=>pendingSmsSend=resolve);
   if(p==='/api/auth/sms/status')return {expired:true};
   if(p==='/api/checkin/batch/cancel'){batch.status='cancelled';return {job:batch,ledger:book};}
   if(p==='/api/checkin/batch/status')return {job:batch,ledger:book};
   if(p==='/api/checkin/batch/start'){response('/api/checkin/run',b);batch={operationId:'fixture-batch',status:hold?'running':'completed',total:b.roleKeys.length,done:hold?0:b.roleKeys.length,roles:[]};return {job:batch,ledger:book};}
   if(p==='/api/session')return {csrfToken:'fixture'};
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
  assert.equal(await page.locator('#filter svg').count(),1);
  assert.equal(await page.locator('.card:visible').count(),3);
  const bottom=await page.locator('.card:visible').last().evaluate(x=>x.getBoundingClientRect().bottom);
  assert.ok(bottom>100&&bottom<=height,platform+width+' three cards exceed viewport '+bottom);

  if(width<600)await page.locator('#menuToggle').click();await page.locator('[data-page=checkin]').click();
  const allKeys=state.accounts.flatMap(a=>a.roles.map(r=>a.id+'|'+r.game+':'+r.uid+':'+r.serverId));
  assert.equal(allKeys.length,7);assert.equal(await page.locator('.checkin-group').count(),3);assert.equal(await page.locator('[data-checkin-select]:checked').count(),7);assert.equal(await page.locator('details[open]').count(),0);
  const runCalls=()=>calls.filter(x=>x.path==='/api/checkin/batch/start');assert.equal(runCalls().length,0);
  assert.ok((await page.locator('#checkinRefresh').boundingBox()).y<(await page.locator('#checkinChoices').boundingBox()).y);
  const waitEnd=()=>page.waitForFunction(()=>document.querySelector('#checkinBatchStatus').textContent.includes('已结束'));
  await page.locator('[data-checkin-only]').first().click();await page.locator('#checkinRefresh').click();await waitEnd();assert.deepEqual(runCalls().at(-1).body.roleKeys,[allKeys[0]]);assert.equal(runCalls().at(-1).body.operation,'info');
  await page.locator('[data-checkin-clear]').click();assert.equal(await page.locator('#checkinRefresh').isDisabled(),true);
  for(const k of [allKeys[0],allKeys[3],allKeys[6]])await page.locator('[data-checkin-select="'+k+'"]').check();assert.equal(await page.locator('[data-checkin-all]').evaluate(x=>x.indeterminate),true);await page.locator('#checkinSign').click();await waitEnd();assert.deepEqual(new Set(runCalls().at(-1).body.roleKeys),new Set([allKeys[0],allKeys[3],allKeys[6]]));
  await page.locator('[data-checkin-all]').check();await page.locator('[data-checkin-collapse]').first().click();assert.equal(await page.locator('[data-checkin-select]:checked').count(),7);await page.locator('#checkinRefresh').click();await waitEnd();assert.equal(runCalls().at(-1).body.roleKeys.length,7);
  const consumed=()=>calls.filter(x=>x.path==='/api/checkin/resign/confirm').length;await page.locator('#checkinResign').click();assert.equal(consumed(),0);assert.equal(await page.locator('#resignDialog').isVisible(),false);await page.locator('[data-checkin-collapse]').first().click();await page.locator('[data-checkin-only]').first().click();
  await page.locator('#checkinResign').click();await page.locator('#resignDialog').waitFor();assert.match(await page.locator('#resignQuote').innerText(),/150 米游币/);await page.locator('#cancelResign').click();assert.equal(consumed(),0);await page.locator('#checkinResign').click();await page.locator('#confirmResign').click();await page.waitForFunction(()=>document.querySelector('#checkinDetail').textContent.includes('服务端回读已确认'));assert.equal(consumed(),1);
  await page.locator('[data-checkin-all]').check();hold=true;await page.locator('#checkinSign').click();await page.locator('#cancelCheckinBatch').waitFor();const frozen=runCalls().at(-1).body.roleKeys.slice();await page.locator('[data-checkin-clear]').click();await page.locator('#cancelCheckinBatch').click();await page.waitForFunction(()=>document.querySelector('#checkinBatchStatus').textContent.includes('已取消'));assert.equal(frozen.length,7);assert.equal(await page.locator('[data-checkin-select]:checked').count(),0);hold=false;
  await page.locator('[data-checkin-all]').check();await page.evaluate(()=>{preferences.accountIds=[];preferences.roleKeys=[];state.accounts[0].roles.push({game:'genshin',uid:'100000001',serverId:'cn_qd01',server:'另一服务器',nickname:'新增角色'});render();});assert.equal(await page.locator('[data-checkin-select]:checked').count(),8);
  await page.locator('[data-checkin-only]').first().click();await page.evaluate(()=>{state.accounts[1].roles.push({game:'starrail',uid:'100000002',serverId:'prod_gf_cn'});render();});assert.equal(await page.locator('[data-checkin-select]:checked').count(),1);await page.evaluate(()=>{state.selectedId='222';state.accounts[0].roles.shift();render();});assert.equal(await page.locator('[data-checkin-select]:checked').count(),0);
  await page.locator('[data-checkin-all]').check();await page.evaluate(()=>{state.accounts[0].label='很长的通行证昵称ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789'.repeat(3);state.accounts[0].roles[0].nickname='很长的角色昵称'.repeat(12);render();document.documentElement.style.fontSize='24px';});const sw=await page.evaluate(()=>document.documentElement.scrollWidth);assert.ok(sw<=width+1,platform+width+' overflow '+sw);assert.deepEqual(errors,[]);
  fs.mkdirSync('work/stage39',{recursive:true});await page.screenshot({path:'work/stage39/'+platform+'-selection-'+width+'.png',fullPage:true});evidence.push({platform,width,height,defaultRoles:7,groups:3,scopes:[1,3,7,0],cancelled:true,newAndDeletedRoles:true,resourceFilterIndependent:true,quoteCancelPosts:0,quoteConfirmPosts:1,scrollWidth:sw,errors});await page.close();
 }
 fs.writeFileSync('work/stage39/ui-results.json',JSON.stringify(evidence,null,2));console.log('PASS stage39 grouped selection: 10 platform/viewports, exact 1/3/7/0 scopes, cancellation, isolation, new/deleted roles, single-role consumption');
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
