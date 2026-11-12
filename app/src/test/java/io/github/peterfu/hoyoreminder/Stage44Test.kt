package io.github.peterfu.hoyoreminder
import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject
import org.json.JSONArray
class Stage44Test {
 private class Store:AccountPersistence{
  val accounts=mutableListOf(fpAccount("222",false,"abcdef1234567"));private var book=obj()
  @Synchronized override fun load()=accounts.toList();override fun selected()="222";override fun select(id:String?){}
  @Synchronized override fun remove(id:String,selected:String?){accounts.removeAll{it.id==id}}
  override fun installationId()="fixture";@Synchronized override fun save(a:Account,select:Boolean){accounts.removeAll{it.id==a.id};accounts.add(a)}
  @Synchronized override fun loadCheckin()=book.copyJson();@Synchronized override fun saveCheckin(value:JSONObject){book=value.copyJson()}
 }
 private val key="222|genshin:100000001:cn_gf01"
 private class Harness(var mode:String="network"){
  val store=Store();var now=java.time.Instant.parse("2026-10-28T16:05:00Z").toEpochMilli();var signed=false
  val attempts=java.util.Collections.synchronizedList(mutableListOf<String>());val diagnostics=Diagnostics()
  val api=ApiClient(object:Transport{override fun execute(method:String,url:String,headers:Map<String,String>,body:JSONObject?,scope:String):WireResponse{
   val op=java.net.URI(url).path.substringAfterLast('/');attempts.add(op)
   if(mode=="network"&&op=="info")throw PublicError("NETWORK_ERROR")
   if(mode=="429")return WireResponse(obj(),mapOf("Retry-After" to listOf("7200")),429)
   if(mode=="503")return WireResponse(obj(),emptyMap(),503)
   if(mode=="risk")return WireResponse(obj("retcode" to 10041,"data" to obj()))
   if(mode=="auth")return WireResponse(obj("retcode" to -100,"data" to obj()))
   if(mode=="post_rate"&&op=="sign")return WireResponse(obj(),mapOf("Retry-After" to listOf("600")),429)
   if(mode=="post_timeout_applied"&&op=="sign"){signed=true;throw PublicError("NETWORK_ERROR")}
   if(mode=="post_timeout"&&op=="sign")throw PublicError("NETWORK_ERROR")
   if(op=="sign"){signed=true;return WireResponse(obj("retcode" to 0,"data" to obj()))}
   return WireResponse(obj("retcode" to 0,"data" to if(op=="home")obj("month" to 10,"awards" to JSONArray(listOf(obj("name" to "fixture","cnt" to 1)))) else obj("today" to CheckInProtocol.day(now),"is_sign" to signed,"total_sign_day" to if(signed)5 else 4)))
  }},diagnostics)
  var service=CheckInService(store,api){now}
  init{service.configure(obj("roleKeys" to JSONArray(listOf("222|genshin:100000001:cn_gf01")),"enabled" to true))}
  fun run():JSONObject{service.requestAuto("resource_manual_refresh",java.util.UUID.randomUUID().toString().replace("-",""));repeat(300){val j=service.autoView().getJSONObject("job");if(j.optString("status") !in setOf("queued","running"))return j;Thread.sleep(10)};error("bounded job timed out")}
  fun row()=store.loadCheckin().getJSONObject("roles").getJSONObject("222|genshin:100000001:cn_gf01|CN")
  fun close(){service.closeBatch()}
 }
 @Test fun network0005Read0006Post0011HasThreeInitialsOnePostOneVerify(){val h=Harness();try{
  var j=h.run();assertEquals(300,h.row().getJSONObject("retry").getInt("durationSec"));val until=h.row().getLong("nextAt");assertEquals(0,j.getJSONObject("metrics").getInt("sign_sends"))
  h.mode="ok";h.now+=60000;j=h.run();assertEquals(1,j.getJSONObject("metrics").getInt("initial_sends"));assertEquals(0,j.getJSONObject("metrics").getInt("sign_sends"));assertEquals(until,h.row().getLong("nextAt"))
  h.now+=300000;j=h.run();assertEquals(1,j.getJSONObject("counts").getInt("signed"));assertEquals(1,h.attempts.count{it=="sign"});assertEquals(4,h.attempts.count{it=="info"})
  val decisions=h.diagnostics.snapshot().getJSONArray("events").objects().filter{it.optString("event")=="checkin_auto_decision"};assertEquals(3,decisions.size);assertTrue(decisions.all{it.optString("phase")=="final_decision"})
 }finally{h.close()}}
 @Test fun quickClicksSendNothingAndNeverSlideDeadline(){val h=Harness();try{h.run();val until=h.row().getJSONObject("retry").getLong("until");repeat(4){h.now+=5000;val j=h.run();assertEquals(0,j.getJSONObject("metrics").getInt("initial_sends"));assertEquals(1,j.getJSONObject("counts").getInt("throttled"));assertEquals(until,h.row().getJSONObject("retry").getLong("until"))};assertEquals(listOf("info"),h.attempts)}finally{h.close()}}
 @Test fun consecutiveEligibleFailuresUseFiveTenFifteenMinuteCap(){val h=Harness();try{for(duration in listOf(300,600,900,900)){h.run();assertEquals(duration,h.row().getJSONObject("retry").getInt("durationSec"));h.now=h.row().getLong("nextAt")}}finally{h.close()}}
 @Test fun serviceUnavailableSoftButRetryAfterCannotBeBypassed(){val h=Harness("503");try{h.run();assertEquals("server_unavailable",h.row().getJSONObject("retry").getString("kind"));assertEquals(300,h.row().getJSONObject("retry").getInt("durationSec"));h.now+=60000;h.mode="429";h.run();assertEquals(7200,h.row().getJSONObject("retry").getInt("durationSec"));val calls=h.attempts.size;h.mode="ok";h.now+=300000;assertEquals(1,h.run().getJSONObject("counts").getInt("rate_limited"));assertEquals(calls,h.attempts.size)}finally{h.close()}}
 @Test fun successfulInfoDoesNotProveRiskRecovery(){val h=Harness("risk");try{h.run();h.mode="ok";h.now+=10800000;val j=h.run();assertEquals(0,j.getJSONObject("metrics").getInt("sign_sends"));assertEquals("blocked",h.row().getString("state"));assertEquals(10041,h.row().getJSONObject("retry").getInt("retcode"))}finally{h.close()}}
 @Test fun validAuthenticatedInfoCanClearExpiredCredentials(){val h=Harness("auth");try{h.run();h.mode="ok";h.now+=60000;assertEquals(1,h.run().getJSONObject("counts").getInt("signed"))}finally{h.close()}}
 @Test fun uncertainPostSurvivesRestartAndNewDayArchivesOldIntent(){val h=Harness("post_timeout");try{h.run();assertTrue(h.row().getBoolean("postPending"));assertEquals(1,h.attempts.count{it=="sign"});h.close();h.service=CheckInService(h.store,h.api){h.now};h.now+=600000;h.run();assertEquals(1,h.attempts.count{it=="sign"});h.now+=86400000;h.run();assertEquals(2,h.attempts.count{it=="sign"});assertEquals("unconfirmed",h.row().getJSONArray("pendingHistory").getJSONObject(0).getString("state"))}finally{h.close()}}
 @Test fun rateLimitedPostDefersReadbackAndRetainsPending(){val h=Harness("post_rate");try{val j=h.run();assertEquals(1,j.getJSONObject("metrics").getInt("sign_sends"));assertEquals(0,j.getJSONObject("metrics").getInt("verify_sends"));assertTrue(h.row().getBoolean("postPending"));val before=h.attempts.size;h.now+=60000;h.run();assertEquals(before,h.attempts.size);h.now+=600000;h.mode="ok";h.run();assertEquals(1,h.attempts.count{it=="sign"})}finally{h.close()}}
 @Test fun unknownReceiptConfirmedImmediatelyIsOnlyAlreadySigned(){val h=Harness("post_timeout_applied");try{val j=h.run();assertEquals(1,j.getJSONObject("counts").getInt("already_signed"));assertEquals(0,j.getJSONObject("metrics").getInt("signed_count"));assertEquals(1,j.getJSONObject("metrics").getInt("verify_sends"))}finally{h.close()}}
 @Test fun successfulReadbackClearsPendingWithoutAnotherPost(){val h=Harness("post_timeout");try{h.run();h.signed=true;h.mode="ok";h.now+=60000;assertEquals(1,h.run().getJSONObject("counts").getInt("already_signed"));assertFalse(h.row().getBoolean("postPending"));assertEquals(1,h.attempts.count{it=="sign"})}finally{h.close()}}
 @Test fun rollbackDoesNotResetPersistentInterval(){val h=Harness();try{h.run();h.now-=3600000;assertEquals(1,h.run().getJSONObject("counts").getInt("throttled"));assertEquals(listOf("info"),h.attempts)}finally{h.close()}}
 @Test fun retryPlanIsRoleLocalAndExcludesUserAction(){val r=obj("enabled" to true,"nextAt" to 100000L,"lastAutoAt" to 1L,"retry" to obj("kind" to "transient"));assertEquals(100000L,CheckInRetry.nextDue(obj("roles" to obj("a" to r))));r.getJSONObject("retry").put("kind","risk");assertNull(CheckInRetry.nextDue(obj("roles" to obj("a" to r))))}
 @Test fun legacyUnknownIsNotGuessedToBeTransient(){val row=obj("state" to "cooldown","nextAt" to 1000000L);assertEquals("legacy_unknown",CheckInRetry.normalize(row,1000).getString("kind"));assertEquals(1000000L,row.getJSONObject("retry").getLong("until"))}
 @Test fun actualHttpBoundaryPreserves429AndRetryAfter(){var sent=0;val connection=object:java.net.HttpURLConnection(java.net.URL("https://api-takumi.mihoyo.com/")){
  override fun connect(){};override fun disconnect(){};override fun usingProxy()=false;override fun getResponseCode()=429;override fun getHeaderField(name:String)=if(name=="Retry-After")"7200" else null;override fun getHeaderFields()=mapOf("Retry-After" to listOf("7200"))
 };try{HttpTransport{connection}.executeCheckin("GET","https://api-takumi.mihoyo.com/event/luna/info",emptyMap(),null,"checkin:fixture"){sent++};fail("expected official rate limit")}catch(e:CheckInHttpFailure){assertEquals(429,e.status);assertEquals(7200000L,e.retryAfter);assertEquals(1,sent)}}
 @Test fun dailyRetryBudgetStopsBackgroundAndKeepsExplicitManualOpportunity(){val h=Harness();try{repeat(6){h.run();h.now=h.row().getLong("nextAt")};assertTrue(h.row().getJSONObject("retry").getBoolean("autoPaused"));assertNull(CheckInRetry.nextDue(h.store.loadCheckin(),h.now));assertNotNull(CheckInRetry.nextDue(h.store.loadCheckin(),h.now+86400000));h.mode="ok";assertEquals(1,h.run().getJSONObject("counts").getInt("signed"))}finally{h.close()}}
 @Test fun closedRetryDiagnosticsDoNotExportCredentials(){val d=Diagnostics();d.checkinRefresh("checkin_cooldown_applied",obj("cooldown_kind" to "transient","cooldown_until" to 1793232000000L,"remaining_sec" to 300,"cookie" to "SECRET","device_id" to "SECRET","cooldown_source" to "secret"));val text=d.jsonLines();assertFalse(text.contains("SECRET"));val row=d.snapshot().getJSONArray("events").getJSONObject(0);assertEquals(300,row.getInt("remaining_sec"));assertFalse(row.has("cooldown_source"))}
}
