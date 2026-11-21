package com.nuvio.tv.ui.screens.sportslive

import android.content.Context
import com.nuvio.tv.R
import com.nuvio.tv.core.sportslive.SportsLiveLocalTime
import com.nuvio.tv.core.sportslive.displayName
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
