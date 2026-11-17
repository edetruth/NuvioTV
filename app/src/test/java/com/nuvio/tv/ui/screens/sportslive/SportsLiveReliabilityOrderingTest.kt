package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.SportsLiveSourceReliability
import com.nuvio.tv.core.sportslive.SportsLiveSourceReliability.Outcome
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.ui.screens.player.sourceFallbackKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

/** How remembered source reliability (#40) orders a Sports & Live card's sources in the full player. */
class SportsLiveReliabilityOrderingTest {

    @Before
    fun setUp() {
        SportsLiveExtraSources.reset()
        SportsLiveSourceReliability.reset()
    }

    @After
    fun tearDown() {
        SportsLiveExtraSources.reset()
        SportsLiveSourceReliability.reset()
    }

    // --- source identity ---

    @Test
    fun `a source is its addon and catalog, not the volatile item id`() {
        assertEquals("one|one_live", SportsLiveSourceReliability.keyOf(PRIMARY))
        assertEquals(
            SportsLiveSourceReliability.keyOf(PRIMARY),
            SportsLiveSourceReliability.keyOf(PRIMARY.copy(meta = PRIMARY.meta.copy(id = "tomorrows-game")))
        )
    }

    @Test
    fun `the player credits a stream to its source by url, else by its Sources panel name`() {
        SportsLiveExtraSources.register("game", CARD)
        val fromAlt = stream("x", "https://two.example.invalid/x.m3u8", "Two")
        SportsLiveExtraSources.noteStreamSource(fromAlt, ALT)

        assertEquals("two|two_live", SportsLiveExtraSources.sourceKeyFor("game", fromAlt.url, "anything"))
        assertEquals("one|one_live", SportsLiveExtraSources.sourceKeyFor("game", "https://unknown.example.invalid/", "One"))
        assertEquals("two|two_live", SportsLiveExtraSources.sourceKeyFor("game", null, "Two"))
        assertNull(SportsLiveExtraSources.sourceKeyFor("game", null, "Stranger"))
    }

    @Test
    fun `movies and shows are never credited`() {
        assertNull(SportsLiveExtraSources.sourceKeyFor("movie", "https://cdn.example.invalid/m.mp4", "One"))
        assertNull(SportsLiveExtraSources.sourceKeyFor(null, null, null))
    }

    @Test
    fun `a single-source card marked for sports is credited by its addon name`() {
        SportsLiveExtraSources.markSportsPlayback("solo", PRIMARY.copy(meta = PRIMARY.meta.copy(id = "solo")))

        assertEquals("one|one_live", SportsLiveExtraSources.sourceKeyFor("solo", null, "One"))
    }

    @Test
    fun `a light player credits the source the resolver found the stream on, else the card's`() {
        val fromAlt = stream("x", "https://two.example.invalid/x.m3u8", "Two")
        SportsLiveExtraSources.noteStreamSource(fromAlt, ALT)

        assertEquals("two|two_live", SportsLiveExtraSources.sourceKeyFor(CARD, fromAlt))
        assertEquals("one|one_live", SportsLiveExtraSources.sourceKeyFor(CARD, stream("y", "https://one.example.invalid/y.m3u8", "One")))
    }

    // --- Sources panel ---

    @Test
    fun `the Sources panel lists the card's most reliable source first, other groups stay put`() {
        SportsLiveExtraSources.register("game", CARD)
        val groups = listOf(group("One"), group("Unrelated"), group("Two"))

        // No evidence yet: the player's order.
        assertEquals(groups, SportsLiveExtraSources.orderGroups("game", groups))

        SportsLiveSourceReliability.record("one|one_live", Outcome.FAILURE)
        SportsLiveSourceReliability.record("two|two_live", Outcome.SUCCESS)

        assertEquals(listOf("Two", "Unrelated", "One"), SportsLiveExtraSources.orderGroups("game", groups).map { it.addonName })
    }

    @Test
    fun `the Sources panel order of other content is untouched`() {
        val groups = listOf(group("One"), group("Two"))
        SportsLiveSourceReliability.record("two|two_live", Outcome.SUCCESS)

        assertSame(groups, SportsLiveExtraSources.orderGroups("movie", groups))
    }

    // --- failover ---

