package com.nuvio.tv.core.sportslive

import androidx.media3.common.PlaybackException

/**
 * Why a sports link could not play on this TV (#166), from the player's error and, when that is not
 * conclusive, the link's manifest ([SportsLiveManifestDrm]). Only legitimate playback: a protected
 * link is played only with a licence its addon provides ([SportsLiveStreamLicences]), never otherwise.
 */
enum class SportsLiveLinkCause {
    /** DRM: DASH ContentProtection, or HLS EXT-X-KEY with a Widevine, PlayReady or FairPlay KEYFORMAT. */
    DRM,

    /** The source refused the request (401 or 403): usually a missing Referer, Origin or User-Agent. */
    HEADERS,

    /** A codec or container this TV's decoders cannot play. */
    CODEC,

    /** The link's token expired, or it is blocked in this region (404, 410, 451). */
    EXPIRED_OR_GEO,

    /** The host does not exist or refuses connections. */
    DEAD_HOST,

    /** None of the above, as far as the error tells. */
    OTHER;

    companion object {
        private val DECODER_CODES = setOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
        )

        private val CONTAINER_CODES = setOf(
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED
        )

        /** A decoder could not be found, set up or keep up with the format. */
        fun isDecoderError(errorCode: Int): Boolean = errorCode in DECODER_CODES

        /** The container or manifest format is one the player cannot parse. */
        fun isContainerError(errorCode: Int): Boolean = errorCode in CONTAINER_CODES

        /** Any of the DRM error codes (6000-6999). */
        fun isDrmError(errorCode: Int): Boolean = errorCode in 6000..6999

        /**
         * The cause of a playback error: [errorCode] (a [PlaybackException] code), the HTTP status of a
         * failed request ([httpStatus]) and whether the host was not found ([deadHost]).
         */
        fun classify(errorCode: Int, httpStatus: Int? = null, deadHost: Boolean = false): SportsLiveLinkCause = when {
            isDrmError(errorCode) -> DRM
            deadHost -> DEAD_HOST
            httpStatus == 401 || httpStatus == 403 || httpStatus == 407 -> HEADERS
            httpStatus == 404 || httpStatus == 410 || httpStatus == 451 -> EXPIRED_OR_GEO
            errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> DEAD_HOST
            isDecoderError(errorCode) || isContainerError(errorCode) -> CODEC
            else -> OTHER
        }
    }
}

/**
 * DRM in a manifest's text (#166). DASH: any `ContentProtection` element. HLS: an `EXT-X-KEY` or
 * `EXT-X-SESSION-KEY` whose KEYFORMAT names Widevine, PlayReady or FairPlay (or a FairPlay `skd://`
 * key). Plain AES-128 or SAMPLE-AES with an identity key is ordinary encryption the player handles
 * with the key the playlist names, so it is not DRM.
 */
object SportsLiveManifestDrm {
    enum class Kind { DASH, HLS }

    private const val WIDEVINE_UUID = "edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"
    private const val PLAYREADY_UUID = "9a04f079-9840-4286-ab92-e65be0885f95"
    private val MPD = Regex("""<\s*(?:[A-Za-z0-9_]+:)?MPD\b""")
    private val CONTENT_PROTECTION =Regex("""<\s*(?:[A-Za-z0-9_]+:)?ContentProtection\b""")
    private val HLS_KEY = Regex("""^#EXT-X-(?:SESSION-)?KEY:(.*)$""", RegexOption.MULTILINE)
    private val KEYFORMAT = Regex("""KEYFORMAT\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)
    private val METHOD = Regex("""METHOD\s*=\s*([A-Z0-9-]+)""", RegexOption.IGNORE_CASE)
    private val URI = Regex("""URI\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)

    /** The manifest's kind from its text; null when it is neither a DASH MPD nor an HLS playlist. */
    fun kindOf(text: String): Kind? {
        val head = text.trimStart('﻿', ' ', '\n', '\r', '\t')
        return when {
            head.startsWith("#EXTM3U") -> Kind.HLS
            MPD.containsMatchIn(head) -> Kind.DASH
            else -> null
        }
    }

    /** True when [text] (a DASH MPD or an HLS playlist) needs a DRM licence to play. */
    fun isProtected(text: String): Boolean = when (kindOf(text)) {
        Kind.DASH -> CONTENT_PROTECTION.containsMatchIn(text)
        Kind.HLS -> HLS_KEY.findAll(text).any { isDrmKey(it.groupValues[1]) }
        null -> false
    }

    private fun isDrmKey(attributes: String): Boolean {
        val method = METHOD.find(attributes)?.groupValues?.get(1)?.uppercase()
        if (method == null || method == "NONE") return false
        val format = KEYFORMAT.find(attributes)?.groupValues?.get(1)?.lowercase().orEmpty()
        val uri = URI.find(attributes)?.groupValues?.get(1)?.lowercase().orEmpty()
        return format.contains(WIDEVINE_UUID) ||
            format.contains("com.widevine") ||
            format.contains(PLAYREADY_UUID) ||
            format.contains("com.microsoft.playready") ||
            format.contains("com.apple.streamingkeydelivery") ||
            uri.startsWith("skd://")
    }

    /** An HLS multivariant playlist's first variant (or media) playlist uri, as written; null for a media playlist. */
    fun firstVariantUri(text: String): String? {
        if (kindOf(text) != Kind.HLS) return null
        val lines = text.lineSequence().map(String::trim).toList()
        val streamInf = lines.indexOfFirst { it.startsWith("#EXT-X-STREAM-INF") }
        if (streamInf < 0) return null
        return lines.drop(streamInf + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
    }
}
