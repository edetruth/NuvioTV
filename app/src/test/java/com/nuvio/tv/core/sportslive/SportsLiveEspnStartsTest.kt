package com.nuvio.tv.core.sportslive

import com.nuvio.tv.core.sportslive.scores.EspnLeague
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoreState
import com.nuvio.tv.core.sportslive.scores.ScoreTeam
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.ClaimedCatalog
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.domain.model.SportsLiveTab
import java.time.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** live.20 (user TV report): a game an addon lists live that ESPN schedules later is listed on Upcoming at ESPN's start. */
class SportsLiveEspnStartsTest {
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()
    private val minute = 60_000L

    // Friday evening: the addon lists Nice / PSG live (started by its clock); ESPN has it Saturday 16:15Z, scheduled.
    private val friday = ms("2026-11-20T15:40:00Z")
    private val espnKickoff = ms("2026-11-21T16:15:00Z")
    private val live = claimed("live", "Live Events")

    private val starts = SportsLiveEspnStarts.shared

    @After
    fun tearDown() = starts.clear()

    private fun nicePsg(id: String = "nice-psg", status: String = "", released: String? = "2026-11-20T15:30:00Z", releaseInfo: String? = null) =
        SportsLiveItemBucketer.describe(meta(id, "${status}Nice vs PSG", released = released, releaseInfo = releaseInfo), live, friday)

    @Test
    fun `a live card ESPN schedules for the next day is on Upcoming at ESPN's start, and live from it`() {
        val card = nicePsg()
        assertTrue(card.isLive)
        assertEquals(listOf("nice-psg"), SportsLiveItemBucketer.bucket(listOf(card), friday).getValue(SportsLiveTab.LIVE_NOW).map { it.id })

        starts.record(listOf(card.streamKey), espnKickoff, friday)
        val tabs = SportsLiveItemBucketer.bucket(listOf(card), friday)
        assertTrue(tabs.getValue(SportsLiveTab.LIVE_NOW).isEmpty())
        val upcoming = tabs.getValue(SportsLiveTab.UPCOMING).single()
        assertEquals(espnKickoff, upcoming.startMs)
        assertFalse(upcoming.isLive)
        // Home's row and the player's panel read Live now the same way.
        assertTrue(SportsLiveItemBucketer.bucketTab(listOf(card), SportsLiveTab.LIVE_NOW, friday).isEmpty())
        // The next clock change is ESPN's kick-off, and the game is live from it.
        assertEquals(espnKickoff, SportsLiveItemBucketer.nextClockChange(listOf(card), friday))
        val saturday = SportsLiveItemBucketer.bucket(listOf(card), espnKickoff + minute)
        assertEquals(listOf("nice-psg"), saturday.getValue(SportsLiveTab.LIVE_NOW).map { it.id })
    }

    @Test
    fun `ESPN's later start overrules an addon's live status too, never a channel's`() {
        val red = nicePsg(id = "red", status = "🔴 ", released = null)
        val marked = nicePsg(id = "marked", released = null, releaseInfo = "LIVE")
        assertTrue(red.isLive && marked.isLive)
        starts.record(listOf(red.streamKey, marked.streamKey), espnKickoff, friday)
        val tabs = SportsLiveItemBucketer.bucket(listOf(red, marked), friday)
        assertTrue(tabs.getValue(SportsLiveTab.LIVE_NOW).isEmpty())
        assertEquals(setOf("red", "marked"), tabs.getValue(SportsLiveTab.UPCOMING).map { it.id }.toSet())

        val channel = SportsLiveItemBucketer.describe(meta("ch", "Sports 1 [Nice vs PSG]"), claimed("ch", "Channels", SportsLiveKind.CHANNELS), friday)
        starts.record(listOf(channel.streamKey), espnKickoff, friday)
        assertEquals(channel, SportsLiveItemBucketer.reclock(channel, friday))
    }

    @Test
    fun `a card ESPN gave no later start keeps its live status`() {
        val card = nicePsg()
        starts.record(listOf("other|id"), espnKickoff, friday)
        assertEquals(listOf("nice-psg"), SportsLiveItemBucketer.bucket(listOf(card), friday).getValue(SportsLiveTab.LIVE_NOW).map { it.id })
    }

    @Test
    fun `a live-catalog card whose own start is hours ahead is not live, with no ESPN help`() {
        val card = nicePsg(released = "2026-11-21T16:15:00Z")
        assertFalse(card.isLive)
        val tabs = SportsLiveItemBucketer.bucket(listOf(card), friday)
        assertTrue(tabs.getValue(SportsLiveTab.LIVE_NOW).isEmpty())
        assertEquals(listOf("nice-psg"), tabs.getValue(SportsLiveTab.UPCOMING).map { it.id })
    }

    @Test
    fun `only a scheduled game more than half an hour away is a later start`() {
        val event = event(ScoreState.PRE, espnKickoff)
        assertEquals(espnKickoff, SportsLiveEspnStarts.laterStart(event, friday))
        assertNull(SportsLiveEspnStarts.laterStart(event, espnKickoff - 30 * minute))
        assertNull(SportsLiveEspnStarts.laterStart(event(ScoreState.IN, espnKickoff), friday))
        assertNull(SportsLiveEspnStarts.laterStart(event(ScoreState.POST, espnKickoff), friday))
        assertNull(SportsLiveEspnStarts.laterStart(event.copy(timeValid = false), friday))
    }

    @Test
    fun `a start is kept for six hours after it, and the revision moves only on a change`() {
        val book = SportsLiveEspnStarts()
        book.record(listOf("k"), espnKickoff, friday)
        val revision = book.revision.value
        book.record(listOf("k"), espnKickoff, friday)
        assertEquals(revision, book.revision.value)
        assertEquals(espnKickoff, book.startOf("k", espnKickoff + 5 * 60 * minute))
        assertNull(book.startOf("k", espnKickoff + 6 * 60 * minute))
    }

    private fun event(state: ScoreState, start: Long) = ScoreSnapshot(
        eventId = "e", league = EspnLeague("soccer", "fra.1"), startMs = start, state = state, clockText = null,
        isFinal = state == ScoreState.POST, home = team("Nice"), away = team("Paris Saint-Germain")
    )

    private fun team(name: String) = ScoreTeam(
        id = name, abbreviation = null, displayName = name, shortDisplayName = name, name = name, location = name,
        logo = null, score = null, linescores = emptyList()
    )

    private fun claimed(id: String, name: String, kind: SportsLiveKind = SportsLiveKind.EVENTS): ClaimedCatalog {
        val catalog = CatalogDescriptor(type = ContentType.fromString("tv"), rawType = "tv", id = id, name = name)
        val addon = Addon(
            id = "a",
            name = "Source A",
            version = "1.0.0",
            description = null,
            logo = null,
            baseUrl = "https://a.example.invalid",
            catalogs = listOf(catalog),
            types = listOf(catalog.type),
            resources = emptyList()
        )
        return ClaimedCatalog(addon, catalog, "a_tv_$id", kind)
    }

    private fun meta(id: String, name: String, releaseInfo: String? = null, released: String? = null) = MetaPreview(
        id = id,
        type = ContentType.UNKNOWN,
        rawType = "tv",
        name = name,
        poster = null,
        posterShape = PosterShape.LANDSCAPE,
        background = null,
        logo = null,
        description = null,
        releaseInfo = releaseInfo,
        imdbRating = null,
        genres = listOf("Ligue 1"),
        released = released
    )
}
