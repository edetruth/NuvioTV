package com.nuvio.tv.data.local

import android.content.Context
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sportslive.SportsLiveDebugTiming
import com.nuvio.tv.core.sportslive.SportsLiveListCache
import com.nuvio.tv.core.sportslive.SportsLiveListSnapshot
import com.nuvio.tv.core.sportslive.SportsLiveListSnapshots
import com.squareup.moshi.Moshi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Sports & Live list last built for each profile (live.20 B1): in memory for the process, so coming back to the
 * section shows it at once, and in a file per profile in app storage (`sportslive/lists/‹profile›.json`), so the
 * first open after a restart does too. Never synced, never sent anywhere; no addon URL in the file
 * ([SportsLiveListSnapshots.encode]). A failed read or write only loses the list: it never crashes the app.
 */
@Singleton
class SportsLiveListSnapshotStore internal constructor(
    private val dir: File,
    private val moshi: Moshi,
    private val profileId: () -> Int,
    private val ioDispatcher: CoroutineDispatcher,
    /** Writes wait this long, so a burst of builds costs one write. */
    private val writeDelayMs: Long = WRITE_DELAY_MS
) : SportsLiveListCache {

    @Inject
    constructor(@ApplicationContext context: Context, moshi: Moshi, profileManager: ProfileManager) : this(
        dir = File(context.filesDir, DIR),
        moshi = moshi,
        profileId = { profileManager.activeProfileId.value },
        ioDispatcher = Dispatchers.IO
    )

    private val memory = ConcurrentHashMap<Int, SportsLiveListSnapshot>()
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val pending = Channel<Pair<Int, SportsLiveListSnapshot>>(Channel.UNLIMITED)

    init {
        scope.launch {
            // Each profile's newest list, written once the burst settles.
            val newest = LinkedHashMap<Int, SportsLiveListSnapshot>()
            while (true) {
                val (profile, snapshot) = pending.receive()
                newest[profile] = snapshot
                delay(writeDelayMs)
                while (true) {
                    val next = pending.tryReceive().getOrNull() ?: break
                    newest[next.first] = next.second
                }
                for ((p, s) in newest) write(p, s)
                newest.clear()
            }
        }
    }

    override fun peek(): SportsLiveListSnapshot? = memory[profileId()]

    override suspend fun read(): SportsLiveListSnapshot? {
        val profile = profileId()
        memory[profile]?.let { return it }
        val read = withContext(ioDispatcher) { readFile(profile) } ?: return null
        // A list built meanwhile is newer than the file's.
        return memory.putIfAbsent(profile, read) ?: read
    }

    override fun save(snapshot: SportsLiveListSnapshot) {
        val profile = profileId()
        memory[profile] = snapshot
        pending.trySend(profile to snapshot)
    }

    /** [profile]'s list as stored on the device, or null (none, unreadable, another version). */
    internal fun readFile(profile: Int): SportsLiveListSnapshot? {
        val startedAt = System.nanoTime()
        val file = fileOf(profile)
        val snapshot = try {
            if (file.isFile) SportsLiveListSnapshots.decode(file.readText(), moshi) else null
        } catch (e: Exception) {
            null
        }
        SportsLiveDebugTiming.log {
            "sportsOpen stage=snapshotRead ms=${(System.nanoTime() - startedAt) / 1_000_000} bytes=${file.length()} found=${snapshot != null}"
        }
        return snapshot
    }

    /** Writes [snapshot] as [profile]'s list now (through a temporary file, so a crash never leaves half a list). */
    internal fun write(profile: Int, snapshot: SportsLiveListSnapshot) {
        val startedAt = System.nanoTime()
        try {
            val file = fileOf(profile)
            dir.mkdirs()
            val temp = File(dir, file.name + ".tmp")
            val text = SportsLiveListSnapshots.encode(snapshot, moshi)
            temp.writeText(text)
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
            SportsLiveDebugTiming.log {
                "sportsOpen stage=snapshotWrite ms=${(System.nanoTime() - startedAt) / 1_000_000} chars=${text.length}"
            }
        } catch (e: Exception) {
            // Failure-silent: the next build writes again.
        }
    }

    private fun fileOf(profile: Int): File = File(dir, "$profile.json")

    private companion object {
        const val DIR = "sportslive/lists"
        const val WRITE_DELAY_MS = 5_000L
    }
}
