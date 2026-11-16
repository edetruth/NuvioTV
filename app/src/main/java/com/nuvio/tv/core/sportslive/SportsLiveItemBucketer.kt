package com.nuvio.tv.core.sportslive

import com.nuvio.tv.domain.model.CatalogStats
import com.nuvio.tv.domain.model.ClaimedCatalog
import com.nuvio.tv.domain.model.FollowedTeam
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.domain.model.SportsLiveTab

/** Annotates items from claimed catalogs and sorts them into the Sports & Live tabs. */
object SportsLiveItemBucketer {
    /** How long after a timed start an events-catalog item still counts as live. */
    private const val LIVE_WINDOW_MS = 4 * 60 * 60 * 1000L

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
     * appears once, as its first occurrence in [items].
     */
    fun bucket(items: List<SportsLiveItem>, nowMs: Long): Map<SportsLiveTab, List<SportsLiveItem>> =
        SportsLiveTab.entries.associateWith { tab -> bucketTab(items, tab, nowMs) }

    /** One tab of [bucket], without sorting the other tabs' items (the Home row needs only Live Now). */
    fun bucketTab(items: List<SportsLiveItem>, tab: SportsLiveTab, nowMs: Long): List<SportsLiveItem> =
        items.filter { !it.isFinished && belongsTo(it, tab, nowMs) }
            .distinctBy { it.addonId to it.id }
            .sortedWith(if (tab == SportsLiveTab.REPLAYS) replayOrder else tabOrder)

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
        if (followed.isEmpty()) items else items.map { if (SportsLiveTeams.isFollowed(it, followed)) it.copy(followed = true) else it }

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
