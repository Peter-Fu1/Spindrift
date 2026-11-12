// Only the packaged local UI receives HoYoNative. Official verification pages do not.
(()=>{
 const pending=new Map();let serial=0;
 function request(path,body,method){
  method=method||(body===undefined?'GET':'POST');
  if(typeof path!=='string'||!path.startsWith('/api/')||path.length>200)return Promise.reject(Error('无效的原生接口'));
  if(!window.HoYoNative?.postMessage)return Promise.reject(Error('Android 原生接口不可用，请在 APK 内运行'));
  const id=String(++serial);
  return new Promise((resolve,reject)=>{
   const timer=setTimeout(()=>{pending.delete(id);if(path!=='/api/auth/sms/login')window.HoYoNative.postMessage(JSON.stringify({id,method:'CANCEL'}));reject(Error('请求超时，请稍后重试；已保存的账号和数据不受影响'));},path==='/api/checkin/resign/confirm'?180000:path==='/api/checkin/resign/prepare'?100000:65000);
   pending.set(id,{resolve,reject,timer,path});
   window.HoYoNative.postMessage(JSON.stringify({id,path,method,body:body??{}}));
  });
 }
 function settle(event){
  let value;try{value=JSON.parse(event.data);}catch{return;}
  const p=pending.get(value.id);if(!p)return;pending.delete(value.id);clearTimeout(p.timer);
  value.ok?p.resolve(value.result):p.reject(Object.assign(Error(value.error||'原生操作失败'),{code:value.code,upstreamRetcode:value.upstreamRetcode,fpResultCode:value.fpResultCode}));
 }
 if(window.HoYoNative)window.HoYoNative.onmessage=settle;
 window.HoYoPlatform={request,imageUrl(a,r){return "https://appassets.androidplatform.net/profile-image?accountId="+encodeURIComponent(a.id)+(r?"&roleKey="+encodeURIComponent(r.game+":"+r.uid+":"+(r.serverId||r.server)):"");},rewardUrl(owner,key,index){return "https://appassets.androidplatform.net/reward-image?accountId="+encodeURIComponent(owner)+"&roleKey="+encodeURIComponent(key)+"&index="+index;},back(){const dialogs=[...document.querySelectorAll('dialog[open]')];if(dialogs.length){dialogs[dialogs.length-1].close();return true;}if(window.HoYoCheckin?.closeDrawer())return true;const menu=document.querySelector('#accountMenu');if(menu&&!menu.hidden){menu.hidden=true;return true;}return false;}};
 window.addEventListener('pagehide',()=>{for(const [id,p] of pending){clearTimeout(p.timer);p.reject(Error('页面已关闭'));if(p.path!=='/api/auth/sms/login')window.HoYoNative?.postMessage(JSON.stringify({id,method:'CANCEL'}));}pending.clear();});
})();
