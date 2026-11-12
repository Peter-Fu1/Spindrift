package io.github.peterfu.hoyoreminder

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import android.util.Base64

class MainActivity:ComponentActivity() {
 companion object { const val ORIGIN="https://appassets.androidplatform.net";const val PAGE="$ORIGIN/assets/web/index.html"
 @Volatile var transportFactory:(()->Transport)?=null // Debug instrumentation only; release ignores and R8 removes this hook.
 private fun qrPngStatic(text:String):String {
  val matrix=QRCodeWriter().encode(text,BarcodeFormat.QR_CODE,512,512);val pixels=IntArray(512*512){i->if(matrix[i%512,i/512])0xff17283c.toInt() else 0xffffffff.toInt()}
  val bitmap=Bitmap.createBitmap(pixels,512,512,Bitmap.Config.ARGB_8888);val out=ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.PNG,100,out);bitmap.recycle();return "data:image/png;base64,"+Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP)
 }
 }
 private lateinit var ui:WebView
 private lateinit var root:FrameLayout
 private lateinit var store:AccountStore
 private lateinit var repository:Repository
 private lateinit var smsCaptcha:SmsCaptcha
 private lateinit var images:ProfileImages
 private lateinit var api:ApiClient
 private lateinit var verification:OfficialVerification
 private var executor=Executors.newFixedThreadPool(3)
 private data class Retained(val store:AccountStore,val repository:Repository,val api:ApiClient,val images:ProfileImages,val executor:java.util.concurrent.ExecutorService)
 override fun onRetainCustomNonConfigurationInstance():Any=Retained(store,repository,api,images,executor)
 private val pending=ConcurrentHashMap<String,FutureTask<Unit>>()
 private var destroyed=false
 private var exportText:String?=null
 private val saveDiagnostics=registerForActivityResult(ActivityResultContracts.CreateDocument("application/x-ndjson")){uri->
  val text=exportText;exportText=null
  if(uri!=null&&text!=null&&!destroyed)executor.execute {
   val saved=runCatching{contentResolver.openOutputStream(uri,"w")?.use{it.write(text.toByteArray(Charsets.UTF_8))}?:throw java.io.IOException()}.isSuccess
   runOnUiThread{if(!destroyed)android.widget.Toast.makeText(this,if(saved)"脱敏诊断已保存，可自行分享该文件" else "诊断文件保存失败，请重新选择保存位置",android.widget.Toast.LENGTH_LONG).show()}
  }
 }
 private fun exportDiagnostics(latest:Boolean) {
  if(exportText!=null)throw PublicError("REFRESH_IN_PROGRESS")
  val scope=if(latest)repository.diagnosticRefreshId()?:throw PublicError("NO_REFRESH_LOG") else null
  val header=obj("type" to "build","appVersion" to BuildConfig.VERSION_NAME,"sourceRevision" to BuildConfig.SOURCE_REVISION,
   "androidApi" to android.os.Build.VERSION.SDK_INT,"webViewVersion" to WebView.getCurrentWebViewPackage()?.versionName?.takeIf{Regex("[0-9.]{1,50}").matches(it)})
  exportText=header.toString()+"\n"+api.diagnostics.jsonLines(scope)
  try{saveDiagnostics.launch("HoYoReminder-diagnostics.jsonl")}catch(e:Exception){exportText=null;throw PublicError("SAVE_FAILED")}
 }
 @SuppressLint("SetJavaScriptEnabled")
 override fun onCreate(savedInstanceState:Bundle?) {
  super.onCreate(savedInstanceState)
  WindowCompat.setDecorFitsSystemWindows(window,false)
  root=FrameLayout(this).apply{setBackgroundColor(0xff1976dc.toInt())};setContentView(root)
  ViewCompat.setOnApplyWindowInsetsListener(root){v,insets->val safe=insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime() or WindowInsetsCompat.Type.displayCutout());v.setPadding(safe.left,safe.top,safe.right,safe.bottom);WindowInsetsCompat.Builder(insets).setInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime() or WindowInsetsCompat.Type.displayCutout(),Insets.NONE).build()}
  try{
   val began=System.currentTimeMillis();api=ApiClient(if(BuildConfig.DEBUG)transportFactory?.invoke()?:HttpTransport() else HttpTransport(),RuntimeDiagnostics.value)
   api.diagnostics.event("app_started",stage="startup",source="local")
   smsCaptcha=SmsCaptcha(this)
   val retained=lastCustomNonConfigurationInstance as? Retained
   if(retained!=null){executor.shutdownNow();store=retained.store;repository=retained.repository;api=retained.api;images=retained.images;executor=retained.executor
    val state=SmsLifetime.client().status();state.optString("smsId").takeIf{it.isNotBlank()}?.let{api.diagnostics.login("login_activity_retained",it,state.optString("phase"),"activity_recreate",state.optBoolean("completed"))}
   }else{store=AccountStore(applicationContext);images=ProfileImages(applicationContext).apply{diagnostics=api.diagnostics};repository=Repository(store,api,{text->qrPngStatic(text)},deviceProperties=AndroidDeviceProperties(applicationContext),smsClient=SmsLifetime.client())}
   repository.automaticProfiles=true;repository.profileImages=images;repository.onProfileReady={runOnUiThread{if(!destroyed)ui.evaluateJavascript("window.dispatchEvent(new Event('hoyo-account-update'))",null)}}
   repository.prepareProfiles()
   CheckInWorker.retire(applicationContext)
   verification=OfficialVerification(this,root,store,api,onCommitted={if(::ui.isInitialized&&!destroyed)ui.evaluateJavascript("window.dispatchEvent(new Event('hoyo-account-update'))",null)});repository.verifier=verification
   ui=WebView(this);root.addView(ui,FrameLayout.LayoutParams(-1,-1));ViewCompat.requestApplyInsets(root)
   ui.webChromeClient=object:android.webkit.WebChromeClient(){override fun onConsoleMessage(message:android.webkit.ConsoleMessage)=true}
   ui.settings.apply{javaScriptEnabled=true;domStorageEnabled=true;allowFileAccess=false;allowContentAccess=false;mixedContentMode=android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW;setSupportMultipleWindows(false)}
   api.diagnostics.browser("local_ui",ui.settings.userAgentString)
   WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
   val loader=WebViewAssetLoader.Builder().addPathHandler("/assets/",WebViewAssetLoader.AssetsPathHandler(this)).build()
   ui.webViewClient=object:WebViewClient(){
    override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest)=request.url.toString()!=PAGE
    override fun shouldInterceptRequest(view:WebView,request:WebResourceRequest):WebResourceResponse? {
     if(request.url.scheme=="https"&&request.url.host=="appassets.androidplatform.net"){
      if(request.url.path=="/reward-image"){
       val owner=request.url.getQueryParameter("accountId").orEmpty()
       val source=repository.rewardImage(owner,request.url.getQueryParameter("roleKey").orEmpty(),request.url.getQueryParameter("index")?.toIntOrNull()?:-1)?:return denied()
       val bytes=images.cached(owner,source.second,source.first)?:return denied()
       return WebResourceResponse("image/png","UTF-8",200,"OK",mapOf("Cache-Control" to "no-store"),ByteArrayInputStream(bytes))
      }
      if(request.url.path=="/profile-image"){
       val owner=request.url.getQueryParameter("accountId").orEmpty();val role=request.url.getQueryParameter("roleKey")
       val source=repository.profileImage(owner,role)?.first
       val bytes=source?.let{images.cached(owner,role?:"account",it)}
       api.diagnostics.avatar("avatar_fetch_result",owner,role?:"account",if(role==null)"passport" else "game_role",if(bytes==null)"cache_miss" else "cache_hit")
       if(bytes==null)return denied()
       return WebResourceResponse("image/png","UTF-8",200,"OK",mapOf("Cache-Control" to "no-store"),ByteArrayInputStream(bytes))
      }
      return loader.shouldInterceptRequest(request.url)?:denied()
     }
     val host=request.url.host?:""
     return denied() // Local UI images are served from owner-scoped native cache only.
    }
    override fun onPageCommitVisible(view:WebView,url:String){if(url==PAGE){
     api.diagnostics.event("navigation_completed",durationMs=System.currentTimeMillis()-began,stage="navigation",source="local")
     view.evaluateJavascript("navigator.userAgent"){raw->val observed=runCatching{org.json.JSONTokener(raw).nextValue() as? String}.getOrNull();api.diagnostics.browser("local_ui",view.settings.userAgentString,observed==view.settings.userAgentString)}
    }}
   }
   if(!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))throw PublicError("BRIDGE_UNSUPPORTED")
   WebViewCompat.addWebMessageListener(ui,"HoYoNative",setOf(ORIGIN)){_,message,origin,mainFrame,reply->
    if(!mainFrame||origin.toString()!=ORIGIN||destroyed)return@addWebMessageListener
    val text=message.data?:return@addWebMessageListener;if(text.length>65536)return@addWebMessageListener
    val request=runCatching{JSONObject(text)}.getOrNull()?:return@addWebMessageListener
    val id=request.optString("id");if(!Regex("[0-9]{1,10}").matches(id))return@addWebMessageListener
    if(request.optString("method")=="CANCEL"){pending.remove(id)?.cancel(true);return@addWebMessageListener}
    if(pending.size>=32||pending.containsKey(id)){reply.postMessage(obj("id" to id,"ok" to false,"code" to "REFRESH_IN_PROGRESS","error" to publicMessage("REFRESH_IN_PROGRESS")).toString());return@addWebMessageListener}
    if(request.optString("path")=="/api/diagnostics/export"&&request.optString("method")=="POST"){
     val response=try{exportDiagnostics(request.optJSONObject("body")?.optBoolean("latestRefresh")==true);obj("id" to id,"ok" to true,"result" to obj("opened" to true))}catch(e:Exception){val code=(e as? PublicError)?.code?:"SAVE_FAILED";obj("id" to id,"ok" to false,"code" to code,"error" to publicMessage(code),"upstreamRetcode" to (e as? PublicError)?.retcode,"fpResultCode" to (e as? PublicError)?.fpResultCode)}
     reply.postMessage(response.toString());return@addWebMessageListener
    }
    if(request.optString("path")=="/api/auth/sms/challenge"&&request.optString("method")=="POST"){
     try{
      if(verification.isOpen())throw PublicError("VERIFICATION_PENDING")
      val smsId=request.getJSONObject("body").getString("smsId")
      smsCaptcha.open(repository.smsChallenge(smsId)){answer->
       if(answer!=null)executor.execute{runCatching{repository.answerSms(smsId,answer)};runOnUiThread{if(!destroyed)ui.evaluateJavascript("window.dispatchEvent(new Event('hoyo-sms-update'))",null)}}
      }
      reply.postMessage(obj("id" to id,"ok" to true,"result" to obj("opened" to true)).toString())
     }catch(e:Exception){reply.postMessage(obj("id" to id,"ok" to false,"code" to ((e as? PublicError)?.code?:"LOCAL_ERROR"),"error" to "短信验证组件未能打开").toString())}
     return@addWebMessageListener
    }
    val task=FutureTask<Unit>{
     val response=try{obj("id" to id,"ok" to true,"result" to repository.dispatch(request.optString("path"),request.optString("method"),request.optJSONObject("body")?:JSONObject()).also{value->
      val owner=request.optJSONObject("body")?.optString("accountId").orEmpty()
      if(request.optString("path")=="/api/accounts/remove")images.remove(owner)
      if(request.optString("path")=="/api/checkin/run"||request.optString("path")=="/api/checkin/batch/status"&&value.optJSONObject("job")?.optString("status")=="completed"){
       val book=(value.optJSONObject("ledger")?:value).optJSONObject("roles");val deadline=System.nanoTime()+8_000_000_000L
       val requested=request.optJSONObject("body")?.optJSONArray("roleKeys")?:value.optJSONObject("job")?.optJSONArray("roles")?.let{rows->org.json.JSONArray(rows.objects().map{it.getString("roleKey")})}
       val chosen=(0 until (requested?.length()?:0)).map{requested!!.getString(it)}.toSet()
       book?.keys()?.forEach{key->
        if(key.removeSuffix("|CN") !in chosen)return@forEach
        val owner=key.substringBefore("|");val role=key.substringAfter("|").removeSuffix("|CN")
        val awards=book.optJSONObject(key)?.optJSONObject("home")?.optJSONArray("awards")
        if(awards!=null)for(i in 0 until awards.length()){
         val source=repository.rewardImage(owner,role,i)?:continue
         val budget=(deadline-System.nanoTime())/1000000
         if(budget<=0)break
         images.fetch(owner,source.second,source.first,budget)
        }
       }
      }
      if(request.optString("path")=="/api/accounts/profile"){
       val a=value.optJSONArray("accounts")?.objects()?.firstOrNull{it.optString("id")==owner}
       val sources=mutableListOf<Pair<String,String>>()
       repository.profileImage(owner,null)?.first?.let{sources.add("account" to it)}
       a?.optJSONArray("roles")?.objects()?.forEach{saved->val key=Role.fromSaved(saved).key();repository.profileImage(owner,key)?.first?.let{sources.add(key to it)}}
       val imageDeadline=System.nanoTime()+8_000_000_000L
       sources.forEach{(key,url)->val budget=(imageDeadline-System.nanoTime())/1_000_000;if(!destroyed&&budget>0)runCatching{images.fetch(owner,key,url,budget)}}
      }
     })}catch(e:Exception){val code=(e as? PublicError)?.code?:"LOCAL_ERROR";obj("id" to id,"ok" to false,"code" to code,"error" to publicMessage(code),"upstreamRetcode" to (e as? PublicError)?.retcode,"fpResultCode" to (e as? PublicError)?.fpResultCode)}
     runOnUiThread{if(!destroyed)runCatching{reply.postMessage(response.toString())}};pending.remove(id);Unit
    };pending[id]=task;executor.execute(task)
   }
   ui.loadUrl(PAGE)
   onBackPressedDispatcher.addCallback(this,object:OnBackPressedCallback(true){override fun handleOnBackPressed(){if(verification.isOpen()){verification.back();return};ui.evaluateJavascript("window.HoYoPlatform?.back()"){handled->if(handled!="true")finish()}}})
  }catch(e:Exception){if(::api.isInitialized)api.diagnostics.event("app_failed",stage="startup",source="local",exceptionCategory="local_error");root.removeAllViews();root.addView(TextView(this).apply{text=publicMessage((e as? PublicError)?.code?:"LOCAL_ERROR");setPadding(24,40,24,24)})}
 }
 private fun denied()=WebResourceResponse("text/plain","UTF-8",403,"Forbidden",emptyMap(),ByteArrayInputStream(ByteArray(0)))
 private fun qrPng(text:String):String {
  val matrix=QRCodeWriter().encode(text,BarcodeFormat.QR_CODE,512,512)
  val pixels=IntArray(512*512){i->if(matrix[i%512,i/512])0xff17283c.toInt() else 0xffffffff.toInt()}
  val bitmap=Bitmap.createBitmap(pixels,512,512,Bitmap.Config.ARGB_8888)
  val output=ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.PNG,100,output);bitmap.recycle()
  return "data:image/png;base64,"+Base64.encodeToString(output.toByteArray(),Base64.NO_WRAP)
 }
 override fun onPause(){if(::verification.isInitialized)verification.pause();if(::ui.isInitialized){ui.onPause();ui.pauseTimers()};super.onPause()}
 override fun onResume(){super.onResume();if(::ui.isInitialized){ui.onResume();ui.resumeTimers();ui.evaluateJavascript("window.dispatchEvent(new Event('hoyo-resume'))",null)};if(::verification.isInitialized)verification.resume()}
 override fun onDestroy(){
  destroyed=true;exportText=null
  if(::repository.isInitialized)repository.onProfileReady=null
  if(::repository.isInitialized&&!isChangingConfigurations)repository.close() else if(::repository.isInitialized)repository.verifier=null
  if(::verification.isInitialized)verification.destroy()
  if(::api.isInitialized&&!isChangingConfigurations)api.transport.cancel("*")
  if(!isChangingConfigurations){pending.values.forEach{it.cancel(true)};pending.clear();executor.shutdownNow()}
  if(::smsCaptcha.isInitialized)smsCaptcha.close()
  if(::ui.isInitialized){root.removeView(ui);ui.stopLoading();ui.destroy()}
  if(::store.isInitialized&&!isChangingConfigurations)store.close()
  super.onDestroy()
 }
}
