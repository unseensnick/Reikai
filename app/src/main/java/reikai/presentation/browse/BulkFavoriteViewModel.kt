package reikai.presentation.browse

import androidx.lifecycle.ViewModel
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga

/**
 * The manga facade of [EntryBulkFavoriteViewModel], used by every manga browse surface. Reuses
 * [MangaLibraryAdder] for the actual favoriting, so the category, tracker and default-chapter-flags
 * behaviour matches the single-tap long-press path. Ported from Komikku and trimmed: one category
 * choice applies to the whole selection, with no per-entry tri-state and no per-duplicate prompt.
 * Migrate stays a single-entry action on the details screen.
 */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class BulkFavoriteViewModel(
    private val libraryAdder: MangaLibraryAdder,
    private val libraryPreferences: LibraryPreferences,
) : EntryBulkFavoriteViewModel<Manga>() {

    override fun keyOf(item: Manga): Any = item.id

    override suspend fun userCategories(): List<Category> = libraryAdder.getUserCategories()

    override suspend fun defaultCategoryId(): Int = libraryPreferences.defaultCategory.get()

    override suspend fun addToLibrary(items: List<Manga>, categoryIds: List<Long>) {
        items.forEach { manga ->
            finishAdd(
                categoryIds = categoryIds,
                favorite = { manga.id.takeIf { libraryAdder.changeFavorite(manga) } },
                fileCategories = { _, ids -> libraryAdder.moveToCategories(manga, ids) },
            )
        }
    }

    fun addFavorite() = addFavoriteFiltered { it.favorite }
}
