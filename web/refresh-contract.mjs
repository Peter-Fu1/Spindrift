const states=new Set(['idle','refreshing','verification_required','verification_opening','waiting_for_user','verifying_result','saving_session','success','failed','cancelled']);
export function normalizeRefresh(value){
 if(!value||!/^[a-f0-9]{32}$/.test(value.refreshId||''))return null;
 const text=v=>typeof v==='string'?v.slice(0,80):'';
 const ms=v=>Number.isFinite(v)&&v>=0?Math.min(v,86400000):0;
 return {refreshId:value.refreshId,status:['running','completed','cancelled','failed'].includes(value.status)?value.status:'failed',
  trigger:['manual','login'].includes(value.trigger)?value.trigger:'background',interactionReady:value.interactionReady===true,elapsedMs:ms(value.elapsedMs),
  roles:(Array.isArray(value.roles)?value.roles:[]).slice(0,500).filter(r=>['genshin','starrail','zenless'].includes(r.game)).map(r=>({
   accountId:text(r.accountId),roleUid:text(r.roleUid),server:text(r.server),game:r.game,state:states.has(r.state)?r.state:'failed',
   error:/^[a-z_]{1,60}$/.test(r.error||'')?r.error:null,retcode:Number.isInteger(r.retcode)?r.retcode:null,
   durationMs:ms(r.durationMs),changedAt:Number.isSafeInteger(r.changedAt)?r.changedAt:null}))};
}
