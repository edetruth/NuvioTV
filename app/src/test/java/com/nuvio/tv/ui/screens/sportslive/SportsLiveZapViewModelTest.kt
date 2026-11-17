package com.nuvio.tv.ui.screens.sportslive

import android.view.KeyEvent
import androidx.lifecycle.SavedStateHandle
import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.debrid.DirectDebridResolver
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.domain.model.SportsLiveTab
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.repository.SportsLiveRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SportsLiveZapViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Before
    fun setUp() {
        SportsLiveExtraSources.reset()
        SportsLiveZapSessions.clear()
    }

    @After
    fun tearDown() {
        SportsLiveExtraSources.reset()
        SportsLiveZapSessions.clear()
    }

    @Test
    fun `a zap to a merged game drops this player's extras and registers the target's`() = runTest(mainDispatcherRule.dispatcher) {
        SportsLiveExtraSources.register(PLAYING.id, PLAYING)
        SportsLiveZapSessions.start(
            SportsLiveZapSession.of(listOf(PLAYING, NEXT), PLAYING.streamKey, SportsLiveTab.LIVE_NOW, null, returnToHomeOnBack = false)!!
        )
        val resolver = mockk<SportsLiveZapResolver>()
        coEvery { resolver.resolve(any()) } returns STREAM
        val debrid = mockk<DirectDebridResolver>()
        coEvery { debrid.shouldResolveToPlayableStream(any()) } returns false
        val profiles = mockk<ProfileManager>()
        every { profiles.activeProfileId } returns MutableStateFlow(1)
        val repo = mockk<SportsLiveRepository>(relaxed = true)
        every { repo.endedKeys } returns MutableStateFlow(emptySet())
        val viewModel = SportsLiveZapViewModel(SavedStateHandle(mapOf("contentId" to PLAYING.id)), resolver, debrid, profiles, repo, mockk(relaxed = true))
        val events = mutableListOf<SportsLiveZapEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.collect { events += it } }

        assertTrue(viewModel.onKey(KeyEvent.KEYCODE_DPAD_DOWN, partyActive = false))
        advanceUntilIdle()

        assertEquals(NEXT, (events.single() as SportsLiveZapEvent.Navigate).target.item)
        assertFalse(SportsLiveExtraSources.isRegistered(PLAYING.id))
        assertTrue(SportsLiveExtraSources.isRegistered(NEXT.id))
    }

    private companion object {
        fun item(id: String, addon: String) = SportsLiveItem(
            meta = MetaPreview(
                id = id,
                type = ContentType.UNKNOWN,
                rawType = "sport",
                name = "$id vs Rivals",
                poster = null,
                posterShape = PosterShape.LANDSCAPE,
                background = null,
                logo = null,
                description = null,
                releaseInfo = "LIVE",
                imdbRating = null,
                genres = emptyList()
            ),
            addonId = addon,
            addonName = addon,
            addonBaseUrl = "https://$addon.example",
            catalogKey = "${addon}_sport_live",
            catalogName = "Live",
            kind = SportsLiveKind.EVENTS,
            isLive = true,
            isChannel = false,
            startMs = null,
            sport = null
        )

        val PLAYING = item("playing", "one").copy(alternates = listOf(item("playing-alt", "two")))
        val NEXT = item("next", "one").copy(alternates = listOf(item("next-alt", "two")))

        val STREAM = Stream(
            name = "One 720p",
            title = null,
            description = null,
            url = "https://cdn.example/one.m3u8",
            ytId = null,
            infoHash = null,
            fileIdx = null,
            externalUrl = null,
            behaviorHints = null,
            addonName = "one",
            addonLogo = null
        )
    }
}
