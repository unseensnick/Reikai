package reikai.presentation.browse

import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * The manga facade of [EntryBulkFavoriteScreenModel], used by every manga browse surface
 * (per-source Browse, global search, the MangaDex follows screen). Reuses [MangaLibraryAdder] for
 * the actual favoriting, so the category / tracker / default-chapter-flags behaviour matches the
 * single-tap long-press path.
 *
 * Ported from Komikku, trimmed: the category picker applies one choice to the whole selection (no
 * per-entry common/mix tri-state), and it does not prompt per duplicate (already-favourited entries
 * are simply skipped). Migrate stays a single-entry action on the details screen.
 */
class BulkFavoriteScreenModel(
    private val libraryAdder: MangaLibraryAdder = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
) : EntryBulkFavoriteScreenModel<Manga>() {

    override fun keyOf(item: Manga): Any = item.id

    override suspend fun userCategories(): List<Category> = libraryAdder.getUserCategories()

    override suspend fun defaultCategoryId(): Int = libraryPreferences.defaultCategory.get()

    override suspend fun addToLibrary(items: List<Manga>, categoryIds: List<Long>) {
        items.forEach { manga ->
            libraryAdder.moveToCategories(manga, categoryIds)
            libraryAdder.changeFavorite(manga)
        }
    }

    fun addFavorite() = addFavoriteFiltered { it.favorite }
}
