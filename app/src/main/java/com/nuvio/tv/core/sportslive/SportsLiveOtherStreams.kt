package com.nuvio.tv.core.sportslive

import com.nuvio.tv.core.sportslive.scores.ScoreCard
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import java.text.Normalizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Live listings named "A vs B" that are no game in progress (live.27, TV reports #29-#36, 2026-11-23: "LIVE · 178" on a
 * Monday evening): team and league channels showing a schedule card ("Bayern München vs Hoffenheim", "Started 4:00 PM"),
 * replays, season passes and shows that a source's live events list labels live. A non-game shown LIVE is worse than
 * leaving it out (product rulings). They leave Live now, Home's Live now row, the player's panel
 * (CH± lineup) and every live count, for Live now's "Other streams" group at the end of More live
 * ([SportsLiveMoreLive.OTHER_STREAMS]), still playable, never marked LIVE. A row is one when every source of it is:
 * - ESPN has no such game ([SportsLiveNoEspnGames]): its league's scoreboards for ESPN's day answered afresh, both its
 *   teams are that league's teams, and neither plays on them (the score repository decides; it fails open);
 * - or its title says so on its own ([isNotGameTitle]): a season ("2026-27"), a show after a colon (": The Match"), or a
 *   sport or league word as one side ("Kosárlabda vs NBA").
 * Never a channel (Live now lists one only with a live signal of its own), never a replay (not on Live now anyway), and
 * never a row a source of which ESPN matched to a game ([SportsLiveMatchedEvents]): a title rule never overrules ESPN.
 */
object SportsLiveOtherStreams {

    /** True when the Live now row [item] is no game in progress at [nowMs]; see the object doc. */
    fun isOtherStream(
        item: SportsLiveItem,
        nowMs: Long,
        verdicts: SportsLiveNoEspnGames = SportsLiveNoEspnGames.shared,
        matched: SportsLiveMatchedEvents = SportsLiveMatchedEvents.shared
    ): Boolean {
        val sources = (listOf(item) + item.alternates).filter { !it.isChannel }
        if (item.isChannel || item.kind == SportsLiveKind.REPLAY || sources.isEmpty()) return false
        if (sources.any { matched.leagueOf(it.streamKey) != null }) return false
        return sources.all { source -> isNotGameTitle(source) || verdicts.holds(source, nowMs) }
    }

    /** Live now's rows [items] split into the games and the other streams ([isOtherStream]), each kept in its order. */
    fun split(
        items: List<SportsLiveItem>,
        nowMs: Long,
        verdicts: SportsLiveNoEspnGames = SportsLiveNoEspnGames.shared,
        matched: SportsLiveMatchedEvents = SportsLiveMatchedEvents.shared
    ): Pair<List<SportsLiveItem>, List<SportsLiveItem>> {
        if (items.isEmpty()) return items to emptyList()
        val (others, games) = items.partition { isOtherStream(it, nowMs, verdicts, matched) }
        return games to others
    }

    /**
     * True when [item]'s own title says it is no game: a season written in it ([isSeasonTitle]), a show's name after a
     * colon ([isShowSubtitle]), or a side that is a sport or league word rather than a team ([isSportWordSide]).
     * Conservative: a real game's subtitle (": Premier League", ": El Clásico", ": NBA Cup") is none of these.
     */
    fun isNotGameTitle(item: SportsLiveItem): Boolean = notGameTitle(item.meta.name to item.teams)

    // Kept per title (#152: every list build reads every row's; the folding and regexes run once per title).
    private val notGameTitle = Memo(4_096) { (name, teams): Pair<String, TeamPair?> ->
        isSeasonTitle(name) || isShowSubtitle(name) || isSportWordSide(teams ?: SportsLiveTeams.parse(name))
    }

    /**
     * "Utah Jazz vs LA Lakers 2026-27", "… 2025/26", "… 2026-2027": a season closing a title with no colon (a season pass),
     * never a date ("2026-11-23") nor a competition's season before the game ("NBA 2026-27: Jazz vs Lakers").
     */
    internal fun isSeasonTitle(name: String): Boolean = ':' !in name && SEASON.findAll(name).any { match ->
        val first = match.groupValues[1].toInt()
        val second = match.groupValues[2]
        val next = if (second.length == 2) (first + 1) % 100 else first + 1
        second.toInt() == next
    }

    /** "Liverpool vs Manchester Utd: The Match": what follows the colon is a show's or a replay's name ([SHOW_WORDS]). */
    internal fun isShowSubtitle(name: String): Boolean {
        val colon = name.indexOf(':')
        if (colon < 0) return false
        val subtitle = fold(name.substring(colon + 1)).replace(NON_WORD, " ").trim()
        if (subtitle.isEmpty()) return false
        if (subtitle in SHOW_TITLES) return true
        val words = subtitle.split(' ').filter { it.isNotEmpty() }
        return words.any { it in SHOW_WORDS } || words.zipWithNext().any { (a, b) -> "$a $b" in SHOW_PHRASES }
    }

    /**
     * "Kosárlabda vs NBA", "Basketball vs NBA": a side of a two-sided title is a sport or a league named alone, not a team.
     * A title with no team-like side at all ("ATP vs Stockholm Open") names no game and is [SportsLiveOtherEvents]' to place.
     */
    internal fun isSportWordSide(teams: TeamPair?): Boolean {
        teams ?: return false
        return listOf(teams.home, teams.away).any { side ->
            val folded = fold(side).replace(NON_WORD, " ").replace(SPACES, " ").trim()
            folded in SPORT_WORDS || folded in LEAGUE_WORDS || SportsLiveSportKind.isPlainSportWord(side)
        }
    }

