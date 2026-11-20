package com.nuvio.tv.core.sportslive.report

/**
 * What Report a problem (#172) reads that no other place keeps, in memory only: the screen open, the Sports & Live
 * card focused last, and short rings of the sign-in log and the merge near misses. Release builds strip
 * android.util.Log, so the report reads these, never logcat. Raw text: the report redacts it when it writes.
 */
object SportsLiveReportTrail {
    const val MAX_SIGN_IN = 40
    const val MAX_NEAR_MISSES = 30

    @Volatile
    var screen: String? = null
        private set

    @Volatile
    var focused: String? = null
        private set

    private val signIn = Ring(MAX_SIGN_IN)
    private val nearMisses = Ring(MAX_NEAR_MISSES)

    /** The navigation route now open. */
    fun onScreen(route: String?) {
        screen = route
    }

    /** A Sports & Live card took focus: its clean [title]. */
    fun onFocused(title: String) {
        focused = title
    }

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
