package com.nuvio.tv.ui.screens.sportslive

import android.content.Context
import com.nuvio.tv.R
import com.nuvio.tv.core.sportslive.SportsLiveLocalTime
import com.nuvio.tv.core.sportslive.displayName
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.ui.navigation.Screen
import java.time.ZoneId
import java.util.Locale

/**
 * [returnToHomeOnBack]: true from Home's Live now row, false from the section so Back returns to it.
 * [manualSelection]: the user picks on the Stream screen (no autoplay there), as "Choose stream" asks.
 */
fun streamRouteFor(item: SportsLiveItem, returnToHomeOnBack: Boolean, manualSelection: Boolean = false): String = Screen.Stream.createRoute(
    videoId = item.id,
    contentType = item.apiType,
    title = item.displayName,
    poster = item.meta.poster,
    backdrop = item.meta.backdropUrl,
    logo = item.meta.logo,
    contentId = item.id,
    contentName = item.meta.name,
    manualSelection = manualSelection,
    returnToHomeOnBack = returnToHomeOnBack
)

/**
 * [text] (an addon's release info, which the Stream screen shows beside the title) with every UTC start stamp in
 * the TV's own time (TV QA live.20: "20 Nov 2026 · 23:00 UTC" read as UTC in the header). Other text is unchanged.
 */
internal fun sportsLiveLocalStamps(context: Context, text: String?): String? {
    if (text == null) return null
    val tomorrow = context.getString(R.string.sports_live_tomorrow)
    return SportsLiveLocalTime.localizeUtcStamps(
        text = text,
        nowMs = System.currentTimeMillis(),
        zone = ZoneId.systemDefault(),
        locale = Locale.getDefault()
    ) { time -> String.format(tomorrow, time) }
}

/**
 * [meta], the addon's meta the Stream screen fills its missing artwork and year from and hands the player its next
 * videos from, as it may use it for [videoId]: a spoiler-safe replay's (#223) masked as its Detail page shows it
 * ([spoilerMaskedMeta]: no artwork, release info, summaries; F2: its videos too); any other title's as is.
 */
internal fun sportsLiveStreamScreenMeta(videoId: String?, meta: Meta): Meta =
    if (!SportsLiveExtraSources.isSpoilerSafe(videoId)) meta else spoilerMaskedMeta(meta)

/**
 * Opens [item]'s Detail page (a replay team folder's episodes, More… > Details; #240, #243): a replay whose result is
 * [hidden] for the user (as its card's links: [sportsLiveHidesReplayLinks], or a Game page not showing its result) opens
 * masked ([sportsLiveDetailMeta]); anything else as the addon gives it.
 */
internal fun sportsLiveOpenDetail(item: SportsLiveItem, hidden: Boolean, open: (SportsLiveItem) -> Unit) {
    // Played from there it is Sports & Live playback, as from every other path (F3): no upstream watch progress (the
    // addon's raw card on Home), and a replay's player gets the Hold OK sheet (#254).
    SportsLiveExtraSources.markSportsPlayback(item.id, item)
    SportsLiveExtraSources.markSpoilerSafe(item.id, hidden)
    open(item)
}

/**
 * [meta] as the Detail page for [itemId] shows it (#240, #243): a spoiler-safe replay's ([sportsLiveOpenDetail]) with any
 * score masked in its title and its videos' titles and links, and no description, release info or date, status, awards,
 * running time (it tells extra time), artwork, trailer or episode summary, any of which may carry the result (F7); an
 * episode keeps its date, which only orders the list. Its videos become spoiler-safe too, so an episode's Stream screen
 * and player mask as the replay does. Any other title's as is.
 */
internal fun sportsLiveDetailMeta(itemId: String, meta: Meta): Meta {
    // The page plays under meta.id, which an addon may give differently from the catalog's id (F3, F8).
    if (meta.id.isNotBlank() && meta.id != itemId && SportsLiveExtraSources.isSportsPlayback(itemId)) {
        SportsLiveExtraSources.markSportsPlayback(meta.id)
    }
    val id = listOf(itemId, meta.id).firstOrNull(SportsLiveExtraSources::isSpoilerSafe) ?: return meta
    // The page plays under meta.id, which an addon may give differently from the catalog's id (F8).
    SportsLiveExtraSources.markSpoilerSafeVideos(id, meta.videos.map { it.id } + meta.id)
    return spoilerMaskedMeta(meta)
}

/** [meta] with nothing that may carry a result (see [sportsLiveDetailMeta]): the one mask of an addon's meta. */
private fun spoilerMaskedMeta(meta: Meta): Meta =
    meta.copy(
        name = spoilerSafeLinkText(meta.name) ?: meta.name,
        description = null,
        releaseInfo = null,
        released = null,
        status = null,
        awards = null,
        runtime = null,
        poster = null,
        background = null,
        landscapePoster = null,
        logo = null,
        rawPosterUrl = null,
        seasonPosters = emptyMap(),
        trailerYtIds = emptyList(),
        trailers = emptyList(),
        videos = meta.videos.map { video ->
            video.copy(
                title = spoilerSafeLinkText(video.title) ?: video.title,
                overview = null,
                runtime = null,
                thumbnail = null,
                seasonPoster = null,
                streams = video.streams.map(::spoilerSafeReplayStream)
            )
        }
    )
