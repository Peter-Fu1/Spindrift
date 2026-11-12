package io.github.peterfu.hoyoreminder

import org.json.JSONObject
import java.net.URLEncoder

/** Observed in official m_bf05806d.js (v3.0.17); metadata, NEVER a solved challenge. GS/SR remain untouched. */
object ZzzH5Headers {
 const val PAGE="v3.0.17_#/zzz"
 fun apply(base:Map<String,String>,secret:JSONObject,role:Role,page:String=PAGE):Map<String,String>{
  require(role.game==Game.ZENLESS&&Role.valid(role.game,role.uid,role.server))
  val account=ApiClient.verifiedCookies(secret.getJSONObject("cookies"))
  require(page==PAGE||page=="v3.0.17_#/zzz/notebook")
  val h=NotesAdapter.headers(base,Game.ZENLESS).toMutableMap()
  val fields=secret.optJSONObject("deviceFpContext")?.optJSONObject("extFields")
  h["x-rpc-platform"]="2";h["x-rpc-page"]=page
  h["x-rpc-app_version"]=Protocol.VERSION
  h["x-rpc-device_name"]=URLEncoder.encode(fields?.optString("model")?:"","UTF-8").replace("+","%20")
  h["x-rpc-sys_version"]=fields?.optString("osVersion")?:""
  h["x-rpc-lrsag"]=""
  h["X-Requested-With"]="com.mihoyo.hyperion"
  h["x-rpc-geetest_ext"]=obj("viewUid" to account,"server" to role.server,"gameId" to 8,"page" to page,
   "isHost" to 1,"viewSource" to 3,"actionSource" to 127).toString()
  return h
 }
}
