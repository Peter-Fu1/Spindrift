package io.github.peterfu.hoyoreminder
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*

class Stage23Test {
 private val role=Role(Game.GENSHIN,"100000001","cn_gf01","fixture","fixture-region",60)
 @Test fun modernDailyTaskIsAuthoritativeWithoutLegacyFields(){
  val note=NoteParser.parse(role,obj("current_resin" to 100,"max_resin" to 200,"daily_task" to obj("finished_num" to 4,"total_num" to 4,"is_extra_task_reward_received" to true)))
  assertEquals("done",note.getString("taskState"));assertEquals(4,note.getInt("dailyCurrent"));assertEquals(4,note.getInt("dailyMax"))
  val pending=NoteParser.parse(role,obj("current_resin" to 100,"max_resin" to 200,"is_extra_task_reward_received" to true,"daily_task" to obj("is_extra_task_reward_received" to false)))
  assertEquals("pending",pending.getString("taskState"))
 }
 @Test fun jsonLinesAreParseableScopedAndDoNotExposeUnknownInput(){
  val d=Diagnostics();val a=d.trace("111",role.key(),"a".repeat(32));val b=d.trace("222",role.key(),"b".repeat(32))
  d.event("app_started",stage="startup",source="local")
  d.event("notes_request_started",role.game,Protocol.noteUrl(role)+"&token=secret",trace=a,source="native_api")
  d.event("notes_response_received",role.game,trace=b,source="secret",exceptionCategory="private-cookie")
  val rows=d.jsonLines("a".repeat(32)).trim().lines().map{JSONObject(it)}
  assertEquals(2,rows.size);assertEquals(1,rows[0].getInt("events"));assertEquals(a.requestId,rows[1].getString("request_id"))
  val all=d.jsonLines();all.trim().lines().forEach{JSONObject(it)}
  assertFalse(all.contains("secret"));assertFalse(all.contains(role.uid));assertFalse(all.contains("private-cookie"))
  try{d.jsonLines("invalid");fail()}catch(e:IllegalArgumentException){}
 }
 @Test fun startupAndRestoredRolesAreDiagnosedWithoutNetworking(){
  val store=MemoryStore();val cookies=JSONObject().apply{ApiClient.AUTH_NAMES.forEach{put(it,if(it in setOf("account_id_v2","ltuid_v2"))"111" else "fixture")}}
  store.accounts["111"]=Account("111",obj("roles" to org.json.JSONArray(listOf(role.json())),"games" to org.json.JSONArray()),obj("cookies" to cookies,"deviceId" to "private-device"))
  val d=Diagnostics();val wire=FixtureTransport();val repo=Repository(store,ApiClient(wire,d),{"fixture"})
  try{val rows=d.snapshot().getJSONArray("events").objects();assertTrue(rows.any{it.getString("event")=="account_restored"});assertTrue(rows.any{it.getString("event")=="role_resolved"});assertEquals(0,wire.notes);assertNull(repo.diagnosticRefreshId());assertFalse(d.jsonLines().contains("private-device"))}finally{repo.close()}
 }
 @Test fun missingSuccessfulNotePayloadFailsAtParseAndLoginNetworkFailureKeepsCategory(){
  val store=MemoryStore();val cookies=JSONObject().apply{ApiClient.AUTH_NAMES.forEach{put(it,if(it in setOf("account_id_v2","ltuid_v2"))"111" else "fixture")}}
  store.accounts["111"]=Account("111",obj("roles" to org.json.JSONArray(listOf(role.json())),"games" to org.json.JSONArray()),obj("cookies" to cookies,"deviceId" to "private-device"))
  val d=Diagnostics();val wire=object:Transport{override fun execute(method:String,url:String,headers:Map<String,String>,body:JSONObject?,scope:String)=WireResponse(obj("retcode" to 0,"data" to JSONObject.NULL))}
  val repo=Repository(store,ApiClient(wire,d),{"fixture"})
  try{
   repo.startRefresh("a".repeat(32),"manual")
   repeat(200){if(repo.dispatch("/api/accounts/refresh/status","GET",JSONObject()).getJSONObject("refresh").getString("status")=="running")Thread.sleep(10)}
   val row=repo.dispatch("/api/accounts/refresh/status","GET",JSONObject()).getJSONObject("refresh").getJSONArray("roles").getJSONObject(0)
   assertEquals("response_invalid",row.getString("error"));assertEquals("parse",row.getString("failureStage"))
   assertFalse(d.snapshot().getJSONArray("events").objects().any{it.getString("event")=="notes_committed"})
  }finally{repo.close()}
  val login=ApiClient(object:Transport{override fun execute(method:String,url:String,headers:Map<String,String>,body:JSONObject?,scope:String):WireResponse{throw PublicError("NETWORK_ERROR")}},d)
  try{login.createQr("private-device","qr");fail()}catch(e:PublicError){}
  assertEquals("network_error",d.snapshot().getJSONArray("events").objects().last().getString("exception_category"))
  assertEquals("auth_expired",publicExceptionCategory(PublicError("AUTH_EXPIRED")))
 }
}