    private val MARKS = Regex("""\p{Mn}+""")
    private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")
    private val SPACES = Regex("""\s+""")

    private fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(MARKS, "").lowercase().trim()

    // A year, then "-", "/" or "–", then the next year in two or four digits, ending the title; no date part before it.
    private val SEASON = Regex("""(?<![\d/-])((?:19|20)\d{2})\s?[-/–]\s?(\d{4}|\d{2})[\s)\]]*$""")

    /** Subtitles that are a show's whole name. */
    // Not "The Game" (Michigan v Ohio State's own name) nor "The Big Match" (a broadcaster's live branding).
    private val SHOW_TITLES = setOf("the match", "match of the day")

    /** A word in the subtitle that makes it a show or a replay, not the game itself. */
    private val SHOW_WORDS = setOf(
        "highlights", "preview", "previews", "review", "replay", "rewind", "classic", "classics", "documentary", "reaction",
        "analysis", "magazine", "recap", "debate", "preshow", "postshow", "pregame", "postgame", "prematch", "postmatch"
    )

    /** Two-word phrases that do the same. */
    private val SHOW_PHRASES = setOf("build up", "pre match", "post match", "pre game", "post game", "full match", "extended highlights")

    /** Sport words in the languages the sources write them in, folded (no accents), beyond [SportsLiveSportKind]'s own. */
    private val SPORT_WORDS = setOf(
        "kosarlabda", "labdarugas", "foci", "fudbal", "futbal", "kosarka", "koszykowka", "pilka nozna", "baloncesto",
        "basquete", "basquetbol", "calcio", "pallacanestro", "jegkorong", "hokej", "voetbal", "fotboll", "fodbold",
        "fotball", "jalkapallo", "handball", "rukomet", "kezilabda", "volleyball", "odbojka", "roplabda", "pallavolo"
    )

    /** Leagues and governing bodies named alone, folded. */
    private val LEAGUE_WORDS = setOf(
        "nba", "nfl", "nhl", "mlb", "mls", "wnba", "ncaa", "ncaab", "ncaaf", "ncaam", "ncaaw", "nba g league", "g league",
        "euroleague", "eurocup", "premier league", "epl", "la liga", "laliga", "bundesliga", "serie a", "ligue 1",
        "eredivisie", "champions league", "europa league", "conference league", "ucl", "uefa", "fifa", "ufc", "atp", "wta",
        "f1", "formula 1"
    )
}

/**
 * The live listings the score repository found ESPN has no game for ([SportsLiveOtherStreams]), by source key
 * ([SportsLiveItem.streamKey]), with the two sides they were judged on: a source retitled for another game is judged
 * again. A verdict holds for the rest of ESPN's (US Eastern) day it was last confirmed on, whose scoreboard said no game,
 * and [ACROSS_MIDNIGHT_MS] into the next (a source moved to Other streams is tracked only while Live now's More live row
 * lists it; Home tracks its row alone): the next day it is a listing like any other until ESPN is asked again. Bounded,
 * thread-safe.
 */
class SportsLiveNoEspnGames {

    private class Verdict(val teams: TeamPair, val atMs: Long)

    private val verdicts = LinkedHashMap<String, Verdict>()
    private val revisionFlow = MutableStateFlow(0L)

    /** Bumped when a source becomes or stops being one: lists are built again ([SportsLiveSharedBuckets.stampChanges]). */
    val revision: StateFlow<Long> = revisionFlow.asStateFlow()

    /** ESPN has no game today for the sources [keys], whose card names [teams]. */
    @Synchronized
    fun record(keys: Collection<String>, teams: TeamPair, nowMs: Long) {
        var changed = false
        for (key in keys) {
            val old = verdicts.remove(key)
            if (old == null || old.teams != teams || !valid(old, nowMs)) changed = true
            if (verdicts.size >= MAX_KEPT) verdicts.remove(verdicts.keys.first())
            verdicts[key] = Verdict(teams, nowMs)
        }
        if (changed) revisionFlow.value += 1
    }

    /** The sources [keys] are (or may be) games again: ESPN has their game, or the check no longer holds. */
    @Synchronized
    fun forget(keys: Collection<String>) {
        var changed = false
        for (key in keys) if (verdicts.remove(key) != null) changed = true
        if (changed) revisionFlow.value += 1
    }

    /** True when [source] holds a verdict at [nowMs], judged on the two sides its title names now. */
    @Synchronized
    fun holds(source: SportsLiveItem, nowMs: Long): Boolean {
        val verdict = verdicts[source.streamKey] ?: return false
        if (!valid(verdict, nowMs)) return false
        return ScoreCard.from(source)?.teams == verdict.teams
    }

    @Synchronized
    internal fun clear() {
        verdicts.clear()
        revisionFlow.value += 1
    }

    private fun valid(verdict: Verdict, nowMs: Long): Boolean {
        // A list built on a clock read a moment before the verdict landed counts it too.
        return nowMs - verdict.atMs < ACROSS_MIDNIGHT_MS || SportsLiveMatchedEvents.espnDay(nowMs) == SportsLiveMatchedEvents.espnDay(verdict.atMs)
    }

    companion object {
        /** A verdict confirmed late on ESPN's day holds this long into the next (a game's night across Eastern midnight). */
        const val ACROSS_MIDNIGHT_MS = 2 * 60 * 60_000L
        private const val MAX_KEPT = 2_000

        /** What every list uses. */
        val shared = SportsLiveNoEspnGames()
    }
}
