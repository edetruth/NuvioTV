package com.nuvio.tv.ui.screens.sportslive.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens.Colors
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens.Size
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens.Type
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsLiveTokensTest {

    @Test
    fun `board px map to dp and sp at two px per dp`() {
        assertEquals(40.dp, SportsLiveTokens.pxToDp(80))
        assertEquals(11.sp, SportsLiveTokens.pxToSp(22))
    }

    @Test
    fun `the type scale is the board's in sp`() {
        assertEquals(SportsLiveTokens.pxToSp(24), Type.Tag.fontSize) // #95: decorative caps 11 -> 12 sp
        assertEquals(SportsLiveTokens.pxToSp(26), Type.Meta.fontSize)
        assertEquals(SportsLiveTokens.pxToSp(30), Type.RowName.fontSize)
        assertEquals(SportsLiveTokens.pxToSp(40), Type.RowScore.fontSize)
        assertEquals(SportsLiveTokens.pxToSp(64), Type.StageScore.fontSize)
        assertEquals(SportsLiveTokens.pxToSp(24), Type.SectionHeader.fontSize)
        assertEquals(SportsLiveTokens.pxToSp(34), Type.Tab.fontSize)
        assertEquals("tnum", Type.RowScore.fontFeatureSettings)
        assertEquals("tnum", Type.StageScore.fontFeatureSettings)
    }

    @Test
    fun `rows, columns and frame are the board's in dp`() {
        assertEquals(listOf(112, 96, 72, 80).map(SportsLiveTokens::pxToDp), listOf(Size.RowLive, Size.RowSoon, Size.RowChannel, Size.RowJustEnded))
        assertEquals(listOf(160, 376, 96, 176).map(SportsLiveTokens::pxToDp) /* status column 88 dp (live.24 design audit: 26 px floor) */, listOf(Size.ColumnLeague, Size.ColumnNames, Size.ColumnScores, Size.ColumnStatus))
        assertEquals(SportsLiveTokens.pxToDp(720), Size.StageWidth)
        assertEquals(SportsLiveTokens.pxToDp(80), Size.HeaderHeight)
        assertEquals(SportsLiveTokens.pxToDp(54), Size.SafeTop)
    }

    @Test
    fun `the toast card and the More sheet keep their sizes`() {
        // The toast lines up with the alert cards it stacks under (#136).
        assertEquals(SportsLiveTokens.Alert.Width, SportsLiveTokens.Toast.Width)
        assertEquals(SportsLiveTokens.pxToSp(28), SportsLiveTokens.Toast.Title.fontSize)
        assertEquals(SportsLiveTokens.pxToSp(26), SportsLiveTokens.Toast.Line.fontSize)
        assertEquals(SportsLiveTokens.pxToDp(720), SportsLiveTokens.Sheet.Width)
        assertEquals(SportsLiveTokens.pxToDp(72), SportsLiveTokens.Sheet.Entry)
    }

    @Test
    fun `focus is a 4 px ring and fills 8 percent lighter`() {
        assertEquals(SportsLiveTokens.pxToDp(4), SportsLiveTokens.Focus.RingWidth)
        assertEquals(1.03f, SportsLiveTokens.Focus.LIFT)
        val lighter = SportsLiveTokens.focusFill(Colors.Row)
        assertTrue(lighter.red > Colors.Row.red && lighter.green > Colors.Row.green && lighter.blue > Colors.Row.blue)
        assertEquals(Colors.Row.red + (1f - Colors.Row.red) * 0.08f, lighter.red, 0.01f)
        assertEquals(lighter, Colors.RowFocused)
    }

    @Test
    fun `selected is never a white fill and live red is never the focus colour`() {
        assertNotEquals(Color.White, Colors.SelectedTint)
        assertNotEquals(Colors.FocusWhite, Colors.SelectedTint)
        assertNotEquals(Colors.LiveRed, Colors.FocusWhite)
        assertNotEquals(Colors.FollowedGold, Colors.WarningAmber)
        assertNotEquals(Colors.WhyTagBlue, Colors.LiveRed)
    }

    @Test
    fun `inactive tabs are 70 percent white`() {
        assertEquals(0.7f, Colors.TextInactive.alpha, 0.005f)
    }
}
