package com.nuvio.tv.core.sportslive

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsLiveDataSaverTest {

    @Test
    fun `hover preview needs its setting on and data saver off`() {
        assertTrue(SportsLiveDataSaver.hoverPreviewAllowed(hoverPreviewSetting = true, dataSaver = false))
        assertFalse(SportsLiveDataSaver.hoverPreviewAllowed(hoverPreviewSetting = true, dataSaver = true))
        assertFalse(SportsLiveDataSaver.hoverPreviewAllowed(hoverPreviewSetting = false, dataSaver = false))
    }

    @Test
    fun `the on-focus probe runs only when data saver is off`() {
        assertTrue(SportsLiveDataSaver.onFocusProbeAllowed(dataSaver = false))
        assertFalse(SportsLiveDataSaver.onFocusProbeAllowed(dataSaver = true))
    }
}
