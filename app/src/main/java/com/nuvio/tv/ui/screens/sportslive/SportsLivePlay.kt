package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.displayName
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.debrid.DirectDebridResolver
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sportslive.SportsLiveOpenGame
import com.nuvio.tv.core.sportslive.SportsLivePlayingGame
import com.nuvio.tv.core.sportslive.SportsLiveSpeedClock
import com.nuvio.tv.core.sportslive.SportsLiveWorkingLinks
import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.core.sportslive.scores.SpoilerProtection
import com.nuvio.tv.core.sportslive.scores.SportsResults
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.SportsLivePreferencesDataStore
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.domain.repository.SportsLiveRepository
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.ui.screens.player.SportsLiveLaunchHeaders
import com.nuvio.tv.ui.screens.sportslive.mini.SportsLiveMiniPlayerController
import com.nuvio.tv.ui.screens.sportslive.mini.SportsLiveMiniPlayers
import com.nuvio.tv.ui.screens.sportslive.mini.SportsLiveMiniState
import com.nuvio.tv.ui.screens.stream.streamPlaybackInfoFor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What OK on a Sports & Live card does. */
enum class SportsLivePlayAction {
    /** The item's Stream screen, as upstream (a single-source card the user picks for, or one not live). */
    STREAM_SCREEN,

    /** The best stream (across a merged card's sources) plays at once, as a zap picks it. */
    PLAY_BEST,

    /** A merged card the user picks for: every source's streams. */
    PICK
}

/**
 * Whether OK on [item] may play its best stream at once (#56): a live game or a channel. Replays
 * and games not started yet keep their behaviour.
 */
fun sportsLiveAutoPlayEligible(item: SportsLiveItem): Boolean =
    item.kind != SportsLiveKind.REPLAY && (item.isLive || item.isChannel)

/**
 * What OK on [item] does. A live game or channel follows [openGame] ("When I open a game"), unless
 * the user asked to [choose] ("Choose stream"); any other card keeps its pre-#56 behaviour, where a
 * merged card follows the player's autoplay [mode].
 */
fun sportsLivePlayAction(
    item: SportsLiveItem,
    mode: StreamAutoPlayMode,
    openGame: SportsLiveOpenGame = SportsLiveOpenGame.PLAY_BEST,
    choose: Boolean = false
): SportsLivePlayAction {
    val eligible = sportsLiveAutoPlayEligible(item)
    val userPicks = choose || (eligible && openGame == SportsLiveOpenGame.LET_ME_CHOOSE)
    return when {
        userPicks -> if (item.alternates.isEmpty()) SportsLivePlayAction.STREAM_SCREEN else SportsLivePlayAction.PICK
        eligible -> SportsLivePlayAction.PLAY_BEST
        item.alternates.isEmpty() -> SportsLivePlayAction.STREAM_SCREEN
        mode == StreamAutoPlayMode.MANUAL -> SportsLivePlayAction.PICK
        else -> SportsLivePlayAction.PLAY_BEST
    }
}

/** True when [stream] needs the Stream screen's preparation (server, YouTube or debrid resolve). */
internal suspend fun sportsLiveNeedsPreparation(stream: Stream, directDebridResolver: DirectDebridResolver): Boolean =
    stream.serverTarget != null ||
        stream.youTubeIdToResolve() != null ||
        directDebridResolver.shouldResolveToPlayableStream(stream)

/**
 * Where playing [stream] for [item] goes: the Player route, or (null playback) the item's Stream
 * screen when the stream needs that screen's preparation (server, YouTube or debrid resolve).
 */
