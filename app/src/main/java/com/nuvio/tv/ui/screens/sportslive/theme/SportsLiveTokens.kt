package com.nuvio.tv.ui.screens.sportslive.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme

/**
 * The Sports & Live design tokens (V2, #96): one place for the section's colours, TV type scale, sizes
 * and focus values, from the approved boards (`docs/superpowers/designs/2026-11-16-sports-live-views`,
 * V2-Header-and-Focus).
 *
 * The boards are drawn in px on the 1920×1080 frame. This app runs a 1080p TV at density 320, so
 * **1 dp = 2 px** (and 1 sp = 2 px at font scale 1): every value below is the board's px value halved.
 */
object SportsLiveTokens {

    /** Board px → dp at 1080p (density 320: 2 px per dp). */
    fun pxToDp(px: Int): Dp = (px / PX_PER_DP).dp

    /** Board px → sp at 1080p (font scale 1). */
    fun pxToSp(px: Int): TextUnit = (px / PX_PER_DP).sp

    const val PX_PER_DP = 2f

    /** Semantic colours: the section's dark broadcast palette, the same under every app theme (#94). */
    object Colors {
        val Background = Color(0xFF0A0D12)
        /** Panels and dialogs. */
        val Surface = Color(0xFF121821)
        val SurfaceRaised = Color(0xFF1F2A36)
        /** A list row at rest. */
        val Row = Color(0xFF141A23)
        /** A list row with focus: [Row] 8 % lighter. */
        val RowFocused = focusFill(Row)
        /** A live game's row tint. */
        val LiveRow = Color(0xFF1C1519)
        /** Outlines of resting controls (filter, icon buttons). */
        val Outline = Color(0xFF2A3442)
        /** Outline of a secondary button. */
        val OutlineStrong = Color(0xFF3A4554)
        /** Hairline dividers. */
        val Divider = Color(0xFF1E2632)

        val TextPrimary = Color(0xFFF2F4F7)
        val TextSecondary = Color(0xFFC7CED8)
        val TextMuted = Color(0xFF9AA4B2)
        /** Tabs not selected, filter options not selected: 70 % white. */
        val TextInactive = TextPrimary.copy(alpha = 0.7f)

        /** Live games only (the dot, the clock, the progress bar): never focus, never a pill fill. */
        val LiveRed = Color(0xFFFF4D57)
        val LiveText = Color(0xFFFFB3B8)
        /** Followed teams: the ★, the left bar, "★ My teams". */
        val FollowedGold = Color(0xFFFFC107)
        /** Replay pill and "stream listed". */
        val ReplayGreen = Color(0xFF7EE0A1)
        val ReplayGreenText = Color(0xFF0B1F12)
        /** Why tags and reminders: blue, so they never read as live (red) or followed (gold). */
        val WhyTagBlue = Color(0xFF6AA8FF)
        val WhyTagText = Color(0xFFCFE1FF)
        val WhyTagBackground = Color(0xFF13233A)
        val WhyTagBorder = Color(0xFF2B4A75)
        /** A switch that is on (#134 Settings): blue already means "you set this", like the reminder bell. */
        val SetBlue = WhyTagBlue
        /** Warnings (sources that did not respond). */
        val WarningAmber = Color(0xFFFFB020)
        val WarningAmberOutline = WarningAmber.copy(alpha = 0.45f)
        /** The focus ring, and the selected tab's underline. */
        val FocusWhite = Color(0xFFFFFFFF)
        /** A selected filter option: a tinted fill, never white. */
        val SelectedTint = Color(0xFF263142)
        /** The primary button's fill and its text. */
        val PrimaryFill = TextPrimary
        val OnPrimary = Background
        /** Team stats bars (#134): the side with the better value, and the other side. */
        val StatBetter = Color(0xFF9AC2FF)
        val StatOther = Color(0xFF4B5A6E)
    }

