package com.nuvio.tv.core.sportslive

import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** live.23, TV report #9: rows with no tag ("you have no idea what they are"), from the report's titles not placed. */
class SportsLiveUntaggedRulesTest {

    private fun item(name: String, sport: String? = "OTHER", catalog: String = "Live Events", genres: List<String> = emptyList()) = SportsLiveItem(
        meta = MetaPreview(
            id = name, type = ContentType.UNKNOWN, rawType = "sport", name = name, poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = genres
        ),
        addonId = "a", addonName = "a", addonBaseUrl = "https://example.invalid", catalogKey = "k", catalogName = catalog,
        kind = SportsLiveKind.EVENTS, isLive = true, isChannel = false, startMs = null, sport = sport,
        startHasTimeOfDay = false, teams = SportsLiveTeams.parse(name)
    )

    private fun assertKind(expected: SportsLiveSportKind?, item: SportsLiveItem) {
        assertEquals(item.meta.name, expected, SportsLiveSportKind.tagOf(item))
        assertEquals(item.meta.name, expected, SportsLiveMySportsScope.kindOf(item))
    }

    @Test
    fun `cricket competitions name their sport`() {
        assertKind(SportsLiveSportKind.CRICKET, item("Italia T10"))
        assertKind(SportsLiveSportKind.CRICKET, item("The Hundred: Oval Invincibles vs Welsh Fire"))
    }

    @Test
    fun `two rugby clubs no other sport shares are rugby, a shared name never`() {
        assertKind(SportsLiveSportKind.RUGBY, item("Saracens vs Harlequin FC"))
        assertKind(SportsLiveSportKind.RUGBY, item("Leinster vs Stade Toulousain"))
        assertKind(SportsLiveSportKind.RUGBY, item("Glasgow Warriors vs Benetton"))
        // Soccer clubs share these names: no guess.
        assertKind(null, item("Toulouse vs Lyon"))
        assertKind(null, item("Saracens vs Cardiff"))
    }

    @Test
    fun `fight promotions and fight nights are combat sports, boxing first`() {
        assertKind(SportsLiveSportKind.BOXING, item("BKFC Fight Night Myrtle Beach Richardson vs Oxendine"))
        assertKind(SportsLiveSportKind.BOXING, item("Gromda 24"))
        assertKind(SportsLiveSportKind.BOXING, item("Matchroom Boxing Fight Night: Kabayel vs Hysa"))
        assertKind(SportsLiveSportKind.MMA, item("UFC Fight Night 294 Prochazka vs Stirling"))
        assertKind(SportsLiveSportKind.MMA, item("ONE Friday Fights 135"))
        assertKind(SportsLiveSportKind.MMA, item("Friday Fights"))
        assertKind(SportsLiveSportKind.MMA, item("Oktagon 98 Legierski vs Buchinger"))
        assertKind(SportsLiveSportKind.MMA, item("Kickboxing: Zouad vs Diani"))
        // Two fighters' names in a fights catalog.
        assertKind(SportsLiveSportKind.MMA, item("Henry Cejudo vs Song Yadong", catalog = "Fights"))
    }

    @Test
    fun `a wrestling show's name alone is wrestling`() {
        listOf("Raw", "Dynamite", "AEW", "WWE", "NXT", "AEW Collision").forEach { assertKind(SportsLiveSportKind.WRESTLING, item(it)) }
        // A word of another title is not the show.
        assertKind(null, item("Raw Power vs Kings"))
    }

    @Test
    fun `what no rule tells keeps an unknown sport, the signal for the Other events fold`() {
        // Two plain nations (cricket, football and hockey all play them, #204), two fighters with nothing else, a bare
        // league word two sports use.
        for (name in listOf("Australia vs England", "New Zealand vs Scotland", "Henry Cejudo vs Song Yadong", "ISL", "NPL")) {
            val row = item(name)
            assertKind(null, row)
            assertTrue(name, SportsLiveUnidentified.isUntagged(row))
        }
        assertFalse(SportsLiveUnidentified.isUntagged(item("Saracens vs Harlequin FC")))
    }

    @Test
    fun `two plain nations are never guessed cricket, whatever nations they are`() {
        // Review A2 (replaces the 2026-11-21 "mainly cricket nation" guess): untagged AFC, SAFF and CAF qualifiers and
        // field hockey (India vs Singapore, Zimbabwe vs Nigeria) were tagged CRICKET. A pair of nations is cricket only
        // once ESPN's live cricket list confirms it (TeamIdentifier) or a text names cricket.
        listOf(
            "Sri Lanka vs Hong Kong", "South Africa vs Bangladesh", "Ireland Women vs Zimbabwe Women", "India vs Singapore",
            "Bangladesh vs Hong Kong", "Afghanistan vs Kuwait", "Zimbabwe vs South Africa", "Zimbabwe vs Nigeria",
            "India vs Germany", "UAE vs Nepal", "West Indies vs Afghanistan"
        ).forEach { name ->
            assertFalse(name, SportsLiveCricket.isCricketGame(SportsLiveTeams.parse(name)))
            assertKind(null, item(name))
        }
        // A side against a cricket nation, or two franchises: still cricket from the teams alone.
        assertTrue(SportsLiveCricket.isCricketGame(SportsLiveTeams.parse("Bangladesh A vs Hong Kong")))
        assertTrue(SportsLiveCricket.isCricketGame(SportsLiveTeams.parse("Mumbai Indians vs Chennai Super Kings")))
    }
}
