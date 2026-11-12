package io.github.peterfu.hoyoreminder
import android.webkit.WebView
/** Only the isolated official game-record WebView; never the UI or captcha widget. */
object OfficialWebSettings {
 fun apply(web:WebView){web.settings.userAgentString=Protocol.USER_AGENT}
}
