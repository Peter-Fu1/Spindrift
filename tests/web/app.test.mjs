import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {readFileSync} from 'node:fs';
const source=readFileSync(new URL('../../web/app.js',import.meta.url),'utf8');
function harness(accounts=[],handler=null){
 const elements=new Map(),calls=[];
 const element=s=>{if(!elements.has(s))elements.set(s,{innerHTML:'',textContent:'',dataset:{},replaceChildren(...nodes){this.children=nodes;this.textContent=nodes.map(x=>x.textContent).join('');},hidden:true,classList:{add(){},remove(){}},addEventListener(){},setAttribute(){},showModal(){this.open=true;}});return elements.get(s);};
 const context=vm.createContext({window:{addEventListener(){},HoYoPlatform:{request:async(url,body)=>{calls.push(url);if(handler&&url.includes("/fingerprint/"))return handler(url,body);return url==='/api/session'?{csrfToken:'x'}:{accounts,selectedId:accounts[0]?.id};}}},document:{hidden:false,createElement:()=>({textContent:'',className:'',hidden:false,setAttribute(){}}),querySelector:element,querySelectorAll:()=>[],addEventListener(){}},structuredClone,setInterval(){},clearInterval(){},setTimeout(){},clearTimeout(){},fetch:async(url)=>{calls.push(url);return {ok:true,json:async()=>url==='/api/session'?{csrfToken:'x'}:{accounts,selectedId:accounts[0]?.id}};},Date,console});
 for(const name of ["resource-estimate.js","ui-model.js"])vm.runInContext(readFileSync("web/"+name,"utf8"),context);
 vm.runInContext(source,context);return {context,elements,calls};
}
test('startup shows empty content and never refreshes notes',async()=>{const h=harness();await new Promise(r=>setImmediate(r));assert.deepEqual(h.calls,['/api/session','/api/accounts']);assert.equal(h.elements.get('#groups').innerHTML,'');});
test('restored accounts are collapsed and only discovered games appear',async()=>{const h=harness([{id:'1',label:'<script>',roles:[{game:'genshin',nickname:'旅行者'}],games:[]}]);await new Promise(r=>setImmediate(r));const html=h.elements.get('#groups').innerHTML;assert.match(html,/aria-expanded="false"/);assert.match(html,/&lt;script&gt;/);assert.match(html,/原神/);assert.doesNotMatch(html,/星穹铁道|绝区零/);assert.match(h.elements.get('#accountMenu').innerHTML,/account-select selected/);});

test('verified page samples disclose their source and retain sample time',async()=>{const h=harness();await new Promise(r=>setImmediate(r));const html=vm.runInContext("card({id:'genshin',status:'ok',source:'miyoushe_webview',cached:true,current:20,max:200,updated:1700000000000},{roles:[]})",h.context);assert.match(html,/米游社页面采集/);assert.match(html,/本地缓存/);});
test('request rejection retains its code without falsely labeling it as a captcha',async()=>{const h=harness();await new Promise(r=>setImmediate(r));const html=vm.runInContext("card({id:'starrail',status:'request_invalid',retcode:-10001},{roles:[]})",h.context);assert.match(html,/-10001/);assert.match(html,/请求未被接受|未接受请求/);assert.doesNotMatch(html,/需要人机验证/);});


test('FP repair binds clicked account, reports saved state and does not auto refresh',async()=>{
 let target;
 const a={id:'111',label:'A',roles:[],games:[],fingerprint:{present:false,source:'missing',profileVersion:0}};
 const h=harness([a],async(url,body)=>{target=body.accountId;return {accounts:[a],selectedId:'111',fingerprintResult:{state:'saved',same:false}};});
 await new Promise(r=>setImmediate(r));
 await h.elements.get('#settings').onclick();
 await h.elements.get('#repairFingerprint').onclick();
 assert.equal(target,'111');
 assert.match(h.elements.get('#fpResult').textContent,/新 FP，已保存/);
 assert.match(h.elements.get('#fpResult').textContent,/手动刷新/);
 assert.equal(h.calls.filter(x=>x==='/api/accounts/fingerprint/repair').length,1);
 assert.equal(h.calls.some(x=>x.includes('/refresh/start')),false);
});
test('FP rejection displays numeric business code without claiming success',async()=>{
 const a={id:'222',label:'B',roles:[],games:[],fingerprint:{present:false,source:'missing',profileVersion:0}};
 const h=harness([a],async()=>{throw Object.assign(Error('官方未接受设备指纹申请'),{upstreamRetcode:0,fpResultCode:403});});
 await new Promise(r=>setImmediate(r));await h.elements.get('#settings').onclick();await h.elements.get('#repairFingerprint').onclick();
 assert.match(h.elements.get('#fpResult').textContent,/签发码 403/);
 assert.doesNotMatch(h.elements.get('#fpResult').textContent,/已保存/);
 assert.equal(h.elements.get('#repairFingerprint').disabled,false);
});


test('healthy roles browse original records independently of challenge availability',async()=>{
 const a={id:'111',label:'A',roles:[{game:'genshin',uid:'100000001',nickname:'fixture'}],games:[{id:'genshin',roleUid:'100000001',status:'ok',retcode:0}]};
 const h=harness([a]);await new Promise(r=>setImmediate(r));h.elements.get('#verify').onclick();
 const html=h.elements.get('#verifyGames').innerHTML;
 assert.match(html,/data-record="genshin"/);assert.match(html,/打开官方战绩/);assert.doesNotMatch(html,/data-webview/);
});
test('10041 keeps an independent record link and does not manufacture a captcha',async()=>{
 const a={id:'111',label:'A',roles:[{game:'zenless',uid:'10000001',nickname:'fixture'}],games:[{id:'zenless',roleUid:'10000001',status:'account_restricted',retcode:10041}]};
 const h=harness([a]);await new Promise(r=>setImmediate(r));h.elements.get('#verify').onclick();
 const html=h.elements.get('#verifyGames').innerHTML;
 assert.match(html,/data-record="zenless"/);assert.match(html,/data-webview="zenless"/);
 assert.match(readFileSync(new URL('../../web/app.js',import.meta.url),'utf8'),/browse\?'\/api\/accounts\/record\/open':'\/api\/accounts\/verify'/);
});

test('10041 card retains restriction meaning rather than first-refresh placeholder',async()=>{
 const h=harness();await new Promise(r=>setImmediate(r));
 const html=vm.runInContext("card({id:'zenless',status:'account_restricted',retcode:10041},{roles:[]})",h.context);
 assert.match(html,/官方限制(?:当前|此)请求/);assert.match(html,/10041/);assert.doesNotMatch(html,/等待首次刷新|验证成功/);
});
