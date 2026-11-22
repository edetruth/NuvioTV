package com.nuvio.tv.ui.screens.sportslive

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.sportslive.scores.EspnLeague
import com.nuvio.tv.core.sportslive.scores.TeamDirectory
import com.nuvio.tv.domain.model.FollowedTeam
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveFocus
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens.Colors

/**
 * Live now's "N new" line (live.25 M, report #22): the list's first line while games wait (TV QA Q5: nothing moves while
 * the list is browsed), where they will appear, instead of a pill on Refresh that squeezed the header's "My sports" and
 * did not say what it did. It says what OK does, a followed team's game first: "Mavericks game started · 13 new — OK to
 * show", else "13 new games — OK to show". Blue, as the section's other info marks, so it never reads as live (red) or
 * followed (gold). A "new posts" line at the top of the list is the pattern feed apps use for content that waited.
 */
@Composable
internal fun SportsLiveNewRowsLine(count: Int, followedTeam: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val text = if (followedTeam != null) {
        stringResource(R.string.sports_live_new_rows_line_followed, followedTeam, count)
    } else {
        pluralStringResource(R.plurals.sports_live_new_rows_line, count, count)
    }
    val shape = SportsLiveTokens.Radius.RowShape
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = SportsLiveTokens.Focus.LIFT),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Colors.WhyTagBackground,
            contentColor = Colors.WhyTagText,
            focusedContainerColor = SportsLiveFocus.focusedFill(Colors.WhyTagBackground),
            focusedContentColor = Colors.TextPrimary,
            pressedContainerColor = SportsLiveFocus.focusedFill(Colors.WhyTagBackground),
            pressedContentColor = Colors.TextPrimary
        ),
        border = ClickableSurfaceDefaults.border(focusedBorder = SportsLiveFocus.ring(shape), pressedBorder = SportsLiveFocus.ring(shape))
    ) {
        Text(
            text = text,
            style = SportsLiveTokens.style(SportsLiveTokens.Type.Button),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
        )
    }
}

/**
 * [team]'s short name for the "N new" line (live.25 M): ESPN's short name from the loaded team list ("Mavericks" for
 * Dallas Mavericks, "Illinois" for a school) when the team was followed from ESPN, else the name it was followed by.
 */
internal fun sportsLiveTeamShortName(team: FollowedTeam, directory: TeamDirectory): String {
    val id = team.espnId ?: return team.name
    val parts = team.league?.split('/')?.takeIf { it.size == 2 && it.all(String::isNotBlank) } ?: return team.name
    return directory.teams(EspnLeague(parts[0], parts[1])).firstOrNull { it.id == id }?.shortName?.takeIf { it.isNotBlank() } ?: team.name
}
