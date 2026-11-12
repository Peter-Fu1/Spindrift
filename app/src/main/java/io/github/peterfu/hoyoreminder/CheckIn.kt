package io.github.peterfu.hoyoreminder
import org.json.JSONObject
import org.json.JSONArray
import java.time.Instant
import java.time.ZoneOffset
import java.time.LocalDate
import java.net.URLEncoder

data class SignInActivity(val base:String,val actId:String,val signgame:String)
object CheckInProtocol {
 fun config(game:Game)=when(game){
  Game.GENSHIN->SignInActivity("https://api-takumi.mihoyo.com/event/luna/","e202311201442471","hk4e")
  Game.STARRAIL->SignInActivity("https://api-takumi.mihoyo.com/event/luna/","e202304121516551","hkrpg")
  Game.ZENLESS->SignInActivity("https://act-nap-api.mihoyo.com/event/luna/zzz/","e202406242138391","zzz")
 }
 fun hasDayRollover(now:Long)=Instant.ofEpochMilli(now).atOffset(ZoneOffset.ofHours(8)).toLocalTime()>=java.time.LocalTime.of(0,1)
 fun day(now:Long)=Instant.ofEpochMilli(now).atOffset(ZoneOffset.ofHours(8)).toLocalDate().toString()
 fun key(owner:String,role:Role)=UiPreferences.key(owner,role)+"|CN"
 fun risk(data:JSONObject)=data.optInt("success")==1||data.optInt("risk_code")!=0||data.optBoolean("is_risk")||listOf("gt","challenge").any{data.optString(it).isNotBlank()}
 fun classify(code:Int)=when(code){-5003->"already_signed";-100,-111,10001->"login_expired";5003,10041,10035,1034,-3101->"blocked";else->"failed"}
 fun info(data:JSONObject,role:Role,now:Long):JSONObject {
  val date=runCatching{LocalDate.parse(data.getString("today"))}.getOrNull()?:throw PublicError("RESPONSE_INVALID")
  if(data.opt("is_sign") !is Boolean||DeviceFingerprint.int(data.opt("total_sign_day"))==null||data.getInt("total_sign_day") !in 0..date.lengthOfMonth())throw PublicError("RESPONSE_INVALID")
  if(data.has("region")&&data.optString("region").isNotBlank()&&data.optString("region")!=role.server)throw PublicError("RESPONSE_INVALID")
  if(listOf("uid","game_uid").any{data.has(it)&&data.optString(it)!=role.uid})throw PublicError("RESPONSE_INVALID")
  val current=date.toString()==day(now)
  return obj("today" to date.toString(),"isSign" to data.getBoolean("is_sign"),"total" to data.getInt("total_sign_day"),
   "state" to if(!current)"unknown" else if(data.getBoolean("is_sign"))"already_signed" else "not_signed")
 }
 fun home(data:JSONObject,today:String):JSONObject {
  val date=LocalDate.parse(today);val month=DeviceFingerprint.int(data.opt("month"))
  if(month!=date.monthValue)throw PublicError("RESPONSE_INVALID")
  val awards=data.optJSONArray("awards")?:throw PublicError("RESPONSE_INVALID")
  if(awards.length() !in 1..31)throw PublicError("RESPONSE_INVALID")
  return obj("month" to today.take(7),"days" to date.lengthOfMonth(),"awards" to JSONArray(awards.objects().map{
   obj("name" to (it.opt("name") as? String).orEmpty().take(100),"count" to DeviceFingerprint.int(it.opt("cnt"))?.takeIf{v->v in 0..1000000},
    "icon" to (SafeImages.url(it.optString("icon"))?:""))
  }))
 }
}
class SignInClient(private val api:ApiClient){
 fun call(account:Account,role:Role,op:String,scope:String,beforeSend:()->Unit={}):WireResponse {
  require(op in setOf("home","info","sign","resign_info","resign"));if(!Role.valid(role.game,role.uid,role.server))throw PublicError("RESPONSE_INVALID")
  val s=account.secret?:throw PublicError("AUTH_EXPIRED")
  if(ApiClient.verifiedCookies(s.getJSONObject("cookies"))!=account.id)throw PublicError("AUTH_EXPIRED")
  val fp=DeviceFingerprint.formal(s)?:throw PublicError("FP_RESPONSE_INVALID")
  val c=CheckInProtocol.config(role.game)
  // Deliberately no DS and no note adapter. Same frozen UA, account-owned canonical FP/cookies.
  val h=mapOf("User-Agent" to Protocol.USER_AGENT,"x-rpc-app_version" to Protocol.VERSION,"x-rpc-client_type" to "5",
   "x-rpc-device_id" to s.getString("deviceId"),"x-rpc-device_fp" to fp,"x-rpc-signgame" to c.signgame,
   "Referer" to "https://webstatic.mihoyo.com/","Origin" to "https://act.mihoyo.com","X-Requested-With" to "com.mihoyo.hyperion",
   "Cookie" to ApiClient.cookieHeader(DeviceFingerprint.cookies(s)))
  val query="?lang=zh-cn&act_id="+c.actId+(if(op=="home")"" else "&region="+URLEncoder.encode(role.server,"UTF-8")+"&uid="+role.uid)
  return api.transport.executeCheckin(if(op in setOf("sign","resign"))"POST" else "GET",c.base+op+(if(op in setOf("sign","resign"))"" else query),h,
   if(op in setOf("sign","resign"))obj("act_id" to c.actId,"region" to role.server,"uid" to role.uid) else null,scope,beforeSend)
 }
}
class CheckInAudit(val id:String,val trigger:String,val metrics:JSONObject,val refs:Map<String,String>)
/** One process-wide gate covers Activity and WorkManager instances; ledger never writes credentials or notes. */
class CheckInService(private val store:AccountPersistence,private val api:ApiClient,private val clock:()->Long={System.currentTimeMillis()}){
 companion object {private val gate=java.util.concurrent.locks.ReentrantLock()}
 private inline fun<T> exclusive(wait:Boolean=false,block:()->T):T {
  if(wait)gate.lockInterruptibly()
  if(!wait&&!gate.tryLock())throw PublicError("REFRESH_IN_PROGRESS")
  return try{block()}finally{gate.unlock()}
 }
 var onCompleted:((JSONObject)->Unit)?=null
 var workerReference:String?=null
 private val client=SignInClient(api)
 private fun targets()=store.load().flatMap{a->a.metadata.optJSONArray("roles")?.objects().orEmpty().mapNotNull{r->runCatching{a to Role.fromSaved(r)}.getOrNull()}}
 private fun ledger():JSONObject {
  val value=store.loadCheckin();val rows=value.optJSONObject("roles")?:JSONObject().also{value.put("roles",it)}
  val known=targets().map{CheckInProtocol.key(it.first.id,it.second)}.toSet()
  rows.keys().asSequence().toList().filter{it !in known}.forEach{rows.remove(it)}
  value.put("version",1);return value
 }
 private fun save(value:JSONObject){value.put("statusRevision",store.loadCheckin().optLong("statusRevision")+1);store.saveCheckin(value)}
 // Persisted truth for one business day. Kept apart from this run's check outcome so a later
 // throttled/transient/late result can never turn an official confirmation into "not signed".
 private fun confirmedSigned(row:JSONObject,today:String)=row.optJSONObject("confirmed")?.let{it.optString("day")==today&&it.optBoolean("isSigned")}==true
 private fun recordConfirmed(row:JSONObject,today:String,isSigned:Boolean){row.put("confirmed",obj("day" to today,"isSigned" to isSigned,"at" to clock()))}
 private fun applyOutcome(row:JSONObject,next:String,today:String){
  if(confirmedSigned(row,today)&&next in setOf("throttled","rate_limited","cooldown","failed","unconfirmed"))row.put("check",obj("state" to next,"day" to today,"at" to clock()))
  else row.put("state",next)
 }
 fun view():JSONObject=run{val v=ledger();val now=clock()
  v.getJSONObject("roles").keys().forEach{k->val row=v.getJSONObject("roles").getJSONObject(k)
   if(row.optJSONObject("info")?.optString("today")!=CheckInProtocol.day(now)&&row.optString("state") in setOf("signed","already_signed","not_signed"))row.put("state","unknown")
   if(row.optJSONObject("check")?.optString("day")!=CheckInProtocol.day(now))row.remove("check")
  };v
 }
 fun reward(owner:String,roleKey:String,index:Int):Pair<String,String>?=run{
  if(index !in 0..30)return@run null
  val pair=targets().firstOrNull{it.first.id==owner&&it.second.key()==roleKey}?:return@run null
  val row=ledger().getJSONObject("roles").optJSONObject(CheckInProtocol.key(owner,pair.second))?:return@run null
  val home=row.optJSONObject("home")?:return@run null
  val icon=home.optJSONArray("awards")?.optJSONObject(index)?.optString("icon")?:return@run null
  SafeImages.url(icon)?.let{it to ("reward:"+roleKey+":"+home.optString("month")+":"+index)}
 }
 fun configure(body:JSONObject):JSONObject=exclusive{
  val chosen=body.getJSONArray("roleKeys").let{(0 until it.length()).map{n->it.getString(n)}};val all=targets()
  val known=all.associateBy{UiPreferences.key(it.first.id,it.second)}
  if(chosen.distinct().size!=chosen.size||chosen.any{it !in known}||body.opt("enabled") !is Boolean)throw PublicError("RESPONSE_INVALID")
  val v=ledger();val rows=v.getJSONObject("roles");chosen.forEach{k->val pair=known.getValue(k);val id=CheckInProtocol.key(pair.first.id,pair.second)
   val row=rows.optJSONObject(id)?:JSONObject();row.put("enabled",body.getBoolean("enabled"))
   if(body.getBoolean("enabled"))row.put("nextAt",clock()+180000+(Math.random()*300000).toLong())
   rows.put(id,row)
  };v.put("configurationRevision",v.optLong("configurationRevision")+1);save(v)
  chosen.forEach{k->val pair=known.getValue(k);api.diagnostics.event("checkin_scheduled",pair.second.game,trace=api.diagnostics.trace(pair.first.id,pair.second.key(),"schedule"),stage="persist",source="store")}
  view()
 }
 fun run(body:JSONObject,automatic:Boolean=false,progress:(String,String)->Unit={_,_->},queued:Boolean=false,identities:Map<String,String>?=null,epochs:Map<String,Long>?=null,autoKeys:Set<String>?=null,audit:CheckInAudit?=null,valid:()->Boolean={true}):JSONObject=exclusive(automatic||queued){
  val all=targets();val ids=body.optJSONArray("roleKeys")?.let{a->(0 until a.length()).map{a.getString(it)}}?:if(automatic)emptyList() else throw PublicError("RESPONSE_INVALID")
  val known=all.associateBy{UiPreferences.key(it.first.id,it.second)}
  if(ids.size>64||ids.distinct().size!=ids.size||identities==null&&ids.any{it !in known})throw PublicError("RESPONSE_INVALID")
  val op=if(automatic)"sign" else body.optString("operation","info");if(op !in setOf("info","sign"))throw PublicError("RESPONSE_INVALID")
  var v=ledger();val rows=v.getJSONObject("roles");val started=clock()
  for((uiKey,pair) in known){
   if(!valid()||Thread.currentThread().isInterrupted||clock()-started>240000)break
   if(autoKeys!=null&&uiKey !in ids)continue
   var automatic=if(autoKeys==null)automatic else uiKey in autoKeys
   var op=if(autoKeys==null)op else if(automatic)"sign" else "info"
   val (a,role)=pair;val k=CheckInProtocol.key(a.id,role);val row=rows.optJSONObject(k)?:obj("enabled" to false)
   if(automatic&&body.has("roleKeys")&&uiKey !in ids)continue
   if(identities!=null&&identities[uiKey]!=a.secret.toString()||epochs!=null&&!AccountEpoch.unchanged(a.id,epochs)){progress(uiKey,"cancelled");continue}
   CheckInRetry.normalize(row,clock())
   if(automatic&&!row.optBoolean("enabled")){if(autoKeys!=null&&body.optJSONArray("selectedKeys")?.let{v->(0 until v.length()).any{v.optString(it)==uiKey}}==true){automatic=false;op="info"}else{progress(uiKey,"disabled");continue}}
   val last=row.optLong("lastAutoAt");val limited=automatic&&last>0&&clock()-last<CheckInRetry.MIN_INTERVAL
   val policy=row.optJSONObject("retry");val serverWait=(policy?.optString("kind")=="server_throttle"&&policy.optLong("until")>clock())||(policy?.optLong("serverWaitUntil")?:0)>clock()
   // A role already confirmed signed for the current business day is terminal: a redundant trigger
   // (second refresh, duplicate same-day operation) must never overwrite it with throttled/rate_limited.
   val settledSigned=confirmedSigned(row,CheckInProtocol.day(clock()))
   if((limited||serverWait)&&settledSigned){
    progress(uiKey,"already_signed")
    if(audit!=null){auditEvent("checkin_cooldown_evaluated",audit,CheckInRetry.fields(row,clock()).put("role_ref",audit.refs[uiKey]).put("allowed",false));auditEvent("checkin_auto_decision",audit,obj("role_ref" to audit.refs[uiKey],"decision" to "already_signed","phase" to "final_decision","valid" to true).put("confirmed_signed_today",true))}
    continue
   }
   if(limited||serverWait){
    row.optJSONObject("info")?.put("fresh",false)
    applyOutcome(row,if(serverWait)"rate_limited" else "throttled",CheckInProtocol.day(clock()))
    row.put("nextAt",maxOf(if(serverWait)policy!!.optLong("until") else 0,last+CheckInRetry.MIN_INTERVAL))
    rows.put(k,row);save(v);progress(uiKey,row.getString("state"))
    if(audit!=null){auditEvent("checkin_cooldown_evaluated",audit,CheckInRetry.fields(row,clock()).put("role_ref",audit.refs[uiKey]).put("allowed",false));auditEvent("checkin_auto_decision",audit,obj("role_ref" to audit.refs[uiKey],"decision" to row.getString("state"),"phase" to "final_decision","valid" to false).put("confirmed_signed_today",confirmedSigned(row,CheckInProtocol.day(clock()))))};continue
   }
   if(!automatic&&uiKey !in ids)continue
   rows.put(k,row);progress(uiKey,"querying")
   val roleEpochs=AccountEpoch.snapshot()
   var officialDay:String?=null;var lastOperation="initial_status";var failure:Exception?=null;var initialReported=false;var initialSent=false
   val now=clock();val trace=api.diagnostics.trace(a.id,role.key(),"checkin")
   fun event(name:String,code:Int?=null){api.diagnostics.event(name,role.game,retcode=code,trace=trace,source=if(automatic)"store" else "native_api",stage="response")}
   // Credentials/role may change during network; every mutation/send checks fresh owner and secret.
   fun live():Boolean=AccountEpoch.unchanged(a.id,roleEpochs)&&store.load().firstOrNull{it.id==a.id}?.let{current->current.secret?.toString()==a.secret?.toString()&&current.metadata.getJSONArray("roles").objects().any{Role.fromSaved(it).key()==role.key()}}==true
   fun call(operation:String):JSONObject {
    lastOperation=if(operation=="info"&&row.optString("state")=="verifying_result")"post_sign_verify" else if(operation=="sign")"sign" else "initial_status"
    if(!valid()||!live()||Thread.currentThread().isInterrupted||automatic&&!ledger().getJSONObject("roles").optJSONObject(k).let{it?.optBoolean("enabled")==true})throw PublicError("CANCELLED")
    if(operation=="sign"&&(officialDay!=CheckInProtocol.day(clock())))throw CheckInError("server_day_mismatch")
    if(operation=="sign"&&automatic&&!CheckInProtocol.hasDayRollover(clock()))throw CheckInError("skipped_before_0001")
    val phase=if(operation=="info"&&row.optString("state")=="verifying_result")"post_sign_verify" else "initial_status"
    val rid=java.util.UUID.randomUUID().toString().replace("-","")
    fun observe(name:String,http:Int?=null,code:Int?=null,sent:Boolean=false){if(audit!=null&&operation in setOf("info","sign")){
     auditEvent(name,audit,obj("role_ref" to audit.refs[uiKey],"status_request_id" to rid,"source_status_request_id" to row.optString("statusRequestId"),"phase" to phase,"source" to "official","sent" to sent,"http_status" to http,"retcode" to code,"shared_inflight" to false))
    }}
    if(operation=="info"){if(phase=="initial_status")row.put("statusRequestId",rid).put("operationId",audit?.id?:rid) else row.put("verifyRequestId",rid)}
    val response=try{client.call(a,role,operation,"checkin:"+k){
     if(operation=="info"&&phase=="initial_status")initialSent=true
     if(operation=="info"&&phase=="initial_status"&&automatic){row.put("lastAutoAt",clock());save(v)}
     if(audit!=null&&operation in setOf("info","sign")){val counter=if(operation=="sign")"sign_sends" else if(phase=="initial_status")"initial_sends" else "verify_sends";audit.metrics.put(counter,audit.metrics.optInt(counter)+1)}
     observe(if(operation=="info")"checkin_info_requested" else "checkin_sign_result",sent=true)
    }}catch(e:Exception){observe(if(operation=="info")"checkin_info_completed" else "checkin_sign_result",(e as? CheckInHttpFailure)?.status?:(e as? PublicError)?.httpStatus);throw e}
    observe(if(operation=="info")"checkin_info_completed" else "checkin_sign_result",response.httpStatus,DeviceFingerprint.int(response.json.opt("retcode")))

    if(!valid()||!live())throw PublicError("CANCELLED")
    if(response.httpStatus !in 200..299)throw CheckInHttpFailure(response.httpStatus,CheckInRetry.retryAfter(response.headers.entries.firstOrNull{it.key.equals("Retry-After",true)}?.value?.firstOrNull(),clock()),response.headers.keys.any{it.equals("x-rpc-aigis",true)})
    val code=response.json.getInt("retcode");event(if(operation=="sign")"checkin_post_result" else "checkin_info_result",code)
    if(response.headers.keys.any{it.equals("x-rpc-aigis",true)})throw CheckInError("blocked",code)
    if(code!=0)throw CheckInError(CheckInProtocol.classify(code),code)
    val data=response.json.optJSONObject("data")?:throw PublicError("RESPONSE_INVALID")
    if(operation=="info"&&CheckInProtocol.risk(data))throw CheckInError("blocked",code)
    return data
   }
   row.optJSONObject("info")?.put("fresh",false)
   row.remove("code")
   row.put("source",if(automatic)body.optString("trigger","resource_manual_refresh") else "manual").put("checkedAt",now)
   try{
    val info=CheckInProtocol.info(call("info"),role,clock()).put("source","official").put("queriedAt",clock()).put("fresh",true).put("statusRequestId",row.optString("statusRequestId")).put("operationId",row.optString("operationId"));info.put("fresh",info.optString("today")==CheckInProtocol.day(clock()));officialDay=info.getString("today");row.put("info",info).put("state",info.getString("state"));row.remove("code");if(info.optBoolean("fresh")){CheckInRetry.rolloverPending(row,info.getString("today"));recordConfirmed(row,info.getString("today"),info.getString("state")=="already_signed")};if(info.getString("state")=="already_signed")row.put("postPending",false)
    if(audit!=null)auditEvent("checkin_initial_status_result",audit,obj("role_ref" to audit.refs[uiKey],"source_status_request_id" to row.optString("statusRequestId"),"phase" to "initial_status","source" to "official","valid" to true,"server_day_match" to info.optBoolean("fresh"),"is_signed" to info.getBoolean("isSign"),"sent" to initialSent));initialReported=true
    if(automatic&&info.getString("today")!=CheckInProtocol.day(clock()))throw CheckInError("server_day_mismatch")
    if(op=="sign"&&info.getString("state")=="already_signed"){event("checkin_skipped_signed")}
    val prior=row.optJSONObject("retry")
    if(info.getString("state")=="already_signed"||prior?.optString("kind")=="auth"||prior?.optString("kind") in CheckInRetry.soft+setOf("legacy_unknown","invalid","server_rejected")&&prior!!.optLong("until")<=clock()){
     val cleared=CheckInRetry.fields(row,clock());if(row.remove("retry")!=null&&audit!=null)auditEvent(if(cleared.optLong("remaining_sec")==0L)"checkin_cooldown_expired" else "checkin_cooldown_cleared",audit,cleared.put("role_ref",audit.refs[uiKey]).put("allowed",true))
    }
    val blocked=if(automatic)CheckInRetry.gate(row,clock()) else null
    if(audit!=null)auditEvent("checkin_cooldown_evaluated",audit,CheckInRetry.fields(row,clock()).put("role_ref",audit.refs[uiKey]).put("allowed",blocked==null))
    if(automatic&&!CheckInProtocol.hasDayRollover(clock()))throw CheckInError("skipped_before_0001")
    if(blocked!=null&&info.getString("state")!="already_signed")throw CheckInError(blocked)
    if(op=="sign"&&info.getString("state")=="not_signed"){
     val posted=row.optString("postedDay").ifBlank{if(row.optLong("postedAt")>0)CheckInProtocol.day(row.getLong("postedAt")) else ""}
     if(posted>=CheckInProtocol.day(clock())||row.optBoolean("postPending")&&posted.isBlank())throw CheckInError("unconfirmed")
     // Write intent BEFORE the non-idempotent POST; crash/timeout never blindly replays it.
     row.put("postPending",true).put("postedAt",clock()).put("postedDay",CheckInProtocol.day(clock())).put("state","signing");save(v)
     var accepted=true;var already=false
     try{val result=call("sign");if(CheckInProtocol.risk(result))throw CheckInError("blocked")}
     catch(e:CheckInError){if(e.state!="already_signed")throw e else already=true}
     catch(e:Exception){if(e is CheckInHttpFailure&&(e.status==429||e.retryAfter>0))throw e;accepted=false}
     if(!live()||!valid())continue
     row.put("state","verifying_result");save(v)
     val confirmed=CheckInProtocol.info(call("info"),role,clock()).put("source","official").put("phase","post_sign_verify").put("queriedAt",clock()).put("statusRequestId",row.optString("verifyRequestId")).put("operationId",row.optString("operationId"));confirmed.put("fresh",confirmed.optString("today")==CheckInProtocol.day(clock()));row.put("info",confirmed)
     if(confirmed.getString("state")=="already_signed"){row.put("state",if(already||!accepted)"already_signed" else "signed").put("postPending",false);recordConfirmed(row,confirmed.getString("today"),true)}
     else throw CheckInError(if(accepted)"unconfirmed" else "unconfirmed")
    }
    val date=row.optJSONObject("info")?.optString("today").orEmpty()
    if(date.length==10&&row.optJSONObject("info")?.optString("state")!="unknown"&&(!automatic||op=="info"||body.optJSONArray("selectedKeys")?.let{v->(0 until v.length()).any{v.optString(it)==uiKey}}==true)&&row.optJSONObject("home")?.optString("month")!=date.take(7)){
     try{row.put("home",CheckInProtocol.home(call("home"),date)).put("homeAt",clock())}catch(e:Exception){row.put("homeError",true)}
    }
   }catch(e:Exception){
    failure=e
    applyOutcome(row,if(e is CheckInError)e.state else if(e is CheckInHttpFailure&&e.risk)"blocked" else if(e is CheckInHttpFailure&&e.status==429)"rate_limited" else if(e is CheckInHttpFailure&&e.status==401||e is PublicError&&e.code=="AUTH_EXPIRED")"login_expired" else if(e is CheckInHttpFailure&&e.status==403)"blocked" else if(row.optBoolean("postPending"))"unconfirmed" else "failed",CheckInProtocol.day(clock()))
    (e as? CheckInError)?.code?.let{row.put("code",it)}
    if(e is CheckInError&&e.state in setOf("blocked","login_expired"))row.put("postPending",false)
    if(row.optString("state")=="blocked")event("checkin_blocked")
   }
   if(!live()||!valid())continue // cancelled/deleted/relogged requests cannot commit late results
   val state=row.optString("state")
   val error=failure
   if(state in setOf("signed","already_signed")){
    row.remove("retry");val tomorrow=Instant.ofEpochMilli(clock()).atOffset(ZoneOffset.ofHours(8)).toLocalDate().plusDays(1).atStartOfDay().toInstant(ZoneOffset.ofHours(8)).toEpochMilli();row.put("nextAt",tomorrow+180000+(Math.random()*300000).toLong())
   }else if(error!=null&&state !in setOf("cooldown","cancelled","skipped_before_0001","server_day_mismatch")){
    val kind=when{row.optBoolean("postPending")->"pending";state=="blocked"->"risk";state=="login_expired"->"auth";error is CheckInHttpFailure&&(error.status==429||error.retryAfter>0)->"server_throttle";error is CheckInHttpFailure&&error.status in 500..599->"server_unavailable";error is PublicError&&error.code=="REFRESH_COOLDOWN"->"server_throttle";error is PublicError&&error.code=="NETWORK_ERROR"&&error.httpStatus==null->"transient";error is java.io.IOException->"transient";error is CheckInError&&error.code!=null||error is CheckInHttpFailure->"server_rejected";else->"invalid"}
    val (appliedPolicy,applied)=CheckInRetry.apply(row,kind,clock(),row.optString("source"),lastOperation,(error as? CheckInHttpFailure)?.retryAfter?:0)
    (error as? CheckInError)?.code?.let{appliedPolicy.put("retcode",it)};(error as? CheckInHttpFailure)?.let{appliedPolicy.put("httpStatus",it.status)}
    if(error is CheckInHttpFailure&&(error.retryAfter>0||error.status==429)){val until=clock()+maxOf(60000L,if(error.retryAfter>0)error.retryAfter else 3600000L);appliedPolicy.put("serverWaitUntil",until).put("until",maxOf(appliedPolicy.optLong("until"),until));row.put("nextAt",appliedPolicy.getLong("until"));appliedPolicy.put("durationSec",(appliedPolicy.getLong("until")-appliedPolicy.getLong("setAt"))/1000)}
    if(audit!=null)auditEvent(if(applied)"checkin_cooldown_applied" else "checkin_cooldown_evaluated",audit,CheckInRetry.fields(row,clock()).put("role_ref",audit.refs[uiKey]).put("allowed",false))
   }else row.put("nextAt",if(state=="cooldown")row.optJSONObject("retry")?.optLong("until")?:clock()+CheckInRetry.MIN_INTERVAL else clock()+if(state=="server_day_mismatch")600000 else if(state=="skipped_before_0001")CheckInRetry.MIN_INTERVAL else 1800000)
   if(audit!=null&&!initialReported)auditEvent("checkin_initial_status_result",audit,CheckInRetry.fields(row,clock()).put("role_ref",audit.refs[uiKey]).put("source_status_request_id",row.optString("statusRequestId")).put("phase","initial_status").put("valid",false).put("sent",initialSent))
   if(audit!=null){val info=row.optJSONObject("info");auditEvent("checkin_auto_decision",audit,CheckInRetry.fields(row,clock()).put("role_ref",audit.refs[uiKey]).put("source_status_request_id",row.optString("statusRequestId")).put("decision",if(state=="signed")"sign" else if(state=="not_signed")"query_only" else state).put("phase","final_decision").put("valid",info?.optBoolean("fresh")==true).put("server_day_match",info?.optString("today")==CheckInProtocol.day(clock())))}
   // Preserve changes to auto switches made by another instance only through the shared gate.
   val alive=targets().map{CheckInProtocol.key(it.first.id,it.second)}.toSet()
   rows.keys().asSequence().toList().filter{it !in alive}.forEach{rows.remove(it)}
   save(v);event("checkin_state_committed");progress(uiKey,row.optString("state","failed"))
  };view()
 }
 private val batchLock=Any()
 private var batch:JSONObject?=null
 @Volatile private var batchCancelled=false
 private var batchExecutor:java.util.concurrent.ExecutorService?=null
 fun batchView():JSONObject=synchronized(batchLock){obj("job" to batch?.copyJson(),"autoJob" to autoView().optJSONObject("job"),"ledger" to view())}
 fun startBatch(body:JSONObject):JSONObject=synchronized(batchLock){
  if(batch?.optString("status")=="running")throw PublicError("REFRESH_IN_PROGRESS")
  val ids=body.getJSONArray("roleKeys").let{a->(0 until a.length()).map{a.getString(it)}}
  val all=targets();val known=all.associateBy{UiPreferences.key(it.first.id,it.second)}
  if(ids.size>64||ids.distinct().size!=ids.size||ids.any{it !in known}||body.optString("operation") !in setOf("info","sign"))throw PublicError("RESPONSE_INVALID")
  if(body.optString("operation")=="info"){requestAuto("checkin_status_manual_refresh",java.util.UUID.randomUUID().toString().replace("-",""),ids);if(ids.isEmpty())batch=obj("operationId" to autoJob!!.getString("operationId"),"operation" to "info","status" to "completed","total" to 0,"done" to 0,"roles" to JSONArray());return@synchronized batchView()}
  val frozen=body.copyJson();val identities=ids.associateWith{k->known.getValue(k).first.secret.toString()}
  val active=obj("operationId" to java.util.UUID.randomUUID().toString(),"status" to if(ids.isEmpty())"completed" else "running","operation" to body.getString("operation"),"total" to ids.size,"done" to 0,
   "roles" to JSONArray(ids.map{obj("roleKey" to it,"state" to "queued")}))
  batch=active;batchCancelled=false;api.diagnostics.checkinBatch(ids.size,active.getString("status"))
  fun live():Boolean= !batchCancelled && targets().associateBy{UiPreferences.key(it.first.id,it.second)}.let{current->identities.all{(key,secret)->current.containsKey(key)&&current[key]?.first?.secret.toString()==secret}}
  if(ids.isNotEmpty()){
   if(batchExecutor==null)batchExecutor=java.util.concurrent.Executors.newSingleThreadExecutor()
   batchExecutor!!.submit{
    try{run(frozen,queued=true,progress={key,state->synchronized(batchLock){active.getJSONArray("roles").objects().first{it.getString("roleKey")==key}.put("state",state);active.put("done",active.getJSONArray("roles").objects().count{it.optString("state") !in setOf("queued","querying")})}},valid={live()})}
    catch(_:Exception){synchronized(batchLock){active.put("error","operation_failed")}}
    finally{synchronized(batchLock){active.getJSONArray("roles").objects().filter{it.optString("state") in setOf("queued","querying")}.forEach{it.put("state",if(live())"failed" else "cancelled")};active.put("done",ids.size).put("status",if(!live())"cancelled" else if(active.has("error"))"failed" else "completed");api.diagnostics.checkinBatch(ids.size,active.getString("status"))}}
   }
  };batchView()
 }
 fun cancelBatch(body:JSONObject):JSONObject=synchronized(batchLock){
  if(batch?.optString("operationId")==body.optString("operationId")&&batch?.optString("status")=="running")batchCancelled=true
  if(autoJob?.optString("operationId")==body.optString("operationId"))autoJob?.put("cancelRequested",true)
  batchView()
 }
 fun closeBatch(){autoClosed=true;batchCancelled=true;batchExecutor?.shutdownNow()}
 private var autoIdentity:Map<String,String> = emptyMap()
 private var autoEpochs:Map<String,Long> = emptyMap()
 private var autoJob:JSONObject?=null
 private var autoFuture:java.util.concurrent.Future<*>?=null
 private val autoRequests=linkedMapOf<String,String>()
 @Volatile private var autoClosed=false
 fun autoView():JSONObject=synchronized(batchLock){val saved=ledger().optJSONObject("autoRun");val current=autoJob;obj("job" to (if(saved!=null&&(current==null||current.optString("status") !in setOf("queued","running")&&saved.optLong("startedAt")>current.optLong("startedAt")))saved else current?.copyJson()),"ledger" to view())}
 private fun auditEvent(name:String,audit:CheckInAudit,fields:JSONObject=obj()) {api.diagnostics.checkinRefresh(name,fields.put("worker_ref",workerReference).put("operation_id",audit.id).put("trigger",audit.trigger).put("business_day",CheckInProtocol.day(clock())).put("after_0001",CheckInProtocol.hasDayRollover(clock())))}
 // One shared status chain for both pages. Only a real user refresh may start it, and the scope is
 // frozen from that page's current selection: resource refresh = resource filter, check-in detail
 // refresh = check-in selection. Landscape policy: resource refreshes may auto sign inside the
 // frozen scope when the role is auto-enabled and freshly unsigned; the detail refresh is query-only.
 fun requestAuto(trigger:String,requestId:String,selected:List<String> = emptyList()):JSONObject=synchronized(batchLock){
  if(trigger !in setOf("resource_manual_refresh","resource_card_refresh","checkin_status_manual_refresh")||!Regex("[a-f0-9]{32}").matches(requestId))throw PublicError("RESPONSE_INVALID")
  if(autoClosed)throw PublicError("CANCELLED")
  val known=targets().associateBy{UiPreferences.key(it.first.id,it.second)}
  if(selected.size>64||selected.distinct().size!=selected.size||selected.any{it !in known})throw PublicError("RESPONSE_INVALID")
  val running=autoJob?.optString("status") in setOf("queued","running")
  if(running&&(autoJob?.optString("day")!=CheckInProtocol.day(clock())||autoIdentity.any{(k,v)->known[k]?.first?.secret?.toString()!=v||!AccountEpoch.unchanged(known[k]!!.first.id,autoEpochs)}))throw PublicError("REFRESH_IN_PROGRESS")
  if(running||autoRequests.containsKey(requestId)){
   if(selected.isNotEmpty()){
    val all=autoJob?.optJSONArray("allRoles")?.objects().orEmpty()
    if(!running||!selected.all{k->all.any{it.optString("roleKey")==k}})throw PublicError("REFRESH_IN_PROGRESS")
    batch=obj("operationId" to autoJob!!.getString("operationId"),"operation" to "info","status" to "running","total" to selected.size,"done" to 0,"roles" to JSONArray(all.filter{it.optString("roleKey") in selected}.map{it.copyJson()}))
   };return@synchronized autoView()
  }
  val book=ledger();val queryOnly=trigger=="checkin_status_manual_refresh"
  // Auto sign targets are all auto-enabled roles (independent of the resource filter). The resource
  // filter only adds roles that must also receive this run's status query.
  val enabledAll=known.filter{(_,pair)->val(a,r)=pair;a.secret!=null&&Role.valid(r.game,r.uid,r.server)&&book.getJSONObject("roles").optJSONObject(CheckInProtocol.key(a.id,r))?.optBoolean("enabled")==true}.keys.toList()
  val scope=if(queryOnly)selected.distinct() else (selected+enabledAll).distinct()
  val autoKeys: Set<String> = if(queryOnly)emptySet() else enabledAll.toSet()
  val tracked=if(queryOnly)scope else enabledAll
  if(scope.isEmpty()){
   val oid=java.util.UUID.randomUUID().toString().replace("-","");val metrics=obj("initial_sends" to 0,"verify_sends" to 0,"sign_sends" to 0,"reused" to 0,"selected_count" to 0,"enabled_count" to 0,"union_count" to 0,"query_policy" to if(queryOnly)"query_only" else "auto_if_enabled_unsigned")
   autoJob=obj("operationId" to oid,"trigger" to trigger,"queryOnly" to queryOnly,"day" to CheckInProtocol.day(clock()),"startedAt" to clock(),"status" to "completed","total" to 0,"done" to 0,"counts" to obj(),"metrics" to metrics,"roles" to JSONArray(),"allRoles" to JSONArray());autoIdentity=emptyMap();autoEpochs=AccountEpoch.snapshot()
   api.diagnostics.checkinRefresh("checkin_refresh_completed",metrics.copyJson().put("operation_id",oid).put("trigger",trigger).put("worker_ref",workerReference).put("business_day",CheckInProtocol.day(clock())).put("after_0001",CheckInProtocol.hasDayRollover(clock())).put("ui_published",true).put("decision","completed").put("query_policy",if(queryOnly)"query_only" else "auto_if_enabled_unsigned"))
   return@synchronized autoView()
  }
  val identities=scope.associateWith{known.getValue(it).first.secret.toString()};val epochs=AccountEpoch.snapshot()
  val oid=java.util.UUID.randomUUID().toString().replace("-","");val policy=if(queryOnly)"query_only" else "auto_if_enabled_unsigned"
  val metrics=obj("initial_sends" to 0,"verify_sends" to 0,"sign_sends" to 0,"reused" to 0,"selected_count" to selected.size,"enabled_count" to enabledAll.size,"union_count" to scope.size,"query_policy" to policy)
  val audit=CheckInAudit(oid,trigger,metrics,scope.associateWith{java.util.UUID.randomUUID().toString().replace("-","").take(24)})
  auditEvent("checkin_auto_trigger",audit,metrics.copyJson().put("actual_at",clock()));auditEvent("checkin_refresh_requested",audit,metrics.copyJson());auditEvent("checkin_roles_resolved",audit,metrics.copyJson())
  val rows=scope.map{obj("roleKey" to it,"state" to "queued")}
  val active=obj("operationId" to oid,"trigger" to trigger,"queryOnly" to queryOnly,"day" to CheckInProtocol.day(clock()),"startedAt" to clock(),"status" to "queued","total" to tracked.size,"done" to 0,"counts" to obj(),"metrics" to metrics,"roles" to JSONArray(rows.filter{it.optString("roleKey") in tracked}),"allRoles" to JSONArray(rows))
  autoJob=active;autoIdentity=identities;autoEpochs=epochs;autoRequests[requestId]=oid;while(autoRequests.size>64)autoRequests.remove(autoRequests.keys.first())
  batch=obj("operationId" to oid,"operation" to "info","status" to "running","total" to selected.size,"done" to 0,"roles" to JSONArray(rows.filter{it.optString("roleKey") in selected}.map{it.copyJson()}));batchCancelled=false
  fun live()=!autoClosed&&!active.optBoolean("cancelRequested")&&active.optString("day")==CheckInProtocol.day(clock())
  fun progress(key:String,state:String){synchronized(batchLock){
   rows.filter{it.optString("roleKey")==key}.forEach{it.put("state",state)}
   active.put("done",active.getJSONArray("roles").objects().count{it.optString("state") !in setOf("queued","querying")})
   if(batch?.optString("operationId")==oid){batch!!.getJSONArray("roles").objects().filter{it.optString("roleKey")==key}.forEach{it.put("state",state)};batch!!.put("done",batch!!.getJSONArray("roles").objects().count{it.optString("state") !in setOf("queued","querying")})}
  }}
  if(batchExecutor==null)batchExecutor=java.util.concurrent.Executors.newSingleThreadExecutor()
  autoFuture=batchExecutor!!.submit{
   synchronized(batchLock){active.put("status","running")}
   var terminal="completed"
   try{run(obj("roleKeys" to JSONArray(scope),"selectedKeys" to JSONArray(selected),"trigger" to trigger),queued=true,identities=identities,epochs=epochs,autoKeys=autoKeys,audit=audit,progress=::progress,valid={live()})}
   catch(_:Exception){terminal=if(!live()||Thread.currentThread().isInterrupted)"cancelled" else "failed"}
   finally{
    synchronized(batchLock){
     val currentKeys=targets().map{UiPreferences.key(it.first.id,it.second)}.toSet()
     rows.filter{it.optString("state") in setOf("queued","querying")}.forEach{progress(it.getString("roleKey"),if(!live()||it.optString("roleKey") !in currentKeys)"cancelled" else "failed")}
     val counts=obj();rows.filter{it.optString("roleKey") in tracked}.groupingBy{it.optString("state")}.eachCount().forEach{(state,count)->counts.put(state,count)}
     metrics.put("already_signed_count",counts.optInt("already_signed")).put("signed_count",counts.optInt("signed")).put("failed_count",listOf("failed","unconfirmed","blocked","login_expired").sumOf{counts.optInt(it)}).put("skipped_count",listOf("disabled","cooldown","rate_limited","throttled","skipped_before_0001","server_day_mismatch","cancelled").sumOf{counts.optInt(it)}).put("duplicate_suppressed",metrics.optInt("reused"))
     metrics.put("cooldown_count",counts.optInt("cooldown")).put("blocked_count",counts.optInt("blocked")+counts.optInt("login_expired")).put("unconfirmed_count",counts.optInt("unconfirmed")).put("throttled_count",counts.optInt("throttled")).put("rate_limited_count",counts.optInt("rate_limited"))
     if(!live())terminal="cancelled"
     active.put("done",tracked.size).put("counts",counts)
    }
    val committed=runCatching{exclusive(true){val value=ledger();value.put("autoRun",synchronized(batchLock){active.copyJson().put("status",terminal)});save(value)}}.isSuccess
    if(!committed)terminal=if(!live()||Thread.currentThread().isInterrupted)"cancelled" else "failed"
    // Publish completion only after durable summary and gate release.
    synchronized(batchLock){active.put("status",terminal);if(batch?.optString("operationId")==oid)batch!!.put("done",batch!!.getInt("total")).put("status",terminal)}
    if(committed)runCatching{onCompleted?.invoke(view())}
    auditEvent("checkin_refresh_completed",audit,metrics.copyJson().put("ui_published",true).put("decision",active.optString("status")))
   }
  }
  autoView()
 }
 // Retired on 2026-11-03: no day-rollover, scheduled or retry entry point exists any more.
 // A status query or an automatic POST may only be started from a real user refresh.
 private data class ResignIntent(val owner:String,val role:Role,val secret:String,val expires:Long,val day:String,val info:JSONObject,val qualification:JSONObject)
 private val resignIntents=mutableMapOf<String,ResignIntent>()
 private fun resignTarget(body:JSONObject):Pair<Account,Role> = targets().firstOrNull{UiPreferences.key(it.first.id,it.second)==body.optString("roleKey")}?:throw PublicError("RESPONSE_INVALID")
 private val resignScopes=mutableMapOf<String,String>()
 private fun resignLog(name:String,a:Account,r:Role,phase:String="terminal",q:JSONObject?=null,decision:String?=null,code:Int?=null){
  val scope=resignScopes[CheckInProtocol.key(a.id,r)]?:"resign"
  api.diagnostics.resign(name,api.diagnostics.trace(a.id,r.key(),scope),phase,decision?:q?.optString("decision"),code,q?.optString("schema"),q?.optInt("evidenceMask"))
 }
 private fun qualification(a:Account,r:Role,info:JSONObject):JSONObject {
  val value=ResignRules.parse(resignCall(a,r,"resign_info"),info,CheckInProtocol.day(clock()))
  resignLog("resign_info_parsed",a,r,"parsed",value);resignLog("resign_rule_evaluated",a,r,"parsed",value);return value
 }
 private fun resignCall(a:Account,r:Role,op:String):JSONObject {
  val stage=if(op=="resign")"resign_post_result" else "resign_info_http"
  if(op!="info")resignLog(stage,a,r,"application_send")
  val response=try{client.call(a,r,op,"resign:"+CheckInProtocol.key(a.id,r))}catch(e:Exception){if(op!="info")resignLog(stage,a,r,"network_error",decision="network_error");throw e}
  val code=response.json.getInt("retcode");if(op!="info")resignLog(stage,a,r,"business_response",decision=if(code==0)null else if(CheckInProtocol.classify(code)=="blocked")"risk_control" else "not_eligible",code=code)
  api.diagnostics.event(if(op=="resign")"checkin_post_result" else "checkin_info_result",r.game,retcode=code,trace=api.diagnostics.trace(a.id,r.key(),"resign"),stage="response",source="native_api")
  if(response.headers.keys.any{it.equals("x-rpc-aigis",true)})throw CheckInError("blocked",code)
  if(code!=0)throw CheckInError(CheckInProtocol.classify(code),code)
  val data=response.json.optJSONObject("data")?:throw PublicError("RESPONSE_INVALID")
  if(op in setOf("info","resign_info")&&CheckInProtocol.risk(data))throw CheckInError("blocked",code)
  return data
 }
 fun prepareResign(body:JSONObject):JSONObject=try{exclusive{
  api.diagnostics.resign("resign_prepare_requested")
  val (a,r)=resignTarget(body);resignScopes[CheckInProtocol.key(a.id,r)]=java.util.UUID.randomUUID().toString().replace("-","");val originalSecret=a.secret.toString();val book=ledger();val rows=book.getJSONObject("roles");val key=CheckInProtocol.key(a.id,r)
  val row=rows.optJSONObject(key)?:obj("enabled" to false);rows.put(key,row)
  val info=CheckInProtocol.info(resignCall(a,r,"info"),r,clock());row.put("info",info).put("state",info.getString("state"))
  val qualification=qualification(a,r,info)
  if(store.load().none{it.id==a.id&&it.secret.toString()==originalSecret&&it.metadata.getJSONArray("roles").objects().any{saved->Role.fromSaved(saved).key()==r.key()}})throw PublicError("CANCELLED")
  row.put("resignInfo",qualification).put("checkedAt",clock());save(book)
  resignIntents.entries.removeAll{it.value.expires<clock()||it.value.owner==a.id&&it.value.role.key()==r.key()}
  if(row.optBoolean("resignPending")){resignLog("resign_prepare_result",a,r,"not_sent",decision="previous_result_unconfirmed");return@exclusive qualification.copyJson().put("eligible",false).put("reason","previous_result_unconfirmed")}
  resignLog("resign_prepare_result",a,r,q=qualification)
  if(!qualification.getBoolean("eligible"))return@exclusive qualification
  val token=java.util.UUID.randomUUID().toString();val expires=clock()+60000
  resignIntents[token]=ResignIntent(a.id,r,a.secret.toString(),expires,info.getString("today"),info.copyJson(),qualification.copyJson())
  qualification.copyJson().put("confirmationId",token).put("expiresAt",expires)
 }}catch(e:CheckInError){api.diagnostics.resign("resign_prepare_result",phase="terminal",decision=if(e.state=="blocked")"risk_control" else "not_eligible",code=e.code);obj("eligible" to false,"reason" to if(e.state=="blocked")"risk_control" else e.state,"code" to e.code)}
 catch(e:PublicError){val decision=when(e.code){"AUTH_EXPIRED"->"login_expired";"NETWORK_ERROR"->"network_error";"CANCELLED"->"cancelled";else->"missing_required_evidence"};api.diagnostics.resign("resign_prepare_result",phase="terminal",decision=decision);obj("eligible" to false,"reason" to decision)}
 fun cancelResign(body:JSONObject):JSONObject=exclusive{resignIntents.remove(body.optString("confirmationId"));obj("ok" to true)}
 fun confirmResign(body:JSONObject):JSONObject=exclusive{
  api.diagnostics.resign("resign_confirm_attempt")
  if(body.opt("confirmed")!=true)throw PublicError("RESPONSE_INVALID")
  val token=body.optString("confirmationId");val intent=resignIntents.remove(token)?:throw PublicError("RESPONSE_INVALID") // consume once, even on failure
  val (a,r)=resignTarget(body)
  if(a.id!=intent.owner||r.key()!=intent.role.key()||a.secret.toString()!=intent.secret||clock()>intent.expires||CheckInProtocol.day(clock())!=intent.day)throw PublicError("RESPONSE_INVALID")
  val book=ledger();val rows=book.getJSONObject("roles");val key=CheckInProtocol.key(a.id,r);val row=rows.optJSONObject(key)?:obj("enabled" to false);rows.put(key,row)
  if(row.optBoolean("resignPending"))throw PublicError("REFRESH_IN_PROGRESS")
  val info=CheckInProtocol.info(resignCall(a,r,"info"),r,clock());val current=qualification(a,r,info)
  if(!current.getBoolean("eligible")||current.toString()!=intent.qualification.toString()||info.toString()!=intent.info.toString())throw PublicError("RESIGN_QUOTE_CHANGED")
  fun live()=store.load().any{it.id==a.id&&it.secret.toString()==intent.secret&&it.metadata.optJSONArray("roles")?.objects()?.any{saved->Role.fromSaved(saved).key()==r.key()}==true}
  if(!live()||Thread.currentThread().isInterrupted||clock()>intent.expires||CheckInProtocol.day(clock())!=intent.day)throw PublicError("CANCELLED")
  row.put("resignPending",true).put("resignState","submitting").put("resignAttemptAt",clock());save(book)
  var accepted=false;var definitive=false
  try{val result=resignCall(a,r,"resign");if(CheckInProtocol.risk(result)){row.put("resignState","blocked")}else accepted=true}
  catch(e:CheckInError){row.put("resignState",e.state).put("resignCode",e.code);definitive=true}
  catch(_:Exception){row.put("resignState","unconfirmed")}
  resignLog("resign_readback",a,r,"application_send")
  // Every result, including timeout/rejection, receives one readback; never POST again.
  try{
   val fresh=CheckInProtocol.info(resignCall(a,r,"info"),r,clock());val q=qualification(a,r,fresh)
   row.put("info",fresh).put("state",fresh.getString("state")).put("resignInfo",q).put("checkedAt",clock())
   val advanced=fresh.getInt("total")>intent.info.getInt("total")||q.optInt("monthly",-1)>intent.qualification.optInt("monthly",-1)
   if(accepted&&advanced){row.put("resignState","confirmed");definitive=true}else if(accepted)row.put("resignState","unconfirmed")
  }catch(_:Exception){if(!definitive)row.put("resignState","unconfirmed")}
  resignLog("resign_readback",a,r,"terminal",decision=if(row.optString("resignState")=="confirmed")"confirmed" else "unconfirmed")
  if(definitive)row.put("resignPending",false)
  if(live())save(book)
  view()
 }
 private class CheckInError(val state:String,val code:Int?=null):Exception()
}
