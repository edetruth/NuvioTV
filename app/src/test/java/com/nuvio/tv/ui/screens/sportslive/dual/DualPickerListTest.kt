package com.nuvio.tv.ui.screens.sportslive.dual

import com.nuvio.tv.core.sportslive.SportsLiveChannelPicks
import com.nuvio.tv.core.sportslive.SportsLiveChannelRef
import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.core.sportslive.scores.EspnFixtures
import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
import com.nuvio.tv.core.sportslive.scores.ScoreChip
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoreState
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.ui.screens.sportslive.ScoreboardGroup
import com.nuvio.tv.ui.screens.sportslive.ScoreboardRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * live.26 item 3 (#222): "Pick another to watch" in sections Your teams, Same league, More live and Channels (the
 * profile's favourite and recent channels only), headed from six rows; offered only with another live game; every row
 * under the one spoiler gate.
 */
class DualPickerListTest {

    private val now = 1_800_000_000_000L

    private fun item(
        id: String,
        name: String = id,
        sport: String? = null,
        followed: Boolean = false,
        channel: Boolean = false,
        alternates: List<SportsLiveItem> = emptyList()
    ) = SportsLiveItem(
        meta = MetaPreview(
            id = id, type = ContentType.UNKNOWN, rawType = "sport", name = name, poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = emptyList()
        ),
        addonId = "a", addonName = "a", addonBaseUrl = "https://example.invalid", catalogKey = "k", catalogName = "Live",
        kind = if (channel) SportsLiveKind.CHANNELS else SportsLiveKind.EVENTS, isLive = true, isChannel = channel,
        startMs = null, sport = sport, teams = if (channel) null else SportsLiveTeams.parse(name),
        followed = followed, alternates = alternates
    )

    private fun ref(channel: SportsLiveItem) = SportsLiveChannelRef(channel.addonId, channel.id, channel.meta.name)

    private val anchor = item("anchor", sport = "NBA")

    private fun list(
        candidates: List<SportsLiveItem>,
        from: SportsLiveItem? = anchor,
        scores: Map<String, ScoreSnapshot> = emptyMap(),
        picks: SportsLiveChannelPicks = SportsLiveChannelPicks.NONE
    ) = dualPickerList(candidates, from, scores, picks)

    @Test
    fun `sections come as Your teams, Same league, More live then Channels, each in the shared order`() {
        val otherLeague = item("nhl", sport = "NHL")
        val sameFirst = item("nba1", sport = "NBA")
        val followed = item("nfl", sport = "NFL", followed = true)
        val sameSecond = item("nba2", sport = "nba")
        val unknown = item("unknown")
        val favourite = item("ch1", channel = true)
        val recent = item("ch2", channel = true)
        val neither = item("ch3", channel = true)
        val picked = list(
            listOf(otherLeague, sameFirst, favourite, followed, neither, sameSecond, recent, unknown),
            picks = SportsLiveChannelPicks(favorites = listOf(ref(favourite)), recent = listOf(ref(recent)))
        )
        assertEquals(
            listOf("nfl", "nba1", "nba2", "nhl", "unknown", "ch1", "ch2"),
            picked.rows.map { it.item.id }
        )
        assertEquals(
            listOf(
                DualPickerSection.YOUR_TEAMS, DualPickerSection.SAME_LEAGUE, DualPickerSection.SAME_LEAGUE,
                DualPickerSection.MORE_LIVE, DualPickerSection.MORE_LIVE, DualPickerSection.CHANNELS, DualPickerSection.CHANNELS
            ),
            picked.rows.map { it.section }
        )
    }

    @Test
    fun `with no favourite or recent channel no channel is listed at all`() {
        val picked = list(listOf(item("g1"), item("ch1", channel = true), item("ch2", channel = true)))
        assertEquals(listOf("g1"), picked.rows.map { it.item.id })
    }

    @Test
    fun `headers show only from six rows and with two sections or more, never as rows of their own`() {
        val five = list(listOf(item("f", followed = true)) + (1..4).map { item("g$it") })
        assertFalse(five.headed)
        assertNull(five.headerAt(0))

        val sixOneSection = list((1..6).map { item("g$it") })
        assertFalse(sixOneSection.headed)

        val six = list(listOf(item("f", followed = true)) + (1..5).map { item("g$it") })
        assertTrue(six.headed)
        assertEquals(6, six.rows.size)
        assertEquals(DualPickerSection.YOUR_TEAMS, six.headerAt(0))
        assertEquals(DualPickerSection.MORE_LIVE, six.headerAt(1))
        assertNull(six.headerAt(2))
        assertEquals(5, six.countOf(DualPickerSection.MORE_LIVE))
    }

    @Test
    fun `a followed team's game is the first row with twenty live games, so it takes no press`() {
        val games = (1..24).map { index -> item("g$index", sport = if (index % 3 == 0) "NBA" else "NHL", followed = index == 18) }
        val picked = list(games)
        val presses = picked.rows.indexOfFirst { it.item.followed }
        assertEquals(0, presses)
        assertTrue(presses <= 2)
        // Same league comes right after it.
        assertEquals(DualPickerSection.SAME_LEAGUE, picked.rows[1].section)
    }

    @Test
    fun `a bare sport word, a catch-all or the catalog's name is no league, so no guessing`() {
        assertNull(dualPickerLeagueOf(item("b", sport = "Basketball"), null))
        assertNull(dualPickerLeagueOf(item("s", sport = "Sports"), null))
        assertNull(dualPickerLeagueOf(item("c", sport = "Live"), null))
        assertNull(dualPickerLeagueOf(item("n"), null))
        assertEquals("nba", dualPickerLeagueOf(item("l", sport = "NBA"), null))

        val bare = item("anchor2", sport = "Basketball")
        val picked = list(listOf(item("x", sport = "Basketball")), from = bare)
        assertEquals(DualPickerSection.MORE_LIVE, picked.rows.single().section)
    }

    @Test
    fun `from a channel there is no Same league`() {
        val picked = list(listOf(item("x", sport = "NBA")), from = item("ch", sport = "NBA", channel = true))
        assertEquals(DualPickerSection.MORE_LIVE, picked.rows.single().section)
    }

    @Test
    fun `two games matched to the same ESPN league are Same league whatever their listing says`() {
        val from = item("from", name = "76ers vs Pacers", sport = "Basketball")
        val other = item("other", name = "76ers vs Pacers", sport = "Basketball")
        val picked = list(listOf(other), from = from, scores = mapOf(from.streamKey to pacers, other.streamKey to pacers))
        assertEquals(DualPickerSection.SAME_LEAGUE, picked.rows.single().section)
    }

    @Test
    fun `2 sources shows only for a game with two, never a source's name`() {
        val single = item("one")
        val merged = item("two", alternates = listOf(item("two-b")))
        val rows = list(listOf(single, merged)).rows.associateBy { it.item.id }
        assertFalse(rows.getValue("one").showsSources)
        assertTrue(rows.getValue("two").showsSources)
        assertEquals(2, rows.getValue("two").sources)
    }

    @Test
    fun `the picker is offered only with another live game, never for channels alone`() {
        assertFalse(dualPickerOffers(emptyList()))
        assertFalse(dualPickerOffers(listOf(item("ch1", channel = true))))
        assertTrue(dualPickerOffers(listOf(item("ch1", channel = true), item("g1"))))
    }

    // The one spoiler gate (#117) in every mode, as Live now, Home's row and dual screen draw the picker.

    private val nba = EspnFixtures.events(EspnFixtures.NBA_20261115, EspnLeagueMap.NBA)
    private val pacers = nba.first { it.eventId == "401909985" }
    private val live: ScoreSnapshot = pacers.copy(
        state = ScoreState.IN, isFinal = false, clockText = "4:12 - 4th",
        home = pacers.home.copy(score = 88), away = pacers.away.copy(score = 91)
    )
    private val game = item("game", name = "76ers vs Pacers", sport = "NBA")
    private val channel = item("channel", channel = true)

    private fun boards(mode: ScoresMode?, revealed: Set<String> = emptySet(), snapshot: ScoreSnapshot = live) =
        dualPickerBoards(listOf(game, channel), mapOf(game.streamKey to snapshot), mode, revealed, now)

    @Test
    fun `every candidate gets a row drawn as Live now draws it`() {
        assertEquals(setOf(game.streamKey, channel.streamKey), boards(ScoresMode.SHOW).keys)
        assertNull(boards(ScoresMode.SHOW).getValue(channel.streamKey).chip)
    }

    @Test
    fun `under Show the live score shows`() {
        assertTrue(boards(ScoresMode.SHOW).getValue(game.streamKey).chip is ScoreChip.Live)
    }

    @Test
    fun `under Hide until focused the score stays hidden until the row was focused`() {
        val hidden = boards(ScoresMode.HIDE_UNTIL_FOCUSED).getValue(game.streamKey)
        assertEquals(ScoreChip.Hidden, hidden.chip)
        assertNull(hidden.tennis)
        assertTrue(boards(ScoresMode.HIDE_UNTIL_FOCUSED, revealed = setOf(game.streamKey)).getValue(game.streamKey).chip is ScoreChip.Live)
    }

    @Test
    fun `under Off or before the setting is read no score and no close-game badge show`() {
        listOf(ScoresMode.OFF, null).forEach { mode ->
            val board = boards(mode).getValue(game.streamKey)
            assertNull(board.chip)
            assertFalse(board.closeGame)
        }
    }

    @Test
    fun `a protected game stays hidden under Show and once focused`() {
        val board = boards(ScoresMode.SHOW, revealed = setOf(game.streamKey), snapshot = live.copy(spoilerProtected = true))
            .getValue(game.streamKey)
        assertEquals(ScoreChip.Hidden, board.chip)
        assertFalse(board.closeGame)
    }

    @Test
    fun `a game that ended while the picker was open starts nothing`() {
        fun board(group: ScoreboardGroup) = ScoreboardRow(
            item = game, group = group, chip = null, art = null,
            firstFollowed = false, secondFollowed = false, probablyEnded = false
        )
        assertTrue(dualPickerRowEnded(board(ScoreboardGroup.JUST_ENDED)))
        assertFalse(dualPickerRowEnded(board(ScoreboardGroup.LIVE)))
        assertFalse(dualPickerRowEnded(board(ScoreboardGroup.CHANNELS)))
    }
}
