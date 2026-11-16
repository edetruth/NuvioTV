package com.nuvio.tv.ui.screens.sportslive

import androidx.annotation.VisibleForTesting
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.player.StreamAutoPlaySelector
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.local.StreamAutoPlaySource
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.ui.screens.player.nextSourceFallbackCandidate
import com.nuvio.tv.ui.screens.player.sourceFallbackKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A lookup of a merged game's alternates' streams; one that is not [complete] (a source timed out or failed) is not cached. */
class SportsLiveExtraLookup(val streams: List<Stream>, val complete: Boolean)

/**
 * The other addons' streams of a merged Sports & Live game, for the player playing its primary:
 * the source fallback tries them after the primary's own streams, and the Sources panel lists them
 * under their addon names. Players of content with nothing registered are unaffected.
 *
 * A merged game is registered under the player's content id (the primary's id) when it is played
 * from the section or zapped to, and cleared when that player exits or zaps on. At most
 * [MAX_ENTRIES] games are kept; registering another evicts the oldest.
 */
object SportsLiveExtraSources {
    const val MAX_ENTRIES = 4

    private class Entry(val item: SportsLiveItem) {
        val mutex = Mutex()

        @Volatile
        var streams: List<Stream>? = null
    }

    // Insertion order is age order; guarded by itself.
    private val entries = LinkedHashMap<String, Entry>()

    // Content ids of Sports & Live games/channels being played, merged or single-addon, so the full
    // player can scope auto-failover and the error overlay to sports only. Bounded like [entries].
    private val sportsPlaybackIds = LinkedHashSet<String>()

    @Volatile
    private var fetcher: (suspend (SportsLiveItem) -> SportsLiveExtraLookup)? = null

    // Drops the resolve cache for a content id; the full player uses it to evict a dead stream.
    @Volatile
    private var resolveInvalidator: ((String) -> Unit)? = null

    /** Sets how a registered game's alternates are resolved; the Application installs the zap resolver. */
    fun install(fetch: suspend (SportsLiveItem) -> SportsLiveExtraLookup) {
        fetcher = fetch
    }

    /** Sets how a content id's resolve cache is evicted; the Application installs the zap resolver. */
    fun installResolveInvalidator(invalidate: (String) -> Unit) {
        resolveInvalidator = invalidate
    }

    /**
     * Evicts the resolve cache for [contentId] after a playback failure, so the next play/zap of this
     * game/channel re-resolves instead of serving the dead URL. No-op until the invalidator is installed.
     */
    fun invalidateResolve(contentId: String?) {
        if (contentId != null) resolveInvalidator?.invoke(contentId)
    }

    /** Registers merged [item] for the player showing [contentId]; a single-source item only clears it. */
    fun register(contentId: String, item: SportsLiveItem) {
        markSportsPlayback(contentId)
        synchronized(entries) {
            entries.remove(contentId)
            if (item.alternates.isEmpty()) return
            entries[contentId] = Entry(item)
            while (entries.size > MAX_ENTRIES) entries.remove(entries.keys.first())
        }
    }

    /**
     * Marks [contentId] as a Sports & Live playback (merged or single-addon) so the full player
     * scopes auto-failover and the recovery overlay to sports. Set before the player opens.
     */
    fun markSportsPlayback(contentId: String?) {
        if (contentId == null) return
        synchronized(sportsPlaybackIds) {
            sportsPlaybackIds.remove(contentId)
            sportsPlaybackIds.add(contentId)
            while (sportsPlaybackIds.size > MAX_ENTRIES) sportsPlaybackIds.remove(sportsPlaybackIds.first())
        }
    }

    /** Whether the player for [contentId] is playing a Sports & Live game/channel. */
    fun isSportsPlayback(contentId: String?): Boolean =
        contentId != null && synchronized(sportsPlaybackIds) { contentId in sportsPlaybackIds }

    fun clear(contentId: String?) {
        if (contentId == null) return
        synchronized(entries) { entries.remove(contentId) }
    }

    fun isRegistered(contentId: String?): Boolean =
        contentId != null && synchronized(entries) { contentId in entries }

    /** The extra streams resolved and cached for [contentId]; empty when not registered or not resolved yet. */
    fun cachedStreamsFor(contentId: String?): List<Stream> = entry(contentId)?.streams.orEmpty()

    /**
     * The alternates' streams of the game registered for [contentId] (not the primary's), with their
     * addon names and junk filtered out. Resolved once and cached; concurrent callers share the lookup.
     * A cancelled, empty or incomplete (timed-out) lookup caches nothing, so the next call tries again.
     * [refresh] (the Sources panel's reload) drops the cache first.
     */
    suspend fun extraStreamsFor(contentId: String?, refresh: Boolean = false): List<Stream> {
        val entry = entry(contentId) ?: return emptyList()
        if (refresh) entry.streams = null
        entry.streams?.let { return it }
        val fetch = fetcher ?: return emptyList()
        return entry.mutex.withLock {
            entry.streams ?: fetch(entry.item).let { lookup ->
                if (lookup.complete && lookup.streams.isNotEmpty()) entry.streams = lookup.streams
                lookup.streams
            }
        }
    }

    private fun entry(contentId: String?): Entry? =
        if (contentId == null) null else synchronized(entries) { entries[contentId] }

