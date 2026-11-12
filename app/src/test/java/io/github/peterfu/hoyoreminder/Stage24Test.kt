package io.github.peterfu.hoyoreminder
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Test
import org.junit.Assert.*
class Stage24Test {
 private val roles=listOf(Role(Game.GENSHIN,"100000001","cn_gf01","fixture","fixture",60),Role(Game.STARRAIL,"100000001","prod_gf_cn","fixture","fixture",70),Role(Game.ZENLESS,"10000001","prod_gf_cn","fixture","fixture",60))
 private fun account()=Account("111",obj("roles" to JSONArray(roles.map{it.json()}),"games" to JSONArray()),obj("deviceId" to "private-device","cookies" to JSONObject().apply{ApiClient.AUTH_NAMES.forEach{put(it,if(it in setOf("ltuid_v2","account_id_v2"))"111" else "PRIVATE")}}))
 private fun waitFor(repo:Repository):JSONObject{repeat(250){val r=repo.dispatch("/api/accounts/refresh/status","GET",JSONObject()).getJSONObject("refresh");if(r.getString("status")!="running")return r;Thread.sleep(10)};throw AssertionError("deadline")}
 @Test fun accountRestrictionsAreNotOrdinaryCaptcha(){
  assertEquals("account_risk",NoteParser.status(5003))
  assertEquals("risk_control_required",NoteParser.status(10035))
  assertEquals("account_restricted",NoteParser.status(10041))
  assertEquals("account_restricted",VerificationResolution.reason(10041,"aigis",true))
  assertEquals("account_risk",VerificationResolution.reason(5003,"legacy",true))
 }
 @Test fun challengeInspectionIsBoundedAndNeverExportsTickets(){
  val d=Diagnostics()
  val malformed=obj("session_id" to "SECRET-TICKET","mmt_type" to 1,"data" to "PRIVATE")
  assertEquals("malformed",VerificationContext.inspect(WireResponse(obj("retcode" to 10035),mapOf("X-Rpc-Aigis" to listOf(malformed.toString())))).kind)
  val aigis=obj("session_id" to "SECRET-TICKET","mmt_type" to 1,"data" to obj("gt" to "PRIVATE","challenge" to "PRIVATE").toString())
  val context=VerificationContext.inspect(WireResponse(obj("retcode" to 10035),mapOf("X-Rpc-Aigis" to listOf(aigis.toString()))))
  assertEquals("aigis",context.kind)
  assertTrue(context.aigisParseValid)
  assertEquals("challenge_unsupported",VerificationResolution.reason(10035,context.kind,false))
  d.event("verification_context_checked",Game.STARRAIL,retcode=10035,challengeKind=context.kind,authFieldsPresent=6)
  assertFalse(d.jsonLines().contains("SECRET"));assertFalse(d.jsonLines().contains("PRIVATE"))
  assertEquals("malformed",VerificationContext.inspect(WireResponse(JSONObject(),mapOf("x-rpc-aigis" to listOf("{")))).kind)
  assertEquals("none",VerificationContext.inspect(WireResponse(JSONObject())).kind)
  assertEquals("legacy",VerificationContext.inspect(WireResponse(obj("data" to obj("gt" to "PRIVATE","challenge" to "PRIVATE")))).kind)
 }
 @Test fun nativeThreeErrorsRemainDistinctAndNoNavigationIsFabricated(){
  val store=MemoryStore();store.accounts["111"]=account();val d=Diagnostics()
  val wire=object:Transport{override fun execute(method:String,url:String,headers:Map<String,String>,body:JSONObject?,scope:String)=WireResponse(obj("retcode" to when{url.contains("genshin")->5003;url.contains("hkrpg")->10035;else->10041}))}
  val repo=Repository(store,ApiClient(wire,d),{"fixture"})
  try{
   repo.startRefresh("a".repeat(32),"manual");val rows=waitFor(repo).getJSONArray("roles").objects()
   assertEquals(listOf(5003,10035,10041),rows.map{it.getInt("retcode")})
   assertEquals("account_restricted",rows[2].getString("error"))
   assertEquals("challenge_missing",rows[1].getString("verificationReason"))
   val events=d.snapshot().getJSONArray("events").objects()
   assertEquals(3,events.count{it.getString("event")=="verification_context_checked"})
   assertFalse(events.any{it.getString("event") in setOf("verification_page_opened","verification_completed","notes_committed")})
   // Manual Genshin must use its own 5003, never the most recent ZZZ 10041.
   repo.dispatch("/api/accounts/verify","POST",obj("accountId" to "111","game" to "genshin","roleUid" to roles[0].uid))
   val e=d.snapshot().getJSONArray("events").objects().last()
   assertEquals(5003,e.getInt("retcode"));assertEquals("account_risk",e.getString("classification"))
  }finally{repo.close()}
 }
 @Test fun resolutionSeparatesConfigurationFromMissingOrUnsupportedContext(){
  assertEquals("client_entry_unconfigured",VerificationResolution.reason(null,"none",false))
  assertEquals("challenge_missing",VerificationResolution.reason(10035,"none",false))
  assertEquals("challenge_invalid",VerificationResolution.reason(10035,"malformed",false))
  assertEquals("configured_entry",VerificationResolution.reason(10035,"aigis",true))
  assertFalse(VerificationPolicy.hasValidatedEntry(Game.STARRAIL))
  assertEquals("promotion",VerificationPolicy.pageClass("扫码下载米游社"))
 }

