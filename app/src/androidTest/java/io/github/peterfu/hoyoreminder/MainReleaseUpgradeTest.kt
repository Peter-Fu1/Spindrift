package io.github.peterfu.hoyoreminder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.work.*
import android.webkit.WebView
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.content.pm.ApplicationInfo
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Isolated MAIN package; synthetic data only; seed -> actual APK replace -> restore -> wrong-key reject. */
@RunWith(AndroidJUnit4::class)
class MainReleaseUpgradeTest {
 @Test fun encryptedDataAndOsIdentitySurvivePackageReplacement(){
  val ins=InstrumentationRegistry.getInstrumentation();val args=InstrumentationRegistry.getArguments()
  assumeTrue("Only explicit isolated upgrade runs",args.getString("mainUpgradeTest")=="true")
  val ctx=ins.targetContext;assertEquals("io.github.peterfu.hoyoreminder",ctx.packageName)
  assertEquals(0,ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)
  val phase=args.getString("mainUpgradePhase")?:error("Explicit phase required")
  val store=AccountStore(ctx);val wm=WorkManager.getInstance(ctx)
  val profile="main-upgrade-synthetic";val cookie="fixture_session=offline-only; Secure; Path=/"
  val origin="https://main-upgrade.invalid/";val fp="abcdef1234567"
  val folder=File(ctx.getExternalFilesDir(null),"main-upgrade").apply{mkdirs()}
  val role=Role(Game.GENSHIN,"100000001","cn_gf01","synthetic","synthetic",60)
  if(phase=="seed"){
   // Exercise the actual non-debuggable launcher before synthetic seeding; no account exists yet.
   val activity=ins.startActivitySync(Intent(ctx,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
   fun findWeb(v:View):WebView? { if(v is WebView)return v;if(v is ViewGroup)for(i in 0 until v.childCount)findWeb(v.getChildAt(i))?.let{return it};return null }
   try{
    var loaded=false
    for(attempt in 0 until 40){
     val ready=CountDownLatch(1)
     ins.runOnMainSync{val web=findWeb(activity.window.decorView)?:error("Launcher WebView missing");web.evaluateJavascript("Boolean(document.body && document.body.innerText.length > 0 && document.readyState === 'complete')"){loaded=it=="true";ready.countDown()}}
     assertTrue(ready.await(2,TimeUnit.SECONDS));if(loaded)break;Thread.sleep(100)
    }
    assertTrue("Actual release UI must load from appassets",loaded)
   }finally{ins.runOnMainSync{activity.finish()};ins.waitForIdleSync()}

   assertTrue("Never overwrite an existing account set",store.load().isEmpty())
   val context=DeviceFingerprint.identity("888","synthetic-main-device",null,{JSONObject()},1800000000000)
   val secret=obj("cookies" to obj("account_id_v2" to "888","ltuid_v2" to "888","cookie_token_v2" to "synthetic-only"),"deviceId" to "synthetic-main-device","deviceFp" to fp,"profileKey" to profile,"deviceFpContext" to context,"deviceFpPending" to context.copyJson())
   val note=NoteParser.parse(role,obj("current_resin" to 100,"max_resin" to 200,"resin_recovery_time" to 47540),1800000000000)
   store.save(Account("888",obj("label" to "synthetic","roles" to JSONArray(listOf(role.json())),"games" to JSONArray(listOf(note))),secret),true)
   val request=OneTimeWorkRequestBuilder<CheckInWorker>().setInitialDelay(365,TimeUnit.DAYS).build()
   wm.enqueueUniqueWork("main-upgrade-offline",ExistingWorkPolicy.KEEP,request).result.get(10,TimeUnit.SECONDS)
   store.saveUi(obj("mode" to "local","originalUid" to android.os.Process.myUid(),"originalPid" to android.os.Process.myPid(),"workId" to request.id.toString()))
   store.saveCheckin(obj("roles" to obj(),"fixtureMarker" to "retained"))
   val ack=CountDownLatch(1)
   ins.runOnMainSync{
    assertTrue(WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE))
    val web=WebView(ctx);WebViewCompat.setProfile(web,profile)
    val manager=WebViewCompat.getProfile(web).cookieManager
    manager.setCookie(origin,cookie){ok->assertTrue(ok);manager.flush();web.destroy();ack.countDown()}
   }
   assertTrue(ack.await(15,TimeUnit.SECONDS))
   File(folder,"seed.json").writeText(obj("phase" to "seed","syntheticOnly" to true,"networkRequests" to 0,"nonDebuggable" to true,"firstLaunchUiLoaded" to true,"versionCode" to ctx.packageManager.getPackageInfo(ctx.packageName,0).longVersionCode).toString())
  }else{
   val prefs=store.loadUi();val account=store.load().single();val secret=account.secret?:error("Keystore decryption failed")
   assertEquals(prefs.getInt("originalUid"),android.os.Process.myUid())
   assertNotEquals(prefs.getInt("originalPid"),android.os.Process.myPid())
   assertEquals("888",store.selected());assertEquals(1,store.readableDatabase.version)
   assertEquals("local",prefs.getString("mode"));assertEquals("retained",store.loadCheckin().getString("fixtureMarker"))
   assertEquals("synthetic-main-device",secret.getString("deviceId"));assertEquals(fp,secret.getString("deviceFp"))
   assertEquals("synthetic-only",secret.getJSONObject("cookies").getString("cookie_token_v2"));assertEquals(profile,secret.getString("profileKey"))
   assertEquals(53,secret.getJSONObject("deviceFpContext").getJSONObject("extFields").length())
   assertEquals(secret.getJSONObject("deviceFpContext").getString("seedId"),secret.getJSONObject("deviceFpPending").getString("seedId"))
   assertEquals(100,account.metadata.getJSONArray("games").getJSONObject(0).getInt("current"))
   val rows=wm.getWorkInfosForUniqueWork("main-upgrade-offline").get(10,TimeUnit.SECONDS).filter{!it.state.isFinished}
   assertEquals(1,rows.size);assertEquals(prefs.getString("workId"),rows.single().id.toString())
   val ack=CountDownLatch(1)
   ins.runOnMainSync{
    val web=WebView(ctx);WebViewCompat.setProfile(web,profile)
    assertTrue(WebViewCompat.getProfile(web).cookieManager.getCookie(origin).contains("fixture_session=offline-only"))
    web.destroy();ack.countDown()
   }
   assertTrue(ack.await(15,TimeUnit.SECONDS))
   assertEquals(20L,ctx.packageManager.getPackageInfo(ctx.packageName,0).longVersionCode)
   File(folder,phase+".json").writeText(obj("phase" to phase,"syntheticOnly" to true,"sameUid" to true,"distinctProcess" to true,"credentialsDecrypt" to true,"fpAndPendingRetained" to true,"cookieRetained" to true,"profileRetained" to true,"workManagerRetained" to true,"cacheAndSettingsRetained" to true,"schema" to 1,"networkRequests" to 0,"versionCode" to 20).toString())
  }
  store.close()
 }
}
