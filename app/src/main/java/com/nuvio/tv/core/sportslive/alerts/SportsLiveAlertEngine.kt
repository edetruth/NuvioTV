package com.nuvio.tv.core.sportslive.alerts

import com.nuvio.tv.core.sportslive.scores.CrunchMoment
import com.nuvio.tv.core.sportslive.Memo
import com.nuvio.tv.core.sportslive.SportsLiveFullTimeAnnouncements
import com.nuvio.tv.core.sportslive.SportsLiveItemBucketer
import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.core.sportslive.scores.ScoreSide
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoreState
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.domain.model.SportsLiveItem
import kotlin.math.abs

/** Settings → Sports & Live → Alerts (#42, #44); each on by default. */
data class SportsLiveAlertSettings(
    /** Followed teams' games get the kickoff prompt without "Remind me". */
    val remindMyTeams: Boolean = true,
    /**
     * Score cards while watching something else (Scores = Show only), live.28: one level for every followed team, Big
     * moments by default ([ScoreAlertCadence]).
     */
    val scoreAlertLevel: ScoreAlertLevel = ScoreAlertLevel.DEFAULT,
    /** The full-time toast (names only under Hide until focused; none under Off). */
    val fullTimeAlerts: Boolean = true,
    /** Crunch-time alerts (#137): a close game late, in My sports (no score under Hide until focused; none under Off). */
    val closeGameAlerts: Boolean = true,
    /** "At tip-off" (research Q17): Remind me (a toast, default) or Switch automatically (opt-in, 10 s countdown). */
    val tipOff: com.nuvio.tv.core.sportslive.SportsLiveTipOff = com.nuvio.tv.core.sportslive.SportsLiveTipOff.DEFAULT
)

/** What is on screen now, as far as alerts care. */
data class SportsLiveWatching(
    /** Content ids playing (the full player's, both dual panes', the mini player's): their games never alert. */
    val playingIds: Set<String> = emptySet(),
    /**
     * The games on screen by their two sides and start ([PlayingGame]), so another addon's copy of a game playing, or a
     * merged card led by another source, never alerts either: the same sides within [PlayingGame.WINDOW_MS] of the start
     * (live.28: a UTC-day key missed a game around midnight UTC).
     */
    val playingGameKeys: Set<PlayingGame> = emptySet(),
    /** The ESPN event ids of the games on screen, where known: a card matched to one of them never alerts. */
    val playingEventIds: Set<String> = emptySet(),
    /** The full player is on screen: the kickoff prompt is the small one, and score toasts may show. */
    val inPlayer: Boolean = false,
    /** What the full player plays is Sports & Live content (a game or channel), not a movie or show. */
    val sportsPlayback: Boolean = false,
    /** When the stream on screen started (null: none): nothing shows in its first 10 s. */
    val streamStartedAtMs: Long? = null,
    /**
     * Dual screen is on: prompts wait. Toasts show (#136), over the pane they are not about; neither pane's game
     * alerts ([playingIds] holds both).
     */
    val dual: Boolean = false,
    /** The mini player plays a game: score toasts show over it as over the full player (#136). */
    val miniPlaying: Boolean = false,
    /** The title playing in the full player, for "you're watching ‹title›". */
    val playingTitle: String? = null,
    /**
     * A Sports & Live screen is on (the section, a Game page), not dual screen or the player: no close finish there,
     * the rows already say "Close late" (live.24).
     */
    val sportsLiveScreen: Boolean = false,
    /**
     * The movie or show in the full player is paused (#91; the player says so once it stayed paused a moment): the viewer
     * is free to be asked, so the kickoff card may take OK.
     */
    val moviePaused: Boolean = false
)

/** A game on screen as alerts match it: its two sides, canonical and sorted ([sides]), and its start when known. */
data class PlayingGame(val sides: String, val startMs: Long?) {
    /** [item] (one source of a card) is this game: the same sides, its start within [WINDOW_MS] (either unknown: yes). */
    fun matches(item: SportsLiveItem): Boolean {
        val teams = item.teams ?: return false
        if (SportsLiveAlertEngine.sidesKey(teams.home, teams.away) != sides) return false
        val start = item.startMs
        return startMs == null || start == null || abs(start - startMs) <= WINDOW_MS
    }

