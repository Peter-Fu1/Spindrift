package io.github.peterfu.hoyoreminder
import org.json.JSONArray
import org.json.JSONObject

object UiPreferences {
 fun key(accountId:String,role:Role)=accountId+"|"+role.key()
 fun keys(accounts:Collection<Account>)=accounts.flatMap{a->a.metadata.optJSONArray("roles")?.objects().orEmpty().map{key(a.id,Role.fromSaved(it))}}
 fun normalize(value:JSONObject,accounts:Collection<Account>):JSONObject {
  val ids=accounts.map{it.id};val roles=keys(accounts).toSet()
  fun strings(name:String)=value.optJSONArray(name)?.let{a->(0 until a.length()).mapNotNull{a.opt(it) as? String}.distinct().take(2048)}
  val order=strings("order").orEmpty().filter{it in ids}.toMutableList();ids.filterNot{it in order}.forEach{order.add(it)}
  return obj("version" to 1,"order" to JSONArray(order),"accountIds" to strings("accountIds")?.filter{it in ids}?.let{JSONArray(it)},
   "roleKeys" to strings("roleKeys")?.filter{it in roles}?.let{JSONArray(it)},"expanded" to JSONArray(strings("expanded").orEmpty().filter{it in ids}),
   "mode" to if(value.optString("mode")=="local")"local" else "server")
 }
 fun targets(body:JSONObject,accounts:Collection<Account>):Set<String>? {
  if(!body.has("roleKeys"))return null
  val a=body.optJSONArray("roleKeys")?:throw PublicError("RESPONSE_INVALID")
  if(a.length()>2048)throw PublicError("RESPONSE_INVALID")
  val valid=keys(accounts).toSet()
  val targets=(0 until a.length()).map{a.opt(it) as? String?:throw PublicError("RESPONSE_INVALID")}
  if(targets.any{it !in valid}||targets.distinct().size!=targets.size)throw PublicError("RESPONSE_INVALID")
  return targets.toSet()
 }
}
object ResourceSnapshots {
 fun attach(role:Role,note:JSONObject,remaining:Double?):JSONObject {
  val period=if(role.game==Game.GENSHIN)480 else 360
  val current=note.getInt("current");val max=note.getInt("max");val deficit=max-current
  // A rounded full-recovery duration describes the next tick phase, not an average tick interval.
  val phase=remaining?.minus((deficit-1).toDouble()*period)
  val usable=deficit==0||phase!=null&&phase>0&&phase<=period
  return note.put("serverId",role.server).put("lastSuccessfulFetchAt",note.getLong("updated"))
   .put("resourceSnapshot",obj("version" to 1,"source" to "server_snapshot","serverResourceValue" to current,"resourceMax" to max,
    "observedAtUtc" to note.getLong("updated"),"periodSeconds" to period,"remainingSeconds" to remaining,
    "phaseAvailable" to usable)).put("interval",period)
 }
}
