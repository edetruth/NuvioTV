package com.nuvio.tv.ui.screens.sportslive.mini

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where the mini player sits: the bottom-right [CORNER], or [STAGE], inside the media slot of Live Now's
 * stage (V2, #132), where it takes the preview's place so nothing overlaps.
 */
enum class SportsLiveMiniSpot { CORNER, STAGE }

/** A slot in window pixels: where the stage's media box is drawn. */
data class SportsLiveMiniSlot(val x: Int, val y: Int, val width: Int, val height: Int)

/**
 * The spot the showing screen asks for. A screen [claim]s a spot while composed and [release]s it on
 * leaving; a release by a screen that no longer owns the spot does nothing. The stage passes its media
 * box's [SportsLiveMiniSlot] as it is laid out ([moveTo]). Main thread only.
 */
object SportsLiveMiniPlacement {
    private val _spot = MutableStateFlow(SportsLiveMiniSpot.CORNER)
    val spot: StateFlow<SportsLiveMiniSpot> = _spot.asStateFlow()
    private val _slot = MutableStateFlow<SportsLiveMiniSlot?>(null)

    /** The [SportsLiveMiniSpot.STAGE] slot; null until laid out, or for the corner. */
    val slot: StateFlow<SportsLiveMiniSlot?> = _slot.asStateFlow()
    private var owner: Any? = null

    fun claim(token: Any, spot: SportsLiveMiniSpot, slot: SportsLiveMiniSlot? = null) {
        owner = token
        _spot.value = spot
        _slot.value = slot
    }

    /** The owner's slot moved (laid out again); ignored from anyone else. */
    fun moveTo(token: Any, slot: SportsLiveMiniSlot) {
        if (owner === token) _slot.value = slot
    }

    fun release(token: Any) {
        if (owner !== token) return
        owner = null
        _spot.value = SportsLiveMiniSpot.CORNER
        _slot.value = null
    }
}

/** The mini player's corner geometry in dp, for a window [windowWidthDp] wide (N3). */
object SportsLiveMiniGeometry {
    /** Its distance from the window's right and bottom edges. */
    const val EDGE_DP = 24f

    /** Its caption line under the video (hint text and padding). */
    const val CAPTION_DP = 28f

    /** The ring and its inset around the video. */
    const val FRAME_DP = 6f

    /** The space kept between the panel's buttons and the mini player. */
    const val GAP_DP = 12f

    /** Its width in the corner, as a share of the window's. */
    const val CORNER_WIDTH_FRACTION = 0.24f

    fun widthDp(windowWidthDp: Float): Float = windowWidthDp * CORNER_WIDTH_FRACTION

    /** Its full height in the corner (video, frame and caption), without the bottom edge. */
    fun heightDp(windowWidthDp: Float): Float =
        (widthDp(windowWidthDp) - FRAME_DP) * 9f / 16f + FRAME_DP + CAPTION_DP

    /**
     * How much of its bottom a details pane leaves free while the mini player sits in its corner (QA pass
     * 3), given the screen's own bottom padding [screenBottomPaddingDp]: the player's height, its edge
     * distance and a gap, less that padding. Its buttons then never sit under the video.
     */
    fun cornerReserveBottomDp(windowWidthDp: Float, screenBottomPaddingDp: Float): Float =
        (heightDp(windowWidthDp) + EDGE_DP + GAP_DP - screenBottomPaddingDp).coerceAtLeast(0f)
}
