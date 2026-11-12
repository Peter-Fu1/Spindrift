package io.github.peterfu.hoyoreminder
import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject
import org.json.JSONArray
class Stage40Test {
 private val now=java.time.Instant.parse("2026-10-25T03:00:00Z").toEpochMilli()
 private class Store:AccountPersistence {
  val accounts=mutableListOf(fpAccount("111",true,"abcdef1234567"),fpAccount("222",false,"bcdef12345678"),fpAccount("333",true,"cdef123456789"));var book=JSONObject()
  override fun load()=accounts.toList();override fun selected()="222";override fun select(id:String?){};override fun remove(id:String,selected:String?){accounts.removeAll{it.id==id}}
  override fun installationId()="fixture";override fun save(account:Account,select:Boolean){accounts.removeAll{it.id==account.id};accounts.add(account)}
  override fun loadCheckin()=book.copyJson();override fun saveCheckin(value:JSONObject){book=value.copyJson()}
 }
 private fun qual()=obj("resign_cnt_daily" to 0,"resign_cnt_monthly" to 0,"resign_limit_daily" to 1,"resign_limit_monthly" to 3,"sign_cnt_missed" to 4,"signed" to true)
 private fun info()=obj("today" to "2026-10-25","isSign" to true,"total" to 18)
 @Test fun zeroPlaceholderAliasesNestedAndTypedMissingRemainDistinct(){
  assertTrue(listOf(0,150).any{it!=150}) // old condition directly reproduces the false conflict
  for(game in Game.entries)for(data in listOf(qual(),qual().put("coin_cost",0).put("cost",150),obj("resign_info" to qual().put("coin_cost","150")),qual().put("resign_limit_monthly",4))){val value=ResignRules.parse(data,info(),"2026-10-25");assertTrue(game.id+value.toString(),value.getBoolean("eligible"));assertEquals(150,value.getInt("cost"));assertTrue(value.isNull("balance"))}
  for((data,reason) in listOf(qual().put("cost",151) to "official_explicit_conflict",qual().put("resign_limit_daily",0) to "quota_exhausted",qual().put("resign_cnt_monthly",3) to "quota_exhausted",qual().put("coin_cnt",0) to "insufficient_balance",qual().apply{remove("resign_cnt_monthly")} to "missing_required_evidence")){val value=ResignRules.parse(data,info(),"2026-10-25");assertEquals(reason,value.getString("reason"));assertFalse(value.getBoolean("eligible"))}
 }
 @Test fun oneConfigStorePersistsAllSevenWithoutAnyOfficialRequest(){
  val s=Store();var requests=0;val wire=object:Transport{override fun execute(method:String,url:String,headers:Map<String,String>,body:JSONObject?,scope:String):WireResponse{requests++;throw AssertionError()}}
  val service=CheckInService(s,ApiClient(wire),{now});val keys=s.accounts.flatMap{a->a.metadata.getJSONArray("roles").objects().map{UiPreferences.key(a.id,Role.fromSaved(it))}}
  val first=service.configure(obj("roleKeys" to JSONArray(keys),"enabled" to true));assertEquals(1,first.getInt("configurationRevision"));assertEquals(7,first.getJSONObject("roles").keys().asSequence().count{first.getJSONObject("roles").getJSONObject(it).getBoolean("enabled")})
  val restarted=CheckInService(s,ApiClient(wire),{now});assertEquals(1,restarted.view().getInt("configurationRevision"));restarted.configure(obj("roleKeys" to JSONArray(keys),"enabled" to false));assertEquals(2,restarted.view().getInt("configurationRevision"));assertEquals(0,requests)
 }
 private fun waitProfile(repo:Repository):JSONObject {val limit=System.nanoTime()+4_000_000_000L;var view=repo.dispatch("/api/accounts/profile/batch/status","GET",obj());while(view.getJSONObject("job").getString("status")=="running"&&System.nanoTime()<limit){Thread.sleep(10);view=repo.dispatch("/api/accounts/profile/batch/status","GET",obj())};assertNotEquals("running",view.getJSONObject("job").getString("status"));return view}
 @Test fun profileBatchTouchesThreeOwnersSevenRolesAndReusesHealthySources(){
  val s=Store();val calls=java.util.Collections.synchronizedList(mutableListOf<String>());val owners=mutableSetOf<String>();val diagnostics=Diagnostics(true)
  val wire=object:Transport{override fun execute(method:String,url:String,headers:Map<String,String>,body:JSONObject?,scope:String):WireResponse{
   val owner=Regex("account_id_v2=([0-9]+)").find(headers.getValue("Cookie"))!!.groupValues[1];owners.add(owner);calls.add(url);assertEquals("GET",method)
   if(url.contains("getUserFullInfo"))return WireResponse(obj("retcode" to 0,"data" to obj("user_info" to obj("uid" to owner,"nickname" to "Name "+owner,"avatar_url" to "https://upload-bbs.mihoyo.com/"+owner+".png"))))
   val uid=url.substringAfter("role_id=").substringBefore('&');return WireResponse(obj("retcode" to 0,"data" to obj("role" to obj("uid" to uid,"nickname" to "Role "+owner),"cur_head_icon_url" to "https://upload-bbs.mihoyo.com/"+owner+"/role.png")))
  }}
  val repo=Repository(s,ApiClient(wire,diagnostics),{"fixture"},{now})
  try{repo.dispatch("/api/accounts/profile/batch/start","POST",obj());val result=waitProfile(repo);assertEquals(3,result.getJSONObject("job").getInt("total"));assertEquals(7,result.getJSONObject("job").getInt("roles"));assertEquals(10,calls.size);assertEquals(setOf("111","222","333"),owners);val before=calls.size;repo.dispatch("/api/accounts/profile/batch/start","POST",obj());waitProfile(repo);assertEquals(before,calls.size)}finally{repo.close()}
 }
 @Test fun resignDiagnosticSchemaCannotWriteResponseIdentityOrBalance(){
  val d=Diagnostics(true);d.resign("resign_rule_evaluated",d.trace("private-owner","private-role","private-operation"),"parsed","known_standard_no_dynamic_price",schema="resign_info",mask=1)
  val row=d.snapshot().getJSONArray("events").getJSONObject(0);assertEquals("known_standard_no_dynamic_price",row.getString("decision"));for(k in listOf("balance","coin_cnt","uid","Cookie","deviceFP","roleName"))assertFalse(row.has(k));assertFalse(row.toString().contains("private-owner"))
 }
}
