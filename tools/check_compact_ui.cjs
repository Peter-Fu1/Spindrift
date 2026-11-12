
const fs=require('node:fs'), path=require('node:path'), assert=require('node:assert/strict');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const baseline=process.env.COMPACT_BASELINE;
const css=baseline?require('node:child_process').execFileSync('git',['show',baseline+':web/styles.css']):null;
(async()=>{
 const root=path.resolve(__dirname,'..'),out=path.join(root,'work/stage33');fs.mkdirSync(out,{recursive:true});
 const browser=await chromium.launch({headless:true,executablePath:process.env.CHROME_PATH||undefined});
 try{
 const rows=[];
 for(const [width,height] of [[393,780],[360,740],[412,820]]){
  for(const variant of ['cached','restricted']){
   const page=await browser.newPage({viewport:{width,height},deviceScaleFactor:2,isMobile:true,hasTouch:true});
   await page.route('https://compact.invalid/**',route=>{
    const name=new URL(route.request().url()).pathname.slice(1)||'index.html';
    if(name.includes('..')||!['index.html','styles.css','app.js','platform-adapter.js','resource-estimate.js','ui-model.js','checkin-ui.js','sms-ui.js'].includes(name))return route.abort();
    return route.fulfill({body:name==='styles.css'&&css?css:fs.readFileSync(path.join(root,'web',name)),contentType:name.endsWith('.css')?'text/css':name.endsWith('.js')?'text/javascript':'text/html'});
   });
   await page.addInitScript(()=>{
    const now=Date.now();const ids=['genshin','starrail','zenless'];
    const roles=ids.map((game,i)=>({game,uid:'10000000'+i,nickname:'旅行者',server:['天空岛','星穹列车','新艾利都'][i],level:60}));
    const games=ids.map((id,i)=>({id,roleUid:roles[i].uid,status:'ok',accent:['#cf9663','#7a64ce','#58a183'][i],nickname:roles[i].nickname,server:roles[i].server,level:60,current:116,max:i?300:200,interval:360,updated:now,cached:true,stale:true,lastError:'cancelled',dailyCurrent:i?500:4,dailyMax:i?500:4,weeklyRemaining:3,weeklyMax:3,cycleCurrent:i===1?14000:200,cycleMax:i===1?14000:200,task:'已完成',taskState:'done',reserve:i===1?100:undefined}));
    const accounts=[{id:'123456789',label:'米游社 123456789',roles,games}];
    window.HoYoNative={postMessage(raw){const v=JSON.parse(raw);queueMicrotask(()=>this.onmessage({data:JSON.stringify({id:v.id,ok:true,result:v.path==='/api/session'?{csrfToken:'fixture'}:{accounts,selectedId:'123456789'}})}));}};
   });
   await page.goto('https://compact.invalid/index.html');await page.locator('.group-heading').click();
   if(variant==='restricted')await page.evaluate(()=>{state.accounts[0].games[2]={...state.accounts[0].games[2],status:'account_restricted',retcode:10041,stale:false};render();});
   await page.screenshot({path:path.join(out,(baseline?'before-':'compact-')+width+'-'+variant+'.png'),fullPage:true});
   const measure=await page.evaluate(()=>({viewport:innerHeight,scroll:document.documentElement.scrollHeight,width:innerWidth,scrollWidth:document.documentElement.scrollWidth,cards:[...document.querySelectorAll('.card')].map(c=>({height:c.getBoundingClientRect().height,bottom:c.getBoundingClientRect().bottom,text:c.innerText}))}));
   assert.equal(measure.cards.length,3);if(!baseline)assert.ok(measure.cards[2].bottom<=height,JSON.stringify(measure));assert.ok(measure.scrollWidth<=width);
   if(variant==='restricted')assert.match(measure.cards[2].text,/10041/);
   rows.push({width,height,variant,...measure});await page.close();
  }
 }
 const page=await browser.newPage({viewport:{width:320,height:568}});
 await page.setContent('<style>'+fs.readFileSync(path.join(root,'web/styles.css'),'utf8')+'</style><article class="card"><div class="metrics"><div class="resource">树脂</div><div class="tasks"><div class="task">周本奖励剩余 <b>3 / 3</b></div></div></div></article>');
 assert.equal(await page.locator('.card').evaluate(c=>getComputedStyle(c).overflow),'visible');await page.close();
 fs.writeFileSync(path.join(out,baseline?'before-measurements.json':'compact-measurements.json'),JSON.stringify(rows,null,2));console.log(rows.map(r=>({width:r.width,height:r.height,variant:r.variant,cardHeights:r.cards.map(c=>c.height),lastBottom:r.cards[2].bottom,scroll:r.scroll})));
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1});
