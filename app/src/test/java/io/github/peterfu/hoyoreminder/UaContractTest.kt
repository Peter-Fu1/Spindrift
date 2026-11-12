package io.github.peterfu.hoyoreminder
import org.junit.Test
import org.junit.Assert.*
class UaContractTest {
 @Test fun finalNativeRequestsUsePinnedContractWithoutChangingQrOrDiscovery(){
  val calls=mutableListOf<Pair<String,Map<String,String>>>()
  val wire=object:Transport{
   override fun execute(method:String,url:String,headers:Map<String,String>,body:org.json.JSONObject?,scope:String):WireResponse{
    calls.add(url to headers)
    return WireResponse(obj("retcode" to 0,"data" to obj("list" to org.json.JSONArray(),"url" to "https://passport-api.miyoushe.com/fixture","ticket" to "fixture")))
   }
  }
  val api=ApiClient(wire);val secret=obj("deviceId" to "own-device","cookies" to fpCookies("111"))
  for(game in Game.entries){
   val role=Role(game,if(game==Game.ZENLESS)"10000001" else "100000001",if(game==Game.GENSHIN)"cn_gf01" else "prod_gf_cn","","",1)
   api.note(secret,role,"fixture",officialHeaders=mapOf("User-Agent" to "wrong"))
   assertEquals(Protocol.USER_AGENT,calls.last().second["User-Agent"])
   api.observedNote(secret,role,Protocol.noteUrl(role),ApiClient.cookieHeader(secret.getJSONObject("cookies")),mapOf("User-Agent" to "wrong","x-rpc-app_version" to "9.9.9"),"fixture")
   assertEquals(Protocol.USER_AGENT,calls.last().second["User-Agent"])
   assertEquals(Protocol.VERSION,calls.last().second["x-rpc-app_version"])
  }
  api.createQr("qr-device","fixture");assertFalse(calls.last().second.containsKey("User-Agent"))
  api.discover(secret,"fixture");assertEquals("HoYoReminder-Android/0.1.1",calls.last().second["User-Agent"])
  assertEquals("Mozilla/5.0 (Linux; Android 15) Mobile miHoYoBBS/2.102.1",Protocol.USER_AGENT)
  assertEquals("2.102.1",Protocol.VERSION)
 }
}
