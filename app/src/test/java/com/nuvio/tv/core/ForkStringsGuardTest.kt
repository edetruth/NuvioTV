package com.nuvio.tv.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Fork guard (#121): Nuvio Live strings live in values/strings_sports_live.xml, never in upstream's
 * strings.xml, so upstream merges of strings.xml do not conflict with the fork.
 */
class ForkStringsGuardTest {

    private val valuesDir = File("src/main/res/values")
    private val forkFile = File(valuesDir, "strings_sports_live.xml")
    private val keyRegex = Regex("""<(string|plurals|string-array)\s+name="([^"]+)""")
    private val valueRegex = Regex("""<(?:string|item)\b[^>]*>(.*?)</(?:string|item)>""")

    private fun keysOf(file: File): List<String> =
        keyRegex.findAll(file.readText()).map { it.groupValues[2] }.toList()

    @Test
    fun `every string key in values is defined exactly once across all files`() {
        assertTrue("run from the app module", valuesDir.isDirectory)
        val counts = valuesDir.listFiles().orEmpty()
            .filter { it.isFile && it.extension == "xml" }
            .flatMap { keysOf(it) }
            .groupingBy { it }
            .eachCount()
        val duplicated = counts.filterValues { it > 1 }.keys
        assertTrue("Defined more than once: $duplicated", duplicated.isEmpty())
    }

    @Test
    fun `fork strings stay out of upstream strings file`() {
        val forkKeys = keysOf(forkFile)
        assertTrue("fork strings found", forkKeys.size > 700)
        val upstreamKeys = keysOf(File(valuesDir, "strings.xml")).toSet()
        val leaked = upstreamKeys.filter { it.startsWith("sports_live_") || it in forkKeys }
        assertTrue("Move these to strings_sports_live.xml: $leaked", leaked.isEmpty())
    }

    @Test
    fun `fork string values are escaped`() {
        val problems = valueRegex.findAll(forkFile.readText())
            .map { it.groupValues[1] }
            .filter { value ->
                value.isEmpty() ||
                    Regex("""(?<!\\)'""").containsMatchIn(value) ||
                    value.startsWith("@") || value.startsWith("?")
            }
            .toList()
        assertTrue("Escape apostrophes as \\' and a leading @ or ? with a backslash: $problems", problems.isEmpty())
    }
}
