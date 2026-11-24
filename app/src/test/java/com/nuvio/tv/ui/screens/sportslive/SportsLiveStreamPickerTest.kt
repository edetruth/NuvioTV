package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.domain.model.Stream
import org.junit.Assert.assertEquals
import org.junit.Test

/** Stream picker cleanup: the picker lists the Sources panel's rows, groups and counts. */
class SportsLiveStreamPickerTest {

    private fun stream(name: String, addon: String) = Stream(
        name = name, title = null, description = null, url = "https://example.invalid/$name.m3u8", ytId = null,
        infoHash = null, fileIdx = null, externalUrl = null, behaviorHints = null, addonName = addon, addonLogo = null
    )

    @Test
    fun `the first links get no header, failed links read Didn't work and the sources' messages come last`() {
        // Links 0-1, failed from 2, messages from 4.
        val headers = sportsLivePickerHeaders(size = 5, isNotice = { it >= 4 }, isFailed = { it in 2..3 })
        assertEquals(mapOf(2 to SportsLiveSourceGroup.DIDNT_WORK, 4 to SportsLiveSourceGroup.NOTICES), headers)
    }

    @Test
    fun `a list of failed links only starts with its Didn't work header`() {
        assertEquals(mapOf(0 to SportsLiveSourceGroup.DIDNT_WORK), sportsLivePickerHeaders(size = 2, isNotice = { false }, isFailed = { true }))
        assertEquals(emptyMap<Int, SportsLiveSourceGroup>(), sportsLivePickerHeaders(size = 3, isNotice = { false }, isFailed = { false }))
    }

    @Test
    fun `the count line counts links and their sources, never a source's messages`() {
        val note = stream("Upgrade for more", "Source C")
        val streams = listOf(stream("One", "Source A"), stream("Two", "Source A"), stream("Three", "Source B"), note)
        assertEquals(3 to 2, sportsLivePickerCounts(streams) { it === note })
        assertEquals(0 to 0, sportsLivePickerCounts(listOf(note)) { true })
    }

    private fun streamWithTitle(name: String, title: String?, addon: String) = stream(name, addon).copy(title = title)

    @Test
    fun `a source name shows only on links that read the same as another source's link`() {
        val streams = listOf(
            stream("Main feed", "Source A"), stream("Main feed", "Source B"), stream("Backup", "Source A"), stream("Backup", "Source A")
        )
        // Two sources share "Main feed": both are tagged. "Backup" twice from one source is not told apart by its name.
        assertEquals(setOf(0, 1), sportsLiveSourceTaggedIndexes(streams, "Unknown"))
    }

    @Test
    fun `no link shows a source name when every link reads differently`() {
        val streams = listOf(stream("One", "Source A"), stream("Two", "Source B"))
        assertEquals(emptySet<Int>(), sportsLiveSourceTaggedIndexes(streams, "Unknown"))
    }

    @Test
    fun `a source's messages are never tagged`() {
        val note = stream("Main feed", "Source C")
        val streams = listOf(stream("Main feed", "Source A"), note)
        assertEquals(emptySet<Int>(), sportsLiveSourceTaggedIndexes(streams, "Unknown") { it === note })
    }

    @Test
    fun `a link label masks a score in its name and second line`() {
        val (name, details) = sportsLiveLinkLabel(streamWithTitle("Team A 2-1 Team B", "Live 3-1 HD", "Source A"), "Unknown")
        assertEquals("Team A – Team B", name)
        assertEquals("Live – HD", details)
    }

    @Test
    fun `two links differing only by a masked score read the same and get their source names`() {
        val streams = listOf(stream("Team A 1-0 Team B", "Source A"), stream("Team A 2-0 Team B", "Source B"))
        assertEquals(setOf(0, 1), sportsLiveSourceTaggedIndexes(streams, "Unknown"))
    }
}
