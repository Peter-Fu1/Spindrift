package io.github.peterfu.hoyoreminder
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/** Closed schema: random process-local references; never raw identity, request or page text. */
data class DiagnosticTrace(val refreshId:String,val requestId:String,val accountRef:String,val roleRef:String)
class Diagnostics(val detailed:Boolean=BuildConfig.DEBUG||BuildConfig.DETAILED_DIAGNOSTICS,private val sink:(String)->Unit = {}) {
 private val rows=ArrayDeque<JSONObject>()
 private val refs=mutableMapOf<String,String>()
 private var dropped=0
 private var sequence=0L
 private val sampled=mutableMapOf<String,Int>()
 private var suppressed=0
 @Synchronized private fun ref(key:String)=refs.getOrPut(key){java.util.UUID.randomUUID().toString().replace("-","").take(12)}
 @Synchronized fun trace(account:String,role:String,scope:String)=DiagnosticTrace(
  scope.takeIf{Regex("[a-f0-9]{32}").matches(it)}?:ref("scope:"+scope),
  java.util.UUID.randomUUID().toString().replace("-",""),ref("account:"+account),ref("role:"+account+":"+role))
 @Synchronized fun event(name:String,game:Game?=null,url:String?=null,retcode:Int?=null,classification:String?=null,fpPresent:Boolean?=null,httpStatus:Int?=null,trace:DiagnosticTrace?=null,durationMs:Long?=null,stage:String?=null,source:String?=null,exceptionCategory:String?=null,challengeKind:String?=null,authFieldsPresent:Int?=null,verificationKind:String?=null,pageClassification:String?=null,failureStage:String?=null,contextChecks:Int?=null,aigisPresent:Boolean?=null,legacyPresent:Boolean?=null,aigisParseValid:Boolean?=null,windowKind:String?=null,uaPlatform:String?=null,uaMajor:Int?=null,uaNavigatorMatch:Boolean?=null,fpResultCode:Int?=null,fpMatchesAccount:Boolean?=null,uaContractMatch:Boolean?=null,profileVersion:Int?=null,fieldCount:Int?=null,uaSource:String?=null,cookieScopeMatch:Boolean?=null,contentVisible:Boolean?=null,bridgeMethod:String?=null,bridgeStatus:String?=null,outcome:String?=null,entrySource:String?=null,contextLocation:String?=null,proofPresent:Boolean?=null,endpointKind:String?=null,requestStage:String?=null,methodKind:String?=null,fpCookiePresent:Boolean?=null,deviceMatches:Boolean?=null,credentialMatches:Boolean?=null,callbackDelivered:Boolean?=null,scopeMatch:Boolean?=null,bridgeImpact:String?=null,observation:String?=null,headerMask:Int?=null,authenticationPhase:String?=null) {
  if(name in setOf("bridge_method_called","bridge_method_result")){
   val key=listOf(trace?.refreshId,bridgeMethod?.takeIf{it in RecordBridge.methods}?:"unsupported",bridgeStatus,name).joinToString(":")
   val count=(sampled[key]?:0)+1;sampled[key]=count
   if(sampled.size>256)sampled.remove(sampled.keys.first())
   if(count>4&&count%50!=0){suppressed++;return}
  }
  require(name in setOf("sms_request_result","sms_credentials_parsed","checkin_scheduled","checkin_info_result","checkin_post_result","checkin_blocked","checkin_state_committed","checkin_skipped_signed","record_card_request_identity","record_card_response_result","outbound_request_identity","outbound_response_result","bridge_method_result","h5_network_stage","verification_network_stage","device_fp_auto_checked","webview_request_identity","verification_requested","record_entry_discovered","webview_ua_verified","webview_cookie_scope_checked","verification_navigation","verification_page_classified","bridge_method_called","challenge_ui_ready","challenge_ui_completed","verification_terminal_state","device_fp_http_send","device_fp_http_receive","device_fp_accepted","device_fp_committed","device_fp_failed","request_identity","notes_api_started","notes_api_observed","notes_api_result","navigation_started","navigation_redirected","navigation_completed","final_page_classified","account_authenticated","role_resolved","notes_request_started","notes_response_received","notes_business_result","verification_route_selected","verification_page_navigated","verification_unavailable","notes_committed","refresh_completed","app_started","app_failed","account_restored","login_failed","notes_failed","verification_context_checked","verification_route_resolution_started","verification_route_resolution_result","verification_page_opened","verification_page_navigation","verification_completed","notes_retry_started","notes_retry_result","verification_ui_completed","verification_context_updated","verification_required","verification_context_parsed","verification_ui_opened","verification_page_ready","verification_ui_error","verification_discovery_started","verification_discovery_result","verification_submit_started","verification_submit_result","notes_data_committed","browser_environment"))
  val u=runCatching{URI(url?:"")}.getOrNull()
  val safeHost=u?.host?.takeIf{it in setOf("public-data-api.mihoyo.com","api-takumi-record.mihoyo.com","webstatic.mihoyo.com","act.mihoyo.com")}
  val safePath=u?.path?.takeIf{it in Game.entries.map{g->g.path}+listOf("/device-fp/api/getFp","/app/community-game-records/index.html","/app/community-game-records/rpg/index.html","/app/mihoyo-zzz-game-record/m.html")}
  val row=obj("event" to name,"sequence" to ++sequence,"timestamp" to System.currentTimeMillis(),"at" to System.currentTimeMillis(),"game" to game?.id,"host" to safeHost,"path" to safePath,"retcode" to retcode,"classification" to classification?.takeIf{it in setOf("native_api","valid_note","business_error","promotion","external_app","unknown","unverified_entry","http_error","response_invalid","network_error","authenticated","resolved","committed","completed","cancelled","session_persist_failed","auth_expired","upstream_rate_limited","account_risk","account_restricted","configured_entry","challenge_unsupported","challenge_invalid","challenge_missing","client_entry_unconfigured","page_navigation_failed","verification_timeout","verification_page_unavailable","official_verification_unavailable")},"fpPresent" to fpPresent,"httpStatus" to httpStatus?.takeIf{it in 100..599},
   "duration_ms" to durationMs?.coerceAtLeast(0),"stage" to stage?.takeIf{it in setOf("startup","restore","authentication","role_discovery","request","response","parse","persist","verification_policy","navigation","refresh")},
   "source" to source?.takeIf{it in setOf("native_api","webview","local","qr","sms","store")},
   "exception_category" to exceptionCategory?.takeIf{it in setOf("auth_expired","network_error","response_invalid","session_persist_failed","cancelled","upstream_rate_limited","upstream_rejected","local_error")})
  row.put("authentication_phase",authenticationPhase?.takeIf{it in setOf("send","login_confirm","ltoken_exchange","cookie_exchange","role_discovery")}?:JSONObject.NULL)
  row.put("endpoint_kind",endpointKind?.takeIf{it in RequestEndpoints.kinds}?:JSONObject.NULL)
  row.put("request_stage",requestStage?.takeIf{it in setOf("constructed","webview_partial","application_send","transport_delegate","page_response")}?:JSONObject.NULL)
  row.put("method_kind",methodKind?.takeIf{it in setOf("GET","POST","OPTIONS","other")}?:JSONObject.NULL)
  row.put("fp_check_scope",observation?.takeIf{it in setOf("application_boundary","partial_headers","page_reported","not_observable","not_applicable")}?: "not_applicable")
  row.put("fp_cookie_present",fpCookiePresent?:JSONObject.NULL).put("device_id_matches_account",deviceMatches?:JSONObject.NULL)
  row.put("credential_scope_match",credentialMatches?:JSONObject.NULL).put("callback_delivered",callbackDelivered?:JSONObject.NULL)
  row.put("scope_match",scopeMatch?:JSONObject.NULL)
  row.put("header_mask",headerMask?.takeIf{it in 0..255}?:JSONObject.NULL)
  row.put("bridge_impact",bridgeImpact?.takeIf{it in setOf("required_for_current_note","optional_telemetry","unverified_requirement","authorization_only")}?:JSONObject.NULL)
  row.put("proof_present",proofPresent?:JSONObject.NULL)
  row.put("fp_result_code",fpResultCode?:JSONObject.NULL).put("fp_matches_account",fpMatchesAccount?:JSONObject.NULL)
   .put("ua_contract_match",uaContractMatch?:JSONObject.NULL).put("profile_version",profileVersion?:JSONObject.NULL).put("field_count",fieldCount?:JSONObject.NULL)
  row.put("ua_source",uaSource?.takeIf{it in setOf("settings","request","navigator","redirect","document_start")}?:JSONObject.NULL)
  row.put("cookie_scope_match",cookieScopeMatch?:JSONObject.NULL).put("content_visible",contentVisible?:JSONObject.NULL)
  row.put("bridge_method",bridgeMethod?.takeIf{it in setOf("getCookieInfo","getCookieToken","getHTTPRequestHeaders","getUserInfo","getDS","getDS2","getCurrentLocale","getStatusBarHeight","closePage","pushPage","eventTrack","configure_share","showLoading","hideLoading","isFloatingWindow","login","getActionTicket","unsupported")}?:JSONObject.NULL)
  row.put("bridge_status",bridgeStatus?.takeIf{it in setOf("supported","unsupported","rejected","failed")}?:JSONObject.NULL)
  row.put("outcome",outcome?.takeIf{it in setOf("opening","awaiting_user","page_loaded","submitted","retrying","notes_committed","cancelled","expired","rejected","unavailable","page_error","closed_without_data","superseded","blocked")}?:JSONObject.NULL)
  row.put("entry_source",entrySource?.takeIf{it in setOf("card","catalog","bridge","redirect")}?:JSONObject.NULL)
  row.put("context_location",contextLocation?.takeIf{it in setOf("header","data","nested","none","unknown")}?:JSONObject.NULL)
  row.put("operation_id",trace?.refreshId?.takeIf{Regex("[a-f0-9]{32}").matches(it)}?:JSONObject.NULL)
  row.put("business_retcode",retcode?:JSONObject.NULL)
  row.put("verification_kind",verificationKind?.takeIf{it in setOf("aigis","record","legacy","none","malformed")}?:JSONObject.NULL)
  row.put("page_classification",pageClassification?.takeIf{it in setOf("overview","daily","captcha","promotion","error","unknown","local_shell")}?:JSONObject.NULL)
  row.put("failure_stage",failureStage?.takeIf{it in setOf("discovery","context","navigation","component","submission","retry","persist","native_rejection")}?:JSONObject.NULL)
  row.put("context_checks",contextChecks?.takeIf{it in 0..3}?:JSONObject.NULL)
  row.put("aigis_header_present",aigisPresent?:JSONObject.NULL)
  row.put("aigis_parse_valid",aigisParseValid?:JSONObject.NULL)
  row.put("window_kind",windowKind?.takeIf{it in setOf("local_ui","captcha","official_h5")}?:JSONObject.NULL)
  row.put("ua_platform",uaPlatform?.takeIf{it in setOf("android","windows","unknown")}?:JSONObject.NULL)
  row.put("ua_chromium_major",uaMajor?.takeIf{it in 0..999}?:JSONObject.NULL)
  row.put("ua_navigator_match",uaNavigatorMatch?:JSONObject.NULL)
  row.put("legacy_fields_present",legacyPresent?:JSONObject.NULL)
  row.put("challenge_kind",challengeKind?.takeIf{it in setOf("aigis","legacy","none","malformed")}?:JSONObject.NULL)
  row.put("auth_fields_present",authFieldsPresent?.takeIf{it in 0..6}?:JSONObject.NULL)
  if(trace!=null)for((k,v) in listOf("refresh_id" to trace.refreshId,"request_id" to trace.requestId,"account_ref" to trace.accountRef,"role_ref" to trace.roleRef))if(Regex("[a-f0-9]{12,32}").matches(v))row.put(k,v)
  if(rows.size>=2000){rows.removeFirst();dropped++}
  rows.addLast(row);sink(row.toString())
 }
 /** Closed, bounded refresh audit. Random operation/role references only. */
 @Synchronized fun checkinRefresh(name:String,fields:JSONObject){
  require(name in setOf("checkin_refresh_requested","checkin_roles_resolved","checkin_info_requested","checkin_info_completed","checkin_auto_decision","checkin_sign_result","checkin_refresh_completed","checkin_initial_status_result","checkin_cooldown_expired","checkin_cooldown_applied","checkin_cooldown_evaluated","checkin_cooldown_cleared","checkin_auto_trigger","checkin_worker_started","checkin_worker_scheduled"))
  val row=obj("event" to name,"sequence" to ++sequence,"timestamp" to System.currentTimeMillis())
  for(k in listOf("operation_id","status_request_id","source_status_request_id","role_ref","worker_ref")){val v=fields.optString(k);if(Regex("[a-f0-9]{24,32}").matches(v))row.put(k,v)}
  val enums=mapOf("trigger" to setOf("resource_manual_refresh","resource_card_refresh","checkin_status_manual_refresh","scheduled_checkin","day_rollover"),"phase" to setOf("initial_status","post_sign_verify","final_decision","sign"),"source" to setOf("official"),"decision" to setOf("already_signed","sign","query_only","skip_before_0001","server_day_not_ready","server_day_mismatch","unknown","blocked","cooldown","failed","unconfirmed","cancelled","login_expired","completed","rate_limited","throttled"))
  for((k,values) in enums)if(fields.optString(k) in values)row.put(k,fields.getString(k))
  for(k in listOf("initial_sends","verify_sends","sign_sends","reused","selected_count","enabled_count","union_count","already_signed_count","signed_count","failed_count","skipped_count","duplicate_suppressed","http_status","retcode"))DeviceFingerprint.int(fields.opt(k))?.let{row.put(k,it)}
  for((key,values) in mapOf("query_policy" to setOf("query_only","auto_if_enabled_unsigned"),"worker_noop_reason" to setOf("background_automatic_disabled","legacy_work_cancelled")))if(fields.optString(key) in values)row.put(key,fields.getString(key))
  if(fields.opt("confirmed_signed_today") is Boolean)row.put("confirmed_signed_today",fields.getBoolean("confirmed_signed_today"))
  for((key,values) in mapOf("network_state" to setOf("unknown","available","unavailable"),"cooldown_kind" to setOf("none","min_interval","transient","server_unavailable","server_throttle","risk","auth","pending","invalid","server_rejected","legacy_unknown"),"cooldown_source" to setOf("resource_manual_refresh","resource_card_refresh","checkin_status_manual_refresh","scheduled_checkin","day_rollover","legacy","manual","none")))if(fields.optString(key) in values)row.put(key,fields.getString(key))
  for(key in listOf("policy_version","cooldown_duration_sec","remaining_sec","failure_count","cooldown_count","blocked_count","unconfirmed_count","throttled_count","rate_limited_count","delay_sec","dropped_events","cooldown_until","scheduled_at","actual_at","next_attempt_at")){val value=fields.opt(key);if(value is Number&&value !is Double&&value !is Float&&value.toLong() in 0..4102444800000L)row.put(key,value.toLong())}
  if(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(fields.optString("business_day")))row.put("business_day",fields.getString("business_day"))
  for(k in listOf("after_0001","sent","shared_inflight","server_day_match","valid","ui_published","allowed","inherited","network_available","is_signed"))if(fields.opt(k) is Boolean)row.put(k,fields.getBoolean(k))
  if(rows.size>=2000){rows.removeFirst();dropped++};rows.addLast(row);sink(row.toString())
 }
 @Synchronized fun autoCheckin(trigger:String,count:Int,status:String){
  require(trigger in setOf("resource_manual_refresh","resource_card_refresh","checkin_status_manual_refresh","scheduled_checkin","day_rollover"))
  val row=obj("event" to "auto_checkin_completed","sequence" to ++sequence,"timestamp" to System.currentTimeMillis(),"trigger" to trigger,"enabled_count" to count.coerceIn(0,64),"status" to status.takeIf{it in setOf("completed","cancelled","failed")})
  if(rows.size>=2000){rows.removeFirst();dropped++};rows.addLast(row);sink(row.toString())
 }
 @Synchronized fun resign(name:String,trace:DiagnosticTrace?=null,phase:String="not_sent",decision:String?=null,code:Int?=null,schema:String?=null,mask:Int?=null){
  require(name in setOf("resign_prepare_requested","resign_info_http","resign_info_parsed","resign_rule_evaluated","resign_prepare_result","resign_confirm_attempt","resign_post_result","resign_readback"))
  val row=obj("event" to name,"sequence" to ++sequence,"timestamp" to System.currentTimeMillis(),"request_stage" to phase.takeIf{it in setOf("not_sent","application_send","business_response","network_error","parsed","terminal")},
   "decision" to decision?.takeIf{it in setOf("ready","known_standard_no_dynamic_price","official_explicit_conflict","quota_exhausted","not_eligible","missing_required_evidence","insufficient_balance","risk_control","network_error","login_expired","previous_result_unconfirmed","confirmed","unconfirmed","cancelled")},
   "business_retcode" to code,"schema" to schema?.takeIf{it in setOf("flat","resign_info")},"field_mask" to mask?.takeIf{it in 0..1023})
  if(trace!=null)row.put("operation_id",trace.refreshId).put("account_ref",trace.accountRef).put("role_ref",trace.roleRef)
  if(rows.size>=2000){rows.removeFirst();dropped++};rows.addLast(row);sink(row.toString())
 }
 @Synchronized fun checkinBatch(count:Int,state:String){
  val row=obj("event" to "checkin_batch_state","sequence" to ++sequence,"timestamp" to System.currentTimeMillis(),"count" to count.coerceIn(0,64),"state" to state.takeIf{it in setOf("running","completed","cancelled","failed")})
  if(rows.size>=2000){rows.removeFirst();dropped++};rows.addLast(row);sink(row.toString())
 }
 /** Product display fields only, never physical model, phone or raw request headers. */
 @Synchronized fun passportDisplay(phase:String,model:Boolean){
  val row=obj("event" to "passport_display_fields","sequence" to ++sequence,"timestamp" to System.currentTimeMillis(),
   "passport_device_name_class" to "product","model_field_present" to model,"model_class" to if(model)"PC" else "omitted",
   "request_stage" to phase.takeIf{it in setOf("send","login_confirm","ltoken_exchange","cookie_exchange")})
  if(rows.size>=2000){rows.removeFirst();dropped++};rows.addLast(row);sink(row.toString())
 }
 /** Closed SMS lifecycle schema: references are random, never UID/phone/credential hashes. */
 @Synchronized fun login(name:String,id:String,phase:String,trigger:String="none",committed:Boolean=false,generationMatch:Boolean=true){
  require(name in setOf("login_sms_started","sms_send_accepted","sms_confirm_accepted","ltoken_exchange_accepted","cookie_exchange_accepted","credentials_validated","account_registration_started","account_committed","roles_discovered","login_ui_ack","login_cancel_requested","login_cancel_applied","login_aborted","login_activity_retained"))
  val row=obj("event" to name,"sequence" to ++sequence,"timestamp" to System.currentTimeMillis(),"operation_id" to ref("sms-operation:"+id),
   "authentication_phase" to phase.takeIf{it in setOf("send","login_confirm","ltoken_exchange","cookie_exchange","credentials_validated","role_discovery","persist","committed","ui_ack")},
   "trigger" to trigger.takeIf{it in setOf("none","user","back","navigation","activity_recreate","timeout","replaced_session","callback_error","logout","expired","bridge_timeout")},
   "generation_match" to generationMatch,"store_committed" to committed,"cancellation_owner" to "sms_transaction","thread_stage" to if(Thread.currentThread().name=="main")"ui" else "worker")
  if(rows.size>=2000){rows.removeFirst();dropped++};rows.addLast(row);sink(row.toString())
 }
 @Synchronized fun avatar(name:String,owner:String,role:String="account",kind:String="passport",result:String="unknown",http:Int?=null,mime:String?=null,bytes:Int?=null,duration:Long?=null,retcode:Int?=null,reason:String?=null,resolver:String?=null,present:Boolean?=null){
  require(name in setOf("avatar_source_resolved","avatar_url_validated","avatar_fetch_started","avatar_fetch_result","avatar_render_success","avatar_render_failed"))
  val t=trace(owner,role,"avatar")
  val row=obj("event" to name,"sequence" to ++sequence,"timestamp" to System.currentTimeMillis(),"account_ref" to t.accountRef,"role_ref" to t.roleRef,
   "business_retcode" to retcode,"avatar_kind" to kind.takeIf{it in setOf("passport","game_role","reward")},"avatar_result" to result.takeIf{it in setOf("valid","missing","invalid_type","invalid_url","source_failed","cache_hit","cache_miss","started","cookie_policy","redirect_rejected","http_error","mime_rejected","too_large","decode_failed","pixel_limit","saved","network_error","deadline","decoded","render_failed","unknown")},
   "httpStatus" to http?.takeIf{it in 100..599},"mime_kind" to mime?.takeIf{it in setOf("png","jpeg","webp","other")},
   "bytes" to bytes?.takeIf{it in 0..524289},"duration_ms" to duration?.coerceAtLeast(0))
  row.put("game",role.substringBefore(':').takeIf{it in Game.entries.map{g->g.id}}?:org.json.JSONObject.NULL)
  row.put("avatar_cache_hit",if(result in setOf("cache_hit","cache_miss"))result=="cache_hit" else org.json.JSONObject.NULL)
  row.put("image_decode_result",if(result in setOf("saved","decoded"))"decoded" else if(result=="decode_failed")"decode_failed" else "not_observed")
  row.put("source_http_status",http?:org.json.JSONObject.NULL)
  row.put("validation_reason",reason?.takeIf{it in setOf("missing","malformed","scheme","host","unsafe_component","accepted") }?:org.json.JSONObject.NULL)
  row.put("resolver_kind",resolver?.takeIf{it in setOf("avatar_ext","avatar_url","avatar_id","record_index") }?:org.json.JSONObject.NULL).put("profile_field_present",present?:org.json.JSONObject.NULL)
  if(rows.size>=2000){rows.removeFirst();dropped++};rows.addLast(row);sink(row.toString())
 }
 fun browser(window:String,ua:String,navigatorMatch:Boolean?=null){
  val platform=if(ua.contains("Android"))"android" else if(ua.contains("Windows NT"))"windows" else "unknown"
  val major=Regex("(?:Chrome|Chromium)/(\\d{1,3})\\.").find(ua)?.groupValues?.get(1)?.toIntOrNull()?:0
  event("browser_environment",source="webview",stage="navigation",windowKind=window,uaPlatform=platform,uaMajor=major,uaNavigatorMatch=navigatorMatch,uaContractMatch=if(window=="official_h5")ua==Protocol.USER_AGENT else null)
 }
 @Synchronized fun snapshot()=obj("schemaVersion" to 4,"events" to JSONArray(rows.map{it.copyJson()}),"droppedEvents" to dropped,"bufferCapacity" to 2000,"historyTruncated" to (dropped>0),"suppressedBridgeEvents" to suppressed,"containsCredentials" to false,"references" to "process_local_random")