    companion object {
        /** One game's listings differ by hours at most (an addon's pre-show start, a time zone slip); a rematch is days away. */
        const val WINDOW_MS = 6 * 60 * 60_000L
    }
}

/** A card the alerts look at: reminded, or a followed team's game, with its delayed ESPN game. */
data class AlertGame(
    val item: SportsLiveItem,
    /** The card's strict ESPN match from the delayed feed; null when none. */
    val snapshot: ScoreSnapshot?,
    /** Reminded by hand, or automatically as a followed team's game ([RemindersState.isReminded]). */
    val reminded: Boolean,
    /** The sides of [snapshot] a followed team is, by ESPN data ([com.nuvio.tv.core.sportslive.scores.TeamFollows]). */
    val followedSides: Set<ScoreSide> = emptySet()
)

/** How a kickoff prompt shows, by what is on screen ([SportsLiveAlertEngine.kickoffStyle]). */
enum class KickoffStyle {
    /** Outside the player: the kickoff card (live.24: OK Watch · Back Close). */
    FULL,

    /**
     * In the player, over a Sports & Live stream or a movie or show: the small kickoff card. Over a Sports & Live stream,
     * or a paused movie, OK switches to the game, after the countdown, the movie's place saved (live.24). Over a movie
     * that plays the card is passive (#91): it takes no key, because OK is the key that pauses.
     */
    COMPACT
}

/** One overlay to show. */
sealed interface SportsLiveAlert {
    val item: SportsLiveItem

    /**
     * "Watch now?" at kickoff (#44, and #42 for followed teams). [again]: the one re-ask after Later.
     * [noStreamYet] (#162): the quiet note after no link came up within 20 minutes, with Remind me in 10 min.
     */
    data class Kickoff(
        override val item: SportsLiveItem,
        val snapshot: ScoreSnapshot?,
        val again: Boolean = false,
        val noStreamYet: Boolean = false,
        /**
         * Over a movie that plays (#91): the card only informs ("Pause to watch it"), takes no key, and is stamped when it
         * shows ([SportsLiveAlertEngine.kickoffPassive]). When it ends the game's one deferred offer is kept.
         */
        val passive: Boolean = false,
        /** The one deferred offer (#91): the viewer paused or left the movie while the game is live. */
        val deferred: Boolean = false
    ) : SportsLiveAlert

    /**
     * A followed team's game changed score (#42). [scorer]: the side that scored; null when both changed.
     * [leadChange]: the other side led before and this one leads now, a big moment (#136). [moment] (live.28): what
     * the card is about ([ScoreAlertCadence]); a lead change has [leadChange] too.
     */
    data class ScoreChange(
        override val item: SportsLiveItem,
        val snapshot: ScoreSnapshot,
        val scorer: ScoreSide?,
        val leadChange: Boolean = false,
        val moment: ScoreMoment = ScoreMoment.SCORE
    ) : SportsLiveAlert

    /**
     * A followed team's game ended (#42). [namesOnly]: Hide until focused, no score. [resultHidden] (user, live.24, review
     * M1): the result is protected ("Hide my teams' results", on by default), so the card says only "‹[team]› game is
     * over · result hidden" and OK plays the replay from the start; never a score, a winner or OT.
     */
    data class FullTime(
        override val item: SportsLiveItem,
        val snapshot: ScoreSnapshot,
        val namesOnly: Boolean,
        val resultHidden: Boolean = false,
        val team: String? = null
    ) : SportsLiveAlert

    /**
     * Crunch time (#137): a close game late, in My sports, whoever plays ([CrunchTime]). [namesOnly]: Hide until
     * focused, "Close finish: ND vs AUB, final minute" with no score.
     */
    data class Crunch(
        override val item: SportsLiveItem,
        val snapshot: ScoreSnapshot,
        val moment: CrunchMoment,
        val namesOnly: Boolean
    ) : SportsLiveAlert
}

