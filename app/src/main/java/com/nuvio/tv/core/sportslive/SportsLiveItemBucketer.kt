package com.nuvio.tv.core.sportslive

import com.nuvio.tv.domain.model.CatalogStats
import com.nuvio.tv.domain.model.ClaimedCatalog
import com.nuvio.tv.domain.model.FollowedTeam
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.domain.model.SportsLiveTab
import kotlin.math.abs

/** Annotates items from claimed catalogs and sorts them into the Sports & Live tabs. */
object SportsLiveItemBucketer {
    /** How long after a timed start an events-catalog item still counts as live. */
    private const val LIVE_WINDOW_MS = 4 * 60 * 60 * 1000L

    /** How far apart two addons' starts for one game may be. */
    private const val MERGE_START_WINDOW_MS = 30 * 60 * 1000L

    private val nonLetters = Regex("""[^\p{L}]+""")
    // "LIVE NOW", "Live now": a whole phrase, so "Alive nowhere" does not count.
    private val liveNowPhrase = Regex("""(?<![\p{L}\p{N}])live now(?![\p{L}\p{N}])""", RegexOption.IGNORE_CASE)
    private const val LIVE_EMOJI = "🔴" // 🔴
    private const val SOON_EMOJI = "🔜" // 🔜
    private const val DONE_EMOJI = "✅" // ✅

