package io.github.peterfu.hoyoreminder
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONTokener
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
@RunWith(AndroidJUnit4::class)
class Stage37UiTest{
 private fun find(v:View):WebView?=if(v is WebView)v else if(v is ViewGroup)(0 until v.childCount).firstNotNullOfOrNull{find(v.getChildAt(it))} else null
 private fun js(w:WebView,s:String):Any?{
  val ins=InstrumentationRegistry.getInstrumentation();val latch=CountDownLatch(1);var value:Any?=null
  ins.runOnMainSync{w.evaluateJavascript(s){value=runCatching{JSONTokener(it).nextValue()}.getOrNull();latch.countDown()}}
  assertTrue(latch.await(10,TimeUnit.SECONDS));return value
 }
 @Test fun packagedCheckinHasOwnedTargetsAndNavigationNeverPosts(){
  val ins=InstrumentationRegistry.getInstrumentation();val activity=ins.startActivitySync(Intent(ins.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
  try{
   var web:WebView?=null;ins.runOnMainSync{web=find(activity.window.decorView)};val w=web!!
   repeat(100){if(js(w,"document.querySelector('#groups')?.getAttribute('aria-busy')==='false'")!=true)Thread.sleep(100)}
   assertEquals(true,js(w,"typeof HoYoCheckin==='object'&&document.querySelector('#filter svg')!==null"))
   assertEquals(true,js(w,"""
    const role={game:'genshin',uid:'100000001',serverId:'cn_gf01',server:'天空岛',nickname:'fixture',level:60};
    window.stage40Book={configurationRevision:0,statusRevision:1000,roles:{}};state={accounts:['111','222','333'].map(id=>({id,label:'fixture '+id,roles:id==='222'?[role]:[role,{...role,game:'starrail',serverId:'prod_gf_cn'},{...role,game:'zenless',uid:'10000001',serverId:'prod_gf_cn'}],games:[]})),selectedId:'333'};
    preferences=HoYoUi.normalize({},state.accounts);render();window.stage37Calls=[];
    HoYoPlatform.request=async(path,body)=>{stage37Calls.push({path,body});if(path==='/api/checkin/auto/start'){return {job:{operationId:'a'.repeat(32),total:7,done:7,status:'completed',counts:{already_signed:7}},ledger:stage40Book};}if(path==='/api/checkin/auto/status')return {job:null,ledger:stage40Book};if(path==='/api/checkin/preferences'){stage40Book.configurationRevision++;stage40Book.statusRevision++;for(const k of body.roleKeys)stage40Book.roles[k+'|CN']={enabled:body.enabled};return stage40Book;}if(path==='/api/checkin')return stage40Book;return path==='/api/accounts'?state:path==='/api/checkin/batch/start'?{job:{status:'completed',operationId:'fixture',done:body.roleKeys.length,total:body.roleKeys.length,roles:[]},autoJob:{operationId:'fixture',total:Object.values(stage40Book.roles).filter(x=>x.enabled).length,done:Object.values(stage40Book.roles).filter(x=>x.enabled).length,status:'completed',counts:{already_signed:Object.values(stage40Book.roles).filter(x=>x.enabled).length}},ledger:stage40Book}:{roles:{}};};
    document.querySelector('[data-checkin-account="222"]').click();true;
   """))
   assertEquals(1,(js(w,"document.querySelectorAll('[data-checkin-target]').length") as Number).toInt())
   assertEquals(true,js(w,"document.querySelector('[data-checkin-target]').dataset.checkinTarget.startsWith('222|')"))
   js(w,"document.querySelector('#confirmAccountCheckin').click();true")
   assertEquals(true,js(w,"stage37Calls.filter(x=>x.path==='/api/checkin/batch/start').length===1&&stage37Calls.find(x=>x.path==='/api/checkin/batch/start').body.roleKeys.every(x=>x.startsWith('222|'))"))
   js(w,"document.querySelector('#accountCheckinDialog').close();if(document.querySelector('#menuToggle').getAttribute('aria-expanded')!=='true')document.querySelector('#menuToggle').click();document.querySelector('[data-page=checkin]').click();true")
   assertEquals(true,js(w,"!document.querySelector('#checkinPage').hidden&&document.querySelector('#resourcePage').hidden&&stage37Calls.filter(x=>x.path==='/api/checkin/batch/start').length===1"))
   assertEquals(true,js(w,"document.querySelectorAll('.checkin-group').length===3&&document.querySelectorAll('[data-checkin-select]:checked').length===7"))
   assertEquals(true,js(w,"document.querySelector('[data-checkin-clear]').click();document.querySelectorAll('[data-checkin-select]:checked').length===0&&document.querySelector('#checkinRefresh').disabled"))
   js(w,"document.querySelector('[data-checkin-all]').click();true")
   assertEquals(true,js(w,"document.querySelector('[data-page=resources] svg path')!==null&&document.querySelector('.checkin-history').open===false&&document.querySelector('[data-checkin-resign]').textContent==='补签'"))
   assertEquals(true,js(w,"document.querySelector('#pageTitle').getAttribute('aria-label')==='签到明细'&&document.querySelector('#checkinResign + #chooseCheckinMode')!==null"))
   js(w,"document.querySelector('#chooseCheckinMode').click();true")
   repeat(40){if(js(w,"document.querySelectorAll('#checkinModeOptions [data-auto-checkin]').length!==7")==true)Thread.sleep(50)}
   assertEquals(true,js(w,"document.querySelectorAll('#checkinModeOptions [data-auto-checkin]').length===7&&document.querySelectorAll('#checkinModeOptions fieldset').length===3"))
   js(w,"document.querySelector('#checkinModeOptions [data-auto-checkin-all]').click();true")
   repeat(40){if(js(w,"document.querySelector('#checkinModeOptions input').disabled")==true)Thread.sleep(50)}
   assertEquals(true,js(w,"document.querySelectorAll('#checkinModeOptions [data-auto-checkin]:checked').length===7&&stage37Calls.filter(x=>x.path==='/api/checkin/preferences').length===1&&stage37Calls.filter(x=>x.path==='/api/checkin/batch/start').length===1"))
   js(w,"document.querySelector('#checkinModeDialog').close();document.querySelector('#filter').click();true")
   assertEquals(true,js(w,"document.querySelectorAll('#autoCheckinOptions [data-auto-checkin]:checked').length===7&&document.querySelectorAll('[data-checkin-select]:checked').length===7"))
   js(w,"document.querySelector('#filterDialog').close();true")
   js(w,"document.querySelector('[data-checkin-only]').click();document.querySelector('#checkinRefresh').click();true")
   repeat(40){if(js(w,"stage37Calls.filter(x=>x.path==='/api/checkin/batch/start').length<2")==true)Thread.sleep(50)}
   assertEquals(true,js(w,"stage37Calls.filter(x=>x.path==='/api/checkin/auto/start').length===0&&document.querySelector('#autoCheckinStatus').textContent.includes('7 个已启用角色')"))

   assertEquals(true,js(w,"(()=>{const el=document.querySelector('#autoCheckinStatus'),h=el.querySelector('.status-heading'),r=el.querySelector('.status-result');return r.textContent.startsWith('检查结束')&&r.getBoundingClientRect().top>=h.getBoundingClientRect().bottom-1})()"))

   assertEquals(true,js(w,"(()=>{const r=document.querySelector('#autoCheckinStatus .status-result'),range=document.createRange();range.selectNodeContents(r);return range.getClientRects().length===1&&r.getAttribute('aria-label').includes('未确认')})()"))
   val scaled=CountDownLatch(1)
   ins.runOnMainSync{w.settings.textZoom=180;w.postVisualStateCallback(42,object:WebView.VisualStateCallback(){override fun onComplete(requestId:Long){scaled.countDown()}})}
   assertTrue(scaled.await(10,TimeUnit.SECONDS))
   var fitsLarge=false
   repeat(20){if(!fitsLarge){fitsLarge=js(w,"(()=>{const el=document.querySelector('#autoCheckinStatus');el.fitStatusResult();const range=document.createRange();range.selectNodeContents(el.querySelector('.status-result'));return range.getClientRects().length===1})()")==true;if(!fitsLarge)Thread.sleep(50)}}
   assertTrue("Summary must fit one result row at WebView textZoom180",fitsLarge)
   ins.runOnMainSync{w.settings.textZoom=100}

   assertEquals(true,js(w,"if(document.querySelector('#menuToggle').getAttribute('aria-expanded')!=='true')document.querySelector('#menuToggle').click();HoYoPlatform.back()&&document.querySelector('#menuToggle').getAttribute('aria-expanded')==='false'"))
  }finally{ins.runOnMainSync{activity.finish()}}
 }
}
