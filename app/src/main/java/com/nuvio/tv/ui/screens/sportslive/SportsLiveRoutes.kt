package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.ui.navigation.Screen

/**
 * [returnToHomeOnBack]: true from Home's Live now row, false from the section so Back returns to it.
 * [manualSelection]: the user picks on the Stream screen (no autoplay there), as "Choose stream" asks.
 */
fun streamRouteFor(item: SportsLiveItem, returnToHomeOnBack: Boolean, manualSelection: Boolean = false): String = Screen.Stream.createRoute(
    videoId = item.id,
    contentType = item.apiType,
    title = item.meta.name,
    poster = item.meta.poster,
    backdrop = item.meta.backdropUrl,
    logo = item.meta.logo,
    contentId = item.id,
    contentName = item.meta.name,
    manualSelection = manualSelection,
    returnToHomeOnBack = returnToHomeOnBack
)
