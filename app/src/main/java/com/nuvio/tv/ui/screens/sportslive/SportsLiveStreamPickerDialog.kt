package com.nuvio.tv.ui.screens.sportslive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.core.sportslive.SportsLiveLinkGroups
import com.nuvio.tv.core.sportslive.displayName
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens

/**
 * Choose stream… (stream picker cleanup, docs/design/stream-picker-cleanup.md): the V2 sheet with the player's Sources
 * rows ([SportsLiveSourceRow]), so both pickers look and work the same. The heading is the clean matchup (local time,
 * never UTC) over "6 links · 3 sources"; each link carries its source's clean name at the end instead of per-addon
 * headers; links that failed for this game (#156) go under "Didn't work", and the sources' messages (N2) last under
 * "From the sources", as small lines nothing can focus or play. The first link takes focus; picking one plays it; Back
 * closes.
 */
@Composable
internal fun SportsLiveStreamPickerDialog(
    item: SportsLiveItem,
    streams: List<Stream>,
    onPick: (Stream) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val notices = remember(streams) { streams.filter(SportsLiveZapResolver::isNoticeEntry).toSet() }
    // Links that failed for this game (#156) go to a trailing "Didn't work" group; protected ones too, unless hidden (#166).
    val linkMarks = rememberSportsLiveLinkMarks(item.id, knownSports = true)
    val grouped = remember(streams, notices, linkMarks) {
        val noticesLast = sportsLiveNoticesLast(streams) { it in notices }
        linkMarks?.group(noticesLast, isNotice = { it in notices }) ?: SportsLiveLinkGroups(noticesLast, -1)
    }
    val ordered = grouped.items
    val headers = remember(grouped, notices) {
        sportsLivePickerHeaders(
            size = ordered.size,
            isNotice = { ordered[it] in notices },
            isFailed = { grouped.didntWorkFrom >= 0 && it >= grouped.didntWorkFrom }
        )
    }
    val unknownLink = stringResource(R.string.stream_unknown)
    val tagged = remember(ordered, notices, unknownLink) { sportsLiveSourceTaggedIndexes(ordered, unknownLink) { it in notices } }
    val heading = rememberMoreHeading(item, null)
    val title = remember(heading.title) { sportsLiveLocalStamps(context, heading.title) ?: heading.title }
    val (links, sources) = remember(streams, notices) { sportsLivePickerCounts(streams) { it in notices } }
    val status = stringResource(
        R.string.sports_live_picker_count,
        pluralStringResource(R.plurals.sports_live_picker_links, links, links),
        pluralStringResource(R.plurals.sports_live_sources_count, sources, sources)
    )
    SportsLiveSheet(
        title = title,
        status = status,
        statusLive = false,
        onDismiss = onDismiss,
        onBack = null,
        width = SportsLiveTokens.Sheet.PickerWidth
    ) { first ->
        ordered.forEachIndexed { index, stream ->
            val header = headers[index]
            if (stream in notices) {
                SportsLiveSourceNoticeRow(stream, header = header)
                return@forEachIndexed
            }
            header?.let { SportsLiveSourceGroupHeader(it) }
            SportsLiveSourceRow(
                stream = stream,
                focusRequester = null,
                requestInitialFocus = false,
                isCurrentStream = false,
                nowPlayingAnimates = false,
                linkMark = linkMarks?.markOf(stream),
                linkMarkNowMs = linkMarks?.nowMs ?: 0L,
                onClick = { onPick(stream) },
                onFocusChanged = null,
                onUpKey = null,
                modifier = first(),
                showSource = index in tagged
            )
        }
    }
}

/**
 * The picker's group headers (stream picker cleanup): the Sources panel's ([sportsLiveSourceGroupHeaders]) with no
 * playing link, and none over the first links, which need no "Links" heading of their own.
 */
internal fun sportsLivePickerHeaders(
    size: Int,
    isNotice: (Int) -> Boolean,
    isFailed: (Int) -> Boolean
): Map<Int, SportsLiveSourceGroup> =
    sportsLiveSourceGroupHeaders(size, currentIndex = -1, isNotice = isNotice, isFailed = isFailed)
        .filterValues { it != SportsLiveSourceGroup.LINKS }

/** The picker's count line: (links, sources). A source's messages are not links, and a source with only messages is not counted. */
internal fun sportsLivePickerCounts(streams: List<Stream>, isNotice: (Stream) -> Boolean): Pair<Int, Int> {
    val links = streams.filterNot(isNotice)
    return links.size to links.mapTo(HashSet()) { it.addonName }.size
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
