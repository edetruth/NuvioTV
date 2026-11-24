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
    fun `a replay opened on its Detail page plays as Sports and Live, never into Continue Watching`() {
        // F3: as every other sports play path, so the player keeps no upstream watch progress (the addon's raw card on
        // Home) and a replay's player gets the Hold OK sheet (#254), whether its result is hidden or shown.
        listOf(true, false).forEach { hidden ->
            SportsLiveExtraSources.reset()
            sportsLiveOpenDetail(folderCard, hidden) {}
            assertTrue(SportsLiveExtraSources.isSportsPlayback("folder"))
            assertFalse(sportsLiveKeepsWatchProgress("folder"))
        }
        // An addon's own meta id is the one the Detail page plays under.
        sportsLiveDetailMeta("folder", folder.copy(id = "canonical:folder"))
        assertTrue(SportsLiveExtraSources.isSportsPlayback("canonical:folder"))
    }

    @Test
    fun `a replay whose addon names it by another id plays under that id masked too`() {
        // F8: the Detail page plays under meta.id, which an addon may give differently from the catalog's id.
        SportsLiveExtraSources.markSpoilerSafe("catalog:1", true)
        sportsLiveDetailMeta("catalog:1", folder.copy(id = "canonical:1"))
        assertTrue(SportsLiveExtraSources.isSpoilerSafe("canonical:1"))
    }

    @Test
    fun `the player reading the folder again from another addon keeps its episodes masked`() {
        // F8-1: a second read with fewer (or no) videos adds to the folder's spoiler-safe videos, never replaces them.
        SportsLiveExtraSources.markSpoilerSafe("folder", true)
        sportsLiveDetailMeta("folder", folder)
        sportsLiveDetailMeta("folder", folder.copy(videos = emptyList()))
        assertTrue(SportsLiveExtraSources.isSpoilerSafe(episode.id))
    }

    @Test
    fun `the player reading a folder under the addon's own id adds its new episodes to the folder`() {
        // F8-1: "canonical:1" is a video of the folder "catalog:1", so its later reads add to that folder.
        SportsLiveExtraSources.markSpoilerSafe("catalog:1", true)
        sportsLiveDetailMeta("catalog:1", folder.copy(id = "canonical:1"))
        val second = episode.copy(id = "folder:ep2")
        sportsLiveDetailMeta("canonical:1", folder.copy(id = "canonical:1", videos = listOf(episode, second)))
        assertTrue(SportsLiveExtraSources.isSpoilerSafe(second.id))
        assertTrue(SportsLiveExtraSources.isSpoilerSafe(episode.id))
    }

    @Test
    fun `a blank meta id is never registered`() {
        SportsLiveExtraSources.markSpoilerSafe("folder", true)
        SportsLiveExtraSources.markSportsPlayback("folder")
        sportsLiveDetailMeta("folder", folder.copy(id = ""))
        assertFalse(SportsLiveExtraSources.isSpoilerSafe(""))
        assertFalse(SportsLiveExtraSources.isSportsPlayback(""))
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
    fun `the player and the Stream screen read a hidden folder's videos and episode links masked`() {
        // F2: the player fetches the meta again (description, Episodes panel, Next episode card) through
        // sportsLiveDetailMeta under its content id; the Stream screen hands the player its next videos.
        SportsLiveExtraSources.markSpoilerSafe("folder", true)
        val inPlayer = sportsLiveDetailMeta("folder", folder)
        assertNull(inPlayer.description)
        assertNull(inPlayer.videos.single().overview)
        val nextVideos = sportsLiveStreamScreenMeta(episode.id, folder).videos
        assertEquals(listOf("Team A – Team B highlights"), nextVideos.map { it.title })
        assertNull(nextVideos.single().thumbnail)
        // The Episodes panel's links for another episode, read under the player's content id (the folder).
        assertEquals(listOf("Team A – Team B"), SportsLiveExtraSources.spoilerSafeStreams("folder", listOf(scored)).map { it.name })
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
