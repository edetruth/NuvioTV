package com.nuvio.tv.core.sportslive.scores

import com.nuvio.tv.core.sportslive.SportsLiveTeams
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
 * ESPN teams seen in matched games (#66), kept in app storage so a card draws its logos on the next
 * launch before any score feed answers. Each entry is an ESPN team (its league, soccer as one, and
 * its ESPN id) with its logo, abbreviation and ESPN names; the addon's text is never stored. A card
 * side not matched yet finds its team only through those names, and only when exactly one team of
 * the card's league answers to it ("Arsenal" with both an English and an Argentine Arsenal known:
 * none, the abbreviation circle shows). Least recently used teams go past [maxEntries].
 */
class TeamLogoBook(
    private val maxEntries: Int = MAX_ENTRIES,
    active: Boolean = false,
    /** Every team of the watched leagues (#82): a card none of whose sides was matched yet resolves there. */
    val directory: TeamDirectory = TeamDirectory()
) {

    private class Team(val known: KnownTeam, val names: Set<String>)

    private val entries = LinkedHashMap<String, Team>(64, 0.75f, true)
    private val revisionFlow = MutableStateFlow(0)

    /** Bumped when teams are loaded or learned: a drawn card re-reads its logos. */
    val revision: StateFlow<Int> = revisionFlow.asStateFlow()

    /**
     * False until the Scores setting is known, and while it is Off: no learned logo is drawn. A change
     * bumps [revision], so rows drawn before the setting was read redraw with their logos (I3).
     */
    @Volatile
    var active: Boolean = active
        set(value) {
            if (field == value) return
            field = value
            revisionFlow.update { it + 1 }
        }

    val size: Int @Synchronized get() = entries.size

    init {
        directory.onChange = { revisionFlow.update { it + 1 } }
    }

    /** Remembers [team] of [league] (its logo, abbreviation and names); true when that changed anything. */
    fun learn(league: EspnLeague, team: ScoreTeam): Boolean {
        val logo = team.logo?.takeIf { it.isNotBlank() && it.none(::unsafe) } ?: return false
        val id = (team.id?.takeIf { it.isNotBlank() } ?: team.displayName?.let(::fold))?.takeIf { it.none(::unsafe) } ?: return false
        val names = namesOf(team)
        if (names.isEmpty()) return false
        val entry = Team(KnownTeam(logo, team.abbreviation?.trim()?.takeIf { it.isNotEmpty() && it.none(::unsafe) }), names)
        val key = "${bookLeague(league)}|$id"
        val changed = synchronized(this) {
            val old = entries[key]
            if (old != null && old.known == entry.known && old.names == entry.names) {
                false
            } else {
                entries[key] = entry
                trimLocked()
                true
            }
        }
        if (changed) revisionFlow.update { it + 1 }
        return changed
    }

    /** The one known team of [league] whose ESPN names include [cardSide]; null when none or several. */
    fun resolve(league: EspnLeague, cardSide: String): KnownTeam? {
        val keys = sideKeys(cardSide)
        if (keys.isEmpty()) return null
        val prefix = "${bookLeague(league)}|"
        val found = synchronized(this) {
            entries.entries.filter { (key, team) -> key.startsWith(prefix) && team.names.any { it in keys } }.map { it.key }
        }
        val key = found.singleOrNull() ?: return null
        return synchronized(this) { entries[key]?.known }
    }

    /**
     * Both sides' known teams for [item], whose sides are [first] and [second]: only when exactly one
     * of the leagues the card can be on ([EspnLeagueMap], with its college fallback) resolves both.
     * Otherwise the [directory] (#82), under its own exactly-one rule, so logos never wait for a match.
     */
    fun forCard(item: SportsLiveItem, first: String, second: String): Pair<KnownTeam, KnownTeam>? {
        if (!active) return null
        return learnedForCard(item, first, second) ?: directory.forCard(item, first, second)
    }

    private fun learnedForCard(item: SportsLiveItem, first: String, second: String): Pair<KnownTeam, KnownTeam>? {
        val source = (listOf(item) + item.alternates).firstOrNull { EspnLeagueMap.leaguesFor(it).isNotEmpty() } ?: return null
        val leagues = (EspnLeagueMap.leaguesFor(source) + listOfNotNull(EspnLeagueMap.collegeFallbackFor(source)))
            .distinctBy(::bookLeague)
        val found = leagues.mapNotNull { league ->
            val a = resolve(league, first) ?: return@mapNotNull null
            val b = resolve(league, second) ?: return@mapNotNull null
            a to b
        }.distinct()
        return found.singleOrNull()
    }

    /** One line per team: key, logo, abbreviation and names (unit-separated); least recently used first. */
    @Synchronized
    fun encode(): String = entries.entries.joinToString("\n") { (key, team) ->
        "$key\t${team.known.logo}\t${team.known.abbreviation.orEmpty()}\t${team.names.joinToString(NAME_SEPARATOR)}"
    }

    /** Adds [text]'s teams ([encode]d) under the ones already known; unreadable lines are skipped. */
    fun load(text: String) {
        val read = text.lineSequence().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size != 4 || parts[0].isEmpty() || parts[1].isEmpty()) return@mapNotNull null
            val names = parts[3].split(NAME_SEPARATOR).filter { it.isNotEmpty() }.toSet()
            if (names.isEmpty()) return@mapNotNull null
            parts[0] to Team(KnownTeam(parts[1], parts[2].ifEmpty { null }), names)
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
        val shared = TeamLogoBook(directory = TeamDirectory.shared)

        private const val NAME_SEPARATOR = "\u001f"
        private val MARKS = Regex("""\p{Mn}+""")

        private fun unsafe(c: Char) = c.isWhitespace() || c == '\u001f'

        private fun bookLeague(league: EspnLeague): String = if (league.sport == "soccer") "soccer" else league.path

        private fun fold(text: String): String =
            Normalizer.normalize(text.replace('’', '\''), Normalizer.Form.NFD).replace(MARKS, "").trim()

        private fun keysOf(name: String): Set<String> {
            val folded = fold(name)
            return setOf(SportsLiveTeams.normalise(folded), SportsLiveTeams.canonicalise(folded)) - ""
        }

        /**
         * The names a card side may use for [team], as [ScoreMatcher] reads them: ESPN's names and
         * location + name (never the city alone); the abbreviation only lowercased ("IND" is not India).
         */
        internal fun namesOf(team: ScoreTeam): Set<String> {
            val names = listOfNotNull(team.displayName, team.shortDisplayName, team.name,
                if (team.location != null && team.name != null) "${team.location} ${team.name}" else null)
            val keys = names.filter { it.isNotBlank() }.flatMapTo(LinkedHashSet(), ::keysOf)
            team.abbreviation?.takeIf { it.isNotBlank() }?.let { keys += SportsLiveTeams.normalise(fold(it)) }
            return keys.filterTo(LinkedHashSet()) { it.isNotEmpty() && '\t' !in it && '\n' !in it && NAME_SEPARATOR !in it }
        }

        internal fun sideKeys(cardSide: String): Set<String> = keysOf(cardSide)
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
