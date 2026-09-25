package reikai.domain.category

import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository

/**
 * Delete a category, renumber every remaining row, and scrub the deleted id from the category-id
 * preferences the caller passes: both libraries' for a universal category, or the id is left stranded.
 * Renumbering covers the whole table, since both libraries read universal rows and renumbering one
 * alone would reorder them against the other's positions. Throws on a DB failure, for the caller to map.
 */
suspend fun deleteCategoryAndCleanup(
    categoryRepository: CategoryRepository,
    categoryId: Long,
    defaultCategoryPreferences: List<Preference<Int>>,
    categorySetPreferences: List<Preference<Set<String>>>,
) {
    categoryRepository.delete(categoryId)

    // The system row keeps its -1 sort so it always sorts first; renumbering it too would tie it with the
    // first user category and let ORDER BY sort put them in either order.
    val orderedIds = categoryRepository.getUnfiltered()
        .filterNot(Category::isSystemCategory)
        .map { it.id }

    defaultCategoryPreferences
        .filter { it.get() == categoryId.toInt() }
        .forEach { it.delete() }
    scrubCategoryIdFromSetPrefs(categoryId, categorySetPreferences)

    categoryRepository.updateAllOrders(orderedIds = orderedIds)
}

/** Drop a deleted category's id out of each given set preference. */
private fun scrubCategoryIdFromSetPrefs(categoryId: Long, categorySetPreferences: List<Preference<Set<String>>>) {
    val categoryIdString = categoryId.toString()
    categorySetPreferences.forEach { preference ->
        val ids = preference.get()
        if (categoryIdString in ids) preference.set(ids.minus(categoryIdString))
    }
}
