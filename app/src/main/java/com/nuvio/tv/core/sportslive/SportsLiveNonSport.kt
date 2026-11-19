package com.nuvio.tv.core.sportslive

import com.nuvio.tv.domain.model.SportsLiveItem

/**
 * Items an addon lists beside its games that are no sport (#145): TV episodes ("LIVE: Chicago P.D. S14, E7")
 * and shows under a catch-all tag ("OTHER") that name no sport and no pairing. They stay out of the sports
 * tabs. Anything whose title, genres, description or catalog names a sport ([SportsLiveSportKind.tagOf]) is
 * always kept, as is a channel.
 */
object SportsLiveNonSport {
    // "S14, E7", "S14E7", "S1 E2", "1x02", "Season 3 Episode 4".
    private val EPISODE = Regex(
        """\bS\d{1,3}\s*[,.]?\s*E\d{1,4}\b|\b\d{1,2}x\d{2}\b|\bseason\s*\d+\b.*\bepisode\s*\d+\b""",
        RegexOption.IGNORE_CASE
    )

    /** True when [item] is no sport: an episode, or a non-sport tag with no pairing, and no sport named anywhere. */
    fun isNonSport(item: SportsLiveItem): Boolean {
        if (item.isChannel) return false
        val episode = EPISODE.containsMatchIn(item.meta.name)
        val untaggedShow = item.teams == null && item.sport?.let(SportsLiveTeams::isNonSportTag) == true
        if (!episode && !untaggedShow) return false
        return SportsLiveSportKind.tagOf(item) == null
    }
}
