package com.nuvio.tv.ui.components

import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.sp
import androidx.tv.material3.LocalTextStyle

/**
 * Single-line text that shrinks its font size to fit the available width
 * instead of truncating with an ellipsis. Backed by Compose's official
 * BasicText autoSize support.
 */
@Composable
fun AutoResizeText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    minFontSize: TextUnit = 9.sp,
    textAlign: TextAlign? = null
) {
    val maxFontSize = if (style.fontSize.type == TextUnitType.Sp) style.fontSize else 14.sp
    val autoSize = remember(minFontSize, maxFontSize) {
        TextAutoSize.StepBased(
            minFontSize = minFontSize,
            maxFontSize = maxFontSize,
            stepSize = 1.sp
        )
    }
    val mergedStyle = remember(style, textAlign) {
        if (textAlign != null) style.copy(textAlign = textAlign) else style
    }
    BasicText(
        text = text,
        modifier = modifier,
        style = mergedStyle,
        color = ColorProducer { color },
        autoSize = autoSize,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip
    )
}

/**
 * One font size for a set of sibling labels (a menu): the largest, from [style]'s size (14 sp when it
 * has none) down to [minFontSize] in 1 sp steps, at which every one of [texts] fits [maxWidth] on one
 * line. Pass it as the style of each [AutoResizeText], so a long label never sits smaller than the rest.
 */
@Composable
fun rememberSharedFitTextStyle(
    texts: List<String>,
    maxWidth: Dp,
    style: TextStyle = LocalTextStyle.current,
    minFontSize: TextUnit = 9.sp
): TextStyle {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(texts, maxWidth, style, minFontSize, density) {
        val maxSp = if (style.fontSize.type == TextUnitType.Sp) style.fontSize.value else 14f
        val widthPx = with(density) { maxWidth.roundToPx() }
        val size = sharedFitFontSize(maxSp, minFontSize.value) { sp ->
            texts.all { text ->
                measurer.measure(text, style.copy(fontSize = sp.sp), maxLines = 1, softWrap = false).size.width <= widthPx
            }
        }
        style.copy(fontSize = size.sp)
    }
}

/** The largest size from [maxSp] down to [minSp] (1 sp steps) that [fits]; [minSp] when none does. */
internal fun sharedFitFontSize(maxSp: Float, minSp: Float, fits: (Float) -> Boolean): Float {
    var size = maxSp
    while (size > minSp && !fits(size)) size -= 1f
    return size.coerceAtLeast(minSp)
}
