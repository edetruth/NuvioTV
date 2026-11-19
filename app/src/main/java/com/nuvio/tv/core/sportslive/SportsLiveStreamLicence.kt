package com.nuvio.tv.core.sportslive

import androidx.media3.common.C
import java.util.UUID

/**
 * A DRM licence server an addon names for its stream (#166): played through Media3's
 * DefaultDrmSessionManager, as any licensed player does. Only a licence server is used; raw content
 * keys an addon may publish are never used ([SportsLiveStreamLicences.parse] reports them as
 * [Parsed.hasRawKeys] for the debug probe's counts only).
 */
data class SportsLiveStreamLicence(
    val scheme: Scheme,
    val licenseUrl: String,
    /** Headers the addon asks for on licence requests, on top of the stream's own request headers. */
    val headers: Map<String, String> = emptyMap()
) {
    enum class Scheme(val uuid: UUID) {
        WIDEVINE(C.WIDEVINE_UUID),
        PLAYREADY(C.PLAYREADY_UUID),
        CLEARKEY(C.CLEARKEY_UUID)
    }

    /** A stream's licence fields as parsed: the [licence] to use (if any), and whether raw keys came too. */
    data class Parsed(val licence: SportsLiveStreamLicence?, val hasRawKeys: Boolean)
}

/**
 * Licence servers addons named for their streams, by link ([SportsLiveWorkingLinks.linkKey]), so every
 * player that builds a media source for the link ([com.nuvio.tv.ui.screens.player.PlayerMediaSourceFactory])
 * finds it without the stream being handed through. In memory only; holds no content key.
 *
 * Addons have no standard field for this, so [parse] accepts the shapes in use: a flat object
 * (`{"type": "widevine", "licenseUrl": "...", "headers": {...}}`, with common spellings of each key)
 * and the dash.js protection-data shape (`{"com.widevine.alpha": {"serverURL": "...", "httpRequestHeaders": {...}}}`),
 * under `behaviorHints.drm`, `behaviorHints.license`, or the stream's own `drm` / `license`.
 */
object SportsLiveStreamLicences {
    private const val MAX_LINKS = 500
    private val SCHEME_KEYS = listOf("keySystem", "keysystem", "key_system", "type", "scheme", "system", "drm")
    private val URL_KEYS = listOf(
        "licenseUrl", "licenseURL", "license_url", "licenseUri", "licenseServer", "license_server",
        "licenseServerUrl", "serverURL", "serverUrl", "la_url", "laUrl", "url", "uri"
    )
    private val HEADER_KEYS = listOf("headers", "licenseHeaders", "license_headers", "httpRequestHeaders", "requestHeaders")
    private val RAW_KEY_KEYS = listOf("keys", "clearKeys", "clearkeys", "clearKey", "clearkey", "kid", "key", "keyId", "license_key")

