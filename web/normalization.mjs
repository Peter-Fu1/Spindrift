const allowedIds=new Set(['genshin','starrail','zenless']);
const allowedStatuses=new Set(['ok','unknown','expired','unavailable','notes_not_enabled','auth_expired','account_risk','risk_control_required','upstream_rate_limited','no_role','role_mapping_error','network_error','notes_access_denied','request_invalid','verification_pending','cancelled','session_persist_failed','verification_timeout','verification_page_unavailable','browser_crashed','response_invalid','http_error','upstream_rejected']);
const defaultNames={genshin:['原神','Teyvat · 提瓦特','原粹树脂','✧','#d9955f','#fff2e9'],starrail:['崩坏：星穹铁道','Astral Express · 星穹列车','开拓力','✦','#7768d9','#f0edff'],zenless:['绝区零','New Eridu · 新艾利都','电量','◈','#4c9f84','#e9f5f1']};
const validNumber=(v,min=0,max=1_000_000)=>Number.isFinite(v)&&v>=min&&v<=max?v:null;
const validColor=(v,fallback)=>typeof v==='string'&&/^#[0-9a-f]{6}$/i.test(v)?v:fallback;

export function normalizeGames(input,updatedAt=Date.now()){
  if(!Array.isArray(input))throw new TypeError('UNSUPPORTED_RESPONSE');
  return input.filter(g=>g&&allowedIds.has(g.id)).map(g=>{
    const [name,subtitle,resource,icon,accent,tint]=defaultNames[g.id];
    const current=validNumber(g.current),maximum=validNumber(g.maximum??g.max);
    const taskState=g.status&&g.status!=='ok'?'unknown':['done','pending','unknown'].includes(g.taskState)?g.taskState:'unknown';
    return {
      id:g.id,name,subtitle,resource,icon,accent,tint,roleUid:/^\d{1,24}$/.test(g.roleUid||'')?g.roleUid:null,
      nickname:String(g.nickname||'').slice(0,80),server:String(g.server||'').slice(0,80),level:validNumber(g.level),
      ...Object.fromEntries(['dailyCurrent','dailyMax','weeklyRemaining','weeklyMax','cycleCurrent','cycleMax','reserve'].map(k=>[k,validNumber(g[k])])),
      current:current===null||maximum===null?null:Math.min(current,maximum),
      max:maximum,
      interval:validNumber(g.interval,1,86_400),
      updated:validNumber(g.updated,0,Number.MAX_SAFE_INTEGER)||updatedAt,
      task:['done','pending','unknown'].includes(taskState)?({done:'已完成',pending:'待完成',unknown:'状态未知'}[taskState]):'状态未知',
      taskState,
      status:allowedStatuses.has(g.status)?g.status:'unknown',
      cached:g.status==='ok'&&g.cached===true,
      stale:g.status==='ok'&&g.stale===true,
      lastError:allowedStatuses.has(g.lastError)?g.lastError:null,
      source:g.status==='ok'&&g.source==='miyoushe_webview'?'miyoushe_webview':'sdk',
      retcode:Number.isInteger(g.retcode)?g.retcode:null
    };
  });
}
