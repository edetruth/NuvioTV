package com.nuvio.tv.core.sportslive

import java.util.Locale

/**
 * National teams (#204). Two nations ("New Zealand vs India") meet in soccer, cricket, rugby, hockey and more,
 * so a game between them is never given a sport from the teams alone: a team search or a team list answers
 * with whichever national side it covers (ESPN's search found only India's soccer team, so the cricket Test
 * read as soccer). A side counts with or without a women's or youth note ("India Women", "New Zealand U17").
 */
object SportsLiveNations {

    private const val MEMO = 4_096
    private val nations = Memo(MEMO) { name: String -> isNationUncached(name) }

    /** True when both [first] and [second] are nations; see the class doc. */
    fun isNationalPair(first: String, second: String): Boolean = isNation(first) && isNation(second)

    /** True when [name] is a nation's side ("NZ", "India Women", "Mali U17"). */
    fun isNation(name: String): Boolean = nations(name)

    /**
     * [name] as a key two spellings of one side share ("NZ" and "New Zealand"), its women's or youth note kept
     * ("India Women" is not "India").
     */
    fun key(name: String): String = SportsLiveTeams.canonicalise(name)

    private fun isNationUncached(name: String): Boolean {
        val base = QUALIFIER.replace(SportsLiveTeams.canonicalise(name), "").trim()
        return base.isNotEmpty() && (base in NAMES || SportsLiveTeamAliases.canonical(base)?.let { it in NAMES } == true)
    }

    // A trailing women's or youth note, as [SportsLiveTeams.normalise] leaves it: "women", "w", "(w)", "u17".
    private val QUALIFIER = Regex("""(\s+(women|womens|w|\(w\)|\(women\)|\(?u\d{1,2}\)?|\[u\d{1,2}]))+$""")

    // The four home nations and other sides that are no ISO country, and common short forms.
    private val EXTRA = listOf(
        "England", "Scotland", "Wales", "Northern Ireland", "West Indies", "Hong Kong", "Macau", "USA", "United States",
        "South Korea", "Korea Republic", "North Korea", "Czech Republic", "Ivory Coast", "Cote d Ivoire", "Chinese Taipei",
        "Kosovo", "Palestine", "Republic of Ireland", "DR Congo", "Congo DR", "Cape Verde", "Bosnia and Herzegovina",
        "North Macedonia", "Turkey", "Turkiye", "Russia", "Iran", "Syria", "Vietnam", "Laos", "Brunei", "Bolivia",
        "Venezuela", "Tanzania", "Moldova", "Eswatini", "Curacao", "Kyrgyz Republic", "Faroe Islands", "Gibraltar",
        "Papua New Guinea", "United Arab Emirates", "UAE"
    )

    private val NAMES: Set<String> by lazy {
        val iso = Locale.getISOCountries().map { Locale("", it).getDisplayCountry(Locale.ENGLISH) }
        (iso + EXTRA).mapTo(HashSet()) { SportsLiveTeams.canonicalise(it) }.apply { remove("") }
    }
}
