package io.github.peterfu.hoyoreminder

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors

data class VerifiedNote(val note:JSONObject?,val browserCookies:JSONObject)
data class VerificationRequest(val account:Account,val role:Role,val scope:String,val challenge:NoteChallenge?=null,val trace:DiagnosticTrace?=null,val browse:Boolean=false,val autoRecover:Boolean=false){@Volatile var retryRetcode:Int?=null}
interface VerificationPort {
 fun open(request:VerificationRequest,done:(VerifiedNote?,String)->Boolean)
 fun close(scope:String)
 fun focus()
 fun supportsRecordBrowsing():Boolean = false
 fun supportsRecordChallenges():Boolean = false
 fun available(game:Game):Boolean = true
 fun available(request:VerificationRequest):Boolean = available(request.role.game)
}
class Repository(private val store:AccountPersistence,private val api:ApiClient,private val renderQr:(String)->String,private val clock:()->Long={System.currentTimeMillis()},private val deviceProperties:()->JSONObject={JSONObject()},smsClient:PassportSms?=null) {
 private val lock=Any()
 private val sms=smsClient?:PassportSms(api,clock)
 fun smsChallenge(id:String)=sms.challenge(id)
 fun answerSms(id:String,answer:JSONObject)=sms.send(obj("smsId" to id),answer)
 private val checkin=CheckInService(store,api,clock)
 // Automatic check-in is user-triggered only (2026-11-03): no day watcher, no background scheduler.
 private val accounts=store.load().associateBy{it.id}.toMutableMap()
 private var uiPreferences=UiPreferences.normalize(store.loadUi(),accounts.values)
 private var selected=store.selected()?.takeIf{accounts.containsKey(it)}?:accounts.keys.firstOrNull()
 private data class Qr(val device:String,val ticket:String,val expires:Long,var polling:Boolean=false,var result:JSONObject?=null)
 private val qrs=mutableMapOf<String,Qr>()
 var automaticProfiles=false
 var profileImages:ProfileImages?=null
 var onProfileReady:(()->Unit)?=null
 private val profileExecutor=Executors.newSingleThreadExecutor()
 private val profilePending=mutableSetOf<String>()
 private val profileAfter=mutableMapOf<String,Long>()
 fun prepareProfiles(owners:Set<String>?=null){
  if(!automaticProfiles||(owners==null&&uiPreferences.optString("mode")=="local"))return
  val planned=synchronized(lock){accounts.values.filter{a->(owners==null||a.id in owners)&&a.secret!=null&&a.id !in profilePending&&(clock()>=maxOf(profileAfter[a.id]?:0,a.metadata.optLong("profileCheckedAt")+300000)||(a.secret?.let{DeviceFingerprint.formal(it)}!=null&&a.metadata.has("profileHadFp")&&!a.metadata.optBoolean("profileHadFp")))&&profileNeedsRepair(a)}.onEach{profilePending.add(it.id);profileAfter[it.id]=clock()+300000}.toList()}
  for(account in planned)profileExecutor.submit{
   val beganWithoutFp=account.secret?.let{DeviceFingerprint.formal(it)}==null
   try{
    val result=api.profile(account,"profile:"+UUID.randomUUID(),missingOnly=true)
    synchronized(lock){if(!closed&&accounts[account.id]===account){applyProfile(account,result)}}
    if(!closed)warmProfile(account)
    onProfileReady?.invoke()
   }catch(_:Exception){}finally{synchronized(lock){profilePending.remove(account.id)};if(!closed&&beganWithoutFp&&account.secret?.let{DeviceFingerprint.formal(it)}!=null&&account.metadata.has("profileHadFp")&&!account.metadata.optBoolean("profileHadFp"))prepareProfiles(setOf(account.id))}
  }
 }
 private fun profileNeedsRepair(a:Account):Boolean {
  if(a.metadata.optString("label") in setOf("","未获取昵称","米游社账号")||SafeImages.url(a.metadata.optString("avatar"))==null)return true
  val sources=mutableListOf("account" to a.metadata.optString("avatar"))
  for(saved in a.metadata.optJSONArray("roles")?.objects().orEmpty()){
   val key=Role.fromSaved(saved).key();val url=a.metadata.optJSONObject("roleProfiles")?.optJSONObject(key)?.optString("avatar").orEmpty()
   if(SafeImages.url(url)==null||saved.optString("nickname").isBlank()&&a.metadata.optJSONObject("roleProfiles")?.optJSONObject(key)?.optString("nickname").isNullOrBlank())return true;sources.add(key to url)
  }
  return profileImages?.let{images->sources.any{(key,url)->images.cached(a.id,key,url)==null}}?:false
 }
 private fun applyProfile(account:Account,result:JSONObject){
  val meta=account.metadata.copyJson();result.optJSONObject("user")?.let{u->
   u.optString("label").takeIf{it.isNotBlank()}?.let{meta.put("label",it)}
   if(SafeImages.url(u.optString("avatar"))!=null)meta.put("avatar",u.getString("avatar")).put("avatarState","loaded")
   else if(SafeImages.url(meta.optString("avatar"))==null)meta.put("avatar","").put("avatarState",u.getString("avatarState"))
   meta.put("profileState","loaded")
  };meta.put("profileError",result.optBoolean("userFailed")).put("profileCheckedAt",clock()).put("profileHadFp",result.optBoolean("hadFp"))
  val profiles=meta.optJSONObject("roleProfiles")?:JSONObject()
  result.getJSONObject("roles").keys().forEach{key->val row=result.getJSONObject("roles").getJSONObject(key);val merged=profiles.optJSONObject(key)?.copyJson()?:JSONObject()
   if(SafeImages.url(row.optString("avatar"))!=null||SafeImages.url(merged.optString("avatar"))==null){merged.put("avatar",row.optString("avatar")).put("state",row.optString("state"))}
   row.optString("nickname").takeIf{it.isNotBlank()}?.let{merged.put("nickname",it)};profiles.put(key,merged)}
  meta.put("roleProfiles",profiles);store.save(Account(account.id,meta,account.secret,account.revision));meta.keys().forEach{key->account.metadata.put(key,meta.get(key))}
 }
 private fun warmProfile(a:Account,valid:()->Boolean={true}){
  val images=profileImages?:return;val deadline=System.nanoTime()+8000000000L
  val sources=mutableListOf("account" to a.metadata.optString("avatar"));a.metadata.optJSONObject("roleProfiles")?.let{p->p.keys().forEach{key->sources.add(key to p.getJSONObject(key).optString("avatar"))}}
  for((key,url) in sources){val left=(deadline-System.nanoTime())/1000000;if(left<=0)return;if(SafeImages.url(url)!=null)images.fetch(a.id,key,url,left,valid)}
 }
 private val refreshExecutor=Executors.newSingleThreadExecutor()
 private var job:JSONObject?=null
 private var cancelled=false
 private var waiting=false
 private data class FpOperation(val account:Account,val id:String)
 private var fpOperation:FpOperation?=null
 private var closed=false
 private val fpAfter=mutableMapOf<String,Long>()
 private var manualScope:String?=null
 private var manualAccountId:String?=null
 private var lastDiagnosticScope:String?=null
 private val verificationAfter=mutableMapOf<String,Long>()
 private val challenges=mutableMapOf<String,NoteChallenge>()
 private val verificationCodes=mutableMapOf<String,Int>()
 private fun failVerified(account:Account,role:Role,error:String)=failNote(account,role,error,verificationCodes[challengeKey(account,role)])
 private fun challengeKey(account:Account,role:Role)=account.id+":"+role.key()
 private fun challenge(account:Account,role:Role)=challenges[challengeKey(account,role)]?.takeIf{it.fresh()}
 private fun routeAvailable(account:Account,role:Role,recover:Boolean=false)=verifier?.available(VerificationRequest(account,role,"",challenge(account,role),browse=recover&&challenge(account,role)==null,autoRecover=recover))==true
 private var generation=0
 private var afterRefresh=0L
 private var afterQr=0L
 var verifier:VerificationPort?=null
 init {
  runCatching{store.saveUi(uiPreferences)} // settings-only migration preserves display order before scoped saves change rowids.
  accounts.values.forEach{account->
   api.diagnostics.event("account_restored",classification=if(account.secret==null)"auth_expired" else "authenticated",trace=api.diagnostics.trace(account.id,"account","restore"),stage="restore",source="store")
   account.metadata.optJSONArray("roles")?.objects()?.forEach{saved->
    runCatching{Role.fromSaved(saved)}.onSuccess{role->api.diagnostics.event("role_resolved",role.game,classification="resolved",trace=api.diagnostics.trace(account.id,role.key(),"restore"),stage="role_discovery",source="store")}
   }
  }
 }
 fun rewardImage(owner:String,key:String,index:Int)=checkin.reward(owner,key,index)
 fun diagnosticRefreshId():String?=synchronized(lock){lastDiagnosticScope?:job?.optString("refreshId")}

