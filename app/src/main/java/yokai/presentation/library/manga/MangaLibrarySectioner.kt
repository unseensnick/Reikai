package yokai.presentation.library.manga

import eu.kanade.tachiyomi.data.database.models.Category
import eu.kanade.tachiyomi.data.database.models.LibraryManga
import eu.kanade.tachiyomi.ui.library.models.LibraryItem

/**
 * Arranges library manga into category sections. Per-category sorting happens downstream in
 * [MangaLibrarySort]; this sectioner only does the bucket-by-category step plus default-category
 * injection and the (still-hardcoded) category-list ordering by [Category.order]. Filtering
 * runs in the Compose layer; grouping ([MangaLibraryGrouping]) and sorting run between the
 * sectioner and the rendered state.
 */
object MangaLibrarySectioner {

    /**
     * @param libraryManga favorited manga from `GetLibraryManga.subscribe()`.
     * @param userCategories user-defined categories from `GetCategories.subscribe()`.
     * @param defaultCategory the "Default" category for manga with `category = 0`. Included in
     *   the result only if at least one manga uses it.
     */
    fun section(
        libraryManga: List<LibraryManga>,
        userCategories: List<Category>,
        defaultCategory: Category,
    ): Map<Category, List<LibraryItem.Manga>> {
        if (libraryManga.isEmpty() && userCategories.isEmpty()) return emptyMap()

        val mangaByCategoryId = libraryManga
            .distinctBy { it.manga.id }
            .groupBy { it.category }

        val orderedCategories = buildList {
            if (mangaByCategoryId.containsKey(0)) add(defaultCategory)
            addAll(userCategories.filter { it.id != null && it.id != 0 }.sortedBy { it.order })
        }

        return orderedCategories.associateWith { category ->
            (mangaByCategoryId[category.id] ?: emptyList())
                .map { LibraryItem.Manga(libraryManga = it) }
        }
    }
}
