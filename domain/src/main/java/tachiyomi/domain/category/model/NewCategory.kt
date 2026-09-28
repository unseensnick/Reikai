package tachiyomi.domain.category.model

import reikai.domain.category.CategoryContentType

data class NewCategory(
    val name: String,
    val flags: Long,
    val contentType: Long = CategoryContentType.MANGA, // RK: which library the category belongs to
)
