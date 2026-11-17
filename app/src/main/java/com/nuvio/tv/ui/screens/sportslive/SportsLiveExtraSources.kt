package com.nuvio.tv.ui.screens.sportslive

import androidx.annotation.VisibleForTesting
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.player.StreamAutoPlaySelector
import com.nuvio.tv.core.sportslive.SportsLiveSourceLabels
import com.nuvio.tv.core.sportslive.SportsLiveSourceReliability
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

    // Per played content id: each source's Sources-panel name (its label, and the primary's addon name)
    // to its reliability key, so the full player can credit the source it plays. Bounded like [entries].
    private val sourceNames = LinkedHashMap<String, SourceNames>()

    private class SourceNames(val primaryKey: String, val byName: Map<String, String>)

    // Stream url -> the reliability key of the source it was resolved from (the resolver notes it),
    // so a stream picked across a merged card's sources is credited to the right one.
    private val streamSources = LinkedHashMap<String, String>()
    private const val MAX_STREAM_SOURCES = 256

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
        markSportsPlayback(contentId, item)
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
    fun markSportsPlayback(contentId: String?, item: SportsLiveItem? = null) {
        if (contentId == null) return
        synchronized(sportsPlaybackIds) {
            sportsPlaybackIds.remove(contentId)
            sportsPlaybackIds.add(contentId)
            while (sportsPlaybackIds.size > MAX_ENTRIES) sportsPlaybackIds.remove(sportsPlaybackIds.first())
        }
        if (item != null) {
            synchronized(sourceNames) {
                sourceNames.remove(contentId)
                sourceNames[contentId] = sourceNamesOf(item)
                while (sourceNames.size > MAX_ENTRIES) sourceNames.remove(sourceNames.keys.first())
            }
        }
    }

    /** Each of [item]'s sources' names (provider label; the primary's addon name too) to its reliability key. */
    private fun sourceNamesOf(item: SportsLiveItem): SourceNames {
        val labels = SportsLiveSourceLabels.of(item)
        val byName = LinkedHashMap<String, String>()
        (listOf(item) + item.alternates).forEach { source ->
            labels[source.streamKey]?.let { byName.putIfAbsent(it, SportsLiveSourceReliability.keyOf(source)) }
        }
        // The primary's streams come from the player's own search, under its addon name.
        byName.putIfAbsent(item.addonName, SportsLiveSourceReliability.keyOf(item))
        return SourceNames(SportsLiveSourceReliability.keyOf(item), byName)
    }

    /** Notes that [stream] was resolved from [source], so its plays are credited to that source. */
    fun noteStreamSource(stream: Stream, source: SportsLiveItem) {
        val url = stream.getStreamUrl() ?: return
        synchronized(streamSources) {
            streamSources.remove(url)
            streamSources[url] = SportsLiveSourceReliability.keyOf(source)
            while (streamSources.size > MAX_STREAM_SOURCES) streamSources.remove(streamSources.keys.first())
        }
    }

    /** The reliability key of the source [url] was resolved from, or null when not noted. */
    private fun sourceOfUrl(url: String?): String? =
        if (url.isNullOrBlank()) null else synchronized(streamSources) { streamSources[url] }

    /**
     * The reliability key of the source the player for [contentId] plays: by its [url] when the
     * resolver noted it, else by its Sources-panel [addonName]; null outside Sports & Live.
     */
    fun sourceKeyFor(contentId: String?, url: String?, addonName: String?): String? {
        if (!isSportsPlayback(contentId)) return null
        sourceOfUrl(url)?.let { return it }
        if (addonName == null) return null
        return synchronized(sourceNames) { sourceNames[contentId]?.byName?.get(addonName) }
    }

    /** The reliability key of [stream] played for [item] on a light player: the source it came from, else [item]'s. */
    fun sourceKeyFor(item: SportsLiveItem, stream: Stream): String =
        sourceOfUrl(stream.getStreamUrl()) ?: SportsLiveSourceReliability.keyOf(item)

    /** The reliability score of [stream] in the player for [contentId]; neutral when its source is unknown. */
    fun reliabilityOf(contentId: String?, stream: Stream): Double =
        SportsLiveSourceReliability.scoreOf(sourceKeyFor(contentId, stream.getStreamUrl(), stream.addonName))

    /**
     * [groups] (the Sources panel's, in the player's order) with a Sports & Live card's source groups
     * ordered by reliability, best first; the primary's group keeps a small edge. Unchanged for content
     * that is not a Sports & Live playback, or with fewer than two of the card's groups.
     */
    fun orderGroups(contentId: String?, groups: List<AddonStreams>): List<AddonStreams> {
        if (!isSportsPlayback(contentId) || groups.size < 2) return groups
        val names = synchronized(sourceNames) { sourceNames[contentId] } ?: return groups
        // Only the card's own groups move, among the places they hold; any other group stays put.
        val slots = groups.indices.filter { names.byName[groups[it].addonName] != null }
        if (slots.size < 2) return groups
        val cardGroups = slots.map { groups[it] }
        val ranked = SportsLiveSourceReliability.rank(
            candidates = cardGroups,
            scoreOf = { SportsLiveSourceReliability.scoreOf(names.byName[it.addonName]) },
            primaryIndex = cardGroups.indexOfFirst { names.byName[it.addonName] == names.primaryKey }
        )
        val ordered = groups.toMutableList()
        slots.forEachIndexed { i, slot -> ordered[slot] = ranked[i] }
        return ordered
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

    /**
     * How to relabel the primary's search group so the whole pooled set reads by provider: its addon's
     * display name (the group's name) mapped to its provider label, or null when the primary is its
     * addon's only source (then its addon name already labels it). The alternates are relabeled where
     * they are resolved; the primary's streams come from the player's own search, so they are relabeled
     * here, in [appendExtraGroups].
     */
    fun primaryGroupRelabel(contentId: String?): Pair<String, String>? {
        val item = entry(contentId)?.item ?: return null
        val label = SportsLiveSourceLabels.of(item)[item.streamKey] ?: return null
        return if (label == item.addonName) null else item.addonName to label
    }

    private fun entry(contentId: String?): Entry? =
        if (contentId == null) null else synchronized(entries) { entries[contentId] }

    @VisibleForTesting
    internal fun reset() {
        synchronized(entries) { entries.clear() }
        synchronized(sportsPlaybackIds) { sportsPlaybackIds.clear() }
        synchronized(sourceNames) { sourceNames.clear() }
        synchronized(streamSources) { streamSources.clear() }
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
    val primaryRelabel = SportsLiveExtraSources.primaryGroupRelabel(contentId)
    val extras = flow<List<Stream>?> {
        emit(null)
        emit(SportsLiveExtraSources.extraStreamsFor(contentId, refresh))
    }
    return combine(this, extras) { result, ex -> withExtraStreams(result, ex, primaryRelabel) }
}

/**
 * [result] with [extras] appended, each under its addon's group; a failed search still shows them.
 * While the extras are pending ([extras] null) a failure is held back as loading, so the search
 * does not look finished before they arrive.
 */
internal fun withExtraStreams(
    result: NetworkResult<List<AddonStreams>>,
    extras: List<Stream>?,
    primaryRelabel: Pair<String, String>? = null
): NetworkResult<List<AddonStreams>> {
    if (extras == null && result is NetworkResult.Error) return NetworkResult.Loading
    if (extras.isNullOrEmpty() && primaryRelabel == null) return result
    return when (result) {
        is NetworkResult.Success -> NetworkResult.Success(appendExtraGroups(result.data, extras.orEmpty(), primaryRelabel))
        is NetworkResult.Error -> NetworkResult.Success(appendExtraGroups(emptyList(), extras.orEmpty(), primaryRelabel))
        NetworkResult.Loading -> result
    }
}

/**
 * [result]'s groups with the primary's group relabeled by provider ([primaryRelabel]) and the extras
 * appended under their own labels, so a single-addon multi-provider card reads consistently by
 * provider. Extras the search already lists are not added again.
 */
private fun appendExtraGroups(
    groups: List<AddonStreams>,
    extras: List<Stream>,
    primaryRelabel: Pair<String, String>? = null
): List<AddonStreams> {
    val relabeledGroups = if (primaryRelabel == null) {
        groups
    } else {
        val (from, to) = primaryRelabel
        groups.map { group ->
            if (group.addonName != from) group
            else group.copy(addonName = to, streams = group.streams.map { it.copy(addonName = to) })
        }
    }
    val listedKeys = relabeledGroups.flatMap { it.streams }.mapTo(HashSet()) { it.sourceFallbackKey() }
    val extraByAddon = extras.filterNot { it.sourceFallbackKey() in listedKeys }.groupBy { it.addonName }
    val names = relabeledGroups.mapTo(HashSet()) { it.addonName }
    return relabeledGroups.map { group ->
        extraByAddon[group.addonName]?.let { group.copy(streams = group.streams + it) } ?: group
    } + extraByAddon.filterKeys { it !in names }.map { (name, streams) ->
        AddonStreams(addonName = name, addonLogo = streams.first().addonLogo, streams = streams)
    }
}

/**
 * The source fallback's next candidate when [extras] (a merged game's other addons' streams) exist.
 * The card's sources (the primary's streams, and the extras grouped by addon/provider name) are
 * tried in reliability order ([sourceScore], see [SportsLiveSourceReliability.rank]; the primary
 * keeps a small edge, so with no evidence it goes first as before). Within a source, the stream is
 * the one [nextSourceFallbackCandidate] picks. Extras ranked below the primary wait until its search
 * is done ([extrasAllowed]). Extras that need server or YouTube preparation are left out, since the
 * fallback only prepares debrid links. With no extras, exactly [nextSourceFallbackCandidate].
 */
internal fun sportsLiveFallbackCandidate(
    streams: List<Stream>,
    extras: List<Stream>,
    extrasAllowed: Boolean,
    settings: PlayerSettings,
    installedAddonNames: Set<String>,
    triedKeys: Set<String>,
    failedAddonName: String?,
    failedInfoHash: String?,
    sourceScore: (Stream) -> Double = { SportsLiveSourceReliability.NEUTRAL }
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
    // The Sources panel's list holds the extras too; they are tried as their own sources.
    val extraKeys = extras.mapTo(HashSet()) { it.sourceFallbackKey() }
    val primary = streams.filterNot { it.sourceFallbackKey() in extraKeys }
    val untried = extras.filterNot { stream ->
        stream.sourceFallbackKey() in triedKeys ||
            stream.serverTarget != null ||
            stream.youTubeIdToResolve() != null ||
            (failedInfoHash != null && stream.addonName == failedAddonName &&
                stream.getEffectiveInfoHash().equals(failedInfoHash, ignoreCase = true))
    }
    // Index 0 is the primary's group; each extra source is a group of its own.
    val groups = listOf(primary) + untried.groupBy { it.addonName }.values
    val ranked = SportsLiveSourceReliability.rank(
        candidates = groups.indices.toList(),
        scoreOf = { index -> groups[index].firstOrNull()?.let(sourceScore) ?: SportsLiveSourceReliability.NEUTRAL },
        primaryIndex = 0
    )
    // Extras ranked ahead of the primary may go first; the rest wait for the primary's search.
    val eligible = if (extrasAllowed) ranked else ranked.takeWhile { it != 0 } + 0
    // As on the play path: the user's autoplay choice, else the first playable stream, unless the
    // user's regex or source/addon choice rules streams out (then only a stream it picks is tried).
    eligible.forEach { index -> pick(groups[index])?.let { return it } }
    if (!extrasAllowed || honoursAutoPlayFilters(settings)) return null
    return ranked.filter { it != 0 }.firstNotNullOfOrNull { index ->
        groups[index].firstOrNull(StreamAutoPlaySelector::isPlayable)
    }
}

/** True when the user's autoplay settings narrow which streams may play (a regex, or chosen sources). */
private fun honoursAutoPlayFilters(settings: PlayerSettings): Boolean =
    settings.streamAutoPlayMode == StreamAutoPlayMode.REGEX_MATCH ||
        settings.streamAutoPlaySource != StreamAutoPlaySource.ALL_SOURCES ||
        settings.streamAutoPlaySelectedAddons.isNotEmpty() ||
        settings.streamAutoPlaySelectedPlugins.isNotEmpty()
