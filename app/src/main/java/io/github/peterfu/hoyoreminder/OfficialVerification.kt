package io.github.peterfu.hoyoreminder

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.webkit.*
import android.widget.*
import androidx.webkit.*
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Trusted official record browser, isolated per account. Only its top frame receives a scoped BBS bridge.
 * Browsing is independent from genuine Card/Aigis challenges. No H5 navigation is a note-success signal.
 */
class OfficialVerification(
 private val activity:MainActivity,private val root:FrameLayout,private val store:AccountStore,private val api:ApiClient,
 private val onCommitted:()->Unit={},
 // Instrumentation replaces public resources, never credentials, native note interception or the bridge.
 private val resourceOverride:((WebResourceRequest)->WebResourceResponse?)?=null
):VerificationPort {
 private val captcha=CaptchaVerification(activity,root,api,onCommitted)
 private val handler=Handler(Looper.getMainLooper())
 private val executor=Executors.newSingleThreadExecutor()
 private var view:WebView?=null
 private var foreground=false
 private var deferred:Pair<VerificationRequest,(VerifiedNote?,String)->Boolean>?=null
 private var pane:LinearLayout?=null
 @Volatile private var active:VerificationRequest?=null
 private var done:((VerifiedNote?,String)->Boolean)?=null
 private var status:TextView?=null
 private var checkButton:Button?=null
 private var diagnosticTrace:DiagnosticTrace?=null
 private var documentScript:ScriptHandler?=null
 private val children=mutableListOf<WebView>()
 @Volatile private var currentUrl=""
 private var lastPageSignal=""
 private var lastPageKind="unknown"
 @Volatile private var lastValidNote=false
 private var recovery=RecordRecovery()
 private val finished=AtomicBoolean(false)
 private val timeout=Runnable{finish("verification_timeout")}
 init{
  if(WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)){
   val profiles=ProfileStore.getInstance()
   store.pendingProfiles().forEach{name->runCatching{profiles.deleteProfile(name);store.profileDeleted(name)}}
  }
 }
 override fun supportsRecordBrowsing()=WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)&&WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)&&WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
 override fun supportsRecordChallenges()=WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
 override fun available(game:Game)=false // No challenge is inferred from a game or a business error code.
 override fun available(request:VerificationRequest)=if(request.browse)supportsRecordBrowsing() else request.challenge!=null&&captcha.available(request)
 fun isOpen()=pane!=null||deferred!=null||captcha.isOpen()
 fun pause(){captcha.pause();foreground=false;view?.onPause()}
 fun resume(){captcha.resume();foreground=true;view?.onResume();val pending=deferred;deferred=null;if(pending!=null)open(pending.first,pending.second)}
 override fun focus(){captcha.focus();handler.post{pane?.bringToFront()}}
 override fun close(scope:String){
  captcha.close(scope);handler.post{
   val pending=deferred
   if(pending?.first?.scope==scope){deferred=null;pending.second(null,"cancelled")}
   if(active?.scope==scope)finish("cancelled",false)
  }
 }
 fun cancel(){if(captcha.isOpen())captcha.cancel() else {val pending=deferred;deferred=null;pending?.second?.invoke(null,"cancelled");finish(if(active?.autoRecover==true)"cancelled" else "closed_without_data")}}
 fun back(){if(captcha.isOpen()){captcha.cancel();return};val web=view;if(web!=null&&web.canGoBack())web.goBack() else cancel()}
 private fun live(request:VerificationRequest)=active===request&&!finished.get()
 private fun cookies(secret:JSONObject,url:String):JSONObject {
  val manager=view?.let{WebViewCompat.getProfile(it).cookieManager}?:throw PublicError("CANCELLED")
  val result=JSONObject()
  (manager.getCookie(url)?:"").split(';').map{it.trim().split('=',limit=2)}.filter{it.size==2&&it[0] in ApiClient.COOKIE_NAMES}.forEach{result.put(it[0],it[1])}
  if(ApiClient.verifiedCookies(result)!=ApiClient.verifiedCookies(secret.getJSONObject("cookies")))throw PublicError("AUTH_EXPIRED")
  return DeviceFingerprint.cookies(secret.copyJson().put("cookies",result))
 }
 private fun navigate(request:VerificationRequest,url:String,source:String){
  if(!live(request))return
  val target=RecordEntries.target(url,currentUrl,request.role)
  if(target==null){
   api.diagnostics.event("verification_navigation",request.role.game,trace=diagnosticTrace,source="webview",stage="navigation",outcome="blocked",entrySource=source)
   status?.text="页面请求的入口不属于当前游戏或角色，已阻止。可关闭后导出诊断。";return
  }
  view?.loadUrl(target)
 }
 private fun pageSignal(request:VerificationRequest,url:String,value:JSONObject){
  if(!live(request)||!RecordEntries.allowed(url,request.role))return
  val kind=value.optString("kind").takeIf{it in setOf("overview","daily","captcha","promotion","error","unknown")}?:"unknown"
  val visible=value.optBoolean("visible")
  lastPageKind=kind
  val key=kind+"|"+visible+"|"+value.optBoolean("challengeVisible")+"|"+url.substringAfter('#',"").substringBefore('?')
  if(lastPageSignal==key)return
  lastPageSignal=key
  api.diagnostics.event("verification_page_classified",request.role.game,url,trace=diagnosticTrace,source="webview",stage="navigation",
   pageClassification=kind,contentVisible=visible,outcome="awaiting_user")
  if(request.autoRecover){
   val wasSeen=recovery.sawChallenge
   val check=recovery.transition(value.optBoolean("challengeVisible"),kind,visible)
   if(!wasSeen&&recovery.sawChallenge)api.diagnostics.event("challenge_ui_ready",request.role.game,trace=diagnosticTrace,source="webview",stage="verification_policy",windowKind="official_h5")
   if(check)checkRecovery(request)
  }
  status?.text=when(kind){
   "overview"->"官方战绩内容已出现，请核对当前角色；页面显示不代表便笺已更新。"
   "daily"->"官方便笺内容已出现。关闭页面后可手动刷新本工具。"
   "captcha"->"官方页面正在要求本人验证；请在页面操作，工具不会伪造验证结果。"
   "promotion"->"官方页面显示推广内容，尚未确认可用战绩。请导出本次诊断。"
   "error"->"官方页面显示错误或限制，原便笺保留。请核对提示并导出诊断。"
   else->"官方页面已加载，等待内容或本人操作；关闭后手动刷新。"
  }
 }
 @SuppressLint("SetJavaScriptEnabled")
 override fun open(request:VerificationRequest,done:(VerifiedNote?,String)->Boolean){
  if(!request.browse){if(request.challenge!=null)captcha.open(request,done) else done(null,"official_verification_unavailable");return}
  handler.post{
   if(!supportsRecordBrowsing()){done(null,"verification_page_unavailable");return@post}
   if(!foreground){if(deferred!=null)done(null,"verification_pending") else deferred=request to done;return@post}
   if(pane!=null||captcha.isOpen()){done(null,"verification_pending");return@post}
   val secret=request.account.secret?.copyJson()
   if(secret==null){done(null,"auth_expired");return@post}
   this.done=done;active=request;finished.set(false);lastPageSignal="";lastPageKind="unknown";lastValidNote=false;recovery=RecordRecovery()
   diagnosticTrace=request.trace?:api.diagnostics.trace(request.account.id,request.role.key(),request.scope)
   val inFlight=AtomicBoolean(false)
   val callbacks=mutableMapOf<String,Triple<String,String,DiagnosticTrace>>()
   var networkSignals=0
   val web=WebView(activity);view=web
   WebViewCompat.setProfile(web,secret.getString("profileKey"));val profile=WebViewCompat.getProfile(web)
   OfficialWebSettings.apply(web)
   web.settings.apply{javaScriptEnabled=true;domStorageEnabled=true;allowFileAccess=false;allowContentAccess=false
    mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW;setSupportMultipleWindows(true);javaScriptCanOpenWindowsAutomatically=false}
   api.diagnostics.event("webview_ua_verified",request.role.game,trace=diagnosticTrace,source="webview",stage="navigation",windowKind="official_h5",uaSource="settings",uaContractMatch=web.settings.userAgentString==Protocol.USER_AGENT)
   val layout=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(0xffffffff.toInt())}
   val heading=LinearLayout(activity)
   heading.addView(TextView(activity).apply{text="官方战绩 · "+request.role.game.id+" · "+request.role.uid;setPadding(16,8,16,8)},LinearLayout.LayoutParams(0,-2,1f))
   heading.addView(Button(activity).apply{text="关闭";setOnClickListener{cancel()}})
   status=TextView(activity).apply{text=if(request.autoRecover)"请本人完成官方验证；取得并保存新便笺后自动关闭。风险限制不会被视为验证成功。" else "正在加载官方原始战绩页面；不自动申请验证码，不自动重试便笺。";setPadding(16,8,16,8)}
   layout.addView(heading);layout.addView(status)
   if(request.autoRecover)layout.addView(Button(activity).apply{checkButton=this;text="已操作，检查便笺结果";setOnClickListener{if(recovery.claimCheck())checkRecovery(request)}})
   layout.addView(web,LinearLayout.LayoutParams(-1,0,1f))
   pane=layout;root.addView(layout,FrameLayout.LayoutParams(-1,-1))
   val bridge=RecordBridge(api,request.account,request.role){secret.copyJson().put("cookies",cookies(secret,currentUrl))}
   WebViewCompat.addWebMessageListener(web,"HoYoRecord",setOf(RecordEntries.origin(request.role.game))){_,message,origin,mainFrame,reply->
    if(!mainFrame||origin.toString()!=RecordEntries.origin(request.role.game)||!live(request))return@addWebMessageListener
    val actual=web.url?:currentUrl
    if(!RecordEntries.allowed(actual,request.role))return@addWebMessageListener
    val raw=message.data?:return@addWebMessageListener;if(raw.length>65536)return@addWebMessageListener
    val params=runCatching{JSONObject(raw)}.getOrNull()?:return@addWebMessageListener
    val method=params.optString("method")
    if(method=="__pageState"){pageSignal(request,actual,params.optJSONObject("payload")?:JSONObject());return@addWebMessageListener}
    if(method=="__bridgeAck"){
     val payload=params.optJSONObject("payload")?:return@addWebMessageListener
     val item=callbacks.remove(payload.optString("id"))?:return@addWebMessageListener
     api.diagnostics.event("bridge_method_result",request.role.game,trace=item.third,source="webview",stage="navigation",
      bridgeMethod=item.first,bridgeStatus=item.second,callbackDelivered=payload.optBoolean("delivered"),scopeMatch=true,
      bridgeImpact=RecordBridge.impact(item.first))
     return@addWebMessageListener
    }
    if(method=="__networkResult"){
     if(!api.diagnostics.detailed||networkSignals++>=80)return@addWebMessageListener
     val payload=params.optJSONObject("payload")?:return@addWebMessageListener
     val kind=payload.optString("kind").takeIf{it in RequestEndpoints.kinds&&it!="unknown"}?:return@addWebMessageListener
     val code=DeviceFingerprint.int(payload.opt("retcode"))
     api.diagnostics.event(if(kind.startsWith("challenge_"))"verification_network_stage" else "h5_network_stage",
      request.role.game,trace=api.diagnostics.trace(request.account.id,request.role.key(),request.scope),source="webview",stage="response",
      endpointKind=kind,requestStage="page_response",observation="page_reported",methodKind=payload.optString("method"),
      httpStatus=DeviceFingerprint.int(payload.opt("httpStatus")),retcode=code)
     return@addWebMessageListener
    }
    val callback=RecordBridge.callback(params.opt("callback"))?:return@addWebMessageListener
    val bridgeTrace=api.diagnostics.trace(request.account.id,request.role.key(),request.scope)
    val safeMethod=method.takeIf{it in RecordBridge.methods}?:"unsupported"
    val result=try{bridge.handle(method,params.optJSONObject("payload")?:JSONObject(),actual)}
     catch(e:Exception){RecordBridge.Result(obj("retcode" to -1,"message" to "BRIDGE_REQUEST_REJECTED","data" to JSONObject.NULL),"rejected")}
    api.diagnostics.event("bridge_method_called",request.role.game,trace=bridgeTrace,source="webview",stage="navigation",bridgeMethod=safeMethod,bridgeStatus=result.status,
     fpPresent=if(method=="getHTTPRequestHeaders")DeviceFingerprint.formal(secret)!=null else null,
     fpMatchesAccount=if(method=="getHTTPRequestHeaders")DeviceFingerprint.formal(secret)?.let{result.response.optJSONObject("data")?.optString("x-rpc-device_fp")==it} else null)
    if(callback.isNotEmpty()&&live(request)){
     val auditId=if(api.diagnostics.detailed)java.util.UUID.randomUUID().toString().replace("-","") else ""
     if(auditId.isNotEmpty()){if(callbacks.size>=128)callbacks.remove(callbacks.keys.first());callbacks[auditId]=Triple(safeMethod,result.status,bridgeTrace)}
     val posted=runCatching{reply.postMessage(obj("callback" to callback,"result" to result.response,"auditId" to auditId).toString())}.isSuccess
     if(!posted&&auditId.isNotEmpty()){
      callbacks.remove(auditId)
      api.diagnostics.event("bridge_method_result",request.role.game,trace=bridgeTrace,source="webview",stage="navigation",bridgeMethod=safeMethod,bridgeStatus="failed",callbackDelivered=false,scopeMatch=true,bridgeImpact=RecordBridge.impact(safeMethod))
     }
    }
    if(result.navigate!=null)navigate(request,result.navigate,"bridge")
    if(result.close)back()
   }
   documentScript=WebViewCompat.addDocumentStartJavaScript(web,RecordScripts.bootstrap+(if(api.diagnostics.detailed)H5NetworkDiagnostics.script else ""),setOf(RecordEntries.origin(request.role.game)))
   web.webChromeClient=object:WebChromeClient(){
    override fun onConsoleMessage(message:ConsoleMessage)=true
    override fun onCreateWindow(view:WebView,isDialog:Boolean,isUserGesture:Boolean,resultMsg:Message):Boolean{
     if(!isUserGesture||!live(request))return false
     val child=WebView(activity);children.add(child);WebViewCompat.setProfile(child,secret.getString("profileKey"));OfficialWebSettings.apply(child)
     fun redirect(url:String){handler.post{if(live(request))navigate(request,url,"bridge");children.remove(child);child.stopLoading();child.destroy()}}
     child.webViewClient=object:WebViewClient(){
      override fun shouldOverrideUrlLoading(v:WebView,r:WebResourceRequest):Boolean{redirect(r.url.toString());return true}
      override fun shouldInterceptRequest(v:WebView,r:WebResourceRequest):WebResourceResponse{if(r.isForMainFrame)redirect(r.url.toString());return blocked()}
     }
     (resultMsg.obj as WebView.WebViewTransport).webView=child;resultMsg.sendToTarget()
     handler.postDelayed({if(children.remove(child))child.destroy()},15000);return true
    }
   }
   web.webViewClient=object:WebViewClient(){
    override fun shouldOverrideUrlLoading(v:WebView,r:WebResourceRequest):Boolean{
     if(!live(request))return true
     if(!r.isForMainFrame)return false
     val target=RecordEntries.target(r.url.toString(),currentUrl,request.role)
     if(target==null){navigate(request,r.url.toString(),"redirect");return true}
     if(target!=r.url.toString()){navigate(request,target,"redirect");return true}
     return false
    }
    override fun onPageStarted(v:WebView,url:String,icon:android.graphics.Bitmap?){
     if(!live(request))return
     if(!RecordEntries.allowed(url,request.role)){finish("record_entry_invalid",false);return}
     currentUrl=url
     api.diagnostics.event("verification_navigation",request.role.game,url,trace=diagnosticTrace,source="webview",stage="navigation",outcome="opening",entrySource="redirect")
     api.diagnostics.event("webview_ua_verified",request.role.game,url,trace=diagnosticTrace,source="webview",stage="navigation",windowKind="official_h5",uaSource="redirect",uaContractMatch=v.settings.userAgentString==Protocol.USER_AGENT)
    }
    override fun onPageFinished(v:WebView,url:String){
     if(!live(request)||!RecordEntries.allowed(url,request.role))return
     api.diagnostics.event("verification_navigation",request.role.game,url,trace=diagnosticTrace,source="webview",stage="navigation",outcome="page_loaded")
     v.evaluateJavascript("navigator.userAgent"){raw->
      if(live(request)){val observed=runCatching{org.json.JSONTokener(raw).nextValue() as? String}.getOrNull()
       api.diagnostics.event("webview_ua_verified",request.role.game,url,trace=diagnosticTrace,source="webview",stage="navigation",windowKind="official_h5",uaSource="navigator",uaContractMatch=observed==Protocol.USER_AGENT,uaNavigatorMatch=observed==v.settings.userAgentString)}
     }
     v.evaluateJavascript(RecordScripts.classify){raw->if(live(request))runCatching{JSONObject(raw)}.onSuccess{pageSignal(request,url,it)}}
    }
    override fun onReceivedError(v:WebView,r:WebResourceRequest,e:WebResourceError){if(r.isForMainFrame&&live(request))finish("page_error")}
    override fun onReceivedHttpError(v:WebView,r:WebResourceRequest,e:WebResourceResponse){if(r.isForMainFrame&&live(request))finish("page_error")}
    override fun onRenderProcessGone(v:WebView,detail:RenderProcessGoneDetail):Boolean{if(live(request))finish("page_error",false);return true}
    override fun shouldInterceptRequest(v:WebView,r:WebResourceRequest):WebResourceResponse? {
     if(!live(request))return blocked()
     val url=r.url.toString();val uri=r.url
     val networkTrace=api.diagnostics.trace(request.account.id,request.role.key(),request.scope)
     val endpoint=RequestEndpoints.kind(url,true)
     if(api.diagnostics.detailed&&endpoint!="unknown"){
      fun header(name:String)=RequestEndpoints.header(r.requestHeaders,name)
      val fp=DeviceFingerprint.formal(secret)
      // WebResourceRequest is a partial map. Absence of Cookie is NOT absence on the wire.
      api.diagnostics.event("webview_request_identity",request.role.game,url,trace=networkTrace,source="webview",stage="request",
       endpointKind=endpoint,requestStage="webview_partial",observation="partial_headers",methodKind=r.method,
       fpPresent=if(r.method=="OPTIONS")null else header("x-rpc-device_fp")?.let{it.isNotBlank()},
       fpMatchesAccount=if(r.method=="OPTIONS")null else header("x-rpc-device_fp")?.let{fp!=null&&it==fp},
       fpCookiePresent=header("Cookie")?.let{Regex("(?:^|;\\s*)DEVICEFP=").containsMatchIn(it)},
       deviceMatches=header("x-rpc-device_id")?.let{it==secret.optString("deviceId")},
       uaContractMatch=header("User-Agent")?.let{it==Protocol.USER_AGENT},
       proofPresent=if(r.method=="OPTIONS")null else !header("x-rpc-challenge").isNullOrBlank()||!header("x-rpc-aigis").isNullOrBlank(),
       headerMask=RequestEndpoints.mask(r.requestHeaders))
     }
     if(r.isForMainFrame)api.diagnostics.event("webview_ua_verified",request.role.game,url,trace=diagnosticTrace,source="webview",stage="navigation",windowKind="official_h5",uaSource="request",uaContractMatch=r.requestHeaders.entries.firstOrNull{it.key.equals("User-Agent",true)}?.value==Protocol.USER_AGENT)
     if(uri.host=="api-takumi-record.mihoyo.com"&&Game.entries.any{it.path==uri.path}){
      if(!Protocol.matchesNote(url,request.role))return blocked()
      val origin=r.requestHeaders.entries.firstOrNull{it.key.equals("Origin",true)}?.value
      if(origin!=null&&origin!=RecordEntries.origin(request.role.game))return blocked()
      val cors=mapOf("Access-Control-Allow-Origin" to RecordEntries.origin(request.role.game),"Access-Control-Allow-Credentials" to "true","Cache-Control" to "no-store")
      if(r.method=="OPTIONS")return WebResourceResponse("text/plain","UTF-8",200,"OK",cors+mapOf("Access-Control-Allow-Methods" to "GET, OPTIONS","Access-Control-Allow-Headers" to "DS, Content-Type, x-rpc-device_fp, x-rpc-device_id, x-rpc-app_version, x-rpc-client_type, x-rpc-page, x-rpc-platform, x-rpc-geetest_ext, X-Requested-With, x-rpc-device_model, x-rpc-device_name, x-rpc-sys_version, x-rpc-channel, x-rpc-language, x-rpc-lang, x-rpc-sdk_version, x-rpc-app_id, x-rpc-lrsag, x-rpc-challenge, x-rpc-aigis, x-rpc-tool_verison, x-rpc-tool-version"),ByteArrayInputStream(ByteArray(0)))
      if(r.method!="GET"||!inFlight.compareAndSet(false,true))return blocked()
      try{
       val browserCookies=profile.cookieManager.getCookie(url)?:throw PublicError("AUTH_EXPIRED")
       api.diagnostics.event("notes_api_observed",request.role.game,url,trace=diagnosticTrace,source="webview",stage="request")
       val response=api.observedNote(secret,request.role,url,browserCookies,r.requestHeaders,request.scope,networkTrace)
       if(!live(request))return blocked()
       val setters=response.headers.filterKeys{it.equals("Set-Cookie",true)}.values.flatten()
        .filterNot{it.substringBefore("=").trim() in setOf("DEVICEFP","DEVICEFP_SEED_ID","DEVICEFP_SEED_TIME")}
       if(setters.isNotEmpty()){
        val latch=CountDownLatch(setters.size);val accepted=AtomicBoolean(true)
        handler.post{if(live(request))setters.forEach{raw->profile.cookieManager.setCookie(url,raw){ok->if(!ok)accepted.set(false);latch.countDown()}} else repeat(setters.size){latch.countDown()}}
        if(!latch.await(3,TimeUnit.SECONDS)||!accepted.get())throw PublicError("SAVE_FAILED")
       }
       val code=response.json.getInt("retcode")
       val note=if(code==0)NoteParser.parse(request.role,response.json.optJSONObject("data")?:throw PublicError("RESPONSE_INVALID")).put("source","miyoushe_webview") else null
       request.retryRetcode=code;lastValidNote=note!=null
       if(request.autoRecover&&note!=null)handler.post{if(live(request))runCatching{VerifiedNote(note,cookies(secret,currentUrl))}.onSuccess{completeRecovery(request,it)}.onFailure{finish("auth_expired",false)}}
       api.diagnostics.event("notes_retry_result",request.role.game,url,retcode=code,httpStatus=response.httpStatus,trace=networkTrace,source="webview",stage="response",classification=if(code==0)"valid_note" else NoteParser.status(code))
       val context=VerificationContext.inspect(response)
       api.diagnostics.event("verification_context_checked",request.role.game,retcode=code,trace=diagnosticTrace,source="webview",stage="verification_policy",
        challengeKind=context.kind,aigisPresent=context.aigisPresent,aigisParseValid=context.aigisParseValid,legacyPresent=context.legacyPresent,contextLocation=context.location)
       // The original page receives the real result. Browsing remains open; user refreshes native notes after closing.
       return WebResourceResponse("application/json","UTF-8",response.httpStatus,"OK",cors,ByteArrayInputStream(response.json.toString().toByteArray(Charsets.UTF_8)))
      }catch(e:Exception){handler.post{if(live(request))status?.text="页面便笺请求未完成，请关闭后导出诊断；旧数据保留。"};return blocked()}
      finally{inFlight.set(false)}
     }
     resourceOverride?.let{return it(r)?:blocked()}
     val host=uri.host?:""
     return if(uri.scheme=="https"&&uri.userInfo==null&&uri.port in listOf(-1,443)&&listOf("mihoyo.com","miyoushe.com","hoyoverse.com","geetest.com","geevisit.com","captchami.com").any{host==it||host.endsWith("."+it)})null else blocked()
    }
   }
   handler.postDelayed(timeout,600000)
   executor.execute{
    try{
     val entry=api.recordEntry(secret,request.account.id,request.role,request.scope,diagnosticTrace)
     if(!RecordEntries.allowed(entry.url,request.role))throw PublicError("RECORD_ENTRY_INVALID")
     api.diagnostics.event("record_entry_discovered",request.role.game,entry.url,trace=diagnosticTrace,source="native_api",stage="navigation",entrySource=entry.source)
     handler.post{
      if(!live(request))return@post
      currentUrl=entry.url
      OfficialCookies.seed(secret,{origin,value,callback->profile.cookieManager.setCookie(origin,value){callback(it)}},{profile.cookieManager.flush()}){ok->
       if(!live(request))return@seed
       val jar=profile.cookieManager.getCookie(entry.url)?:""
       val values=JSONObject();jar.split(';').map{it.trim().split('=',limit=2)}.filter{it.size==2&&it[0] in ApiClient.COOKIE_NAMES}.forEach{values.put(it[0],it[1])}
       val same=runCatching{ApiClient.verifiedCookies(values)==request.account.id}.getOrDefault(false)
       val fp=DeviceFingerprint.formal(secret);val fpSame=if(fp==null)!values.has("DEVICEFP") else values.optString("DEVICEFP")==fp
       api.diagnostics.event("webview_cookie_scope_checked",request.role.game,trace=diagnosticTrace,source="webview",stage="navigation",cookieScopeMatch=ok&&same,fpPresent=fp!=null,fpMatchesAccount=fpSame)
       if(ok&&same&&fpSame)web.loadUrl(entry.url) else finish("session_persist_failed",false)
      }
     }
    }catch(e:Exception){handler.post{if(live(request))finish("official_verification_unavailable",false)}}
   }
  }
 }
 private fun checkRecovery(request:VerificationRequest){
  if(!live(request)||!request.autoRecover)return
  checkButton?.isEnabled=false
  val secret=runCatching{request.account.secret!!.copyJson().also{it.put("cookies",cookies(it,currentUrl))}}.getOrNull()?:return
  status?.text="正在检查当前角色的新便笺；失败保留原数据，不重复请求。"
  executor.execute{
   try{
    if(!live(request))return@execute
    api.diagnostics.event("notes_retry_started",request.role.game,trace=diagnosticTrace,stage="request",source="native_api")
    val response=api.note(secret,request.role,request.scope,trace=diagnosticTrace)
    if(!live(request))return@execute
    val code=response.json.getInt("retcode");request.retryRetcode=code
    api.diagnostics.event("notes_retry_result",request.role.game,retcode=code,trace=diagnosticTrace,stage="response",source="native_api")
    if(code==0){
     val note=NoteParser.parse(request.role,response.json.getJSONObject("data"))
     val jar=DeviceFingerprint.cookies(secret);val updates=ApiClient.cookiesFrom(response.headers)
     updates.keys().forEach{k->jar.put(k,updates.getString(k))}
     handler.post{if(live(request))completeRecovery(request,VerifiedNote(note,jar))}
    }else handler.post{if(live(request))status?.text="官方仍返回 "+code+"，尚未恢复便笺。可继续查看官方提示或关闭；不会反复弹窗。"}
   }catch(e:Exception){handler.post{if(live(request))status?.text="便笺检查失败，旧数据保留。可关闭后导出本次诊断。"}}
  }
 }
 private fun completeRecovery(request:VerificationRequest,result:VerifiedNote){
  if(!live(request)||!request.autoRecover||!finished.compareAndSet(false,true))return
  val trace=diagnosticTrace
  val accepted=runCatching{done?.invoke(result,"ok")==true}.getOrDefault(false)
  if(accepted){
   api.diagnostics.event("notes_committed",request.role.game,retcode=0,classification="committed",trace=trace,source="webview",stage="persist")
   api.diagnostics.event("verification_completed",request.role.game,trace=trace,source="webview",stage="persist")
  }
  api.diagnostics.event("verification_terminal_state",request.role.game,trace=trace,source="webview",stage="persist",outcome=if(accepted)"notes_committed" else "rejected")
  dispose();onCommitted()
 }
 private fun blocked()=WebResourceResponse("application/json","UTF-8",409,"Locally blocked",mapOf("Cache-Control" to "no-store"),ByteArrayInputStream("{\"retcode\":-1,\"data\":null}".toByteArray()))
 private fun finish(error:String,saveSession:Boolean=true){
  if(!finished.compareAndSet(false,true))return
  val request=active
  val result=if(saveSession&&request?.autoRecover!=true&&request?.account?.secret!=null)runCatching{VerifiedNote(null,cookies(request.account.secret!!,currentUrl))}.getOrNull() else null
  api.diagnostics.event("verification_terminal_state",request?.role?.game,trace=diagnosticTrace,source="webview",stage="verification_policy",retcode=request?.retryRetcode,pageClassification=lastPageKind,classification=if(lastValidNote)"valid_note" else null,outcome=when(error){"cancelled"->"cancelled";"verification_timeout"->"expired";"page_error"->"page_error";"closed_without_data"->"closed_without_data";else->"unavailable"})
  done?.invoke(result,error);dispose();onCommitted()
 }
 private fun dispose(){
  handler.removeCallbacks(timeout);documentScript?.remove();documentScript=null
  val scope=active?.scope;active=null
  view?.let{if(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))WebViewCompat.removeWebMessageListener(it,"HoYoRecord");it.stopLoading();it.destroy()}
  children.forEach{it.stopLoading();it.destroy()};children.clear()
  pane?.let{root.removeView(it)};view=null;pane=null;done=null;status=null;checkButton=null;currentUrl=""
  if(scope!=null)api.transport.cancel(scope)
 }
 fun destroy(){captcha.destroy();deferred?.second?.invoke(null,"cancelled");deferred=null;if(pane!=null)finish("cancelled",false);handler.removeCallbacksAndMessages(null);executor.shutdownNow()}
}
