const fs=require('node:fs'),path=require('node:path'),http=require('node:http'),assert=require('node:assert/strict');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const root=path.resolve(__dirname,'..'),out=path.join(root,'work/ui-preview-tests');fs.mkdirSync(out,{recursive:true});
(async()=>{
 const modified=new Map();let sockets=new Set();
 const server=http.createServer((req,res)=>{
  const u=new URL(req.url,'http://127.0.0.1'),file=path.resolve(root,'.'+decodeURIComponent(u.pathname));
  if(!file.startsWith(root+path.sep)||!fs.existsSync(file)||!fs.statSync(file).isFile()){res.writeHead(404).end();return;}
  res.setHeader('Content-Type',file.endsWith('.html')?'text/html; charset=utf-8':file.endsWith('.css')?'text/css; charset=utf-8':'text/javascript; charset=utf-8');
  res.setHeader('Cache-Control','no-store');res.end(modified.get(file)||fs.readFileSync(file));
 });
 server.on('connection',s=>{sockets.add(s);s.on('close',()=>sockets.delete(s));});
 await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
 const base='http://127.0.0.1:'+server.address().port;
 const browser=await chromium.launch({headless:true,executablePath:process.env.CHROME_PATH});
 const results=[];
 try{
  const context=await browser.newContext(),page=await context.newPage();
  page.setDefaultTimeout(8000);const errors=[],requests=[],external=[];page.on('pageerror',e=>errors.push(e.message));
  await context.route('**/*',route=>{const url=route.request().url();requests.push(url);if(!url.startsWith(base+'/')&&!url.startsWith('data:')){external.push(url);return route.abort();}return route.continue();});
  async function open(scenario='default',viewport='393x780'){
   await page.goto(base+'/tools/ui-preview/index.html?preview=1&scenario='+scenario+'&viewport='+viewport);
   await page.frameLocator('#uiPreview').locator('#groups').waitFor({state:'attached'});
   const frame=page.frames().find(f=>f.url()==='about:srcdoc');
   assert.ok(frame);
   await frame.waitForLoadState("domcontentloaded");
   await frame.waitForFunction(()=>document.querySelector('#groups')?.getAttribute('aria-busy')==='false');
   assert.equal(await frame.locator('body').innerText().then(x=>x.includes('原生服务未就绪')),false);
   return frame;
  }
  await page.goto(base+'/tools/ui-preview/index.html');assert.equal(await page.locator('iframe').getAttribute('srcdoc'),null);
  await page.locator('#enable').click();await page.waitForFunction(()=>!!document.querySelector('iframe').getAttribute('srcdoc'));
  results.push('explicit opt-in');
  let frame=await open();
  assert.equal(await frame.locator('.group').count(),3);assert.equal(await frame.locator('.card:visible').count(),3);
  assert.equal(await frame.locator('.avatar').first().getAttribute('src').then(x=>x.startsWith('data:')),true);
  const loaded=await frame.locator('script[src]').evaluateAll(xs=>xs.map(x=>x.getAttribute('src')));
  assert.ok(loaded.includes('app.js'));assert.ok(loaded.includes('checkin-ui.js'));assert.ok(loaded.includes('sms-ui.js'));assert.ok(!loaded.includes('platform-adapter.js'));
  await frame.locator('#filter').click();await frame.locator('input[name=refreshMode][value=local]').check();await frame.locator('#filterDialog .close').click();
  await frame.locator('#refresh').click();await frame.waitForFunction(()=>document.querySelector('#notice').textContent.includes('本地估算完成')||document.querySelector('#notice').textContent.includes('本地估算已更新'));
  const endpoints=['/api/auth/qr/start','/api/auth/sms/send','/api/auth/sms/login','/api/accounts/fingerprint/repair','/api/accounts/profile','/api/accounts/record/open','/api/accounts/verify','/api/diagnostics/export','/api/unrecognized'];
  assert.deepEqual(await frame.evaluate(async paths=>Promise.all(paths.map(async p=>{try{await HoYoPlatform.request(p,{phone:'not-real'});return false;}catch{return true;}})),endpoints),endpoints.map(()=>true));
  const violations=await frame.evaluate(async()=>{
   const violations=[];document.addEventListener('securitypolicyviolation',e=>violations.push(e.violatedDirective));
   const attempts=[()=>fetch('https://api-takumi-record.mihoyo.com/never-send'),()=>fetch('/api/never-send'),()=>fetch('http://127.0.0.1:1/never-send')];
   for(const attempt of attempts)try{await attempt();}catch{}
   const img=new Image();img.src='https://bbs-static.miyoushe.com/never-load.png';document.body.append(img);
   await new Promise(r=>setTimeout(r,100));return violations;
  });
  assert.ok(violations.filter(x=>x==='connect-src').length>=3);assert.ok(violations.includes('img-src'));assert.equal(external.length,0);
  assert.equal(requests.filter(x=>new URL(x).pathname.startsWith('/api/')).length,0);
  await frame.evaluate(()=>{localStorage.setItem('hoyo-nav-open','preview-only');});
  assert.notEqual(await page.evaluate(()=>localStorage.getItem('hoyo-nav-open')),'preview-only');
  results.push('formal renderer reuse, local actions, sensitive RPC denied, CSP blocks external and loopback API, storage isolated');
  frame=await open('genshin-only');assert.equal(await frame.locator('.card').count(),1);assert.match(await frame.locator('.card h3').innerText(),/原神/);
  await frame.locator('#refresh').click();await frame.waitForFunction(()=>document.querySelector('#refreshSummary').textContent.includes('1/1'));
  frame=await open('empty');assert.equal(await frame.locator('.group').count(),0);assert.equal(await frame.locator('#refresh').isDisabled(),true);
  frame=await open('errors');assert.match(await frame.locator('#groups').innerText(),/5003/);assert.match(await frame.locator('#groups').innerText(),/10041/);assert.match(await frame.locator('#groups').innerText(),/1034/);
  await frame.locator('#verify').click();await frame.locator('[data-record]').first().click();await frame.waitForFunction(()=>document.querySelector('#notice').textContent.includes('UI Preview'));
  frame=await open('login');await frame.locator('#login[open]').waitFor({state:'visible'});assert.equal(await frame.locator('#newQr').isDisabled(),true);await frame.locator('#smsTab').click();assert.equal(await frame.locator('#sendSms').isDisabled(),true);
  results.push('single game, empty, cached errors, verification blocked, login dialog');
  frame=await open('checkin');assert.equal(await frame.locator('#checkinPage').isVisible(),true);await frame.locator('[data-checkin-only]').first().click();await frame.locator('details summary').click();assert.ok(await frame.locator('.reward').count()>=28);
  await frame.locator('[data-checkin-read]').click();await frame.locator('[data-checkin-resign]').click();assert.match(await frame.locator('#resignQuote').innerText(),/150\s*米游币/);await frame.locator('#cancelResign').click();
  await frame.locator('[data-checkin-resign]').click();await frame.locator('#confirmResign').click();await frame.waitForFunction(()=>document.querySelector('#checkinDetail').textContent.includes('服务端回读已确认'));
  results.push('synthetic checkin details, rewards, quote cancel/confirm');
  for(const scenario of ['default','long-text'])for(const viewport of ['360x740','393x780','412x820','1280x900']){
   frame=await open(scenario,viewport);
   const dimensions=await frame.evaluate(()=>({width:innerWidth,scroll:document.documentElement.scrollWidth,body:document.body.scrollWidth,cards:[...document.querySelectorAll('.card')].filter(x=>x.getBoundingClientRect().height>0).map(x=>x.getBoundingClientRect().bottom)}));
   assert.ok(dimensions.scroll<=dimensions.width+1,JSON.stringify({scenario,viewport,dimensions}));assert.equal(dimensions.cards.length,3);
   if(scenario==='default'&&Number(viewport.split('x')[0])<600)assert.ok(dimensions.cards.at(-1)<Number(viewport.split('x')[1]),JSON.stringify(dimensions));
   await page.locator('#uiPreview').screenshot({path:path.join(out,scenario+'-'+viewport+'.png')});results.push({scenario,viewport,dimensions});
  }
  // Simulates an edit on disk without touching the user's working tree. Every reload fetches current formal files.
  modified.set(path.join(root,'web/index.html'),fs.readFileSync(path.join(root,'web/index.html'),'utf8').replace('id="checkinResign" data-checkin-resign>补签','id="checkinResign" data-checkin-resign>预览补签'));
  modified.set(path.join(root,'web/app.js'),fs.readFileSync(path.join(root,'web/app.js'),'utf8')+'\ndocument.documentElement.dataset.previewJsEdit="visible";');
  modified.set(path.join(root,'web/styles.css'),fs.readFileSync(path.join(root,'web/styles.css'),'utf8')+'\nheader h1{color:rgb(1, 2, 3)!important}');
  frame=await open();assert.equal(await frame.locator('#checkinResign').innerText(),'预览补签');assert.equal(await frame.locator('header h1').evaluate(x=>getComputedStyle(x).color),'rgb(1, 2, 3)');assert.equal(await frame.locator('html').getAttribute('data-preview-js-edit'),'visible');modified.clear();
  results.push('HTML, CSS and JS edits visible after reload with original shared scripts');
  await page.goto(base+'/web/index.html?preview=1');await page.waitForFunction(()=>document.querySelector('#groups').getAttribute('aria-busy')==='false');assert.equal(await page.locator('.group').count(),0);assert.match(await page.locator('#notice').innerText(),/原生服务未就绪/);
  results.push('formal index ignores preview query and retains native guard');
  // Actual native-bridge presence check needs an init script before loader execution.
  const native=await browser.newContext();await native.addInitScript(()=>window.HoYoNative={postMessage(){throw Error('must not call native');}});const np=await native.newPage();await np.goto(base+'/tools/ui-preview/index.html?preview=1');assert.equal(await np.locator('iframe').getAttribute('srcdoc'),null);assert.match(await np.locator('#status').innerText(),/预览被拒绝/);await native.close();
  const remote=await browser.newContext();const rp=await remote.newPage();await remote.route('**/*',route=>{const u=new URL(route.request().url()),f=path.join(root,u.pathname);return fs.existsSync(f)?route.fulfill({body:fs.readFileSync(f),contentType:f.endsWith('.html')?'text/html':f.endsWith('.css')?'text/css':'text/javascript'}):route.abort();});await rp.goto('https://appassets.androidplatform.net/tools/ui-preview/index.html?preview=1');assert.equal(await rp.locator('iframe').getAttribute('srcdoc'),null);await remote.close();
  assert.equal(external.length,0);assert.deepEqual(errors,[]);
  results.push('native bridge and appassets host refuse preview; no page errors or external requests');
  fs.writeFileSync(path.join(out,'results.json'),JSON.stringify({passed:true,results,externalRequests:external.length,requests:requests.length},null,2));console.log(JSON.stringify({passed:true,checks:results.length,externalRequests:external.length,out},null,2));
 }finally{await browser.close();for(const s of sockets)s.destroy();await new Promise(resolve=>server.close(resolve));}
})().catch(e=>{console.error(e);process.exitCode=1;});
