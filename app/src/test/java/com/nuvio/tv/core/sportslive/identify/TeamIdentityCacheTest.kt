package com.nuvio.tv.core.sportslive.identify

import com.nuvio.tv.core.sportslive.identify.TeamLookupFixtures.SDB_DUBAI_BASKETBALL
import com.nuvio.tv.core.sportslive.identify.TeamLookupFixtures.SDB_MACCABI_TEL_AVIV_BC
import com.nuvio.tv.core.sportslive.identify.TeamLookupFixtures.moshi
import com.nuvio.tv.core.sportslive.identify.TeamLookupFixtures.sportsDb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** #168: known names are kept for good, unknown ones for a week, and both survive the file. */
class TeamIdentityCacheTest {

    private val week = TeamIdentityCache.UNKNOWN_TTL_MS
    private val dubai = sportsDb(SDB_DUBAI_BASKETBALL)

    @Test
    fun `a known name is kept for good and an unknown one expires after a week`() {
        val cache = TeamIdentityCache()
        assertNull(cache.answer("Dubai Basketball", 0L))
        cache.record("Dubai Basketball", dubai, 0L)
        cache.record("Nowhere Town", emptyList(), 0L)
        assertEquals(dubai, cache.answer("dubai  BASKETBALL", 10 * week))
        assertEquals(emptyList<TeamCandidate>(), cache.answer("Nowhere Town", week - 1))
        assertNull(cache.answer("Nowhere Town", week))
    }

    @Test
    fun `alternate names hold the team and still get their own look`() {
        val cache = TeamIdentityCache()
        cache.record("Dubai Basketball", dubai, 0L)
        // "BC Dubai" holds the team but was never asked itself.
        assertNull(cache.answer("BC Dubai", 0L))
        cache.record("BC Dubai", emptyList(), 1L)
        assertEquals(dubai, cache.answer("BC Dubai", 1L))
        // An unknown name that a later team names as its alternate becomes known.
        cache.record("Maccabi Tel Aviv", emptyList(), 2L)
        cache.recordHint("Maccabi Tel Aviv", "basketball", sportsDb(SDB_MACCABI_TEL_AVIV_BC), 3L)
        assertEquals("basketball", cache.answer("Maccabi Tel Aviv", 4L)!!.single().sport)
        assertEquals(setOf("basketball"), cache.hinted("Maccabi Tel Aviv"))
    }

    @Test
    fun `the cache goes through its file and back`() {
        val dir = Files.createTempDirectory("team_identity").toFile()
        try {
            val file = File(dir, "sportslive/team_identity.json")
            val cache = TeamIdentityCache()
            cache.record("Dubai Basketball", dubai, 5L)
            cache.record("Nowhere Town", emptyList(), 6L)
            cache.recordHint("Nowhere Town", "basketball", emptyList(), 7L)
            TeamIdentityStore(file, moshi).write(cache.snapshot())

            val read = TeamIdentityCache().apply { restore(TeamIdentityStore(file, moshi).read()) }
            assertEquals(dubai, read.answer("Dubai Basketball", 100L))
            assertEquals(emptyList<TeamCandidate>(), read.answer("Nowhere Town", 100L))
            assertNull(read.answer("Nowhere Town", 6L + week))
            assertEquals(setOf("basketball"), read.hinted("Nowhere Town"))
            assertEquals(cache.snapshot().toSet(), read.snapshot().toSet())

            // An unreadable file is empty.
            file.writeText("not json")
            assertTrue(TeamIdentityStore(file, moshi).read().isEmpty())
            assertTrue(TeamIdentityStore(File(dir, "missing.json"), moshi).read().isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `the oldest names go past the bound`() {
        val cache = TeamIdentityCache(maxEntries = 2)
        cache.record("Alpha", emptyList(), 1L)
        cache.record("Beta", emptyList(), 2L)
        cache.record("Gamma", emptyList(), 3L)
        assertNull(cache.answer("Alpha", 3L))
        assertEquals(emptyList<TeamCandidate>(), cache.answer("Gamma", 3L))
    }
}
