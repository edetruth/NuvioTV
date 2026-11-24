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
import org.junit.Assert.assertFalse
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
    fun `the hero's status line is dot, clock, period and the score between the abbreviations`() {
        val chip = ScoreChip.Live("Q4 4:12", ChipScore(91, 88))
        assertEquals("● Q4 4:12 · BOS 91–88 NYK", SportsLiveHomeMeta.statusLine(chip, art, strings, "● %1\$s"))
        // No drawn art: the score alone.
        assertEquals("● Q4 4:12 · 91–88", SportsLiveHomeMeta.statusLine(chip, null, strings, "● %1\$s"))
        // A clock with no score yet.
        assertEquals("● Q4 4:12", SportsLiveHomeMeta.statusLine(ScoreChip.Live("Q4 4:12", null), art, strings, "● %1\$s"))
        // A final reads as its chip.
        assertEquals("Final 91–88", SportsLiveHomeMeta.statusLine(ScoreChip.Final(null, ChipScore(91, 88)), art, strings, "● %1\$s"))
    }

    @Test
    fun `long names never stand in for abbreviations in the status line`() {
        val named = art.copy(first = art.first.copy(short = "Boston Celtics"))
        val chip = ScoreChip.Live("Q4 4:12", ChipScore(91, 88))
        assertEquals("● Q4 4:12 · 91–88", SportsLiveHomeMeta.statusLine(chip, named, strings, "● %1\$s"))
    }

    @Test
    fun `a live card shows the red dot, the clock, the period and the score under Show`() {
        val footer = SportsLiveHomeMeta.cardFooter(ScoreChip.Live("Q4 4:12", ChipScore(91, 88)), ScoresMode.SHOW, strings, "Live")
        assertEquals(DrawnCardFooter(lead = "Q4 4:12", live = true, trailing = DrawnCardTrailing.Score("91–88")), footer)
    }

    @Test
    fun `#375 - a game waiting for ESPN reads its start, one Live Now calls Probably ended says so, never Live`() {
        assertEquals(DrawnCardFooter(lead = "7:00 PM"), SportsLiveHomeMeta.cardFooter(null, ScoresMode.SHOW, strings, "Live", awaiting = "7:00 PM"))
        assertEquals(
            DrawnCardFooter(lead = "Probably ended"),
            SportsLiveHomeMeta.cardFooter(null, ScoresMode.SHOW, strings, "Live", noScoreYet = "No score yet", probablyEnded = "Probably ended")
        )
        // ESPN's answer wins over the wait: a live chip reads live.
        assertEquals(true, SportsLiveHomeMeta.cardFooter(ScoreChip.Live("Q4 4:12", ChipScore(91, 88)), ScoresMode.SHOW, strings, "Live", awaiting = "7:00 PM").live)
        // Nothing waiting or ended: as before.
        assertEquals(DrawnCardFooter(lead = "Live", live = true), SportsLiveHomeMeta.cardFooter(null, ScoresMode.OFF, strings, "Live"))
    }

    @Test
    fun `#468 - a postponed or cancelled game's card reads ESPN's word in every Scores mode, never Live`() {
        for (mode in listOf(ScoresMode.SHOW, ScoresMode.HIDE_UNTIL_FOCUSED, ScoresMode.OFF)) {
            val chip = if (mode == ScoresMode.HIDE_UNTIL_FOCUSED) ScoreChip.Hidden else null
            assertEquals(DrawnCardFooter(lead = "Canceled"), SportsLiveHomeMeta.cardFooter(chip, mode, strings, "Live", offStatus = "Canceled"))
        }
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
    fun `a live card with no score says No score yet under Show, as Live Now's rows do`() {
        assertEquals(
            DrawnCardFooter(lead = "Live", live = true, trailing = DrawnCardTrailing.Note("No score yet")),
            SportsLiveHomeMeta.cardFooter(null, ScoresMode.SHOW, strings, "Live", noScoreYet = "No score yet")
        )
        // Scores Off: nothing was looked up, so nothing reads as missing.
        assertEquals(DrawnCardFooter(lead = "Live", live = true), SportsLiveHomeMeta.cardFooter(null, ScoresMode.OFF, strings, "Live", noScoreYet = "No score yet"))
    }

    @Test
    fun `the game playing in the mini player reads Watching, never No score yet`() {
        // live.26 TV: its score is masked while it plays (the broadcast shows its own), so it has no chip; Live Now's row
        // says Watching, and Home's card said "No score yet" during a 2-0 game.
        assertEquals(
            DrawnCardFooter(lead = "Live", live = true, trailing = DrawnCardTrailing.Note("▶ Watching")),
            SportsLiveHomeMeta.cardFooter(null, ScoresMode.SHOW, strings, "Live", noScoreYet = "No score yet", watching = "▶ Watching")
        )
        // Hide until focused keeps its one chip.
        assertEquals(
            DrawnCardFooter(lead = "Live", live = true, trailing = DrawnCardTrailing.Note("Score hidden")),
            SportsLiveHomeMeta.cardFooter(ScoreChip.Hidden, ScoresMode.HIDE_UNTIL_FOCUSED, strings, "Live", watching = "▶ Watching")
        )
    }

    @Test
    fun `a game the feed says has not started reads its start, never Live`() {
        assertEquals(
            DrawnCardFooter(lead = "Starts 7:00 PM"),
            SportsLiveHomeMeta.cardFooter(null, ScoresMode.SHOW, strings, "Live", noScoreYet = "No score yet", startsAt = "Starts 7:00 PM")
        )
        // Spoiler rules first: Hide until focused keeps one chip for scheduled and live games alike.
        assertEquals(
            DrawnCardFooter(lead = "Live", live = true, trailing = DrawnCardTrailing.Note("Score hidden")),
            SportsLiveHomeMeta.cardFooter(ScoreChip.Hidden, ScoresMode.HIDE_UNTIL_FOCUSED, strings, "Live", startsAt = "Starts 7:00 PM")
        )
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
        val row = SportsLiveHomeRow.build(listOf(game), "Live now", mapOf(game.streamKey to "● Q4 4:12 · BOS 91–88 NYK"), words)
        val card = row.items.single()
        // Drawn by Home: no addon art with LIVE baked in.
        assertNull(card.poster)
        assertNull(card.landscapePoster)
        val hero = sportsLiveHeroPreview(card, row)!!
        assertEquals("Celtics vs Knicks", hero.title)
        assertEquals("NBA · Live now", hero.contentTypeText)
        assertEquals("● Q4 4:12 · BOS 91–88 NYK", hero.secondaryHighlightText)
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
    fun `the hero shows no addon date when a game has no score label`() {
        val game = item("g", "Celtics vs Knicks").let { it.copy(meta = it.meta.copy(releaseInfo = "Nov 19, 20:30")) }
        val words = SportsLiveHomeRow.Words(liveNow = "Live now", yourTeam = "★ Your team", streams = { "$it streams" })
        val row = SportsLiveHomeRow.build(listOf(game), "Live now", emptyMap(), words)
        assertNull(sportsLiveHeroPreview(row.items.single(), row)!!.secondaryHighlightText)
    }

    @Test
    fun `a catch-all sport tag never reads as the league in the hero`() {
        val game = item("g", "Cartagena vs Tigres").copy(sport = "Other")
        val words = SportsLiveHomeRow.Words(liveNow = "Live now", yourTeam = "★ Your team", streams = { "$it streams" })
        val row = SportsLiveHomeRow.build(listOf(game), "Live now", emptyMap(), words)
        assertEquals("Live now", sportsLiveHeroPreview(row.items.single(), row)!!.contentTypeText)
    }

    @Test
    fun `sports rows are titled without the type suffix`() {
        val row = SportsLiveHomeRow.build(listOf(item("g", "Celtics vs Knicks")), "Live now")
        assertEquals("Live now", catalogRowTitle(row, showCatalogTypeSuffix = true))
    }

    @Test
    fun `other rows keep the usual hero`() {
        val card = item("m", "A Movie").meta
        val row = SportsLiveHomeRow.build(listOf(item("m", "A Movie")), "Popular").copy(addonBaseUrl = "https://addon.example")
        assertNull(sportsLiveHeroPreview(card, row))
    }

    @Test
    fun `addon titles lose their pipes and stray spaces`() {
        assertEquals("Moto3 · Round 21 · Portugal Free Practice 1", SportsLiveHomeMeta.cleanTitle(" Moto3 | Round 21 |  Portugal Free Practice 1"))
        assertEquals("Celtics vs Knicks", SportsLiveHomeMeta.cleanTitle("Celtics vs Knicks"))
        assertEquals("NBA", SportsLiveHomeMeta.cleanTitle("| NBA |"))
    }

    @Test
    fun `every game gets a drawn card on Home, a channel keeps its art`() {
        assertTrue(SportsLiveHomeMeta.drawsCard(item("g", "Celtics vs Knicks")))
        assertTrue(SportsLiveHomeMeta.drawsCard(item("m", "Moto3 Round 21")))
        assertFalse(SportsLiveHomeMeta.drawsCard(item("c", "Sports Channel 1").copy(isChannel = true, kind = SportsLiveKind.CHANNELS)))
    }

    @Test
    fun `a game naming no two sides carries no addon art and a clean title`() {
        val art = "https://example.invalid/channel-logo.png"
        val event = item("m", " Moto3 | Round 21 | Portugal Free Practice 1").let {
            it.copy(meta = it.meta.copy(poster = art, background = art, landscapePoster = art, logo = art))
        }
        val words = SportsLiveHomeRow.Words(liveNow = "Live now", yourTeam = "★ Your team", streams = { "$it streams" })
        val card = SportsLiveHomeRow.build(listOf(event), "Live now", emptyMap(), words).items.single()
        assertNull(card.poster)
        assertNull(card.background)
        assertNull(card.landscapePoster)
        assertNull(card.logo)
        assertTrue(card.name, '|' !in card.name && !card.name.startsWith(" "))
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
