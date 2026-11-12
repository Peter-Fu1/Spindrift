// Platform-neutral view contract; keep the existing game normalizer and refresh states.
import {normalizeGames} from '../normalization.mjs';
import {normalizeRefresh} from '../refresh-contract.mjs';

/** @typedef {'genshin'|'starrail'|'zenless'} GameId */
/** @typedef {{accountId:string,game:GameId,roleUid:string,server:string}} RoleKey */
/** @typedef {ReturnType<typeof normalizeGames>[number]} Note */
/** @typedef {'unknown'|'authenticated'|'expired'} AuthState */
/** @typedef {'unknown'|'clear'|'account_risk'|'verification_required'} RiskState */
/** @typedef {'idle'|'required'|'waiting_for_user'|'completed'|'cancelled'|'expired'|'unavailable'} VerificationState */
/**
 * A persisted snapshot contains ONLY normalized public note data, never credentials.
 * @typedef {{version:1,key:RoleKey,note:Note|null,lastSuccessfulAt:number|null,
 * recoveryAt:number|null,estimatedCurrent:number|null,estimated:boolean,
 * auth:AuthState,risk:RiskState,verification:VerificationState,
 * stale:boolean,error:string|null}} RoleSnapshot
 */
/** @typedef {{id:string,label:string,avatar:string|null,auth:AuthState}} AccountSummary */
/**
 * Platform port design (not an implemented Android backend):
 * AccountRepository.list({signal}) -> Promise<AccountSummary[]>; roles(accountId,{signal}) -> RoleKey[]
 * CredentialStore: native-only put/read/remove(accountId), never expose a read method to UI JS.
 * DailyNoteProvider.refresh(key,{signal}) -> Promise<RoleSnapshot>; cache.get/commit(key,snapshot).
 * VerificationCoordinator.open(key,{signal}) -> Promise<VerificationState>, foreground/user-only.
 * BrowserSessionProvider.acquire(accountId,{signal}) -> native account-owned handle; release(handle).
 * RefreshScheduler.schedule/cancel(key,preferences), initial state is disabled.
 * DiagnosticsProvider.emit(allowlistedEvent), no credentials, UID or proof values.
 * These map to existing AccountRuntime/AccountStore/RefreshJobs, not a second Windows engine.
 */
const games=new Set(['genshin','starrail','zenless']);
const verifications=new Set(['idle','required','waiting_for_user','completed','cancelled','expired','unavailable']);
const timestamp=v=>Number.isSafeInteger(v)&&v>0;

/** Reject display server names: a role's server must be the actual API region code. */
export function roleKey(value){
 if(!value||!/^\d{1,24}$/.test(value.accountId||'')||!/^\d{1,24}$/.test(value.roleUid||'')||!games.has(value.game)||!/^[a-z0-9_]{1,40}$/.test(value.server||''))throw TypeError('INVALID_ROLE_KEY');
 return {accountId:value.accountId,game:value.game,roleUid:value.roleUid,server:value.server};
}
export function roleIdentity(value){const k=roleKey(value);return JSON.stringify([k.accountId,k.game,k.roleUid,k.server]);}

/** UI adapter for the existing Windows accountList response, with no credential projection. */
export function accountSummary(value){
 if(!value||!/^\d{1,24}$/.test(value.id||''))throw TypeError('INVALID_ACCOUNT');
 return {id:value.id,label:String(value.label||'米游社账号').slice(0,80),
  avatar:typeof value.avatar==='string'&&/^https:\/\//.test(value.avatar)?value.avatar:null,
  auth:value.auth==='expired'?'expired':value.auth==='authenticated'?'authenticated':'unknown'};
}

/** @returns {RoleSnapshot} */
export function roleSnapshot(key,input,{previous=null,now=Date.now(),verification='idle'}={}){
 key=roleKey(key);
 if(previous&&roleIdentity(previous.key)!==roleIdentity(key))throw TypeError('CROSS_ROLE_CACHE');
 if(input?.id!==key.game||String(input?.roleUid)!==key.roleUid)throw TypeError('CROSS_ROLE_NOTE');
 if(!timestamp(now)||!verifications.has(verification))throw TypeError('INVALID_SNAPSHOT_CONTEXT');
 const normalized=normalizeGames([input],now)[0];
 const valid=normalized.status==='ok'&&normalized.current!==null&&normalized.max>0&&timestamp(input.updated)&&input.updated<=now;
 const note=valid?normalized:previous?.note||null;
 const error=normalized.status==='ok'?normalized.lastError:normalized.status;
 const lastSuccessfulAt=valid?input.updated:previous?.lastSuccessfulAt||null;
 const recoveryAt=note&&lastSuccessfulAt&&note.interval?lastSuccessfulAt+(note.max-note.current)*note.interval*1000:null;
 const estimatedCurrent=note?Math.min(note.max,note.current+(note.interval&&lastSuccessfulAt?Math.floor(Math.max(0,now-lastSuccessfulAt)/(note.interval*1000)):0)):null;
 return {version:1,key,note,lastSuccessfulAt,recoveryAt,estimatedCurrent,
  estimated:note!==null&&now>lastSuccessfulAt,auth:error==='auth_expired'?'expired':valid&&!normalized.stale?'authenticated':'unknown',
  risk:error==='account_risk'?'account_risk':error==='risk_control_required'?'verification_required':valid&&!normalized.stale?'clear':'unknown',
  verification,stale:!valid||normalized.stale||normalized.cached,error:error==='ok'?null:error};
}

/** Same progressive/partial-success protocol on both platforms. */
export const refreshStatus=normalizeRefresh;

/** Ports must propagate AbortSignal to native work; cancelling JS alone is insufficient. */
export async function refreshRole(provider,key,{signal}={}){
 signal?.throwIfAborted();
 const result=await provider.refresh(roleKey(key),{signal});
 signal?.throwIfAborted();
 if(!result||result.version!==1||roleIdentity(result.key)!==roleIdentity(key))throw TypeError('INVALID_ROLE_RESULT');
 return result;
}

/** Browser isolation is a capability, never a best-effort CookieManager clear/swap. */
export function browserIsolation({multiProfile=false,isolatedProcess=false}={}){
 return multiProfile?'named_profile':isolatedProcess?'process_directory':'unavailable';
}
