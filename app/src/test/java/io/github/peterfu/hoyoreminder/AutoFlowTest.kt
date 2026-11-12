package io.github.peterfu.hoyoreminder
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Test
import org.junit.Assert.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class AutoFlowTest {
 private fun awaitDone(repo:Repository):JSONObject{
  repeat(400){val p=repo.dispatch("/api/accounts/refresh/status","GET",JSONObject());if(p.getJSONObject("refresh").getString("status")!="running")return p;Thread.sleep(5)}
  throw AssertionError("fixture deadline")
 }
 private class LoginWire:Transport {
  val original=FixtureTransport();var fpCalls=0;var noteCalls=0;var reject=false
  var entered:CountDownLatch?=null;var release:CountDownLatch?=null
  override fun execute(method:String,url:String,headers:Map<String,String>,body:JSONObject?,scope:String):WireResponse{
   if(url==DeviceFingerprint.ENDPOINT){
    fpCalls++;assertFalse(headers.containsKey("Cookie"));entered?.countDown();release?.await(3,TimeUnit.SECONDS)
    return WireResponse(obj("retcode" to 0,"data" to obj("code" to if(reject)403 else 200,"device_fp" to "abcdef1234567")))
   }
   if(url==Protocol.noteUrl(Role(Game.GENSHIN,"100000001","cn_gf01","","",60))){
    noteCalls++;assertEquals("abcdef1234567",headers["x-rpc-device_fp"])
    assertTrue(headers["Cookie"]!!.contains("DEVICEFP=abcdef1234567"))
   }
   return original.execute(method,url,headers,body,scope)
  }
 }
 private fun login(repo:Repository):JSONObject{
  val id=repo.dispatch("/api/auth/qr/start","POST",JSONObject()).getString("sessionId")
  return repo.dispatch("/api/auth/qr/"+id,"GET",JSONObject())
 }
 @Test fun firstLoginRequestsFormalFpBeforeItsOnlyInitialNoteAndQrPollingStaysIdempotent(){
  val store=MemoryStore();val wire=LoginWire();val repo=Repository(store,ApiClient(wire),{"fixture"})
  try{
   val id=repo.dispatch("/api/auth/qr/start","POST",JSONObject()).getString("sessionId")
   assertEquals("confirmed",repo.dispatch("/api/auth/qr/"+id,"GET",JSONObject()).getString("status"))
   awaitDone(repo)
   assertEquals(1,wire.fpCalls);assertEquals(1,wire.noteCalls)
   assertEquals("abcdef1234567",store.accounts["111"]!!.secret!!.getString("deviceFp"))
   repo.dispatch("/api/auth/qr/"+id,"GET",JSONObject());assertEquals(1,wire.fpCalls)
  }finally{repo.close()}
 }
 @Test fun existingFormalAndOtherAccountsAreNotRotatedByLogin(){
  val store=MemoryStore();store.accounts["111"]=fpAccount("111",false,"abcdef1234567");store.accounts["333"]=fpAccount("333",true,"3333333333333")
  val before=store.accounts["333"]!!.secret.toString();val wire=LoginWire();val repo=Repository(store,ApiClient(wire),{"fixture"})
  try{login(repo);assertEquals(0,wire.fpCalls);assertEquals(0,wire.noteCalls);assertEquals(before,store.accounts["333"]!!.secret.toString())}finally{repo.close()}
 }
 @Test fun fpRejectionKeepsLoginAndPendingWithoutRiskRequestsOrAutomaticRetries(){
  val store=MemoryStore();val wire=LoginWire().apply{reject=true};val repo=Repository(store,ApiClient(wire),{"fixture"})
  try{
   login(repo);val progress=awaitDone(repo)
   assertEquals(1,store.accounts.size);assertEquals(1,wire.fpCalls);assertEquals(0,wire.noteCalls)
   assertFalse(store.accounts["111"]!!.secret!!.has("deviceFp"));assertTrue(store.accounts["111"]!!.secret!!.has("deviceFpPending"))
   assertEquals("device_fp_unavailable",progress.getJSONObject("refresh").getJSONArray("roles").getJSONObject(0).getString("error"))
  }finally{repo.close()}
 }
 @Test fun cancelDuringAutomaticIssuanceCannotPersistLateFpOrRunNotes(){
  val store=MemoryStore();val wire=LoginWire().apply{entered=CountDownLatch(1);release=CountDownLatch(1)}
  val repo=Repository(store,ApiClient(wire),{"fixture"})
  try{
   val result=login(repo);assertTrue(wire.entered!!.await(2,TimeUnit.SECONDS))
   repo.dispatch("/api/accounts/refresh/action","POST",obj("refreshId" to result.getJSONObject("initialRefresh").getString("refreshId"),"action" to "cancel"))
   wire.release!!.countDown();Thread.sleep(50)
   assertFalse(store.accounts["111"]!!.secret!!.has("deviceFp"));assertEquals(0,wire.noteCalls)
  }finally{wire.release!!.countDown();repo.close()}
 }
 @Test fun loginWithNoRolesStillAcquiresItsOwnFp(){
  val wire=LoginWire().apply{original.bindings=JSONArray()};val store=MemoryStore();val repo=Repository(store,ApiClient(wire),{"fixture"})
  try{login(repo);awaitDone(repo);assertEquals(1,wire.fpCalls);assertEquals(0,wire.noteCalls);assertEquals("abcdef1234567",store.accounts["111"]!!.secret!!.getString("deviceFp"))}finally{repo.close()}
 }
 @Test fun identicalReloginWithoutFpBootstrapsButARepeatedQrPollDoesNot(){
  val store=MemoryStore();val acc=fpAccount("111");acc.secret!!.put("cookies",JSONObject().apply{ApiClient.AUTH_NAMES.forEach{put(it,if(it in setOf("account_id_v2","ltuid_v2"))"111" else "fixture")}});store.accounts["111"]=acc
  val wire=LoginWire();val repo=Repository(store,ApiClient(wire),{"fixture"})
  try{val r=login(repo);assertEquals("ALREADY_LOGGED_IN",r.getString("code"));assertEquals("scheduled",r.getJSONObject("initialRefresh").getString("status"));awaitDone(repo);assertEquals(1,wire.fpCalls)}finally{repo.close()}
 }
 @Test fun actualBrowserChallengeHeaderSurvivesOnlyItsExactRequestAndNeverLeaksToNativeRefresh(){
  val sent=mutableListOf<Map<String,String>>()
  val api=ApiClient(object:Transport{override fun execute(method:String,url:String,headers:Map<String,String>,body:JSONObject?,scope:String):WireResponse{sent.add(headers);return WireResponse(obj("retcode" to 10041))}})
  val a=fpAccount("111",true,"abcdef1234567");val b=fpAccount("222",false,"2222222222222")
  val role=Role(Game.ZENLESS,"10000001","prod_gf_cn","","",60)
  val cookie=ApiClient.cookieHeader(DeviceFingerprint.cookies(a.secret!!))
  api.observedNote(a.secret!!,role,Protocol.noteUrl(role),cookie,mapOf("X-Rpc-Challenge" to "fixture-server-token"),"fixture")
  assertEquals("fixture-server-token",sent.last()["x-rpc-challenge"])
  assertEquals("abcdef1234567",sent.last()["x-rpc-device_fp"]);assertEquals(Protocol.USER_AGENT,sent.last()["User-Agent"])
  api.note(a.secret!!,role,"fresh-native");assertFalse(sent.last().containsKey("x-rpc-challenge"))
  val count=sent.size
  for(headers in listOf(mapOf("x-rpc-challenge" to "bad\r\nvalue"),mapOf("x-rpc-challenge" to "a","X-Rpc-Challenge" to "b"))){
   try{api.observedNote(a.secret!!,role,Protocol.noteUrl(role),cookie,headers,"fixture");fail("unsafe proof")}catch(e:PublicError){assertEquals("RESPONSE_INVALID",e.code)}
  }
  try{api.observedNote(b.secret!!,role,Protocol.noteUrl(role),cookie,mapOf("x-rpc-challenge" to "fixture-server-token"),"fixture");fail("other owner")}catch(e:PublicError){assertEquals("AUTH_EXPIRED",e.code)}
  assertEquals(count,sent.size)
  assertTrue(api.diagnostics.snapshot().getJSONArray("events").objects().any{it.optBoolean("proof_present")})
  assertFalse(api.diagnostics.jsonLines().contains("fixture-server-token"))
 }
 @Test fun visibleChallengeOnlyTriggersOneCheckAndNeverProvesSuccess(){
  val r=RecordRecovery()
  assertFalse(r.transition(false,"overview",true))
  assertFalse(r.transition(true,"overview",true))
  assertFalse(r.transition(false,"error",true))
  assertTrue(r.transition(false,"overview",true));assertFalse(r.transition(false,"daily",true));assertFalse(r.claimCheck())
 }
 @Test fun manualRefreshWithFormalFpAutomaticallyBrowsesMissingChallengesForAllRiskCodes(){
  for(code in listOf(1034,10035,5003,10041)){
   val store=MemoryStore();store.accounts["111"]=fpAccount("111",false,"abcdef1234567")
   val wire=FixtureTransport().apply{retcode=code};val repo=Repository(store,ApiClient(wire),{"fixture"});var opens=0
   repo.verifier=object:VerificationPort{
    override fun available(request:VerificationRequest)=request.browse&&request.autoRecover
    override fun open(request:VerificationRequest,done:(VerifiedNote?,String)->Boolean){
     opens++;assertTrue(request.autoRecover);assertTrue(request.browse);assertNull(request.challenge)
     val role=request.role;val good=NoteParser.parse(role,obj("current_resin" to 10,"max_resin" to 200))
     assertTrue(done(VerifiedNote(good,DeviceFingerprint.cookies(request.account.secret!!)),"ok"))
    }
    override fun close(scope:String){};override fun focus(){}
   }
   try{
    repo.startRefresh("","manual");val p=awaitDone(repo)
    assertEquals(1,opens);assertEquals(1,wire.notes)
    val row=p.getJSONObject("refresh").getJSONArray("roles").getJSONObject(0)
    assertEquals("success",row.getString("state"));assertEquals(0,row.getInt("retcode"))
   }finally{repo.close()}
  }
 }
 @Test fun missingFpAnd5003DoNotAutomaticallyOpenBrowser(){
  val store=MemoryStore();store.accounts["111"]=fpAccount("111");val repo=Repository(store,ApiClient(FixtureTransport().apply{retcode=5003}),{"fixture"})
  var opens=0;repo.verifier=object:VerificationPort{override fun open(request:VerificationRequest,done:(VerifiedNote?,String)->Boolean){opens++};override fun close(scope:String){};override fun focus(){}}
  try{repo.startRefresh("","manual");awaitDone(repo);assertEquals(0,opens)}finally{repo.close()}
 }
 @Test fun cancellationPreservesTheOriginalRiskAndDiscardsLateSuccessfulResult(){
  val store=MemoryStore();store.accounts["111"]=fpAccount("111",false,"abcdef1234567")
  val repo=Repository(store,ApiClient(FixtureTransport().apply{retcode=10041}),{"fixture"})
  val opened=CountDownLatch(1);var callback:((VerifiedNote?,String)->Boolean)?=null;var request:VerificationRequest?=null
  repo.verifier=object:VerificationPort{override fun available(request:VerificationRequest)=true
   override fun open(r:VerificationRequest,done:(VerifiedNote?,String)->Boolean){request=r;callback=done;opened.countDown()}
   override fun close(scope:String){};override fun focus(){}
  }
  try{
   val p=repo.startRefresh("","manual");assertTrue(opened.await(2,TimeUnit.SECONDS))
   repo.dispatch("/api/accounts/refresh/action","POST",obj("refreshId" to p.getJSONObject("refresh").getString("refreshId"),"action" to "cancel"))
   assertFalse(callback!!.invoke(VerifiedNote(NoteParser.parse(request!!.role,obj("current_resin" to 100,"max_resin" to 200)),fpCookies("111")),"ok"))
   assertEquals("account_restricted",store.accounts["111"]!!.metadata.getJSONArray("games").getJSONObject(0).getString("status"))
  }finally{repo.close()}
 }
}