internal suspend fun sportsLiveZapTarget(
    item: SportsLiveItem,
    stream: Stream,
    profileId: Int,
    returnToHomeOnBack: Boolean,
    directDebridResolver: DirectDebridResolver,
    autoPicked: Boolean = false
): SportsLiveZapTarget {
    val playback = if (sportsLiveNeedsPreparation(stream, directDebridResolver)) {
        null
    } else {
        // Same mapping as the Stream screen's getStreamForPlayback, with the item as the content.
        streamPlaybackInfoFor(
            stream = stream,
            title = item.displayName,
            year = null,
            contentId = item.id,
            contentType = item.apiType,
            contentName = item.meta.name,
            poster = item.meta.poster,
            backdrop = item.meta.backdropUrl,
            logo = item.meta.logo,
            videoId = item.id,
            season = null,
            episode = null,
            episodeTitle = null,
            profileId = profileId,
            contentLanguage = null
        ).takeIf { it.url != null || it.isTorrent }
    }
    // Scope the full player's auto-failover and recovery overlay to this sports game/channel.
    SportsLiveExtraSources.markSportsPlayback(item.id, item)
    // The player finds this exact stream again once it plays, for dual screen to take over (#65).
    SportsLiveWorkingLinks.shared.noteLaunch(item.id, stream)
    // The full-player launch carries only request headers; stash the response headers so the player
    // seeds them at init and plays with the same proxy headers as the details -> play / preview paths.
    playback?.url?.let { url ->
        SportsLiveLaunchHeaders.set(url, stream.behaviorHints?.proxyHeaders?.response.orEmpty())
    }
    return SportsLiveZapTarget(item, playback, returnToHomeOnBack, autoPicked)
}

/**
 * L (live.25): Watch or OK on the game the mini player plays (it, or another addon's copy of it:
 * [SportsLivePlayingGame.isSameGame], as J5) takes the mini player back to full screen
 * ([SportsLiveMiniPlayerController.requestExpand]): no lookup, and its link plays on, handed to the full player. With
 * [stream] (one the user picked), only the link the mini player plays, while it plays, does so; another of the game's
 * streams opens as usual. True when the mini player expands.
 */
internal fun sportsLiveBackToMiniGame(
    item: SportsLiveItem,
    stream: Stream? = null,
    mini: SportsLiveMiniPlayerController = SportsLiveMiniPlayers.controller
): Boolean {
    val playing = mini.state.value as? SportsLiveMiniState.Playing ?: return false
    if (!SportsLivePlayingGame.isSameGame(item, playing.item)) return false
    if (stream != null && (stream.getStreamUrl() != playing.stream.getStreamUrl() || !mini.isLinkPlaying(playing))) return false
    return mini.requestExpand()
}

sealed interface SportsLivePlayState {
    data object Idle : SportsLivePlayState
    data class Resolving(val item: SportsLiveItem) : SportsLivePlayState
    /** [spoilerSafe]: a replay whose result is hidden (#223), its [streams] named with any score masked. */
    data class Picking(val item: SportsLiveItem, val streams: List<Stream>, val spoilerSafe: Boolean = false) : SportsLivePlayState

    /** A catch-up replay's streams are looked up (#58); [title] is ESPN's matchup, never the replay's name. */
    data class ResolvingReplay(val title: String) : SportsLivePlayState

    /** A catch-up replay offered by several sources: [sources] (replay to its best stream), named by source only. */
    data class PickingReplaySource(val title: String, val sources: List<Pair<SportsLiveItem, Stream>>) : SportsLivePlayState
}

/**
 * [replay] as a catch-up item plays it (#58): named [title] (ESPN's matchup), without the addon's name,
 * description or artwork, any of which may carry the score, so the player shows none of them.
 */
internal fun spoilerSafeReplay(replay: SportsLiveItem, title: String): SportsLiveItem = replay.copy(
    meta = replay.meta.copy(
        name = title,
        description = null,
        releaseInfo = null,
        poster = null,
        background = null,
        landscapePoster = null,
        logo = null,
        rawPosterUrl = null
    )
)

/**
 * [item] as Choose stream or OK on its card hands it on (#223): a replay whose result is [hidden]
 * ([sportsLiveHidesReplayLinks]) as Watch replay plays it ([spoilerSafeReplay]), named [title] (ESPN's matchup), so the
 * Stream screen's header and the player show no addon title or artwork; with its result shown, as is. A fallback title
 * (the card's own, when no ESPN game matches) has any score in it masked too.
 */
internal fun sportsLiveCardToPlay(item: SportsLiveItem, hidden: Boolean, title: () -> String): SportsLiveItem =
    if (hidden) spoilerSafeReplay(item, hiddenReplayText(title()).orEmpty()) else item

/**
 * How a game that went past normal time is tagged in brackets: "AET", "a.e.t.", "pens", "on penalties", "p", "OT", "2OT",
 * "SO".
 */
private const val EXTRA_TIME_TAG =
    """(?:a\.?e\.?t\.?|after\s+extra\s+time|(?:on\s+)?pens?\.?|(?:on\s+)?penalties|p|\d*\s?o\.?t\.?|s\.?o\.?)"""

