package com.nuvio.tv.core.sportslive.scores

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.lang.reflect.Type

/**
 * ESPN's JSON, read entry by entry (live.20 TV report: one game's `"odds": [null]` failed a whole score feed). In every
 * list of an ESPN body a null entry is dropped, and an entry of a shape it cannot be read in drops only itself, never
 * the body around it. Dropped unreadable entries are counted per read ([counting]): a feed's data errors; null entries
 * are an everyday ESPN habit and are not.
 */
object EspnJson {

    /** [moshi] with ESPN's lists read entry by entry. */
    fun guarded(moshi: Moshi): Moshi = moshi.newBuilder().add(EntryGuard).build()

    /** The entries a read dropped as unreadable: how many, and the JSON path of the first ("$.events[3].competitions[0]"). */
    data class Skips(val count: Int, val first: String?) {
        companion object {
            val NONE = Skips(0, null)
        }
    }

    // The unreadable entries dropped by the read running on this thread ([counting]); null outside one.
    private val dropped = ThreadLocal<MutableList<String>?>()

    /** [read] (a guarded adapter's fromJson) and the unreadable entries it dropped. */
    fun <T> counting(read: () -> T): Pair<T, Skips> {
        val outer = dropped.get()
        val mine = ArrayList<String>()
        dropped.set(mine)
        try {
            val value = read()
            return value to if (mine.isEmpty()) Skips.NONE else Skips(mine.size, mine.first())
        } finally {
            dropped.set(outer)
        }
    }

    private object EntryGuard : JsonAdapter.Factory {
        override fun create(type: Type, annotations: Set<Annotation>, moshi: Moshi): JsonAdapter<*>? {
            if (annotations.isNotEmpty()) return null
            val raw = Types.getRawType(type)
            if (raw != List::class.java && raw != Collection::class.java) return null
            val element = moshi.adapter<Any>(Types.collectionElementType(type, Collection::class.java))
            return GuardedList(element).nullSafe()
        }
    }

    private class GuardedList(private val element: JsonAdapter<Any>) : JsonAdapter<List<Any>>() {
        override fun fromJson(reader: JsonReader): List<Any> {
            val out = ArrayList<Any>()
            reader.beginArray()
            while (reader.hasNext()) {
                if (reader.peek() == JsonReader.Token.NULL) {
                    reader.nextNull<Any>()
                    continue
                }
                val path = reader.path
                // Read on a peek, so an entry that fails leaves the reader where it was: it is then skipped whole.
                val value = try {
                    element.fromJson(reader.peekJson())
                } catch (e: JsonDataException) {
                    // Its JSON path only: a message can quote a value.
                    dropped.get()?.add(path)
                    null
                }
                reader.skipValue()
                if (value != null) out += value
            }
            reader.endArray()
            return out
        }

        override fun toJson(writer: JsonWriter, value: List<Any>?) {
            writer.beginArray()
            value?.forEach { element.toJson(writer, it) }
            writer.endArray()
        }
    }
}
