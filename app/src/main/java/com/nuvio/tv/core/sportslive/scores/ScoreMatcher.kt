package com.nuvio.tv.core.sportslive.scores

import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.sportslive.Memo
import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.core.sportslive.TeamPair
import com.nuvio.tv.domain.model.SportsLiveItem
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/** What the matcher needs from a card: a stable [key], its two sides and its start. */
data class ScoreCard(
    val key: String,
    val teams: TeamPair,
    val startMs: Long?,
    /** False when [startMs] is a bare date (midnight UTC), which is matched by day, not ±3 h. */
    val hasTimeOfDay: Boolean
) {
    companion object {
        /**
         * The card for [item], or null when it is not a game. A channel is a game only when
         * [SportsLiveTeams.parseBracketed] finds one in its name; its other teams are never used.
         */
        fun from(item: SportsLiveItem): ScoreCard? {
            val teams = if (item.isChannel) {
                SportsLiveTeams.parseBracketed(item.meta.name)
            } else {
                item.teams ?: SportsLiveTeams.parse(item.meta.name)
            } ?: return null
            return ScoreCard(item.streamKey, teams, item.startMs, item.startHasTimeOfDay)
        }
    }
}


/**
 * Finds the score-feed event for a card. Precision over recall (a wrong score is worse than none):
 * - **Both** sides must match distinct competitors, in either order. A side matches STRONG (3) by
 *   equality with the competitor's abbreviation, displayName, shortDisplayName, name or location+name
 *   (normalised, diacritics stripped, folded through [SportsLiveTeams.canonicalise]; the abbreviation
 *   is only lowercased), or WEAK (2) by equality with its location alone or by the
 *   [SportsLiveTeams.matches] suffix rule ("Raiders" ↔ "Las Vegas Raiders"). The total must be at least
 *   5, so one side must match STRONG.
 * - A (W)/Women or U21-style qualifier must agree: the card's against the competitor's names or, when
 *   they carry none, the event's league slug (`eng.w.1`, `fifa.friendly_u21`).
 * - Time: within ±3 h of a timed card's start; with no game there at all, the pair's one meeting within ±14 h when
 *   both sides match STRONG and the pair meets no other time within ±38 h (#181, a timezone slip). A card with a
 *   bare date is matched on that day first, ranked by distance from mid-day, and on the day before only when that
 *   day has no match, in the device time zone.
 * - A card with no start (chiefly a channel's "[A vs B]") is matched on ESPN's days, US Eastern: today
 *   and yesterday, whatever the device's zone. One meeting of the pair is the match; when there are
 *   more (a series, a home-and-home, a back-to-back), only the one live game, or else the one
 *   scheduled for today, or else today's lone final, is, so yesterday's final never lands on
 *   tonight's card. See [undated].
 * - Best score wins; two different events tied for the best score give no match (ambiguous).
 * A dated card's match is cached per card per day, so a later poll finds the event by id. A miss
 * returns null and is not cached, so the next refresh retries.
 */
