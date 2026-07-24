package reikai.domain.novel.model

import tachiyomi.domain.category.model.Category
import java.io.Serializable

/**
 * Domain mirror of a novel category. Since the schema unification novel categories are rows in the
 * shared `categories` table (content_type 2), so this parallels [tachiyomi.domain.category.model.Category]
 * exactly; it stays a distinct type only while the novel category stack is collapsed onto the shared one.
 */
data class NovelCategory(
    val id: Long,
    val name: String,
    val order: Long,
    val flags: Long,
) : Serializable {

    val isSystemCategory: Boolean = id == UNCATEGORIZED_ID

    companion object {
        const val UNCATEGORIZED_ID = 0L
    }
}

/** Re-type a novel category as Mihon's [Category] so it flows through the shared category UI/views. */
fun NovelCategory.toCategory(): Category = Category(id = id, name = name, order = order, flags = flags)
