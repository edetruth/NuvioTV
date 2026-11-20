package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.core.sportslive.scores.ChipScore
import com.nuvio.tv.core.sportslive.scores.GameArt
import com.nuvio.tv.core.sportslive.scores.GameArtTeam
import com.nuvio.tv.core.sportslive.scores.ScoreChip
import com.nuvio.tv.core.sportslive.scores.ScoreChipStrings
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.ui.screens.sportslive.DrawnCardFooter
import com.nuvio.tv.ui.screens.sportslive.DrawnCardTrailing
import com.nuvio.tv.ui.screens.sportslive.SportsLiveCatchUpHomeRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

/** Home's drawn sports cards and hero (V2 phase 10): the words they show. */
class SportsLiveHomeMetaTest {

    private val strings = ScoreChipStrings("%1\$d–%2\$d", "%1\$s · %2\$s", "%1\$s %2\$s", "Final", "Postponed", "Score hidden")
    private val art = GameArt(
        first = GameArtTeam(name = "Boston Celtics", short = "BOS", logo = null),
        second = GameArtTeam(name = "New York Knicks", short = "NYK", logo = null),
        league = "NBA",
        startMs = null,
        hasTimeOfDay = false
    )

    @Test
    fun `ESPN's clock reads clock first then the period`() {
        assertEquals("4:12" to "Q4", SportsLiveHomeMeta.clockParts("Q4 4:12"))
        assertEquals("9:51" to "2nd half", SportsLiveHomeMeta.clockParts("9:51 - 2nd Half"))
        assertEquals("78'" to null, SportsLiveHomeMeta.clockParts("78'"))
        assertEquals("Halftime" to null, SportsLiveHomeMeta.clockParts("Halftime"))
    }

    @Test
    fun `the hero's status line is dot, clock, period and the score between the abbreviations`() {
        val chip = ScoreChip.Live("Q4 4:12", ChipScore(91, 88))
        assertEquals("● 4:12 · Q4 · BOS 91–88 NYK", SportsLiveHomeMeta.statusLine(chip, art, strings, "● %1\$s"))
        // No drawn art: the score alone.
        assertEquals("● 4:12 · Q4 · 91–88", SportsLiveHomeMeta.statusLine(chip, null, strings, "● %1\$s"))
        // A clock with no score yet.
        assertEquals("● 4:12 · Q4", SportsLiveHomeMeta.statusLine(ScoreChip.Live("Q4 4:12", null), art, strings, "● %1\$s"))
        // A final reads as its chip.
        assertEquals("Final 91–88", SportsLiveHomeMeta.statusLine(ScoreChip.Final(null, ChipScore(91, 88)), art, strings, "● %1\$s"))
    }

    @Test
    fun `long names never stand in for abbreviations in the status line`() {
        val named = art.copy(first = art.first.copy(short = "Boston Celtics"))
        val chip = ScoreChip.Live("Q4 4:12", ChipScore(91, 88))
        assertEquals("● 4:12 · Q4 · 91–88", SportsLiveHomeMeta.statusLine(chip, named, strings, "● %1\$s"))
    }

    @Test
    fun `a live card shows the red dot, the clock, the period and the score under Show`() {
        val footer = SportsLiveHomeMeta.cardFooter(ScoreChip.Live("Q4 4:12", ChipScore(91, 88)), ScoresMode.SHOW, strings, "Live")
        assertEquals(DrawnCardFooter(lead = "4:12", detail = "Q4", live = true, trailing = DrawnCardTrailing.Score("91–88")), footer)
    }

    @Test
    fun `Hide until focused reads Score hidden on every game card`() {
        assertEquals(
            DrawnCardFooter(lead = "Live", live = true, trailing = DrawnCardTrailing.Note("Score hidden")),
            SportsLiveHomeMeta.cardFooter(ScoreChip.Hidden, ScoresMode.HIDE_UNTIL_FOCUSED, strings, "Live")
        )
        // Home polls nothing under Hide: no chip still reads Score hidden.
        assertEquals(
            DrawnCardFooter(lead = "Live", live = true, trailing = DrawnCardTrailing.Note("Score hidden")),
            SportsLiveHomeMeta.cardFooter(null, ScoresMode.HIDE_UNTIL_FOCUSED, strings, "Live")
        )
    }

    @Test
    fun `Scores Off shows the live dot and no score`() {
        assertEquals(DrawnCardFooter(lead = "Live", live = true), SportsLiveHomeMeta.cardFooter(null, ScoresMode.OFF, strings, "Live"))
    }

