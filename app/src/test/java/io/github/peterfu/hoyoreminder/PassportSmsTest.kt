package io.github.peterfu.hoyoreminder
import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject
class PassportSmsTest{
 @Test fun fullFlowKeepsLeadingZeroOtpExchangesCredentialsThenCommitsOnce(){
  val stages=mutableListOf<String>();var logins=0
  val wire=object:Transport{override fun execute(method:String,url:String,headers:Map<String,String>,body:JSONObject?,scope:String):WireResponse{
   stages.add(url.substringAfter(".com/").substringBefore("?"))
   assertFalse(headers.containsKey("DS"))
   if(url.contains("createLoginCaptcha")){
    assertEquals("POST",method);assertNotEquals("13800000000",body!!.getString("mobile"));assertEquals(128,java.util.Base64.getDecoder().decode(body.getString("mobile")).size)
    return WireResponse(obj("retcode" to 0,"data" to obj("action_type" to "fixture")))
   }
   if(url.contains("loginByMobileCaptcha")){logins++;assertEquals("001234",body!!.getString("captcha"));return WireResponse(obj("retcode" to 0,"data" to obj("token" to obj("token" to "fixture-stoken"),"user_info" to obj("aid" to "222","mid" to "fixture-mid"))))}
   if(url.contains("getLToken"))return WireResponse(obj("retcode" to 0,"data" to obj("ltoken" to "fixture-ltoken")))
   return WireResponse(obj("retcode" to 0,"data" to obj("uid" to "222","cookie_token" to "fixture-cookie")))
  }}
  val sms=PassportSms(ApiClient(wire));val id=sms.send(obj("phone" to "13800000000")).getString("smsId")
  var commits=0
  val result=sms.login(obj("smsId" to id,"code" to "001234")){cookies,_,device,valid->assertTrue(device.isNotBlank());assertTrue(valid());assertEquals("222",ApiClient.verifiedCookies(cookies));assertFalse(cookies.has("stoken"));commits++;obj("status" to "confirmed")}
  assertEquals("confirmed",result.getString("status"));assertEquals(1,commits);assertEquals(1,logins);assertEquals(4,stages.size);assertTrue(sms.status().getBoolean("completed"));sms.acknowledge(id);assertTrue(sms.status().getBoolean("expired"))
 }
 @Test fun officialChallengeMustBeAnsweredBySameSessionAndCannotFakeSmsSent(){
  var sends=0
  val wire=object:Transport{override fun execute(method:String,url:String,headers:Map<String,String>,body:JSONObject?,scope:String):WireResponse{
   sends++;if(sends==1)return WireResponse(obj("retcode" to -3101),mapOf("x-rpc-aigis" to listOf(obj("session_id" to "fixture-session","data" to obj("gt" to "fixture-captcha","risk_type" to "slide")).toString())))
   assertTrue(headers["x-rpc-aigis"]!!.startsWith("fixture-session;"));return WireResponse(obj("retcode" to 0,"data" to obj("action_type" to "fixture")))
  }}
  val sms=PassportSms(ApiClient(wire));val state=sms.send(obj("phone" to "13800000000"));val id=state.getString("smsId");assertFalse(state.getBoolean("sent"));assertTrue(state.getBoolean("challenge"))
  val proof=obj("captcha_id" to "fixture-captcha","lot_number" to "fixture","pass_token" to "fixture","gen_time" to "fixture","captcha_output" to "fixture")
  assertTrue(sms.send(obj("smsId" to id),proof).getBoolean("sent"));assertEquals(2,sends)
  sms.cancel();try{sms.login(obj("smsId" to id,"code" to "001234")){_,_,_,_->fail();JSONObject()};fail()}catch(e:PublicError){assertEquals("CANCELLED",e.code)}
 }
 @Test fun cancellationRejectsLateSendAndExpiryDoesNotReuseOldPhone(){
  val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
  val wire=object:Transport{override fun execute(method:String,url:String,headers:Map<String,String>,body:JSONObject?,scope:String):WireResponse{
   entered.countDown();release.await(2,java.util.concurrent.TimeUnit.SECONDS);return WireResponse(obj("retcode" to 0,"data" to obj("action_type" to "fixture")))
  }}
  val sms=PassportSms(ApiClient(wire));var rejected=false
  val thread=Thread{try{sms.send(obj("phone" to "13800000000"))}catch(e:PublicError){rejected=e.code=="CANCELLED"}}
  thread.start();assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS));sms.cancel();release.countDown();thread.join(2500);assertTrue(rejected);assertTrue(sms.status().getBoolean("expired"))
 }

 @Test fun staleCancelCannotAbortReplacementAndCompletedResultSurvivesUntilUiAck(){
  var clock=100000L;var posts=0
  val wire=object:Transport{override fun execute(method:String,url:String,headers:Map<String,String>,body:JSONObject?,scope:String):WireResponse{
   if(url.contains("createLoginCaptcha"))return WireResponse(obj("retcode" to 0,"data" to obj("action_type" to "fixture")))
   if(url.contains("loginByMobileCaptcha")){posts++;return WireResponse(obj("retcode" to 0,"data" to obj("token" to obj("token" to "s"),"user_info" to obj("aid" to "232","mid" to "m"))))}
   return WireResponse(obj("retcode" to 0,"data" to if(url.contains("getLToken"))obj("ltoken" to "l") else obj("uid" to "232","cookie_token" to "c")))
  }}
  val diagnostics=Diagnostics(true);val sms=PassportSms(ApiClient(wire,diagnostics)){clock}
  val old=sms.send(obj("phone" to "13800000000")).getString("smsId");clock+=61000
  val current=sms.send(obj("phone" to "13800000001")).getString("smsId");sms.cancel(old,"navigation")
  var commits=0;val input=obj("smsId" to current,"code" to "001234")
  val result=sms.login(input){_,_,_,valid->assertTrue(valid());commits++;obj("status" to "confirmed")}
  sms.cancel(current,"user");assertTrue(sms.status().getBoolean("completed"));assertEquals(result.toString(),sms.login(input){_,_,_,_->fail();JSONObject()}.toString())
  assertEquals(1,posts);assertEquals(1,commits);sms.acknowledge(current);assertTrue(sms.status().getBoolean("expired"))
  val lines=diagnostics.jsonLines();assertTrue(lines.contains("login_ui_ack"));assertFalse(lines.contains("13800000001"));assertFalse(lines.contains(current))
 }
 @Test fun statusRemainsReadableWhileCredentialExchangeIsBlocked(){
  val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
  val wire=object:Transport{override fun execute(method:String,url:String,headers:Map<String,String>,body:JSONObject?,scope:String):WireResponse{
   if(url.contains("createLoginCaptcha"))return WireResponse(obj("retcode" to 0,"data" to obj("action_type" to "fixture")))
   entered.countDown();release.await(2,java.util.concurrent.TimeUnit.SECONDS);return WireResponse(obj("retcode" to -100))
  }}
  val sms=PassportSms(ApiClient(wire));val id=sms.send(obj("phone" to "13800000000")).getString("smsId")
  val t=Thread{runCatching{sms.login(obj("smsId" to id,"code" to "001234")){_,_,_,_->JSONObject()}}};t.start();assertTrue(entered.await(1,java.util.concurrent.TimeUnit.SECONDS))
  val start=System.nanoTime();assertEquals("login_confirm",sms.status().getString("phase"));assertTrue(System.nanoTime()-start<100000000);release.countDown();t.join(2500)
 }

 @Test fun acquiredCredentialsCanResumeLocalSaveWithoutAnotherOtpOrOfficialLogin(){
  var confirmations=0
  val wire=object:Transport{override fun execute(method:String,url:String,headers:Map<String,String>,body:JSONObject?,scope:String):WireResponse{
   assertEquals("Spindrift",headers["x-rpc-device_name"])
   assertFalse(headers.containsKey("x-rpc-device_model"))
   assertEquals("2",headers["x-rpc-client_type"]);assertEquals("HoYoReminder-Windows/0.2",headers["User-Agent"])
   if(url.contains("createLoginCaptcha"))return WireResponse(obj("retcode" to 0,"data" to obj("action_type" to "fixture")))
   if(url.contains("loginByMobileCaptcha")){confirmations++;return WireResponse(obj("retcode" to 0,"data" to obj("token" to obj("token" to "s"),"user_info" to obj("aid" to "234","mid" to "m"))))}
   return WireResponse(obj("retcode" to 0,"data" to if(url.contains("getLToken"))obj("ltoken" to "l") else obj("uid" to "234","cookie_token" to "c")))
  }}
  val sms=PassportSms(ApiClient(wire));val id=sms.send(obj("phone" to "13800000000")).getString("smsId")
  try{sms.login(obj("smsId" to id,"code" to "001234")){_,_,_,_->throw PublicError("SAVE_FAILED")};fail()}catch(e:PublicError){assertEquals("SAVE_FAILED",e.code)}
  assertTrue(sms.status().getBoolean("authenticated"));assertEquals("role_discovery",sms.status().getString("phase"))
  assertEquals("confirmed",sms.login(obj("smsId" to id,"code" to "")){_,_,_,valid->assertTrue(valid());obj("status" to "confirmed")}.getString("status"));assertEquals(1,confirmations)
 }

 @Test fun failedInitialSendReleasesUnknownSessionWithoutRetrying(){
  var sent=0
  val wire=object:Transport{override fun execute(method:String,url:String,headers:Map<String,String>,body:JSONObject?,scope:String):WireResponse{sent++;throw PublicError("NETWORK_ERROR")}}
  val sms=PassportSms(ApiClient(wire))
  try{sms.send(obj("phone" to "13800000000"));fail()}catch(e:PublicError){assertEquals("NETWORK_ERROR",e.code)}
  assertTrue(sms.status().getBoolean("expired"));assertEquals(1,sent)
 }
}