    private val licences = object : LinkedHashMap<String, SportsLiveStreamLicence>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SportsLiveStreamLicence>?): Boolean =
            size > MAX_LINKS
    }

    /** Links whose stream came with licence fields we could not use (raw keys, or no licence url), for the probe's counts. */
    private val unusable = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > MAX_LINKS
    }

    /** Remembers [licence] for [url] (none: forgets it). */
    fun note(url: String?, licence: SportsLiveStreamLicence?) {
        val key = SportsLiveWorkingLinks.linkKey(url) ?: return
        synchronized(licences) { if (licence == null) licences.remove(key) else licences[key] = licence }
    }

    /** Remembers what an addon sent with [url]'s stream as licence fields ([parse]'s result). */
    fun note(url: String?, parsed: SportsLiveStreamLicence.Parsed) {
        note(url, parsed.licence)
        val key = SportsLiveWorkingLinks.linkKey(url) ?: return
        val unusableKind = when {
            parsed.licence != null -> null
            parsed.hasRawKeys -> "keys"
            else -> "fields"
        }
        synchronized(licences) { if (unusableKind == null) unusable.remove(key) else unusable[key] = unusableKind }
    }

    /**
     * What licence information [url]'s stream carried, for the debug probe's counts: "licence" (a server
     * url, used), "keys" (raw keys only, never used), "fields" (licence fields without a usable server) or "none".
     */
    fun describe(url: String?): String {
        val key = SportsLiveWorkingLinks.linkKey(url) ?: return "none"
        return synchronized(licences) {
            when {
                key in licences -> "licence"
                else -> unusable[key] ?: "none"
            }
        }
    }

    /** The licence its addon named for [url], or null. */
    fun forUrl(url: String?): SportsLiveStreamLicence? {
        val key = SportsLiveWorkingLinks.linkKey(url) ?: return null
        return synchronized(licences) { licences[key] }
    }

    fun has(url: String?): Boolean = forUrl(url) != null

    internal fun clear() = synchronized(licences) {
        licences.clear()
        unusable.clear()
    }

    /** The licence among [candidates] (raw JSON values, in order of preference). */
    fun parse(vararg candidates: Any?): SportsLiveStreamLicence.Parsed {
        var rawKeys = false
        var found: SportsLiveStreamLicence? = null
        for (candidate in candidates) {
            val map = candidate as? Map<*, *> ?: continue
            if (hasRawKeys(map)) rawKeys = true
            if (found == null) found = flat(map) ?: protectionData(map)
        }
        return SportsLiveStreamLicence.Parsed(found, rawKeys)
    }

    private fun flat(map: Map<*, *>): SportsLiveStreamLicence? {
        val url = URL_KEYS.firstNotNullOfOrNull { (map[it] as? String)?.trim()?.takeIf(::isHttpUrl) } ?: return null
        val scheme = SCHEME_KEYS.firstNotNullOfOrNull { (map[it] as? String)?.let(::schemeOf) }
            ?: SportsLiveStreamLicence.Scheme.WIDEVINE
        return SportsLiveStreamLicence(scheme, url, headersOf(map))
    }

    private fun protectionData(map: Map<*, *>): SportsLiveStreamLicence? {
        for ((name, value) in map) {
            val scheme = (name as? String)?.let(::schemeOf) ?: continue
            val inner = value as? Map<*, *> ?: continue
            val url = URL_KEYS.firstNotNullOfOrNull { (inner[it] as? String)?.trim()?.takeIf(::isHttpUrl) } ?: continue
            return SportsLiveStreamLicence(scheme, url, headersOf(inner))
        }
        return null
    }

    private fun hasRawKeys(map: Map<*, *>): Boolean =
        RAW_KEY_KEYS.any { map[it] != null } || map.values.any { (it as? Map<*, *>)?.let(::hasRawKeys) == true }

    private fun headersOf(map: Map<*, *>): Map<String, String> {
        val raw = HEADER_KEYS.firstNotNullOfOrNull { map[it] as? Map<*, *> } ?: return emptyMap()
        return raw.entries.mapNotNull { (k, v) ->
            val key = (k as? String)?.trim()?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
            val value = (v as? String)?.trim()?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
            key to value
        }.toMap()
    }

    internal fun schemeOf(name: String): SportsLiveStreamLicence.Scheme? {
        val n = name.trim().lowercase()
        return when {
            n.contains("widevine") || n == "wv" || n.contains("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed") ->
                SportsLiveStreamLicence.Scheme.WIDEVINE
            n.contains("playready") || n == "pr" || n.contains("9a04f079-9840-4286-ab92-e65be0885f95") ->
                SportsLiveStreamLicence.Scheme.PLAYREADY
            n.contains("clearkey") || n.contains("org.w3.clearkey") || n.contains("e2719d58-a985-b3c9-781a-b030af78d30e") ->
                SportsLiveStreamLicence.Scheme.CLEARKEY
            else -> null
        }
    }

    private fun isHttpUrl(value: String): Boolean =
        value.startsWith("https://", ignoreCase = true) || value.startsWith("http://", ignoreCase = true)
}
