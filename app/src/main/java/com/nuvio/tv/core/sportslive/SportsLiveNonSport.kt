package com.nuvio.tv.core.sportslive

import com.nuvio.tv.core.sportslive.identify.TeamIdentityRules
import com.nuvio.tv.domain.model.SportsLiveItem

/**
 * Items an addon lists beside its games that are no sport (#145): TV episodes ("LIVE: Chicago P.D. S14, E7")
 * and shows under a catch-all tag ("OTHER") that name no sport and no pairing. They stay out of the sports
 * tabs. Anything whose title, genres, description or catalog names a sport ([SportsLiveSportKind.tagOf]) is
 * always kept, as is a channel.
 *
 * live.20 (user TV report #4: "Family Guy", "South Park" in a live catalog): a title naming no two sides is a
 * show too when it is a known show ([SHOW_TITLES], kept small: add a title when the unidentified log
 * ([SportsLiveUnidentified]) shows one), says "Season 3" or "Episode 4", or the addon lists it under a TV genre
 * ([SHOW_GENRES]: "Animation", "Sitcom", "Series"). Those are kept only when their own text (title,
 * genres, description, an addon sport tag that is not just the catalog's name) names a sport: a "Live Football"
 * catalog's name does not make "Family Guy" a game. A two-team title is never one of these: only an episode
 * pattern can make it a show, or a known show's title split at a dash ("The Simpsons - Treehouse of Horror").
 */
object SportsLiveNonSport {
    // "S14, E7", "S14E7", "S1 E2", "1x02", "Season 3 Episode 4".
    private val EPISODE = Regex(
        """\bS\d{1,3}\s*[,.]?\s*E\d{1,4}\b|\b\d{1,2}x\d{2}\b|\bseason\s*\d+\b.*\bepisode\s*\d+\b""",
        RegexOption.IGNORE_CASE
    )

    // "Season 3", "Episode 12", "Ep. 4" alone: a show only when the title names no two sides.
    private val SEASON_OR_EPISODE = Regex("""\bseason\s*\d{1,2}\b|\bepisode\s*\d{1,4}\b|\bep\.?\s*\d{1,4}\b""", RegexOption.IGNORE_CASE)

    /** TV shows addons list in their live catalogs (live.20). Titles as written; matched as whole leading words. */
    private val SHOW_TITLES: List<String> = listOf(
        "Family Guy", "South Park", "The Simpsons", "Simpsons", "American Dad", "Bob's Burgers", "Futurama",
        "Rick and Morty", "King of the Hill", "The Cleveland Show", "SpongeBob SquarePants", "SpongeBob", "The Office",
        "Seinfeld", "The Big Bang Theory", "How I Met Your Mother", "Two and a Half Men", "Brooklyn Nine-Nine",
        "Parks and Recreation", "It's Always Sunny in Philadelphia", "Bluey", "Peppa Pig", "Paw Patrol"
    )
    private val showKeys: List<String> = SHOW_TITLES.map(TeamIdentityRules::nameKey)

    /** Genres that say TV, not sport. */
    private val SHOW_GENRES = setOf(
        "animation", "animated", "cartoon", "cartoons", "comedy", "sitcom", "drama", "reality", "reality tv", "kids",
        "children", "family", "series", "tv series", "tv show", "tv shows", "show", "shows", "movie", "movies", "film",
        "films", "documentary", "soap", "anime", "talk show"
    )

    // Every list build asks again for the same titles (#152: a regex call on Android allocates native memory).
    private val episodeTitle = Memo(4_096) { name: String -> EPISODE.containsMatchIn(name) }
    private val seasonTitle = Memo(4_096) { name: String -> SEASON_OR_EPISODE.containsMatchIn(name) }
    private val showTitle = Memo(4_096) { name: String -> showTitleUncached(name) }

    /** True when [item] is no sport: an episode, or a show with no pairing, and no sport named; see the class doc. */
    fun isNonSport(item: SportsLiveItem): Boolean {
        if (item.isChannel) return false
        val episode = episodeTitle(item.meta.name)
        val untaggedShow = item.teams == null && item.sport?.let(SportsLiveTeams::isNonSportTag) == true
        if (episode || untaggedShow) return SportsLiveSportKind.tagOf(item) == null
        // A known show's title split at a dash ("The Simpsons - Treehouse of Horror") names no game; "vs" does.
        val show = if (item.teams == null) looksLikeShow(item) else !writtenAsGame(item.meta.name) && showTitle(item.meta.name)
        return show && !namesSportItself(item)
    }

    /** True when [title] is (or starts with) a known show's name ("Family Guy", "South Park: Marathon"). */
    fun isShowTitle(title: String): Boolean = showTitle(title)

    private fun looksLikeShow(item: SportsLiveItem): Boolean =
        showTitle(item.meta.name) || seasonTitle(item.meta.name) ||
            item.meta.genres.any { it.trim().lowercase() in SHOW_GENRES }

    // The item's own words, not its catalog's name (a "Live Football" catalog names no sport for "Family Guy").
    private fun namesSportItself(item: SportsLiveItem): Boolean {
        val sport = item.sport?.takeUnless { it.trim().equals(item.catalogName.trim(), ignoreCase = true) }
        return (listOf(sport, item.meta.name, item.meta.description) + item.meta.genres).any { SportsLiveSportKind.of(it) != null }
    }

    private fun showTitleUncached(title: String): Boolean {
        var key = TeamIdentityRules.nameKey(SportsLiveDisplayText.name(title))
        // "LIVE: Family Guy", "Watch South Park".
        for (lead in LEADS) if (key.startsWith("$lead ")) key = key.removePrefix("$lead ")
        return showKeys.any { show -> key == show || key.startsWith("$show ") }
    }

    private val LEADS = listOf("live", "watch", "now")

    private val VERSUS = listOf(" vs ", " vs. ", " v ", " v. ", " at ", " @ ", " x ")

    private fun writtenAsGame(name: String): Boolean = name.lowercase().let { text -> VERSUS.any { it in text } }
}
