package com.nuvio.tv.ui.screens.sportslive.mini

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where the mini player sits (N3): the bottom-right [CORNER], or [TIMELINE_PANEL], a little smaller at
 * the right end of the Timeline's bottom panel, whose buttons keep clear of it. #74 later picks a free
 * corner for every layout.
 */
enum class SportsLiveMiniSpot { CORNER, TIMELINE_PANEL }

/**
 * The spot the showing screen asks for. A screen [claim]s a spot while composed and [release]s it on
 * leaving; a release by a screen that no longer owns the spot does nothing. Main thread only.
 */
object SportsLiveMiniPlacement {
    private val _spot = MutableStateFlow(SportsLiveMiniSpot.CORNER)
    val spot: StateFlow<SportsLiveMiniSpot> = _spot.asStateFlow()
    private var owner: Any? = null

    fun claim(token: Any, spot: SportsLiveMiniSpot) {
        owner = token
        _spot.value = spot
    }

    fun release(token: Any) {
        if (owner !== token) return
        owner = null
        _spot.value = SportsLiveMiniSpot.CORNER
    }
}

/** The mini player's geometry in dp, for a window [windowWidthDp] wide (N3). */
object SportsLiveMiniGeometry {
    /** Its distance from the window's right and bottom edges. */
    const val EDGE_DP = 24f

    /** Its caption line under the video (hint text and padding). */
    const val CAPTION_DP = 28f

    /** The ring and its inset around the video. */
    const val FRAME_DP = 6f

    /** The space kept between the panel's buttons and the mini player. */
    const val GAP_DP = 12f

    fun widthFraction(spot: SportsLiveMiniSpot): Float = when (spot) {
        SportsLiveMiniSpot.CORNER -> 0.24f
        SportsLiveMiniSpot.TIMELINE_PANEL -> 0.2f
    }

    fun widthDp(spot: SportsLiveMiniSpot, windowWidthDp: Float): Float = windowWidthDp * widthFraction(spot)

    /** Its full height (video, frame and caption), without the bottom edge. */
    fun heightDp(spot: SportsLiveMiniSpot, windowWidthDp: Float): Float =
        (widthDp(spot, windowWidthDp) - FRAME_DP) * 9f / 16f + FRAME_DP + CAPTION_DP

    /**
     * How much of its right end the Timeline panel leaves free, given the screen's own end padding
     * [screenEndPaddingDp]: the mini player's width, its edge distance and a gap, less that padding.
     */
    fun timelinePanelReserveDp(windowWidthDp: Float, screenEndPaddingDp: Float): Float =
        (widthDp(SportsLiveMiniSpot.TIMELINE_PANEL, windowWidthDp) + EDGE_DP + GAP_DP - screenEndPaddingDp).coerceAtLeast(0f)
}
