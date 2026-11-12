package io.github.peterfu.hoyoreminder

import android.content.Intent
import android.webkit.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real engine/profile/cookie ACK; all resources intercepted offline. No live account or endpoint traffic. */
@RunWith(AndroidJUnit4::class)
class FingerprintWebViewTest {
 private fun cookies(id:String)=JSONObject().apply{ApiClient.AUTH_NAMES.forEach{put(it,if(it in setOf("account_id_v2","ltuid_v2"))id else "fixture-"+id)}}
 private fun secret(id:String,fp:String)=obj("cookies" to cookies(id),"deviceId" to "fixture-"+id,"deviceFp" to fp,
  "deviceFpContext" to DeviceFingerprint.identity(id,"fixture-"+id,null,{JSONObject()},1800000000000))
 @Test fun officialUaAndFpAreAppliedBeforeFirstLoadAndProfileReuse(){
  val instrumentation=InstrumentationRegistry.getInstrumentation()
  val activity=instrumentation.startActivitySync(Intent(instrumentation.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
  val names=List(2){"fp-test-"+UUID.randomUUID()}
  var web:WebView?=null
  try{
   instrumentation.runOnMainSync{assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE))}
   val sessions=listOf(secret("111","1111111111111"),secret("222","2222222222222"),secret("111","aaaaaaaaaaaaa"))
   for((index,session) in sessions.withIndex()){
    val loaded=CountDownLatch(1);val fault=AtomicReference<Throwable?>();val profileName=names[if(index==1)1 else 0]
    instrumentation.runOnMainSync{
     val view=WebView(activity);web=view
     WebViewCompat.setProfile(view,profileName);OfficialWebSettings.apply(view);view.settings.javaScriptEnabled=true
     val manager=WebViewCompat.getProfile(view).cookieManager
     view.webViewClient=object:WebViewClient(){
      override fun shouldInterceptRequest(v:WebView,r:WebResourceRequest):WebResourceResponse {
       try{assertEquals(Protocol.USER_AGENT,r.requestHeaders.entries.firstOrNull{it.key.equals("User-Agent",true)}?.value)}
       catch(e:Throwable){fault.set(e)}
       return WebResourceResponse("text/html","UTF-8",ByteArrayInputStream("<html><body>offline fixture</body></html>".toByteArray()))
      }
      override fun onPageFinished(v:WebView,url:String){
       v.evaluateJavascript("navigator.userAgent"){raw->
        try{
         assertEquals(Protocol.USER_AGENT,org.json.JSONTokener(raw).nextValue())
         val values=(manager.getCookie(OfficialCookies.ORIGIN)?:"").split(';').map{it.trim().split('=',limit=2)}.filter{it.size==2}.associate{it[0] to it[1]}
         assertEquals(session.getJSONObject("cookies").getString("account_id_v2"),values["account_id_v2"])
         assertEquals(session.getString("deviceFp"),values["DEVICEFP"])
         assertEquals(session.getJSONObject("deviceFpContext").getString("seedId"),values["DEVICEFP_SEED_ID"])
        }catch(e:Throwable){fault.set(e)}finally{loaded.countDown()}
       }
      }
     }
     OfficialCookies.seed(session,{origin,value,cb->manager.setCookie(origin,value){cb(it)}},{manager.flush()}){ok->
      if(ok)view.loadUrl(OfficialCookies.ORIGIN+"fp-offline-fixture") else {fault.set(AssertionError("Cookie ACK rejected"));loaded.countDown()}
     }
    }
    assertTrue("offline WebView deadline",loaded.await(20,TimeUnit.SECONDS));fault.get()?.let{throw AssertionError(it)}
    instrumentation.runOnMainSync{web?.stopLoading();web?.destroy();web=null}
   }
   // B's Profile must retain B after A is reused with a new FP.
   instrumentation.runOnMainSync{
    val manager=ProfileStore.getInstance().getProfile(names[1])!!.cookieManager
    assertTrue(manager.getCookie(OfficialCookies.ORIGIN).contains("DEVICEFP=2222222222222"))
    assertFalse(manager.getCookie(OfficialCookies.ORIGIN).contains("aaaaaaaaaaaaa"))
   }
  // Some providers keep a destroyed WebView Profile in-use until process exit. Cleanup cannot mask assertions.
  }finally{instrumentation.runOnMainSync{web?.stopLoading();web?.destroy();if(WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE))names.forEach{runCatching{ProfileStore.getInstance().deleteProfile(it)}};activity.finish()}}
 }
 @Test fun encryptedV1StorePreservesFormalPendingAndCacheAcrossReopenWithoutSchemaReset(){
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  val name="fp-migration-test-"+UUID.randomUUID()+".db"
  try{
   val old=secret("111","1111111111111")
   val cache=obj("status" to "ok","updated" to 1800000000000)
   AccountStore(context,name).use{store->
    store.save(Account("111",obj("roles" to org.json.JSONArray(),"games" to org.json.JSONArray(listOf(cache))),old),true)
    assertEquals(1,store.readableDatabase.version)
   }
   AccountStore(context,name).use{store->
    val account=store.load().single();assertEquals("111",store.selected())
    account.secret!!.put("deviceFpPending",DeviceFingerprint.identity("111","fixture-111",account.secret!!.getJSONObject("deviceFpContext"),{throw AssertionError("existing profile must survive")},1800000060001))
    store.save(account)
    store.readableDatabase.rawQuery("SELECT metadata,secret FROM accounts",null).use{c->
     assertTrue(c.moveToFirst());assertFalse(c.getString(0).contains("deviceFp"))
     assertFalse(String(c.getBlob(1),Charsets.UTF_8).contains("1111111111111"))
    }
   }
   AccountStore(context,name).use{store->
    val account=store.load().single();assertEquals("1111111111111",account.secret!!.getString("deviceFp"))
    assertTrue(account.secret!!.has("deviceFpPending"));assertEquals(53,account.secret!!.getJSONObject("deviceFpPending").getJSONObject("extFields").length())
    assertEquals(1800000000000,account.metadata.getJSONArray("games").getJSONObject(0).getLong("updated"))
   }
  }finally{context.deleteDatabase(name)} // Only the random test database; never hoyoreminder.db or the Keystore.
 }
}
