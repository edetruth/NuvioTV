package com.nuvio.tv.core.sportslive

import com.nuvio.tv.domain.model.SportsLiveItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What a check found for a game (#147): a link proved live now, or no working stream. Unknown has no mark. */
enum class SportsLiveReadyMark { READY, NO_STREAM }

/**
 * The ✓ ready / ⚠ no working stream marks (#147), by game id ([SportsLiveItem.id]), each standing
 * [SportsLiveStreamThresholds.CHECK_AHEAD_CACHE_MS] (links come and go). Set by the in-player panel's
 * check-ahead, a start's probe race and a first frame; read by the panel's rows and "Next live game"
 * (✓ first, ⚠ passed over). In memory only. Thread-safe. Per game, not per link: the per-link marks of #156 are another thing.
 */
class SportsLiveGameReadiness(
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val ttlMs: Long = SportsLiveStreamThresholds.CHECK_AHEAD_CACHE_MS
) {
    companion object {
        private const val MAX_GAMES = 500

        val shared = SportsLiveGameReadiness()
    }

    private data class Entry(val mark: SportsLiveReadyMark, val atMs: Long)

    private val entries = HashMap<String, Entry>()
    private val _marks = MutableStateFlow<Map<String, SportsLiveReadyMark>>(emptyMap())

    /** The marks standing now, by game id; expired ones leave at the next change or [prune]. */
    val marks: StateFlow<Map<String, SportsLiveReadyMark>> = _marks.asStateFlow()

    fun set(gameId: String, mark: SportsLiveReadyMark) {
        if (gameId.isBlank()) return
        synchronized(this) {
            entries[gameId] = Entry(mark, nowMs())
            if (entries.size > MAX_GAMES) entries.minByOrNull { it.value.atMs }?.key?.let(entries::remove)
            publish()
        }
    }

    /** [item]'s mark while it stands: its own, else (merged card) ✓ when any of its sources is ready. */
    fun of(item: SportsLiveItem): SportsLiveReadyMark? = synchronized(this) {
        val now = nowMs()
        val own = entries[item.id]?.takeIf { now - it.atMs < ttlMs }?.mark
        own ?: SportsLiveReadyMark.READY.takeIf {
            item.alternates.any { alt -> entries[alt.id]?.let { now - it.atMs < ttlMs && it.mark == SportsLiveReadyMark.READY } == true }
        }
    }

    /** When [gameId] was last marked, while that mark stands; null otherwise. */
    fun markedAt(gameId: String): Long? = synchronized(this) {
        entries[gameId]?.atMs?.takeIf { nowMs() - it < ttlMs }
    }

    /** Drops the marks that no longer stand. */
    fun prune() = synchronized(this) { publish() }

    fun clear() = synchronized(this) {
        entries.clear()
        publish()
    }

    private fun publish() {
        val now = nowMs()
        entries.values.removeAll { now - it.atMs >= ttlMs }
        val shown = entries.mapValues { it.value.mark }
        if (shown != _marks.value) _marks.value = shown
    }
}
