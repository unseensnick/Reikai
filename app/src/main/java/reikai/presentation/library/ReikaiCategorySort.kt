package reikai.presentation.library

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import reikai.domain.library.CategorySortOrder
import reikai.domain.library.ReikaiLibraryPreferences
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository

/**
 * Orders a category list by the Reikai category-sort-order pref, so every surface that lists
 * categories shows them in the same order. Manual follows each `Category.order`, whatever order the
 * list arrived in; A to Z and Z to A pin the system category to the top. Every caller passes real DB
 * categories; dynamic grouping orders its own buckets inside [LibraryDynamicGrouping].
 */
fun reikaiSortCategories(categories: List<Category>, sortOrder: CategorySortOrder): List<Category> {
    val manual = categories.sortedBy { it.order }
    val (system, rest) = manual.partition { it.isSystemCategory }
    return when (sortOrder) {
        CategorySortOrder.MANUAL -> manual
        CategorySortOrder.A_TO_Z -> system + rest.sortedBy { it.name.lowercase() }
        CategorySortOrder.Z_TO_A -> system + rest.sortedByDescending { it.name.lowercase() }
    }
}

/** [reikaiSortCategories] applied to every emission, re-sorting whenever the sort-order pref changes. */
fun Flow<List<Category>>.sortedByCategoryPref(prefs: ReikaiLibraryPreferences): Flow<List<Category>> =
    combine(this, prefs.categorySortOrder.changes(), ::reikaiSortCategories)

/** Every category of both libraries, live and in the category sort order, for a list spanning the two. */
fun CategoryRepository.subscribeAllInCategoryOrder(prefs: ReikaiLibraryPreferences): Flow<List<Category>> =
    getUnfilteredAsFlow().sortedByCategoryPref(prefs)
