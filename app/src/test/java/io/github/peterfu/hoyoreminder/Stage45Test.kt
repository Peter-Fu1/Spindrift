package io.github.peterfu.hoyoreminder
import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject
import org.json.JSONArray
/** User-triggered-only automatic check-in (2026-11-03): a redundant trigger must never downgrade a confirmed sign. */
class Stage45Test {
 private class Store:AccountPersistence {
  val accounts=mutableListOf(fpAccount("111",true,"abcdef1234567"),fpAccount("222",false,"bcdef12345678"),fpAccount("333",true,"cdef123456789"));private var book=JSONObject()
  @Synchronized override fun load()=accounts.toList();override fun selected()="222";override fun select(id:String?){}
  @Synchronized override fun remove(id:String,selected:String?){accounts.removeAll{it.id==id}}
  override fun installationId()="fixture";@Synchronized override fun save(a:Account,select:Boolean){accounts.removeAll{it.id==a.id};accounts.add(a)}
  @Synchronized override fun loadCheckin()=book.copyJson();@Synchronized override fun saveCheckin(value:JSONObject){book=value.copyJson()}
 }
 private val keys=listOf("111|starrail:100000001:prod_gf_cn","222|genshin:100000001:cn_gf01","333|zenless:10000001:prod_gf_cn")
 private class Harness(val mode:String="ok",start:String="2026-11-02T16:05:00Z"){
  val store=Store();var now=java.time.Instant.parse(start).toEpochMilli();val calls=java.util.Collections.synchronizedList(mutableListOf<Pair<String,String>>());val signed=mutableSetOf<String>()
  val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(if(mode=="pause")1 else 0)
  val service=CheckInService(store,ApiClient(object:Transport{override fun execute(method:String,url:String,headers:Map<String,String>,body:JSONObject?,scope:String):WireResponse{
   val owner=Regex("account_id_v2=([0-9]+)").find(headers.getValue("Cookie"))!!.groupValues[1];val game=mapOf("hk4e" to "genshin:100000001:cn_gf01","hkrpg" to "starrail:100000001:prod_gf_cn","zzz" to "zenless:10000001:prod_gf_cn").getValue(headers.getValue("x-rpc-signgame"));val key=owner+"|"+game
   calls.add(method to key);entered.countDown();assertTrue(release.await(2,java.util.concurrent.TimeUnit.SECONDS))
   if(method=="POST"){signed.add(key);return WireResponse(obj("retcode" to 0,"data" to obj()))}
   return WireResponse(obj("retcode" to 0,"data" to obj("today" to CheckInProtocol.day(now),"is_sign" to (key in signed),"total_sign_day" to (4+if(key in signed)1 else 0))))
  }}),{now})
  fun enable(keys:List<String>){service.configure(obj("roleKeys" to JSONArray(keys),"enabled" to true))}
  fun waitJob():JSONObject {val until=System.nanoTime()+4_000_000_000L;var j=service.autoView().getJSONObject("job");while(j.optString("status") in setOf("queued","running")&&System.nanoTime()<until){Thread.sleep(10);j=service.autoView().getJSONObject("job")};assertEquals("completed",j.getString("status"));return j}
 }
 @Test fun redundantTriggerCannotDowngradeConfirmedSign(){
  val h=Harness();try{
   h.enable(keys)
   val first=h.service.requestAuto("resource_manual_refresh","a".repeat(32));assertEquals(3,first.getJSONObject("job").getInt("total"));h.waitJob()
   assertEquals(3,h.calls.count{it.first=="POST"})
   h.now+=5000
   h.service.requestAuto("resource_manual_refresh","b".repeat(32));val second=h.waitJob()
   assertEquals(3,second.getJSONObject("counts").getInt("already_signed"))
   assertEquals(0,second.getJSONObject("metrics").getInt("initial_sends"))
   assertEquals(0,second.getJSONObject("metrics").getInt("sign_sends"))
   assertEquals(3,h.calls.count{it.first=="POST"})
   for(k in keys)assertEquals("signed",h.store.loadCheckin().getJSONObject("roles").getJSONObject(k+"|CN").getString("state"))
  }finally{h.service.closeBatch()}
 }
}
