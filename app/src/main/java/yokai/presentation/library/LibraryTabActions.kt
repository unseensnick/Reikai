package yokai.presentation.library

/**
 * User-triggered actions exposed by a tab's screen model to the shared composable.
 *
 * Shaped against `Long` ids (not domain types) so the same surface plugs into the novels
 * tab in Phase 7 without bifurcating per-type interfaces.
 *
 * Note: `LibraryScreen` currently passes individual callbacks to `LibraryContent` rather than
 * a single actions object. This interface is the content-type-agnostic contract for the
 * eventual Phase 8 tabbed shell; new callbacks land here AND on the call site at the same
 * time so the contract stays in lockstep with the live wiring.
 */
interface LibraryTabActions {
    fun onItemClick(itemId: Long)

    /** Toggle a single manga's membership in the selection set. Used by long-press in C2. */
    fun onToggleSelection(itemId: Long)

    /** Clear all selected items. Triggered by back press or the contextual bar's close icon. */
    fun onClearSelection()
}
