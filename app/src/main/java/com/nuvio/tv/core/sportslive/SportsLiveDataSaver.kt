package com.nuvio.tv.core.sportslive

/**
 * What Data saver turns off. Data saver is for limited/metered stream providers (e.g. free-tier
 * quotas): when on, a stream resolves only on an explicit play or zap-settle, so the on-focus
 * conveniences that each cost a resolve request are suppressed.
 */
object SportsLiveDataSaver {
    /** Hover preview runs only when its own setting is on AND Data saver is off. */
    fun hoverPreviewAllowed(hoverPreviewSetting: Boolean, dataSaver: Boolean): Boolean =
        hoverPreviewSetting && !dataSaver

    /** The on-focus ended-probe runs only when Data saver is off. */
    fun onFocusProbeAllowed(dataSaver: Boolean): Boolean = !dataSaver

    /** live.20 B2: streams are looked up ahead (a rested row, the zap neighbours) only when Data saver is off. */
    fun prefetchAllowed(dataSaver: Boolean): Boolean = !dataSaver
}
