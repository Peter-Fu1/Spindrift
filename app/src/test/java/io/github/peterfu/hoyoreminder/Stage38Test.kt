package io.github.peterfu.hoyoreminder
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Test
import org.junit.Assert.*
class Stage38Test {
 private val now=java.time.Instant.parse("2026-10-24T03:00:00Z").toEpochMilli()
 private class Store:AccountPersistence{
  var a=fpAccount("222",false,"bcdef12345678");var book=JSONObject()
  override fun load()=listOf(a);override fun selected()="222";override fun select(id:String?){};override fun remove(id:String,selected:String?){}
  override fun installationId()="fixture";override fun save(account:Account,select:Boolean){a=account};override fun loadCheckin()=book.copyJson();override fun saveCheckin(value:JSONObject){book=value.copyJson()}
 }
 private fun qualification(count:Int=0,balance:Int=500)=obj("rule" to "每次补签消耗150米游币","coin_cnt" to balance,"coin_cost" to 150,"resign_cnt_daily" to count,"resign_cnt_monthly" to count,"resign_limit_daily" to 1,"resign_limit_monthly" to 3,"sign_cnt_missed" to 4,"signed" to true)
 private class Harness(val store:Store,val service:CheckInService,val calls:MutableList<String>,val key:String)
 private fun fixture(mode:String="success"):Harness {
  val store=Store();val calls=mutableListOf<String>();var count=0
  val role=Role.fromSaved(store.a.metadata.getJSONArray("roles").getJSONObject(0));val key=UiPreferences.key("222",role)
  val transport=object:Transport{override fun execute(method:String,url:String,headers:Map<String,String>,body:JSONObject?,scope:String):WireResponse{
   calls.add(method+" "+url.substringBefore('?'));assertTrue(headers["Cookie"]!!.contains("account_id_v2=222"));assertEquals("bcdef12345678",headers["x-rpc-device_fp"])
   if(method=="POST"){
    assertTrue(url.endsWith("/resign"));assertEquals(setOf("act_id","region","uid"),body!!.keys().asSequence().toSet())
    if(mode=="timeout")throw PublicError("NETWORK_ERROR")
    if(mode=="risk")return WireResponse(obj("retcode" to 10041))
    count++;return WireResponse(obj("retcode" to 0,"data" to JSONObject()))
   }
   val data=if(url.contains("resign_info?"))qualification(count,500-150*count) else obj("today" to "2026-10-24","is_sign" to true,"total_sign_day" to 18+count)
   return WireResponse(obj("retcode" to 0,"data" to data))
  }}
  return Harness(store,CheckInService(store,ApiClient(transport),{now}),calls,key)
 }
 @Test fun quoteCancelAndSingleUseConfirmationNeverReplayConsumption(){
  val h=fixture();val input=obj("roleKey" to h.key);val q=h.service.prepareResign(input)
  assertTrue(q.getBoolean("eligible"));assertEquals(150,q.getInt("cost"));assertEquals(500,q.getInt("balance"));assertTrue(h.calls.none{it.startsWith("POST")})
  h.service.cancelResign(q);try{h.service.confirmResign(q.copyJson().put("roleKey",h.key).put("confirmed",true));fail()}catch(_:PublicError){}
  val second=h.service.prepareResign(input).put("roleKey",h.key).put("confirmed",true)
  val result=h.service.confirmResign(second);assertEquals("confirmed",result.getJSONObject("roles").getJSONObject(h.key+"|CN").getString("resignState"))
  try{h.service.confirmResign(second);fail()}catch(_:PublicError){};assertEquals(1,h.calls.count{it.startsWith("POST")})
 }
 @Test fun timeoutSurvivesRestartAsUncertainIntentAndDoesNotBecomeSuccess(){
  val h=fixture("timeout");val body=obj("roleKey" to h.key);val q=h.service.prepareResign(body).put("roleKey",h.key).put("confirmed",true)
  val row=h.service.confirmResign(q).getJSONObject("roles").getJSONObject(h.key+"|CN");assertTrue(row.getBoolean("resignPending"));assertEquals("unconfirmed",row.getString("resignState"))
  assertEquals("previous_result_unconfirmed",h.service.prepareResign(body).getString("reason"));assertEquals(1,h.calls.count{it.startsWith("POST")})
 }
 @Test fun officialRiskReadbackKeepsExactBusinessCode(){
  val h=fixture("risk");val q=h.service.prepareResign(obj("roleKey" to h.key)).put("roleKey",h.key).put("confirmed",true)
  val row=h.service.confirmResign(q).getJSONObject("roles").getJSONObject(h.key+"|CN");assertEquals(10041,row.getInt("resignCode"));assertNotEquals("confirmed",row.getString("resignState"));assertTrue(h.calls.last().contains("resign_info"))
 }
 @Test fun unknownBalanceConflictingCostAndInsufficientBalanceCannotAuthorize(){
  val info=obj("today" to "2026-10-24","isSign" to true,"total" to 18)
  assertTrue(ResignRules.parse(qualification().apply{remove("rule")},info,"2026-10-24").getBoolean("eligible"))
  assertFalse(ResignRules.parse(qualification(),info.copyJson().put("total",0),"2026-10-24").getBoolean("eligible"))
  for(data in listOf(qualification(balance=149),qualification().put("cost",151),qualification().put("coin_cnt",500.5),qualification().put("signed",false))){assertFalse(ResignRules.parse(data,info,"2026-10-24").getBoolean("eligible"))}
 }
 @Test fun refreshCompensationCannotExpandEmptyOrForeignRoleScope(){
  val h=fixture();h.service.configure(obj("roleKeys" to JSONArray(listOf(h.key)),"enabled" to true))
  h.service.run(obj("roleKeys" to JSONArray(),"trigger" to "resource_manual_refresh"),true);assertTrue(h.calls.isEmpty())
  h.service.run(obj("roleKeys" to JSONArray(listOf(h.key)),"trigger" to "resource_manual_refresh"),true);assertEquals(1,h.calls.size);assertTrue(h.calls.single().startsWith("GET"))
  h.calls.clear();h.service.run(obj("roleKeys" to JSONArray(listOf(h.key)),"trigger" to "resource_manual_refresh"),true){false};assertTrue(h.calls.isEmpty())
 }
 @Test fun supportedAvatarSourcesNormalizeWithoutBroadeningHosts(){
  assertEquals("https://bbs-static.miyoushe.com/avatar/avatar42.png",SafeImages.passport(obj("avatar" to "42")).url)
  assertEquals("https://upload-bbs.mihoyo.com/a.png",SafeImages.passport(obj("avatar_ext" to obj("avatar_type" to 2,"hd_resources" to JSONArray(listOf(obj("format" to 2,"url" to "//upload-bbs.mihoyo.com/a.png")))))).url)
  assertNull(SafeImages.url("https://evil.invalid/a.png"));assertNull(SafeImages.url("file:///private"));assertNull(SafeImages.url("https://upload-bbs.mihoyo.com/a.png?%74oken=private"))
 }

