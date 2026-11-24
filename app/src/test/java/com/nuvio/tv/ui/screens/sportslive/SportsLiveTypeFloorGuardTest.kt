package com.nuvio.tv.ui.screens.sportslive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #95: nothing a viewer reads in Sports and Live is under 12 sp (the pills and hints are 13 sp). The theme's
 * labelSmall is 10 sp, so it is banned here too. The two files with no callers (SportsLiveListCard / DetailsPane
 * and their art) are exempt until the dead code is deleted.
 */
class SportsLiveTypeFloorGuardTest {

    private val dead = setOf("SportsLiveCards.kt", "SportsLiveGameArt.kt")

    @Test
    fun `no sports live text is smaller than 12 sp`() {
        val root = File("src/main/java/com/nuvio/tv/ui/screens/sportslive")
        assertTrue("run from the app module", root.isDirectory)
        // The section's files in the player and settings too (review R12).
        val elsewhere = listOf("player", "settings").flatMap { dir ->
            File("src/main/java/com/nuvio/tv/ui/screens/$dir").listFiles { file -> file.name.startsWith("SportsLive") && file.extension == "kt" }.orEmpty().toList()
        }
        assertTrue(elsewhere.size >= 8)
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name !in dead }.toList() + elsewhere
        assertTrue(files.size > 50)
        val problems = files.flatMap { file ->
            file.readLines().mapIndexedNotNull { i, line -> violation(line)?.let { "${file.name}:${i + 1} $it" } }
        }
        assertEquals(problems.joinToString("\n"), 0, problems.size)
    }

    @Test
    fun `the scanner flags small literals and labelSmall`() {
        assertTrue(violation("style = TextStyle(fontSize = 11.sp)") != null)
        assertTrue(violation("copy(fontSize = 9.5.sp)") != null)
        assertTrue(violation("MaterialTheme.typography.labelSmall") != null)
        assertTrue(violation("copy(fontSize = 12.sp)") == null)
        assertTrue(violation("copy(fontSize = 13.sp)") == null)
        assertTrue(violation("copy(lineHeight = 10.sp)") == null)
    }

    private fun violation(line: String): String? {
        if (line.trimStart().startsWith("//") || line.trimStart().startsWith("*")) return null
        if ("typography.labelSmall" in line) return "labelSmall is 10 sp"
        val small = Regex("""fontSize\s*=\s*(\d+(?:\.\d+)?)\.sp""").findAll(line).firstOrNull { it.groupValues[1].toDouble() < 12.0 }
        return small?.let { "fontSize ${it.groupValues[1]} sp" }
    }
}
