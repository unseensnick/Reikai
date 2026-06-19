package reikai.presentation.category

/**
 * Pure set math for the category manager's multi-select mode, shared by the manga and novel
 * category screen models so both tabs behave identically. Ids are category ids; only user-created
 * categories ever reach here (the system Default is filtered out of the list upstream).
 */
object CategorySelection {

    /** Toggle a single category in/out of the selection. */
    fun toggle(selection: Set<Long>, categoryId: Long): Set<Long> =
        if (categoryId in selection) selection - categoryId else selection + categoryId

    /** Add every visible category to the selection. */
    fun selectAll(selection: Set<Long>, visibleIds: List<Long>): Set<Long> =
        selection + visibleIds

    /** Flip the selection: visible categories not currently selected become the new selection. */
    fun invert(selection: Set<Long>, visibleIds: List<Long>): Set<Long> =
        visibleIds.toSet() - selection
}
