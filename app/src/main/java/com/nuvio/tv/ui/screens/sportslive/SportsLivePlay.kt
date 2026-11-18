package com.nuvio.tv.ui.screens.sportslive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.debrid.DirectDebridResolver
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sportslive.SportsLiveOpenGame
import com.nuvio.tv.core.sportslive.SportsLiveWorkingLinks
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.SportsLivePreferencesDataStore
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.ui.screens.player.SportsLiveLaunchHeaders
import com.nuvio.tv.ui.screens.stream.streamPlaybackInfoFor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
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
            title = item.meta.name,
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

sealed interface SportsLivePlayState {
    data object Idle : SportsLivePlayState
    data class Resolving(val item: SportsLiveItem) : SportsLivePlayState
    data class Picking(val item: SportsLiveItem, val streams: List<Stream>) : SportsLivePlayState
}

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
    private val sportsLivePreferences: SportsLivePreferencesDataStore
) : ViewModel() {

    private val _state = MutableStateFlow<SportsLivePlayState>(SportsLivePlayState.Idle)
    val state: StateFlow<SportsLivePlayState> = _state.asStateFlow()

    private val _events = Channel<SportsLivePlayEvent>(Channel.BUFFERED)
    val events: Flow<SportsLivePlayEvent> = _events.receiveAsFlow()

    private var job: Job? = null

    /** Whether Back from the played stream returns to Home (the Home Live now row) rather than the section. */
    private var returnToHomeOnBack = false

    /** OK on [item]: see [sportsLivePlayAction]. */
    fun play(item: SportsLiveItem, returnToHomeOnBack: Boolean = false) = start(item, returnToHomeOnBack, choose = false)

    /** "Choose stream" on [item]: its streams to pick from (the Stream screen, or a merged card's picker). */
    fun chooseStream(item: SportsLiveItem, returnToHomeOnBack: Boolean = false) = start(item, returnToHomeOnBack, choose = true)

    /**
     * OK on a section card whose hover preview may be playing: unless the stored setting lets the
     * user choose, [takeOverPreview] hands over the preview's stream, which plays at once (instant
     * start); otherwise as [play]. The setting is read from storage first, never assumed.
     */
    fun playCard(item: SportsLiveItem, takeOverPreview: () -> Stream?) {
        job?.cancel()
        returnToHomeOnBack = false
        _state.value = SportsLivePlayState.Idle
        job = viewModelScope.launch {
            val chooses = sportsLiveAutoPlayEligible(item) && openGame() == SportsLiveOpenGame.LET_ME_CHOOSE
            val previewed = if (chooses) null else takeOverPreview()
            if (previewed != null) {
                playStream(item, previewed, autoPicked = item.alternates.isNotEmpty(), announce = sportsLiveAutoPlayEligible(item))
            } else {
                run(item, choose = false)
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

    private fun start(item: SportsLiveItem, returnToHomeOnBack: Boolean, choose: Boolean) {
        job?.cancel()
        this.returnToHomeOnBack = returnToHomeOnBack
        _state.value = SportsLivePlayState.Idle
        job = viewModelScope.launch { run(item, choose) }
    }

    private suspend fun run(item: SportsLiveItem, choose: Boolean) {
        val eligible = sportsLiveAutoPlayEligible(item)
        if (item.alternates.isEmpty() && (choose || !eligible)) {
            // Nothing to read or look up: the Stream screen opens at once, as before.
            openStreamScreen(item, manualSelection = choose)
            return
        }
        val openGame = openGame()
        val mode = playerSettingsDataStore.playerSettings.first().streamAutoPlayMode
        when (sportsLivePlayAction(item, mode, openGame, choose)) {
            SportsLivePlayAction.STREAM_SCREEN -> openStreamScreen(item, manualSelection = true)
            SportsLivePlayAction.PLAY_BEST -> {
                _state.value = SportsLivePlayState.Resolving(item)
                // The zap's lookup: reliability order, the user's filters, junk dropped, cached.
                val found = resolver.lookup(item)
                _state.value = SportsLivePlayState.Idle
                when {
                    found is SportsLiveLookup.Found ->
                        playStream(item, found.stream, autoPicked = item.alternates.isNotEmpty(), announce = eligible)
                    !eligible -> noStream(item)
                    // No source returned anything: a toast, and no Stream screen re-asking every addon.
                    found is SportsLiveLookup.NothingFound -> _events.send(SportsLivePlayEvent.NothingFound)
                    // Streams came back but none may play. A single source's Stream screen joins the
                    // repository's search session just run (kept 15 minutes), so it asks no addon again.
                    item.alternates.isEmpty() -> openStreamScreen(item, manualSelection = true, noStreamFound = true)
                    // A merged game: its sources' streams, already fetched, in the picker.
                    else -> _state.value = SportsLivePlayState.Picking(item, (found as SportsLiveLookup.NonePlayable).streams)
                }
            }
            SportsLivePlayAction.PICK -> {
                _state.value = SportsLivePlayState.Resolving(item)
                val streams = resolver.resolveAll(item)
                if (streams.isEmpty()) {
                    _state.value = SportsLivePlayState.Idle
                    noStream(item)
                } else {
                    _state.value = SportsLivePlayState.Picking(item, streams)
                }
            }
        }
    }

    /**
     * [stream], already found for [item] (the hover preview's), plays at once: no lookup and no
     * Stream screen, with [item]'s other sources registered as on the auto play path.
     */
    fun playResolved(item: SportsLiveItem, stream: Stream) {
        job?.cancel()
        returnToHomeOnBack = false
        _state.value = SportsLivePlayState.Idle
        job = viewModelScope.launch {
            playStream(item, stream, autoPicked = item.alternates.isNotEmpty(), announce = sportsLiveAutoPlayEligible(item))
        }
    }

    /** A stream picked in the picker plays. */
    fun pick(stream: Stream) {
        val picking = _state.value as? SportsLivePlayState.Picking ?: return
        _state.value = SportsLivePlayState.Idle
        job = viewModelScope.launch { playStream(picking.item, stream) }
    }

    /**
     * Back on the picker or while a stream is looked up, or the screen stopped: nothing started
     * before plays later, including an event not yet collected.
     */
    fun dismiss() {
        job?.cancel()
        _state.value = SportsLivePlayState.Idle
        while (_events.tryReceive().isSuccess) Unit
    }

    /**
     * [item]'s Stream screen, marked sports so its full player still auto-fails over across the
     * addon's links (and a merged card's other sources) and shows the recovery overlay.
     */
    private fun openStreamScreen(item: SportsLiveItem, manualSelection: Boolean, noStreamFound: Boolean = false) {
        SportsLiveExtraSources.markSportsPlayback(item.id, item)
        if (item.alternates.isNotEmpty()) SportsLiveExtraSources.register(item.id, item)
        _events.trySend(SportsLivePlayEvent.OpenStreamScreen(item, manualSelection, noStreamFound))
    }

    private suspend fun playStream(
        item: SportsLiveItem,
        stream: Stream,
        autoPicked: Boolean = false,
        announce: Boolean = false
    ) {
        val target = sportsLiveZapTarget(
            item = item,
            stream = stream,
            profileId = profileManager.activeProfileId.value,
            returnToHomeOnBack = returnToHomeOnBack,
            directDebridResolver = directDebridResolver,
            autoPicked = autoPicked
        )
        // The player (or the primary's Stream screen) gets the other addons' streams for fallback and Sources.
        SportsLiveExtraSources.register(item.id, item)
        // A stream that opens the Stream screen for preparation is not announced: it is not playing yet.
        val source = stream.addonName.takeIf { announce && target.playback != null && it.isNotBlank() }
        _events.send(SportsLivePlayEvent.Play(target, source))
    }

    private suspend fun noStream(item: SportsLiveItem) {
        _events.send(SportsLivePlayEvent.NoStream(item.meta.name))
    }
}
