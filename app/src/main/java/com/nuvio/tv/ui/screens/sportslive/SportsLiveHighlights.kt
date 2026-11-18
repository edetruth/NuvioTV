package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.scores.GameHighlight
import com.nuvio.tv.ui.navigation.Screen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** One link of a highlight clip to hand to the player. */
data class HighlightClipPlay(val clipId: String, val url: String, val title: String)

/** What follows a clip ending or failing: another link or clip to play, or back to the Game page. */
sealed interface HighlightStep {
    /** The clip given up on (every link failed): toast it and hide it; null otherwise. */
    val failedClipId: String?

    data class Play(val clip: HighlightClipPlay, override val failedClipId: String? = null) : HighlightStep
    data class Done(override val failedClipId: String? = null) : HighlightStep
}

/**
 * The clips of one OK (a single clip) or one "Watch all highlights" (every clip, in order). A clip
 * that ends goes on to the next; a link that fails goes on to the clip's next link, and a clip whose
 * links all failed is given up on and the next clip plays (#61).
 */
class HighlightPlaylist(clips: List<GameHighlight>) {
    private val clips = clips.filter { it.links.isNotEmpty() }
    private var clipIndex = 0
    private var linkIndex = 0

    /** What plays now; null once the playlist is over (or was empty). */
    val current: HighlightClipPlay?
        get() = clips.getOrNull(clipIndex)?.let { clip -> HighlightClipPlay(clip.id, clip.links[linkIndex], clip.title) }

    /** Whether [url] is one of this playlist's links. */
    fun owns(url: String?): Boolean = url != null && clips.any { url in it.links }

    /** The clip playing ended: the next clip, else done. */
    fun ended(): HighlightStep = nextClip(failedClipId = null)

    /** The link playing failed: the clip's next link, else the clip is given up on and the next clip plays. */
    fun failed(): HighlightStep {
        val clip = clips.getOrNull(clipIndex) ?: return HighlightStep.Done()
        if (linkIndex + 1 < clip.links.size) {
            linkIndex++
            return HighlightStep.Play(current!!)
        }
        return nextClip(failedClipId = clip.id)
    }

    private fun nextClip(failedClipId: String?): HighlightStep {
        if (clipIndex < clips.size) clipIndex++
        linkIndex = 0
        return current?.let { HighlightStep.Play(it, failedClipId) } ?: HighlightStep.Done(failedClipId)
    }
}

/**
 * The highlight playback in progress (one at a time) and the clips that failed this session, which the
 * Game page hides. The player asks [isClip] so a clip, often under two minutes, still counts as ended
 * when it ends; the navigation drives the playlist through [ended], [failed] and [stop].
 */
object SportsLiveHighlights {
    private val failedIds = MutableStateFlow<Set<String>>(emptySet())

    /** Clips given up on this session: hidden on every Game page until the app restarts. */
    val failedClipIds: StateFlow<Set<String>> = failedIds.asStateFlow()

    @Volatile
    private var playlist: HighlightPlaylist? = null

    /** Starts playing [clips] in order (clips failed earlier are left out); the first to play, or null. */
    fun start(clips: List<GameHighlight>): HighlightClipPlay? {
        val skipped = failedIds.value
        val next = HighlightPlaylist(clips.filterNot { it.id in skipped })
        playlist = next.takeIf { it.current != null }
        return next.current
    }

    /** Whether [url] is a link of the highlights playing now. */
    fun isClip(url: String?): Boolean = playlist?.owns(url) == true

    fun ended(): HighlightStep = step { it.ended() }

    fun failed(): HighlightStep = step { it.failed() }

    /** Back from the player: the playlist ends. */
    fun stop() {
        playlist = null
    }

    /**
     * The player that played [url] is gone (zap, party, closed, any exit): the playlist ends, unless it
     * has already moved on to another link or clip, which a newer player plays.
     */
    fun playerLeft(url: String?) {
        if (url != null && playlist?.current?.url == url) playlist = null
    }

    private fun step(advance: (HighlightPlaylist) -> HighlightStep): HighlightStep {
        val current = playlist ?: return HighlightStep.Done()
        val step = advance(current)
        step.failedClipId?.let { id -> failedIds.update { it + id } }
        if (step is HighlightStep.Done) playlist = null
        return step
    }

    /** Test hook: forgets the session's playlist and failures. */
    internal fun reset() {
        playlist = null
        failedIds.value = emptySet()
    }
}

/**
 * The Player route of a highlight clip: a plain url, played as a transient playback. With no content
 * id, type or video id the player saves no watch progress or Continue watching entry, sends no Trakt
 * scrobble or addon player event, caches no link and runs no sports failover or working-links marks.
 */
fun highlightPlayerRoute(clip: HighlightClipPlay, launchStartedAtMs: Long): String = Screen.Player.createRoute(
    streamUrl = clip.url,
    title = clip.title,
    contentId = null,
    contentType = null,
    videoId = null,
    autoPlayNav = false,
    launchStartedAtMs = launchStartedAtMs
)
