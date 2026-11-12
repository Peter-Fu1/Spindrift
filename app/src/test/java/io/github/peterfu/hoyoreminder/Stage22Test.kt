package io.github.peterfu.hoyoreminder
import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject
import org.json.JSONArray

/** Offline fixtures only; no official account or physical Android WebView is exercised. */
class Stage22Test {
 private val gs=Role(Game.GENSHIN,"100000001","cn_gf01","fixture","fixture-region",60)
 private fun secret(id:String)=obj("deviceId" to "original-device","profileKey" to "original-profile","deviceFp" to "0123456789abc","cookies" to JSONObject().apply{ApiClient.AUTH_NAMES.forEach{put(it,if(it in setOf("account_id_v2","ltuid_v2"))id else "fixture")}})
 private fun saved()=Account("111",obj("roles" to JSONArray(listOf(gs.json())),"games" to JSONArray(),"auth" to "authenticated"),secret("111"))
 private fun done(repo:Repository):JSONObject {repeat(250){val r=repo.dispatch("/api/accounts/refresh/status","GET",JSONObject());if(r.getJSONObject("refresh").getString("status")!="running")return r;Thread.sleep(10)};throw AssertionError("fixture deadline") }
 private fun unavailable()=object:VerificationPort{override fun available(game:Game)=false;override fun open(request:VerificationRequest,done:(VerifiedNote?,String)->Boolean){fail("not accepted route")};override fun close(scope:String){};override fun focus(){}}
 @Test fun loginRiskWithoutUsableRouteCompletesBeforeUiReadyAndKeepsOriginalCode(){
  val store=MemoryStore();store.accounts["111"]=saved();val d=Diagnostics()
  val repo=Repository(store,ApiClient(FixtureTransport().apply{retcode=10035},d),{"fixture"});repo.verifier=unavailable()
  try{
   repo.startRefresh("a".repeat(32),"login");val result=done(repo)
   val row=result.getJSONObject("refresh").getJSONArray("roles").getJSONObject(0)
   assertEquals("failed",row.getString("state"));assertEquals(10035,row.getInt("retcode"))
   val v=result.getJSONArray("accounts").getJSONObject(0).getJSONObject("verification")
   assertEquals(10035,v.getInt("retcode"));assertEquals("official_verification_unavailable",v.getString("state"))
   val events=d.snapshot().getJSONArray("events").objects()
   for(name in listOf("notes_request_started","notes_response_received","notes_business_result","verification_route_selected","verification_unavailable","refresh_completed"))assertTrue(name,events.any{it.getString("event")==name})
   val ids=events.filter{it.optString("refresh_id")=="a".repeat(32)&&it.getString("event") !in setOf("refresh_completed","device_fp_auto_checked")}.mapNotNull{it.optString("request_id").takeIf{v->v.isNotBlank()}}.toSet()
   assertTrue(events.single{it.getString("event")=="device_fp_auto_checked"}.getBoolean("fpPresent"))
   assertEquals(1,ids.size);assertFalse(d.snapshot().toString().contains(gs.uid));assertFalse(d.snapshot().toString().contains("original-device"))
  }finally{repo.close()}
 }
 @Test fun successfulNativeDataEmitsCommitOnlyAfterSaveAndUsesSameRequest(){
  val store=MemoryStore();store.accounts["111"]=saved();val d=Diagnostics();val repo=Repository(store,ApiClient(FixtureTransport(),d),{"fixture"})
  try{repo.startRefresh("b".repeat(32),"manual");done(repo);val events=d.snapshot().getJSONArray("events").objects()
   val commit=events.single{it.getString("event")=="notes_committed"};val request=events.single{it.getString("event")=="notes_request_started"}
   assertEquals(request.getString("request_id"),commit.getString("request_id"));assertEquals("b".repeat(32),commit.getString("refresh_id"))
   assertEquals("ok",store.accounts["111"]!!.metadata.getJSONArray("games").getJSONObject(0).getString("status"))
  }finally{repo.close()}
 }
 @Test fun persistenceFailureHasNoCommitEvent(){
  val store=MemoryStore();store.accounts["111"]=saved();store.fail=true;val d=Diagnostics();val repo=Repository(store,ApiClient(FixtureTransport(),d),{"fixture"})
  try{repo.startRefresh("","manual");val row=done(repo).getJSONObject("refresh").getJSONArray("roles").getJSONObject(0);assertEquals("session_persist_failed",row.getString("error"));assertEquals("persist",row.getString("failureStage"))
   assertFalse(d.snapshot().getJSONArray("events").objects().any{it.getString("event")=="notes_committed"})
  }finally{repo.close()}
 }
 @Test fun reauthenticationRoleDiscoveryKeepsExistingDeviceAndProfile(){
  val store=MemoryStore();store.accounts["111"]=saved()
  val base=FixtureTransport()
  val wire=object:Transport{override fun execute(method:String,url:String,headers:Map<String,String>,body:JSONObject?,scope:String):WireResponse {
   if(url.contains("getUserGameRolesByCookie")){assertEquals("original-device",headers["x-rpc-device_id"]);assertEquals("0123456789abc",headers["x-rpc-device_fp"])}
   return base.execute(method,url,headers,body,scope)
  }}
  val repo=Repository(store,ApiClient(wire),{"fixture"})
  try{val id=repo.dispatch("/api/auth/qr/start","POST",JSONObject()).getString("sessionId");assertEquals("confirmed",repo.dispatch("/api/auth/qr/"+id,"GET",JSONObject()).getString("status"));assertEquals("original-profile",store.accounts["111"]!!.secret!!.getString("profileKey"))}finally{repo.close()}
 }
 @Test fun malformedOrForeignIdentityNeverPassesCredentialValidation(){
  val c=secret("111").getJSONObject("cookies");c.put("ltmid_v2","foreign")
  try{ApiClient.verifiedCookies(c);fail()}catch(e:PublicError){assertEquals("RESPONSE_INVALID",e.code)}
  c.put("ltmid_v2","fixture").put("cookie_token_v2","invalid;another=value")
  try{ApiClient.verifiedCookies(c);fail()}catch(e:PublicError){assertEquals("RESPONSE_INVALID",e.code)}
 }
 @Test fun processLocalReferencesAreIsolatedAndExportIsBounded(){
  val d=Diagnostics();val a=d.trace("111",gs.key(),"a".repeat(32));val b=d.trace("222",gs.key(),"a".repeat(32))
  assertNotEquals(a.accountRef,b.accountRef);assertNotEquals(a.roleRef,b.roleRef)
  assertEquals(a.accountRef,d.trace("111",gs.key(),"b".repeat(32)).accountRef)
  repeat(2001){d.event("notes_request_started",gs.game,Protocol.noteUrl(gs)+"&token=fixture-secret",classification="native_api",trace=a)}
  val s=d.snapshot();assertEquals(2000,s.getJSONArray("events").length());assertEquals(1,s.getInt("droppedEvents"))
  assertFalse(s.toString().contains("fixture-secret"));assertFalse(s.toString().contains(gs.uid));assertFalse(s.toString().contains("role_id"))
 }
}
