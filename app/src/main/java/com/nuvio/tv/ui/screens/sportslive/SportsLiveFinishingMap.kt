package com.nuvio.tv.ui.screens.sportslive

import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Like mapLatest, except that a running [transform] is let finish while the next value is only an update of the same
 * view ([supersedes] false): that value waits, and the latest one waiting runs right after. A value that [supersedes]
 * the running one (another tab or filter) cancels it at once, as mapLatest does.
 *
 * F21: on entering Live Now the scores feeds, logos and the sports found in the background each change the board's
 * inputs within the first second or two; with mapLatest every change cancelled the board being built, so the first one
 * only landed once the inputs settled. Now the first board shows after one build and the newest follows.
 *
 * [instant] (#143): when a value starts a new run (another tab or filter, or nothing was running), a result it already
 * has for that value's view is sent at once, before [transform] runs: a tab opened again shows its last list while
 * the fresh one is built.
 */
internal fun <T, R> Flow<T>.mapFinishing(
    supersedes: (running: T, next: T) -> Boolean,
    instant: (T) -> R? = { null },
    transform: suspend (T) -> R
): Flow<R> = channelFlow {
    val lock = Mutex()
    var worker: Job? = null
    var running: Held<T>? = null
    var waiting: Held<T>? = null
    collect { value ->
        lock.withLock {
            val current = running
            if (current != null && worker?.isActive == true && !supersedes(current.value, value)) {
                waiting = Held(value)
            } else {
                worker?.cancel()
                waiting = null
                running = Held(value)
                instant(value)?.let { send(it) }
                worker = launch {
                    val self = currentCoroutineContext()[Job]
                    var next: T = value
                    while (true) {
                        send(transform(next))
                        next = lock.withLock {
                            // A newer view took over meanwhile: this worker is done.
                            if (worker !== self) return@withLock null
                            val pending = waiting
                            waiting = null
                            running = pending
                            pending?.value
                        } ?: break
                    }
                }
            }
        }
    }
}

/** A value held as running or waiting (a wrapper, so a null value is still told from none). */
private class Held<T>(val value: T)
