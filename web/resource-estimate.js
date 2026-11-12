/* Immutable server observation; no network or mutation. */
(function(root){
 function estimateResource(snapshot,now,max){
  const unavailable=status=>({status,estimatedValue:null,remainingToNext:null,estimatedFullAt:null});
  if(!snapshot||snapshot.version!==1||snapshot.source!=='server_snapshot')return unavailable('no_snapshot');
  const {serverResourceValue:v,resourceMax:m,observedAtUtc:t,periodSeconds:p,remainingSeconds:r}=snapshot;
  if(!Number.isInteger(v)||!Number.isInteger(m)||m<=0||m>1000000||v<0||v>m||max!=null&&max!==m||
    !Number.isFinite(t)||t<946684800000||t>4102444800000||!Number.isFinite(now)||![360,480].includes(p))return unavailable('invalid_snapshot');
  const elapsed=(now-t)/1000;
  if(elapsed<0)return unavailable('clock_rollback');
  if(elapsed>366*86400)return unavailable('clock_jump');
  if(v===m)return {status:'full',estimatedValue:m,remainingToNext:0,estimatedFullAt:t};
  const deficit=m-v,phase=r-(deficit-1)*p;
  if(!Number.isFinite(r)||!snapshot.phaseAvailable||phase<=0||phase>p)return unavailable('phase_unavailable');
  const gain=elapsed<phase?0:1+Math.floor((elapsed-phase)/p);
  const value=Math.min(m,Math.max(0,v+gain));
  return {status:value===m?'full':'estimated',estimatedValue:value,remainingToNext:value===m?0:Math.max(0,Math.ceil(phase+gain*p-elapsed)),estimatedFullAt:t+r*1000};
 }
 function presentation(note,now){
  const estimate=estimateResource(note?.resourceSnapshot,now,note?.max),value=estimate.estimatedValue??note?.current;
  const full=Number.isFinite(value)&&Number.isFinite(note?.max)&&note.max>0&&value>=note.max;
  if(full)return {estimate,value,next:'已回满',eta:'无需恢复',etaTitle:'普通体力已达到上限'};
  if(estimate.estimatedValue==null||!Number.isFinite(estimate.remainingToNext)||!Number.isFinite(estimate.estimatedFullAt))return {estimate,value,next:value==null?'等待首次成功刷新':'下点时间不可用',eta:'回满时间不可用',etaTitle:'当前快照不足或时钟异常，无法可靠估计恢复时间'};
  const seconds=estimate.remainingToNext,date=new Date(estimate.estimatedFullAt),current=new Date(now);
  const options={month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit'};
  if(date.getFullYear()!==current.getFullYear())options.year='numeric';
  return {estimate,value,next:Math.floor(seconds/60)+':'+String(seconds%60).padStart(2,'0')+' 后回复1点',eta:date.toLocaleString('zh-CN',options)+' 回满',etaTitle:date.toLocaleString('zh-CN')+' 回满（本地时区）'};
 }
 root.HoYoResource={estimateResource,presentation};if(typeof module!=='undefined')module.exports=root.HoYoResource;
})(globalThis);
