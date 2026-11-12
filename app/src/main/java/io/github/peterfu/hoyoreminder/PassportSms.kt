package io.github.peterfu.hoyoreminder
import org.json.JSONObject
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher

/** The same audited Passport endpoints/RSA key as Windows PassportSms, independent of note UA. */
class PassportSms(private val api:ApiClient,private val now:()->Long={System.currentTimeMillis()}){
 data class Challenge(val session:String,val config:JSONObject,val version:Int){
  fun proof(answer:JSONObject):String {
   val keys=if(version==3)listOf("geetest_challenge","geetest_validate","geetest_seccode") else listOf("captcha_id","lot_number","pass_token","gen_time","captcha_output")
   if(keys.any{answer.opt(it) !is String||answer.getString(it).length !in 1..8192||answer.getString(it).any{c->c in "\r\n"}})throw PublicError("RESPONSE_INVALID")
   if(version==4&&answer.getString("captcha_id")!=config.getString("gt"))throw PublicError("RESPONSE_INVALID")
   val checked=JSONObject();keys.forEach{checked.put(it,answer.getString(it))}
   return session+";"+Base64.getEncoder().encodeToString(checked.toString().toByteArray(Charsets.UTF_8))
  }
 }
 private data class Session(val id:String,val phone:String,val device:String,val expires:Long,val epochs:Map<String,Long> = AccountEpoch.snapshot(),@Volatile var sent:Boolean=false,@Volatile var action:String="",@Volatile var challenge:Challenge?=null,@Volatile var cookies:JSONObject?=null,@Volatile var error:String?=null,@Volatile var code:Int?=null,@Volatile var phase:String="send",@Volatile var result:JSONObject?=null,@Volatile var cancelReason:String="none")
 @Volatile private var session:Session?=null
 private var cooldown=0L
 private val lock=Any()
 private val identityLock=Any()
 private fun event(name:String,s:Session,trigger:String="none",committed:Boolean=s.result!=null,match:Boolean=session===s)=api.diagnostics.login(name,s.id,s.phase,trigger,committed,match)
 private fun valid(s:Session):Boolean=session===s&&now()<=s.expires
 private fun check(s:Session){if(!valid(s)){event("login_aborted",s,if(now()>s.expires)"expired" else s.cancelReason);throw PublicError(if(now()>s.expires)"SMS_SESSION_EXPIRED" else "CANCELLED")}}
 private fun encrypt(text:String):String{
  val key=KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode("MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDDvekdPMHN3AYhm/vktJT+YJr7cI5DcsNKqdsx5DZX0gDuWFuIjzdwButrIYPNmRJ1G8ybDIF7oDW2eEpm5sMbL9zs9ExXCdvqrn51qELbqj0XxtMTIpaCHFSI50PfPpTFV9Xt/hmyVwokoOXFlAEgCn+QCgGs52bFoYMtyi+xEQIDAQAB")))
  val cipher=Cipher.getInstance("RSA/ECB/PKCS1Padding");cipher.init(Cipher.ENCRYPT_MODE,key)
  return Base64.getEncoder().encodeToString(cipher.doFinal(text.toByteArray(Charsets.UTF_8)))
 }
 private fun request(s:Session,path:String,body:JSONObject?=null,proof:String?=null,stoken:String?=null,mid:String?=null):WireResponse {
  val h=mutableMapOf("User-Agent" to "HoYoReminder-Windows/0.2","Accept" to "application/json","x-rpc-app_id" to "bll8iq97cem8",
   "x-rpc-device_id" to s.device,"x-rpc-client_type" to "2","x-rpc-device_name" to "Spindrift")
  // Passport display metadata only; never the account device/FP model. Moonward AddCaptchaHeaders uses this optional field.
  // Optional display model is deliberately omitted; not the physical device model.
  if(path.contains("createLoginCaptcha")){h["Referer"]="https://user.miyoushe.com/";h["x-rpc-game_biz"]="hk4e_cn"}
  if(proof!=null)h["x-rpc-aigis"]=proof
  if(stoken!=null&&mid!=null)h["Cookie"]="mid=$mid;stoken=$stoken"
  s.phase=when{path.contains("createLoginCaptcha")->"send";path.contains("loginByMobileCaptcha")->"login_confirm";path.contains("getLToken")->"ltoken_exchange";else->"cookie_exchange"}
  api.diagnostics.passportDisplay(s.phase,false)
  val response=api.transport.execute(if(body==null)"GET" else "POST","https://passport-api.mihoyo.com/"+path,h,body,s.id)
  api.diagnostics.event("sms_request_result",retcode=response.json.getInt("retcode"),trace=api.diagnostics.trace("sms:"+s.id,"account",s.id),stage="authentication",source="sms",authenticationPhase=s.phase)
  check(s)
  if(response.json.getInt("retcode")==0)event(when(s.phase){"send"->"sms_send_accepted";"login_confirm"->"sms_confirm_accepted";"ltoken_exchange"->"ltoken_exchange_accepted";else->"cookie_exchange_accepted"},s)
  return response
 }
 private fun data(r:WireResponse):JSONObject {
  val c=r.json.getInt("retcode")
  if(c!=0)throw PublicError("SMS_OFFICIAL_REJECTED",c)
  return r.json.optJSONObject("data")?:throw PublicError("RESPONSE_INVALID")
 }
 fun status():JSONObject {
  val s=session?.takeIf{it.expires>=now()}?:return obj("expired" to true)
  return obj("smsId" to s.id,"sent" to s.sent,"challenge" to (s.challenge!=null),"error" to s.error,"retcode" to s.code,"authenticated" to (s.cookies!=null),"phase" to s.phase,"completed" to (s.result!=null),"result" to s.result?.copyJson())
 }
 fun cancel(id:String?=null,trigger:String="user"){
  synchronized(identityLock){val s=session?:return;event("login_cancel_requested",s,trigger,match=id==null||id==s.id)
   if(id!=null&&id!=s.id)return
   if(s.result!=null)return // UI closure cannot undo a committed account; retain its ACK.
   s.cancelReason=trigger;session=null;event("login_cancel_applied",s,trigger);api.transport.cancel(s.id)
  }
 }
 fun acknowledge(id:String):JSONObject=synchronized(identityLock){
  val s=session?.takeIf{it.id==id&&it.result!=null}?:throw PublicError("RESPONSE_INVALID")
  event("login_ui_ack",s,committed=true);session=null;obj("ok" to true)
 }
 fun challenge(id:String):Challenge=synchronized(lock){val s=session?.takeIf{it.id==id&&it.expires>=now()}?:throw PublicError("CANCELLED");s.challenge?:throw PublicError("RESPONSE_INVALID")}
 fun send(body:JSONObject,answer:JSONObject?=null):JSONObject=synchronized(lock){
  val id=body.optString("smsId")
  val s=if(id.isBlank()){
   val phone=body.optString("phone");if(!Regex("1[0-9]{10}").matches(phone)||body.optString("areaCode","+86")!="+86")throw PublicError("SMS_INVALID_PHONE")
   if(now()<cooldown)throw PublicError("REFRESH_COOLDOWN")
   cooldown=now()+60000
   synchronized(identityLock){session?.let{old->old.cancelReason="replaced_session";event("login_cancel_applied",old,"replaced_session");api.transport.cancel(old.id)}
    Session(UUID.randomUUID().toString(),phone,UUID.randomUUID().toString(),now()+300000).also{session=it;event("login_sms_started",it)}
   }
  }else session?.takeIf{it.id==id&&it.expires>=now()}?:throw PublicError("CANCELLED")
  try{
   if(s.sent)return status()
   val proof=if(answer==null)null else (s.challenge?:throw PublicError("CANCELLED")).proof(answer)
   val response=request(s,"account/ma-cn-verifier/verifier/createLoginCaptcha",obj("area_code" to encrypt("+86"),"mobile" to encrypt(s.phone)),proof)
   if(response.json.getInt("retcode")==-3101){
    val raw=response.headers.filterKeys{it.equals("x-rpc-aigis",true)}.values.flatten().singleOrNull()?.takeIf{it.length<=16384}?:throw PublicError("RESPONSE_INVALID")
    val envelope=JSONObject(raw);val sid=envelope.getString("session_id")
    if(sid.length !in 1..1024||sid.any{it in "\r\n;"})throw PublicError("RESPONSE_INVALID")
    val cfg=when(val c=envelope.get("data")){is String->JSONObject(c);is JSONObject->c;else->throw PublicError("RESPONSE_INVALID")}
    if(!Regex("[A-Za-z0-9_-]{1,128}").matches(cfg.optString("gt")))throw PublicError("RESPONSE_INVALID")
    val version=if(cfg.optString("challenge").isNotBlank())3 else 4
    if(version==3&&!Regex("[A-Za-z0-9_-]{1,256}").matches(cfg.optString("challenge")))throw PublicError("RESPONSE_INVALID")
    s.challenge=Challenge(sid,cfg,version)
   }else{
    val d=data(response);val action=d.optString("action_type");if(action.length !in 1..128)throw PublicError("RESPONSE_INVALID")
    s.action=action;s.sent=true;s.challenge=null
   };s.error=null;s.code=null;status()
  }catch(e:Exception){s.error=(e as? PublicError)?.code?:"NETWORK_ERROR";s.code=(e as? PublicError)?.retcode;if(id.isBlank())synchronized(identityLock){if(session===s)session=null};event("login_aborted",s,if(e is PublicError&&e.code=="CANCELLED")s.cancelReason else "callback_error");throw e}
 }
 fun login(body:JSONObject,commit:(JSONObject,String,String,()->Boolean)->JSONObject):JSONObject=synchronized(lock){
  val s=session?.takeIf{it.id==body.optString("smsId")&&it.expires>=now()&&it.sent}?:throw PublicError("CANCELLED")
  s.result?.let{return it.copyJson()}
  val code=body.opt("code");if(s.cookies==null&&(code !is String||!Regex("[0-9]{4,8}").matches(code)))throw PublicError("SMS_INVALID_CODE")
  try{
   if(s.cookies==null){
    val d=data(request(s,"account/ma-cn-passport/app/loginByMobileCaptcha",obj("area_code" to encrypt("+86"),"mobile" to encrypt(s.phone),"captcha" to code,"action_type" to s.action)))
    val token=d.getJSONObject("token").getString("token");val user=d.getJSONObject("user_info");val aid=user.getString("aid");val mid=user.getString("mid")
    if(!Regex("[0-9]{1,24}").matches(aid)||listOf(token,mid).any{it.isBlank()||it.any{c->c in "\r\n;"}})throw PublicError("RESPONSE_INVALID")
    val query="?stoken="+java.net.URLEncoder.encode(token,"UTF-8")
    val lt=data(request(s,"account/auth/api/getLTokenBySToken"+query,stoken=token,mid=mid)).getString("ltoken")
    val ct=data(request(s,"account/auth/api/getCookieAccountInfoBySToken"+query,stoken=token,mid=mid))
    if(ct.optString("uid",aid)!=aid)throw PublicError("RESPONSE_INVALID")
    val cookies=obj("account_id" to aid,"ltuid" to aid,"account_id_v2" to aid,"ltuid_v2" to aid,"account_mid_v2" to mid,"ltmid_v2" to mid,
     "ltoken" to lt,"ltoken_v2" to lt,"cookie_token" to ct.getString("cookie_token"),"cookie_token_v2" to ct.getString("cookie_token"))
    ApiClient.verifiedCookies(cookies);s.cookies=cookies;s.phase="credentials_validated";event("credentials_validated",s)
   }
   val owner=ApiClient.verifiedCookies(s.cookies!!)
   if(!AccountEpoch.unchanged(owner,s.epochs)){event("login_aborted",s,"logout");throw PublicError("CANCELLED")}
   check(s)
   s.phase="role_discovery";event("account_registration_started",s)
   val result=commit(s.cookies!!.copyJson(),s.id,s.device){session===s&&now()<=s.expires&&AccountEpoch.unchanged(owner,s.epochs)}
   s.result=result.copyJson();s.phase="committed";s.error=null;event("account_committed",s,committed=true);result
  }catch(e:Exception){s.error=(e as? PublicError)?.code?:"SMS_LOGIN_FAILED";s.code=(e as? PublicError)?.retcode;event("login_aborted",s,if(e is PublicError&&e.code=="CANCELLED")s.cancelReason else "callback_error");throw e}
 }
}
