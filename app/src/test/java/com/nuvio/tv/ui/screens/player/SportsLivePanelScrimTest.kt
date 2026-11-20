package com.nuvio.tv.ui.screens.player

import androidx.compose.ui.unit.dp
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** TV QA BUG-H: the in-player panel's backing (P6 spec), and that the panel draws it under its own rows. */
class SportsLivePanelScrimTest {
    private val stops = panelScrimStops(SportsLiveTokens.Size.PanelWidth, 80.dp)

    @Test
    fun `the backing is near-opaque behind the rows and clear past the fade`() {
        assertEquals(0.dp, stops.first().at)
        assertEquals(0.95f, stops.first().alpha, 0f)
        // At the panel's right edge, still 90 % black: every row's text sits on it.
        val edge = stops.single { it.at == SportsLiveTokens.Size.PanelWidth }
        assertTrue(edge.alpha >= 0.9f)
        // Then it falls off to clear 80 dp past the panel.
        assertEquals(SportsLiveTokens.Size.PanelWidth + 80.dp, stops.last().at)
        assertEquals(0f, stops.last().alpha, 0f)
    }

    @Test
    fun `the backing's stops run left to right and only fade`() {
        stops.zipWithNext().forEach { (a, b) ->
            assertTrue(a.at < b.at)
            assertTrue(a.alpha >= b.alpha)
        }
        stops.forEach { assertTrue(it.alpha in 0f..1f) }
    }

    @Test
    fun `the panel draws its own backing, not a layer of its own beside it`() {
        val overlay = listOf(
            File("src/main/java/com/nuvio/tv/ui/screens/player/SportsLiveZapOverlay.kt"),
            File("app/src/main/java/com/nuvio/tv/ui/screens/player/SportsLiveZapOverlay.kt")
        ).first { it.exists() }.readText()
        assertTrue(overlay.contains(".panelScrim()"))
        assertFalse(overlay.contains("PANEL_SCRIM"))
        assertFalse(overlay.contains("zIndex(3.1f)"))
    }
}
