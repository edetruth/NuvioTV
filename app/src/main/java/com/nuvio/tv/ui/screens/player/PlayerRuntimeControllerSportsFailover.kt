package com.nuvio.tv.ui.screens.player

import android.util.Log
import androidx.media3.common.Player
import com.nuvio.tv.R
import com.nuvio.tv.core.debrid.DirectDebridPlayableResult
import com.nuvio.tv.core.sportslive.SportsLiveFailover
import com.nuvio.tv.core.sportslive.SportsLiveSourceReliability
import com.nuvio.tv.core.sportslive.SportsLiveStallWatchdog
import com.nuvio.tv.ui.screens.sportslive.SportsLiveExtraSources
import com.nuvio.tv.ui.screens.sportslive.sportsLiveFallbackCandidate
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Sports & Live two-stage error recovery (#28). On a fatal playback error — or a stall after
 * playback has started — the player automatically advances to the next untried source for this
 * game/channel (its own addon's other links plus a merged game's other addons' extras), up to
 * [SPORTS_FAILOVER_MAX_SOURCES] distinct sources per session. When that cap is hit or no untried
 * source remains, the standalone, focusable error overlay is shown so the user picks one by hand.
 *
 * It is scoped to sports playback ([SportsLiveExtraSources.isSportsPlayback]); movies and shows keep
 * upstream behaviour. Already-tried sources are skipped, and the resolve path reuses whatever the
 * shared stream search already found, so a known source costs nothing extra.
 */
internal val SPORTS_FAILOVER_MAX_SOURCES get() = SportsLiveFailover.MAX_SOURCES

private const val SPORTS_FAILOVER_POLL_COUNT = 30
private const val SPORTS_FAILOVER_POLL_INTERVAL_MS = 500L
private const val SPORTS_STALL_POLL_INTERVAL_MS = 2_000L

/**
 * Remembers ([SportsLiveSourceReliability]) that the sports source playing reached its first frame,
 * with its time to first frame. A manual pick in the Sources panel that plays counts too. Sports only.
 */
internal fun PlayerRuntimeController.recordSportsSourceFirstFrame() {
    val key = currentSportsSourceKey() ?: return
    SportsLiveSourceReliability.record(
        key,
        SportsLiveSourceReliability.Outcome.SUCCESS,
        ttffMs = System.currentTimeMillis() - sportsAttemptStartedAtMs
    )
}

/** The reliability key of the sports source playing; null for movies/shows or an unknown source. */
private fun PlayerRuntimeController.currentSportsSourceKey(): String? =
    SportsLiveExtraSources.sourceKeyFor(contentId, currentStreamUrl, currentAddonName)

/**
 * Auto-fails over to the next untried sports source after [errorMessage], or shows the recovery
 * overlay when the cap is reached or nothing untried remains. Returns true when sports handled the
 * error (so the generic error chain stops); false for non-sports playback. The failed source is
 * remembered as a failure, or a stall when [stalled] (the stall watchdog fired).
 */
internal fun PlayerRuntimeController.trySportsLiveFailoverAfterError(errorMessage: String, stalled: Boolean = false): Boolean {
    if (!SportsLiveExtraSources.isSportsPlayback(contentId)) return false
    // The source that just failed must not be re-served from the resolve cache next time.
    SportsLiveExtraSources.invalidateResolve(contentId)
    if (sourceFallbackJob?.isActive == true) return true
    SportsLiveSourceReliability.record(
        currentSportsSourceKey(),
        if (stalled) SportsLiveSourceReliability.Outcome.STALL else SportsLiveSourceReliability.Outcome.FAILURE
    )
    cancelSportsStallWatchdog()

    val failedUrl = currentStreamUrl
    val failedAddonName = currentAddonName
    val failedInfoHash = _uiState.value.currentStreamInfoHash ?: navigationArgs.infoHash
    sportsFailoverTriedKeys += "url:$failedUrl"
    val videoId = currentVideoId ?: contentId
    if (videoId == null || !SportsLiveFailover.canAutoTry(sportsFailoverAttempts)) {
        surfaceSportsFailoverError(errorMessage)
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
        while (SportsLiveFailover.canAutoTry(sportsFailoverAttempts)) {
            val sources = _uiState.value
            val searchDone = !sources.isLoadingSourceStreams &&
                (sources.sourceAllStreams.isNotEmpty() || sources.sourceStreamsError != null)
            val next = sportsLiveFallbackCandidate(
                streams = sources.sourceAllStreams,
                extras = SportsLiveExtraSources.cachedStreamsFor(contentId),
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
                    "(${sportsFailoverAttempts}/$SPORTS_FAILOVER_MAX_SOURCES)"
            )
            switchToSourceStream(playable)
            sportsFailoverTriedKeys += "url:$currentStreamUrl"
            return@launch
        }
        surfaceSportsFailoverError(errorMessage)
    }
    return true
}

/** Shows the standalone, focusable recovery overlay (Try another source / Retry / Back). */
private fun PlayerRuntimeController.surfaceSportsFailoverError(errorMessage: String) {
    streamCacheKey?.let { key -> scope.launch { streamLinkCacheDataStore.remove(key) } }
    _uiState.update {
        it.copy(
            error = errorMessage,
            canChooseAnotherSource = true,
            isBuffering = false,
            showLoadingOverlay = false,
            showPauseOverlay = false
        )
    }
}

/**
 * After the first frame, watches for a stale source (endless buffering / position not advancing) and
 * triggers the same auto-failover. Sports only; it never runs during startup. Idempotent.
 */
internal fun PlayerRuntimeController.scheduleSportsStallWatchdog() {
    if (!SportsLiveExtraSources.isSportsPlayback(contentId)) return
    // Cancel-and-restart so each new source's first frame rebaselines the progress tracking.
    cancelSportsStallWatchdog()
    sportsStallWatchdogJob = scope.launch {
        var lastPosition = currentPlaybackPositionMs() ?: 0L
        var lastProgressAtMs = System.currentTimeMillis()
        while (true) {
            delay(SPORTS_STALL_POLL_INTERVAL_MS)
            if (_uiState.value.error != null || isInBackground || !hasRenderedFirstFrame) {
                lastProgressAtMs = System.currentTimeMillis()
                continue
            }
            val player = _exoPlayer
            val position = currentPlaybackPositionMs() ?: lastPosition
            val buffering = player?.playbackState == Player.STATE_BUFFERING
            val advancing = position > lastPosition
            val paused = player?.playWhenReady == false || userPausedManually
            if (advancing || paused) {
                lastPosition = position
                lastProgressAtMs = System.currentTimeMillis()
                continue
            }
            val stalledForMs = System.currentTimeMillis() - lastProgressAtMs
            // Only a buffering/no-progress state counts; a paused-but-ready player is left alone above.
            if (buffering && SportsLiveStallWatchdog.isStalled(hasStartedPlaying = true, stalledForMs = stalledForMs)) {
                Log.w(PlayerRuntimeController.TAG, "Sports source stalled ${stalledForMs}ms after start, failing over")
                val message = context.getString(R.string.player_error_source_stalled)
                trySportsLiveFailoverAfterError(message, stalled = true)
                return@launch
            }
        }
    }
}

internal fun PlayerRuntimeController.cancelSportsStallWatchdog() {
    sportsStallWatchdogJob?.cancel()
    sportsStallWatchdogJob = null
}
