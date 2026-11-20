package com.nuvio.tv.core.sportslive.identify

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import java.io.File

/**
 * What the device has learned about team names (#168), by [TeamIdentityRules.nameKey]. A name that was
 * looked up keeps its teams for good; a name no source knows is unknown for [UNKNOWN_TTL_MS], then asked
 * again. Every name and alternate name of a found team is a key too, so "BC Dubai" is known once "Dubai
 * Basketball" is; such a key still gets its own look, merged in. A failed request is never recorded.
 */
internal class TeamIdentityCache(private val maxEntries: Int = MAX_ENTRIES) {

    /** [looked]: this key itself was looked up (else it only holds teams found under another name). */
    data class Entry(
        val candidates: List<TeamCandidate>,
        val looked: Boolean,
        val checkedAtMs: Long,
        /** Sports a hinted look already tried for this name ([TeamIdentityRules.hintSport]). */
        val hinted: Set<String>
    )

    private val entries = HashMap<String, Entry>()

    /** [name]'s teams (empty: known to be unknown); null when it must be looked up. */
    @Synchronized
    fun answer(name: String, now: Long): List<TeamCandidate>? {
        val entry = entries[TeamIdentityRules.nameKey(name)] ?: return null
        if (!entry.looked) return null
        if (entry.candidates.isEmpty() && now - entry.checkedAtMs !in 0 until UNKNOWN_TTL_MS) return null
        return entry.candidates
    }

    @Synchronized
    fun hinted(name: String): Set<String> = entries[TeamIdentityRules.nameKey(name)]?.hinted.orEmpty()

    /** Records a lookup of [name] that answered [found] (empty: no source knows it). */
    @Synchronized
    fun record(name: String, found: List<TeamCandidate>, now: Long) {
        val key = TeamIdentityRules.nameKey(name).takeIf { it.isNotEmpty() } ?: return
        val old = entries[key]
        // A name that was unknown is asked afresh, hinted looks included.
        val hinted = if (old != null && old.candidates.isNotEmpty()) old.hinted else emptySet()
        entries[key] = Entry(merge(old?.candidates.orEmpty(), found), looked = true, checkedAtMs = now, hinted = hinted)
        spread(key, found, now)
        trim()
    }

    /** Records a hinted look for [name] in [sport] that answered [found] (empty: none). */
    @Synchronized
    fun recordHint(name: String, sport: String, found: List<TeamCandidate>, now: Long) {
        val key = TeamIdentityRules.nameKey(name).takeIf { it.isNotEmpty() } ?: return
        val old = entries[key] ?: Entry(emptyList(), looked = false, checkedAtMs = now, hinted = emptySet())
        entries[key] = old.copy(candidates = merge(old.candidates, found), hinted = old.hinted + sport)
        spread(key, found, now)
        trim()
    }

    // Each found team under its own name and alternates as well.
    private fun spread(own: String, found: List<TeamCandidate>, now: Long) {
        for (team in found) {
            for (text in listOf(team.name) + team.alternates) {
                val key = TeamIdentityRules.nameKey(text)
                if (key.isEmpty() || key == own) continue
                val entry = entries[key]
                entries[key] = entry?.copy(candidates = merge(entry.candidates, listOf(team)))
                    ?: Entry(listOf(team), looked = false, checkedAtMs = now, hinted = emptySet())
            }
        }
    }

    private fun merge(old: List<TeamCandidate>, new: List<TeamCandidate>): List<TeamCandidate> {
        if (new.isEmpty()) return old
        return (old + new).distinctBy { it.sport to TeamIdentityRules.nameKey(it.name) }
    }

    // Oldest first past the bound.
    private fun trim() {
        if (entries.size <= maxEntries) return
        entries.entries.sortedBy { it.value.checkedAtMs }.take(entries.size - maxEntries).map { it.key }.forEach { entries.remove(it) }
    }

    @Synchronized
    fun snapshot(): List<TeamIdentityEntryDto> = entries.map { (key, entry) ->
        TeamIdentityEntryDto(key, entry.looked, entry.checkedAtMs, entry.hinted.toList(), entry.candidates)
    }

    /** Adds [stored] entries (from [TeamIdentityStore.read]) under any already learned. */
    @Synchronized
    fun restore(stored: List<TeamIdentityEntryDto>) {
        for (dto in stored) {
            if (dto.key.isEmpty() || dto.key in entries) continue
            entries[dto.key] = Entry(dto.candidates, dto.looked, dto.checkedAtMs, dto.hinted.toSet())
        }
        trim()
    }

    companion object {
        /** A name no source knows is asked again after a week. */
        const val UNKNOWN_TTL_MS = 7L * 24 * 60 * 60 * 1000
        /** Names kept in memory and on disk. */
        const val MAX_ENTRIES = 4_000
    }
}

/** The cache's file in app storage (`sportslive/team_identity.json`). Failure-silent: an unreadable file is empty. */
class TeamIdentityStore(private val file: File, moshi: Moshi) {
    private val adapter = moshi.adapter(TeamIdentityFileDto::class.java)

    fun read(): List<TeamIdentityEntryDto> = try {
        if (!file.isFile) {
            emptyList()
        } else {
            adapter.fromJson(file.readText())?.takeIf { it.version == VERSION }?.entries.orEmpty()
                .filter { dto -> dto.key.isNotEmpty() && dto.candidates.all { it.sport.isNotEmpty() && it.name.isNotEmpty() } }
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun write(entries: List<TeamIdentityEntryDto>) {
        try {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(adapter.toJson(TeamIdentityFileDto(VERSION, entries)))
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
        } catch (e: Exception) {
            // Failure-silent: the next write tries again.
        }
    }

    companion object {
        private const val VERSION = 1
    }
}

@JsonClass(generateAdapter = true)
data class TeamIdentityFileDto(val version: Int = 0, val entries: List<TeamIdentityEntryDto> = emptyList())

@JsonClass(generateAdapter = true)
data class TeamIdentityEntryDto(
    val key: String = "",
    val looked: Boolean = false,
    val checkedAtMs: Long = 0L,
    val hinted: List<String> = emptyList(),
    val candidates: List<TeamCandidate> = emptyList()
)
