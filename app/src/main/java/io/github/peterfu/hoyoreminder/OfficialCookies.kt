package io.github.peterfu.hoyoreminder
import org.json.JSONObject
object OfficialCookies {
 const val ORIGIN="https://webstatic.mihoyo.com/"
 fun seed(secret:JSONObject,set:(String,String,(Boolean)->Unit)->Unit,flush:()->Unit,done:(Boolean)->Unit){
  val cookies=DeviceFingerprint.cookies(secret)
  if(runCatching{ApiClient.verifiedCookies(cookies)}.isFailure){done(false);return}
  val names=(cookies.keys().asSequence().filter{it in ApiClient.COOKIE_NAMES}.toList()+listOf("DEVICEFP","DEVICEFP_SEED_ID","DEVICEFP_SEED_TIME")).distinct()
  if(names.isEmpty()){done(false);return}
  var remaining=names.size;var accepted=true
  for(name in names)set(ORIGIN,name+"="+(if(cookies.has(name))cookies.getString(name) else "; Max-Age=0")+"; Domain=.mihoyo.com; Path=/; Secure; HttpOnly"){ok->
   accepted=accepted&&ok;remaining--
   if(remaining==0){if(accepted)flush();done(accepted)}
  }
 }
}
