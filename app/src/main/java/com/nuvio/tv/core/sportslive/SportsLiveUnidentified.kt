package com.nuvio.tv.core.sportslive

import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.sportslive.identify.SportsLiveTeamIdentity
import com.nuvio.tv.core.sportslive.identify.TeamIdentityBook
import com.nuvio.tv.core.sportslive.scores.TeamDirectory
import com.nuvio.tv.domain.model.SportsLiveItem

/**
 * The continuous cleanup's evidence (live.20, user TV report #4): Live now and Upcoming rows the app could not place,
 * no sport from any source (tag, title, genres, description, team directory, team lookup), not a channel, not a show.
 * Each is kept as its clean title and its addon's display name only, never a link, id or description, with how many
 * loads listed it, on this device: [MAX_ENTRIES] titles at most, the longest unseen dropped first. A title the app
 * places later (a team lookup answering) leaves the list.
 *
 * How the coordinator reads it (nothing leaves the device by itself):
 * - Debug builds log each title once per app run under [TAG]: `adb logcat -d -s NuvioUnidentified` after the app has
 *   loaded Sports & Live, one line per title: `<loads>x | <addon> | <title>`. The nightly #192 job reads these lines.
 * - [report] gives the same lines, most listed first, for Report a problem (#172) to attach.
 * - [SportsLiveUnidentifiedStore][com.nuvio.tv.data.local.SportsLiveUnidentifiedStore] keeps the list across restarts.
 * A title seen there gets a rule (a show title in [SportsLiveNonSport], a brand in [SportsLiveBroadcasters], a league
 * word in [SportsLiveSportKind]) and a regression test.
 */
class SportsLiveUnidentified(private val maxEntries: Int = MAX_ENTRIES) {

    /** One unplaced title: listed by [addon] on [loads] loads, last at [lastSeenMs]. */
    data class Entry(val addon: String, val title: String, val loads: Int, val lastSeenMs: Long)

    private val entries = LinkedHashMap<String, Entry>()
    private val countedLoad = HashMap<String, Int>()
    private val logged = HashSet<String>()
    private var onChange: ((List<Entry>) -> Unit)? = null

    /**
     * Records [items] (one Live now or Upcoming list of the load [loadToken]): each unplaced one is added or counted
     * (once per load), each placed one leaves the list.
     */
    @Synchronized
    fun record(
        items: List<SportsLiveItem>,
        loadToken: Int,
        nowMs: Long,
        place: (SportsLiveItem) -> SportsLiveItem = { placed(it) }
    ) {
        var changed = false
        for (item in items) {
            if (item.isChannel) continue
            val title = SportsLiveDisplayText.name(item.meta.name).trim()
            if (title.isEmpty() || "://" in title) continue
            val addon = item.addonName.trim()
            val key = keyOf(addon, title)
            if (!isUnplaced(item, place)) {
                if (entries.remove(key) != null) changed = true
                countedLoad.remove(key)
                continue
            }
            val old = entries[key]
            val counted = countedLoad[key] == loadToken
            if (old != null && counted) continue
            countedLoad[key] = loadToken
            val entry = Entry(addon, title, (old?.loads ?: 0) + 1, nowMs)
            entries.remove(key)
            entries[key] = entry
            changed = true
            if (BuildConfig.IS_DEBUG_BUILD && logged.add(key)) Log.d(TAG, lineOf(entry))
        }
        while (entries.size > maxEntries) {
            val oldest = entries.entries.minByOrNull { it.value.lastSeenMs }?.key ?: break
            entries.remove(oldest)
            countedLoad.remove(oldest)
        }
        if (changed) onChange?.invoke(entries.values.toList())
    }

    /** The unplaced titles, most listed first. */
    @Synchronized
    fun snapshot(): List<Entry> = entries.values.sortedWith(compareByDescending<Entry> { it.loads }.thenByDescending { it.lastSeenMs })

    /** [snapshot] as text, one `<loads>x | <addon> | <title>` line each (Report a problem, #172). */
    fun report(): String = snapshot().joinToString("\n") { lineOf(it) }

    /** Restores [stored] (from [decode]) and reports every later change to [onChange] (the store saves it). */
    @Synchronized
    fun install(stored: List<Entry>, onChange: (List<Entry>) -> Unit) {
        for (entry in stored.sortedBy { it.lastSeenMs }) {
            val key = keyOf(entry.addon, entry.title)
            if (key !in entries) entries[key] = entry
        }
        this.onChange = onChange
    }

    private fun isUnplaced(item: SportsLiveItem, place: (SportsLiveItem) -> SportsLiveItem): Boolean {
        if (sportKnown(item)) return false
        val view = place(item)
        return !sportKnown(view)
    }

    private fun sportKnown(item: SportsLiveItem): Boolean =
        (listOf(item) + item.alternates).any { SportsLiveMySportsScope.kindOf(it) != null || SportsLiveTeams.knownSport(it) != null }

    companion object {
        const val TAG = "NuvioUnidentified"
        const val MAX_ENTRIES = 200

        /** The one the app shares. */
        val shared = SportsLiveUnidentified()

        /** [item] as the lists show it once its league and teams are inferred. */
        fun placed(item: SportsLiveItem): SportsLiveItem =
            SportsLiveTeamIdentity.identified(SportsLiveSportInference.infer(item, TeamDirectory.shared), TeamIdentityBook.shared)

        private fun keyOf(addon: String, title: String): String = addon.lowercase() + "\u0000" + title.lowercase()

        private fun lineOf(entry: Entry): String = "${entry.loads}x | ${entry.addon} | ${entry.title}"

        private fun clean(text: String): String = text.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

        /** [entries] as stored text: one tab-separated line each. */
        fun encode(entries: List<Entry>): String =
            entries.joinToString("\n") { "${it.loads}\t${it.lastSeenMs}\t${clean(it.addon)}\t${clean(it.title)}" }

        /** Stored text from [encode] as entries; unreadable lines are skipped. */
        fun decode(text: String?): List<Entry> =
            text.orEmpty().split('\n').mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size != 4) return@mapNotNull null
                val loads = parts[0].toIntOrNull() ?: return@mapNotNull null
                val seen = parts[1].toLongOrNull() ?: return@mapNotNull null
                if (parts[3].isBlank()) null else Entry(parts[2], parts[3], loads, seen)
            }
    }
}
