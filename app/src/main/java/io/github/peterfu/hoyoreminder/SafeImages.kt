package io.github.peterfu.hoyoreminder
import java.net.URI

object SafeImages {
 val hosts=setOf("upload-bbs.mihoyo.com","bbs-static.miyoushe.com","act-webstatic.mihoyo.com","act-upload.mihoyo.com","api-static.mihoyo.com")
 data class Resolution(val url:String?,val reason:String,val kind:String,val present:Boolean)
 fun url(value:String):String?=runCatching{
  if(value.length !in 1..2048||value.any{it<' '})return null
  val raw=if(value.startsWith("//"))"https:"+value else value
  val u=URI(raw)
  if(!u.scheme.equals("https",true)||u.host?.lowercase() !in hosts||u.userInfo!=null||u.port !in listOf(-1,443)||u.fragment!=null)return null
  val query=java.net.URLDecoder.decode(u.rawQuery.orEmpty(),"UTF-8")
  if(Regex("(?i)(token|cookie|device_fp|seed|ticket|uid)=").containsMatchIn(query))return null
  "https://"+u.host.lowercase()+(if(u.port==443)":443" else "")+u.rawPath.orEmpty()+(u.rawQuery?.let{"?"+it}?:"")
 }.getOrNull()
 fun reason(value:String):String {
  if(value.isBlank())return "missing"
  val u=runCatching{URI(if(value.startsWith("//"))"https:"+value else value)}.getOrNull()?:return "malformed"
  if(!u.scheme.equals("https",true))return "scheme"
  if(u.host?.lowercase() !in hosts)return "host"
  return if(url(value)==null)"unsafe_component" else "accepted"
 }
 /** CN avatar mapping confirmed in Hutao UserInfo.AvatarUri; never guess an arbitrary CDN. */
 fun passport(user:org.json.JSONObject):Resolution {
  val ext=user.optJSONObject("avatar_ext");val type=ext?.optInt("avatar_type",-1)
  val hd=ext?.optJSONArray("hd_resources")?.objects()?.firstOrNull{it.optInt("format",-2)==type}?.optString("url")
  for((kind,value) in listOf("avatar_ext" to hd,"avatar_url" to user.optString("avatar_url"))){
   url(value.orEmpty())?.let{return Resolution(it,"accepted",kind,true)}
  }
  val id=user.opt("avatar")?.toString()?.takeIf{Regex("[0-9]{1,8}").matches(it)}
  if(id!=null)return Resolution("https://bbs-static.miyoushe.com/avatar/avatar"+id+".png","accepted","avatar_id",true)
  val raw=hd?.takeIf{it.isNotBlank()}?:user.optString("avatar_url")
  return Resolution(null,reason(raw),if(hd!=null)"avatar_ext" else "avatar_url",raw.isNotBlank())
 }
}
