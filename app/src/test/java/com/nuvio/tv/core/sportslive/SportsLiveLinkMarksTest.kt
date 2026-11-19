package com.nuvio.tv.core.sportslive

import com.nuvio.tv.domain.model.Stream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsLiveLinkMarksTest {

    private var now = 10_000_000L
    private val marks = SportsLiveLinkMarks(nowMs = { now })

    private val a = link("a")
    private val b = link("b")
    private val c = link("c")
    private val d = link("d")

    @After
    fun tearDown() {
        SportsLiveStreamLicences.clear()
    }

    private fun failedReason(url: String?, game: String = GAME): SportsLiveLinkFailure? =
        (marks.markOf(game, url) as? SportsLiveLinkMark.Failed)?.reason

    // Grouping

    @Test
    fun `failed links move to a trailing didnt work group, the rest keep their order`() {
        marks.failed(GAME, a.url, SportsLiveStreamHealth.Problem.NO_START)
        marks.failed(GAME, c.url, SportsLiveStreamHealth.Problem.FROZEN)
        marks.played(GAME, d.url)

        val groups = SportsLiveLinkGroups.of(listOf(a, b, c, d), { marks.markOf(GAME, it.url) }, hideProtected = true)

        assertEquals(listOf(b, d, a, c), groups.items)
        assertEquals(2, groups.didntWorkFrom)
    }

    @Test
    fun `with nothing failed there is no didnt work group and the order is kept`() {
        marks.played(GAME, b.url)
        val groups = SportsLiveLinkGroups.of(listOf(a, b, c), { marks.markOf(GAME, it.url) }, hideProtected = true)
        assertEquals(listOf(a, b, c), groups.items)
        assertEquals(-1, groups.didntWorkFrom)
    }

    @Test
    fun `notices stay after the didnt work group`() {
        marks.failed(GAME, a.url)
        val notice = link("notice")
        val groups = SportsLiveLinkGroups.of(
            listOf(a, notice, b),
            { marks.markOf(GAME, it.url) },
            hideProtected = true,
            isNotice = { it === notice }
        )
        assertEquals(listOf(b, a, notice), groups.items)
        assertEquals(1, groups.didntWorkFrom)
    }

    @Test
    fun `a failure for another game does not mark the link here`() {
        marks.failed("other", a.url)
        assertNull(marks.markOf(GAME, a.url))
        assertEquals(listOf(a, b), SportsLiveLinkGroups.of(listOf(a, b), { marks.markOf(GAME, it.url) }, true).items)
    }

    // Reason text

    @Test
    fun `the reason says whether the link never started, stopped, or is dead`() {
        marks.failed(GAME, a.url, SportsLiveStreamHealth.Problem.NO_START)
        marks.failed(GAME, b.url, SportsLiveStreamHealth.Problem.BUFFERING_TOO_LONG)
        marks.failed(GAME, c.url, SportsLiveStreamHealth.Problem.DEAD_LINK)

        assertEquals(SportsLiveLinkFailure.DIDNT_START, failedReason(a.url))
        assertEquals(SportsLiveLinkFailure.STOPPED, failedReason(b.url))
        assertEquals(SportsLiveLinkFailure.DEAD, failedReason(c.url))
    }

    @Test
    fun `an error with no problem reads didnt start unless the link played before`() {
        marks.failed(GAME, a.url)
        marks.played(GAME, b.url)
        marks.failed(GAME, b.url)

        assertEquals(SportsLiveLinkFailure.DIDNT_START, failedReason(a.url))
        assertEquals(SportsLiveLinkFailure.STOPPED, failedReason(b.url))
    }

    @Test
    fun `a classified cause names the reason`() {
        marks.noteCause(a.url, SportsLiveLinkCause.HEADERS)
        marks.failed(GAME, a.url, SportsLiveStreamHealth.Problem.NO_START)
        marks.noteCause(b.url, SportsLiveLinkCause.CODEC)
        marks.failed(GAME, b.url)
        marks.noteCause(c.url, SportsLiveLinkCause.EXPIRED_OR_GEO)
        marks.failed(GAME, c.url)
        marks.noteCause(d.url, SportsLiveLinkCause.OTHER)
        marks.failed(GAME, d.url, SportsLiveStreamHealth.Problem.FROZEN)

        assertEquals(SportsLiveLinkFailure.REFUSED, failedReason(a.url))
        assertEquals(SportsLiveLinkFailure.UNSUPPORTED, failedReason(b.url))
        assertEquals(SportsLiveLinkFailure.EXPIRED, failedReason(c.url))
        assertEquals(SportsLiveLinkFailure.STOPPED, failedReason(d.url))
    }

    @Test
    fun `an old cause no longer names the reason`() {
        marks.noteCause(a.url, SportsLiveLinkCause.HEADERS)
        now += SportsLiveLinkMarks.CAUSE_FRESH_MS
        marks.failed(GAME, a.url, SportsLiveStreamHealth.Problem.NO_START)
        assertEquals(SportsLiveLinkFailure.DIDNT_START, failedReason(a.url))
    }

    @Test
    fun `the age counts whole minutes, and dead or protected links carry none`() {
        val mark = SportsLiveLinkMark.Failed(SportsLiveLinkFailure.DIDNT_START, atMs = now)
        assertEquals(0, mark.minutesAgo(now + 59_999))
        assertEquals(3, mark.minutesAgo(now + 3 * 60_000 + 5_000))
        assertEquals(0, mark.minutesAgo(now - 1_000))
        assertTrue(SportsLiveLinkFailure.DIDNT_START.showsAge)
        assertTrue(SportsLiveLinkFailure.STOPPED.showsAge)
        assertFalse(SportsLiveLinkFailure.DEAD.showsAge)
        assertFalse(SportsLiveLinkFailure.PROTECTED.showsAge)
    }

    // Clearing

    @Test
    fun `the mark clears when the link plays, and it reads played`() {
        marks.failed(GAME, a.url, SportsLiveStreamHealth.Problem.NO_START)
        marks.played(GAME, a.url)

        assertEquals(SportsLiveLinkMark.Played, marks.markOf(GAME, a.url))
        assertEquals(listOf(a, b), SportsLiveLinkGroups.of(listOf(a, b), { marks.markOf(GAME, it.url) }, true).items)
    }

    @Test
    fun `marks lapse after about half an hour`() {
        marks.failed(GAME, a.url)
        marks.played(GAME, b.url)
        now += SportsLiveLinkMarks.MARK_FOR_MS - 1
        assertEquals(SportsLiveLinkFailure.DIDNT_START, failedReason(a.url))
        assertEquals(SportsLiveLinkMark.Played, marks.markOf(GAME, b.url))
        now += 1
        assertNull(marks.markOf(GAME, a.url))
        assertNull(marks.markOf(GAME, b.url))
    }

    @Test
    fun `a link is the same with or without user info`() {
        marks.failed(GAME, "https://user:pass@example.invalid/a.m3u8")
        assertEquals(SportsLiveLinkFailure.DIDNT_START, failedReason("https://example.invalid/a.m3u8"))
    }

    @Test
    fun `clear forgets everything and moves the revision on`() {
        marks.failed(GAME, a.url)
        marks.noteCause(b.url, SportsLiveLinkCause.DRM)
        val before = marks.revision.value
        marks.clear()
        assertNull(marks.markOf(GAME, a.url))
        assertFalse(marks.isProtected(b.url))
        assertTrue(marks.revision.value > before)
    }

    // Working links tell the marks

    @Test
    fun `working links pass failures and first frames on to the marks`() {
        val links = SportsLiveWorkingLinks(nowMs = { now }, marks = marks)
        links.failed(GAME, a.url, SportsLiveStreamHealth.Problem.NO_START)
        assertEquals(SportsLiveLinkFailure.DIDNT_START, failedReason(a.url))

        links.startedPlaying(GAME, a)
        assertEquals(SportsLiveLinkMark.Played, marks.markOf(GAME, a.url))
    }

    // Protected links (#166)

    @Test
    fun `a DRM link reads protected for every game and is never counted playable`() {
        marks.noteCause(a.url, SportsLiveLinkCause.DRM)

        assertTrue(marks.isProtected(a.url))
        assertEquals(SportsLiveLinkFailure.PROTECTED, failedReason(a.url))
        assertEquals(SportsLiveLinkFailure.PROTECTED, failedReason(a.url, game = "other"))
        assertFalse(marks.isProtected(b.url))
    }

    @Test
    fun `a protected link stays protected after it is marked played elsewhere`() {
        marks.noteCause(a.url, SportsLiveLinkCause.DRM)
        marks.failed(GAME, a.url)
        assertEquals(SportsLiveLinkFailure.PROTECTED, failedReason(a.url))
    }

    @Test
    fun `a DRM link whose addon names a licence server is not protected`() {
        SportsLiveStreamLicences.note(a.url, SportsLiveStreamLicence(SportsLiveStreamLicence.Scheme.WIDEVINE, "https://example.invalid/licence"))
        marks.noteCause(a.url, SportsLiveLinkCause.DRM)
        assertFalse(marks.isProtected(a.url))
    }

    @Test
    fun `hiding leaves protected links out unless kept, showing puts them in the bottom group`() {
        marks.noteCause(b.url, SportsLiveLinkCause.DRM)
        marks.failed(GAME, c.url)
        val markOf = { s: Stream -> marks.markOf(GAME, s.url) }

        val hidden = SportsLiveLinkGroups.of(listOf(a, b, c), markOf, hideProtected = true)
        assertEquals(listOf(a, c), hidden.items)
        assertEquals(1, hidden.didntWorkFrom)

        val shown = SportsLiveLinkGroups.of(listOf(a, b, c), markOf, hideProtected = false)
        assertEquals(listOf(a, b, c), shown.items)
        assertEquals(1, shown.didntWorkFrom)

        val kept = SportsLiveLinkGroups.of(listOf(a, b, c), markOf, hideProtected = true, keep = { it === b })
        assertEquals(listOf(a, b, c), kept.items)
    }

    @Test
    fun `the hide setting is on by default`() {
        assertTrue(SportsLiveLinkMarks().hideUnplayable.value)
    }

    @Test
    fun `recovery never picks a protected link`() {
        marks.noteCause(b.url, SportsLiveLinkCause.DRM)
        val eligible = SportsLiveRecoveryCandidates.eligible(
            GAME,
            listOf(a, b, c),
            SportsLiveWorkingLinks(nowMs = { now }),
            coolingAddonNames = emptySet(),
            isProtected = { marks.isProtected(it.url) }
        )
        assertEquals(listOf(a, c), eligible)
    }

    private companion object {
        const val GAME = "game"

        fun link(name: String) = Stream(
            name = name,
            title = null,
            description = null,
            url = "https://example.invalid/$name.m3u8",
            ytId = null,
            infoHash = null,
            fileIdx = null,
            externalUrl = null,
            behaviorHints = null,
            addonName = "addon",
            addonLogo = null
        )
    }
}