class ScoreMatcher(
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    /** The least total for a match: 5 (one side STRONG) for cards; 6 (both STRONG) for stricter uses. */
    private val minScore: Int = MIN_TOTAL,
    /** False: a bare-date or undated card is matched on its own day only, never the day before. */
    private val previousDayFallback: Boolean = true
) {

    private data class Cached(val day: LocalDate, val eventId: String)

    private val cache = ConcurrentHashMap<String, Cached>()

    fun match(card: ScoreCard, events: List<ScoreSnapshot>, nowMs: Long): ScoreSnapshot? {
        val zoneId = zone()
        val start = card.startMs
        val timed = start != null && card.hasTimeOfDay
        val home = side(card.teams.home)
        val away = side(card.teams.away)
        // No start: never cached, so a later poll that brings today's meeting replaces yesterday's.
        if (start == null) return undated(home, away, events, Instant.ofEpochMilli(nowMs).atZone(EASTERN).toLocalDate())
        val day = when {
            timed -> Instant.ofEpochMilli(start).atZone(zoneId).toLocalDate()
            // A bare date is midnight UTC of that date.
            else -> Instant.ofEpochMilli(start).atZone(ZoneOffset.UTC).toLocalDate()
        }
        cache[card.key]?.takeIf { it.day == day }?.let { cached ->
            events.firstOrNull { it.eventId == cached.eventId }?.let { return it.takeUnless { doubleHeader(home, away, it, events) } }
        }
        // The day before is tried only when the day has no candidate at all, not when it is ambiguous.
        val pick = if (timed) {
            best(home, away, events, (start - WINDOW_MS)..(start + WINDOW_MS), reference = start, byStart = true)
                ?: offsetPick(home, away, events, start)
        } else {
            bestOnDay(home, away, events, day, zoneId)
                ?: if (previousDayFallback) bestOnDay(home, away, events, day.minusDays(1), zoneId) else null
        }
        val best = pick?.event?.takeUnless { doubleHeader(home, away, it, events) }
        if (best == null) {
            if (BuildConfig.DEBUG) Log.d(TAG, "no match: ${card.teams.home} / ${card.teams.away}")
            return null
        }
        if (cache.size >= MAX_CACHED) cache.clear()
        cache[card.key] = Cached(day, best.eventId)
        return best
    }

    /**
     * A card with no start, on ESPN's (US Eastern) [today] and, with [previousDayFallback], yesterday:
     * the pair's only meeting there; with more than one, the one live, else the one scheduled for
     * [today], else the one final of [today] (an NBA back-to-back: tonight's game over, last night's
     * final never lands on the card, I6); anything else (two live, two scheduled, finals of
     * yesterday only, two today) gives none.
     */
    private fun undated(home: Side, away: Side, events: List<ScoreSnapshot>, today: LocalDate): ScoreSnapshot? {
        val first = if (previousDayFallback) today.minusDays(1) else today
        val from = first.atStartOfDay(EASTERN).toInstant().toEpochMilli()
        val until = today.plusDays(1).atStartOfDay(EASTERN).toInstant().toEpochMilli()
        val candidates = events
            .filter { event -> event.startMs != null && event.startMs in from until until && score(home, away, event) != null }
            .distinctBy { it.eventId }
        if (candidates.size <= 1) return candidates.firstOrNull()?.takeUnless { doubleHeader(home, away, it, events) }
        val live = candidates.filter { it.state == ScoreState.IN }
        val pick = if (live.isNotEmpty()) {
            live.singleOrNull()
        } else {
            val onToday = candidates.filter { Instant.ofEpochMilli(it.startMs!!).atZone(EASTERN).toLocalDate() == today }
            onToday.singleOrNull { it.state == ScoreState.PRE }
                ?: onToday.singleOrNull()?.takeIf { it.state == ScoreState.POST }
        }
        if (pick == null && BuildConfig.DEBUG) Log.d(TAG, "ambiguous: ${candidates.size} meetings for ${home.base} / ${away.base}")
        return pick?.takeUnless { doubleHeader(home, away, it, events) }
    }

    /**
     * A timed card whose addon clock is hours off ESPN's (#181: a timezone slip, ~6 h), tried only when ±3 h has no
     * candidate at all (never when it is ambiguous): the pair's one meeting within [OFFSET_WINDOW_MS] of the card's
     * start, both sides matched STRONG ([BOTH_STRONG]), and no other meeting of the pair within [UNIQUE_WINDOW_MS]
     * (a back-to-back or a series is never paired across days). A women's-feed game never takes a card that names no
     * women's side (the namesake rule; a youth or women's slug already fails the qualifier check). Teams over time.
     */
    private fun offsetPick(home: Side, away: Side, events: List<ScoreSnapshot>, start: Long): Pick? {
        val meetings = events
            .filter { event -> event.startMs != null && abs(event.startMs - start) <= UNIQUE_WINDOW_MS && score(home, away, event) != null }
            .distinctBy { it.eventId }
        val only = meetings.singleOrNull() ?: return null
        if (abs(only.startMs!! - start) > OFFSET_WINDOW_MS) return null
        if (isWomenFeed(only) && home.qualifier != "w" && away.qualifier != "w") return null
        if ((score(home, away, only) ?: 0) < BOTH_STRONG) return null
        if (BuildConfig.DEBUG) Log.d(TAG, "time-off match: ${home.base} / ${away.base}")
        return Pick(only)
    }

    /**
     * The pair's meeting ESPN has not started yet and has scheduled more than [LATER_LEAD_MS] after [nowMs] (live.20: an
     * addon listed tomorrow's game live). Read for its start only, never as the card's score: it must be the pair's only
     * meeting among [events] (a series or a home-and-home gives none), within [LATER_MAX_MS], both sides STRONG, and a
     * women's-feed game never takes a card that names no women's side. Null otherwise.
     */
    fun laterMeeting(card: ScoreCard, events: List<ScoreSnapshot>, nowMs: Long): ScoreSnapshot? {
        val home = side(card.teams.home)
        val away = side(card.teams.away)
        val only = events.filter { it.startMs != null && score(home, away, it) != null }.distinctBy { it.eventId }.singleOrNull() ?: return null
        val start = only.startMs!!
        if (only.state != ScoreState.PRE || !only.timeValid || start <= nowMs + LATER_LEAD_MS || start > nowMs + LATER_MAX_MS) return null
        if (isWomenFeed(only) && home.qualifier != "w" && away.qualifier != "w") return null
        if ((score(home, away, only) ?: 0) < BOTH_STRONG) return null
        return only
    }

    private fun bestOnDay(home: Side, away: Side, events: List<ScoreSnapshot>, day: LocalDate, zoneId: ZoneId): Pick? {
        val from = day.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val until = day.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val midDay = day.atTime(12, 0).atZone(zoneId).toInstant().toEpochMilli()
        return best(home, away, events, from until until, reference = midDay)
    }

    /** A window's outcome: [event] is null when two different events tie for the best score. */
    private class Pick(val event: ScoreSnapshot?)

    /**
     * The best-scoring event in [window]; null when none matches. When two different events share the
     * best score (a doubleheader, two meetings on one day, a same-city pair) the pick has no event:
     * no score beats a wrong one. With [byStart] (a timed card), a tie between games of different leagues
     * (a men's and a women's college game of the same two schools that day, #163) goes to the one game that
     * starts within [TIE_BREAK_MS] of the card's start.
     */
    private fun best(
        home: Side,
        away: Side,
        events: List<ScoreSnapshot>,
        window: LongRange,
        reference: Long,
        byStart: Boolean = false
    ): Pick? {
        val ranked = events.asSequence()
            .filter { event -> event.startMs != null && event.startMs in window }
            .mapNotNull { event -> score(home, away, event)?.let { event to it } }
            .sortedWith(compareByDescending<Pair<ScoreSnapshot, Int>> { it.second }
                .thenBy { abs(it.first.startMs!! - reference) })
            .toList()
        val top = ranked.firstOrNull() ?: return null
        val tied = ranked.drop(1).any { it.second == top.second && it.first.eventId != top.first.eventId }
        if (tied) {
            if (byStart) startTieBreak(ranked.filter { it.second == top.second }.map { it.first }, reference)?.let { return Pick(it) }
            if (BuildConfig.DEBUG) Log.d(TAG, "ambiguous: ${ranked.size} candidates for ${home.base} / ${away.base}")
            return Pick(null)
        }
        return Pick(top.first)
    }

    // The one tied game near the card's start, when the tied games are of different leagues; else null.
    private fun startTieBreak(tied: List<ScoreSnapshot>, reference: Long): ScoreSnapshot? {
        val games = tied.distinctBy { it.eventId }
        val near = games.singleOrNull { abs(it.startMs!! - reference) <= TIE_BREAK_MS } ?: return null
        return near.takeIf { games.all { it === near || it.league != near.league } }
    }

    /**
     * A card side: its base name (qualifier removed) for the suffix rule, equality keys and qualifier, and
     * [collegeKeys] ([collegeAlternates]: "Georgetown University" as "georgetown"), compared with a
     * team's own names only, never its city (N9).
     */
    private class Side(val base: String, val keys: Set<String>, val qualifier: String?, val collegeKeys: Set<String> = emptySet())

    private fun side(name: String): Side = sideOf(name)

    /**
     * Best total over both orientations, or null unless both sides match and the total is at least 5.
     * A `soccer/all` event whose league is unknown (no league link, no sibling naming it, no women's or
     * youth note in its competition or team names) could be a women's or youth game: both sides must
     * match STRONG (#66).
     */
    private fun score(home: Side, away: Side, event: ScoreSnapshot): Int? {
        val slugQualifier = eventQualifier(event)
        val women = isWomenFeed(event)
        val eventHome = competitor(event.home, slugQualifier, isCollegeFeed(event))
        val eventAway = competitor(event.away, slugQualifier, isCollegeFeed(event))
        val least = if (unknownLeague(event, eventHome, eventAway)) BOTH_STRONG else minScore
        return listOfNotNull(
            pairScore(eventHome.score(home, women), eventAway.score(away, women), least),
            pairScore(eventAway.score(home, women), eventHome.score(away, women), least)
        ).maxOrNull()
    }

    private fun pairScore(first: Int, second: Int, least: Int = minScore): Int? =
        if (first > 0 && second > 0 && first + second >= least) first + second else null

    private fun unknownLeague(event: ScoreSnapshot, home: Competitor, away: Competitor): Boolean =
        event.league == EspnLeagueMap.SOCCER_ALL && event.leagueSlug == EspnLeagueMap.SOCCER_ALL.league &&
            home.qualifier == null && away.qualifier == null

    private fun unknownLeague(event: ScoreSnapshot): Boolean {
        val qualifier = leagueQualifier(event.leagueSlug, event.leagueName)
        return unknownLeague(event, competitor(event.home, qualifier), competitor(event.away, qualifier))
    }

    /**
     * True when [pick] and another event of the pair share ESPN's (US Eastern) day and either has an
     * unknown league (#66): a men's and a women's game of the same two clubs that day (a double-header)
     * cannot be told apart, so neither gets a score.
     */
    private fun doubleHeader(home: Side, away: Side, pick: ScoreSnapshot, events: List<ScoreSnapshot>): Boolean {
        val day = pick.startMs?.let { Instant.ofEpochMilli(it).atZone(EASTERN).toLocalDate() } ?: return false
        val pickUnknown = unknownLeague(pick)
        return events.any { other ->
            other.eventId != pick.eventId &&
                other.startMs?.let { Instant.ofEpochMilli(it).atZone(EASTERN).toLocalDate() } == day &&
                (pickUnknown || unknownLeague(other)) &&
                score(home, away, other) != null
        }
    }

    /** A competitor's names, qualifiers removed: STRONG keys, the WEAK location key, suffix-rule names. */
    /**
     * [college]: the team is on a college feed, where its location is the school's own name ("Central Michigan",
     * ESPN's short name "C Michigan"; item 4), one team per school, so it matches STRONG; a pro city ("Los
     * Angeles") names several teams and stays WEAK.
     */
    private class Competitor(team: ScoreTeam, slugQualifier: String?, private val college: Boolean = false) {
        private val names: List<Pair<String, String?>> =
            (listOfNotNull(team.displayName, team.shortDisplayName, team.name) + team.alternateNames).map { splitQualifier(fold(it)) }

        val qualifier: String? = names.firstNotNullOfOrNull { it.second } ?: slugQualifier

        private val fullNames: List<String> = (names.map { it.first } + listOfNotNull(
            if (team.location != null && team.name != null) splitQualifier(fold("${team.location} ${team.name}")).first else null
        )).filter { it.isNotEmpty() }.distinct()

        // An abbreviation is only lowercased, not canonicalised: ESPN's "IND" is Indiana, not India.
        private val strongKeys: Set<String> = buildSet {
            team.abbreviation?.let { add(SportsLiveTeams.normalise(fold(it))) }
            for (name in fullNames) {
                add(SportsLiveTeams.normalise(name))
                add(SportsLiveTeams.canonicalise(name))
            }
            remove("")
        }

        // A city alone ("Dallas", "Los Angeles") names more than one team.
        private val locationKeys: Set<String> = team.location?.let { splitQualifier(fold(it)).first }
            ?.let { setOf(SportsLiveTeams.normalise(it), SportsLiveTeams.canonicalise(it)) - "" }.orEmpty()

        /** [womenFeed]: the event is on a women's feed, where a card's women's marker is what the feed already says. */
        fun score(side: Side, womenFeed: Boolean = false): Int = when {
            (if (womenFeed && side.qualifier == "w") null else side.qualifier) != qualifier -> 0
            side.keys.any { it in strongKeys } -> STRONG
            side.collegeKeys.any { it in strongKeys } -> STRONG
            side.keys.any { it in locationKeys } -> if (college) STRONG else WEAK
            fullNames.any { SportsLiveTeams.matches(side.base, it) } -> WEAK
            else -> 0
        }
    }

    companion object {
        /** Both sides matched by strong (name or abbreviation) equality. */
        const val BOTH_STRONG = 6

        private const val TAG = "ScoreMatcher"
        private const val WINDOW_MS = 3 * 60 * 60 * 1000L
        /** How far a timed card's start may be off its one strongly matched game (#181). */
        private const val OFFSET_WINDOW_MS = 14 * 60 * 60 * 1000L
        /** No other meeting of the pair may be this close to the card's start for [OFFSET_WINDOW_MS] to apply. */
        private const val UNIQUE_WINDOW_MS = OFFSET_WINDOW_MS + 24 * 60 * 60 * 1000L
        /** How close to a timed card's start a game must be to win a tie with another league's game (#163). */
        private const val TIE_BREAK_MS = 45 * 60 * 1000L
        /** A game ESPN schedules further ahead than this is not live, whatever its addon says (live.20). */
        const val LATER_LEAD_MS = 30 * 60 * 1000L
        /** [laterMeeting] looks this far ahead at most. */
        private const val LATER_MAX_MS = 48 * 60 * 60 * 1000L
        private const val STRONG = 3
        private const val WEAK = 2
        private const val MIN_TOTAL = 5
        private const val MAX_CACHED = 2_000

        /** ESPN files games under their US Eastern date. */
        private val EASTERN: ZoneId = ZoneId.of("America/New_York")

        private val MARKS = Regex("""\p{Mn}+""")

        // Trailing qualifiers that tell a team apart: "(W)", "Women", "Women's", " W"; "U21", "(U21)", "[U21]".
        private val WOMEN = Regex("""\s*(\((w|women)\)|\bwomen'?s?|\s+w)\s*$""", RegexOption.IGNORE_CASE)
        // "U21", "U-21", "Under 21", "Under-21", "U21s", "Sub-17", "Sub 17" (#165), bracketed or not.
        private val YOUTH = Regex(
            """\s*([\[(](?:u|under|sub)[- ]?(\d{1,2})s?[\])]|\b(?:u|under|sub)[- ]?(\d{1,2})s?)\s*$""",
            RegexOption.IGNORE_CASE
        )

        // League slugs of women's competitions, an explicit list checked against ESPN: "eng.w.1",
        // "can.w.nsl", "usa.w.usl.1", "usa.nwsl", "uefa.wchampions", "fifa.wwc", "uefa.weuro",
        // "uefa.wnations", "fifa.wworld.u17", "fifa.wworldq.uefa", "esp.copa_de_la_reina",
        // "conmebol.america.femenina", "fifa.shebelieves". Not
        // "fifa.world" or "fifa.worldq.*" (men's), nor "wal.1" (Wales).
        private val WOMEN_SLUG = Regex("""(^|\.)w(\.|$)|wchampions|\.wwc|nwsl|\.weuro|\.wnations|\.wworld|copa_de_la_reina|women|femenin|feminin|shebelieves""")
        // Youth: "fifa.friendly_u21", "uefa.euro_u21_qual", "fifa.world.u17".
        private val YOUTH_SLUG = Regex("""(?<![a-z])u(\d{1,2})(?!\d)""")

        // A card is scored against every event of its feeds on every poll, and each card side and each
        // team folds the same names every time (#152): the folded forms are kept.
        private val sides = Memo(4_096) { name: String -> sideOfUncached(name) }
        private val competitors = Memo(4_096) { (team, qualifier, college): Triple<ScoreTeam, String?, Boolean> -> Competitor(team, qualifier, college) }

        private fun competitor(team: ScoreTeam, qualifier: String?, college: Boolean = false): Competitor =
            competitors(Triple(team, qualifier, college))

        /** A college feed (item 4): a team's location is its school. */
        private fun isCollegeFeed(event: ScoreSnapshot): Boolean = event.league in COLLEGE_FEEDS

        private val COLLEGE_FEEDS = setOf(EspnLeagueMap.COLLEGE_BASKETBALL, EspnLeagueMap.COLLEGE_BASKETBALL_WOMEN, EspnLeagueMap.COLLEGE_FOOTBALL)

        private fun sideOf(name: String): Side = sides(name)

        private fun sideOfUncached(name: String): Side {
            val (base, qualifier) = splitQualifier(fold(name))
            val keys = setOf(SportsLiveTeams.normalise(base), SportsLiveTeams.canonicalise(base)) - ""
            return Side(base, keys, qualifier, collegeAlternates(SportsLiveTeams.normalise(base)) - keys)
        }

        // A bare name that is another school than "‹name› University": Miami University is Miami (OH),
        // not Miami (FL); Washington University (St. Louis) is not Washington.
        private val UNIVERSITY_KEEPS = setOf("miami", "washington")
        private val SCHOOL_WORDS = setOf("university", "college", "univ")

        /**
         * The other names a college card side may go by on ESPN (N9), from its [normalised] name: without a
         * trailing "University" / "College" ("georgetown university" → "georgetown") or a leading
         * "University of" ("university of michigan" → "michigan"), and with a trailing "State" written
         * "St" or the reverse ("michigan state" ↔ "michigan st", ESPN's short form). "State" is never
         * dropped ("Ohio State" is not Ohio), a one-word name is left alone ("College"), and
         * [UNIVERSITY_KEEPS] are not shortened. They count only against a team's own names, never its city.
         */
        internal fun collegeAlternates(normalised: String): Set<String> {
            val words = normalised.split(' ').filter { it.isNotEmpty() }
            if (words.size < 2) return emptySet()
            val stripped = when {
                words.size >= 3 && words[0] in SCHOOL_WORDS && words[1] == "of" -> words.drop(2)
                words.last() in SCHOOL_WORDS -> words.dropLast(1).takeUnless { it.joinToString(" ") in UNIVERSITY_KEEPS }
                else -> null
            } ?: words
            return buildSet {
                if (stripped != words) add(stripped.joinToString(" "))
                if (stripped.size >= 2 && stripped.last() == "state") add((stripped.dropLast(1) + "st").joinToString(" "))
                if (stripped.size >= 2 && stripped.last() == "st") add((stripped.dropLast(1) + "state").joinToString(" "))
            }
        }

        /**
         * True when [name] is [team] by STRONG equality (abbreviation, displayName, shortDisplayName, name,
         * location+name; never the city alone or the suffix rule) with the same (W)/U21 qualifier, the
         * event's [leagueSlug] (else its competition [leagueName]) standing in when the team's names carry
         * none. "Arsenal" is not "Arsenal Women", "Lakers" alone is not "Los Angeles Lakers".
         */
        fun isStrongSide(name: String, team: ScoreTeam, leagueSlug: String, leagueName: String? = null): Boolean =
            competitor(team, leagueQualifier(leagueSlug, leagueName)).score(sideOf(name)) == STRONG

        /**
         * Which way a matched card's sides sit on [event]: true when [first] is its home team and [second]
         * its away team, false the other way round, null when neither way reaches the match total or both
         * fit equally well. A card shows a score only when this is known, so a score is never put on the
         * wrong team.
         */
        fun firstIsHome(first: String, second: String, event: ScoreSnapshot): Boolean? {
            // A live tennis copy (#160): its home side is ESPN's first listed player, matched by surname.
            (event.individual as? TennisMatch)?.let { match ->
                return if (match.live) TennisNames.firstIsFirstListed(first, second, match) else null
            }
            val qualifier = eventQualifier(event)
            val women = isWomenFeed(event)
            val home = competitor(event.home, qualifier, isCollegeFeed(event))
            val away = competitor(event.away, qualifier, isCollegeFeed(event))
            val firstSide = sideOf(first)
            val secondSide = sideOf(second)
            fun total(a: Int, b: Int) = if (a > 0 && b > 0) a + b else 0
            val asHome = total(home.score(firstSide, women), away.score(secondSide, women))
            val asAway = total(away.score(firstSide, women), home.score(secondSide, women))
            return when {
                maxOf(asHome, asAway) < MIN_TOTAL || asHome == asAway -> null
                else -> asHome > asAway
            }
        }

        private fun fold(text: String): String =
            Normalizer.normalize(text.replace('’', '\''), Normalizer.Form.NFD).replace(MARKS, "").trim()

        /** "Namibia (W)" → ("Namibia", "w"); "England U21" → ("England", "u21"); "Arsenal" → ("Arsenal", null). */
        private fun splitQualifier(name: String): Pair<String, String?> {
            WOMEN.find(name)?.let { return name.substring(0, it.range.first).trim() to "w" }
            YOUTH.find(name)?.let { m ->
                val age = m.groupValues[2].ifEmpty { m.groupValues[3] }
                return name.substring(0, m.range.first).trim() to "u$age"
            }
            return name to null
        }

        // Competition names of women's and youth games, for a soccer/all event whose slug is unknown:
        // "Women's International Friendly", "NCAAW Soccer", "Men's U-21 Friendly", "EURO U-21 Qualifying".
        private val WOMEN_NAME = Regex("""\bwomen'?s?\b|\bncaaw\b|femenin|feminin""", RegexOption.IGNORE_CASE)
        private val YOUTH_NAME = Regex("""\b(?:u|under)[- ]?(\d{1,2})\b""", RegexOption.IGNORE_CASE)

        /**
         * A game on a women's feed (NCAAW, WNBA, NWSL; #163): its teams carry no women's marker, and a card's
         * "(W)" or none both fit it. On a mixed feed (soccer/all) the slug's qualifier still tells them apart.
         */
        private fun isWomenFeed(event: ScoreSnapshot): Boolean = event.league in EspnLeagueMap.WOMEN_FEEDS

        private fun eventQualifier(event: ScoreSnapshot): String? =
            if (isWomenFeed(event)) null else leagueQualifier(event.leagueSlug, event.leagueName)

        /** The women's / youth qualifier of an event's league: from its slug, else its competition name. */
        internal fun leagueQualifier(slug: String, name: String?): String? =
            slugQualifier(slug) ?: name?.let { competition ->
                when {
                    WOMEN_NAME.containsMatchIn(competition) -> "w"
                    else -> YOUTH_NAME.find(competition)?.let { "u${it.groupValues[1]}" }
                }
            }

        private fun slugQualifier(slug: String): String? = when {
            WOMEN_SLUG.containsMatchIn(slug) -> "w"
            // UEFA Youth League ("uefa.youth", "uefa.youth_league") is an under-19 competition.
            "youth" in slug -> YOUTH_SLUG.find(slug)?.let { "u${it.groupValues[1]}" } ?: "u19"
            else -> YOUTH_SLUG.find(slug)?.let { "u${it.groupValues[1]}" }
        }
    }
}
