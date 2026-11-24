package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.ui.screens.sportslive.SportsLiveMenuSlot.CHOOSE_STREAM
import com.nuvio.tv.ui.screens.sportslive.SportsLiveMenuSlot.DETAILS
import com.nuvio.tv.ui.screens.sportslive.SportsLiveMenuSlot.FAVORITE
import com.nuvio.tv.ui.screens.sportslive.SportsLiveMenuSlot.FOLLOW
import com.nuvio.tv.ui.screens.sportslive.SportsLiveMenuSlot.GAME_STATS
import com.nuvio.tv.ui.screens.sportslive.SportsLiveMenuSlot.HIDE
import com.nuvio.tv.ui.screens.sportslive.SportsLiveMenuSlot.POSTGAME
import com.nuvio.tv.ui.screens.sportslive.SportsLiveMenuSlot.REMIND
import com.nuvio.tv.ui.screens.sportslive.SportsLiveMenuSlot.REPORT
import com.nuvio.tv.ui.screens.sportslive.SportsLiveMenuSlot.SCORES_AHEAD
import com.nuvio.tv.ui.screens.sportslive.SportsLiveMenuSlot.TEAMS
import com.nuvio.tv.ui.screens.sportslive.SportsLiveMenuSlot.TEAM_PAGE
import com.nuvio.tv.ui.screens.sportslive.SportsLiveMenuSlot.WATCH_LATER
import com.nuvio.tv.ui.screens.sportslive.SportsLiveMenuSlot.WATCH_WITH
import com.nuvio.tv.ui.screens.sportslive.SportsLiveMenuSlot.WATCH_WITH_TEAM
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hold OK menu trim (docs/design/hold-ok-menu-trim.md): at most 7 entries, Teams… for the teams, Report always on screen. */
class SportsLiveMenuTrimTest {

    // A live, ESPN-matched two-team game on Live Now: 11 entries before the trim.
    private val liveMatched = SportsLiveMenuOffer(
        report = true, chooseStream = true, watchWith = true, gameStats = true,
        follows = 2, teamReminders = 2, teamPages = 2, watchLater = true, details = true
    )

    @Test
    fun `a live matched game lists 7, things for the game first and the teams behind Teams`() {
        assertEquals(
            listOf(CHOOSE_STREAM, WATCH_WITH, GAME_STATS, WATCH_LATER, TEAMS, DETAILS, REPORT),
            sportsLiveMenuSlots(liveMatched)
        )
    }

    @Test
    fun `an upcoming game starts with Remind me`() {
        val upcoming = SportsLiveMenuOffer(report = true, remind = true, gameStats = true, follows = 2, teamPages = 2, watchLater = true, details = true)
        assertEquals(listOf(REMIND, GAME_STATS, WATCH_LATER, TEAMS, DETAILS, REPORT), sportsLiveMenuSlots(upcoming))
    }

    @Test
    fun `a channel starts with Add to favorites`() {
        val channel = SportsLiveMenuOffer(report = true, favorite = true, chooseStream = true, watchWith = true, details = true)
        assertEquals(listOf(FAVORITE, CHOOSE_STREAM, WATCH_WITH, DETAILS, REPORT), sportsLiveMenuSlots(channel))
    }

    @Test
    fun `an unmatched item keeps Hide, first when no source gives a sport`() {
        val unmatched = SportsLiveMenuOffer(report = true, chooseStream = true, watchWith = true, details = true, hide = true)
        assertEquals(listOf(CHOOSE_STREAM, WATCH_WITH, DETAILS, HIDE, REPORT), sportsLiveMenuSlots(unmatched))
        assertEquals(listOf(HIDE, CHOOSE_STREAM, WATCH_WITH, DETAILS, REPORT), sportsLiveMenuSlots(unmatched.copy(hideFirst = true)))
    }

