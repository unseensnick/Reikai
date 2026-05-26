package eu.kanade.tachiyomi.data.database.models

import yokai.domain.novel.models.Novel

/**
 * Junction-row data class for the `novels_categories` table. Mirrors [MangaCategory] one-for-one;
 * named [NovelInCategory] (not `NovelCategory`) because the latter is already taken by the
 * category-itself interface that parallels [Category].
 */
class NovelInCategory {

    var id: Long? = null

    var novel_id: Long = 0

    var category_id: Int = 0

    companion object {

        fun create(novel: Novel, category: NovelCategory): NovelInCategory {
            val nc = NovelInCategory()
            nc.novel_id = novel.id!!
            nc.category_id = category.id!!
            return nc
        }
    }
}
