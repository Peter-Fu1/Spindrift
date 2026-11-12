package io.github.peterfu.hoyoreminder
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.json.JSONArray
import org.json.JSONTokener
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
@RunWith(AndroidJUnit4::class)
class SmsRotationTest {
 private fun find(v:View):WebView?=if(v is WebView)v else if(v is ViewGroup)(0 until v.childCount).firstNotNullOfOrNull{find(v.getChildAt(it))} else null
 private fun js(w:WebView,s:String):Any?{val ins=InstrumentationRegistry.getInstrumentation();val latch=CountDownLatch(1);var value:Any?=null;ins.runOnMainSync{w.evaluateJavascript(s){value=runCatching{JSONTokener(it).nextValue()}.getOrNull();latch.countDown()}};assertTrue(latch.await(10,TimeUnit.SECONDS));return value}
 private fun ready(w:WebView){repeat(100){if(js(w,"document.querySelector('#groups')?.getAttribute('aria-busy')==='false'")==true)return;Thread.sleep(100)};fail("UI readiness deadline")}
 @Test fun successfulExchangeCanFinishAcrossActualActivityRecreationAndPersist(){
  val ins=InstrumentationRegistry.getInstrumentation();val context=ins.targetContext;val entered=CountDownLatch(1);val release=CountDownLatch(1);var posts=0
  val owner="888802";val others=listOf("888801","888803")
  val wire=object:Transport{override fun execute(method:String,url:String,headers:Map<String,String>,body:JSONObject?,scope:String):WireResponse{
   val data=when{
    url.contains("createLoginCaptcha")->obj("action_type" to "fixture")
    url.contains("loginByMobileCaptcha")-> {posts++;assertEquals("001234",body!!.getString("captcha"));obj("token" to obj("token" to "fixture-s"),"user_info" to obj("aid" to owner,"mid" to "fixture-mid"))}
    url.contains("getLToken")->obj("ltoken" to "fixture-l")
    url.contains("getCookieAccount")-> {entered.countDown();assertTrue(release.await(20,TimeUnit.SECONDS));obj("uid" to owner,"cookie_token" to "fixture-c")}
    url.contains("getUserGameRolesByCookie")->obj("list" to JSONArray(listOf(obj("game_biz" to "hk4e_cn","game_uid" to "100000001","region" to "cn_gf01","region_name" to "天空岛","nickname" to "fixture role","level" to 60))))
    url==DeviceFingerprint.ENDPOINT->obj("code" to 200,"device_fp" to "abcdef1234567")
    url.contains("dailyNote")->obj("current_resin" to 100,"max_resin" to 200,"resin_recovery_time" to 48000)
    url.contains("getUserFullInfo")-> {val uid=Regex("account_id_v2=([0-9]+)").find(headers["Cookie"].orEmpty())!!.groupValues[1];obj("user_info" to obj("uid" to uid,"nickname" to "fixture nickname","avatar_url" to ""))}
    else->JSONObject() // no image source: cannot download any CDN in this test
   };return WireResponse(obj("retcode" to 0,"data" to data))
  }}
  val store=AccountStore(context)
  for(id in others){val cookies=JSONObject();ApiClient.AUTH_NAMES.forEach{cookies.put(it,if(it in setOf("account_id_v2","ltuid_v2"))id else "fixture")};store.save(Account(id,obj("label" to "fixture","roles" to JSONArray(),"games" to JSONArray()),obj("cookies" to cookies,"deviceId" to "fixture-"+id,"deviceFp" to "bcdef12345678","profileKey" to "fixture-"+id)))}
  val field=SmsLifetime::class.java.getDeclaredField("client").apply{isAccessible=true};val previous=field.get(null);val sms=PassportSms(ApiClient(wire,RuntimeDiagnostics.value));field.set(null,sms);MainActivity.transportFactory={wire}
  var activity=ins.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
  try{
   var web:WebView?=null;ins.runOnMainSync{web=find(activity.window.decorView)};ready(web!!)
   js(web!!,"document.querySelector('#login').showModal();document.querySelector('#smsTab').click();document.querySelector('#phone').value='13800000000';document.querySelector('#sendSms').click();true")
   repeat(60){if(js(web!!,"document.querySelector('#smsStatus').textContent.includes('已接受')")!=true)Thread.sleep(100)}
   js(web!!,"document.querySelector('#smsCode').value='001234';document.querySelector('#smsLogin').click();true")
   assertTrue(entered.await(10,TimeUnit.SECONDS));assertEquals("cookie_exchange",sms.status().getString("phase"))
   val monitor=ins.addMonitor(MainActivity::class.java.name,null,false);ins.runOnMainSync{activity.recreate()};val recreated=ins.waitForMonitorWithTimeout(monitor,15000) as? MainActivity;ins.removeMonitor(monitor);assertNotNull(recreated);activity=recreated!!
   ins.runOnMainSync{web=find(activity.window.decorView)};ready(web!!);release.countDown()
   var saved=false
   repeat(120){if(js(web!!,"state.accounts.some(a=>a.id==='888802')&&!document.querySelector('#login').open")==true)saved=true;if(!saved)Thread.sleep(100)}
   assertTrue("SMS account/UI ACK missing after rotation",saved);assertEquals(1,posts)
   assertNotNull(store.load().firstOrNull{it.id==owner}?.secret)
   for(id in others)assertEquals("bcdef12345678",store.load().first{it.id==id}.secret!!.getString("deviceFp"))
   assertTrue(RuntimeDiagnostics.value.jsonLines().contains("login_ui_ack"))
   ins.runOnMainSync{activity.finish()};ins.waitForIdleSync();activity=ins.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
   ins.runOnMainSync{web=find(activity.window.decorView)};ready(web!!);assertEquals(true,js(web!!,"state.accounts.some(a=>a.id==='888802')"))
  }finally{release.countDown();ins.runOnMainSync{activity.finish()};MainActivity.transportFactory=null;field.set(null,previous);for(id in others+owner)store.remove(id,null);store.close()}
 }
}
