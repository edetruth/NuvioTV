package com.nuvio.tv.core.sportslive.scores

import com.nuvio.tv.core.sportslive.SportsLivePillTime
import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

class GameArtTest {

    private val nba = EspnFixtures.events(EspnFixtures.NBA_20261115, EspnLeagueMap.NBA)
    private val pacers = nba.first { it.eventId == "401909985" } // IND @ PHI, 18:00Z, league "NBA"

    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    /** The device's short time format (JDKs differ on the space before AM/PM). */
    private fun time(hour: Int, minute: Int) =
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.US).format(LocalTime.of(hour, minute))

    private fun item(
        name: String,
        start: String? = "2026-11-15T18:00:00Z",
        timed: Boolean = true,
        kind: SportsLiveKind = SportsLiveKind.EVENTS,
        isChannel: Boolean = false,
        sport: String? = "Basketball"
    ) = SportsLiveItem(
        meta = MetaPreview(
            id = name, type = ContentType.UNKNOWN, rawType = "sport", name = name, poster = "https://example.invalid/p.jpg",
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = emptyList()
        ),
        addonId = "a", addonName = "a", addonBaseUrl = "https://example.invalid", catalogKey = "k", catalogName = "Live",
        kind = kind, isLive = false, isChannel = isChannel, startMs = start?.let(::ms), sport = sport,
        startHasTimeOfDay = timed, teams = if (isChannel) null else SportsLiveTeams.parse(name)
    )

    @Test
    fun `a matched game draws ESPN names, abbreviations and logos in the title's order`() {
        val art = GameArt.of(item("Pacers at 76ers"), pacers)!!
        assertEquals("Indiana Pacers", art.first.name)
        assertEquals("IND", art.first.short)
        assertEquals("Philadelphia 76ers", art.second.name)
        assertEquals("PHI", art.second.short)
        assertTrue(art.hasLogos)
        assertTrue(art.first.logo!!.startsWith("https://a.espncdn.com/"))
        assertEquals("NBA", art.league)
        assertEquals(ms("2026-11-15T18:00:00Z"), art.startMs)
    }

    @Test
    fun `a game saved for later draws no records, which can tell the result`() {
        val withRecords = pacers.copy(
            home = pacers.home.copy(record = "10-5"),
            away = pacers.away.copy(record = "1-15")
        )
        // The fixture's records show when the game is not protected.
        assertEquals("1-15", GameArt.of(item("Pacers at 76ers"), withRecords)!!.first.record)
        assertEquals("10-5", GameArt.ofEvent(withRecords).second.record)
        val saved = withRecords.copy(spoilerProtected = true)
        val art = GameArt.of(item("Pacers at 76ers"), saved)!!
        assertNull(art.first.record)
        assertNull(art.second.record)
        val event = GameArt.ofEvent(saved)
        assertNull(event.first.record)
        assertNull(event.second.record)
        // Results' events carry no protection mark: the stage passes whether its score shows.
        assertNull(GameArt.ofEvent(withRecords, records = false).first.record)
        assertEquals("1-15", GameArt.ofEvent(withRecords, records = true).first.record)
    }

    @Test
    fun `an ESPN start without a time (TBD) keeps the card's own start`() {
        val tbd = pacers.copy(startMs = ms("2026-11-15T05:00:00Z"), timeValid = false)
        val art = GameArt.of(item("Pacers at 76ers", start = "2026-11-15T18:00:00Z"), tbd)!!
        assertEquals(ms("2026-11-15T18:00:00Z"), art.startMs)
        assertTrue(art.hasLogos)
    }

    @Test
    fun `an unmatched game still gets a card from its own names and start, without logos`() {
        val art = GameArt.of(item("NBA: Boston Celtics vs New York Knicks", start = "2026-11-15T23:30:00Z"), null)!!
        assertEquals(GameArtTeam("Boston Celtics", "Boston Celtics", null), art.first)
        assertEquals(GameArtTeam("New York Knicks", "New York Knicks", null), art.second)
        assertFalse(art.hasLogos)
        assertEquals("Basketball", art.league)
        assertEquals(ms("2026-11-15T23:30:00Z"), art.startMs)
        assertTrue(art.hasTimeOfDay)
    }

    @Test
    fun `an event the sides do not sit on is not used`() {
        // "Celtics vs 76ers" matches only one side of IND @ PHI: own names, no logos.
        val art = GameArt.of(item("Celtics vs 76ers"), pacers)!!
        assertEquals("Celtics", art.first.name)
        assertFalse(art.hasLogos)
        assertEquals("Basketball", art.league)
    }

    @Test
    fun `a logo missing on one side sets both names in large type`() {
        val art = GameArt.of(item("Pacers at 76ers"), pacers.copy(home = pacers.home.copy(logo = null)))!!
        assertFalse(art.hasLogos)
        assertEquals("IND", art.first.short)
    }

    @Test
    fun `channels, replays and items naming no game keep the addon's art`() {
        assertNull(GameArt.of(item("US| ESPN [Pacers vs 76ers]", isChannel = true, kind = SportsLiveKind.CHANNELS), pacers))
        assertNull(GameArt.of(item("Pacers at 76ers", kind = SportsLiveKind.REPLAY), pacers))
        assertNull(GameArt.of(item("UFC 300: Main Card"), null))
    }

    @Test
    fun `kickoff is shown in the viewer's local time, not the addon's UTC`() {
        val art = GameArt.of(item("Celtics vs Knicks", start = "2026-11-15T13:00:00Z"), null)!!
        val now = ms("2026-11-15T09:00:00Z")
        // 13:00 UTC is 22:00 in Tokyo, the same day.
        assertEquals(SportsLivePillTime.Today(time(22, 0)), GameArt.kickoff(art, now, ZoneId.of("Asia/Tokyo"), Locale.US))
        // And 08:00 in New York.
        assertEquals(SportsLivePillTime.Today(time(8, 0)), GameArt.kickoff(art, now, ZoneId.of("America/New_York"), Locale.US))
        assertEquals(SportsLivePillTime.Today(time(13, 0)), GameArt.kickoff(art, now, ZoneOffset.UTC, Locale.US))
        // 23:30 UTC is tomorrow in Tokyo.
        val late = GameArt.of(item("Celtics vs Knicks", start = "2026-11-15T23:30:00Z"), null)!!
        assertEquals(SportsLivePillTime.Tomorrow(time(8, 30)), GameArt.kickoff(late, now, ZoneId.of("Asia/Tokyo"), Locale.US))
    }

    @Test
    fun `a bare date shows as a date and no start shows none`() {
        val bare = GameArt.of(item("Celtics vs Knicks", start = "2026-11-20T00:00:00Z", timed = false), null)!!
        assertTrue(GameArt.kickoff(bare, ms("2026-11-15T09:00:00Z"), ZoneOffset.UTC, Locale.US) is SportsLivePillTime.Other)
        assertNull(GameArt.kickoff(GameArt.of(item("Celtics vs Knicks", start = null), null)!!, 0L, ZoneOffset.UTC, Locale.US))
    }

    @Test
    fun `an unmatched card draws the logos its sides matched before, before any score (#66)`() {
        val book = TeamLogoBook(active = true)
        book.learn(EspnLeagueMap.NBA, pacers.away)
        book.learn(EspnLeagueMap.NBA, pacers.home)
        val art = GameArt.of(item("Pacers at 76ers"), null, book)!!
        assertTrue(art.hasLogos)
        assertEquals(pacers.away.logo, art.first.logo)
        assertEquals("IND", art.first.short)
        // Names stay the card's own until the game is matched.
        assertEquals("Pacers", art.first.name)
        // One side unknown: no logos (both or none).
        assertFalse(GameArt.of(item("Pacers at Knicks"), null, book)!!.hasLogos)
    }

}
