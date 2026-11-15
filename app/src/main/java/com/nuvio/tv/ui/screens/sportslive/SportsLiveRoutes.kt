package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.ui.navigation.Screen

fun streamRouteFor(item: SportsLiveItem): String = Screen.Stream.createRoute(
    videoId = item.id,
    contentType = item.apiType,
    title = item.meta.name,
    poster = item.meta.poster,
    backdrop = item.meta.backdropUrl,
    logo = item.meta.logo,
    contentId = item.id,
    contentName = item.meta.name,
    returnToHomeOnBack = true
)
