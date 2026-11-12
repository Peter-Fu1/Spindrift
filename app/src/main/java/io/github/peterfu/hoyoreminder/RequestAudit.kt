package io.github.peterfu.hoyoreminder

import org.json.JSONObject
import java.net.URI

/** Exact public-script-derived categories only. Unknown URLs are never exported or forwarded. */
object RequestEndpoints {
 val kinds=setOf("record_card","native_note","h5_note","account_context","challenge_bootstrap","challenge_submit","authorization","unknown")
 val known=mapOf(
  "api-takumi-record.mihoyo.com/game_record/app/card/wapi/getGameRecordCard" to "record_card",
  "api-takumi-record.mihoyo.com/game_record/app/card/wapi/createVerification" to "challenge_bootstrap",
  "api-takumi-record.mihoyo.com/game_record/app/card/wapi/verifyVerification" to "challenge_submit",
  "api-takumi.mihoyo.com/event/toolcomsrv/risk/createGeetest" to "challenge_bootstrap",
  "api-takumi.mihoyo.com/event/toolcomsrv/risk/verifyGeetest" to "challenge_submit",
  "api-takumi.mihoyo.com/binding/api/getUserGameRolesByCookie" to "account_context",
  "api-takumi.mihoyo.com/binding/api/getUserGameRolesByCookieToken" to "account_context",
  "bbs-api.miyoushe.com/binding/api/getUserGameRolesByCookie" to "account_context",
  "bbs-api.miyoushe.com/community/user/wapi/getUserFullInfo" to "account_context",
  "api-takumi.mihoyo.com/community/user/wapi/getUserFullInfo" to "account_context",
  "api-takumi.mihoyo.com/account/binding/api/getAllRegions" to "account_context",
  "webapi.account.mihoyo.com/Api/cookie_accountinfo_by_authkey" to "authorization"
 )
 fun kind(url:String,h5:Boolean=false):String {
  val u=runCatching{URI(url)}.getOrNull()?:return "unknown"
  if(u.scheme!="https"||u.userInfo!=null||u.port !in listOf(-1,443))return "unknown"
  if(u.host=="api-takumi-record.mihoyo.com"&&Game.entries.any{it.path==u.path})return if(h5)"h5_note" else "native_note"
  return known[u.host+u.path]?:"unknown"
 }
 fun header(headers:Map<String,String>,name:String)=headers.entries.firstOrNull{it.key.equals(name,true)}?.value
 // Safe NAME bitset only: FP, cookie, device, UA, challenge, aigis, page, geetest metadata.
 fun mask(h:Map<String,String>)=listOf("x-rpc-device_fp","Cookie","x-rpc-device_id","User-Agent","x-rpc-challenge","x-rpc-aigis","x-rpc-page","x-rpc-geetest_ext").mapIndexed{i,k->if(header(h,k)!=null)1 shl i else 0}.sum()
}

/** Ephemeral owner expectation; no identity values are retained in the diagnostic ring. */
class RequestAudit(private val diagnostics:Diagnostics,private val secret:JSONObject,private val role:Role,
 val trace:DiagnosticTrace,private val url:String,private val source:String){
 val enabled get()=diagnostics.detailed
 fun send(headers:Map<String,String>?,boundary:Boolean){
  if(!diagnostics.detailed)return
  val kind=RequestEndpoints.kind(url,source=="webview")
  val fp=DeviceFingerprint.formal(secret)
  fun h(name:String)=headers?.let{RequestEndpoints.header(it,name)}
  val jar=headers?.let{JSONObject().apply{
   h("Cookie").orEmpty().split(';').map{it.trim().split('=',limit=2)}.filter{it.size==2&&it[0] in ApiClient.COOKIE_NAMES}.forEach{put(it[0],it[1])}
  }}
  val credentials=jar?.let{v->ApiClient.AUTH_NAMES.all{v.optString(it).isNotBlank()&&v.optString(it)==secret.getJSONObject("cookies").optString(it)}}
  diagnostics.event(if(kind=="record_card")"record_card_request_identity" else "outbound_request_identity",
   role.game,trace=trace,source=source,stage="request",endpointKind=kind,
   requestStage=if(boundary)"application_send" else "transport_delegate",
   observation=if(boundary)"application_boundary" else "not_observable",
   fpPresent=headers?.let{!h("x-rpc-device_fp").isNullOrBlank()},
   fpMatchesAccount=headers?.let{fp!=null&&h("x-rpc-device_fp")==fp},
   fpCookiePresent=jar?.let{it.optString("DEVICEFP").isNotBlank()},
   cookieScopeMatch=jar?.let{fp!=null&&it.optString("DEVICEFP")==fp},
   deviceMatches=headers?.let{h("x-rpc-device_id")==secret.optString("deviceId")&&h("x-rpc-device_id")?.isNotBlank()==true},
   credentialMatches=credentials,uaContractMatch=headers?.let{h("User-Agent")==Protocol.USER_AGENT},
   proofPresent=headers?.let{!h("x-rpc-challenge").isNullOrBlank()||!h("x-rpc-aigis").isNullOrBlank()},
   headerMask=headers?.let{RequestEndpoints.mask(it)})
 }
 fun failure(error:PublicError){
  if(diagnostics.detailed)diagnostics.event(if(RequestEndpoints.kind(url)=="record_card")"record_card_response_result" else "outbound_response_result",
   role.game,trace=trace,source=source,stage="response",endpointKind=RequestEndpoints.kind(url,source=="webview"),
   retcode=error.retcode,httpStatus=error.httpStatus,exceptionCategory=publicExceptionCategory(error))
 }
 fun result(response:WireResponse){
  if(diagnostics.detailed)diagnostics.event(if(RequestEndpoints.kind(url)=="record_card")"record_card_response_result" else "outbound_response_result",
   role.game,trace=trace,source=source,stage="response",endpointKind=RequestEndpoints.kind(url,source=="webview"),
   retcode=response.json.optInt("retcode"),httpStatus=response.httpStatus)
 }
}
