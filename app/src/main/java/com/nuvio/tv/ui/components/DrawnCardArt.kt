package com.nuvio.tv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf

/**
 * Art drawn in place of a card's image, by card id; null keeps the card's own image or placeholder.
 * Home provides it for Sports & Live's Catch up cards (#58), which carry no image URL at all, so no
 * addon artwork (which may show a score) is ever loaded for them.
 */
val LocalDrawnCardArt = compositionLocalOf<(String) -> (@Composable () -> Unit)?> { { null } }

/** The drawn art for card [id] when there is one, else [fallback]. */
@Composable
fun DrawnCardArtOr(id: String?, fallback: @Composable () -> Unit) {
    val drawn = id?.let { LocalDrawnCardArt.current(it) }
    if (drawn != null) drawn() else fallback()
}
