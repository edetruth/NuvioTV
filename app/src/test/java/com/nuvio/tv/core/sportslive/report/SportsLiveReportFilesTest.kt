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
    @Test
    fun `Report a problem shows the newest report only while it is fresh`() {
        val name = "report-003-20261120-194205.txt"
        val text = "Nuvio Live report #3\nFrom: Settings\n${SportsLiveReport.DETAILS}\n"
        val saved = 1_000_000L
        assertTrue(SportsLiveReportFiles.showsAsIs(name, text, saved, saved))
        assertTrue(SportsLiveReportFiles.showsAsIs(name, text, saved, saved + SportsLiveReportFiles.FRESH_MS))
        assertFalse(SportsLiveReportFiles.showsAsIs(name, text, saved, saved + SportsLiveReportFiles.FRESH_MS + 1))
        // A clock set back since: not fresh either.
        assertFalse(SportsLiveReportFiles.showsAsIs(name, text, saved, saved - 60_000L))
    }

    @Test
    fun `a crash report is shown as it is however old`() {
        val name = "report-004-20261120-194205.txt"
        val text = "Nuvio Live report #4\nCrash: java.lang.IllegalStateException\nFrom: crash\n${SportsLiveReport.DETAILS}\n"
        assertTrue(SportsLiveReportFiles.showsAsIs(name, text, 0L, 3 * 24 * 60 * 60_000L))
    }

    @Test
    fun `a screenshot is named after its report`() {
        assertEquals("report-012-20251120-194205.jpg", SportsLiveReportFiles.shotName("report-012-20251120-194205.txt"))
        assertNull(SportsLiveReportFiles.shotName(SportsLiveReportFiles.LATEST))
        assertNull(SportsLiveReportFiles.shotName("notes.txt"))
        assertNull(SportsLiveReportFiles.shotName("report-012-20251120-194205.jpg"))
    }

    @Test
    fun `a screenshot is saved next to its report and never counts as one`() {
        val files = SportsLiveReportFiles(dir())
        val report = files.save(1, "first", at, utc)
        val shot = files.saveShot(report, byteArrayOf(1, 2, 3))
        assertEquals(File(dir(), "report-001-20251120-194205.jpg"), shot)
        assertEquals(shot, files.shotOf(report))
        assertEquals(listOf(report), files.reports())
        assertEquals(2, files.nextNumber())
    }

    @Test
    fun `no screenshot is saved for a file that is not a saved report`() {
        val files = SportsLiveReportFiles(dir())
        files.save(1, "first", at, utc)
        assertNull(files.saveShot(File(dir(), SportsLiveReportFiles.LATEST), byteArrayOf(1)))
        assertNull(files.saveShot(File(dir(), "report-009-20251120-194205.txt"), byteArrayOf(1)))
        assertEquals(listOf("report-001-20251120-194205.txt", SportsLiveReportFiles.LATEST), dir().list()!!.sorted())
    }

    @Test
    fun `a dropped report takes its screenshot with it`() {
        val files = SportsLiveReportFiles(dir(), keep = 2)
        for (n in 1..3) {
            val report = files.save(n, "report $n", at + n * 1_000L, utc)
            files.saveShot(report, byteArrayOf(n.toByte()))
        }
        files.save(4, "report 4", at + 4_000L, utc)
        val shots = dir().list()!!.filter { it.endsWith(".jpg") }.sorted()
        assertEquals(listOf("report-003-20251120-194208.jpg"), shots)
        assertEquals(listOf(3, 4), files.reports().map { SportsLiveReportFiles.numberOf(it.name) })
    }

    @Test
    fun `a screenshot whose report was deleted goes at the next save and other images stay`() {
        val files = SportsLiveReportFiles(dir())
        val first = files.save(1, "first", at, utc)
        files.saveShot(first, byteArrayOf(1))
        val other = File(dir(), "keep-me.jpg").apply { writeBytes(byteArrayOf(9)) }
        first.delete()
        files.save(2, "second", at + 1_000, utc)
        assertFalse(File(dir(), "report-001-20251120-194205.jpg").exists())
        assertTrue(other.exists())
    }

    @Test
    fun `the report text its latest copy and its QR code never mention the screenshot`() {
        val files = SportsLiveReportFiles(dir())
        val text = "Nuvio Live report #1
From: Hold OK
${SportsLiveReport.DETAILS}
"
        val report = files.save(1, text, at, utc)
        val shot = files.saveShot(report, byteArrayOf(1, 2, 3))!!
        files.rewrite(report, SportsLiveReport.withNote(report.readText(), "the score is wrong", emptyList()))
        val latest = File(dir(), SportsLiveReportFiles.LATEST).readText()
        for (shared in listOf(report.readText(), latest, SportsLiveReport.qrPayload(report.readText()))) {
            assertFalse(shared.contains(shot.name))
            assertFalse(shared.contains(".jpg", ignoreCase = true))
            assertFalse(shared.contains("screenshot", ignoreCase = true))
        }
    }

    @Test
    fun `screenshots only where no other app can read the reports folder`() {
        assertFalse(SportsLiveReportFiles.shotsAllowed(sdkInt = 25, externalFolder = false))
        assertTrue(SportsLiveReportFiles.shotsAllowed(sdkInt = 26, externalFolder = false))
        assertFalse(SportsLiveReportFiles.shotsAllowed(sdkInt = 29, externalFolder = true))
        assertTrue(SportsLiveReportFiles.shotsAllowed(sdkInt = 30, externalFolder = true))
    }
}
