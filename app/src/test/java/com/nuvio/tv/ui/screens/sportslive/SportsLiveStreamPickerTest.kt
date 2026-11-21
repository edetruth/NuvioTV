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
}
