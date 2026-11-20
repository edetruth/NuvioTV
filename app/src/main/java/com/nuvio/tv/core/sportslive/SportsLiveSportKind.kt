package com.nuvio.tv.core.sportslive

import androidx.annotation.VisibleForTesting
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
            BASKETBALL to """basketball|\bnba\b|\bncaab\b|\bncaam\b|\bncaaw\b|euroleague|eurocup|\bg league\b|\bwnba\b""",
            HOCKEY to """hockey|\bnhl\b|\bkhl\b""",
            BASEBALL to """baseball|\bmlb\b""",
            TENNIS to """tennis|\batp\b|\bwta\b|\bitf\b""",
            CRICKET to """cricket|\bicc\b|\bt20\b|\bodi\b|\bipl\b|\bw?bbl\b|test match|big bash""",
            RUGBY to """rugby|\bnrl\b|six nations""",
            GOLF to """golf|\bpga\b|\blpga\b|\bliv golf\b""",
            MMA to """\bmma\b|\bufc\b|\bpfl\b|bellator""",
            BOXING to """boxing""",
            MOTORSPORT to """motorsport|motor sport|formula 1|formula one|\bf1\b|motogp|nascar|indycar|grand prix""",
            DARTS to """darts""",
            SNOOKER to """snooker""",
            WRESTLING to """wrestling|\bwwe\b|\baew\b|\bnxt\b|smackdown""",
            CYCLING to """cycling|tour de france""",
            SOCCER to """soccer|football|futbol|futebol|premier league|la liga|bundesliga|serie a|ligue 1|\bmls\b|\bnwsl\b|\buefa\b|champions league"""
        ).map { (kind, pattern) -> kind to Regex(pattern) }

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
        fun tagOf(item: SportsLiveItem): SportsLiveSportKind? =
            (listOf(item.sport, item.meta.name) + item.meta.genres + item.meta.description + item.catalogName).firstNotNullOfOrNull(::of)
                ?: CRICKET.takeIf { SportsLiveCricket.isCricketGame(item.teams) }

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
