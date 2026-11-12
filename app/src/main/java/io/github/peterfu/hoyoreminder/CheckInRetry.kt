package io.github.peterfu.hoyoreminder
import org.json.JSONObject
/** Existing per-role ledger, no credentials or independent HTTP/scheduler. */
object CheckInRetry {
 const val VERSION=1
 const val MIN_INTERVAL=60000L
 val soft=setOf("transient","server_unavailable")
 private val kinds=soft+setOf("server_throttle","risk","auth","pending","invalid","server_rejected","legacy_unknown")
 fun normalize(row:JSONObject,now:Long):JSONObject {
  row.optJSONObject("retry")?.let{if(it.optInt("version")==VERSION&&it.optString("kind") in kinds)return it}
  if(row.optString("state") in setOf("blocked","login_expired","failed","unknown","unconfirmed","cooldown")&&row.optLong("nextAt")>now){
   val kind=when{row.optString("state")=="blocked"||row.optInt("code") in setOf(5003,10041,10035,1034,-3101)->"risk";row.optString("state")=="login_expired"->"auth";row.optBoolean("postPending")->"pending";else->"legacy_unknown"}
   return obj("version" to VERSION,"kind" to kind,"setAt" to row.optLong("checkedAt",now),"until" to row.optLong("nextAt"),"durationSec" to maxOf(0L,(row.optLong("nextAt")-row.optLong("checkedAt",now))/1000),"failures" to 0,"source" to "legacy","phase" to "initial_status").also{row.put("retry",it)}
  };return obj()
 }
 fun remaining(p:JSONObject,now:Long)=maxOf(0L,(p.optLong("until")-now+999)/1000)
 fun gate(row:JSONObject,now:Long):String? {val p=normalize(row,now);return when{p.optString("kind")=="risk"->"blocked";p.optString("kind")=="auth"->"login_expired";p.optString("kind")=="pending"||row.optBoolean("postPending")->"unconfirmed";p.optLong("until")>now->if(p.optString("kind")=="server_throttle")"rate_limited" else "cooldown";else->null}}
 fun apply(row:JSONObject,kind:String,now:Long,source:String,phase:String,retryAfter:Long=0):Pair<JSONObject,Boolean>{
  val old=row.optJSONObject("retry")?.takeIf{it.optInt("version")==VERSION&&it.optString("kind") in kinds}?:obj();if(old.optString("kind")==kind&&old.optLong("until")>now&&!(retryAfter>0&&now+retryAfter>old.optLong("until")))return old to false
  val failures=if(old.optString("kind")==kind)minOf(3,old.optInt("failures")+1) else 1
  val duration=maxOf(when(kind){in soft->minOf(900L,300L*failures);"server_throttle"->3600L;"pending","invalid","server_rejected"->900L;else->7200L},(retryAfter+999)/1000)
  val businessDay=CheckInProtocol.day(now);val attempts=if(old.optString("businessDay")==businessDay)old.optInt("attempts")+1 else 1
  val effective=if(kind=="server_throttle"&&retryAfter>0)maxOf(60L,(retryAfter+999)/1000) else duration
  val p=obj("version" to VERSION,"kind" to kind,"setAt" to now,"until" to now+effective*1000,"durationSec" to effective,"failures" to failures,"attempts" to attempts,"businessDay" to businessDay,"autoPaused" to (attempts>=6),"source" to source,"phase" to phase);if(old.optString("kind")==kind)for(key in listOf("retcode","httpStatus","serverWaitUntil"))if(old.has(key))p.put(key,old.get(key));row.put("retry",p).put("nextAt",p.getLong("until"));return p to true
 }
 fun fields(row:JSONObject,now:Long):JSONObject {val p=normalize(row,now);return obj("policy_version" to VERSION,"cooldown_kind" to p.optString("kind","none"),"cooldown_source" to p.optString("source","none"),"cooldown_duration_sec" to p.optLong("durationSec"),"remaining_sec" to remaining(p,now),"cooldown_until" to p.optLong("until"),"failure_count" to p.optInt("failures"),"inherited" to (p.optLong("setAt",now)<now))}
 fun rolloverPending(row:JSONObject,today:String){val posted=row.optString("postedDay");if(row.optBoolean("postPending")&&Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(posted)&&posted<today){val history=row.optJSONArray("pendingHistory")?:org.json.JSONArray();history.put(obj("day" to posted,"postedAt" to row.optLong("postedAt"),"state" to "unconfirmed"));while(history.length()>7)history.remove(0);row.put("pendingHistory",history).put("postPending",false);if(row.optJSONObject("retry")?.optString("kind")=="pending")row.remove("retry")}}
 fun nextDue(book:JSONObject,now:Long=System.currentTimeMillis()):Long?=book.optJSONObject("roles")?.let{rows->rows.keys().asSequence().map{rows.getJSONObject(it)}.filter{it.optBoolean("enabled")&&!(it.optJSONObject("retry")?.optBoolean("autoPaused")==true&&it.optJSONObject("retry")?.optString("businessDay")==CheckInProtocol.day(now))&&(it.optJSONObject("retry")?.optString("kind") in soft+setOf("pending","server_throttle","legacy_unknown","invalid","server_rejected")||it.optString("state") in setOf("skipped_before_0001","throttled","server_day_mismatch"))}.map{maxOf(it.optLong("nextAt"),it.optLong("lastAutoAt")+MIN_INTERVAL)}.minOrNull()}
 fun retryAfter(value:String?,now:Long):Long {
  if(value==null)return 0
  value.trim().toLongOrNull()?.let{return it.coerceIn(0,31536000)*1000}
  return runCatching{(java.time.ZonedDateTime.parse(value,java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()-now).coerceIn(0,31536000000)}.getOrDefault(0)
 }
}
class CheckInHttpFailure(val status:Int,val retryAfter:Long=0,val risk:Boolean=false):Exception()
