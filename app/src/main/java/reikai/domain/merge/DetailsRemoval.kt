package reikai.domain.merge

/**
 * Which entries the details page's library heart takes out of the library. A source chip removes the
 * source it shows, not the entry the page was opened on. The All view of a merged entry asks first,
 * offering every grouped source as the library's Remove dialog does; declined, only the opened entry
 * leaves. [groupIds] are the library members the page resolved, the opened entry among them.
 */
data class DetailsRemoval(val openedId: Long, val groupIds: List<Long>, val selectedId: Long?) {

    /** Whether the remove asks first, offering [groupIds]. */
    val asksForGroup: Boolean get() = selectedId == null && groupIds.size > 1

    fun targets(removeGrouped: Boolean): List<Long> = when {
        selectedId != null -> listOf(selectedId)
        asksForGroup && removeGrouped -> groupIds
        else -> listOf(openedId)
    }
}
