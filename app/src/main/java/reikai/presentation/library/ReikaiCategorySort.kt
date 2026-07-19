package reikai.presentation.library

import tachiyomi.domain.category.model.Category

/**
 * Orders a category list by the Reikai category-sort-order pref, so every surface that lists
 * categories (library sections, jump-to-category, the filter include/exclude picker, the category
 * manager) shows them in the same order.
 *
 * 0 = manual (leave as-is, i.e. the caller's `Category.order`); 1 = A->Z; 2 = Z->A. The system
 * (uncategorized) category is always pinned to the top. Sorts by display name so it works for both
 * real DB categories and synthetic dynamic-grouping categories.
 */
fun reikaiSortCategories(categories: List<Category>, sortOrder: Int): List<Category> {
    if (sortOrder == 0 || categories.isEmpty()) return categories
    val (system, rest) = categories.partition { it.isSystemCategory }
    val orderedRest = when (sortOrder) {
        1 -> rest.sortedBy { ReikaiDynamicCategory.displayName(it).lowercase() }
        2 -> rest.sortedByDescending { ReikaiDynamicCategory.displayName(it).lowercase() }
        else -> rest
    }
    return system + orderedRest
}
