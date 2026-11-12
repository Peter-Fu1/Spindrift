package io.github.peterfu.hoyoreminder
import android.content.Intent
import android.webkit.*
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList

/** Real Android WebView + production HTML. Widget JS is offline, never official proof. */
@RunWith(AndroidJUnit4::class)
class CaptchaWebViewTest {
 @Test fun widgetReadyAndCompletionReachAnOriginRestrictedNativeListener()=runWidget(false)
 @Test fun widgetCancellationReturnsWithoutAnyBusinessRequest()=runWidget(true)
 private fun runWidget(cancel:Boolean){
  val instrumentation=InstrumentationRegistry.getInstrumentation()
  val activity=instrumentation.startActivitySync(Intent(instrumentation.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
  val complete=CountDownLatch(1);val messages=CopyOnWriteArrayList<String>();val rejected=CopyOnWriteArrayList<String>()
  var web:WebView?=null
  val origin="https://hoyo-captcha.androidplatform.net";val page="$origin/challenge"
  val role=Role(Game.GENSHIN,"100000001","cn_gf01","fixture","fixture",60)
  val challenge=NoteChallenge.fromRecord(WireResponse(obj("retcode" to 0,"data" to obj("gt" to "fixture","challenge" to "fixture-challenge"))),"111",role)!!
  val html=CaptchaPage.html(challenge)
  val script="""window.initGeetest=(options,callback)=>{let success=()=>{},closed=()=>{};const widget={appendTo(){},onSuccess(fn){success=fn;},onClose(fn){closed=fn;},onError(){},onReady(fn){setTimeout(fn,0);},verify(){setTimeout(()=>{CANCEL?closed():success();},20);},getValidate(){return {geetest_challenge:'fixture-answer',geetest_validate:'fixture-validate',geetest_seccode:'fixture-seccode'};}};callback(widget);};""".replace("CANCEL",cancel.toString())
  try{
   instrumentation.runOnMainSync{
    assertTrue(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
    val view=WebView(activity);web=view;view.settings.javaScriptEnabled=true
    activity.addContentView(view,FrameLayout.LayoutParams(360,500))
    view.webViewClient=object:WebViewClient(){
     override fun shouldInterceptRequest(v:WebView,r:WebResourceRequest):WebResourceResponse{
      val url=r.url.toString()
      val text=when(url){
       page->html
       "$origin/favicon.ico"->""
       "https://static.geetest.com/static/js/gt.0.4.9.js"->script
       "https://static.geetest.com/v4/gt4.js"->""
       else->{rejected.add(r.url.host?:"unknown");""}
      }
      return WebResourceResponse(if(url==page)"text/html" else "application/javascript","UTF-8",ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
     }
    }
    WebViewCompat.addWebMessageListener(view,"CaptchaResult",setOf(origin)){_,m,source,mainFrame,_->
     if(mainFrame&&source.toString()==origin){
      val value=m.data?:"";messages.add(value)
      if(value=="cancel"||value.contains("geetest_validate"))complete.countDown()
     }
    }
    view.loadUrl(page)
   }
   assertTrue("production shell did not complete",complete.await(30,TimeUnit.SECONDS))
   assertTrue(messages.any{it.contains("ready")})
   if(cancel)assertEquals("cancel",messages.last())
   else assertEquals("fixture-answer",JSONObject(messages.last()).getString("geetest_challenge"))
   assertTrue("unexpected intercepted resource hosts: "+rejected.joinToString(","),rejected.isEmpty())
  }finally{instrumentation.runOnMainSync{web?.stopLoading();web?.destroy();activity.finish()}}
 }
}
