package io.github.peterfu.hoyoreminder
import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject
import org.json.JSONArray
/** 2026-11-03: only a real user refresh starts the status chain; the check-in detail refresh is query only. */
class Stage41Test {
 private class Store:AccountPersistence {
  val accounts=mutableListOf(fpAccount("111",true,"abcdef1234567"),fpAccount("222",false,"bcdef12345678"),fpAccount("333",true,"cdef123456789"));private var book=JSONObject()
  @Synchronized override fun load()=accounts.toList();override fun selected()="222";override fun select(id:String?){}
  @Synchronized override fun remove(id:String,selected:String?){accounts.removeAll{it.id==id}}
  override fun installationId()="fixture";@Synchronized override fun save(a:Account,select:Boolean){accounts.removeAll{it.id==a.id};accounts.add(a)}
  @Synchronized override fun loadCheckin()=book.copyJson();@Synchronized override fun saveCheckin(value:JSONObject){book=value.copyJson()}
 }
 private val keys=listOf("111|starrail:100000001:prod_gf_cn","222|genshin:100000001:cn_gf01","333|zenless:10000001:prod_gf_cn")
 private val selected="333|genshin:100000001:cn_gf01"
 private class Harness(val mode:String="ok",start:String="2026-10-25T16:01:00Z"){
  val store=Store();var now=java.time.Instant.parse(start).toEpochMilli();val operations=java.util.Collections.synchronizedList(mutableListOf<Pair<String,String>>());val calls=java.util.Collections.synchronizedList(mutableListOf<Pair<String,String>>());val signed=mutableSetOf<String>()
  val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(if(mode=="pause")1 else 0)
  val service=CheckInService(store,ApiClient(object:Transport{override fun execute(method:String,url:String,headers:Map<String,String>,body:JSONObject?,scope:String):WireResponse{
   val owner=Regex("account_id_v2=([0-9]+)").find(headers.getValue("Cookie"))!!.groupValues[1];val game=mapOf("hk4e" to "genshin:100000001:cn_gf01","hkrpg" to "starrail:100000001:prod_gf_cn","zzz" to "zenless:10000001:prod_gf_cn").getValue(headers.getValue("x-rpc-signgame"));val key=owner+"|"+game
   assertFalse(url.contains("resign"));assertEquals(store.load().firstOrNull{it.id==owner}?.secret?.getString("deviceId"),headers["x-rpc-device_id"])
   operations.add(key to url.substringBefore("?").substringAfterLast("/"));calls.add(method to key);entered.countDown();assertTrue(release.await(2,java.util.concurrent.TimeUnit.SECONDS))
   if(method=="POST"){
    if(mode=="timeout")throw PublicError("NETWORK_ERROR")
    if(mode=="risk")return WireResponse(obj("retcode" to 0,"data" to obj("risk_code" to 1)))
    signed.add(key);return WireResponse(obj("retcode" to 0,"data" to obj()))
   }
   return WireResponse(obj("retcode" to 0,"data" to obj("today" to if(mode=="old")"2026-10-25" else CheckInProtocol.day(now),"is_sign" to (key in signed),"total_sign_day" to (4+if(key in signed)1 else 0))))
  }}),{now})
  fun enable(keys:List<String>){service.configure(obj("roleKeys" to JSONArray(keys),"enabled" to true))}
  fun waitJob():JSONObject {val until=System.nanoTime()+4_000_000_000L;var j=service.autoView().getJSONObject("job");while(j.optString("status") in setOf("queued","running")&&System.nanoTime()<until){Thread.sleep(10);j=service.autoView().getJSONObject("job")};assertEquals("completed",j.getString("status"));return j}
 }
 @Test fun resourceRefreshQueriesSelectionAndSignsAutoEnabledOnly(){
  val h=Harness();try{h.enable(keys)
   h.service.requestAuto("resource_manual_refresh","a".repeat(32),listOf(selected));val j=h.waitJob()
   assertEquals(4,j.getJSONObject("metrics").getInt("initial_sends"));assertEquals(3,j.getJSONObject("metrics").getInt("sign_sends"));assertEquals(3,h.calls.count{it.first=="POST"})
   assertEquals("not_signed",h.service.batchView().getJSONObject("job").getJSONArray("roles").getJSONObject(0).getString("state"))
  }finally{h.service.closeBatch()}
 }
 @Test fun checkinDetailRefreshIsQueryOnly(){
  val h=Harness();try{h.enable(keys)
   h.service.startBatch(obj("operation" to "info","roleKeys" to JSONArray(listOf(selected))))
   val until=System.nanoTime()+4_000_000_000L;while(h.service.batchView().getJSONObject("job").optString("status")=="running"&&System.nanoTime()<until)Thread.sleep(10)
   assertEquals(0,h.calls.count{it.first=="POST"});assertEquals(1,h.operations.count{it.first==selected&&it.second=="info"});assertEquals(1,h.operations.count{it.first==selected&&it.second=="home"})
   assertTrue(h.service.autoView().getJSONObject("job").getBoolean("queryOnly"))
  }finally{h.service.closeBatch()}
 }
 @Test fun noDayRolloverOrScheduledEntryPoint(){
  val h=Harness();try{h.enable(keys)
   for(trigger in listOf("day_rollover","scheduled_checkin","timer","render","local"))try{h.service.requestAuto(trigger,"b".repeat(32),keys);fail()}catch(_:PublicError){}
   assertTrue(h.calls.isEmpty());assertTrue(h.service.autoView().isNull("job"))
  }finally{h.service.closeBatch()}
 }
 @Test fun manualRefreshBefore0001QueriesOnlyThenSignsAfterGate(){
  val h=Harness(start="2026-10-25T16:00:59Z");try{h.enable(keys)
   h.service.requestAuto("resource_manual_refresh","a".repeat(32),listOf(selected));h.waitJob();assertEquals(0,h.calls.count{it.first=="POST"})
   h.now+=1000;h.service.requestAuto("resource_manual_refresh","b".repeat(32),listOf(selected));assertEquals(3,h.waitJob().getJSONObject("counts").getInt("throttled"))
   h.now+=61000;h.service.requestAuto("resource_manual_refresh","c".repeat(32),listOf(selected));assertEquals(3,h.waitJob().getJSONObject("counts").getInt("signed"));assertEquals(3,h.calls.count{it.first=="POST"})
  }finally{h.service.closeBatch()}
 }
 @Test fun twoClicksCoalesceAndSecondRefreshCannotReplay(){
  val h=Harness("pause");try{h.enable(keys)
   val one=h.service.requestAuto("resource_card_refresh","a".repeat(32),listOf(selected));assertTrue(h.entered.await(2,java.util.concurrent.TimeUnit.SECONDS))
   val two=h.service.requestAuto("resource_manual_refresh","b".repeat(32),listOf(selected));assertEquals(one.getJSONObject("job").getString("operationId"),two.getJSONObject("job").getString("operationId"))
   h.release.countDown();h.waitJob();assertEquals(3,h.calls.count{it.first=="POST"})
   h.now+=5000;h.service.requestAuto("resource_manual_refresh","c".repeat(32),listOf(selected));assertEquals(3,h.waitJob().getJSONObject("counts").getInt("already_signed"));assertEquals(3,h.calls.count{it.first=="POST"})
  }finally{h.release.countDown();h.service.closeBatch()}
 }
 @Test fun uncertainAndRiskNeverBecomeSuccessOrReplayAfterAnHour(){
  for(mode in listOf("timeout","risk")){val h=Harness(mode);try{h.enable(keys);h.service.requestAuto("resource_manual_refresh","a".repeat(32),listOf(selected));val j=h.waitJob();assertEquals(3,j.getJSONObject("counts").getInt(if(mode=="risk")"blocked" else "unconfirmed"));h.now+=3600000;val restartPosts=java.util.concurrent.atomic.AtomicInteger();val restarted=CheckInService(h.store,ApiClient(object:Transport{override fun execute(method:String,url:String,headers:Map<String,String>,body:JSONObject?,scope:String):WireResponse{if(method=="POST")restartPosts.incrementAndGet();assertEquals("GET",method);return WireResponse(obj("retcode" to 0,"data" to obj("today" to CheckInProtocol.day(h.now),"is_sign" to false,"total_sign_day" to 4)))}}),{h.now});try{restarted.requestAuto("resource_manual_refresh","b".repeat(32),listOf(selected));val until=System.nanoTime()+2_000_000_000L;while(restarted.autoView().getJSONObject("job").getString("status") in setOf("queued","running")&&System.nanoTime()<until)Thread.sleep(10);assertEquals("completed",restarted.autoView().getJSONObject("job").getString("status"))}finally{restarted.closeBatch()};assertEquals(0,restartPosts.get());assertEquals(3,h.calls.count{it.first=="POST"})}finally{h.service.closeBatch()}}
 }
 @Test fun emptyAndUnsupportedTriggersNeverCallTransport(){
  val h=Harness();try{assertEquals(0,h.service.requestAuto("resource_manual_refresh","a".repeat(32),emptyList()).getJSONObject("job").getInt("total"));for(trigger in listOf("timer","render","local","resign"))try{h.service.requestAuto(trigger,"b".repeat(32),keys);fail()}catch(_:PublicError){};assertTrue(h.calls.isEmpty())}finally{h.service.closeBatch()}
 }
}
