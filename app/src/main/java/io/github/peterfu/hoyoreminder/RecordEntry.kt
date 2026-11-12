package io.github.peterfu.hoyoreminder

import java.net.URI
import java.net.URLDecoder
import org.json.JSONObject

/** CN record entry contract from Moonward + independently fetched official HTML. Not a server acceptance claim. */
data class RecordEntry(val url:String,val source:String)
object RecordEntries {
 fun gameId(game:Game)=when(game){Game.GENSHIN->2;Game.STARRAIL->6;Game.ZENLESS->8}
 fun catalog(role:Role)=RecordEntry(when(role.game){
  Game.GENSHIN->"https://webstatic.mihoyo.com/app/community-game-records/index.html?mhy_presentation_style=fullscreen&bbs_auth_required=true&game_id=2"
  Game.STARRAIL->"https://webstatic.mihoyo.com/app/community-game-records/rpg/index.html?mhy_presentation_style=fullscreen&game_id=6"
  Game.ZENLESS->"https://act.mihoyo.com/app/mihoyo-zzz-game-record/m.html?game_id=8"
 },"catalog")
 fun origin(game:Game)=if(game==Game.ZENLESS)"https://act.mihoyo.com" else "https://webstatic.mihoyo.com"
 private fun pairs(query:String?):Map<String,String>{
  if(query.isNullOrEmpty())return emptyMap()
  val parts=query.split('&').filter{it.isNotEmpty()}.map{it.split('=',limit=2)}
  val values=parts.map{URLDecoder.decode(it[0],"UTF-8") to URLDecoder.decode(it.getOrElse(1){""},"UTF-8")}
  require(values.map{it.first}.toSet().size==values.size)
  return values.toMap()
 }
 fun allowed(url:String,role:Role):Boolean=runCatching{
  require(url.length<=8192&&url.none{it<' '})
  val u=URI(url);val expected=URI(catalog(role).url)
  require(u.scheme=="https"&&u.userInfo==null&&u.port in listOf(-1,443)&&u.host==expected.host&&u.path==expected.path)
  fun check(params:Map<String,String>){
   params["game_id"]?.let{require(it==gameId(role.game).toString())}
   for(k in listOf("role_id","game_role_id","uid"))params[k]?.let{require(it==role.uid)}
   for(k in listOf("server","region"))params[k]?.let{require(it==role.server)}
  }
  check(pairs(u.rawQuery));check(pairs(u.rawFragment?.substringAfter('?',missingDelimiterValue="")))
  val fragment=u.fragment?:""
  require(!fragment.contains(Regex("(?i)(javascript:|https?://|data:)")))
  when(role.game){
   Game.GENSHIN->require(!fragment.startsWith("/rpg")&&!fragment.startsWith("/zzz"))
   Game.STARRAIL->require(!fragment.startsWith("/ys")&&!fragment.startsWith("/zzz"))
   Game.ZENLESS->require(!fragment.startsWith("/ys")&&!fragment.startsWith("/rpg"))
  }
  true
 }.getOrDefault(false)
 fun target(raw:String,current:String,role:Role):String?=runCatching{
  var value=raw
  if(value.startsWith("mihoyobbs://webview?")){
   val uri=URI(value);value=pairs(uri.rawQuery)["link"]?:return null
  }
  val url=URI(current).resolve(value).toString()
  url.takeIf{allowed(it,role)}
 }.getOrNull()
 fun fromCard(card:JSONObject,role:Role):RecordEntry? {
  if(card.optInt("game_id")!=gameId(role.game))return null
  val uid=card.optString("game_role_id",card.optString("uid"))
  if(uid!=role.uid)return null
  val server=card.optString("region",card.optString("server"))
  if(server.isNotEmpty()&&server!=role.server)throw PublicError("RECORD_ENTRY_INVALID")
  val url=card.optString("url")
  if(!allowed(url,role))throw PublicError("RECORD_ENTRY_INVALID")
  // Explicit browse starts at the original overview, not at a direct daily-note fragment.
  return if(URI(url).fragment?.contains("daily")==true)catalog(role) else RecordEntry(url,"card")
 }
}
