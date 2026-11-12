package io.github.peterfu.hoyoreminder
import org.json.JSONObject

/** Same bounded parser as the executable challenge path; no raw proof in diagnostics. */
data class VerificationContext(val kind:String,val present:Boolean,val aigisPresent:Boolean=false,val legacyPresent:Boolean=false,val aigisParseValid:Boolean=false,val location:String="none") {
 companion object {
  fun inspect(response:WireResponse):VerificationContext {
   val headers=response.headers.filterKeys{it.equals("x-rpc-aigis",true)}.values.flatten()
   if(headers.isNotEmpty()){
    val valid=NoteChallenge.aigisConfig(response)!=null
    return VerificationContext(if(valid)"aigis" else "malformed",true,aigisPresent=true,aigisParseValid=valid,location="header")
   }
   val data=response.json.optJSONObject("data")
   val gt=data?.opt("gt");val challenge=data?.opt("challenge")
   val legacy=gt is String&&challenge is String&&Regex("[A-Za-z0-9_-]{1,128}").matches(gt)&&Regex("[A-Za-z0-9_-]{1,256}").matches(challenge)
   val fields=data?.has("gt")==true||data?.has("challenge")==true
   val nested=data?.optJSONObject("risk")?:data?.optJSONObject("challenge")?:data?.optJSONObject("captcha")
   val nestedPresent=nested!=null||data?.has("risk_code")==true||data?.has("risk_type")==true
   return VerificationContext(if(legacy)"legacy" else if(fields)"malformed" else "none",fields||nestedPresent,legacyPresent=legacy,
    location=if(fields)"data" else if(nestedPresent)"nested" else "none")
  }
 }
}
object VerificationResolution {
 fun reason(code:Int?,context:String,available:Boolean):String = when {
  code==5003->"account_risk"
  code==10041->"account_restricted"
  available->"configured_entry"
  context in setOf("aigis","legacy")->"challenge_unsupported"
  context=="malformed"->"challenge_invalid"
  code in setOf(1034,10035)->"challenge_missing"
  else->"client_entry_unconfigured"
 }
}
