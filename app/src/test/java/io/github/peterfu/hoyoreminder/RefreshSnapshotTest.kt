package io.github.peterfu.hoyoreminder
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicInteger

/** Deterministically guards the shared row instead of hoping polling wins a JSON iterator race. */
class RefreshSnapshotTest {
 private class GuardRow(private val ownerLock:Any,private val violations:AtomicInteger):JSONObject(){
  override fun put(name:String,value:Any?):JSONObject {
   if(!Thread.holdsLock(ownerLock))violations.incrementAndGet()
   return super.put(name,value)
  }
 }
 @Test fun allSharedProgressWritesUseTheSnapshotLockIncludingRequestAndChallengeFields(){
  val store=MemoryStore();store.accounts["111"]=fpAccount("111",false)
  val completed=CountDownLatch(1)
  val diagnostics=Diagnostics{if(JSONObject(it).optString("event")=="refresh_completed")completed.countDown()}
  val repo=Repository(store,ApiClient(FixtureTransport().apply{retcode=10041},diagnostics),{"fixture"})
  val release=CountDownLatch(1);val entered=CountDownLatch(1)
  fun field(name:String)=Repository::class.java.getDeclaredField(name).apply{isAccessible=true}.get(repo)
  val executor=field("refreshExecutor") as ExecutorService
  val lock=field("lock")!!
  val violations=AtomicInteger()
  executor.submit{entered.countDown();release.await(5,TimeUnit.SECONDS)}
  assertTrue(entered.await(2,TimeUnit.SECONDS))
  try{
   repo.startRefresh("","manual")
   synchronized(lock){
    val current=field("job") as JSONObject
    val rows=current.getJSONArray("roles")
    assertEquals(1,rows.length())
    val original=rows.getJSONObject(0);val guarded=GuardRow(lock,violations)
    original.keys().forEach{guarded.put(it,original.get(it))}
    rows.put(0,guarded)
   }
   release.countDown()
   assertTrue("refresh fixture did not finish",completed.await(3,TimeUnit.SECONDS))
   assertEquals("shared progress mutated outside serialization lock",0,violations.get())
   val refresh=repo.dispatch("/api/accounts/refresh/status","GET",JSONObject()).getJSONObject("refresh")
   assertEquals("completed",refresh.getString("status"))
   val row=refresh.getJSONArray("roles").getJSONObject(0)
   assertTrue(row.has("request_id"));assertEquals("none",row.getString("challengeKind"))
   assertEquals(10041,row.getInt("retcode"))
  }finally{release.countDown();repo.close()}
 }
}
