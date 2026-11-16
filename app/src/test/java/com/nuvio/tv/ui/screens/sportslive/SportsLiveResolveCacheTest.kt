package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.domain.model.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsLiveResolveCacheTest {

    private val stream = Stream(
        name = "HD",
        title = null,
        description = null,
        url = "https://cdn.example/hd.m3u8",
        ytId = null,
        infoHash = null,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = null,
        addonName = "Sports",
        addonLogo = null
    )

    @Test
    fun `a put value is returned within the TTL and gone after it`() {
        var now = 0L
        val cache = SportsLiveResolveCache().apply { nowMs = { now } }

        cache.put("k", stream)
        assertEquals(stream, cache.get("k"))
        assertTrue(cache.isFresh("k"))

        now += SportsLiveResolveCache.TTL_MS - 1
        assertEquals(stream, cache.get("k"))

        now += 1
        assertNull(cache.get("k"))
        assertFalse(cache.isFresh("k"))
    }

    @Test
    fun `invalidate drops the entry`() {
        val cache = SportsLiveResolveCache()
        cache.put("k", stream)
        cache.invalidate("k")
        assertNull(cache.get("k"))
    }
}
