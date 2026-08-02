package reikai.domain.category

import tachiyomi.domain.category.model.Category

/**
 * Where a freshly favorited entry lands, per the default-category preference semantics shared by
 * manga and novels (upstream's): a real category id applies that category; 0 means "none", so the
 * entry is added uncategorized (also the case when the user has no categories); any other value
 * with categories present means "always ask". Returns the category-id list to apply directly, or
 * null when the caller must prompt the user. One kernel so the three add paths (both library
 * adders and the bulk-favorite engine) cannot drift.
 */
fun resolveDefaultCategoryIds(categories: List<Category>, defaultCategoryId: Int): List<Long>? {
    val defaultCategory = categories.find { it.id == defaultCategoryId.toLong() }
    return when {
        defaultCategory != null -> listOf(defaultCategory.id)
        defaultCategoryId == 0 || categories.isEmpty() -> emptyList()
        else -> null
    }
}
