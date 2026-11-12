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

  const resourceKey='111|genshin:100000001:cn_gf01',selected='333|genshin:100000001:cn_gf_cn';
  const auto=()=>calls.filter(x=>x.path==='/api/checkin/auto/start'),resources=()=>calls.filter(x=>x.path==='/api/accounts/refresh/start');
  assert.equal(await page.locator('.card').count(),1);assert.equal(auto().length,0);
  await page.locator('#refresh').click();await page.waitForFunction(()=>document.querySelector('#autoCheckinStatus').textContent.includes('3 个已启用角色'));
  const resourceLayout=await page.evaluate(()=>['autoCheckinStatus'].map(id=>{const el=document.getElementById(id),h=el.querySelector('.status-heading').getBoundingClientRect(),r=el.querySelector('.status-result').getBoundingClientRect();return {id,headingHeight:h.height,resultY:r.y,headingBottom:h.bottom,resultText:el.querySelector('.status-result').textContent};}));
  for(const x of resourceLayout){assert.ok(x.headingHeight>0);assert.ok(x.resultY>=x.headingBottom-1,JSON.stringify(x));assert.match(x.resultText,/^检查结束/);}
  const summaryLines=await page.evaluate(()=>['autoCheckinStatus','viewStatus'].map(id=>{const el=document.querySelector('#'+id+' .status-result'),range=document.createRange();range.selectNodeContents(el);return {id,lines:range.getClientRects().length,text:el.textContent,full:el.getAttribute('aria-label')};}));
  for(const line of summaryLines)assert.equal(line.lines,1,JSON.stringify({platform,width,...line}));
  fs.mkdirSync('work/stage42',{recursive:true});await page.screenshot({path:'work/stage42/'+platform+'-resources-'+width+'.png',fullPage:true});
  const summaryBottom=await page.locator('#viewStatus').evaluate(el=>Math.ceil(el.getBoundingClientRect().bottom+4));await page.screenshot({path:'work/stage42/'+platform+'-summary-'+width+'.png',clip:{x:0,y:0,width,height:summaryBottom}});
  assert.deepEqual(resources().at(-1).body.roleKeys,[resourceKey]);assert.equal(auto().at(-1).body.trigger,'resource_manual_refresh');assert.equal(Object.hasOwn(auto().at(-1).body,'roleKeys'),false);assert.match(auto().at(-1).body.requestId,/^[a-f0-9]{32}$/);
  await page.locator('[data-refresh-role]').click();await page.waitForFunction(()=>!document.querySelector('#refresh').disabled);assert.deepEqual(resources().at(-1).body.roleKeys,[resourceKey]);assert.equal(auto().at(-1).body.trigger,'resource_card_refresh');
  resourceFails=true;await page.locator('#refresh').click();await page.waitForFunction(()=>!document.querySelector('#refresh').disabled);assert.equal(auto().length,3);resourceFails=false;
  await page.locator('#filter').click();await page.locator('input[name=refreshMode][value=local]').check();await page.locator('#filterDialog .close').click();const before=auto().length,networkResources=resources().length;await page.locator('[data-refresh-role]').click();await page.waitForFunction(()=>document.querySelector('#autoCheckinStatus').textContent.includes('本次未检查'));assert.equal(auto().length,before);assert.equal(resources().length,networkResources);
  if(width<600&&await page.locator('#menuToggle').getAttribute('aria-expanded')!=='true')await page.locator('#menuToggle').click();await page.locator('[data-page="checkin"]').click();
  const key='333|genshin:100000001:cn_gf01';await page.locator('[data-checkin-only]').filter({hasText:'仅选'}).last().count();await page.locator('[data-checkin-only="'+key+'"]').click();await page.locator('#checkinRefresh').click();await page.waitForFunction(()=>!document.querySelector('#checkinRefresh').disabled);
  assert.deepEqual(calls.filter(x=>x.path==='/api/checkin/batch/start').at(-1).body.roleKeys,[key]);assert.equal(auto().length,before);assert.match(await page.locator('#autoCheckinStatus').innerText(),/3 个已启用角色/);
  const positions=await page.evaluate(()=>['autoCheckinStatus'].map(id=>{const el=document.getElementById(id),head=el.querySelector('.status-heading'),result=el.querySelector('.status-result');return {id,heading:head?.getBoundingClientRect().toJSON(),result:result?.getBoundingClientRect().toJSON(),text:result?.textContent};}));
  for(const pos of positions){assert.ok(pos.result.y>=pos.heading.bottom-1,JSON.stringify(pos));assert.match(pos.text,/^检查结束/);}
  await page.evaluate(()=>document.documentElement.style.fontSize='24px');await page.emulateMedia({colorScheme:'dark'});
  const zoomLines=await page.evaluate(()=>{const el=document.querySelector('#autoCheckinStatus');el.style.fontSize='24px';el.fitStatusResult();const range=document.createRange();range.selectNodeContents(el.querySelector('.status-result'));return range.getClientRects().length;});assert.equal(zoomLines,1,'large-font summary must use shorter labels');

  assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth)<=width+1);
  const previous=auto().length;await page.locator('#checkinSign').click();await page.waitForFunction(()=>!document.querySelector('#checkinSign').disabled);assert.equal(auto().length,previous);
  await page.locator('#chooseCheckinMode').click();await page.locator('#checkinModeOptions [data-auto-checkin-all]').check();await page.waitForFunction(()=>!document.querySelector('#checkinModeOptions input').disabled);await page.locator('#checkinModeOptions [data-auto-checkin-all]').uncheck();await page.waitForFunction(()=>!document.querySelector('#checkinModeOptions input').disabled);await page.locator('#checkinModeDialog .close').click();await page.locator('#checkinRefresh').click();await page.waitForFunction(()=>!document.querySelector('#checkinRefresh').disabled);await page.waitForFunction(()=>document.querySelector('#autoCheckinStatus').textContent.includes('0 个已启用角色'));
  const done=auto().length;await page.evaluate(()=>{HoYoCheckin.tick();HoYoCheckin.render();});assert.equal(auto().length,done);assert.equal(calls.filter(x=>x.path==='/api/checkin/resign/confirm').length,0);
  book.statusRevision=100;book.roles[key+'|CN']={...book.roles[key+'|CN'],state:'already_signed',info:{today,isSign:true,total:14}};
  await page.evaluate(()=>HoYoCheckin.read());book=structuredClone(book);book.statusRevision=99;book.roles[key+'|CN'].state='not_signed';book.roles[key+'|CN'].info.isSign=false;
  await page.evaluate(()=>HoYoCheckin.read());assert.match(await page.locator('[data-checkin-select="'+key+'"]').locator('..').locator('..').locator('.checkin-role-status').innerText(),/今日已签到/);
  const scroll=await page.evaluate(()=>document.documentElement.scrollWidth);assert.ok(scroll<=width+1);assert.deepEqual(errors,[]);
  fs.mkdirSync('work/stage42',{recursive:true});await page.screenshot({path:'work/stage42/'+platform+'-trigger-'+width+'.png',fullPage:true});evidence.push({platform,width,height,resourceOnlyA:true,autoIndependentThree:true,localZeroAutoRequests:true,selectedQueryOnlyC:true,resourceFailureIndependent:true,disabledAutoEmpty:true,timerStartCalls:0,errors});await page.close();
 }
 fs.writeFileSync('work/stage42/ui-results.json',JSON.stringify(evidence,null,2));console.log('PASS stage42 10 platform/viewports: independent resource/card/status refresh, local zero network, disabled roles, no timer start or resign');
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
