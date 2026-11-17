package com.nuvio.tv.core.sportslive.scores

/**
 * The score delay for a page that fetches its own data (the Game page of a live game): each value
 * pushed becomes current only once it is [delayMs] old, so the page shows what the delayed feed would.
 * Until the first value is due nothing is current. [delayMs] 0 makes every value current at once.
 */
class DelayedValues<T>(private val delayMs: Long) {
    private val values = ArrayList<Pair<Long, T>>()

    fun push(value: T, atMs: Long) {
        values += atMs to value
    }

    /** The newest value at least [delayMs] old at [nowMs]; older ones are dropped. Null before the first is due. */
    fun current(nowMs: Long): T? {
        val index = values.indexOfLast { (at, _) -> at + delayMs <= nowMs }
        if (index < 0) return null
        if (index > 0) values.subList(0, index).clear()
        return values.first().second
    }

    /** When the next value not yet current becomes due; null when none is waiting. */
    fun nextDueAt(nowMs: Long): Long? = values.firstOrNull { (at, _) -> at + delayMs > nowMs }?.let { it.first + delayMs }
}
