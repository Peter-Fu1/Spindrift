import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {readFileSync} from 'node:fs';
const kt=readFileSync('app/src/main/java/io/github/peterfu/hoyoreminder/H5NetworkDiagnostics.kt','utf8');
const script=kt.match(/return """([\s\S]*?)"""/)[1].replace('ENDPOINTS',JSON.stringify({'api-takumi.mihoyo.com/event/toolcomsrv/risk/createGeetest':'challenge_bootstrap'}));
function setup(fetchImpl){
 const messages=[];
 class Xhr{open(method,url){this.args=[method,url];}addEventListener(_,f){this.listener=f;}removeEventListener(){this.listener=null;}send(body){this.sent=body;this.status=200;this.responseType='text';this.responseText='{"retcode":10041,"token":"DO_NOT_EXPORT"}';this.listener?.();}}
 const window={HoYoRecord:{postMessage:m=>messages.push(JSON.parse(m))},fetch:fetchImpl};
 const context=vm.createContext({window,top:window,URL,location:{href:'https://act.mihoyo.com/'},XMLHttpRequest:Xhr,WeakMap,Uint8Array,TextDecoder,JSON});
 vm.runInContext(script,context);return {window,messages,Xhr};
}
test('read-only fetch observation keeps original promise and response, exports only enum/status/code',async()=>{
 let calls=0;const response=new Response('{"retcode":0,"data":{"ticket":"DO_NOT_EXPORT"}}',{headers:{'Content-Type':'application/json'}});
 const promise=Promise.resolve(response);const h=setup(()=>{calls++;return promise;});
 assert.equal(h.window.fetch('https://api-takumi.mihoyo.com/event/toolcomsrv/risk/createGeetest?token=DO_NOT_EXPORT'),promise);
 assert.equal(await (await promise).text(),'{"retcode":0,"data":{"ticket":"DO_NOT_EXPORT"}}');
 await new Promise(r=>setTimeout(r,20));
 assert.equal(calls,1);assert.equal(h.messages[0].payload.retcode,0);
 assert.equal(JSON.stringify(h.messages).includes('DO_NOT_EXPORT'),false);
});
test('XHR observation preserves body and original method and excludes unknown hosts',()=>{
 const h=setup(()=>Promise.resolve(new Response('{}')));
 const xhr=new h.Xhr();xhr.open('POST','https://api-takumi.mihoyo.com/event/toolcomsrv/risk/createGeetest?secret=x');xhr.send('PRIVATE_BODY');
 assert.equal(xhr.sent,'PRIVATE_BODY');assert.equal(h.messages[0].payload.retcode,10041);
 const bad=new h.Xhr();bad.open('GET','https://evil.invalid/event/toolcomsrv/risk/createGeetest');bad.send();
 assert.equal(h.messages.length,1);assert.equal(JSON.stringify(h.messages).includes('PRIVATE'),false);
});