 @Test fun automaticProfileRepairDeduplicatesKeepsNotesAndPersistsBackoff(){
  val st=MemoryStore();st.accounts["222"]=fpAccount("222",false,"bcdef12345678")
  val before=st.accounts.getValue("222").metadata.getJSONArray("games").toString();val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1);val calls=java.util.concurrent.atomic.AtomicInteger()
  val diagnostics=Diagnostics(true)
  val wire=object:Transport{override fun execute(method:String,url:String,headers:Map<String,String>,body:JSONObject?,scope:String):WireResponse{
   calls.incrementAndGet();assertTrue(headers["Cookie"]!!.contains("account_id_v2=222"))
   if(url.contains("getUserFullInfo")){entered.countDown();release.await(2,java.util.concurrent.TimeUnit.SECONDS);return WireResponse(obj("retcode" to 0,"data" to obj("user_info" to obj("uid" to "222","nickname" to "fixture real name","avatar_url" to ""))))}
   return WireResponse(obj("retcode" to -10001))
  }}
  val repo=Repository(st,ApiClient(wire,diagnostics),{"fixture"},{now});repo.automaticProfiles=true
  try{
   repo.prepareProfiles(setOf("222"));repo.prepareProfiles(setOf("222"));assertTrue(entered.await(1,java.util.concurrent.TimeUnit.SECONDS))
   val key=UiPreferences.key("222",Role.fromSaved(st.accounts.getValue("222").metadata.getJSONArray("roles").getJSONObject(0)))
   repo.dispatch("/api/resources/estimate","POST",obj("roleKeys" to JSONArray(listOf(key))));assertEquals(1,calls.get());release.countDown()
   repeat(100){if(st.accounts.getValue("222").metadata.optString("label")!="fixture real name")Thread.sleep(10)}
   assertEquals("fixture real name",st.accounts.getValue("222").metadata.getString("label"));assertEquals(before,st.accounts.getValue("222").metadata.getJSONArray("games").toString())
   repo.prepareProfiles(setOf("222"));Thread.sleep(30);assertEquals(2,calls.get());assertTrue(diagnostics.jsonLines().contains("-10001"))
  }finally{release.countDown();repo.close()}
  val restored=Repository(st,ApiClient(wire),{"fixture"},{now});restored.automaticProfiles=true
  try{restored.prepareProfiles(setOf("222"));Thread.sleep(30);assertEquals(2,calls.get())}finally{restored.close()}
 }
}
