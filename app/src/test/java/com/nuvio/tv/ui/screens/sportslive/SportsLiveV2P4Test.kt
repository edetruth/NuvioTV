package com.nuvio.tv.ui.screens.sportslive

import androidx.compose.ui.unit.dp
import com.nuvio.tv.core.sportslive.SportsLiveBroadcasters
import com.nuvio.tv.core.sportslive.SportsLiveChannelList
import com.nuvio.tv.core.sportslive.SportsLiveChannelRegions
import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.core.sportslive.scores.EspnLeague
import com.nuvio.tv.core.sportslive.scores.FinalNote
import com.nuvio.tv.core.sportslive.scores.ResultDay
import com.nuvio.tv.core.sportslive.scores.ResultRow
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoreState
import com.nuvio.tv.core.sportslive.scores.ScoreTeam
import com.nuvio.tv.core.sportslive.scores.TennisEnding
import com.nuvio.tv.core.sportslive.scores.TennisMatch
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.domain.model.SportsLiveTab
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/** V2 P4 (#132): Channels, Replays and Results in the V2 frame, and the P3 follow-ups. */
class SportsLiveV2P4Test {

    private val zone = ZoneId.of("UTC")
    /** Wed 18 Nov 2026, 13:09 UTC. */
    private val now = ZonedDateTime.of(2026, 11, 18, 13, 9, 0, 0, zone).toInstant().toEpochMilli()

    private fun at(day: Int, hour: Int, minute: Int = 0): Long = ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    private fun item(
        name: String,
        kind: SportsLiveKind = SportsLiveKind.EVENTS,
        startMs: Long? = null,
        timed: Boolean = startMs != null,
        sport: String? = null,
        group: String? = null,
        description: String? = null,
        sources: Int = 1
    ): SportsLiveItem {
        val channel = kind == SportsLiveKind.CHANNELS
        val base = SportsLiveItem(
            meta = MetaPreview(
                id = name, type = ContentType.UNKNOWN, rawType = "sport", name = name, poster = null,
                posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = description,
                releaseInfo = null, imdbRating = null, genres = listOfNotNull(sport)
            ),
            addonId = "a", addonName = "a", addonBaseUrl = "https://example.invalid", catalogKey = "k", catalogName = "Catalog",
            kind = kind, isLive = channel, isChannel = channel, startMs = startMs, sport = sport, startHasTimeOfDay = timed,
            group = group, teams = if (channel) null else SportsLiveTeams.parse(name)
        )
        return base.copy(alternates = (1 until sources).map { base.copy(addonId = "b$it") })
    }

    private fun channel(name: String, group: String?) = item(name, kind = SportsLiveKind.CHANNELS, group = group)

    // --- Channels: regions and grouping ---

    @Test
    fun `a channel's region is its group's country code, else its title's, else Other`() {
        assertEquals("US", SportsLiveChannelRegions.regionOf(channel("Sports Channel 1", "US")))
        assertEquals("UK", SportsLiveChannelRegions.regionOf(channel("Sports Channel 2", "UK")))
        assertEquals("CA", SportsLiveChannelRegions.regionOf(channel("CA : Sports Channel 3", null)))
        // One code per country.
        assertEquals("AR", SportsLiveChannelRegions.regionOf(channel("Canal 1", "ARG")))
        assertEquals("UK", SportsLiveChannelRegions.regionOf(channel("Sports Channel 7", "GBR")))
        // "NOW" is no region: Other.
        assertEquals(SportsLiveChannelRegions.OTHER, SportsLiveChannelRegions.regionOf(channel("Sports Channel 4", "NOW")))
        // A long group name is kept as written.
        assertEquals("UNITED KINGDOM TV", SportsLiveChannelRegions.regionOf(channel("Sports Channel 5", "UNITED KINGDOM TV")))
        // A one-word group that is no country (an addon's own tag) goes under Other.
        assertEquals(SportsLiveChannelRegions.OTHER, SportsLiveChannelRegions.regionOf(channel("Sports Channel 6", "SOMEADDON")))
    }

    @Test
    fun `regions list codes first, then names, Other last`() {
        assertEquals(
            listOf("CA", "UK", "US", "UNITED KINGDOM TV", SportsLiveChannelRegions.OTHER),
            SportsLiveChannelRegions.ordered(listOf(SportsLiveChannelRegions.OTHER, "US", "UNITED KINGDOM TV", "UK", "CA"))
        )
    }

