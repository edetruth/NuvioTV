package com.nuvio.tv.ui.screens.sportslive.preview

import android.app.ActivityManager
import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.sportslive.SportsLiveDataSaver
import com.nuvio.tv.core.sportslive.SportsLiveSourceCooldown
import com.nuvio.tv.data.local.SportsLivePreferencesDataStore
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveTab
import com.nuvio.tv.ui.screens.sportslive.SportsLiveZapResolver
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The device could not give a second player a decoder while another played (dual screen's device
 * limit, or a panel preview's decoder failure): from then on, for this run of the app, the in-player
 * panel never previews (#83), as the device plays one video at a time.
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

/**
 * Whether the in-player panel may preview its focused game (#83): the hover preview setting on, data
 * saver off, and a device that can decode a second video beside the one playing (not a low-RAM device,
 * no decoder limit seen). Otherwise the corner shows the game's logos art.
 */
internal fun panelPreviewAllowed(hoverPreview: Boolean, dataSaver: Boolean, lowRam: Boolean, decoderLimit: Boolean): Boolean =
    SportsLiveDataSaver.hoverPreviewAllowed(hoverPreview, dataSaver) && !lowRam && !decoderLimit

/**
 * The panel preview's lookup (#83): nothing while [item]'s source cools down after a rate limit (C1);
 * otherwise [lookup], the hover preview's budgeted one (a cached resolve, else at most one resolve per
 * game per window and a few a minute across games).
 */
internal suspend fun panelPreviewLookup(
    item: SportsLiveItem,
    cooldown: SportsLiveSourceCooldown,
    lookup: suspend (SportsLiveItem) -> SportsLivePreviewLookup
): SportsLivePreviewLookup =
    if (cooldown.isCoolingDown(item.addonBaseUrl)) SportsLivePreviewLookup.NotPreviewable else lookup(item)

/**
 * The in-player panel's preview of the focused row (#83): the hover preview's controller (Live now,
 * after [settleMs]) fed with the panel's focus, never the game playing. It plays only while the panel
 * is open ([onPanelOpen]), the app is in the foreground ([onForeground]: not behind Home or the
 * screensaver) and [onAllowed]; focus moving, the panel closing or a pick ([onPick]) stop it. It plays
 * at most [maxRowMs] per row (counted from the row taking focus), then stays on the art until focus moves.
 */
class SportsLivePanelPreview(
    private val scope: CoroutineScope,
    resolve: suspend (SportsLiveItem) -> SportsLivePreviewLookup,
    settleMs: Long = SETTLE_MS,
    private val maxRowMs: Long = MAX_ROW_MS
) {
    companion object {
        /** A row rests this long before its preview is looked up. */
        const val SETTLE_MS = 1_000L

        /** A row's preview stops this long after the row took focus: a preview is a glance, not a stream. */
        const val MAX_ROW_MS = 20_000L
    }

    private val controller = SportsLivePreviewController(scope, resolve, settleMs).apply { onTab(SportsLiveTab.LIVE_NOW) }

    val state: StateFlow<SportsLivePreviewState> = controller.state

    private var open = false
    private var foreground = true
    private var focusedKey: String? = null
    private var cap: Job? = null

    /** The focused row's game (null: none, or the chips or a button); [currentKey] is the game playing's stream key. */
    fun onFocus(item: SportsLiveItem?, currentKey: String?) {
        val target = item?.takeUnless { it.streamKey == currentKey }
        if (target?.previewKey != focusedKey) {
            focusedKey = target?.previewKey
            cap?.cancel()
            cap = target?.let {
                scope.launch {
                    delay(maxRowMs)
                    // Still on this row: its preview ends, and stays ended until focus moves.
                    controller.onFocus(null)
                }
            }
        }
        // A row whose time is up stays on its art.
        if (cap?.isCompleted == true) return
        controller.onFocus(target)
    }

    fun onPanelOpen(open: Boolean) {
        this.open = open
        if (!open) stopRow()
        controller.onVisible(open && foreground)
    }

    /** The app came to the foreground (true), or went behind Home, another app or the screensaver. */
    fun onForeground(foreground: Boolean) {
        this.foreground = foreground
        controller.onVisible(open && foreground)
    }

    fun onAllowed(allowed: Boolean) = controller.onEnabled(allowed)

    /** A game was picked: the preview stops before the zap. */
    fun onPick() = stopRow()

    private fun stopRow() {
        cap?.cancel()
        cap = null
        focusedKey = null
        controller.onFocus(null)
    }

    fun onFirstFrame(item: SportsLiveItem) = controller.onFirstFrame(item)

    fun onError(item: SportsLiveItem) = controller.onError(item)
}

/** The in-player panel's preview (#83), gated by the hover preview setting, data saver and the device. */
@HiltViewModel
class SportsLivePanelPreviewViewModel internal constructor(
    resolve: suspend (SportsLiveItem) -> SportsLivePreviewLookup,
    hoverPreview: Flow<Boolean>,
    dataSaver: Flow<Boolean>,
    private val lowRam: Boolean,
    private val onPreviewFailed: (SportsLiveItem) -> Unit
) : ViewModel() {

    @Inject constructor(
        application: Application,
        resolver: SportsLiveZapResolver,
        prefs: SportsLivePreferencesDataStore
    ) : this(
        resolve = { item -> panelPreviewLookup(item, SportsLiveSourceCooldown.shared, resolver::previewLookup) },
        hoverPreview = prefs.hoverPreview,
        dataSaver = prefs.dataSaver,
        lowRam = application.getSystemService(ActivityManager::class.java)?.isLowRamDevice == true,
        onPreviewFailed = resolver::invalidate
    )

    private val preview = SportsLivePanelPreview(viewModelScope, resolve)

    val state: StateFlow<SportsLivePreviewState> = preview.state

    // Re-read whenever the panel opens, so a decoder limit seen meanwhile (dual screen) applies.
    private val decoderLimit = MutableStateFlow(SportsLiveDecoderLimit.seen)

    init {
        viewModelScope.launch {
            combine(hoverPreview, dataSaver, decoderLimit) { hover, saver, limit ->
                panelPreviewAllowed(hover, saver, lowRam, limit)
            }.collect(preview::onAllowed)
        }
    }

    fun onPanelOpen(open: Boolean) {
        if (open) decoderLimit.value = SportsLiveDecoderLimit.seen
        preview.onPanelOpen(open)
    }

    fun onFocus(item: SportsLiveItem?, currentKey: String?) = preview.onFocus(item, currentKey)

    fun onForeground(foreground: Boolean) = preview.onForeground(foreground)

    fun onPick() = preview.onPick()

    fun onFirstFrame(item: SportsLiveItem) = preview.onFirstFrame(item)

    fun onError(item: SportsLiveItem) {
        // Its cached resolve is dropped, so the next play or preview resolves again.
        onPreviewFailed(item)
        preview.onError(item)
    }

    /** The preview found no decoder beside the game playing: no more previews on this device. */
    fun onDecoderLimit() {
        SportsLiveDecoderLimit.note()
        decoderLimit.value = true
    }
}
