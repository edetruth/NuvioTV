package com.nuvio.tv.core.sportslive

import com.nuvio.tv.core.sportslive.identify.SportsLiveTeamIdentity
import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
import com.nuvio.tv.core.sportslive.scores.TeamDirectory
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
    // Every load merges the same names again (#152). First, so it is set before any other initialiser runs.
    private val sideKeysOf = Memo(8_192) { team: String -> sideKeysUncached(team) }
    private val wordsOf = Memo(4_096) { text: String -> wordsUncached(text) }

    /** How long after a timed start an events-catalog item still counts as live. */
    private const val LIVE_WINDOW_MS = 4 * 60 * 60 * 1000L

    /** How far apart two addons' starts for one game may be. */
    private const val MERGE_START_WINDOW_MS = 30 * 60 * 1000L

    /** A copy with no time of day joins a timed copy starting this close to now ("today", #145). */
    private const val MERGE_TODAY_WINDOW_MS = 12 * 60 * 60 * 1000L

    /** Two dated copies (one with no time of day) are one game only this close together. */
    private const val MERGE_DATE_WINDOW_MS = 24 * 60 * 60 * 1000L

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
        // An events catalog's item tagged as a TV network (#145) is a channel listing, never a game.
        val network = claimed.kind == SportsLiveKind.EVENTS &&
            (rawMeta.genres.any { SportsLiveKeywords.containsKeyword(it, SportsLiveKeywords.NETWORKS) } || isChannelName(rawMeta.name))
        val kind = if (network) SportsLiveKind.CHANNELS else claimed.kind
        // A leading 🔴 / 🔜 / ✅ (CNCVerse) is a status, not part of the name.
        val trimmedName = rawMeta.name.trimStart()
        val status = listOf(LIVE_EMOJI, SOON_EMOJI, DONE_EMOJI).firstOrNull { trimmedName.startsWith(it) }
        val meta = if (status == null) rawMeta else rawMeta.copy(name = trimmedName.removePrefix(status).trimStart())
        val start = SportsLiveTimeParser.parseStart(meta.releaseInfo, meta.released)
        val startMs = start?.startMs
        // "Live TV" in a channels catalog's name is a kind of content, not a sign its channels are on now.
        val liveCatalog = kind != SportsLiveKind.CHANNELS &&
            (SportsLiveKeywords.containsKeyword(catalog.name, SportsLiveKeywords.LIVE) ||
                SportsLiveKeywords.containsKeyword(catalog.id, SportsLiveKeywords.LIVE))
        val liveMarker = isLiveMarker(meta.releaseInfo) ||
            meta.name.contains("LIVE NOW") ||
            meta.description?.let(liveNowPhrase::containsMatchIn) == true
        val description = meta.description.orEmpty()
        val isChannel = kind == SportsLiveKind.CHANNELS ||
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
                (kind == SportsLiveKind.EVENTS && start != null && start.hasTimeOfDay &&
                    start.startMs > nowMs - LIVE_WINDOW_MS && start.startMs <= nowMs)
        }
        val item = SportsLiveItem(
            meta = meta,
            addonId = claimed.addon.id,
            addonName = claimed.addon.name,
            addonBaseUrl = claimed.addon.baseUrl,
            catalogKey = claimed.key,
            catalogName = catalog.name,
            kind = kind,
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
     * True when [title] is a channel's name, not a game (F4: "NFL Network", "Tennis Channel" in a live-events catalog):
     * its clean name ends in "Network", "Channel" or "TV", with or without a number ("Sports Channel 2"), and it
     * names no two sides and no show on a channel ("Raw on USA Network").
     */
    internal fun isChannelName(title: String): Boolean {
        val words = SportsLiveDisplayText.name(title).lowercase().split(' ').filter { it.isNotEmpty() }
        // "Raw on USA Network" is a show on a channel.
        if (words.size < 2 || "on" in words || SportsLiveTeams.parse(title) != null) return false
        val last = if (words.last().all(Char::isDigit)) words[words.size - 2] else words.last()
        return last in CHANNEL_WORDS && words.size >= (if (last == words.last()) 2 else 3)
    }

    private val CHANNEL_WORDS = setOf("network", "channel", "tv")

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
     * user's order) picks its primary copy. Copies are merged only within the tab: when one addon
     * lists a game live and another lists it upcoming (no live marker yet), the copies sit in
     * different tabs and are not merged until both are live. Replays are never merged.
     */
    fun bucketTab(
        items: List<SportsLiveItem>,
        tab: SportsLiveTab,
        nowMs: Long,
        addonOrder: List<String> = firstSeenAddons(items)
    ): List<SportsLiveItem> =
        items.filter { !it.isFinished && belongsTo(it, tab, nowMs) }
            .distinctBy { it.addonId to it.id }
            .let { if (tab == SportsLiveTab.REPLAYS) it else mergeSameTitle(merge(it, addonOrder, nowMs), addonOrder) }
            .sortedWith(if (tab == SportsLiveTab.REPLAYS) replayOrder else tabOrder)

    /**
     * Folds copies of one game into one item, pooling every source: the primary (first in [addonOrder],
     * unknown addons last; among one addon's copies the first catalog name) is kept, carrying the others
     * as [SportsLiveItem.alternates]. A source is identified by its [SportsLiveItem.streamKey]
     * (`addonId|id`), so copies pool across addons AND across one addon's provider sub-catalogs (each
     * exposes the same game with its own stream under a different catalog/id); only two sources sharing
     * a stream key are never pooled twice. Two copies are one game when both are events (not channels,
     * not replays) whose canonical team pairs match either way round (never a "TBD vs TBD" placeholder; youth
     * markers and accents fold, #165: "Panamá Sub-17" = "Panama U17", never "Panama"), their sports agree
     * ([sportsAgree]: aliases fold, an unknown one agrees), neither is a women's game the other's men's
     * ([EspnLeagueMap.otherGender], #163; a copy on a women's competition makes an unmarked copy's sides women's,
     * #174), and they are at one time
     * ([sameTime]). Each copy is compared with a group's primary; items that merge with nothing are
     * returned unchanged and in place. A merged card shows the first known sport
     * ([SportsLiveTeams.knownSport]) of its sources, and the first timed start among them when its
     * primary has none, so its reminder bell and its place in the list follow the game.
     *
     * Copies whose names differ beyond that ("San Antonio Spurs / Los Angeles Clippers" and "LA Clippers / San
     * Antonio Spurs", live.18) also merge when both sides resolve to the same two ESPN teams of a league either
     * card can be on ([idVerdict], through the [directory]: ids decide first, names only where ids are unknown); the merged row then shows the copy that names its
     * league ("NBA") when the primary names only its sport, keeping every source.
     */
    fun merge(
        items: List<SportsLiveItem>,
        addonOrder: List<String>,
        nowMs: Long = System.currentTimeMillis(),
        directory: TeamDirectory = TeamDirectory.shared
    ): List<SportsLiveItem> {
        val rank = addonOrder.withIndex().associate { (index, id) -> id to index }
        // Copies of one game share a team-pair key, so only plausible matches are compared.
        val candidates = candidateGroups(
            items.filter {
                it.kind == SportsLiveKind.EVENTS && !it.isChannel && it.teams != null &&
                    !SportsLiveTeams.isPlaceholderGame(it.teams)
            }
        )
        if (candidates.isEmpty()) return items
        // Keyed by the primary's stream key; a source joins at most one primary.
        val alternatesOf = HashMap<String, MutableList<SportsLiveItem>>()
        // A primary with no timed start takes its first timed copy's, by the primary's stream key.
        val adoptedStart = HashMap<String, SportsLiveItem>()
        val folded = HashSet<String>()
        // Primaries that took a copy by ESPN team ids, and the copy whose league is named, by the primary's stream key.
        val presentedBy = HashMap<String, SportsLiveItem>()
        for (group in candidates) {
            // Each primary as later copies are compared with it: with the start it adopted, if any.
            val primaries = mutableListOf<SportsLiveItem>()
            // Deterministic primary: addon order, then catalog name, then id (independent of input order).
            val ordered = group.sortedWith(
                compareBy({ rank[it.addonId] ?: Int.MAX_VALUE }, { it.catalogName }, { it.id })
            )
            for (item in ordered) {
                var byIds = false
                val index = primaries.indexOfFirst { primary ->
                    val pooled = alternatesOf[primary.streamKey].orEmpty() + primary
                    if (pooled.any { it.streamKey == item.streamKey }) return@indexOfFirst false
                    // ESPN team ids decide first; names only for teams the directory does not know.
                    when (idVerdict(primary, item, directory)) {
                        IdVerdict.SAME -> when (idGuardFailure(primary, item, nowMs, directory)) {
                            null -> true.also { byIds = true }
                            // A copy of unknown sport: its teams were read in the other copy's league; names decide.
                            SPORT_UNKNOWN -> sameGame(primary, item, nowMs)
                            else -> false
                        }
                        IdVerdict.DIFFERENT -> false
                        IdVerdict.UNKNOWN -> sameGame(primary, item, nowMs)
                    }
                }
                if (index < 0) {
                    primaries += item
                } else {
                    val primary = primaries[index]
                    alternatesOf.getOrPut(primary.streamKey) { mutableListOf() } += item
                    folded += item.streamKey
                    if (byIds && !namesLeague(primary) && namesLeague(item)) presentedBy.putIfAbsent(primary.streamKey, item)
                    if (timedStart(primary) == null && timedStart(item) != null) {
                        adoptedStart[primary.streamKey] = item
                        primaries[index] = primary.copy(startMs = item.startMs, startHasTimeOfDay = true)
                    }
                }
            }
        }
        if (folded.isEmpty()) return items
        return items.mapNotNull { item ->
            if (item.streamKey in folded) return@mapNotNull null
            val alternates = alternatesOf[item.streamKey] ?: return@mapNotNull item
            val timed = adoptedStart[item.streamKey]
            val merged = item.copy(
                alternates = alternates,
                sport = mergedSport(item, alternates),
                startMs = timed?.startMs ?: item.startMs,
                startHasTimeOfDay = timed != null || item.startHasTimeOfDay
            )
            // The copy naming the league lends the row its title, sides and sport (it is the one ESPN matches).
            presentedBy[item.streamKey]?.let { named ->
                merged.copy(meta = merged.meta.copy(name = named.meta.name), teams = named.teams, sport = named.sport)
            } ?: merged
        }
    }

    /**
     * Copies of one event that names no two sides folded into one row (F4: "MotoGP" from two addons, "UEFA Nations
     * League Draw" tagged Soccer by one addon and Football by another): events (not channels, never replays) with
     * no teams and the same specific clean title (case, accents, emoji, symbols and spacing aside; never a generic
     * one, [isGenericTitle]), whose sports agree ([sportsAgree]), which are both live (an upcoming listing's time
     * cannot tell two such events apart) and which come from different addons: one addon's feeds of one title are
     * likely different courts or feeds, so they stay apart. As in [merge], the primary is the first in [addonOrder]
     * (then catalog name, then id) and the others become its alternates; the rest is unchanged, in place.
     */
    internal fun mergeSameTitle(items: List<SportsLiveItem>, addonOrder: List<String>): List<SportsLiveItem> {
        val groups = items
            .filter { it.kind == SportsLiveKind.EVENTS && !it.isChannel && it.teams == null && it.isLive }
            .groupBy(::titleKey)
            .filterKeys { it.isNotEmpty() && !isGenericTitle(it) }
            .values.filter { it.size > 1 }
        if (groups.isEmpty()) return items
        val rank = addonOrder.withIndex().associate { (index, id) -> id to index }
        val alternatesOf = HashMap<String, MutableList<SportsLiveItem>>()
        val folded = HashSet<String>()
        for (group in groups) {
            val primaries = mutableListOf<SportsLiveItem>()
            for (item in group.sortedWith(compareBy({ rank[it.addonId] ?: Int.MAX_VALUE }, { it.catalogName }, { it.id }))) {
                val primary = primaries.firstOrNull { primary ->
                    sportsAgree(primary, item) &&
                        (listOf(primary) + alternatesOf[primary.streamKey].orEmpty()).none { it.addonId == item.addonId }
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
            if (item.streamKey in folded) return@mapNotNull null
            val extra = alternatesOf[item.streamKey] ?: return@mapNotNull item
            val alternates = item.alternates + extra.flatMap { listOf(it) + it.alternates }
            item.copy(alternates = alternates, sport = mergedSport(item, alternates))
        }
    }

    // A teamless event's title as rows show it ([displayName]), folded: "MotoGP", "MOTOGP 📺" and "MotoGP " are one,
    // as are "ATP 250 vs Stockholm Open" and an "ATP 250" whose description names Stockholm Open.
    private fun titleKey(item: SportsLiveItem): String =
        SportsLiveTeams.foldAccents(item.displayName).lowercase().trim()

    /**
     * True when a teamless title says too little to be one event (coordinator ruling, live.20): a sport word alone
     * ("Tennis"), a tour alone ("ATP", "WTA"), a court, day or feed ("Court 3", "Day 6 Singles", "Main Feed").
     */
    internal fun isGenericTitle(key: String): Boolean =
        SportsLiveSportKind.isPlainSportWord(key) || GENERIC_TITLE.matches(key.trim().lowercase())

    private val GENERIC_TITLE = Regex(
        """(atp|wta|itf|atp tour|wta tour|tennis|court \d+|centre court|center court|day \d+( (singles|doubles|session))?|""" +
            """(main |live |hd )?feed( \d+)?|stream( \d+)?|live|live now|live event|highlights)"""
    )

    private fun mergedSport(primary: SportsLiveItem, alternates: List<SportsLiveItem>): String? =
        SportsLiveTeams.knownSport(primary) ?: alternates.firstNotNullOfOrNull(SportsLiveTeams::knownSport) ?: primary.sport

    private fun sameGame(a: SportsLiveItem, b: SportsLiveItem, nowMs: Long): Boolean {
        val teamsA = a.teams ?: return false
        val teamsB = b.teams ?: return false
        // A copy on a women's competition is never one game with a men's copy; with a copy that names no gender, both
        // are read as women's sides (#174): "Arsenal vs OL Lyonnes" (Football) is "Arsenal W vs OL Lyonnes W" (UWCL).
        val menA = saysMen(a)
        val menB = saysMen(b)
        val womenA = saysWomenCompetition(a)
        val womenB = saysWomenCompetition(b)
        if ((womenA && !menA && menB && !womenB) || (womenB && !menB && menA && !womenA)) return false
        val asWomen = (womenA || womenB) && !menA && !menB
        val x = if (asWomen) SportsLiveTeams.asWomen(teamsA) else teamsA
        val y = if (asWomen) SportsLiveTeams.asWomen(teamsB) else teamsB
        // Both sides must match. When both match only by prefix ("Brisbane W" / "Brisbane Heat Women" vs
        // "Hobart W" / "Hobart Hurricanes Women"), both sports must be known as well, so an unknown-sport
        // "Adelaide vs Brisbane" never takes "Adelaide United vs Brisbane Roar".
        val sportsKnown = SportsLiveTeams.knownSport(a) != null && SportsLiveTeams.knownSport(b) != null
        val teamsMatch = sidesMatch(x.home, y.home, x.away, y.away, sportsKnown) ||
            sidesMatch(x.home, y.away, x.away, y.home, sportsKnown)
        return teamsMatch && sportsAgree(a, b) && !EspnLeagueMap.otherGender(a, b) && sameTime(a, b, nowMs)
    }

    private const val SPORT_UNKNOWN = "sport unknown"

    /** What ESPN team ids say about two copies (live.18). */
    internal enum class IdVerdict { SAME, DIFFERENT, UNKNOWN }

    /**
     * Two copies by ESPN team ids, the primary rule for every sport the team directory knows (live.18): in the
     * leagues either card can be on ([TeamDirectory.leaguesFor], after its league inference and #168 identity),
     * [IdVerdict.SAME] when both copies' sides resolve to the same two teams in either order
     * ([TeamDirectory.teamId], cities short or in full), [IdVerdict.DIFFERENT] when they resolve to other teams,
     * [IdVerdict.UNKNOWN] when no league resolves all four sides (the name rules decide).
     */
    internal fun idVerdict(a: SportsLiveItem, b: SportsLiveItem, directory: TeamDirectory = TeamDirectory.shared): IdVerdict {
        val viewA = idView(a, directory)
        val viewB = idView(b, directory)
        val teamsA = viewA.teams ?: return IdVerdict.UNKNOWN
        val teamsB = viewB.teams ?: return IdVerdict.UNKNOWN
        var resolved = false
        for (league in (TeamDirectory.leaguesFor(viewA) + TeamDirectory.leaguesFor(viewB)).distinct()) {
            val a1 = directory.teamId(league, teamsA.home) ?: continue
            val a2 = directory.teamId(league, teamsA.away) ?: continue
            val b1 = directory.teamId(league, teamsB.home) ?: continue
            val b2 = directory.teamId(league, teamsB.away) ?: continue
            if (a1 == a2 || b1 == b2) continue
            if ((a1 == b1 && a2 == b2) || (a1 == b2 && a2 == b1)) return IdVerdict.SAME
            resolved = true
        }
        // A copy of unknown sport was read in the other copy's leagues only: no veto from it.
        val bothKnown = SportsLiveTeams.knownSport(viewA) != null && SportsLiveTeams.knownSport(viewB) != null
        return if (resolved && bothKnown) IdVerdict.DIFFERENT else IdVerdict.UNKNOWN
    }

    // A copy as the lists show it: its league inferred from its teams, its sport from #168's team lookup.
    private fun idView(item: SportsLiveItem, directory: TeamDirectory): SportsLiveItem =
        SportsLiveTeamIdentity.identified(SportsLiveSportInference.infer(item, directory))

    /**
     * Why two copies whose team ids agree are still not one game, or null when they are: their sports must both be
     * known and agree ("Basketball" and "NBA"), neither may say women's unless both do, and they are at one time.
     */
    internal fun idGuardFailure(
        a: SportsLiveItem,
        b: SportsLiveItem,
        nowMs: Long,
        directory: TeamDirectory = TeamDirectory.shared
    ): String? {
        val viewA = idView(a, directory)
        val viewB = idView(b, directory)
        if (SportsLiveTeams.knownSport(viewA) == null || SportsLiveTeams.knownSport(viewB) == null) return SPORT_UNKNOWN
        if (!sportsAgree(viewA, viewB)) return "sport mismatch"
        // A women's copy is never one game with a copy that does not say women's; "NCAAM" and an unmarked "College"
        // copy of the same two schools are (live.18: the sweep's most common split).
        val womenA = EspnLeagueMap.saysWomen(viewA) || saysWomenCompetition(viewA)
        val womenB = EspnLeagueMap.saysWomen(viewB) || saysWomenCompetition(viewB)
        if (womenA != womenB || EspnLeagueMap.otherGender(viewA, viewB)) return "gender rule"
        if (!sameTime(viewA, viewB, nowMs)) return "start window"
        return null
    }

    /**
     * Why [a] and [b] are not one row (the debug duplicate log, live.18), or null when [merge] would join them:
     * not both events, a placeholder, different team ids, an id guard, or (ids unknown) the name rules with the
     * guard that failed.
     */
    internal fun splitReason(a: SportsLiveItem, b: SportsLiveItem, nowMs: Long, directory: TeamDirectory = TeamDirectory.shared): String? {
        if (a.kind != SportsLiveKind.EVENTS || b.kind != SportsLiveKind.EVENTS || a.isChannel || b.isChannel) return "not both events"
        val teamsA = a.teams ?: return "no teams"
        val teamsB = b.teams ?: return "no teams"
        if (SportsLiveTeams.isPlaceholderGame(teamsA) || SportsLiveTeams.isPlaceholderGame(teamsB)) return "placeholder"
        return when (idVerdict(a, b, directory)) {
            IdVerdict.SAME -> when (val failure = idGuardFailure(a, b, nowMs, directory)) {
                null -> null
                SPORT_UNKNOWN -> if (sameGame(a, b, nowMs)) null else "same team ids, sport unknown, names differ"
                else -> "same team ids, $failure"
            }
            IdVerdict.DIFFERENT -> "different team ids"
            IdVerdict.UNKNOWN -> when {
                sameGame(a, b, nowMs) -> null
                !sportsAgree(a, b) -> "no team id, sport mismatch"
                EspnLeagueMap.otherGender(a, b) -> "no team id, gender rule"
                !sameTime(a, b, nowMs) -> "no team id, start window"
                else -> "no team id, names differ"
            }
        }
    }

    // A copy whose routing names one league ("NBA"), not only a sport word that may also be college ("Basketball").
    private fun namesLeague(item: SportsLiveItem): Boolean {
        val leagues = EspnLeagueMap.leaguesFor(item)
        return leagues.size == 1 && leagues[0] != EspnLeagueMap.SOCCER_ALL && EspnLeagueMap.collegeFallbackFor(item) == null
    }

    private fun sidesMatch(home: String, otherHome: String, away: String, otherAway: String, sportsKnown: Boolean): Boolean {
        val first = SportsLiveTeams.mergeMatch(home, otherHome)
        if (first == SportsLiveTeams.MergeMatch.NONE) return false
        val second = SportsLiveTeams.mergeMatch(away, otherAway)
        if (second == SportsLiveTeams.MergeMatch.NONE) return false
        return first == SportsLiveTeams.MergeMatch.FULL || second == SportsLiveTeams.MergeMatch.FULL || sportsKnown
    }

    // A women's competition, as a whole word of a copy's sport, genres or catalog (#174). Not its title: there
    // "Women" or "W" is a side's marker, which [SportsLiveTeams.mergeMatch] already compares.
    private val WOMEN_COMPETITION_WORDS = setOf(
        "women", "womens", "ladies", "uwcl", "nwsl", "wsl", "awsl", "ncaaw", "wbb", "wcbb", "wnba", "wbbl",
        "frauen", "femenina", "feminino", "feminine", "femminile"
    )

    // A men's game, as a whole word anywhere on a copy, its title included.
    private val MEN_WORDS = setOf("men", "mens", "ncaam", "mbb")

    private fun saysWomenCompetition(item: SportsLiveItem): Boolean =
        says(item.sport, WOMEN_COMPETITION_WORDS) || says(item.catalogName, WOMEN_COMPETITION_WORDS) ||
            item.meta.genres.any { says(it, WOMEN_COMPETITION_WORDS) }

    private fun saysMen(item: SportsLiveItem): Boolean =
        says(item.meta.name, MEN_WORDS) || says(item.sport, MEN_WORDS) || says(item.catalogName, MEN_WORDS) ||
            item.meta.genres.any { says(it, MEN_WORDS) }

    private fun says(text: String?, words: Set<String>): Boolean = text != null && wordsOf(text).any { it in words }

    /** [text]'s words, lowercase and without accents ("Women's" → women, s); plain string work, no regex (#152). */
    private fun wordsUncached(text: String): Set<String> {
        val words = HashSet<String>()
        val word = StringBuilder()
        for (c in SportsLiveTeams.foldAccents(text)) {
            if (c.isLetterOrDigit()) {
                word.append(c.lowercaseChar())
            } else if (word.isNotEmpty()) {
                words += word.toString()
                word.setLength(0)
            }
        }
        if (word.isNotEmpty()) words += word.toString()
        return words
    }

    /**
     * The sports of two copies agree (#145): the same word, or words naming one sport ("Soccer", "Football",
     * "Futbol", "MLS" are all soccer; a bare "Football" may also be American football). An unknown or
     * non-sport tag on either side ("COLLEGE", "OTHER", none) agrees with anything.
     */
    private fun sportsAgree(a: SportsLiveItem, b: SportsLiveItem): Boolean {
        val sportA = SportsLiveTeams.knownSport(a) ?: return true
        val sportB = SportsLiveTeams.knownSport(b) ?: return true
        if (sportA.equals(sportB, ignoreCase = true)) return true
        val kindsA = sportKinds(sportA) ?: return true
        val kindsB = sportKinds(sportB) ?: return true
        return kindsA.any { it in kindsB }
    }

    private val bareFootball = Regex("""^\s*football\s*$""", RegexOption.IGNORE_CASE)

    // The sports a sport word can mean; null when it names none.
    private fun sportKinds(sport: String): Set<SportsLiveSportKind>? {
        val kind = SportsLiveSportKind.of(sport) ?: return null
        return if (bareFootball.matches(sport)) setOf(SportsLiveSportKind.SOCCER, SportsLiveSportKind.FOOTBALL) else setOf(kind)
    }

    /**
     * Two copies are at one time: both live, or both timed within [MERGE_START_WINDOW_MS]. A copy with no
     * time of day (none, or a date only) joins when every timed copy is live or starts within
     * [MERGE_TODAY_WINDOW_MS] of now (#145: the game is on today), and two dated copies are within a day.
     */
    private fun sameTime(a: SportsLiveItem, b: SportsLiveItem, nowMs: Long): Boolean {
        if (a.isLive && b.isLive) return true
        val timedA = timedStart(a)
        val timedB = timedStart(b)
        if (timedA != null && timedB != null) return abs(timedA - timedB) <= MERGE_START_WINDOW_MS
        if (a.startMs != null && b.startMs != null && abs(a.startMs - b.startMs) > MERGE_DATE_WINDOW_MS) return false
        return listOf(a to timedA, b to timedB).all { (item, timed) ->
            timed == null || item.isLive || abs(timed - nowMs) <= MERGE_TODAY_WINDOW_MS
        }
    }

    private fun timedStart(item: SportsLiveItem): Long? = item.startMs?.takeIf { item.startHasTimeOfDay }

    /**
     * The items that may be copies of one game, in groups of two or more: items sharing a team-pair key are
     * joined, transitively. A side's keys are the first and last words of its canonical name (abbreviations
     * folded in: "SL" -> "sri lanka"), so "Lakers" meets "Los Angeles Lakers" by the last word and
     * "Adelaide" meets "Adelaide 36ers" by the first (#153). The keys keep the groups coarse; [sameGame] is precise.
     */
    private fun candidateGroups(items: List<SportsLiveItem>): Collection<List<SportsLiveItem>> {
        val parent = IntArray(items.size) { it }
        fun root(i: Int): Int {
            var r = i
            while (parent[r] != r) r = parent[r]
            var j = i
            while (parent[j] != r) j = parent[j].also { parent[j] = r }
            return r
        }
        val firstWithKey = HashMap<Pair<String, String>, Int>()
        items.forEachIndexed { index, item ->
            for (key in pairKeys(item.teams!!)) {
                val other = firstWithKey.putIfAbsent(key, index) ?: continue
                parent[root(index)] = root(other)
            }
        }
        return items.indices.groupBy(::root, items::get).values.filter { it.size > 1 }
    }

    private fun pairKeys(teams: TeamPair): List<Pair<String, String>> {
        val home = sideKeys(teams.home)
        val away = sideKeys(teams.away)
        return home.flatMap { a -> away.map { b -> if (a <= b) a to b else b to a } }.distinct()
    }

    private fun sideKeys(team: String): List<String> = sideKeysOf(team)

    private fun sideKeysUncached(team: String): List<String> {
        // Accents folded (#165): "Panamá" and "Panama" share a key.
        val canonical = SportsLiveTeams.canonicalise(SportsLiveTeams.foldAccents(team))
        return listOf(canonical.substringAfterLast(' '), canonical.substringBefore(' ')).distinct()
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
    // Pro wrestling an addon files under MMA ("AEW Dynamite", #145) is wrestling.
    fun sportOf(meta: MetaPreview, catalogName: String): String? {
        val sport = meta.genres.firstOrNull { it.isNotBlank() }?.trim()
            ?: catalogName.trim().takeIf { it.isNotEmpty() }
        return when {
            SportsLiveSportKind.isWrestlingShow(meta.name, sport) -> WRESTLING
            SportsLiveSportKind.of(sport) == SportsLiveSportKind.MOTORSPORT -> misfiledMotorsport(meta.name) ?: sport
            else -> sport
        }
    }

    /**
     * What a game an addon files under motorsport really is when its title says otherwise (F4): figure skating
     * ("ISU Grand Prix") is "Skating"; horse racing ("Live racing from Catterick") is "Racing", as is any title
     * that says "racing" with no motor word. Null when the title is motorsport, or says nothing.
     */
    internal fun misfiledMotorsport(title: String): String? {
        val words = wordsOf(title)
        return when {
            words.any { it in SKATING_WORDS } -> SKATING
            words.any { it in HORSE_WORDS } -> RACING
            "racing" in words && words.none { it in MOTOR_WORDS } -> RACING
            else -> null
        }
    }

    private val SKATING_WORDS = setOf("isu", "skating", "skate", "skater", "skaters")
    private val HORSE_WORDS = setOf("horse", "horses", "racecourse", "hurdle", "hurdles", "steeplechase", "greyhound", "greyhounds", "thoroughbred", "equestrian")
    private val MOTOR_WORDS = setOf(
        "motor", "motorsport", "motorsports", "moto", "motogp", "moto2", "moto3", "motorgp", "f1", "f2", "f3", "formula", "nascar",
        "indycar", "rally", "wrc", "superbike", "superbikes", "wsbk", "karting", "dtm", "wec", "imsa", "supercars", "gt",
        "touring", "endurance", "prix", "gp", "speedway", "drift", "drag", "truck", "trucks", "ferrari", "porsche"
    )

    private const val WRESTLING = "Wrestling"
    private const val SKATING = "Skating"
    private const val RACING = "Racing"

    private fun belongsTo(item: SportsLiveItem, tab: SportsLiveTab, nowMs: Long): Boolean {
        val replay = item.kind == SportsLiveKind.REPLAY
        return when (tab) {
            // A channel is shown live only when an events catalog lists it with a live signal of its
            // own (highfly's "LIVE NOW"); a channels catalog marking its whole list live would flood the tab.
            // "TBD vs TBD" placeholders and TV shows (#145) are no game to list.
            SportsLiveTab.LIVE_NOW -> item.isLive && !replay &&
                (!item.isChannel || item.kind == SportsLiveKind.EVENTS) &&
                !SportsLiveTeams.isPlaceholderGame(item.teams) && !SportsLiveNonSport.isNonSport(item)
            SportsLiveTab.UPCOMING -> !item.isLive && !replay && !item.isChannel && isUpcoming(item, nowMs) &&
                !SportsLiveTeams.isPlaceholderGame(item.teams) && !SportsLiveNonSport.isNonSport(item)
            SportsLiveTab.CHANNELS -> item.isChannel && !replay
            // A finished event from a catalog the user included by override is shown on demand as a
            // replay rather than dropped; rule-claimed catalogs keep the 4-hour cut-off.
            SportsLiveTab.REPLAYS -> (replay ||
                (item.viaOverride && item.kind == SportsLiveKind.EVENTS && !item.isLive && !isUpcoming(item, nowMs))) &&
                !SportsLiveNonSport.isNonSport(item)
            // Results come from the score feed, never from addon items.
            SportsLiveTab.RESULTS -> false
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
