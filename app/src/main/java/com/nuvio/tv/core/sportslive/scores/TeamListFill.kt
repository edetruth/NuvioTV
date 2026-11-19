package com.nuvio.tv.core.sportslive.scores

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.CoroutineContext

/** What [TeamListFill.ensure] found for a league. */
sealed interface TeamListResult {
    val teams: List<DirectoryTeam>

    /** The kept list, less than a week old: no request. */
    data class Cached(override val teams: List<DirectoryTeam>) : TeamListResult
    /** Just fetched from ESPN (and kept). */
    data class Fetched(override val teams: List<DirectoryTeam>) : TeamListResult
    /** ESPN did not answer, but an older list is kept: searchable, refreshed next time. */
    data class Stale(override val teams: List<DirectoryTeam>) : TeamListResult
    /** ESPN did not answer and nothing is kept (offline on first use). */
    data object Failed : TeamListResult {
        override val teams: List<DirectoryTeam> get() = emptyList()
    }
}

/**
 * Fills a league's ESPN team list on demand (#149): team search asks for the leagues it shows, whether or
 * not any of their games is on screen, so it works off-season (a team list is not a schedule). The list
 * goes into the shared [directory] and its files ([TeamDirectoryStore], the same files the background
 * [TeamDirectorySync] keeps), so it is fetched at most weekly and survives restarts. Requests go one at a
 * time; a league that just failed is asked again only when the screen asks again (the viewer retries).
 */
@Singleton
class TeamListFill internal constructor(
    private val directory: TeamDirectory,
    private val fetch: suspend (EspnLeague) -> List<DirectoryTeam>?,
    private val store: TeamDirectoryStore?,
    private val now: () -> Long,
    private val io: CoroutineContext = Dispatchers.IO
) {
    @Inject
    constructor(provider: ScoreProvider, @ApplicationContext context: Context) : this(
        directory = TeamDirectory.shared,
        fetch = provider::teamList,
        store = TeamDirectoryStore(File(context.filesDir, "sportslive/team_directory")),
        now = System::currentTimeMillis
    )

    private val mutex = Mutex()
    private var loaded = false

    /** The kept list of [league] when it is less than a week old; else fetched, else the older list. */
    suspend fun ensure(league: EspnLeague): TeamListResult = mutex.withLock {
        loadLocked()
        val kept = directory.teams(league)
        val at = directory.fetchedAt(league)
        if (at != null && kept.isNotEmpty() && now() - at in 0 until TeamDirectory.REFRESH_MS) {
            return@withLock TeamListResult.Cached(kept)
        }
        val fetched = runCatching { fetch(league) }.getOrNull()?.takeIf { it.isNotEmpty() }
        if (fetched == null) {
            return@withLock if (kept.isNotEmpty()) TeamListResult.Stale(kept) else TeamListResult.Failed
        }
        val stamp = now()
        val dropped = directory.put(league, fetched, stamp)
        withContext(io) {
            dropped.forEach { store?.delete(it) }
            store?.write(league, fetched, stamp)
        }
        TeamListResult.Fetched(fetched)
    }

    /** The kept files, read once: the background sync may not have loaded them yet when search opens. */
    private suspend fun loadLocked() {
        if (loaded) return
        loaded = true
        val stored = withContext(io) { store?.readAll().orEmpty() }
        for ((league, at, teams) in stored) {
            val known = directory.fetchedAt(league)
            if (known == null || known < at) directory.put(league, teams, at)
        }
    }
}
