package io.github.peterfu.hoyoreminder
import org.junit.Test
import org.junit.Assert.*
class MoonwardContractTest {
 @Test fun zzzDoesNotInheritGenshinSignatureOrClientProfile(){
  val base=mapOf("DS" to "gs-signature","x-rpc-client_type" to "5","x-rpc-device_id" to "owned","Cookie" to "own-cookie")
  val h=NotesAdapter.headers(base,Game.ZENLESS)
  assertNull(h["DS"]);assertNull(h["x-rpc-client_type"]);assertEquals("owned",h["x-rpc-device_id"]);assertEquals("own-cookie",h["Cookie"]);assertEquals("https://act.mihoyo.com",h["Origin"])
  assertFalse(h.containsKey("x-rpc-geetest_ext"));assertFalse(h.containsKey("X-Requested-With"));assertFalse(h.containsKey("x-rpc-device_fp"))
 }
 @Test fun gsAndSrKeepOwnSignatureAndUseSeparateToolContracts(){
  val gs=NotesAdapter.headers(mapOf("DS" to "own"),Game.GENSHIN);val sr=NotesAdapter.headers(mapOf("DS" to "own"),Game.STARRAIL)
  assertEquals("own",gs["DS"]);assertEquals("own",sr["DS"]);assertNotEquals(gs["x-rpc-tool_verison"],sr["x-rpc-tool_verison"])
 }
 @Test fun transportMetadataIsSafeAndCannotBeTakenAsValidNotes(){
  val d=Diagnostics();d.event("notes_api_result",Game.GENSHIN,retcode=5003,classification="business_error",httpStatus=200)
  val row=d.snapshot().getJSONArray("events").getJSONObject(0);assertEquals(200,row.getInt("httpStatus"));assertEquals(5003,row.getInt("retcode"));assertEquals("business_error",row.getString("classification"))
 }
}
