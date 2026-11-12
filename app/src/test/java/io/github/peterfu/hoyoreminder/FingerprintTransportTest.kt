package io.github.peterfu.hoyoreminder
import org.junit.Test
import org.junit.Assert.*
import java.net.HttpURLConnection
import java.net.URL
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Runs production transport against a fake connection, including size/deadline/cancel and redirect paths. */
class FingerprintTransportTest {
 private class Connection:HttpURLConnection(URL(DeviceFingerprint.ENDPOINT)){
  val sent=ByteArrayOutputStream();val entered=CountDownLatch(1);val disconnected=CountDownLatch(1)
  var status=200;var content="""{"retcode":0,"data":{"code":200,"device_fp":"abcdef1234567"}}""";var blocking=false
  override fun connect(){}
  override fun usingProxy()=false
  override fun disconnect(){disconnected.countDown()}
  override fun getResponseCode()=status
  override fun getContentType()="application/json"
  override fun getOutputStream()=sent
  override fun getInputStream():InputStream {
   entered.countDown()
   if(blocking){assertTrue(disconnected.await(2,TimeUnit.SECONDS));throw java.io.IOException("fixture disconnected")}
   return ByteArrayInputStream(content.toByteArray(Charsets.UTF_8))
  }
 }
 private val headers=mapOf("User-Agent" to Protocol.USER_AGENT,"x-rpc-app_version" to Protocol.VERSION)
 private fun call(wire:FingerprintHttpTransport)=wire.execute("POST",DeviceFingerprint.ENDPOINT,headers,obj("fixture" to true),"fixture-scope")
 private fun error(code:String,block:()->Unit){try{block();fail("Expected "+code)}catch(e:PublicError){assertEquals(code,e.code)}}
 @Test fun dedicatedConnectionUsesNoCookiesNoRedirectsAndOnlyOnePost(){
  val c=Connection();var opens=0;val wire=FingerprintHttpTransport({opens++;c})
  val result=call(wire)
  assertEquals(0,result.json.getInt("retcode"));assertEquals(1,opens)
  assertEquals("POST",c.requestMethod);assertFalse(c.instanceFollowRedirects)
  assertFalse(c.useCaches);assertEquals(15000,c.connectTimeout);assertEquals(15000,c.readTimeout)
  assertNull(c.getRequestProperty("Cookie"));assertNull(c.getRequestProperty("Authorization"))
  assertEquals(Protocol.USER_AGENT,c.getRequestProperty("User-Agent"))
  assertEquals(0,c.disconnected.count.toInt())
 }
 @Test fun redirectDoesNotFollowOrAcceptSuccessShapedBody(){
  val c=Connection();c.status=302;var opens=0;val wire=FingerprintHttpTransport({opens++;c})
  error("FP_HTTP_REJECTED"){call(wire)};assertEquals(1,opens);assertEquals(1,c.entered.count.toInt())
 }
 @Test fun oversizeOrMalformedJsonIsRejectedThroughProductionReader(){
  for(content in listOf("x".repeat(65537),"not-json")){
   val c=Connection();c.content=content
   error("RESPONSE_INVALID".takeIf{content.length<65536}?:"FP_RESPONSE_INVALID"){call(FingerprintHttpTransport({c}))}
  }
 }
 @Test fun overallDeadlineDisconnectsAStalledResponseAndDoesNotRetry(){
  val c=Connection();c.blocking=true;var opens=0
  error("FP_TIMEOUT"){call(FingerprintHttpTransport({opens++;c},30))}
  assertEquals(1,opens);assertEquals(0,c.disconnected.count.toInt())
 }
 @Test fun cancellationDisconnectsAStalledResponseAndRejectsLateResult(){
  val c=Connection();c.blocking=true;val wire=FingerprintHttpTransport({c});var failure:Throwable?=null
  val worker=Thread{try{call(wire)}catch(e:Throwable){failure=e}}
  worker.start();assertTrue(c.entered.await(2,TimeUnit.SECONDS));wire.cancel("fixture-scope");worker.join(2000)
  assertFalse(worker.isAlive);assertEquals("CANCELLED",(failure as PublicError).code)
 }
 @Test fun preCancelledScopeNeverOpensConnection(){
  val wire=FingerprintHttpTransport({throw AssertionError("must not open")})
  wire.cancel("fixture-scope");error("CANCELLED"){call(wire)}
 }
}
