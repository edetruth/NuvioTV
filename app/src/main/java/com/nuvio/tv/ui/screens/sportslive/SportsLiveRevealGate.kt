package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.core.sportslive.scores.SpoilerContext
import com.nuvio.tv.core.sportslive.scores.SpoilerPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf

/**
 * The reveal-gated load of a page (Game, Team, Player card; #117). On every change of [settings], [revealed] and
 * [visible], [onChange] is told whether the page may load ([SpoilerPolicy] for [context]: under Never show only
 * after Reveal); while it may and the page is visible, [load] runs, cancelled by the next change.
 */
internal suspend fun <S> revealGatedLoad(
    settings: Flow<S>,
    revealed: Flow<Boolean>,
    visible: Flow<Boolean> = flowOf(true),
    mode: (S) -> ScoresMode,
    context: SpoilerContext,
    onChange: (settings: S, revealed: Boolean, mayLoad: Boolean) -> Unit,
    load: suspend (S) -> Unit
) {
    combine(settings, revealed, visible) { current, isRevealed, isVisible -> Triple(current, isRevealed, isVisible) }
        .distinctUntilChanged()
        .collectLatest { (current, isRevealed, isVisible) ->
            val mayLoad = SpoilerPolicy.visibility(mode(current), isRevealed, context).mayLoad
            onChange(current, isRevealed, mayLoad)
            if (mayLoad && isVisible) load(current)
        }
}
