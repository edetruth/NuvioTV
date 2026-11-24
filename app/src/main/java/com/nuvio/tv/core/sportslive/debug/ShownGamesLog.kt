package com.nuvio.tv.core.sportslive.debug

import android.app.Application
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.sportslive.scores.GameArt
import com.nuvio.tv.core.sportslive.scores.ScoreChips
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoreState
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveTab
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One game as a surface showed it, for the day review (T8). Never a score, never an id, link or host: [title] holds
 * team names only ([ShownText.clean]). The three y/n/? columns are "?" when the surface cannot know.
 */
data class ShownGame(
    /** Tells copies of one game apart for the once-per-day rule; kept in memory only, never written. */
    val key: String,
    val title: String,
    val sport: String,
    /** The league tag the row shows; blank when it shows the bare sport. */
    val league: String,
    val homeLogo: Boolean?,
    val awayLogo: Boolean?,
    val scoreMatched: Boolean?,
    /** ESPN's status when a game is matched: scheduled, in, final; else none. */
    val status: String
)

/** Text fit for the shown-games file: no link, host, id or control character may pass. */
object ShownText {
    private val LINKISH = Regex("""(?i)(https?:|www\.|://|[a-z0-9-]+\.[a-z]{2,}(/|:|$))""")
    private val IDLIKE = Regex("""^([0-9a-fA-F]{12,}|[A-Za-z]{1,4}\d{5,}|(?=.*\d)(?=.*[A-Za-z])[A-Za-z0-9-]{16,})$""")
    private const val EDGE = "()[]{},;:!?\"'"
    private const val INSIDE = ":/\\@=%&#_<>|"

    /** [raw] without the words that look like a link, host or id, on one line; at most [max] characters. */
    fun clean(raw: String?, max: Int = 80): String {
        if (raw.isNullOrBlank()) return ""
        val kept = raw.split(Regex("\\s+")).filter { it.isNotEmpty() }.filterNot(::leaks)
        return kept.joinToString(" ").take(max).trim()
    }

    private fun leaks(word: String): Boolean {
        if (LINKISH.containsMatchIn(word)) return true
        val core = word.trim { it in EDGE }
        return core.any { it in INSIDE } || IDLIKE.matches(core)
    }
}

/**
 * The file writer behind [ShownGamesLog]: one TSV line per game, per surface, per day (again only when what the row
 * shows changes: its status, tag, logos or match), appended to `<dir>/<yyyy-MM-dd>.tsv`. Columns: local time, surface,
 * title, sport, league tag, home logo, away logo, score matched, status. Keeps [keepDays] days. [enabled] false (a release
 * build): writes nothing and never touches the folder.
 */
class ShownGamesWriter(
    private val enabled: Boolean,
    private val dir: () -> File?,
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val keepDays: Int = 7
) {
    private val seen = HashSet<String>()
    private var seenDay: LocalDate? = null

    @Synchronized
    fun record(surface: String, games: List<ShownGame>) {
        if (!enabled || games.isEmpty()) return
        try {
            val folder = dir() ?: return
            val at = Instant.ofEpochMilli(now()).atZone(zone())
            val day = at.toLocalDate()
            if (seenDay != day) {
                seen.clear()
                seenDay = day
                folder.mkdirs()
                rotate(folder, day)
            }
            val time = String.format(java.util.Locale.ROOT, "%02d:%02d:%02d", at.hour, at.minute, at.second)
            val lines = StringBuilder()
            for (game in games) {
                val title = ShownText.clean(game.title)
                if (title.isEmpty()) continue
                val row = listOf(
                    surface, title, ShownText.clean(game.sport, 40), ShownText.clean(game.league, 40),
                    yn(game.homeLogo), yn(game.awayLogo), yn(game.scoreMatched), ShownText.clean(game.status, 12)
                )
                if (!seen.add(game.key + "\u0000" + row.joinToString("\t"))) continue
                lines.append(time).append('\t').append(row.joinToString("\t")).append('\n')
            }
            if (lines.isNotEmpty()) File(folder, "$day.tsv").appendText(lines.toString(), Charsets.UTF_8)
        } catch (_: Throwable) {
            // A debug aid never breaks the app.
        }
    }

    private fun yn(value: Boolean?): String = when (value) {
        true -> "y"
        false -> "n"
        null -> "?"
    }

    private fun rotate(folder: File, today: LocalDate) {
        val oldest = today.minusDays((keepDays - 1).toLong())
        folder.listFiles()?.forEach { file ->
            val day = runCatching { LocalDate.parse(file.name.removeSuffix(".tsv")) }.getOrNull()
            if (file.name.endsWith(".tsv") && day != null && day.isBefore(oldest)) file.delete()
        }
    }
}

/**
 * Debug builds only (T8, day review): the games the Sports & Live section shows, appended to
 * `files/shown/<date>.tsv` on the device for the nightly day review. A release build writes nothing. Calls are one-line
 * seams in the state builders; the work runs on a background thread and a list already handed in is not read again.
 */