/**
 * Decides, from the delayed snapshots alone, which alerts are due (#42, #44). Main-thread or otherwise
 * serialised use only.
 *
 * - Kickoff: a reminded game whose ESPN state turns live (from pre, or within [KICKOFF_WINDOW_MS] of
 *   the start), or, with no ESPN game, whose timed start has just passed. Asked once; [later] asks once
 *   more after [LATER_MS]. Never for the game playing (it counts as answered).
 * - Score change: a followed side's game, Scores = Show, while a game plays (the full player, dual screen or the
 *   mini player, #136), not the game playing nor one whose score is blanked as watched; when the level and sport
 *   call it news ([ScoreAlertCadence], live.28). Never under Hide until focused (a "scored" toast already
 *   tells part of the score) or Off.
 * - Full time: a followed side's game seen live, then final: the score under Show, names only under
 *   Hide until focused, nothing under Off.
 * - Nothing in a stream's first [STREAM_SETTLE_MS] (prompts wait; toasts are dropped), and only a game
 *   seen before changes is announced (a first look never alerts).
 */
class SportsLiveAlertEngine {
    private class Prompt(
        var shown: Boolean = false,
        var laterAt: Long? = null,
        var laterUsed: Boolean = false,
        var done: Boolean = false,
        /** A passive card ran its time over a movie (#91): one actionable offer waits for the viewer to be free. */
        var deferred: Boolean = false
    )

    private class Seen(val state: ScoreState, val home: Int?, val away: Int?)

    /** By [gameKey], so a merged card whose primary source changes is still one game. */
    private val prompts = HashMap<String, Prompt>()

    /** By ESPN event id: what was last seen. */
    private val seen = HashMap<String, Seen>()
    private val sawPregame = HashSet<String>()
    private val cadence = ScoreAlertCadence()
    private val finals = HashSet<String>()

    fun update(
        nowMs: Long,
        games: List<AlertGame>,
        settings: SportsLiveAlertSettings,
        mode: ScoresMode,
        watching: SportsLiveWatching
    ): List<SportsLiveAlert> {
        trim()
        val settling = watching.streamStartedAtMs?.let { nowMs - it < STREAM_SETTLE_MS } == true
        val alerts = ArrayList<SportsLiveAlert>()
        for (game in games.distinctBy { gameKey(it.item) }) {
            val playing = isPlaying(game.item, watching, game.snapshot)
            if (game.reminded) kickoff(game, nowMs, playing, holding = settling || watching.dual, watching = watching)?.let(alerts::add)
            // A score kept from before the app closed (live.23) is old: no change is measured from it.
            val event = game.snapshot?.takeIf { !it.kept } ?: continue
            val before = seen[event.eventId]
            seen[event.eventId] = Seen(event.state, event.homeScore, event.awayScore)
            if (event.state == ScoreState.PRE) sawPregame += event.eventId
            if (game.followedSides.isEmpty()) continue
            if (playing) {
                // On screen (or blanked as watched): what it does meanwhile is never announced afterwards.
                cadence.forget(event.eventId)
                continue
            }
            scoreChange(game, event, nowMs, settings, mode, watching, settling)?.let(alerts::add)
            if (before == null || settling) continue
            fullTime(game, event, before, settings, mode)?.let(alerts::add)
        }
        return alerts
    }

    /** Later (or the prompt closed by itself): asked once more after [LATER_MS]; the second time, done. */
    fun later(item: SportsLiveItem, nowMs: Long) {
        val prompt = prompts.getOrPut(gameKey(item)) { Prompt(shown = true) }
        if (prompt.laterUsed) {
            prompt.done = true
        } else {
            prompt.laterUsed = true
            prompt.laterAt = nowMs + LATER_MS
        }
    }

    /** Watch, or Back on the prompt: not asked again. */
    fun answered(item: SportsLiveItem) {
        prompts.getOrPut(gameKey(item)) { Prompt() }.done = true
    }

    /**
     * A passive kickoff card ran out over a playing movie (#91): the game is not answered; its one offer waits until the
     * viewer pauses or leaves the movie while the game is still live ([kickoff]).
     */
    fun deferOffer(item: SportsLiveItem) {
        val prompt = prompts.getOrPut(gameKey(item)) { Prompt(shown = true) }
        if (!prompt.done) prompt.deferred = true
    }