    private val tabOrder: Comparator<SportsLiveItem> =
        compareByDescending<SportsLiveItem> { it.isLive }
            .thenBy(nullsLast()) { it.startMs }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.meta.name }

    // Replays: newest first, undated last.
    private val replayOrder: Comparator<SportsLiveItem> =
        compareByDescending<SportsLiveItem> { it.isLive }
            .thenBy(nullsLast(reverseOrder())) { it.startMs }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.meta.name }

    fun describe(rawMeta: MetaPreview, claimed: ClaimedCatalog, nowMs: Long): SportsLiveItem =
        describeWithSignal(rawMeta, claimed, nowMs).first

    /**
     * Describes a catalog's items. When none of them carries a signal of its own (a parseable start,
     * a live marker, a 🔴/🔜 status or a channel marker), the catalog name decides for all of them;
     * see [SportsLiveCatalogFallback].
     */
    fun describeCatalog(metas: List<MetaPreview>, claimed: ClaimedCatalog, nowMs: Long): List<SportsLiveItem> {
        val described = metas.map { describeWithSignal(it, claimed, nowMs) }
        val items = described.map { it.first }
        return if (described.any { it.second }) items else SportsLiveCatalogFallback.apply(items, claimed.catalog.name)
    }

    /** Recognition counts for one catalog's items, each counted once; see [CatalogStats]. */
    fun stats(items: List<SportsLiveItem>, nowMs: Long): CatalogStats {
        var live = 0
        var channels = 0
        var replays = 0
        var timed = 0
        var upcomingTba = 0
        var ended = 0
        var unrecognised = 0
        for (item in items) {
            if (item.isFinished) continue
            when {
                belongsTo(item, SportsLiveTab.LIVE_NOW, nowMs) -> live++
                belongsTo(item, SportsLiveTab.CHANNELS, nowMs) -> channels++
                belongsTo(item, SportsLiveTab.REPLAYS, nowMs) -> replays++
                belongsTo(item, SportsLiveTab.UPCOMING, nowMs) ->
                    if (item.startMs != null) timed++ else upcomingTba++
                // Recognised, but its start is past the live window: the event is over.
                item.startMs != null && item.startMs <= nowMs -> ended++
                else -> unrecognised++
            }
        }
        return CatalogStats(live, timed, upcomingTba, channels, replays, unrecognised, ended)
    }

    /** The item, and whether the item itself (not its catalog) says what it is. */
    private fun describeWithSignal(rawMeta: MetaPreview, claimed: ClaimedCatalog, nowMs: Long): Pair<SportsLiveItem, Boolean> {
        val catalog = claimed.catalog
        // A leading 🔴 / 🔜 / ✅ (CNCVerse) is a status, not part of the name.
        val trimmedName = rawMeta.name.trimStart()
        val status = listOf(LIVE_EMOJI, SOON_EMOJI, DONE_EMOJI).firstOrNull { trimmedName.startsWith(it) }
        val meta = if (status == null) rawMeta else rawMeta.copy(name = trimmedName.removePrefix(status).trimStart())
        val start = SportsLiveTimeParser.parseStart(meta.releaseInfo, meta.released)
        val startMs = start?.startMs
        // "Live TV" in a channels catalog's name is a kind of content, not a sign its channels are on now.
        val liveCatalog = claimed.kind != SportsLiveKind.CHANNELS &&
            (SportsLiveKeywords.containsKeyword(catalog.name, SportsLiveKeywords.LIVE) ||
                SportsLiveKeywords.containsKeyword(catalog.id, SportsLiveKeywords.LIVE))
        val liveMarker = isLiveMarker(meta.releaseInfo) ||
            meta.name.contains("LIVE NOW") ||
            meta.description?.let(liveNowPhrase::containsMatchIn) == true
        val description = meta.description.orEmpty()
        val isChannel = claimed.kind == SportsLiveKind.CHANNELS ||
            description.contains("IPTV") ||
            description.contains("Live on") ||
            meta.genres.any { SportsLiveKeywords.containsKeyword(it, SportsLiveKeywords.LIVE_TV) }
        // A channel is live only on a signal of its own (marker, 🔴, timed window): a "Live" catalog
        // name alone would mark every channel listed beside the events live.
        val isLive = when (status) {
            LIVE_EMOJI -> true
            SOON_EMOJI, DONE_EMOJI -> false
            else -> liveMarker ||
                (liveCatalog && startMs == null && !isChannel) ||
                (claimed.kind == SportsLiveKind.EVENTS && start != null && start.hasTimeOfDay &&
                    start.startMs > nowMs - LIVE_WINDOW_MS && start.startMs <= nowMs)
        }
        val item = SportsLiveItem(
            meta = meta,
            addonId = claimed.addon.id,
            addonName = claimed.addon.name,
            addonBaseUrl = claimed.addon.baseUrl,
            catalogKey = claimed.key,
            catalogName = catalog.name,
            kind = claimed.kind,
            isLive = isLive,
            isChannel = isChannel,
            startMs = startMs,
            startHasTimeOfDay = start?.hasTimeOfDay == true,
            sport = sportOf(meta, catalog.name),
            viaOverride = claimed.viaOverride,
            group = if (isChannel) SportsLiveChannelGrouper.groupOf(meta, catalog.name, claimed.addon.name) else null,
            isFinished = status == DONE_EMOJI,
            // A channel is a game only when its [...] group names one; "Sky Sports - HD" is not.
            teams = if (isChannel) SportsLiveTeams.parseBracketed(meta.name) else SportsLiveTeams.parse(meta.name)
        )
        val hasSignal = status == LIVE_EMOJI || status == SOON_EMOJI || start != null || liveMarker || isChannel
        return item to hasSignal
    }

    /**
     * Every tab is present in the result (possibly empty), each sorted live first, then by start
     * (newest first on Replays), then by name. Finished items are in no tab.
     * An addon often lists the same item in several catalogs (Live Now and Today); within a tab it
     * appears once, as its first occurrence in [items]. Copies of one game from different addons are
     * one item; see [merge].
     */
    fun bucket(
        items: List<SportsLiveItem>,
        nowMs: Long,
        addonOrder: List<String> = firstSeenAddons(items)
    ): Map<SportsLiveTab, List<SportsLiveItem>> =
        SportsLiveTab.entries.associateWith { tab -> bucketTab(items, tab, nowMs, addonOrder) }

    /**
     * One tab of [bucket], without sorting the other tabs' items (the Home row needs only Live Now).
     * The same game from several addons becomes one item ([merge]); [addonOrder] (addon ids, the
     * user's order) picks its primary copy.
     */
    fun bucketTab(
        items: List<SportsLiveItem>,
        tab: SportsLiveTab,
        nowMs: Long,
        addonOrder: List<String> = firstSeenAddons(items)
    ): List<SportsLiveItem> =
        items.filter { !it.isFinished && belongsTo(it, tab, nowMs) }
            .distinctBy { it.addonId to it.id }
            .let { merge(it, addonOrder) }
            .sortedWith(if (tab == SportsLiveTab.REPLAYS) replayOrder else tabOrder)

    /**
     * Folds copies of one game from different addons into one item: the copy of the addon first in
     * [addonOrder] (unknown addons last) is kept, carrying the others as [SportsLiveItem.alternates].
     * Two copies are one game when both are events (not channels, not replays) whose team pairs match
     * either way round, their sports (from genres) agree or one is unknown, and both are live or their
     * starts are within [MERGE_START_WINDOW_MS]. Each copy is compared with a group's primary; items
     * that merge with nothing are returned unchanged and in place.
     */
    fun merge(items: List<SportsLiveItem>, addonOrder: List<String>): List<SportsLiveItem> {
        val rank = addonOrder.withIndex().associate { (index, id) -> id to index }
        // Matching team names share their last word, so only items under one key are compared.
        val candidates = items.filter { it.kind == SportsLiveKind.EVENTS && !it.isChannel && it.teams != null }
            .groupBy { pairKey(it.teams!!) }
            .values.filter { it.size > 1 }
        if (candidates.isEmpty()) return items
        // By stream key, unique after the per-addon dedupe.
        val alternatesOf = HashMap<String, MutableList<SportsLiveItem>>()
        val folded = HashSet<String>()
        for (group in candidates) {
            val primaries = mutableListOf<SportsLiveItem>()
            for (item in group.sortedBy { rank[it.addonId] ?: Int.MAX_VALUE }) {
                val primary = primaries.firstOrNull { primary ->
                    val copies = alternatesOf[primary.streamKey].orEmpty()
                    primary.addonId != item.addonId && copies.none { it.addonId == item.addonId } &&
                        sameGame(primary, item)
                }
                if (primary == null) {
                    primaries += item
                } else {
                    alternatesOf.getOrPut(primary.streamKey) { mutableListOf() } += item
                    folded += item.streamKey
                }
            }
        }
        if (folded.isEmpty()) return items
        return items.mapNotNull { item ->
            if (item.streamKey in folded) null else alternatesOf[item.streamKey]?.let { item.copy(alternates = it) } ?: item
        }
    }

    private fun sameGame(a: SportsLiveItem, b: SportsLiveItem): Boolean {
        val x = a.teams ?: return false
        val y = b.teams ?: return false
        val teamsMatch = (SportsLiveTeams.matches(x.home, y.home) && SportsLiveTeams.matches(x.away, y.away)) ||
            (SportsLiveTeams.matches(x.home, y.away) && SportsLiveTeams.matches(x.away, y.home))
        val sportA = knownSport(a)
        val sportB = knownSport(b)
        val sportsAgree = sportA == null || sportB == null || sportA.equals(sportB, ignoreCase = true)
        val sameTime = (a.isLive && b.isLive) ||
            (a.startMs != null && b.startMs != null && abs(a.startMs - b.startMs) <= MERGE_START_WINDOW_MS)
        return teamsMatch && sportsAgree && sameTime
    }

    // A sport taken from the catalog's name ("⚡SportzX Live Events (tv)") is not a known sport.
    private fun knownSport(item: SportsLiveItem): String? =
        item.sport?.takeIf { item.meta.genres.any { it.isNotBlank() } }

    private fun pairKey(teams: TeamPair): Pair<String, String> {
        val a = SportsLiveTeams.normalise(teams.home).substringAfterLast(' ')
        val b = SportsLiveTeams.normalise(teams.away).substringAfterLast(' ')
        return if (a <= b) a to b else b to a
    }

    private fun firstSeenAddons(items: List<SportsLiveItem>): List<String> = items.mapTo(LinkedHashSet()) { it.addonId }.toList()

    /**
     * Marks the items a followed team plays in and moves them first, each part keeping its order.
     * For Live Now, Upcoming and Replays; Channels are only marked ([markFollowed]).
     */
    fun pinFollowed(items: List<SportsLiveItem>, followed: List<FollowedTeam>): List<SportsLiveItem> {
        val (pinned, rest) = markFollowed(items, followed).partition { it.followed }
        return pinned + rest
    }

    /** Marks the items a followed team plays in, keeping the order (Channels). */
    fun markFollowed(items: List<SportsLiveItem>, followed: List<FollowedTeam>): List<SportsLiveItem> =
        if (followed.isEmpty()) {
            items
        } else {
            // A merged game is followed when any of its sources is (their sports may differ).
            items.map { item ->
                val isFollowed = SportsLiveTeams.isFollowed(item, followed) ||
                    item.alternates.any { SportsLiveTeams.isFollowed(it, followed) }
                if (isFollowed) item.copy(followed = true) else item
            }
        }

    // "LIVE", "🔴 LIVE": the word LIVE once emoji, punctuation and digits are stripped.
    private fun isLiveMarker(releaseInfo: String?): Boolean =
        releaseInfo?.split(nonLetters)?.any { it.equals("LIVE", ignoreCase = true) } == true

    // MetaPreview does not carry the addon's "category" field, so the fallback goes straight to the catalog name.
    fun sportOf(meta: MetaPreview, catalogName: String): String? =
        meta.genres.firstOrNull { it.isNotBlank() }?.trim()
            ?: catalogName.trim().takeIf { it.isNotEmpty() }

    private fun belongsTo(item: SportsLiveItem, tab: SportsLiveTab, nowMs: Long): Boolean {
        val replay = item.kind == SportsLiveKind.REPLAY
        return when (tab) {
            // A channel is shown live only when an events catalog lists it with a live signal of its
            // own (highfly's "LIVE NOW"); a channels catalog marking its whole list live would flood the tab.
            SportsLiveTab.LIVE_NOW -> item.isLive && !replay &&
                (!item.isChannel || item.kind == SportsLiveKind.EVENTS)
            SportsLiveTab.UPCOMING -> !item.isLive && !replay && !item.isChannel && isUpcoming(item, nowMs)
            SportsLiveTab.CHANNELS -> item.isChannel && !replay
            // A finished event from a catalog the user included by override is shown on demand as a
            // replay rather than dropped; rule-claimed catalogs keep the 4-hour cut-off.
            SportsLiveTab.REPLAYS -> replay ||
                (item.viaOverride && item.kind == SportsLiveKind.EVENTS && !item.isLive && !isUpcoming(item, nowMs))
        }
    }

    // An upcoming-named catalog vouches only for items without a known time of day;
    // a timed start in the past never counts as upcoming. An events item with no start at
    // all is kept as "Time TBA" so a manually included catalog never disappears.
    private fun isUpcoming(item: SportsLiveItem, nowMs: Long): Boolean =
        (item.startMs != null && item.startMs > nowMs) ||
            (item.startMs == null && item.kind == SportsLiveKind.EVENTS) ||
            (!item.startHasTimeOfDay &&
                SportsLiveKeywords.containsKeyword(item.catalogName, SportsLiveKeywords.UPCOMING))
}
