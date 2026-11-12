package io.github.peterfu.hoyoreminder
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.CookieHandler
import java.net.URL
import java.security.MessageDigest

/** Public images only. Download exclusively on explicit profile completion, never on cold/local rendering. */
class ProfileImages(context:Context,private val connection:(String)->HttpURLConnection={URL(it).openConnection() as HttpURLConnection}){
 var diagnostics:Diagnostics?=null
 private fun event(name:String,owner:String,key:String,result:String,http:Int?=null,mime:String?=null,bytes:Int?=null){diagnostics?.avatar(name,owner,key,if(key=="account")"passport" else if(key.startsWith("reward:"))"reward" else "game_role",result,http,mime,bytes)}
 private val root=File(context.filesDir,"profile-images-v1")
 private val legacyRoot=File(context.cacheDir,"profile-images-v1")
 private fun file(owner:String,key:String,url:String):File {
  require(Regex("[0-9]{1,24}").matches(owner))
  val hash=MessageDigest.getInstance("SHA-256").digest((key+"|"+url).toByteArray()).joinToString(""){"%02x".format(it)}
  return File(File(root,owner),hash+".png")
 }
 @Synchronized fun cached(owner:String,key:String,url:String):ByteArray? {
  if(SafeImages.url(url)==null){event("avatar_url_validated",owner,key,"invalid_url");return null}
  return runCatching{
   val target=file(owner,key,url);val candidate=if(target.exists())target else File(File(legacyRoot,owner),target.name)
   if(candidate.length() !in 1..524288)return@runCatching null
   val bytes=candidate.readBytes();val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
   if(bounds.outWidth !in 1..512||bounds.outHeight !in 1..512){candidate.delete();return@runCatching null}
   val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size)
   if(bitmap==null){candidate.delete();event("avatar_fetch_result",owner,key,"decode_failed");return@runCatching null};bitmap.recycle()
   if(candidate!=target){target.parentFile!!.mkdirs();target.writeBytes(bytes)}
   bytes
  }.getOrNull()
 }
 @Synchronized fun remove(owner:String){
  if(!Regex("[0-9]{1,24}").matches(owner))return
  File(root,owner).listFiles()?.forEach{if(it.isFile)it.delete()}
 }
 @Synchronized fun fetch(owner:String,key:String,url:String,budgetMs:Long=8000,valid:()->Boolean={true}) {
  if(!valid())return
  val deadline=System.nanoTime()+budgetMs.coerceIn(1,8000)*1_000_000
  if(cached(owner,key,url)!=null){event("avatar_fetch_result",owner,key,"cache_hit");return}
  if(CookieHandler.getDefault()!=null){event("avatar_fetch_result",owner,key,"cookie_policy");return}
  event("avatar_fetch_started",owner,key,"started")
  var target=SafeImages.url(url)?:run{event("avatar_url_validated",owner,key,"invalid_url");return}
  repeat(3){
   if(Thread.currentThread().isInterrupted||System.nanoTime()>=deadline){event("avatar_fetch_result",owner,key,"deadline");return}
   val c=connection(target)
   try{
    c.instanceFollowRedirects=false;c.connectTimeout=minOf(4000,maxOf(1,((deadline-System.nanoTime())/1_000_000).toInt()));c.readTimeout=c.connectTimeout;c.useCaches=false
    val code=c.responseCode
    if(code in 300..399){target=SafeImages.url(URL(URL(target),c.getHeaderField("Location")?:"").toString())?:run{event("avatar_fetch_result",owner,key,"redirect_rejected",code);return};return@repeat}
    val type=c.contentType?.substringBefore(';')?.trim()?.lowercase()
    if(code!=200){event("avatar_fetch_result",owner,key,"http_error",code);return}
    if(c.contentLengthLong>524288){event("avatar_fetch_result",owner,key,"too_large",code);return}
    if(type !in setOf("image/png","image/jpeg","image/webp")){event("avatar_fetch_result",owner,key,"mime_rejected",code,"other");return}
    val out=ByteArrayOutputStream()
    c.inputStream.use{input->val b=ByteArray(8192);while(true){if(Thread.currentThread().isInterrupted||System.nanoTime()>=deadline){event("avatar_fetch_result",owner,key,"deadline");return};val n=input.read(b);if(n<0)break;if(out.size()+n>524288){event("avatar_fetch_result",owner,key,"too_large",code);return};out.write(b,0,n)}}
    val bytes=out.toByteArray();val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
    BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
    if(bounds.outWidth !in 1..4096||bounds.outHeight !in 1..4096){event("avatar_fetch_result",owner,key,"pixel_limit",code);return}
    val options=BitmapFactory.Options().apply{inSampleSize=if(maxOf(bounds.outWidth,bounds.outHeight)>256)maxOf(bounds.outWidth,bounds.outHeight)/128 else 1}
    val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)?:run{event("avatar_fetch_result",owner,key,"decode_failed",code);return}
    val result=ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.PNG,100,result);bitmap.recycle()
    if(result.size()>524288)return
    if(!valid()||Thread.currentThread().isInterrupted)return
    val destination=file(owner,key,url);destination.parentFile!!.mkdirs()
    destination.writeBytes(result.toByteArray())
    event("avatar_fetch_result",owner,key,"saved",code,type?.substringAfter("/"),bytes.size)
    destination.parentFile!!.listFiles()?.sortedByDescending{it.lastModified()}?.drop(128)?.forEach{it.delete()}
    return
   }catch(e:Exception){event("avatar_fetch_result",owner,key,"network_error");return}finally{c.disconnect()}
  }
 }
}
