package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.GameStatusStrings
import com.nuvio.tv.core.sportslive.SportsLiveSportKind
import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.core.sportslive.scores.EspnFixtures
import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
import com.nuvio.tv.core.sportslive.scores.ScoreChipStrings
import com.nuvio.tv.core.sportslive.scores.ScoreChips
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoreState
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.domain.model.SportsLiveTab
import com.nuvio.tv.ui.screens.sportslive.dual.DualScoreStrip
import com.nuvio.tv.ui.screens.sportslive.dual.dualPickerRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * live.24 (lessons 2026-11-21, "Cross-surface consistency gaps"): for the same item and score snapshot, every surface
 * that lists games names it alike: the same names, codes, ranks, logos and tag ([GameIdentity]). Table-driven over each
 * surface's own row builder; a surface only states the fields it shows. A new surface that lists games belongs here.
 */
class SportsLiveGameIdentityConsistencyTest {

    private val now = 1_800_000_000_000L
    private val label: (SportsLiveSportKind) -> String = { it.name }
    private val codeOf: (String) -> String? = { null }
    private val mode = ScoresMode.SHOW

    private val strings = GameStatusStrings(
        liveClock = "● %1\$s", live = "LIVE", clockPeriod = "%1\$s · %2\$s", timeCountdown = "%1\$s · %2\$s",
        inHoursMinutes = "in %1\$d h %2\$d m", inHours = "in %1\$d h", inMinutes = "in %1\$d min",
        final = "Final", fullTime = "FT", probablyEnded = "Probably ended", postponed = "Postponed",
        chip = ScoreChipStrings("%1\$d–%2\$d", "%1\$s · %2\$s", "%1\$s %2\$s", "Final", "Postponed", "Score hidden")
    )

    // IND @ PHI, as a ranked college game in play: home (Philadelphia) #5, away unranked.
    private val nba = EspnFixtures.events(EspnFixtures.NBA_20261115, EspnLeagueMap.NBA).first { it.eventId == "401909985" }
    private val ranked = nba.copy(
        league = EspnLeagueMap.COLLEGE_BASKETBALL, leagueName = "NCAAM", state = ScoreState.IN, isFinal = false,
        clockText = "12:00 - 2nd", home = nba.home.copy(score = 40, rank = 5), away = nba.away.copy(score = 38, rank = null)
    )

    private fun item(name: String, sport: String = "Basketball") = SportsLiveItem(
        meta = MetaPreview(
            id = name, type = ContentType.UNKNOWN, rawType = "sport", name = name, poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = emptyList()
        ),
        addonId = "a", addonName = "a", addonBaseUrl = "https://example.invalid", catalogKey = "k", catalogName = "Live",
        kind = SportsLiveKind.EVENTS, isLive = true, isChannel = false,
        startMs = now - 60 * 60_000L, startHasTimeOfDay = true, sport = sport, teams = SportsLiveTeams.parse(name)
    )

    private fun GameIdentity.fields(): Map<String, Any?> = mapOf(
        "firstNames" to first?.names, "secondNames" to second?.names,
        "firstCode" to first?.code, "secondCode" to second?.code,
        "firstRank" to first?.rank, "secondRank" to second?.rank,
        "firstLogo" to first?.logo, "secondLogo" to second?.logo,
        "tag" to tag
    )

    private fun GameSideIdentity.fields(prefix: String): Map<String, Any?> = mapOf(
        "${prefix}Names" to names, "${prefix}Code" to code, "${prefix}Rank" to rank, "${prefix}Logo" to logo
    )

