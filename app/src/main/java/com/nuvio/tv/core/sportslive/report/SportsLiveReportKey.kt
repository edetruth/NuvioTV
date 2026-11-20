package com.nuvio.tv.core.sportslive.report

import android.view.KeyEvent

/** What the activity does with a key for Report a problem (#172). */
enum class SportsLiveReportKeyAction {
    /** Not ours: the screens handle it as before. */
    PASS,

    /** A repeat of a held report key, or the release after a report: eaten. */
    CONSUME,

    /** Held long enough: save a report now (once per hold). */
    REPORT
}

/**
 * The remote shortcut for Report a problem (#172): hold one of [KEYS] for [holdMs]. The ★ key of Google TV remotes
 * (KEYCODE_MACRO_1, first: the onn remote has no Play/Pause or Info, TV QA), the TV key (KEYCODE_GUIDE) as a backup,
 * Play/Pause (none has Menu, Google TV quality rule TV-DM) and Info. The first press still does its usual job (the player pauses);
 * the key's repeats are eaten while it is held, so a held Play/Pause no longer flips the player on every repeat and a
 * held Info no longer flickers the stream details. The report fires once per hold, and its release is eaten too.
 * No other key or press changes.
 */
class SportsLiveReportKey(private val holdMs: Long = HOLD_MS) {
    // The down time of the press that already reported; -1 for none.
    private var reportedDownTime = -1L

    fun onKey(keyCode: Int, action: Int, repeatCount: Int, downTime: Long, eventTime: Long): SportsLiveReportKeyAction {
        if (keyCode !in KEYS) return SportsLiveReportKeyAction.PASS
        return when (action) {
            KeyEvent.ACTION_DOWN -> when {
                repeatCount == 0 -> {
                    reportedDownTime = -1L
                    SportsLiveReportKeyAction.PASS
                }
                reportedDownTime == downTime -> SportsLiveReportKeyAction.CONSUME
                eventTime - downTime >= holdMs -> {
                    reportedDownTime = downTime
                    SportsLiveReportKeyAction.REPORT
                }
                else -> SportsLiveReportKeyAction.CONSUME
            }
            KeyEvent.ACTION_UP -> if (reportedDownTime == downTime) {
                reportedDownTime = -1L
                SportsLiveReportKeyAction.CONSUME
            } else {
                SportsLiveReportKeyAction.PASS
            }
            else -> SportsLiveReportKeyAction.PASS
        }
    }

    companion object {
        const val HOLD_MS = 2_000L
        val KEYS = setOf(KeyEvent.KEYCODE_MACRO_1, KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_INFO)
    }
}
