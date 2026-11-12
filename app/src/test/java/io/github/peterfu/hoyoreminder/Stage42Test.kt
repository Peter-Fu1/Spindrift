package io.github.peterfu.hoyoreminder
import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject
import org.json.JSONArray
/** 2026-11-03: resource refresh signs auto-enabled roles in its frozen scope; detail refresh is query only. */
class Stage42Test {
 private class Store:AccountPersistence {
  val accounts=mutableListOf(fpAccount("111",true,"abcdef1234567"),fpAccount("222",false,"bcdef12345678"),fpAccount("333",true,"cdef123456789"));private var book=JSONObject()
  @Synchronized override fun load()=accounts.toList();override fun selected()="222";override fun select(id:String?){}
  @Synchronized override fun remove(id:String,selected:String?){accounts.removeAll{it.id==id}}
  override fun installationId()="fixture";@Synchronized override fun save(a:Account,select:Boolean){accounts.removeAll{it.id==a.id};accounts.add(a)}
  val finalEntered=java.util.concurrent.CountDownLatch(1);val finalRelease=java.util.concurrent.CountDownLatch(1);@Volatile var pauseFinal=false
  @Synchronized override fun loadCheckin()=book.copyJson();override fun saveCheckin(value:JSONObject){if(pauseFinal&&value.optJSONObject("autoRun")?.optString("status")=="completed"){finalEntered.countDown();assertTrue(finalRelease.await(2,java.util.concurrent.TimeUnit.SECONDS))};synchronized(this){book=value.copyJson()}}
 }
 private val keys=listOf("111|starrail:100000001:prod_gf_cn","222|genshin:100000001:cn_gf01","333|zenless:10000001:prod_gf_cn")
 private class Harness(val mode:String="ok",start:String="2026-10-25T16:01:00Z"){
  val store=Store();var now=java.time.Instant.parse(start).toEpochMilli();val operations=java.util.Collections.synchronizedList(mutableListOf<Pair<String,String>>());val calls=java.util.Collections.synchronizedList(mutableListOf<Pair<String,String>>());val signed=mutableSetOf<String>()
  val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(if(mode=="pause")1 else 0)
  val service=CheckInService(store,ApiClient(object:Transport{override fun execute(method:String,url:String,headers:Map<String,String>,body:JSONObject?,scope:String):WireResponse{
   val owner=Regex("account_id_v2=([0-9]+)").find(headers.getValue("Cookie"))!!.groupValues[1];val game=mapOf("hk4e" to "genshin:100000001:cn_gf01","hkrpg" to "starrail:100000001:prod_gf_cn","zzz" to "zenless:10000001:prod_gf_cn").getValue(headers.getValue("x-rpc-signgame"));val key=owner+"|"+game
   assertFalse(url.contains("resign"));assertEquals(store.load().firstOrNull{it.id==owner}?.secret?.getString("deviceId"),headers["x-rpc-device_id"])
   operations.add(key to url.substringBefore("?").substringAfterLast("/"));calls.add(method to key);entered.countDown();assertTrue(release.await(2,java.util.concurrent.TimeUnit.SECONDS))
   if(mode=="invalid"&&key=="111|starrail:100000001:prod_gf_cn")return WireResponse(obj("retcode" to 0,"data" to obj("today" to CheckInProtocol.day(now))))
   if(method=="POST"){
    if(mode=="timeout")throw PublicError("NETWORK_ERROR")
    if(mode=="risk")return WireResponse(obj("retcode" to 0,"data" to obj("risk_code" to 1)))
    signed.add(key);return WireResponse(obj("retcode" to if(mode=="duplicate")-5003 else 0,"data" to obj()))
   }
   return WireResponse(obj("retcode" to 0,"data" to obj("today" to if(mode=="old")"2026-10-25" else CheckInProtocol.day(now),"is_sign" to (key in signed),"total_sign_day" to (4+if(key in signed)1 else 0))))
  }}),{now})
  fun enable(keys:List<String>){service.configure(obj("roleKeys" to JSONArray(keys),"enabled" to true))}
  fun waitJob():JSONObject {val until=System.nanoTime()+4_000_000_000L;var j=service.autoView().getJSONObject("job");while(j.optString("status") in setOf("queued","running")&&System.nanoTime()<until){Thread.sleep(10);j=service.autoView().getJSONObject("job")};assertEquals("completed",j.getString("status"));return j}
 }
 private val selected="333|genshin:100000001:cn_gf01"
 @Test fun disjointUnionSharesOneInitialResultPerRole(){
  val h=Harness();try{h.enable(keys);val v=h.service.requestAuto("resource_manual_refresh","a".repeat(32),listOf(selected));val j=h.waitJob();val m=j.getJSONObject("metrics")
   assertEquals(4,m.getInt("initial_sends"));assertEquals(3,m.getInt("sign_sends"));assertEquals(3,m.getInt("verify_sends"));assertEquals(0,m.getInt("reused"));assertEquals(v.getJSONObject("job").getString("operationId"),j.getString("operationId"));assertFalse(h.operations.contains(selected to "sign"))
   assertEquals("not_signed",h.service.batchView().getJSONObject("job").getJSONArray("roles").getJSONObject(0).getString("state"))
  }finally{h.service.closeBatch()}
 }
 @Test fun checkinDetailRefreshQueriesOnlyAndPublishesSelection(){
  val h=Harness();try{h.enable(keys);val v=h.service.startBatch(obj("operation" to "info","roleKeys" to JSONArray(listOf(selected,keys[0]))));val j=h.waitJob()
   assertEquals(2,j.getJSONObject("metrics").getInt("initial_sends"));assertEquals(0,j.getJSONObject("metrics").getInt("sign_sends"));assertEquals(0,h.calls.count{it.first=="POST"});assertTrue(j.getBoolean("queryOnly"));assertEquals(v.getJSONObject("job").getString("operationId"),j.getString("operationId"))
  }finally{h.service.closeBatch()}
 }
 @Test fun alreadySignedAndInvalidCannotCauseBlindPost(){
  val h=Harness("invalid");try{h.enable(keys);h.signed.add(keys[1]);h.service.requestAuto("resource_manual_refresh","a".repeat(32),listOf(selected));val j=h.waitJob()
   assertEquals(4,j.getJSONObject("metrics").getInt("initial_sends"));assertEquals(1,j.getJSONObject("metrics").getInt("sign_sends"));assertEquals(1,j.getJSONObject("metrics").getInt("verify_sends"));assertEquals(1,j.getJSONObject("counts").getInt("already_signed"))
  }finally{h.service.closeBatch()}
 }
 @Test fun before0001OnlyQueriesThenUserRefreshSigns(){
  val h=Harness(start="2026-10-25T16:00:59Z");try{h.enable(keys)
   h.service.requestAuto("resource_manual_refresh","a".repeat(32),listOf(selected));h.waitJob();assertEquals(4,h.operations.count{it.second=="info"});assertEquals(1,h.operations.count{it.second=="home"});assertEquals(0,h.calls.count{it.first=="POST"})
   h.now+=61000;h.service.requestAuto("resource_manual_refresh","b".repeat(32),listOf(selected));assertEquals(3,h.waitJob().getJSONObject("metrics").getInt("sign_sends"))
  }finally{h.service.closeBatch()}
 }
 @Test fun rapidSecondTriggerNeverLosesSelectionOrDuplicatesPost(){
  val h=Harness("pause");try{h.enable(keys);val v=h.service.requestAuto("resource_card_refresh","a".repeat(32),listOf(selected));assertTrue(h.entered.await(2,java.util.concurrent.TimeUnit.SECONDS))
   assertEquals(v.getJSONObject("job").getString("operationId"),h.service.requestAuto("resource_manual_refresh","b".repeat(32),listOf(selected)).getJSONObject("job").getString("operationId"))
   try{h.service.startBatch(obj("operation" to "info","roleKeys" to JSONArray(listOf("222|starrail:100000001:prod_gf_cn"))));fail()}catch(_:PublicError){}
   h.release.countDown();assertEquals(4,h.waitJob().getJSONObject("metrics").getInt("initial_sends"));assertEquals(3,h.calls.count{it.first=="POST"})
  }finally{h.release.countDown();h.service.closeBatch()}
 }
 @Test fun cancellationPreventsPostAndLeavesTerminalSnapshot(){
  val h=Harness("pause");try{h.enable(keys);val v=h.service.startBatch(obj("operation" to "info","roleKeys" to JSONArray(listOf(selected))));assertTrue(h.entered.await(2,java.util.concurrent.TimeUnit.SECONDS));h.service.cancelBatch(obj("operationId" to v.getJSONObject("job").getString("operationId")));h.release.countDown();val end=System.nanoTime()+3_000_000_000L;while(h.service.autoView().getJSONObject("job").optString("status") in setOf("queued","running")&&System.nanoTime()<end)Thread.sleep(10);assertEquals("cancelled",h.service.autoView().getJSONObject("job").getString("status"));assertEquals(0,h.calls.count{it.first=="POST"})}finally{h.release.countDown();h.service.closeBatch()}
 }
 @Test fun diagnosticProjectionRejectsRawIdentifiers(){val d=Diagnostics();d.checkinRefresh("checkin_info_requested",obj("operation_id" to "a".repeat(32),"role_ref" to "raw-uid","phase" to "initial_status","Cookie" to "secret","fp" to "secret","initial_sends" to 4));val row=d.snapshot().getJSONArray("events").getJSONObject(0);assertEquals("a".repeat(32),row.getString("operation_id"));assertFalse(row.has("role_ref"));assertFalse(row.has("Cookie"));assertFalse(row.has("fp"));assertEquals(4,row.getInt("initial_sends"))}
 @Test fun removedAccountCannotCommitItsLateResponseOrBorrowCredentials(){
  val h=Harness("pause");try{h.enable(keys);h.service.requestAuto("resource_manual_refresh","a".repeat(32),listOf(selected));assertTrue(h.entered.await(2,java.util.concurrent.TimeUnit.SECONDS));h.store.remove("111",null);h.release.countDown();h.waitJob();assertFalse(h.calls.any{it.first=="POST"&&it.second.startsWith("111|")});assertFalse(h.store.loadCheckin().getJSONObject("roles").keys().asSequence().any{it.startsWith("111|")})}finally{h.release.countDown();h.service.closeBatch()}
 }
 @Test fun dayChangeCannotJoinOldOperationOrSignFromLateResult(){
  val h=Harness("pause");try{h.enable(keys);h.service.requestAuto("resource_card_refresh","a".repeat(32),listOf(selected));assertTrue(h.entered.await(2,java.util.concurrent.TimeUnit.SECONDS));h.now+=86400000;try{h.service.requestAuto("resource_card_refresh","b".repeat(32),listOf(selected));fail()}catch(_:PublicError){};h.release.countDown();val end=System.nanoTime()+3_000_000_000L;while(h.service.autoView().getJSONObject("job").optString("status") in setOf("queued","running")&&System.nanoTime()<end)Thread.sleep(10);assertEquals("cancelled",h.service.autoView().getJSONObject("job").getString("status"));assertEquals(0,h.calls.count{it.first=="POST"});h.service.requestAuto("resource_manual_refresh","c".repeat(32),listOf(selected));assertEquals(3,h.waitJob().getJSONObject("metrics").getInt("sign_sends"))
 }finally{h.release.countDown();h.service.closeBatch()}
 }
 @Test fun authoritativeRevisionAdvancesAndLocalViewsDoNotFetch(){
  val h=Harness();try{h.enable(keys);val before=h.service.view().getLong("statusRevision");h.service.startBatch(obj("operation" to "info","roleKeys" to JSONArray(listOf(selected))));h.waitJob();val after=h.service.view().getLong("statusRevision");assertTrue(after>before);val calls=h.calls.size;h.service.autoView();h.service.batchView();assertEquals(after,h.service.view().getLong("statusRevision"));assertEquals(calls,h.calls.size)}finally{h.service.closeBatch()}
 }
 @Test fun completionIsNotPublishedBeforeDurableSummaryAndGateRelease(){
  val h=Harness();try{h.enable(keys);h.store.pauseFinal=true;h.service.startBatch(obj("operation" to "info","roleKeys" to JSONArray(listOf(selected))));assertTrue(h.store.finalEntered.await(2,java.util.concurrent.TimeUnit.SECONDS));assertEquals("running",h.service.autoView().getJSONObject("job").getString("status"));assertEquals("running",h.service.batchView().getJSONObject("job").getString("status"));h.store.finalRelease.countDown();h.waitJob();assertEquals("completed",h.store.loadCheckin().getJSONObject("autoRun").getString("status"));h.enable(emptyList())}finally{h.store.finalRelease.countDown();h.service.closeBatch()}
 }
 @Test fun repeatedFreshQueriesCannotShortenOrBypassRiskCooldown(){
  val h=Harness();try{h.enable(keys);val book=h.store.loadCheckin();val until=h.now+7200000;for(k in keys)book.getJSONObject("roles").getJSONObject(k+"|CN").put("state","blocked").put("nextAt",until);h.store.saveCheckin(book);repeat(2){i->h.now+=61000;h.service.requestAuto("resource_manual_refresh",(i.toString()+"a".repeat(31)),listOf(selected));val j=h.waitJob();assertEquals(0,j.getJSONObject("metrics").getInt("sign_sends"));for(k in keys)assertEquals(until,h.store.loadCheckin().getJSONObject("roles").getJSONObject(k+"|CN").getLong("nextAt"))};h.now=until+1;h.service.requestAuto("resource_manual_refresh","b".repeat(32),listOf(selected));assertEquals(0,h.waitJob().getJSONObject("metrics").getInt("sign_sends"))
 }finally{h.service.closeBatch()}
 }
 @Test fun negativeAlreadySignedRaceNeverCountsAsNewSign(){
  val h=Harness("duplicate");try{h.enable(keys);h.service.requestAuto("resource_manual_refresh","a".repeat(32),listOf(selected));val j=h.waitJob();assertEquals(4,j.getJSONObject("metrics").getInt("initial_sends"));assertEquals(3,j.getJSONObject("metrics").getInt("verify_sends"));assertEquals(3,j.getJSONObject("counts").getInt("already_signed"));assertEquals(0,j.getJSONObject("metrics").getInt("signed_count"))}finally{h.service.closeBatch()}
 }
}
