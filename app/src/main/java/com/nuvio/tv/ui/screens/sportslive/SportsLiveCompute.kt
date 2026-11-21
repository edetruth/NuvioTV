package com.nuvio.tv.ui.screens.sportslive

import android.os.Process
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Where Sports & Live's heavy rebuilds run (#143, RC regression): bucketing the load, re-inferring sports, the state and
 * board builds and Upcoming's build-ahead. Three threads of their own, a few steps below the UI's priority, so on a
 * four-core TV box they never take every core from the main and render threads (the RC ran them back to back on
 * Dispatchers.Default while the viewer scrolled). Never the background scheduling group: work is only deferred, not
 * starved.
 */
internal object SportsLiveCompute {
    private const val THREADS = 3

    private val factory = object : ThreadFactory {
        private val count = AtomicInteger()

        override fun newThread(task: Runnable): Thread = Thread({
            // Below the default (0); under 10, which would move the thread to the background group.
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND / 2) }
            task.run()
        }, "SportsLiveCompute-${count.incrementAndGet()}").apply { isDaemon = true }
    }

    val dispatcher: CoroutineDispatcher by lazy { Executors.newFixedThreadPool(THREADS, factory).asCoroutineDispatcher() }
}
