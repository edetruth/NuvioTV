package com.nuvio.tv.core.sportslive.nightly

import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * #192: the nightly accuracy run, on the PC only. Skipped unless [ENV_OUT] and [ENV_ADDONS] are set, so normal runs
 * and CI never touch the network. Started by D:/Nuvio/tools/nightly/run-nightly.sh (Task Scheduler, 03:00):
 * `./gradlew :app:testFullDebugUnitTest --tests '*NightlyAccuracyRun*' --rerun` with
 * [ENV_OUT] = the reports folder (outside the repo) and [ENV_ADDONS] = the folder of private addon URL files.
 */
class NightlyAccuracyRun {

    @Test
    fun run() {
        val out = System.getenv(ENV_OUT)?.takeIf { it.isNotBlank() }
        val addons = System.getenv(ENV_ADDONS)?.takeIf { it.isNotBlank() }
        assumeTrue("nightly accuracy run: $ENV_OUT and $ENV_ADDONS unset", out != null && addons != null)
        val hours = System.getenv(ENV_UPCOMING_HOURS)?.toIntOrNull()?.coerceIn(1, 48) ?: 12
        try {
            NightlyAccuracy(File(out!!), File(addons!!), upcomingHours = hours).run()
        } catch (error: Throwable) {
            // An exception's message may hold a link: only its type and frames (class, method, line) leave the run.
            throw AssertionError("nightly run failed: ${error::class.java.name} at ${error.stackTrace.take(8).joinToString(" < ")}")
        }
    }

    private companion object {
        const val ENV_OUT = "NUVIO_NIGHTLY_OUT"
        const val ENV_ADDONS = "NUVIO_NIGHTLY_ADDONS"
        const val ENV_UPCOMING_HOURS = "NUVIO_NIGHTLY_UPCOMING_HOURS"
    }
}
