package com.nuvio.tv.core.sportslive

import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.sportslive.scores.TeamDirectory
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import kotlin.math.abs

/**
 * The duplicate detector of debug builds (live.18): rows of one list that look like one game but did not
 * merge. Two event rows are suspects when they are at one time (both live, or timed within [WINDOW_MS]) and
 * their sides resolve to the same ESPN teams in either order, or their sides' words overlap by at least
 * [MIN_OVERLAP] on both sides. Each suspect carries why [SportsLiveItemBucketer.merge] kept them apart
 * ([SportsLiveItemBucketer.splitReason]). Logged under [TAG] with titles and tags only, never a URL or id.
 */
object SportsLiveDupes {
    const val TAG = "NuvioDupes"

    /** How far apart two timed starts may be. */
    const val WINDOW_MS = 3 * 60 * 60_000L

    /** The least share of the shorter side's words the other side holds. */
    const val MIN_OVERLAP = 0.6

    /** One suspected duplicate: [why] they look alike, [reason] they stayed apart. */
    data class Suspect(val a: SportsLiveItem, val b: SportsLiveItem, val why: String, val reason: String)

    private val words = Memo(8_192) { side: String ->
        SportsLiveTeams.normalise(SportsLiveTeams.foldAccents(side)).split(' ').filterTo(HashSet()) { it.isNotEmpty() }
    }

    /** The suspected duplicates among [rows] (each row with its merged copies), in list order. */
    fun scan(rows: List<SportsLiveItem>, nowMs: Long, directory: TeamDirectory = TeamDirectory.shared): List<Suspect> {
        val games = rows.filter { it.kind == SportsLiveKind.EVENTS && !it.isChannel && it.teams != null }
        val found = ArrayList<Suspect>()
        for (i in games.indices) {
            for (j in i + 1 until games.size) {
                val a = games[i]
                val b = games[j]
                if (!atOneTime(a, b) || !shareWords(a, b)) continue
                val why = when {
                    SportsLiveItemBucketer.idVerdict(a, b, directory) == SportsLiveItemBucketer.IdVerdict.SAME -> "same team ids"
                    namesAlike(a, b) -> "names alike"
                    else -> continue
                }
                val reason = SportsLiveItemBucketer.splitReason(a, b, nowMs, directory) ?: "would merge now (merged before its list loaded)"
                found += Suspect(a, b, why, reason)
            }
        }
        return found
    }

    private fun atOneTime(a: SportsLiveItem, b: SportsLiveItem): Boolean {
        if (a.isLive && b.isLive) return true
        val startA = a.startMs?.takeIf { a.startHasTimeOfDay } ?: return false
        val startB = b.startMs?.takeIf { b.startHasTimeOfDay } ?: return false
        return abs(startA - startB) <= WINDOW_MS
    }

    // Cheap first look: each side shares a word with a side of the other row ("LA Clippers" and "Los Angeles
    // Clippers" share "clippers"), in either order; only such pairs are worth resolving to team ids.
    private fun shareWords(a: SportsLiveItem, b: SportsLiveItem): Boolean {
        val x = a.teams ?: return false
        val y = b.teams ?: return false
        return (overlap(x.home, y.home) > 0 && overlap(x.away, y.away) > 0) || (overlap(x.home, y.away) > 0 && overlap(x.away, y.home) > 0)
    }

    /** True when both sides' words overlap by [MIN_OVERLAP], in either order. */
    internal fun namesAlike(a: SportsLiveItem, b: SportsLiveItem): Boolean {
        val x = a.teams ?: return false
        val y = b.teams ?: return false
        return (overlap(x.home, y.home) >= MIN_OVERLAP && overlap(x.away, y.away) >= MIN_OVERLAP) ||
            (overlap(x.home, y.away) >= MIN_OVERLAP && overlap(x.away, y.home) >= MIN_OVERLAP)
    }

    /** The share of the shorter side's words the other side holds ("LA Clippers" in "Los Angeles Clippers": 0.5). */
    internal fun overlap(first: String, second: String): Double {
        val a = words(first)
        val b = words(second)
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return a.count { it in b }.toDouble() / minOf(a.size, b.size)
    }

    // Pairs already logged this session, so a list rebuilt every few seconds logs each pair once.
    private val logged = LinkedHashSet<String>()

    /** Logs [rows]' new suspects under [TAG] for [surface] ("Live now", "Panel"); debug builds only. */
    fun log(surface: String, rows: List<SportsLiveItem>, nowMs: Long) {
        if (!BuildConfig.IS_DEBUG_BUILD) return
        for (suspect in scan(rows, nowMs)) {
            val key = listOf(surface, suspect.a.streamKey, suspect.b.streamKey).joinToString("\u0000")
            val fresh = synchronized(logged) {
                if (logged.size >= MAX_LOGGED) logged.clear()
                logged.add(key)
            }
            if (!fresh) continue
            Log.d(TAG, "[$surface] \"${suspect.a.meta.name}\" (${tags(suspect.a)}) ~ \"${suspect.b.meta.name}\" (${tags(suspect.b)}): " +
                "${suspect.why}; not merged: ${suspect.reason}")
        }
    }

    // The row's sport and genres (its tags); never its id, catalog key or any link.
    private fun tags(item: SportsLiveItem): String =
        (listOfNotNull(item.sport) + item.meta.genres).joinToString(", ").ifEmpty { "no tags" }

    private const val MAX_LOGGED = 2_000
}
