package reikai.domain.merge

/**
 * A merged series sits in one set of categories: every category write to a member is written to every
 * library member of its group ([set], [change]), and a merge hands the settings owner's categories to
 * the members joining ([adoptOwnerCategories]). The card is placed by its lead, which follows chapter
 * counts, so members that disagree let a refresh move the card by itself. Writes go through each type's
 * own category interactor. See docs/dev/subsystems/merged-series.md.
 */
open class GroupCategories(
    private val categoriesOf: suspend (id: Long) -> List<Long>,
    private val write: suspend (id: Long, categoryIds: List<Long>) -> Unit,
) {

    /** Files [id] and the other library members of its group under exactly [categoryIds]. */
    suspend fun set(id: Long, categoryIds: List<Long>, mergeManager: EntryMergeManager) {
        groupOf(id, mergeManager).forEach { write(it, categoryIds) }
    }

    /**
     * Adds and removes categories on [id]'s whole group. The group's current categories are every
     * member's together, so a category a picker showed as mixed is kept rather than dropped.
     */
    suspend fun change(id: Long, add: List<Long>, remove: Collection<Long>, mergeManager: EntryMergeManager) {
        val members = groupOf(id, mergeManager)
        val changed = (members.flatMap { categoriesOf(it) } - remove.toSet() + add).distinct()
        members.forEach { write(it, changed) }
    }

    /** After a merge: every member of [memberIds] takes the categories of the first, the settings owner. */
    suspend fun adoptOwnerCategories(memberIds: List<Long>) {
        val ownerId = memberIds.firstOrNull() ?: return
        val owned = categoriesOf(ownerId)
        memberIds.drop(1).forEach { write(it, owned) }
    }

    /** The categories [id]'s group holds, read from another of its library members; null outside a group. */
    suspend fun groupCategoriesFor(id: Long, mergeManager: EntryMergeManager): List<Long>? =
        mergeManager.groupLibraryMembers(id).firstOrNull { it != id }?.let { categoriesOf(it) }

    /** [id], having entered its group without a category write of its own, takes the group's categories. */
    suspend fun takeGroupCategories(id: Long, mergeManager: EntryMergeManager) {
        groupCategoriesFor(id, mergeManager)?.let { write(id, it) }
    }

    /** Every group of [mergeManager]'s type takes its first library member's categories. */
    suspend fun alignEveryGroup(mergeManager: EntryMergeManager) {
        mergeManager.libraryGroups().forEach { adoptOwnerCategories(it) }
    }

    private suspend fun groupOf(id: Long, mergeManager: EntryMergeManager): List<Long> =
        mergeManager.computeRelatedIds(id).asList()
}
