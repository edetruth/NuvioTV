package com.nuvio.tv.core.sportslive

import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What a check found for a game (#147, 1b): a verified link is held ("▶ Ready"), or no working stream ("No stream yet"). Unknown has no mark. */
enum class SportsLiveReadyMark { READY, NO_STREAM }

/**
 * The "▶ Ready" / "No stream yet" marks (#147, live.20 1b), by game id ([SportsLiveItem.id]). In memory only. Thread-safe.
 *
 * HARD RULE (1b): a game is [SportsLiveReadyMark.READY] only while its verified link is held here ([ready]), for
 * [readyMs] from its last verification (the resolve cache's life); OK on it plays that link at once
 * ([heldLink], which the zap resolver's lookup serves first). The link failing anywhere ([linkFailed]) or the hold
 * lapsing takes the mark away. [SportsLiveReadyMark.NO_STREAM] holds no link and stands [noStreamMs].
 * Set by a start's probe race, a first frame (no probe), the my-games checks ([com.nuvio.tv.ui.screens.sportslive.SportsLiveReadyKeeper])
 * and the kickoff probe. Per game, not per link: the per-link marks of #156 are another thing.
 */
class SportsLiveGameReadiness(
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val readyMs: Long = SportsLiveStreamThresholds.READY_HOLD_MS,
    private val noStreamMs: Long = SportsLiveStreamThresholds.CHECK_AHEAD_CACHE_MS
) {
    companion object {
        private const val MAX_GAMES = 500

        val shared = SportsLiveGameReadiness()
    }

    private data class Entry(val mark: SportsLiveReadyMark, val atMs: Long, val link: Stream?)

    private val entries = HashMap<String, Entry>()
    private val _marks = MutableStateFlow<Map<String, SportsLiveReadyMark>>(emptyMap())

    /** The marks standing now, by game id; expired ones leave at the next change or [prune]. Read a card through [of]. */
    val marks: StateFlow<Map<String, SportsLiveReadyMark>> = _marks.asStateFlow()

    /** [link] was verified for [gameId] now (a probe proved it live, or it reached its first frame): "▶ Ready", holding it. */
    fun ready(gameId: String, link: Stream) = put(gameId, Entry(SportsLiveReadyMark.READY, nowMs(), link))

    /** No working stream was found for [gameId]: "No stream yet"; any held link goes. */
    fun noStream(gameId: String) = put(gameId, Entry(SportsLiveReadyMark.NO_STREAM, nowMs(), null))

    private fun put(gameId: String, entry: Entry) {
        if (gameId.isBlank()) return
        synchronized(this) {
            entries[gameId] = entry
            if (entries.size > MAX_GAMES) entries.minByOrNull { it.value.atMs }?.key?.let(entries::remove)
            publish()
        }
    }

    /** [item]'s mark while it stands: READY when it (or, merged card, any of its sources) holds a link, else its own. */
    fun of(item: SportsLiveItem): SportsLiveReadyMark? = synchronized(this) {
        if (heldLinkLocked(item) != null) SportsLiveReadyMark.READY else standing(item.id)?.mark
    }

    /** The verified link [item] holds while it is READY (its own, else a merged source's); null otherwise. */
    fun heldLink(item: SportsLiveItem): Stream? = synchronized(this) { heldLinkLocked(item) }

    /** When [gameId]'s held link was last verified, while it is held; null otherwise. */
    fun heldAt(gameId: String): Long? = synchronized(this) {
        standing(gameId)?.takeIf { it.link != null }?.atMs
    }

    /** A link of [gameId] failed: when it is the held one, "▶ Ready" goes at once (the hard rule). */
    fun linkFailed(gameId: String, url: String?) {
        val key = SportsLiveWorkingLinks.linkKey(url) ?: return
        synchronized(this) {
            val held = entries[gameId]?.link ?: return
            if (SportsLiveWorkingLinks.linkKey(held.getStreamUrl()) != key) return
            entries.remove(gameId)
            publish()
        }
    }

    /** Drops the marks that no longer stand. */
    fun prune() = synchronized(this) { publish() }

    fun clear() = synchronized(this) {
        entries.clear()
        publish()
    }

    private fun lifeOf(entry: Entry): Long = if (entry.mark == SportsLiveReadyMark.READY) readyMs else noStreamMs

    private fun standing(gameId: String): Entry? = entries[gameId]?.takeIf { nowMs() - it.atMs < lifeOf(it) }

    private fun heldLinkLocked(item: SportsLiveItem): Stream? =
        (listOf(item) + item.alternates).firstNotNullOfOrNull { source ->
            standing(source.id)?.takeIf { it.mark == SportsLiveReadyMark.READY }?.link
        }

    private fun publish() {
        val now = nowMs()
        entries.values.removeAll { now - it.atMs >= lifeOf(it) }
        val shown = entries.mapValues { it.value.mark }
        if (shown != _marks.value) _marks.value = shown
    }
}
