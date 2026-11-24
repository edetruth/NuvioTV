package com.nuvio.tv.core.sportslive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #282: the fork's debug behaviour (diagnostic logs, test hooks) follows BuildConfig.IS_DEBUG_BUILD. BuildConfig.DEBUG
 * is false on the non-debuggable debug and test-app builds, which silently turned those off. Upstream files keep theirs.
 */
class SportsLiveDebugFlagGuardTest {

    /** Fork files outside the Sports and Live names that gate debug behaviour. */
    private val forkDebugFiles = setOf("PlayerVideoDecoderRecovery.kt", "TestMute.kt", "LogRedact.kt")

    @Test
    fun `Sports and Live code gates debug behaviour on IS_DEBUG_BUILD, never BuildConfig DEBUG`() {
        val root = File("src/main/java")
        assertTrue("run from the app module", root.isDirectory)
        val debugFlag = Regex("""BuildConfig\.DEBUG\b""")
        val files = root.walkTopDown().filter { file ->
            file.isFile && file.extension == "kt" &&
                ("sportslive" in file.path.lowercase() || "Sports" in file.name || file.name in forkDebugFiles)
        }.toList()
        assertTrue("found ${files.size} files", files.size > 50)
        val uses = files.flatMap { file ->
            file.readLines().mapIndexedNotNull { i, line ->
                "${file.name}:${i + 1}".takeIf { debugFlag.containsMatchIn(line.substringBefore("//")) }
            }
        }
        assertEquals(uses.joinToString("\n"), 0, uses.size)
    }
}
