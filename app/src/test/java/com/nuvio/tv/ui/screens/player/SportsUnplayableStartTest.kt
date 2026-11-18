package com.nuvio.tv.ui.screens.player

import androidx.media3.common.PlaybackException
import com.nuvio.tv.core.sportslive.SportsLiveWorkingLinks
import com.nuvio.tv.domain.model.Stream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** QA C1: when an unparseable container at start fails over at once, and the black screen after Sources. */
class SportsUnplayableStartTest {

    private val unsupported = PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED

    @Test
    fun `a real link gets the parsing probe before it fails over`() {
        // An extension-less or wrong-MIME HLS link: the probe and its retry run first.
        assertFalse(sportsFailsOverAtOnce(unsupported, hasRenderedFirstFrame = false, probeAttempted = false, isNotice = false))
    }

    @Test
    fun `once the probe was tried, the same error fails over at once`() {
        assertTrue(sportsFailsOverAtOnce(unsupported, hasRenderedFirstFrame = false, probeAttempted = true, isNotice = false))
    }

    @Test
    fun `a notice link fails over at once, with no probe`() {
        assertTrue(sportsFailsOverAtOnce(unsupported, hasRenderedFirstFrame = false, probeAttempted = false, isNotice = true))
    }

    @Test
    fun `after the first frame, or for other errors, the normal recovery runs`() {
        assertFalse(sportsFailsOverAtOnce(unsupported, hasRenderedFirstFrame = true, probeAttempted = true, isNotice = true))
        assertFalse(
            sportsFailsOverAtOnce(
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                hasRenderedFirstFrame = false,
                probeAttempted = true,
                isNotice = true
            )
        )
        assertFalse(
            sportsFailsOverAtOnce(
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                hasRenderedFirstFrame = false,
                probeAttempted = true,
                isNotice = false
            )
        )
    }

    @Test
    fun `the failed link loaded with nothing started since is what brings the recovery overlay back`() {
        val links = SportsLiveWorkingLinks(nowMs = { 0L })
        val failed = "https://cdn.example.invalid/a.m3u8"

        assertFalse(links.failedNow("game", failed))
        links.stoppedPlaying("game", failedUrl = failed)
        assertTrue(links.failedNow("game", failed))
        // A new link was picked and is loading: no overlay over it.
        assertFalse(links.failedNow("game", "https://cdn.example.invalid/b.m3u8"))

        links.startedPlaying("game", stream("https://cdn.example.invalid/b.m3u8"))
        assertFalse(links.failedNow("game", failed))
    }

    private fun stream(url: String) = Stream(
        name = null,
        title = null,
        description = null,
        url = url,
        ytId = null,
        infoHash = null,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = null,
        addonName = "Sports",
        addonLogo = null
    )
}
