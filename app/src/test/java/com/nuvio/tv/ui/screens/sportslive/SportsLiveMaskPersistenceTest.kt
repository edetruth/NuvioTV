package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.Video
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #458 (#362): a hidden replay's Detail page, Stream screen or player restored after process death masks as before: the
 * spoiler-safe and Sports & Live playback ids are saved and read back at app start, for a day.
 */
class SportsLiveMaskPersistenceTest {

    private class MemoryStore : SportsLiveMaskStore {
        var text: String? = null
        override fun read(): String? = text
        override fun write(text: String) {
            this.text = text
        }
    }

    private val start = 1_800_000_000_000L
    private var now = start

    @Before
    fun setUp() {
        SportsLiveExtraSources.reset()
        SportsLiveExtraSources.nowMs = { now }
    }

    @After
    fun tearDown() = SportsLiveExtraSources.reset()

    private val folder = Meta(
        id = "folder", type = ContentType.SERIES, rawType = "series", name = "Team A 2-1 Team B", poster = "https://img.example.invalid/p.jpg",
        posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = "Team A won", releaseInfo = null,
        imdbRating = null, genres = emptyList(), runtime = null, director = emptyList(), cast = emptyList(),
        videos = listOf(
            Video(id = "folder:ep1", title = "Highlights", released = null, thumbnail = null, season = 1, episode = 1, overview = null)
        ),
        country = null, awards = null, language = null, links = emptyList()
    )

    /** The process dies: memory is gone; the next start reads the store back. */
    private fun processDeath(store: MemoryStore) {
        SportsLiveExtraSources.reset()
        SportsLiveExtraSources.nowMs = { now }
        SportsLiveExtraSources.installMaskStore(store)
    }

    @Test
    fun `a hidden replay restored after process death is still masked, its videos and sports playback too`() {
        val store = MemoryStore()
        SportsLiveExtraSources.installMaskStore(store)
        SportsLiveExtraSources.markSportsPlayback("folder")
        SportsLiveExtraSources.markSpoilerSafe("folder", true)
        sportsLiveDetailMeta("folder", folder)

        now += 2 * 60 * 60_000L
        processDeath(store)

        assertTrue(SportsLiveExtraSources.isSpoilerSafe("folder"))
        assertTrue(SportsLiveExtraSources.isSpoilerSafe("folder:ep1"))
        assertTrue(SportsLiveExtraSources.isSportsPlayback("folder"))
        assertFalse(sportsLiveKeepsWatchProgress("folder"))
        // The restored Detail page reads the addon's meta again: masked.
        val shown = sportsLiveDetailMeta("folder", folder)
        assertEquals("Team A – Team B", shown.name)
        assertNull(shown.description)
        assertNull(shown.poster)
    }

    @Test
    fun `a replay shown again unmasked is saved unmasked`() {
        val store = MemoryStore()
        SportsLiveExtraSources.installMaskStore(store)
        SportsLiveExtraSources.markSpoilerSafe("folder", true)
        SportsLiveExtraSources.markSpoilerSafe("folder", false)
        processDeath(store)
        assertFalse(SportsLiveExtraSources.isSpoilerSafe("folder"))
    }

    @Test
    fun `saved masks older than a day are not restored`() {
        val store = MemoryStore()
        SportsLiveExtraSources.installMaskStore(store)
        SportsLiveExtraSources.markSpoilerSafe("folder", true)
        now += SportsLiveMasks.EXPIRY_MS + 1
        processDeath(store)
        assertFalse(SportsLiveExtraSources.isSpoilerSafe("folder"))
    }

    @Test
    fun `an unreadable or missing store restores nothing and breaks nothing`() {
        val store = MemoryStore().apply { text = "garbage" }
        SportsLiveExtraSources.installMaskStore(store)
        assertFalse(SportsLiveExtraSources.isSpoilerSafe("folder"))
        SportsLiveExtraSources.installMaskStore(MemoryStore())
        assertFalse(SportsLiveExtraSources.isSportsPlayback("folder"))
    }

    @Test
    fun `the saved text round-trips and leaves out ids it cannot store`() {
        val masks = SportsLiveMasks(
            savedAtMs = start,
            spoilerSafe = listOf("a", "bad\nid", " "),
            videos = mapOf("a" to setOf("a:1", "a:2", "bad\u001Fid")),
            sportsPlayback = listOf("a", "p")
        )
        val back = SportsLiveMasks.decode(masks.encode(), start + 1)
        assertEquals(listOf("a"), back?.spoilerSafe)
        assertEquals(mapOf("a" to setOf("a:1", "a:2")), back?.videos)
        assertEquals(listOf("a", "p"), back?.sportsPlayback)
    }
}
