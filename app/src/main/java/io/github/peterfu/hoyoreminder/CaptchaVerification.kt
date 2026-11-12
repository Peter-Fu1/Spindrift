package io.github.peterfu.hoyoreminder

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.*
import android.widget.*
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/** Public widget only: no app bridge, account cookies or device identity enter this view. */
class CaptchaVerification(private val activity:MainActivity,private val root:FrameLayout,private val api:ApiClient,private val changed:()->Unit):VerificationPort {
 private val handler=Handler(Looper.getMainLooper())
 private val executor=Executors.newSingleThreadExecutor()
 private var pane:LinearLayout?=null
 private var view:WebView?=null
 @Volatile private var active:VerificationRequest?=null
 private var done:((VerifiedNote?,String)->Boolean)?=null
 private var foreground=false
 private var deferred:Pair<VerificationRequest,(VerifiedNote?,String)->Boolean>?=null
 private val submitted=AtomicBoolean(false)
 private var currentTrace:DiagnosticTrace?=null
 private val readyDeadline=Runnable{active?.let{api.diagnostics.event("verification_ui_error",it.role.game,trace=currentTrace,stage="navigation",source="webview",verificationKind=it.challenge?.kind,failureStage="component");finish(null,"verification_component_failed")}}
 private val deadline=Runnable{finish(null,"verification_timeout")}
 private val origin="https://hoyo-captcha.androidplatform.net"
 private val page="$origin/challenge"
 override fun available(game:Game)=false
 override fun available(request:VerificationRequest)=request.challenge?.fresh()==true&&WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
 fun isOpen()=pane!=null||deferred!=null
 fun pause(){foreground=false;view?.onPause()}
 fun resume(){foreground=true;view?.onResume();val next=deferred;deferred=null;if(next!=null)open(next.first,next.second)}
 override fun focus(){handler.post{pane?.bringToFront()}}
 override fun close(scope:String){handler.post{if(deferred?.first?.scope==scope){deferred?.second?.invoke(null,"cancelled");deferred=null};if(active?.scope==scope)finish(null,"cancelled")}}
 fun cancel(){val waiting=deferred;deferred=null;waiting?.second?.invoke(null,"cancelled");finish(null,"cancelled")}
 @SuppressLint("SetJavaScriptEnabled")
 override fun open(request:VerificationRequest,done:(VerifiedNote?,String)->Boolean){
  handler.post{
   if(!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)||!available(request)){done(null,"official_verification_unavailable");return@post}
   if(pane!=null||deferred!=null){done(null,"verification_pending");return@post}
   if(!foreground){deferred=request to done;return@post}
   val challenge=request.challenge!!
   active=request;this.done=done;submitted.set(false)
   val trace=request.trace?:api.diagnostics.trace(request.account.id,request.role.key(),request.scope);currentTrace=trace
   val web=WebView(activity);view=web
   web.settings.apply{javaScriptEnabled=true;allowFileAccess=false;allowContentAccess=false;domStorageEnabled=false;mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW;setSupportMultipleWindows(false)}
   api.diagnostics.browser("captcha",web.settings.userAgentString)
   api.diagnostics.event("webview_ua_verified",request.role.game,trace=trace,stage="navigation",source="webview",windowKind="captcha",uaSource="settings",uaContractMatch=web.settings.userAgentString==Protocol.USER_AGENT)
   CookieManager.getInstance().setAcceptThirdPartyCookies(web,false)
   web.webChromeClient=object:WebChromeClient(){override fun onConsoleMessage(message:ConsoleMessage)=true}
   val layout=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(0xffffffff.toInt())}
   layout.addView(TextView(activity).apply{text="请本人完成人机验证 · "+request.role.game.id;setPadding(16,16,16,16)})
   layout.addView(Button(activity).apply{text="取消";setOnClickListener{cancel()}})
   layout.addView(web,LinearLayout.LayoutParams(-1,0,1f));pane=layout;root.addView(layout,FrameLayout.LayoutParams(-1,-1))
   val html=CaptchaPage.html(challenge)
   web.webViewClient=object:WebViewClient(){
    override fun shouldOverrideUrlLoading(v:WebView,r:WebResourceRequest)=r.isForMainFrame&&r.url.toString()!=page
    override fun shouldInterceptRequest(v:WebView,r:WebResourceRequest):WebResourceResponse?{
     if(r.url.toString()==page&&r.method=="GET")return WebResourceResponse("text/html","UTF-8",200,"OK",mapOf("Cache-Control" to "no-store"),ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)))
     val host=r.url.host?:""
     if(r.url.scheme=="https"&&r.url.port in listOf(-1,443)&&listOf("geetest.com","geevisit.com").any{host==it||host.endsWith(".$it")})return null
     return WebResourceResponse("text/plain","UTF-8",403,"Forbidden",emptyMap(),ByteArrayInputStream(ByteArray(0)))
    }
    override fun onReceivedError(v:WebView,r:WebResourceRequest,e:WebResourceError){if(r.isForMainFrame&&active===request)finish(null,"page_navigation_failed")}
    override fun onPageCommitVisible(v:WebView,url:String){if(active===request&&url==page){
     api.diagnostics.event("verification_page_opened",request.role.game,trace=trace,source="webview",stage="navigation")
     v.evaluateJavascript("navigator.userAgent"){raw->val ua=runCatching{org.json.JSONTokener(raw).nextValue() as? String}.getOrNull()
      api.diagnostics.event("webview_ua_verified",request.role.game,trace=trace,stage="navigation",source="webview",windowKind="captcha",uaSource="navigator",uaContractMatch=ua==Protocol.USER_AGENT,uaNavigatorMatch=ua==v.settings.userAgentString)}
    }}
   }
   api.diagnostics.event("verification_ui_opened",request.role.game,trace=trace,stage="navigation",source="webview",verificationKind=challenge.kind)
   WebViewCompat.addWebMessageListener(web,"CaptchaResult",setOf(origin)){_,message,source,mainFrame,_->
    if(!mainFrame||source.toString()!=origin||active!==request||submitted.get())return@addWebMessageListener
    val raw=message.data?:return@addWebMessageListener
    if(raw=="cancel"){finish(null,"cancelled");return@addWebMessageListener}
    if(raw.length>49152)return@addWebMessageListener
    val messageValue=runCatching{JSONObject(raw)}.getOrElse{finish(null,"response_invalid");return@addWebMessageListener}
    if(messageValue.optString("event")=="ready"){
     handler.removeCallbacks(readyDeadline)
     api.diagnostics.event("challenge_ui_ready",request.role.game,trace=trace,source="webview",stage="navigation",pageClassification="local_shell")
     api.diagnostics.event("verification_page_ready",request.role.game,trace=trace,stage="navigation",source="webview",verificationKind=challenge.kind,pageClassification="captcha")
     return@addWebMessageListener
    }
    if(messageValue.optString("event")=="error"){
     api.diagnostics.event("verification_ui_error",request.role.game,trace=trace,stage="navigation",source="webview",verificationKind=challenge.kind,failureStage="component")
     finish(null,"verification_component_failed");return@addWebMessageListener
    }
    val answer=runCatching{challenge.checkedAnswer(messageValue)}.getOrElse{finish(null,"response_invalid");return@addWebMessageListener}
    if(!submitted.compareAndSet(false,true))return@addWebMessageListener
    api.diagnostics.event("challenge_ui_completed",request.role.game,trace=trace,source="webview",stage="verification_policy")
    api.diagnostics.event("verification_ui_completed",request.role.game,trace=trace,stage="verification_policy",source="webview")
    val secret=request.account.secret?.copyJson()
    if(secret==null){finish(null,"auth_expired");return@addWebMessageListener}
    executor.execute{
     var result:VerifiedNote?=null;var error="network_error"
     try{
      if(active!==request||!challenge.fresh()){handler.post{if(active===request)finish(null,"verification_timeout")};return@execute}
      val (session,proof)=api.answerChallenge(secret,request.role,challenge,answer,request.scope,trace)
      if(active!==request)return@execute
      api.diagnostics.event("verification_context_updated",request.role.game,trace=trace,stage="verification_policy",source="native_api")
      api.diagnostics.event("notes_retry_started",request.role.game,trace=trace,stage="request",source="native_api")
      val response=api.note(session,request.role,request.scope,officialHeaders=proof,trace=trace)
      val code=response.json.getInt("retcode");request.retryRetcode=code
      api.diagnostics.event("notes_retry_result",request.role.game,retcode=code,httpStatus=response.httpStatus,trace=trace,stage="response",source="native_api")
      if(code==0){result=VerifiedNote(NoteParser.parse(request.role,response.json.optJSONObject("data")?:throw PublicError("RESPONSE_INVALID")),session.getJSONObject("cookies").apply{ApiClient.cookiesFrom(response.headers).let{u->u.keys().forEach{k->put(k,u.getString(k))}}});error="ok"}
      else error=NoteParser.status(code)
     }catch(e:Exception){error=when((e as? PublicError)?.code){"RESPONSE_INVALID"->"response_invalid";"VERIFICATION_REJECTED"->"verification_rejected";"CANCELLED"->"cancelled";else->"network_error"}}
     val note=result;val outcome=error
     handler.post{if(active===request){val accepted=finish(note,outcome);if(accepted){api.diagnostics.event("notes_committed",request.role.game,retcode=0,classification="committed",trace=trace,stage="persist",source="native_api");api.diagnostics.event("verification_completed",request.role.game,trace=trace,stage="persist",source="native_api");changed()}}}
    }
   }
   handler.postDelayed(deadline,180000);handler.postDelayed(readyDeadline,15000)
   api.diagnostics.event("verification_route_selected",request.role.game,classification="configured_entry",trace=trace,stage="verification_policy",source="local")
   web.loadUrl(page)
  }
 }
 private fun finish(note:VerifiedNote?,error:String):Boolean{
  if(active==null)return false
  handler.removeCallbacks(deadline);handler.removeCallbacks(readyDeadline)
  val callback=done;val request=active!!;val scope=request.scope
  api.diagnostics.event("verification_terminal_state",request.role.game,trace=currentTrace,stage="verification_policy",source="webview",
   outcome=when(error){"cancelled"->"cancelled";"verification_timeout"->"expired";"ok"->"submitted";else->"rejected"})
  active=null;done=null
  view?.let{if(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))WebViewCompat.removeWebMessageListener(it,"CaptchaResult");it.stopLoading();it.destroy()};view=null
  pane?.let{root.removeView(it)};pane=null
  api.transport.cancel(scope)
  return callback?.invoke(note,error)==true
 }
 fun destroy(){deferred?.second?.invoke(null,"cancelled");deferred=null;finish(null,"cancelled");handler.removeCallbacksAndMessages(null);executor.shutdownNow()}
}
