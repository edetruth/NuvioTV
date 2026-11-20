package com.nuvio.tv.ui.screens.sportslive

import androidx.compose.ui.unit.dp
import com.nuvio.tv.core.sportslive.SportsLiveToastKind
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens.Colors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The one toast card (#134): its icon colour carries the meaning, and no system Toast is left in the section. */
class SportsLiveToastCardTest {

    @Test
    fun `the icon colour says what the toast is about`() {
        assertEquals(Colors.WhyTagBlue, toastIconColor(SportsLiveToastKind.REMINDER))
        assertEquals(Colors.FollowedGold, toastIconColor(SportsLiveToastKind.FOLLOWED))
        assertEquals(Colors.WarningAmber, toastIconColor(SportsLiveToastKind.PROBLEM))
        // Four meanings, four colours.
        val meanings = listOf(SportsLiveToastKind.REMINDER, SportsLiveToastKind.FOLLOWED, SportsLiveToastKind.PROBLEM, SportsLiveToastKind.FINAL)
        assertEquals(meanings.size, meanings.map(::toastIconColor).toSet().size)
    }

    @Test
    fun `with the games panel open the corner moves just right of it and keeps the bottom edge`() {
        val video = sportsLiveVideoCorner(controlsUp = false)
        val beside = sportsLiveCornerBesidePanel(video)
        assertEquals(video.bottom, beside.bottom)
        assertEquals(SportsLiveTokens.Size.PanelWidth + SportsLiveTokens.Size.StageGap, beside.start)
        assertTrue(beside.start > SportsLiveTokens.Size.PanelWidth)
        // No panel width to clear: the corner stays where it is.
        val far = video.copy(start = 400.dp)
        assertEquals(far, sportsLiveCornerBesidePanel(far))
    }

    @Test
    fun `the panel's corner wins while it is open and the player's comes back when it closes`() {
        val player = Any()
        val panel = Any()
        val video = sportsLiveVideoCorner(controlsUp = false)
        val beside = sportsLiveCornerBesidePanel(video)
        try {
            SportsLiveToastPlace.set(player, video)
            SportsLiveToastPlace.set(panel, beside)
            // The player's controls come up under the open panel: its corner changes, the panel's still wins.
            SportsLiveToastPlace.set(player, sportsLiveVideoCorner(controlsUp = true))
            assertEquals(beside, SportsLiveToastPlace.corner.value)
            SportsLiveToastPlace.remove(panel)
            assertEquals(sportsLiveVideoCorner(controlsUp = true), SportsLiveToastPlace.corner.value)
        } finally {
            SportsLiveToastPlace.remove(panel)
            SportsLiveToastPlace.remove(player)
        }
    }

    @Test
    fun `no Android system Toast is left in the Sports and Live code`() {
        val roots = listOf("src/main/java/com/nuvio/tv", "app/src/main/java/com/nuvio/tv").map(::File).first { it.exists() }
        val dirs = listOf("ui/screens/sportslive", "core/sportslive").map { File(roots, it) }
        val offenders = dirs.flatMap { dir -> dir.walkTopDown().filter { it.extension == "kt" && it.readText().contains("Toast.makeText") }.toList() }
        assertTrue("System Toasts in: ${offenders.map { it.name }}", offenders.isEmpty())
    }
}
