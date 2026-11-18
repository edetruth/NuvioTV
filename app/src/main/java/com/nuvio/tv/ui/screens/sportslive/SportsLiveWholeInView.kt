package com.nuvio.tv.ui.screens.sportslive

import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.launch

/**
 * When focus comes into this panel or section, the whole of it (its heading, padding and the focus ring of
 * what is focused) is scrolled on screen, not only the focused child (QA pass 3: Game page Top performers
 * and Team page tiles were cut flat at the bottom, and a heading slid under the header). A panel taller
 * than the screen shows from its top.
 */
@Composable
internal fun Modifier.wholeInViewOnFocus(): Modifier {
    val requester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    return this
        .bringIntoViewRequester(requester)
        .onFocusChanged { if (it.hasFocus) scope.launch { requester.bringIntoView() } }
}
