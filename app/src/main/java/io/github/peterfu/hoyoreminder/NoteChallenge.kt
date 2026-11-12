package io.github.peterfu.hoyoreminder

import org.json.JSONObject
import java.util.Base64

/** An upstream challenge is held in memory for one role, never in public metadata or storage. */
class NoteChallenge private constructor(val session:String,val config:JSONObject,val version:Int,val created:Long,val kind:String="aigis",val owner:String="",val roleKey:String="",val cookieUpdates:JSONObject=JSONObject()) {
 fun fresh(now:Long=System.currentTimeMillis())=now-created in 0..180000
 fun checkedAnswer(answer:JSONObject,now:Long=System.currentTimeMillis()):JSONObject {
  if(!fresh(now))throw PublicError("CANCELLED")
  val keys=if(version==3)listOf("geetest_challenge","geetest_validate","geetest_seccode") else listOf("captcha_id","lot_number","pass_token","gen_time","captcha_output")
  if(keys.any{val v=answer.opt(it);v !is String||v.isBlank()||v.length>8192||v.any{c->c=='\r'||c=='\n'}})throw PublicError("RESPONSE_INVALID")
  if(version==4&&answer.getString("captcha_id")!=config.getString("gt"))throw PublicError("RESPONSE_INVALID")
  val value=JSONObject();keys.forEach{value.put(it,answer.getString(it))}
  return value
 }
 fun proof(answer:JSONObject,now:Long=System.currentTimeMillis()):Map<String,String> {
  require(kind=="aigis")
  val value=checkedAnswer(answer,now)
  val headers=mutableMapOf("x-rpc-aigis" to (session+";"+Base64.getEncoder().encodeToString(value.toString().toByteArray(Charsets.UTF_8))))
  if(version==3)headers["x-rpc-challenge"]=answer.getString("geetest_challenge")
  return headers
 }
 companion object {
  fun fromRecord(response:WireResponse,accountId:String,role:Role,now:Long=System.currentTimeMillis()):NoteChallenge? {
   if(role.game!=Game.GENSHIN||response.json.optInt("retcode") !in setOf(0,1034,10035,10041,5003))return null
   return runCatching{
    val data=response.json.getJSONObject("data")
    val gt=data.getString("gt");val challenge=data.getString("challenge")
    require(Regex("[A-Za-z0-9_-]{1,128}").matches(gt)&&Regex("[A-Za-z0-9_-]{1,256}").matches(challenge))
    NoteChallenge("",obj("gt" to gt,"challenge" to challenge),3,now,"record",accountId,role.key(),ApiClient.cookiesFrom(response.headers))
   }.getOrNull()
  }
  internal data class AigisConfig(val session:String,val config:JSONObject,val version:Int)
  internal fun aigisConfig(response:WireResponse):AigisConfig? {
   val values=response.headers.filterKeys{it.equals("x-rpc-aigis",true)}.values.flatten()
   val raw=values.singleOrNull()?:return null
   if(raw.length>16384)return null
   return runCatching{
    val e=JSONObject(raw);val session=e.getString("session_id")
    require(session.length in 1..1024&&session.none{it in "\r\n;"})
    val mmt=e.opt("mmt_type");require((mmt is Int||mmt is Long)&&(mmt as Number).toLong()>=0)
    val cfg=when(val data=e.get("data")){is String->JSONObject(data);is JSONObject->data;else->throw IllegalArgumentException()}
    val gt=cfg.getString("gt");require(Regex("[A-Za-z0-9_-]{1,128}").matches(gt))
    val version=if(cfg.optString("challenge").isNotBlank())3 else 4
    val safe=obj("gt" to gt)
    if(version==3){val c=cfg.getString("challenge");require(Regex("[A-Za-z0-9_-]{1,256}").matches(c));safe.put("challenge",c)}
    else{val risk=cfg.optString("risk_type",cfg.optString("riskType","slide"));require(Regex("[A-Za-z0-9_-]{1,64}").matches(risk));safe.put("risk_type",risk)}
    AigisConfig(session,safe,version)
   }.getOrNull()
  }
  fun parse(response:WireResponse,now:Long=System.currentTimeMillis(),owner:String="",role:Role?=null):NoteChallenge? {
   if(response.json.optInt("retcode") !in setOf(1034,10035,10041,5003))return null
   val cfg=aigisConfig(response)?:return null
   return NoteChallenge(cfg.session,cfg.config,cfg.version,now,owner=owner,roleKey=role?.key()?:"")
  }
 }
}
