package com.nuvio.tv.core.sportslive.report

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The saved reports (#172) in [dir]: `report-012-20261120-194205.txt` (number, then the local date and time), the
 * newest also copied to [LATEST] so one fixed name always pulls the last one. Only the [keep] newest are kept.
 *
 * On the TV, [dir] is the app's own external files folder (no permission needed, nothing else can read it), so:
 * `adb pull /sdcard/Android/data/<application id>/files/reports/`.
 *
 * A report asked for from the remote may have a screenshot next to it ([saveShot]): `report-012-20261120-194205.jpg`.
 * It can show addon names, so it is a local file only: never in a report's text, [LATEST], the QR code or any export,
 * and it goes when its report goes.
 */
class SportsLiveReportFiles(private val dir: File, private val keep: Int = KEEP) {

    /** The saved reports, oldest first. */
    fun reports(): List<File> =
        dir.listFiles().orEmpty().filter { it.isFile && numberOf(it.name) != null }.sortedBy { numberOf(it.name) }

    fun latest(): File? = reports().lastOrNull()

    /** The number the next report gets: one more than the newest kept. */
    fun nextNumber(): Int = (reports().lastOrNull()?.let { numberOf(it.name) } ?: 0) + 1

    /** Writes report [number] saved at [savedAtMs], refreshes [LATEST] and drops all but the [keep] newest, screenshots too. */
    fun save(number: Int, text: String, savedAtMs: Long, zone: ZoneId): File {
        dir.mkdirs()
        val file = File(dir, fileName(number, savedAtMs, zone))
        file.writeText(text)
        File(dir, LATEST).writeText(text)
        reports().dropLast(keep).forEach { it.delete() }
        dropOrphanShots()
        return file
    }

    /** Saves [jpeg] as [report]'s screenshot, next to it with the same base name; null when [report] is not a saved report. */
    fun saveShot(report: File, jpeg: ByteArray): File? {
        val name = shotName(report.name) ?: return null
        if (!report.isFile) return null
        return File(dir, name).apply { writeBytes(jpeg) }
    }

    /** [report]'s screenshot; null when it has none. */
    fun shotOf(report: File): File? = shotName(report.name)?.let { File(dir, it) }?.takeIf { it.isFile }

    /** Deletes every screenshot whose report is gone (dropped as one of the oldest, or deleted by hand). */
    private fun dropOrphanShots() {
        dir.listFiles().orEmpty()
            .filter { it.isFile && SHOT_NAME.matches(it.name) && !File(dir, it.name.removeSuffix(SHOT_EXT) + ".txt").isFile }
            .forEach { it.delete() }
    }

    /** Rewrites [file] with [text] (a note added), and [LATEST] too when it is the newest. */
    fun rewrite(file: File, text: String) {
        file.writeText(text)
        if (latest()?.name == file.name) File(dir, LATEST).writeText(text)
    }

    companion object {
        const val KEEP = 20

        /** How old the newest report may be for Report a problem to show it rather than save a new one (release review). */
        const val FRESH_MS = 5 * 60_000L
        const val LATEST = "report-latest.txt"
        const val FOLDER = "reports"

        private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)
        private val NAME = Regex("""report-(\d+)-\d{8}-\d{6}\.txt""")
        private const val SHOT_EXT = ".jpg"
        private val SHOT_NAME = Regex("""report-\d+-\d{8}-\d{6}\.jpg""")

        fun fileName(number: Int, savedAtMs: Long, zone: ZoneId): String =
            "report-" + number.toString().padStart(3, '0') + "-" + STAMP.format(Instant.ofEpochMilli(savedAtMs).atZone(zone)) + ".txt"

        /**
         * True when Report a problem shows the newest report ([name], [text], written at [modifiedMs]) as it is: saved
         * within [FRESH_MS] of [nowMs], or made by a crash, ANR or native crash (it is about that moment, and the
         * crash toast sent the user to it). Otherwise a new report is saved, so the QR code tells the state now.
         */
        fun showsAsIs(name: String, text: String, modifiedMs: Long, nowMs: Long): Boolean =
            nowMs - modifiedMs in 0..FRESH_MS || SportsLiveCrashReport.crashToastNumber(name, text, null) != null

        /** The screenshot's name for the report named [reportName] (same base name, `.jpg`); null for any other file. */
        fun shotName(reportName: String): String? =
            if (numberOf(reportName) == null) null else reportName.removeSuffix(".txt") + SHOT_EXT

        /**
         * True when a report may have a screenshot: the copy needs Android 8 (PixelCopy of a window), and where the reports
         * folder is on shared storage ([externalFolder]) only from Android 11, the first that keeps every other app out of
         * an app's own folder there (a screenshot cannot be redacted like the text).
         */
        fun shotsAllowed(sdkInt: Int, externalFolder: Boolean): Boolean = sdkInt >= if (externalFolder) 30 else 26

        /** The report number in a report file's [name]; null for any other file ([LATEST] included). */
        fun numberOf(name: String): Int? = NAME.matchEntire(name)?.groupValues?.get(1)?.toIntOrNull()
    }
}
