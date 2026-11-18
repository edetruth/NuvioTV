package com.nuvio.tv.ui.screens.player

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** QA C1: an unparseable container at start is a failed link, so sports failover moves on at once. */
class SportsUnplayableStartTest {

    @Test
    fun `an unsupported container before the first frame fails over at once`() {
        assertTrue(sportsFailsOverAtOnce(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, hasRenderedFirstFrame = false))
    }

    @Test
    fun `the same error after the first frame takes the normal recovery`() {
        assertFalse(sportsFailsOverAtOnce(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, hasRenderedFirstFrame = true))
    }

    @Test
    fun `other errors take the normal recovery`() {
        assertFalse(sportsFailsOverAtOnce(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, hasRenderedFirstFrame = false))
        assertFalse(sportsFailsOverAtOnce(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, hasRenderedFirstFrame = false))
    }
}
