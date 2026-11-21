package com.nuvio.tv.core.sportslive

import androidx.annotation.VisibleForTesting
import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
import com.nuvio.tv.core.sportslive.scores.TeamDirectory
import com.nuvio.tv.domain.model.SportsLiveItem
import java.text.Normalizer

/**
 * The sports Sports & Live names in short (V2, #132): the header filter's options ("Football", not
 * "American Football"; a bare "Football" is soccer, as the score feed reads it) and a row's sport tag when
 * the addon gave no league ("TENNIS", "CRICKET"). Labels come from string resources; this only decides.
 */
enum class SportsLiveSportKind {
    FOOTBALL, SOCCER, BASKETBALL, HOCKEY, BASEBALL, TENNIS, CRICKET, RUGBY, GOLF, MMA, BOXING, MOTORSPORT,
    DARTS, SNOOKER, WRESTLING, CYCLING;

    companion object {
        // Asked for every card's sport, title, genres and catalog name on every load (#152).
        private const val TEXT_CACHE_SIZE = 8_192
        private val sportOf = Memo(TEXT_CACHE_SIZE) { text: String -> ofUncached(text) }
        private val wrestlingShow = Memo(TEXT_CACHE_SIZE) { title: String -> WRESTLING_SHOW.containsMatchIn(fold(title)) }
        private val promotion = Memo(TEXT_CACHE_SIZE) { title: String -> PROMOTION.containsMatchIn(fold(title)) }

        /** A filter value standing for a [SportsLiveSportKind] (never an addon's own text). */
        private const val FILTER_PREFIX = "\u0000sport:"

        // In order: American football before soccer's "football"; the first rule found anywhere decides.
        private val RULES: List<Pair<SportsLiveSportKind, Regex>> = listOf(
            FOOTBALL to """american[ _]football|\bnfl\b|\bncaaf\b|college football|\bcfl\b""",
            BASKETBALL to """basketball|\bnba\b|\bncaab\b|\bncaam\b|\bncaaw\b|euroleague|eurocup|\bg league\b|\bwnba\b|""" +
                """baloncesto|\bacb\b|liga endesa|\bvtb\b|aba league|aba liga|betclic elite|lega basket|\bfiba\b""",
            HOCKEY to """hockey|\bnhl\b|\bkhl\b|\bshl\b|\bliiga\b|\bahl\b|\bechl\b|\bohl\b|\bwhl\b|\bqmjhl\b|\biihf\b""",
            BASEBALL to """baseball|\bmlb\b""",
            TENNIS to """tennis|\batp\b|\bwta\b|\bitf\b""",
            CRICKET to """cricket|\bicc\b|\bt20i?\b|\bt10\b|the hundred|\bodi\b|\bipl\b|\bw?bbl\b|test match|big bash|\bilt20\b|\bsa20\b|\bbpl\b|""" +
                """ranji|sheffield shield|super smash|county championship""",
            RUGBY to """rugby|\bnrlw?\b|six nations|\btop 14\b|\burc\b|state of origin""",
            GOLF to """golf|\bpga\b|\blpga\b|\bliv golf\b""",
            // Boxing first, so a boxing card's "Fight Night" is boxing; bare-knuckle boxing too (BKFC, Gromda). Not kickboxing.
            BOXING to """\bboxing\b|\bbkfc\b|bare.?knuckle|\bgromda\b""",
            // Fight promotions and their nights (live.23, TV report #9): "ONE Friday Fights", "UFC Fight Night", "Oktagon 98".
            MMA to """\bmma\b|\bufc\b|\bpfl\b|bellator|fight night|friday fights|\bfights\b|combat sports|kickboxing|""" +
                """muay thai|\bglory \d|\boktagon\b|\bksw\b|cage warriors|\bone championship\b""",
            MOTORSPORT to """motorsport|motor sport|formula 1|formula one|\bf1\b|motogp|nascar|indycar|(?<!\bisu )grand prix""",
            DARTS to """darts""",
            SNOOKER to """snooker""",
            WRESTLING to """wrestling|\bwwe\b|\baew\b|\bnxt\b|smackdown""",
            CYCLING to """cycling|tour de france""",
            SOCCER to """soccer|football|futbol|futebol|premier league|\bla ?liga|bundesliga|scottish premiership|\bspfl\b|\bwsl\b|women'?s super league|\bbundes\b|serie a|ligue [12]\b|\bmls\b|\bnwsl\b|\buefa\b|""" +
                """champions league|libertadores|sudamericana|\bcopa\b|ligapro|liga pro\b|liga mx|eredivisie|primeira liga|""" +
                """liga portugal|super lig|ekstraklasa|allsvenskan|\bconmebol\b|\bconcacaf\b|\befl\b|fa cup|carabao|a-league|""" +
                """j1 league|k league|serie b\b|coppa italia|dfb pokal"""
        ).map { (kind, pattern) -> kind to Regex(pattern) }

        /**
         * True when [text] names a sport with no [SportsLiveSportKind] ("Volleyball", "Beach Volleyball", "Handball",
         * "Netball"): a known sport all the same, so a card tagged so is never placed in another sport by its teams.
         */
        fun namesOtherSport(text: String?): Boolean = text != null && OTHER_SPORT.containsMatchIn(fold(text))

        private val OTHER_SPORT = Regex(
            """volley|handball|balonmano|handebol|pallamano|netball|lacrosse|water ?polo|futsal|badminton|table tennis|""" +
                """squash|bowls|athletics|swimming"""
        )

        /** The sport [text] names ("American Football" → FOOTBALL, "Football" → SOCCER); null when none. */
        fun of(text: String?): SportsLiveSportKind? = text?.let(sportOf::invoke)

        @VisibleForTesting
        internal fun ofUncached(text: String?): SportsLiveSportKind? {
            val folded = fold(text ?: return null).takeIf { it.isNotEmpty() } ?: return null
            return RULES.firstOrNull { (_, regex) -> regex.containsMatchIn(folded) }?.first
        }

        /**
         * The header filter value [item] sits under on every tab but Channels (null: under All alone). Its
         * sport word as a [SportsLiveSportKind], so "American Football" and "NFL" share one "Football" option;
         * another real sport keeps its own text; a catch-all ("Sports", "Live Events", a catalog's name)
         * folds into All.
         */
        fun filterKeyOf(item: SportsLiveItem): String? {
            val sport = item.sport?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            of(sport)?.let { return filterKeyOf(it) }
            return SportsLiveTeams.knownSport(item)?.trim()
        }

        fun filterKeyOf(kind: SportsLiveSportKind): String = FILTER_PREFIX + kind.name

        /** The kind a filter value from [filterKeyOf] stands for; null for an addon's own sport text. */
        fun ofFilterKey(value: String?): SportsLiveSportKind? =
            value?.takeIf { it.startsWith(FILTER_PREFIX) }?.removePrefix(FILTER_PREFIX)?.let { name -> entries.firstOrNull { it.name == name } }

        /**
         * The sport a row may be tagged with when no league is known: its sport word, else its title, genres,
         * description (where addons name the competition: "ICC World Test Championship") or catalog name
         * ("ATP 250 - Stockholm Open" → TENNIS). Null when none names one.
         */
        fun tagOf(item: SportsLiveItem): SportsLiveSportKind? {
            val texts = listOf(item.meta.name) + item.meta.genres + item.meta.description + item.catalogName
            val sport = of(item.sport)
            // A bare "Football" is soccer, unless the card's own text says American football ("NFL", F4).
            if (sport == SOCCER && isBareFootball(item.sport) && texts.any { of(it) == FOOTBALL }) return FOOTBALL
            return sport ?: texts.firstNotNullOfOrNull(::of) ?: fromTeamsOrTitle(item)
        }

        /**
         * The sport a card that names none is of by its teams or its whole title (live.23, TV report #9): two cricket
         * franchises or A sides ([SportsLiveCricket]), two rugby clubs ([SportsLiveRugby]), or a wrestling show's name
         * alone ("Raw", "Dynamite"). Null when none tells: the card's sport stays unknown (sport null or a non-sport tag
         * like "OTHER"), which is the "unknown sport" signal the lists read ([SportsLiveUnidentified.isUntagged]).
         * A card whose sport word, genres or title name a sport with no kind ([namesOtherSport]: "Volleyball", "Netball",
         * "Handball") is that sport: never placed in another by its teams (live.23 review A1).
         */
        fun fromTeamsOrTitle(item: SportsLiveItem): SportsLiveSportKind? = when {
            (listOf(item.sport, item.meta.name) + item.meta.genres).any(::namesOtherSport) -> null
            SportsLiveCricket.isCricketGame(item.teams) -> CRICKET
            SportsLiveRugby.isRugbyGame(item.teams) -> RUGBY
            fold(SportsLiveDisplayText.name(item.meta.name)) in WRESTLING_TITLES -> WRESTLING
            else -> null
        }

        // A weekly wrestling show's name as a whole title: never a word of another sport's title ("Raw" alone).
        private val WRESTLING_TITLES = setOf(
            "raw", "wwe raw", "monday night raw", "dynamite", "aew dynamite", "collision", "aew collision", "rampage", "aew rampage"
        )

        /**
         * The sport whose one name a row's tag uses when [item]'s sport is a plain sport word ("Soccer", "Futbol",
         * "Ice Hockey", "Motorsport"), so one addon's "FOOTBALL" and another's "SOCCER" read the same (F4); null for a
         * league or competition ("NBA", "Premier League"), which stays as written. A bare "Football" is soccer, as most
         * of the world means it (#206), unless the card says American football (a copy tagged so, or its title, genres,
         * description or catalog naming an American football competition) or both its teams are on the loaded NFL or
         * college football list of [directory]; null when the card says both. Until [directory] has the NFL list (cold
         * start, first launch) a bare "Football" the card says nothing more about stays as written (null): "Chiefs vs
         * Bills" never reads SOCCER first (release review). Callers read it again when the directory's revision moves.
         */
        fun plainTagOf(item: SportsLiveItem, directory: TeamDirectory = TeamDirectory.shared): SportsLiveSportKind? {
            val sport = item.sport ?: return null
            if (fold(SportsLiveDisplayText.label(sport) ?: return null) !in PLAIN_SPORT_WORDS) return null
            val kind = of(sport) ?: return null
            if (!isBareFootball(sport)) return kind
            val others = item.alternates.map { it.sport } + item.meta.name + item.meta.genres + item.meta.description + item.catalogName
            val kinds = others.filterNot { it == null || isBareFootball(it) }.mapNotNull(::of)
            return when {
                SOCCER in kinds && FOOTBALL in kinds -> null
                FOOTBALL in kinds || americanTeams(item, directory) -> FOOTBALL
                SOCCER in kinds -> SOCCER
                directory.fetchedAt(EspnLeagueMap.NFL) == null -> null
                else -> SOCCER
            }
        }

        // Both sides on one loaded American football list (#206): "Bears vs Packers" tagged "Football".
        private fun americanTeams(item: SportsLiveItem, directory: TeamDirectory): Boolean {
            val (first, second) = SportsLiveTeams.writtenOrder(item.meta.name) ?: return false
            return AMERICAN_FOOTBALL.any { league -> directory.resolve(league, first) != null && directory.resolve(league, second) != null }
        }

        private val AMERICAN_FOOTBALL = listOf(EspnLeagueMap.NFL, EspnLeagueMap.COLLEGE_FOOTBALL)

        /** True when [text] is a plain sport word alone ("Tennis", "Ice Hockey", "Soccer"): no event of its own. */
        fun isPlainSportWord(text: String?): Boolean =
            SportsLiveDisplayText.label(text)?.let { fold(it) in PLAIN_SPORT_WORDS } == true

        private fun isBareFootball(text: String?): Boolean = text != null && fold(text) == "football"

        private val PLAIN_SPORT_WORDS = setOf(
            "soccer", "football", "futbol", "futebol", "fussball", "american football", "basketball", "hockey", "ice hockey",
            "baseball", "tennis", "cricket", "rugby", "rugby union", "rugby league", "golf", "mma", "boxing", "motorsport",
            "motorsports", "motor sport", "motor sports", "darts", "snooker", "wrestling", "cycling"
        )

        // A promotion or its weekly show ("AEW Dynamite", "WWE Raw"): addons file these under MMA (#145).
        private val WRESTLING_SHOW = Regex("""\b(wwe|aew|nxt|smackdown|raw|dynamite|collision|rampage)\b""")
        private val PROMOTION = Regex("""\b(wwe|aew|nxt|smackdown)\b""")

        /**
         * True when [title] is pro wrestling filed under [sport] (#145): a promotion's name ("AEW", "WWE", "NXT",
         * "SmackDown") under MMA or no known sport, or a weekly show's name ("Raw", "Dynamite", "Collision",
         * "Rampage") under MMA or wrestling only, as those words mean other things elsewhere.
         */
        fun isWrestlingShow(title: String, sport: String?): Boolean =
            when (of(sport)) {
                MMA, WRESTLING -> wrestlingShow(title)
                null -> promotion(title)
                else -> false
            }

        /**
         * The one sport [text] names (#146); null when it names none or several ("Live Sports (Soccer, Tennis,
         * F1)"). "American football" is football alone, not soccer as well.
         */
        fun single(text: String?): SportsLiveSportKind? {
            val folded = fold(text ?: return null).takeIf { it.isNotEmpty() } ?: return null
            val kinds = RULES.filter { (_, regex) -> regex.containsMatchIn(folded) }.mapTo(LinkedHashSet()) { it.first }
            if (FOOTBALL in kinds) kinds -= SOCCER
            return kinds.singleOrNull()
        }

        private val MARKS = Regex("""\p{Mn}+""")

        private fun fold(text: String): String =
            Normalizer.normalize(text, Normalizer.Form.NFD).replace(MARKS, "").lowercase().trim()
    }
}