 fun view():JSONObject=synchronized(lock){obj("runtimeVersion" to BuildConfig.VERSION_NAME,"backendVersion" to "android-ui-41","selectedId" to selected,"uiPreferences" to UiPreferences.normalize(uiPreferences,accounts.values),"accounts" to JSONArray(accounts.values.map{it.metadata.copyJson().put("id",it.id).put("fingerprint",DeviceFingerprint.status(it.secret,clock()))}))}
 private fun progress():JSONObject=synchronized(lock){view().put("refresh",job?.copyJson()?:JSONObject.NULL)}
 private fun active()=job?.optString("status")=="running"
 fun dispatch(path:String,method:String,body:JSONObject):JSONObject {
  return when {
   path=="/api/auth/sms/status"&&method=="GET" -> sms.status()
   path=="/api/auth/sms/send"&&method=="POST" -> sms.send(body)
   path=="/api/auth/sms/login"&&method=="POST" -> sms.login(body){cookies,id,device,valid->commitLogin(cookies,id,valid,"sms",device)}
   path=="/api/auth/sms/cancel"&&method=="POST" -> {val id=body.optString("smsId");if(id.isNotBlank())sms.cancel(id,body.optString("trigger","user"));obj("ok" to true)}
   path=="/api/auth/sms/ack"&&method=="POST" -> sms.acknowledge(body.getString("smsId"))
   path=="/api/session"&&method=="GET" -> obj("csrfToken" to "native","progressiveRefresh" to synchronized(lock){active()})
   path=="/api/accounts"&&method=="GET" -> view()
   path=="/api/checkin/auto/start"&&method=="POST" -> {val trigger=body.getString("trigger");if(trigger !in setOf("resource_manual_refresh","resource_card_refresh","checkin_status_manual_refresh"))throw PublicError("RESPONSE_INVALID");checkin.requestAuto(trigger,body.getString("requestId"),body.optJSONArray("roleKeys")?.let{a->(0 until a.length()).map{a.getString(it)}}?:throw PublicError("RESPONSE_INVALID"))}
   path=="/api/checkin/auto/status"&&method=="GET" -> checkin.autoView()
   path=="/api/checkin"&&method=="GET" -> checkin.view()
   path=="/api/checkin/batch/start"&&method=="POST" -> checkin.startBatch(body)
   path=="/api/checkin/batch/status"&&method=="GET" -> checkin.batchView()
   path=="/api/checkin/batch/cancel"&&method=="POST" -> checkin.cancelBatch(body)
   path=="/api/checkin/run"&&method=="POST" -> checkin.run(body)
   path=="/api/checkin/resign/prepare"&&method=="POST" -> checkin.prepareResign(body)
   path=="/api/checkin/resign/confirm"&&method=="POST" -> checkin.confirmResign(body)
   path=="/api/checkin/resign/cancel"&&method=="POST" -> checkin.cancelResign(body)
   path=="/api/checkin/preferences"&&method=="POST" -> checkin.configure(body)
   path=="/api/avatar/render"&&method=="POST" -> synchronized(lock){
    val owner=body.optString("accountId");val key=body.optString("roleKey","account").ifBlank{"account"}
    val a=accounts[owner]?:throw PublicError("AUTH_EXPIRED")
    if(key!="account"&&a.metadata.getJSONArray("roles").objects().none{Role.fromSaved(it).key()==key})throw PublicError("RESPONSE_INVALID")
    api.diagnostics.avatar(if(body.optBoolean("success"))"avatar_render_success" else "avatar_render_failed",owner,key,if(key=="account")"passport" else "game_role",if(body.optBoolean("success"))"decoded" else "render_failed")
    obj("ok" to true)
   }
   path=="/api/diagnostics"&&method=="GET" -> api.diagnostics.snapshot()
   path=="/api/accounts/refresh/status"&&method=="GET" -> progress()
   path=="/api/auth/diagnostic"&&method=="POST" -> obj("ok" to true) // no raw diagnostic body logging
   path=="/api/accounts/refresh/rendered"&&method=="POST" -> obj("ok" to true)
   path=="/api/auth/qr/start"&&method=="POST" -> startQr()
   path.startsWith("/api/auth/qr/")&&method=="GET" -> pollQr(path.substringAfterLast('/'))
   path.startsWith("/api/auth/qr/")&&method=="DELETE" -> {cancelQr(path.substringAfterLast('/'));obj("ok" to true)}
   path=="/api/accounts/fingerprint/repair"&&method=="POST" -> repairFingerprint(body)
   path=="/api/accounts/fingerprint/cancel"&&method=="POST" -> cancelFingerprint(body)
   path=="/api/accounts/select"&&method=="POST" -> synchronized(lock){val id=body.getString("accountId");if(!accounts.containsKey(id))throw PublicError("AUTH_EXPIRED");if(id!=selected)cancelManual();store.select(id);selected=id;view()}
   path=="/api/accounts/remove"&&method=="POST" -> remove(body.getString("accountId"))
   path=="/api/ui/preferences"&&method=="POST" -> synchronized(lock){
    val next=UiPreferences.normalize(body,accounts.values);store.saveUi(next);uiPreferences=next;view()
   }
   path=="/api/resources/estimate"&&method=="POST" -> synchronized(lock){UiPreferences.targets(body,accounts.values);view()}
   path=="/api/accounts/profile/batch/start"&&method=="POST" -> startProfileBatch(body)
   path=="/api/accounts/profile/batch/status"&&method=="GET" -> profileBatchView()
   path=="/api/accounts/profile/batch/cancel"&&method=="POST" -> cancelProfileBatch(body)
   path=="/api/accounts/profile"&&method=="POST" -> refreshProfile(body)
   path=="/api/accounts/refresh/start"&&method=="POST" -> synchronized(lock){
    if(body.optString("mode","server")!="server")throw PublicError("RESPONSE_INVALID")
    startRefresh(body.optString("refreshId"),"manual",targets=UiPreferences.targets(body,accounts.values))
   }
   path=="/api/accounts/refresh/action"&&method=="POST" -> action(body)
   path=="/api/accounts/record/open"&&method=="POST" -> openRecord(body)
   path=="/api/accounts/verify"&&method=="POST" -> verify(body)
   path=="/api/accounts/verification/cancel"&&method=="POST" -> cancelManual()
   else -> throw PublicError("OPERATION_UNSUPPORTED")
  }
 }
 fun profileImage(accountId:String,roleKey:String?):Pair<String,Boolean>?=synchronized(lock){
  val account=accounts[accountId]?:return@synchronized null
  val url=if(roleKey==null)account.metadata.optString("avatar") else {
   if(account.metadata.optJSONArray("roles")?.objects().orEmpty().none{Role.fromSaved(it).key()==roleKey})return@synchronized null
   account.metadata.optJSONObject("roleProfiles")?.optJSONObject(roleKey)?.optString("avatar").orEmpty()
  }
  SafeImages.url(url)?.let{it to (uiPreferences.optString("mode")!="local")}
 }
 @Volatile private var profileBatch:JSONObject?=null
 @Volatile private var profileBatchCancelled=false
 @Volatile private var profileBatchScope:String?=null
 private var profileBatchTask:java.util.concurrent.Future<*>?=null
 private fun profileBatchView():JSONObject=synchronized(lock){obj("job" to profileBatch?.copyJson(),"state" to view())}
 private fun startProfileBatch(body:JSONObject):JSONObject=synchronized(lock){
  if(closed||profileBatch?.optString("status")=="running"||manualScope!=null||fpOperation!=null)throw PublicError("REFRESH_IN_PROGRESS")
  val targets=accounts.values.filter{it.secret!=null}.toList();val id=UUID.randomUUID().toString()
  val current=obj("operationId" to id,"status" to if(targets.isEmpty())"completed" else "running","total" to targets.size,"done" to 0,"roles" to targets.sumOf{it.metadata.optJSONArray("roles")?.length()?:0},
   "accounts" to org.json.JSONArray(targets.map{obj("accountId" to it.id,"state" to "queued")}))
  profileBatch=current;profileBatchCancelled=false;targets.forEach{profileAfter[it.id]=clock()+300000}
  val identities=targets.associate{it.id to it.secret.toString()};val roleSets=targets.associate{it.id to it.metadata.getJSONArray("roles").objects().map{r->Role.fromSaved(r).key()}.toSet()}
  if(targets.isNotEmpty())profileBatchTask=profileExecutor.submit{
   try{for(account in targets){
    if(profileBatchCancelled||profileBatch!==current||closed||Thread.currentThread().isInterrupted)break
    val row=current.getJSONArray("accounts").objects().first{it.getString("accountId")==account.id}
    fun live():Boolean= !profileBatchCancelled&&profileBatch===current&&!closed&&store.load().any{it.id==account.id&&it.secret.toString()==identities[account.id]&&it.metadata.getJSONArray("roles").objects().map{r->Role.fromSaved(r).key()}.toSet()==roleSets[account.id]}
    synchronized(lock){row.put("state","processing")}
    try{
     if(!live())throw PublicError("CANCELLED")
     if(!profileNeedsRepair(account)){synchronized(lock){row.put("state","cached")}}
     else{
      val scope=UUID.randomUUID().toString();profileBatchScope=scope
      val result=api.profile(account,scope,missingOnly=true,valid={live()},budgetMs=125000)
      synchronized(lock){if(!live()||accounts[account.id]!==account)throw PublicError("CANCELLED");applyProfile(account,result)}
      warmProfile(account){live()};if(!live())throw PublicError("CANCELLED")
      synchronized(lock){row.put("state",if(result.optBoolean("userFailed")||result.optBoolean("incomplete")||profileNeedsRepair(account))"partial" else "completed")}
      onProfileReady?.invoke()
     }
    }catch(e:Exception){synchronized(lock){row.put("state",if(e is PublicError&&e.code=="CANCELLED")"cancelled" else "failed")}}
    finally{profileBatchScope=null;synchronized(lock){current.put("done",current.optInt("done")+1)}}
   }}finally{synchronized(lock){current.getJSONArray("accounts").objects().filter{it.optString("state") in setOf("queued","processing")}.forEach{it.put("state","cancelled")};current.put("status",if(profileBatchCancelled||profileBatch!==current||closed)"cancelled" else "completed")}}
  }
  profileBatchView()
 }
 private fun cancelProfileBatch(body:JSONObject):JSONObject=synchronized(lock){
  if(profileBatch?.optString("operationId")==body.optString("operationId")&&profileBatch?.optString("status")=="running"){
   profileBatchCancelled=true;profileBatchScope?.let{api.transport.cancel(it)};profileBatchTask?.cancel(true)
   profileBatch?.put("status","cancelled");profileBatch?.optJSONArray("accounts")?.objects()?.filter{it.optString("state") in setOf("queued","processing")}?.forEach{it.put("state","cancelled")}
  };profileBatchView()
 }
 private fun refreshProfile(body:JSONObject):JSONObject {
  val account:Account;val scope=UUID.randomUUID().toString().replace("-","")
  synchronized(lock){
   if(active()||manualScope!=null||fpOperation!=null||closed)throw PublicError("REFRESH_IN_PROGRESS")
   account=accounts[body.getString("accountId")]?:throw PublicError("AUTH_EXPIRED")
   if(account.secret==null)throw PublicError("AUTH_EXPIRED")
   manualScope=scope;manualAccountId=account.id
  }
  try{
   val future=profileExecutor.submit<JSONObject>{api.profile(account,scope)}
   val result=try{future.get(60,java.util.concurrent.TimeUnit.SECONDS)}catch(e:Exception){future.cancel(true);api.transport.cancel(scope);throw e}
   return synchronized(lock){
    if(closed||manualScope!=scope||accounts[account.id]!==account)throw PublicError("CANCELLED")
    applyProfile(account,result)
    view()
   }
  }finally{synchronized(lock){if(manualScope==scope){manualScope=null;manualAccountId=null}}}
 }
 private fun repairFingerprint(body:JSONObject,loginJob:JSONObject?=null):JSONObject {
  val operation: FpOperation
  val identity: JSONObject
  val previousFp: String?
  synchronized(lock){
   if(loginJob!=null&&(job!==loginJob||cancelled||loginJob.optString("trigger")!="login"))throw PublicError("CANCELLED")
   if(closed||(active()&&job!==loginJob)||manualScope!=null||fpOperation!=null)throw PublicError("REFRESH_IN_PROGRESS")
   val account=accounts[body.getString("accountId")]?:throw PublicError("AUTH_EXPIRED")
   val secret=account.secret?.copyJson()?:throw PublicError("AUTH_EXPIRED")
   if(ApiClient.verifiedCookies(secret.getJSONObject("cookies"))!=account.id)throw PublicError("AUTH_EXPIRED")
   if(clock()<(fpAfter[account.id]?:secret.optLong("deviceFpAttemptAt")))throw PublicError("REFRESH_COOLDOWN")
   val prior=(secret.optJSONObject("deviceFpPending")?:secret.optJSONObject("deviceFpContext"))?.takeIf{it.optString("owner",account.id)==account.id}
   val shared=accounts.values.any{it.id!=account.id&&it.secret?.optString("deviceId")==secret.optString("deviceId")}
   val device=prior?.optString("bbsDeviceId")?.takeIf{candidate->candidate.isNotBlank()&&accounts.values.none{it.id!=account.id&&it.secret?.optString("deviceId")==candidate}}?:
    if(shared||secret.optString("deviceId")==store.installationId())UUID.randomUUID().toString() else secret.getString("deviceId")
   identity=DeviceFingerprint.identity(account.id,device,prior,deviceProperties,clock())
   previousFp=DeviceFingerprint.formal(secret)
   val until=clock()+60000
   secret.put("deviceFpPending",identity).put("deviceFpAttemptAt",until)
   store.save(Account(account.id,account.metadata,secret,account.revision));account.secret=secret
   fpAfter[account.id]=until
   operation=FpOperation(account,UUID.randomUUID().toString().replace("-",""));fpOperation=operation
  }
  val trace=api.diagnostics.trace(operation.account.id,"account",operation.id)
  try {
   synchronized(lock){if(fpOperation!==operation||closed||Thread.currentThread().isInterrupted)throw PublicError("CANCELLED")}
   api.diagnostics.event("device_fp_http_send",url=DeviceFingerprint.ENDPOINT,trace=trace,stage="request",source="native_api",profileVersion=2,fieldCount=identity.getJSONObject("extFields").length())
   val fp=api.fingerprint(identity,previousFp?.takeIf{operation.account.secret?.optString("deviceId")==identity.getString("bbsDeviceId")},operation.id)
   api.diagnostics.event("device_fp_accepted",retcode=0,fpResultCode=200,trace=trace,stage="response",source="native_api")
   return synchronized(lock){
    if(closed||Thread.currentThread().isInterrupted||fpOperation!==operation||accounts[operation.account.id]!==operation.account||(loginJob!=null&&(job!==loginJob||cancelled)))throw PublicError("CANCELLED")
    val account=operation.account;val secret=account.secret!!.copyJson()
    val context=identity.copyJson().put("source","official_getFp").put("updatedAt",clock())
    secret.put("deviceFp",fp).put("deviceFpContext",context).put("deviceId",context.getString("bbsDeviceId"))
    secret.remove("deviceFpPending");secret.put("cookies",DeviceFingerprint.cookies(secret))
    store.save(Account(account.id,account.metadata,secret,account.revision));account.secret=secret
    prepareProfiles(setOf(account.id))
    api.diagnostics.event("device_fp_committed",retcode=0,fpResultCode=200,trace=trace,stage="persist",source="store",fpPresent=true,fpMatchesAccount=true)
    view().put("fingerprintResult",obj("state" to "saved","same" to (fp==previousFp)))
   }
  }catch(e:Exception){
   api.diagnostics.event("device_fp_failed",retcode=(e as? PublicError)?.retcode,fpResultCode=(e as? PublicError)?.fpResultCode,
    httpStatus=(e as? PublicError)?.httpStatus,trace=trace,stage="response",source="native_api",exceptionCategory=publicExceptionCategory(e))
   throw e
  }finally{synchronized(lock){if(fpOperation===operation)fpOperation=null}}
 }
 private fun cancelFingerprint(body:JSONObject):JSONObject=synchronized(lock){
  fpOperation?.takeIf{it.account.id==body.optString("accountId")}?.let{fpOperation=null;api.transport.cancel(it.id)}
  obj("cancelled" to true)
 }
 private fun startQr():JSONObject {
  val id=UUID.randomUUID().toString()
  val device=synchronized(lock){
   val now=clock();qrs.entries.removeAll{it.value.expires<now}
   if(now<afterQr)throw PublicError("REFRESH_COOLDOWN");if(qrs.values.count{it.result==null}>=3)throw PublicError("REFRESH_IN_PROGRESS");afterQr=now+2000
   store.installationId()
  }
  val data=api.createQr(device,id);val url=data.getString("url")
  val uri=java.net.URI(url);if(uri.scheme!="https"||uri.userInfo!=null||!(uri.host.endsWith(".mihoyo.com")||uri.host.endsWith(".miyoushe.com")||uri.host=="miyoushe.com"))throw PublicError("RESPONSE_INVALID")
  synchronized(lock){qrs[id]=Qr(device,data.getString("ticket"),clock()+180000)}
  return obj("sessionId" to id,"qrImageDataUrl" to renderQr(url),"expiresAt" to clock()+180000)
 }
 private fun pollQr(id:String):JSONObject {
  val qr=synchronized(lock){
   val q=qrs[id]?:return obj("status" to "expired")
   if(q.expires<clock()){qrs.remove(id);return obj("status" to "expired")}
   q.result?.let{return it.copyJson()};if(q.polling)return obj("status" to "pending");q.polling=true;q
  }
  try{
   val response=api.pollQr(qr.device,qr.ticket,id);val data=api.data(response);val status=data.getString("status")
   if(status!="Confirmed")return obj("status" to if(status=="Scanned")"scanned" else "pending")
   val result=commitLogin(ApiClient.cookiesFrom(response.headers),id,valid={synchronized(lock){qrs[id]===qr&&qr.expires>=clock()}})
   synchronized(lock){if(qrs[id]===qr)qr.result=result}
   return result
  } catch(e:Exception){api.diagnostics.event("login_failed",retcode=(e as? PublicError)?.retcode,trace=api.diagnostics.trace("qr-session:"+id,"account",id),stage="authentication",source="qr",exceptionCategory=publicExceptionCategory(e));throw e} finally{synchronized(lock){qr.polling=false}}
 }
 private fun commitLogin(cookies:JSONObject,id:String,valid:()->Boolean,source:String="qr",device:String?=null):JSONObject {
   val accountId=ApiClient.verifiedCookies(cookies)
   val secret=obj("cookies" to cookies,"deviceId" to (device?:UUID.randomUUID().toString()),"profileKey" to "account-"+UUID.randomUUID().toString())
   secret.put("cookies",DeviceFingerprint.cookies(secret))
   synchronized(lock){accounts[accountId]?.secret?.let{old->for(k in listOf("deviceId","profileKey","deviceFp","deviceFpPending","deviceFpContext","deviceFpAttemptAt"))old.opt(k)?.let{secret.put(k,it)};if(!secret.has("deviceFp"))old.opt("deviceFp")?.let{secret.put("deviceFp",it)}}}
   secret.put("cookies",DeviceFingerprint.cookies(secret))
   val roles=api.discover(secret,id)
   if(source=="sms")api.diagnostics.login("roles_discovered",id,"role_discovery")
   return synchronized(lock){
    if(!valid())throw PublicError(if(source=="sms")"CANCELLED" else "QR_EXPIRED")
    if(fpOperation!=null||closed)throw PublicError("REFRESH_IN_PROGRESS")
    val previous=accounts[accountId]
    val same=previous?.secret?.optJSONObject("cookies")?.let{old->ApiClient.AUTH_NAMES.all{old.optString(it)==cookies.optString(it)}}==true
    if(same){
     prepareProfiles(setOf(accountId))
     val result=obj("status" to "confirmed","code" to "ALREADY_LOGGED_IN","initialRefresh" to obj("status" to "not_needed"))
     if(previous?.secret?.let{DeviceFingerprint.formal(it)}==null&&!active()&&manualScope==null){
      val progress=startRefresh("","login",setOf(accountId));result.put("initialRefresh",obj("status" to "scheduled","refreshId" to progress.getJSONObject("refresh").getString("refreshId")))
     }
     return@synchronized result
    }
    if(active()||manualScope!=null||fpOperation!=null)throw PublicError("REFRESH_IN_PROGRESS")
    if(previous!=null){for(k in listOf("deviceId","profileKey","deviceFp","deviceFpPending","deviceFpContext","deviceFpAttemptAt"))previous.secret?.opt(k)?.let{secret.put(k,it)};if(!secret.has("deviceFp"))previous.secret?.opt("deviceFp")?.let{secret.put("deviceFp",it)}}
    val metadata=obj("id" to accountId,"label" to (previous?.metadata?.optString("label")?:"未获取昵称"),"avatar" to (previous?.metadata?.optString("avatar")?:""),"profileState" to previous?.metadata?.optString("profileState","unavailable"),"avatarState" to previous?.metadata?.optString("avatarState","unavailable"),"profileError" to previous?.metadata?.optBoolean("profileError",false),"roleProfiles" to (previous?.metadata?.optJSONObject("roleProfiles")?:JSONObject()),"roles" to JSONArray(roles.map{it.json()}),"games" to (previous?.metadata?.optJSONArray("games")?:JSONArray()),"verification" to JSONObject(),"auth" to "authenticated")
    val account=Account(accountId,metadata,secret)
    store.save(account,true);if(source=="sms")api.diagnostics.login("account_committed",id,"persist",committed=true);accounts[accountId]=account;selected=accountId;uiPreferences=UiPreferences.normalize(uiPreferences,accounts.values);store.saveUi(uiPreferences);prepareProfiles(setOf(accountId))
    api.diagnostics.event("account_authenticated",classification="authenticated",trace=api.diagnostics.trace(accountId,"account",id),stage="authentication",source=source)
    roles.forEach{api.diagnostics.event("role_resolved",it.game,classification="resolved",trace=api.diagnostics.trace(accountId,it.key(),id),stage="role_discovery",source=source)}
    val result=obj("status" to "confirmed","code" to if(previous==null)"LOGIN_SUCCESS" else "ACCOUNT_UPDATED","initialRefresh" to obj("status" to "not_needed"))
    if(previous==null||DeviceFingerprint.formal(secret)==null){val progress=startRefresh(UUID.randomUUID().toString().replace("-",""),"login",setOf(accountId));result.put("initialRefresh",obj("status" to "scheduled","refreshId" to progress.getJSONObject("refresh").getString("refreshId")))}
    result
   }
 }
 private fun cancelQr(id:String){synchronized(lock){qrs.remove(id)};api.transport.cancel(id)}
 private fun remove(id:String):JSONObject=synchronized(lock){
  if(active()||closed)throw PublicError("REFRESH_IN_PROGRESS")
  val replacement=if(selected==id)accounts.keys.firstOrNull{it!=id}else selected
  fpOperation?.takeIf{it.account.id==id}?.let{fpOperation=null;api.transport.cancel(it.id)};store.remove(id,replacement);AccountEpoch.removed(id);accounts.remove(id);selected=replacement;uiPreferences=UiPreferences.normalize(uiPreferences,accounts.values);store.saveUi(uiPreferences);manualScope?.takeIf{manualAccountId==id}?.let{verifier?.close(it);manualScope=null};view()
 }
 fun startRefresh(requestId:String,trigger:String,only:Set<String>?=null,targets:Set<String>?=null):JSONObject=synchronized(lock){
  if(manualScope!=null)throw PublicError("VERIFICATION_PENDING")
  if(active()||fpOperation!=null||closed)throw PublicError("REFRESH_IN_PROGRESS")
  if(trigger=="manual"&&clock()<afterRefresh)throw PublicError("REFRESH_COOLDOWN")
  val id=requestId.takeIf{Regex("[a-f0-9]{32}").matches(it)}?:UUID.randomUUID().toString().replace("-","")
  if(targets!=null&&targets.any{it !in UiPreferences.keys(accounts.values)})throw PublicError("RESPONSE_INVALID")
  val rows=accounts.values.filter{only==null||it.id in only}.flatMap{a->a.metadata.getJSONArray("roles").objects().filter{targets==null||UiPreferences.key(a.id,Role.fromSaved(it)) in targets}.map{r->obj("accountId" to a.id,"roleUid" to r.getString("uid"),"server" to r.getString("serverId"),"game" to r.getString("game"),"state" to "idle","error" to null,"durationMs" to 0)}}
  val current=obj("refreshId" to id,"status" to "running","trigger" to trigger,"interactionReady" to (trigger=="manual"),"roles" to JSONArray(rows),"elapsedMs" to 0,"startedAt" to clock())
  if(trigger=="login")current.put("preparingDevices",true).put("loginAccounts",JSONArray(accounts.keys.filter{only==null||it in only}))
  challenges.clear();lastDiagnosticScope=id;job=current;cancelled=false;waiting=false;val version=++generation
  if(trigger=="manual"||trigger=="login")prepareProfiles(rows.map{it.getString("accountId")}.toSet())
  refreshExecutor.submit{collect(current,version)}
  progress()
 }
 private fun prepareLoginDevices(current:JSONObject,version:Int){
  if(current.optString("trigger")!="login")return
  val ids=synchronized(lock){val list=current.getJSONArray("loginAccounts");(0 until list.length()).map{list.getString(it)}}
  for(id in ids){
   val missing=synchronized(lock){
    if(job!==current||cancelled||generation!=version||closed)return
    val account=accounts[id]?:return
    val absent=account.secret?.let{DeviceFingerprint.formal(it)}==null
    api.diagnostics.event("device_fp_auto_checked",trace=api.diagnostics.trace(id,"account",current.getString("refreshId")),source="local",stage="authentication",fpPresent=!absent)
    if(absent)current.getJSONArray("roles").objects().filter{it.optString("accountId")==id}.forEach{it.put("state","preparing_device")}
    absent
   }
   if(missing){
    try{
     repairFingerprint(obj("accountId" to id),current)
     synchronized(lock){if(job!==current||cancelled||generation!=version)return;current.getJSONArray("roles").objects().filter{it.optString("accountId")==id}.forEach{it.put("state","idle")}}
    }
    catch(e:Exception){synchronized(lock){
     if(job!==current||cancelled||generation!=version||closed)return
     val account=accounts[id]?:return
     account.metadata.put("fingerprintBootstrap",obj("state" to "failed","code" to ((e as? PublicError)?.code?:"NETWORK_ERROR")))
     current.getJSONArray("roles").objects().filter{it.optString("accountId")==id}.forEach{
      it.put("state","failed").put("error","device_fp_unavailable")
      val role=account.metadata.getJSONArray("roles").objects().map{r->Role.fromSaved(r)}.first{r->r.game.id==it.optString("game")&&r.uid==it.optString("roleUid")&&r.server==it.optString("server")}
      runCatching{failNote(account,role,"device_fp_unavailable")}
     }
    }}
   }
  }
 }
 private fun collect(current:JSONObject,version:Int) {
  prepareLoginDevices(current,version)
  synchronized(lock){if(job!==current||cancelled||generation!=version)return;current.put("preparingDevices",false)}
  for(row in current.getJSONArray("roles").objects()){
   val pair=synchronized(lock){
    if(job!==current||cancelled||generation!=version)return
    if(row.optString("state")=="failed")return@synchronized null
    val account=accounts[row.getString("accountId")]?:return
    val role=account.metadata.getJSONArray("roles").objects().map{Role.fromSaved(it)}.first{it.game.id==row.getString("game")&&it.uid==row.getString("roleUid")&&it.server==row.getString("server")}
    row.put("state","refreshing");account to role
   }?:continue
   val began=clock()
   val trace=api.diagnostics.trace(pair.first.id,pair.second.key(),current.getString("refreshId"))
   synchronized(lock){if(job!==current||cancelled||generation!=version)return;row.put("request_id",trace.requestId)}
   var stage="request"
   try{
    val secret=pair.first.secret?:throw PublicError("AUTH_EXPIRED")
    val response=api.note(secret,pair.second,current.getString("refreshId"),trace=trace)
    val code=response.json.getInt("retcode")
    synchronized(lock){if(job===current&&!cancelled&&generation==version){val k=challengeKey(pair.first,pair.second);challenges.remove(k);(NoteChallenge.parse(response,owner=pair.first.id,role=pair.second)?:NoteChallenge.fromRecord(response,pair.first.id,pair.second))?.let{challenges[k]=it}}}
    if(code!=0){
     val context=VerificationContext.inspect(response)
     synchronized(lock){if(job!==current||cancelled||generation!=version)return;row.put("challengeKind",context.kind)}
     api.diagnostics.event("verification_context_checked",pair.second.game,retcode=code,
      classification=VerificationResolution.reason(code,context.kind,false),trace=trace,stage="verification_policy",source="native_api",
      challengeKind=context.kind,contextChecks=2,aigisPresent=context.aigisPresent,aigisParseValid=context.aigisParseValid,legacyPresent=context.legacyPresent,contextLocation=context.location,authFieldsPresent=ApiClient.AUTH_NAMES.count{secret.getJSONObject("cookies").optString(it).isNotBlank()})
    }
    stage="parse"
    api.diagnostics.event("notes_business_result",pair.second.game,retcode=code,classification=if(code==0)"native_api" else "business_error",trace=trace,stage=stage)
    val note=if(code==0)NoteParser.parse(pair.second,response.json.optJSONObject("data")?:throw PublicError("RESPONSE_INVALID"))else null
    synchronized(lock){
     if(job!==current||cancelled||generation!=version)return
     if(note!=null){
      val updatedSecret=mergeResponseCookies(pair.first,ApiClient.cookiesFrom(response.headers))
      stage="persist"
      commit(pair.first,pair.second,note,updatedSecret);row.put("state","success")
      api.diagnostics.event("notes_committed",pair.second.game,retcode=0,classification="committed",trace=trace,durationMs=clock()-began,stage="persist",source="native_api")
      api.diagnostics.event("notes_api_result",pair.second.game,Protocol.noteUrl(pair.second),0,"valid_note")
     }
     else{val status=NoteParser.status(code);failNote(pair.first,pair.second,status,code)
      val recover=current.optString("trigger")=="manual"&&DeviceFingerprint.formal(secret)!=null&&code in setOf(1034,10035,5003,10041)
      row.put("autoRecover",recover).put("state",if(recover||status=="risk_control_required")"verification_required" else "failed").put("error",status).put("retcode",code)
      if(!recover&&status!="risk_control_required")resolveVerification(pair.first,pair.second,code,row.optString("challengeKind","none"),trace)
     }
    }
   }catch(e:Exception){synchronized(lock){if(job===current&&!cancelled){val status=if(e is PublicError&&e.code=="AUTH_EXPIRED")"auth_expired" else if(e is PublicError&&e.code=="SAVE_FAILED")"session_persist_failed" else if(e is PublicError&&e.code=="RESPONSE_INVALID")"response_invalid" else if(e is PublicError&&e.code=="REFRESH_COOLDOWN")"upstream_rate_limited" else "network_error";runCatching{failNote(pair.first,pair.second,status)};row.put("state","failed").put("error",status).put("failureStage",stage);api.diagnostics.event("notes_failed",pair.second.game,retcode=(e as? PublicError)?.retcode,classification=status,trace=trace,stage=stage,source="native_api",exceptionCategory=status)}}}
   synchronized(lock){row.put("durationMs",clock()-began);row.put("changedAt",clock());current.put("elapsedMs",clock()-current.getLong("startedAt"))}
   // Native requests serialize without an artificial browser-navigation delay.
  }
  synchronized(lock){if(job===current&&!cancelled)advance()}
 }
 private fun mergeResponseCookies(account:Account,updates:JSONObject):JSONObject {
  val secret=account.secret?.copyJson()?:throw PublicError("AUTH_EXPIRED")
  val cookies=secret.getJSONObject("cookies")
  updates.keys().forEach{k->if(k in ApiClient.COOKIE_NAMES)cookies.put(k,updates.getString(k))}
  if(ApiClient.verifiedCookies(cookies)!=account.id)throw PublicError("AUTH_EXPIRED")
  if(DeviceFingerprint.formal(secret)==null&&!secret.has("deviceFpContext"))updates.optString("DEVICEFP").takeIf{DeviceFingerprint.valid(it)}?.let{secret.put("deviceFp",it)}
  secret.put("cookies",DeviceFingerprint.cookies(secret))
  return secret
 }
 private fun commitVerified(account:Account,role:Role,result:VerifiedNote) {
  val secret=account.secret?.copyJson()?:throw PublicError("AUTH_EXPIRED")
  val cookies=secret.getJSONObject("cookies")
  result.browserCookies.keys().forEach{k->if(k in ApiClient.COOKIE_NAMES)cookies.put(k,result.browserCookies.getString(k))}
  if(ApiClient.verifiedCookies(cookies)!=account.id)throw PublicError("AUTH_EXPIRED")
  secret.put("cookies",DeviceFingerprint.cookies(secret))
  if(DeviceFingerprint.formal(secret)==null&&!secret.has("deviceFpContext"))result.browserCookies.optString("DEVICEFP").takeIf{DeviceFingerprint.valid(it)}?.let{secret.put("deviceFp",it)}
  secret.put("cookies",DeviceFingerprint.cookies(secret))
  commit(account,role,result.note?:throw PublicError("RESPONSE_INVALID"),secret)
 }
 private fun commit(account:Account,role:Role,note:JSONObject,secret:JSONObject?=account.secret) {
  if(accounts[account.id]!==account)throw PublicError("CANCELLED")
  if(note.optString("status")!="ok"||note.optString("id")!=role.game.id||note.optString("roleUid")!=role.uid||note.optString("source") !in setOf("android_api","miyoushe_webview"))throw PublicError("RESPONSE_INVALID")
  val savedNote=note.copyJson().put("serverId",role.server).put("lastRefreshAttemptAt",clock())
  val meta=account.metadata.copyJson();val games=meta.optJSONArray("games")?.objects().orEmpty().filterNot{it.optString("id")==role.game.id&&it.optString("roleUid")==role.uid&&it.optString("serverId",role.server)==role.server}.toMutableList();games.add(savedNote)
  meta.put("games",JSONArray(games));meta.put("auth","authenticated");meta.put("verification",obj("state" to "notes_received","game" to role.game.id))
  val saved=Account(account.id,meta,secret,account.revision);store.save(saved);account.secret=secret
  account.metadata.put("games",meta.getJSONArray("games")).put("auth","authenticated").put("verification",meta.getJSONObject("verification"))
 }
 private fun failNote(account:Account,role:Role,error:String,retcode:Int?=null) {
  val meta=account.metadata.copyJson();val games=meta.optJSONArray("games")?.objects().orEmpty().toMutableList();val old=games.firstOrNull{it.optString("id")==role.game.id&&it.optString("roleUid")==role.uid&&it.optString("serverId",role.server)==role.server}
  if(old!=null)old.put("serverId",role.server).put("lastRefreshAttemptAt",clock())
  if(old?.optString("status")=="ok")old.put("stale",true).put("cached",true).put("lastError",error).put("retcode",retcode)
  else{games.remove(old);games.add(obj("id" to role.game.id,"roleUid" to role.uid,"serverId" to role.server,"lastRefreshAttemptAt" to clock(),"status" to error,"retcode" to (retcode?:if(error=="account_risk")5003 else null)))}
  val original=retcode?:account.metadata.optJSONObject("verification")?.takeIf{it.optString("game")==role.game.id}?.opt("retcode")
  meta.put("games",JSONArray(games));meta.put("verification",obj("state" to error,"game" to role.game.id,"retcode" to original));if(error=="auth_expired")meta.put("auth","expired")
  // A lost key must not prevent public cache recovery; no rewrite with missing credentials.
  if(account.secret!=null)store.save(Account(account.id,meta,account.secret,account.revision))
  account.metadata.put("games",meta.getJSONArray("games")).put("auth",meta.optString("auth","unknown")).put("verification",meta.getJSONObject("verification"))
 }
 private fun advance() {
  val current=job?:return
  if(waiting||cancelled||current.optBoolean("preparingDevices")||current.optString("status")!="running")return
  val rows=current.getJSONArray("roles").objects();if(rows.any{it.optString("state") in listOf("idle","refreshing")})return
  val pending=rows.firstOrNull{it.optString("state")=="verification_required"}
  if(pending!=null){
   val account=accounts[pending.getString("accountId")]?:return
   val role=account.metadata.getJSONArray("roles").objects().map{Role.fromSaved(it)}.first{it.game.id==pending.getString("game")&&it.uid==pending.getString("roleUid")&&it.server==pending.getString("server")}
   val trace=api.diagnostics.trace(account.id,role.key(),current.getString("refreshId")).copy(requestId=pending.optString("request_id"))
   val recover=pending.optBoolean("autoRecover")
   val routeReason=resolveVerification(account,role,pending.optInt("retcode"),pending.optString("challengeKind","none"),trace,recover)
   pending.put("verificationReason",routeReason)
   if(!routeAvailable(account,role,recover)){
    val error=try{failNote(account,role,"official_verification_unavailable",pending.optInt("retcode"));"official_verification_unavailable"}catch(e:Exception){"session_persist_failed"}
    account.metadata.optJSONObject("verification")?.put("reason",routeReason)
    pending.put("state","failed").put("error",error)
    api.diagnostics.event("verification_route_selected",role.game,retcode=pending.optInt("retcode"),classification=routeReason,trace=trace,stage="verification_policy",source="local")
    api.diagnostics.event("verification_unavailable",role.game,retcode=pending.optInt("retcode"),classification=routeReason,trace=trace,stage="verification_policy",source="local")
    refreshExecutor.submit{synchronized(lock){advance()}};return
   }
   if(!current.optBoolean("interactionReady"))return
   waiting=true;pending.put("state","waiting_for_user")
   openVerification(account,role,current.getString("refreshId"),recover){note,error->
    synchronized(lock){
     if(job!==current||cancelled||pending.optString("state")!="waiting_for_user")return@synchronized false
     verificationCodes[challengeKey(account,role)]?.let{pending.put("retcode",it)}
     var accepted=false
     try{if(note!=null){commitVerified(account,role,note);pending.put("state","success").put("retcode",0).put("error",JSONObject.NULL);accepted=true}else{failVerified(account,role,error);pending.put("state",if(error=="cancelled")"cancelled" else "failed").put("error",error)}}catch(e:Exception){pending.put("state","failed").put("error",if(e is PublicError&&e.code=="RESPONSE_INVALID")"response_invalid" else "session_persist_failed")}
     waiting=false;refreshExecutor.submit{synchronized(lock){advance()}};accepted
    }
   };return
  }
  if(rows.all{it.optString("state") in listOf("success","failed","cancelled")}){current.put("status","completed").put("elapsedMs",clock()-current.getLong("startedAt"));afterRefresh=clock()+5000;api.diagnostics.event("refresh_completed",source="native_api",classification="completed",trace=api.diagnostics.trace("refresh","refresh",current.getString("refreshId")),durationMs=clock()-current.getLong("startedAt"),stage="refresh")}
 }
 private fun resolveVerification(account:Account,role:Role,code:Int?,context:String,trace:DiagnosticTrace,recover:Boolean=false):String {
  api.diagnostics.event("verification_route_resolution_started",role.game,retcode=code,trace=trace,stage="verification_policy",source="local")
  val reason=VerificationResolution.reason(code,context,routeAvailable(account,role,recover))
  api.diagnostics.event("verification_route_resolution_result",role.game,retcode=code,classification=reason,trace=trace,stage="verification_policy",source="local",challengeKind=context)
  api.diagnostics.event("verification_route_selected",role.game,retcode=code,classification=reason,trace=trace,stage="verification_policy",source="local",verificationKind=challenge(account,role)?.kind?:"none",failureStage=if(!routeAvailable(account,role,recover))"native_rejection" else null)
  return reason
 }
 private fun openVerification(account:Account,role:Role,scope:String,recover:Boolean=false,done:(VerifiedNote?,String)->Boolean) {
  val port=verifier
  if(port==null){done(null,"verification_page_unavailable");return}
  val request=VerificationRequest(account,role,scope,challenge(account,role),api.diagnostics.trace(account.id,role.key(),scope),browse=recover&&challenge(account,role)==null,autoRecover=recover)
  request.retryRetcode=(account.metadata.optJSONArray("games")?.objects()?.firstOrNull{it.optString("id")==role.game.id&&it.optString("roleUid")==role.uid&&it.optString("serverId",role.server)==role.server}?.opt("retcode") as? Number)?.toInt()
  port.open(request){note,error->synchronized(lock){
   val key=challengeKey(account,role);challenges.remove(key)
   request.retryRetcode?.let{verificationCodes[key]=it}
   try{done(note,error)}finally{verificationCodes.remove(key)}
  }}
 }
 private fun action(body:JSONObject):JSONObject=synchronized(lock){
  val current=job?:throw PublicError("CANCELLED")
  if(body.optString("refreshId")!=current.optString("refreshId"))throw PublicError("CANCELLED")
  when(body.optString("action")){
   "cancel"->{fpOperation?.let{fpOperation=null;api.transport.cancel(it.id)};challenges.clear();cancelled=true;generation++;current.put("status","cancelled");current.getJSONArray("roles").objects().filter{it.optString("state") !in listOf("success","failed")}.forEach{it.put("state","cancelled")};api.transport.cancel(current.getString("refreshId"));verifier?.close(current.getString("refreshId"));waiting=false;afterRefresh=clock()+5000}
   "ready"->{current.put("interactionReady",true);refreshExecutor.submit{synchronized(lock){advance()}}}
   "focus"->{current.put("interactionReady",true);verifier?.focus();refreshExecutor.submit{synchronized(lock){advance()}}}
   else->throw PublicError("OPERATION_UNSUPPORTED")
  };progress()
 }
 private fun cancelManual():JSONObject=synchronized(lock){
  val scope=manualScope
  if(scope!=null){
   manualAccountId?.let{accounts[it]}?.let{account->
    val previous=account.metadata.optJSONObject("verification")?:JSONObject()
    account.metadata.put("verification",previous.copyJson().put("state","cancelled"))
    challenges.keys.filter{it.startsWith(account.id+":")}.toList().forEach{challenges.remove(it)}
   }
   manualScope=null;manualAccountId=null
   api.transport.cancel(scope);verifier?.close(scope)
  }
  obj("cancelled" to true)
 }
 private fun openRecord(body:JSONObject):JSONObject {
  val request=synchronized(lock){
   if(closed||active()||fpOperation!=null)throw PublicError("REFRESH_IN_PROGRESS")
   val account=accounts[body.optString("accountId",selected?:"")]?:throw PublicError("AUTH_EXPIRED")
   if(account.secret==null)throw PublicError("AUTH_EXPIRED")
   val role=account.metadata.getJSONArray("roles").objects().map{Role.fromSaved(it)}
    .firstOrNull{it.game.id==body.optString("game")&&it.uid==body.optString("roleUid")}?:throw PublicError("RESPONSE_INVALID")
   if(manualScope!=null){verifier?.focus();return obj("opened" to false,"alreadyPending" to true)}
   if(verifier?.supportsRecordBrowsing()!=true)throw PublicError("PROFILE_UNSUPPORTED")
   val scope=UUID.randomUUID().toString().replace("-","")
   manualScope=scope;manualAccountId=account.id;lastDiagnosticScope=scope
   VerificationRequest(account,role,scope,trace=api.diagnostics.trace(account.id,role.key(),scope),browse=true)
  }
  val account=request.account;val role=request.role;val scope=request.scope
  api.diagnostics.event("verification_requested",role.game,trace=request.trace,source="local",stage="verification_policy",verificationKind="none",outcome="opening")
  try {
   synchronized(lock){
    if(closed||manualScope!=scope||accounts[account.id]!==account)throw PublicError("CANCELLED")
    account.metadata.put("verification",obj("state" to "opening_record","game" to role.game.id,"route" to "overview"))
    verifier!!.open(request){result,error->synchronized(lock){
     if(closed||manualScope!=scope||accounts[account.id]!==account)return@synchronized false
     manualScope=null;manualAccountId=null
     try{
      if(result?.note!=null){commitVerified(account,role,result);true}
      else{
       val state=when(error){"cancelled"->"cancelled";"verification_timeout"->"verification_timeout";"promotion"->"record_promotion";"page_error"->"record_page_error";else->"closed_without_data"}
       val metadata=account.metadata.copyJson().put("verification",obj("state" to state,"game" to role.game.id,"route" to "overview"))
       if(result!=null){
        if(ApiClient.verifiedCookies(result.browserCookies)!=account.id)throw PublicError("AUTH_EXPIRED")
        val secret=mergeResponseCookies(account,result.browserCookies)
        store.save(Account(account.id,metadata,secret,account.revision));account.secret=secret
       }
       account.metadata.put("verification",metadata.getJSONObject("verification"));false
      }
     }catch(e:Exception){account.metadata.put("verification",obj("state" to "session_persist_failed","game" to role.game.id));false}
    }}
   }
   return obj("opened" to true,"route" to "overview","operationId" to scope)
  }catch(e:Exception){
   synchronized(lock){if(manualScope==scope){manualScope=null;manualAccountId=null;account.metadata.put("verification",obj("state" to "official_verification_unavailable","game" to role.game.id))}}
   api.diagnostics.event("verification_terminal_state",role.game,trace=request.trace,source="local",stage="verification_policy",outcome="unavailable")
   throw e
  }
 }
 private fun verify(body:JSONObject):JSONObject {
  val request=synchronized(lock){
   if(manualScope!=null){verifier?.focus();return obj("opened" to false,"alreadyPending" to true)}
   if(active()||fpOperation!=null||closed)throw PublicError("REFRESH_IN_PROGRESS")
   val account=accounts[body.optString("accountId",selected?:"")]?:throw PublicError("AUTH_EXPIRED")
   val role=account.metadata.getJSONArray("roles").objects().map{Role.fromSaved(it)}.firstOrNull{it.game.id==body.optString("game")&&it.uid==body.optString("roleUid")}?:throw PublicError("RESPONSE_INVALID")
   val key=challengeKey(account,role)
   if(clock()<(verificationAfter[key]?:0))throw PublicError("REFRESH_COOLDOWN")
   verificationAfter[key]=clock()+30000
   val scope=UUID.randomUUID().toString().replace("-","")
   manualScope=scope;manualAccountId=account.id;lastDiagnosticScope=scope
   VerificationRequest(account,role,scope,challenge(account,role),api.diagnostics.trace(account.id,role.key(),scope))
  }
  val account=request.account;val role=request.role;val scope=request.scope
  val trace=request.trace?:api.diagnostics.trace(account.id,role.key(),scope)
  api.diagnostics.event("verification_requested",role.game,trace=trace,source="local",stage="verification_policy")
  try{
   // Manual capability discovery is separate from interpreting 5003/10041 as a captcha.
   if(request.challenge==null&&role.game==Game.GENSHIN&&verifier?.supportsRecordChallenges()==true){
    val context=api.createRecordChallenge(account.secret?.copyJson()?:throw PublicError("AUTH_EXPIRED"),role,scope,trace)
    synchronized(lock){
     if(manualScope!=scope||accounts[account.id]!==account)throw PublicError("CANCELLED")
     if(context!=null)challenges[challengeKey(account,role)]=context
    }
   }
   return synchronized(lock){
    if(manualScope!=scope||accounts[account.id]!==account)throw PublicError("CANCELLED")
    val note=account.metadata.optJSONArray("games")?.objects()?.firstOrNull{it.optString("id")==role.game.id&&it.optString("roleUid")==role.uid&&it.optString("serverId",role.server)==role.server}
    val code=(note?.opt("retcode") as? Number)?.toInt()
    val hasContext=challenge(account,role)!=null
    val reason=resolveVerification(account,role,code,if(hasContext)challenge(account,role)!!.kind else "none",trace)
    if(!routeAvailable(account,role)||(code in setOf(5003,10041)&&!hasContext)){
     manualScope=null;manualAccountId=null
     account.metadata.put("verification",obj("state" to "official_verification_unavailable","game" to role.game.id,"roleUid" to role.uid,"retcode" to code,"reason" to reason))
     api.diagnostics.event("verification_unavailable",role.game,retcode=code,classification=reason,trace=trace,stage="verification_policy",source="local")
     return@synchronized obj("opened" to false,"reason" to "official_verification_unavailable")
    }
    val route=challenge(account,role)?.kind?:"overview"
    account.metadata.put("verification",obj("state" to "opened","game" to role.game.id,"route" to route))
    openVerification(account,role,scope){result,error->synchronized(lock){
     if(manualScope!=scope)return@synchronized false
     manualScope=null;manualAccountId=null
     if(accounts[account.id]!==account)return@synchronized false
     try{if(result!=null){commitVerified(account,role,result);true}else{failVerified(account,role,error);false}}
     catch(e:Exception){account.metadata.put("verification",obj("state" to "session_persist_failed"));false}
    }}
    obj("opened" to true,"route" to route)
   }
  }catch(e:Exception){
   synchronized(lock){if(manualScope==scope){manualScope=null;manualAccountId=null;challenges.remove(challengeKey(account,role));account.metadata.put("verification",obj("state" to if(e is PublicError&&e.code=="CANCELLED")"cancelled" else "official_verification_unavailable","game" to role.game.id))}}
   api.diagnostics.event("verification_unavailable",role.game,retcode=(e as? PublicError)?.retcode,classification="official_verification_unavailable",trace=trace,stage="verification_policy",source="native_api",failureStage="discovery")
   throw e
  }
 }
 fun close() {
  synchronized(lock){closed=true;fpOperation?.let{api.transport.cancel(it.id)};fpOperation=null;manualScope?.let{verifier?.close(it)};manualScope=null;qrs.keys.toList().forEach{cancelQr(it)};cancelled=true;generation++;job?.optString("refreshId")?.let{api.transport.cancel(it);verifier?.close(it)}}
  synchronized(lock){challenges.clear()}
  profileBatchCancelled=true;profileBatchTask?.cancel(true);profileBatchScope?.let{api.transport.cancel(it)}
  checkin.closeBatch();profileExecutor.shutdownNow();refreshExecutor.shutdownNow()
 }
}