    @VisibleForTesting
    internal fun reset() {
        synchronized(entries) { entries.clear() }
        synchronized(sportsPlaybackIds) { sportsPlaybackIds.clear() }
        fetcher = null
        resolveInvalidator = null
    }
}

/**
 * The player's source search with the extra streams of the game registered for [contentId]
 * appended under their addon names once resolved; unchanged when nothing is registered.
 * [refresh] (a forced reload) looks the extras up again rather than reusing the cached ones.
 */
fun Flow<NetworkResult<List<AddonStreams>>>.withSportsLiveExtras(
    contentId: String?,
    refresh: Boolean = false
): Flow<NetworkResult<List<AddonStreams>>> {
    if (!SportsLiveExtraSources.isRegistered(contentId)) return this
    val extras = flow<List<Stream>?> {
        emit(null)
        emit(SportsLiveExtraSources.extraStreamsFor(contentId, refresh))
    }
    return combine(this, extras, ::withExtraStreams)
}

/**
 * [result] with [extras] appended, each under its addon's group; a failed search still shows them.
 * While the extras are pending ([extras] null) a failure is held back as loading, so the search
 * does not look finished before they arrive.
 */
internal fun withExtraStreams(
    result: NetworkResult<List<AddonStreams>>,
    extras: List<Stream>?
): NetworkResult<List<AddonStreams>> {
    if (extras == null && result is NetworkResult.Error) return NetworkResult.Loading
    if (extras.isNullOrEmpty()) return result
    return when (result) {
        is NetworkResult.Success -> NetworkResult.Success(appendExtraGroups(result.data, extras))
        is NetworkResult.Error -> NetworkResult.Success(appendExtraGroups(emptyList(), extras))
        NetworkResult.Loading -> result
    }
}

// Extras the search already lists (the same source from an addon both share) are not added again.
private fun appendExtraGroups(groups: List<AddonStreams>, extras: List<Stream>): List<AddonStreams> {
    val listedKeys = groups.flatMap { it.streams }.mapTo(HashSet()) { it.sourceFallbackKey() }
    val extraByAddon = extras.filterNot { it.sourceFallbackKey() in listedKeys }.groupBy { it.addonName }
    val names = groups.mapTo(HashSet()) { it.addonName }
    return groups.map { group ->
        extraByAddon[group.addonName]?.let { group.copy(streams = group.streams + it) } ?: group
    } + extraByAddon.filterKeys { it !in names }.map { (name, streams) ->
        AddonStreams(addonName = name, addonLogo = streams.first().addonLogo, streams = streams)
    }
}

/**
 * The source fallback's next candidate when [extras] (a merged game's other addons' streams) exist:
 * the primary's streams first, as [nextSourceFallbackCandidate] picks them, then, once the primary's
 * search is done ([extrasAllowed]), the best untried extra stream. Extras that need server or
 * YouTube preparation are left out, since the fallback only prepares debrid links. With no extras,
 * exactly [nextSourceFallbackCandidate].
 */
internal fun sportsLiveFallbackCandidate(
    streams: List<Stream>,
    extras: List<Stream>,
    extrasAllowed: Boolean,
    settings: PlayerSettings,
    installedAddonNames: Set<String>,
    triedKeys: Set<String>,
    failedAddonName: String?,
    failedInfoHash: String?
): Stream? {
    fun pick(from: List<Stream>) = nextSourceFallbackCandidate(
        streams = from,
        settings = settings,
        installedAddonNames = installedAddonNames,
        triedKeys = triedKeys,
        failedAddonName = failedAddonName,
        failedInfoHash = failedInfoHash
    )
    if (extras.isEmpty()) return pick(streams)
    // The Sources panel's list holds the extras too; they are tried only after the primary's streams.
    val extraKeys = extras.mapTo(HashSet()) { it.sourceFallbackKey() }
    pick(streams.filterNot { it.sourceFallbackKey() in extraKeys })?.let { return it }
    if (!extrasAllowed) return null
    val untried = extras.filterNot { stream ->
        stream.sourceFallbackKey() in triedKeys ||
            stream.serverTarget != null ||
            stream.youTubeIdToResolve() != null ||
            (failedInfoHash != null && stream.addonName == failedAddonName &&
                stream.getEffectiveInfoHash().equals(failedInfoHash, ignoreCase = true))
    }
    // As on the play path: the user's autoplay choice, else the first playable stream, unless the
    // user's regex or source/addon choice rules streams out (then only a stream it picks is tried).
    pick(untried)?.let { return it }
    return if (honoursAutoPlayFilters(settings)) null else untried.firstOrNull(StreamAutoPlaySelector::isPlayable)
}

/** True when the user's autoplay settings narrow which streams may play (a regex, or chosen sources). */
private fun honoursAutoPlayFilters(settings: PlayerSettings): Boolean =
    settings.streamAutoPlayMode == StreamAutoPlayMode.REGEX_MATCH ||
        settings.streamAutoPlaySource != StreamAutoPlaySource.ALL_SOURCES ||
        settings.streamAutoPlaySelectedAddons.isNotEmpty() ||
        settings.streamAutoPlaySelectedPlugins.isNotEmpty()
