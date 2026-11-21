package com.nuvio.tv.ui.screens.addon

import android.content.Context
import com.nuvio.tv.R
import com.nuvio.tv.core.network.NetworkResult

/** Why adding an addon failed, in the words a viewer can act on (#203). */
internal enum class AddonInstallFailure { BLOCKED, CLEARTEXT, NO_HOST, TIMEOUT, REFUSED, NOT_FOUND, SERVER, OTHER }

/**
 * [message] and [code] of a failed manifest fetch as a [AddonInstallFailure] (#203). The raw text is the
 * exception's message (OkHttp / the platform's TLS stack) or the HTTP status line, as upstream's stream errors
 * read it too: "Unable to parse TLS packet header" and other TLS / certificate failures are a network in the way
 * (an ISP filter or a captive portal answering in plain text).
 */
internal fun addonInstallFailureOf(message: String, code: Int?): AddonInstallFailure {
    val text = message.lowercase()
    return when {
        code == 404 -> AddonInstallFailure.NOT_FOUND
        code != null && code >= 500 -> AddonInstallFailure.SERVER
        "cleartext" in text -> AddonInstallFailure.CLEARTEXT
        BLOCKED_MARKERS.any { it in text } -> AddonInstallFailure.BLOCKED
        "unable to resolve host" in text || "no address associated" in text -> AddonInstallFailure.NO_HOST
        // A connect timeout reads "failed to connect to … after 10000ms".
        "timeout" in text || "timed out" in text || CONNECT_TIMEOUT.containsMatchIn(text) -> AddonInstallFailure.TIMEOUT
        REFUSED_MARKERS.any { it in text } -> AddonInstallFailure.REFUSED
        else -> AddonInstallFailure.OTHER
    }
}

private val BLOCKED_MARKERS = listOf("tls", "ssl", "handshake", "certificate", "trust anchor", "chain validation")
private val CONNECT_TIMEOUT = Regex("""after \d+\s?ms""")
private val REFUSED_MARKERS =listOf("failed to connect", "connection refused", "connection reset", "unexpected end of stream")

/**
 * The install error a viewer reads (#203): what happened and what to do, in plain words. The raw text stays in
 * the log (the repository logs it); only a failure with no known cause adds it, on a second line.
 */
internal fun addonInstallErrorText(context: Context, error: NetworkResult.Error): String =
    when (addonInstallFailureOf(error.message, error.code)) {
        AddonInstallFailure.BLOCKED -> context.getString(R.string.addon_install_failed_blocked)
        AddonInstallFailure.CLEARTEXT -> context.getString(R.string.addon_install_failed_cleartext)
        AddonInstallFailure.NO_HOST -> context.getString(R.string.addon_install_failed_no_host)
        AddonInstallFailure.TIMEOUT -> context.getString(R.string.addon_install_failed_timeout)
        AddonInstallFailure.REFUSED -> context.getString(R.string.addon_install_failed_refused)
        AddonInstallFailure.NOT_FOUND -> context.getString(R.string.addon_install_failed_not_found)
        AddonInstallFailure.SERVER -> context.getString(R.string.addon_install_failed_server)
        AddonInstallFailure.OTHER -> {
            val plain = context.getString(R.string.addon_install_failed_other)
            val detail = error.message.trim()
            if (detail.isEmpty()) plain else plain + "\n" + context.getString(R.string.addon_install_failed_detail, detail)
        }
    }
