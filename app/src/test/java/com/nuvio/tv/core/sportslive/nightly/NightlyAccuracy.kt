package com.nuvio.tv.core.sportslive.nightly

import com.nuvio.tv.core.sportslive.SportsLiveCatalogPolicy
import com.nuvio.tv.core.sportslive.SportsLiveDisplayText
import com.nuvio.tv.core.sportslive.SportsLiveDupes
import com.nuvio.tv.core.sportslive.SportsLiveItemBucketer
import com.nuvio.tv.core.sportslive.SportsLiveSportInference
import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.core.sportslive.SportsLiveUnidentified
import com.nuvio.tv.core.sportslive.scores.EspnLeague
import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
import com.nuvio.tv.core.sportslive.scores.EspnScoreProvider
import com.nuvio.tv.core.sportslive.scores.EspnScoreboardDto
import com.nuvio.tv.core.sportslive.scores.LiveScoresRepository
import com.nuvio.tv.core.sportslive.scores.ScoreCard
import com.nuvio.tv.core.sportslive.scores.ScoreMatcher
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoreTeam
import com.nuvio.tv.core.sportslive.scores.TeamDirectory
import com.nuvio.tv.core.sportslive.scores.TennisMatcher
import com.nuvio.tv.data.mapper.toDomain
import com.nuvio.tv.data.mapper.toDomainOrNull
import com.nuvio.tv.data.remote.dto.AddonManifestDto
import com.nuvio.tv.data.remote.dto.CatalogResponseDto
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.ClaimedCatalog
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveTab
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * #192, the PC-side nightly accuracy run: the user's sports addons' live and upcoming games, through the app's own
 * bucketer, sport inference and [ScoreMatcher], against ESPN's public scoreboards. Writes a dated report (addons as
 * hashed labels, no links), appends `disagreements.csv` and `reliability.csv` in [outDir], and keeps the ESPN
 * scoreboards a finding came from under `raw/<run>/espn/` as ready test fixtures. Never writes into the repo.
 *
 * [addonsDir] holds one addon manifest URL per `*.txt` file (first non-blank line); other URLs are skipped.
 */
