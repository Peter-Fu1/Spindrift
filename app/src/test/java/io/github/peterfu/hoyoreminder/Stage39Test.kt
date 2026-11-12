package io.github.peterfu.hoyoreminder
import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject
import org.json.JSONArray
class Stage39Test {
 private val now=java.time.Instant.parse("2026-10-25T03:00:00Z").toEpochMilli()
 private class Store:AccountPersistence{
  val accounts=mutableListOf(fpAccount("111",true,"abcdef1234567"),fpAccount("222",false,"bcdef12345678"),fpAccount("333",true,"cdef123456789"));var book=JSONObject()
  override fun load()=accounts.toList();override fun selected()="111";override fun select(id:String?){};override fun remove(id:String,selected:String?){accounts.removeAll{it.id==id}}
  override fun installationId()="fixture";override fun save(account:Account,select:Boolean){accounts.removeAll{it.id==account.id};accounts.add(account)}
  override fun loadCheckin()=book.copyJson();override fun saveCheckin(value:JSONObject){book=value.copyJson()}
 }
 private fun keys(s:Store)=s.accounts.flatMap{a->a.metadata.getJSONArray("roles").objects().map{UiPreferences.key(a.id,Role.fromSaved(it))}}
 private fun waitDone(service:CheckInService):JSONObject {val until=System.nanoTime()+3000000000;while(service.batchView().getJSONObject("job").getString("status")=="running"&&System.nanoTime()<until)Thread.sleep(10);return service.batchView().also{assertNotEquals("running",it.getJSONObject("job").getString("status"))}}
 @Test fun allSubsetAndEmptyUseExactReadOnlyScope(){
  val s=Store();var infos=0;var posts=0
  val wire=object:Transport{override fun execute(method:String,url:String,headers:Map<String,String>,body:JSONObject?,scope:String):WireResponse{
   if(method=="POST")posts++;if(url.contains("/info?"))infos++
   return WireResponse(obj("retcode" to 0,"data" to obj("today" to "2026-10-25","is_sign" to true,"total_sign_day" to 18)))
  }}
  val service=CheckInService(s,ApiClient(wire),{now});val all=keys(s);assertEquals(7,all.size)
  for(chosen in listOf(all.take(1),listOf(all[0],all[3],all[6]),all,emptyList())){infos=0;service.startBatch(obj("roleKeys" to JSONArray(chosen),"operation" to "info"));assertEquals(chosen.size,waitDone(service).getJSONObject("job").getInt("total"));assertEquals(chosen.size,infos)}
  assertEquals(0,posts);service.closeBatch()
 }
 @Test fun cancellationWhileInfoInFlightPreventsSignAndNextRole(){
  val s=Store();val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1);var calls=0
  val wire=object:Transport{override fun execute(method:String,url:String,headers:Map<String,String>,body:JSONObject?,scope:String):WireResponse{calls++;assertEquals("GET",method);entered.countDown();release.await(2,java.util.concurrent.TimeUnit.SECONDS);return WireResponse(obj("retcode" to 0,"data" to obj("today" to "2026-10-25","is_sign" to false,"total_sign_day" to 18)))}}
  val service=CheckInService(s,ApiClient(wire),{now});val job=service.startBatch(obj("roleKeys" to JSONArray(keys(s)),"operation" to "sign")).getJSONObject("job");assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS));service.cancelBatch(job);release.countDown();assertEquals("cancelled",waitDone(service).getJSONObject("job").getString("status"));assertEquals(1,calls);service.closeBatch()
 }
 @Test fun fixedFeeNeedsLiveTypedEligibilityButNotOptionalBalanceOrPrice(){
  val info=obj("today" to "2026-10-25","isSign" to true,"total" to 18)
  val q=obj("resign_cnt_daily" to 0,"resign_cnt_monthly" to 0,"resign_limit_daily" to 1,"resign_limit_monthly" to 3,"sign_cnt_missed" to 4,"signed" to true)
  val result=ResignRules.parse(q,info,"2026-10-25");assertTrue(result.getBoolean("eligible"));assertEquals(150,result.getInt("cost"));assertTrue(result.isNull("balance"))
  for(bad in listOf(q.copyJson().put("coin_cost",151),q.copyJson().put("coin_cnt",149),q.copyJson().put("resign_cnt_monthly",3),q.copyJson().put("resign_cnt_daily",1),q.copyJson().put("sign_cnt_missed",0),q.copyJson().apply{remove("resign_cnt_monthly")})){assertFalse(ResignRules.parse(bad,info,"2026-10-25").getBoolean("eligible"))}
 }
}
