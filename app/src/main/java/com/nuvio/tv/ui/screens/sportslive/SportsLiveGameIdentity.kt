package com.nuvio.tv.ui.screens.sportslive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.nuvio.tv.core.sportslive.SportsLiveSportKind
import com.nuvio.tv.core.sportslive.SportsLiveTags
import com.nuvio.tv.core.sportslive.scores.GameArt
import com.nuvio.tv.core.sportslive.scores.GameArtTeam
import com.nuvio.tv.core.sportslive.scores.RankDisplay
import com.nuvio.tv.core.sportslive.scores.ScoreChip
import com.nuvio.tv.core.sportslive.scores.ScoreChips
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.TeamDirectory
import com.nuvio.tv.core.sportslive.scores.TeamLogoBook
import com.nuvio.tv.core.sportslive.scores.TennisMatch
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens.Colors

/*
 * One game identity (live.24, lessons 2026-11-21 "Cross-surface consistency gaps"): every surface that lists games (Live
 * Now and Upcoming rows, Results, Home's Live now row and hero, the in-player panel and table guide, the dual panes and
 * picker, the Your teams strip, the stage) names a game from this one model, so a game reads the same everywhere: the
 * same names (full, short, a code that is never cut), logos, league or sport tag, college rank, followed marks and the
 * score chip (Scores setting, spoiler protection and score delay already applied). Surfaces only choose how much of it
 * fits. Built off the row a surface already holds (stable inputs: remember it by that row, never per frame).
 */

/** One side of a game as every surface names it. */
@Immutable
internal data class GameSideIdentity(
    /** The full name ("Maryland Eastern Shore Hawks"), clean of an addon's leading decoration. */
    val full: String,
    /** ESPN's short name ("Auburn" for "Auburn Tigers"); the full name when none is known. */
    val short: String,
    /** A code that is never cut ("ORL"): ESPN's, else one a team list knows, else one made from the name; a player's last name. */
    val code: String,
    val logo: String?,
    /** The rank rows show before the name ([RankDisplay.onRows]: a ranked college team, "#5 Duke"); null for none. */
    val rank: Int?,
    /** A followed team: the gold ★ beside it. */
    val followed: Boolean
) {
    /** The names a line may take, longest first (full, short, code): a line shows the first that fits, never a cut one. */
    val names: List<String> = listOf(full, short, code).map { it.trim() }.filter { it.isNotEmpty() }.distinct().ifEmpty { listOf(full) }
}

/**
 * A game (or a single-line event, or a channel) as every surface names it: its two [first] / [second] sides in the card's
 * order (null both for a single-line row, which shows [title]); the short league or sport [tag] ("NCAAM", "SOCCER"; ""
 * when none is known); the score [chip] as the Scores setting and spoiler rules allow (null: no score of ours shows).
 */
@Immutable
internal data class GameIdentity(
    val key: String,
    val first: GameSideIdentity?,
    val second: GameSideIdentity?,
    /** A single-line row's clean title ([panelTitleOf]); null for a two-sided game. */
    val title: String?,
    val tag: String,
    val sport: SportsLiveSportKind?,
    val chip: ScoreChip?,
    /** A followed team plays (either side, or the addon's own mark). */
    val followed: Boolean
) {
    val twoSided: Boolean get() = first != null && second != null
}

internal object GameIdentities {

    /** Longest a side's own short form counts as a code (a slim card sets "UCONN" whole beside the other). */
    const val CODE_MAX = 5

    /**
     * The code a known team list gives [name]: a learned ESPN match first ([TeamLogoBook.abbreviationAnywhere]), else
     * our league lists with the city written short or in full ("Los Angeles Clippers" is the NBA's "LAC").
     */
    fun knownCode(name: String): String? =
        TeamLogoBook.shared.abbreviationAnywhere(name)?.trim()?.takeIf { it.isNotEmpty() }
            ?: TeamDirectory.cityVariants(name).firstNotNullOfOrNull { TeamDirectory.shared.abbreviationAnywhere(it)?.trim()?.takeIf(String::isNotEmpty) }

