package com.nuvio.tv.core.sportslive.scores

import com.nuvio.tv.domain.model.SportsLiveItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.text.Normalizer

/** A team's logo and abbreviation, learned from a matched game (#66). */
data class KnownTeam(val logo: String, val abbreviation: String?)

/**
 * Team logos learned from matched games (#66), kept in app storage so a card draws its logos on the
 * next launch before any score feed answers. Keyed by league (soccer as one, since `soccer/all` and the
 * competition feeds list the same clubs) and the side's name as the card writes it, so only a name
 * that once matched strictly is ever drawn with a logo. Least recently used entries go past [maxEntries].
 */
class TeamLogoBook(private val maxEntries: Int = MAX_ENTRIES) {

    private val entries = LinkedHashMap<String, KnownTeam>(64, 0.75f, true)
    private val revisionFlow = MutableStateFlow(0)

    /** Bumped when entries are loaded or learned: a drawn card re-reads its logos. */
    val revision: StateFlow<Int> = revisionFlow.asStateFlow()

    /** False while Scores is Off: no learned logo is drawn. */
    @Volatile
    var active: Boolean = true

    val size: Int @Synchronized get() = entries.size

    /** Remembers [team]'s logo for the card side [cardSide] on [league]; true when that changed anything. */
    fun learn(league: EspnLeague, cardSide: String, team: ScoreTeam): Boolean {
        val logo = team.logo?.takeIf { it.isNotBlank() && it.none(Char::isWhitespace) } ?: return false
        val key = key(league, cardSide) ?: return false
        val known = KnownTeam(logo, team.abbreviation?.trim()?.takeIf { it.isNotEmpty() && it.none(Char::isWhitespace) })
        val changed = synchronized(this) {
            if (entries[key] == known) {
                false
            } else {
                entries[key] = known
                trimLocked()
                true
            }
        }
        if (changed) revisionFlow.update { it + 1 }
        return changed
    }

    fun lookup(league: EspnLeague, cardSide: String): KnownTeam? {
        val key = key(league, cardSide) ?: return null
        return synchronized(this) { entries[key] }
    }

    /**
     * Both sides' known teams for [item], whose sides are [first] and [second]: only when exactly one
     * of the leagues the card can be on ([EspnLeagueMap], with its college fallback) knows both sides.
     */
    fun forCard(item: SportsLiveItem, first: String, second: String): Pair<KnownTeam, KnownTeam>? {
        if (!active) return null
        val source = (listOf(item) + item.alternates).firstOrNull { EspnLeagueMap.leaguesFor(it).isNotEmpty() } ?: return null
        val leagues = (EspnLeagueMap.leaguesFor(source) + listOfNotNull(EspnLeagueMap.collegeFallbackFor(source)))
            .distinctBy(::bookLeague)
        val found = leagues.mapNotNull { league ->
            val a = lookup(league, first) ?: return@mapNotNull null
            val b = lookup(league, second) ?: return@mapNotNull null
            a to b
        }.distinct()
        return found.singleOrNull()
    }

    /** One line per entry: key, logo and abbreviation, tab-separated; least recently used first. */
    @Synchronized
    fun encode(): String = entries.entries.joinToString("\n") { (key, team) -> "$key\t${team.logo}\t${team.abbreviation.orEmpty()}" }

    /** Adds [text]'s entries ([encode]d) under the ones already known; unreadable lines are skipped. */
    fun load(text: String) {
        val read = text.lineSequence().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size != 3 || parts[0].isEmpty() || parts[1].isEmpty()) return@mapNotNull null
            parts[0] to KnownTeam(parts[1], parts[2].ifEmpty { null })
        }.toList()
        if (read.isEmpty()) return
        synchronized(this) {
            val learned = LinkedHashMap(entries)
            entries.clear()
            read.forEach { (key, team) -> entries[key] = team }
            entries.putAll(learned)
            trimLocked()
        }
        revisionFlow.update { it + 1 }
    }

    private fun trimLocked() {
        val iterator = entries.keys.iterator()
        while (entries.size > maxEntries && iterator.hasNext()) {
            iterator.next()
            iterator.remove()
        }
    }

    companion object {
        const val MAX_ENTRIES = 2_000

        /** The app's book: filled by [LiveScoresRepository], read by [GameArt.of]. */
        val shared = TeamLogoBook()

        private val MARKS = Regex("""\p{Mn}+""")
        private val SPACE = Regex("""[\s\t]+""")

        private fun bookLeague(league: EspnLeague): String = if (league.sport == "soccer") "soccer" else league.path

        internal fun key(league: EspnLeague, cardSide: String): String? {
            val name = Normalizer.normalize(cardSide, Normalizer.Form.NFD).replace(MARKS, "").lowercase()
                .replace(SPACE, " ").trim()
            return if (name.isEmpty()) null else "${bookLeague(league)}|$name"
        }
    }
}

/** The [TeamLogoBook]'s file in app storage. Failure-silent: an unreadable or unwritable file is a miss. */
class TeamLogoFile(private val file: File) {
    fun read(): String? = runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull()

    fun write(text: String) {
        runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(text)
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
        }
    }
}

/** ESPN logos at the size they are drawn (#66): the full PNGs are 500 px and ~50 KB, a 96 px one ~6 KB. */
object TeamLogoUrls {
    /** The card size; also what is prefetched. */
    const val CARD_PX = 96
    /** The details pane's large logos. */
    const val LARGE_PX = 192

    // Only ESPN's /i/ image paths go through its combiner (a /guid/ logo answers HTTP 400 there).
    private val ESPN_IMAGE = Regex("""^https?://a\.espncdn\.com(/i/[A-Za-z0-9/._-]+\.png)$""")

    /** [url] through ESPN's image combiner at [px] square; any other URL unchanged. */
    fun scaled(url: String, px: Int = CARD_PX): String {
        val path = ESPN_IMAGE.matchEntire(url.trim())?.groupValues?.get(1) ?: return url
        return "https://a.espncdn.com/combiner/i?img=$path&w=$px&h=$px"
    }
}
