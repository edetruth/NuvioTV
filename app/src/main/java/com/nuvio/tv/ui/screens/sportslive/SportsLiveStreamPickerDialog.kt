package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.displayName
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

private const val FOCUS_ATTEMPTS = 5
private const val FOCUS_RETRY_MS = 50L

/**
 * The streams of every source of a merged game, under their addon's name, in the order given
 * (primary's first). Picking one plays it; Back dismisses.
 */
@Composable
internal fun SportsLiveStreamPickerDialog(
    item: SportsLiveItem,
    streams: List<Stream>,
    onPick: (Stream) -> Unit,
    onDismiss: () -> Unit
) {
    // A source's notices (N2) go after every stream, as info rows; otherwise the order is kept as given.
    val notices = remember(streams) { streams.filter(SportsLiveZapResolver::isNonStream).toSet() }
    val ordered = remember(streams, notices) { sportsLiveNoticesLast(streams) { it in notices } }
    val firstPlayable = ordered.firstOrNull { it !in notices }
    // Consecutive streams of one addon share a header.
    val groups = remember(ordered) {
        ordered.fold(mutableListOf<Pair<String, MutableList<Stream>>>()) { acc, stream ->
            val addon = stream.addonName
            if (acc.lastOrNull()?.first == addon) acc.last().second += stream else acc += addon to mutableListOf(stream)
            acc
        }
    }
    val firstFocusRequester = remember { FocusRequester() }
    val firstFocused = remember { mutableStateOf(false) }
    // The first row may not be laid out yet when the dialog opens: retry until it has focus
    // (a request can throw, or be ignored, before the row is attached).
    LaunchedEffect(Unit) {
        repeat(2) { withFrameNanos { } }
        repeat(FOCUS_ATTEMPTS) {
            runCatching { firstFocusRequester.requestFocus() }
            withFrameNanos { }
            if (firstFocused.value) return@LaunchedEffect
            delay(FOCUS_RETRY_MS)
        }
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = item.displayName,
        subtitle = pluralStringResource(R.plurals.sports_live_sources_count, item.sourceCount, item.sourceCount),
        width = 640.dp
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
        ) {
            groups.forEachIndexed { groupIndex, (addon, groupStreams) ->
                item(key = "header-$groupIndex") {
                    Text(
                        text = addon,
                        style = MaterialTheme.typography.titleSmall,
                        color = NuvioTheme.colors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = if (groupIndex == 0) 0.dp else NuvioTheme.spacing.sm)
                    )
                }
                items(groupStreams.size, key = { "stream-$groupIndex-$it" }) { index ->
                    val stream = groupStreams[index]
                    if (stream in notices) {
                        SportsLiveSourceNoticeRow(stream)
                        return@items
                    }
                    StreamButton(
                        stream = stream,
                        onClick = { onPick(stream) },
                        modifier = if (stream === firstPlayable) {
                            Modifier
                                .focusRequester(firstFocusRequester)
                                .onFocusChanged { firstFocused.value = it.isFocused }
                        } else {
                            Modifier
                        }
                    )
                }
            }
        }
    }
}

/** Shown while a merged game's streams are looked up; Back cancels. */
@Composable
internal fun SportsLiveResolvingDialog(item: SportsLiveItem, onDismiss: () -> Unit) {
    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.sports_live_zap_switching, item.displayName)
    ) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            LoadingIndicator()
        }
    }
}

@Composable
private fun StreamButton(stream: Stream, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val name = stream.name?.takeIf { it.isNotBlank() } ?: stream.addonName
    val detail = (stream.title ?: stream.description)?.takeIf { it.isNotBlank() && it != name }
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = ButtonDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            contentColor = NuvioTheme.colors.TextPrimary
        )
    ) {
        Column {
            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail != null) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
