package io.github.peterfu.hoyoreminder
import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject
import org.json.JSONArray

class Stage29Test {
 private val role=Role(Game.GENSHIN,"100000001","cn_gf01","fixture","fixture",60)
 private fun secret(id:String="111")=obj("deviceId" to "stable","profileKey" to "stable","cookies" to JSONObject().apply{ApiClient.AUTH_NAMES.forEach{put(it,if(it in setOf("account_id_v2","ltuid_v2"))id else "fixture")}})
 private fun response(data:Any=obj("gt" to "fixture","challenge" to "upstream"),mmt:Any=1)=WireResponse(obj("retcode" to 1034),mapOf("X-Rpc-Aigis" to listOf(obj("session_id" to "private-session","mmt_type" to mmt,"data" to data).toString())))
 @Test fun diagnosticAndExecutableParserAgreeForBothOfficialDataEncodings(){
  for(data in listOf<Any>(obj("gt" to "fixture","challenge" to "upstream"),obj("gt" to "fixture","risk_type" to "slide").toString())){
   val wire=response(data);val context=VerificationContext.inspect(wire)
   assertEquals("aigis",context.kind);assertTrue(context.aigisParseValid);assertNotNull(NoteChallenge.parse(wire))
  }
 }
 @Test fun duplicateAndMalformedAigisArePresentButNeverExecutable(){
  val valid=response()
  val duplicate=valid.copy(headers=mapOf("x-rpc-aigis" to valid.headers.values.flatten(),"X-Rpc-Aigis" to valid.headers.values.flatten()))
  for(wire in listOf(duplicate,response("not-json"),response(obj("private" to "must-not-log")),response(mmt=-1),response(mmt=1.5),response(mmt="1"))){
   assertEquals("malformed",VerificationContext.inspect(wire).kind)
   assertTrue(VerificationContext.inspect(wire).aigisPresent)
   assertFalse(VerificationContext.inspect(wire).aigisParseValid)
   assertNull(NoteChallenge.parse(wire))
  }
  val newline=WireResponse(obj("retcode" to 1034),mapOf("x-rpc-aigis" to listOf(obj("session_id" to "private\nvalue","mmt_type" to 1,"data" to obj("gt" to "fixture")).toString())))
  assertNull(NoteChallenge.parse(newline))
 }
 @Test fun partialLegacyFieldsAreMalformedRatherThanAbsent(){
  val wire=WireResponse(obj("retcode" to 1034,"data" to obj("gt" to "fixture")))
  assertEquals("malformed",VerificationContext.inspect(wire).kind)
  assertFalse(VerificationContext.inspect(wire).legacyPresent)
 }
 @Test fun actualAigisCannotBeAppliedToAnotherAccountOrRole(){
  val challenge=NoteChallenge.parse(response(),owner="111",role=role)!!
  val api=ApiClient(object:Transport{override fun execute(method:String,url:String,headers:Map<String,String>,body:JSONObject?,scope:String):WireResponse=throw AssertionError("must not send")})
  val proof=obj("geetest_challenge" to "answer","geetest_validate" to "validate","geetest_seccode" to "seccode")
  for(pair in listOf(secret("222") to role,secret() to role.copy(uid="100000002"))){
   try{api.answerChallenge(pair.first,pair.second,challenge,proof,"scope",api.diagnostics.trace("111",role.key(),"f".repeat(32)));fail()}catch(e:PublicError){assertEquals("RESPONSE_INVALID",e.code)}
  }
 }
 @Test fun manualCancellationHasTerminalStateAndDiscardsLateCommit(){
  val store=MemoryStore()
  val old=obj("id" to "genshin","roleUid" to role.uid,"status" to "ok","current" to 19,"updated" to 12,"retcode" to 5003)
  store.accounts["111"]=Account("111",obj("roles" to JSONArray(listOf(role.json())),"games" to JSONArray(listOf(old))),secret())
  val repo=Repository(store,ApiClient(object:Transport{override fun execute(method:String,url:String,headers:Map<String,String>,body:JSONObject?,scope:String):WireResponse{assertTrue(url.contains("createVerification"));return WireResponse(obj("retcode" to 0,"data" to obj("gt" to "fixture","challenge" to "upstream")))}}),{"fixture"})
  var late:((VerifiedNote?,String)->Boolean)?=null
  repo.verifier=object:VerificationPort{
   override fun supportsRecordChallenges()=true
   override fun available(request:VerificationRequest)=request.challenge!=null
   override fun open(request:VerificationRequest,done:(VerifiedNote?,String)->Boolean){late=done}
   override fun close(scope:String){};override fun focus(){}
  }
  try{
   assertTrue(repo.dispatch("/api/accounts/verify","POST",obj("accountId" to "111","game" to "genshin","roleUid" to role.uid)).getBoolean("opened"))
   repo.dispatch("/api/accounts/verification/cancel","POST",JSONObject())
   assertEquals("cancelled",store.accounts["111"]!!.metadata.getJSONObject("verification").getString("state"))
   assertFalse(late!!(VerifiedNote(obj("status" to "ok"),JSONObject()),"ok"))
   assertEquals(19,store.accounts["111"]!!.metadata.getJSONArray("games").getJSONObject(0).getInt("current"))
  }finally{repo.close()}
 }
 @Test fun browserObservationsNeverExportRawUaOrUnknownWindow(){
  val d=Diagnostics()
  d.browser("private-window","Android Chrome/155.0.1 private-token")
  val rows=d.snapshot().getJSONArray("events");val row=rows.getJSONObject(0)
  assertTrue(row.isNull("window_kind"));assertEquals("android",row.getString("ua_platform"));assertEquals(155,row.getInt("ua_chromium_major"))
  assertFalse(d.jsonLines().contains("private"))
 }
}
