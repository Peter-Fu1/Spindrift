package io.github.peterfu.hoyoreminder
import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject
import org.json.JSONArray
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MemoryStore:AccountPersistence{
 private var ui=JSONObject()
 override fun loadUi()=ui.copyJson()
 override fun saveUi(value:JSONObject){if(fail)throw PublicError("SAVE_FAILED");ui=value.copyJson()}
 val accounts=mutableMapOf<String,Account>();var choice:String?=null;var fail=false
 override fun load()=accounts.values.toList()
 override fun selected()=choice
 override fun save(account:Account,select:Boolean){if(fail)throw PublicError("SAVE_FAILED");accounts[account.id]=account;if(select)choice=account.id}
 override fun select(id:String?){choice=id}
 override fun remove(id:String,selected:String?){accounts.remove(id);choice=selected}
 override fun installationId()="fixture-installation"
}
class FixtureTransport:Transport{
 var bindings:JSONArray?=null;val codes=mutableMapOf<String,Int>();var notes=0;var creates=0;var polls=0;var retcode=0;var roleGame="hk4e_cn";var release:CountDownLatch?=null;val entered=CountDownLatch(1)
 override fun execute(method:String,url:String,headers:Map<String,String>,body:JSONObject?,scope:String):WireResponse {
  if(url==DeviceFingerprint.ENDPOINT)return WireResponse(obj("retcode" to 0,"data" to obj("code" to 200,"device_fp" to "abcdef1234567")))
  if(url.endsWith("createQRLogin")){creates++;return WireResponse(obj("retcode" to 0,"data" to obj("ticket" to "fixture-ticket","url" to "https://passport-api.miyoushe.com/fixture")))}
  if(url.endsWith("queryQRLoginStatus")){polls++;entered.countDown();release?.await(2,TimeUnit.SECONDS);return WireResponse(obj("retcode" to 0,"data" to obj("status" to "Confirmed")),mapOf("Set-Cookie" to ApiClient.AUTH_NAMES.map{"$it=${if(it in setOf("account_id_v2","ltuid_v2"))"111" else "fixture"}; Domain=.mihoyo.com; Path=/; Secure"}))}
  if(url.contains("getUserGameRolesByCookie"))return WireResponse(obj("retcode" to 0,"data" to obj("list" to (bindings?:JSONArray(listOf(obj("game_biz" to roleGame,"game_uid" to "100000001","region" to "cn_gf01","region_name" to "天空岛","nickname" to "fixture","level" to 60)))))))
  if(url.contains("dailyNote")||url.contains("/hkrpg/api/note")||url.contains("/zzz/note")){notes++;val game=if(url.contains("dailyNote"))"genshin" else if(url.contains("/hkrpg/"))"starrail" else "zenless";return WireResponse(obj("retcode" to (codes[game]?:retcode),"data" to when(game){"genshin"->obj("current_resin" to 100,"max_resin" to 200,"resin_recovery_time" to "48000");"starrail"->obj("current_stamina" to 100,"max_stamina" to 300,"stamina_recover_time" to 72000);else->obj("energy" to obj("progress" to obj("current" to 100,"max" to 300),"restore" to 72000))}))}
  throw AssertionError("Unexpected fixture endpoint")
 }
}
class RepositoryTest {
 private fun awaitDone(repo:Repository):JSONObject{
  repeat(150){val v=repo.dispatch("/api/accounts/refresh/status","GET",JSONObject());if(v.optJSONObject("refresh")?.optString("status")!="running")return v;Thread.sleep(10)}
  throw AssertionError("Local fixture deadline")
 }
 @Test fun qrCommitIsIdempotentAndPublicViewDoesNotLeakCredentials(){
  val store=MemoryStore();val wire=FixtureTransport();var clock=1800000000000L;val repo=Repository(store,ApiClient(wire),{"data:image/png;base64,fixture"},{clock})
  try{
   val first=repo.dispatch("/api/auth/qr/start","POST",JSONObject()).getString("sessionId")
   val confirmed=repo.dispatch("/api/auth/qr/"+first,"GET",JSONObject());assertEquals("confirmed",confirmed.getString("status"))
   assertEquals("LOGIN_SUCCESS",confirmed.getString("code"));awaitDone(repo)
   assertEquals("confirmed",repo.dispatch("/api/auth/qr/"+first,"GET",JSONObject()).getString("status"));assertEquals(1,wire.polls)
   assertEquals(1,store.accounts.size);assertFalse(repo.view().toString().contains("fixture-ticket"));assertFalse(repo.view().toString().contains("ltoken_v2"))
   clock+=3000;val second=repo.dispatch("/api/auth/qr/start","POST",JSONObject()).getString("sessionId")
   assertEquals("ALREADY_LOGGED_IN",repo.dispatch("/api/auth/qr/"+second,"GET",JSONObject()).getString("code"));assertEquals(1,wire.notes)
  }finally{repo.close()}
 }
 @Test fun saveFailureDoesNotPublishAccount(){
  val store=MemoryStore();store.fail=true;val repo=Repository(store,ApiClient(FixtureTransport()),{"fixture"})
  try{val id=repo.dispatch("/api/auth/qr/start","POST",JSONObject()).getString("sessionId");try{repo.dispatch("/api/auth/qr/"+id,"GET",JSONObject());fail()}catch(e:PublicError){assertEquals("SAVE_FAILED",e.code)};assertEquals(0,repo.view().getJSONArray("accounts").length())}finally{repo.close()}
 }
 @Test fun cancelledQrCannotPublishLateConfirmation(){
  val store=MemoryStore();val wire=FixtureTransport();wire.release=CountDownLatch(1);val repo=Repository(store,ApiClient(wire),{"fixture"})
  try{
   val id=repo.dispatch("/api/auth/qr/start","POST",JSONObject()).getString("sessionId")
   val thread=Thread{runCatching{repo.dispatch("/api/auth/qr/"+id,"GET",JSONObject())}};thread.start();assertTrue(wire.entered.await(2,TimeUnit.SECONDS));repo.dispatch("/api/auth/qr/"+id,"DELETE",JSONObject());wire.release!!.countDown();thread.join(2000);assertTrue(store.accounts.isEmpty())
  }finally{repo.close()}
 }
 @Test fun risk5003DoesNotOpenCaptchaOrRetry(){
  val store=MemoryStore();val wire=FixtureTransport();wire.retcode=5003;val repo=Repository(store,ApiClient(wire),{"fixture"});var opens=0
  repo.verifier=object:VerificationPort{override fun open(request:VerificationRequest,done:(VerifiedNote?,String)->Boolean){opens++};override fun close(scope:String){};override fun focus(){}}
  try{val id=repo.dispatch("/api/auth/qr/start","POST",JSONObject()).getString("sessionId");repo.dispatch("/api/auth/qr/"+id,"GET",JSONObject());val result=awaitDone(repo);assertEquals(1,wire.notes);assertEquals(0,opens);assertEquals("account_risk",result.getJSONArray("accounts").getJSONObject(0).getJSONArray("games").getJSONObject(0).getString("status"))}finally{repo.close()}
 }
 @Test fun loginVerificationWaitsUntilUiReadyAndCancelDoesNotClaimSuccess(){
  val store=MemoryStore();val wire=FixtureTransport();wire.retcode=1034;val repo=Repository(store,ApiClient(wire),{"fixture"});var opened:((VerifiedNote?,String)->Boolean)?=null
  repo.verifier=object:VerificationPort{override fun open(request:VerificationRequest,done:(VerifiedNote?,String)->Boolean){opened=done};override fun close(scope:String){opened?.invoke(null,"cancelled")};override fun focus(){}}
  try{
   val id=repo.dispatch("/api/auth/qr/start","POST",JSONObject()).getString("sessionId");val login=repo.dispatch("/api/auth/qr/"+id,"GET",JSONObject());val refresh=login.getJSONObject("initialRefresh").getString("refreshId")
   repeat(60){if(repo.dispatch("/api/accounts/refresh/status","GET",JSONObject()).getJSONObject("refresh").getJSONArray("roles").getJSONObject(0).getString("state")=="verification_required")return@repeat;Thread.sleep(10)}
   assertNull(opened);repo.dispatch("/api/accounts/refresh/action","POST",obj("refreshId" to refresh,"action" to "ready"));repeat(100){if(opened==null)Thread.sleep(10)};assertNotNull(opened)
   repo.dispatch("/api/accounts/refresh/action","POST",obj("refreshId" to refresh,"action" to "cancel"))
   assertFalse(opened!!.invoke(null,"cancelled"));assertEquals("cancelled",repo.dispatch("/api/accounts/refresh/status","GET",JSONObject()).getJSONObject("refresh").getString("status"))
  }finally{repo.close()}
 }
 @Test fun deletedAccountCannotRestoreFromOldPayloadAndCacheSurvivesReload(){
  val store=MemoryStore();val wire=FixtureTransport();val repo=Repository(store,ApiClient(wire),{"fixture"})
  try{val id=repo.dispatch("/api/auth/qr/start","POST",JSONObject()).getString("sessionId");repo.dispatch("/api/auth/qr/"+id,"GET",JSONObject());awaitDone(repo)}finally{repo.close()}
  val restored=Repository(store,ApiClient(wire),{"fixture"})
  try{assertEquals(1,restored.view().getJSONArray("accounts").length());val roles=restored.view().getJSONArray("accounts").getJSONObject(0).getJSONArray("roles");assertEquals("cn_gf01",roles.getJSONObject(0).getString("serverId"));restored.dispatch("/api/accounts/remove","POST",obj("accountId" to "111"));assertTrue(store.accounts.isEmpty())}finally{restored.close()}
 }
 @Test fun smsRequiresValidPhoneBeforeAnyNetwork(){
  val repo=Repository(MemoryStore(),ApiClient(FixtureTransport()),{"fixture"})
  try{try{repo.dispatch("/api/auth/sms/send","POST",JSONObject());fail()}catch(e:PublicError){assertEquals("SMS_INVALID_PHONE",e.code)}}finally{repo.close()}
 }
 @Test fun threeGameRefreshCommitsPartialResultsIndependently(){
  val wire=FixtureTransport();wire.bindings=JSONArray(listOf(obj("game_biz" to "hk4e_cn","game_uid" to "100000001","region" to "cn_gf01"),obj("game_biz" to "hkrpg_cn","game_uid" to "100000001","region" to "prod_gf_cn"),obj("game_biz" to "nap_cn","game_uid" to "10000001","region" to "prod_gf_cn")));wire.codes["starrail"]=5003
  val repo=Repository(MemoryStore(),ApiClient(wire),{"fixture"})
  try{val id=repo.dispatch("/api/auth/qr/start","POST",JSONObject()).getString("sessionId");repo.dispatch("/api/auth/qr/"+id,"GET",JSONObject());val result=awaitDone(repo);assertEquals(3,wire.notes);val games=result.getJSONArray("accounts").getJSONObject(0).getJSONArray("games").objects();assertEquals(2,games.count{it.getString("status")=="ok"});assertEquals("account_risk",games.first{it.getString("id")=="starrail"}.getString("status"))}finally{repo.close()}
 }

}
