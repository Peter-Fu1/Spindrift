package io.github.peterfu.hoyoreminder
import java.util.concurrent.atomic.AtomicBoolean
/** DOM transitions only trigger a single real note check; never prove verification success. */
class RecordRecovery {
 var sawChallenge=false;private set
 private val checked=AtomicBoolean(false)
 fun transition(challengeVisible:Boolean,kind:String,visible:Boolean):Boolean {
  if(challengeVisible||kind=="captcha"){sawChallenge=true;return false}
  return sawChallenge&&visible&&kind in setOf("overview","daily")&&claimCheck()
 }
 fun claimCheck()=checked.compareAndSet(false,true)
}
