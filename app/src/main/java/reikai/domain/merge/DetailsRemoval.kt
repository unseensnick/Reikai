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

    fun targets(removeGrouped: Boolean): List<Long> =
        if (asksForGroup && !removeGrouped) listOf(openedId) else viewedMembers(groupIds, selectedId, openedId)
}

/**
 * The entries a details page shows: the source chip's alone, else every grouped source, else the
 * opened entry, which also covers a group not resolved yet ([groupIds] empty).
 */
fun viewedMembers(groupIds: List<Long>, selectedId: Long?, openedId: Long): List<Long> = when {
    selectedId != null -> listOf(selectedId)
    groupIds.size > 1 -> groupIds
    else -> listOf(openedId)
}
