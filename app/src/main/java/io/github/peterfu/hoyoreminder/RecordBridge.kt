package io.github.peterfu.hoyoreminder

import org.json.JSONObject
import java.net.URLDecoder

/** Pure, role-bound implementation of the official method/payload/callback protocol. No fabricated SSO/proofs. */
class RecordBridge(private val api:ApiClient,private val account:Account,private val role:Role,private val session:()->JSONObject){
 data class Result(val response:JSONObject,val status:String="supported",val navigate:String?=null,val close:Boolean=false)
 companion object {
  val methods=setOf("getCookieInfo","getCookieToken","getHTTPRequestHeaders","getUserInfo","getDS","getDS2","getCurrentLocale","getStatusBarHeight","pushPage","closePage","showLoading","hideLoading","eventTrack","configure_share","isFloatingWindow","login","getActionTicket")
  fun impact(method:String)=when(method){
   "getActionTicket","login","getCookieToken","getCookieInfo"->"authorization_only"
   "eventTrack","configure_share","showLoading","hideLoading"->"optional_telemetry"
   "getHTTPRequestHeaders"->"required_for_current_note"
   else->"unverified_requirement"
  }
  fun callback(value:Any?):String?=if(value==null||value==JSONObject.NULL)"" else (value as? String)?.takeIf{it.length<=160&&it.none{c->c<' '}}
  fun ds1(timestamp:Long=System.currentTimeMillis()/1000,nonce:String=(1..6).map{"abcdefghijklmnopqrstuvwxyz0123456789"[Protocol.random.nextInt(36)]}.joinToString("")):String =
   "$timestamp,$nonce,"+Protocol.md5("salt=yBh10ikxtLPoIhgwgPZSv5dmfaOTSJ6a&t=$timestamp&r=$nonce")
  private fun scalar(value:Any?):String=when(value){null,JSONObject.NULL->"";is String,is Number,is Boolean->value.toString();else->throw PublicError("RESPONSE_INVALID")}
  fun query(value:Any?):String=when(value){
   null,JSONObject.NULL->""
   is JSONObject->value.keys().asSequence().sorted().joinToString("&"){it+"="+scalar(value.opt(it))}
   is String->value.removePrefix("?").split('&').filter{it.isNotEmpty()}.map{it.split('=',limit=2)}.sortedBy{it[0]}.joinToString("&"){URLDecoder.decode(it[0],"UTF-8")+"="+URLDecoder.decode(it.getOrElse(1){""},"UTF-8")}
   else->throw PublicError("RESPONSE_INVALID")
  }
 }
 fun handle(method:String,payload:JSONObject,current:String):Result {
  if(!RecordEntries.allowed(current,role))throw PublicError("RECORD_ENTRY_INVALID")
  val secret=session();val cookies=secret.getJSONObject("cookies")
  if(ApiClient.verifiedCookies(cookies)!=account.id)throw PublicError("AUTH_EXPIRED")
  fun ok(data:JSONObject=JSONObject())=Result(obj("retcode" to 0,"message" to "","data" to data))
  fun no(code:String)=Result(obj("retcode" to -1,"message" to code,"data" to JSONObject.NULL),"unsupported")
  return when(method){
   "getCookieInfo"->ok(DeviceFingerprint.cookies(secret).put("ltuid",account.id).put("ltoken",cookies.optString("ltoken_v2",cookies.optString("ltoken"))).put("login_ticket",""))
   "getCookieToken"->if(payload.optBoolean("forceRefresh"))no("AUTH_REFRESH_REQUIRED") else ok(obj("cookie_token" to cookies.getString("cookie_token_v2")))
   "getUserInfo"->ok(obj("id" to account.id,"nickname" to account.metadata.optString("label"),"avatar_url" to account.metadata.optString("avatar"),"gender" to "","introduce" to ""))
   "getCurrentLocale"->ok(obj("language" to "zh-cn","timeZone" to "GMT+8"))
   "getStatusBarHeight"->ok(obj("statusBarHeight" to 0))
   "isFloatingWindow"->ok(obj("isFloatingWindow" to false))
   "getDS"->ok(obj("DS" to ds1()))
   "getDS2"->{
    val body=payload.opt("body").let{if(it==null||it==JSONObject.NULL)"" else if(it is String)it else if(it is JSONObject)it.toString() else throw PublicError("RESPONSE_INVALID")}
    val q=query(payload.opt("query"))
    val pairs=q.split('&').map{it.split('=',limit=2)}.filter{it.size==2}.associate{it[0] to it[1]}
    pairs["role_id"]?.let{if(it!=role.uid)throw PublicError("RESPONSE_INVALID")}
    pairs["server"]?.let{if(it!=role.server)throw PublicError("RESPONSE_INVALID")}
    ok(obj("DS" to Protocol.ds2(q,body=body)))
   }
   "getHTTPRequestHeaders"->{
    val values=api.headers(secret,record=true).filterKeys{it!="Cookie"&&it!="Referer"}
    val h=JSONObject(values);val fields=secret.optJSONObject("deviceFpContext")?.optJSONObject("extFields")
    h.put("x-rpc-device_name",fields?.optString("model")?:"").put("x-rpc-device_model",fields?.optString("model")?:"")
     .put("x-rpc-sys_version",fields?.optString("osVersion")?:"").put("x-rpc-channel","miyousheluodi")
    ok(h)
   }
   "pushPage"->RecordEntries.target(payload.optString("page"),current,role)?.let{Result(obj("retcode" to 0,"data" to JSONObject()),navigate=it)}
    ?:Result(obj("retcode" to -1,"message" to "RECORD_ENTRY_INVALID"),"rejected")
   "closePage"->Result(obj("retcode" to 0,"data" to JSONObject()),close=true)
   "eventTrack","configure_share","showLoading","hideLoading"->ok()
   else->no("BRIDGE_METHOD_UNSUPPORTED")
  }
 }
}
