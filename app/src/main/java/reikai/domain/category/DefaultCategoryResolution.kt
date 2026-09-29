package reikai.domain.category

import tachiyomi.domain.category.model.Category

/**
 * Where a freshly favorited entry lands, per the default-category preference semantics shared by
 * manga and novels (upstream's): a real category id applies that category; 0 means "none", so the
 * entry is added uncategorized (also the case when the user has no categories); any other value
 * with categories present means "always ask". Returns the category-id list to apply directly, or
 * null when the caller must prompt the user. One kernel so the add paths that favourite an entry
 * cannot drift.
 */
fun resolveDefaultCategoryIds(categories: List<Category>, defaultCategoryId: Int): List<Long>? {
    val defaultCategory = categories.find { it.id == defaultCategoryId.toLong() }
    return when {
        defaultCategory != null -> listOf(defaultCategory.id)
        defaultCategoryId == 0 || categories.isEmpty() -> emptyList()
        else -> null
    }
}

/**
 * The ids a category write may carry: the system category is where an entry filed nowhere shows, not
 * one it can be filed into, so it never reaches a write or counts as a group's categories.
 */
fun List<Long>.withoutSystemCategory(): List<Long> = filter { it != Category.UNCATEGORIZED_ID }

/**
 * Where an entry joining a group lands: the categories the group's members already use, so a new
 * source sits with the rest of the series, else [default], whose null means ask.
 */
suspend fun groupOrDefaultCategoryIds(
    groupCategories: List<Category>,
    default: suspend () -> List<Long>?,
): List<Long>? =
    groupCategories.map { it.id }.withoutSystemCategory().distinct().ifEmpty { null } ?: default()
