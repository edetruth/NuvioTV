package com.nuvio.tv.ui.screens.sportslive

import android.view.KeyEvent
import com.nuvio.tv.core.sportslive.SportsLiveSportKind
import com.nuvio.tv.core.sportslive.SportsLiveTags
import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.core.sportslive.report.SportsLiveReport
import com.nuvio.tv.core.sportslive.scores.MySports
import com.nuvio.tv.core.sportslive.scores.ScoreChip

/** What the full player's OK does with the guide and the panel closed (#218), from [SportsLivePlayerOkKey]. */
internal enum class SportsLivePlayerOk {
    /** Not ours: the player handles the key as it always did. */
    NONE,

    /** Ours, nothing to do now (the press waiting for its release or its hold, a repeat, the release of a hold). */
    CONSUMED,

    /** A short OK with the new guide on: open it. */
    OPEN_GUIDE,

    /** A short OK with the side panel style: the player's own OK (it shows the controls on live), replayed now. */
    SHORT,

    /** OK held to the system's long-press time: the report snapshot is taken now, before anything else draws. */
    HOLD,

    /**
     * The release of a held OK: the player menu opens now. Not at the hold: the menu is a dialog window, which would
     * get the rest of the press and take its release for a click on its first entry (as dual screen's DualHoldKey).
     */
    MENU
}

/**
 * The full player's OK while nothing else wants it (#218): short, it does what it always did (opens the guide, or the
 * player's own OK); held, it opens the player menu (Report a problem first) and never also does the short action.
 * - [menu] (the player menu is offered: a live game playing, the player idle): the press waits. Its first repeat (the
 *   system's long-press time, as Hold OK everywhere on the TV) is [SportsLivePlayerOk.HOLD] and its release
 *   [SportsLivePlayerOk.MENU]; a release before that is the short action ([SportsLivePlayerOk.OPEN_GUIDE] when [guide], else [SportsLivePlayerOk.SHORT]).
 * - Only [guide]: the guide opens on the press, as before (live.21 B2).
 * - Neither: [SportsLivePlayerOk.NONE].
 * The rest of a press taken (repeats, the release), matched by its downTime, is [SportsLivePlayerOk.CONSUMED], so it
 * never reaches the guide or the menu that opened. A repeat or release without its press is NONE.
 */
internal class SportsLivePlayerOkKey {
    private var takenDownTime = NOT_TAKEN
    private var waiting = false
    private var held = false
    private var guideAtPress = false

    fun onKey(keyCode: Int, action: Int, repeatCount: Int, downTime: Long, guide: Boolean, menu: Boolean): SportsLivePlayerOk {
        if (keyCode != KeyEvent.KEYCODE_DPAD_CENTER && keyCode != KeyEvent.KEYCODE_ENTER && keyCode != KeyEvent.KEYCODE_NUMPAD_ENTER) {
            return SportsLivePlayerOk.NONE
        }
        if (action == KeyEvent.ACTION_DOWN && repeatCount == 0) {
            held = false
            guideAtPress = guide
            return when {
                menu -> {
                    takenDownTime = downTime
                    waiting = true
                    SportsLivePlayerOk.CONSUMED
                }
                guide -> {
                    takenDownTime = downTime
                    waiting = false
                    SportsLivePlayerOk.OPEN_GUIDE
                }
                else -> {
                    takenDownTime = NOT_TAKEN
                    waiting = false
                    SportsLivePlayerOk.NONE
                }
            }
        }
        if (takenDownTime == NOT_TAKEN || downTime != takenDownTime) return SportsLivePlayerOk.NONE
        if (action == KeyEvent.ACTION_DOWN) {
            if (!waiting || held) return SportsLivePlayerOk.CONSUMED
            held = true
            return SportsLivePlayerOk.HOLD
        }
        if (action != KeyEvent.ACTION_UP) return SportsLivePlayerOk.CONSUMED
        val short = waiting && !held
        val wasHeld = waiting && held
        takenDownTime = NOT_TAKEN
        waiting = false
        held = false
        return when {
            wasHeld -> SportsLivePlayerOk.MENU
            !short -> SportsLivePlayerOk.CONSUMED
            guideAtPress -> SportsLivePlayerOk.OPEN_GUIDE
            else -> SportsLivePlayerOk.SHORT
        }
    }

    private companion object {
        const val NOT_TAKEN = Long.MIN_VALUE
    }
}

/** The panel's scope for a report (#218): "My sports", "All sports", "My teams" or the sport chip picked. */
internal fun panelReportScope(chip: String?): String = when (chip) {
    null -> "My sports"
    SportsLiveUiState.ALL_SPORTS_FILTER -> "All sports"
    SportsLiveUiState.MY_TEAMS_FILTER -> "My teams"
    else -> chip
}

/** A panel row for a report (#218): its tag (as the row draws it, plain words) and whether its score showed. No names. */
internal fun panelReportRow(board: ScoreboardRow): String {
    val item = board.item
    val tag = SportsLiveTags.short(
        scoreboardRowTag(board.art?.league, item.sport?.takeUnless(SportsLiveTeams::isNonSportTag), item.catalogName),
        SportsLiveSportKind.tagOf(item)
    ).ifEmpty { "no tag" }
    val score = when (val chip = board.chip) {
        is ScoreChip.Live -> if (chip.score != null) SportsLiveReport.SCORE_SHOWN else "no score"
        is ScoreChip.Final -> SportsLiveReport.SCORE_SHOWN
        ScoreChip.Hidden -> "score hidden"
        else -> "no score"
    }
    return "$tag | $score"
}

/** My sports as one report line (#218): "default (every sport)", else each sport with the leagues picked. */
internal fun mySportsReportLine(sports: MySports): String {
    if (sports.isDefault) return "default (every sport)"
    return sports.effectiveSports.joinToString(" · ") { sport ->
        val leagues = sports.namedLeagues(sport)
        val name = sport.name.lowercase()
        if (leagues.isEmpty()) name else "$name: " + leagues.joinToString(", ") { it.name.lowercase() }
    }
}
