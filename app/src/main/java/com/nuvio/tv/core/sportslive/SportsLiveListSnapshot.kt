package com.nuvio.tv.core.sportslive

import com.nuvio.tv.domain.model.ClaimedCatalog
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.domain.model.SportsLiveTab
import com.nuvio.tv.domain.repository.SportsLiveLoad
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import java.net.URI

/**
 * The last Sports & Live list built for a profile (live.20 B1, stale-while-revalidate): its tabs as bucketed, so the
 * section opens on it at once, marked "Updated ‹time›", while the catalogs refresh behind it.
 *
 * In memory it also says what it was built from ([load], [claimedKeys], [addonOrder], [directoryStamp], [builtAtMs]),
 * so coming back to the section while that load is still the latest reuses it instead of bucketing again. On the
 * device only [savedAtMs] and [tabs] are kept, without addon URLs ([SportsLiveListSnapshots.encode]).
 */
data class SportsLiveListSnapshot(
    /** When the catalogs it shows were loaded: the "Updated" time. */
    val savedAtMs: Long,
    val tabs: Map<SportsLiveTab, List<SportsLiveItem>>,
    /** In memory only: the load it was bucketed from. */
    val load: SportsLiveLoad? = null,
    val claimedKeys: Set<String> = emptySet(),
    val addonOrder: List<String> = emptyList(),
    /** In memory only: the team directory and identities as they were (a change merges copies differently). */
    val directoryStamp: Long = 0L,
    /** When it was bucketed (the clock it read). */
    val builtAtMs: Long = savedAtMs
)

/** Where the section keeps its last list (live.20 B1): in memory for the process, and on the device per profile. */
interface SportsLiveListCache {
    /** The active profile's list in memory (this process), or null; never reads the device. */
    fun peek(): SportsLiveListSnapshot?

    /** The active profile's list: in memory, else from the device; null when there is none. */
    suspend fun read(): SportsLiveListSnapshot?

    /** Keeps [snapshot] as the active profile's list, in memory at once and on the device soon after. */
    fun save(snapshot: SportsLiveListSnapshot)

    companion object {
        /** Keeps nothing (tests, previews). */
        val NONE: SportsLiveListCache = object : SportsLiveListCache {
            override fun peek(): SportsLiveListSnapshot? = null
            override suspend fun read(): SportsLiveListSnapshot? = null
            override fun save(snapshot: SportsLiveListSnapshot) = Unit
        }
    }
}

/**
 * The list's form on the device and back (live.20 B1). Compact and privacy-safe: no addon URL (each item's is taken
 * from the catalogs claimed when it is restored), no image hosted on an addon's own host (its path may hold the
 * install's token), only what the lists draw. A list too old, of another version or unreadable restores as nothing.
 */
object SportsLiveListSnapshots {
    const val VERSION = 1

    /** A list older than this is not shown: Live now would mislead (games long over). */
    const val MAX_AGE_MS = 6 * 60 * 60 * 1000L

    /** Items kept per tab on the device (Channels can hold thousands; the refresh brings the rest). */
    const val MAX_PER_TAB = 300

    fun encode(snapshot: SportsLiveListSnapshot, moshi: Moshi): String {
        val tabs = snapshot.tabs.mapNotNull { (tab, items) ->
            if (tab == SportsLiveTab.RESULTS || items.isEmpty()) null else SportsLiveListTabDto(tab.name, items.take(MAX_PER_TAB).map(::toDto))
        }
        return moshi.adapter(SportsLiveListFileDto::class.java).toJson(SportsLiveListFileDto(VERSION, snapshot.savedAtMs, tabs))
    }

    /** The list in [json] with every addon URL empty (see [restore]); null when unreadable or of another version. */
    fun decode(json: String?, moshi: Moshi): SportsLiveListSnapshot? {
        if (json.isNullOrBlank()) return null
        val dto = try {
            moshi.adapter(SportsLiveListFileDto::class.java).fromJson(json)
        } catch (e: Exception) {
            null
        } ?: return null
        if (dto.version != VERSION || dto.savedAtMs <= 0L) return null
        val tabs = HashMap<SportsLiveTab, List<SportsLiveItem>>()
        for (tabDto in dto.tabs) {
            val tab = SportsLiveTab.entries.firstOrNull { it.name == tabDto.tab } ?: continue
            tabs[tab] = tabDto.items.mapNotNull(::fromDto)
        }
        return SportsLiveListSnapshot(savedAtMs = dto.savedAtMs, tabs = tabs)
    }

    /**
     * [snapshot]'s tabs to show at [nowMs] for the catalogs [claimed] now: only items of claimed catalogs (a merged
     * copy of an unclaimed one is dropped from its game), each with its catalog's addon URL, and moved by the clock
     * ([SportsLiveItemBucketer.moveKickedOff]: a game that has started since is listed live). Every tab is present.
     * Null when it is older than [MAX_AGE_MS] (or from the future), when nothing is claimed, or when nothing is left.
     */
    fun restore(snapshot: SportsLiveListSnapshot, claimed: List<ClaimedCatalog>, nowMs: Long): Map<SportsLiveTab, List<SportsLiveItem>>? {
        val age = nowMs - snapshot.savedAtMs
        if (age < 0L || age > MAX_AGE_MS || claimed.isEmpty()) return null
        val baseUrls = claimed.associate { it.key to it.addon.baseUrl }
        fun attach(item: SportsLiveItem): SportsLiveItem? {
            val baseUrl = baseUrls[item.catalogKey] ?: return null
            val alternates = item.alternates.mapNotNull { attach(it) }
            val unchanged = baseUrl == item.addonBaseUrl && alternates.size == item.alternates.size &&
                alternates.indices.all { alternates[it] === item.alternates[it] }
            return if (unchanged) item else item.copy(addonBaseUrl = baseUrl, alternates = alternates)
        }
        val tabs = SportsLiveTab.entries.associateWith { tab -> snapshot.tabs[tab].orEmpty().mapNotNull { attach(it) } }
        if (tabs.values.all { it.isEmpty() }) return null
        return SportsLiveItemBucketer.moveKickedOff(tabs, nowMs)
    }

