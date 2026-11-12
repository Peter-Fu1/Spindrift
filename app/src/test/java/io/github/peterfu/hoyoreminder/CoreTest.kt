package io.github.peterfu.hoyoreminder
import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject
import org.json.JSONArray

class CoreTest {
 private val gs=Role(Game.GENSHIN,"100000001","cn_gf01","fixture","天空岛",60)
 @Test fun uidServersAreStrictAndUnsupportedGamesAreHidden(){
  assertTrue(Role.valid(Game.GENSHIN,"500000001","cn_qd01"));assertFalse(Role.valid(Game.GENSHIN,"500000001","cn_gf01"))
  assertTrue(Role.valid(Game.STARRAIL,"100000001","prod_gf_cn"));assertTrue(Role.valid(Game.ZENLESS,"10000001","prod_gf_cn"))
  assertFalse(Role.valid(Game.ZENLESS,"100000001","prod_gf_cn"))
  assertNull(Role.fromApi(obj("game_biz" to "bh3_cn")))
 }
 @Test fun genshinDailyAndRecoveryMatchExistingContract(){
  val n=NoteParser.parse(gs,obj("current_resin" to 100,"max_resin" to 200,"resin_recovery_time" to "48000","finished_task_num" to 4,"total_task_num" to 4,"is_extra_task_reward_received" to true,"remain_resin_discount_num" to 2,"resin_discount_num_limit" to 3),123456)
  assertEquals(100,n.getInt("current"));assertEquals(200,n.getInt("max"));assertEquals(480.0,n.getDouble("interval"),0.01);assertEquals("done",n.getString("taskState"));assertEquals(2,n.getInt("weeklyRemaining"));assertEquals(123456L,n.getLong("updated"))
 }
 @Test fun starrailAndZenlessUseSameFields(){
  val sr=NoteParser.parse(gs.copy(game=Game.STARRAIL,server="prod_gf_cn"),obj("current_stamina" to 120,"max_stamina" to 300,"stamina_recover_time" to 64800,"current_train_score" to 500,"max_train_score" to 500,"current_rogue_score" to 1000,"max_rogue_score" to 18000,"weekly_cocoon_cnt" to 1,"weekly_cocoon_limit" to 3,"current_reserve_stamina" to 400))
  assertEquals(300,sr.getInt("max"));assertEquals("done",sr.getString("taskState"));assertEquals(400,sr.getInt("reserve"))
  val z=NoteParser.parse(gs.copy(game=Game.ZENLESS,uid="10000001",server="prod_gf_cn"),obj("energy" to obj("progress" to obj("current" to 20,"max" to 300),"restore" to 100800),"vitality" to obj("current" to 200,"max" to 400),"bounty_commission" to obj("num" to 2,"total" to 3)))
  assertEquals(20,z.getInt("current"));assertEquals("pending",z.getString("taskState"));assertEquals(2,z.getInt("cycleCurrent"));assertEquals(3,z.getInt("cycleMax"))
 }
 @Test fun malformedResourceNeverBecomesZeroOrSuccess(){
  try{NoteParser.parse(gs,obj("max_resin" to 200));fail()}catch(e:PublicError){assertEquals("RESPONSE_INVALID",e.code)}
  try{NoteParser.parse(gs,obj("current_resin" to 201,"max_resin" to 200));fail()}catch(e:PublicError){assertEquals("RESPONSE_INVALID",e.code)}
  val n=NoteParser.parse(gs,obj("current_resin" to 200,"max_resin" to 200));assertEquals("unknown",n.getString("taskState"))
 }
 @Test fun businessErrorsAreNotCaptchaSuccess(){
  assertEquals("account_risk",NoteParser.status(5003));assertEquals("risk_control_required",NoteParser.status(1034));assertEquals("risk_control_required",NoteParser.status(10035));assertEquals("auth_expired",NoteParser.status(10001))
 }
 @Test fun strictNoteScopeRejectsOtherRolesOriginsAndDuplicateQuery(){
  val url=Protocol.noteUrl(gs);assertTrue(Protocol.matchesNote(url,gs));assertTrue(Protocol.matchesNote(url+"&lang=zh-cn",gs))
  assertFalse(Protocol.matchesNote(url+"&role_id=999",gs));assertFalse(Protocol.matchesNote(url.replace(gs.uid,"100000002"),gs));assertFalse(Protocol.matchesNote(url.replace("https:","http:"),gs));assertFalse(Protocol.matchesNote(url.replace("mihoyo.com","mihoyo.com.evil.invalid"),gs))
  assertFalse(Protocol.trustedPage("https://webstatic.mihoyo.com@evil.invalid/"))
 }
 @Test fun ds2HasDeterministicPinnedProtocolOutput(){
  val q="role_id=100000001&server=cn_gf01";val expected="1700000000,123456,"+Protocol.md5("salt=xV8v4Qu54lUKrEYFZkJhB8cuOh9Asafs&t=1700000000&r=123456&b=&q="+q)
  assertEquals(expected,Protocol.ds2(q,1700000000,123456))
 }
 @Test fun credentialsRequireAllFieldsAndConsistentAccount(){
  val c=JSONObject();ApiClient.AUTH_NAMES.forEach{c.put(it,if(it in listOf("account_id_v2","ltuid_v2"))"111" else "fixture")}
  assertEquals("111",ApiClient.verifiedCookies(c));c.put("ltuid_v2","222");try{ApiClient.verifiedCookies(c);fail()}catch(e:PublicError){assertEquals("RESPONSE_INVALID",e.code)}
  val parsed=ApiClient.cookiesFrom(mapOf("Set-Cookie" to listOf("ltoken_v2=fixture; Domain=.mihoyo.com; Path=/; Secure; HttpOnly","ltuid_v2=222; Domain=evil.invalid")))
  assertEquals("fixture",parsed.getString("ltoken_v2"));assertFalse(parsed.has("ltuid_v2"))
 }
 @Test fun observedRequestDoesNotForwardPageDeviceIdentityOrAnotherRole(){
  var sentHeaders=emptyMap<String,String>()
  val fake=object:Transport{override fun execute(method:String,url:String,headers:Map<String,String>,body:JSONObject?,scope:String):WireResponse{sentHeaders=headers;return WireResponse(obj("retcode" to 0,"data" to JSONObject()))}}
  val client=ApiClient(fake);val secret=obj("deviceId" to "owned-device","cookies" to fpCookies("111"))
  client.observedNote(secret,gs,Protocol.noteUrl(gs),ApiClient.cookieHeader(fpCookies("111")),mapOf("x-rpc-device_id" to "foreign-device","DS" to "page-signature"),"fixture")
  assertEquals("owned-device",sentHeaders["x-rpc-device_id"]);assertEquals("page-signature",sentHeaders["DS"])
  try{client.observedNote(secret,gs,Protocol.noteUrl(gs).replace(gs.uid,"999"),"",emptyMap(),"fixture");fail()}catch(e:PublicError){assertEquals("RESPONSE_INVALID",e.code)}
 }
}