    /** The TV type scale (board px → sp). Merge with the app font: [SportsLiveTokens.style]. */
    object Type {
        /** Tags (league, period, badges): 22 px. */
        val Tag = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold)
        /** Metadata ("Today · 3:30 PM · in 2 h 21 m"): 26 px. */
        val Meta = TextStyle(fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
        /** Row names: 30 px. */
        val RowName = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Normal)
        /** Row scores: 40 px bold, tabular. */
        val RowScore = TextStyle(fontSize = 20.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum")
        /** The stage's scoreline: 64 px bold, tabular. */
        val StageScore = TextStyle(fontSize = 32.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum")
        /** Group / section headers: 24 px caps, 0.08 em apart. */
        val SectionHeader = TextStyle(fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.08.em)
        /** Header tabs: 34 px (selected semibold, others medium). */
        val Tab = TextStyle(fontSize = 17.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
        val TabSelected = Tab.copy(fontWeight = FontWeight.SemiBold)
        /** Header controls (filter options, source count): 24 px. */
        val Control = TextStyle(fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium)
        val ControlSelected = Control.copy(fontWeight = FontWeight.SemiBold)
        /** Buttons (Watch, More…): 28 px semibold. */
        val Button = TextStyle(fontSize = 14.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold)
        /** Dual screen's score strip under a tile (#124): 34 px semibold, tabular. */
        val DualStrip = TextStyle(fontSize = 17.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum")
        /** The in-player panel's one-line rows (#140), the row scale in compact form: 26 px names and status. */
        val PanelLine = TextStyle(fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
        /** Their scores: 26 px tabular; the leader's set in real bold. */
        val PanelScore = TextStyle(fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum")
        /** The panel's title: 32 px semibold. */
        val PanelTitle = TextStyle(fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
        /** The Game page's hero score (#134): 96 px bold, tabular. */
        val HeroScore = TextStyle(fontSize = 48.sp, lineHeight = 52.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum")
        /** A pushed page's title (a team or player name, #134): 52 px bold. */
        val PageTitle = TextStyle(fontSize = 26.sp, lineHeight = 31.sp, fontWeight = FontWeight.Bold)
    }

    /** Frame, rows, columns and controls (board px → dp). */
    object Size {
        /** Safe area inside the section's content box (the board: top 54, bottom 1026, right 1824 px). */
        val SafeTop = 27.dp
        val SafeBottom = 27.dp
        val SafeEnd = 48.dp
        /**
         * Where the header and list start, from the content box's left edge: the board's x = 256 px. The
         * section's content box starts at x = 108 px beside the collapsed rail (measured on the 1080p
         * emulator), so (256 − 108) / 2.
         */
        val SafeStart = 74.dp

        /** The one-line header: 80 px. */
        val HeaderHeight = 40.dp
        /** Header gap between text tabs (32 px), and the tab's own focus padding. */
        val TabGap = 16.dp
        val TabPaddingHorizontal = 6.dp
        /** The selected tab's underline: 4 px, 10 px under the text. */
        val TabUnderline = 2.dp
        val TabUnderlineGap = 5.dp
        /** Header controls: 56 px tall (filter, source icon, refresh), 14 px apart. */
        val ControlHeight = 28.dp
        val ControlGap = 7.dp
        val ControlIcon = 14.dp
        /** Header to list: the list starts at y = 150 px. */
        val HeaderBottomGap = 8.dp

        /** Row heights: live 112, starting soon / upcoming 96, channel 72, just ended 80 px. */
        val RowLive = 56.dp
        val RowSoon = 48.dp
        val RowChannel = 36.dp
        val RowJustEnded = 40.dp
        val RowGap = 6.dp
        /**
         * Row columns: league 160, names 376, scores 96, status 150 px. One tag column on every row type (#134):
         * a ten-letter sport word ("BASKETBALL") fits at the Tag size, and names start at the same x on every row.
         */
        val ColumnLeague = 80.dp
        val ColumnNames = 188.dp
        val ColumnScores = 48.dp
        val ColumnStatus = 75.dp
        /** The list (800 px) and the stage (720 px). */
        val ListWidth = 400.dp
        val StageWidth = 360.dp
        val StageGap = 24.dp

        /** The in-player panel (#140): 560 px wide (29 % of 1080p), one-line rows 44 px tall, 4 px apart. */
        val PanelWidth = 280.dp
        val PanelLine = 22.dp
        val PanelLineGap = 2.dp
        /** A matched panel row's team logos or flags: 36 px, beside the abbreviations. */
        val PanelLogo = 18.dp

        /** Buttons: 72 px tall; the primary at least 200 px wide. */
        val ButtonHeight = 36.dp
        val ButtonMinWidth = 100.dp
        val ButtonGap = 11.dp

        /** A Settings row (#134): 72 px, one line, the label centred. */
        val SettingsRow = 36.dp

        /**
         * Dual screen (V2 board ①): split tiles 912 px wide, 32 px apart, so 32 px side margins; the score strip
         * under each tile (its row, with the gap above it).
         */
        val DualSideMargin = 16.dp
        val DualGap = 16.dp
        val DualCaption = 32.dp
        /** Split: the room kept under the strips for the game info overlay (#139), so the tiles sit a little high. */
        val DualInfoRoom = 84.dp
        /** Big + small (#123): side margins; the large tile takes 3/4 of the row, the small one 1/4. */
        val DualBigMargin = 24.dp
    }

    /**
     * Followed teams' alert cards (#136): one slim, slightly translucent card at the top right, the same
     * spot over the full player and the mini player (clear of the section's header, and of broadcast score
     * bugs, which sit top left or along the bottom); in dual screen, the top right of a pane.
     */
    object Alert {
        /** Bottom left of the safe area on every screen (the board: x 256, bottom 1026 px), clear of the side rail (F8). */
        val Start = 128.dp
        val Bottom = Size.SafeBottom
        /** Over full-screen video: the player's corner, lined up with its starting card. */
        val StartOverVideo = Player.CornerStart
        val BottomOverVideo = Player.CornerBottom
        /** Inside a dual screen pane, from its top right corner. */
        val PaneInset = 12.dp
        val Width = 340.dp
        val Padding = 12.dp
        val Gap = 4.dp
        val Logo = 22.dp
        val Radius = 12.dp
        /** The card's fill: the panel colour, slightly see-through. */
        const val FILL_ALPHA = 0.86f
        val Border = Colors.Divider
        /** The hint line ("OK Watch · ▼ Peek"). */
        val Hint = Colors.TextMuted
        /** A grouped card's dots: the one showing, the others. */
        val DotOn = Colors.TextPrimary
        val DotOff = Colors.TextMuted.copy(alpha = 0.4f)
        val Dot = 5.dp
        /** It slides in from the right this fast. */
        const val SLIDE_MS = 220

        /** The peek's label over the peeked game. */
        val PeekLabelFill = Color.Black.copy(alpha = 0.6f)
    }

    /** Corner radii (board px → dp). */
    object Radius {
        /** Rows: 20 px. */
        val Row = 10.dp
        /** Buttons and menu entries: 14 px. */
        val Button = 7.dp
        /** Pills (filter, source icon): fully round. */
        val Pill = 14.dp
        val Badge = 3.dp
        val RowShape = RoundedCornerShape(Row)
        val ButtonShape = RoundedCornerShape(Button)
        val PillShape = RoundedCornerShape(50)
    }

    /** Settings → Sports & Live (#134 phase 6): flat rows, the one help line and the On/Off switch. */
    object Settings {
        /** Rows lift less than list rows (1.02): they span the pane, so 1.03 would touch its edge. */
        const val ROW_LIFT = 1.02f
        val RowGap = 4.dp
        val RowPaddingHorizontal = 14.dp
        /** The switch: a 40 × 22 px track with a 16 px knob, after its "On" / "Off". */
        val SwitchTrackWidth = 20.dp
        val SwitchTrackHeight = 11.dp
        val SwitchKnob = 8.dp
        val SwitchGap = 8.dp
        /** Room above a section title, and the fixed help line: two 22 px lines. */
        val SectionTop = 10.dp
        val HelpLineHeight = 34.dp
        /** Row labels: 30 px; values: 26 px. */
        val Label = TextStyle(fontSize = 15.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
        val Value = TextStyle(fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
        /** The help line: 22 px. */
        val Help = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium)
    }

    /** The one focus language (#93). */
    object Focus {
        /** Focused: a 4 px white ring. */
        val RingWidth = 2.dp
        /** The primary button's ring sits 4 px off its white fill, so the fill keeps its role. */
        val PrimaryRingGap = 2.dp
        /** Focused: a 1.03 lift. */
        const val LIFT = 1.03f
        /** Focused: the fill 8 % lighter. */
        const val FILL_LIGHTEN = 0.08f
    }

    /**
     * The one toast card (#134, `SportsLiveToast`): 640 px wide on the raised surface, an icon column whose colour
     * carries the meaning, a 28 px title over a 26 px line, each one line with an ellipsis. It sits top right, in
     * the same spot as the alert cards (#136), on every screen: inside the stage's top right on browse screens.
     */
    /** The full player's Sports & Live corner (#138, F8): its starting, waiting and error cards, the toast above them. */
    object Player {
        val CornerStart = 48.dp
        val CornerBottom = 48.dp
    }

    object Toast {
        /** The alert cards' width (#136), so a toast stacked under one lines up with it (the board's 640 px + 40). */
        val Width = Alert.Width
        /** Bottom left of the safe area on every screen (the board: x 256, bottom 1026 px), clear of the side rail (F8). */
        val Start = 128.dp
        val Bottom = Size.SafeBottom
        /** Over full-screen video: the player's corner, lined up with its starting card. */
        val StartOverVideo = Player.CornerStart
        val BottomOverVideo = Player.CornerBottom
        val PaddingHorizontal = 12.dp
        val PaddingVertical = 9.dp
        val IconColumn = 20.dp
        val IconGap = 10.dp
        val Radius = 10.dp
        /** 28 px semibold title, 26 px line. */
        val Title = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold)
        val Line = TextStyle(fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal)
        /** "FT" in the icon column. */
        val FinalMark = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold)
        /** It fades and slides in this fast. */
        const val ENTER_MS = 200
    }

    /** More… as a sheet over the stage (#134): 720 px wide, 72 px entries, its title and one status line. */
    object Sheet {
        val Width = 360.dp
        val Top = Size.SafeTop + Size.HeaderHeight + Size.HeaderBottomGap
        val End = Size.SafeEnd
        val Bottom = Size.SafeBottom
        val Padding = 16.dp
        val Entry = 36.dp
        val Radius = 12.dp
        /** 36 px semibold title. */
        val Title = TextStyle(fontSize = 18.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
    }

    /** One empty / error card (#134) and the skeleton rows that stand in while a list loads. */
    object State {
        val Padding = 16.dp
        val Gap = 6.dp
        /** 32 px title. */
        val Title = TextStyle(fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
        val Skeleton = Color(0xFF161D27)
        val SkeletonRows = 5
    }

    /** [base] with focus: each sRGB channel 8 % of the way to white (#93); alpha kept. */
    fun focusFill(base: Color): Color {
        fun up(channel: Float) = channel + (1f - channel) * Focus.FILL_LIGHTEN
        return Color(red = up(base.red), green = up(base.green), blue = up(base.blue), alpha = base.alpha)
    }

    /** [token] in the app's font (the user's font choice), with the token's size, weight and spacing. */
    @Composable
    @ReadOnlyComposable
    fun style(token: TextStyle): TextStyle = MaterialTheme.typography.bodyMedium.merge(token)
}
