package io.github.peterfu.hoyoreminder
import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject
import org.json.JSONArray
class CheckInTest{
 private val now=java.time.Instant.parse("2026-10-24T03:00:00Z").toEpochMilli()
 private class Store:AccountPersistence{
  val a=mutableListOf(fpAccount("111",true,"abcdef1234567"),fpAccount("222",false,"bcdef12345678"),fpAccount("333",true,"cdef123456789"));var book=JSONObject()
  override fun load()=a.toList()
  override fun selected()="333"
  override fun save(account:Account,select:Boolean){a.removeAll{it.id==account.id};a.add(account)}
  override fun select(id:String?){}
  override fun remove(id:String,selected:String?){a.removeAll{it.id==id}}
  override fun installationId()="fixture"
  override fun loadCheckin()=book.copyJson()
  override fun saveCheckin(value:JSONObject){book=value.copyJson()}
 }
 private fun role(a:Account)=Role.fromSaved(a.metadata.getJSONArray("roles").getJSONObject(0))
 private fun request(s:Store,owner:String,op:String="sign")=obj("roleKeys" to JSONArray(listOf(UiPreferences.key(owner,role(s.a.first{it.id==owner})))),"operation" to op)
 @Test fun nativeConfigUsesOwnedCanonicalContextAndNoNoteDs(){
  for(game in Game.entries){
   val a=fpAccount("111",true,"abcdef1234567");val role=Role.fromSaved(a.metadata.getJSONArray("roles").objects().first{it.getString("game")==game.id})
   val t=object:Transport{override fun execute(method:String,url:String,headers:Map<String,String>,body:JSONObject?,scope:String):WireResponse{
    assertEquals("POST",method);assertEquals(CheckInProtocol.config(game).base+"sign",url);assertEquals(role.server,body!!.getString("region"));assertEquals(role.uid,body.getString("uid"))
    assertEquals(Protocol.USER_AGENT,headers["User-Agent"]);assertFalse(headers.containsKey("DS"));assertEquals("abcdef1234567",headers["x-rpc-device_fp"]);assertTrue(headers["Cookie"]!!.contains("account_id_v2=111"))
    return WireResponse(obj("retcode" to 0,"data" to JSONObject()))
   }}
   SignInClient(ApiClient(t)).call(a,role,"sign","fixture")
  }
 }
 @Test fun accountBOnlyHasGenshinAndAlreadySignedNeverPosts(){
  val s=Store();val calls=mutableListOf<String>()
  val t=object:Transport{override fun execute(method:String,url:String,headers:Map<String,String>,body:JSONObject?,scope:String):WireResponse{
   calls.add(method);assertTrue(headers["Cookie"]!!.contains("account_id_v2=222"))
   return WireResponse(obj("retcode" to 0,"data" to obj("today" to "2026-10-24","is_sign" to true,"total_sign_day" to 5)))
  }}
  val service=CheckInService(s,ApiClient(t),{now});assertEquals(0,service.view().getJSONObject("roles").length())
  val v=service.run(request(s,"222"));assertEquals(listOf("GET","GET"),calls);assertEquals(1,v.getJSONObject("roles").objectsCount())
 }
 private fun JSONObject.objectsCount()=keys().asSequence().count()
 @Test fun postSuccessRequiresFreshInfoAndDoesNotTouchNotes(){
  val s=Store();val before=s.a.map{it.metadata.toString()};var signed=false;var posts=0
  val wire=object:Transport{override fun execute(method:String,url:String,headers:Map<String,String>,body:JSONObject?,scope:String):WireResponse{
   if(method=="POST"){posts++;signed=true;return WireResponse(obj("retcode" to 0,"data" to JSONObject()))}
   return WireResponse(obj("retcode" to 0,"data" to obj("today" to "2026-10-24","is_sign" to signed,"total_sign_day" to if(signed)6 else 5)))
  }}
  val service=CheckInService(s,ApiClient(wire),{now});val v=service.run(request(s,"222"));assertEquals("signed",v.getJSONObject("roles").getJSONObject(CheckInProtocol.key("222",role(s.a[1]))).getString("state"))
  service.run(request(s,"222"));assertEquals(1,posts);assertEquals(before,s.a.map{it.metadata.toString()})
 }
 @Test fun retcodeZeroRiskAndPositive5003NeverCountAsSigned(){
  assertEquals("already_signed",CheckInProtocol.classify(-5003));assertEquals("blocked",CheckInProtocol.classify(5003))
  for(risk in listOf(obj("success" to 1),obj("risk_code" to 9),obj("is_risk" to true),obj("gt" to "fixture"),obj("challenge" to "fixture"))){
   assertTrue(CheckInProtocol.risk(risk));val s=Store()
   val wire=object:Transport{override fun execute(method:String,url:String,headers:Map<String,String>,body:JSONObject?,scope:String)=WireResponse(obj("retcode" to 0,"data" to if(method=="POST")risk else obj("today" to "2026-10-24","is_sign" to false,"total_sign_day" to 5)))}
   val v=CheckInService(s,ApiClient(wire),{now}).run(request(s,"222"))
   assertEquals("blocked",v.getJSONObject("roles").getJSONObject(CheckInProtocol.key("222",role(s.a[1]))).getString("state"))
  }
 }
 @Test fun uncertainPostIsPersistedAndNextActionQueriesWithoutReposting(){
  val s=Store();var posts=0;var gets=0
  val wire=object:Transport{override fun execute(method:String,url:String,headers:Map<String,String>,body:JSONObject?,scope:String):WireResponse{
   if(method=="POST"){posts++;throw PublicError("NETWORK_ERROR")};gets++;return WireResponse(obj("retcode" to 0,"data" to obj("today" to "2026-10-24","is_sign" to false,"total_sign_day" to 5)))
  }}
  val api=ApiClient(wire);CheckInService(s,api,{now}).run(request(s,"222"));CheckInService(s,api,{now}).run(request(s,"222"));assertEquals(1,posts);assertEquals(3,gets)
 }
 @Test fun switchesAreRoleScopedPersistedAndBackgroundNeverRunsDisabledTargets(){
  val s=Store();val calls=mutableListOf<String>()
  val wire=object:Transport{override fun execute(method:String,url:String,headers:Map<String,String>,body:JSONObject?,scope:String):WireResponse{
   calls.add(headers["Cookie"]!!);return WireResponse(obj("retcode" to 0,"data" to obj("today" to "2026-10-24","is_sign" to true,"total_sign_day" to 5)))
  }}
  CheckInService(s,ApiClient(wire),{now}).run(obj(),true);assertTrue(calls.isEmpty())
  val b=request(s,"222").put("enabled",true);CheckInService(s,ApiClient(wire),{now}).configure(b)
  CheckInService(s,ApiClient(wire),{now+500000}).run(obj(),true);assertEquals(1,calls.size);assertTrue(calls[0].contains("account_id_v2=222"))
 }
 @Test fun dayAndRewardDoNotInferClaimsOrTrustOldServerDay(){
  assertEquals("2026-10-25",CheckInProtocol.day(java.time.Instant.parse("2026-10-24T16:00:00Z").toEpochMilli()))
  val r=role(fpAccount("222"));assertEquals("unknown",CheckInProtocol.info(obj("today" to "2026-10-23","is_sign" to true,"total_sign_day" to 5),r,now).getString("state"))
  val home=CheckInProtocol.home(obj("month" to 2,"awards" to JSONArray(listOf(obj("name" to "fixture","cnt" to 1)))),"2028-02-01");assertEquals(29,home.getInt("days"));assertFalse(home.toString().contains("claimed"));assertTrue(CheckInProtocol.home(obj("month" to 2,"awards" to JSONArray(listOf(obj("cnt" to 1.5)))),"2028-02-01").getJSONArray("awards").getJSONObject(0).isNull("count"))
 }
 @Test fun negativeAlreadySignedIsVerifiedByInfoAndNeverReplayed(){
  val s=Store();var signed=false;var posts=0
  val wire=object:Transport{override fun execute(method:String,url:String,headers:Map<String,String>,body:JSONObject?,scope:String):WireResponse{
   if(method=="POST"){posts++;signed=true;return WireResponse(obj("retcode" to -5003,"data" to JSONObject()))}
   return WireResponse(obj("retcode" to 0,"data" to obj("today" to "2026-10-24","is_sign" to signed,"total_sign_day" to 1)))
  }}
  val service=CheckInService(s,ApiClient(wire),{now});val v=service.run(request(s,"222"));service.run(request(s,"222"));assertEquals(1,posts)
  assertEquals("already_signed",v.getJSONObject("roles").getJSONObject(CheckInProtocol.key("222",role(s.a[1]))).getString("state"))
 }
 @Test fun manualAndBackgroundShareGateButLocalReadDoesNotWaitBehindNetwork(){
  val s=Store();val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
  val wire=object:Transport{override fun execute(method:String,url:String,headers:Map<String,String>,body:JSONObject?,scope:String):WireResponse{
   entered.countDown();release.await(2,java.util.concurrent.TimeUnit.SECONDS);return WireResponse(obj("retcode" to 0,"data" to obj("today" to "2026-10-24","is_sign" to true,"total_sign_day" to 1)))
  }}
  val api=ApiClient(wire);val worker=Thread{CheckInService(s,api,{now}).run(request(s,"222"))};worker.start()
  assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS))
  val began=System.nanoTime();CheckInService(s,api,{now}).view();assertTrue((System.nanoTime()-began)/1000000<500)
  val queued=Thread{CheckInService(s,api,{now}).run(obj(),true)};queued.start();Thread.sleep(50);assertTrue(queued.isAlive)
  release.countDown();worker.join(2500);queued.join(2500);assertFalse(worker.isAlive);assertFalse(queued.isAlive)
 }
 @Test fun nonexistentBGameAndDuplicateTargetsNeverReachTransport(){
  val s=Store();var calls=0;val wire=object:Transport{override fun execute(method:String,url:String,headers:Map<String,String>,body:JSONObject?,scope:String):WireResponse{calls++;throw AssertionError()}}
  val service=CheckInService(s,ApiClient(wire),{now})
  for(keys in listOf(listOf("222|starrail:100000001:prod_gf_cn"),listOf("222|genshin:100000001:cn_gf01","222|genshin:100000001:cn_gf01"))){
   try{service.run(obj("roleKeys" to JSONArray(keys),"operation" to "sign"));fail()}catch(e:PublicError){assertEquals("RESPONSE_INVALID",e.code)}
  };assertEquals(0,calls)
 }

}