 @Synchronized fun jsonLines(refreshId:String?=null):String {
  require(refreshId==null||Regex("[a-f0-9]{32}").matches(refreshId))
  val selected=rows.filter{refreshId==null||it.optString("refresh_id")==refreshId||it.optString("operation_id")==refreshId}
  val header=obj("type" to "hoyoreminder_diagnostics","schemaVersion" to 4,"containsCredentials" to false,"droppedEvents" to dropped,"bufferCapacity" to 2000,"historyTruncated" to (dropped>0),"suppressedBridgeEvents" to suppressed,"refresh_id" to refreshId,"events" to selected.size)
  return (listOf(header.toString())+selected.map{it.toString()}).joinToString("\n",postfix="\n")
 }
}

/** Automatic challenge capability stays separate from explicit trusted record browsing.
 * A catalog/browser is not evidence of a supported server challenge or successful verification.
 */
object VerificationPolicy {
 fun hasValidatedEntry(game:Game):Boolean = when(game) {
  Game.GENSHIN,Game.STARRAIL,Game.ZENLESS -> false
 }
 fun pageClass(text:String,externalApp:Boolean=false):String {
  if(externalApp)return "external_app"
  val compact=text.replace(Regex("\\s+"),"")
  if(Regex("^(打开|下载)App$",RegexOption.IGNORE_CASE).matches(compact))return "external_app"
  return if(listOf("原神战绩轻松查","星穹铁道战绩工具","扫码下载米游社","打开米游社","下载米游社","米游社App查询").any{compact.contains(it,true)})"promotion" else "unknown"
 }
}
