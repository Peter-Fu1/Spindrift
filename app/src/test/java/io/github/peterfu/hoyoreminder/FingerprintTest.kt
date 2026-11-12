package io.github.peterfu.hoyoreminder
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Test
import org.junit.Assert.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal fun fpCookies(id:String)=JSONObject().apply{ApiClient.AUTH_NAMES.forEach{put(it,if(it in setOf("account_id_v2","ltuid_v2"))id else "fixture-"+id)}}
internal fun fpAccount(id:String,three:Boolean=false,fp:String?=null):Account {
 val roles=Game.entries.filter{three||it==Game.GENSHIN}.map{g->Role(g,if(g==Game.ZENLESS)"10000001" else "100000001",if(g==Game.GENSHIN)"cn_gf01" else "prod_gf_cn","","",60).json()}
 val secret=obj("deviceId" to "device-"+id,"profileKey" to "profile-"+id,"cookies" to fpCookies(id))
 if(fp!=null)secret.put("deviceFp",fp)
 return Account(id,obj("roles" to JSONArray(roles),"games" to JSONArray(),"auth" to "authenticated"),secret)
}
private class FpWire:Transport {
 var calls=0;var response=obj("retcode" to 0,"data" to obj("code" to 200,"device_fp" to "abcdef1234567"))
 var entered:CountDownLatch?=null;var release:CountDownLatch?=null;var error:String?=null;var beforeReturn:(()->Unit)?=null
 val payloads=mutableListOf<JSONObject>();var headers=emptyMap<String,String>();var cancelled=false
 override fun execute(method:String,url:String,headers:Map<String,String>,body:JSONObject?,scope:String):WireResponse {
  assertEquals(DeviceFingerprint.ENDPOINT,url);assertEquals("POST",method);calls++;this.headers=headers;payloads.add(body!!.copyJson())
  entered?.countDown();release?.await(2,TimeUnit.SECONDS);beforeReturn?.invoke();error?.let{throw PublicError(it)}
  return WireResponse(response)
 }
 override fun cancel(scope:String){cancelled=true}
}
class FingerprintTest {
 private val now=1800000000000L
 private fun repair(repo:Repository,id:String="222")=repo.dispatch("/api/accounts/fingerprint/repair","POST",obj("accountId" to id))
 private fun store()=MemoryStore().apply{accounts["111"]=fpAccount("111",true,"1111111111111");accounts["222"]=fpAccount("222");accounts["333"]=fpAccount("333",true,"3333333333333");choice="222"}
 private fun error(code:String,action:()->Unit){try{action();fail("Expected "+code)}catch(e:PublicError){assertEquals(code,e.code)}}
 @Test fun stable53FieldProfileUsesActualPropertiesAndIndependentLogicalIdentities(){
  val a=DeviceFingerprint.identity("111","d1",null,{obj("sdkVersion" to "28","osVersion" to "9","model" to "ActualDevice")},now)
  val b=DeviceFingerprint.identity("222","d2",null,{JSONObject()},now)
  assertEquals(53,a.getJSONObject("extFields").length())
  assertEquals("28",a.getJSONObject("extFields").getString("sdkVersion"))
  assertEquals("9",a.getJSONObject("extFields").getString("osVersion"))
  assertEquals("ActualDevice",a.getJSONObject("extFields").getString("model"))
  assertEquals("",b.getJSONObject("extFields").getString("oaid"))
  for(k in listOf("androidId","seedId","candidateFp","bbsDeviceId"))assertNotEquals(a.getString(k),b.getString(k))
  val retry=DeviceFingerprint.identity("111","d1",a,{throw AssertionError("profile must persist")},now+70000)
  assertEquals(a.toString(),retry.toString())
 }
 @Test fun minimalPendingUpgradePreservesIdsAndSeed(){
  val old=DeviceFingerprint.identity("222","d2",null,{JSONObject()},now)
  old.remove("extFields");old.put("profileVersion",1)
  val updated=DeviceFingerprint.identity("222","d2",old,{JSONObject()},now+70000)
  for(k in listOf("bbsDeviceId","androidId","seedId","seedTime","candidateFp"))assertEquals(old.get(k),updated.get(k))
  assertEquals(53,JSONObject(DeviceFingerprint.payload(updated,null).getString("ext_fields")).length())
 }
 @Test fun candidateIsNeverAnIssuedCredentialAndMalformedSuccessIsRejected(){
  val s=fpAccount("222").secret!!;s.put("deviceFpPending",DeviceFingerprint.identity("222","device-222",null,{JSONObject()},now))
  assertNull(DeviceFingerprint.formal(s));assertFalse(DeviceFingerprint.cookies(s).has("DEVICEFP"))
  for(e in listOf(obj("retcode" to "0"),obj("retcode" to 1),obj("retcode" to 0,"data" to obj("code" to "200")),
   obj("retcode" to 0,"data" to obj("code" to 201)),obj("retcode" to 0,"data" to obj("code" to 200,"device_fp" to "0000000000000")),
   obj("retcode" to 0,"data" to obj("code" to 200,"device_fp" to "arbitrary")))){
   assertTrue(runCatching{DeviceFingerprint.accepted(e)}.isFailure)
  }
  assertEquals("abcdef1234567",DeviceFingerprint.accepted(obj("retcode" to 0,"data" to obj("code" to 200,"device_fp" to "ABCDEF1234567"))))
 }
 @Test fun bWithOnlyGenshinCanApplyWithoutTouchingAOrCAndReloadUsesSameFormal(){
  val store=store();val a=store.accounts["111"]!!.secret.toString();val c=store.accounts["333"]!!.secret.toString()
  val wire=FpWire();val repo=Repository(store,ApiClient(wire),{"fixture"},{now})
  try{
   val result=repair(repo);assertEquals("saved",result.getJSONObject("fingerprintResult").getString("state"))
   val b=store.accounts["222"]!!.secret!!
   assertEquals("abcdef1234567",b.getString("deviceFp"));assertFalse(b.has("deviceFpPending"))
   assertEquals(b.getString("deviceFp"),DeviceFingerprint.cookies(b).getString("DEVICEFP"))
   assertEquals(a,store.accounts["111"]!!.secret.toString());assertEquals(c,store.accounts["333"]!!.secret.toString())
   assertFalse(wire.headers.keys.any{it.equals("Cookie",true)});assertEquals(2,wire.headers.size)
   assertEquals("2",wire.payloads.single().getString("platform"))
   assertFalse(repo.view().toString().contains("abcdef1234567"));assertFalse(repo.view().toString().contains("seedId"))
   error("REFRESH_COOLDOWN"){repair(repo)}
  }finally{repo.close()}
  val reload=Repository(store,ApiClient(wire),{"fixture"},{now})
  try{assertTrue(reload.view().getJSONArray("accounts").objects().first{it.getString("id")=="222"}.getJSONObject("fingerprint").getBoolean("present"));error("REFRESH_COOLDOWN"){repair(reload)}}finally{reload.close()}
 }
 @Test fun rejectionRetainsFormalPendingAndRetrySeed(){
  val store=store();val old=store.accounts["111"]!!.secret.toString();val wire=FpWire()
  wire.response=obj("retcode" to 0,"data" to obj("code" to 403))
  var clock=now;val repo=Repository(store,ApiClient(wire),{"fixture"},{clock})
  try{
   error("FP_UPSTREAM_REJECTED"){repair(repo,"111")};val first=wire.payloads.single()
   assertEquals("1111111111111",store.accounts["111"]!!.secret!!.getString("deviceFp"))
   assertTrue(store.accounts["111"]!!.secret!!.has("deviceFpPending"))
   clock+=60001;error("FP_UPSTREAM_REJECTED"){repair(repo,"111")}
   assertEquals(first.toString(),wire.payloads.last().toString())
   assertFalse(repo.view().toString().contains(store.accounts["111"]!!.secret!!.getJSONObject("deviceFpPending").getString("androidId")))
   assertNotEquals(old,store.accounts["111"]!!.secret.toString())
  }finally{repo.close()}
 }
 @Test fun savedFpIsReportedAsSameWithoutAutomaticNotesRequest(){
  val store=store();val wire=FpWire();wire.response=obj("retcode" to 0,"data" to obj("code" to 200,"device_fp" to "1111111111111"))
  val repo=Repository(store,ApiClient(wire),{"fixture"},{now})
  try{assertTrue(repair(repo,"111").getJSONObject("fingerprintResult").getBoolean("same"));assertEquals(1,wire.calls)}finally{repo.close()}
 }
 @Test fun failureToSavePendingDoesNotSendAndFailureToSaveFormalDoesNotPublish(){
  val store=store();val wire=FpWire();val repo=Repository(store,ApiClient(wire),{"fixture"},{now})
  try{
   store.fail=true;error("SAVE_FAILED"){repair(repo)};assertEquals(0,wire.calls);assertFalse(store.accounts["222"]!!.secret!!.has("deviceFpPending"))
   store.fail=false;wire.beforeReturn={store.fail=true};error("SAVE_FAILED"){repair(repo)}
   assertFalse(store.accounts["222"]!!.secret!!.has("deviceFp"));assertTrue(store.accounts["222"]!!.secret!!.has("deviceFpPending"))
  }finally{repo.close()}
 }
 @Test fun cancelledLateReplyCannotPublishAndConcurrentRefreshOrSecondRepairIsBlocked(){
  val store=store();val wire=FpWire();wire.entered=CountDownLatch(1);wire.release=CountDownLatch(1)
  val repo=Repository(store,ApiClient(wire),{"fixture"},{now});var outcome:Throwable?=null
  val thread=Thread{try{repair(repo)}catch(e:Throwable){outcome=e}}
  try{
   thread.start();assertTrue(wire.entered!!.await(2,TimeUnit.SECONDS))
   error("REFRESH_IN_PROGRESS"){repo.startRefresh("","manual")};error("REFRESH_IN_PROGRESS"){repair(repo,"111")}
   repo.dispatch("/api/accounts/select","POST",obj("accountId" to "333"))
   repo.dispatch("/api/accounts/fingerprint/cancel","POST",obj("accountId" to "222"))
   wire.release!!.countDown();thread.join(2000);assertFalse(thread.isAlive);assertEquals("CANCELLED",(outcome as PublicError).code)
   assertTrue(wire.cancelled);assertFalse(store.accounts["222"]!!.secret!!.has("deviceFp"));assertEquals("333",repo.view().getString("selectedId"))
  }finally{wire.release!!.countDown();repo.close()}
 }
 @Test fun deletedTargetCannotBeResurrectedByLateOfficialResponse(){
  val store=store();val wire=FpWire();wire.entered=CountDownLatch(1);wire.release=CountDownLatch(1)
  val repo=Repository(store,ApiClient(wire),{"fixture"},{now});val thread=Thread{runCatching{repair(repo)}}
  try{
   thread.start();assertTrue(wire.entered!!.await(2,TimeUnit.SECONDS));repo.dispatch("/api/accounts/remove","POST",obj("accountId" to "222"))
   wire.release!!.countDown();thread.join(2000);assertFalse(store.accounts.containsKey("222"))
  }finally{wire.release!!.countDown();repo.close()}
 }
 @Test fun timeoutAndCancellationKeepPreviousFormalAndPending(){
  for(code in listOf("FP_TIMEOUT","CANCELLED","NETWORK_ERROR","FP_HTTP_REJECTED")){
   val store=store();val wire=FpWire();wire.error=code;val repo=Repository(store,ApiClient(wire),{"fixture"},{now})
   try{error(code){repair(repo,"111")};assertEquals("1111111111111",store.accounts["111"]!!.secret!!.getString("deviceFp"));assertTrue(store.accounts["111"]!!.secret!!.has("deviceFpPending"))}finally{repo.close()}
  }
 }
 @Test fun sharedLegacyIdentityChangesOnlyForTargetAfterOfficialAcceptance(){
  val store=store();for(a in store.accounts.values)a.secret!!.put("deviceId","fixture-installation")
  val wire=FpWire();val repo=Repository(store,ApiClient(wire),{"fixture"},{now})
  try{repair(repo);assertNotEquals("fixture-installation",store.accounts["222"]!!.secret!!.getString("deviceId"));assertEquals("fixture-installation",store.accounts["111"]!!.secret!!.getString("deviceId"));assertEquals("fixture-installation",store.accounts["333"]!!.secret!!.getString("deviceId"))}finally{repo.close()}
 }
 @Test fun fpContextAndSeedsCannotBeBorrowedFromAnotherAccount(){
  val a=fpAccount("111",fp="1111111111111").secret!!
  a.put("deviceFpContext",DeviceFingerprint.identity("222","device-111",null,{JSONObject()},now))
  assertNull(DeviceFingerprint.formal(a));assertFalse(DeviceFingerprint.cookies(a).has("DEVICEFP"))
 }
 @Test fun cookieAcknowledgementsGateNavigationOnFirstLoadAndReuse(){
  val s=fpAccount("222",fp="abcdef1234567").secret!!
  s.put("deviceFpContext",DeviceFingerprint.identity("222","device-222",null,{JSONObject()},now))
  s.getJSONObject("cookies").put("DEVICEFP","0000000000001")
  repeat(2){
   val callbacks=mutableListOf<(Boolean)->Unit>();val values=mutableListOf<String>();var flushed=false;var result:Boolean?=null
   OfficialCookies.seed(s,{_,v,cb->values.add(v);callbacks.add(cb)},{flushed=true}){result=it}
   assertNull(result);assertFalse(flushed);assertTrue(values.any{it.startsWith("DEVICEFP=abcdef1234567;")})
   assertTrue(values.any{it.startsWith("DEVICEFP_SEED_ID=")})
   callbacks.dropLast(1).forEach{it(true)};assertNull(result);callbacks.last()(true);assertEquals(true,result);assertTrue(flushed)
  }
 }
 @Test fun rejectedCookieWriteDoesNotFlushOrNavigate(){
  val callbacks=mutableListOf<(Boolean)->Unit>();var flushed=false;var accepted:Boolean?=null
  OfficialCookies.seed(fpAccount("222").secret!!,{_,_,cb->callbacks.add(cb)},{flushed=true}){accepted=it}
  callbacks.forEachIndexed{i,cb->cb(i!=0)};assertEquals(false,accepted);assertFalse(flushed)
 }
 @Test fun issuanceTransportRejectsRedirectTargetsCookiesAndOversize(){
  error("FP_RESPONSE_INVALID"){FingerprintHttpTransport.requireRequest("POST","https://other.example/",emptyMap())}
  error("FP_RESPONSE_INVALID"){FingerprintHttpTransport.requireRequest("GET",DeviceFingerprint.ENDPOINT,emptyMap())}
  error("FP_RESPONSE_INVALID"){FingerprintHttpTransport.requireRequest("POST",DeviceFingerprint.ENDPOINT,mapOf("Cookie" to "secret"))}
  FingerprintHttpTransport.checkSize(65536);error("FP_RESPONSE_INVALID"){FingerprintHttpTransport.checkSize(65537)}
  assertEquals(15000,DeviceFingerprint.TIMEOUT_MS)
 }
 @Test fun diagnosticsDoNotExportFingerprintOrRequestIdentity(){
  val store=store();val d=Diagnostics();val repo=Repository(store,ApiClient(FpWire(),d),{"fixture"},{now})
  try{repair(repo);val log=d.jsonLines();val secret=store.accounts["222"]!!.secret!!
   assertFalse(log.contains(secret.getString("deviceFp")))
   val ctx=secret.getJSONObject("deviceFpContext");for(k in listOf("bbsDeviceId","androidId","seedId","candidateFp"))assertFalse(log.contains(ctx.getString(k)))
   assertTrue(log.contains("device_fp_committed"))
  }finally{repo.close()}
 }
 @Test fun reloginKeepsIssuedFingerprintProfileAndSeeds(){
  val store=store();val secret=store.accounts["111"]!!.secret!!
  val context=DeviceFingerprint.identity("111",secret.getString("deviceId"),null,{JSONObject()},now)
  secret.put("deviceFpContext",context);secret.put("deviceFpPending",context.copyJson())
  val repo=Repository(store,ApiClient(FixtureTransport()),{"fixture"},{now})
  try{
   val ticket=repo.dispatch("/api/auth/qr/start","POST",JSONObject()).getString("sessionId")
   repo.dispatch("/api/auth/qr/"+ticket,"GET",JSONObject())
   repeat(150){if(repo.dispatch("/api/accounts/refresh/status","GET",JSONObject()).optJSONObject("refresh")?.optString("status")=="running")Thread.sleep(10)}
   val saved=store.accounts["111"]!!.secret!!
   assertEquals("1111111111111",saved.getString("deviceFp"))
   assertEquals("device-111",saved.getString("deviceId"));assertEquals("profile-111",saved.getString("profileKey"))
   assertEquals(context.toString(),saved.getJSONObject("deviceFpContext").toString())
   assertTrue(saved.has("deviceFpPending"))
  }finally{repo.close()}
 }
 @Test fun rawCookieMismatchCannotCrossAccountInObservedNote(){
  val role=Role(Game.GENSHIN,"100000001","cn_gf01","","",60)
  val api=ApiClient(object:Transport{override fun execute(method:String,url:String,headers:Map<String,String>,body:JSONObject?,scope:String):WireResponse{throw AssertionError("must reject before send")}})
  error("AUTH_EXPIRED"){api.observedNote(fpAccount("111").secret!!,role,Protocol.noteUrl(role),ApiClient.cookieHeader(fpCookies("222")),emptyMap(),"fixture")}
 }