    private fun kickoff(game: AlertGame, nowMs: Long, playing: Boolean, holding: Boolean, watching: SportsLiveWatching): SportsLiveAlert.Kickoff? {
        val prompt = prompts.getOrPut(gameKey(game.item)) { Prompt() }
        if (prompt.done) return null
        val event = game.snapshot
        // Already watching it, or it is over: nothing to ask.
        if (playing || event?.state == ScoreState.POST || event?.state == ScoreState.OFF) {
            prompt.done = true
            return null
        }
        if (holding) return null
        if (prompt.deferred) {
            // The deferred offer (#91): once, while the game is listed live and not over, when the viewer is free. It is not
            // held for a link again and not bound to the 20 minute window, so pausing 40 minutes into a movie still offers it.
            if (!liveNow(game, nowMs) || !viewerFree(watching)) return null
            prompt.deferred = false
            return SportsLiveAlert.Kickoff(game.item, event, again = true, deferred = true)
        }
        val laterAt = prompt.laterAt
        if (laterAt != null) {
            if (nowMs < laterAt) return null
            prompt.laterAt = null
            return SportsLiveAlert.Kickoff(game.item, event, again = true)
        }
        if (prompt.shown || !kickedOff(game, nowMs)) return null
        prompt.shown = true
        return SportsLiveAlert.Kickoff(game.item, event)
    }

    /**
     * The game is live now: ESPN says it is on; with no ESPN game (Scores Off, or a league ESPN does not cover), a game
     * with a timed start is live for the section's live window from it (review R3: the listing was read at load, before
     * kick-off or hours ago, and the alerts do not reload it), and an untimed one as it is listed.
     */
    private fun liveNow(game: AlertGame, nowMs: Long): Boolean = when (game.snapshot?.state) {
        ScoreState.IN -> true
        null -> {
            val listed = SportsLiveItemBucketer.reclock(game.item, nowMs)
            val start = listed.startMs?.takeIf { listed.startHasTimeOfDay }
            if (start != null) SportsLiveItemBucketer.liveByClockAt(start, nowMs) else listed.isLive
        }
        else -> false
    }

    private fun kickedOff(game: AlertGame, nowMs: Long): Boolean {
        val event = game.snapshot
        val start = event?.takeIf { it.timeValid }?.startMs ?: game.item.startMs?.takeIf { game.item.startHasTimeOfDay }
        return when (event?.state) {
            // ESPN says it is on: seen before kick-off, or just started (not a game long under way).
            ScoreState.IN -> event.eventId in sawPregame || start == null || nowMs - start <= KICKOFF_WINDOW_MS
            null -> start != null && nowMs >= start && nowMs - start <= KICKOFF_WINDOW_MS
            // Pre-game (ESPN's start may slip past the listed one), or anything else: wait.
            else -> false
        }
    }

    /**
     * The score card due for a followed side's [event], if any ([ScoreAlertCadence]). Every snapshot is shown to the
     * cadence so it knows the game's course; a card may come only while a game plays elsewhere (the full player, dual
     * screen or the mini player), under Show, unprotected, past a stream's first seconds ([settling]).
     */
    private fun scoreChange(
        game: AlertGame,
        event: ScoreSnapshot,
        nowMs: Long,
        settings: SportsLiveAlertSettings,
        mode: ScoresMode,
        watching: SportsLiveWatching,
        settling: Boolean
    ): SportsLiveAlert.ScoreChange? {
        val allowed = mode == ScoresMode.SHOW && !event.spoilerProtected && !settling &&
            (watching.inPlayer || watching.dual || watching.miniPlaying)
        val call = cadence.observe(event, nowMs, settings.scoreAlertLevel, allowed) ?: return null
        return SportsLiveAlert.ScoreChange(
            game.item, event, call.scorer, leadChange = call.moment == ScoreMoment.LEAD_CHANGE, moment = call.moment
        )
    }

