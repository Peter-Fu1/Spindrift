const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
(async()=>{
 const browser=await chromium.launch({headless:true,executablePath:process.env.CHROME_PATH});
 const page=await browser.newPage({viewport:{width:393,height:780},isMobile:true,hasTouch:true});
 let external=0;const errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.route('**/*',async route=>{
  const u=new URL(route.request().url());
  if(u.host!=='appassets.androidplatform.net'){external++;return route.abort();}
  if(u.pathname==='/profile-image')return route.fulfill({contentType:'image/png',body:Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aK8sAAAAASUVORK5CYII=','base64')});
  const name=path.basename(u.pathname);
  if(!['index.html','app.js','styles.css','platform-adapter.js','resource-estimate.js','ui-model.js','checkin-ui.js','sms-ui.js'].includes(name))return route.abort();
  return route.fulfill({body:fs.readFileSync(path.join("web",name)),contentType:name.endsWith('.html')?'text/html':name.endsWith('.css')?'text/css':'text/javascript'});
 });
 await page.addInitScript(()=>{
  const now=Date.now();
  const account=id=>{
   const games=id==='222'?['genshin']:['genshin','starrail','zenless'];
   const roles=games.map((game,i)=>({game,uid:(id==='111'?'1':id==='222'?'2':'3')+(game==='zenless'?'0000001':'00000001'),serverId:game==='genshin'?'cn_gf01':'prod_gf_cn',server:'区服',nickname:'角色'+id,level:60}));
   return {id,label:'昵称'+id,avatar:'https://upload-bbs.mihoyo.com/'+id+'.png',roleProfiles:Object.fromEntries(roles.map(r=>[r.game+':'+r.uid+':'+r.serverId,{avatar:'https://act-webstatic.mihoyo.com/'+id+'/'+r.game+'.png'}])),roles,games:roles.map(r=>({
    id:r.game,roleUid:r.uid,serverId:r.serverId,status:'ok',current:100,max:200,updated:now-40000,lastSuccessfulFetchAt:now-40000,
    taskState:'done',task:'已完成',retcode:r.game==='zenless'&&id==='111'?10041:0,stale:r.game==='zenless'&&id==='111',lastError:r.game==='zenless'&&id==='111'?'account_restricted':null,
    resourceSnapshot:{version:1,source:'server_snapshot',serverResourceValue:100,resourceMax:200,observedAtUtc:now-40000,periodSeconds:r.game==='genshin'?480:360,remainingSeconds:(99*(r.game==='genshin'?480:360))+20,phaseAvailable:true}
   }))};
  };
  const accounts=['111','222','333'].map(account);let prefs=JSON.parse(localStorage.getItem('fixturePrefs')||'{}'),selectedId=localStorage.getItem('fixtureSelected')||'333',job=null;
  const value=()=>({accounts,selectedId,uiPreferences:prefs,refresh:job});
  window.fixtureCalls=[];
  window.HoYoNative={postMessage(raw){const request=JSON.parse(raw);if(request.method==='CANCEL')return;const body=request.body||{};fixtureCalls.push({path:request.path,body:structuredClone(body)});
   let result;
   if(request.path==='/api/session')result={csrfToken:'fixture'};
   else if(request.path==='/api/ui/preferences'){prefs=body;localStorage.setItem('fixturePrefs',JSON.stringify(body));result=value();}
   else if(request.path==='/api/accounts/select'){selectedId=body.accountId;localStorage.setItem('fixtureSelected',selectedId);result=value();}
   else if(request.path==='/api/accounts/refresh/start'){job={refreshId:body.refreshId,status:'completed',elapsedMs:1,roles:body.roleKeys.map(key=>({state:'success',accountId:key.split('|')[0],game:key.split('|')[1].split(':')[0],roleUid:key.split(':')[1]}))};result=value();}
   else result=value();
   queueMicrotask(()=>HoYoNative.onmessage({data:JSON.stringify({id:request.id,ok:true,result:structuredClone(result)})}));
  }};
 });
 await page.goto('https://appassets.androidplatform.net/assets/web/index.html');
 await page.waitForFunction(()=>document.querySelector('#groups').getAttribute('aria-busy')==='false');
 assert.equal(await page.locator('#avatar img').getAttribute('src'),'https://appassets.androidplatform.net/profile-image?accountId=333');
 assert.match(await page.locator('#viewStatus').innerText(),/7 个筛选角色/);
 assert.equal(await page.evaluate(()=>fixtureCalls.some(c=>c.path.includes('/refresh/start'))),false);
 await page.locator('[data-expand="111"]').click();
 const before=await page.evaluate(()=>JSON.stringify(state.accounts[0].games));
 await page.locator('#filter').click();await page.locator('input[value="local"]').check();
 await page.locator('#filterDialog .close').click();
 await page.locator('.card-refresh').first().click();
 assert.equal(await page.evaluate(()=>fixtureCalls.filter(c=>c.path==='/api/accounts/refresh/start').length),0);
 assert.equal(await page.evaluate(()=>fixtureCalls.filter(c=>c.path==='/api/resources/estimate').at(-1).body.roleKeys.length),1);
 assert.equal(await page.evaluate(()=>JSON.stringify(state.accounts[0].games)),before);
 assert.match(await page.locator('.card').nth(2).innerText(),/10041/);
 await page.locator('#filter').click();await page.locator('#filterClear').click();
 assert.equal(await page.locator('#refresh').isDisabled(),true);
 await page.locator('#filterAll').click();
 await page.locator('[data-filter-account="111"]').uncheck();await page.locator('[data-filter-account="333"]').uncheck();
 await page.locator('input[value="server"]').check();await page.locator('#filterDialog .close').click();
 await page.locator('#refresh').click();await page.waitForFunction(()=>!refreshStarting);
 const chosen=await page.evaluate(()=>fixtureCalls.filter(c=>c.path==='/api/accounts/refresh/start').at(-1).body.roleKeys);
 assert.deepEqual(chosen,['222|genshin:200000001:cn_gf01']);
 await page.locator('#filter').click();await page.locator('#filterAll').click();await page.locator('#filterDialog .close').click();
 await page.locator('[data-sort="333"]').click();await page.locator('#sortUp').click();await page.locator('#sortUp').click();await page.locator('#sortDialog .close').click();
 await page.evaluate(()=>prefsQueue);
 assert.equal(await page.locator('.group').first().getAttribute('data-account-id'),'333');
 await page.reload();await page.waitForFunction(()=>document.querySelector('#groups').getAttribute('aria-busy')==='false');
 assert.equal(await page.locator('.group').first().getAttribute('data-account-id'),'333');
 // Pointer long-press sorting is tested separately from click expansion/scroll.
 const handle=page.locator('[data-sort="333"]'),destination=page.locator('[data-sort="111"]');
 const from=await handle.boundingBox(),to=await destination.boundingBox();
 await page.mouse.move(from.x+from.width/2,from.y+from.height/2);await page.mouse.down();await page.waitForTimeout(350);
 await page.mouse.move(to.x+to.width/2,to.y+to.height/2);await page.mouse.up();await page.evaluate(()=>prefsQueue);
 assert.equal(await page.locator('.group').nth(1).getAttribute('data-account-id'),'333');
 const order=await page.evaluate(()=>preferences.order);
 await page.evaluate(()=>{document.documentElement.style.fontSize='200%';document.querySelectorAll('button,h1,h3,small,.task,.muted,#viewStatus').forEach(e=>e.style.fontSize='200%');});
 assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
 fs.mkdirSync('work/stage36',{recursive:true});await page.screenshot({path:'work/stage36/ui-large-font.png',fullPage:true});
 assert.equal(external,0);assert.deepEqual(errors,[]);
 fs.writeFileSync('work/stage36/ui-interaction-evidence.json',JSON.stringify({externalRequests:external,serverTargets:chosen,dragOrder:order,localOfficialRequests:0,pageErrors:errors,largeFontNoHorizontalOverflow:true},null,2));
 console.log('PASS: local zero network, stable scopes, selected avatar, sorting/reload/touch and large-font layout');
 await browser.close();
})().catch(e=>{console.error(e);process.exit(1);});
