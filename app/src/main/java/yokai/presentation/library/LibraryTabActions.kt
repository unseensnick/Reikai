package yokai.presentation.library

/**
 * User-triggered actions exposed by a tab's screen model to the shared composable.
 *
 * Phase 1 only needs item navigation. Later phases add selection toggles (Phase 5), filter
 * changes (Phase 3), refresh (Phase 4), and grouping changes (Phase 6).
 */
interface LibraryTabActions {
    fun onItemClick(itemId: Long)
}
