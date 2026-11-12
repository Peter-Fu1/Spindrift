package io.github.peterfu.hoyoreminder
import org.junit.Test
import org.junit.Assert.*
class AccountEpochTest {
 @Test fun brandNewAccountAndTombstoneUseSameLongZero(){
  val owner="fixture-"+java.util.UUID.randomUUID();val snapshot=AccountEpoch.snapshot()
  assertTrue(AccountEpoch.unchanged(owner,snapshot));AccountEpoch.removed(owner)
  assertFalse(AccountEpoch.unchanged(owner,snapshot));assertTrue(AccountEpoch.unchanged(owner,AccountEpoch.snapshot()))
  val values=mutableMapOf<String,Long>();val captured=values.toMap()
  println("legacy_empty_epoch_equal="+((values[owner]?:0)==(captured[owner]?:0)))
 }
}
