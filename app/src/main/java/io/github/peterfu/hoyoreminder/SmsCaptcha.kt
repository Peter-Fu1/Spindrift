package io.github.peterfu.hoyoreminder
import android.annotation.SuppressLint
import android.app.Dialog
import android.webkit.*
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
class SmsCaptcha(private val activity:MainActivity){
 private var dialog:Dialog?=null
 private var web:WebView?=null
 private val handler=android.os.Handler(android.os.Looper.getMainLooper())
 fun close(){dialog?.dismiss()}
 @SuppressLint("SetJavaScriptEnabled")
 fun open(challenge:PassportSms.Challenge,done:(JSONObject?)->Unit){
  if(dialog!=null||!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))throw PublicError("VERIFICATION_PENDING")
  val once=AtomicBoolean(false);val d=Dialog(activity);val w=WebView(activity);web=w;dialog=d
  fun finish(answer:JSONObject?){if(once.compareAndSet(false,true)){d.dismiss();done(answer)}}
  val origin="https://hoyo-sms.androidplatform.net"
  val cfg=java.util.Base64.getEncoder().encodeToString(challenge.config.toString().toByteArray())
  val sid=java.util.Base64.getEncoder().encodeToString(challenge.session.toByteArray())
  w.settings.apply{javaScriptEnabled=true;allowFileAccess=false;allowContentAccess=false;domStorageEnabled=false;mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW}
  w.webChromeClient=object:WebChromeClient(){override fun onConsoleMessage(m:ConsoleMessage)=true}
  w.webViewClient=object:WebViewClient(){
   override fun shouldOverrideUrlLoading(v:WebView,r:WebResourceRequest)=true
   override fun shouldInterceptRequest(v:WebView,r:WebResourceRequest):WebResourceResponse?{
    val u=r.url
    if(u.scheme=="https"&&u.host in setOf("static.geetest.com","static.geevisit.com","gcaptcha4.geetest.com","gcaptcha4.geevisit.com","api.geetest.com","api.geevisit.com","api-na.geetest.com","api2.geetest.com"))return null
    return WebResourceResponse("text/plain","UTF-8",403,"Denied",emptyMap(),java.io.ByteArrayInputStream(byteArrayOf()))
   }
  }
  WebViewCompat.addWebMessageListener(w,"SmsResult",setOf(origin)){_,m,o,main,_->
   if(!main||o.toString()!=origin)return@addWebMessageListener
   val text=m.data?:return@addWebMessageListener
   if(text=="cancel"){finish(null);return@addWebMessageListener}
   if(text.length>50000)return@addWebMessageListener
   val answer=runCatching{JSONObject(text)}.getOrNull()?:return@addWebMessageListener
   if(answer.has("event"))return@addWebMessageListener
   if(runCatching{challenge.proof(answer)}.isSuccess)finish(answer)
  }
  d.setContentView(w);d.setOnDismissListener{if(once.compareAndSet(false,true))done(null);dialog=null;web=null;w.stopLoading();w.destroy()}
  d.show();d.window?.setLayout((activity.resources.displayMetrics.widthPixels*.96).toInt(),(activity.resources.displayMetrics.heightPixels*.82).toInt())
  val html="""<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><h3>本人完成短信验证</h3><div id="captcha"></div>
<script src="https://static.geetest.com/static/js/gt.0.4.9.js"></script><script src="https://static.geetest.com/v4/gt4.js"></script><script>
const c=JSON.parse(atob('$cfg')),sid=atob('$sid'),send=x=>SmsResult.postMessage(typeof x==='string'?x:JSON.stringify(x));
function ready(o){o.appendTo('#captcha');o.onSuccess(()=>send(o.getValidate()));o.onClose(()=>send('cancel'));o.onError(()=>send({event:'error'}));o.onReady(()=>c.challenge?o.verify():o.showCaptcha());}
if(c.challenge)initGeetest({gt:c.gt,challenge:c.challenge,offline:false,new_captcha:true,product:'popup',https:true},ready);
else initGeetest4({captchaId:c.gt,riskType:c.risk_type||'slide',product:'popup',lang:'zho',https:true,protocol:'https://',userInfo:JSON.stringify({session_id:sid})},ready);
</script>"""
  w.loadDataWithBaseURL(origin+"/challenge",html,"text/html","UTF-8",null)
  handler.postDelayed({if(dialog===d)finish(null)},180000)
 }
}
