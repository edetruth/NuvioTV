package com.nuvio.tv.ui.screens.sportslive.preview

/**
 * The device could not give a second player a decoder while another played (dual screen's device
 * limit), noted for this run of the app. The in-player panel that read it no longer previews (live.20, #106).
 */
object SportsLiveDecoderLimit {
    @Volatile
    var seen: Boolean = false
        private set

    fun note() {
        seen = true
    }

    internal fun reset() {
        seen = false
    }
}
