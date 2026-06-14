package reikai.domain.novel.model

import tachiyomi.domain.category.model.Category
import java.io.Serializable

/**
 * Domain mirror of the `novel_categories` table. Parallels [tachiyomi.domain.category.model.Category]
 * with one extra column: [novelOrder] carries the per-category drag order (slash-separated novel
 * ids) or sort-mode char, the same dual encoding the manga side uses on `manga_order`. The
 * library-side resolvers for that encoding land with the Novels library (later stage); S1 stores
 * it as an opaque [String].
 */
data class NovelCategory(
    val id: Long,
    val name: String,
    val order: Long,
    val flags: Long,
    val novelOrder: String,
) : Serializable {

    val isSystemCategory: Boolean = id == UNCATEGORIZED_ID

    companion object {
        const val UNCATEGORIZED_ID = 0L
    }
}

/** Re-type a novel category as Mihon's [Category] so it flows through the shared category UI/views. */
fun NovelCategory.toCategory(): Category = Category(id = id, name = name, order = order, flags = flags)
