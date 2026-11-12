(function(root){
 const roleKey=(a,r)=>a.id+'|'+r.game+':'+r.uid+':'+(r.serverId||r.server||'');
 function normalize(value={},accounts=[]){
  const ids=accounts.map(a=>a.id),keys=accounts.flatMap(a=>a.roles.map(r=>roleKey(a,r)));
  const clean=(v,valid)=>Array.isArray(v)?[...new Set(v)].filter(x=>valid.includes(x)):null;
  const order=clean(value.order,ids)||[];ids.forEach(id=>{if(!order.includes(id))order.push(id);});
  return {version:1,order,accountIds:clean(value.accountIds,ids),roleKeys:clean(value.roleKeys,keys),expanded:clean(value.expanded,ids)||[],mode:value.mode==='local'?'local':'server'};
 }
 function targets(accounts,prefs){return accounts.flatMap(a=>(prefs.accountIds===null||prefs.accountIds.includes(a.id))?a.roles.filter(r=>prefs.roleKeys===null||prefs.roleKeys.includes(roleKey(a,r))).map(r=>roleKey(a,r)):[]);}
 function move(order,id,to){const result=order.filter(x=>x!==id);result.splice(Math.max(0,Math.min(result.length,to)),0,id);return result;}
 function checkinKeys(accounts,selection={mode:'all'}){const keys=[...new Set(accounts.flatMap(a=>a.roles.filter(r=>['genshin','starrail','zenless'].includes(r.game)&&r.uid&&(r.serverId||r.server)).map(r=>roleKey(a,r))))];return selection.mode==='all'?keys:keys.filter(k=>Array.isArray(selection.roleKeys)&&selection.roleKeys.includes(k));}
 root.HoYoUi={roleKey,normalize,targets,move,checkinKeys};if(typeof module!=='undefined')module.exports=root.HoYoUi;
})(globalThis);
