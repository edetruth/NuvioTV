package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveFocus
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens.Colors
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens.Settings

/**
 * One Settings row (#134 V2): 72 px, flat [Colors.Row] fill, the label centred on one line. On the right,
 * in order: a [value], a [tag] and a gold ★ ([followed]), then the On/Off switch ([switchOn] non-null) or a
 * › ([chevron]). Focus is the V2 ring and a 1.02 lift. A row that is not [enabled] keeps its focus, so the
 * help line can say why it is off (#89), and OK does nothing.
 */
@Composable
internal fun SportsLiveSettingRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    value: String? = null,
    switchOn: Boolean? = null,
    chevron: Boolean = false,
    tag: String? = null,
    followed: Boolean = false,
    enabled: Boolean = true,
    onFocused: () -> Unit = {}
) {
    val shape = SportsLiveTokens.Radius.ButtonShape
    val onText = stringResource(R.string.sports_live_switch_on)
    val offText = stringResource(R.string.sports_live_switch_off)
    Surface(
        onClick = { if (enabled) onClick() },
        modifier = modifier
            .fillMaxWidth()
            .height(SportsLiveTokens.Size.SettingsRow)
            .onFocusChanged { if (it.isFocused) onFocused() }
            .semantics {
                if (!enabled) disabled()
                if (switchOn != null) {
                    role = Role.Switch
                    stateDescription = if (switchOn) onText else offText
                }
            },
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = Settings.ROW_LIFT),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Colors.Row,
            contentColor = Colors.TextPrimary,
            focusedContainerColor = Colors.RowFocused,
            focusedContentColor = Colors.TextPrimary,
            pressedContainerColor = Colors.RowFocused,
            pressedContentColor = Colors.TextPrimary
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = SportsLiveFocus.ring(shape),
            pressedBorder = SportsLiveFocus.ring(shape)
        )
    ) {
        Row(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth()
                .padding(horizontal = Settings.RowPaddingHorizontal)
                .alpha(if (enabled) 1f else DISABLED_ALPHA),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Settings.SwitchGap)
        ) {
            Text(
                text = title,
                style = SportsLiveTokens.style(Settings.Label),
                color = Colors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (value != null) {
                Text(
                    text = value,
                    style = SportsLiveTokens.style(Settings.Value),
                    color = Colors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = VALUE_MAX_WIDTH)
                )
            }
            if (tag != null) {
                Text(
                    text = tag,
                    style = SportsLiveTokens.style(SportsLiveTokens.Type.Tag),
                    color = Colors.TextMuted,
                    maxLines = 1
                )
            }
            if (followed) {
                Text(text = "★", style = SportsLiveTokens.style(Settings.Value), color = Colors.FollowedGold)
            }
            if (switchOn != null) SportsLiveSwitch(on = switchOn, onText = onText, offText = offText)
            if (chevron) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = Colors.TextMuted,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

/** The switch (#134): the word On / Off, then a track that is blue when on, so the two states read apart. */
@Composable
private fun SportsLiveSwitch(on: Boolean, onText: String, offText: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Settings.SwitchGap)) {
        Text(
            text = if (on) onText else offText,
            style = SportsLiveTokens.style(Settings.Value).copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium),
            color = if (on) Colors.TextPrimary else Colors.TextMuted,
            maxLines = 1
        )
        Box(
            modifier = Modifier
                .width(Settings.SwitchTrackWidth)
                .height(Settings.SwitchTrackHeight)
                .background(if (on) Colors.SetBlue else Colors.OutlineStrong, CircleShape)
                .padding(horizontal = (Settings.SwitchTrackHeight - Settings.SwitchKnob) / 2),
            contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart
        ) {
            Box(
                modifier = Modifier
                    .size(Settings.SwitchKnob)
                    .background(if (on) Colors.FocusWhite else Colors.TextMuted, CircleShape)
            )
        }
    }
}

/** A section title: 24 px caps, muted; plain text, so the D-pad passes over it. */
@Composable
internal fun SportsLiveSettingsSectionTitle(title: String) {
    Text(
        text = title.uppercase(),
        style = SportsLiveTokens.style(SportsLiveTokens.Type.SectionHeader),
        color = Colors.TextMuted,
        maxLines = 1,
        modifier = Modifier.padding(start = 4.dp, top = Settings.SectionTop, bottom = 2.dp)
    )
}

/** A plain line in a page's list (an empty state): Meta size, secondary. */
@Composable
internal fun SportsLiveSettingsNote(text: String) {
    Text(
        text = text,
        style = SportsLiveTokens.style(SportsLiveTokens.Type.Meta),
        color = Colors.TextSecondary,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
    )
}

/**
 * The one help line (#134): the focused row's explanation, fixed at the bottom of the pane, so rows stay
 * one line and nothing re-flows when focus moves. Two lines at most, under a hairline that sets it apart from
 * the list scrolling above it (live.19 F19: it read as part of the last section).
 */
@Composable
internal fun SportsLiveHelpLine(text: String?, modifier: Modifier = Modifier) = Column(modifier = modifier.fillMaxWidth()) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .height(1.dp)
            .background(Colors.Divider)
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .height(Settings.HelpLineHeight)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (text.isNullOrEmpty()) return@Row
        Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = null,
            tint = Colors.TextMuted,
            modifier = Modifier.size(12.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = SportsLiveTokens.style(Settings.Help),
            color = Colors.TextSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private const val DISABLED_ALPHA = 0.45f
private val VALUE_MAX_WIDTH = 240.dp