internal class NightlyAccuracy(
    private val outDir: File,
    private val addonsDir: File,
    private val upcomingHours: Int = 12,
    private val nowMs: Long = System.currentTimeMillis(),
    private val zone: ZoneId = ZoneId.systemDefault()
) {
    private val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()
    // ESPN gets the app's request: league and date, a generic User-Agent.
    private val espnHttp = http.newBuilder()
        .addNetworkInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", EspnScoreProvider.USER_AGENT).removeHeader("Accept-Language").build())
        }
        .build()
    private val matcher = ScoreMatcher({ zone })
    private val strictMatcher = ScoreMatcher({ zone }, minScore = ScoreMatcher.BOTH_STRONG)
    private val boardAdapter = moshi.adapter(EspnScoreboardDto::class.java)

    private val runId = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm").withZone(zone).format(Instant.ofEpochMilli(nowMs))
    private val rawDir = File(outDir, "raw/$runId/espn")
    private val secrets = LinkedHashMap<String, String>()
    private val notes = ArrayList<String>()
    private val boards = HashMap<Pair<EspnLeague, LocalDate>, List<ScoreSnapshot>?>()
    private val boardFileOf = HashMap<String, String>()
    private var boardRequests = 0
    private var boardFailures = 0

    private class Source(val label: String, val addon: Addon, val items: List<SportsLiveItem>, val catalogs: Int, val failedCatalogs: Int)

    class Alias(val cardSide: String, val matchedSide: String, val espnName: String, val espnTeamId: String?, val event: ScoreSnapshot)

    class Check(
        val label: String,
        val row: SportsLiveItem,
        val live: Boolean,
        val verdict: Verdict,
        /** The ESPN game the verdict is about: the match, or the later meeting. */
        val espn: ScoreSnapshot? = null,
        val appCatches: Boolean = false,
        val leagues: List<EspnLeague> = emptyList(),
        val aliases: List<Alias> = emptyList()
    )

    /** Runs once and writes everything; returns the short summary. */
    fun run(): String {
        outDir.mkdirs()
        val sources = loadSources()
        loadDirectory(sources)
        val checks = sources.flatMap { checkSource(it) }
        val allItems = sources.flatMap { it.items }
        val order = sources.map { it.addon.id }
        val mergedLive = SportsLiveItemBucketer.bucketTab(allItems, SportsLiveTab.LIVE_NOW, nowMs, order)
        val mergedUpcoming = SportsLiveItemBucketer.bucketTab(allItems, SportsLiveTab.UPCOMING, nowMs, order)
        val dupes = SportsLiveDupes.scan(mergedLive, nowMs)
        val nearMisses = SportsLiveDupes.nearMisses(mergedLive + mergedUpcoming.filter { isSoon(it) }, zone)
        return NightlyWriter(
            outDir = outDir, runId = runId, nowMs = nowMs, zone = zone, upcomingHours = upcomingHours,
            secrets = secrets, notes = notes, boardFileOf = boardFileOf,
            sources = sources.map { NightlyWriter.SourceInfo(it.label, it.catalogs, it.failedCatalogs) },
            skippedFiles = skippedFiles, boardRequests = boardRequests, boardFailures = boardFailures,
            directoryLists = directoryLists
        ).write(checks, dupes, nearMisses)
    }

    // ---- Addons ----

    private var skippedFiles = 0

    private fun loadSources(): List<Source> {
        val files = addonsDir.listFiles { file -> file.isFile && file.name.endsWith(".txt") }.orEmpty().sortedBy { it.name }
        val sources = ArrayList<Source>()
        for (file in files) {
            val url = file.readLines().map { it.removePrefix("\uFEFF").trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
            val path = url?.substringBefore('?')
            if (url == null || path == null || !path.endsWith(MANIFEST)) {
                skippedFiles++
                continue
            }
            val label = NightlyText.label(url)
            remember(url, label)
            val base = path.removeSuffix(MANIFEST) + url.substring(path.length)
            remember(base, label)
            runCatching { URI(path).host }.getOrNull()?.let { remember(it, label) }
            // A long path segment is a token.
            path.split('/').filter { it.length >= TOKEN_LENGTH && it != MANIFEST.removePrefix("/") }.forEach { remember(it, label) }
            val body = get(http, url)
            val dto = body?.let { runCatching { moshi.adapter(AddonManifestDto::class.java).fromJson(it) }.getOrNull() }
            if (dto == null) {
                notes += "$label: manifest not loaded"
                continue
            }
            val addon = dto.toDomain(base)
            remember(addon.name, label)
            remember(addon.id, label)
            sources += loadCatalogs(label, addon)
        }
        return sources
    }

    private fun loadCatalogs(label: String, addon: Addon): Source {
        val claimed = addon.catalogs.mapNotNull { catalog ->
            SportsLiveCatalogPolicy.classify(addon, catalog, emptyMap())?.let { claim ->
                ClaimedCatalog(addon, catalog, SportsLiveCatalogPolicy.keyFor(addon, catalog), claim.kind)
            }
        }
        val items = ArrayList<SportsLiveItem>()
        var failed = 0
        val adapter = moshi.adapter(CatalogResponseDto::class.java)
        for (catalog in claimed) {
            val body = get(http, catalogUrl(addon.baseUrl, catalog.catalog.apiType, catalog.catalog.id))
            val metas = body?.let { runCatching { adapter.fromJson(it) }.getOrNull() }
                ?.metas?.mapNotNull { it?.toDomainOrNull(catalog.catalog.apiType, addon.baseUrl) }
            if (metas == null) {
                failed++
                continue
            }
            // The addon's name never reaches the report: its label stands in.
            items += SportsLiveItemBucketer.describeCatalog(metas, catalog, nowMs).map { it.copy(addonName = label) }
        }
        if (failed > 0) notes += "$label: $failed of ${claimed.size} catalogs not loaded"
        return Source(label, addon, items, claimed.size, failed)
    }

    // As CatalogRepositoryImpl.buildCatalogUrl, first page, no extras.
    private fun catalogUrl(baseUrl: String, type: String, catalogId: String): String {
        val trimmed = baseUrl.trimEnd('/')
        val queryStart = trimmed.indexOf('?')
        val basePath = if (queryStart >= 0) trimmed.substring(0, queryStart).trimEnd('/') else trimmed
        val baseQuery = if (queryStart >= 0) trimmed.substring(queryStart) else ""
        return "$basePath/catalog/$type/$catalogId.json$baseQuery"
    }

    private fun remember(secret: String?, label: String) {
        val text = secret?.trim().orEmpty()
        if (text.length >= 4) secrets[text] = label
    }

    // ---- ESPN ----

    private var directoryLists = 0

    /** The team lists the app keeps for inference, then those of the leagues the listed games route to. */
    private fun loadDirectory(sources: List<Source>) {
        val routed = sources.flatMap { it.items }.filter { !it.isChannel }.flatMap { EspnLeagueMap.leaguesFor(it) }.distinct()
        val provider = EspnScoreProvider(espnHttp, moshi, EspnScoreProvider.BASE_URL)
        val wanted = LiveScoresRepository.directoryLeagueOrder(routed).take(MAX_DIRECTORY_LISTS)
        for (league in wanted) {
            val teams = runBlocking { provider.teamList(league) }
            if (teams == null) {
                notes += "team list ${league.path}: not loaded"
                continue
            }
            TeamDirectory.shared.put(league, teams, nowMs)
            directoryLists++
            pause()
        }
        // Inference's leagues are part of the order above; a missing one leaves its games unplaced.
        val missing = SportsLiveSportInference.leagues.filter { TeamDirectory.covers(it) && TeamDirectory.shared.fetchedAt(it) == null }
        if (missing.isNotEmpty()) notes += "inference team lists missing: ${missing.joinToString { it.path }}"
    }

    private fun scoreboard(league: EspnLeague, date: LocalDate): List<ScoreSnapshot>? {
        val key = league to date
        if (key in boards) return boards[key]
        return fetchBoard(league, date).also { boards[key] = it }
    }

    private fun fetchBoard(league: EspnLeague, date: LocalDate): List<ScoreSnapshot>? {
        val day = date.format(DateTimeFormatter.BASIC_ISO_DATE)
        val url = "${EspnScoreProvider.BASE_URL}${league.path}/scoreboard?dates=$day&limit=$ESPN_LIMIT${EspnScoreProvider.groupsFor(league)}"
        boardRequests++
        val body = get(espnHttp, url)
        pause()
        val dto = body?.let { runCatching { boardAdapter.fromJson(it) }.getOrNull() }
        if (body == null || dto == null) {
            boardFailures++
            notes += "ESPN ${league.path} $day: not loaded"
            return null
        }
        val events = EspnScoreProvider.toSnapshots(dto, league)
        val name = "${league.sport}_${league.league}_$day.json"
        rawDir.mkdirs()
        File(rawDir, name).writeText(body)
        events.forEach { boardFileOf.putIfAbsent(it.eventId, name) }
        return events
    }

    private fun eventsOf(leagues: List<EspnLeague>, dates: List<LocalDate>): List<ScoreSnapshot> =
        leagues.flatMap { league -> dates.flatMap { scoreboard(league, it).orEmpty() } }.distinctBy { it.eventId }

    // As LiveScoresRepository.datesFor: ESPN files games under their US Eastern date.
    private fun datesFor(card: ScoreCard): List<LocalDate> {
        val start = card.startMs
        if (start == null) {
            val today = Instant.ofEpochMilli(nowMs).atZone(EASTERN).toLocalDate()
            return listOf(today, today.minusDays(1))
        }
        if (!card.hasTimeOfDay) return listOf(Instant.ofEpochMilli(start).atZone(java.time.ZoneOffset.UTC).toLocalDate())
        return listOf(
            Instant.ofEpochMilli(start - WINDOW_MS).atZone(EASTERN).toLocalDate(),
            Instant.ofEpochMilli(start + WINDOW_MS).atZone(EASTERN).toLocalDate()
        ).distinct()
    }

    private fun laterDates(): List<LocalDate> {
        val today = Instant.ofEpochMilli(nowMs).atZone(EASTERN).toLocalDate()
        return listOf(today, today.plusDays(1))
    }

    // ---- Checks ----

    private fun isSoon(item: SportsLiveItem): Boolean {
        val start = item.startMs ?: return false
        return item.startHasTimeOfDay && start > nowMs && start <= nowMs + upcomingHours * HOUR_MS
    }

    private fun checkSource(source: Source): List<Check> {
        val order = listOf(source.addon.id)
        val live = SportsLiveItemBucketer.bucketTab(source.items, SportsLiveTab.LIVE_NOW, nowMs, order).filter { !it.isChannel }
        val upcoming = SportsLiveItemBucketer.bucketTab(source.items, SportsLiveTab.UPCOMING, nowMs, order)
            .filter { !it.isChannel && isSoon(it) }
        return live.map { check(source.label, it, live = true) } + upcoming.map { check(source.label, it, live = false) }
    }

    /** One row as the app scores it: placed ([SportsLiveUnidentified.placed]), routed and matched as the score repository does. */
    private fun check(label: String, row: SportsLiveItem, live: Boolean): Check {
        val view = SportsLiveUnidentified.placed(row)
        val card = ScoreCard.from(view)
            ?: return Check(label, row, live, if (SportsLiveUnidentified.isUntagged(row)) Verdict.UNPLACED else Verdict.NO_TEAMS)
        val copies = listOf(view) + view.alternates
        val routed = copies.asSequence().map { it to EspnLeagueMap.leaguesFor(it) }.firstOrNull { it.second.isNotEmpty() }
        val dates = datesFor(card)
        if (routed == null) {
            val tennis = copies.asSequence().map(EspnLeagueMap::tennisLeaguesFor).firstOrNull { it.isNotEmpty() }
                ?: return Check(label, row, live, if (SportsLiveUnidentified.isUntagged(row)) Verdict.UNPLACED else Verdict.NO_FEED)
            val match = TennisMatcher.match(card, eventsOf(tennis, dates))
            val verdict = if (live) NightlyText.liveVerdict(match, null, nowMs) else NightlyText.upcomingVerdict(match)
            return Check(label, row, live, verdict, match, NightlyText.appCatches(verdict, match, null, false, nowMs), tennis)
        }
        val (copy, leagues) = routed
        var events = eventsOf(leagues, dates)
        var match = matcher.match(card, events, nowMs)
        // A soccer competition feed without the game: soccer/all, as the app.
        if (match == null && leagues.all { it.sport == "soccer" && it != EspnLeagueMap.SOCCER_ALL }) {
            events = (events + eventsOf(listOf(EspnLeagueMap.SOCCER_ALL), dates)).distinctBy { it.eventId }
            match = matcher.match(card, events, nowMs)
        }
        // A bare "Basketball" / "Football" card: the college feed, both sides STRONG, as the app.
        val fallback = EspnLeagueMap.collegeFallbackFor(copy)?.takeIf { it !in leagues }
        var strict = false
        if (match == null && fallback != null) {
            val college = eventsOf(listOf(fallback), dates)
            match = strictMatcher.match(card, college, nowMs)
            strict = match != null
            events = (events + college).distinctBy { it.eventId }
        }
        val allLeagues = leagues + listOfNotNull(fallback)
        if (!live) {
            val verdict = NightlyText.upcomingVerdict(match)
            val aliases = if (match == null) aliasesOf(card, events) else emptyList()
            return Check(label, row, false, verdict, match, false, allLeagues, aliases)
        }
        val later = if (match == null) {
            matcher.laterMeeting(card, (events + eventsOf(leagues, laterDates())).distinctBy { it.eventId }, nowMs)
        } else {
            null
        }
        val verdict = NightlyText.liveVerdict(match, later, nowMs)
        val strictSame = strict || (match != null && strictMatcher.match(card, events, nowMs)?.eventId == match.eventId)
        val aliases = if (match == null && later == null) aliasesOf(card, events) else emptyList()
        return Check(label, row, true, verdict, match ?: later, NightlyText.appCatches(verdict, match, later, strictSame, nowMs), allLeagues, aliases)
    }

    /**
     * Alias candidates for an unmatched card: ESPN games near its time where one side matches and the other does not
     * ("Ind. del Valle" beside a matched "Flamengo").
     */
    private fun aliasesOf(card: ScoreCard, events: List<ScoreSnapshot>): List<Alias> {
        val start = card.startMs
        val near = events.filter { event ->
            val eventStart = event.startMs ?: return@filter false
            start == null || !card.hasTimeOfDay || abs(eventStart - start) <= ALIAS_WINDOW_MS
        }
        val found = ArrayList<Alias>()
        for (event in near) {
            val home = card.teams.home
            val away = card.teams.away
            val pairs = listOf(
                Triple(home, away, event.home to event.away),
                Triple(away, home, event.home to event.away),
                Triple(home, away, event.away to event.home),
                Triple(away, home, event.away to event.home)
            )
            for ((matchedSide, otherSide, teams) in pairs) {
                if (hits(matchedSide, teams.first) && !hits(otherSide, teams.second)) {
                    found += Alias(otherSide, matchedSide, teams.second.displayName ?: teams.second.name.orEmpty(), teams.second.id, event)
                }
            }
        }
        return found.distinctBy { it.cardSide + "\u0000" + it.event.eventId }.take(MAX_ALIASES)
    }

    private fun hits(side: String, team: ScoreTeam): Boolean {
        val names = listOfNotNull(
            team.abbreviation, team.displayName, team.shortDisplayName, team.name, team.location,
            if (team.location != null && team.name != null) "${team.location} ${team.name}" else null
        ) + team.alternateNames
        return names.any { it.isNotBlank() && SportsLiveTeams.matches(side, it) }
    }

    // ---- HTTP ----

    /** The body of a 2xx answer, or null. Errors are counted, never logged with their URL. */
    private fun get(client: OkHttpClient, url: String): String? = runCatching {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (response.isSuccessful) response.body?.string() else null
        }
    }.getOrNull()

    private fun pause() = Thread.sleep(PAUSE_MS)

    internal companion object {
        private const val MANIFEST = "/manifest.json"
        private const val TOKEN_LENGTH = 12
        private const val ESPN_LIMIT = 1000
        private const val MAX_DIRECTORY_LISTS = 40
        private const val MAX_ALIASES = 4
        private const val PAUSE_MS = 250L
        private const val HOUR_MS = 60 * 60_000L
        private const val WINDOW_MS = 3 * HOUR_MS
        private const val ALIAS_WINDOW_MS = 6 * HOUR_MS
        private val EASTERN: ZoneId = ZoneId.of("America/New_York")

        /** A row's title as the app shows it. */
        fun titleOf(item: SportsLiveItem): String = SportsLiveDisplayText.name(item.meta.name).trim()
    }
}
