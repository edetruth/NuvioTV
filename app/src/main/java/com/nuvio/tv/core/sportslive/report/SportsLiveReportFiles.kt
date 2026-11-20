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
 */
class SportsLiveReportFiles(private val dir: File, private val keep: Int = KEEP) {

    /** The saved reports, oldest first. */
    fun reports(): List<File> =
        dir.listFiles().orEmpty().filter { it.isFile && numberOf(it.name) != null }.sortedBy { numberOf(it.name) }

    fun latest(): File? = reports().lastOrNull()

    /** The number the next report gets: one more than the newest kept. */
    fun nextNumber(): Int = (reports().lastOrNull()?.let { numberOf(it.name) } ?: 0) + 1

    /** Writes report [number] saved at [savedAtMs], refreshes [LATEST] and drops all but the [keep] newest. */
    fun save(number: Int, text: String, savedAtMs: Long, zone: ZoneId): File {
        dir.mkdirs()
        val file = File(dir, fileName(number, savedAtMs, zone))
        file.writeText(text)
        File(dir, LATEST).writeText(text)
        reports().dropLast(keep).forEach { it.delete() }
        return file
    }

    /** Rewrites [file] with [text] (a note added), and [LATEST] too when it is the newest. */
    fun rewrite(file: File, text: String) {
        file.writeText(text)
        if (latest()?.name == file.name) File(dir, LATEST).writeText(text)
    }

    companion object {
        const val KEEP = 20
        const val LATEST = "report-latest.txt"
        const val FOLDER = "reports"

        private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)
        private val NAME = Regex("""report-(\d+)-\d{8}-\d{6}\.txt""")

        fun fileName(number: Int, savedAtMs: Long, zone: ZoneId): String =
            "report-" + number.toString().padStart(3, '0') + "-" + STAMP.format(Instant.ofEpochMilli(savedAtMs).atZone(zone)) + ".txt"

        /** The report number in a report file's [name]; null for any other file ([LATEST] included). */
        fun numberOf(name: String): Int? = NAME.matchEntire(name)?.groupValues?.get(1)?.toIntOrNull()
    }
}
