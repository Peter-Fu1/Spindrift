package io.github.peterfu.hoyoreminder

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.HttpCookie
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

data class WireResponse(val json:JSONObject,val headers:Map<String,List<String>> = emptyMap(),val httpStatus:Int=200)
interface Transport { fun execute(method:String,url:String,headers:Map<String,String>,body:JSONObject?=null,scope:String=""):WireResponse;fun cancel(scope:String){}
 fun executeCheckin(method:String,url:String,headers:Map<String,String>,body:JSONObject?,scope:String,beforeSend:()->Unit):WireResponse {beforeSend();return execute(method,url,headers,body,scope)}
 fun executeAudited(method:String,url:String,headers:Map<String,String>,body:JSONObject?=null,scope:String="",audit:RequestAudit):WireResponse {
  audit.send(null,false);return execute(method,url,headers,body,scope).also{audit.result(it)}
 }
}
fun parseEnvelope(text:String,contentType:String?):JSONObject {
 if(contentType!=null&&!contentType.substringBefore(';').trim().lowercase().let{it=="application/json"||it.endsWith("+json")})throw PublicError("RESPONSE_INVALID")
 val value=try{JSONObject(text)}catch(e:Exception){throw PublicError("RESPONSE_INVALID")}
 val code=value.opt("retcode")
 if((code !is Int&&code !is Long)||(code as Number).toLong() !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())throw PublicError("RESPONSE_INVALID")
 return value
}
class HttpTransport(private val connection:(URI)->HttpURLConnection={it.toURL().openConnection() as HttpURLConnection}):Transport {
 private val fingerprint=FingerprintHttpTransport()
 private val active=ConcurrentHashMap<HttpURLConnection,String>()
 override fun cancel(scope:String){fingerprint.cancel(scope);active.entries.filter{scope=="*"||it.value==scope}.forEach{it.key.disconnect()}}
 override fun execute(method:String,url:String,headers:Map<String,String>,body:JSONObject?,scope:String)=perform(method,url,headers,body,scope,null)
 override fun executeCheckin(method:String,url:String,headers:Map<String,String>,body:JSONObject?,scope:String,beforeSend:()->Unit)=perform(method,url,headers,body,scope,null,beforeSend,true)
 override fun executeAudited(method:String,url:String,headers:Map<String,String>,body:JSONObject?,scope:String,audit:RequestAudit)=perform(method,url,headers,body,scope,audit).also{audit.result(it)}
 private fun perform(method:String,url:String,headers:Map<String,String>,body:JSONObject?,scope:String,audit:RequestAudit?,beforeSend:()->Unit={},checkin:Boolean=false):WireResponse {
  if(url==DeviceFingerprint.ENDPOINT)return fingerprint.execute(method,url,headers,body,scope)
  val uri=URI(url)
  if(uri.scheme!="https"||uri.userInfo!=null||uri.port !in listOf(-1,443)||uri.host !in setOf("passport-api.miyoushe.com","passport-api.mihoyo.com","act-nap-api.mihoyo.com","api-takumi.mihoyo.com","api-takumi-record.mihoyo.com","bbs-api.miyoushe.com"))throw PublicError("RESPONSE_INVALID")
  val conn=connection(uri);active[conn]=scope
  try {
   conn.requestMethod=method;conn.instanceFollowRedirects=false;conn.connectTimeout=15000;conn.readTimeout=20000;conn.useCaches=false
   headers.forEach{(k,v)->if(k.contains('\n')||v.contains('\r')||v.contains('\n'))throw PublicError("RESPONSE_INVALID") else conn.setRequestProperty(k,v)}
   if(audit?.enabled==true)runCatching{conn.requestProperties.mapValues{it.value.singleOrNull()?:""}}.fold({audit.send(it,true)},{audit.send(null,false)})
   beforeSend()
   if(method=="POST"){conn.doOutput=true;conn.setRequestProperty("Content-Type","application/json");conn.outputStream.use{it.write((body?.toString()?:"{}").toByteArray(Charsets.UTF_8))}}
   if(checkin&&conn.responseCode !in 200..299)throw CheckInHttpFailure(conn.responseCode,CheckInRetry.retryAfter(conn.getHeaderField("Retry-After"),System.currentTimeMillis()),conn.headerFields.keys.any{it.equals("x-rpc-aigis",true)})
   if(conn.responseCode !in 200..299)throw PublicError(if(conn.responseCode==429)"REFRESH_COOLDOWN" else "NETWORK_ERROR",httpStatus=conn.responseCode)
   val bytes=conn.inputStream.use{input->val out=ByteArrayOutputStream();val buffer=ByteArray(8192);while(true){val n=input.read(buffer);if(n<0)break;if(out.size()+n>2_000_000)throw PublicError("RESPONSE_INVALID");out.write(buffer,0,n)};out.toByteArray()}
   if(bytes.size>2_000_000)throw PublicError("RESPONSE_INVALID")
   val value=try{parseEnvelope(String(bytes,Charsets.UTF_8),conn.contentType)}catch(e:PublicError){throw PublicError(e.code,e.retcode,conn.responseCode)}
   return WireResponse(value,conn.headerFields.filterKeys{it!=null}.mapKeys{it.key!!},conn.responseCode)
  } catch(e:CheckInHttpFailure){throw e} catch(e:PublicError){audit?.failure(e);throw e} catch(e:Exception){val error=PublicError("NETWORK_ERROR");audit?.failure(error);throw error} finally {active.remove(conn);conn.disconnect()}
 }
}
class ApiClient(val transport:Transport,val diagnostics:Diagnostics=Diagnostics()) {
 companion object {
  val AUTH_NAMES=setOf("cookie_token_v2","account_mid_v2","account_id_v2","ltoken_v2","ltmid_v2","ltuid_v2")
  val COOKIE_NAMES=AUTH_NAMES+setOf("ltoken","ltuid","cookie_token","account_id","DEVICEFP","DEVICEFP_SEED_ID","DEVICEFP_SEED_TIME")
  fun cookiesFrom(headers:Map<String,List<String>>):JSONObject {
   val result=JSONObject()
   headers.filterKeys{it.equals("Set-Cookie",true)}.values.flatten().forEach { raw ->
    runCatching{HttpCookie.parse(raw)}.getOrDefault(emptyList()).forEach{c->
     val domain=c.domain?.removePrefix(".")
     if(c.name in COOKIE_NAMES&&(domain==null||domain=="miyoushe.com"||domain=="mihoyo.com"||domain=="passport-api.miyoushe.com")&&c.value.length<=8192&&c.value.none{it=='\r'||it=='\n'||it==';'})result.put(c.name,c.value)
    }
   };return result
  }
  fun cookieHeader(cookies:JSONObject)=cookies.keys().asSequence().filter{it in COOKIE_NAMES}.map{"$it=${cookies.getString(it)}"}.joinToString("; ")
  fun verifiedCookies(cookies:JSONObject):String {
   if(!AUTH_NAMES.all{cookies.optString(it).isNotBlank()}||!Regex("[0-9]{1,24}").matches(cookies.optString("account_id_v2"))||cookies.optString("ltuid_v2")!=cookies.optString("account_id_v2")||cookies.optString("ltmid_v2")!=cookies.optString("account_mid_v2")||AUTH_NAMES.any{cookies.optString(it).length>8192||cookies.optString(it).any{c->c in "\r\n;"}})throw PublicError("RESPONSE_INVALID")
   return cookies.getString("account_id_v2")
  }
 }
 private fun authHeaders(device:String)=mapOf("x-rpc-app_id" to "bll8iq97cem8","x-rpc-client_type" to "4","x-rpc-game_biz" to "bbs_cn","x-rpc-device_id" to device)
 fun headers(secret:JSONObject,query:String="",body:String="",record:Boolean=false):Map<String,String> {
  val h=mutableMapOf("User-Agent" to if(record)Protocol.USER_AGENT else "HoYoReminder-Android/0.1.1","x-rpc-app_version" to Protocol.VERSION,"x-rpc-client_type" to "5","x-rpc-app_id" to "bll8iq97cem8","x-rpc-device_id" to secret.getString("deviceId"),"x-rpc-language" to "zh-cn","x-rpc-lang" to "zh-cn","x-rpc-sdk_version" to "2.16.0","x-rpc-channel" to "","Referer" to "https://webstatic.mihoyo.com/","Cookie" to cookieHeader(DeviceFingerprint.cookies(secret)))
  DeviceFingerprint.formal(secret)?.let{h["x-rpc-device_fp"]=it}
  if(query.isNotEmpty()||body.isNotEmpty())h["DS"]=Protocol.ds2(query,body=body)
  return h
 }
 fun fingerprint(identity:JSONObject,current:String?,scope:String):String {
  val response=transport.execute("POST",DeviceFingerprint.ENDPOINT,
   mapOf("User-Agent" to Protocol.USER_AGENT,"x-rpc-app_version" to Protocol.VERSION),
   DeviceFingerprint.payload(identity,current),scope)
  diagnostics.event("device_fp_http_receive",url=DeviceFingerprint.ENDPOINT,retcode=DeviceFingerprint.int(response.json.opt("retcode")),fpResultCode=DeviceFingerprint.int(response.json.optJSONObject("data")?.opt("code")),httpStatus=response.httpStatus,trace=diagnostics.trace(identity.getString("owner"),"account",scope),source="native_api",stage="response")
  if(response.httpStatus!=200)throw PublicError("FP_HTTP_REJECTED",httpStatus=response.httpStatus)
  return DeviceFingerprint.accepted(response.json)
 }
 fun data(response:WireResponse):JSONObject {
  val code=response.json.getInt("retcode");if(code!=0)throw PublicError(when(code){5003->"ACCOUNT_RISK";10001,-100->"AUTH_EXPIRED";else->"UPSTREAM_REJECTED"},code)
  return response.json.optJSONObject("data")?:throw PublicError("RESPONSE_INVALID")
 }
 fun createQr(device:String,scope:String):JSONObject {
  try{return data(transport.execute("POST","https://passport-api.miyoushe.com/account/ma-cn-passport/web/createQRLogin",authHeaders(device),scope=scope))}
  catch(e:Exception){diagnostics.event("login_failed",retcode=(e as? PublicError)?.retcode,trace=diagnostics.trace("qr:"+scope,"account",scope),stage="authentication",source="qr",exceptionCategory=publicExceptionCategory(e));throw e}
 }
 fun pollQr(device:String,ticket:String,scope:String)=transport.execute("POST","https://passport-api.miyoushe.com/account/ma-cn-passport/web/queryQRLoginStatus",authHeaders(device),obj("ticket" to ticket),scope)
 fun discover(secret:JSONObject,scope:String):List<Role> = data(transport.execute("GET","https://api-takumi.mihoyo.com/binding/api/getUserGameRolesByCookie",headers(secret),scope=scope)).getJSONArray("list").objects().mapNotNull{Role.fromApi(it)}
 fun note(secret:JSONObject,role:Role,scope:String,overrideCookie:String?=null,officialHeaders:Map<String,String> = emptyMap(),trace:DiagnosticTrace?=null):WireResponse {
  if(!Role.valid(role.game,role.uid,role.server))throw PublicError("RESPONSE_INVALID")
  val query="role_id=${role.uid}&server=${role.server}";val base=NotesAdapter.headers(headers(secret,query,record=true),role.game);val h=(if(role.game==Game.ZENLESS)ZzzH5Headers.apply(base,secret,role) else base).toMutableMap()
  // Only headers for this exact role endpoint; no arbitrary URL forwarding.
  officialHeaders.filterKeys{it.lowercase() in setOf("x-rpc-aigis","x-rpc-challenge")}.forEach{(k,v)->h.keys.firstOrNull{it.equals(k,true)}?.let{h.remove(it)};h[k]=v}
  if(overrideCookie!=null){
   val observed=JSONObject();overrideCookie.split(';').map{it.trim().split('=',limit=2)}.filter{it.size==2&&it[0] in COOKIE_NAMES}.forEach{observed.put(it[0],it[1])}
   if(verifiedCookies(observed)!=verifiedCookies(secret.getJSONObject("cookies")))throw PublicError("AUTH_EXPIRED")
   h["Cookie"]=cookieHeader(DeviceFingerprint.cookies(secret.copyJson().put("cookies",observed)))
  }
  val url=Protocol.noteUrl(role)
  diagnostics.event("request_identity",role.game,url,trace=trace,stage="request",source="native_api",fpPresent=!h["x-rpc-device_fp"].isNullOrBlank(),fpMatchesAccount=DeviceFingerprint.formal(secret)?.let{h["x-rpc-device_fp"]==it}?:false,uaContractMatch=h["User-Agent"]==Protocol.USER_AGENT,requestStage="constructed",observation="not_applicable")
  val began=System.nanoTime()
  diagnostics.event("notes_request_started",role.game,url,classification="native_api",fpPresent=h.containsKey("x-rpc-device_fp"),trace=trace,stage="request",source="native_api")
  try {
   val response=transport.executeAudited("GET",url,h,scope=scope,audit=RequestAudit(diagnostics,secret,role,trace?:diagnostics.trace(verifiedCookies(secret.getJSONObject("cookies")),role.key(),scope),url,"native_api"))
   diagnostics.event("notes_response_received",role.game,url,response.json.getInt("retcode"),if(response.json.getInt("retcode")==0)"native_api" else "business_error",httpStatus=response.httpStatus,trace=trace,durationMs=(System.nanoTime()-began)/1000000,stage="response",source="native_api")
   return response
  }catch(e:PublicError){diagnostics.event("notes_response_received",role.game,url,classification=if(e.code=="RESPONSE_INVALID")"response_invalid" else if(e.httpStatus!=null)"http_error" else "network_error",httpStatus=e.httpStatus,trace=trace,durationMs=(System.nanoTime()-began)/1000000,stage="response",source="native_api");throw e}
 }
 fun observedNote(secret:JSONObject,role:Role,url:String,cookie:String,officialHeaders:Map<String,String>,scope:String,trace:DiagnosticTrace?=null):WireResponse {
  if(!Protocol.matchesNote(url,role))throw PublicError("RESPONSE_INVALID")
  val pairs=URI(url).rawQuery.split("&").map{it.split("=",limit=2)}.sortedBy{it[0]}
  val query=pairs.joinToString("&"){java.net.URLDecoder.decode(it[0],"UTF-8")+"="+java.net.URLDecoder.decode(it[1],"UTF-8")}
  val observed=JSONObject()
  cookie.split(';').map{it.trim().split('=',limit=2)}.filter{it.size==2&&it[0] in COOKIE_NAMES}.forEach{observed.put(it[0],it[1])}
  if(verifiedCookies(observed)!=verifiedCookies(secret.getJSONObject("cookies")))throw PublicError("AUTH_EXPIRED")
  val browser=secret.copyJson().put("cookies",observed)
  val h=headers(secret,query,record=true).toMutableMap();h["Cookie"]=cookieHeader(DeviceFingerprint.cookies(browser))
  val allowed=setOf("ds","x-rpc-client_type","x-rpc-sdk_version","x-rpc-tool_verison","x-rpc-tool-version")
  officialHeaders.filterKeys{it.lowercase() in allowed}.forEach{(k,v)->h.keys.firstOrNull{it.equals(k,true)}?.let{h.remove(it)};h[k]=v}
  // Official H5 puts the server-accepted verification token on this exact role request.
  // Do not drop it, persist it, synthesize it, or reuse it for a subsequent native request.
  val proofs=officialHeaders.entries.filter{it.key.equals("x-rpc-challenge",true)}
  if(proofs.size>1)throw PublicError("RESPONSE_INVALID")
  proofs.singleOrNull()?.value?.takeIf{it.isNotBlank()}?.let{
   if(it.length>8192||it.any{c->c<' '||c=='\u007f'})throw PublicError("RESPONSE_INVALID")
   h["x-rpc-challenge"]=it
  }
  val page=officialHeaders.entries.firstOrNull{it.key.equals("x-rpc-page",true)}?.value
   ?.takeIf{it in setOf(ZzzH5Headers.PAGE,"v3.0.17_#/zzz/notebook")}?:ZzzH5Headers.PAGE
  val finalHeaders=if(role.game==Game.ZENLESS)ZzzH5Headers.apply(h,browser,role,page) else h
  val requestTrace=trace?:diagnostics.trace(verifiedCookies(observed),role.key(),scope)
  val fp=DeviceFingerprint.formal(secret)
  diagnostics.event("request_identity",role.game,url,trace=requestTrace,source="webview",stage="request",
   fpPresent=finalHeaders.containsKey("x-rpc-device_fp"),
   fpMatchesAccount=fp!=null&&finalHeaders["x-rpc-device_fp"]==fp&&DeviceFingerprint.cookies(browser).optString("DEVICEFP")==fp&&finalHeaders["x-rpc-device_id"]==secret.optString("deviceId"),
   uaContractMatch=finalHeaders["User-Agent"]==Protocol.USER_AGENT,proofPresent=!finalHeaders["x-rpc-challenge"].isNullOrBlank())
  return transport.executeAudited("GET",url,finalHeaders,scope=scope,audit=RequestAudit(diagnostics,secret,role,requestTrace,url,"webview"))
 }
 private fun recordHeaders(secret:JSONObject,role:Role,query:String="",body:String=""):Map<String,String> {
  if(role.game!=Game.GENSHIN||!Role.valid(role.game,role.uid,role.server))throw PublicError("VERIFICATION_UNAVAILABLE")
  verifiedCookies(secret.getJSONObject("cookies"))
  return headers(secret,query,body,record=true)+mapOf("x-rpc-challenge_game" to "2","x-rpc-challenge_path" to ("https://api-takumi-record.mihoyo.com"+role.game.path))
 }
 fun createRecordChallenge(secret:JSONObject,role:Role,scope:String,trace:DiagnosticTrace):NoteChallenge? {
  val query="is_high=true"
  diagnostics.event("verification_discovery_started",role.game,trace=trace,stage="verification_policy",source="native_api",verificationKind="record")
  val response=transport.execute("GET","https://api-takumi-record.mihoyo.com/game_record/app/card/wapi/createVerification?$query",recordHeaders(secret,role,query),scope=scope)
  val code=response.json.getInt("retcode")
  diagnostics.event("verification_discovery_result",role.game,retcode=code,httpStatus=response.httpStatus,trace=trace,stage="verification_policy",source="native_api",verificationKind="record")
  if(code==30001)return null
  if(code!=0)throw PublicError("VERIFICATION_REJECTED",code)
  return NoteChallenge.fromRecord(response,verifiedCookies(secret.getJSONObject("cookies")),role)?:throw PublicError("RESPONSE_INVALID")
 }
 fun answerChallenge(secret:JSONObject,role:Role,challenge:NoteChallenge,answer:JSONObject,scope:String,trace:DiagnosticTrace):Pair<JSONObject,Map<String,String>> {
  if(challenge.owner.isNotBlank()&&(challenge.owner!=verifiedCookies(secret.getJSONObject("cookies"))||challenge.roleKey!=role.key()))throw PublicError("RESPONSE_INVALID")
  if(challenge.kind=="aigis")return secret to challenge.proof(answer)
  if(challenge.owner!=verifiedCookies(secret.getJSONObject("cookies"))||challenge.roleKey!=role.key())throw PublicError("RESPONSE_INVALID")
  val session=secret.copyJson()
  challenge.cookieUpdates.keys().forEach{k->session.getJSONObject("cookies").put(k,challenge.cookieUpdates.getString(k))}
  if(verifiedCookies(session.getJSONObject("cookies"))!=challenge.owner)throw PublicError("RESPONSE_INVALID")
  val proof=challenge.checkedAnswer(answer);val body=proof.toString()
  diagnostics.event("verification_submit_started",role.game,trace=trace,stage="verification_policy",source="native_api",verificationKind="record")
  val response=transport.execute("POST","https://api-takumi-record.mihoyo.com/game_record/app/card/wapi/verifyVerification",recordHeaders(session,role,body=body),proof,scope)
  val code=response.json.getInt("retcode")
  diagnostics.event("verification_submit_result",role.game,retcode=code,httpStatus=response.httpStatus,trace=trace,stage="verification_policy",source="native_api",verificationKind="record")
  if(code!=0)throw PublicError("VERIFICATION_REJECTED",code)
  val token=response.json.optJSONObject("data")?.optString("challenge")?:""
  if(token.isBlank()||token.length>8192||token.any{it=='\r'||it=='\n'})throw PublicError("RESPONSE_INVALID")
  cookiesFrom(response.headers).keys().forEach{k->session.getJSONObject("cookies").put(k,cookiesFrom(response.headers).getString(k))}
  if(verifiedCookies(session.getJSONObject("cookies"))!=challenge.owner)throw PublicError("RESPONSE_INVALID")
  return session to mapOf("x-rpc-challenge" to token)
 }
 fun recordEntry(secret:JSONObject,accountId:String,role:Role,scope:String,trace:DiagnosticTrace?=null):RecordEntry {
  if(verifiedCookies(secret.getJSONObject("cookies"))!=accountId||!Role.valid(role.game,role.uid,role.server))throw PublicError("AUTH_EXPIRED")
  val cards=try{
   val query="uid=$accountId"
   val url="https://api-takumi-record.mihoyo.com/game_record/app/card/wapi/getGameRecordCard?$query"
   data(transport.executeAudited("GET",url,headers(secret,query,record=true),scope=scope,audit=RequestAudit(diagnostics,secret,role,trace?:diagnostics.trace(accountId,role.key(),scope),url,"native_api"))).getJSONArray("list").objects()
  }catch(e:PublicError){if(e.code in setOf("AUTH_EXPIRED","CANCELLED"))throw e else emptyList()}
  for(card in cards)RecordEntries.fromCard(card,role)?.let{return it}
  // Independently confirmed official catalog, not a guessed captcha URL or API JSON page.
  return RecordEntries.catalog(role)
 }
 /** Explicit profile completion only; failures never block notes/login or change device context. */
 fun profile(account:Account,scope:String,missingOnly:Boolean=false,valid:()->Boolean={true},budgetMs:Long=50000):JSONObject {
  val secret=account.secret?.copyJson()?:throw PublicError("AUTH_EXPIRED")
  if(verifiedCookies(secret.getJSONObject("cookies"))!=account.id)throw PublicError("AUTH_EXPIRED")
  val result=obj("roles" to JSONObject(),"userFailed" to false,"hadFp" to (DeviceFingerprint.formal(secret)!=null))
  val deadline=System.nanoTime()+budgetMs.coerceIn(50000,125000)*1_000_000L
  if(!missingOnly||account.metadata.optString("label") in setOf("","未获取昵称","米游社账号")||SafeImages.url(account.metadata.optString("avatar"))==null)try{
   if(!valid())throw PublicError("CANCELLED")
   val h=headers(secret,record=true).toMutableMap();h["Referer"]="https://www.miyoushe.com/";h["DS"]=RecordBridge.ds1()
   val user=data(transport.execute("GET","https://bbs-api.miyoushe.com/user/wapi/getUserFullInfo",h,scope=scope)).getJSONObject("user_info")
   if(!valid())throw PublicError("CANCELLED")
   if(user.optString("uid")!=account.id)throw PublicError("AUTH_EXPIRED")
   val image=SafeImages.passport(user)
   diagnostics.avatar("avatar_source_resolved",account.id,result=if(image.present)"valid" else "missing",resolver=image.kind,present=image.present)
   diagnostics.avatar("avatar_url_validated",account.id,result=if(image.url!=null)"valid" else "invalid_url",reason=image.reason,resolver=image.kind,present=image.present)
   result.put("user",obj("label" to user.optString("nickname").take(100),"avatar" to image.url.orEmpty(),"avatarState" to if(image.url!=null)"loaded" else if(!image.present)"not_provided" else "unavailable"))
  }catch(e:Exception){if(e is PublicError&&e.code=="CANCELLED")throw e;diagnostics.avatar("avatar_source_resolved",account.id,result="source_failed",http=(e as? PublicError)?.httpStatus,retcode=(e as? PublicError)?.retcode);result.put("userFailed",true)}
  for(saved in account.metadata.getJSONArray("roles").objects()){
   if(System.nanoTime()+35_000_000_000L>deadline){result.put("incomplete",true);break}
   if(!valid()||Thread.currentThread().isInterrupted)throw PublicError("CANCELLED")
   val role=Role.fromSaved(saved)
   if(missingOnly&&DeviceFingerprint.formal(secret)==null)continue
   if(missingOnly&&SafeImages.url(account.metadata.optJSONObject("roleProfiles")?.optJSONObject(role.key())?.optString("avatar").orEmpty())!=null&&(saved.optString("nickname").isNotBlank()||!account.metadata.optJSONObject("roleProfiles")?.optJSONObject(role.key())?.optString("nickname").isNullOrBlank()))continue
   try{
    val path=when(role.game){Game.GENSHIN->"/game_record/app/genshin/api/index";Game.STARRAIL->"/game_record/app/hkrpg/api/index";Game.ZENLESS->"/event/game_record_zzz/api/zzz/index"}
    val q=(if(role.game==Game.GENSHIN)"avatar_list_type=1&" else "")+"server="+role.server+"&role_id="+role.uid
    val h=NotesAdapter.headers(headers(secret,q,record=true),role.game)
    val final=if(role.game==Game.ZENLESS)ZzzH5Headers.apply(h,secret,role) else h
    val value=data(transport.execute("GET","https://api-takumi-record.mihoyo.com"+path+"?"+q,final,scope=scope))
    if(!valid())throw PublicError("CANCELLED")
    val inner=value.optJSONObject("role")
    if(!inner?.optString("uid").isNullOrBlank()&&inner!!.optString("uid")!=role.uid)throw PublicError("RESPONSE_INVALID")
    val candidate=listOf(value.optString("cur_head_icon_url"),inner?.optString("AvatarUrl").orEmpty(),inner?.optString("game_head_icon").orEmpty()).firstOrNull{it.isNotBlank()}
    val avatar=SafeImages.url(candidate.orEmpty())
    diagnostics.avatar("avatar_source_resolved",account.id,role.key(),"game_role",if(candidate==null)"missing" else "valid")
    diagnostics.avatar("avatar_url_validated",account.id,role.key(),"game_role",if(avatar==null)"invalid_url" else "valid",reason=SafeImages.reason(candidate.orEmpty()),resolver="record_index",present=candidate!=null)
    result.getJSONObject("roles").put(role.key(),obj("avatar" to avatar.orEmpty(),"state" to if(avatar!=null)"loaded" else if(candidate==null)"not_provided" else "unavailable","nickname" to inner?.optString("nickname").orEmpty().take(100)))
   }catch(e:Exception){if(e is PublicError&&e.code=="CANCELLED")throw e;result.put("incomplete",true);diagnostics.avatar("avatar_source_resolved",account.id,role.key(),"game_role","source_failed",http=(e as? PublicError)?.httpStatus,retcode=(e as? PublicError)?.retcode)}
  }
  return result
 }
 fun entry(secret:JSONObject,accountId:String,role:Role,scope:String)=recordEntry(secret,accountId,role,scope).url
}
