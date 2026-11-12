package io.github.peterfu.hoyoreminder
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.json.JSONArray
import org.json.JSONTokener
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Exercises the production window, Profile, document-start bridge and HTTP interception.
 * Public HTML is an offline fixture: this is NOT real-account or captcha acceptance.
 */
@RunWith(AndroidJUnit4::class)
class RecordBrowserTest {
 private fun role(game:Game)=Role(game,if(game==Game.ZENLESS)"10000001" else "100000001",if(game==Game.GENSHIN)"cn_gf01" else "prod_gf_cn","fixture","fixture",60)
 private fun cookies(id:String)=JSONObject().apply{ApiClient.AUTH_NAMES.forEach{put(it,if(it in setOf("account_id_v2","ltuid_v2"))id else "fixture-"+id)}}
 private fun fp(id:String)=id.first().toString().repeat(13)
 private fun account(id:String):Account {
  val s=obj("cookies" to cookies(id),"deviceId" to "fixture-"+id,"profileKey" to "record-fixture-"+UUID.randomUUID(),"deviceFp" to fp(id))
  s.put("deviceFpContext",DeviceFingerprint.identity(id,s.getString("deviceId"),null,{obj("model" to "Fixture Android","osVersion" to "15")},1800000000000))
  return Account(id,obj("label" to "fixture","roles" to JSONArray((if(id=="222")listOf(Game.GENSHIN) else Game.entries).map{role(it).json()}),"games" to JSONArray(),"auth" to "authenticated"),s)
 }
 private class Wire(private val initialRisk:Int?=null,private val constantRisk:Boolean=false,private val requireProof:Boolean=false):Transport {
  var notes=0;var creations=0
  override fun execute(method:String,url:String,headers:Map<String,String>,body:JSONObject?,scope:String):WireResponse{
   assertEquals(Protocol.USER_AGENT,headers["User-Agent"])
   if(url.contains("getGameRecordCard"))return WireResponse(obj("retcode" to 0,"data" to obj("list" to JSONArray())))
   if(url.contains("createVerification")){creations++;throw AssertionError("browsing must never create a challenge")}
   notes++
   if(requireProof&&notes>1)assertEquals("fixture-server-accepted",headers["x-rpc-challenge"])
   return WireResponse(obj("retcode" to if(initialRisk!=null&&(notes==1||constantRisk))initialRisk else if(url.contains("/zzz/")&&headers["Cookie"]?.contains("account_id_v2=333")!=true)10041 else 0,"data" to when{
    url.contains("genshin")->obj("current_resin" to 100,"max_resin" to 200,"resin_recovery_time" to "48000")
    url.contains("hkrpg")->obj("current_stamina" to 100,"max_stamina" to 300,"stamina_recover_time" to 72000)
    else->obj("energy" to obj("progress" to obj("current" to 100,"max" to 300),"restore" to 600))
   }))
  }
 }
 private fun html(role:Role,promotion:Boolean=false):String{
  if(promotion)return "<html><body>原神战绩轻松查 扫码下载米游社</body></html>"
  return """
  <html><head><meta charset="utf-8"><script>
  window.callbacks={};window.iframeLeak=false;
  window.mhyWebBridge=(name,value)=>{const resolve=callbacks[name];if(resolve){delete callbacks[name];resolve(value);}};
  window.callNative=(method,payload)=>new Promise(resolve=>{const callback='bbs_callback_'+Object.keys(callbacks).length+'_'+method;callbacks[callback]=resolve;MiHoYoJSInterface.postMessage(JSON.stringify({method,payload,callback}));});
  window.bridgeWasPresentAtHead=!!window.MiHoYoJSInterface;
  </script></head><body>正在加载公开页面夹具<script>
  (async()=>{
   const headers=await callNative('getHTTPRequestHeaders',{});
   const cookie=await callNative('getCookieInfo',{});
   const ds=await callNative('getDS2',{query:{role_id:'ROLE_ID',server:'SERVER'},body:''});
   const unsupported=await callNative('getActionTicket',{});
   await new Promise(resolve=>{const xhr=new XMLHttpRequest();xhr.open('GET','https://api-takumi.mihoyo.com/event/toolcomsrv/risk/createGeetest');xhr.onloadend=resolve;xhr.send();});
   const noteHeaders={...headers.data,DS:ds.data.DS};delete noteHeaders['User-Agent'];
   const response=await fetch('NOTE_URL',{credentials:'include',headers:noteHeaders});
   const note=await response.json();
   window.fixtureResult={head:bridgeWasPresentAtHead,fp:headers.data['x-rpc-device_fp'],uid:cookie.data.ltuid,ua:headers.data['User-Agent'],unsupported:unsupported.retcode,note:note.retcode};
   document.body.innerHTML='<h1>我的角色</h1><p>活跃天数 成就达成数 打开米游社</p><iframe id="child"></iframe>';
   const child=document.getElementById('child');
   child.srcdoc="<script>if(window.HoYoRecord){HoYoRecord.onmessage=()=>parent.postMessage('iframe-leak','*');HoYoRecord.postMessage(JSON.stringify({method:'getCookieInfo',callback:'child_callback'}));}<"+"/script>";
   addEventListener('message',e=>{if(e.data==='iframe-leak')window.iframeLeak=true;});
  })();
  </script></body></html>
  """.replace("ROLE_ID",role.uid).replace("SERVER",role.server).replace("NOTE_URL",Protocol.noteUrl(role))
 }
 private fun findWeb(view:View):WebView? {
  if(view is WebView)return view
  if(view is ViewGroup)for(i in 0 until view.childCount)findWeb(view.getChildAt(i))?.let{return it}
  return null
 }
 private fun evaluate(web:WebView,script:String):Any? {
  val instrumentation=InstrumentationRegistry.getInstrumentation();val latch=CountDownLatch(1);var result:Any?=null
  instrumentation.runOnMainSync{web.evaluateJavascript(script){raw->result=runCatching{JSONTokener(raw).nextValue()}.getOrNull();latch.countDown()}}
  assertTrue("JS callback timeout",latch.await(10,TimeUnit.SECONDS));return result
 }
 private fun awaitEvent(rows:List<JSONObject>,name:String,kind:String?=null,uaSource:String?=null){
  repeat(300){
   if(rows.any{it.optString("event")==name&&(kind==null||it.optString("page_classification")==kind)&&(uaSource==null||it.optString("ua_source")==uaSource)})return
   Thread.sleep(50)
  }
  fail("Missing safe event "+name+" / "+kind)
 }
 @Test fun allThreeGamesUseTheRealProductionBridgeUaCookiesAndOneScopedPageNote(){
  exercise(false,true)
 }
 @Test fun promotionIsVisibleButCannotBeReportedAsCompletedVerificationOrNotes(){
  exercise(true,false)
 }
 @Test fun automaticRefreshWindowClosesOnlyAfterARealInterceptedNoteIsSaved(){exerciseRecovery(false)}
 @Test fun stillRestrictedPageStaysOpenUntilUserCancelsWithoutLosingTheOriginalCode(){exerciseRecovery(true)}
 private fun exerciseRecovery(restricted:Boolean){
  val ins=InstrumentationRegistry.getInstrumentation()
  val activity=ins.startActivitySync(Intent(ins.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
  val name="auto-record-"+UUID.randomUUID()+".db";val store=AccountStore(activity,name)
  val rows=CopyOnWriteArrayList<JSONObject>();val wire=Wire(if(restricted)10041 else 5003,restricted,requireProof=!restricted)
  val api=ApiClient(wire,Diagnostics{rows.add(JSONObject(it))});store.save(account("222"),true)
  val repo=Repository(store,api,{"fixture"});var port:OfficialVerification?=null;var root:FrameLayout?=null
  try{
   ins.runOnMainSync{
    root=FrameLayout(activity);activity.addContentView(root,FrameLayout.LayoutParams(-1,-1))
    port=OfficialVerification(activity,root!!,store,api,resourceOverride={r->
     val body=if(r.isForMainFrame)html(role(Game.GENSHIN)).let{if(restricted)it else it.replace("const noteHeaders={...headers.data,DS:ds.data.DS};","const noteHeaders={...headers.data,DS:ds.data.DS,'x-rpc-challenge':'fixture-server-accepted'};")} else ""
     WebResourceResponse("text/html","UTF-8",ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)))
    });port!!.resume();repo.verifier=port
   }
   val started=repo.startRefresh("","manual")
   if(restricted){
    awaitEvent(rows,"verification_page_classified","overview")
    assertTrue(port!!.isOpen());assertFalse(rows.any{it.optString("event")=="notes_committed"})
    assertEquals("running",repo.dispatch("/api/accounts/refresh/status","GET",JSONObject()).getJSONObject("refresh").getString("status"))
    assertEquals(10041,store.load().single().metadata.getJSONArray("games").getJSONObject(0).getInt("retcode"))
    repo.dispatch("/api/accounts/refresh/action","POST",obj("refreshId" to started.getJSONObject("refresh").getString("refreshId"),"action" to "cancel"))
   }else{
    awaitEvent(rows,"notes_committed")
    val note=store.load().single().metadata.getJSONArray("games").getJSONObject(0)
    assertEquals("ok",note.getString("status"));assertEquals("miyoushe_webview",note.getString("source"))
   }
   ins.waitForIdleSync();assertFalse(port!!.isOpen());assertEquals(2,wire.notes);assertEquals(0,wire.creations)
   assertTrue(rows.any{it.optString("event")=="webview_request_identity"&&it.optBoolean("fp_matches_account")})
   assertTrue(rows.any{it.optString("event")=="request_identity"&&it.optString("source")=="webview"&&it.optBoolean("fp_matches_account")})
   assertEquals(fp("222"),store.load().single().secret!!.getString("deviceFp"))
  }finally{repo.close();ins.runOnMainSync{port?.destroy();root?.let{(it.parent as? ViewGroup)?.removeView(it)};activity.finish()};store.close();activity.deleteDatabase(name)}
 }
 private fun exercise(promotion:Boolean,multiple:Boolean){
  val instrumentation=InstrumentationRegistry.getInstrumentation()
  val activity=instrumentation.startActivitySync(Intent(instrumentation.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
  val name="record-test-"+UUID.randomUUID()+".db";val store=AccountStore(activity,name)
  val rows=CopyOnWriteArrayList<JSONObject>();val api=ApiClient(Wire(),Diagnostics{rows.add(JSONObject(it))})
  val a=account("111");val b=account("222");val c=account("333");store.save(a,true);store.save(b);store.save(c)
  val repo=Repository(store,api,{"fixture"});var root:FrameLayout?=null;var port:OfficialVerification?=null
  var currentRole=role(Game.GENSHIN)
  try{
   instrumentation.runOnMainSync{
    val frame=FrameLayout(activity);root=frame;activity.addContentView(frame,FrameLayout.LayoutParams(-1,-1))
    port=OfficialVerification(activity,frame,store,api,resourceOverride={r->
     val text=if(r.isForMainFrame)html(currentRole,promotion) else ""
     WebResourceResponse(if(r.isForMainFrame)"text/html" else "text/plain","UTF-8",ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
    })
    assertTrue("provider lacks secure Profile/document-start support",port!!.supportsRecordBrowsing())
    port!!.resume();repo.verifier=port
   }
   val cases=if(multiple)listOf("111" to Game.GENSHIN,"111" to Game.STARRAIL,"111" to Game.ZENLESS,"222" to Game.GENSHIN,"333" to Game.GENSHIN,"333" to Game.STARRAIL,"333" to Game.ZENLESS,"111" to Game.GENSHIN) else listOf("111" to Game.GENSHIN)
   for((id,game) in cases){
    rows.clear();currentRole=role(game);repo.dispatch("/api/accounts/select","POST",obj("accountId" to id))
    assertTrue(repo.dispatch("/api/accounts/record/open","POST",obj("accountId" to id,"game" to game.id,"roleUid" to currentRole.uid)).getBoolean("opened"))
    awaitEvent(rows,"verification_page_classified",if(promotion)"promotion" else "overview")
    awaitEvent(rows,"webview_ua_verified",uaSource="navigator")
    val web=run{var value:WebView?=null;instrumentation.runOnMainSync{value=findWeb(root!!)};value!!}
    if(!promotion){
     val value=JSONObject(evaluate(web,"JSON.stringify(window.fixtureResult)") as String)
     assertTrue(value.getBoolean("head"));assertEquals(id,value.getString("uid"))
     assertEquals(fp(id),value.getString("fp"))
     assertEquals(Protocol.USER_AGENT,value.getString("ua"));assertEquals(-1,value.getInt("unsupported"))
     assertEquals(if(game==Game.ZENLESS&&id!="333")10041 else 0,value.getInt("note"))
     awaitEvent(rows,"bridge_method_result")
     awaitEvent(rows,"h5_network_stage")
     awaitEvent(rows,"verification_network_stage")
     assertTrue(rows.any{it.optString("event")=="bridge_method_result"&&it.optString("bridge_method")=="getHTTPRequestHeaders"&&it.optBoolean("callback_delivered")})
     assertTrue(rows.any{it.optString("event")=="verification_network_stage"&&it.optString("endpoint_kind")=="challenge_bootstrap"&&it.optString("fp_check_scope")=="page_reported"})
     assertEquals(false,evaluate(web,"window.iframeLeak"))
     assertTrue(rows.any{it.optString("event")=="bridge_method_called"&&it.optString("bridge_method")=="getDS2"&&it.optString("bridge_status")=="supported"})
     // A same-document route change preserves the bridge/UA and doesn't manufacture a note commit.
     evaluate(web,"history.pushState({},'',location.pathname+location.search+'#/'+('"+game.id+"'));true")
    }
    assertTrue(rows.any{it.optString("event")=="webview_ua_verified"&&it.optString("ua_source")=="request"&&it.optBoolean("ua_contract_match")})
    assertTrue(rows.any{it.optString("event")=="webview_ua_verified"&&it.optString("ua_source")=="navigator"&&it.optBoolean("ua_navigator_match")})
    assertTrue(rows.any{it.optString("event")=="webview_cookie_scope_checked"&&it.optBoolean("cookie_scope_match")&&it.optBoolean("fp_matches_account")})
    assertFalse(rows.any{it.optString("event") in setOf("notes_committed","verification_completed")})
    instrumentation.runOnMainSync{port!!.cancel()}
    assertTrue(repo.view().getJSONArray("accounts").objects().all{it.getJSONArray("games").length()==0})
    val saved=store.load().first{it.id==id}
    assertEquals(fp(id),saved.secret!!.getString("deviceFp"))
    assertFalse(port!!.isOpen())
   }
   assertEquals(0,(api.transport as Wire).creations)
  }finally{
   repo.close();instrumentation.runOnMainSync{port?.destroy();root?.let{(it.parent as? ViewGroup)?.removeView(it)};activity.finish()}
   store.close();activity.deleteDatabase(name)
  }
 }
}
