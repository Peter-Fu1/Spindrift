package io.github.peterfu.hoyoreminder
import org.json.JSONObject
/** Flat/explicit resign_info wrapper; no recursive guessing and no zero -> price-conflict coercion. */
object ResignRules {
 const val COST=150
 private fun number(v:Any?):Int?=when(v){is Int->v.takeIf{it in 0..1000000};is Long->v.takeIf{it in 0..1000000}?.toInt();is String->v.takeIf{Regex("[0-9]{1,7}").matches(it)}?.toIntOrNull()?.takeIf{it<=1000000};else->null}
 fun parse(original:JSONObject,info:JSONObject,today:String):JSONObject {
  val nested=original.optJSONObject("resign_info");val data=nested?:original;val schema=if(nested==null)"flat" else "resign_info"
  val fields=listOf("coin_cost","cost","coin_cnt","resign_cnt_daily","resign_cnt_monthly","resign_limit_daily","resign_limit_monthly","sign_cnt_missed","signed","sign_days")
  val mask=fields.withIndex().sumOf{(i,key)->if(data.has(key)&&!data.isNull(key))1.shl(i) else 0}
  fun present(key:String)=data.has(key)&&!data.isNull(key)&&data.opt(key)!=""
  val prices=listOf("coin_cost","cost").filter{present(it)}.map{number(data.opt(it))}
  val daily=number(data.opt("resign_cnt_daily"));val monthly=number(data.opt("resign_cnt_monthly"));val missed=number(data.opt("sign_cnt_missed"))
  val dailyLimit=if(present("resign_limit_daily"))number(data.opt("resign_limit_daily")) else 1
  val monthLimit=if(present("resign_limit_monthly"))number(data.opt("resign_limit_monthly")) else 3
  val balance=number(data.opt("coin_cnt"));val rule=(data.opt("rule") as? String).orEmpty().take(8192)
  val declared=Regex("""(?:消耗|花费|扣除|需(?:要)?)\s*([0-9]+)\s*(?:个)?米游币""").find(rule)?.groupValues?.get(1)?.toIntOrNull()
  val ambiguous=nested!=null&&fields.any{original.has(it)&&data.has(it)&&original.opt(it).toString()!=data.opt(it).toString()}
  val reason=when{
   ambiguous||prices.any{it==null}||listOf(daily,monthly,missed,dailyLimit,monthLimit).any{it==null}||present("coin_cnt")&&balance==null||present("signed")&&data.opt("signed") !is Boolean->"missing_required_evidence"
   prices.filterNotNull().any{it>0&&it!=COST}||declared!=null&&declared>0&&declared!=COST->"official_explicit_conflict"
   rule.contains("不支持补签")->"unsupported"
   info.optString("today")!=today->"server_day_mismatch"
   !info.optBoolean("isSign")||(present("signed")&&!data.optBoolean("signed"))->"sign_today_first"
   info.optInt("total") !in 1..java.time.LocalDate.parse(today).dayOfMonth||present("sign_days")&&number(data.opt("sign_days"))!=info.optInt("total")->"inconsistent_info"
   missed==0->"no_missed_days"
   dailyLimit==0||monthLimit==0||daily!!>=minOf(dailyLimit!!,1)||monthly!!>=minOf(monthLimit!!,3)->"quota_exhausted"
   balance!=null&&balance<COST->"insufficient_balance"
   prices.none{it==COST}->"known_standard_no_dynamic_price"
   else->"ready"
  }
  val decision=when(reason){"ready","known_standard_no_dynamic_price"->reason;"sign_today_first","no_missed_days","inconsistent_info","unsupported"->"not_eligible";"server_day_mismatch"->"missing_required_evidence";else->reason}
  return obj("eligible" to (reason in setOf("ready","known_standard_no_dynamic_price")),"currency" to "miyou_coin","currencyVerified" to true,"costSource" to "cn_standard","cost" to COST,
   "reason" to reason,"decision" to decision,"balance" to balance,"daily" to daily,"monthly" to monthly,"missed" to missed,"rule" to "cn_standard_live_qualification","schema" to schema,"evidenceMask" to mask)
 }
}
