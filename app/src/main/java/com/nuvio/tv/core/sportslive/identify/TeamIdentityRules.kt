package com.nuvio.tv.core.sportslive.identify

import com.nuvio.tv.core.sportslive.Memo
import java.text.Normalizer

/**
 * The pure rules of team identification (#168): name keys, sport keys, when two sides agree, and when a
 * second, sport-hinted look is worth asking for. Plain string work, memoised (#152): no regex.
 */
object TeamIdentityRules {

    private const val KEY_MEMO = 8_192
    private val keys = Memo(KEY_MEMO) { text: String -> keyUncached(text) }

    /**
     * [text] as a lookup key: lowercase, accents folded, anything but letters and digits a single space
     * ("Maccabi Tel-Aviv" and "maccabi tel aviv" share one key; "București" is "bucuresti").
     */
    fun nameKey(text: String): String = keys(text)

    private fun keyUncached(text: String): String {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
        val out = StringBuilder(decomposed.length)
        var spaced = true
        for (c in decomposed) {
            if (Character.getType(c) == Character.NON_SPACING_MARK.toInt()) continue
            if (Character.isLetterOrDigit(c)) {
                out.append(c.lowercaseChar())
                spaced = false
            } else if (!spaced) {
                out.append(' ')
                spaced = true
            }
        }
        return out.toString().trimEnd()
    }

    /**
     * A source's sport name as an ESPN-style key: "Soccer" → soccer, "American Football" → football,
     * "Ice Hockey" → hockey; any other is lowercased ("Basketball" → basketball, "Volleyball" → volleyball).
     */
    fun sportKey(text: String): String {
        val key = nameKey(text)
        return SPORT_ALIASES[key] ?: key
    }

    private val SPORT_ALIASES = mapOf(
        "american football" to "football",
        "ice hockey" to "hockey",
        "association football" to "soccer",
        "rugby union" to "rugby",
        "rugby league" to "rugby"
    )

    /**
     * The sport word a card shows for [sport], one [com.nuvio.tv.core.sportslive.SportsLiveSportKind.of]
     * reads ("Ice Hockey", "American Football"); another sport is its key capitalised ("Volleyball").
     */
    fun sportWord(sport: String): String = when (sport) {
        "basketball" -> "Basketball"
        "soccer" -> "Soccer"
        "hockey" -> "Ice Hockey"
        "football" -> "American Football"
        "baseball" -> "Baseball"
        "rugby" -> "Rugby"
        "cricket" -> "Cricket"
        else -> sport.split(' ').filter { it.isNotEmpty() }.joinToString(" ") { word -> word.replaceFirstChar { it.uppercaseChar() } }
    }

    /**
     * True when [candidate] answers to [queryKey]: its name or an alternate is the query, or starts out as it
     * word for word ("Olympiacos" finds "Olympiacos BC"); a source's loose match ("BC Dubai" → "Dubai City")
     * is left out.
     */
    fun answersTo(candidate: TeamCandidate, queryKey: String): Boolean {
        if (queryKey.isEmpty()) return false
        val padded = " $queryKey "
        return (listOf(candidate.name) + candidate.alternates).any { text ->
            val key = nameKey(text)
            key.isNotEmpty() && (key == queryKey || " $key ".contains(padded) || (key.contains(' ') && padded.contains(" $key ")))
        }
    }

    /**
     * The game both sides' candidates agree on: exactly one sport both have, and within it a league both
     * list (several: the first side's first-listed one). A side with no candidates, two shared sports or no
     * shared league is no identity.
     */
    fun agree(first: List<TeamCandidate>, second: List<TeamCandidate>): GameIdentity? {
        if (first.isEmpty() || second.isEmpty()) return null
        val shared = first.mapTo(LinkedHashSet()) { it.sport }.intersect(second.mapTo(HashSet()) { it.sport })
        val sport = shared.singleOrNull() ?: return null
        val firstTeams = first.filter { it.sport == sport }
        val secondTeams = second.filter { it.sport == sport }
        val secondLeagues = secondTeams.flatMapTo(HashSet()) { team -> team.leagues.map(::nameKey) }
        val league = firstTeams.asSequence().flatMap { it.leagues.asSequence() }
            .firstOrNull { it.isNotBlank() && nameKey(it) in secondLeagues } ?: return null
        val leagueKey = nameKey(league)
        fun badgeOf(teams: List<TeamCandidate>) =
            (teams.filter { team -> team.leagues.any { nameKey(it) == leagueKey } } + teams).firstNotNullOfOrNull { it.badge }
        return GameIdentity(sport, league, badgeOf(firstTeams) to badgeOf(secondTeams))
    }

    // Words in a club's name that say its sport ("BC Dubai", "Dubai Basketball", "KK Partizan").
    private val MARKERS = mapOf(
        "bc" to "basketball", "bk" to "basketball", "kk" to "basketball", "basket" to "basketball",
        "basketball" to "basketball", "baloncesto" to "basketball", "basquet" to "basketball", "pallacanestro" to "basketball"
    )

    /**
     * The words a sport-hinted look tries after a club's base name (TheSportsDB names a multi-sport club's
     * basketball team "Maccabi Tel Aviv BC" or "Dubai Basketball"). A sport without any is never hinted.
     */
    internal val HINT_SUFFIXES = mapOf("basketball" to listOf("BC", "Basketball"))

    /** The sport [name]'s own words say ("BC Dubai" → basketball); null when none or several. */
    fun markedSport(name: String): String? =
        nameKey(name).split(' ').mapNotNullTo(HashSet()) { MARKERS[it] }.singleOrNull()

    /** [name] without its sport words ("BC Dubai" → "Dubai"), as written; plain words. */
    fun withoutMarkers(name: String): String =
        name.split(' ').filter { word -> word.isNotBlank() && MARKERS[nameKey(word)] == null }.joinToString(" ")

    /**
     * The sport worth a second look when [agree] found nothing (#168). TheSportsDB's free key answers a
     * multi-sport club with one team, usually its football club, so "Maccabi Tel Aviv" reads as soccer
     * alone. A sport named in either side's name decides; else, one side known in a single sport other
     * than soccer against a side known only in soccer. Null: nothing worth asking (or no suffixes for it).
     */
    fun hintSport(firstName: String, first: List<TeamCandidate>, secondName: String, second: List<TeamCandidate>): String? {
        val marked = listOfNotNull(markedSport(firstName), markedSport(secondName)).toSet()
        val sport = when {
            marked.isNotEmpty() -> marked.singleOrNull()
            else -> {
                val a = first.mapTo(HashSet()) { it.sport }
                val b = second.mapTo(HashSet()) { it.sport }
                when {
                    a.size == 1 && a.first() != SOCCER && b == setOf(SOCCER) -> a.first()
                    b.size == 1 && b.first() != SOCCER && a == setOf(SOCCER) -> b.first()
                    else -> null
                }
            }
        }
        return sport?.takeIf { it in HINT_SUFFIXES }
    }

    private const val SOCCER = "soccer"
}
