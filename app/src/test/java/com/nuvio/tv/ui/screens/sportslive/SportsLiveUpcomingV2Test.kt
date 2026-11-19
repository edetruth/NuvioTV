package com.nuvio.tv.ui.screens.sportslive

import android.view.KeyEvent
import com.nuvio.tv.core.sportslive.SportsLiveSportKind
import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.core.sportslive.TeamPair
import com.nuvio.tv.core.sportslive.scores.GameArt
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.domain.model.SportsLiveTab
import com.nuvio.tv.ui.screens.sportslive.mini.SportsLiveMiniCaptionSpot
import com.nuvio.tv.ui.screens.sportslive.mini.SportsLiveMiniGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** V2 Upcoming (#132, P3): the Agenda, its stage and remote, and the Live Now polish (single-line rows, channel stage, caption, panel). */
class SportsLiveUpcomingV2Test {

    private val zone = ZoneId.of("UTC")
    /** Wed 18 Nov 2026, 13:09 UTC. */
    private val now = ZonedDateTime.of(2026, 11, 18, 13, 9, 0, 0, zone).toInstant().toEpochMilli()

    private fun at(day: Int, hour: Int, minute: Int): Long = ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    private fun item(
        name: String,
        startMs: Long?,
        timed: Boolean = startMs != null,
        sport: String? = "Basketball",
        genres: List<String> = listOfNotNull(sport),
        catalogName: String = "Events",
        followed: Boolean = false,
        isLive: Boolean = false,
        isChannel: Boolean = false
    ) = SportsLiveItem(
        meta = MetaPreview(
            id = name, type = ContentType.UNKNOWN, rawType = "sport", name = name, poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = genres
        ),
        addonId = "a", addonName = "a", addonBaseUrl = "https://example.invalid", catalogKey = "k", catalogName = catalogName,
        kind = if (isChannel) SportsLiveKind.CHANNELS else SportsLiveKind.EVENTS, isLive = isLive, isChannel = isChannel,
        startMs = startMs, sport = sport, startHasTimeOfDay = timed,
        teams = SportsLiveTeams.parse(name), followed = followed
    )

    private fun agendaOf(items: List<SportsLiveItem>): SportsLiveAgenda =
        SportsLiveAgenda.of(scoreboardRows(items, SportsLiveTab.UPCOMING, emptyMap(), null, emptySet(), now), now, zone)

    private val week = listOf(
        item("Magic at Rockets", at(18, 15, 30)),
        item("Bruins vs Rangers", at(18, 18, 0), sport = "Hockey"),
        item("Lakers vs Suns", at(18, 18, 0), followed = true),
        item("Arsenal vs Chelsea", at(18, 18, 0), sport = "Soccer"),
        item("Bills vs Dolphins", at(19, 12, 30), sport = "American Football"),
        item("Celtics vs Knicks", at(20, 19, 0)),
        item("Flamengo vs Palmeiras", at(21, 0, 0), timed = false, sport = "Soccer"),
        item("Mystery Event", null, sport = null)
    )

    // Grouping

    @Test
    fun `games group under Today, Tomorrow, the date and a closing Any time`() {
        val agenda = agendaOf(week)
        assertEquals(
            listOf(AgendaDayKind.TODAY, AgendaDayKind.TOMORROW, AgendaDayKind.DATE, AgendaDayKind.ANY_TIME),
            agenda.days.map { it.kind }
        )
        assertEquals(listOf(18, 19, 20), agenda.days.mapNotNull { it.date?.dayOfMonth })
        // Date-only and untimed items sit under Any time, dated first.
        assertEquals(listOf("Flamengo vs Palmeiras", "Mystery Event"), agenda.games.filter { it.day == 3 }.map { it.row.item.meta.name })
        assertEquals("Wed 18 Nov", agendaDateText(agenda.days[0].date!!, java.util.Locale.UK))
    }

    @Test
    fun `slots hold the games starting together and count them`() {
        val agenda = agendaOf(week)
        assertEquals(listOf(1, 3, 1, 1, 2), agenda.slots.map { it.count })
        assertNull(agenda.slots.last().startMs)
        // Each slot's header rides on its first game only.
        assertEquals(listOf(0, 1, 4, 5, 6), agenda.games.indices.filter(agenda::opensSlot))
    }

    @Test
    fun `a followed team leads its slot, the rest keep their order`() {
        val agenda = agendaOf(week)
        assertEquals(listOf("Lakers vs Suns", "Bruins vs Rangers", "Arsenal vs Chelsea"), agenda.games.filter { it.slot == 1 }.map { it.row.item.meta.name })
    }

    @Test
    fun `day headers are list positions of their own, games map to and from them`() {
        val agenda = agendaOf(week)
        assertTrue(agenda.entries[0] is AgendaEntry.Day)
        assertEquals(1, agenda.listIndexOf(0))
        assertEquals(6, agenda.listIndexOf(4)) // the first game of Tomorrow, under two day headers
        assertNull(agenda.gameAt(0))
        assertEquals(4, agenda.gameAt(6))
        agenda.games.indices.forEach { assertEquals(it, agenda.gameAt(agenda.listIndexOf(it))) }
    }

    @Test
    fun `upcoming games are never live rows and a start already passed stays under Today`() {
        val late = item("Hawks vs Heat", at(18, 12, 0))
        val agenda = agendaOf(listOf(late) + week)
        assertEquals(AgendaDayKind.TODAY, agenda.days[agenda.games.first().day].kind)
        assertTrue(agenda.games.none { it.row.group == ScoreboardGroup.LIVE })
    }

    @Test
    fun `a slot counts down only inside the next day`() {
        assertEquals(2 to 21, agendaCountdown(at(18, 15, 30), now))
        assertEquals(23 to 21, agendaCountdown(at(19, 12, 30), now))
        assertNull(agendaCountdown(at(20, 19, 0), now))
        assertNull(agendaCountdown(at(18, 12, 0), now))
        assertNull(agendaCountdown(null, now))
    }

    // Slot and day navigation

    @Test
    fun `left and right jump to the previous or next time slot's first game`() {
        val agenda = agendaOf(week)
        assertEquals(1, agenda.target(0, AgendaKey.SLOT_FORWARD))
        // From the middle of the 6 PM slot: back to 3:30 PM, on to Tomorrow.
        assertEquals(0, agenda.target(2, AgendaKey.SLOT_BACK))
        assertEquals(4, agenda.target(2, AgendaKey.SLOT_FORWARD))
        assertNull(agenda.target(0, AgendaKey.SLOT_BACK))
        // The last slot's Right is left to the stage.
        assertNull(agenda.target(agenda.games.lastIndex, AgendaKey.SLOT_FORWARD))
    }

    @Test
    fun `channel keys jump a day, CH minus on and CH plus back`() {
        val agenda = agendaOf(week)
        assertEquals(4, agenda.target(2, AgendaKey.DAY_FORWARD))
        assertEquals(0, agenda.target(4, AgendaKey.DAY_BACK))
        assertEquals(6, agenda.target(5, AgendaKey.DAY_FORWARD))
        assertNull(agenda.target(0, AgendaKey.DAY_BACK))
        assertEquals(AgendaKey.DAY_FORWARD, agendaKeyOf(KeyEvent.KEYCODE_CHANNEL_DOWN))
        assertEquals(AgendaKey.DAY_BACK, agendaKeyOf(KeyEvent.KEYCODE_CHANNEL_UP))
        assertEquals(AgendaKey.SLOT_BACK, agendaKeyOf(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(AgendaKey.SLOT_FORWARD, agendaKeyOf(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertNull(agendaKeyOf(KeyEvent.KEYCODE_DPAD_DOWN))
    }

    // Reminder bell and primary button

    @Test
    fun `the bell is outlined when off, filled when set, absent when no reminder can be set`() {
        assertEquals(AgendaBell.OFF, agendaBellOf(remindable = true, reminded = false))
        assertEquals(AgendaBell.ON, agendaBellOf(remindable = true, reminded = true))
        assertEquals(AgendaBell.NONE, agendaBellOf(remindable = false, reminded = true))
    }

    @Test
    fun `Remind me leads over 15 minutes out, Watch inside them or with no time`() {
        val later = item("Magic at Rockets", at(18, 15, 30))
        assertEquals(listOf(UpcomingStageButton.REMIND, UpcomingStageButton.MORE), upcomingStageButtons(later, later.startMs, now))
        val soon = item("Hawks vs Heat", now + 10 * 60_000L)
        assertEquals(listOf(UpcomingStageButton.WATCH, UpcomingStageButton.MORE), upcomingStageButtons(soon, soon.startMs, now))
        val edge = item("Hawks vs Heat", now + REMIND_LEAD_MS)
        assertEquals(UpcomingStageButton.WATCH, upcomingStageButtons(edge, edge.startMs, now).first())
        val dateOnly = item("Flamengo vs Palmeiras", at(21, 0, 0), timed = false)
        assertEquals(UpcomingStageButton.WATCH, upcomingStageButtons(dateOnly, null, now).first())
    }

    @Test
    fun `records read with en dashes`() {
        assertEquals("18–9", sportsLiveRecordText("18-9"))
        assertEquals("12–8–14", sportsLiveRecordText("12-8-14"))
    }

    @Test
    fun `the stage says at for a game written away first, else vs`() {
        assertTrue(agendaWrittenAt("Magic at Rockets"))
        assertTrue(agendaWrittenAt("Bills @ Chiefs"))
        assertFalse(agendaWrittenAt("Arsenal vs Chelsea"))
    }

    // Filter labels

    @Test
    fun `the filter names sports short and folds catch-alls into All`() {
        assertEquals(SportsLiveSportKind.FOOTBALL, SportsLiveSportKind.of("American Football"))
        assertEquals(SportsLiveSportKind.FOOTBALL, SportsLiveSportKind.of("NFL"))
        assertEquals(SportsLiveSportKind.SOCCER, SportsLiveSportKind.of("Football"))
        assertEquals(SportsLiveSportKind.SOCCER, SportsLiveSportKind.of("Soccer"))
        assertEquals(SportsLiveSportKind.HOCKEY, SportsLiveSportKind.of("Ice Hockey"))
        // One option for one sport, whatever the addon calls it.
        val nfl = SportsLiveSportKind.filterKeyOf(item("A vs B", now, sport = "NFL"))
        assertEquals(nfl, SportsLiveSportKind.filterKeyOf(item("C vs D", now, sport = "American Football")))
        assertEquals(SportsLiveSportKind.FOOTBALL, SportsLiveSportKind.ofFilterKey(nfl))
        // Catch-alls: a generic genre, and a catalog's own name with no genres.
        assertNull(SportsLiveSportKind.filterKeyOf(item("A vs B", now, sport = "Sports")))
        assertNull(SportsLiveSportKind.filterKeyOf(item("A vs B", now, sport = "Live Events", genres = emptyList(), catalogName = "Live Events")))
        // Another real sport keeps its own text.
        assertEquals("Handball", SportsLiveSportKind.filterKeyOf(item("A vs B", now, sport = "Handball")))
    }

    @Test
    fun `filter options carry the short labels after My teams and All`() {
        val football = SportsLiveSportKind.filterKeyOf(SportsLiveSportKind.FOOTBALL)
        val options = sportsLiveFilterOptions(
            listOf(SportsLiveUiState.MY_TEAMS_FILTER, football),
            "★ My teams",
            "All"
        ) { if (it == football) "Football" else it }
        assertEquals(listOf("★ My teams", "All", "Football"), options.map { it.label })
        assertEquals(listOf(SportsLiveUiState.MY_TEAMS_FILTER, null, football), options.map { it.value })
    }

    // Single-line versus two-team rows

    @Test
    fun `a tournament title is a single-line row, a real match two teams`() {
        assertNull(SportsLiveTeams.parse("ATP 250 - Stockholm Open"))
        val tennis = item("ATP 250 - Stockholm Open", null, sport = null, genres = emptyList(), isLive = true)
        val tennisRow = scoreboardRows(listOf(tennis), SportsLiveTab.LIVE_NOW, emptyMap(), null, emptySet(), now).single()
        assertFalse(tennisRow.twoSided)
        assertEquals(SportsLiveSportKind.TENNIS, tennisRow.sportTag)

        val cricket = item("New Zealand v India", null, sport = "Cricket", isLive = true)
        val cricketRow = scoreboardRows(listOf(cricket), SportsLiveTab.LIVE_NOW, emptyMap(), null, emptySet(), now).single()
        assertTrue(cricketRow.twoSided)
        assertEquals(SportsLiveSportKind.CRICKET, cricketRow.sportTag)

        val bare = item("Tennis", null, sport = null, genres = emptyList(), isLive = true)
        val bareRow = scoreboardRows(listOf(bare), SportsLiveTab.LIVE_NOW, emptyMap(), null, emptySet(), now).single()
        assertFalse(bareRow.twoSided)
        assertEquals(SportsLiveSportKind.TENNIS, bareRow.sportTag)
    }

    @Test
    fun `a non-team pair written as a game reads as one title, and the description can name the sport`() {
        assertEquals("ATP 250 · Stockholm Open", singleLineTitle(item("ATP 250 vs Stockholm Open", null, sport = null)))
        assertEquals("Tennis", singleLineTitle(item("Tennis", null, sport = null)))
        val cricket = item("New Zealand vs India", null, sport = "Live Events", genres = emptyList(), catalogName = "Live Events")
            .let { it.copy(meta = it.meta.copy(description = "ICC World Test Championship")) }
        assertEquals(SportsLiveSportKind.CRICKET, SportsLiveSportKind.tagOf(cricket))
    }

    @Test
    fun `two parsed sides that are not team-like draw one line`() {
        val art = GameArt.of(item("Grand Prix Qualifying vs Race Day", now), null)
        assertFalse(scoreboardTwoSided(art, matched = false))
        // An ESPN match is always two-sided.
        assertTrue(scoreboardTwoSided(art, matched = true))
        assertFalse(scoreboardTwoSided(null, matched = true))
    }

    // Channel stage

    @Test
    fun `a channel's stage lists Now and Next only when known`() {
        assertEquals(emptyList<ChannelStageLine>(), channelStageLines(null, null))
        assertEquals(listOf(ChannelStageLine.Now("Celtics vs 76ers")), channelStageLines("Celtics vs 76ers", " "))
        assertEquals(
            listOf(ChannelStageLine.Now("Matchday Live"), ChannelStageLine.Next("Fight Night")),
            channelStageLines("Matchday Live", "Fight Night")
        )
        assertEquals(listOf(ChannelStageLine.Next("Fight Night")), channelStageLines(null, "Fight Night"))
    }

    // Mini player caption

    @Test
    fun `the mini player's caption sits top-left in the stage, below the video in the corner`() {
        assertEquals(SportsLiveMiniCaptionSpot.TOP_START, SportsLiveMiniGeometry.captionSpot(inStage = true))
        assertEquals(SportsLiveMiniCaptionSpot.BELOW, SportsLiveMiniGeometry.captionSpot(inStage = false))
        // A 20 px chip 12 px from the top of a 406 px box is clear of the scorebug band; one at the bottom is not.
        assertTrue(SportsLiveMiniGeometry.clearOfScorebug(top = 12f, bottom = 32f, height = 406f))
        assertFalse(SportsLiveMiniGeometry.clearOfScorebug(top = 370f, bottom = 394f, height = 406f))
    }

    // In-player panel

    @Test
    fun `the panel corner shows only a playing preview`() {
        assertTrue(panelPreviewCornerShown(previewPlaying = true))
        assertFalse(panelPreviewCornerShown(previewPlaying = false))
    }

    @Test
    fun `the panel's chips drop catch-alls that only repeat All`() {
        val items = listOf(
            item("A vs B", now, sport = "Basketball", isLive = true),
            item("C vs D", now, sport = "Sports", isLive = true),
            item("E vs F", now, sport = "Live Events", genres = emptyList(), catalogName = "Live Events", isLive = true)
        )
        val basketball = SportsLiveSportKind.filterKeyOf(SportsLiveSportKind.BASKETBALL)
        assertEquals(listOf(null, basketball), panelChips(items, selected = null))
        assertEquals(1, panelChipCounts(items)[basketball])
        assertEquals(1, filterPanelItems(items, basketball).size)
    }

    @Test
    fun `a tournament before or a stage after the game is not part of a side`() {
        assertEquals(TeamPair("Arsenal", "Chelsea"), SportsLiveTeams.parse("Premier League - Arsenal vs Chelsea"))
        assertEquals(TeamPair("Arsenal", "Chelsea"), SportsLiveTeams.parse("Arsenal vs Chelsea - Matchday 5"))
        assertEquals(TeamPair("New Zealand", "India"), SportsLiveTeams.parse("New Zealand v India"))
        assertEquals(TeamPair("Real Madrid", "Barcelona"), SportsLiveTeams.parse("Real Madrid - Barcelona"))
        assertNull(SportsLiveTeams.parse("Formula 1 - Las Vegas Grand Prix"))
        assertNull(SportsLiveTeams.parse("WTA 500 - Round 2"))
        assertTrue(SportsLiveTeams.isTeamLike("Saint-Etienne"))
        assertFalse(SportsLiveTeams.isTeamLike("Stockholm Open"))
        assertFalse(SportsLiveTeams.isTeamLike("ATP 250"))
    }
}

/** The filter value of a sport word ("Soccer" → its [SportsLiveSportKind] value), as the filters and chips hold it. */
internal fun sportKey(sport: String): String = SportsLiveSportKind.filterKeyOf(SportsLiveSportKind.of(sport)!!)