    @Test
    fun `Channels with no picks and no popular brand list the viewer's countries A to Z, no region headings`() {
        // Channels phase 2 (was: grouped by region): regions survive as the country filter and the row's tag.
        val items = listOf(
            channel("US 2", "US"), channel("Mystery", "NOW"), channel("UK 1", "UK"), channel("US 1", "US"), channel("UK 2", "UK")
        )
        val inputs = boardInputs(SportsLiveTab.CHANNELS, items).copy(
            channelLists = com.nuvio.tv.core.sportslive.SportsLiveChannelLists(tvRegion = "US")
        )
        val board = SportsLiveBoard.build(inputs, zone) { false }
        val rows = board.rows!!
        assertEquals(listOf("Mystery", "US 1", "US 2"), rows.map { it.item.meta.name })
        assertEquals(listOf("M", "U", "U").map { SportsLiveChannelList.LETTER + it }, rows.map { it.section })
        assertTrue(board.channels!!.fallback)
        assertTrue(rows.all { it.group == ScoreboardGroup.CHANNELS && it.chip == null })
        // The list is the board's: focus and the stage follow the rows' order.
        assertEquals(rows.map { it.item }, board.items)
    }

    @Test
    fun `a region's label is its country in words, a long name cleaned, or Other`() {
        assertEquals("United States", channelRegionLabel("US", "Other", Locale.US))
        assertEquals("United Kingdom", channelRegionLabel("UK", "Other", Locale.US))
        assertEquals("Albania", channelRegionLabel("AL", "Other", Locale.US))
        assertEquals("Australia", channelRegionLabel("AU", "Other", Locale.US))
        // Sports addons file African channels under AF (P8).
        assertEquals("Africa", channelRegionLabel("AF", "Other", Locale.US))
        assertEquals("Latin America", channelRegionLabel("LAT", "Other", Locale.US))
        assertEquals("United Kingdom TV", channelRegionLabel("UNITED KINGDOM TV", "Other", Locale.US))
        assertEquals("Other", channelRegionLabel(SportsLiveChannelRegions.OTHER, "Other", Locale.US))
    }

    @Test
    fun `sports channels lead - regions with one first, and in each region its sports channels first`() {
        val items = listOf(
            channel("Better Life TV", "AF"), channel("Gabon TV", "AF"),
            channel("Peace TV HD", "AL"), channel("Eurosport 1 HD", "AL"),
            channel("9GEM TV", "AU"), channel("FOX Sports 502", "AU"),
            channel("Mystery Sports", "NOW")
        )
        val grouped = SportsLiveChannelRegions.grouped(items)
        // Other stays last, even with a sports channel.
        assertEquals(listOf("AL", "AU", "AF", SportsLiveChannelRegions.OTHER), grouped.map { it.first })
        assertEquals(listOf("Eurosport 1 HD", "Peace TV HD"), grouped[0].second.map { it.meta.name })
        assertEquals(listOf("FOX Sports 502", "9GEM TV"), grouped[1].second.map { it.meta.name })
        assertEquals(listOf("Better Life TV", "Gabon TV"), grouped[2].second.map { it.meta.name })
    }

    @Test
    fun `a sports channel is a broadcaster brand or a name with a sport word`() {
        assertTrue(SportsLiveBroadcasters.isSportsChannel("Eurosport 1 HD"))
        assertTrue(SportsLiveBroadcasters.isSportsChannel("US: ESPN 2"))
        assertTrue(SportsLiveBroadcasters.isSportsChannel("Sky Sports Main Event"))
        assertTrue(SportsLiveBroadcasters.isSportsChannel("Arena Sport 3"))
        assertFalse(SportsLiveBroadcasters.isSportsChannel("BFM TV"))
        assertFalse(SportsLiveBroadcasters.isSportsChannel("Peace TV HD"))
        assertFalse(SportsLiveBroadcasters.isSportsChannel("Better Life TV"))
    }

