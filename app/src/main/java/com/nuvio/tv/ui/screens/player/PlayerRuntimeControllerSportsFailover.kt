package com.nuvio.tv.ui.screens.player

import android.util.Log
import androidx.media3.common.PlaybackException
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.R
import com.nuvio.tv.core.debrid.DirectDebridPlayableResult
import com.nuvio.tv.core.sportslive.SportsLiveFailover
import com.nuvio.tv.core.sportslive.SportsLiveLinkCause
import com.nuvio.tv.core.sportslive.SportsLiveLinkMarks
import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.ui.screens.sportslive.SportsLiveManifestSniffer
import com.nuvio.tv.ui.screens.sportslive.noteSportsLiveLinkError
import com.nuvio.tv.core.sportslive.SportsLiveGameWatch
import com.nuvio.tv.core.sportslive.SportsLiveKickoffWait
import com.nuvio.tv.data.repository.SportsLiveKickoffProbe
import com.nuvio.tv.core.sportslive.SportsLiveLinkErrors
import com.nuvio.tv.core.sportslive.SportsLiveReadyMark
import com.nuvio.tv.core.sportslive.SportsLiveGameReadiness
import com.nuvio.tv.core.sportslive.SportsLiveLinkSpeed
import com.nuvio.tv.core.sportslive.SportsLiveRecoveryCandidates
import com.nuvio.tv.core.sportslive.SportsLiveRecoveryLadder
import com.nuvio.tv.core.sportslive.SportsLiveSourceCooldown
import com.nuvio.tv.core.sportslive.SportsLiveSourceReliability
import com.nuvio.tv.core.sportslive.SportsLiveStartupTimeout
import com.nuvio.tv.core.sportslive.SportsLiveStreamThresholds
import com.nuvio.tv.core.sportslive.SportsLiveViewerText
import com.nuvio.tv.core.sportslive.SportsLiveStreamHealth
import com.nuvio.tv.core.sportslive.SportsLiveWorkingLinks
import com.nuvio.tv.domain.model.ProxyHeaders
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import com.nuvio.tv.ui.screens.sportslive.SportsLiveExtraSources
import com.nuvio.tv.ui.screens.sportslive.SportsLiveZapResolver
import com.nuvio.tv.ui.screens.sportslive.retrySportsLiveLink
import com.nuvio.tv.ui.screens.sportslive.sportsLiveFallbackCandidate
import com.nuvio.tv.ui.screens.sportslive.sportsLiveHealthSample
import com.nuvio.tv.ui.screens.sportslive.sportsLiveLinkId
import com.nuvio.tv.ui.screens.sportslive.traceSportsLiveStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Sports & Live two-stage error recovery (#28). On a fatal playback error — or a stall after
 * playback has started — the player automatically advances to the next untried source for this
 * game/channel (its own addon's other links plus a merged game's other addons' extras), link after
 * link while none shows a first frame, for up to [SportsLiveStreamThresholds.MAX_FAILOVER_LINKS] links
 * and [SportsLiveStreamThresholds.FAILOVER_RUN_MS] ([SportsLiveFailover], #153, F1). When the run gives up
 * or no untried source remains, the game is marked ⚠ no working stream and the standalone, focusable
 * error overlay is shown so the user picks one by hand ("Try next link" and a Sources pick still play).
 *
 * It is scoped to sports playback ([SportsLiveExtraSources.isSportsPlayback]); movies and shows keep
 * upstream behaviour. Already-tried sources are skipped, and the resolve path reuses whatever the
 * shared stream search already found, so a known source costs nothing extra.
 */
private const val SPORTS_FAILOVER_POLL_COUNT = 30
private const val SPORTS_FAILOVER_POLL_INTERVAL_MS = 500L

/**
 * Remembers ([SportsLiveSourceReliability]) that the sports source playing reached its first frame,
 * with its time to first frame. A manual pick in the Sources panel that plays counts too. Sports only.
 */
internal fun PlayerRuntimeController.recordSportsSourceFirstFrame() {
    // A link plays: the failover run is over, the next failure starts a new one (#153).
    sportsFailoverRunStartedAtMs = null
    sportsFailoverAttempts = 0
    // A link plays: the waiting card (#162) is over, never left over a playing stream (live.19 QA F12).
    cancelSportsWaiting()
    // The stream playing now, for dual screen to take over as is (#65).
    if (SportsLiveExtraSources.isSportsPlayback(contentId)) {
        currentSportsStream()?.let {
            SportsLiveWorkingLinks.shared.startedPlaying(contentId.orEmpty(), it)
            // A game tried and failed plays after all: the tried games may be offered again (#138 follow-up).
            com.nuvio.tv.core.sportslive.SportsLiveTriedGames.shared.onPlaying(contentId.orEmpty())
            // #147: this addon's links start this fast for the game; the game is ✓ ready.
            SportsLiveLinkSpeed.shared.recordStart(contentId, it.addonName, System.currentTimeMillis() - sportsAttemptStartedAtMs)
            SportsLiveGameReadiness.shared.set(contentId.orEmpty(), SportsLiveReadyMark.READY)
        }
    }
    val key = currentSportsSourceKey() ?: return
    SportsLiveSourceReliability.record(
        key,
        SportsLiveSourceReliability.Outcome.SUCCESS,
        ttffMs = System.currentTimeMillis() - sportsAttemptStartedAtMs
    )
}

/**
 * The stream the sports player plays (#65): the one launched for this game when its url is the one
 * playing (with every header and hint), else one built from what the player plays. Null without a url.
 */
private fun PlayerRuntimeController.currentSportsStream(): Stream? {
    val gameKey = contentId ?: return null
    val url = currentStreamUrl.takeIf { it.isNotBlank() } ?: return null
    val launched = SportsLiveWorkingLinks.shared.launched(gameKey)
    val launchedUrl = launched?.getStreamUrl()
    if (launched != null && launchedUrl != null && (launchedUrl == url ||
            PlayerMediaSourceFactory.normalizePlaybackRequest(launchedUrl, launched.behaviorHints?.proxyHeaders?.request).url == url)
    ) {
        return launched
    }
    return Stream(
        name = _uiState.value.currentStreamName,
        title = null,
        description = currentStreamDescription,
        url = url,
        ytId = null,
        infoHash = null,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = StreamBehaviorHints(
            notWebReady = null,
            bingeGroup = null,
            countryWhitelist = null,
            proxyHeaders = ProxyHeaders(request = currentHeaders, response = currentStreamResponseHeaders),
            filename = currentFilename
        ),
        addonName = currentAddonName.orEmpty(),
        addonLogo = currentAddonLogo
    )
}

/** The reliability key of the sports source playing; null for movies/shows or an unknown source. */
private fun PlayerRuntimeController.currentSportsSourceKey(): String? =
    SportsLiveExtraSources.sourceKeyFor(contentId, currentStreamUrl, currentAddonName)

/**
 * Auto-fails over to the next untried sports source after [errorMessage], or shows the recovery
 * overlay when the cap is reached, nothing untried remains or [autoTry] is false (the stream-health
 * recovery budget is used up, #138). Returns true when sports handled the error (so the generic error
 * chain stops); false for non-sports playback. The failed source is remembered as a failure, or a
 * stall when [stalled] (the stream-health monitor fired), and its link as failed for the game (#65:
 * quarantined unless known good); a [decoderInit] failure (the device's fault, not the source's) is
 * not held against it.
 */
internal fun PlayerRuntimeController.trySportsLiveFailoverAfterError(
    errorMessage: String,
    stalled: Boolean = false,
    decoderInit: Boolean = false,
    autoTry: Boolean = true,
    /** False when the viewer skipped a link still starting (#138): it is only left behind, not held against it. */
    recordFailure: Boolean = true,
    /** What went wrong, when the health ladder knows: a slow start is quarantined for less time (#158). */
    problem: SportsLiveStreamHealth.Problem? = null,
    /** The viewer's "Try next link": the next link plays even once the run's cap is reached (F1). */
    manual: Boolean = false
): Boolean {
    if (!SportsLiveExtraSources.isSportsPlayback(contentId)) return false
    // The game is over (#161) or not live yet (#162): no failover, no error; the end card or the waiting card.
    if (recordFailure && (trySportsGameEnded() || trySportsWaitForKickoff())) return true
    // Nothing that works plays now (also a Sources panel pick failing before its first frame): dual
    // screen must not take over a stream this player no longer plays (#65).
    SportsLiveWorkingLinks.shared.stoppedPlaying(contentId.orEmpty(), failedUrl = currentStreamUrl)
    // The source that just failed must not be re-served from the resolve cache next time.
    SportsLiveExtraSources.invalidateResolve(contentId)
    if (sourceFallbackJob?.isActive == true) return true
    if (!decoderInit && recordFailure) {
        SportsLiveSourceReliability.record(
            currentSportsSourceKey(),
            if (stalled) SportsLiveSourceReliability.Outcome.STALL else SportsLiveSourceReliability.Outcome.FAILURE
        )
        // A link that failed to start, hit a fatal error or froze is skipped for this game for a
        // while, unless it is known to work and this is its only recent failure (#65, #138).
        SportsLiveWorkingLinks.shared.failed(contentId.orEmpty(), currentSportsStream()?.getStreamUrl(), problem)
        // #147: a link that never showed a frame counts against its addon's starts for this game.
        if (!hasRenderedFirstFrame) SportsLiveLinkSpeed.shared.recordFailure(contentId, currentAddonName)
    } else if (recordFailure) {
        // No decoder for it: not held against the link, but the stream lists still say it didn't work (#156).
        SportsLiveLinkMarks.shared.failed(contentId.orEmpty(), currentSportsStream()?.getStreamUrl(), problem)
    }
    // A link that never showed a frame without saying why may be DRM-only: its manifest is checked (#166).
    if (recordFailure && problem == SportsLiveStreamHealth.Problem.NO_START) {
        SportsLiveManifestSniffer.checkInBackground(currentSportsStream()?.getStreamUrl() ?: currentStreamUrl, currentHeaders)
    }
    cancelSportsStallWatchdog()

    val failedUrl = currentStreamUrl
    val failedAddonName = currentAddonName
    val failedInfoHash = _uiState.value.currentStreamInfoHash ?: navigationArgs.infoHash
    sportsFailoverTriedKeys += "url:$failedUrl"
    val videoId = currentVideoId ?: contentId
    if (sportsFailoverRunStartedAtMs == null) sportsFailoverRunStartedAtMs = System.currentTimeMillis()
    if (videoId == null || !autoTry || !canTrySportsLink(manual)) {
        // The run gave up (F1: its cap of links or its time): no working stream for now.
        if (videoId != null && autoTry) markSportsGameNoStream()
        surfaceSportsFailoverError(if (autoTry) sportsGaveUpMessage(errorMessage) else errorMessage)
        return true
    }
    val season = currentSeason
    val episode = currentEpisode

    sourceFallbackJob = scope.launch {
        showRecoveryOverlay()
        // Joins the shared source search, so links the stream screen / Sources panel already found are reused.
        loadSourceStreams(forceRefresh = false)
        val settings = playerSettingsDataStore.playerSettings.first()
        val installedAddonNames = streamAddonsFor(videoId).map { it.displayName }.toSet()

        var polls = 0
        while (canTrySportsLink(manual)) {
            val sources = _uiState.value
            val searchDone = !sources.isLoadingSourceStreams &&
                (sources.sourceAllStreams.isNotEmpty() || sources.sourceStreamsError != null)
            // Links known to work for this game first (#65); quarantined links, sources cooling down
            // after a rate limit, an addon's informational entries and junk links are never tried (C1, #138).
            val cooling = SportsLiveSourceCooldown.shared.coolingNames()
            val next = sportsLiveFallbackCandidate(
                streams = SportsLiveRecoveryCandidates.eligible(
                    contentId.orEmpty(),
                    sources.sourceAllStreams.filterNot(SportsLiveZapResolver::isNonStream),
                    SportsLiveWorkingLinks.shared,
                    cooling
                ),
                extras = SportsLiveRecoveryCandidates.eligible(
                    contentId.orEmpty(),
                    SportsLiveExtraSources.availableStreamsFor(contentId).filterNot(SportsLiveZapResolver::isNonStream),
                    SportsLiveWorkingLinks.shared,
                    cooling
                ),
                extrasAllowed = searchDone || polls >= SPORTS_FAILOVER_POLL_COUNT,
                settings = settings,
                installedAddonNames = installedAddonNames,
                triedKeys = sportsFailoverTriedKeys,
                failedAddonName = failedAddonName,
                failedInfoHash = failedInfoHash,
                sourceScore = { SportsLiveExtraSources.reliabilityOf(contentId, it) }
            )
            if (next == null) {
                if (searchDone || polls >= SPORTS_FAILOVER_POLL_COUNT) break
                delay(SPORTS_FAILOVER_POLL_INTERVAL_MS)
                polls++
                continue
            }
            sportsFailoverTriedKeys += next.sourceFallbackKey()
            sportsFailoverAttempts++

            val playable = (directDebridResolver.resolveToPlayableStream(next, season, episode)
                as? DirectDebridPlayableResult.Success)?.stream
            val url = playable?.getStreamUrl()
            if (playable == null || url.isNullOrBlank() || url == failedUrl) {
                continue
            }
            Log.i(
                PlayerRuntimeController.TAG,
                "Sports source failed, auto-failing over to ${playable.name ?: playable.addonName} " +
                    "(link ${sportsFailoverAttempts} of this run)"
            )
            SportsLiveWorkingLinks.shared.noteLaunch(contentId.orEmpty(), playable)
            switchToSourceStream(playable)
            sportsFailoverTriedKeys += "url:$currentStreamUrl"
            return@launch
        }
        markSportsGameNoStream()
        surfaceSportsFailoverError(sportsGaveUpMessage(errorMessage))
    }
    return true
}

/**
 * What the recovery card says once the tries gave up (live.19 QA F9): with two links or more started for the game,
 * "No working stream found after N links", not the last link's own error ("That link was slow to start").
 */
private fun PlayerRuntimeController.sportsGaveUpMessage(errorMessage: String): String =
    sportsLiveGaveUpLinks(sportsStartedLinks.size)
        ?.let { links -> context.resources.getQuantityString(R.plurals.sports_live_no_working_stream_after, links, links) }
        ?: errorMessage

/** The number of links the recovery card names (F9): the links started for the game, when two or more were. */
internal fun sportsLiveGaveUpLinks(startedLinks: Int): Int? = startedLinks.takeIf { it >= 2 }

private fun PlayerRuntimeController.canTrySportsLink(manual: Boolean): Boolean =
    SportsLiveFailover.canTryNext(sportsFailoverRunStartedAtMs, System.currentTimeMillis(), sportsFailoverAttempts, manual)

/**
 * The failover run gave up with no link playing (F1): the game is marked ⚠ no working stream (#147), so
 * "Next live game", the end card's Up next and the panel pass it over while the mark stands.
 */
private fun PlayerRuntimeController.markSportsGameNoStream() {
    if (hasRenderedFirstFrame) return
    contentId?.takeIf(String::isNotBlank)?.let { SportsLiveGameReadiness.shared.set(it, SportsLiveReadyMark.NO_STREAM) }
}

/**
 * Any playback error of a sports link that a generic recovery would otherwise turn into the error
 * screen (#153: the parsing probe's dead end, mpv's error handler): the stream-health ladder once the
 * link played, else the next link. False for movies and shows.
 */
internal fun PlayerRuntimeController.trySportsRecoveryAfterError(errorMessage: String, decoderInit: Boolean = false): Boolean =
    trySportsHealthRecoveryAfterError(errorMessage, decoderInit) ||
        trySportsLiveFailoverAfterError(errorMessage, decoderInit = decoderInit)

/**
 * Whether a sports [errorCode] skips straight to failover (C1): a container the player cannot parse
 * before the first frame, once the parsing probe has already re-tried it with the probed type
 * ([probeAttempted]), or at once when the link is an addon's notice or placeholder ([isNotice]). A
 * real link without an extension or with a wrong MIME type still gets the probe first. It counts as
 * a failed link (#65 quarantine).
 */
internal fun sportsFailsOverAtOnce(
    errorCode: Int,
    hasRenderedFirstFrame: Boolean,
    probeAttempted: Boolean,
    isNotice: Boolean
): Boolean =
    !hasRenderedFirstFrame &&
        errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED &&
        (probeAttempted || isNotice)

/**
 * [sportsFailsOverAtOnce] for the error just raised: hands it to [trySportsLiveFailoverAfterError].
 * True when sports handled it; false for movies/shows and any other error.
 */
internal fun PlayerRuntimeController.trySportsFailoverOnUnplayableStart(errorCode: Int, errorMessage: String): Boolean {
    if (!SportsLiveExtraSources.isSportsPlayback(contentId)) return false
    val isNotice = currentSportsStream()?.let(SportsLiveZapResolver::isNonStream) == true
    if (!sportsFailsOverAtOnce(errorCode, hasRenderedFirstFrame, parsingErrorProbeAttempted, isNotice)) return false
    return trySportsLiveFailoverAfterError(errorMessage)
}

/**
 * A sports link's playback error (#166): why it failed is noted for the stream lists' marks, and an error
 * that does not say why has the link's manifest checked for DRM. Movies and shows: nothing.
 */
internal fun PlayerRuntimeController.noteSportsLinkError(error: PlaybackException) {
    if (!SportsLiveExtraSources.isSportsPlayback(contentId)) return
    val stream = currentSportsStream()
    noteSportsLiveLinkError(stream, stream?.getStreamUrl() ?: currentStreamUrl, error)
}

/**
 * Whether a sports link that failed with [errorCode] is retried once with the mpv engine before the next
 * link (#166): a decoder or unsupported-format failure before the first frame, on ExoPlayer, not yet
 * retried with mpv, and not a DRM-protected link (mpv cannot play that either). A container ExoPlayer
 * cannot parse is retried only after the parsing probe already tried it ([probeAttempted]).
 */
internal fun sportsRetriesWithMpv(
    errorCode: Int,
    hasRenderedFirstFrame: Boolean,
    onMpv: Boolean,
    alreadyRetried: Boolean,
    probeAttempted: Boolean,
    isProtected: Boolean
): Boolean {
    if (hasRenderedFirstFrame || onMpv || alreadyRetried || isProtected) return false
    if (SportsLiveLinkCause.isDecoderError(errorCode)) return true
    return errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED && probeAttempted
}

/**
 * [sportsRetriesWithMpv] for the error just raised: the same link plays again on mpv, once. True when
 * it does; false for movies and shows and any other case.
 */
internal fun PlayerRuntimeController.trySportsCodecRetryWithMpv(errorCode: Int): Boolean {
    if (!SportsLiveExtraSources.isSportsPlayback(contentId)) return false
    val url = currentStreamUrl
    val retries = sportsRetriesWithMpv(
        errorCode = errorCode,
        hasRenderedFirstFrame = hasRenderedFirstFrame,
        onMpv = isUsingMpvEngine(),
        alreadyRetried = url in sportsMpvRetriedLinks,
        probeAttempted = parsingErrorProbeAttempted,
        isProtected = SportsLiveLinkMarks.shared.isProtected(url)
    )
    if (!retries || url.isBlank()) return false
    sportsMpvRetriedLinks += url
    Log.i(PlayerRuntimeController.TAG, "Sports link could not be decoded, retrying it once with mpv")
    com.nuvio.tv.core.sportslive.SportsLiveDebugTiming.log { "codecRetryMpv who=full link=${sportsLiveLinkId(url)} code=$errorCode" }
    switchToInternalPlayerEngine(InternalPlayerEngine.MVP_PLAYER, reason = "sports-codec-retry")
    return true
}

/**
 * The Sources panel closed while nothing plays for a sports game (its source failed and no pick
 * replaced it): the recovery overlay (Choose stream / Retry / Back) comes back, not a black player (C1).
 * Engine-agnostic (ExoPlayer and mpv): it reads the failure the failover recorded for the link loaded.
 */
internal fun PlayerRuntimeController.restoreSportsRecoveryIfNothingPlays() {
    if (!SportsLiveExtraSources.isSportsPlayback(contentId)) return
    if (sourceFallbackJob?.isActive == true) return
    if (!SportsLiveWorkingLinks.shared.failedNow(contentId.orEmpty(), currentStreamUrl)) return
    surfaceSportsFailoverError(context.getString(R.string.sports_live_nothing_playing))
}

/** Shows the standalone, focusable recovery overlay (Choose stream / Next live game / Retry / Back). */
private fun PlayerRuntimeController.surfaceSportsFailoverError(errorMessage: String) {
    streamCacheKey?.let { key -> scope.launch { streamLinkCacheDataStore.remove(key) } }
    finishSportsStartup()
    // Never a url or a host name on screen (#138): a raw exception becomes a plain message.
    val shown = SportsLiveViewerText.forViewer(errorMessage, context.getString(R.string.sports_live_link_not_working))
    _uiState.update {
        it.copy(
            error = shown,
            canChooseAnotherSource = true,
            sportsReconnecting = false,
            isBuffering = false,
            showLoadingOverlay = false,
            showPauseOverlay = false
        )
    }
}

/**
 * After the first frame, watches the sports stream's health (#138, [SportsLiveStreamHealth]): a long
 * buffering, a stream frozen while playing (no new frame and no position advance) or an unexpected
 * end of a live stream starts the recovery ladder ([recoverSportsHealth]). Its progress also marks the
 * link as working for the game (#65). Sports only; it never runs during startup. Idempotent.
 */
internal fun PlayerRuntimeController.scheduleSportsStallWatchdog() {
    if (!SportsLiveExtraSources.isSportsPlayback(contentId)) return
    // Cancel-and-restart so each first frame rebaselines the health tracking (a new link forgets its errors).
    cancelSportsStallWatchdog()
    sportsHealth.onFirstFrame(link = currentStreamUrl)
    sportsStallWatchdogJob = scope.launch {
        var markedGood = false
        while (true) {
            delay(SportsLiveStreamHealth.POLL_INTERVAL_MS)
            val player = _exoPlayer
            if (player == null || _uiState.value.error != null || isInBackground || !hasRenderedFirstFrame ||
                sourceFallbackJob?.isActive == true
            ) {
                sportsHealth.rebaseline()
                continue
            }
            val problem = sportsHealth.sample(
                player.sportsLiveHealthSample(playWhenReady = player.playWhenReady && !userPausedManually)
            )
            if (problem != null) {
                Log.w(PlayerRuntimeController.TAG, "Sports stream unhealthy after start: $problem")
                recoverSportsHealth(problem, context.getString(R.string.player_error_source_stalled))
                continue
            }
            if (!sportsHealth.isProgressing) continue
            // 30 s of actual progress (not paused, not buffering) mark the link as working for this game (#65).
            if (!markedGood) {
                markedGood = SportsLiveWorkingLinks.shared.progressed(contentId.orEmpty(), SportsLiveStreamHealth.POLL_INTERVAL_MS)
            }
            if (_uiState.value.sportsReconnecting) {
                // Recovered as is: the link plays again for dual screen and the link memory.
                _uiState.update { it.copy(sportsReconnecting = false) }
                currentSportsStream()?.let {
                    SportsLiveWorkingLinks.shared.startedPlaying(contentId.orEmpty(), it)
                    com.nuvio.tv.core.sportslive.SportsLiveTriedGames.shared.onPlaying(contentId.orEmpty())
                }
            }
        }
    }
}

/**
 * A playback error after the first frame of a sports stream (#138): the stream-health recovery
 * ladder handles it ([recoverSportsHealth]). False for movies/shows, at start-up (the start-up
 * failover handles that), for a decoder failure (the device's, not the link's) and while a failover runs.
 */
internal fun PlayerRuntimeController.trySportsHealthRecoveryAfterError(errorMessage: String, decoderInit: Boolean): Boolean {
    if (!SportsLiveExtraSources.isSportsPlayback(contentId)) return false
    if (decoderInit || !hasRenderedFirstFrame || sourceFallbackJob?.isActive == true) return false
    recoverSportsHealth(sportsHealth.onError(), errorMessage)
    return true
}

/**
 * The recovery ladder of this player for [problem] (#138): the same link loaded again from the live
 * edge once (last frame kept, "Reconnecting…"), else the next best link and other sources (the
 * sports failover), else, with the game's recovery budget used up, the recovery overlay's choices.
 */
private fun PlayerRuntimeController.recoverSportsHealth(problem: SportsLiveStreamHealth.Problem, message: String) {
    // A stream stopping as its game ends is no failure (#161): the end card, never the ladder.
    if (trySportsGameEnded()) return
    val gameKey = contentId.orEmpty()
    val url = currentSportsStream()?.getStreamUrl() ?: currentStreamUrl
    val step = sportsRecoveryLadder.decide(gameKey, url, problem, started = hasRenderedFirstFrame)
    Log.i(PlayerRuntimeController.TAG, "Sports stream recovery: $problem -> $step")
    val player = _exoPlayer
    if (step == SportsLiveRecoveryLadder.Step.RETRY_SAME && player != null) {
        SportsLiveSourceReliability.record(
            currentSportsSourceKey(),
            if (problem.isStall) SportsLiveSourceReliability.Outcome.STALL else SportsLiveSourceReliability.Outcome.FAILURE
        )
        SportsLiveWorkingLinks.shared.failed(gameKey, url, problem)
        _uiState.update { it.copy(sportsReconnecting = true) }
        sportsHealth.rebaseline()
        player.retrySportsLiveLink()
        return
    }
    val autoTry = step != SportsLiveRecoveryLadder.Step.CHOICES
    _uiState.update { it.copy(sportsReconnecting = autoTry) }
    trySportsLiveFailoverAfterError(message, stalled = problem.isStall, autoTry = autoTry, problem = problem)
}

/**
 * A sports link starts loading (#138): the starting card shows ("Starting link N of M · source"), and
 * with no first frame within [SportsLiveStartupTimeout] the next link is tried, a step of the recovery
 * ladder. Not sports: the card is cleared and nothing is scheduled. Called for every new load.
 */
internal fun PlayerRuntimeController.scheduleSportsStartupTimeout() {
    val sports = SportsLiveExtraSources.isSportsPlayback(contentId)
    // A sports link whose host does not exist fails at once instead of being retried (#138).
    mediaSourceFactory.failFastOnDeadHost = sports
    val url = currentStreamUrl
    // The player's own retries (a rebuild, a timeout retry) load the same link again: its start-up
    // time keeps running, or retrying would hold a dead link on "Starting stream…" forever.
    if (sports && url == sportsStartupUrl && sportsStartupJob?.isActive == true && !hasRenderedFirstFrame) return
    // A new link loads (a source picked, the waiting card's live link): the waiting card is over (#162).
    if (_uiState.value.sportsWaiting != null || sportsWaitingJob != null) {
        sportsWaitingJob?.cancel()
        sportsWaitingJob = null
        _uiState.update { it.copy(sportsWaiting = null) }
    }
    sportsStartupJob?.cancel()
    sportsStartRequestFailed = false
    sportsStartupUrl = url
    // Another game in the same player (a zap): its links and its failover tries start from zero.
    if (contentId != sportsStartupGame) {
        sportsStartupGame = contentId
        sportsStartedLinks.clear()
        sportsFailoverAttempts = 0
        sportsFailoverRunStartedAtMs = null
        sportsFailoverTriedKeys.clear()
        sportsMpvRetriedLinks.clear()
    }
    if (!sports) {
        if (_uiState.value.sportsStarting != null) _uiState.update { it.copy(sportsStarting = null) }
        return
    }
    if (url.isNotBlank()) sportsStartedLinks += url
    // Debug builds only (the #162 emulator check): this start fails at once, as a link not live yet does, so the
    // waiting card comes the real way: the link is stopped first (live.19 QA F12: it played behind the card when
    // the card was raised before the player had even loaded it).
    val debugWait = BuildConfig.IS_DEBUG_BUILD && SportsLiveGameWatch.shared.consumeDebugWait()
    val number = sportsStartedLinks.indexOf(url) + 1
    _uiState.update {
        it.copy(
            sportsStarting = SportsLiveStartingInfo(
                linkNumber = number.coerceAtLeast(1),
                sourceName = currentAddonName?.takeIf(String::isNotBlank) ?: it.currentStreamName,
                startedAtMs = System.currentTimeMillis()
            )
        )
    }
    if (BuildConfig.IS_DEBUG_BUILD) {
        scope.launch {
            traceSportsLiveStart(
                who = "full",
                link = sportsLiveLinkId(url),
                player = { _exoPlayer },
                bytes = { mediaSourceFactory.bytesLoaded },
                firstFrame = { hasRenderedFirstFrame },
                stillLoading = { currentStreamUrl == url && _uiState.value.error == null }
            )
        }
    }
    sportsStartupJob = scope.launch {
        if (debugWait) {
            awaitSportsLinkLoading(url)
        } else {
            // A slow link that keeps loading is not cut off (#158): only one that stopped making progress.
            SportsLiveStartupTimeout.await(
                requestFailed = { sportsStartRequestFailed },
                progress = { sportsStartProgress() }
            )
        }
        // The viewer is choosing a source: the link is left alone until the panel closes.
        while (_uiState.value.showSourcesPanel) delay(SportsLiveStreamThresholds.POLL_INTERVAL_MS)
        if (hasRenderedFirstFrame || currentStreamUrl != url) return@launch
        if (_uiState.value.error != null || isInBackground || sourceFallbackJob?.isActive == true) return@launch
        Log.w(PlayerRuntimeController.TAG, "Sports link showed no first frame in time, trying the next one")
        com.nuvio.tv.core.sportslive.SportsLiveDebugTiming.log { "startupTimeout who=full link=${sportsLiveLinkId(url)} requestFailed=$sportsStartRequestFailed" }
        stopPendingSportsStart()
        recoverSportsHealth(SportsLiveStreamHealth.Problem.NO_START, context.getString(R.string.sports_live_link_slow))
    }
}

/** Debug builds only: waits until the player is loading [url] (at most a few seconds), so stopping it stops it. */
private suspend fun PlayerRuntimeController.awaitSportsLinkLoading(url: String) {
    repeat(DEBUG_WAIT_POLLS) {
        // First a beat: the new load resets the first-frame flag after this job is launched.
        delay(DEBUG_WAIT_POLL_MS)
        val exoLoading = _exoPlayer?.let { it.playbackState != androidx.media3.common.Player.STATE_IDLE } == true
        if (currentStreamUrl != url || hasRenderedFirstFrame || exoLoading || mpvView != null) return
    }
}

private const val DEBUG_WAIT_POLLS = 50
private const val DEBUG_WAIT_POLL_MS = 100L

/** What the sports link still starting has loaded so far (#158), for its start-up timeout. */
private fun PlayerRuntimeController.sportsStartProgress(): SportsLiveStartupTimeout.Progress? =
    _exoPlayer?.let { SportsLiveStartupTimeout.Progress(mediaSourceFactory.bytesLoaded, it.totalBufferedDuration) }

/** A request of the sports link still starting failed: its start-up time shortens (#138). */
internal fun PlayerRuntimeController.noteSportsStartRequestFailed() {
    if (!hasRenderedFirstFrame && sportsStartupJob?.isActive == true) {
        if (!sportsStartRequestFailed) {
            com.nuvio.tv.core.sportslive.SportsLiveDebugTiming.log { "requestFailed who=full link=${sportsLiveLinkId(sportsStartupUrl)}" }
        }
        sportsStartRequestFailed = true
    }
}

/** The sports link showed its first frame (or the start was given up): no timeout, no starting card. */
internal fun PlayerRuntimeController.finishSportsStartup() {
    sportsStartupJob?.cancel()
    sportsStartupJob = null
    if (_uiState.value.sportsStarting != null) _uiState.update { it.copy(sportsStarting = null) }
}

/**
 * Drops the sports link still starting (#138): the player stops loading it at once, so a late first
 * frame of that link can never show (or count as the next link's) while the next one is looked up.
 */
private fun PlayerRuntimeController.stopPendingSportsStart() {
    sportsStartupJob?.cancel()
    sportsStartupJob = null
    // A retry of the same link the player scheduled must not bring it back.
    errorRetryJob?.cancel()
    if (!hasRenderedFirstFrame) _exoPlayer?.stop()
}

/**
 * "Try next link" on the starting card (#138): the link still starting is dropped (not held against
 * it) and the next one is tried, as an automatic failover would.
 */
internal fun PlayerRuntimeController.sportsTryNextLink() {
    if (!SportsLiveExtraSources.isSportsPlayback(contentId)) return
    if (sourceFallbackJob?.isActive == true) return
    // From the recovery card (live.19 QA F9): nothing plays, whether or not a link played before; the next link
    // plays even once the automatic tries reached their cap (manual, F1).
    val fromError = _uiState.value.error != null
    if (hasRenderedFirstFrame && !fromError) return
    if (fromError) _uiState.update { it.copy(error = null) } else stopPendingSportsStart()
    trySportsLiveFailoverAfterError(context.getString(R.string.sports_live_link_not_working), recordFailure = false, manual = true)
}

/**
 * A sports live stream fell behind its live window (#138): back to the live edge on the same player
 * and link, not counted as an error; only when it keeps happening is it a stall for the recovery
 * ladder. False for anything else, and for movies and shows.
 */
internal fun PlayerRuntimeController.trySportsBehindLiveWindowInPlace(errorCode: Int): Boolean {
    if (errorCode != PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) return false
    if (!SportsLiveExtraSources.isSportsPlayback(contentId)) return false
    val player = _exoPlayer ?: return false
    val problem = sportsHealth.onBehindLiveWindow()
    if (problem == null) {
        Log.i(PlayerRuntimeController.TAG, "Sports stream fell behind the live window: back to the live edge")
        player.retrySportsLiveLink()
        return true
    }
    recoverSportsHealth(problem, context.getString(R.string.player_error_source_stalled))
    return true
}

/**
 * A sports link whose host does not exist (unknown host, DNS failure) is dead (#138): no retry, the
 * next link at once (a step of the recovery ladder), and the viewer never sees the host name.
 */
internal fun PlayerRuntimeController.trySportsDeadLinkFailover(error: PlaybackException): Boolean {
    if (!SportsLiveExtraSources.isSportsPlayback(contentId)) return false
    if (!SportsLiveLinkErrors.isDeadHost(error)) return false
    if (sourceFallbackJob?.isActive == true) return true
    Log.w(PlayerRuntimeController.TAG, "Sports link host is unknown, trying the next link")
    stopPendingSportsStart()
    recoverSportsHealth(SportsLiveStreamHealth.Problem.DEAD_LINK, context.getString(R.string.sports_live_link_not_working))
    return true
}

/**
 * The sports stream stopped or failed as its game ended (#161, [SportsLiveGameWatch.failureIsEnd]: ESPN final,
 * the Just ended window, or around the expected end with ESPN not showing it in play): nothing is retried and no
 * error shows; the player stops trying, keeps the last frame, and the zap overlay's end card takes over. False
 * when it is a real failure (the ladder runs) or not sports.
 */
internal fun PlayerRuntimeController.trySportsGameEnded(): Boolean {
    if (!SportsLiveExtraSources.isSportsPlayback(contentId)) return false
    val gameId = contentId ?: return false
    val reason = SportsLiveGameWatch.shared.failureIsEnd(gameId) ?: return false
    Log.i(PlayerRuntimeController.TAG, "Sports stream stopped at the game's end ($reason): no recovery")
    stopPendingSportsStart()
    cancelSportsStallWatchdog()
    cancelSportsWaiting()
    sourceFallbackJob?.cancel()
    finishSportsStartup()
    SportsLiveWorkingLinks.shared.stoppedPlaying(gameId)
    _exoPlayer?.playWhenReady = false
    _uiState.update {
        it.copy(
            error = null,
            sportsReconnecting = false,
            isBuffering = false,
            showLoadingOverlay = false,
            showPauseOverlay = false
        )
    }
    SportsLiveGameWatch.shared.reportEnd(gameId, reason)
    return true
}

/**
 * The game's links are not live yet around its kickoff (#162, [SportsLiveKickoffWait]): instead of the failure
 * ladder and an error, "Waiting for the stream to start…" shows and the links are checked every 30 s, one
 * probe per addon ([SportsLiveLiveCheck]: a media playlist with recent segments, never a master alone). The
 * first live link plays at once. Once the kickoff window passes with none live, the usual recovery choices
 * show. False when it does not apply (a link played already, or the game is not near its kickoff).
 */
internal fun PlayerRuntimeController.trySportsWaitForKickoff(): Boolean {
    if (hasRenderedFirstFrame) return false
    val gameId = contentId ?: return false
    if (!SportsLiveGameWatch.shared.waits(gameId)) return false
    if (sportsWaitingJob?.isActive == true) return true
    Log.i(PlayerRuntimeController.TAG, "Sports links not live yet near kickoff: waiting for the stream to start")
    stopPendingSportsStart()
    finishSportsStartup()
    cancelSportsStallWatchdog()
    val start = SportsLiveGameWatch.shared.item(gameId)?.let { item -> item.startMs?.takeIf { item.startHasTimeOfDay } }
    _uiState.update {
        it.copy(
            error = null,
            sportsWaiting = SportsLiveWaitingInfo(start),
            sportsReconnecting = false,
            isBuffering = false,
            // The loading backdrop stays behind the card.
            showLoadingOverlay = true,
            showPauseOverlay = false
        )
    }
    val season = currentSeason
    val episode = currentEpisode
    sportsWaitingJob = scope.launch {
        while (true) {
            delay(SportsLiveKickoffWait.RECHECK_MS)
            if (isInBackground) continue
            // A fresh search (one request per addon), then one probe per addon.
            loadSourceStreams(forceRefresh = true)
            var polls = 0
            while (_uiState.value.isLoadingSourceStreams && polls < SPORTS_FAILOVER_POLL_COUNT) {
                delay(SPORTS_FAILOVER_POLL_INTERVAL_MS)
                polls++
            }
            val streams = (_uiState.value.sourceAllStreams + SportsLiveExtraSources.availableStreamsFor(contentId))
                .filterNot(SportsLiveZapResolver::isNonStream)
            val live = SportsLiveKickoffProbe.liveCheck.firstLive(streams).live
            if (live != null) {
                val playable = (directDebridResolver.resolveToPlayableStream(live, season, episode)
                    as? DirectDebridPlayableResult.Success)?.stream
                if (playable != null && !playable.getStreamUrl().isNullOrBlank()) {
                    Log.i(PlayerRuntimeController.TAG, "A link went live: playing it")
                    _uiState.update { it.copy(sportsWaiting = null) }
                    sportsFailoverRunStartedAtMs = null
                    SportsLiveWorkingLinks.shared.noteLaunch(gameId, playable)
                    switchToSourceStream(playable)
                    return@launch
                }
            }
            if (!SportsLiveGameWatch.shared.waits(gameId)) {
                // The kickoff window passed with nothing live: the usual choices.
                _uiState.update { it.copy(sportsWaiting = null) }
                surfaceSportsFailoverError(context.getString(R.string.sports_live_link_not_working))
                return@launch
            }
        }
    }
    return true
}

/** Leaves the waiting card (#162): a link plays, the viewer picked a source, or the player moved on. */
internal fun PlayerRuntimeController.cancelSportsWaiting() {
    sportsWaitingJob?.cancel()
    sportsWaitingJob = null
    if (_uiState.value.sportsWaiting != null) _uiState.update { it.copy(sportsWaiting = null) }
}

internal fun PlayerRuntimeController.cancelSportsStallWatchdog() {
    sportsStallWatchdogJob?.cancel()
    sportsStallWatchdogJob = null
}