    private val settings = PlayerSettings(
        streamAutoPlayFallbackOnFailure = true,
        streamAutoPlayMode = StreamAutoPlayMode.FIRST_STREAM
    )
    private val primaryStreams = (1..2).map { stream("P$it", "https://one.example.invalid/p$it.m3u8", "One") }
    private val twoStreams = listOf(stream("T1", "https://two.example.invalid/t1.m3u8", "Two"))
    private val threeStreams = listOf(stream("H1", "https://three.example.invalid/h1.m3u8", "Three"))

    private fun next(
        scores: Map<String, Double>,
        tried: Set<String> = emptySet(),
        extrasAllowed: Boolean = true,
        settings: PlayerSettings = this.settings
    ) = sportsLiveFallbackCandidate(
        streams = primaryStreams,
        extras = twoStreams + threeStreams,
        extrasAllowed = extrasAllowed,
        settings = settings,
        installedAddonNames = setOf("One", "Two", "Three"),
        triedKeys = tried,
        failedAddonName = "One",
        failedInfoHash = null,
        sourceScore = { scores[it.addonName] ?: SportsLiveSourceReliability.NEUTRAL }
    )

    @Test
    fun `failover tries the most reliable source first`() {
        assertEquals(threeStreams[0], next(mapOf("One" to 0.2, "Two" to 0.5, "Three" to 0.9)))
    }

    @Test
    fun `with no evidence failover keeps the primary's streams first`() {
        assertEquals(primaryStreams[0], next(emptyMap()))
    }

    @Test
    fun `an extra ranked ahead of the primary need not wait for the primary's search`() {
        assertEquals(threeStreams[0], next(mapOf("One" to 0.2, "Three" to 0.9), extrasAllowed = false))
        // Ranked behind the primary, extras still wait for it.
        val primaryTried = primaryStreams.mapTo(HashSet()) { it.sourceFallbackKey() }
        assertNull(next(emptyMap(), tried = primaryTried, extrasAllowed = false))
    }

    @Test
    fun `the user's regex still rules out a more reliable source's streams`() {
        val regex = settings.copy(streamAutoPlayMode = StreamAutoPlayMode.REGEX_MATCH, streamAutoPlayRegex = "T1")

        assertEquals(twoStreams[0], next(mapOf("Three" to 0.95), settings = regex))
        assertNull(next(mapOf("Three" to 0.95), settings = regex.copy(streamAutoPlayRegex = "nothing")))
    }

    @Test
    fun `chosen addons still rule out a more reliable source`() {
        val chosen = settings.copy(streamAutoPlaySelectedAddons = setOf("Two"))
        val primaryTried = primaryStreams.mapTo(HashSet()) { it.sourceFallbackKey() }

        assertEquals(twoStreams[0], next(mapOf("Three" to 0.95), tried = primaryTried, settings = chosen))
    }

    private companion object {
        fun stream(name: String, url: String, addon: String) = Stream(
            name = name,
            title = null,
            description = null,
            url = url,
            ytId = null,
            infoHash = null,
            fileIdx = null,
            externalUrl = null,
            behaviorHints = null,
            addonName = addon,
            addonLogo = null
        )

        fun group(name: String) = AddonStreams(addonName = name, addonLogo = null, streams = listOf(stream(name, "https://$name.example.invalid/s.m3u8", name)))

        fun item(id: String, addon: String) = SportsLiveItem(
            meta = MetaPreview(
                id = id,
                type = ContentType.UNKNOWN,
                rawType = "sport",
                name = "A vs B",
                poster = null,
                posterShape = PosterShape.LANDSCAPE,
                background = null,
                logo = null,
                description = null,
                releaseInfo = "LIVE",
                imdbRating = null,
                genres = emptyList()
            ),
            addonId = addon.lowercase(),
            addonName = addon,
            addonBaseUrl = "https://${addon.lowercase()}.example.invalid",
            catalogKey = "${addon.lowercase()}_live",
            catalogName = "Live",
            kind = SportsLiveKind.EVENTS,
            isLive = true,
            isChannel = false,
            startMs = null,
            sport = null
        )

        val PRIMARY = item("game", "One")
        val ALT = item("alt", "Two")
        val CARD = PRIMARY.copy(alternates = listOf(ALT))
    }
}
