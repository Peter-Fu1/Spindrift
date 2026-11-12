package io.github.peterfu.hoyoreminder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

/** One normal roundtrip plus CI seed -> force-stop -> restore in distinct Android processes. */
@RunWith(AndroidJUnit4::class)
class ProcessRestartTest {
 @Test fun persistedSnapshotAndUiSurviveOfflineProcessRestart(){
  val ins=InstrumentationRegistry.getInstrumentation();val context=ins.targetContext
  val phase=InstrumentationRegistry.getArguments().getString("stage36Phase")?:"roundtrip"
  val database="stage36-process-fixture.db";val role=Role(Game.GENSHIN,"100000001","cn_gf01","fixture","fixture",60)
  val key="888|"+role.key();val pid=android.os.Process.myPid()
  if(phase in setOf("roundtrip","seed")){
   val store=AccountStore(context,database)
   val cookies=JSONObject().apply{ApiClient.AUTH_NAMES.forEach{put(it,if(it in setOf("account_id_v2","ltuid_v2"))"888" else "fixture")}}
   val note=NoteParser.parse(role,obj("current_resin" to 100,"max_resin" to 200,"resin_recovery_time" to 47540),System.currentTimeMillis()-50000)
   store.save(Account("888",obj("roles" to JSONArray(listOf(role.json())),"games" to JSONArray(listOf(note)),"label" to "fixture"),obj("cookies" to cookies,"deviceId" to "fixture-only","profileKey" to "fixture-only","deviceFp" to "abcdef1234567")),true)
   store.saveUi(obj("order" to JSONArray(listOf("888")),"roleKeys" to JSONArray(listOf(key)),"expanded" to JSONArray(listOf("888")),"mode" to "local","testSeedPid" to pid))
   store.saveCheckin(obj("version" to 1,"roles" to obj((key+"|CN") to obj("enabled" to true,"state" to "blocked","nextAt" to 1900000000000L,"source" to "manual","code" to 10041))))
   store.close()
   val folder=File(context.getExternalFilesDir(null),"stage36").apply{mkdirs()}
   File(folder,"process-seed.json").writeText(obj("pid" to pid,"syntheticOnly" to true).toString())
  }
  if(phase in setOf("roundtrip","restore")){
   val store=AccountStore(context,database)
   val seed=store.loadUi().getInt("testSeedPid")
   if(phase=="restore")assertNotEquals("must be a new process after force-stop",seed,pid)
   var sent=0
   val api=ApiClient(object:Transport{override fun execute(method:String,url:String,headers:Map<String,String>,body:JSONObject?,scope:String):WireResponse{sent++;throw AssertionError("offline restore must never send")}})
   val repo=Repository(store,api,{"fixture"})
   try{
    val a=repo.view().getJSONArray("accounts").getJSONObject(0)
    val note=a.getJSONArray("games").getJSONObject(0);assertEquals("server_snapshot",note.getJSONObject("resourceSnapshot").getString("source"))
    assertEquals(100,note.getInt("current"));assertEquals(47540.0,note.getJSONObject("resourceSnapshot").getDouble("remainingSeconds"),0.0)
    assertEquals("local",repo.view().getJSONObject("uiPreferences").getString("mode"))
    assertEquals("888",repo.view().getString("selectedId"))
    assertNotNull(store.load().single().secret)
    repo.dispatch("/api/resources/estimate","POST",obj("roleKeys" to JSONArray(listOf(key))))
    val ledger=CheckInService(store,api).view().getJSONObject("roles").getJSONObject(key+"|CN")
    assertTrue(ledger.getBoolean("enabled"));assertEquals("blocked",ledger.getString("state"));assertEquals(10041,ledger.getInt("code"))
    assertEquals(1900000000000L,ledger.getLong("nextAt"))
    assertEquals(0,sent)
    val folder=File(context.getExternalFilesDir(null),"stage36").apply{mkdirs()}
    File(folder,"process-restore.json").writeText(obj("seedPid" to seed,"restorePid" to pid,"distinctProcess" to (seed!=pid),"networkRequests" to sent,"snapshotRestored" to true,"uiRestored" to true,"credentialsDecrypt" to true,"checkinRestored" to true).toString())
   }finally{repo.close();store.close();context.deleteDatabase(database)}
  }
 }
}