    @Test
    fun `a Results line steps from the full name to the short name and abbreviation`() {
        val umes = ScoreTeam(
            id = "1", abbreviation = "UMES", displayName = "Maryland Eastern Shore Hawks", shortDisplayName = "MD Eastern",
            name = "Hawks", location = "Maryland Eastern Shore", logo = null, score = 60, linescores = emptyList()
        )
        assertEquals(listOf("Maryland Eastern Shore Hawks", "MD Eastern", "UMES"), resultNameChoices(umes))
        // Blanks and repeats drop; a team with no names keeps one line.
        val plain = umes.copy(abbreviation = " ", shortDisplayName = "Maryland Eastern Shore Hawks")
        assertEquals(listOf("Maryland Eastern Shore Hawks"), resultNameChoices(plain))
        val bare = umes.copy(abbreviation = null, displayName = null, shortDisplayName = null)
        assertEquals(listOf("Hawks"), resultNameChoices(bare))
    }

    @Test
    fun `the dropdown's face names the selected option, else the first`() {
        val options = sportsLiveFilterOptions(listOf("US", "UK"), "★ My teams", "All regions")
        assertEquals("All regions", sportsLiveDropdownFace(options, null)?.label)
        assertEquals("UK", sportsLiveDropdownFace(options, "UK")?.label)
        assertEquals("All regions", sportsLiveDropdownFace(options, "gone")?.label)
    }

    @Test
    fun `Channels, Replays and Upcoming show a header filter, Live now and Results do not`() {
        assertTrue(sportsLiveHeaderFilterShown(SportsLiveTab.CHANNELS, 2))
        assertTrue(sportsLiveHeaderFilterShown(SportsLiveTab.REPLAYS, 3))
        assertTrue(sportsLiveHeaderFilterShown(SportsLiveTab.UPCOMING, 2))
        assertFalse(sportsLiveHeaderFilterShown(SportsLiveTab.LIVE_NOW, 5))
        assertFalse(sportsLiveHeaderFilterShown(SportsLiveTab.RESULTS, 5))
        assertFalse(sportsLiveHeaderFilterShown(SportsLiveTab.CHANNELS, 1))
    }

    // --- Replays: days, clean titles, never a score ---

    private fun boardInputs(tab: SportsLiveTab, items: List<SportsLiveItem>) = SportsLiveBoardInputs(
        tab = tab, filter = null, items = items, scores = SportsLiveBoardScores(), ended = emptyMap(), excitement = emptyMap(),
        followed = emptyList(), clockMs = now, logoRevision = 0
    )

    private fun replay(name: String, startMs: Long?, timed: Boolean = startMs != null, description: String? = null, sport: String? = "Basketball") =
        item(name, kind = SportsLiveKind.REPLAY, startMs = startMs, timed = timed, sport = sport, description = description)

    private val replays = listOf(
        replay("Team A vs Team B", at(17, 20)),
        replay("Team C vs Team D", at(18, 1)),
        replay("Team E vs Team F", at(18, 11)),
        replay("Team G vs Team H", at(15, 19)),
        replay("Highlights Show", null),
        replay("Team I vs Team J", at(17, 23))
    )

    @Test
    fun `replays group under Today, Yesterday, then the date, newest first, undated last`() {
        val agenda = SportsLiveBoard.build(boardInputs(SportsLiveTab.REPLAYS, replays), zone).agenda!!
        assertEquals(
            listOf(AgendaDayKind.TODAY, AgendaDayKind.YESTERDAY, AgendaDayKind.DATE, AgendaDayKind.UNDATED),
            agenda.days.map { it.kind }
        )
        assertEquals(listOf(18, 17, 15), agenda.days.mapNotNull { it.date?.dayOfMonth })
        // Within a day by time, newest first.
        assertEquals(
            listOf("Team E vs Team F", "Team C vs Team D", "Team I vs Team J", "Team A vs Team B", "Team G vs Team H", "Highlights Show"),
            agenda.items.map { it.meta.name }
        )
        // Day headers sit in the list; a game's list position counts them.
        assertEquals(1, agenda.listIndexOf(0))
        assertEquals(4, agenda.listIndexOf(2))
    }

    @Test
    fun `a CH key on Replays jumps a day`() {
        val agenda = SportsLiveBoard.build(boardInputs(SportsLiveTab.REPLAYS, replays), zone).agenda!!
        assertEquals(2, agenda.target(0, AgendaKey.DAY_FORWARD))
        assertEquals(0, agenda.target(2, AgendaKey.DAY_BACK))
        assertNull(agenda.target(0, AgendaKey.DAY_BACK))
    }

