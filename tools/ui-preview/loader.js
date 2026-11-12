/* Development launcher only. Not referenced or packaged by the Android app. */
(()=>{
 'use strict';
 const local=location.protocol==='http:'||location.protocol==='https:';
 const allowed=local&&['localhost','127.0.0.1','[::1]'].includes(location.hostname)&&window.top===window&&!window.HoYoNative;
 const status=document.querySelector('#status'),frame=document.querySelector('#uiPreview');
 const scenario=document.querySelector('#scenario'),viewport=document.querySelector('#viewport');
 const params=new URLSearchParams(location.search);
 for(const [select,key] of [[scenario,'scenario'],[viewport,'viewport']])if([...select.options].some(x=>x.value===params.get(key)))select.value=params.get(key);
 let active=params.get('preview')==='1',generation=0;
 if(!allowed){status.textContent='预览被拒绝：仅支持 Windows 本机 localhost / 127.0.0.1 / [::1] 的 HTTP(S) 服务；file://、局域网地址和 APK 均不可用。';document.querySelectorAll('button,select').forEach(x=>x.disabled=true);return;}
 async function load(){
  const run=++generation;
  const url=new URL(location.href);url.searchParams.set('preview','1');url.searchParams.set('scenario',scenario.value);url.searchParams.set('viewport',viewport.value);history.replaceState(null,'',url);
  const [w,h]=viewport.value.split('x').map(Number);frame.style.width=w+'px';frame.style.height=h+'px';
  status.textContent='正在读取web/ 目录的正式 UI…';
  try{
   const root=new URL('../../web/',location.href);
   const response=await fetch(new URL('index.html',root),{cache:'no-store',credentials:'omit'});
   if(!response.ok)throw Error('index.html HTTP '+response.status);
   const doc=new DOMParser().parseFromString(await response.text(),'text/html');
   const scripts=[...doc.querySelectorAll('script')],known=new Set(['resource-estimate.js','ui-model.js','platform-adapter.js','app.js','checkin-ui.js','sms-ui.js']);
   let replaced=0;
   for(const script of scripts){
    const src=script.getAttribute('src');
    // Live Server may inject a reload script into fetched HTML. The outer launcher handles reload.
    if(!known.has(src)){script.remove();continue;}
    if(src==='platform-adapter.js'){
     const fixtures=doc.createElement('script');fixtures.src='../tools/ui-preview/fixtures.js';script.before(fixtures);
     script.src='../tools/ui-preview/adapter.js';replaced++;
    }
   }
   if(replaced!==1)throw Error('正式平台适配器数量异常，拒绝启动');
   doc.querySelectorAll('base').forEach(x=>x.remove());
   const base=doc.createElement('base');base.href=root.href;doc.head.prepend(base);
   const csp=doc.createElement('meta');csp.httpEquiv='Content-Security-Policy';
   csp.content="default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src data:; connect-src 'none'; frame-src 'none'; media-src 'none'; object-src 'none'; worker-src 'none'; base-uri 'self'; form-action 'none'";doc.head.prepend(csp);
   doc.documentElement.dataset.previewScenario=scenario.value;
   if(run!==generation)return;
   document.documentElement.dataset.preview='active';frame.srcdoc='<!doctype html>\n'+doc.documentElement.outerHTML;
   status.textContent='本地虚拟预览已启用 · '+scenario.selectedOptions[0].text+' · '+w+' × '+h+' CSS px（窗口不足时宽度受限）';
  }catch(e){frame.removeAttribute('srcdoc');status.textContent='预览未启动：'+e.message+'。请从仓库根目录启动 Live Server。';}
 }
 document.querySelector('#enable').onclick=()=>{active=true;load();};
 document.querySelector('#reset').onclick=()=>{if(active)load();};
 scenario.onchange=viewport.onchange=()=>{if(active)load();};
 if(active)load();
})();
