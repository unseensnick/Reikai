package yokai.presentation.library.manga

import eu.kanade.tachiyomi.data.database.models.Category
import eu.kanade.tachiyomi.data.database.models.LibraryManga
import eu.kanade.tachiyomi.ui.library.models.LibraryItem

/**
 * Phase 1 grouper. Arranges library manga into category sections with default title sort.
 *
 * Mirrors the legacy presenter's default-category injection and category order. Omits everything
 * that requires later-phase UI: no filter, no source / tag grouping, no hidden placeholders, no
 * custom sort modes. Those land in their respective phases as parallel sibling files under this
 * package.
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
                .sortedBy { it.manga.title.lowercase() }
                .map { LibraryItem.Manga(libraryManga = it) }
        }
    }
}