 @Test fun verificationPortFailureStatesTerminateWithoutCommit(){
  for(error in listOf("official_verification_unavailable","page_navigation_failed","cancelled","verification_timeout")){
   val store=MemoryStore();val acc=account();acc.metadata.put("roles",JSONArray(listOf(roles[0].json())));store.accounts["111"]=acc
   val d=Diagnostics();val repo=Repository(store,ApiClient(FixtureTransport().apply{retcode=10035},d),{"fixture"})
   var opens=0
   repo.verifier=object:VerificationPort{
    override fun open(request:VerificationRequest,done:(VerifiedNote?,String)->Boolean){opens++;assertFalse(done(null,error))}
    override fun close(scope:String){};override fun focus(){}
   }
   try{
    repo.startRefresh("b".repeat(32),"manual");val row=waitFor(repo).getJSONArray("roles").getJSONObject(0)
    assertEquals(1,opens);assertEquals(error,row.getString("error"))
    assertEquals(if(error=="cancelled")"cancelled" else "failed",row.getString("state"))
    assertFalse(d.snapshot().getJSONArray("events").objects().any{it.getString("event")=="notes_committed"})
   }finally{repo.close()}
  }
 }
 @Test fun verifiedNativeRetryIsPersistedBeforeAcceptanceAndRestores(){
  val store=MemoryStore();val acc=account();acc.metadata.put("roles",JSONArray(listOf(roles[0].json())));store.accounts["111"]=acc
  val repo=Repository(store,ApiClient(FixtureTransport().apply{retcode=10035}),{"fixture"})
  repo.verifier=object:VerificationPort {
   override fun open(request:VerificationRequest,done:(VerifiedNote?,String)->Boolean){
    // Port fixture only: a subsequent valid note response, never a real captcha success.
    val wire=ApiClient(FixtureTransport());val response=wire.note(request.account.secret!!,request.role,request.scope)
    val note=NoteParser.parse(request.role,response.json.getJSONObject("data")).put("source","miyoushe_webview")
    assertTrue(done(VerifiedNote(note,JSONObject()),"ok"))
    assertEquals("ok",store.accounts["111"]!!.metadata.getJSONArray("games").getJSONObject(0).getString("status"))
   }
   override fun close(scope:String){};override fun focus(){}
  }
  try{repo.startRefresh("c".repeat(32),"manual");assertEquals("success",waitFor(repo).getJSONArray("roles").getJSONObject(0).getString("state"))}finally{repo.close()}
  val restored=Repository(store,ApiClient(FixtureTransport()),{"fixture"})
  try{assertEquals("ok",restored.view().getJSONArray("accounts").getJSONObject(0).getJSONArray("games").getJSONObject(0).getString("status"))}finally{restored.close()}
 }
}