    private fun fullTime(
        game: AlertGame,
        event: ScoreSnapshot,
        before: Seen,
        settings: SportsLiveAlertSettings,
        mode: ScoresMode
    ): SportsLiveAlert.FullTime? {
        if (before.state != ScoreState.IN || event.state != ScoreState.POST || !event.isFinal) return null
        if (!finals.add(event.eventId)) return null
        if (!settings.fullTimeAlerts || mode == ScoresMode.OFF) return null
        // A spoiler-protected result (#151, the default for my teams): the spoiler-free card (review M1), never silence.
        if (event.spoilerProtected) {
            val side = game.followedSides.firstOrNull()?.let { if (it == ScoreSide.HOME) event.home else event.away }
            val team = side?.let { listOf(it.shortDisplayName, it.displayName, it.name).firstOrNull { name -> !name.isNullOrBlank() } }
            return SportsLiveAlert.FullTime(game.item, event, namesOnly = true, resultHidden = true, team = team)
        }
        return SportsLiveAlert.FullTime(game.item, event, namesOnly = mode == ScoresMode.HIDE_UNTIL_FOCUSED)
    }

    private fun trim() {
        if (prompts.size > MAX_REMEMBERED) prompts.clear()
        if (seen.size > MAX_REMEMBERED) seen.clear()
        if (sawPregame.size > MAX_REMEMBERED) sawPregame.clear()
        if (finals.size > MAX_REMEMBERED) finals.clear()
    }

