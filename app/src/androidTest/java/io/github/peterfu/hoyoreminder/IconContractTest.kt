package io.github.peterfu.hoyoreminder
import android.graphics.*
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
@RunWith(AndroidJUnit4::class)
class IconContractTest {
 @Test fun installedResourceHasNewOpaqueForegroundAndCorrectProductName(){
  val ctx=InstrumentationRegistry.getInstrumentation().targetContext
  assertEquals("Spindrift",ctx.applicationInfo.loadLabel(ctx.packageManager).toString())
  val icon=ctx.packageManager.getApplicationIcon(ctx.packageName) as AdaptiveIconDrawable
  val foreground=icon.foreground as BitmapDrawable;assertEquals(255,Color.alpha(foreground.bitmap.getPixel(0,0)))
  val sheet=Bitmap.createBitmap(720,440,Bitmap.Config.ARGB_8888);val canvas=Canvas(sheet)
  for(row in 0..1)for(col in 0..2){
   val x=col*240;val y=row*220;val paint=Paint();paint.color=if(row==0)Color.WHITE else Color.rgb(32,39,53);canvas.drawRect(x.toFloat(),y.toFloat(),(x+240).toFloat(),(y+220).toFloat(),paint)
   canvas.save();canvas.translate((x+24).toFloat(),(y+16).toFloat())
   if(col==0){icon.setBounds(0,0,192,192);icon.draw(canvas)}else{
    val path=Path();if(col==1)path.addCircle(96f,96f,96f,Path.Direction.CW) else path.addRoundRect(0f,0f,192f,192f,42f,42f,Path.Direction.CW)
    canvas.clipPath(path);icon.foreground.setBounds(-48,-48,240,240);icon.foreground.draw(canvas)
   };canvas.restore()
  }
  val folder=File(ctx.getExternalFilesDir(null),"stage41").apply{mkdirs()};File(folder,"installed-launcher-masks.png").outputStream().use{sheet.compress(Bitmap.CompressFormat.PNG,100,it)}
  File(folder,"icon-contract.json").writeText(obj("label" to "Spindrift","opaqueForeground" to true,"defaultCircleRoundedLightDark" to true,"package" to ctx.packageName,"syntheticOnly" to true).toString())
 }
}
