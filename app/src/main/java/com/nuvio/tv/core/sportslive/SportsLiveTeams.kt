package com.nuvio.tv.core.sportslive

import com.nuvio.tv.domain.model.FollowedTeam
import com.nuvio.tv.domain.model.SportsLiveItem

/** Display names of the two sides of a game, rankings removed. */
data class TeamPair(val home: String, val away: String)

/** Parses and matches team names in event titles; shared by My Teams and one-card-per-game. */
object SportsLiveTeams {
    // In order of preference. " at " / " @ " put the home side second.
    private val SEPARATORS = listOf(" vs ", " vs. ", " v ", " at ", " @ ", " - ")
    private val AWAY_FIRST = setOf(" at ", " @ ")
    private val LEADING_RANK = Regex("^\\d+\\s+")
    private val WHITESPACE = Regex("\\s+")
    private val CLUB_SUFFIXES = setOf("fc", "sc", "cf")
    // "Celtics [HD]", "76ers (2026-11-10 19:00:05)": a trailing bracketed group is not part of the name.
    private val TRAILING_GROUP = Regex("""\s*[\[(][^\[\]()]*[\])]\s*$""")

    // "(W)", "(Women)", "(U21)", "[U21]": they tell teams apart, so they stay part of the name.
    private val QUALIFIER = Regex("""\s*(\((w|women|u\d+)\)|\[u\d+])\s*$""", RegexOption.IGNORE_CASE)
    private val BRACKET_GROUP = Regex("""\[([^\[\]]*)]""")

    /**
     * A game named inside a channel's `[...]` group ("US| PEACOCK PPV 004 [Celtics vs. 76ers (...)]");
     * the rest of a channel name is never parsed, so "Sky Sports - HD" stays null.
     */
    fun parseBracketed(channelName: String): TeamPair? =
        BRACKET_GROUP.findAll(channelName).firstNotNullOfOrNull { parse(it.groupValues[1]) }

    fun parse(name: String): TeamPair? {
        for (separator in SEPARATORS) {
            val index = name.indexOf(separator, ignoreCase = true)
            if (index < 0) continue
            val first = display(name.substring(0, index))
            val second = display(name.substring(index + separator.length))
            if (first.isEmpty() || second.isEmpty()) continue
            return if (separator in AWAY_FIRST) TeamPair(home = second, away = first) else TeamPair(first, second)
        }
        return null
    }

    fun normalise(team: String): String {
        val cleaned = clean(team).lowercase()
        // A qualifier is set aside so "Arsenal FC (W)" still loses its club suffix: "arsenal (w)".
        val qualifier = QUALIFIER.find(cleaned)
        val base = if (qualifier == null) cleaned else cleaned.substring(0, qualifier.range.first)
        val tokens = base.trim().replace(LEADING_RANK, "").split(WHITESPACE).toMutableList()
        while (tokens.size > 1 && tokens.last() in CLUB_SUFFIXES) tokens.removeAt(tokens.lastIndex)
        return (tokens.joinToString(" ") + " " + qualifier?.value?.trim().orEmpty()).trim()
    }

    fun matches(a: String, b: String): Boolean {
        val x = normalise(a)
        val y = normalise(b)
        if (x.isEmpty() || y.isEmpty() || x in CLUB_SUFFIXES || y in CLUB_SUFFIXES) return false
        return x == y || x.endsWith(" $y") || y.endsWith(" $x")
    }

    /**
     * True when [team] plays in [item]: its name matches either side, and the sports agree or either is
     * unknown ([knownSport]). A channel's sport is only its catalog's name ("EasyPlay Live TV"), so it is not compared.
     */
    fun follows(team: FollowedTeam, item: SportsLiveItem): Boolean {
        val pair = item.teams ?: return false
        return followsSide(team, pair.home, item) || followsSide(team, pair.away, item)
    }

    /**
     * True when [team] is the side [side] of [item], under the same sport rule as [follows]: the
     * stored sport and the item's [knownSport] are compared only when both are known.
     */
    fun followsSide(team: FollowedTeam, side: String, item: SportsLiveItem): Boolean {
        val teamSport = team.sport?.takeUnless(::isGenericSport)
        val itemSport = if (item.isChannel) null else knownSport(item)
        val sportsAgree = teamSport == null || itemSport == null || teamSport.equals(itemSport, ignoreCase = true)
        return sportsAgree && matches(team.name, side)
    }

    /** [followsSide] for a merged card: true when [team] is [side] in the card or any of its alternates. */
    fun followsSideOfAnySource(team: FollowedTeam, side: String, item: SportsLiveItem): Boolean =
        followsSide(team, side, item) || item.alternates.any { followsSide(team, side, it) }

    /**
     * The item's sport when it is one: taken from its genres, not its catalog's name ("⚡SportzX Live
     * Events (tv)"), and not a generic genre ("Sports", "Live TV"). Null when unknown.
     */
    fun knownSport(item: SportsLiveItem): String? {
        val sport = item.sport?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        // With no genres the sport is the catalog's name (a merged card may carry an alternate's sport).
        val fromCatalog = item.meta.genres.none { it.isNotBlank() } && sport.equals(item.catalogName.trim(), ignoreCase = true)
        return sport.takeUnless { fromCatalog || isGenericSport(it) }
    }

    private val GENERIC_SPORTS = setOf("sports", "live tv", "live", "tv", "events")

    private fun isGenericSport(sport: String): Boolean = sport.trim().lowercase() in GENERIC_SPORTS

    fun isFollowed(item: SportsLiveItem, followed: List<FollowedTeam>): Boolean = followed.any { follows(it, item) }

    private fun display(side: String): String = clean(side).replace(LEADING_RANK, "").trim()

    /**
     * Drops what an addon wraps around a team name: anything up to an unclosed `[`
     * ("US| PEACOCK PPV 004 [Celtics"), then trailing `[...]` / `(...)` groups and a stray `]` or `)`.
     */
    private fun clean(side: String): String {
        var text = side.trim()
        val open = text.lastIndexOf('[')
        if (open >= 0 && text.indexOf(']', open) < 0) text = text.substring(open + 1).trim()
        while (true) {
            val next = when {
                QUALIFIER.containsMatchIn(text) -> break
                TRAILING_GROUP.containsMatchIn(text) -> text.replace(TRAILING_GROUP, "")
                text.endsWith("]") && '[' !in text -> text.dropLast(1)
                text.endsWith(")") && '(' !in text -> text.dropLast(1)
                else -> break
            }.trim()
            if (next.isEmpty()) break
            text = next
        }
        return text
    }
}
