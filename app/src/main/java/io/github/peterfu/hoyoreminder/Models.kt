package io.github.peterfu.hoyoreminder

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom

fun obj(vararg pairs: Pair<String, Any?>): JSONObject = JSONObject().apply { pairs.forEach { put(it.first,it.second ?: JSONObject.NULL) } }
fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
fun JSONObject.number(name: String): Double? = (opt(name) as? Number)?.toDouble()?.takeIf { it.isFinite() && it>=0 && it<=1_000_000 }
fun JSONObject.copyJson() = JSONObject(toString())
class PublicError(val code: String, val retcode: Int? = null, val httpStatus:Int? = null, val fpResultCode:Int? = null): Exception(code)
fun publicExceptionCategory(e:Exception):String=when((e as? PublicError)?.code) {
 "AUTH_EXPIRED"->"auth_expired";"NETWORK_ERROR"->"network_error";"SAVE_FAILED"->"session_persist_failed"
 "REFRESH_COOLDOWN"->"upstream_rate_limited";"CANCELLED","QR_EXPIRED"->"cancelled"
 "RESPONSE_INVALID"->"response_invalid";"ACCOUNT_RISK","UPSTREAM_REJECTED"->"upstream_rejected"
 else->"local_error"
}
fun publicMessage(code: String): String = when(code) {
 "FP_UPSTREAM_REJECTED" -> "官方未接受设备指纹申请；旧正式指纹和已保存数据保留。"
 "FP_RESPONSE_INVALID" -> "指纹响应未通过严格校验，未更新正式指纹。"
 "FP_HTTP_REJECTED" -> "指纹服务 HTTP 请求未被接受，未重试。"
 "FP_TIMEOUT" -> "指纹申请超过15秒，已终止；请求上下文保留。"
 "FP_COOKIE_POLICY" -> "当前网络存在隐式 Cookie 处理器，为避免发送认证信息，已停止指纹申请。"
 "NO_REFRESH_LOG" -> "本次运行还没有刷新记录，请先刷新一次或导出全部日志。"
 "QR_EXPIRED" -> "二维码已过期或已取消，请重新生成。"
 "AUTH_EXPIRED" -> "认证已失效，请重新扫码登录；缓存数据保留。"
 "ACCOUNT_RISK" -> "官方返回5003，请在米游社官方App检查同一角色；不会自动重试。"
 "RECORD_ENTRY_INVALID" -> "官方战绩入口与当前游戏或角色不匹配，已阻止加载。"
 "PROFILE_UNSUPPORTED" -> "系统WebView不支持安全多账号Profile，请更新系统WebView；缓存和原生API仍可使用。"
 "VERIFICATION_UNAVAILABLE" -> "本版本尚未确认可用的外部官方验证入口。请导出诊断；可在米游社官方 App 检查同一角色后手动重试。"
 "VERIFICATION_REJECTED" -> "官方未接受本次人工验证，原便笺错误保留。"
 "CHALLENGE_NOT_REQUIRED" -> "战绩验证服务本次未提供挑战；不代表便笺已恢复，请在官方App检查同一角色。"
 "VERIFICATION_PENDING" -> "已有官方验证页面，请先完成或关闭。"
 "REFRESH_IN_PROGRESS" -> "已有刷新或账号操作，请先完成或取消。"
 "REFRESH_COOLDOWN" -> "刚刚操作过，请稍后重试。"
 "CANCELLED" -> "本次操作已取消。"
 "RESPONSE_INVALID" -> "官方响应未通过校验，本次未更新数据。"
 "SAVE_FAILED" -> "本地安全存储失败，本次结果未提交。"
 "BRIDGE_UNSUPPORTED" -> "系统WebView缺少安全消息通道，请更新系统WebView。"
 else -> "操作失败或网络不可用，请稍后重试；上次有效数据已保留。"
}
enum class Game(val id:String,val biz:String,val path:String,val accent:String,val resource:String) {
 GENSHIN("genshin","hk4e_cn","/game_record/app/genshin/api/dailyNote","#d9955f","原粹树脂"),
 STARRAIL("starrail","hkrpg_cn","/game_record/app/hkrpg/api/note","#7768d9","开拓力"),
 ZENLESS("zenless","nap_cn","/event/game_record_zzz/api/zzz/note","#4c9f84","电量");
 companion object { fun byId(id:String)=entries.firstOrNull{it.id==id} }
}
data class Role(val game:Game,val uid:String,val server:String,val nickname:String,val region:String,val level:Int) {
 fun key()="${game.id}:$uid:$server"
 fun json()=obj("game" to game.id,"uid" to uid,"serverId" to server,"server" to region,"nickname" to nickname,"level" to level)
 companion object {
  fun valid(game:Game,uid:String,server:String):Boolean {
   if(!Regex("[1-9][0-9]{7,23}").matches(uid))return false
   val prefix=uid.dropLast(8)
   return when(game) {
    Game.GENSHIN -> (server=="cn_gf01"&&prefix in listOf("1","2","3"))||(server=="cn_qd01"&&prefix=="5")
    Game.STARRAIL -> (server=="prod_gf_cn"&&prefix in listOf("1","2"))||(server=="prod_qd_cn"&&prefix=="5")
    Game.ZENLESS -> uid.length==8&&server=="prod_gf_cn"
   }
  }
  fun fromApi(value:JSONObject):Role? {
   val game=Game.entries.firstOrNull{it.biz==value.optString("game_biz")}?:return null
   val uid=value.optString("game_uid");val server=value.optString("region")
   if(!valid(game,uid,server))throw PublicError("RESPONSE_INVALID")
   return Role(game,uid,server,value.optString("nickname","角色").take(80),value.optString("region_name",server).take(80),value.optInt("level").coerceIn(0,1000))
  }
  fun fromSaved(value:JSONObject):Role {
   val game=Game.byId(value.getString("game"))?:throw PublicError("RESPONSE_INVALID")
   val uid=value.getString("uid");val server=value.getString("serverId")
   if(!valid(game,uid,server))throw PublicError("RESPONSE_INVALID")
   return Role(game,uid,server,value.optString("nickname"),value.optString("server",server),value.optInt("level"))
  }
 }
}
object NoteParser {
 fun status(code:Int)=when(code) {5003->"account_risk";10041->"account_restricted";1034,10035->"risk_control_required";10001,-100->"auth_expired";10102->"notes_not_enabled";-10001->"request_invalid";10101->"upstream_rate_limited";else->"upstream_rejected"}
 fun parse(role:Role,data:JSONObject,now:Long=System.currentTimeMillis()):JSONObject {
  val current:Double?;val maximum:Double?;val remaining:Double?
  fun seconds(v:Any?):Double?=(if(v is String)v.toDoubleOrNull() else (v as? Number)?.toDouble())?.takeIf{it.isFinite()&&it>=0&&it<=1e9}
  when(role.game) {
   Game.GENSHIN->{current=data.number("current_resin");maximum=data.number("max_resin");remaining=seconds(data.opt("resin_recovery_time"))}
   Game.STARRAIL->{current=data.number("current_stamina");maximum=data.number("max_stamina");remaining=seconds(data.opt("stamina_recover_time"))}
   Game.ZENLESS->{val energy=data.optJSONObject("energy");val p=energy?.optJSONObject("progress");current=p?.number("current");maximum=p?.number("max");remaining=seconds(energy?.opt("restore"))}
  }
  if(current==null||maximum==null||maximum<=0||current>maximum||current%1.0!=0.0||maximum%1.0!=0.0)throw PublicError("RESPONSE_INVALID")
  val interval=if(current==maximum)when(role.game){Game.GENSHIN->480.0;else->360.0}else remaining?.div(maximum-current)?.takeIf{it in 1.0..86400.0}
  val modern=data.optJSONObject("daily_task")
  val daily=when(role.game){Game.GENSHIN->obj("current" to (modern?.number("finished_num")?:data.number("finished_task_num")),"max" to (modern?.number("total_num")?:data.number("total_task_num")));Game.STARRAIL->obj("current" to data.number("current_train_score"),"max" to data.number("max_train_score"));Game.ZENLESS->data.optJSONObject("vitality")?:JSONObject()}
  val done=if(role.game==Game.GENSHIN)(modern?.opt("is_extra_task_reward_received")?:data.opt("is_extra_task_reward_received")) as? Boolean else daily.number("current")?.let{c->daily.number("max")?.takeIf{it>0}?.let{c>=it}}
  val task=when(done){true->"done";false->"pending";else->"unknown"}
  val record=obj("id" to role.game.id,"roleUid" to role.uid,"nickname" to role.nickname,"server" to role.region,"level" to role.level,"current" to current.toInt(),"max" to maximum.toInt(),"interval" to interval,"updated" to now,"status" to "ok","source" to "android_api","cached" to false,"stale" to false,"taskState" to task,"task" to when(task){"done"->"已完成";"pending"->"待完成";else->"状态未知"},"accent" to role.game.accent,"resource" to role.game.resource,"dailyCurrent" to daily.number("current"),"dailyMax" to daily.number("max"))
  when(role.game) {
   Game.GENSHIN->record.put("weeklyRemaining",data.number("remain_resin_discount_num")).put("weeklyMax",data.number("resin_discount_num_limit"))
   Game.STARRAIL->record.put("weeklyRemaining",data.number("weekly_cocoon_cnt")).put("weeklyMax",data.number("weekly_cocoon_limit")).put("cycleCurrent",data.number("current_rogue_score")).put("cycleMax",data.number("max_rogue_score")).put("reserve",data.number("current_reserve_stamina"))
   Game.ZENLESS->{val bounty=(data.optJSONObject("hollow_zero")?.optJSONObject("bounty_commission")?:data.optJSONObject("bounty_commission"));record.put("cycleCurrent",bounty?.number("num")?:bounty?.number("cur_completed")).put("cycleMax",bounty?.number("total"))}
  }
  return ResourceSnapshots.attach(role,record,remaining)
 }
}
object Protocol {
 const val USER_AGENT="Mozilla/5.0 (Linux; Android 15) Mobile miHoYoBBS/2.102.1"
 const val VERSION="2.102.1" // Pinned public protocol profile from Windows; not a claim of latest official App.
 val random=SecureRandom()
 fun md5(text:String)=MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
 fun ds2(query:String,timestamp:Long=System.currentTimeMillis()/1000,nonce:Int=100001+random.nextInt(100000),body:String=""):String {
  val salt="xV8v4Qu54lUKrEYFZkJhB8cuOh9Asafs" // Public DS2 protocol constant, never a personal credential.
  return "$timestamp,$nonce,"+md5("salt=$salt&t=$timestamp&r=$nonce&b=$body&q=$query")
 }
 fun noteUrl(role:Role)="https://api-takumi-record.mihoyo.com${role.game.path}?role_id=${role.uid}&server=${role.server}"
 fun matchesNote(url:String,role:Role):Boolean=runCatching {
  val u=URI(url);val pairs=(u.rawQuery?:"").split("&").map{it.split("=",limit=2)}
  u.scheme=="https"&&u.host=="api-takumi-record.mihoyo.com"&&u.port in listOf(-1,443)&&u.userInfo==null&&u.path==role.game.path&&pairs.size in 2..10&&pairs.all{it.size==2}&&pairs.map{it[0]}.toSet().size==pairs.size&&pairs.associate{it[0] to it[1]}["role_id"]==role.uid&&pairs.associate{it[0] to it[1]}["server"]==role.server
 }.getOrDefault(false)
 fun trustedPage(url:String):Boolean=runCatching{val u=URI(url);u.scheme=="https"&&u.host in setOf("webstatic.mihoyo.com","act.mihoyo.com")&&u.userInfo==null&&u.port in listOf(-1,443)}.getOrDefault(false)
}
