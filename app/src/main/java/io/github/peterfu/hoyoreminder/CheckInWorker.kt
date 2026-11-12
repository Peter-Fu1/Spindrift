package io.github.peterfu.hoyoreminder
import android.content.Context
import androidx.work.*
/**
 * Retired worker kept only so WorkManager can still resolve work persisted by older installs.
 *
 * Since 2026-11-03 automatic check-in runs exclusively when the user refreshes the resource
 * overview or the check-in detail page. This class therefore performs no network work: it
 * cancels the legacy unique works and succeeds. Do not re-add periodic/retry scheduling here.
 */
class CheckInWorker(context:Context,params:WorkerParameters):Worker(context,params){
 override fun doWork():Result {retire(applicationContext);return Result.success()}
 companion object{
  private const val PERIODIC="hoyo-checkin-v1"
  private const val RETRY="hoyo-checkin-retry-v1"
  fun retire(context:Context){
   val manager=WorkManager.getInstance(context)
   manager.cancelUniqueWork(PERIODIC);manager.cancelUniqueWork(RETRY)
  }
 }
}