object ShownGamesLog {
    const val LIVE_NOW = "live_now"
    const val UPCOMING = "upcoming"
    const val HOME_ROW = "home_row"
    const val PANEL = "panel"
    const val TABLE = "table"
    const val RESULTS = "results"
    const val DUAL_PICKER = "dual_picker"

    @Volatile
    private var writer: ShownGamesWriter? = null
    private val executor by lazy {
        Executors.newSingleThreadExecutor { task -> Thread(task, "shown-games").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    }
    private val lastList = ConcurrentHashMap<String, Any>()
    private val busy = ConcurrentHashMap<String, AtomicBoolean>()

    /** Once, from the application's onCreate. */
    fun install(app: Application) {
        if (!BuildConfig.IS_DEBUG_BUILD) return
        writer = ShownGamesWriter(enabled = true, dir = { app.getExternalFilesDir(null)?.let { File(it, "shown") } })
    }

    /** [items] as [surface] shows them; [scores] by card key, or null when no score feed is on (the match is then unknown). */
    fun noteItems(surface: String, items: List<SportsLiveItem>, scores: Map<String, ScoreSnapshot>?, skip: Set<String> = emptySet()) {
        val out = writer ?: return
        if (items.isEmpty() || lastList[surface] === items) return
        lastList[surface] = items
        submit(surface) {
            out.record(surface, items.filterNot { it.isChannel || it.streamKey in skip }.map { ShownGames.ofItem(it, scores?.get(it.streamKey), scores != null) })
        }
    }

    /** The tab the section shows: Live now and Upcoming only; the other tabs are other surfaces. */
    fun noteTab(tab: SportsLiveTab, items: List<SportsLiveItem>, scores: Map<String, ScoreSnapshot>?, skip: Set<String> = emptySet()) {
        when (tab) {
            SportsLiveTab.LIVE_NOW -> noteItems(LIVE_NOW, items, scores, skip)
            SportsLiveTab.UPCOMING -> noteItems(UPCOMING, items, scores)
            else -> Unit
        }
    }

    /** ESPN [events] as [surface] (Results) lists them. */
    fun noteEvents(surface: String, events: List<ScoreSnapshot>) {
        val out = writer ?: return
        if (events.isEmpty() || lastList[surface] === events) return
        lastList[surface] = events
        submit(surface) { out.record(surface, events.map(ShownGames::ofEvent)) }
    }

    private fun submit(surface: String, work: () -> Unit) {
        val flag = busy.getOrPut(surface) { AtomicBoolean(false) }
        if (!flag.compareAndSet(false, true)) {
            lastList.remove(surface) // dropped: the next build offers it again
            return
        }
        try {
            executor.execute {
                try {
                    work()
                } catch (_: Throwable) {
                } finally {
                    flag.set(false)
                }
            }
        } catch (_: Throwable) {
            flag.set(false)
        }
    }
}

/** What a game's row shows, as [ShownGame]. */
internal object ShownGames {
    fun ofItem(item: SportsLiveItem, snapshot: ScoreSnapshot?, scoresKnown: Boolean): ShownGame {
        val art = GameArt.of(item, snapshot)
        val matched = ScoreChips.matchedEvent(item, snapshot)
        val sport = item.sport.orEmpty()
        val title = art?.let { "${it.first.name} vs ${it.second.name}" } ?: item.meta.name
        val league = art?.league?.takeUnless { it.equals(sport, ignoreCase = true) }.orEmpty()
        val firstLogo = art?.first?.logo != null
        val secondLogo = art?.second?.logo != null
        val firstIsHome = art?.firstIsHome != false
        return ShownGame(
            key = item.streamKey,
            title = title,
            sport = sport,
            league = league,
            homeLogo = if (art == null) null else if (firstIsHome) firstLogo else secondLogo,
            awayLogo = if (art == null) null else if (firstIsHome) secondLogo else firstLogo,
            scoreMatched = if (!scoresKnown) null else matched != null,
            status = statusOf(matched)
        )
    }

    fun ofEvent(event: ScoreSnapshot): ShownGame {
        val art = GameArt.ofEvent(event, records = false)
        return ShownGame(
            key = event.eventId,
            title = "${art.first.name} vs ${art.second.name}",
            sport = event.league.sport,
            league = art.league.orEmpty(),
            homeLogo = art.second.logo != null,
            awayLogo = art.first.logo != null,
            scoreMatched = true,
            status = statusOf(event)
        )
    }

    private fun statusOf(event: ScoreSnapshot?): String = when {
        event == null -> "none"
        event.isFinal || event.state == ScoreState.POST -> "final"
        event.state == ScoreState.IN -> "in"
        event.state == ScoreState.PRE -> "scheduled"
        else -> "none"
    }
}