    companion object {
        /** "Later" asks again this much later, once (decision 2). */
        const val LATER_MS = 5 * 60_000L

        /** Every score: at most one score toast per game this often (decision 4). */
        const val SCORE_ALERT_GAP_MS = ScoreAlertCadence.EVERY_SCORE_GAP_MS

        /** A game first seen live this long after its start is not "kicking off" any more. */
        const val KICKOFF_WINDOW_MS = 20 * 60_000L

        /** Nothing shows in a stream's first seconds (the design's 10 s). */
        const val STREAM_SETTLE_MS = 10_000L

        private const val MAX_REMEMBERED = 500

        private const val DAY_MS = 24 * 60 * 60_000L

        /**
         * One game, whichever addon lists it or leads a merged card: its two sides (canonical, sorted)
         * and its start's UTC day; a card naming no game keeps its own key.
         */
        fun gameKey(item: SportsLiveItem): String {
            val teams = item.teams ?: return item.streamKey
            return "${sideKeys(teams.home to teams.away)}|${item.startMs?.let { it / DAY_MS }}"
        }

        /** [home] and [away], canonical and sorted, joined: one game's sides whichever way an addon lists them. */
        fun sidesKey(home: String, away: String): String = sideKeys(home to away)

        /** The two sides, canonical and sorted, joined: remembered (#152), as cards and alerts ask for it often. */
        private val sideKeys = Memo(2_048) { (home, away): Pair<String, String> ->
            listOf(SportsLiveTeams.canonicalise(home), SportsLiveTeams.canonicalise(away)).sorted().joinToString("|")
        }

        /** Full outside the player, small in it (over a sports stream, a movie or a show). */
        /** A movie or show plays in the full player (not Sports & Live content). */
        fun overMovie(watching: SportsLiveWatching): Boolean = watching.inPlayer && !watching.sportsPlayback

        /**
         * #91: [alert] shows passive when it would take OK from a movie that plays: no keys, "Pause to watch it". Not for
         * the deferred offer, nor the no-stream note; not over a paused movie, a Sports & Live stream or any other screen.
         */
        fun kickoffPassive(alert: SportsLiveAlert.Kickoff, watching: SportsLiveWatching): Boolean =
            !alert.deferred && !alert.noStreamYet && moviePlays(watching)

        /** A movie or show plays in the full player, not paused: OK is the viewer's pause key (#91). */
        fun moviePlays(watching: SportsLiveWatching): Boolean = overMovie(watching) && !watching.moviePaused

        /** The viewer is free to be asked about a game: they left the movie, or paused it (#91). */
        fun viewerFree(watching: SportsLiveWatching): Boolean = !overMovie(watching) || watching.moviePaused

        fun kickoffStyle(watching: SportsLiveWatching): KickoffStyle =
            if (watching.inPlayer) KickoffStyle.COMPACT else KickoffStyle.FULL

        /**
         * [alert] as it may show now, under the current [mode] and [settings] (it may have waited in the
         * queue while they changed): a score toast only under Show; a full-time toast names only under
         * Hide until focused and none under Off; null when it may not show at all.
         */
        fun stillShowable(alert: SportsLiveAlert, mode: ScoresMode, settings: SportsLiveAlertSettings): SportsLiveAlert? = when (alert) {
            is SportsLiveAlert.Kickoff -> alert
            is SportsLiveAlert.ScoreChange -> alert.takeIf { settings.scoreAlertLevel != ScoreAlertLevel.OFF && mode == ScoresMode.SHOW }
            is SportsLiveAlert.FullTime -> when {
                !settings.fullTimeAlerts || mode == ScoresMode.OFF -> null
                alert.resultHidden -> alert
                mode == ScoresMode.HIDE_UNTIL_FOCUSED -> alert.copy(namesOnly = true)
                else -> alert
            }
            is SportsLiveAlert.Crunch -> when {
                !settings.closeGameAlerts || mode == ScoresMode.OFF -> null
                mode == ScoresMode.HIDE_UNTIL_FOCUSED -> alert.copy(namesOnly = true)
                else -> alert
            }
        }

        /**
         * Whether [alert] may show now, claiming its game's full time when it is a full-time toast: one
         * game's end is announced once, by this toast or by the one for the game playing (#60), whichever
         * claims its ESPN event id first ([SportsLiveFullTimeAnnouncements]). Other alerts always may.
         */
        fun claimToShow(alert: SportsLiveAlert): Boolean =
            alert !is SportsLiveAlert.FullTime || SportsLiveFullTimeAnnouncements.claim(alert.snapshot.eventId)

        /**
         * True when [item]'s game is on screen now (the full player, either dual pane, the mini player): by card
         * (it or one of its sources), by game identity ([PlayingGame]: its two sides near its start, whichever addon
         * lists it), by its ESPN event ([snapshot]), or because the scores blank [snapshot] as a game being watched
         * ([ScoreSnapshot.watched], live.28: that holds before the player learns the event id). Never by title.
         */
        fun isPlaying(item: SportsLiveItem, watching: SportsLiveWatching, snapshot: ScoreSnapshot? = null): Boolean {
            if (snapshot?.watched == true) return true
            if (watching.playingIds.isNotEmpty() && (listOf(item) + item.alternates).any { it.id in watching.playingIds }) return true
            if (item.teams != null && watching.playingGameKeys.isNotEmpty() &&
                (listOf(item) + item.alternates).any { source -> watching.playingGameKeys.any { it.matches(source) } }
            ) {
                return true
            }
            return snapshot != null && snapshot.eventId in watching.playingEventIds
        }

        /** What [games] on screen are, for [SportsLiveWatching] ([eventIdOf]: a game's ESPN event, where known). */
        fun watchingKeys(games: List<SportsLiveItem>, eventIdOf: (SportsLiveItem) -> String?): Triple<Set<String>, Set<PlayingGame>, Set<String>> {
            val sources = games.flatMap { listOf(it) + it.alternates }
            return Triple(
                sources.mapTo(HashSet()) { it.id },
                sources.mapNotNullTo(HashSet()) { source -> source.teams?.let { PlayingGame(sidesKey(it.home, it.away), source.startMs) } },
                games.mapNotNullTo(HashSet(), eventIdOf)
            )
        }

        /** The ESPN game an alert is about, where it has one. */
        fun snapshotOf(alert: SportsLiveAlert): ScoreSnapshot? = when (alert) {
            is SportsLiveAlert.Kickoff -> alert.snapshot
            is SportsLiveAlert.ScoreChange -> alert.snapshot
            is SportsLiveAlert.FullTime -> alert.snapshot
            is SportsLiveAlert.Crunch -> alert.snapshot
        }
    }
}

/**
 * The full player's pause as the alerts hear it (#91, review R1): [paused] (and stayed so a moment) is a pause; it ends only
 * when the video plays again ([playing]). A seek's buffering while paused is neither, so the pause holds through it and an
 * asking kickoff card is not closed by it.
 */
internal fun sportsLivePausedNow(paused: Boolean, playing: Boolean, wasPaused: Boolean): Boolean = paused || (wasPaused && !playing)
