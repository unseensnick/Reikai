package reikai.domain.category

import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository

/**
 * Delete a category, renumber every remaining row, and scrub the deleted id out of the category-id
 * preferences that referenced it. The caller passes the preferences to scrub, which follow the deleted
 * row's content type: a universal category is referenced by both libraries' preferences, so deleting one
 * has to clean both sides or the id is left stranded in whichever side went unscrubbed.
 *
 * Renumbering covers the whole table rather than one content type, because the per-library reads overlap
 * on universal rows: renumbering either one alone would rewrite a universal row's order against the other
 * library's positions. Throws on any DB failure, for the caller to map to its own result type.
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

/**
 * Drop a deleted category's id out of each given set preference. Split out so the app-module category
 * delete can scrub its filter preferences (which live above this module) with the same logic the shared
 * [deleteCategoryAndCleanup] uses for the update/download sets.
 */
fun scrubCategoryIdFromSetPrefs(categoryId: Long, categorySetPreferences: List<Preference<Set<String>>>) {
    val categoryIdString = categoryId.toString()
    categorySetPreferences.forEach { preference ->
        val ids = preference.get()
        if (categoryIdString in ids) preference.set(ids.minus(categoryIdString))
    }
}
