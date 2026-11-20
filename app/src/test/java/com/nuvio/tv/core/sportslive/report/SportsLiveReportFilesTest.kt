package com.nuvio.tv.core.sportslive.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.ZoneId

/** Report a problem (#172): report file names, numbering, the fixed latest copy and keeping the last N. */
class SportsLiveReportFilesTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val utc = ZoneId.of("UTC")

    // 2025-11-20 19:42:05 UTC
    private val at = 1_763_667_725_000L

    private fun dir() = File(temp.root, SportsLiveReportFiles.FOLDER)

    @Test
    fun `a report is named by its number and local time`() {
        assertEquals("report-012-20251120-194205.txt", SportsLiveReportFiles.fileName(12, at, utc))
        assertEquals("report-1234-20251120-144205.txt", SportsLiveReportFiles.fileName(1234, at, ZoneId.of("America/New_York")))
    }

    @Test
    fun `the number is read back from a report name only`() {
        assertEquals(12, SportsLiveReportFiles.numberOf("report-012-20251120-194205.txt"))
        assertNull(SportsLiveReportFiles.numberOf(SportsLiveReportFiles.LATEST))
        assertNull(SportsLiveReportFiles.numberOf("report-012.txt"))
        assertNull(SportsLiveReportFiles.numberOf("notes.txt"))
    }

    @Test
    fun `numbers start at one and follow the newest`() {
        val files = SportsLiveReportFiles(dir())
        assertEquals(1, files.nextNumber())
        assertNull(files.latest())
        files.save(1, "first", at, utc)
        files.save(2, "second", at + 1_000, utc)
        assertEquals(3, files.nextNumber())
        assertEquals("report-002-20251120-194206.txt", files.latest()?.name)
    }

    @Test
    fun `the latest copy always holds the newest report`() {
        val files = SportsLiveReportFiles(dir())
        files.save(1, "first", at, utc)
        files.save(2, "second", at + 1_000, utc)
        assertEquals("second", File(dir(), SportsLiveReportFiles.LATEST).readText())
    }

    @Test
    fun `only the newest are kept and the latest copy is never counted`() {
        val files = SportsLiveReportFiles(dir(), keep = 3)
        for (n in 1..5) files.save(n, "report $n", at + n * 1_000L, utc)
        assertEquals(listOf(3, 4, 5), files.reports().map { SportsLiveReportFiles.numberOf(it.name) })
        assertTrue(File(dir(), SportsLiveReportFiles.LATEST).exists())
        assertEquals(6, files.nextNumber())
    }

    @Test
    fun `the default keeps the last twenty`() {
        val files = SportsLiveReportFiles(dir())
        for (n in 1..23) files.save(n, "report $n", at + n * 1_000L, utc)
        assertEquals(SportsLiveReportFiles.KEEP, files.reports().size)
        assertEquals(4, SportsLiveReportFiles.numberOf(files.reports().first().name))
    }

    @Test
    fun `numbers sort as numbers past 999`() {
        val files = SportsLiveReportFiles(dir(), keep = 2)
        files.save(999, "a", at, utc)
        files.save(1000, "b", at + 1_000, utc)
        files.save(1001, "c", at + 2_000, utc)
        assertEquals(listOf(1000, 1001), files.reports().map { SportsLiveReportFiles.numberOf(it.name) })
    }

    @Test
    fun `other files in the folder are left alone`() {
        dir().mkdirs()
        val other = File(dir(), "keep-me.txt").apply { writeText("x") }
        val files = SportsLiveReportFiles(dir(), keep = 1)
        files.save(1, "a", at, utc)
        files.save(2, "b", at + 1_000, utc)
        assertTrue(other.exists())
        assertEquals(1, files.reports().size)
    }

    @Test
    fun `rewriting the newest report updates the latest copy and an older one does not`() {
        val files = SportsLiveReportFiles(dir())
        val first = files.save(1, "first", at, utc)
        val second = files.save(2, "second", at + 1_000, utc)
        files.rewrite(second, "second with a note")
        assertEquals("second with a note", File(dir(), SportsLiveReportFiles.LATEST).readText())
        files.rewrite(first, "first with a note")
        assertEquals("first with a note", first.readText())
        assertEquals("second with a note", File(dir(), SportsLiveReportFiles.LATEST).readText())
        assertFalse(first.name == second.name)
    }
}
