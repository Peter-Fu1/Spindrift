package io.github.peterfu.hoyoreminder

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class CheckInScheduleTest {
 @Test fun legacyCheckInWorkIsRetiredWithoutCancellingUnrelatedWork() {
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  val manager=WorkManager.getInstance(context)
  val legacy=listOf("hoyo-checkin-v1","hoyo-checkin-retry-v1")
  val unrelated="migration-unrelated-fixture"
  val names=legacy+unrelated
  try {
   for(name in names){
    val request=OneTimeWorkRequestBuilder<CheckInWorker>().setInitialDelay(1,TimeUnit.DAYS).build()
    manager.enqueueUniqueWork(name,ExistingWorkPolicy.REPLACE,request).result.get(10,TimeUnit.SECONDS)
   }
   CheckInWorker.retire(context)
   manager.cancelUniqueWork("migration-unused-barrier").result.get(10,TimeUnit.SECONDS)
   for(name in legacy){
    assertTrue(manager.getWorkInfosForUniqueWork(name).get(10,TimeUnit.SECONDS).single().state.isFinished)
   }
   assertEquals(1,manager.getWorkInfosForUniqueWork(unrelated).get(10,TimeUnit.SECONDS).count{!it.state.isFinished})
  } finally {
   for(name in names)manager.cancelUniqueWork(name).result.get(10,TimeUnit.SECONDS)
  }
 }
}
