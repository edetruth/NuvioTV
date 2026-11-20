package com.nuvio.tv.core.sportslive.scores

import com.nuvio.tv.core.sportslive.Memo
import java.text.Normalizer

/**
 * Tennis names (#160): an addon's side ("Jannik Sinner", "J. Sinner", "Sinner J.", "De Minaur",
 * "Heliovaara/Patten", "A. Gojo & N. Basavareddy") against ESPN's players by surname, accent-folded. Precision
 * first: a card player matches an ESPN player only when its surname is one of the player's surname words and
 * every other word it gives agrees (a first name is the player's, an initial is the first name's).
 */
object TennisNames {

    /** One player as a card writes them: [surname], [given] words (folded), [initials]. */
    internal data class CardPlayer(val surname: String, val given: List<String>, val initials: List<Char>)

    /** One ESPN player: their first name and the rest (the surname words, "de", "minaur"). */
    internal data class EspnPlayer(val first: String?, val surnames: List<String>)

    /**
     * The players [side] names: one, or two for a doubles pair split at "/", "&", "+" or " and ". A leading
     * tournament ("ATP Finals: Sinner") and bracketed parts ("(1)", "[ITA]") are dropped. Empty when a player
     * has no surname (only initials, nothing left).
     */
    internal fun cardPlayers(side: String): List<CardPlayer> = cardSides(side)

    private val cardSides = Memo(2_048) { side: String -> parseCardSide(side) }

    private fun parseCardSide(side: String): List<CardPlayer> {
        val text = side.substringAfterLast(": ").replace(BRACKETED, " ")
        val parts = PAIR.split(text).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.size > 2) return emptyList()
        val players = parts.map { part -> cardPlayer(part) ?: return emptyList() }
        return players
    }

    private fun cardPlayer(text: String): CardPlayer? {
        val raw = fold(text).split(' ').filter { it.isNotEmpty() }
        if (raw.isEmpty()) return null
        val initials = raw.filter { it.length == 1 }.map { it[0] }
        val words = raw.filter { it.length > 1 }
        val surname = words.lastOrNull() ?: return null
        // "Sinner J." writes the surname first: the initial still belongs to the first name.
        return CardPlayer(surname, words.dropLast(1), initials)
    }

    private val espnPlayers = Memo(4_096) { name: String ->
        val words = fold(name).split(' ').filter { it.isNotEmpty() }
        if (words.size <= 1) EspnPlayer(null, words) else EspnPlayer(words.first(), words.drop(1))
    }

    /** True when [card] names [espn] (a full name as ESPN writes it). */
    internal fun matches(card: CardPlayer, espn: String): Boolean {
        val player = espnPlayers(espn)
        if (card.surname !in player.surnames) return false
        val all = listOfNotNull(player.first) + player.surnames
        if (card.given.any { it !in all }) return false
        val firstLetter = player.first?.firstOrNull()
        return card.initials.all { firstLetter == null || it == firstLetter }
    }

    /**
     * True when a card [side] is the ESPN side with [members]' full names: as many players, each card player
     * matching a different member.
     */
    fun sideMatches(side: String, members: List<String>): Boolean {
        val players = cardPlayers(side)
        if (players.isEmpty() || players.size != members.size) return false
        return when (players.size) {
            1 -> matches(players[0], members[0])
            else -> (matches(players[0], members[0]) && matches(players[1], members[1])) ||
                (matches(players[0], members[1]) && matches(players[1], members[0]))
        }
    }

    /**
     * Which way a card's sides sit on [match] (its [TennisMatch.players] in ESPN's order): true when [first] is
     * the first listed player, false when [second] is, null when neither way (or both ways) fits.
     */
    fun firstIsFirstListed(first: String, second: String, match: TennisMatch): Boolean? {
        val players = match.players
        if (players.size != 2) return null
        val straight = sideMatches(first, players[0].members) && sideMatches(second, players[1].members)
        val crossed = sideMatches(first, players[1].members) && sideMatches(second, players[0].members)
        return when {
            straight == crossed -> null
            else -> straight
        }
    }

    /** A side's short name: the surname ("Sinner", "de Minaur"), a pair's two joined ("Heliovaara/Patten"). */
    fun shortName(members: List<String>): String = members.joinToString("/") { surnameOf(it) }

    /** "Alex de Minaur" → "de Minaur", "Jannik Sinner" → "Sinner", a single word as it is. */
    fun surnameOf(fullName: String): String {
        val words = fullName.trim().split(' ').filter { it.isNotEmpty() }
        if (words.size <= 1) return fullName.trim()
        return words.drop(1).joinToString(" ")
    }

    private val MARKS = Regex("""\p{Mn}+""")
    private val NON_LETTER = Regex("""[^\p{L}\s]+""")
    private val WHITESPACE = Regex("""\s+""")
    private val BRACKETED = Regex("""[(\[][^)\]]*[)\]]""")
    private val PAIR = Regex("""\s*/\s*|\s*&\s*|\s*\+\s*|\s+and\s+""", RegexOption.IGNORE_CASE)

    // Accents off, apostrophes and hyphens as spaces ("Auger-Aliassime", "O'Connell" read alike), lower case.
    private val folded = Memo(4_096) { text: String ->
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(MARKS, "")
            .replace('-', ' ').replace('\'', ' ').replace('’', ' ')
            .replace(NON_LETTER, " ").replace(WHITESPACE, " ").trim().lowercase()
    }

    private fun fold(text: String): String = folded(text)
}

