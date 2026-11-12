package io.github.peterfu.hoyoreminder
/** In-memory only: survives Activity recreation, expires in five minutes; process death requires a new SMS. */
object SmsLifetime {
 private var client:PassportSms?=null
 @Synchronized fun client():PassportSms=client?:PassportSms(ApiClient(HttpTransport(),RuntimeDiagnostics.value)).also{client=it}
}