    @Test
    fun `replay rows carry a spoiler free title and no description`() {
        val rows = replayRows(
            listOf(
                replay("Team A 110-102 Team B", at(17, 20), description = "Team A won 110-102 in overtime"),
                replay("TEAM C VS TEAM D (2-1) Live IPTV Recording", at(17, 21), description = "Final 2-1")
            )
        ) { _, _ -> null }
        assertEquals(listOf("Team A vs Team B", "Team C vs Team D"), rows.map { it.item.meta.name })
        assertTrue(rows.all { it.item.meta.description == null })
        // Never a score on a replay row.
        assertTrue(rows.all { it.chip == null && scoreboardScoresOf(it.chip) == ScoreboardScores.None })
        // The row's one-line title is the clean one too.
        assertEquals("Team A · Team B", singleLineTitle(rows[0].item))
        val digits = Regex("""\d+\s*[-–]\s*\d+""")
        assertTrue(rows.none { digits.containsMatchIn(it.item.meta.name) })
    }

    @Test
    fun `a replay that names two teams draws them, else it is a single line`() {
        val rows = replayRows(listOf(replay("Team A vs Team B", at(17, 20)), replay("Highlights Show", at(17, 21))))
        assertTrue(rows[0].twoSided)
        assertEquals("Team A", rows[0].art?.first?.name)
        assertFalse(rows[1].twoSided)
    }

    @Test
    fun `a replay row's time is the locale's short time, none without a time`() {
        assertEquals("8:00 PM", replayTimeText(replay("Team A vs Team B", at(17, 20)), zone, Locale.US))
        assertNull(replayTimeText(replay("Team A vs Team B", at(17, 0), timed = false), zone, Locale.US))
    }

    @Test
    fun `dates read day first in the shared format, never US numeric`() {
        assertEquals("Mon 16 Nov", agendaDateText(LocalDate.of(2026, 11, 16), Locale.UK))
        assertFalse(agendaDateText(LocalDate.of(2026, 11, 16), Locale.US).contains("/"))
    }

    // --- Results: the day in the title, the filter, the rows and the stage ---

    private val today = LocalDate.of(2026, 11, 18)
    private val days = (0..6).map { ResultDay(it, today.minusDays(it.toLong()), isDeviceYesterday = it == 1, isDeviceToday = it == 0) }

    @Test
    fun `left steps to an older day and right to a newer one, nothing past either end`() {
        assertEquals(2, resultsDayStep(days, 1, newer = false))
        assertEquals(0, resultsDayStep(days, 1, newer = true))
        assertNull(resultsDayStep(days, 0, newer = true))
        assertNull(resultsDayStep(days, 6, newer = false))
        // Every earlier day is reachable this way, with no Earlier toggle.
        var day: Int? = 0
        val seen = mutableListOf<Int>()
        while (day != null) {
            seen += day
            day = resultsDayStep(days, day, newer = false)
        }
        assertEquals((0..6).toList(), seen)
    }

    @Test
    fun `the title says Today and Yesterday only when they are the device's days`() {
        assertEquals(ResultsDayName.TODAY, resultsDayName(days[0]))
        assertEquals(ResultsDayName.YESTERDAY, resultsDayName(days[1]))
        assertEquals(ResultsDayName.DATE, resultsDayName(days[2]))
        assertEquals(ResultsDayName.DATE, resultsDayName(ResultDay(1, today.minusDays(1), isDeviceYesterday = false)))
    }

    @Test
    fun `an empty Today still offers yesterday's results`() {
        assertTrue(resultsOffersYesterday(0, hasRows = false))
        assertFalse(resultsOffersYesterday(0, hasRows = true))
        assertFalse(resultsOffersYesterday(1, hasRows = false))
    }

