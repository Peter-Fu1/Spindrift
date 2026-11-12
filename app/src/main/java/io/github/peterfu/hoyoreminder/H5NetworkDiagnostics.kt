package io.github.peterfu.hoyoreminder

import org.json.JSONObject

/** Read-only fetch/XHR response observation. No body, URL, query, token or header values cross the bridge.
 * This is page-reported evidence, never evidence of final wire headers or verified captcha success.
 */
object H5NetworkDiagnostics {
 val script:String get(){
  val endpoints=RequestEndpoints.known+Game.entries.associate{("api-takumi-record.mihoyo.com"+it.path) to "h5_note"}
  return """
  (()=>{
   if(window!==top||window.__hoyoNetworkDiagnostics)return;
   window.__hoyoNetworkDiagnostics=true;
   const port=window.HoYoRecord,known=ENDPOINTS;let count=0;
   const category=value=>{try{const u=new URL(value,location.href);if(u.protocol!=='https:'||u.username||u.password||u.port&&u.port!=='443')return null;return known[u.hostname+u.pathname]||null;}catch(_){return null;}};
   const report=(kind,method,status,value)=>{try{
    if(!kind||count>=80)return;count++;
    port.postMessage(JSON.stringify({method:'__networkResult',payload:{kind,method:method==='GET'||method==='POST'?method:'other',httpStatus:status,retcode:Number.isInteger(value?.retcode)&&value.retcode>=-2147483648&&value.retcode<=2147483647?value.retcode:null}}));
   }catch(_){}};
   if(window.fetch){const original=window.fetch;window.fetch=function(...args){
    const kind=category(typeof args[0]==='string'||args[0] instanceof URL?String(args[0]):args[0]?.url);
    const method=String(args[1]?.method||args[0]?.method||'GET').toUpperCase();
    const result=original.apply(this,args);
    if(kind)result.then(async response=>{try{
     const clone=response.clone();if(!clone.body){report(kind,method,response.status,null);return;}
     const reader=clone.body.getReader();const parts=[];let size=0;
     while(true){const p=await reader.read();if(p.done)break;size+=p.value.length;if(size>262144){reader.cancel().catch(()=>{});report(kind,method,response.status,null);return;}parts.push(p.value);}
     const bytes=new Uint8Array(size);let pos=0;for(const p of parts){bytes.set(p,pos);pos+=p.length;}
     let value=null;try{value=JSON.parse(new TextDecoder().decode(bytes));}catch(_){}
     report(kind,method,response.status,value);
    }catch(_){report(kind,method,response.status,null);}},()=>report(kind,method,0,null));
    return result;
   };}
   const open=XMLHttpRequest.prototype.open,send=XMLHttpRequest.prototype.send,requests=new WeakMap();
   XMLHttpRequest.prototype.open=function(method,url,...rest){const result=open.call(this,method,url,...rest);requests.set(this,{kind:category(url),method:String(method).toUpperCase()});return result;};
   XMLHttpRequest.prototype.send=function(...args){
    const context=requests.get(this);if(context?.kind){const finish=()=>{
     this.removeEventListener('loadend',finish);let value=null;
     try{if(this.responseType==='json')value=this.response;else if((this.responseType===''||this.responseType==='text')&&this.responseText.length<=262144)value=JSON.parse(this.responseText);}catch(_){}
     report(context.kind,context.method,this.status,value);
    };this.addEventListener('loadend',finish);}
    return send.apply(this,args);
   };
  })();
  """.replace("ENDPOINTS",JSONObject(endpoints).toString())
 }
}
