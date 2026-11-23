package com.nuvio.tv.core.sportslive.debug

import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShownGamesLogTest {
    private val zone = ZoneId.of("UTC")
    private val folder: File = Files.createTempDirectory("shown-games").toFile()
    private var nowMs = LocalDate.of(2026, 11, 22).atTime(9, 5, 7).atZone(zone).toInstant().toEpochMilli()

    @After
    fun cleanUp() {
        folder.deleteRecursively()
    }

    private fun writer(enabled: Boolean = true, dir: File? = folder) =
        ShownGamesWriter(enabled, dir = { dir }, now = { nowMs }, zone = { zone })

    private fun game(
        key: String = "k1",
        title: String = "Lakers vs Celtics",
        league: String = "NBA",
        status: String = "in",
        matched: Boolean? = true
    ) = ShownGame(key, title, "Basketball", league, homeLogo = true, awayLogo = false, scoreMatched = matched, status = status)

    private fun lines(day: String = "2026-11-22"): List<String> =
        File(folder, "$day.tsv").takeIf { it.exists() }?.readLines().orEmpty()

    @Test
    fun `a game is one tab separated line in the agreed column order`() {
        writer().record("live_now", listOf(game()))

        assertEquals(listOf("09:05:07\tlive_now\tLakers vs Celtics\tBasketball\tNBA\ty\tn\ty\tin"), lines())
    }

    @Test
    fun `a game with no league tag leaves the column blank and an unknown match reads question mark`() {
        writer().record("home_row", listOf(game(league = "", matched = null, status = "none")))

        assertEquals(listOf("09:05:07\thome_row\tLakers vs Celtics\tBasketball\t\ty\tn\t?\tnone"), lines())
    }

    @Test
    fun `no link host or id can reach the file`() {
        val title = "Lakers https://example.invalid/watch?id=1 vs www.example.invalid Celtics tt1234567 0123456789abcdef0123 feed:abc"
        writer().record("panel", listOf(game(title = title)))

        val text = File(folder, "2026-11-22.tsv").readText()
        assertEquals(listOf("09:05:07\tpanel\tLakers vs Celtics\tBasketball\tNBA\ty\tn\ty\tin"), lines())
        assertFalse(text.contains("example"))
        assertFalse(text.contains("http"))
        assertFalse(text.contains("1234567"))
    }

    @Test
    fun `a title that is nothing but a link is not written at all`() {
        writer().record("panel", listOf(game(title = "https://example.invalid/a/b")))

        assertTrue(lines().isEmpty())
    }

    @Test
    fun `tabs and line breaks in a title never split a line`() {
        writer().record("panel", listOf(game(title = "Lakers\tvs\r\nCeltics")))

        assertEquals(1, lines().size)
        assertEquals("Lakers vs Celtics", lines().single().split("\t")[2])
    }

    @Test
    fun `a game is written once per surface per day and again for another surface`() {
        val w = writer()
        w.record("live_now", listOf(game()))
        w.record("live_now", listOf(game()))
        w.record("panel", listOf(game()))

        assertEquals(listOf("live_now", "panel"), lines().map { it.split("\t")[1] })
    }

    @Test
    fun `a game is written again when what its row shows changes`() {
        val w = writer()
        w.record("live_now", listOf(game(status = "in")))
        w.record("live_now", listOf(game(status = "final")))

        assertEquals(listOf("in", "final"), lines().map { it.split("\t").last() })
    }

    @Test
    fun `the next day starts again and writes to its own file`() {
        val w = writer()
        w.record("live_now", listOf(game()))
        nowMs += 24 * 60 * 60 * 1000L
        w.record("live_now", listOf(game()))

        assertEquals(1, lines("2026-11-22").size)
        assertEquals(1, lines("2026-11-23").size)
    }

    @Test
    fun `files older than seven days are deleted and the last seven kept`() {
        for (offset in 0L..9L) File(folder, "${LocalDate.of(2026, 11, 22).minusDays(offset)}.tsv").writeText("x\n")
        File(folder, "notes.txt").writeText("keep")

        writer().record("live_now", listOf(game()))

        val left = folder.list().orEmpty().sorted()
        assertEquals(
            listOf("2026-11-16.tsv", "2026-11-17.tsv", "2026-11-18.tsv", "2026-11-19.tsv", "2026-11-20.tsv", "2026-11-21.tsv", "2026-11-22.tsv", "notes.txt"),
            left
        )
    }

    @Test
    fun `a release build writes nothing and does not make the folder`() {
        val missing = File(folder, "shown")

        writer(enabled = false, dir = missing).record("live_now", listOf(game()))

        assertFalse(missing.exists())
        assertTrue(folder.list().orEmpty().isEmpty())
    }

    @Test
    fun `no folder known writes nothing and does not fail`() {
        writer(dir = null).record("live_now", listOf(game()))

        assertTrue(folder.list().orEmpty().isEmpty())
    }
}