    @Test
    fun `the score column sits right after the names, inside the list's width`() {
        assertEquals(SportsLiveTokens.Size.ListWidth.value.toInt(), ResultRowColumns.ROW_DP)
        // About 520 px from the row's start at 1080p (2 px a dp), not 1 200 px.
        assertTrue("scores at ${ResultRowColumns.SCORES_START_DP} dp", ResultRowColumns.SCORES_START_DP * 2 in 400..560)
        assertEquals(ResultRowColumns.SCORES_START_DP + ResultRowColumns.SCORES_DP + ResultRowColumns.GAP_DP, ResultRowColumns.NOTE_START_DP)
        assertEquals(
            ResultRowColumns.ROW_DP,
            ResultRowColumns.NOTE_START_DP + ResultRowColumns.NOTE_DP + ResultRowColumns.PAD_DP
        )
        // The same height as Live Now's two-team rows.
        assertEquals(SportsLiveTokens.Size.RowSoon.value.toInt(), ResultRowMetrics.HEIGHT_DP)
    }

    private fun result(id: String, individual: Boolean = false): ResultRow {
        val team = ScoreTeam(null, "T$id", "Team $id", null, null, null, null, 1, emptyList())
        val event = ScoreSnapshot(
            eventId = id, league = EspnLeague("basketball", "nba"), startMs = 0L, state = ScoreState.POST, clockText = "Final",
            isFinal = true, home = team, away = team, leagueName = "NBA",
            individual = if (individual) TennisMatch(title = "Open", players = emptyList(), ending = TennisEnding.COMPLETED) else null
        )
        return ResultRow(event, followed = false, note = FinalNote.FT)
    }

    @Test
    fun `the Results stage leads with Watch replay on a strict replay, else Game stats, More last, nothing for individual sports`() {
        val replayItem = replay("Team A vs Team B", at(17, 20))
        assertEquals(
            listOf(ResultsStageButton.WATCH_REPLAY, ResultsStageButton.GAME_STATS, ResultsStageButton.MORE),
            resultsStageButtons(result("1"), replayItem)
        )
        assertEquals(listOf(ResultsStageButton.GAME_STATS, ResultsStageButton.MORE), resultsStageButtons(result("1"), null))
        assertEquals(emptyList<ResultsStageButton>(), resultsStageButtons(result("2", individual = true), replayItem))
    }

    // --- Part B ---

    @Test
    fun `toasts sit bottom left, clear of the rail, and above a card that holds the corner`() {
        // live.19 QA F8: one corner everywhere, bottom left; over video, the player's corner.
        assertTrue(SportsLiveTokens.Toast.Start > SportsLiveTokens.Toast.StartOverVideo)
        val base = SportsLiveTokens.Toast.Bottom
        assertEquals(base, sportsLiveToastBottom(base, height = 540.dp, claimedTop = null, gap = 6.dp))
        // A starting card whose top is at 440 dp: the toast sits 6 dp above it.
        assertEquals(106.dp, sportsLiveToastBottom(base, height = 540.dp, claimedTop = 440.dp, gap = 6.dp))
        // A claim lower than the base never pulls the toast down.
        assertEquals(base, sportsLiveToastBottom(base, height = 540.dp, claimedTop = 539.dp, gap = 0.dp))
    }

    @Test
    fun `a non-team pair reads as one line in the list and in the player's panel`() {
        assertEquals("ATP 250 · Stockholm Open", singleLineTitle(item("ATP 250 vs Stockholm Open")))
        // Odd spacing an addon writes (a no-break space) reads the same.
        assertEquals("ATP 250 · Stockholm Open", singleLineTitle(item("ATP 250 vs Stockholm Open")))
        assertEquals("ATP 250 · Stockholm Open", singleLineTitle(item("ATP 250 VS STOCKHOLM OPEN")))
        // The panel draws its rows from the same model.
        val row = panelBoards(groupPanelRows(listOf(item("ATP 250 vs Stockholm Open", sport = "Tennis").copy(isLive = true)), emptyMap(), null, emptySet(), now), emptyMap(), null, emptySet(), now)
            .single().board
        assertFalse(row.twoSided)
        assertEquals("ATP 250 · Stockholm Open", singleLineTitle(row.item))
    }

    @Test
    fun `a show's acronym keeps its caps in a row`() {
        assertEquals("AEW", singleLineTitle(item("AEW")))
    }

    @Test
    fun `a Just ended row's two scores fit its height`() {
        assertTrue(JUST_ENDED_SCORE_LINE.value * 2 <= SportsLiveTokens.Size.RowJustEnded.value)
        assertTrue(scoreboardScoreSize(compact = true).value < scoreboardScoreSize(compact = false).value)
    }
}
