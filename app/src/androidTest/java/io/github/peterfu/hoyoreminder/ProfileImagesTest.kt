package io.github.peterfu.hoyoreminder
import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.CookieHandler

@RunWith(AndroidJUnit4::class)
class ProfileImagesTest {
 private class Connection(val bytes:ByteArray,val redirect:String?=null):HttpURLConnection(URL("https://upload-bbs.mihoyo.com/a.png")){
  override fun connect(){}
  override fun usingProxy()=false
  override fun disconnect(){}
  override fun getResponseCode()=if(redirect==null)200 else 302
  override fun getHeaderField(name:String):String?=if(name=="Location")redirect else null
  override fun getContentType()="image/png"
  override fun getContentLengthLong()=bytes.size.toLong()
  override fun getInputStream()=ByteArrayInputStream(bytes)
 }
 @Test fun boundedImagesAreCachedOnlyForTheirOwnerAndCachedReadsNeverSend(){
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  val old=CookieHandler.getDefault();CookieHandler.setDefault(null)
  val out=ByteArrayOutputStream();val bitmap=Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888);bitmap.compress(Bitmap.CompressFormat.PNG,100,out);bitmap.recycle()
  var sent=0;val connections=mutableListOf<Connection>()
  val images=ProfileImages(context){sent++;Connection(out.toByteArray()).also{connections.add(it)}}
  val url="https://upload-bbs.mihoyo.com/fixture-"+System.nanoTime()+".png"
  try{
   assertNull(images.cached("888888","account",url));assertEquals(0,sent)
   images.fetch("888888","account",url);assertNotNull(images.cached("888888","account",url))
   assertNull(images.cached("999999","account",url));images.cached("888888","account",url);assertEquals(1,sent)
   assertTrue(connections.all{it.requestProperties.keys.none{k->k.equals("Cookie",true)||k.startsWith("x-rpc",true)}})
  }finally{images.remove("888888");CookieHandler.setDefault(old)}
 }
 @Test fun untrustedRedirectAndOversizedImagesAreRejectedWithoutFollowingOrCaching(){
  val context=InstrumentationRegistry.getInstrumentation().targetContext;val old=CookieHandler.getDefault();CookieHandler.setDefault(null)
  var sent=0
  val images=ProfileImages(context){sent++;Connection(ByteArray(0),"https://evil.invalid/a.png")}
  val url="https://upload-bbs.mihoyo.com/fixture-redirect.png"
  try{
   images.fetch("888888","redirect",url);assertEquals(1,sent);assertNull(images.cached("888888","redirect",url))
   val huge=ProfileImages(context){Connection(ByteArray(524289))}
   huge.fetch("888888","huge",url);assertNull(huge.cached("888888","huge",url))
  }finally{images.remove("888888");CookieHandler.setDefault(old)}
 }
}
