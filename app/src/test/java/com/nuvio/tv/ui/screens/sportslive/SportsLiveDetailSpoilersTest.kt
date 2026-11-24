package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.alerts.AlertFixtures
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.ui.screens.stream.streamPlaybackInfoFor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #240, #243: a replay whose result is hidden opens the upstream Detail page (a team folder's episodes, or More… >
 * Details) masked, and its episodes' Stream screen and player name their links with the score masked.
 */
class SportsLiveDetailSpoilersTest {

    @Before
    fun setUp() = SportsLiveExtraSources.reset()

    @After
    fun tearDown() = SportsLiveExtraSources.reset()

    private val scored = stream("Team A 2-1 Team B", "https://cdn.example.invalid/replay/index.m3u8")

    private val episode = Video(
        id = "folder:ep1", title = "Team A 2-1 Team B highlights", released = "2026-11-22",
        thumbnail = "https://img.example.invalid/final-score.jpg", streams = listOf(scored), season = 1, episode = 1,
        overview = "Team A won 2-1 after a late goal", seasonPoster = "https://img.example.invalid/season.jpg"
    )

    private val folder = Meta(
        id = "folder", type = ContentType.SERIES, rawType = "series", name = "Team A replays",
        poster = "https://img.example.invalid/poster.jpg", posterShape = PosterShape.LANDSCAPE,
        background = "https://img.example.invalid/celebration.jpg", logo = "https://img.example.invalid/logo.png",
        description = "Latest: Team A 2-1 Team B", releaseInfo = "Final 2-1", imdbRating = null, genres = listOf("Soccer"),
        runtime = null, director = emptyList(), cast = emptyList(), videos = listOf(episode), country = null, awards = null,
        language = null, links = emptyList(), landscapePoster = "https://img.example.invalid/landscape.jpg",
        trailerYtIds = listOf("goals"), seasonPosters = mapOf(1 to "https://img.example.invalid/s1.jpg")
    )

    @Test
    fun `a replay team folder opened with its result hidden shows no result on the Detail page`() {
        SportsLiveExtraSources.markSpoilerSafe("folder", true)
        val shown = sportsLiveDetailMeta("folder", folder)
        val video = shown.videos.single()
        assertEquals("Team A – Team B highlights", video.title)
        assertNull(video.overview)
        assertNull(video.thumbnail)
        assertNull(video.seasonPoster)
        assertEquals(listOf("Team A – Team B"), video.streams.map { it.name })
        assertEquals(video.id, episode.id)
        assertNull(shown.description)
        assertNull(shown.releaseInfo)
        assertNull(shown.poster)
        assertNull(shown.backdropUrl)
        assertNull(shown.logo)
        assertTrue(shown.trailerYtIds.isEmpty())
        assertTrue(shown.seasonPosters.isEmpty())
        assertEquals("Team A replays", shown.name)
        assertEquals(listOf("Soccer"), shown.genres)
    }

    @Test
    fun `a hidden replay's Detail page shows no status, awards, release date or running time`() {
        // F7: the hero draws an unknown status as a badge ("FT 2-1") and the date when there is no release info; a
        // running time tells extra time.
        val replay = folder.copy(
            status = "FT 2-1", awards = "Man of the match", released = "2026-11-22T20:00:00Z", runtime = "128 min",
            videos = listOf(episode.copy(runtime = 128))
        )
        SportsLiveExtraSources.markSpoilerSafe("folder", true)
        val shown = sportsLiveDetailMeta("folder", replay)
        assertNull(shown.status)
        assertNull(shown.awards)
        assertNull(shown.released)
        assertNull(shown.runtime)
        assertNull(shown.videos.single().runtime)
        // An episode's own date only orders the list; it stays.
        assertEquals(episode.released, shown.videos.single().released)
    }

    @Test
    fun `a single replay opened from More Details with its result hidden has its title masked`() {
        val replay = folder.copy(id = "replay", type = ContentType.MOVIE, rawType = "movie", name = "Team A 2-1 Team B", videos = emptyList())
        SportsLiveExtraSources.markSpoilerSafe("replay", true)
        assertEquals("Team A – Team B", sportsLiveDetailMeta("replay", replay).name)
    }

