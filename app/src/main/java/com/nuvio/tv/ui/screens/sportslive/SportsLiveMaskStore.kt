package com.nuvio.tv.ui.screens.sportslive

import android.content.Context

/**
 * Where the ids that mask a hidden replay (and mark Sports & Live playback) are kept across process death (#458, #362):
 * the back stack restores a Detail page, Stream screen or player, and they must mask as before. [read] once at app
 * start, [write] after each change.
 */
interface SportsLiveMaskStore {
    fun read(): String?
    fun write(text: String)
}

/** [SportsLiveMaskStore] in a small private preferences file; written in the background ([android.content.SharedPreferences.Editor.apply]). */
class SportsLiveMaskPrefs(context: Context) : SportsLiveMaskStore {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun read(): String? = prefs.getString(KEY, null)

    override fun write(text: String) {
        prefs.edit().putString(KEY, text).apply()
    }

    private companion object {
        const val FILE = "sports_live_masks"
        const val KEY = "masks"
    }
}

/**
 * The saved masks: the spoiler-safe ids, each folder's videos, the Sports & Live playback ids, and when they were saved.
 * Kept [EXPIRY_MS] (a back stack restored later than that starts afresh); at most [MAX_VIDEOS] videos per folder.
 */
internal data class SportsLiveMasks(
    val savedAtMs: Long,
    val spoilerSafe: List<String>,
    val videos: Map<String, Set<String>>,
    val sportsPlayback: List<String>
) {
    fun encode(): String = buildString {
        append(VERSION).append(SEP).append(savedAtMs)
        spoilerSafe.filter(::storable).forEach { append('\n').append(SAFE).append(SEP).append(it) }
        sportsPlayback.filter(::storable).forEach { append('\n').append(PLAYBACK).append(SEP).append(it) }
        videos.filterKeys(::storable).forEach { (folder, ids) ->
            append('\n').append(VIDEOS).append(SEP).append(folder)
            ids.filter(::storable).takeLast(MAX_VIDEOS).forEach { append(SEP).append(it) }
        }
    }

    companion object {
        const val EXPIRY_MS = 24 * 60 * 60_000L
        const val MAX_VIDEOS = 500
        private const val VERSION = "v1"
        private const val SAFE = "s"
        private const val PLAYBACK = "p"
        private const val VIDEOS = "w"
        private const val SEP = '\u001F'

        private fun storable(id: String): Boolean = id.isNotBlank() && '\n' !in id && SEP !in id

        /** [text] as saved, or null when it is missing, unreadable or older than [EXPIRY_MS] at [nowMs]. */
        fun decode(text: String?, nowMs: Long): SportsLiveMasks? {
            val lines = text?.split('\n') ?: return null
            val head = lines.first().split(SEP)
            if (head.size != 2 || head[0] != VERSION) return null
            val savedAt = head[1].toLongOrNull() ?: return null
            if (nowMs - savedAt !in 0L until EXPIRY_MS) return null
            val safe = ArrayList<String>()
            val playback = ArrayList<String>()
            val videos = LinkedHashMap<String, Set<String>>()
            for (line in lines.drop(1)) {
                val fields = line.split(SEP)
                when {
                    fields.size == 2 && fields[0] == SAFE -> safe += fields[1]
                    fields.size == 2 && fields[0] == PLAYBACK -> playback += fields[1]
                    fields.size >= 2 && fields[0] == VIDEOS -> videos[fields[1]] = fields.drop(2).toSet()
                }
            }
            return SportsLiveMasks(savedAt, safe, videos, playback)
        }
    }
}
