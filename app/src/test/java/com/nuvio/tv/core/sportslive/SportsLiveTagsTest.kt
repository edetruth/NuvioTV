package com.nuvio.tv.core.sportslive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** live.20 F5 / #179: the one short-tag vocabulary of Sports & Live. */
class SportsLiveTagsTest {

    @Test
    fun `a short league or sport tag stays as it is`() {
        listOf("NBA", "NCAAM", "TENNIS", "CRICKET", "HOCKEY", "SOCCER", "BASEBALL", "FOOTBALL", "ATP", "U17 WC", "SERIE A")
            .forEach { assertEquals(it, SportsLiveTags.short(it, null)) }
        assertEquals("NBA", SportsLiveTags.short(" nba ", null))
        assertEquals("", SportsLiveTags.short("  ", SportsLiveSportKind.SOCCER))
    }

    @Test
    fun `a sport reads as its whole plain word, never an invented abbreviation`() {
        // #217: "BBALL", "CS", "AF" and "ALM" were unreadable on the TV.
        assertEquals("BASKETBALL", SportsLiveTags.short("BASKETBALL", null))
        assertEquals("BASKETBALL", SportsLiveTags.short("Basketball", SportsLiveSportKind.BASKETBALL))
        assertEquals("WRESTLING", SportsLiveTags.short("WRESTLING", null))
        assertEquals("RACING", SportsLiveTags.short("MOTORSPORT", null))
        assertEquals("RACING", SportsLiveTags.short("Motorsport", SportsLiveSportKind.MOTORSPORT))
        assertEquals("FIGHT", SportsLiveTags.short("Combat Sports", null))
        assertEquals("AFL", SportsLiveTags.short("Australian Football", null))
        assertEquals("ICE HOCKEY", SportsLiveTags.short("ICE HOCKEY", null))
        assertEquals("HOCKEY", SportsLiveTags.short("FIELD HOCKEY LEAGUE", null))
    }

    @Test
    fun `a long league tag takes its short form, else the row's sport, else its first word, else its start`() {
        // League names of up to ten capitals are written whole.
        assertEquals("BUNDESLIGA", SportsLiveTags.short("BUNDESLIGA", SportsLiveSportKind.SOCCER))
        assertEquals("NATIONS LG", SportsLiveTags.short("NATIONS LG", null))
        assertEquals("EUROLEAGUE", SportsLiveTags.short("EUROLEAGUE", SportsLiveSportKind.BASKETBALL))
        assertEquals("SOCCER", SportsLiveTags.short("EKSTRAKLASA", SportsLiveSportKind.SOCCER))
        assertEquals("GRAND", SportsLiveTags.short("GRAND SLAM CUP", null))
        // A league that names its sport reads as the sport (live.20 told Ekstraklasa as soccer); an unknown one is cut.
        assertEquals("SOCCER", SportsLiveTags.short("EKSTRAKLASA", null))
        assertEquals("SUPERETTAN", SportsLiveTags.short("SUPERETTAN", null))
    }

    @Test
    fun `every word of the vocabulary fits the column`() {
        SportsLiveSportKind.values().forEach { assertTrue(it.name, SportsLiveTags.sportWord(it).length <= SportsLiveTags.MAX_CHARS) }
        listOf(
            "WORLD CUP", "WOMEN'S WC", "U21 FRNDLY", "NATIONS LG", "EURO QUAL", "AFCON QUAL", "ASIAN CUP", "CHAMPNSHP",
            "LEAGUE CUP", "BUNDESLIGA", "DFB POKAL", "EREDIVISIE", "SUPER LIG", "SAUDI PRO", "LIGA 1 ROU", "EUROLEAGUE",
            "SOME LONG UNKNOWN LEAGUE"
        ).forEach { assertTrue(it, SportsLiveTags.short(it, null).length in 1..SportsLiveTags.MAX_CHARS) }
    }
}
