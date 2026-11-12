package io.github.peterfu.hoyoreminder

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Test
import org.junit.Assert.*
import java.net.HttpURLConnection
import java.net.URL
import java.io.ByteArrayInputStream

/** Production connection writer, synthetic server. No actual accounts/network calls. */
class RequestDiagnosticsTest {
 private val role=Role(Game.ZENLESS,"10000001","prod_gf_cn","fixture","fixture",60)
 private open class Connection(val code:Int=0,val dropFp:Boolean=false):HttpURLConnection(URL("https://api-takumi-record.mihoyo.com/")){
  override fun connect(){}
  override fun usingProxy()=false
  override fun disconnect(){}
  override fun setRequestProperty(k:String,v:String){if(!dropFp||!k.equals("x-rpc-device_fp",true))super.setRequestProperty(k,v)}
  override fun getResponseCode()=200
  override fun getContentType()="application/json"
  override fun getInputStream()=ByteArrayInputStream(obj("retcode" to code,"data" to obj("list" to JSONArray())).toString().toByteArray())
 }
 private fun events(d:Diagnostics)=d.snapshot().getJSONArray("events").objects()
 @Test fun actualConnectionBoundaryDistinguishesDroppedFpFromPreservedCookieAndRepairFixture(){
  val a=fpAccount("111",true,"abcdef1234567");val c=fpAccount("333",true,"1234567abcdef")
  for(drop in listOf(true,false)){
   val d=Diagnostics();val api=ApiClient(HttpTransport{Connection(dropFp=drop)},d)
   api.recordEntry(a.secret!!,a.id,role,"a".repeat(32))
   val row=events(d).single{it.getString("event")=="record_card_request_identity"}
   assertEquals(!drop,row.getBoolean("fpPresent"));assertEquals(!drop,row.getBoolean("fp_matches_account"))
   assertTrue(row.getBoolean("fp_cookie_present"));assertTrue(row.getBoolean("cookie_scope_match"))
   assertTrue(row.getBoolean("credential_scope_match"));assertTrue(row.getBoolean("device_id_matches_account"))
   assertEquals("application_boundary",row.getString("fp_check_scope"))
   assertEquals("1234567abcdef",c.secret!!.getString("deviceFp"))
   assertEquals("catalog",api.recordEntry(a.secret!!,a.id,role,"b".repeat(32)).source)
  }
 }
 @Test fun sameEndpointTwoOwnersHaveCorrectIdentityButDifferentBusinessCodesWithoutTicketOrReset(){
  val a=fpAccount("111",true,"abcdef1234567");val c=fpAccount("333",true,"1234567abcdef")
  val d=Diagnostics();val beforeA=a.secret!!.toString();val beforeC=c.secret!!.toString()
  for((account,code) in listOf(a to 10041,c to 0)){
   val api=ApiClient(HttpTransport{Connection(code)},d)
   val trace=d.trace(account.id,role.key(),"c".repeat(32))
   val response=api.observedNote(account.secret!!,role,Protocol.noteUrl(role),ApiClient.cookieHeader(DeviceFingerprint.cookies(account.secret!!)),emptyMap(),"c".repeat(32),trace)
   assertEquals(code,response.json.getInt("retcode"))
   val bridge=RecordBridge(api,account,role){account.secret!!}
   assertEquals("unsupported",bridge.handle("getActionTicket",JSONObject(),RecordEntries.catalog(role).url).status)
  }
  val rows=events(d).filter{it.getString("event")=="outbound_request_identity"}
  assertEquals(2,rows.size);assertNotEquals(rows[0].getString("account_ref"),rows[1].getString("account_ref"))
  rows.forEach{assertTrue(it.getBoolean("fp_matches_account"));assertTrue(it.getBoolean("credential_scope_match"));assertFalse(it.getBoolean("proof_present"))}
  assertEquals(beforeA,a.secret!!.toString());assertEquals(beforeC,c.secret!!.toString())
  assertEquals(listOf(10041,0),events(d).filter{it.getString("event")=="outbound_response_result"}.map{it.getInt("retcode")})
 }
 @Test fun delegateTransportCannotClaimActualHeadersWereObserved(){
  val d=Diagnostics();val api=ApiClient(object:Transport{
   override fun execute(method:String,url:String,headers:Map<String,String>,body:JSONObject?,scope:String)=WireResponse(obj("retcode" to 0,"data" to JSONObject()))
  },d)
  api.note(fpAccount("111",true).secret!!,role,"fixture")
  val row=events(d).single{it.getString("event")=="outbound_request_identity"}
  assertTrue(row.isNull("fpPresent"));assertTrue(row.isNull("credential_scope_match"))
  assertEquals("not_observable",row.getString("fp_check_scope"))
 }
 @Test fun unreadableConnectionPropertiesDoNotAbortTheRequestOrProduceFalseIdentity(){
  val d=Diagnostics();val api=ApiClient(HttpTransport{object:Connection(){
   override fun getRequestProperties():Map<String,List<String>> = throw IllegalStateException("unobservable")
  }},d)
  assertEquals(0,api.note(fpAccount("111",true,"abcdef1234567").secret!!,role,"fixture").json.getInt("retcode"))
  val row=events(d).single{it.getString("event")=="outbound_request_identity"}
  assertTrue(row.isNull("fpPresent"));assertEquals("not_observable",row.getString("fp_check_scope"))
 }
 @Test fun classifierDoesNotExposeUnknownOrLookalikeAuthEndpoints(){
  assertEquals("challenge_submit",RequestEndpoints.kind("https://api-takumi.mihoyo.com/event/toolcomsrv/risk/verifyGeetest?secret=x"))
  assertEquals("authorization",RequestEndpoints.kind("https://webapi.account.mihoyo.com/Api/cookie_accountinfo_by_authkey"))
  for(url in listOf("http://api-takumi.mihoyo.com/event/toolcomsrv/risk/verifyGeetest","https://api-takumi.mihoyo.com.evil.invalid/event/toolcomsrv/risk/verifyGeetest","https://api-takumi.mihoyo.com/unknown","https://user@api-takumi.mihoyo.com/event/toolcomsrv/risk/verifyGeetest"))assertEquals("unknown",RequestEndpoints.kind(url))
 }
 @Test fun closedSchemaRejectsIdentityValuesAndRateLimitsNoisyBridgeCalls(){
  val d=Diagnostics()
  repeat(300){d.event("bridge_method_called",bridgeMethod="SECRET_CALLBACK_TOKEN",bridgeStatus="unsupported",endpointKind="secret-url",methodKind="secret",observation="secret",headerMask=999)}
  d.event("bridge_method_result",bridgeMethod="getHTTPRequestHeaders",bridgeStatus="supported",callbackDelivered=true,scopeMatch=true)
  d.event("notes_api_observed",fpPresent=null)
  val exported=d.jsonLines();assertFalse(exported.contains("SECRET"));assertFalse(exported.contains("secret-url"))
  assertTrue(d.snapshot().getInt("suppressedBridgeEvents")>0)
  val last=events(d).last();assertTrue(last.isNull("fpPresent"));assertEquals("not_applicable",last.getString("fp_check_scope"))
  assertTrue(events(d).any{it.optBoolean("callback_delivered")})
 }
 @Test fun detailedTransportDiagnosticsAreDisabledOutsideDebugUnlessExplicitlyEnabled(){
  val d=Diagnostics(detailed=false);val api=ApiClient(HttpTransport{Connection()},d)
  api.note(fpAccount("111",true).secret!!,role,"fixture")
  assertFalse(events(d).any{it.optString("event")=="outbound_request_identity"})
 }
}