/**
 * Finds a tennis card's match on Live Now's copy of the ATP / WTA scoreboards (#160): both card sides must name
 * the match's two sides ([TennisNames.firstIsFirstListed]), singles against singles, pairs against pairs. One
 * match is the answer; with more (a rematch on the same day, a common surname), the one being played, else the
 * one starting nearest the card's start; a tie gives none (a wrong score is worse than none).
 */
object TennisMatcher {

    fun match(card: ScoreCard, events: List<ScoreSnapshot>): ScoreSnapshot? {
        val candidates = events.asSequence()
            .filter { (it.individual as? TennisMatch)?.live == true }
            .filter { event -> TennisNames.firstIsFirstListed(card.teams.home, card.teams.away, event.individual as TennisMatch) != null }
            .distinctBy { it.eventId }
            .toList()
        if (candidates.size <= 1) return candidates.firstOrNull()
        candidates.filter { it.state == ScoreState.IN }.let { live -> if (live.isNotEmpty()) return live.singleOrNull() }
        val start = card.startMs ?: return null
        val ranked = candidates.filter { it.startMs != null }.sortedBy { kotlin.math.abs(it.startMs!! - start) }
        if (ranked.size >= 2 && kotlin.math.abs(ranked[0].startMs!! - start) == kotlin.math.abs(ranked[1].startMs!! - start)) return null
        return ranked.firstOrNull()
    }
}

/**
 * A tennis row's set columns (#160) in the card's order: [first] and [second] side's games per set ("6 3 2" over
 * "4 6 1"), the side serving ([servingFirst]: true the first, false the second, null when not given or not live),
 * and [leader]: 0 or 1 for the side ahead in sets, null when level.
 */
data class TennisColumns(val first: List<Int>, val second: List<Int>, val servingFirst: Boolean?, val leader: Int?)

/** The set formatting of a live tennis match (#160). Pure. */
object TennisSets {
    /** [match]'s columns with [firstIsFirstListed] telling which listed player the card names first. */
    fun columns(match: TennisMatch, firstIsFirstListed: Boolean): TennisColumns? {
        if (match.players.size != 2) return null
        val (a, b) = if (firstIsFirstListed) match.players[0] to match.players[1] else match.players[1] to match.players[0]
        val count = maxOf(a.sets.size, b.sets.size)
        if (count == 0) return null
        fun games(player: TennisPlayer) = List(count) { player.sets.getOrNull(it)?.games ?: 0 }
        val serving = when {
            a.serving && !b.serving -> true
            b.serving && !a.serving -> false
            else -> null
        }
        val leader = when {
            a.setsWon > b.setsWon -> 0
            b.setsWon > a.setsWon -> 1
            else -> null
        }
        return TennisColumns(games(a), games(b), serving, leader)
    }

    /** "6–4 3–6 7–6(5)" from the first side's view: each set's games, a tiebreak set with its losing points. */
    fun line(match: TennisMatch, firstIsFirstListed: Boolean): String {
        if (match.players.size != 2) return ""
        val (a, b) = if (firstIsFirstListed) match.players[0] to match.players[1] else match.players[1] to match.players[0]
        val count = maxOf(a.sets.size, b.sets.size)
        return (0 until count).joinToString(" ") { index ->
            val mine = a.sets.getOrNull(index) ?: TennisSet(0)
            val theirs = b.sets.getOrNull(index) ?: TennisSet(0)
            if (IndividualFormat.isMatchTiebreak(mine, theirs)) {
                "[${mine.tiebreak}–${theirs.tiebreak}]"
            } else {
                val loser = when {
                    mine.games > theirs.games -> theirs.tiebreak
                    theirs.games > mine.games -> mine.tiebreak
                    else -> null
                }
                "${mine.games}–${theirs.games}" + (loser?.let { "($it)" } ?: "")
            }
        }
    }
}
