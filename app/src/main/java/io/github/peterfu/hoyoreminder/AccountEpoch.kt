package io.github.peterfu.hoyoreminder
/** In-memory tombstones: a login started before explicit logout cannot resurrect that identity. */
object AccountEpoch {
 private val values=mutableMapOf<String,Long>()
 @Synchronized fun snapshot()=values.toMap()
 @Synchronized fun removed(owner:String){values[owner]=(values[owner]?:0L)+1L}
 @Synchronized fun unchanged(owner:String,snapshot:Map<String,Long>)=(values[owner]?:0L)==(snapshot[owner]?:0L)
}
