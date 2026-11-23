package com.nuvio.tv.core

import com.nuvio.tv.core.debug.TestMute
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
}
