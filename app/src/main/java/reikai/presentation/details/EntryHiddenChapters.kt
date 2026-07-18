package reikai.presentation.details

/**
 * Shared hidden-chapter view logic for the manga and novel details models, so the "show hidden only
 * while hidden chapters still exist" collapse rule and the dimmed-id computation can't drift. Each model
 * keeps its own chapter type, key building, and pipeline integration; these operate over a [keyOf] /
 * [idOf] projection of whatever chapter/item type the caller renders.
 */
data class HiddenChapterView<T>(
    val visible: List<T>,
    val hasHidden: Boolean,
    val showHidden: Boolean,
)

/**
 * Resolve the hidden-chapter view: drop hidden items unless the user is temporarily showing them, and
 * resolve "show hidden" against whether any hidden chapter still exists, so unhiding the last one
 * collapses the mode instead of leaving a stale toggle.
 */
fun <T> resolveHiddenChapterView(
    items: List<T>,
    hiddenKeys: Set<String>,
    showHiddenRequested: Boolean,
    keyOf: (T) -> String,
): HiddenChapterView<T> {
    val hasHidden = hiddenKeys.isNotEmpty() && items.any { keyOf(it) in hiddenKeys }
    val showHidden = showHiddenRequested && hasHidden
    val visible = if (showHidden || !hasHidden) items else items.filterNot { keyOf(it) in hiddenKeys }
    return HiddenChapterView(visible, hasHidden, showHidden)
}

/** Ids of the currently-shown hidden rows (for dimming); empty unless hidden chapters are being shown. */
fun <T> hiddenChapterIdsIn(
    displayed: List<T>,
    hiddenKeys: Set<String>,
    showHidden: Boolean,
    keyOf: (T) -> String,
    idOf: (T) -> Long,
): Set<Long> = if (showHidden) {
    displayed.filter { keyOf(it) in hiddenKeys }.mapTo(HashSet()) { idOf(it) }
} else {
    emptySet()
}
