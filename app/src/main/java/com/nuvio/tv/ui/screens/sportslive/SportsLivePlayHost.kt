package com.nuvio.tv.ui.screens.sportslive

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.SportsLiveItem

/**
 * Runs [playViewModel] for a screen that plays Sports & Live cards (the section, Home's Live now
 * row): its events navigate while the screen is started, its lookup and picker show as dialogs,
 * and leaving the screen cancels a lookup so it does not navigate on return. [onOpenStreamScreen]
 * gets whether the Stream screen opens for the user to pick (no autoplay there).
 */
@Composable
fun SportsLivePlayHost(
    playViewModel: SportsLivePlayViewModel,
    onOpenStreamScreen: (item: SportsLiveItem, manualSelection: Boolean) -> Unit,
    onPlayTarget: (SportsLiveZapTarget) -> Unit
) {
    val context = LocalContext.current
    val playState by playViewModel.state.collectAsStateWithLifecycle()
    val currentOnOpenStreamScreen by rememberUpdatedState(onOpenStreamScreen)
    val currentOnPlayTarget by rememberUpdatedState(onPlayTarget)
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(playViewModel, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            playViewModel.events.collect { event ->
                when (event) {
                    is SportsLivePlayEvent.OpenStreamScreen -> {
                        if (event.noStreamFound) {
                            Toast.makeText(context, context.getString(R.string.sports_live_open_choose_stream), Toast.LENGTH_SHORT).show()
                        }
                        currentOnOpenStreamScreen(event.item, event.manualSelection)
                    }
                    is SportsLivePlayEvent.Play -> {
                        currentOnPlayTarget(event.target)
                        // Shown over the player for a few seconds: where the auto-picked stream comes from.
                        event.source?.let { source ->
                            Toast.makeText(context, context.getString(R.string.sports_live_playing_from, source), Toast.LENGTH_LONG).show()
                        }
                    }
                    is SportsLivePlayEvent.NoStream -> Toast.makeText(
                        context,
                        context.getString(R.string.sports_live_zap_no_stream, event.name),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }
    DisposableEffect(lifecycleOwner, playViewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) playViewModel.dismiss()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    when (val state = playState) {
        is SportsLivePlayState.Resolving -> SportsLiveResolvingDialog(state.item, onDismiss = playViewModel::dismiss)
        is SportsLivePlayState.Picking -> SportsLiveStreamPickerDialog(
            item = state.item,
            streams = state.streams,
            onPick = playViewModel::pick,
            onDismiss = playViewModel::dismiss
        )
        SportsLivePlayState.Idle -> Unit
    }
}
