package tachiyomi.domain.category.model

import reikai.domain.category.CategoryContentType
import java.io.Serializable

data class Category(
    val id: Long,
    val name: String,
    val order: Long,
    val flags: Long,
    // RK: which libraries show this category: universal (both), manga-only or novel-only. Defaults to
    // manga so existing construction sites keep today's behavior, matching the column's own default.
    val contentType: Long = CategoryContentType.MANGA,
) : Serializable {

    val isSystemCategory: Boolean = id == UNCATEGORIZED_ID

    companion object {
        const val UNCATEGORIZED_ID = 0L
    }
}
