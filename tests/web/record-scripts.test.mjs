import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {readFileSync} from 'node:fs';
const kt=readFileSync('app/src/main/java/io/github/peterfu/hoyoreminder/RecordScripts.kt','utf8');
const classify=kt.match(/val classify="""([\s\S]*?)"""/)[1];
const bootstrap=kt.match(/val bootstrap="""([\s\S]*?)"""/)[1].replaceAll('CLASSIFY',classify);
function page(text){
 const messages=[],events={},port={postMessage:m=>messages.push(JSON.parse(m))};
 const window={HoYoRecord:port};window.top=window;
 const context=vm.createContext({window,document:{body:{innerText:text}},history:{pushState(){},replaceState(){}},addEventListener:(k,v)=>events[k]=v,setTimeout:f=>f(),MutationObserver:class{observe(){}},JSON});
 vm.runInContext(bootstrap,context);return {context,window,port,events,messages};
}
test('official bridge exists at document start and dispatches the real callback envelope',()=>{
 const h=page('我的角色 活跃天数 成就达成数');
 h.window.MiHoYoJSInterface.postMessage(JSON.stringify({method:'getCookieInfo',callback:'bbs_callback_1'}));
 assert.equal(h.messages[0].method,'getCookieInfo');
 let got;h.window.mhyWebBridge=(name,value)=>{got={name,value};};
 h.port.onmessage({data:JSON.stringify({callback:'bbs_callback_1',result:{retcode:0,data:{ltuid:'fixture'}}})});
 assert.equal(got.name,'bbs_callback_1');assert.equal(got.value.retcode,0);
 assert.equal(h.window.HoYoNative,undefined);
});
test('real record content outranks an incidental open-app banner, empty promotion remains promotion',()=>{
 assert.equal(vm.runInNewContext(classify,{document:{body:{innerText:'我的角色 活跃天数 原神战绩 打开米游社'}}}).kind,'overview');
 assert.equal(vm.runInNewContext(classify,{document:{body:{innerText:'原神战绩轻松查 扫码下载米游社'}}}).kind,'promotion');
 assert.equal(vm.runInNewContext(classify,{document:{body:{innerText:'因战绩功能服务优化升级 V2.10版本无法正常使用'}}}).kind,'error');
 assert.equal(vm.runInNewContext(classify,{document:{body:{innerText:''}}}).kind,'unknown');
});
test('SPA history changes emit enum-only page state, never raw page text',()=>{
 const h=page('我的角色 活跃天数 成就达成数');
 vm.runInContext('history.pushState({}, "", "#/ys")',h.context);
 assert.equal(h.messages[0].method,'__pageState');assert.equal(h.messages[0].payload.kind,'overview');
 assert.deepEqual(Object.keys(h.messages[0].payload).sort(),['challengeVisible','kind','visible']);
});
test('no app bridge is installed in a child frame',()=>{
 const context=vm.createContext({window:{top:{}}});
 vm.runInContext(bootstrap,context);
 assert.equal(vm.runInContext('window.MiHoYoJSInterface',context),undefined);
});

test('visible challenge overlay is observed without claiming completion or leaking its contents',()=>{
 const el={getBoundingClientRect:()=>({width:300,height:200,right:320,bottom:300,left:20,top:100})};
 const env={document:{body:{innerText:'我的角色 活跃天数 成就达成数'},querySelectorAll:()=>[el]},getComputedStyle:()=>({display:'block',visibility:'visible',opacity:'1'}),innerWidth:393,innerHeight:780};
 const shown=vm.runInNewContext(classify,env);assert.equal(shown.challengeVisible,true);assert.equal(shown.kind,'overview');
 env.getComputedStyle=()=>({display:'none',visibility:'visible',opacity:'1'});
 assert.equal(vm.runInNewContext(classify,env).challengeVisible,false);
});


test('callback diagnostics confirm actual invocation and explicitly report missing or throwing callbacks',()=>{
 const h=page('我的角色');
 h.port.onmessage({data:JSON.stringify({callback:'sensitive_callback',result:{retcode:0},auditId:'fixture-id'})});
 assert.equal(h.messages.at(-1).payload.delivered,false);
 h.window.mhyWebBridge=()=>{throw new Error('private');};
 h.port.onmessage({data:JSON.stringify({callback:'sensitive_callback',result:{retcode:0},auditId:'fixture-id-2'})});
 assert.equal(h.messages.at(-1).payload.delivered,false);
 h.window.mhyWebBridge=()=>{};
 h.port.onmessage({data:JSON.stringify({callback:'sensitive_callback',result:{retcode:0},auditId:'fixture-id-3'})});
 assert.equal(h.messages.at(-1).payload.delivered,true);
 assert.equal(JSON.stringify(h.messages).includes('sensitive_callback'),false);
});
