package yokai.presentation.library.novels

import eu.kanade.tachiyomi.data.database.models.LibraryNovel
import eu.kanade.tachiyomi.data.database.models.NovelCategory
import eu.kanade.tachiyomi.ui.library.models.LibraryItem

/**
 * Novel-side parallel of [yokai.presentation.library.manga.MangaLibrarySectioner]. Arranges
 * library novels into category sections. Per-category sorting happens downstream in
 * [NovelLibrarySort]; this sectioner does the bucket-by-category step, default-category
 * injection, and the category-list ordering (driven by the Reikai-fork `categorySortOrder`
 * pref the caller passes in).
 */
object NovelLibrarySectioner {

    /**
     * @param libraryNovel favorited novels from `NovelRepository.getLibraryNovelAsFlow()`.
     * @param userCategories user-defined novel categories.
     * @param defaultCategory the "Default" category for novels with `category = 0`. Included in
     *   the result only if at least one novel uses it.
     * @param categorySortOrder Reikai-fork pref controlling how categories themselves are
     *   ordered. `0` = manual (preserves [NovelCategory.order]); `1` = A→Z by `name`; `2` = Z→A.
     *   Defaults to manual so existing callers without the pref keep the same behavior. Default
     *   category (id = 0) is always pinned at the top regardless.
     */
    fun section(
        libraryNovel: List<LibraryNovel>,
        userCategories: List<NovelCategory>,
        defaultCategory: NovelCategory,
        categorySortOrder: Int = 0,
    ): Map<NovelCategory, List<LibraryItem.Novel>> {
        if (libraryNovel.isEmpty() && userCategories.isEmpty()) return emptyMap()

        val novelByCategoryId = libraryNovel
            .distinctBy { it.novel.id }
            .groupBy { it.category }

        val sortedUserCategories = userCategories
            .filter { it.id != null && it.id != 0 }
            .let { cats ->
                when (categorySortOrder) {
                    1 -> cats.sortedBy { it.name.lowercase() }
                    2 -> cats.sortedByDescending { it.name.lowercase() }
                    else -> cats.sortedBy { it.order }
                }
            }

        val orderedCategories = buildList {
            if (novelByCategoryId.containsKey(0)) add(defaultCategory)
            addAll(sortedUserCategories)
        }

        return orderedCategories.associateWith { category ->
            (novelByCategoryId[category.id] ?: emptyList())
                .map { LibraryItem.Novel(libraryNovel = it) }
        }
    }
}
