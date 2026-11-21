package com.nuvio.tv.core.sportslive.identify

import com.nuvio.tv.core.sportslive.Memo
import com.nuvio.tv.core.sportslive.SportsLiveMySportsScope
import com.nuvio.tv.core.sportslive.SportsLiveNations
import com.nuvio.tv.core.sportslive.SportsLiveSportKind
import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
import com.nuvio.tv.domain.model.SportsLiveItem

/**
 * Puts what [TeamIdentifier] learned on a card (#168): a game whose sport is unknown takes its teams'
 * agreed sport word and league, so filters, My sports and score routing read it like a tagged game.
 * Runs on every list rebuild: a map lookup, memoised name work, no regex.
 */
object SportsLiveTeamIdentity {

    private const val SIDES_MEMO = 4_096
    private val sidesOf = Memo(SIDES_MEMO) { name: String -> SportsLiveTeams.writtenOrder(name) }

    /**
     * [item] with its teams' identity from [book] when its sport is unknown: the sport as a word the sport
     * rules read ("Basketball", "Ice Hockey") and the league added to its genres. Otherwise [item] itself.
     */
    fun identified(item: SportsLiveItem, book: TeamIdentityBook = TeamIdentityBook.shared): SportsLiveItem {
        if (book.isEmpty || item.isChannel || item.teams == null) return item
        val (first, second) = sides(item.meta.name) ?: return item
        val identity = book.identityOf(first, second) ?: return item
        // Two nations (#204): a sport only from a live list of that sport (cricket's), never a team search's answer.
        if (identity.sport != CRICKET && sportUnknown(item) && SportsLiveNations.isNationalPair(first, second)) return item
        // A sport-only identity (live.20: no league both sides share) adds no genre.
        val genres = if (identity.league.isBlank() || item.meta.genres.any { it.equals(identity.league, ignoreCase = true) }) item.meta.genres
        else item.meta.genres + identity.league
        // A soccer game that names no competition (item 3: "FC Porto vs USC Paredes", tagged Soccer) takes its league.
        if (identity.sport == SOCCER && soccerLeagueUnknown(item)) return item.copy(meta = item.meta.copy(genres = genres))
        // ESPN's live cricket list confirms a card only its teams suggested cricket for (2026-11-21): it takes the word.
        if (!sportUnknown(item) && !(identity.sport == CRICKET && sportUnknownByText(item))) return item
        return item.copy(sport = TeamIdentityRules.sportWord(identity.sport), meta = item.meta.copy(genres = genres))
    }

    /**
     * True when [item] is a soccer game that names no competition (item 3): its sport says soccer and every source
     * routes to soccer/all alone, so no league list or feed can place it; its teams are looked up as an unknown
     * sport's are.
     */
    internal fun soccerLeagueUnknown(item: SportsLiveItem): Boolean =
        SportsLiveMySportsScope.kindOf(item) == SportsLiveSportKind.SOCCER &&
            (listOf(item) + item.alternates).all { EspnLeagueMap.leaguesFor(it) == SOCCER_ALL_ONLY }

    private val SOCCER_ALL_ONLY = listOf(EspnLeagueMap.SOCCER_ALL)
    private const val SOCCER = "soccer"
    private const val CRICKET = "cricket"

    /** The logos the teams' lookup found for [first] and [second] (item 3), in that order; null unless both. */
    fun logosOf(first: String, second: String, book: TeamIdentityBook = TeamIdentityBook.shared): Pair<String, String>? {
        val (a, b) = book.identityOf(first, second)?.badges ?: return null
        return if (a.isNullOrBlank() || b.isNullOrBlank()) null else a to b
    }

    /** True when no text of [item] or its alternates names a sport (its teams may still suggest one). */
    internal fun sportUnknownByText(item: SportsLiveItem): Boolean =
        (listOf(item) + item.alternates).all { SportsLiveMySportsScope.textKindOf(it) == null }

    /** True when neither [item] nor any of its alternates tells a sport. */
    internal fun sportUnknown(item: SportsLiveItem): Boolean =
        (listOf(item) + item.alternates).all { SportsLiveMySportsScope.kindOf(it) == null }

    /** The game's two sides in written order ([SportsLiveTeams.writtenOrder]), memoised. */
    internal fun sides(name: String): Pair<String, String>? = sidesOf(name)
}