    @Test
    fun `a clock without a score shows no score`() {
        val footer = SportsLiveHomeMeta.cardFooter(ScoreChip.Live("78'", null), ScoresMode.SHOW, strings, "Live")
        assertEquals(DrawnCardFooter(lead = "78'", live = true), footer)
    }

    @Test
    fun `the hero's eyebrow and extra line`() {
        assertEquals("NBA · Live now", SportsLiveHomeMeta.eyebrow("NBA", "Live now"))
        assertEquals("Live now", SportsLiveHomeMeta.eyebrow(" ", "Live now"))
        val streams: (Int) -> String = { "$it streams" }
        assertEquals("★ Your team · 2 streams", SportsLiveHomeMeta.extraLine(followed = true, streams = 2, yourTeam = "★ Your team", streamsText = streams))
        assertEquals("★ Your team", SportsLiveHomeMeta.extraLine(followed = true, streams = 1, yourTeam = "★ Your team", streamsText = streams))
        assertEquals("3 streams", SportsLiveHomeMeta.extraLine(followed = false, streams = 3, yourTeam = "★ Your team", streamsText = streams))
        assertNull(SportsLiveHomeMeta.extraLine(followed = false, streams = 1, yourTeam = "★ Your team", streamsText = streams))
    }

    @Test
    fun `a Catch up card reads Final and its date, never a score`() {
        val start = Instant.parse("2026-11-15T18:00:00Z").toEpochMilli()
        assertEquals("Final · Sun 15 Nov", SportsLiveHomeMeta.catchUpLead(start, "Final", ZoneOffset.UTC, Locale.UK))
        assertEquals("Final", SportsLiveHomeMeta.catchUpLead(null, "Final", ZoneOffset.UTC, Locale.UK))
    }

    @Test
    fun `the hero for a live game reads league, status and your team, never the addon's type, date or blurb`() {
        val game = item("g", "Celtics vs Knicks", followed = true).let {
            it.copy(meta = it.meta.copy(poster = "https://img.example/live.png", description = "Watch the big game live"))
        }
        val words = SportsLiveHomeRow.Words(liveNow = "Live now", yourTeam = "★ Your team", streams = { "$it streams" })
        val row = SportsLiveHomeRow.build(listOf(game), "Live now", mapOf(game.streamKey to "● 4:12 · Q4 · BOS 91–88 NYK"), words)
        val card = row.items.single()
        // Drawn by Home: no addon art with LIVE baked in.
        assertNull(card.poster)
        assertNull(card.landscapePoster)
        val hero = sportsLiveHeroPreview(card, row)!!
        assertEquals("Celtics vs Knicks", hero.title)
        assertEquals("NBA · Live now", hero.contentTypeText)
        assertEquals("● 4:12 · Q4 · BOS 91–88 NYK", hero.secondaryHighlightText)
        assertEquals("★ Your team", hero.description)
        assertNull(hero.yearText)
        assertNull(hero.imdbText)
        assertTrue(hero.genres.isEmpty())
    }

    @Test
    fun `the hero for a Catch up card names the league and Replay only`() {
        val card = MetaPreview(
            id = "catchup:1", type = ContentType.UNKNOWN, rawType = "sport", name = "Indiana Pacers at Philadelphia 76ers",
            poster = null, posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = "Replay", imdbRating = null, genres = listOf("NBA")
        )
        val row = SportsLiveCatchUpHomeRow.build(emptyList(), "Catch up · your teams", { "" }, "Replay").copy(items = listOf(card))
        val hero = sportsLiveHeroPreview(card, row)!!
        assertEquals("NBA · Replay", hero.contentTypeText)
        assertNull(hero.secondaryHighlightText)
        assertNull(hero.description)
        assertNull(hero.yearText)
    }

    @Test
    fun `other rows keep the usual hero`() {
        val card = item("m", "A Movie").meta
        val row = SportsLiveHomeRow.build(listOf(item("m", "A Movie")), "Popular").copy(addonBaseUrl = "https://addon.example")
        assertNull(sportsLiveHeroPreview(card, row))
    }

    private fun item(id: String, name: String, followed: Boolean = false) = SportsLiveItem(
        meta = MetaPreview(
            id = id, type = ContentType.UNKNOWN, rawType = "tv", name = name, poster = null, posterShape = PosterShape.LANDSCAPE,
            background = null, logo = null, description = null, releaseInfo = null, imdbRating = null, genres = emptyList()
        ),
        addonId = "sports.addon", addonName = "Sports", addonBaseUrl = "https://addon.example", catalogKey = "k", catalogName = "Live",
        kind = SportsLiveKind.EVENTS, isLive = true, isChannel = false, startMs = null, sport = "NBA",
        teams = SportsLiveTeams.parse(name), followed = followed
    )
}
