package com.nuvio.tv.core.sportslive.scores

import com.nuvio.tv.domain.model.FollowedTeam
import com.nuvio.tv.domain.model.SportsLiveItem

/**
 * A side More… can follow (#134): a real ESPN team of [event], named [label] (ESPN's short name).
 * Following stores ESPN's name and id ([TeamFollows.teamToFollow]); never a raw addon title.
 */
data class FollowTarget(val label: String, val event: ScoreSnapshot, val side: ScoreTeam) {
    /** Whether any of [followed] is this team. */
    fun isFollowed(followed: List<FollowedTeam>): Boolean = TeamFollows.followersOf(followed, event, side).isNotEmpty()
}

/**
 * What "Follow ‹team›" More… offers for a card (#134): only sides matched to a real team, never a side read
 * from the raw title ("Follow LIVE: Bowls: New Zealand" was offered and saved as a team).
 * - With the card's strict ESPN game, its two teams, in the order the card shows them ([homeFirst]).
 * - Else the team directory's stand-in game ([TeamDirectory.standInEvent]: every league the card can be on is
 *   listed and exactly one names both sides), in the card's order.
 * - Else nothing.
 * A side ESPN gives no id or name for is left out.
 */
object FollowTargets {

    fun of(
        item: SportsLiveItem,
        game: ScoreSnapshot?,
        homeFirst: Boolean,
        directory: TeamDirectory = TeamDirectory.shared
    ): List<FollowTarget> {
        if (item.isChannel) return emptyList()
        if (game != null) {
            val sides = if (homeFirst) listOf(game.home, game.away) else listOf(game.away, game.home)
            return sides.mapNotNull { target(game, it) }
        }
        val standIn = directory.standInEvent(item) ?: return emptyList()
        return listOf(standIn.away, standIn.home).mapNotNull { target(standIn, it) }
    }

    private fun target(event: ScoreSnapshot, side: ScoreTeam): FollowTarget? {
        if (side.id.isNullOrBlank()) return null
        val label = listOf(side.shortDisplayName, side.displayName, side.name, side.abbreviation)
            .firstNotNullOfOrNull { it?.trim()?.takeIf(String::isNotEmpty) } ?: return null
        return FollowTarget(label, event, side)
    }
}
