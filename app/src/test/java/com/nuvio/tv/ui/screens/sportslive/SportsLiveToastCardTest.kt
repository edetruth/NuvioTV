package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.SportsLiveToastKind
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
    fun `no Android system Toast is left in the Sports and Live code`() {
        val roots = listOf("src/main/java/com/nuvio/tv", "app/src/main/java/com/nuvio/tv").map(::File).first { it.exists() }
        val dirs = listOf("ui/screens/sportslive", "core/sportslive").map { File(roots, it) }
        val offenders = dirs.flatMap { dir -> dir.walkTopDown().filter { it.extension == "kt" && it.readText().contains("Toast.makeText") }.toList() }
        assertTrue("System Toasts in: ${offenders.map { it.name }}", offenders.isEmpty())
    }
}
