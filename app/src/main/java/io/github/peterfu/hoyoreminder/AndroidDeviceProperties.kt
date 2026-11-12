package io.github.peterfu.hoyoreminder

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.BatteryManager
import android.os.Build
import android.os.StatFs
import android.provider.Settings
import org.json.JSONObject

/** Permission-free snapshot, encrypted once per account. No OAID/AAID, SIM/serial or sensor harvesting. */
class AndroidDeviceProperties(private val context:Context):()->JSONObject {
 override fun invoke():JSONObject {
  val result=obj("deviceName" to Build.DEVICE,"productName" to Build.PRODUCT,"hostname" to Build.HOST,
   "model" to Build.MODEL,"brand" to Build.BRAND,"hardware" to Build.HARDWARE,"deviceType" to Build.DEVICE,
   "devId" to Build.ID,"buildTime" to Build.TIME.toString(),"buildUser" to Build.USER,"deviceInfo" to Build.FINGERPRINT,
   "buildType" to Build.TYPE,"sdkVersion" to Build.VERSION.SDK_INT.toString(),"cpuType" to Build.SUPPORTED_ABIS.joinToString(","),
   "manufacturer" to Build.MANUFACTURER,"osVersion" to Build.VERSION.RELEASE,"buildTags" to Build.TAGS,
   "display" to Build.DISPLAY,"board" to Build.BOARD,"packageName" to context.packageName,
   "debugStatus" to if(context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE!=0)1 else 0,
   "emulatorStatus" to if(Build.FINGERPRINT.startsWith("generic")||Build.MODEL.contains("Emulator")||Build.HARDWARE in setOf("goldfish","ranchu"))1 else 0)
  val config=context.resources.configuration
  result.put("isTablet",if(config.smallestScreenWidthDp>=600)1 else 0)
   .put("hasKeyboard",if(config.keyboard!=Configuration.KEYBOARD_NOKEYS)1 else 0)
   .put("ui_mode",when(config.uiMode and Configuration.UI_MODE_TYPE_MASK){Configuration.UI_MODE_TYPE_NORMAL->"UI_MODE_TYPE_NORMAL";else->"unknown"})
  context.resources.displayMetrics.let{result.put("screenSize",""+it.widthPixels+"x"+it.heightPixels)}
  runCatching{
   val disk=StatFs(context.filesDir.path);val mib=1024L*1024
   result.put("romCapacity",(disk.totalBytes/(1024L*mib)).toString()).put("romRemain",(disk.availableBytes/(1024L*mib)).toString())
   result.put("sdCapacity",disk.totalBytes/mib).put("sdRemain",disk.availableBytes/mib)
   val manager=context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
   val memory=ActivityManager.MemoryInfo();manager.getMemoryInfo(memory)
   result.put("ramCapacity",(memory.totalMem/mib).toString()).put("ramRemain",(memory.availMem/mib).toString()).put("appMemory",manager.memoryClass.toString())
  }
  runCatching{
   @Suppress("DEPRECATION") val info=context.packageManager.getPackageInfo(context.packageName,0)
   result.put("packageVersion",info.versionName?:"").put("appInstallTimeDiff",info.firstInstallTime).put("appUpdateTimeDiff",info.lastUpdateTime)
  }
  runCatching{result.put("isAirMode",if(Settings.Global.getInt(context.contentResolver,Settings.Global.AIRPLANE_MODE_ON,0)!=0)1 else 0)}
  runCatching{context.registerReceiver(null,IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let{
   val scale=it.getIntExtra(BatteryManager.EXTRA_SCALE,-1);val level=it.getIntExtra(BatteryManager.EXTRA_LEVEL,-1)
   if(scale>0&&level>=0)result.put("batteryStatus",level*100/scale)
   result.put("chargeStatus",it.getIntExtra(BatteryManager.EXTRA_STATUS,0))
  }}
  // proxyStatus/root/SIM/network/ringer/location/vendor/sensors unknown: schema defaults, never fake observations.
  return result
 }
}
