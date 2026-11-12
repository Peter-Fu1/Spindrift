package io.github.peterfu.hoyoreminder
import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject
import org.json.JSONArray

class Stage26Test {
 private fun response(code:Int=1034,config:JSONObject=obj("gt" to "fixture","risk_type" to "slide"))=WireResponse(obj("retcode" to code),mapOf("x-rpc-aigis" to listOf(obj("session_id" to "private-session","mmt_type" to 1,"data" to config.toString()).toString())))
 @Test fun actualChallengeRequiredAndRestrictedCodesNeverUnlock(){
  assertNull(NoteChallenge.parse(WireResponse(obj("retcode" to 5003))));assertNull(NoteChallenge.parse(WireResponse(obj("retcode" to 10041))))
  assertNotNull(NoteChallenge.parse(response(10041)))
  assertNull(NoteChallenge.parse(WireResponse(obj("retcode" to 1034))))
  assertNull(NoteChallenge.parse(response(config=obj("gt" to "<script>"))))
  assertNotNull(NoteChallenge.parse(response()))
 }
 @Test fun proofBoundToServerCaptchaAndExpires(){
  val c=NoteChallenge.parse(response(),1000)!!
  val proof=obj("captcha_id" to "fixture","lot_number" to "lot","pass_token" to "pass","gen_time" to "time","captcha_output" to "output","arbitrary" to "SECRET")
  val h=c.proof(proof,1001);assertTrue(h.getValue("x-rpc-aigis").startsWith("private-session;"))
  val payload=String(java.util.Base64.getDecoder().decode(h.getValue("x-rpc-aigis").substringAfter(';')))
  assertFalse(payload.contains("SECRET"))
  assertFalse(c.fresh(181001));assertFalse(c.fresh(999))
  try{c.proof(proof,181001);fail()}catch(e:PublicError){assertEquals("CANCELLED",e.code)}
  proof.put("captcha_id","foreign");try{c.proof(proof,1001);fail()}catch(e:PublicError){assertEquals("RESPONSE_INVALID",e.code)}
 }
 @Test fun legacyProofUsesOnlyServerSession(){
  val c=NoteChallenge.parse(response(config=obj("gt" to "fixture","challenge" to "upstream")),1000)!!
  val h=c.proof(obj("geetest_challenge" to "answer","geetest_validate" to "validate","geetest_seccode" to "seccode","session_id" to "foreign"),1001)
  assertEquals("answer",h["x-rpc-challenge"]);assertTrue(h.getValue("x-rpc-aigis").startsWith("private-session;"))
 }
 @Test fun challengeRouteRetriesOriginalRoleOnceAndPersistsBeforeAck(){
  val store=MemoryStore()
  val role=Role(Game.GENSHIN,"100000001","cn_gf01","fixture","fixture",60)
  val secret=obj("deviceId" to "stable","profileKey" to "stable-profile","cookies" to JSONObject().apply{ApiClient.AUTH_NAMES.forEach{put(it,if(it in setOf("account_id_v2","ltuid_v2"))"111" else "fixture")}})
  store.accounts["111"]=Account("111",obj("roles" to JSONArray(listOf(role.json())),"games" to JSONArray()),secret)
  val normal=FixtureTransport();var calls=0;var opened=0
  val transport=object:Transport{
   override fun execute(method:String,url:String,headers:Map<String,String>,body:JSONObject?,scope:String):WireResponse{
    calls++;assertTrue(url.contains("role_id=100000001"));assertTrue(headers.getValue("Cookie").contains("account_id_v2=111"))
    return if(calls==1)response() else {assertTrue(headers.containsKey("x-rpc-aigis"));normal.execute(method,url,headers,body,scope)}
   }
  }
  val api=ApiClient(transport);val repo=Repository(store,api,{"fixture"})
  repo.verifier=object:VerificationPort{
   override fun available(game:Game)=false
   override fun available(request:VerificationRequest)=request.challenge!=null
   override fun open(request:VerificationRequest,done:(VerifiedNote?,String)->Boolean){
    opened++;assertEquals(role.uid,request.role.uid)
    val h=request.challenge!!.proof(obj("captcha_id" to "fixture","lot_number" to "lot","pass_token" to "pass","gen_time" to "time","captcha_output" to "output"))
    val reply=api.note(request.account.secret!!,request.role,request.scope,officialHeaders=h)
    assertTrue(done(VerifiedNote(NoteParser.parse(request.role,reply.json.getJSONObject("data")),JSONObject()),"ok"))
    assertEquals("ok",store.accounts["111"]!!.metadata.getJSONArray("games").getJSONObject(0).getString("status"))
   }
   override fun close(scope:String){};override fun focus(){}
  }
  try{
   repo.startRefresh("f".repeat(32),"manual")
   var complete=false
   repeat(300){if(repo.dispatch("/api/accounts/refresh/status","GET",JSONObject()).getJSONObject("refresh").getString("status")!="running")complete=true;if(!complete)Thread.sleep(10)}
   assertTrue(complete);assertEquals(2,calls);assertEquals(1,opened)
   assertFalse(repo.view().toString().contains("private-session"))
   assertFalse(api.diagnostics.jsonLines().contains("private-session"))
   assertEquals("stable",store.accounts["111"]!!.secret!!.getString("deviceId"))
  }finally{repo.close()}
 }
 @Test fun verificationRetryKeepsItsExactCode(){
  val store=MemoryStore()
  val role=Role(Game.GENSHIN,"100000001","cn_gf01","fixture","fixture",60)
  val secret=obj("deviceId" to "stable","cookies" to JSONObject().apply{ApiClient.AUTH_NAMES.forEach{put(it,if(it in setOf("account_id_v2","ltuid_v2"))"111" else "fixture")}})
  store.accounts["111"]=Account("111",obj("roles" to JSONArray(listOf(role.json())),"games" to JSONArray()),secret)
  val repo=Repository(store,ApiClient(FixtureTransport().apply{retcode=1034}),{"fixture"})
  repo.verifier=object:VerificationPort{
   override fun open(request:VerificationRequest,done:(VerifiedNote?,String)->Boolean){request.retryRetcode=10041;done(null,"account_restricted")}
   override fun close(scope:String){};override fun focus(){}
  }
  try{
   repo.startRefresh("e".repeat(32),"manual")
   var row:JSONObject?=null
   repeat(300){val job=repo.dispatch("/api/accounts/refresh/status","GET",JSONObject()).getJSONObject("refresh");if(job.getString("status")!="running")row=job.getJSONArray("roles").getJSONObject(0);if(row==null)Thread.sleep(10)}
   assertNotNull(row);assertEquals(10041,row!!.getInt("retcode"))
   assertEquals(10041,store.accounts["111"]!!.metadata.getJSONArray("games").getJSONObject(0).getInt("retcode"))
  }finally{repo.close()}
 }

}
