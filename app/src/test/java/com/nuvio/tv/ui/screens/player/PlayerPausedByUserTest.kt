package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #148: only a player the user paused (and left paused) counts as paused on exit. */
class PlayerPausedByUserTest {

    @Test
    fun `a pause, or the buffering after one, is paused`() {
        assertTrue(playerPausedByUser(userPausedManually = true, playIntent = false))
    }

    @Test
    fun `playing, or buffering while playing, is not paused`() {
        assertFalse(playerPausedByUser(userPausedManually = false, playIntent = true))
    }

    @Test
    fun `a pause resumed by a media key is not paused`() {
        assertFalse(playerPausedByUser(userPausedManually = true, playIntent = true))
    }

    @Test
    fun `a player still loading with no play intent yet is not paused`() {
        assertFalse(playerPausedByUser(userPausedManually = false, playIntent = false))
    }
}
