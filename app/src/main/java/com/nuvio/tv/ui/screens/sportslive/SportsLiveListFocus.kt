package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.domain.model.SportsLiveItem

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
 * [current] with the focused card ([focusedId]) kept when the list no longer has it (#145): a load or a
 * verdict never takes the card from under the user. It goes back where it was in [previous], after the
 * nearest card before it still listed (first when none is); it leaves on the next build once focus moves.
 * An emptied list (a profile switch, every catalog gone) keeps nothing.
 */
fun sportsLiveKeepFocused(previous: List<SportsLiveItem>, current: List<SportsLiveItem>, focusedId: String?): List<SportsLiveItem> {
    if (focusedId == null || current.isEmpty() || current.any { it.id == focusedId }) return current
    val index = previous.indexOfFirst { it.id == focusedId }
    if (index < 0) return current
    val listed = current.mapTo(HashSet()) { it.id }
    val before = previous.subList(0, index).lastOrNull { it.id in listed }
    val at = if (before == null) 0 else current.indexOfFirst { it.id == before.id } + 1
    return current.toMutableList().apply { add(at, previous[index]) }
}

/**
 * Live now while the user browses it (F6, extended by TV QA BUG7 / Q5: "rows never re-order under focus"): [previous]
 * as shown, in its order and with its count, each row refreshed from [current] (its score, its state) and a row a load
 * dropped kept in place; the rows [current] adds are held back ([SportsLiveFrozenList.heldBack]) for the "N new" on
 * Refresh, which shows them. The list changes only when the user asks, or once they leave it. Nothing shown yet (the
 * first load) or an emptied list: [current] as it is.
 */
fun sportsLiveFreeze(previous: List<SportsLiveItem>, current: List<SportsLiveItem>): SportsLiveFrozenList {
    if (previous.isEmpty() || current.isEmpty()) return SportsLiveFrozenList(current, emptyList())
    val byId = current.associateBy { it.id }
    val shown = previous.map { byId[it.id] ?: it }
    val shownIds = previous.mapTo(HashSet()) { it.id }
    return SportsLiveFrozenList(shown, current.filter { it.id !in shownIds })
}

/**
 * Live now once shown this visit (J1, live.23: "4 games, then 33 arrive and the rows reshuffle"): [previous]'s rows still
 * in [current], in [previous]'s order and refreshed from [current]; a row a load dropped goes; the rows [current] adds
 * wait ([SportsLiveFrozenList.heldBack]) for "N new". Nothing shown yet, nothing of it left, or an emptied list:
 * [current] as it is.
 */
fun sportsLiveCalm(previous: List<SportsLiveItem>, current: List<SportsLiveItem>): SportsLiveFrozenList {
    if (previous.isEmpty() || current.isEmpty()) return SportsLiveFrozenList(current, emptyList())
    val byId = current.associateBy { it.id }
    val kept = previous.mapNotNull { byId[it.id] }
    if (kept.isEmpty()) return SportsLiveFrozenList(current, emptyList())
    val shownIds = previous.mapTo(HashSet()) { it.id }
    return SportsLiveFrozenList(kept, current.filter { it.id !in shownIds })
}

/** Live now frozen while browsed: the [items] shown, and the new rows [heldBack] until the user asks for them. */
data class SportsLiveFrozenList(val items: List<SportsLiveItem>, val heldBack: List<SportsLiveItem>)

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

/**
 * M3: the row nearest where [goneId] was in [previousIds], among [currentIds]: the closest by distance, the one before
 * it first on a tie. Null when [goneId] was not in [previousIds] or none of its rows is listed.
 */
fun sportsLiveNearestRow(previousIds: List<String>, goneId: String, currentIds: List<String>): String? {
    val index = previousIds.indexOf(goneId)
    if (index < 0) return null
    val listed = currentIds.toHashSet()
    for (distance in 1..previousIds.size) {
        previousIds.getOrNull(index - distance)?.takeIf { it in listed }?.let { return it }
        previousIds.getOrNull(index + distance)?.takeIf { it in listed }?.let { return it }
    }
    return null
}

/**
 * M3 (live.26): Back from the player, focus goes to the row [target] (the game playing now). Compose's focus restorer on
 * the list answers a request from outside the list with the row it remembers (the one played from, before any zap), so
 * focus counts as returned only once [target] itself holds it ([focusedKey]: the focused row while the list holds
 * focus, else null). [request] asks again after each frame ([awaitFrame]), at most [attempts] times; once the list holds
 * focus the next request is a move inside it, which the restorer leaves alone. True when [target] got focus.
 */
suspend fun sportsLiveFocusReturnRow(
    target: String,
    attempts: Int,
    focusedKey: () -> String?,
    request: () -> Unit,
    awaitFrame: suspend () -> Unit
): Boolean {
    repeat(attempts) {
        request()
        awaitFrame()
        if (focusedKey() == target) return true
    }
    return false
}