    @Test
    fun `any other title, or a replay whose result shows, opens its Detail page as the addon gives it`() {
        assertSame(folder, sportsLiveDetailMeta("folder", folder))
        SportsLiveExtraSources.markSpoilerSafe("folder", true)
        SportsLiveExtraSources.markSpoilerSafe("folder", false)
        assertSame(folder, sportsLiveDetailMeta("folder", folder))
    }

    @Test
    fun `an episode of a hidden folder lists, heads and plays its links with the score masked`() {
        SportsLiveExtraSources.markSpoilerSafe("folder", true)
        sportsLiveDetailMeta("folder", folder)

        // Its Stream screen: the list (not a Sports & Live playback: opened from the Detail page), the header's meta.
        assertEquals(listOf("Team A – Team B"), sportsLiveStreamScreenOrder(episode.id, listOf(scored)).map { it.name })
        assertNull(sportsLiveStreamScreenMeta(episode.id, folder).poster)
        // The link it hands the player.
        val info = streamPlaybackInfoFor(
            stream = scored, title = "Team A replays", year = null, contentId = "folder", contentType = "series",
            contentName = "Team A replays", poster = null, backdrop = null, logo = null, videoId = episode.id, season = 1,
            episode = 1, episodeTitle = null, profileId = 1, contentLanguage = null
        )
        assertEquals("Team A – Team B", sportsLiveSpoilerSafePlayback(info).streamName)
    }

    @Test
    fun `the folder opened again with its result shown lists its episodes as they are`() {
        SportsLiveExtraSources.markSpoilerSafe("folder", true)
        sportsLiveDetailMeta("folder", folder)
        SportsLiveExtraSources.markSpoilerSafe("folder", false)
        assertEquals(listOf(scored), sportsLiveStreamScreenOrder(episode.id, listOf(scored)))
    }

    @Test
    fun `Details from the section marks the item as its card hides the result, then opens it`() {
        val replay = AlertFixtures.item(name = "Team A vs Team B", id = "replay").copy(kind = SportsLiveKind.REPLAY)
        val opened = mutableListOf<String>()
        sportsLiveOpenDetail(replay, hidden = true) { opened += it.id }
        assertEquals(listOf("replay"), opened)
        assertTrue(SportsLiveExtraSources.isSpoilerSafe("replay"))
        // Shown (or a game's card): as the addon gives it.
        sportsLiveOpenDetail(replay, hidden = false) { opened += it.id }
        assertEquals(false, SportsLiveExtraSources.isSpoilerSafe("replay"))
    }

    // F5: the Game page's Watch replay and More… > Details.
    private val replayCard = AlertFixtures.item(name = "Team A vs Team B", id = "replay").copy(kind = SportsLiveKind.REPLAY)
    private val folderCard = replayCard.copy(meta = replayCard.meta.copy(id = "folder", type = ContentType.SERIES, rawType = "series"), teams = null)
    private val gameCard = AlertFixtures.item(name = "Team A vs Team B", id = "game")

    @Test
    fun `a Game page opens a replay folder masked while any game in it could be hidden, even with its own result shown`() {
        val shown = SportsLiveGameUiState(mode = ScoresMode.HIDE_UNTIL_FOCUSED, revealed = true, replay = folderCard, replayFoldersHidden = true)
        assertTrue(shown.showsScore)
        assertTrue(shown.hidesDetailOf(folderCard))
        assertFalse(shown.copy(mode = ScoresMode.SHOW, replayFoldersHidden = false).hidesDetailOf(folderCard))
        // Not read yet: masked.
        assertTrue(SportsLiveGameUiState(replay = folderCard).hidesDetailOf(folderCard))
    }

    @Test
    fun `a Game page opens a single replay masked while it hides the result, a game's card as the addon gives it`() {
        val hidden = SportsLiveGameUiState(mode = ScoresMode.HIDE_UNTIL_FOCUSED, replay = replayCard, card = gameCard, replayFoldersHidden = false)
        assertTrue(hidden.hidesDetailOf(replayCard))
        assertFalse(hidden.hidesDetailOf(gameCard))
        assertFalse(hidden.copy(revealed = true).hidesDetailOf(replayCard))
    }

    private fun stream(name: String, url: String) = Stream(
        name = name, title = null, description = null, url = url, ytId = null, infoHash = null, fileIdx = null,
        externalUrl = null, behaviorHints = null, addonName = "Sports", addonLogo = null
    )
}