    private fun toDto(item: SportsLiveItem): SportsLiveListItemDto {
        val host = hostOf(item.addonBaseUrl)
        fun image(url: String?): String? = url?.takeUnless { host != null && hostOf(it) == host }
        val meta = item.meta
        return SportsLiveListItemDto(
            id = meta.id,
            type = meta.rawType,
            name = meta.name,
            poster = image(meta.poster),
            posterShape = meta.posterShape.name,
            background = image(meta.background),
            logo = image(meta.logo),
            landscapePoster = image(meta.landscapePoster),
            description = meta.description,
            releaseInfo = meta.releaseInfo,
            released = meta.released,
            genres = meta.genres,
            imdbRating = meta.imdbRating,
            runtime = meta.runtime,
            status = meta.status,
            ageRating = meta.ageRating,
            language = meta.language,
            country = meta.country,
            addonId = item.addonId,
            addonName = item.addonName,
            catalogKey = item.catalogKey,
            catalogName = item.catalogName,
            kind = item.kind.name,
            isLive = item.isLive,
            isChannel = item.isChannel,
            startMs = item.startMs,
            sport = item.sport,
            startHasTimeOfDay = item.startHasTimeOfDay,
            viaOverride = item.viaOverride,
            group = item.group,
            isFinished = item.isFinished,
            home = item.teams?.home,
            away = item.teams?.away,
            liveByClock = item.liveByClock,
            alternates = item.alternates.map(::toDto)
        )
    }

    private fun fromDto(dto: SportsLiveListItemDto): SportsLiveItem? {
        if (dto.id.isEmpty() || dto.addonId.isEmpty() || dto.catalogKey.isEmpty()) return null
        val kind = SportsLiveKind.entries.firstOrNull { it.name == dto.kind } ?: return null
        val meta = MetaPreview(
            id = dto.id,
            type = ContentType.fromString(dto.type),
            rawType = dto.type,
            name = dto.name,
            poster = dto.poster,
            posterShape = PosterShape.entries.firstOrNull { it.name == dto.posterShape } ?: PosterShape.LANDSCAPE,
            background = dto.background,
            logo = dto.logo,
            description = dto.description,
            releaseInfo = dto.releaseInfo,
            imdbRating = dto.imdbRating,
            genres = dto.genres,
            runtime = dto.runtime,
            status = dto.status,
            ageRating = dto.ageRating,
            language = dto.language,
            released = dto.released,
            country = dto.country,
            landscapePoster = dto.landscapePoster
        )
        val home = dto.home
        val away = dto.away
        return SportsLiveItem(
            meta = meta,
            addonId = dto.addonId,
            addonName = dto.addonName,
            // Never stored: [restore] takes it from the claimed catalog.
            addonBaseUrl = "",
            catalogKey = dto.catalogKey,
            catalogName = dto.catalogName,
            kind = kind,
            isLive = dto.isLive,
            isChannel = dto.isChannel,
            startMs = dto.startMs,
            sport = dto.sport,
            startHasTimeOfDay = dto.startHasTimeOfDay,
            viaOverride = dto.viaOverride,
            group = dto.group,
            isFinished = dto.isFinished,
            teams = if (home != null && away != null) TeamPair(home, away) else null,
            alternates = dto.alternates.mapNotNull(::fromDto),
            liveByClock = dto.liveByClock
        )
    }

    private fun hostOf(url: String?): String? =
        if (url.isNullOrBlank()) null else runCatching { URI(url.trim()).host?.lowercase() }.getOrNull()
}

@JsonClass(generateAdapter = true)
data class SportsLiveListFileDto(
    val version: Int = 0,
    val savedAtMs: Long = 0L,
    val tabs: List<SportsLiveListTabDto> = emptyList()
)

@JsonClass(generateAdapter = true)
data class SportsLiveListTabDto(
    val tab: String = "",
    val items: List<SportsLiveListItemDto> = emptyList()
)

@JsonClass(generateAdapter = true)
data class SportsLiveListItemDto(
    val id: String = "",
    val type: String = "",
    val name: String = "",
    val poster: String? = null,
    val posterShape: String = "",
    val background: String? = null,
    val logo: String? = null,
    val landscapePoster: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    val released: String? = null,
    val genres: List<String> = emptyList(),
    val imdbRating: Float? = null,
    val runtime: String? = null,
    val status: String? = null,
    val ageRating: String? = null,
    val language: String? = null,
    val country: String? = null,
    val addonId: String = "",
    val addonName: String = "",
    val catalogKey: String = "",
    val catalogName: String = "",
    val kind: String = "",
    val isLive: Boolean = false,
    val isChannel: Boolean = false,
    val startMs: Long? = null,
    val sport: String? = null,
    val startHasTimeOfDay: Boolean = false,
    val viaOverride: Boolean = false,
    val group: String? = null,
    val isFinished: Boolean = false,
    val home: String? = null,
    val away: String? = null,
    val liveByClock: Boolean = false,
    val alternates: List<SportsLiveListItemDto> = emptyList()
)
