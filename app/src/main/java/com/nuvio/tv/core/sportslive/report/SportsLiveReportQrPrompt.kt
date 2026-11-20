package com.nuvio.tv.core.sportslive.report

/**
 * Whether a report saved from the remote (#172) opens its QR code dialog at once: only when adb is off (the coordinator
 * cannot pull the file, so the QR code is the way out) and no video plays (the full player, dual screen or the mini
 * player), so a report never covers a game. Otherwise the toast alone says where to find it.
 */
object SportsLiveReportQrPrompt {
    /** The full player's route starts with this (Screen.Player). */
    const val PLAYER_ROUTE_PREFIX = "player/"

    /** Dual screen's route (Screen.SportsLiveDual). */
    const val DUAL_ROUTE = "sports_live_dual"

    fun opensAfterSave(adbOn: Boolean, route: String?, miniPlaying: Boolean): Boolean =
        !adbOn && !miniPlaying && !isPlayback(route)

    fun isPlayback(route: String?): Boolean =
        route != null && (route.startsWith(PLAYER_ROUTE_PREFIX) || route == DUAL_ROUTE)
}