    /** Each surface's own reading of [item] with [snapshot], as the fields it shows. */
    private fun surfaces(item: SportsLiveItem, snapshot: ScoreSnapshot?): Map<String, Map<String, Any?>> {
        val scores = snapshot?.let { mapOf(item.streamKey to it) }.orEmpty()
        val plain = SportsLiveSportKind.plainTagOf(item)
        val liveNow = scoreboardRows(listOf(item), SportsLiveTab.LIVE_NOW, scores, mode, emptySet(), now).single()
        val upcoming = scoreboardRows(listOf(item), SportsLiveTab.UPCOMING, scores, mode, emptySet(), now).single()
        val panelRow = groupPanelRows(listOf(item), scores, mode, emptySet(), now).single()
        val board = panelBoards(listOf(panelRow), scores, mode, emptySet(), now).single().board
        val boardIdentity = GameIdentities.of(board, label, plain, codeOf)
        val guide = guideRowOf(board, strings, null, boardIdentity.tag, codeOf = codeOf)
        val line = panelLineOf(board, strings, null, boardIdentity.tag)
        val chip = ScoreChips.of(item, snapshot, mode, revealed = false)
        val home = GameIdentities.of(item, snapshot, chip, label, plain, codeOf = codeOf)
        val dual = DualScoreStrip.of(item, snapshot, mode, revealed = false, label = label, codeOf = codeOf)
        val picker = dualPickerRows(listOf(item), scores, mode, emptySet(), now).getValue(item.streamKey)
        val art = liveNow.art!!
        return buildMap {
            put("Live Now row", GameIdentities.of(liveNow, label, plain, codeOf).fields())
            put("Upcoming row", GameIdentities.of(upcoming, label, plain, codeOf).fields())
            put("In-player panel", boardIdentity.fields())
            put(
                "Table guide",
                mapOf(
                    "firstNames" to guide.firstNames, "secondNames" to guide.secondNames,
                    "firstRank" to guide.firstRank, "secondRank" to guide.secondRank, "tag" to guide.tag
                )
            )
            put("Slim panel line", mapOf("firstRank" to line.firstRank, "secondRank" to line.secondRank, "tag" to line.tag))
            put("Home card and hero", home.fields() + mapOf("tag" to liveNowItemTag(item, snapshot, label)))
            put("Dual pane caption", dual!!.first.fields("first") + dual.second.fields("second") + mapOf("tag" to dual.tag))
            put("Dual picker", GameIdentities.of(item, null, picker.chip, label, plain, art = picker.teams, codeOf = codeOf).fields())
            put("Your teams strip", mapOf("firstCode" to yourTeamsShortName(art.first, codeOf), "secondCode" to yourTeamsShortName(art.second, codeOf)))
            // Results lists ESPN's own events (away first, as this card).
            snapshot?.let { event ->
                val (away, home2) = resultSidesOf(event, codeOf)
                put("Results row", away.fields("first") + home2.fields("second"))
            }
        }
    }

    private fun assertConsistent(item: SportsLiveItem, snapshot: ScoreSnapshot?): Map<String, Any?> {
        val all = surfaces(item, snapshot)
        val reference = all.getValue("Live Now row")
        for ((surface, fields) in all) {
            for ((field, value) in fields) {
                assertEquals("$surface: $field", reference[field], value)
            }
        }
        return reference
    }

    @Test
    fun `a ranked college game reads the same on every surface, rank included`() {
        val reference = assertConsistent(item("Indiana Pacers at Philadelphia 76ers"), ranked)
        assertEquals(null, reference["firstRank"])
        assertEquals(5, reference["secondRank"])
        assertEquals("IND", reference["firstCode"])
        assertEquals("PHI", reference["secondCode"])
        assertNotNull(reference["firstLogo"])
        assertTrue((reference["tag"] as String).isNotEmpty())
    }

    @Test
    fun `a pro game shows no rank anywhere`() {
        val pro = ranked.copy(league = EspnLeagueMap.NBA, leagueName = "NBA")
        val reference = assertConsistent(item("Indiana Pacers at Philadelphia 76ers"), pro)
        assertEquals(null, reference["secondRank"])
    }

    @Test
    fun `an unmatched game reads the same everywhere, with codes that are never cut`() {
        val reference = assertConsistent(item("Dallas Mavericks vs Los Angeles Clippers"), null)
        @Suppress("UNCHECKED_CAST")
        val names = reference["secondNames"] as List<String>
        assertEquals("Los Angeles Clippers", names.first())
        assertTrue(names.last(), names.last().length <= GameIdentities.CODE_MAX)
    }
}
