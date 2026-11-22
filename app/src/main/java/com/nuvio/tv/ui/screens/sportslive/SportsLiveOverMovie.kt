package com.nuvio.tv.ui.screens.sportslive

/**
 * Context audit G30: a movie or show plays in the full player, not Sports & Live content. Sports toasts
 * ([SportsLiveToastHost]) and the held Play/Pause or Info report shortcut ([SportsLiveReporter.onKey]) stay out of it;
 * alert cards follow their own rule (over a movie, kickoffs only). The app-wide overlays ([SportsLiveActivityOverlays])
 * keep the screen on top here; whether its content is a game is read when asked, as the alerts host does.
 */
object SportsLiveOverMovie {
    @Volatile
    private var route: String? = null

    @Volatile
    private var contentId: String? = null

    /** The screen on top is [route], the player's [contentId] when it is the player. */
    fun onScreen(route: String?, contentId: String?) {
        this.route = route
        this.contentId = contentId
    }

    /** A movie or show plays now. */
    val now: Boolean
        get() = sportsLiveMoviePlaying(route, contentId, SportsLiveExtraSources::isSportsPlayback)
}

/** A movie or show plays on [route]: the full player, with [contentId] not Sports & Live content ([isSports]). */
internal fun sportsLiveMoviePlaying(route: String?, contentId: String?, isSports: (String?) -> Boolean): Boolean =
    alertPlayRoute(route, contentId, isSports) == AlertPlayRoute.REPLACE_MOVIE
