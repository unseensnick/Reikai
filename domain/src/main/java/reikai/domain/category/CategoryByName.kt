package reikai.domain.category

import tachiyomi.domain.category.model.Category

/**
 * Among categories sharing one name, the one of [contentType], else any of them. A name can belong to a
 * universal row and a typed one at once, and a restore binds by name, so it has to pick deterministically.
 */
fun List<Category>.preferring(contentType: Long): Category? =
    firstOrNull { it.contentType == contentType } ?: firstOrNull()

/** Each category name mapped to its row, choosing among rows that share a name by [preferring]. */
fun List<Category>.byNamePreferring(contentType: Long): Map<String, Category> =
    groupBy { it.name }.mapValues { (_, rows) -> rows.preferring(contentType)!! }
