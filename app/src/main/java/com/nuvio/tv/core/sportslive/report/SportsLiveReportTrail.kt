package com.nuvio.tv.core.sportslive.report

import com.nuvio.tv.domain.model.SportsLiveItem

/**
 * What Report a problem (#172) reads that no other place keeps, in memory only: the screen open, the Sports & Live
 * card focused last, and short rings of the sign-in log and the merge near misses. Release builds strip
 * android.util.Log, so the report reads these, never logcat. Raw text: the report redacts it when it writes.
 */
object SportsLiveReportTrail {
    const val MAX_SIGN_IN = 40
    const val MAX_NEAR_MISSES = 30
    const val MAX_PANEL_ROWS = 40

    @Volatile
    var screen: String? = null
        private set

    @Volatile
    var focused: String? = null
        private set

    /** The Sports & Live card focused last, for a report saved from the list (report #23: "Game: none" for a focused game). */
    @Volatile
    var focusedItem: SportsLiveItem? = null
        private set

    /** The profile's My sports, as a line ("All sports (default)", "Basketball: NBA, NCAAM · Soccer"), #218. */
    @Volatile
    var mySports: String? = null
        private set

    /** The in-player panel's or guide's scope while it shows ("My sports", "All sports", a sport); null when closed (#218). */
    @Volatile
    var panelScope: String? = null
        private set

    /** The open panel's rows, "‹tag› | ‹score shown / hidden / no score›" in order, at most [MAX_PANEL_ROWS] (#218). */
    @Volatile
    var panelRows: List<String> = emptyList()
        private set

    fun onMySports(line: String) {
        mySports = line
    }

    /** The in-player panel shows [scope] with [rows]; a null [scope] (closed) clears both. */
    fun onPanel(scope: String?, rows: List<String>) {
        panelScope = scope
        panelRows = if (scope == null) emptyList() else rows.take(MAX_PANEL_ROWS)
    }

    private val signIn = Ring(MAX_SIGN_IN)
    private val nearMisses = Ring(MAX_NEAR_MISSES)

    /** The navigation route now open. */
    fun onScreen(route: String?) {
        screen = route
    }

    /** A Sports & Live card took focus: its clean [title], and the card itself when known. */
    fun onFocused(title: String, item: SportsLiveItem? = null) {
        focused = title
        focusedItem = item
    }

    /**
     * The game a report describes: the one playing; else, on the Sports & Live list, the focused card (a report from a
     * row is about that row); else the one watched last. Never a stale focus from another screen.
     */
    fun <T> gameOf(playing: T?, focused: T?, lastWatched: T?, screen: String?): T? =
        playing ?: focused.takeIf { screen == SECTION_ROUTE } ?: lastWatched

    private const val SECTION_ROUTE = "sports_live"

    fun onSignInLine(line: String) = signIn.add(line)

    fun onNearMiss(line: String) = nearMisses.add(line)

    fun signInLines(): List<String> = signIn.lines()

    fun nearMissLines(): List<String> = nearMisses.lines()

    /** A bounded list of lines: the oldest goes when a new one passes [max]. Thread-safe. */
    class Ring(private val max: Int) {
        private val lines = ArrayDeque<String>()

        @Synchronized
        fun add(line: String) {
            lines.addLast(line)
            while (lines.size > max) lines.removeFirst()
        }

        @Synchronized
        fun lines(): List<String> = lines.toList()
    }
}
