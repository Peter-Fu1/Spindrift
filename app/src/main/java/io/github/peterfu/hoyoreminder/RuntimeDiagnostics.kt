package io.github.peterfu.hoyoreminder
/** Process-wide closed diagnostic ring, including persistent-worker and SMS callbacks. */
object RuntimeDiagnostics {
 val value:Diagnostics by lazy{Diagnostics{android.util.Log.i("HoYoReminder",it)}}
}
