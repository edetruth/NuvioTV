package com.nuvio.tv.ui.screens.sportslive

/**
 * The card a move into the list (Down from the tab or filter row, OK on a tab or chip) lands on:
 * the last focused card when it is on screen, else the first card on screen, else the first card.
 * Only a card on screen is composed, so only such a card can take focus; aiming at one scrolled
 * away would leave focus stuck where it was.
 */
fun sportsLiveListEntryKey(keys: List<String>, focusedKey: String?, visibleIndices: List<Int>): String? {
    val focusedIndex = focusedKey?.let(keys::indexOf) ?: -1
    if (focusedIndex >= 0 && focusedIndex in visibleIndices) return focusedKey
    val firstVisible = visibleIndices.filter { it in keys.indices }.minOrNull()
    return firstVisible?.let(keys::get) ?: keys.firstOrNull()
}

/**
 * The card that takes [selectedKey]'s place after the list changed under the same tab and filter:
 * [selectedKey] itself while it is still listed; otherwise the nearest card after it in
 * [previousKeys] that is still listed, else the nearest before it, else the first card; null for an
 * empty list. The selection (and the preview) never stays on a card that is no longer listed.
 */
fun sportsLiveKeyAfterRemoval(previousKeys: List<String>, currentKeys: List<String>, selectedKey: String?): String? {
    if (currentKeys.isEmpty()) return null
    if (selectedKey == null) return currentKeys.first()
    if (selectedKey in currentKeys) return selectedKey
    val listed = currentKeys.toHashSet()
    val index = previousKeys.indexOf(selectedKey)
    if (index < 0) return currentKeys.first()
    return previousKeys.subList(index + 1, previousKeys.size).firstOrNull { it in listed }
        ?: previousKeys.subList(0, index).lastOrNull { it in listed }
        ?: currentKeys.first()
}
