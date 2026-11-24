package com.nuvio.tv.core.sportslive.nightly

import com.nuvio.tv.core.sportslive.SportsLiveDupes
import com.nuvio.tv.core.sportslive.SportsLiveSportKind
import com.nuvio.tv.core.sportslive.nightly.NightlyAccuracy.Check
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.domain.model.SportsLiveItem
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Writes one nightly run (#192): `<run>.md` and `latest.md`, `summary.txt`, rows appended to `disagreements.csv` and
 * `reliability.csv`. Every text from an addon goes through [NightlyText.redact]; addons appear only as labels.
 */
internal class NightlyWriter(
    private val outDir: File,
    private val runId: String,
    private val nowMs: Long,
    private val zone: ZoneId,
    private val upcomingHours: Int,
    private val secrets: Map<String, String>,
    private val notes: List<String>,
    private val boardFileOf: Map<String, String>,
    private val sources: List<SourceInfo>,
    private val skippedFiles: Int,
    private val boardRequests: Int,
    private val boardFailures: Int,
    private val directoryLists: Int
) {
    class SourceInfo(val label: String, val catalogs: Int, val failedCatalogs: Int)

    private val local = DateTimeFormatter.ofPattern("EEE yyyy-MM-dd HH:mm").withZone(zone)
    private val nowIso = Instant.ofEpochMilli(nowMs).toString()

    private fun r(text: String?): String = NightlyText.redact(text, secrets)
    private fun title(item: SportsLiveItem) = r(NightlyAccuracy.titleOf(item))
    private fun utc(ms: Long?) = ms?.let { Instant.ofEpochMilli(it).toString() }.orEmpty()
    private fun localTime(ms: Long?) = ms?.let { local.format(Instant.ofEpochMilli(it)) } ?: "no time"
    private fun espnName(event: ScoreSnapshot) = "${event.home.displayName ?: event.home.name} vs ${event.away.displayName ?: event.away.name}"
    private fun keyOf(check: Check) =
        NightlyText.label(listOf(check.verdict.code, check.label, NightlyAccuracy.titleOf(check.row).lowercase(), check.espn?.eventId.orEmpty()).joinToString("|"))
            .removePrefix("addon-")

    fun write(
        checks: List<Check>,
        dupes: List<SportsLiveDupes.Suspect>,
        nearMisses: List<Pair<SportsLiveItem, SportsLiveItem>>,
        appLive: NightlyText.AppLive
    ): String {
        val disagreements = checks.filter { it.verdict.disagreement }
        val seen = previousKeys()
        val fresh = disagreements.filter { keyOf(it) !in seen }
        appendDisagreements(disagreements, seen)
        val stats = sources.map { source -> source.label to Stats.of(checks.filter { it.label == source.label }) }
        appendReliability(stats)
        val rolling = rollingReliability()
        val certainty = NightlyText.certainty(appLive)
        appendCertainty(certainty)
        val summary = summary(checks, disagreements, fresh, dupes, nearMisses, appLive, certainty)
        val report = buildString {
            appendLine("# Nightly accuracy run $runId")
            appendLine()
            appendLine("Run at ${local.format(Instant.ofEpochMilli(nowMs))} ($nowIso). Addons are hashed labels; no links. " +
                "Upcoming = timed starts in the next $upcomingHours h.")
            appendLine()
            appendLine("## Summary")
            appendLine()
            summary.lines().forEach { appendLine("- $it") }
            appendLine()
            appendLine("## Per-addon live reliability")
            appendLine()
            appendLine("Reliability = listed-live games ESPN has in play (or starting within 30 min) / listed-live games ESPN knows.")
            appendLine()
            appendLine("| addon | catalogs | listed live | verified | in play | soon | later | final | off | not found | can't place | no feed | reliability | last 30 runs |")
            appendLine("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
            for ((label, s) in stats) {
                val info = sources.first { it.label == label }
                val catalogs = if (info.failedCatalogs > 0) "${info.catalogs} (${info.failedCatalogs} failed)" else "${info.catalogs}"
                appendLine("| $label | $catalogs | ${s.listedLive} | ${s.verified} | ${s.inPlay} | ${s.soon} | ${s.later} | ${s.ended} | ${s.off} | " +
                    "${s.notFound} | ${s.unplaced} | ${s.noFeed} | ${NightlyText.percent(s.inPlay + s.soon, s.verified)} | ${rolling[label] ?: "-"} |")
            }
            appendLine()
            section("A. Listed live, ESPN disagrees", disagreements.filter { it.live }, fresh)
            section("B. Listed upcoming, ESPN already started or finished", disagreements.filter { !it.live }, fresh)
            appendLine("## C. Can't place (no sport or league from any rule)")
            appendLine()
            val unplaced = checks.filter { it.verdict == Verdict.UNPLACED }
            if (unplaced.isEmpty()) appendLine("None.")
            unplaced.forEach { appendLine("- ${it.label} · \"${title(it.row)}\" (${if (it.live) "live" else "upcoming"}; genres ${r(it.row.meta.genres.joinToString())}; catalog \"${r(it.row.catalogName)}\")") }
            appendLine()
            appendLine("## D. Name pairs that failed to match (alias candidates)")
            appendLine()
            val aliases = checks.flatMap { check -> check.aliases.map { check to it } }
            if (aliases.isEmpty()) appendLine("None.")
            for ((check, alias) in aliases) {
                appendLine("- \"${r(alias.cardSide)}\" ↔ ESPN \"${alias.espnName}\" (team ${alias.espnTeamId ?: "?"}, ${alias.event.league.path} " +
                    "event ${alias.event.eventId}, ${utc(alias.event.startMs)}); other side \"${r(alias.matchedSide)}\" matched · ${check.label} \"${title(check.row)}\"")
            }
            val lost = checks.filter { it.verdict == Verdict.NOT_FOUND && it.aliases.isEmpty() }
            appendLine()
            appendLine("Not found on ESPN with no near miss (${lost.size}):")
            appendLine()
            lost.forEach {
                appendLine("- ${it.label} · \"${title(it.row)}\" (${if (it.live) "live" else "upcoming"}; start ${localTime(it.row.startMs)}; " +
                    "feeds ${it.leagues.joinToString { league -> league.path }})")
            }
            appendLine()
            appendLine("## E. One game left as two rows (merged lists)")
            appendLine()
            if (dupes.isEmpty() && nearMisses.isEmpty()) appendLine("None.")
            // The copies' sport words tell a "sport mismatch" apart (live.23, #215.3: college football filed as soccer).
            dupes.forEach {
                appendLine("- \"${title(it.a)}\" (${it.a.addonName}, ${r(it.a.sport ?: "no sport")}) and \"${title(it.b)}\" " +
                    "(${it.b.addonName}, ${r(it.b.sport ?: "no sport")}): ${it.why}; kept apart: ${r(it.reason)}")
            }
            nearMisses.forEach { (a, b) -> appendLine("- near miss: \"${title(a)}\" (${a.addonName}) and \"${title(b)}\" (${b.addonName}): one side alike, the other not") }
            appendLine()
            appendLine("## F. Unscored live games by league")
            appendLine()
            appendLine("Listed-live rows the app shows with no score (not found on ESPN, no feed, no sport, no two teams), by the " +
                "feed they route to or why they route nowhere.")
            appendLine()
            val table = NightlyText.unscoredByLeague(checks.filter { it.live }.map { NightlyText.LiveRow(groupOf(it), it.label, NightlyText.isUnscored(it.verdict)) })
            if (table.isEmpty()) appendLine("None.")
            table.forEach { appendLine(it) }
            appendLine()
            appendLine("## G. Certainty by league/reason")
            appendLine()
            appendLine("Games shown LIVE whose status is not backed by ESPN (in play, or starting within 30 min), by reason; at most 10 examples each.")
            appendLine()
            NightlyText.certaintySection(certainty).forEach { appendLine(r(it)) }
            appendLine()
            appendLine("## Notes")
            appendLine()
            appendLine("- Addon files: ${sources.size} loaded, $skippedFiles skipped (not a manifest URL).")
            appendLine("- ESPN: $boardRequests scoreboards asked, $boardFailures failed; $directoryLists team lists.")
            notes.forEach { appendLine("- ${r(it)}") }
        }
        File(outDir, "$runId.md").writeText(report)
        File(outDir, "latest.md").writeText(report)
        File(outDir, "summary.txt").writeText("Nightly accuracy $runId\n$summary\nReport: ${File(outDir, "$runId.md").absolutePath}\n")
        keepFixtures(disagreements + checks.filter { it.aliases.isNotEmpty() })
        return summary
    }

    /** The group a live row is counted under in section F: its first routed feed, else why it has none (no addon text). */
    private fun groupOf(check: Check): String = when (check.verdict) {
        Verdict.UNPLACED -> "can't place (no sport)"
        Verdict.NO_TEAMS -> "no two teams"
        Verdict.NO_FEED -> "no ESPN feed (${SportsLiveSportKind.tagOf(check.row)?.name?.lowercase() ?: "sport unknown"})"
        else -> check.leagues.firstOrNull()?.path ?: "no ESPN feed"
    }

    private fun summary(
        checks: List<Check>,
        disagreements: List<Check>,
        fresh: List<Check>,
        dupes: List<SportsLiveDupes.Suspect>,
        nearMisses: List<Pair<SportsLiveItem, SportsLiveItem>>,
        appLive: NightlyText.AppLive,
        certainty: NightlyText.Certainty
    ): String {
        val all = Stats.of(checks)
        val live = disagreements.filter { it.live }
        val caught = live.count { it.appCatches }
        val upcoming = checks.filter { !it.live }
        return listOf(
            NightlyText.appLiveLine(appLive),
            NightlyText.certaintyLine(certainty),
            "Listed live: ${all.listedLive} games from ${sources.size} addons; ESPN knows ${all.verified}: in play ${all.inPlay}, starting soon ${all.soon}, " +
                "scheduled later ${all.later}, final ${all.ended}, postponed/cancelled ${all.off} (reliability ${NightlyText.percent(all.inPlay + all.soon, all.verified)}).",
            "Live disagreements: ${live.size} (the app corrects $caught, NOT ${live.size - caught}); new since earlier runs: ${fresh.size}.",
            "Upcoming checked: ${upcoming.size}; ESPN already in play or final: ${disagreements.count { !it.live }}.",
            "Not found on ESPN: ${checks.count { it.verdict == Verdict.NOT_FOUND }} (alias candidates: ${checks.sumOf { it.aliases.size }}); " +
                "can't place: ${checks.count { it.verdict == Verdict.UNPLACED }}; no ESPN feed: ${checks.count { it.verdict == Verdict.NO_FEED }}.",
            "Merged lists: ${dupes.size} possible duplicate rows, ${nearMisses.size} merge near misses.",
            "Unscored live: ${checks.count { it.live && NightlyText.isUnscored(it.verdict) }} of ${all.listedLive} listed live (by league: section F).",
            "ESPN: $boardRequests scoreboards, $boardFailures failed." + if (notes.isNotEmpty()) " Notes: ${notes.size} (see report)." else ""
        ).joinToString("\n")
    }

    private fun StringBuilder.section(heading: String, rows: List<Check>, fresh: List<Check>) {
        appendLine("## $heading")
        appendLine()
        if (rows.isEmpty()) {
            appendLine("None.")
            appendLine()
            return
        }
        for (check in rows) {
            val meta = check.row.meta
            val espn = check.espn
            val isNew = check in fresh
            appendLine("### ${check.label} · \"${title(check.row)}\" — ${check.verdict.code}${if (isNew) " (NEW)" else ""} · app corrects it: ${if (check.appCatches) "yes" else "NO"}")
            appendLine()
            appendLine("- Addon: name `${r(meta.name)}`, releaseInfo `${r(meta.releaseInfo)}`, released `${r(meta.released)}`, genres `${r(meta.genres.joinToString())}`, " +
                "catalog \"${r(check.row.catalogName)}\" (${check.row.kind}), card start ${localTime(check.row.startMs)} ${utc(check.row.startMs)}")
            if (espn != null) {
                appendLine("- ESPN: ${espn.league.path} (slug ${espn.leagueSlug}) event ${espn.eventId} \"${espnName(espn)}\", state ${espn.state}, " +
                    "\"${espn.clockText.orEmpty()}\", start ${localTime(espn.startMs)} ${utc(espn.startMs)}" + if (!espn.timeValid) " (time TBD)" else "")
                appendLine("- Fixture: raw/$runId/espn/${boardFileOf[espn.eventId] ?: "?"}")
            }
            appendLine()
            appendLine("```kotlin")
            appendLine(regressionTest(check))
            appendLine("```")
            appendLine()
        }
    }

    /** A test in the style of SportsLiveLive20DataTest, ready to paste once the fixture is copied to test resources. */
    private fun regressionTest(check: Check): String {
        val meta = check.row.meta
        val espn = check.espn
        val fixture = espn?.let { boardFileOf[it.eventId] } ?: "FIXTURE.json"
        val league = espn?.league
        val expectation = when (check.verdict) {
            Verdict.SCHEDULED_LATER -> "not live: ESPN schedules it at ${utc(espn?.startMs)} (the app lists it on Upcoming)"
            Verdict.FINAL -> "over: ESPN final (the app moves it to Just ended)"
            Verdict.OFF -> "not live: ESPN postponed or cancelled"
            Verdict.STARTED_EARLY -> "live: ESPN has it in play"
            Verdict.ENDED_EARLY -> "over: ESPN final"
            else -> check.verdict.code
        }
        val name = NightlyText.testName("nightly ${runId.take(10)} ${title(check.row)} ${check.verdict.code}")
        return """
            |// #192 nightly $runId, ${check.label}: expected $expectation.
            |// Copy raw/$runId/espn/$fixture (trim to event ${espn?.eventId ?: "?"}) to test resources sportslive/scores/.
            |@Test
            |fun `$name`() {
            |    val now = Instant.parse("$nowIso").toEpochMilli()
            |    val item = SportsLiveItemBucketer.describe(
            |        MetaPreview(
            |            id = "nightly", type = ContentType.UNKNOWN, rawType = ${NightlyText.kotlinString(meta.rawType)},
            |            name = ${NightlyText.kotlinString(r(meta.name))}, poster = null, posterShape = PosterShape.LANDSCAPE,
            |            background = null, logo = null, description = ${NightlyText.kotlinString(meta.description?.let { r(it).take(MAX_DESCRIPTION) })},
            |            releaseInfo = ${NightlyText.kotlinString(meta.releaseInfo?.let(::r))}, imdbRating = null,
            |            genres = listOf(${meta.genres.joinToString { NightlyText.kotlinString(r(it)) }}),
            |            released = ${NightlyText.kotlinString(meta.released?.let(::r))}
            |        ),
            |        claimed(catalogName = ${NightlyText.kotlinString(r(check.row.catalogName))}), // a ${check.row.kind} catalog
            |        now
            |    )
            |    val events = EspnFixtures.events("$fixture", EspnLeague("${league?.sport ?: "?"}", "${league?.league ?: "?"}"))
            |    val card = ScoreCard.from(SportsLiveUnidentified.placed(item))!!
            |    val match = ScoreMatcher().match(card, events, now) ?: ScoreMatcher().laterMeeting(card, events, now)
            |    assertEquals("${espn?.eventId ?: "?"}", match?.eventId)
            |    // Then assert the list: ${if (check.live) "SportsLiveItemBucketer.bucketTab(listOf(item), SportsLiveTab.LIVE_NOW, now)" else "UPCOMING vs LIVE_NOW"} after the fix.
            |}
        """.trimMargin()
    }

    // ---- CSV ----

    private val disagreementsFile = File(outDir, "disagreements.csv")
    private val reliabilityFile = File(outDir, "reliability.csv")
    private val certaintyFile = File(outDir, "certainty.csv")

    private fun appendCertainty(certainty: NightlyText.Certainty) {
        if (!certaintyFile.exists()) certaintyFile.writeText(NightlyText.csv(NightlyText.CERTAINTY_COLUMNS) + "
")
        certaintyFile.appendText(NightlyText.certaintyCsvRow(runId, certainty) + "
")
    }

    private fun previousKeys(): Set<String> =
        if (disagreementsFile.exists()) disagreementsFile.readLines().drop(1).mapTo(HashSet()) { it.substringBefore(',') } else emptySet()

    private fun appendDisagreements(rows: List<Check>, seen: Set<String>) {
        if (!disagreementsFile.exists()) {
            disagreementsFile.writeText(NightlyText.csv(DISAGREEMENT_COLUMNS) + "\n")
        }
        val text = rows.joinToString("") { check ->
            val meta = check.row.meta
            val espn = check.espn
            NightlyText.csv(
                listOf(
                    keyOf(check), runId, check.verdict.code, check.label, title(check.row), r(meta.name), r(meta.releaseInfo), r(meta.released),
                    r(meta.genres.joinToString("|")), r(check.row.catalogName), utc(check.row.startMs),
                    espn?.league?.path, espn?.leagueSlug, espn?.eventId, utc(espn?.startMs), espn?.state, espn?.clockText,
                    espn?.home?.displayName, espn?.away?.displayName, if (check.appCatches) "yes" else "no",
                    if (keyOf(check) in seen) "no" else "yes", espn?.let { boardFileOf[it.eventId]?.let { file -> "raw/$runId/espn/$file" } }
                )
            ) + "\n"
        }
        disagreementsFile.appendText(text)
    }

    private fun appendReliability(stats: List<Pair<String, Stats>>) {
        if (!reliabilityFile.exists()) reliabilityFile.writeText(NightlyText.csv(RELIABILITY_COLUMNS) + "\n")
        reliabilityFile.appendText(stats.joinToString("") { (label, s) ->
            NightlyText.csv(listOf(runId, label, s.listedLive, s.verified, s.inPlay, s.soon, s.later, s.ended, s.off, s.notFound, s.noFeed, s.unplaced,
                s.noTeams, s.upcoming, s.upcomingStarted)) + "\n"
        })
    }

    /** Each addon's reliability over its last [ROLLING_RUNS] runs (this one included). */
    private fun rollingReliability(): Map<String, String> {
        val rows = reliabilityFile.readLines().drop(1).map { it.split(',') }.filter { it.size >= RELIABILITY_COLUMNS.size }
        return rows.groupBy { it[1] }.mapValues { (_, runs) ->
            val last = runs.takeLast(ROLLING_RUNS)
            val verified = last.sumOf { it[3].toIntOrNull() ?: 0 }
            val agreed = last.sumOf { (it[4].toIntOrNull() ?: 0) + (it[5].toIntOrNull() ?: 0) }
            "${NightlyText.percent(agreed, verified)} of $verified (${last.size} runs)"
        }
    }

    /** Keeps the scoreboards a finding refers to; drops the rest of this run's and runs older than [KEEP_RAW_DAYS] days. */
    private fun keepFixtures(findings: List<Check>) {
        val wanted = findings.flatMap { check -> listOfNotNull(check.espn) + check.aliases.map { it.event } }
            .mapNotNullTo(HashSet()) { boardFileOf[it.eventId] }
        val dir = File(outDir, "raw/$runId/espn")
        dir.listFiles().orEmpty().filter { it.name !in wanted }.forEach { it.delete() }
        if (dir.listFiles().isNullOrEmpty()) dir.parentFile?.deleteRecursively()
        val cutoff = nowMs - KEEP_RAW_DAYS * 24 * 60 * 60_000L
        File(outDir, "raw").listFiles().orEmpty().filter { it.isDirectory && it.lastModified() < cutoff }.forEach { it.deleteRecursively() }
    }

    /** Counts per addon (or overall). */
    class Stats(
        val listedLive: Int, val inPlay: Int, val soon: Int, val later: Int, val ended: Int, val off: Int,
        val notFound: Int, val noFeed: Int, val unplaced: Int, val noTeams: Int, val upcoming: Int, val upcomingStarted: Int
    ) {
        val verified: Int get() = inPlay + soon + later + ended + off

        companion object {
            fun of(checks: List<Check>): Stats {
                val live = checks.filter { it.live }
                val upcoming = checks.filter { !it.live }
                fun count(verdict: Verdict) = live.count { it.verdict == verdict }
                return Stats(
                    listedLive = live.size, inPlay = count(Verdict.IN_PLAY), soon = count(Verdict.STARTS_SOON),
                    later = count(Verdict.SCHEDULED_LATER), ended = count(Verdict.FINAL), off = count(Verdict.OFF),
                    notFound = count(Verdict.NOT_FOUND), noFeed = count(Verdict.NO_FEED), unplaced = count(Verdict.UNPLACED),
                    noTeams = count(Verdict.NO_TEAMS), upcoming = upcoming.size, upcomingStarted = upcoming.count { it.verdict.disagreement }
                )
            }
        }
    }

    companion object {
        private const val MAX_DESCRIPTION = 200
        private const val ROLLING_RUNS = 30
        private const val KEEP_RAW_DAYS = 30
        val DISAGREEMENT_COLUMNS = listOf(
            "key", "run", "kind", "addon", "title", "raw_name", "release_info", "released", "genres", "catalog", "card_start_utc",
            "espn_feed", "espn_slug", "espn_event_id", "espn_start_utc", "espn_state", "espn_detail", "espn_home", "espn_away",
            "app_corrects", "new", "fixture"
        )
        val RELIABILITY_COLUMNS = listOf(
            "run", "addon", "listed_live", "verified", "in_play", "starts_soon", "scheduled_later", "final", "off", "not_found",
            "no_feed", "cannot_place", "no_two_teams", "upcoming_checked", "upcoming_started"
        )
    }
}
