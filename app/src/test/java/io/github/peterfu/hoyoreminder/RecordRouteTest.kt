package io.github.peterfu.hoyoreminder
import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject
import org.json.JSONArray

class RecordRouteTest {
 private fun role(game:Game)=Role(game,if(game==Game.ZENLESS)"10000001" else "100000001",if(game==Game.GENSHIN)"cn_gf01" else "prod_gf_cn","fixture","fixture",60)
 private fun error(code:String,block:()->Unit){try{block();fail("Expected "+code)}catch(e:PublicError){assertEquals(code,e.code)}}
 @Test fun catalogHasDistinctTrustedOverviewForEachGame(){
  for(game in Game.entries){
   val r=role(game);val entry=RecordEntries.catalog(r)
   assertTrue(RecordEntries.allowed(entry.url,r));assertFalse(entry.url.contains("daily"))
   assertEquals("catalog",entry.source)
   for(other in Game.entries.filter{it!=game})assertFalse(RecordEntries.allowed(entry.url,role(other)))
  }
 }
 @Test fun entryValidationRejectsOtherRoleServerSchemeHostAndDuplicateIdentity(){
  val r=role(Game.GENSHIN);val url=RecordEntries.catalog(r).url
  for(bad in listOf(url.replace("https:","http:"),url.replace("webstatic.mihoyo.com","webstatic.mihoyo.com.evil.invalid"),
   url.replace("webstatic.mihoyo.com","user@webstatic.mihoyo.com"),url+"&role_id=999999999",url+"&server=cn_qd01",
   url+"&role_id="+r.uid+"&role_id="+r.uid,url+"#/ys/daily?role_id=999999999",url+"#/zzz")){
   assertFalse(bad,RecordEntries.allowed(bad,r))
  }
  assertTrue(RecordEntries.allowed(url+"#/ys/daily?role_id="+r.uid+"&server="+r.server,r))
 }
 @Test fun cardEntriesAreBoundToTheExactGameUidAndRegion(){
  val r=role(Game.STARRAIL);val card=obj("game_id" to 6,"game_role_id" to r.uid,"region" to r.server,"url" to RecordEntries.catalog(r).url)
  assertEquals("card",RecordEntries.fromCard(card,r)!!.source)
  assertNull(RecordEntries.fromCard(card.copyJson().put("game_role_id","100000002"),r))
  error("RECORD_ENTRY_INVALID"){RecordEntries.fromCard(card.copyJson().put("region","prod_qd_cn"),r)}
  error("RECORD_ENTRY_INVALID"){RecordEntries.fromCard(card.copyJson().put("url","https://evil.invalid/"),r)}
 }
 @Test fun apiDiscoversCardsAndDoesNotConfuseRiskRejectionWithNoBrowserEntry(){
  val account=fpAccount("111",true)
  val calls=mutableListOf<String>()
  val api=ApiClient(object:Transport{override fun execute(method:String,url:String,headers:Map<String,String>,body:JSONObject?,scope:String):WireResponse{
   calls.add(url);assertEquals(Protocol.USER_AGENT,headers["User-Agent"])
   return WireResponse(obj("retcode" to 10041))
  }})
  for(game in Game.entries)assertEquals("catalog",api.recordEntry(account.secret!!,account.id,role(game),"fixture").source)
  assertEquals(3,calls.size);assertTrue(calls.all{it.contains("getGameRecordCard")});assertTrue(calls.none{it.contains("createVerification")})
 }
 @Test fun bridgePushPageIsScopedToCurrentGameAndHandlesSafeDeepLinks(){
  val r=role(Game.GENSHIN);val url=RecordEntries.catalog(r).url
  assertNotNull(RecordEntries.target("#/ys/daily?role_id="+r.uid+"&server="+r.server,url,r))
  assertNull(RecordEntries.target("javascript:alert(1)",url,r))
  assertNull(RecordEntries.target("https://evil.invalid/",url,r))
  assertNull(RecordEntries.target(RecordEntries.catalog(role(Game.ZENLESS)).url,url,r))
  assertNotNull(RecordEntries.target("mihoyobbs://webview?link="+java.net.URLEncoder.encode(url,"UTF-8"),url,r))
 }
 @Test fun trueBridgeValuesUseTheAccountAndFormalFpNeverAPendingCandidate(){
  val account=fpAccount("111",true,"abcdef1234567")
  account.secret!!.put("deviceFpPending",DeviceFingerprint.identity("111","device-111",null,{JSONObject()},1800000000000))
  val api=ApiClient(object:Transport{override fun execute(method:String,url:String,headers:Map<String,String>,body:JSONObject?,scope:String):WireResponse=throw AssertionError("no requests")})
  val r=role(Game.GENSHIN);val bridge=RecordBridge(api,account,r){account.secret!!}
  val current=RecordEntries.catalog(r).url
  assertEquals("111",bridge.handle("getCookieInfo",JSONObject(),current).response.getJSONObject("data").getString("ltuid"))
  val headers=bridge.handle("getHTTPRequestHeaders",JSONObject(),current).response.getJSONObject("data")
  assertEquals(Protocol.USER_AGENT,headers.getString("User-Agent"));assertEquals(Protocol.VERSION,headers.getString("x-rpc-app_version"))
  assertEquals("device-111",headers.getString("x-rpc-device_id"));assertEquals("abcdef1234567",headers.getString("x-rpc-device_fp"))
  assertFalse(headers.has("Cookie"));assertTrue(headers.has("x-rpc-device_model"))
  assertEquals("111",bridge.handle("getUserInfo",JSONObject(),current).response.getJSONObject("data").getString("id"))
 }
 @Test fun unsupportedSsoAndRefreshAreExplicitFailuresNotInventedTokens(){
  val a=fpAccount("111");val r=role(Game.GENSHIN);val bridge=RecordBridge(ApiClient(FixtureTransport()),a,r){a.secret!!};val url=RecordEntries.catalog(r).url
  for(pair in listOf("getActionTicket" to JSONObject(),"login" to JSONObject(),"unknown-private" to JSONObject(),"getCookieToken" to obj("forceRefresh" to true))){
   assertEquals(-1,bridge.handle(pair.first,pair.second,url).response.getInt("retcode"))
  }
  assertEquals("rejected",bridge.handle("pushPage",obj("page" to "https://evil.invalid"),url).status)
 }
 @Test fun bridgeRejectsAnotherAccountAndSignsCanonicalDataWithoutChangingNativeSigner(){
  val a=fpAccount("111");val r=role(Game.GENSHIN);val url=RecordEntries.catalog(r).url
  val wrong=RecordBridge(ApiClient(FixtureTransport()),a,r){fpAccount("222").secret!!}
  error("AUTH_EXPIRED"){wrong.handle("getCookieInfo",JSONObject(),url)}
  val bridge=RecordBridge(ApiClient(FixtureTransport()),a,r){a.secret!!}
  assertEquals("a=1&b=2",RecordBridge.query(obj("b" to 2,"a" to 1)))
  val ds=bridge.handle("getDS2",obj("query" to obj("role_id" to r.uid,"server" to r.server),"body" to ""),url).response.getJSONObject("data").getString("DS").split(',')
  assertEquals(Protocol.ds2("role_id="+r.uid+"&server="+r.server,ds[0].toLong(),ds[1].toInt()),ds.joinToString(","))
  error("RESPONSE_INVALID"){bridge.handle("getDS2",obj("query" to obj("role_id" to "100000002")),url)}
  assertEquals("1700000000,abc123,"+Protocol.md5("salt=yBh10ikxtLPoIhgwgPZSv5dmfaOTSJ6a&t=1700000000&r=abc123"),RecordBridge.ds1(1700000000,"abc123"))
 }
 @Test fun nestedUnknownRiskShapeIsObservedButNotFabricatedAsAChallenge(){
  val response=WireResponse(obj("retcode" to 10041,"data" to obj("risk" to obj("token" to "fixture-private"))))
  val context=VerificationContext.inspect(response)
  assertEquals("nested",context.location);assertEquals("none",context.kind)
  assertNull(NoteChallenge.parse(response,owner="111",role=role(Game.ZENLESS)))
  assertEquals("account_restricted",NoteParser.status(10041))
 }
 @Test fun zzzOutgoingHeadersHaveRealMetadataButNoInheritedDsOrSolvedProof(){
  var sent=emptyMap<String,String>();val account=fpAccount("111",true,"abcdef1234567");val r=role(Game.ZENLESS)
  val api=ApiClient(object:Transport{override fun execute(method:String,url:String,headers:Map<String,String>,body:JSONObject?,scope:String):WireResponse{sent=headers;return WireResponse(obj("retcode" to 10041))}})
  api.note(account.secret!!,r,"fixture")
  assertEquals("2",sent["x-rpc-platform"]);assertEquals(ZzzH5Headers.PAGE,sent["x-rpc-page"])
  val metadata=JSONObject(sent["x-rpc-geetest_ext"]!!)
  assertEquals("111",metadata.getString("viewUid"));assertEquals(r.server,metadata.getString("server"));assertEquals(8,metadata.getInt("gameId"))
  assertEquals(Protocol.USER_AGENT,sent["User-Agent"]);assertEquals("abcdef1234567",sent["x-rpc-device_fp"])
  for(name in listOf("DS","x-rpc-client_type","x-rpc-tool_verison","x-rpc-aigis","x-rpc-challenge"))assertFalse(sent.containsKey(name))
  assertTrue(ApiClient.cookieHeader(DeviceFingerprint.cookies(account.secret!!)).contains("account_id_v2=111"))
  api.observedNote(account.secret!!,r,Protocol.noteUrl(r),ApiClient.cookieHeader(DeviceFingerprint.cookies(account.secret!!)),mapOf("X-Rpc-Page" to "v3.0.17_#/zzz/notebook"),"fixture")
  assertEquals("v3.0.17_#/zzz/notebook",sent["x-rpc-page"])
  assertEquals(sent["x-rpc-page"],JSONObject(sent["x-rpc-geetest_ext"]!!).getString("page"))
  api.observedNote(account.secret!!,r,Protocol.noteUrl(r),ApiClient.cookieHeader(DeviceFingerprint.cookies(account.secret!!)),mapOf("x-rpc-page" to "untrusted-value"),"fixture")
  assertEquals(ZzzH5Headers.PAGE,sent["x-rpc-page"])
 }
 @Test fun threeGameBrowserDoesNotRequireChallengeOrModifySuccessfulSamples(){
  val store=MemoryStore();store.accounts["111"]=fpAccount("111",true,"abcdef1234567")
  val before=store.accounts["111"]!!.secret!!.copyJson().also{it.put("cookies",DeviceFingerprint.cookies(it))}
  val repo=Repository(store,ApiClient(object:Transport{override fun execute(method:String,url:String,headers:Map<String,String>,body:JSONObject?,scope:String):WireResponse=throw AssertionError("no challenge or note on browse")}),{"fixture"})
  var opened=0;var callback:((VerifiedNote?,String)->Boolean)?=null
  repo.verifier=object:VerificationPort{
   override fun supportsRecordBrowsing()=true
   override fun open(request:VerificationRequest,done:(VerifiedNote?,String)->Boolean){opened++;assertTrue(request.browse);assertNull(request.challenge);callback=done}
   override fun close(scope:String){};override fun focus(){}
  }
  try{
   for(game in Game.entries){
    assertTrue(repo.dispatch("/api/accounts/record/open","POST",obj("accountId" to "111","game" to game.id,"roleUid" to role(game).uid)).getBoolean("opened"))
    assertFalse(callback!!.invoke(VerifiedNote(null,fpCookies("111")),"closed_without_data"))
    assertEquals(0,store.accounts["111"]!!.metadata.getJSONArray("games").length())
   }
   assertEquals(3,opened);assertEquals(before.toString(),store.accounts["111"]!!.secret.toString())
  }finally{repo.close()}
 }
 @Test fun accountSwitchCancelsBrowserAndLateResultCannotChangeEitherAccount(){
  val store=MemoryStore();store.accounts["111"]=fpAccount("111",true,"1111111111111");store.accounts["222"]=fpAccount("222",false,"2222222222222");store.choice="111"
  var callback:((VerifiedNote?,String)->Boolean)?=null;var closed=0
  val repo=Repository(store,ApiClient(FixtureTransport()),{"fixture"})
  repo.verifier=object:VerificationPort{override fun supportsRecordBrowsing()=true
   override fun open(request:VerificationRequest,done:(VerifiedNote?,String)->Boolean){callback=done}
   override fun close(scope:String){closed++};override fun focus(){}
  }
  try{
   repo.dispatch("/api/accounts/record/open","POST",obj("accountId" to "111","game" to "genshin","roleUid" to role(Game.GENSHIN).uid))
   repo.dispatch("/api/accounts/select","POST",obj("accountId" to "222"));assertEquals(1,closed)
   assertFalse(callback!!.invoke(VerifiedNote(null,fpCookies("222")),"closed_without_data"))
   assertEquals("1111111111111",store.accounts["111"]!!.secret!!.getString("deviceFp"))
   error("RESPONSE_INVALID"){repo.dispatch("/api/accounts/record/open","POST",obj("accountId" to "222","game" to "zenless","roleUid" to role(Game.ZENLESS).uid))}
  }finally{repo.close()}
 }
 @Test fun browserOpeningBlocksFpRepairAndDuplicateOpenOnlyFocuses(){
  val store=MemoryStore();store.accounts["111"]=fpAccount("111",true);val repo=Repository(store,ApiClient(FixtureTransport()),{"fixture"});var opens=0;var focuses=0
  repo.verifier=object:VerificationPort{override fun supportsRecordBrowsing()=true
   override fun open(request:VerificationRequest,done:(VerifiedNote?,String)->Boolean){opens++}
   override fun close(scope:String){};override fun focus(){focuses++}
  }
  try{
   val body=obj("accountId" to "111","game" to "genshin","roleUid" to role(Game.GENSHIN).uid)
   repo.dispatch("/api/accounts/record/open","POST",body);assertTrue(repo.dispatch("/api/accounts/record/open","POST",body).getBoolean("alreadyPending"))
   assertEquals(1,opens);assertEquals(1,focuses)
   error("REFRESH_IN_PROGRESS"){repo.dispatch("/api/accounts/fingerprint/repair","POST",obj("accountId" to "111"))}
   error("VERIFICATION_PENDING"){repo.startRefresh("","manual")}
  }finally{repo.close()}
 }
 @Test fun noProfileCapabilityIsAnExplicitErrorAndNeverOpensAnUnisolatedBrowser(){
  val store=MemoryStore();store.accounts["111"]=fpAccount("111");val repo=Repository(store,ApiClient(FixtureTransport()),{"fixture"})
  try{error("PROFILE_UNSUPPORTED"){repo.dispatch("/api/accounts/record/open","POST",obj("accountId" to "111","game" to "genshin","roleUid" to role(Game.GENSHIN).uid))}}finally{repo.close()}
 }
}
