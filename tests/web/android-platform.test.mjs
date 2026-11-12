import test from 'node:test';import assert from 'node:assert/strict';import vm from 'node:vm';import {readFileSync,existsSync} from 'node:fs';
const source=readFileSync('web/platform-adapter.js','utf8');
function harness(available=true){const calls=[],timers=new Map(),events={};const native={postMessage:s=>calls.push(JSON.parse(s))};let serial=0;const window={...(available?{HoYoNative:native}:{}),addEventListener:(k,v)=>events[k]=v};const context=vm.createContext({window,document:{querySelectorAll:()=>[],querySelector:()=>({hidden:true})},setTimeout:f=>{timers.set(++serial,f);return serial;},clearTimeout:id=>timers.delete(id),JSON,Map,Promise,Error,Object});vm.runInContext(source,context);return {window,native,calls,timers,events};}
test('native transport preserves route semantics without HTTP or credentials',async()=>{const h=harness();const waiting=h.window.HoYoPlatform.request('/api/accounts');assert.equal(h.calls[0].method,'GET');h.native.onmessage({data:JSON.stringify({id:h.calls[0].id,ok:true,result:{accounts:[]}})});assert.equal((await waiting).accounts.length,0);assert.equal(h.timers.size,0);assert.doesNotMatch(source,/fetch\(|XMLHttpRequest|127\.0\.0\.1|8787/);});
test('native business error, timeout and late response settle only once',async()=>{const h=harness();const p=h.window.HoYoPlatform.request('/api/accounts/refresh/start',{});h.native.onmessage({data:JSON.stringify({id:h.calls[0].id,ok:false,code:'ACCOUNT_RISK',error:'官方返回5003'})});await assert.rejects(p,{code:'ACCOUNT_RISK'});const second=h.window.HoYoPlatform.request('/api/accounts');[...h.timers.values()][0]();await assert.rejects(second,/请求超时/);assert.equal(h.calls.at(-1).method,'CANCEL');h.native.onmessage({data:JSON.stringify({id:h.calls[1].id,ok:true,result:{}})});});
test('destroyed UI cancels native work and absent bridge never uses a local server',async()=>{const h=harness();const p=h.window.HoYoPlatform.request('/api/accounts');h.events.pagehide();await assert.rejects(p,/页面已关闭/);assert.equal(h.calls.at(-1).method,'CANCEL');await assert.rejects(harness(false).window.HoYoPlatform.request('/api/accounts'),/APK/);});
test('Android package reuses full UI, includes native SMS and excludes Windows runtime and exposes only a trusted main-frame bridge',()=>{
 const html=readFileSync('web/index.html','utf8'),app=readFileSync('web/app.js','utf8'),gradle=readFileSync('app/build.gradle.kts','utf8'),main=readFileSync('app/src/main/java/io/github/peterfu/hoyoreminder/MainActivity.kt','utf8'),official=readFileSync('app/src/main/java/io/github/peterfu/hoyoreminder/OfficialVerification.kt','utf8');
 for(const id of ['groups','accountMenu','refreshProgress','qrPanel','verifyDialog','settingsDialog'])assert.match(html,new RegExp('id="'+id+'"'));
 assert.match(html,/id="smsPanel"/);assert.match(readFileSync("web/sms-ui.js","utf8"),/\/api\/auth\/sms\/login/);
 assert.match(gradle,/include\("index.html","app.js","platform-adapter.js","styles.css"/);
 assert.match(main,/!mainFrame\|\|origin.toString\(\)!=ORIGIN/);assert.match(main,/setOf\(ORIGIN\)/);assert.match(official,/isFeatureSupported\(WebViewFeature.MULTI_PROFILE\)/);assert.match(official,/WebViewCompat.setProfile/);assert.doesNotMatch(official,/addJavascriptInterface\(|"HoYoNative"/);
 assert.match(official,/addWebMessageListener\(web,"HoYoRecord",setOf\(RecordEntries.origin/);
 assert.match(official,/!mainFrame\|\|origin.toString\(\)!=RecordEntries.origin/);
 assert.match(official,/addDocumentStartJavaScript/);
 assert.ok(existsSync('gradle/wrapper/gradle-wrapper.jar'));assert.ok(existsSync('gradlew.bat'));
});

test('native owns insets once and unavailable verification cannot announce an opened window',()=>{
 const main=readFileSync('app/src/main/java/io/github/peterfu/hoyoreminder/MainActivity.kt','utf8'),css=readFileSync('web/styles.css','utf8'),app=readFileSync('web/app.js','utf8');
 assert.match(main,/setInsets\(WindowInsetsCompat.Type.systemBars\(\).*Insets.NONE/);
 assert.doesNotMatch(css,/env\(safe-area-inset/);
 assert.match(app,/result.opened\?/);assert.match(app,/official_verification_unavailable/);
});
