package com.nuvio.tv.core.sportslive.scores

/**
 * The score delay for a page that fetches its own data (the Game page): each value pushed becomes
 * current once its own hold has passed, so the page shows what the delayed feed would. Until the first
 * value is due nothing is current. A value pushed with no hold is current at once.
 */
class DelayedValues<T> {
    private val values = ArrayList<Pair<Long, T>>()

    /** [value] fetched at [atMs], shown from [atMs] + [holdMs]. */
    fun push(value: T, atMs: Long, holdMs: Long) {
        values += (atMs + holdMs) to value
    }

    /** The newest value due at [nowMs]; older ones are dropped. Null before the first is due. */
    fun current(nowMs: Long): T? {
        val index = values.indexOfLast { (due, _) -> due <= nowMs }
        if (index < 0) return null
        if (index > 0) values.subList(0, index).clear()
        return values.first().second
    }

    /** When the next value not yet current becomes due; null when none is waiting. */
    fun nextDueAt(nowMs: Long): Long? = values.filter { (due, _) -> due > nowMs }.minOfOrNull { it.first }
}
