package io.github.peterfu.hoyoreminder
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.webkit.WebView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real packaged local UI and real Android WebView; all card values are synthetic. */
@RunWith(AndroidJUnit4::class)
class CompactCardsTest {
 private fun find(v:View):WebView? {
  if(v is WebView)return v
  if(v is ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i))?.let{return it}
  return null
 }
 private fun js(w:WebView,s:String):Any? {
  val latch=CountDownLatch(1);var result:Any?=null
  InstrumentationRegistry.getInstrumentation().runOnMainSync{w.evaluateJavascript(s){result=runCatching{JSONTokener(it).nextValue()}.getOrNull();latch.countDown()}}
  assertTrue(latch.await(10,TimeUnit.SECONDS));return result
 }
 @Test fun threeCardsFitPhoneViewportWithCachedWarningsAndRealErrorCode() {
  val ins=InstrumentationRegistry.getInstrumentation()
  val activity=ins.startActivitySync(Intent(ins.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
  try{
   var web:WebView?=null
   ins.runOnMainSync{web=find(activity.window.decorView)}
   val w=web!!
   var ready=false
   for(i in 0 until 100){if(js(w,"typeof render==='function'&&document.readyState==='complete'&&document.querySelector('#groups').getAttribute('aria-busy')==='false'")==true){ready=true;break};Thread.sleep(100)}
   assertTrue("packaged UI not ready",ready)
   val fixture="""
    // Keep synthetic card state stable while slow emulator screenshots cross the profile-poll interval.
    clearInterval(profilePoll);stateRevision++;
    const ids=['genshin','starrail','zenless'],now=Date.now();
    const roles=ids.map((game,i)=>({game,uid:'10000000'+i,nickname:'旅行者',server:['天空岛','星穹列车','新艾利都'][i],level:60}));
    const games=ids.map((id,i)=>({id,roleUid:roles[i].uid,status:'ok',current:116,max:i?300:200,updated:now,interval:360,cached:true,stale:true,lastError:'cancelled',dailyCurrent:i?500:4,dailyMax:i?500:4,weeklyRemaining:3,weeklyMax:3,cycleCurrent:i===1?14000:200,cycleMax:i===1?14000:200,task:'已完成',reserve:i===1?100:undefined}));
    games.forEach((g,i)=>{const p=i?360:480;g.resourceSnapshot={version:1,source:'server_snapshot',serverResourceValue:g.current,resourceMax:g.max,observedAtUtc:now,periodSeconds:p,remainingSeconds:(g.max-g.current-1)*p+30,phaseAvailable:true};});
    state={accounts:[{id:'123456789',label:'米游社 123456789',roles,games}],selectedId:'123456789'};
    expanded.add('123456789');render();document.querySelector('#notice').hidden=true;true;
   """
   assertEquals(true,js(w,fixture))
   for(width in listOf(360,393)){
    val density=activity.resources.displayMetrics.density
    ins.runOnMainSync{w.layoutParams=FrameLayout.LayoutParams((width*density).toInt(),(780*density).toInt())}
    Thread.sleep(300)
    val metrics=JSONObject(js(w,"JSON.stringify({width:innerWidth,height:innerHeight,bottom:document.querySelectorAll('.card')[2].getBoundingClientRect().bottom,overflow:document.documentElement.scrollWidth>innerWidth,text:document.querySelectorAll('.card')[1].innerText})") as String)
    assertTrue(metrics.toString(),metrics.getDouble("bottom")<=metrics.getDouble("height"))
    assertFalse(metrics.getBoolean("overflow"));assertTrue(metrics.getString("text").contains("后备开拓力"))
    assertEquals(true,js(w,"[...document.querySelectorAll('.resource-times')].every(e=>Math.abs(e.children[0].getBoundingClientRect().top-e.children[1].getBoundingClientRect().top)<1)"))
    val folder=File(activity.getExternalFilesDir(null),"stage33");folder.mkdirs()
    ins.runOnMainSync{
     val image=Bitmap.createBitmap(w.width,w.height,Bitmap.Config.ARGB_8888);w.draw(Canvas(image))
     File(folder,"compact-"+width+".png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()
    }
   }
   assertEquals(true,js(w,"(()=>{const before=[...document.querySelectorAll('[data-resource-eta]')].map(x=>x.textContent);updateEstimates();updateEstimates();return before.every((x,i)=>x===document.querySelectorAll('[data-resource-eta]')[i].textContent)&&[...document.querySelectorAll('[data-resource-next]')].every(x=>x.textContent.includes('后回复1点'))})()"))
   for(zoom in listOf(125,200)){
    val drawn=CountDownLatch(1);ins.runOnMainSync{w.settings.textZoom=zoom;w.postVisualStateCallback(44L+zoom,object:WebView.VisualStateCallback(){override fun onComplete(requestId:Long){drawn.countDown()}})};assertTrue(drawn.await(10,TimeUnit.SECONDS))
    assertEquals(true,js(w,"document.documentElement.scrollWidth<=innerWidth+1&&[...document.querySelectorAll('.resource-times')].every(x=>x.scrollWidth<=x.clientWidth+1)"))
   }
   ins.runOnMainSync{w.settings.textZoom=100}
   assertEquals(true,js(w,"state.accounts[0].games[2]={...state.accounts[0].games[2],status:'account_restricted',retcode:10041,stale:false};render();document.querySelectorAll('.card')[2].innerText.includes('10041')"))
  }finally{ins.runOnMainSync{activity.finish()}}
 }
}