    /**
     * [team]'s code, never cut (J4, B2): its own short form when it is one (ESPN's or a learned abbreviation, at most
     * [CODE_MAX] letters, not its name); else [codeOf] by name; else one made from the name ([panelShortCode]).
     */
    fun code(team: GameArtTeam, codeOf: (String) -> String? = { knownCode(it) }): String {
        val short = team.short.trim()
        if (short.isNotEmpty() && short.length <= CODE_MAX && !short.equals(team.name.trim(), ignoreCase = true)) return short
        val name = panelCleanText(team.name)
        return codeOf(name)?.trim()?.takeIf { it.isNotEmpty() } ?: panelShortCode(name)
    }

    /**
     * [team] as a side: [ranks] says the game's league shows ranks on rows ([GameArt.ranksOnRows]); an [individual]
     * sport's player steps down to the last name ([panelSideChoices]) instead of a code.
     */
    fun side(
        team: GameArtTeam,
        ranks: Boolean,
        followed: Boolean = false,
        individual: Boolean = false,
        codeOf: (String) -> String? = { knownCode(it) }
    ): GameSideIdentity {
        val full = panelCleanText(team.name)
        val short = panelCleanText(team.shortName).ifEmpty { full }
        val sideCode = if (individual) panelSideChoices(full, individual = true).last() else code(team, codeOf)
        return GameSideIdentity(full, short, sideCode, team.logo, team.rank?.takeIf { ranks }, followed)
    }

    /** [team]'s rank as a row shows it ([GameArt.ranksOnRows]: a ranked college team), for a surface naming sides its own way. */
    fun rankOf(team: GameArtTeam, art: GameArt): Int? = team.rank?.takeIf { art.ranksOnRows }

    /**
     * The short tag (Live Now's rule, everywhere): [itemTagOf] (a tennis tour, ESPN's league, the card's sport, else the
     * sport its text tells) in the one shared vocabulary ([SportsLiveTags.short]). [plain] is the sport [item]'s text
     * alone tells ([SportsLiveSportKind.plainTagOf]); [label] words a sport ("Soccer").
     */
    fun tag(
        item: SportsLiveItem,
        art: GameArt?,
        sportTag: SportsLiveSportKind?,
        leagueTag: String?,
        plain: SportsLiveSportKind?,
        label: (SportsLiveSportKind) -> String
    ): String = SportsLiveTags.short(itemTagOf(item, art, sportTag, leagueTag, sportTag?.let(label), plain?.let(label)), sportTag ?: plain)

    /** A live tennis match's tour ("ATP", #160) from [item]'s strict ESPN match in [snapshot]; null otherwise. */
    fun leagueTagOf(item: SportsLiveItem, snapshot: ScoreSnapshot?): String? {
        val event = ScoreChips.matchedEvent(item, snapshot) ?: return null
        return (event.individual as? TennisMatch)?.takeIf { it.live }?.let { event.league.league.uppercase() }
    }

    /**
     * [row]'s identity: Live Now, Upcoming, the panel, the table guide and the More… sheet all hold a [ScoreboardRow]
     * (scores mode, spoiler rules and follows already applied). [plain]: as [tag].
     */
    fun of(
        row: ScoreboardRow,
        label: (SportsLiveSportKind) -> String,
        plain: SportsLiveSportKind?,
        codeOf: (String) -> String? = { knownCode(it) }
    ): GameIdentity {
        val item = row.item
        val sport = row.sportTag ?: plain
        val art = row.art
        val game = row.group != ScoreboardGroup.CHANNELS && row.twoSided && art != null
        val individual = sport in PANEL_INDIVIDUAL_SPORTS
        return GameIdentity(
            key = item.streamKey,
            first = if (game) side(art!!.first, art.ranksOnRows, row.firstFollowed, individual, codeOf) else null,
            second = if (game) side(art!!.second, art.ranksOnRows, row.secondFollowed, individual, codeOf) else null,
            title = if (game) null else panelTitleOf(item),
            tag = tag(item, art, row.sportTag, row.leagueTag, plain, label),
            sport = sport,
            chip = row.chip,
            followed = row.followed || row.favorite
        )
    }

