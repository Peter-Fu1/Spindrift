/* Synthetic fixtures only: no account credentials, device identity or disk persistence. */
(function(root){
 'use strict';
 const scenarios=['default','genshin-only','errors','empty','long-text','checkin','login'];
 const key=(a,r)=>a.id+'|'+r.game+':'+r.uid+':'+r.serverId;
 const avatar=(label,color='#3276b1')=>'data:image/svg+xml,'+encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" width="64" height="64"><rect width="64" height="64" rx="32" fill="'+color+'"/><text x="32" y="43" text-anchor="middle" font-size="32" fill="white">'+label+'</text></svg>');
 function create(name='default',now=Date.now()){
  if(!scenarios.includes(name))name='default';
  const today=new Date(now+8*3600000).toISOString().slice(0,10),month=today.slice(0,7),day=Number(today.slice(-2));
  const days=new Date(Date.UTC(Number(today.slice(0,4)),Number(today.slice(5,7)),0)).getUTCDate();
  const account=(id,letter,games)=>{
   const roles=games.map((game,i)=>({game,uid:'DEMO-'+letter+'-'+(i+1),serverId:game==='genshin'?'cn_gf01':game==='starrail'?'prod_gf_cn':'prod_gf_cn',server:name==='long-text'?'虚拟区服名称很长 · 天空岛与星穹列车新艾利都展示区域':['天空岛','星穹列车','新艾利都'][i],nickname:name==='long-text'?'这是用来检验手机换行的很长很长的虚拟旅行者昵称':'虚拟旅行者 '+letter,level:game==='genshin'?60:70}));
   return {id,label:name==='long-text'?'虚拟米游社账号 '+letter+' · 用于验证很长昵称与混合 ABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890 文本布局':'虚拟账号 '+letter+'（仅预览）',avatar:true,avatarState:'loaded',profileState:'loaded',roles,roleProfiles:Object.fromEntries(roles.map(r=>[r.game+':'+r.uid+':'+r.serverId,{avatar:true,state:'loaded'}])),games:roles.map((r,i)=>{
    const max=r.game==='genshin'?200:300,current=[116,123,228][i],period=r.game==='genshin'?480:360,observed=now-20000;
    return {id:r.game,roleUid:r.uid,serverId:r.serverId,server:r.server,nickname:r.nickname,level:r.level,status:'ok',accent:['#d19662','#8263cf','#39a49c'][i],resource:['原粹树脂','开拓力','电量'][i],current,max,updated:observed,lastSuccessfulFetchAt:observed,dailyCurrent:i===0?4:300,dailyMax:i===0?4:i===1?500:400,weeklyRemaining:3-i,weeklyMax:3,cycleCurrent:i===1?12000:4000,cycleMax:r.game==='starrail'?18000:8000,task:'已完成',taskState:'done',...(r.game==='starrail'?{reserve:520}:{}),resourceSnapshot:{version:1,source:'server_snapshot',serverResourceValue:current,resourceMax:max,observedAtUtc:observed,periodSeconds:period,remainingSeconds:(max-current)*period-60,phaseAvailable:true}};
   })};
  };
  const accounts=name==='empty'?[]:name==='genshin-only'?[account('DEMO-B','B',['genshin'])]:[account('DEMO-A','A',['genshin','starrail','zenless']),account('DEMO-B','B',['genshin']),account('DEMO-C','C',['genshin','starrail','zenless'])];
  if(name==='errors'){
   accounts[0].games.forEach((g,i)=>Object.assign(g,{status:['account_risk','risk_control_required','account_restricted'][i],retcode:[5003,1034,10041][i],stale:true,lastError:['account_risk','risk_control_required','account_restricted'][i]}));
   accounts[1].games[0]={id:'genshin',roleUid:accounts[1].roles[0].uid,serverId:'cn_gf01',status:'auth_expired',retcode:-100};
   accounts[2].games[0].status='network_error';
  }
  const state={accounts,selectedId:accounts[0]?.id||null,runtimeVersion:'UI Preview',backendVersion:'虚拟内存适配器',uiPreferences:{version:1,order:accounts.map(a=>a.id),expanded:accounts.length?[accounts[0].id]:[],mode:'server'}};
  const ledger={version:1,roles:{}};
  accounts.forEach(a=>a.roles.forEach((r,i)=>{ledger.roles[key(a,r)+'|CN']={enabled:i===0,state:i===0?'already_signed':i===1?'not_signed':'blocked',...(i===2?{code:10041}:{}),checkedAt:now,info:{today,isSign:i===0,total:i===0?day:Math.max(0,day-1)},home:{month,days,awards:Array.from({length:days},(_,n)=>({name:['虚拟原石','虚拟信用点','虚拟养成材料'][n%3],count:(n+1)*10,icon:true}))}};}));
  return {state,ledger,scenario:name};
 }
 const api={create,scenarios,key,avatar};if(typeof module!=='undefined')module.exports=api;else root.HoYoPreviewFixtures=api;
})(globalThis);