 @Test fun missingFormalDeletesOnlyFingerprintCookiesInTheCurrentProfile(){
  val values=mutableListOf<String>()
  OfficialCookies.seed(fpAccount("222").secret!!,{_,v,cb->values.add(v);cb(true)},{}){}
  for(name in listOf("DEVICEFP","DEVICEFP_SEED_ID","DEVICEFP_SEED_TIME"))assertTrue(values.any{it.startsWith(name+"=; Max-Age=0;")})
  assertFalse(values.any{it.startsWith("account_id_v2=;")})
 }
 @Test fun oldFormalIsRecommendedButNeverExpiresOrClearsAutomatically(){
  val secret=fpAccount("222",fp="abcdef1234567").secret!!
  val ctx=DeviceFingerprint.identity("222","device-222",null,{JSONObject()},now).put("updatedAt",now).put("source","official_getFp")
  secret.put("deviceFpContext",ctx)
  val status=DeviceFingerprint.status(secret,now+8*86400000L)
  assertTrue(status.getBoolean("present"));assertTrue(status.getBoolean("refreshRecommended"))
  assertEquals("abcdef1234567",DeviceFingerprint.formal(secret))
 }

 @Test fun officialHttpRedirectIsRejectedEvenWithSuccessShapedBody(){
  val api=ApiClient(object:Transport{override fun execute(method:String,url:String,headers:Map<String,String>,body:JSONObject?,scope:String)=WireResponse(obj("retcode" to 0,"data" to obj("code" to 200,"device_fp" to "abcdef1234567")),httpStatus=302)})
  val identity=DeviceFingerprint.identity("222","device-222",null,{JSONObject()},now)
  error("FP_HTTP_REJECTED"){api.fingerprint(identity,null,"fixture")}
 }
 @Test fun profileWithoutValidAuthenticationCannotNavigate(){
  var wrote=false;var accepted:Boolean?=null
  OfficialCookies.seed(obj("cookies" to obj()),{_,_,_->wrote=true},{}){accepted=it}
  assertEquals(false,accepted);assertFalse(wrote)
 }

}
