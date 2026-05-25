package yokai.presentation.library.manga

import eu.kanade.tachiyomi.data.database.models.Category
import eu.kanade.tachiyomi.data.database.models.LibraryManga
import eu.kanade.tachiyomi.ui.library.models.LibraryItem

/**
 * Arranges library manga into category sections. Per-category sorting happens downstream in
 * [MangaLibrarySort]; this sectioner does the bucket-by-category step, default-category
 * injection, and the category-list ordering (driven by the Reikai-fork
 * `preferences.categorySortOrder` pref). Filtering runs in the Compose layer; grouping
 * ([MangaLibraryGrouping]) and sorting run between the sectioner and the rendered state.
 */
object MangaLibrarySectioner {

    /**
     * @param libraryManga favorited manga from `GetLibraryManga.subscribe()`.
     * @param userCategories user-defined categories from `GetCategories.subscribe()`.
     * @param defaultCategory the "Default" category for manga with `category = 0`. Included in
     *   the result only if at least one manga uses it.
     * @param categorySortOrder Reikai-fork pref controlling how categories themselves are
     *   ordered. `0` = manual (preserves the user's drag-and-drop [Category.order]); `1` =
     *   alphabetical A→Z by `name`; `2` = alphabetical Z→A. Defaults to manual so existing
     *   callers without the pref keep the same behavior. Default category (id = 0) is always
     *   pinned at the top regardless. Mirrors legacy `LibraryPresenter.kt:781-793`.
     */
    fun section(
        libraryManga: List<LibraryManga>,
        userCategories: List<Category>,
        defaultCategory: Category,
        categorySortOrder: Int = 0,
    ): Map<Category, List<LibraryItem.Manga>> {
        if (libraryManga.isEmpty() && userCategories.isEmpty()) return emptyMap()

        val mangaByCategoryId = libraryManga
            .distinctBy { it.manga.id }
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
            if (mangaByCategoryId.containsKey(0)) add(defaultCategory)
            addAll(sortedUserCategories)
        }

        return orderedCategories.associateWith { category ->
            (mangaByCategoryId[category.id] ?: emptyList())
                .map { LibraryItem.Manga(libraryManga = it) }
        }
    }
}