    /**
     * [item]'s identity outside a board (Home's Live now cards and hero, the dual panes and picker): [snapshot] is its
     * score event as the surface may use it (Scores on; the delay already applied by the scores repository, which holds
     * a watched game's score back), [chip] the score that may show ([ScoreChips.of]). [art] defaults to [snapshot]'s
     * drawn teams; a surface that already holds them passes its own.
     */
    fun of(
        item: SportsLiveItem,
        snapshot: ScoreSnapshot?,
        chip: ScoreChip?,
        label: (SportsLiveSportKind) -> String,
        plain: SportsLiveSportKind? = SportsLiveSportKind.plainTagOf(item),
        followsSide: (SportsLiveItem, String) -> Boolean = { _, _ -> false },
        art: GameArt? = GameArt.of(item, snapshot),
        codeOf: (String) -> String? = { knownCode(it) }
    ): GameIdentity {
        val sportTag = SportsLiveSportKind.tagOf(item)
        val sport = sportTag ?: plain
        val matched = ScoreChips.matchedEvent(item, snapshot) != null
        val game = !item.isChannel && art != null && scoreboardTwoSided(art, matched)
        val individual = sport in PANEL_INDIVIDUAL_SPORTS
        val sides = ScoreChips.orderedSides(item)
        val firstFollowed = sides != null && followsSide(item, sides.first)
        val secondFollowed = sides != null && followsSide(item, sides.second)
        return GameIdentity(
            key = item.streamKey,
            first = if (game) side(art!!.first, art.ranksOnRows, firstFollowed, individual, codeOf) else null,
            second = if (game) side(art!!.second, art.ranksOnRows, secondFollowed, individual, codeOf) else null,
            title = if (game) null else panelTitleOf(item),
            tag = tag(item, art, sportTag, leagueTagOf(item, snapshot), plain, label),
            sport = sport,
            chip = chip,
            followed = firstFollowed || secondFollowed || item.followed
        )
    }
}

/** A sport's word ("Soccer") from the app's strings, for a builder run in composition. */
@Composable
internal fun rememberSportWord(): (SportsLiveSportKind) -> String {
    val context = LocalContext.current
    return remember(context) { { kind -> context.getString(sportKindLabelRes(kind)) } }
}

/**
 * [row]'s identity, kept per row instance and team directory revision (the plain sport tag waits for the lists): a row
 * reused across score polls ([reusingRows]) builds it once.
 */
@Composable
internal fun rememberGameIdentity(row: ScoreboardRow): GameIdentity {
    val plain = rememberTextPlainTag(row)
    val label = rememberSportWord()
    return remember(row, plain, label) { GameIdentities.of(row, label, plain) }
}

/**
 * [name] with its college [rank] before it (#137, live.24): a small muted "#5 " at [rankSize] in tabular figures, the
 * Upcoming rows' style, so a line measures and cuts as one text. [name] alone when unranked.
 */
internal fun rankedName(name: String, rank: Int?, rankSize: androidx.compose.ui.unit.TextUnit): AnnotatedString =
    if (rank == null) {
        AnnotatedString(name)
    } else {
        buildAnnotatedString {
            withStyle(SpanStyle(fontSize = rankSize, fontWeight = FontWeight.Medium, color = Colors.TextMuted, fontFeatureSettings = "tnum")) {
                append(RankDisplay.label(rank))
            }
            append(" ")
            append(name)
        }
    }
