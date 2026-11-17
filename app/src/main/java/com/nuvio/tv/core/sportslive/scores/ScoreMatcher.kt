package com.nuvio.tv.core.sportslive.scores

import android.util.Log
import com.nuvio.tv.BuildConfig
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
 * - **Both** sides must match a competitor: by alias equality (the competitor's abbreviation,
 *   displayName, shortDisplayName, name, location and location+name, normalised, diacritics stripped and
 *   folded through [SportsLiveTeams.canonicalise]), else by the [SportsLiveTeams.matches] suffix rule
 *   ("Raiders" ↔ "Las Vegas Raiders"). The card's order does not matter. (W)/U21 sides stay distinct.
 * - The event starts within ±3 h of the card's start; with no time of day, on the card's day (today when
 *   it has no start) or the day before, in the device time zone.
 * - Best score wins (equality 3 per side, suffix 2); a tie goes to the closest start.
 * A match is cached per card per day, so a later poll finds the event by id. A miss returns null and is
 * not cached, so the next refresh retries.
 */
class ScoreMatcher(private val zone: () -> ZoneId = { ZoneId.systemDefault() }) {

    private data class Cached(val day: LocalDate, val eventId: String)

    private val cache = ConcurrentHashMap<String, Cached>()

    fun match(card: ScoreCard, events: List<ScoreSnapshot>, nowMs: Long): ScoreSnapshot? {
        val zoneId = zone()
        val day = Instant.ofEpochMilli(card.startMs ?: nowMs).atZone(zoneId).toLocalDate()
        cache[card.key]?.takeIf { it.day == day }?.let { cached ->
            events.firstOrNull { it.eventId == cached.eventId }?.let { return it }
        }
        val window = window(card, nowMs, zoneId)
        val reference = card.startMs ?: nowMs
        val home = sideKeys(card.teams.home)
        val away = sideKeys(card.teams.away)
        val best = events.asSequence()
            .filter { event -> event.startMs != null && event.startMs in window }
            .mapNotNull { event -> score(home, away, event)?.let { event to it } }
            .sortedWith(compareByDescending<Pair<ScoreSnapshot, Int>> { it.second }
                .thenBy { abs(it.first.startMs!! - reference) })
            .firstOrNull()?.first
        if (best == null) {
            if (BuildConfig.DEBUG) Log.d(TAG, "no match: ${card.teams.home} / ${card.teams.away}")
            return null
        }
        if (cache.size >= MAX_CACHED) cache.clear()
        cache[card.key] = Cached(day, best.eventId)
        return best
    }

    private fun window(card: ScoreCard, nowMs: Long, zoneId: ZoneId): LongRange {
        val start = card.startMs
        if (start != null && card.hasTimeOfDay) return (start - WINDOW_MS)..(start + WINDOW_MS)
        // A bare date is midnight UTC of that date; no start means today.
        val anchor = if (start != null) {
            Instant.ofEpochMilli(start).atZone(ZoneOffset.UTC).toLocalDate()
        } else {
            Instant.ofEpochMilli(nowMs).atZone(zoneId).toLocalDate()
        }
        val from = anchor.minusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val until = anchor.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        return from until until
    }

    /** The card side's name as written ([raw], for the suffix rule) and its equality keys. */
    private class SideKeys(val raw: String, val keys: Set<String>)

    private fun sideKeys(name: String): SideKeys {
        val folded = fold(name)
        return SideKeys(folded, setOf(SportsLiveTeams.normalise(folded), SportsLiveTeams.canonicalise(folded)))
    }

    /** Best total over both orientations, or null unless both card sides match distinct competitors. */
    private fun score(home: SideKeys, away: SideKeys, event: ScoreSnapshot): Int? {
        val eventHome = Competitor(event.home)
        val eventAway = Competitor(event.away)
        return listOfNotNull(
            pairScore(eventHome.score(home), eventAway.score(away)),
            pairScore(eventAway.score(home), eventHome.score(away))
        ).maxOrNull()
    }

    private fun pairScore(first: Int, second: Int): Int? = if (first > 0 && second > 0) first + second else null

    /** A competitor's names: equality keys from all of them, suffix-rule names from the full ones. */
    private class Competitor(team: ScoreTeam) {
        private val fullNames: List<String> = listOfNotNull(
            team.displayName,
            team.shortDisplayName,
            team.name,
            if (team.location != null && team.name != null) "${team.location} ${team.name}" else null
        ).map(::fold).distinct()

        // An abbreviation is only lowercased, not canonicalised: ESPN's "IND" is Indiana, not India.
        private val keys: Set<String> = buildSet {
            team.abbreviation?.let { add(SportsLiveTeams.normalise(fold(it))) }
            for (name in fullNames + listOfNotNull(team.location?.let(::fold))) {
                add(SportsLiveTeams.normalise(name))
                add(SportsLiveTeams.canonicalise(name))
            }
            remove("")
        }

        fun score(side: SideKeys): Int = when {
            side.keys.any { it in keys } -> EQUALITY
            fullNames.any { SportsLiveTeams.matches(side.raw, it) } -> SUFFIX
            else -> 0
        }
    }

    private companion object {
        const val TAG = "ScoreMatcher"
        const val WINDOW_MS = 3 * 60 * 60 * 1000L
        const val EQUALITY = 3
        const val SUFFIX = 2
        const val MAX_CACHED = 2_000

        val MARKS = Regex("""\p{Mn}+""")

        fun fold(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD).replace(MARKS, "")
    }
}
