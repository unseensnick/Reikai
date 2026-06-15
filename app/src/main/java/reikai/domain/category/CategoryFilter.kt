package reikai.domain.category

/*
 * Shared include/exclude category-filter math, used by the library and the Updates feed (manga +
 * novel). Kept pure (no Android, no coroutines, ids as Long) so it unit-tests directly and stays
 * the single source of truth instead of being copy-pasted per surface.
 *
 * Callers convert their stored Set<String> prefs to Set<Long> first (category ids are Long);
 * resolving a series' category membership (a DB lookup on the Updates side) is the caller's job too.
 */

/** True when an include/exclude category filter is actually constraining the list. */
fun categoryFilterActive(enabled: Boolean, include: Set<Long>, exclude: Set<Long>): Boolean =
    enabled && (include.isNotEmpty() || exclude.isNotEmpty())

/**
 * A series passes when it sits on at least one included category (or none are required) and on none
 * of the excluded categories. [categories] is the set of category ids the series belongs to (empty
 * means uncategorized).
 */
fun matchesCategoryFilter(categories: Collection<Long>, include: Set<Long>, exclude: Set<Long>): Boolean {
    val isIncluded = include.isEmpty() || categories.any { it in include }
    val isExcluded = exclude.isNotEmpty() && categories.any { it in exclude }
    return isIncluded && !isExcluded
}
