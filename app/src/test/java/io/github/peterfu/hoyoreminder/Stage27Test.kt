package io.github.peterfu.hoyoreminder
import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject
import org.json.JSONArray
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class Stage27Test {
 private val role=Role(Game.GENSHIN,"100000001","cn_gf01","fixture","fixture",60)
 private fun secret(id:String="111")=obj("deviceId" to "stable","profileKey" to "stable","cookies" to JSONObject().apply{ApiClient.AUTH_NAMES.forEach{put(it,if(it in setOf("account_id_v2","ltuid_v2"))id else "fixture")}})
 private fun account()=Account("111",obj("roles" to JSONArray(listOf(role.json())),"games" to JSONArray(listOf(obj("id" to "genshin","roleUid" to role.uid,"status" to "account_risk","retcode" to 5003)))),secret())
 private fun created()=WireResponse(obj("retcode" to 0,"data" to obj("gt" to "fixture","challenge" to "server-challenge")))
 private fun answer()=obj("geetest_challenge" to "solved","geetest_validate" to "validate","geetest_seccode" to "seccode")
 @Test fun recordFlowRequiresServerConfirmationBeforeOneOriginalNote(){
  var gets=0;var posts=0;var notes=0
  val native=FixtureTransport()
  val wire=object:Transport{override fun execute(method:String,url:String,headers:Map<String,String>,body:JSONObject?,scope:String):WireResponse {
   if(url.contains("createVerification")){gets++;assertEquals("GET",method);assertEquals("2",headers["x-rpc-challenge_game"]);assertEquals("https://api-takumi-record.mihoyo.com"+role.game.path,headers["x-rpc-challenge_path"]);return created()}
   if(url.contains("verifyVerification")){
    posts++;assertEquals("POST",method);assertNotNull(body)
    val parts=headers.getValue("DS").split(",")
    assertEquals(Protocol.ds2("",parts[0].toLong(),parts[1].toInt(),body!!.toString()),headers["DS"])
    assertEquals("solved",body.getString("geetest_challenge"))
    return WireResponse(obj("retcode" to 0,"data" to obj("challenge" to "server-approved")))
   }
   notes++;assertEquals("server-approved",headers["x-rpc-challenge"]);assertFalse(headers.containsKey("x-rpc-aigis"))
   return native.execute(method,url,headers,body,scope)
  }}
  val api=ApiClient(wire);val trace=api.diagnostics.trace("111",role.key(),"f".repeat(32))
  val c=api.createRecordChallenge(secret(),role,"scope",trace)!!
  val (session,h)=api.answerChallenge(secret(),role,c,answer(),"scope",trace)
  val note=api.note(session,role,"scope",officialHeaders=h)
  assertEquals("ok",NoteParser.parse(role,note.json.getJSONObject("data")).getString("status"))
  assertEquals(1,gets);assertEquals(1,posts);assertEquals(1,notes)
  assertFalse(api.diagnostics.jsonLines().contains("server-approved"))
 }
 @Test fun explicitManual5003CanDiscoverButNeverStartsAnAutomaticLoop(){
  val store=MemoryStore();store.accounts["111"]=account();var discovery=0;var opens=0
  val api=ApiClient(object:Transport{override fun execute(method:String,url:String,headers:Map<String,String>,body:JSONObject?,scope:String):WireResponse{assertTrue(url.contains("createVerification"));discovery++;return created()}})
  val repo=Repository(store,api,{"fixture"})
  repo.verifier=object:VerificationPort{
   override fun supportsRecordChallenges()=true
   override fun available(game:Game)=false
   override fun available(request:VerificationRequest)=request.challenge?.kind=="record"
   override fun open(request:VerificationRequest,done:(VerifiedNote?,String)->Boolean){opens++;assertEquals("record",request.challenge!!.kind);request.retryRetcode=5003;assertFalse(done(null,"account_risk"))}
   override fun close(scope:String){};override fun focus(){}
  }
  try{
   assertTrue(repo.dispatch("/api/accounts/verify","POST",obj("accountId" to "111","game" to "genshin","roleUid" to role.uid)).getBoolean("opened"))
   assertEquals(1,discovery);assertEquals(1,opens)
   assertEquals(5003,store.accounts["111"]!!.metadata.getJSONArray("games").getJSONObject(0).getInt("retcode"))
   assertTrue(repo.diagnosticRefreshId()!!.matches(Regex("[a-f0-9]{32}")))
   assertFalse(repo.view().toString().contains("server-challenge"))
  }finally{repo.close()}
 }
 @Test fun noChallengeDoesNotOpenEmptyWindowOrRetryNotes(){
  val store=MemoryStore();store.accounts["111"]=account();var requests=0;var opens=0
  val repo=Repository(store,ApiClient(object:Transport{override fun execute(method:String,url:String,headers:Map<String,String>,body:JSONObject?,scope:String):WireResponse{requests++;assertTrue(url.contains("createVerification"));return WireResponse(obj("retcode" to 30001,"data" to null))}}),{"fixture"})
  repo.verifier=object:VerificationPort{
   override fun supportsRecordChallenges()=true;override fun available(game:Game)=false
   override fun open(request:VerificationRequest,done:(VerifiedNote?,String)->Boolean){opens++}
   override fun close(scope:String){};override fun focus(){}
  }
  try{assertFalse(repo.dispatch("/api/accounts/verify","POST",obj("game" to "genshin","roleUid" to role.uid)).getBoolean("opened"));assertEquals(1,requests);assertEquals(0,opens)}finally{repo.close()}
 }
 @Test fun serverRejectedProofNeverBecomesANoteHeader(){
  var calls=0
  val api=ApiClient(object:Transport{override fun execute(method:String,url:String,headers:Map<String,String>,body:JSONObject?,scope:String):WireResponse{calls++;assertTrue(url.endsWith("verifyVerification"));return WireResponse(obj("retcode" to -3102))}})
  val c=NoteChallenge.fromRecord(created(),"111",role)!!
  try{api.answerChallenge(secret(),role,c,answer(),"scope",api.diagnostics.trace("111",role.key(),"f".repeat(32)));fail()}catch(e:PublicError){assertEquals("VERIFICATION_REJECTED",e.code);assertEquals(-3102,e.retcode)}
  assertEquals(1,calls)
 }
 @Test fun wrongAccountOrRoleCannotSubmitRecordProof(){
  val api=ApiClient(object:Transport{override fun execute(method:String,url:String,headers:Map<String,String>,body:JSONObject?,scope:String):WireResponse=throw AssertionError("must not send")})
  val c=NoteChallenge.fromRecord(created(),"111",role)!!
  for(pair in listOf(secret("222") to role,secret() to role.copy(uid="100000002"))){
   try{api.answerChallenge(pair.first,pair.second,c,answer(),"scope",api.diagnostics.trace("111",role.key(),"f".repeat(32)));fail()}catch(e:PublicError){assertEquals("RESPONSE_INVALID",e.code)}
  }
 }
 @Test fun cancellationDuringDiscoveryCannotOpenLateWindow(){
  val store=MemoryStore();store.accounts["111"]=account();val entered=CountDownLatch(1);val release=CountDownLatch(1);var opens=0
  val api=ApiClient(object:Transport{override fun execute(method:String,url:String,headers:Map<String,String>,body:JSONObject?,scope:String):WireResponse{entered.countDown();release.await(2,TimeUnit.SECONDS);return created()}})
  val repo=Repository(store,api,{"fixture"})
  repo.verifier=object:VerificationPort{
   override fun supportsRecordChallenges()=true;override fun available(request:VerificationRequest)=true
   override fun open(request:VerificationRequest,done:(VerifiedNote?,String)->Boolean){opens++}
   override fun close(scope:String){};override fun focus(){}
  }
  val worker=Thread{try{repo.dispatch("/api/accounts/verify","POST",obj("game" to "genshin","roleUid" to role.uid))}catch(e:PublicError){assertEquals("CANCELLED",e.code)}}
  try{worker.start();assertTrue(entered.await(2,TimeUnit.SECONDS));repo.dispatch("/api/accounts/verification/cancel","POST",JSONObject());release.countDown();worker.join(2000);assertFalse(worker.isAlive);assertEquals(0,opens)}finally{release.countDown();repo.close()}
 }
}
