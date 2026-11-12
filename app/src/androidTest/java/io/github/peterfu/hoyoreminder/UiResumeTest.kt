package io.github.peterfu.hoyoreminder
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Production packaged UI + actual Android pause/resume/recreation. Only JS account data is synthetic. */
@RunWith(AndroidJUnit4::class)
class UiResumeTest {
 private fun find(v:View):WebView?=if(v is WebView)v else if(v is ViewGroup)(0 until v.childCount).firstNotNullOfOrNull{find(v.getChildAt(it))} else null
 private fun js(w:WebView,script:String):Any?{
  val latch=CountDownLatch(1);var result:Any?=null
  InstrumentationRegistry.getInstrumentation().runOnMainSync{w.evaluateJavascript(script){result=runCatching{JSONTokener(it).nextValue()}.getOrNull();latch.countDown()}}
  assertTrue(latch.await(10,TimeUnit.SECONDS));return result
 }
 private fun ready(w:WebView){repeat(100){if(js(w,"document.querySelector('#groups')?.getAttribute('aria-busy')==='false'")==true)return;Thread.sleep(100)};fail("UI startup timeout")}
 @Test fun backgroundResumeRecomputesResourceWithoutReplacingServerSnapshotOrOfficialError(){
  val ins=InstrumentationRegistry.getInstrumentation()
  var activity=ins.startActivitySync(Intent(ins.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
  try{
   var web:WebView?=null;ins.runOnMainSync{web=find(activity.window.decorView)};val w=web!!;ready(w)
   val fixture="""
    const t=1800000000000;Date.now=()=>t+40000;
    const role={game:'genshin',uid:'100000001',serverId:'cn_gf01',server:'天空岛',nickname:'fixture',level:60};
    const note={id:'genshin',roleUid:role.uid,serverId:role.serverId,status:'ok',current:100,max:200,updated:t,lastSuccessfulFetchAt:t,stale:true,lastError:'account_restricted',retcode:10041,resourceSnapshot:{version:1,source:'server_snapshot',serverResourceValue:100,resourceMax:200,observedAtUtc:t,periodSeconds:480,remainingSeconds:47540,phaseAvailable:true}};
    state={accounts:[{id:'888',label:'fixture',roles:[role],games:[note]}],selectedId:'888'};
    preferences=HoYoUi.normalize({mode:'local'},state.accounts);expanded.add('888');render();
    window.fixtureSnapshot=JSON.stringify(note);window.fixturePaths=[];
    window.HoYoPlatform.request=async path=>{fixturePaths.push(path);return path==='/api/checkin/auto/status'?{job:null,ledger:{roles:{}}}:state;};
    document.querySelector('.card-refresh').click();true;
   """
   assertEquals(true,js(w,fixture))
   ins.waitForIdleSync()
   assertEquals("101",js(w,"document.querySelector('[data-resource-value]').firstChild.textContent"))
   ins.runOnMainSync{ins.callActivityOnPause(activity)}
   assertEquals(true,js(w,"Date.now=()=>1800000500000;true"))
   ins.runOnMainSync{ins.callActivityOnResume(activity)}
   assertEquals("102",js(w,"document.querySelector('[data-resource-value]').firstChild.textContent"))
   assertEquals(true,js(w,"JSON.stringify(state.accounts[0].games[0])===fixtureSnapshot&&fixturePaths.every(p=>['/api/resources/estimate','/api/checkin','/api/checkin/auto/status'].includes(p))&&document.querySelector('.card').innerText.includes('10041')"))
   // Recreate actual Activity. Persistent fixture database is independently checked by ProcessRestartTest.
   val monitor=ins.addMonitor(MainActivity::class.java.name,null,false)
   ins.runOnMainSync{activity.recreate()}
   val recreated=ins.waitForMonitorWithTimeout(monitor,15000) as? MainActivity
   ins.removeMonitor(monitor);assertNotNull("Activity recreation did not resume",recreated);activity=recreated!!
   ins.runOnMainSync{web=find(activity.window.decorView)};ready(web!!)
   assertEquals(true,js(web!!,"typeof HoYoResource.estimateResource==='function'&&document.querySelector('#filter')!==null"))
  }finally{ins.runOnMainSync{activity.finish()}}
 }
}
