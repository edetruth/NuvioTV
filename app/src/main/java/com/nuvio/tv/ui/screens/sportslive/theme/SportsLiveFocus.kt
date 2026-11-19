package com.nuvio.tv.ui.screens.sportslive.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens.Colors
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens.Focus
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens.Size

/**
 * The one focus language of Sports & Live (#93, V2-Header-and-Focus §4):
 * - **Focused** = a 4 px white ring, a 1.03 lift and the fill 8 % lighter.
 * - **Selected** = an underline (tabs), a tinted fill (filter options) or a white left bar (a row while
 *   focus is in the stage). Never a white fill, never red.
 * - **Primary** button (Watch, Remind me) = filled white, dark text; focus adds the ring, off the fill.
 *   **Secondary** button (More…) = outlined.
 */
object SportsLiveFocus {

    /** The focus ring on [shape]; [gap] leaves room between a filled control and its ring. */
    fun ring(shape: Shape, gap: Dp = 0.dp): Border =
        Border(border = BorderStroke(Focus.RingWidth, Colors.FocusWhite), inset = gap, shape = shape)

    /** A resting outline on [shape]. */
    fun outline(shape: Shape, color: Color = Colors.Outline): Border =
        Border(border = BorderStroke(1.dp, color), shape = shape)

    /** The fill of a control with focus: [base] 8 % lighter; a transparent base takes the row colour. */
    fun focusedFill(base: Color): Color =
        if (base.alpha == 0f) SportsLiveTokens.focusFill(Colors.Background) else SportsLiveTokens.focusFill(base)

    /** The selected-state fill of a filter option: tinted, never white (#93). */
    val selectedFill: Color get() = Colors.SelectedTint
}

/** A button's role (#93): the one main action, or the outlined rest. */
enum class SportsLiveButtonRole { PRIMARY, SECONDARY }

/**
 * A Sports & Live button: [SportsLiveButtonRole.PRIMARY] (Watch, Remind me) filled white with dark text,
 * [SportsLiveButtonRole.SECONDARY] (More…, the rest) outlined. Focus adds the white ring and the lift; it
 * never turns a secondary button into a white slab.
 */
@Composable
fun SportsLiveButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    role: SportsLiveButtonRole = SportsLiveButtonRole.SECONDARY,
    onLongClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 15.dp, vertical = 0.dp),
    content: @Composable RowScope.() -> Unit
) {
    val shape = SportsLiveTokens.Radius.ButtonShape
    val primary = role == SportsLiveButtonRole.PRIMARY
    val fill = if (primary) Colors.PrimaryFill else Color.Transparent
    val text = if (primary) Colors.OnPrimary else Colors.TextPrimary
    Button(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.defaultMinSize(minHeight = Size.ButtonHeight),
        scale = ButtonDefaults.scale(focusedScale = Focus.LIFT),
        colors = ButtonDefaults.colors(
            containerColor = fill,
            contentColor = text,
            focusedContainerColor = if (primary) fill else SportsLiveFocus.focusedFill(fill),
            focusedContentColor = text,
            pressedContainerColor = if (primary) fill else SportsLiveFocus.focusedFill(fill),
            pressedContentColor = text
        ),
        border = ButtonDefaults.border(
            border = if (primary) Border.None else SportsLiveFocus.outline(shape, Colors.OutlineStrong),
            focusedBorder = SportsLiveFocus.ring(shape, gap = if (primary) Focus.PrimaryRingGap else 0.dp),
            pressedBorder = SportsLiveFocus.ring(shape, gap = if (primary) Focus.PrimaryRingGap else 0.dp)
        ),
        shape = ButtonDefaults.shape(shape),
        contentPadding = contentPadding
    ) {
        // The label and icon take the role's text colour in every state (#157): the primary's dark text on
        // white at rest too, never a grey that reads as disabled.
        CompositionLocalProvider(LocalContentColor provides text) { content() }
    }
}

/**
 * A header text tab (V2): the selected tab is white with a white underline, the rest 70 % white; focus
 * draws the ring round the tab and lifts it. Tabs select on focus (TV tab rows), so [onFocused] runs
 * when the tab takes focus; OK calls [onClick].
 */
@Composable
fun SportsLiveTextTab(
    label: String,
    selected: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = SportsLiveTokens.Radius.ButtonShape
    Surface(
        onClick = onClick,
        modifier = modifier
            .height(Size.HeaderHeight)
            .onFocusChanged { if (it.isFocused) onFocused() },
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = Focus.LIFT),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Colors.TextPrimary,
            focusedContainerColor = SportsLiveFocus.focusedFill(Color.Transparent),
            focusedContentColor = Colors.TextPrimary,
            pressedContainerColor = SportsLiveFocus.focusedFill(Color.Transparent),
            pressedContentColor = Colors.TextPrimary
        ),
        border = ClickableSurfaceDefaults.border(focusedBorder = SportsLiveFocus.ring(shape), pressedBorder = SportsLiveFocus.ring(shape))
    ) {
        Column(
            modifier = Modifier
                .width(IntrinsicSize.Max)
                .padding(horizontal = Size.TabPaddingHorizontal)
                .align(Alignment.Center),
            horizontalAlignment = Alignment.Start
        ) {
            Spacer(Modifier.height(Size.TabUnderline + Size.TabUnderlineGap))
            Text(
                text = label,
                style = SportsLiveTokens.style(if (selected) SportsLiveTokens.Type.TabSelected else SportsLiveTokens.Type.Tab),
                color = if (selected) Colors.TextPrimary else Colors.TextInactive,
                maxLines = 1,
                softWrap = false
            )
            Spacer(Modifier.height(Size.TabUnderlineGap))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Size.TabUnderline)
                    .background(if (selected) Colors.FocusWhite else Color.Transparent, RoundedCornerShape(1.dp))
            )
        }
    }
}

/**
 * A round header control (refresh) or a pill one (the source-error count): 56 px tall, outlined at rest,
 * the ring and lift when focused.
 */
@Composable
fun SportsLiveHeaderControl(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    outline: Color = Colors.Outline,
    round: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    val shape = SportsLiveTokens.Radius.PillShape
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.height(Size.ControlHeight).then(if (round) Modifier.width(Size.ControlHeight) else Modifier),
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = Focus.LIFT),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Colors.TextSecondary,
            focusedContainerColor = SportsLiveFocus.focusedFill(Color.Transparent),
            focusedContentColor = Colors.TextPrimary,
            pressedContainerColor = SportsLiveFocus.focusedFill(Color.Transparent),
            pressedContentColor = Colors.TextPrimary
        ),
        border = ClickableSurfaceDefaults.border(
            border = SportsLiveFocus.outline(shape, outline),
            focusedBorder = SportsLiveFocus.ring(shape),
            pressedBorder = SportsLiveFocus.ring(shape)
        )
    ) {
        Row(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = if (round) 0.dp else 9.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
    }
}

/** A small outlined badge ("HD", "FHD") beside a clean channel name. */
@Composable
fun SportsLiveBadge(text: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(SportsLiveTokens.Radius.Badge)
    Text(
        text = text,
        style = SportsLiveTokens.style(SportsLiveTokens.Type.Tag),
        color = Colors.TextSecondary,
        maxLines = 1,
        overflow = TextOverflow.Clip,
        modifier = modifier
            .border(1.dp, Colors.OutlineStrong, shape)
            .padding(horizontal = 4.dp, vertical = 1.dp)
    )
}
