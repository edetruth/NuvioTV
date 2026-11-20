package com.nuvio.tv.core.sportslive.identify

import com.nuvio.tv.core.sportslive.Memo
import com.nuvio.tv.core.sportslive.SportsLiveMySportsScope
import com.nuvio.tv.core.sportslive.SportsLiveTeams
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
        if (!sportUnknown(item)) return item
        val genres = if (item.meta.genres.any { it.equals(identity.league, ignoreCase = true) }) item.meta.genres
        else item.meta.genres + identity.league
        return item.copy(sport = TeamIdentityRules.sportWord(identity.sport), meta = item.meta.copy(genres = genres))
    }

    /** True when neither [item] nor any of its alternates tells a sport. */
    internal fun sportUnknown(item: SportsLiveItem): Boolean =
        (listOf(item) + item.alternates).all { SportsLiveMySportsScope.kindOf(it) == null }

    /** The game's two sides in written order ([SportsLiveTeams.writtenOrder]), memoised. */
    internal fun sides(name: String): Pair<String, String>? = sidesOf(name)
}