/** The same tags standing alone after a score ("1-1 aet", "3-2 OT", "2OT"): no single letter, OT and SO in capitals only. */
private const val BARE_EXTRA_TIME_TAG =
    """(?:a\.?e\.?t\.?|after\s+extra\s+time|(?:on\s+)?pens?\.?|(?:on\s+)?penalties|(?-i:\d*OT|SO))"""

/**
 * Inside the brackets after a score: a shoot-out's score and tag either way round ("4-2 pens", "pens 4-2"), or tags
 * before it ("AET, 4-2 pens", "aet, pens 4-2").
 */
private const val SCORE_ENDING_IN_BRACKETS =
    """(?:\d{1,2}\s*[-–—:]\s*\d{1,2}\s*,?\s*$EXTRA_TIME_TAG?|$EXTRA_TIME_TAG(?:\s*,?\s*$EXTRA_TIME_TAG)*(?:\s*,?\s*\d{1,2}\s*[-–—:]\s*\d{1,2}(?:\s*$EXTRA_TIME_TAG)?)?)"""

/**
 * What follows a score and tells how it ended (#244, F6): a shoot-out's score and tag ("(4-2 pens)", "[AET]"), or a tag
 * alone ("aet", "OT"). Masked with the score: "pens" or "AET" beside a hidden score still says it was a draw. A tag
 * closing a bracket is left to [LOOSE_EXTRA_TIME_TAG] with its bracket (S2: "(4-2 pens)" after the team names must not
 * leave "(–)").
 */
private const val SCORE_ENDING =
    """(?:\s*[(\[]\s*$SCORE_ENDING_IN_BRACKETS\s*[)\]]|\s+$BARE_EXTRA_TIME_TAG(?!\w|\.?\s*[)\]]))*"""

/** A shoot-out's goals in brackets beside a side's score: the "(4)" of "1 (4) - (2) 1" or "1 (4) - 1 (2)". */
private const val PENALTY_GOALS = """[(\[]\s*\d{1,2}\s*[)\]]"""

/**
 * A score in a link's words: "2-1", "102 - 99", "3–2", "1:0", "1 (4) - (2) 1", "1 (4) - 1 (2)", with how it ended
 * ([SCORE_ENDING]); not part of a date ("2026-11-22", "22-11-2026") or a picture's aspect ratio ("16:9").
 */
private val SCORE_IN_LINK_TEXT = Regex(
    """(?<!\d[-–—:/.])\b(?!(?:16|21|32):9\b|9:16\b)\d{1,3}(?:\s*$PENALTY_GOALS)?\s*[-–—:]\s*(?:$PENALTY_GOALS\s*)?\d{1,3}\b(?:\s*$PENALTY_GOALS)?(?![-–—:/.]\d)$SCORE_ENDING""",
    RegexOption.IGNORE_CASE
)

/**
 * An extra-time tag away from the score, once a score was masked (F6, F6-1): "Arsenal 1-1 Chelsea (AET)", "(pens 4-2)" after
 * the team names (its score already masked to "–"), "Leafs 3-2 Bruins SO 1080p", "… on penalties". In brackets any tags
 * with masked scores ("(AET, –)"), but at least one tag, so a masked series score "(–)" stays; bare, the same tags as
 * beside a score (no single letter; OT and SO in capitals only), so words stay.
 */
private val LOOSE_EXTRA_TIME_TAG = Regex(
    """\s*[(\[](?=[^)\]]*[a-z])\s*(?:–|$EXTRA_TIME_TAG)(?:\s*,?\s*(?:–|$EXTRA_TIME_TAG))*\s*[)\]]|\s+$BARE_EXTRA_TIME_TAG(?!\w)""",
    RegexOption.IGNORE_CASE
)

/** A score split by "vs" between the team names: "Team A 2 vs 1 Team B", "2 v 1" (a "vs" alone stays). */
private val SPLIT_SCORE_IN_LINK_TEXT =
    Regex("""(?<![\d.])\b\d{1,3}\s+vs?\.?\s+\d{1,3}\b(?![-–—:/.]\d)$SCORE_ENDING""", RegexOption.IGNORE_CASE)

