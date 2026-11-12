package io.github.peterfu.hoyoreminder
/** Installed at document start, only in the trusted top frame. Never installs HoYoNative. */
object RecordScripts {
 val classify="""(()=>{
  const text=(document.body?.innerText||'').replace(/\s+/g,'');
  const visible=!!document.body&&text.length>12;
  let kind='unknown';
  if(/我的角色|活跃天数|成就达成数|开拓等级|忘却之庭|绳网等级|式舆防卫战/.test(text))kind='overview';
  if(/原粹树脂|开拓力|每日委托|电量恢复|每日活跃/.test(text)&&/实时便笺|体力|电量|树脂/.test(text))kind='daily';
  if(kind==='unknown'&&/请完成验证|安全验证|点击进行验证|验证后继续/.test(text))kind='captcha';
  if(kind==='unknown'&&/版本过低|V2\.10|服务升级|账号存在风险|请求失败|加载失败|访问受限/.test(text))kind='error';
  if(kind==='unknown'&&text.length<500&&/扫码下载米游社|打开米游社|下载米游社|米游社App查询|原神战绩轻松查|星穹铁道战绩工具/.test(text))kind='promotion';
  const challengeVisible=kind==='captcha'||[...(document.querySelectorAll?.('[class*="geetest"],iframe[src*="geetest"],iframe[src*="captcha"]')||[])].some(el=>{
   const r=el.getBoundingClientRect(),s=getComputedStyle(el);
   return r.width>0&&r.height>0&&r.right>0&&r.bottom>0&&r.left<innerWidth&&r.top<innerHeight&&s.display!=='none'&&s.visibility!=='hidden'&&s.opacity!=='0';
  });
  return {kind,visible,challengeVisible};
 })()"""
 val bootstrap="""
 (()=>{
  if(window!==window.top||window.__hoyoRecordInstalled)return;
  window.__hoyoRecordInstalled=true;
  const port=window.HoYoRecord;
  if(!port)return;
  port.onmessage=e=>{let m,delivered=false;try{m=JSON.parse(e.data);if(m.callback&&typeof window.mhyWebBridge==='function'){window.mhyWebBridge(m.callback,m.result);delivered=true;}}catch(_){}finally{
   if(m?.auditId)try{port.postMessage(JSON.stringify({method:'__bridgeAck',payload:{id:m.auditId,delivered}}));}catch(_){}
  }};
  window.MiHoYoJSInterface={
   postMessage:value=>{try{port.postMessage(typeof value==='string'?value:JSON.stringify(value));}catch(_){}},
   closePage:()=>port.postMessage(JSON.stringify({method:'closePage'}))
  };
  const report=()=>{try{const value=CLASSIFY;port.postMessage(JSON.stringify({method:'__pageState',payload:value}));}catch(_){}};
  for(const key of ['pushState','replaceState']){const original=history[key];history[key]=function(...args){const value=original.apply(this,args);setTimeout(report,0);return value;};}
  addEventListener('hashchange',report);addEventListener('popstate',report);addEventListener('DOMContentLoaded',report);
  let pending=false;const observe=()=>{if(!document.body)return;new MutationObserver(()=>{if(!pending){pending=true;setTimeout(()=>{pending=false;report();},400);}}).observe(document.body,{subtree:true,childList:true,attributes:true,attributeFilter:['style','class','hidden']});};
  addEventListener('DOMContentLoaded',observe);
 })();
 """.replace("CLASSIFY",classify)
}
