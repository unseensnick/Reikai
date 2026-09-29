package reikai.presentation.library

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import reikai.domain.library.ReikaiLibraryPreferences
import tachiyomi.domain.category.model.Category

/**
 * Orders a category list by the Reikai category-sort-order pref, so every surface that lists
 * categories shows them in the same order. 0 = manual (each `Category.order`, whatever order the list
 * arrived in), 1 = A to Z, 2 = Z to A, with the system category pinned to the top of both. Every caller
 * passes real DB categories; dynamic grouping orders its own buckets inside [LibraryDynamicGrouping].
 */
fun reikaiSortCategories(categories: List<Category>, sortOrder: Int): List<Category> {
    val manual = categories.sortedBy { it.order }
    if (sortOrder == 0) return manual
    val (system, rest) = manual.partition { it.isSystemCategory }
    val orderedRest = when (sortOrder) {
        1 -> rest.sortedBy { it.name.lowercase() }
        2 -> rest.sortedByDescending { it.name.lowercase() }
        else -> rest
    }
    return system + orderedRest
}

/** [reikaiSortCategories] applied to every emission, re-sorting whenever the sort-order pref changes. */
fun Flow<List<Category>>.sortedByCategoryPref(prefs: ReikaiLibraryPreferences): Flow<List<Category>> =
    combine(this, prefs.categorySortOrder.changes(), ::reikaiSortCategories)
