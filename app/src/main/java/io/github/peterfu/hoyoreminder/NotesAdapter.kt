package io.github.peterfu.hoyoreminder

/** Endpoint-specific compatibility contract, compared to Moonward 63f5a4e.
 * No borrowed device/FP, com.mihoyo package identity, geetest ticket or App JSBridge.
 * A matching contract is not proof of real-account retcode=0.
 */
object NotesAdapter {
 fun headers(base:Map<String,String>,game:Game):Map<String,String> {
  val h=base.toMutableMap()
  when(game) {
   Game.GENSHIN->{h["Referer"]="https://webstatic.mihoyo.com/";h["Origin"]="https://webstatic.mihoyo.com";h["x-rpc-tool_verison"]="v7.0.4-gr-cn"}
   Game.STARRAIL->{h["Referer"]="https://webstatic.mihoyo.com/";h["Origin"]="https://webstatic.mihoyo.com";h["x-rpc-tool_verison"]="v4.4.0"}
   Game.ZENLESS->{
    // Moonward's actual ZZZ note adapter does not use the GS/SR DS/client_type/tool profile.
    h.keys.filter{it.lowercase() in setOf("ds","x-rpc-client_type","x-rpc-tool_verison","x-rpc-tool-version")}.toList().forEach{h.remove(it)}
    h["Referer"]="https://act.mihoyo.com/";h["Origin"]="https://act.mihoyo.com"
   }
  }
  return h
 }
}
