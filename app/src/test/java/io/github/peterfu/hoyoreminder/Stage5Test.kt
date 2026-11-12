package io.github.peterfu.hoyoreminder
import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject
import org.json.JSONArray

class Stage5Test {
 private val gs=Role(Game.GENSHIN,"100000001","cn_gf01","fixture","天空岛",60)
 private fun secret(id:String)=obj("deviceId" to "own-device-"+id,"cookies" to JSONObject().apply{ApiClient.AUTH_NAMES.forEach{put(it,if(it in setOf("account_id_v2","ltuid_v2"))id else "fixture")}})
 private fun saved(id:String,role:Role=gs)=Account(id,obj("roles" to JSONArray(listOf(role.json())),"games" to JSONArray(),"auth" to "authenticated"),secret(id))
 private fun waitDone(repo:Repository):JSONObject {
  repeat(250){val p=repo.dispatch("/api/accounts/refresh/status","GET",JSONObject());if(p.getJSONObject("refresh").getString("status")!="running")return p;Thread.sleep(10)}
  throw AssertionError("Fixture deadline")
 }
 private fun port(available:Boolean)=object:VerificationPort {
  override fun available(game:Game)=available
  override fun open(request:VerificationRequest,done:(VerifiedNote?,String)->Boolean){throw AssertionError("Unavailable page must not open")}
  override fun close(scope:String){};override fun focus(){}
 }
 @Test fun htmlAndMalformedEnvelopeCannotBeNotes(){
  for((body,type) in listOf("<html>战绩工具</html>" to "text/html","{}" to "application/json","{\"retcode\":0.5}" to "application/json","{\"retcode\":2147483648}" to "application/json","{\"retcode\":\"0\"}" to "application/json")){
   try{parseEnvelope(body,type);fail()}catch(e:PublicError){assertEquals("RESPONSE_INVALID",e.code)}
  }
  assertEquals(5003,parseEnvelope("{\"retcode\":5003}", "application/json; charset=utf-8").getInt("retcode"))
 }
 @Test fun everyGameRequestUsesItsBoundUidServerAndOwnIdentity(){
  val requests=mutableListOf<Pair<String,Map<String,String>>>()
  val client=ApiClient(object:Transport{override fun execute(method:String,url:String,headers:Map<String,String>,body:JSONObject?,scope:String):WireResponse{requests.add(url to headers);return WireResponse(obj("retcode" to 5003))}})
  for(role in listOf(gs,gs.copy(game=Game.STARRAIL,server="prod_gf_cn"),gs.copy(game=Game.ZENLESS,uid="10000001",server="prod_gf_cn"))){
   client.note(secret("111"),role,"test")
   val (url,h)=requests.last();assertEquals(Protocol.noteUrl(role),url);assertTrue(h["Cookie"]!!.contains("account_id_v2=111"));assertEquals("own-device-111",h["x-rpc-device_id"]);assertFalse(h.containsKey("x-rpc-device_fp"));assertEquals(Protocol.USER_AGENT,h["User-Agent"]);if(role.game==Game.ZENLESS)assertNull(h["DS"]) else assertNotNull(h["DS"])
  }
 }
 @Test fun noUnacceptedExternalEntryIsAdvertisedAsAvailable(){
  Game.entries.forEach{assertFalse(VerificationPolicy.hasValidatedEntry(it))}
  assertEquals("promotion",VerificationPolicy.pageClass("扫码下载 米游社"))
  assertEquals("promotion",VerificationPolicy.pageClass("原神战绩轻松查"))
  assertEquals("external_app",VerificationPolicy.pageClass("",true))
  assertEquals("external_app",VerificationPolicy.pageClass("打开App"))
  assertEquals("unknown",VerificationPolicy.pageClass("加载完成"))
 }
 @Test fun diagnosticsNeverContainSecretsUidOrRawUrls(){
  val d=Diagnostics();repeat(2010){d.event("notes_api_started",Game.GENSHIN,Protocol.noteUrl(gs)+"&token=secret",classification="secret",fpPresent=false)}
  val s=d.snapshot().toString();assertEquals(2000,d.snapshot().getJSONArray("events").length());assertFalse(s.contains(gs.uid));assertFalse(s.contains("secret"));assertFalse(s.contains("role_id"));assertFalse(s.contains("Cookie"))
 }
 @Test fun threeRiskCodesTerminateWithoutOpeningLandingPages(){
  val store=MemoryStore();store.accounts["111"]=saved("111");store.accounts["222"]=saved("222",gs.copy(game=Game.STARRAIL,server="prod_gf_cn"));store.accounts["333"]=saved("333",gs.copy(game=Game.ZENLESS,uid="10000001",server="prod_gf_cn"))
  val wire=FixtureTransport().apply{codes["genshin"]=1034;codes["starrail"]=10035;codes["zenless"]=10035}
  val repo=Repository(store,ApiClient(wire),{"fixture"});repo.verifier=port(false)
  try{repo.startRefresh("","manual");val p=waitDone(repo);val rows=p.getJSONObject("refresh").getJSONArray("roles").objects();assertEquals(3,wire.notes);assertTrue(rows.all{it.getString("state")=="failed"&&it.getString("error")=="official_verification_unavailable"});assertEquals(setOf(1034,10035),rows.map{it.getInt("retcode")}.toSet())
   val r=repo.dispatch("/api/accounts/verify","POST",obj("accountId" to "111","game" to "genshin","roleUid" to gs.uid));assertFalse(r.getBoolean("opened"));assertEquals("official_verification_unavailable",r.getString("reason"))
  }finally{repo.close()}
 }
 @Test fun refreshCookieRotationAndFpAreCommittedOnlyToTheirOwner(){
  val store=MemoryStore();store.accounts["111"]=saved("111");store.accounts["222"]=saved("222")
  val base=FixtureTransport()
  val wire=object:Transport{override fun execute(method:String,url:String,headers:Map<String,String>,body:JSONObject?,scope:String):WireResponse{
   val r=base.execute(method,url,headers,body,scope)
   return if(headers["Cookie"]!!.contains("account_id_v2=111"))r.copy(headers=mapOf("Set-Cookie" to listOf("cookie_token_v2=rotated; Domain=.mihoyo.com; Path=/","DEVICEFP=0123456789abc; Domain=.mihoyo.com; Path=/"))) else r
  }}
  val repo=Repository(store,ApiClient(wire),{"fixture"})
  try{repo.startRefresh("","manual");assertTrue(waitDone(repo).getJSONObject("refresh").getJSONArray("roles").objects().all{it.getString("state")=="success"});assertEquals("rotated",store.accounts["111"]!!.secret!!.getJSONObject("cookies").getString("cookie_token_v2"));assertEquals("0123456789abc",store.accounts["111"]!!.secret!!.getString("deviceFp"));assertEquals("fixture",store.accounts["222"]!!.secret!!.getJSONObject("cookies").getString("cookie_token_v2"));assertFalse(store.accounts["222"]!!.secret!!.has("deviceFp"))
  }finally{repo.close()}
 }
 @Test fun unavailableVerificationPreservesLastSuccessfulSampleAcrossRestart(){
  var now=1800000000000L;val store=MemoryStore();store.accounts["111"]=saved("111");val wire=FixtureTransport();val repo=Repository(store,ApiClient(wire),{"fixture"},{now});repo.verifier=port(false)
  var updated=0L
  try{repo.startRefresh("","manual");waitDone(repo);updated=store.accounts["111"]!!.metadata.getJSONArray("games").getJSONObject(0).getLong("updated");wire.retcode=1034;now+=6000;repo.startRefresh("","manual");val r=waitDone(repo).getJSONArray("accounts").getJSONObject(0).getJSONArray("games").getJSONObject(0);assertEquals(updated,r.getLong("updated"));assertTrue(r.getBoolean("stale"));assertEquals("official_verification_unavailable",r.getString("lastError"))
  }finally{repo.close()}
  val restored=Repository(store,ApiClient(wire),{"fixture"});try{assertEquals(updated,restored.view().getJSONArray("accounts").getJSONObject(0).getJSONArray("games").getJSONObject(0).getLong("updated"))}finally{restored.close()}
 }
 @Test fun responseInvalidKeepsItsOwnErrorCategory(){
  val store=MemoryStore();store.accounts["111"]=saved("111")
  val repo=Repository(store,ApiClient(object:Transport{override fun execute(method:String,url:String,headers:Map<String,String>,body:JSONObject?,scope:String):WireResponse{throw PublicError("RESPONSE_INVALID")}}),{"fixture"})
  try{repo.startRefresh("","manual");assertEquals("response_invalid",waitDone(repo).getJSONObject("refresh").getJSONArray("roles").getJSONObject(0).getString("error"))}finally{repo.close()}
 }
 @Test fun fixtureLabelCannotBeCommittedAsAnOfficialVerificationResult(){
  val store=MemoryStore();store.accounts["111"]=saved("111");val wire=FixtureTransport().apply{retcode=1034}
  val repo=Repository(store,ApiClient(wire),{"fixture"})
  repo.verifier=object:VerificationPort {
   override fun open(request:VerificationRequest,done:(VerifiedNote?,String)->Boolean){
    val fake=NoteParser.parse(gs,obj("current_resin" to 100,"max_resin" to 200)).put("source","mock")
    assertFalse(done(VerifiedNote(fake,JSONObject()),"ok"))
   }
   override fun close(scope:String){};override fun focus(){}
  }
  try{repo.startRefresh("","manual");val r=waitDone(repo);assertEquals("response_invalid",r.getJSONObject("refresh").getJSONArray("roles").getJSONObject(0).getString("error"));assertFalse(store.accounts["111"]!!.metadata.getJSONArray("games").getJSONObject(0).has("updated"))}finally{repo.close()}
 }

}
