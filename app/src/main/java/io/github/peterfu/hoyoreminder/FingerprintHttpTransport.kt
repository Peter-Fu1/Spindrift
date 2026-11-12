package io.github.peterfu.hoyoreminder

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.CookieHandler
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Dedicated no-cookie, single POST. Uses Android's existing system network/proxy policy. */
class FingerprintHttpTransport(
 private val connection:(String)->HttpURLConnection={URI(it).toURL().openConnection() as HttpURLConnection},
 private val timeoutMs:Long=DeviceFingerprint.TIMEOUT_MS.toLong()
):Transport {
 private val active=ConcurrentHashMap<HttpURLConnection,String>()
 private val cancelled=java.util.Collections.synchronizedSet(mutableSetOf<String>())
 private val deadline=Executors.newSingleThreadScheduledExecutor{task->Thread(task,"fp-deadline").apply{isDaemon=true}}
 override fun cancel(scope:String){
  if(scope!="*")synchronized(cancelled){if(cancelled.size>=256)cancelled.clear();cancelled.add(scope)}
  active.entries.filter{scope=="*"||it.value==scope}.forEach{it.key.disconnect()}
 }
 override fun execute(method:String,url:String,headers:Map<String,String>,body:JSONObject?,scope:String):WireResponse {
  requireRequest(method,url,headers)
  // A global Java cookie handler could implicitly inject authentication. Fail closed rather than mutate global state.
  if(CookieHandler.getDefault()!=null)throw PublicError("FP_COOKIE_POLICY")
  if(Thread.currentThread().isInterrupted||cancelled.contains(scope))throw PublicError("CANCELLED")
  val conn=connection(url)
  val timedOut=AtomicBoolean(false);active[conn]=scope
  val timer=deadline.schedule({timedOut.set(true);conn.disconnect()},timeoutMs,TimeUnit.MILLISECONDS)
  fun check(){if(Thread.currentThread().isInterrupted||cancelled.contains(scope))throw PublicError("CANCELLED");if(timedOut.get())throw PublicError("FP_TIMEOUT")}
  try{
   check();conn.requestMethod="POST";conn.instanceFollowRedirects=false;conn.connectTimeout=DeviceFingerprint.TIMEOUT_MS
   conn.readTimeout=DeviceFingerprint.TIMEOUT_MS;conn.useCaches=false;conn.doOutput=true
   headers.forEach{(k,v)->conn.setRequestProperty(k,v)};conn.setRequestProperty("Content-Type","application/json")
   conn.outputStream.use{it.write((body?:throw PublicError("FP_RESPONSE_INVALID")).toString().toByteArray(Charsets.UTF_8))}
   check();val status=conn.responseCode
   if(status!=200)throw PublicError("FP_HTTP_REJECTED",httpStatus=status)
   val bytes=conn.inputStream.use{input->
    val output=ByteArrayOutputStream();val buffer=ByteArray(8192)
    while(true){check();val n=input.read(buffer);if(n<0)break;checkSize(output.size()+n);output.write(buffer,0,n)}
    output.toByteArray()
   }
   check()
   return WireResponse(parseEnvelope(String(bytes,Charsets.UTF_8),conn.contentType),emptyMap(),status)
  }catch(e:PublicError){throw e}catch(e:Exception){check();throw PublicError("NETWORK_ERROR")}
  finally{timer.cancel(false);active.remove(conn);conn.disconnect()}
 }
 companion object {
  fun requireRequest(method:String,url:String,headers:Map<String,String>){
   if(method!="POST"||url!=DeviceFingerprint.ENDPOINT||
    headers.keys.any{it.lowercase() !in setOf("user-agent","x-rpc-app_version")}||
    headers.values.any{it.contains('\r')||it.contains('\n')})throw PublicError("FP_RESPONSE_INVALID")
  }
  fun checkSize(size:Int){if(size>DeviceFingerprint.MAX_BYTES)throw PublicError("FP_RESPONSE_INVALID")}
 }
}