/**
 * [text] with any score in it masked ("Arsenal 2-1 Chelsea highlights" → "Arsenal – Chelsea highlights"): live.26 shorter
 * paths (#223, review gap 1), a replay's link names often carry its result.
 */
internal fun spoilerSafeLinkText(text: String?): String? {
    text ?: return null
    val masked = text.replace(SPLIT_SCORE_IN_LINK_TEXT, "–").replace(SCORE_IN_LINK_TEXT, "–")
    return if (masked == text) text else masked.replace(LOOSE_EXTRA_TIME_TAG, "")
}

/**
 * [text], a hidden replay's name or link name, as its lists, Detail page and player show it (#467): any score masked
 * ([spoilerSafeLinkText]), and also two numbers standing alone in one name (a score split by a team name: "Arsenal 2
 * Chelsea 1", "(2) v (1)", "W 3, L 2") and words that say who won ("Arsenal win"). Over-masking a hidden replay's
 * name costs little; a live game's link names ([sportsLiveLinkLabel]) keep their channel numbers.
 */
internal fun hiddenReplayText(text: String?): String? {
    val scored = spoilerSafeLinkText(text) ?: return null
    val numbers = if (LONE_NUMBER.findAll(scored).take(2).count() < 2) scored else scored.replace(LONE_NUMBER, "–")
    return numbers.replace(RESULT_WORD, "–")
}

/** A number of up to three digits standing alone: not part of a word, a date, a time, a ratio, a codec or a quality. */
private val LONE_NUMBER = Regex("""(?<![\w.:/%+–—-])\d{1,3}(?![\w.:/%+–—-])""")

/** A word that says who won (#467): "Arsenal win", "Arsenal beat Chelsea", "Chelsea lost". */
private val RESULT_WORD = Regex(
    """\b(?:wins?|won|beats?|beaten|defeats?|defeated|edges?|edged|thrash(?:es|ed)?|crush(?:es|ed)?|routs?|routed|loses?|lost|draws?|drew|victory|triumphs?|triumphed)\b""",
    RegexOption.IGNORE_CASE
)

/**
 * Whether [item]'s links are named with any score masked where the user lists or plays them outside Watch replay
 * (Choose stream, a merged replay's picker, OK on a replay card; #223): when its result is hidden exactly as its Results
 * row hides it ([resultHiddenReason], #241: the Scores setting [mode] (null: not read yet), Results' [revealed] event ids,
 * [protection]), for [match], its strict ESPN game. With no ESPN game nothing can reveal it: hidden unless Show, and under
 * Show when it is a saved game's replay (by its sides) or a followed team's under my teams' protection. Under Show with
 * nothing hidden, names stay as the addon gives them; anything not a [replay] keeps them too.
 */
internal fun sportsLiveHidesReplayLinks(
    item: SportsLiveItem,
    replay: Boolean,
    mode: ScoresMode?,
    match: ScoreSnapshot?,
    revealed: Set<String>,
    protection: SpoilerProtection
): Boolean {
    if (!replay) return false
    mode ?: return true
    // A team folder holds many games: no one ESPN match or reveal speaks for the rest (F1).
    if (item.apiType == "series") return sportsLiveReplayFolderHides(mode, protection)
    if (protection.savedReplayKeys(listOf(item), match?.let { mapOf(item.streamKey to it) }.orEmpty()).isNotEmpty()) return true
    if (match != null) {
        val followed = protection.followed.any { SportsResults.follows(it, match) }
        return resultHiddenReason(match, followed, mode, revealed, protection) != null
    }
    val followed = item.followed || protection.followed.any { SportsLiveTeams.follows(it, item) }
    return mode != ScoresMode.SHOW || (followed && (protection.myTeams || protection.myTeamsResults))
}

/**
 * Whether a replay team folder (a series of replays, F1) is shown masked: it holds many games, none matched to one ESPN
 * game, so it is masked whenever any game in it could be hidden: the Scores setting [mode] other than Show (null: not
 * read yet), a game saved for later, or a followed team under my teams' protection.
 */
internal fun sportsLiveReplayFolderHides(mode: ScoresMode?, protection: SpoilerProtection): Boolean =
    mode != ScoresMode.SHOW || protection.saved.isNotEmpty() ||
        (protection.followed.isNotEmpty() && (protection.myTeams || protection.myTeamsResults))

