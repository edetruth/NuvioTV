package com.nuvio.tv.core

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import com.google.common.collect.ImmutableSet
import com.nuvio.tv.core.debug.MuteGuard
import com.nuvio.tv.core.debug.TestMute
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TestMuteTest {

    @Test
    fun `debug build with the flag file mutes`() {
        assertTrue(TestMute.shouldMute(debugBuild = true, flagFileExists = true))
    }

    @Test
    fun `debug build without the flag file does not mute`() {
        assertFalse(TestMute.shouldMute(debugBuild = true, flagFileExists = false))
    }

    @Test
    fun `release build never mutes even with the flag file`() {
        assertFalse(TestMute.shouldMute(debugBuild = false, flagFileExists = true))
        assertFalse(TestMute.shouldMute(debugBuild = false, flagFileExists = false))
    }

    @Test
    fun `flag file name is test-mute`() {
        assertEquals("test-mute", TestMute.FILE_NAME)
    }

    @Test
    fun `volume set back to one is reset to zero while active`() {
        val player = mockk<Player>(relaxed = true)
        MuteGuard(player) { true }.onVolumeChanged(1f)
        verify(exactly = 1) { player.volume = 0f }
    }

    @Test
    fun `volume zero is left alone so the reset cannot loop`() {
        val player = mockk<Player>(relaxed = true)
        MuteGuard(player) { true }.onVolumeChanged(0f)
        verify(exactly = 0) { player.volume = any() }
    }

    @Test
    fun `volume untouched when inactive`() {
        val player = mockk<Player>(relaxed = true)
        MuteGuard(player) { false }.onVolumeChanged(1f)
        verify(exactly = 0) { player.volume = any() }
    }

    private fun params(disabled: Set<Int>, rebuilt: TrackSelectionParameters? = null): TrackSelectionParameters {
        val params = mockk<TrackSelectionParameters>()
        every { params.disabledTrackTypes } returns ImmutableSet.copyOf(disabled)
        if (rebuilt != null) {
            val builder = mockk<TrackSelectionParameters.Builder>()
            every { builder.setTrackTypeDisabled(any(), any()) } returns builder
            every { builder.build() } returns rebuilt
            every { params.buildUpon() } returns builder
        }
        return params
    }

    @Test
    fun `audio re-enabled by a track selection change is disabled again`() {
        val player = mockk<Player>(relaxed = true)
        val fixed = mockk<TrackSelectionParameters>()
        MuteGuard(player) { true }.onTrackSelectionParametersChanged(params(emptySet(), fixed))
        verify(exactly = 1) { player.trackSelectionParameters = fixed }
    }

    @Test
    fun `track selection change with audio already disabled is left alone`() {
        val player = mockk<Player>(relaxed = true)
        MuteGuard(player) { true }.onTrackSelectionParametersChanged(params(setOf(C.TRACK_TYPE_AUDIO)))
        verify(exactly = 0) { player.trackSelectionParameters = any() }
    }

    @Test
    fun `track selection change untouched when inactive`() {
        val player = mockk<Player>(relaxed = true)
        MuteGuard(player) { false }.onTrackSelectionParametersChanged(params(emptySet()))
        verify(exactly = 0) { player.trackSelectionParameters = any() }
    }
}
