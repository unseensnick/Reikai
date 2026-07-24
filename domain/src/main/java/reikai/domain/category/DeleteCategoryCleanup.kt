package reikai.domain.category

import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.category.model.CategoryUpdate
import tachiyomi.domain.category.repository.CategoryRepository

/**
 * Delete a category, renumber the remaining rows of its content type, and scrub the deleted id out of the
 * category-id preferences that referenced it. Shared by manga's [tachiyomi.domain.category.interactor.DeleteCategory]
 * and the novel category delete so the two can't drift; each caller passes its own content type, default-category
 * preference and set-preference list (the preferences live in different modules, so they are injected rather than
 * referenced here). Throws on any DB failure, for the caller to map to its own result type.
 */
suspend fun deleteCategoryAndCleanup(
    categoryRepository: CategoryRepository,
    categoryId: Long,
    contentType: Long,
    defaultCategoryPreference: Preference<Int>,
    categorySetPreferences: List<Preference<Set<String>>>,
) {
    categoryRepository.delete(categoryId)

    val updates = categoryRepository.getAll(contentType).mapIndexed { index, category ->
        CategoryUpdate(id = category.id, order = index.toLong())
    }

    if (defaultCategoryPreference.get() == categoryId.toInt()) {
        defaultCategoryPreference.delete()
    }
    val categoryIdString = categoryId.toString()
    categorySetPreferences.forEach { preference ->
        val ids = preference.get()
        if (categoryIdString in ids) preference.set(ids.minus(categoryIdString))
    }

    categoryRepository.updatePartial(updates)
}