/** [stream] of a replay as the player names it: its name, title and description with any score masked. */
internal fun spoilerSafeReplayStream(stream: Stream): Stream = stream.copy(
    name = hiddenReplayText(stream.name),
    title = hiddenReplayText(stream.title),
    description = hiddenReplayText(stream.description)
)

sealed interface SportsLivePlayEvent {
    /**
     * [item]'s Stream screen. [manualSelection]: the user picks there (no autoplay), as asked or as
     * the fallback; [noStreamFound]: the best-stream lookup found nothing, so a short message shows.
     */
    data class OpenStreamScreen(
        val item: SportsLiveItem,
        val manualSelection: Boolean = false,
        val noStreamFound: Boolean = false
    ) : SportsLivePlayEvent

    /** [source]: the provider an auto-picked stream plays from, announced briefly; null announces nothing. */
    data class Play(val target: SportsLiveZapTarget, val source: String? = null) : SportsLivePlayEvent
    data class NoStream(val name: String) : SportsLivePlayEvent

    /** OK on a live game or channel: no source returned any stream, so nothing opens (#56). */
    data object NothingFound : SportsLivePlayEvent
}

/**
 * OK on a section card or a Home Live now card ([sportsLivePlayAction]): a live game or channel
 * plays its best stream at once through the zap resolver (one lookup, cached), unless the user
 * chooses; other cards keep their behaviour. The screen shows [state] and runs [events] with
 * [SportsLivePlayHost].
 */
