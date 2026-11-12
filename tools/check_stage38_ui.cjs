const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
(async()=>{
 const browser=await chromium.launch({headless:true,executablePath:process.env.CHROME_PATH});
 const evidence=[];const today=new Date(Date.now()+8*3600000).toISOString().slice(0,10);
 try{
 for(const platform of ['android','windows'])for(const [width,height] of [[360,740],[393,780],[412,820],[1280,900],[1024,720]]){
  const root=platform==='android'?path.resolve(process.cwd(),'web'):path.resolve(process.env.HOYO_WINDOWS_UI_ROOT||'../HoYoReminder/src/frontend/shared-ui');
  const page=await browser.newPage({viewport:{width,height},deviceScaleFactor:width===1024?1.25:1,isMobile:width<600,hasTouch:width<600});
  const calls=[],errors=[];page.on('pageerror',e=>errors.push(e.message));
  const t=Date.now()-40000;
  const account=id=>{const roles=(id==='222'?['genshin']:['genshin','starrail','zenless']).map(g=>({game:g,uid:g==='zenless'?'10000001':'100000001',serverId:g==='genshin'?'cn_gf01':'prod_gf_cn',server:'天空岛 / 新艾利都',nickname:'fixture nickname',level:60}));
   return {id,label:'测试通行证'+id,roles,games:roles.map(r=>({id:r.game,roleUid:r.uid,serverId:r.serverId,status:'ok',current:100,max:200,updated:t,lastSuccessfulFetchAt:t,dailyCurrent:4,dailyMax:4,weeklyRemaining:3,weeklyMax:3,cycleCurrent:100,cycleMax:100,task:'已完成',resourceSnapshot:{version:1,source:'server_snapshot',serverResourceValue:100,resourceMax:200,observedAtUtc:t,periodSeconds:480,remainingSeconds:47540,phaseAvailable:true}}))};};
  let state={accounts:['111','222','333'].map(account),selectedId:'333',uiPreferences:{order:['111','222','333'],expanded:['111']}},book={version:1,roles:{}};
  for(const role of state.accounts[0].roles)book.roles['111|'+role.game+':'+role.uid+':'+role.serverId+'|CN']={enabled:true,state:'unknown'};
  let pendingSmsSend=null,batch=null;
  function response(p,b){
   calls.push({path:p,body:b});
   if(p==='/api/auth/sms/send')return new Promise(resolve=>pendingSmsSend=resolve);
   if(p==='/api/auth/sms/status')return {expired:true};
   if(p==='/api/checkin/batch/status')return {job:batch,ledger:book};
   if(p==='/api/checkin/batch/start'){response('/api/checkin/run',b);batch={operationId:'fixture-batch',status:'completed',total:b.roleKeys.length,done:b.roleKeys.length,roles:[]};return {job:batch,ledger:book};}
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
  const baseline=JSON.stringify(state.accounts.map(a=>a.games));
  await page.locator('[data-checkin-account="222"]').click();await page.locator('[data-checkin-target]').first().waitFor();
  assert.equal(await page.locator('[data-checkin-target]').count(),1);
  await page.locator('#confirmAccountCheckin').click();await page.waitForFunction(()=>document.querySelector('#checkinAccountResults').innerText.includes('今日已签到'));
  assert.deepEqual(calls.filter(x=>x.path==='/api/checkin/run').at(-1).body.roleKeys,['222|genshin:100000001:cn_gf01']);
  await page.locator('#accountCheckinDialog .close').click();
  const before=calls.filter(x=>x.path==='/api/checkin/run').length;
  const initialRuns=calls.filter(x=>x.path==='/api/checkin/run').length;
  if(width<600){assert.equal(await page.locator('#menuToggle').getAttribute('aria-expanded'),'false');await page.locator('#menuToggle').click();assert.equal(await page.locator('#menuToggle').getAttribute('aria-expanded'),'true');await page.keyboard.press('Escape');assert.equal(await page.locator('#menuToggle').getAttribute('aria-expanded'),'false');await page.locator('#menuToggle').click();}
  assert.equal(await page.locator('[data-page=resources] svg path').getAttribute('d'),'M20.8 13.3A9 9 0 0 1 10.7 3.2 9 9 0 1 0 20.8 13.3Z');
  await page.locator('[data-page=checkin]').click();await page.locator('[data-checkin-only="222|genshin:100000001:cn_gf01"]').click();
  assert.equal(await page.locator('.reward').count(),31);assert.equal(await page.locator('.reward.estimated').count(),14);assert.equal(await page.locator('details.checkin-history').evaluate(x=>x.open),false);
  if(width<600)assert.equal(await page.locator('#menuToggle').getAttribute('aria-expanded'),'false');
  assert.equal(calls.filter(x=>x.path==='/api/checkin/run').length,before);
  await page.locator('details.checkin-history summary').click();await page.locator('[data-auto-checkin]').first().check();await page.locator('details.checkin-history summary').click();
  assert.ok(book.roles['222|genshin:100000001:cn_gf01|CN'].enabled);
  assert.equal(JSON.stringify(state.accounts.map(a=>a.games)),baseline);
  // Opening/canceling a quote has zero consumption; explicit confirmation exactly one.
  const consumed=()=>calls.filter(x=>x.path==='/api/checkin/resign/confirm').length;
  await page.locator('[data-checkin-resign]').click();await page.locator('#resignDialog').waitFor();assert.equal(consumed(),0);
  assert.match(await page.locator('#resignQuote').textContent(),/预计消耗 150 米游币/);
  assert.equal(await page.evaluate(()=>document.activeElement.id),'cancelResign');await page.locator('#cancelResign').click();assert.equal(consumed(),0);
  await page.locator('[data-checkin-resign]').click();await page.locator('#resignDialog').waitFor();await page.locator('#confirmResign').click();await page.waitForFunction(()=>document.querySelector('#checkinDetail').textContent.includes('回读已确认'));assert.equal(consumed(),1);
  const command=calls.find(x=>x.path==='/api/checkin/resign/confirm').body;assert.equal(command.roleKey,'222|genshin:100000001:cn_gf01');assert.equal(command.confirmed,true);
  const runCount=calls.filter(x=>x.path==='/api/checkin/run').length;await page.locator('[data-checkin-read]').click();await page.waitForTimeout(100);assert.equal(calls.filter(x=>x.path==='/api/checkin/run').at(-1).body.operation,'info');assert.equal(consumed(),1);
  fs.mkdirSync('work/stage38',{recursive:true});await page.evaluate(()=>scrollTo(0,0));await page.screenshot({path:'work/stage38/'+platform+'-collapsed-'+width+'.png',fullPage:true});
  await page.locator('details.checkin-history summary').click();assert.equal(await page.locator('details.checkin-history').evaluate(x=>x.open),true);
  assert.ok((await page.locator('#checkinDetail').textContent()).includes('不是官方自然日签到记录'));
  const officialCommands=calls.filter(x=>x.path==='/api/checkin/run'||x.path==='/api/checkin/resign/confirm').length;
  await page.evaluate(()=>HoYoCheckin.follow(['222|genshin:100000001:cn_gf01']));
  book.roles['222|genshin:100000001:cn_gf01|CN'].checkedAt=Date.now();book.roles['222|genshin:100000001:cn_gf01|CN'].state='blocked';book.roles['222|genshin:100000001:cn_gf01|CN'].code=10041;
  await page.waitForFunction(()=>document.querySelector('#checkinChoices').textContent.includes('10041'),{timeout:7000});
  assert.equal(calls.filter(x=>x.path==='/api/checkin/run'||x.path==='/api/checkin/resign/confirm').length,officialCommands);
  if(platform==='android'){
   await page.locator('#account').click();await page.locator('#addAccount').click();await page.locator('#smsTab').click();await page.locator('#phone').fill('13800000000');await page.locator('#sendSms').click();
   await page.waitForTimeout(50);assert.ok(pendingSmsSend);await page.locator('#login .close').click();pendingSmsSend({smsId:'fixture-late-send',sent:true});
   for(let n=0;n<30&&!calls.some(x=>x.path==='/api/auth/sms/cancel'&&x.body.smsId==='fixture-late-send');n++)await page.waitForTimeout(50);
   assert.ok(calls.some(x=>x.path==='/api/auth/sms/cancel'&&x.body.smsId==='fixture-late-send'));assert.equal(calls.filter(x=>x.path==='/api/auth/sms/login').length,0);
  }
  const toolbar=await page.locator('header nav').boundingBox();assert.ok(toolbar.x+toolbar.width<=width+1);
  const sw=await page.evaluate(()=>document.documentElement.scrollWidth);assert.ok(sw<=width,platform+width+' horizontal overflow '+sw);
  const out='work/stage38';fs.mkdirSync(out,{recursive:true});await page.screenshot({path:path.join(out,platform+'-checkin-'+width+'.png'),fullPage:true});
  assert.deepEqual(errors,[]);evidence.push({platform,width,height,deviceScaleFactor:width===1024?1.25:1,threeCardsBottom:bottom,scrollWidth:sw,ownedBTargets:1,rewardSlots:31,resourceSnapshotUnchanged:true,quoteCancelPosts:0,confirmedResignPosts:1,drawerKeyboard:true,estimatedRewardTiers:14,defaultCollapsed:true,delayedLocalLedgerRead:true,errors});
  await page.close();
 }
 fs.writeFileSync('work/stage38/ui-parity.json',JSON.stringify(evidence,null,2));console.log('PASS stage38 shared UI 10 viewports: menu/keyboard, scoped B, default-collapsed estimates, quote cancel zero / confirmation one, resources unchanged');
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1});
