package com.nuvio.tv.ui.screens.sportslive.mini

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
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
 * box's [SportsLiveMiniSlot] as it is laid out ([moveTo]). Sports Live as a whole [enterSection]s while
 * shown: inside it the mini player stays docked through a tab switch, when one stage leaves before the
 * next is laid out (#157), from the slot it last had ([lastSlot], lost at [slotLostAtMs]). Main thread only.
 */
object SportsLiveMiniPlacement {
    private val _spot = MutableStateFlow(SportsLiveMiniSpot.CORNER)
    val spot: StateFlow<SportsLiveMiniSpot> = _spot.asStateFlow()
    private val _slot = MutableStateFlow<SportsLiveMiniSlot?>(null)

    /** The [SportsLiveMiniSpot.STAGE] slot; null until laid out, or for the corner. */
    val slot: StateFlow<SportsLiveMiniSlot?> = _slot.asStateFlow()
    private var owner: Any? = null

    private val _inside = MutableStateFlow(false)

    /** Sports Live is shown (#157): the mini player docks in its stage on every tab, never the corner. */
    val inside: StateFlow<Boolean> = _inside.asStateFlow()
    private var section: Any? = null

    /** The last slot laid out, kept after its stage left. */
    var lastSlot: SportsLiveMiniSlot? = null
        private set

    /** When the stage slot went away (monotonic ms), or null while one is laid out. */
    var slotLostAtMs: Long? = null
        private set

    fun claim(token: Any, spot: SportsLiveMiniSpot, slot: SportsLiveMiniSlot? = null, nowMs: Long = monotonicMs()) {
        owner = token
        _spot.value = spot
        setSlot(slot, nowMs)
    }

    /** The owner's slot moved (laid out again); ignored from anyone else. */
    fun moveTo(token: Any, slot: SportsLiveMiniSlot) {
        if (owner === token) setSlot(slot, monotonicMs())
    }

    fun release(token: Any, nowMs: Long = monotonicMs()) {
        if (owner !== token) return
        owner = null
        _spot.value = SportsLiveMiniSpot.CORNER
        setSlot(null, nowMs)
    }

    /**
     * Sports Live shows again (back from the player that started the mini player): until its stage is laid
     * out the mini player holds the stage's last slot, the same place on every tab, instead of the corner.
     */
    fun enterSection(token: Any, nowMs: Long = monotonicMs()) {
        section = token
        if (_slot.value == null && lastSlot != null) slotLostAtMs = nowMs
        _inside.value = true
    }

    /** Sports Live left: the corner at once. */
    fun leaveSection(token: Any) {
        if (section !== token) return
        section = null
        _inside.value = false
    }

    private fun setSlot(slot: SportsLiveMiniSlot?, nowMs: Long) {
        val had = _slot.value
        if (slot != null) {
            lastSlot = slot
            slotLostAtMs = null
        } else if (had != null) {
            slotLostAtMs = nowMs
        }
        _slot.value = slot
    }

    internal fun monotonicMs(): Long = System.nanoTime() / 1_000_000
}

/** Where the mini player is drawn: docked over a stage's media box, or in the corner. */
sealed interface SportsLiveMiniDock {
    data object Corner : SportsLiveMiniDock

    /** Over [slot]; [held] while it is the last stage's slot, kept through a tab switch. */
    data class Docked(val slot: SportsLiveMiniSlot, val held: Boolean = false) : SportsLiveMiniDock
}

/** The dock or corner decision (#157), a pure function of the placement's state. */
object SportsLiveMiniDocking {
    /** How long a docked mini player keeps the last stage's slot while none is laid out (a tab switch). */
    const val HOLD_MS = 500L

    /**
     * Outside Sports Live ([inside] false) the corner. Inside it, the stage's [slot]; while none is laid out,
     * [lastSlot] for [HOLD_MS] after it went ([lostAtMs]), so a tab switch never flashes the corner. Only a
     * screen of Sports Live with no stage at all (nothing listed) falls back to the corner after the hold.
     */
    fun decide(inside: Boolean, slot: SportsLiveMiniSlot?, lastSlot: SportsLiveMiniSlot?, lostAtMs: Long?, nowMs: Long): SportsLiveMiniDock {
        if (!inside) return SportsLiveMiniDock.Corner
        if (slot != null) return SportsLiveMiniDock.Docked(slot)
        if (lastSlot != null && holding(lostAtMs, nowMs)) return SportsLiveMiniDock.Docked(lastSlot, held = true)
        return SportsLiveMiniDock.Corner
    }

    /** True while a slot lost at [lostAtMs] is still held at [nowMs]; a slot never laid out is not. */
    fun holding(lostAtMs: Long?, nowMs: Long): Boolean = lostAtMs != null && nowMs - lostAtMs < HOLD_MS

    /** How long until the hold of a slot lost at [lostAtMs] ends, from [nowMs]; 0 when it has. */
    fun holdLeftMs(lostAtMs: Long?, nowMs: Long): Long = if (lostAtMs == null) 0L else (HOLD_MS - (nowMs - lostAtMs)).coerceAtLeast(0L)
}

/**
 * The mini player's dock now ([SportsLiveMiniDocking.decide]), recomposed when a hold runs out. The host and
 * Sports Live's "Now playing" card read the same one, so the card shows only while the video is in the corner.
 */
@Composable
fun rememberSportsLiveMiniDock(): SportsLiveMiniDock {
    val inside by SportsLiveMiniPlacement.inside.collectAsState()
    val spot by SportsLiveMiniPlacement.spot.collectAsState()
    val slot by SportsLiveMiniPlacement.slot.collectAsState()
    val stageSlot = slot?.takeIf { spot == SportsLiveMiniSpot.STAGE }
    val lostAt = SportsLiveMiniPlacement.slotLostAtMs.takeIf { stageSlot == null }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(lostAt) {
        val left = SportsLiveMiniDocking.holdLeftMs(lostAt, SportsLiveMiniPlacement.monotonicMs())
        if (left > 0) {
            delay(left)
            tick++
        }
    }
    // Reading the tick recomposes this when a hold runs out.
    val nowMs = tick.let { SportsLiveMiniPlacement.monotonicMs() }
    return SportsLiveMiniDocking.decide(inside, stageSlot, SportsLiveMiniPlacement.lastSlot, lostAt, nowMs)
}

/** Where the mini player's caption ("‹game› · Play/Pause to expand") goes: under the video, or a chip at its top-left. */
enum class SportsLiveMiniCaptionSpot { BELOW, TOP_START }

/** The mini player's corner geometry in dp, for a window [windowWidthDp] wide (N3). */
object SportsLiveMiniGeometry {
    /**
     * The caption's place (V2 P3): in the corner it sits under the video; in the stage's media box (whose
     * score block sits right under it) a small chip at the video's top-left, so it never covers the bottom
     * 20 % where a broadcast draws its scorebug.
     */
    fun captionSpot(inStage: Boolean): SportsLiveMiniCaptionSpot =
        if (inStage) SportsLiveMiniCaptionSpot.TOP_START else SportsLiveMiniCaptionSpot.BELOW

    /** The share of the video's height, from its bottom, the caption must never cover (a broadcast's scorebug). */
    const val SCOREBUG_SHARE = 0.2f

    /** True when a caption [top]..[bottom] px inside a video [height] px tall stays clear of its scorebug band. */
    fun clearOfScorebug(top: Float, bottom: Float, height: Float): Boolean = top >= 0f && bottom <= height * (1f - SCOREBUG_SHARE)

    /** Its distance from the window's right and bottom edges. */
    const val EDGE_DP = 24f

    /**
     * The chip's widest on a page (coordinator 2026-11-20): the room right of a Game or Team page header's second logo
     * at the top right of the safe area, so it never covers the header.
     */
    const val CHIP_MAX_WIDTH_DP = 130f

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
