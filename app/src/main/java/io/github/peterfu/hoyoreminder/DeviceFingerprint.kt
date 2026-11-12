package io.github.peterfu.hoyoreminder

import org.json.JSONObject
import java.util.UUID

/** Account-scoped logical identifiers; an unissued candidate is NEVER a request credential. */
object DeviceFingerprint {
 const val ENDPOINT="https://public-data-api.mihoyo.com/device-fp/api/getFp"
 const val PROFILE_VERSION=2
 const val MAX_BYTES=65536
 const val TIMEOUT_MS=15000
 private val defaults=obj(
  "proxyStatus" to 0,"isRoot" to 0,"romCapacity" to "","deviceName" to "","productName" to "",
  "romRemain" to "","hostname" to "","screenSize" to "","isTablet" to 0,"aaid" to "",
  "model" to "","brand" to "","hardware" to "","deviceType" to "","devId" to "","serialNumber" to "unknown",
  "sdCapacity" to 0,"buildTime" to "","buildUser" to "","simState" to 0,"ramRemain" to "",
  "appUpdateTimeDiff" to 0L,"deviceInfo" to "","vaid" to "","buildType" to "","sdkVersion" to "",
  "ui_mode" to "","isMockLocation" to 0,"cpuType" to "","isAirMode" to 0,"ringMode" to 0,
  "chargeStatus" to 0,"manufacturer" to "","emulatorStatus" to 0,"appMemory" to "","osVersion" to "",
  "vendor" to "unknown","accelerometer" to "","sdRemain" to 0,"buildTags" to "","packageName" to "",
  "networkType" to "unknown","oaid" to "","debugStatus" to 0,"ramCapacity" to "","magnetometer" to "",
  "display" to "","appInstallTimeDiff" to 0L,"packageVersion" to "","gyroscope" to "",
  "batteryStatus" to 0,"hasKeyboard" to 0,"board" to "")
 fun valid(value:String)=Regex("[a-fA-F0-9]{13}").matches(value)&&value!="0000000000000"
 private fun hex(n:Int)=ByteArray(n).also{Protocol.random.nextBytes(it)}.joinToString(""){"%02x".format(it)}
 fun identity(owner:String,device:String,previous:JSONObject?,properties:()->JSONObject,now:Long):JSONObject {
  require(device.isNotBlank())
  val reusable=previous?.takeIf{
   it.optString("bbsDeviceId")==device&&it.optString("owner",owner)==owner&&
    Regex("[a-f0-9]{16}").matches(it.optString("androidId"))&&
    runCatching{UUID.fromString(it.getString("seedId"))}.isSuccess&&
    Regex("[0-9]{13}").matches(it.optString("seedTime"))&&valid(it.optString("candidateFp"))
  }
  val identity=reusable?.copyJson()?:obj("bbsDeviceId" to device,"androidId" to hex(8),
   "seedId" to UUID.randomUUID().toString(),"seedTime" to now.toString(),"candidateFp" to hex(7).take(13))
  identity.put("owner",owner)
  val ext=identity.optJSONObject("extFields")
  if(identity.optInt("profileVersion")!=PROFILE_VERSION||ext==null||ext.keys().asSequence().toSet()!=defaults.keys().asSequence().toSet()||defaults.keys().asSequence().any{k->val v=ext.opt(k);val expected=defaults.get(k);(expected is String&&v !is String)||(expected is Number&&v !is Number)}){
   val measured=properties();val fields=defaults.copyJson()
   defaults.keys().forEach{k->val v=measured.opt(k);val expected=defaults.get(k)
    if((expected is String&&v is String)||(expected is Number&&v is Number))fields.put(k,v)
   }
   identity.put("extFields",fields).put("profileVersion",PROFILE_VERSION).put("board",fields.getString("board"))
  }
  return identity
 }
 fun payload(identity:JSONObject,formal:String?)=obj(
  "device_id" to identity.getString("androidId"),"bbs_device_id" to identity.getString("bbsDeviceId"),
  "seed_id" to identity.getString("seedId"),"seed_time" to identity.getString("seedTime"),
  "platform" to "2","app_name" to "bbs_cn","device_fp" to (formal?.takeIf{valid(it)}?:identity.getString("candidateFp")),
  "ext_fields" to identity.getJSONObject("extFields").toString())
 fun int(value:Any?):Int?=when(value){is Int->value;is Long->value.takeIf{it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()}?.toInt();else->null}
 fun accepted(envelope:JSONObject):String {
  val outer=int(envelope.opt("retcode"));val data=envelope.optJSONObject("data");val inner=int(data?.opt("code"))
  if(outer!=0||inner!=200)throw PublicError("FP_UPSTREAM_REJECTED",outer,fpResultCode=inner)
  return (data?.opt("device_fp") as? String)?.takeIf{valid(it)}?.lowercase()?:throw PublicError("FP_RESPONSE_INVALID")
 }
 fun formal(secret:JSONObject):String? {
  val fp=secret.optString("deviceFp").takeIf{valid(it)}?:return null
  val context=secret.optJSONObject("deviceFpContext")
  if(context!=null&&(context.optString("bbsDeviceId")!=secret.optString("deviceId")||
    context.optString("owner",secret.optJSONObject("cookies")?.optString("account_id_v2")?:"")!=secret.optJSONObject("cookies")?.optString("account_id_v2")))return null
  return fp
 }
 /** One canonical cookie snapshot for both native HTTP and the same account's WebView Profile. */
 fun cookies(secret:JSONObject):JSONObject {
  val cookies=(secret.optJSONObject("cookies")?:JSONObject()).copyJson()
  for(k in listOf("DEVICEFP","DEVICEFP_SEED_ID","DEVICEFP_SEED_TIME"))cookies.remove(k)
  formal(secret)?.let{cookies.put("DEVICEFP",it)
   secret.optJSONObject("deviceFpContext")?.let{ctx->
    cookies.put("DEVICEFP_SEED_ID",ctx.getString("seedId")).put("DEVICEFP_SEED_TIME",ctx.getString("seedTime"))
   }
  }
  return cookies
 }
 fun status(secret:JSONObject?,now:Long):JSONObject {
  val present=secret?.let{formal(it)}!=null;val ctx=secret?.optJSONObject("deviceFpContext")
  val at=if(present&&ctx?.optString("source")=="official_getFp")ctx.optLong("updatedAt").takeIf{it>0} else null
  return obj("present" to present,"source" to if(at!=null)"official_getFp" else if(present)"legacy" else "missing",
   "updatedAt" to at,"profileVersion" to (ctx?.optInt("profileVersion")?:0),
   "pendingProfileVersion" to (secret?.optJSONObject("deviceFpPending")?.optInt("profileVersion")?:0),
   "pending" to (secret?.has("deviceFpPending")==true),"refreshRecommended" to (!present||(at!=null&&now-at>=7*86400000L)))
 }
}
