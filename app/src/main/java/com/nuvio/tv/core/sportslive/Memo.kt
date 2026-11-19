package com.nuvio.tv.core.sportslive

/**
 * Remembers a pure function's last [maxEntries] answers (#152), thread-safe. On Android every `Regex`
 * call opens a native ICU matcher and copies its input into native memory that is freed only after a
 * garbage collection; folding, routing and sport-naming ran tens of regexes per text, for the same few
 * thousand team names and card texts, on every load and every team directory update, and that garbage
 * reached 1 GB of native heap. [K] must have value equality (a String, a List, a data class).
 */
internal class Memo<K, V>(private val maxEntries: Int, private val compute: (K) -> V) {
    private val cache = object : LinkedHashMap<K, V>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > maxEntries
    }

    operator fun invoke(key: K): V {
        synchronized(cache) {
            // The value itself may be null, so presence is asked separately.
            @Suppress("UNCHECKED_CAST")
            if (cache.containsKey(key)) return cache[key] as V
        }
        val value = compute(key)
        synchronized(cache) { cache[key] = value }
        return value
    }
}