@HiltViewModel
class SportsLivePlayViewModel @Inject constructor(
    private val resolver: SportsLiveZapResolver,
    private val playerSettingsDataStore: PlayerSettingsDataStore,
    private val directDebridResolver: DirectDebridResolver,
    private val profileManager: ProfileManager,
    private val sportsLivePreferences: SportsLivePreferencesDataStore,
    // The shared Live now list every Watch's zap session is built from (#173 follow-up); null in tests that skip it.
    private val repo: SportsLiveRepository? = null
) : ViewModel() {

    private val _state = MutableStateFlow<SportsLivePlayState>(SportsLivePlayState.Idle)
    val state: StateFlow<SportsLivePlayState> = _state.asStateFlow()

    private val _events = Channel<SportsLivePlayEvent>(Channel.BUFFERED)
    val events: Flow<SportsLivePlayEvent> = _events.receiveAsFlow()

    private var job: Job? = null

    /** Each start's request token (#175): a stale start's late lookup never opens a player. */
    private val gate = SportsLiveRequestGate()

    /** Whether Back from the played stream returns to Home (the Home Live now row) rather than the section. */
    private var returnToHomeOnBack = false

    /** [sportsLiveBackToMiniGame], replaced in tests. */
    internal var backToMini: (SportsLiveItem, Stream?) -> Boolean = { item, stream -> sportsLiveBackToMiniGame(item, stream) }

    /**
     * L (live.25): [item] (with [stream], that link) is the mini player's game, which went back full screen on its own
     * player ([backToMini]): no lookup and no new player; a start still in flight is dropped. False otherwise.
     */
    private fun backToMiniGame(item: SportsLiveItem, stream: Stream? = null): Boolean {
        if (!backToMini(item, stream)) return false
        gate.invalidate()
        job?.cancel()
        _state.value = SportsLivePlayState.Idle
        return true
    }

    /** OK on [item]: see [sportsLivePlayAction]. */
    fun play(item: SportsLiveItem, returnToHomeOnBack: Boolean = false) = start(item, returnToHomeOnBack, choose = false)

    /**
     * OK on a Catch up card (#58): its strict replay matches ([candidates], one per source) play at once,
     * never through the addon's page or Stream screen. Each source's best stream is looked up; one found
     * plays, several are offered by source name only ([SportsLivePlayState.PickingReplaySource]), none is
     * a "no stream" notice. The player is named [title] (ESPN's matchup) and shows no addon artwork.
     */
    fun playReplay(candidates: List<SportsLiveItem>, title: String, returnToHomeOnBack: Boolean = false) {
        job?.cancel()
        this.returnToHomeOnBack = returnToHomeOnBack
        _state.value = SportsLivePlayState.Idle
        if (candidates.isEmpty()) return
        SportsLiveZapSessions.clear()
        val safe = candidates.map { spoilerSafeReplay(it, title) }
        val token = gate.begin("replay|$title")
        job = viewModelScope.launch {
            _state.value = SportsLivePlayState.ResolvingReplay(title)
            val found = coroutineScope {
                safe.map { replay -> async { (resolver.lookup(replay) as? SportsLiveLookup.Found)?.let { replay to it.stream } } }.awaitAll()
            }.filterNotNull()
            if (!gate.mayPlay(token)) return@launch
            _state.value = when {
                found.size > 1 -> SportsLivePlayState.PickingReplaySource(title, found)
                else -> SportsLivePlayState.Idle
            }
            when (found.size) {
                0 -> _events.send(SportsLivePlayEvent.NoStream(title))
                1 -> playStream(found[0].first, found[0].second, token = token, zapSession = false)
                else -> Unit
            }
        }
    }

    /** A source picked for a catch-up replay plays its stream. */
    fun pickReplaySource(index: Int) {
        val picking = _state.value as? SportsLivePlayState.PickingReplaySource ?: return
        val (replay, stream) = picking.sources.getOrNull(index) ?: return
        _state.value = SportsLivePlayState.Idle
        val token = gate.begin(replay.streamKey)
        job = viewModelScope.launch { playStream(replay, stream, token = token, zapSession = false) }
    }

    /**
     * "Choose stream" on [item]: its streams to pick from (the Stream screen, or a merged card's picker). [spoilerSafe]: a
     * replay whose result is hidden ([sportsLiveHidesReplayLinks]), its links named with any score masked (#223).
     */
    fun chooseStream(item: SportsLiveItem, returnToHomeOnBack: Boolean = false, spoilerSafe: Boolean = false) =
        start(item, returnToHomeOnBack, choose = true, spoilerSafe = spoilerSafe)

    /**
     * OK on a section card whose hover preview may be playing: unless the stored setting lets the
     * user choose, [takeOverPreview] hands over the preview's stream, which plays at once (instant
     * start); otherwise as [play]. The setting is read from storage first, never assumed.
     */
    /** OK on Live now's Resume chip (#148): [stream], the link the paused player had, at the live edge. */
    fun rejoin(item: SportsLiveItem, stream: Stream) {
        if (backToMiniGame(item)) return
        job?.cancel()
        returnToHomeOnBack = false
        _state.value = SportsLivePlayState.Idle
        val token = gate.begin(item.streamKey)
        job = viewModelScope.launch { playStream(item, stream, liveEdge = true, token = token) }
    }

    fun playCard(item: SportsLiveItem, spoilerSafe: Boolean = false, takeOverPreview: () -> Stream?) {
        if (backToMiniGame(item)) return
        job?.cancel()
        returnToHomeOnBack = false
        _state.value = SportsLivePlayState.Idle
        val token = gate.begin(item.streamKey)
        job = viewModelScope.launch {
            val chooses = sportsLiveAutoPlayEligible(item) && openGame() == SportsLiveOpenGame.LET_ME_CHOOSE
            val previewed = if (chooses) null else takeOverPreview()
            if (previewed != null) {
                playStream(
                    item, previewed, autoPicked = item.alternates.isNotEmpty(), announce = sportsLiveAutoPlayEligible(item), token = token,
                    spoilerSafe = spoilerSafe
                )
            } else {
                run(item, choose = false, token = token, spoilerSafe = spoilerSafe)
            }
        }
    }

    /**
     * "When I open a game" as stored; never set, it is Let me choose for a user who set the player to
     * manual stream selection (they chose to pick streams) and Play best stream otherwise.
     */
    private suspend fun openGame(): SportsLiveOpenGame = SportsLiveOpenGame.effective(
        stored = sportsLivePreferences.openGame.first(),
        manualPlayer = playerSettingsDataStore.playerSettings.first().let {
            it.streamAutoPlayModeChosen && it.streamAutoPlayMode == StreamAutoPlayMode.MANUAL
        }
    )

    private fun start(item: SportsLiveItem, returnToHomeOnBack: Boolean, choose: Boolean, spoilerSafe: Boolean = false) {
        // Watch on the mini player's game (Home's Live now card, the Game or Team page, an alert): back full screen.
        if (!choose && backToMiniGame(item)) return
        job?.cancel()
        this.returnToHomeOnBack = returnToHomeOnBack
        _state.value = SportsLivePlayState.Idle
        val token = gate.begin(item.streamKey)
        SportsLiveSpeedClock.action("ok")
        job = viewModelScope.launch { run(item, choose, token, spoilerSafe) }
    }

    /** [spoilerSafe]: a replay whose result is hidden (#223), every list of its links and its player masking scores. */
    private suspend fun run(item: SportsLiveItem, choose: Boolean, token: SportsLiveRequestToken, spoilerSafe: Boolean = false) {
        val eligible = sportsLiveAutoPlayEligible(item)
        if (item.alternates.isEmpty() && (choose || !eligible)) {
            // Nothing to read or look up: the Stream screen opens at once, as before.
            openStreamScreen(item, manualSelection = choose, spoilerSafe = spoilerSafe)
            return
        }
        val linksOf: (List<Stream>) -> List<Stream> = { streams -> if (spoilerSafe) streams.map(::spoilerSafeReplayStream) else streams }
        val openGame = openGame()
        val mode = playerSettingsDataStore.playerSettings.first().streamAutoPlayMode
        when (sportsLivePlayAction(item, mode, openGame, choose)) {
            SportsLivePlayAction.STREAM_SCREEN -> openStreamScreen(item, manualSelection = true)
            SportsLivePlayAction.PLAY_BEST -> {
                _state.value = SportsLivePlayState.Resolving(item)
                // The zap's lookup: reliability order, the user's filters, junk dropped, cached.
                val found = resolver.lookup(item)
                SportsLiveSpeedClock.stage("resolved", "outcome=${found::class.simpleName}")
                // Stopped or replaced meanwhile (#175): what it found is cached by the resolver, and nothing plays.
                if (!gate.mayPlay(token)) return
                _state.value = SportsLivePlayState.Idle
                when {
                    found is SportsLiveLookup.Found -> playStream(
                        item, found.stream, autoPicked = item.alternates.isNotEmpty(), announce = eligible, token = token,
                        spoilerSafe = spoilerSafe
                    )
                    !eligible -> noStream(item)
                    // No source returned anything: a toast, and no Stream screen re-asking every addon.
                    found is SportsLiveLookup.NothingFound -> _events.send(SportsLivePlayEvent.NothingFound)
                    // Streams came back but none may play. A single source's Stream screen joins the
                    // repository's search session just run (kept 15 minutes), so it asks no addon again.
                    item.alternates.isEmpty() -> openStreamScreen(item, manualSelection = true, noStreamFound = true, spoilerSafe = spoilerSafe)
                    // A merged game: its sources' streams, already fetched, in the picker.
                    else -> _state.value = SportsLivePlayState.Picking(item, linksOf((found as SportsLiveLookup.NonePlayable).streams), spoilerSafe)
                }
            }
            SportsLivePlayAction.PICK -> {
                _state.value = SportsLivePlayState.Resolving(item)
                val streams = resolver.resolveAll(item)
                if (!gate.mayPlay(token)) return
                if (streams.isEmpty()) {
                    _state.value = SportsLivePlayState.Idle
                    noStream(item)
                } else {
                    _state.value = SportsLivePlayState.Picking(item, linksOf(streams), spoilerSafe)
                }
            }
        }
    }

    /**
     * [stream], already found for [item] (the hover preview's), plays at once: no lookup and no
     * Stream screen, with [item]'s other sources registered as on the auto play path.
     */
    fun playResolved(item: SportsLiveItem, stream: Stream) {
        if (backToMiniGame(item)) return
        job?.cancel()
        returnToHomeOnBack = false
        _state.value = SportsLivePlayState.Idle
        val token = gate.begin(item.streamKey)
        SportsLiveSpeedClock.action("okResolved")
        job = viewModelScope.launch {
            playStream(item, stream, autoPicked = item.alternates.isNotEmpty(), announce = sportsLiveAutoPlayEligible(item), token = token)
        }
    }

    /** A stream picked in the picker plays. */
    fun pick(stream: Stream) {
        val picking = _state.value as? SportsLivePlayState.Picking ?: return
        // The very link the mini player plays, picked in Choose stream: back full screen on it (L).
        if (backToMiniGame(picking.item, stream)) return
        _state.value = SportsLivePlayState.Idle
        val token = gate.begin(picking.item.streamKey)
        job = viewModelScope.launch { playStream(picking.item, stream, token = token, spoilerSafe = picking.spoilerSafe) }
    }

    /**
     * Back on the picker or while a stream is looked up, or the screen stopped: nothing started
     * before plays later, including an event not yet collected.
     */
    fun dismiss() {
        gate.invalidate()
        job?.cancel()
        _state.value = SportsLivePlayState.Idle
        while (_events.tryReceive().isSuccess) Unit
    }

    /**
     * [item]'s Stream screen, marked sports so its full player still auto-fails over across the
     * addon's links (and a merged card's other sources) and shows the recovery overlay.
     */
    private suspend fun openStreamScreen(
        item: SportsLiveItem,
        manualSelection: Boolean,
        noStreamFound: Boolean = false,
        spoilerSafe: Boolean = false
    ) {
        ensureZapSession(item)
        SportsLiveExtraSources.markSportsPlayback(item.id, item)
        // A replay whose result is hidden lists its links with the score masked there (#223); any other item as they are.
        SportsLiveExtraSources.markSpoilerSafe(item.id, spoilerSafe)
        if (item.alternates.isNotEmpty()) SportsLiveExtraSources.register(item.id, item)
        _events.trySend(SportsLivePlayEvent.OpenStreamScreen(item, manualSelection, noStreamFound))
    }

    private suspend fun playStream(
        item: SportsLiveItem,
        stream: Stream,
        autoPicked: Boolean = false,
        announce: Boolean = false,
        liveEdge: Boolean = false,
        token: SportsLiveRequestToken,
        /** False for a replay: it has no live games to zap through. */
        zapSession: Boolean = true,
        /** A replay's link names may carry its score (#223): Watch replay's, or a hidden one's chosen or played from its card. */
        spoilerSafe: Boolean = !zapSession
    ) {
        val target = sportsLiveZapTarget(
            item = item,
            // The player names a spoiler-safe replay's link with the score masked.
            stream = if (spoilerSafe) spoilerSafeReplayStream(stream) else stream,
            profileId = profileManager.activeProfileId.value,
            returnToHomeOnBack = returnToHomeOnBack,
            directDebridResolver = directDebridResolver,
            autoPicked = autoPicked
        ).copy(liveEdge = liveEdge, replay = !zapSession)
        // A start made stale meanwhile (#175) opens nothing.
        if (!gate.mayPlay(token)) return
        if (zapSession) ensureZapSession(item)
        // The player (or the primary's Stream screen) gets the other addons' streams for fallback and Sources.
        SportsLiveExtraSources.register(item.id, item)
        // A replay's other links (the Stream screen a link needing preparation opens, the Sources panel) are named
        // with the score masked too (#223); the same item played as a game is not.
        SportsLiveExtraSources.markSpoilerSafe(item.id, spoilerSafe)
        // A stream that opens the Stream screen for preparation is not announced: it is not playing yet.
        val source = stream.addonName.takeIf { announce && target.playback != null && it.isNotBlank() }
        _events.send(SportsLivePlayEvent.Play(target, source))
    }

    /**
     * Before [item]'s player opens: its zap session ([SportsLiveZapSessions.ensureFor]) over the shared Live now
     * list as it stands, so the panel and CH± work whichever screen Watch came from. A list not loaded yet
     * leaves [item] alone in it; the player loads the list and the panel fills in.
     */
    private suspend fun ensureZapSession(item: SportsLiveItem) {
        val repo = repo ?: return
        // Games only: a replay or a channel keeps the session its own list started, if any.
        if (item.kind != SportsLiveKind.EVENTS || SportsLiveZapSessions.activeFor(item.id) != null) return
        val lineup = try {
            val claimed = repo.claimedCatalogs.first()
            val load = repo.latest.value
            liveGamesPanelItems(
                items = load?.items.orEmpty(),
                claimedKeys = claimed.mapTo(HashSet()) { it.key },
                endedKeys = repo.endedKeys.value,
                followed = repo.followedTeams.first(),
                nowMs = System.currentTimeMillis(),
                addonOrder = claimed.map { it.addon.id }.distinct(),
                load = load
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        SportsLiveZapSessions.ensureFor(item, lineup, returnToHomeOnBack)
    }

    private suspend fun noStream(item: SportsLiveItem) {
        _events.send(SportsLivePlayEvent.NoStream(item.displayName))
    }
}
