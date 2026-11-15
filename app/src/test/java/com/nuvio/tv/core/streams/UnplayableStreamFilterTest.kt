package com.nuvio.tv.core.streams

import com.nuvio.tv.core.player.StreamAutoPlaySelector
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.local.StreamAutoPlaySource
import com.nuvio.tv.data.mapper.toDomain
import com.nuvio.tv.data.remote.dto.StreamResponseDto
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamClientResolve
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnplayableStreamFilterTest {

    @Test
    fun `google root link is junk`() {
        assertTrue(UnplayableStreamFilter.isUnplayableJunk(stream(url = "https://www.google.com")))
        assertTrue(UnplayableStreamFilter.isUnplayableJunk(stream(url = "https://www.google.com/")))
    }

    @Test
    fun `health link is junk`() {
        assertTrue(UnplayableStreamFilter.isUnplayableJunk(stream(url = "https://sports.example.invalid/health")))
        assertTrue(UnplayableStreamFilter.isUnplayableJunk(stream(url = "http://sports.example.invalid/HEALTH?x=1")))
    }

    @Test
    fun `m3u8 playlist is not junk`() {
        assertFalse(UnplayableStreamFilter.isUnplayableJunk(stream(url = "https://x.cfd/m3u/1/live.m3u8?ft=REDACTED")))
    }

    @Test
    fun `magnet and infoHash are never junk`() {
        assertFalse(
            UnplayableStreamFilter.isUnplayableJunk(
                stream(url = "magnet:?xt=urn:btih:988c0dfb1d2b22fbf30d10bb9a75323534532dbb")
            )
        )
        assertFalse(
            UnplayableStreamFilter.isUnplayableJunk(
                stream(url = null, infoHash = "988c0dfb1d2b22fbf30d10bb9a75323534532dbb")
            )
        )
        assertFalse(UnplayableStreamFilter.isUnplayableJunk(stream(url = null, ytId = "dQw4w9WgXcQ")))
    }

    @Test
    fun `external url only entry is not junk`() {
        assertFalse(UnplayableStreamFilter.isUnplayableJunk(stream(url = null, externalUrl = "https://pengu.uk/donate")))
    }

    @Test
    fun `junk shaped url with infoHash is kept`() {
        assertFalse(
            UnplayableStreamFilter.isUnplayableJunk(
                stream(url = "https://www.google.com", infoHash = "988c0dfb1d2b22fbf30d10bb9a75323534532dbb")
            )
        )
    }

    @Test
    fun `debrid resolved stream is never junk`() {
        assertFalse(UnplayableStreamFilter.isUnplayableJunk(stream(url = "https://cdn.example.com/").copy(debridResolved = true)))
    }

    @Test
    fun `unparseable url is not junk`() {
        assertFalse(UnplayableStreamFilter.isUnplayableJunk(stream(url = "https://bad host/ with spaces")))
    }

    @Test
    fun `short redirect root url is dropped by rule`() {
        // Accepted trade-off: a bare host root can never be a media file.
        assertTrue(UnplayableStreamFilter.isUnplayableJunk(stream(url = "https://cdn.example.com/")))
    }

    @Test
    fun `all junk addon yields empty list`() {
        val streams = listOf(stream(url = "https://www.google.com"), stream(url = "https://a.b/health"))
        assertEquals(emptyList<Stream>(), streams.filterNot(UnplayableStreamFilter::isUnplayableJunk))
    }

    @Test
    fun `support web page is junk`() {
        assertTrue(
            UnplayableStreamFilter.isUnplayableJunk(
                stream(url = "https://play.rifflehq.in/support.html#from=sports-addon")
            )
        )
        assertTrue(UnplayableStreamFilter.isUnplayableJunk(stream(url = "https://h/about.HTM?x=1")))
    }

    @Test
    fun `health with trailing slash is junk`() {
        assertTrue(UnplayableStreamFilter.isUnplayableJunk(stream(url = "https://h/health/")))
    }

    @Test
    fun `hls index playlist is not junk`() {
        assertFalse(UnplayableStreamFilter.isUnplayableJunk(stream(url = "https://h/live/index.m3u8")))
    }

    @Test
    fun `server target stream is never junk`() {
        assertFalse(
            UnplayableStreamFilter.isUnplayableJunk(
                stream(url = "https://www.google.com").copy(serverTarget = mockk(relaxed = true))
            )
        )
    }

    @Test
    fun `uppercase scheme root is junk`() {
        assertTrue(UnplayableStreamFilter.isUnplayableJunk(stream(url = "HTTPS://WWW.GOOGLE.COM")))
    }

    @Test
    fun `root with port is junk`() {
        assertTrue(UnplayableStreamFilter.isUnplayableJunk(stream(url = "https://h:8080/")))
    }

    @Test
    fun `root with fragment is junk`() {
        assertTrue(UnplayableStreamFilter.isUnplayableJunk(stream(url = "https://h/#x")))
    }

    @Test
    fun `client resolved torrent is never junk`() {
        val resolve = StreamClientResolve(
            type = "torrent", infoHash = "988c0dfb1d2b22fbf30d10bb9a75323534532dbb", fileIdx = null,
            magnetUri = null, sources = null, torrentName = null, filename = null, mediaType = null,
            mediaId = null, mediaOnlyId = null, title = null, season = null, episode = null,
            service = null, serviceIndex = null, serviceExtension = null, isCached = null
        )
        assertFalse(
            UnplayableStreamFilter.isUnplayableJunk(stream(url = "https://www.google.com").copy(clientResolve = resolve))
        )
    }

    @Test
    fun `highfly fixture keeps only the leaf stream`() {
        val kept = fixture("highfly_streams.json", "HighFly").filterNot(UnplayableStreamFilter::isUnplayableJunk)
        assertEquals(1, kept.size)
        assertTrue(kept.single().name!!.startsWith("Leaf (Strong)"))
    }

    @Test
    fun `sports replay fixture keeps torbox and magnet`() {
        val all = fixture("replay_streams.json", "Sports Replay")
        val kept = all.filterNot(UnplayableStreamFilter::isUnplayableJunk)
        assertEquals(listOf("TorBox", "Torrent"), kept.map { it.name })
    }

    @Test
    fun `first stream autoplay skips junk`() {
        val all = fixture("highfly_streams.json", "HighFly")
        val filtered = all.filterNot(UnplayableStreamFilter::isUnplayableJunk)
        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = filtered,
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("HighFly"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet()
        )
        assertTrue(selected!!.name!!.startsWith("Leaf (Strong)"))
    }

    private fun fixture(name: String, addonName: String): List<Stream> {
        val json = javaClass.classLoader!!.getResourceAsStream("streams/$name")!!
            .bufferedReader().use { it.readText() }
        val dto = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
            .adapter(StreamResponseDto::class.java)
            .fromJson(json)!!
        return dto.streams.orEmpty().map { it.toDomain(addonName, null) }
    }

    private fun stream(
        url: String?,
        infoHash: String? = null,
        ytId: String? = null,
        externalUrl: String? = null
    ) = Stream(
        name = "s",
        title = null,
        description = null,
        url = url,
        ytId = ytId,
        infoHash = infoHash,
        fileIdx = null,
        externalUrl = externalUrl,
        behaviorHints = null,
        addonName = "HighFly",
        addonLogo = null
    )
}
