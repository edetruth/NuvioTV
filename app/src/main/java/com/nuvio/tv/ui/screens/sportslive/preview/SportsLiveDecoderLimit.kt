package com.nuvio.tv.ui.screens.sportslive.preview

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.nuvio.tv.BuildConfig

/**
 * The device could not give a second player a decoder while another played (dual screen's device limit, or the
 * full player losing its decoder to a peek). Remembered for this TV model and app version (#106), so no peek tries
 * again after a restart; an app update tests again. [init] loads it once a context is at hand; before that it
 * holds for this run only.
 */
object SportsLiveDecoderLimit {
    private const val PREFS = "sports_live_decoder_limit"
    private const val KEY = "seen_on"

    @Volatile
    var seen: Boolean = false
        private set

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        val store = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = store
        if (store.getString(KEY, null) == deviceKey()) seen = true
        // A limit noted before the store was at hand is kept too.
        if (seen) store.edit().putString(KEY, deviceKey()).apply()
    }

    fun note() {
        seen = true
        prefs?.edit()?.putString(KEY, deviceKey())?.apply()
    }

    internal fun reset() {
        seen = false
    }

    /** This TV model and app version: a new version tests the limit again. */
    private fun deviceKey(): String = "${Build.MANUFACTURER}|${Build.MODEL}|${BuildConfig.VERSION_CODE}"
}
