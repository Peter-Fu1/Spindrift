package io.github.peterfu.hoyoreminder
import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject
import org.json.JSONArray
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class UiUsabilityTest {
 private val now=1800000000000L
 private fun store()=MemoryStore().apply{accounts["111"]=fpAccount("111",true,"abcdef1234567");accounts["222"]=fpAccount("222",false,"bcdef12345678");accounts["333"]=fpAccount("333",true,"cdef123456789");choice="333"}
 private fun role(a:Account,index:Int)=Role.fromSaved(a.metadata.getJSONArray("roles").getJSONObject(index))
 private fun done(repo:Repository):JSONObject {
  repeat(300){val v=repo.dispatch("/api/accounts/refresh/status","GET",JSONObject());if(v.getJSONObject("refresh").optString("status")!="running")return v;Thread.sleep(10)}
  throw AssertionError("fixture timeout")
 }
 @Test fun threeGamesStoreActualRecoveryPhaseAndUtcSnapshotWithoutReplacingSampleOnFailure(){
  for(game in Game.entries){
   val r=Role(game,if(game==Game.ZENLESS)"10000001" else "100000001",if(game==Game.GENSHIN)"cn_gf01" else "prod_gf_cn","","",60)
   val data=when(game){
    Game.GENSHIN->obj("current_resin" to 198,"max_resin" to 200,"resin_recovery_time" to "500")
    Game.STARRAIL->obj("current_stamina" to 198,"max_stamina" to 200,"stamina_recover_time" to 400)
    Game.ZENLESS->obj("energy" to obj("progress" to obj("current" to 198,"max" to 200),"restore" to 400))
   }
   val n=NoteParser.parse(r,data,now);val snap=n.getJSONObject("resourceSnapshot")
   assertEquals(now,snap.getLong("observedAtUtc"));assertEquals("server_snapshot",snap.getString("source"))
   assertTrue(snap.getBoolean("phaseAvailable"));assertEquals(r.server,n.getString("serverId"))
   assertEquals(if(game==Game.GENSHIN)480 else 360,snap.getInt("periodSeconds"))
  }
 }
 @Test fun scopedAndSingleRefreshOnlyInvokeFrozenAuthorizedTargetsEvenIfUiSelectionChanges(){
  val st=store();val calls=mutableListOf<String>();val entered=CountDownLatch(1);val release=CountDownLatch(1)
  val base=FixtureTransport();val wire=object:Transport{
   override fun execute(method:String,url:String,headers:Map<String,String>,body:JSONObject?,scope:String):WireResponse {
    calls.add(ApiClient.verifiedCookies(JSONObject().apply{headers["Cookie"]!!.split(';').forEach{val p=it.trim().split('=',limit=2);if(p.size==2)put(p[0],p[1])}})+":"+java.net.URI(url).path)
    entered.countDown();release.await(2,TimeUnit.SECONDS);return base.execute(method,url,headers,body,scope)
   }
  }
  val repo=Repository(st,ApiClient(wire),{"fixture"},{now})
  try{
   val a=st.accounts.getValue("222");val key=UiPreferences.key(a.id,role(a,0))
   repo.dispatch("/api/accounts/refresh/start","POST",obj("roleKeys" to JSONArray(listOf(key))))
   assertTrue(entered.await(2,TimeUnit.SECONDS))
   repo.dispatch("/api/accounts/select","POST",obj("accountId" to "111"))
   repo.dispatch("/api/ui/preferences","POST",obj("accountIds" to JSONArray(listOf("333"))))
   release.countDown();val v=done(repo)
   assertEquals(1,calls.size);assertTrue(calls.single().startsWith("222:"))
   assertEquals(1,v.getJSONObject("refresh").getJSONArray("roles").length())
   assertEquals("111",v.getString("selectedId"))
   assertEquals(0,st.accounts.getValue("111").metadata.getJSONArray("games").length())
   assertTrue(st.accounts.getValue("222").metadata.getJSONArray("games").getJSONObject(0).has("resourceSnapshot"))
  }catch(e:Exception){throw e}finally{repo.close()}
 }

 @Test fun localPathAndColdRestoreNeverCallTransportOrOpenVerificationEvenDuringCooldown(){
  val st=store();val wire=object:Transport{override fun execute(method:String,url:String,headers:Map<String,String>,body:JSONObject?,scope:String):WireResponse=throw AssertionError("local must not send")}
  val repo=Repository(st,ApiClient(wire),{"fixture"})
  try{
   repo.verifier=object:VerificationPort{override fun open(request:VerificationRequest,done:(VerifiedNote?,String)->Boolean){throw AssertionError("local must not open")};override fun close(scope:String){};override fun focus(){}}
   val keys=UiPreferences.keys(st.accounts.values)
   repeat(3){repo.dispatch("/api/resources/estimate","POST",obj("roleKeys" to JSONArray(keys)))}
   assertFalse(repo.dispatch("/api/accounts/refresh/status","GET",JSONObject()).has("unexpected"))
   assertTrue(repo.dispatch("/api/accounts/refresh/status","GET",JSONObject()).isNull("refresh"))
  }finally{repo.close()}
 }
 @Test fun invalidUnknownDuplicateOrCrossOwnerRoleCannotBeAddedAndEmptyScopeDoesNotMeanAll(){
  val st=store();val wire=FixtureTransport();val repo=Repository(st,ApiClient(wire),{"fixture"},{now})
  try{
   for(keys in listOf(listOf("999|genshin:100000001:cn_gf01"),listOf("222|zenless:10000001:prod_gf_cn"),listOf("222|genshin:100000001:cn_gf01","222|genshin:100000001:cn_gf01"))){
    try{repo.dispatch("/api/accounts/refresh/start","POST",obj("roleKeys" to JSONArray(keys)));fail()}catch(e:PublicError){assertEquals("RESPONSE_INVALID",e.code)}
   }
   repo.dispatch("/api/accounts/refresh/start","POST",obj("roleKeys" to JSONArray()))
   assertEquals(0,done(repo).getJSONObject("refresh").getJSONArray("roles").length());assertEquals(0,wire.notes)
  }finally{repo.close()}
 }
 @Test fun preferencesPersistOrderModeZeroFiltersAndSelectedIdentityIndependentlyAcrossRepositoryReopen(){
  val st=store();var repo=Repository(st,ApiClient(FixtureTransport()),{"fixture"})
  repo.dispatch("/api/ui/preferences","POST",obj("order" to JSONArray(listOf("333","111","222")),"roleKeys" to JSONArray(),"expanded" to JSONArray(listOf("111")),"mode" to "local"));repo.close()
  repo=Repository(st,ApiClient(FixtureTransport()),{"fixture"})
  try{
   val v=repo.view();assertEquals("333",v.getString("selectedId"))
   val p=v.getJSONObject("uiPreferences");assertEquals("333",p.getJSONArray("order").getString(0));assertEquals(0,p.getJSONArray("roleKeys").length());assertEquals("local",p.getString("mode"))
   repo.dispatch("/api/accounts/remove","POST",obj("accountId" to "222"))
   assertFalse(repo.view().getJSONObject("uiPreferences").toString().contains("222"))
  }finally{repo.close()}
 }
 @Test fun realFailureKeepsSuccessfulSnapshotTasksAndTimeButRecordsLatestErrorAndAttempt(){
  val st=store();val wire=FixtureTransport();var clock=now
  val repo=Repository(st,ApiClient(wire),{"fixture"},{clock})
  try{
   val a=st.accounts.getValue("111");val key=UiPreferences.key(a.id,role(a,2))
   repo.dispatch("/api/accounts/refresh/start","POST",obj("roleKeys" to JSONArray(listOf(key))));done(repo)
   val old=a.metadata.getJSONArray("games").getJSONObject(0).copyJson();wire.codes["zenless"]=10041;clock+=6000
   repo.dispatch("/api/accounts/refresh/start","POST",obj("roleKeys" to JSONArray(listOf(key))));done(repo)
   val current=a.metadata.getJSONArray("games").getJSONObject(0)
   assertEquals(old.getJSONObject("resourceSnapshot").toString(),current.getJSONObject("resourceSnapshot").toString())
   assertEquals(old.getLong("updated"),current.getLong("updated"));assertEquals(old.getString("taskState"),current.getString("taskState"))
   assertEquals(10041,current.getInt("retcode"));assertTrue(current.getLong("lastRefreshAttemptAt")>old.getLong("lastRefreshAttemptAt"))
   assertEquals(0,st.accounts.getValue("333").metadata.getJSONArray("games").length())
  }finally{repo.close()}
 }
 @Test fun realProfilesUseOwnerCheckedUserAndCurrentRoleAvatarFieldsAndNeverBorrowAnotherOwner(){
  val st=store();val a=st.accounts.getValue("111")
  val wire=object:Transport{
   override fun execute(method:String,url:String,headers:Map<String,String>,body:JSONObject?,scope:String):WireResponse{
    assertEquals(Protocol.USER_AGENT,headers["User-Agent"])
    return WireResponse(obj("retcode" to 0,"data" to if(url.contains("getUserFullInfo"))obj("user_info" to obj("uid" to "111","nickname" to "真实昵称夹具","avatar_url" to "https://upload-bbs.mihoyo.com/account.png")) else obj("cur_head_icon_url" to "https://act-webstatic.mihoyo.com/current.png")))
   }
  }
  val repo=Repository(st,ApiClient(wire),{"fixture"})
  try{
   val before=a.secret!!.toString();repo.dispatch("/api/accounts/profile","POST",obj("accountId" to "111"))
   assertEquals("真实昵称夹具",a.metadata.getString("label"));assertEquals(3,a.metadata.getJSONObject("roleProfiles").length())
   assertEquals(before,a.secret!!.toString());assertFalse(st.accounts.getValue("333").metadata.has("avatar"))
   assertNull(repo.profileImage("333","genshin:999999999:cn_gf01"))
  }finally{repo.close()}
 }
 @Test fun publicImagePolicyRejectsCredentialsAndNonCdnOrUntrustedRedirectDestinations(){
  assertNotNull(SafeImages.url("https://upload-bbs.mihoyo.com/real.png"))
  for(url in listOf("http://upload-bbs.mihoyo.com/a.png","https://upload-bbs.mihoyo.com.evil.invalid/a.png","https://user@upload-bbs.mihoyo.com/a.png","https://upload-bbs.mihoyo.com/a.png?token=secret","data:image/png;base64,fixture"))
   assertNull(SafeImages.url(url))
 }

 @Test fun anotherOwnerProfileResponseCannotReplaceExistingIdentityOrBlockNextNotesRefresh(){
  val st=store();st.accounts.getValue("111").metadata.put("label","kept-own-label").put("avatar","https://upload-bbs.mihoyo.com/own.png")
  val fixture=FixtureTransport()
  val wire=object:Transport{override fun execute(method:String,url:String,headers:Map<String,String>,body:JSONObject?,scope:String):WireResponse{
   if(url.contains("getUserFullInfo"))return WireResponse(obj("retcode" to 0,"data" to obj("user_info" to obj("uid" to "333","nickname" to "wrong-owner","avatar_url" to "https://upload-bbs.mihoyo.com/other.png"))))
   if(url.contains("/index"))return WireResponse(obj("retcode" to 10041))
   return fixture.execute(method,url,headers,body,scope)
  }}
  val repo=Repository(st,ApiClient(wire),{"fixture"},{now})
  try{
   repo.dispatch("/api/accounts/profile","POST",obj("accountId" to "111"))
   assertEquals("kept-own-label",st.accounts.getValue("111").metadata.getString("label"))
   assertEquals("https://upload-bbs.mihoyo.com/own.png",st.accounts.getValue("111").metadata.getString("avatar"))
   val key=UiPreferences.key("111",role(st.accounts.getValue("111"),0))
   repo.dispatch("/api/accounts/refresh/start","POST",obj("roleKeys" to JSONArray(listOf(key))));done(repo)
   assertEquals(1,fixture.notes)
  }finally{repo.close()}
 }
 @Test fun reloginKeepsVerifiedProfileAndDisplayStateAlongWithExistingDeviceIdentity(){
  val st=MemoryStore();val old=fpAccount("111",true,"abcdef1234567")
  old.metadata.put("label","own-label").put("avatar","https://upload-bbs.mihoyo.com/own.png").put("avatarState","loaded").put("profileState","loaded")
  st.accounts["111"]=old
  val wire=FixtureTransport();val repo=Repository(st,ApiClient(wire),{"fixture"},{now})
  try{
   val qr=repo.dispatch("/api/auth/qr/start","POST",JSONObject()).getString("sessionId")
   repo.dispatch("/api/auth/qr/"+qr,"GET",JSONObject())
   val a=repo.view().getJSONArray("accounts").getJSONObject(0)
   assertEquals("own-label",a.getString("label"));assertEquals("loaded",a.getString("avatarState"))
   assertEquals("abcdef1234567",st.accounts.getValue("111").secret!!.getString("deviceFp"))
  }finally{repo.close()}
 }

 @Test fun emptyAvatarUrlDoesNotProveTheUserHasNeverSetAnAvatar(){
  val st=store();val wire=object:Transport{
   override fun execute(method:String,url:String,headers:Map<String,String>,body:JSONObject?,scope:String):WireResponse =
    WireResponse(obj("retcode" to 0,"data" to if(url.contains("getUserFullInfo"))obj("user_info" to obj("uid" to "111","nickname" to "fixture","avatar_url" to "")) else JSONObject()))
  }
  val repo=Repository(st,ApiClient(wire),{"fixture"})
  try{
   repo.dispatch("/api/accounts/profile","POST",obj("accountId" to "111"))
   assertEquals("not_provided",st.accounts.getValue("111").metadata.getString("avatarState"))
  }finally{repo.close()}
 }
}
