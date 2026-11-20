package com.nuvio.tv.core.sportslive

import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.FollowedTeam
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsLiveTeamsTest {

    @Test
    fun `game names parse with every separator, case-insensitively, and at puts home second without rankings`() {
        val cases = listOf(
            // Highfly "vs" names.
            "Tenerife vs Ceuta" to TeamPair("Tenerife", "Ceuta"),
            "Cyprus vs Montenegro" to TeamPair("Cyprus", "Montenegro"),
            "Detroit Lions vs New England Patriots" to TeamPair("Detroit Lions", "New England Patriots"),
            // Nebula and CNC names.
            "Las Vegas Raiders vs Seattle Seahawks" to TeamPair("Las Vegas Raiders", "Seattle Seahawks"),
            "Dallas Cowboys vs San Francisco 49ers" to TeamPair("Dallas Cowboys", "San Francisco 49ers"),
            "AFG vs ZIM" to TeamPair("AFG", "ZIM"),
            // Sports Replay "at" names: rankings stripped, home second.
            "2 Utah Utes at 23 Arizona Wildcats" to TeamPair(home = "Arizona Wildcats", away = "Utah Utes"),
            "Boston College Eagles at 5 Notre Dame Fighting Irish" to TeamPair(home = "Notre Dame Fighting Irish", away = "Boston College Eagles"),
            // Other separators, case-insensitively.
            "Celtics vs. 76ers" to TeamPair("Celtics", "76ers"),
            "Arsenal v Chelsea" to TeamPair("Arsenal", "Chelsea"),
            "Arsenal VS Chelsea" to TeamPair("Arsenal", "Chelsea"),
            "Bills @ Chiefs" to TeamPair("Chiefs", "Bills"),
            "Real Madrid - Barcelona" to TeamPair("Real Madrid", "Barcelona"),
            "Wolves AT Stoke" to TeamPair(home = "Stoke", away = "Wolves")
        )
        for ((name, expected) in cases) {
            assertEquals(name, expected, SportsLiveTeams.parse(name))
        }
    }

    @Test
    fun `hyphenated words and channel prefixes do not split`() {
        assertNull(SportsLiveTeams.parse("CA-TSN 4"))
        assertNull(SportsLiveTeams.parse("Saint-Etienne"))
        assertNull(SportsLiveTeams.parse("ASIA-IN| STAR SPORTS 1 HINDI FHD"))
        assertEquals(TeamPair("Saint-Etienne", "Paris-FC"), SportsLiveTeams.parse("Saint-Etienne - Paris-FC"))
    }

    @Test
    fun `channels and non-games return null`() {
        listOf(
            "CA : TSN 4", "UK| SKY SPORTS 1", "NFL RedZone", "NFL REDZONE", "ATP 250",
            "Formula 1", "MotoGP", "Today's Games", "beIN Sports 1 AU", "(HD) : TNT SPORT 1"
        ).forEach { assertNull(it, SportsLiveTeams.parse(it)) }
    }

    @Test
    fun `empty side or separator at the edge returns null`() {
        assertNull(SportsLiveTeams.parse(" vs Ceuta"))
        assertNull(SportsLiveTeams.parse("Tenerife vs "))
        assertNull(SportsLiveTeams.parse("Tenerife vs   "))
        assertNull(SportsLiveTeams.parse("vs"))
        assertNull(SportsLiveTeams.parse(""))
        assertNull(SportsLiveTeams.parse("Foo - "))
    }

    @Test
    fun `normalise lowercases strips rankings and club suffixes`() {
        assertEquals("utah utes", SportsLiveTeams.normalise("2 Utah Utes"))
        assertEquals("tenerife", SportsLiveTeams.normalise("  Tenerife  FC "))
        assertEquals("sevilla", SportsLiveTeams.normalise("Sevilla CF"))
        assertEquals("st pauli", SportsLiveTeams.normalise("St   Pauli SC"))
        assertEquals("san francisco 49ers", SportsLiveTeams.normalise("San Francisco 49ers"))
        assertEquals("76ers", SportsLiveTeams.normalise("76ers"))
    }

    @Test
    fun `normalise and parse drop bracketed groups and channel prefixes`() {
        assertEquals("celtics", SportsLiveTeams.normalise("US| PEACOCK PPV 004 [Celtics"))
        assertEquals("76ers", SportsLiveTeams.normalise("76ers (2026-11-10 19:00:05)]"))
        assertEquals("arsenal", SportsLiveTeams.normalise("Arsenal [HD] (1080p)"))
        assertEquals("chelsea", SportsLiveTeams.normalise("Chelsea)"))
        assertEquals("hd", SportsLiveTeams.normalise("(HD)"))
        assertEquals(
            TeamPair("Celtics", "76ers"),
            SportsLiveTeams.parse("US| PEACOCK PPV 004 [Celtics vs 76ers (2026-11-10 19:00:05)]")
        )
        assertTrue(SportsLiveTeams.matches("Boston Celtics", "US| PEACOCK PPV 004 [Celtics"))
    }

    @Test
    fun `team qualifiers stay part of the name while other trailing groups go`() {
        assertEquals("arsenal (w)", SportsLiveTeams.normalise("Arsenal (W)"))
        assertEquals("arsenal (women)", SportsLiveTeams.normalise("Arsenal (Women) [HD]"))
        assertEquals("england (u21)", SportsLiveTeams.normalise("England (U21)"))
        assertEquals("england [u21]", SportsLiveTeams.normalise("England [U21] (1080p)"))
        assertEquals("arsenal", SportsLiveTeams.normalise("Arsenal (HD)"))
        assertEquals(TeamPair("Arsenal (W)", "Chelsea (W)"), SportsLiveTeams.parse("Arsenal (W) vs Chelsea (W) (2026-11-10)"))
        assertEquals("arsenal (w)", SportsLiveTeams.normalise("Arsenal FC (W)"))
        assertEquals(SportsLiveTeams.normalise("Arsenal (W)"), SportsLiveTeams.normalise("Arsenal FC (W) [HD]"))
        assertEquals("sevilla [u21]", SportsLiveTeams.normalise("2 Sevilla CF [U21]"))
        assertTrue(SportsLiveTeams.matches("Arsenal FC (W)", "Arsenal (W)"))
        assertFalse(SportsLiveTeams.matches("Arsenal", "Arsenal (W)"))
        assertTrue(SportsLiveTeams.matches("Arsenal (W)", "arsenal (w)"))
    }

    @Test
    fun `channel names are games only inside a bracket group with a separator`() {
        assertEquals(
            TeamPair("Celtics", "76ers"),
            SportsLiveTeams.parseBracketed("US| PEACOCK PPV 004 [Celtics vs. 76ers (2026-11-10 19:00:05)]")
        )
        assertNull(SportsLiveTeams.parseBracketed("Sky Sports - HD"))
        assertNull(SportsLiveTeams.parseBracketed("US| PEACOCK PPV 004 [HD] - Backup"))
        assertNull(SportsLiveTeams.parseBracketed("Celtics vs 76ers"))
    }

    @Test
    fun `a followed team matches by name when the sports agree or either is unknown`() {
        val game = game("Patriots Academy FC vs New England Patriots", "American Football")
        assertTrue(SportsLiveTeams.follows(FollowedTeam("Patriots", "American Football"), game))
        assertTrue(SportsLiveTeams.follows(FollowedTeam("patriots", "american football"), game))
        assertTrue(SportsLiveTeams.follows(FollowedTeam("Patriots", null), game))
        assertTrue(SportsLiveTeams.follows(FollowedTeam("Patriots", "Soccer"), game.copy(sport = null)))
        assertFalse(SportsLiveTeams.follows(FollowedTeam("Patriots", "Soccer"), game))
        assertFalse(SportsLiveTeams.follows(FollowedTeam("Lions", null), game))
        assertFalse(SportsLiveTeams.follows(FollowedTeam("Patriots", null), game.copy(teams = null)))
        assertTrue(SportsLiveTeams.isFollowed(game, listOf(FollowedTeam("Lions"), FollowedTeam("Patriots"))))
        assertFalse(SportsLiveTeams.isFollowed(game, emptyList()))
    }

    @Test
    fun `followsSide matches one side only, under the same sport rule`() {
        val game = game("Patriots Academy FC vs New England Patriots", "American Football")
        val patriots = FollowedTeam("Patriots", "American Football")
        assertTrue(SportsLiveTeams.followsSide(patriots, "New England Patriots", game))
        assertFalse(SportsLiveTeams.followsSide(patriots, "Patriots Academy FC", game))
        assertFalse(SportsLiveTeams.followsSide(FollowedTeam("Patriots", "Soccer"), "New England Patriots", game))
        assertTrue(SportsLiveTeams.followsSide(FollowedTeam("Patriots", "Soccer"), "New England Patriots", game.copy(isChannel = true)))
    }

    @Test
    fun `a channel naming a followed game matches whatever its catalog-name sport`() {
        val ppvName = "US| PEACOCK PPV 004 [Celtics vs. 76ers (2026-11-10 19:00:05)]"
        val channel = game(ppvName, "EasyPlay Live TV (tv)")
            .copy(isChannel = true, teams = SportsLiveTeams.parseBracketed(ppvName))

        assertTrue(SportsLiveTeams.follows(FollowedTeam("Celtics", "Basketball"), channel))
        assertFalse(SportsLiveTeams.follows(FollowedTeam("Lakers", "Basketball"), channel))
        // An event keeps the sport check.
        assertFalse(SportsLiveTeams.follows(FollowedTeam("Celtics", "Basketball"), channel.copy(isChannel = false)))
    }

    @Test
    fun `knownSport takes genres, never the catalog name or a generic genre`() {
        val basketball = game("Celtics vs 76ers", "Basketball").withGenres("Basketball")
        assertEquals("Basketball", SportsLiveTeams.knownSport(basketball))
        // No genres: the sport is the catalog's name.
        assertNull(SportsLiveTeams.knownSport(game("Celtics vs 76ers", "Live")))
        listOf("Sports", "Live TV", "live", "TV", "EVENTS").forEach { generic ->
            assertNull(generic, SportsLiveTeams.knownSport(game("Celtics vs 76ers", generic).withGenres(generic)))
        }
        assertNull(SportsLiveTeams.knownSport(game("Celtics vs 76ers", null)))
    }

    @Test
    fun `a follow made in one addon matches the same team in another`() {
        // Addon A has no genres (its sport is its catalog's name); addon B says Basketball.
        val inA = game("Celtics vs 76ers", "Live")
        val inB = game("Boston Celtics vs Philadelphia 76ers", "Basketball").withGenres("Basketball")
        val followedInA = FollowedTeam("Celtics", SportsLiveTeams.knownSport(inA))
        val followedInB = FollowedTeam("Boston Celtics", SportsLiveTeams.knownSport(inB))

        assertNull(followedInA.sport)
        assertTrue(SportsLiveTeams.follows(followedInA, inB))
        assertTrue(SportsLiveTeams.follows(followedInB, inA))
        // A legacy follow that stored a catalog name still matches where the sport is unknown.
        assertTrue(SportsLiveTeams.follows(FollowedTeam("Celtics", "SportzX Live Events"), inA))
        // A stored generic genre is no sport either.
        assertTrue(SportsLiveTeams.follows(FollowedTeam("Celtics", "Sports"), inB))
    }

    @Test
    fun `a merged card follows a side when any of its sources does`() {
        val primary = game("Celtics vs 76ers", "Basketball").withGenres("Basketball")
        // The alternate's addon has no genres: its sport is unknown.
        val alternate = game("Boston Celtics vs Philadelphia 76ers", "Live")
        val merged = primary.copy(alternates = listOf(alternate))
        val followed = FollowedTeam("Celtics", "NBA")

        assertFalse(SportsLiveTeams.followsSide(followed, "Celtics", merged))
        assertTrue(SportsLiveTeams.followsSideOfAnySource(followed, "Celtics", merged))
        assertFalse(SportsLiveTeams.followsSideOfAnySource(followed, "76ers", merged))
        // The card's star (markFollowed) and its menu agree.
        assertTrue(SportsLiveItemBucketer.markFollowed(listOf(merged), listOf(followed)).single().followed)
    }

    @Test
    fun `matches equal or whole-word suffix both ways case-insensitively`() {
        assertTrue(SportsLiveTeams.matches("Patriots", "New England Patriots"))
        assertTrue(SportsLiveTeams.matches("NEW ENGLAND PATRIOTS", "patriots"))
        assertTrue(SportsLiveTeams.matches("2 Utah Utes", "Utah Utes"))
        assertTrue(SportsLiveTeams.matches("Tenerife FC", "tenerife"))
        assertFalse(SportsLiveTeams.matches("Patriots", "Patriots Academy"))
        assertFalse(SportsLiveTeams.matches("ots", "Patriots"))
        assertFalse(SportsLiveTeams.matches("Lions", "Detroit Lions Women"))
        assertFalse(SportsLiveTeams.matches("", "Patriots"))
        assertFalse(SportsLiveTeams.matches("FC", "Barcelona FC"))
    }

    @Test
    fun `canonical aliases fold country codes to their full names`() {
        assertTrue(SportsLiveTeams.matches("PAK", "Pakistan"))
        assertTrue(SportsLiveTeams.matches("SL", "Sri Lanka"))
        assertTrue(SportsLiveTeams.matches("nz", "New Zealand"))
        assertTrue(SportsLiveTeams.matches("South Africa", "SA"))
        assertTrue(SportsLiveTeams.matches("USA", "United States"))
        assertEquals("pakistan", SportsLiveTeams.canonicalise("PAK"))
        assertEquals(SportsLiveTeams.canonicalise("Sri Lanka"), SportsLiveTeams.canonicalise("SL"))
        assertEquals(SportsLiveTeams.canonicalise("New Zealand"), SportsLiveTeams.canonicalise("NZ"))
    }

    @Test
    fun `aliases fold only when both sides resolve and keep qualifiers distinct`() {
        assertFalse(SportsLiveTeams.matches("PAK", "Pakistan A")) // one side does not resolve
        assertFalse(SportsLiveTeams.matches("PAK", "IND")) // different canonical teams
        assertFalse(SportsLiveTeams.matches("PAK (W)", "Pakistan")) // women vs men
        assertTrue(SportsLiveTeams.matches("PAK (W)", "Pakistan (W)"))
        // An unknown name is canonicalised to itself, unchanged.
        assertEquals("chelsea", SportsLiveTeams.canonicalise("Chelsea"))
        assertEquals("las vegas raiders", SportsLiveTeams.canonicalise("Las Vegas Raiders"))
    }

    @Test
    fun `fixture event names with a separator all parse`() {
        val names = fixtureNames("highfly_live.json") + fixtureNames("nebula_live.json") +
            fixtureNames("nebula_replays.json") + fixtureNames("replay_football.json") +
            fixtureNames("cnc_live_events.json").map { it.removePrefix("🔜 ").removePrefix("✅ ").removePrefix("🔴 ") }
        // A tournament "vs" its event names no game since live.20 (neither side is team-like).
        val notGames = setOf("ATP 250 vs Stockholm Open")
        val games = names.filter { Regex("(?i) (vs\\.?|v|at|@|-) ").containsMatchIn(it) && it !in notGames }
        assertTrue(games.size > 20)
        games.forEach { name ->
            val pair = SportsLiveTeams.parse(name)
            assertTrue(name, pair != null && pair.home.isNotBlank() && pair.away.isNotBlank())
        }
    }

    @Test
    fun `fixture channel and team-list names never parse`() {
        val names = fixtureNames("nebula_channels.json") + fixtureNames("ultra_tv_uk.json") +
            fixtureNames("replay_football_teams.json") +
            fixtureNames("highfly_live.json").filter { ":" in it }
        assertTrue(names.size > 20)
        names.forEach { assertNull(it, SportsLiveTeams.parse(it)) }
    }

    private fun game(name: String, sport: String?) = SportsLiveItem(
        meta = MetaPreview(
            id = name, type = ContentType.UNKNOWN, rawType = "sport", name = name, poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = emptyList()
        ),
        addonId = "a", addonName = "A", addonBaseUrl = "https://a.example", catalogKey = "k", catalogName = "Live",
        kind = SportsLiveKind.EVENTS, isLive = true, isChannel = false, startMs = null, sport = sport,
        teams = SportsLiveTeams.parse(name)
    )

    private fun SportsLiveItem.withGenres(vararg genres: String) = copy(meta = meta.copy(genres = genres.toList()))

    private fun fixtureNames(file: String): List<String> {
        val json = javaClass.classLoader!!.getResourceAsStream("sportslive/$file")!!
            .bufferedReader().use { it.readText() }
        return Regex("\"name\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(json).map { it.groupValues[1] }.toList()
    }

    @Test
    fun `youth forms are read as one qualifier`() {
        assertEquals("england u21", SportsLiveTeams.normalise("England U-21"))
        assertEquals("england u21", SportsLiveTeams.normalise("England Under 21"))
        assertEquals("england u21", SportsLiveTeams.normalise("England Under-21"))
        assertEquals("england u21", SportsLiveTeams.normalise("England U21s"))
        assertEquals("sevilla u19", SportsLiveTeams.normalise("Sevilla CF U19"))
        assertTrue(SportsLiveTeams.matches("England U-21", "England Under 21"))
        assertFalse(SportsLiveTeams.matches("England", "England U-21"))
        assertEquals("thunder", SportsLiveTeams.normalise("Thunder"))
    }

    @Test
    fun `ampersand, punctuation and small raised letters fold`() {
        assertEquals("brighton and hove albion", SportsLiveTeams.normalise("Brighton & Hove Albion"))
        assertEquals("st louis blues", SportsLiveTeams.normalise("St. Louis Blues"))
        assertEquals("paris saint germain", SportsLiveTeams.normalise("Paris Saint-Germain"))
        assertEquals("arsenal", SportsLiveTeams.normalise("Arsenal ᴸᴵⱽᴱ"))
        assertEquals("arsenal", SportsLiveTeams.normalise("Arsenal ᴴᴰ"))
        assertEquals("arsenal", SportsLiveTeams.normalise("ᴬʀꜱᴇɴᴀʟ"))
        assertTrue(SportsLiveTeams.matches("St Louis Blues", "St. Louis Blues"))
    }

    @Test
    fun `leading club tags drop and club aliases fold`() {
        assertEquals("milan", SportsLiveTeams.normalise("AC Milan"))
        assertEquals("roma", SportsLiveTeams.normalise("AS Roma"))
        assertEquals("stuttgart", SportsLiveTeams.normalise("VfB Stuttgart"))
        assertEquals("cologne", SportsLiveTeams.normalise("1. FC Koln"))
        assertEquals("bournemouth", SportsLiveTeams.normalise("AFC Bournemouth"))
        assertEquals("sc state", SportsLiveTeams.normalise("SC State"))
        assertEquals(SportsLiveTeams.canonicalise("Manchester United"), SportsLiveTeams.canonicalise("Man Utd"))
        assertEquals(SportsLiveTeams.canonicalise("Paris Saint-Germain"), SportsLiveTeams.canonicalise("PSG"))
        assertTrue(SportsLiveTeams.matches("Man Utd", "Manchester United FC"))
        assertFalse(SportsLiveTeams.matches("Inter Milan", "AC Milan"))
        assertFalse(SportsLiveTeams.matches("Spurs", "Tottenham Hotspur"))
    }

    @Test
    fun `merge matching folds name variants and a women marker`() {
        val full = SportsLiveTeams.MergeMatch.FULL
        val prefix = SportsLiveTeams.MergeMatch.PREFIX
        assertEquals(prefix, SportsLiveTeams.mergeMatch("Adelaide", "Adelaide 36ers"))
        assertEquals(prefix, SportsLiveTeams.mergeMatch("Brisbane W", "Brisbane Heat Women"))
        assertEquals(full, SportsLiveTeams.mergeMatch("Brisbane Heat W", "Brisbane Heat Women"))
        assertEquals(full, SportsLiveTeams.mergeMatch("Brisbane Heat (W)", "Brisbane Heat Womens"))
        assertEquals(full, SportsLiveTeams.mergeMatch("Illawarra Hawks", "Illawarra Hawks"))
        assertEquals(full, SportsLiveTeams.mergeMatch("Lakers", "Los Angeles Lakers"))
    }

    @Test
    fun `merge matching keeps different teams and qualifiers apart`() {
        val none = SportsLiveTeams.MergeMatch.NONE
        assertEquals(none, SportsLiveTeams.mergeMatch("Manchester United", "Manchester City"))
        assertEquals(none, SportsLiveTeams.mergeMatch("Brisbane Heat", "Brisbane Heat Women"))
        assertEquals(none, SportsLiveTeams.mergeMatch("Brisbane W", "Brisbane Heat"))
        assertEquals(none, SportsLiveTeams.mergeMatch("England", "England U21"))
        assertEquals(none, SportsLiveTeams.mergeMatch("New", "New England Patriots"))
        assertEquals(none, SportsLiveTeams.mergeMatch("Adelaide", "Melbourne"))
    }

    @Test
    fun `My Teams matching does not take the merge prefix rule`() {
        assertFalse(SportsLiveTeams.matches("Adelaide 36ers", "Adelaide"))
    }

    @Test
    fun `Bayer is Leverkusen and Bayern is Munich, never one another`() {
        // TV report #5: "Bayern München vs 1. FC Köln" must reach ESPN's "Bayern Munich" and "FC Cologne".
        for (name in listOf("Bayern", "FC Bayern", "Bayern München", "Bayern Munchen", "Bayern Muenchen", "FC Bayern München")) {
            assertTrue(name, SportsLiveTeams.matches(name, "Bayern Munich"))
            assertFalse(name, SportsLiveTeams.matches(name, "Bayer Leverkusen"))
            assertEquals(name, SportsLiveTeams.MergeMatch.NONE, SportsLiveTeams.mergeMatch(name, "Bayer Leverkusen"))
        }
        for (name in listOf("1. FC Köln", "Köln", "Koln", "Koeln", "FC Koln", "Cologne")) {
            assertTrue(name, SportsLiveTeams.matches(name, "FC Cologne"))
        }
        for (name in listOf("Bayer Leverkusen", "Bayer 04 Leverkusen", "Leverkusen", "Bayer 04")) {
            assertTrue(name, SportsLiveTeams.matches(name, "Bayer Leverkusen"))
            assertFalse(name, SportsLiveTeams.matches(name, "Bayern Munich"))
        }
        // "Bayer" alone names neither club for sure: no match either way.
        assertFalse(SportsLiveTeams.matches("Bayer", "Bayern Munich"))
        assertFalse(SportsLiveTeams.matches("Bayer", "Bayer Leverkusen"))
    }

    @Test
    fun `Le Havre is ESPN's Le Havre AC and Troyes is TRY`() {
        // TV report #5: "TRY vs Le H…" was Ligue 1's Troyes vs Le Havre; ESPN writes "Le Havre AC".
        assertEquals("le havre", SportsLiveTeams.normalise("Le Havre AC"))
        assertTrue(SportsLiveTeams.matches("Le Havre", "Le Havre AC"))
        assertEquals(SportsLiveTeams.MergeMatch.FULL, SportsLiveTeams.mergeMatch("Le Havre", "Le Havre AC"))
        assertEquals("milan", SportsLiveTeams.normalise("Milan AC"))
        // "AC" alone is no team.
        assertFalse(SportsLiveTeams.matches("AC", "Le Havre AC"))
    }

    @Test
    fun `letters accent folding keeps whole read plain, so a slashed o Bodo is Bodo`() {
        assertEquals("bodo glimt", SportsLiveTeams.normalise("Bodø/Glimt"))
        assertTrue(SportsLiveTeams.matches("Bodø/Glimt", "Bodo/Glimt"))
        assertTrue(SportsLiveTeams.matches("Brøndby IF", "Brondby IF"))
        assertEquals("lodz", SportsLiveTeams.normalise("Łódź").let(SportsLiveTeams::foldAccents))
    }
}