    @Test
    fun `the player's row menu puts Watch both first, Report last, and stays at 7`() {
        // live.25 (user): Report a problem is last in every Hold OK menu, the in-player table's and the player's too.
        val panel = SportsLiveMenuOffer(
            report = true, postgame = true, watchWith = true, watchWithFirst = true, chooseStream = true,
            gameStats = true, follows = 2, scoresAhead = true
        )
        assertEquals(listOf(POSTGAME, WATCH_WITH, CHOOSE_STREAM, GAME_STATS, TEAMS, SCORES_AHEAD, REPORT), sportsLiveMenuSlots(panel))
        // A channel row: Add to favorites first, Report last.
        assertEquals(listOf(FAVORITE, CHOOSE_STREAM, REPORT), sportsLiveMenuSlots(SportsLiveMenuOffer(report = true, favorite = true, chooseStream = true)))
        // The player menu (#218): Report last too.
        val player = SportsLiveMenuOffer(report = true, chooseStream = true, scoresAhead = true)
        assertEquals(listOf(CHOOSE_STREAM, SCORES_AHEAD, REPORT), sportsLiveMenuSlots(player))
    }

    @Test
    fun `a lone team entry is listed as itself, never behind Teams`() {
        assertEquals(listOf(FOLLOW, REPORT), sportsLiveMenuSlots(SportsLiveMenuOffer(report = true, follows = 1)))
        assertEquals(listOf(TEAM_PAGE, REPORT), sportsLiveMenuSlots(SportsLiveMenuOffer(report = true, teamPages = 1)))
        assertEquals(listOf(TEAMS, REPORT), sportsLiveMenuSlots(SportsLiveMenuOffer(report = true, follows = 1, teamPages = 1)))
    }

    @Test
    fun `every section and player combination stays at 7 or fewer with Report on screen`() {
        val bools = listOf(false, true)
        for (watchWith in bools) for (choose in bools) for (stats in bools) for (later in bools) for (remind in bools)
            for (hide in bools) for (sides in 0..2) for (team in bools) {
                // The section: a game offers Remind me (upcoming) or Choose stream / Watch with (live), never both;
                // Hide only for an item with no ESPN game and no team; Watch with ‹team› only beside Watch with.
                if (remind && (choose || watchWith)) continue
                if (hide && (stats || sides > 0 || later)) continue
                if (team && !watchWith) continue
                val offer = SportsLiveMenuOffer(
                    report = true, chooseStream = choose, watchWith = watchWith, gameStats = stats, remind = remind,
                    follows = sides, teamReminders = sides, teamPages = sides, watchLater = later, details = true, hide = hide,
                    watchWithTeam = team
                )
                val slots = sportsLiveMenuSlots(offer)
                assertTrue("$offer gives ${slots.size}", slots.size <= 7)
                assertEquals(REPORT, slots.last())
                if (team) assertTrue("$offer drops Watch with ‹team›", WATCH_WITH_TEAM in slots)
            }
    }

    @Test
    fun `Watch with your team's game sits just before Watch with another game`() {
        // live.26 item 4: the one-press pair beside the picker; a game with fewer entries keeps Details.
        val unmatched = SportsLiveMenuOffer(report = true, chooseStream = true, watchWith = true, watchWithTeam = true, details = true)
        assertEquals(listOf(CHOOSE_STREAM, WATCH_WITH_TEAM, WATCH_WITH, DETAILS, REPORT), sportsLiveMenuSlots(unmatched))
    }

    @Test
    fun `on a full live matched game Details makes room for Watch with your team's game`() {
        // 8 would scroll the sheet (hold-ok-menu-trim.md: at most 7): Details, the least used, leaves the top level.
        assertEquals(
            listOf(CHOOSE_STREAM, WATCH_WITH_TEAM, WATCH_WITH, GAME_STATS, WATCH_LATER, TEAMS, REPORT),
            sportsLiveMenuSlots(liveMatched.copy(watchWithTeam = true))
        )
    }

    @Test
    fun `Watch with your team's game needs Watch with another game`() {
        val noDual = SportsLiveMenuOffer(report = true, chooseStream = true, watchWithTeam = true, details = true)
        assertEquals(listOf(CHOOSE_STREAM, DETAILS, REPORT), sportsLiveMenuSlots(noDual))
    }
}
