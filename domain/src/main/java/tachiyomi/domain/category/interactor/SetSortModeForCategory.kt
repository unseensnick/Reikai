package tachiyomi.domain.category.interactor

import reikai.domain.library.CATEGORY_SORT_CUSTOMIZED
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.model.CategoryUpdate
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.library.model.plus
import tachiyomi.domain.library.service.LibraryPreferences
import kotlin.random.Random

class SetSortModeForCategory(
    private val preferences: LibraryPreferences,
    private val categoryRepository: CategoryRepository,
) {

    suspend fun await(categoryId: Long?, type: LibrarySort.Type, direction: LibrarySort.Direction) {
        val category = categoryId?.let { categoryRepository.get(it) }
        if (type == LibrarySort.Type.Random) {
            preferences.randomSortSeed.set(Random.nextInt())
        }
        if (category != null && preferences.categorizedDisplaySettings.get()) {
            // RK: a per-category sort is an OVERRIDE. Mark the CUSTOMIZED bit so the read
            // (reikai.domain.library.sortForCategory) uses this category's own sort; non-overridden
            // categories follow the global sortingMode instead.
            val flags = (category.flags + type + direction) or CATEGORY_SORT_CUSTOMIZED
            categoryRepository.updatePartial(
                CategoryUpdate(
                    id = category.id,
                    flags = flags,
                ),
            )
        } else {
            // RK: global sort. Non-overridden categories follow it dynamically via sortForCategory, so
            // we no longer brute-force updateAllFlags (which wiped per-category overrides + the hidden bit).
            preferences.sortingMode.set(LibrarySort(type, direction))
        }
    }

    suspend fun await(
        category: Category?,
        type: LibrarySort.Type,
        direction: LibrarySort.Direction,
    ) {
        await(category?.id, type, direction)
    }
}
