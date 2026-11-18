package com.nuvio.tv.ui.screens.player

import androidx.media3.common.text.Cue
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerSubtitleUtilsMergeTest {

    private val dialogue = Cue.Builder().setText("Dialogue").build()
    private val bottomLeftSign = Cue.Builder().setText("Sign")
        .setPosition(0.08f).setPositionAnchor(Cue.ANCHOR_TYPE_START).build()

    @Test
    fun `a side cue does not pull the dialogue merged with it to the side`() {
        val merged = PlayerSubtitleUtils.mergeOverlappingCues(listOf(bottomLeftSign, dialogue)).single()

        assertEquals("Sign\nDialogue", merged.text.toString())
        assertEquals(Cue.DIMEN_UNSET, merged.position)
    }

    @Test
    fun `merged cues without a centered one are centered`() {
        val bottomRightSign = Cue.Builder().setText("Other sign")
            .setPosition(0.92f).setPositionAnchor(Cue.ANCHOR_TYPE_END).build()

        val merged = PlayerSubtitleUtils.mergeOverlappingCues(listOf(bottomLeftSign, bottomRightSign)).single()

        assertEquals(Cue.DIMEN_UNSET, merged.position)
        assertEquals(Cue.TYPE_UNSET, merged.positionAnchor)
    }

    @Test
    fun `cues sharing a position keep it`() {
        val secondLeft = Cue.Builder().setText("Second")
            .setPosition(0.08f).setPositionAnchor(Cue.ANCHOR_TYPE_START).build()

        val merged = PlayerSubtitleUtils.mergeOverlappingCues(listOf(bottomLeftSign, secondLeft)).single()

        assertEquals(0.08f, merged.position, 0f)
        assertEquals(Cue.ANCHOR_TYPE_START, merged.positionAnchor)
    }

    @Test
    fun `highlight clip - a caption repeated in three cues shows once`() {
        val line = "Black open, no."
        // Equal text in objects that never compare equal (as a decoder's styled text).
        val cues = List(3) { Cue.Builder().setText(StringBuilder(line)).build() }

        val merged = PlayerSubtitleUtils.mergeOverlappingCues(PlayerSubtitleUtils.dedupeRepeatedCaptions(cues)).single()

        assertEquals(line, merged.text.toString())
    }

    @Test
    fun `highlight clip - a roll-up caption resent on three rows keeps only its bottom row`() {
        val shown = PlayerSubtitleUtils.dedupeRepeatedCaptions(rollUp)

        assertEquals(1, shown.size)
        assertEquals(15f, shown.single().line, 0f)
    }

    @Test
    fun `highlight clip - different captions still all show`() {
        val first = Cue.Builder().setText("First").build()
        val second = Cue.Builder().setText("Second").build()

        assertEquals(listOf(first, second), PlayerSubtitleUtils.dedupeRepeatedCaptions(listOf(first, second)))
    }

    @Test
    fun `any other playback - merging leaves repeated captions as they come`() {
        // Movies, shows and sports streams never go through the dedupe: their cues are untouched.
        assertEquals(rollUp, PlayerSubtitleUtils.mergeOverlappingCues(rollUp))
    }

    private val rollUp = listOf(13f, 14f, 15f).map { row ->
        Cue.Builder().setText("Black open, no.").setLine(row, Cue.LINE_TYPE_NUMBER).build()
    }

    @Test
    fun `a side cue shown alone keeps its side`() {
        assertEquals(listOf(bottomLeftSign), PlayerSubtitleUtils.mergeOverlappingCues(listOf(bottomLeftSign)))
    }
}
