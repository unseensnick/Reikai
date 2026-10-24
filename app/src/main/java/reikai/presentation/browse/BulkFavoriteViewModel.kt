package reikai.presentation.browse

import androidx.lifecycle.ViewModel
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
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
) : EntryBulkFavoriteViewModel<Manga>() {

    override fun keyOf(item: Manga): Any = item.id

    override suspend fun resolveBatchCategories(): BatchCategories = libraryAdder.resolveBatchCategories()

    override suspend fun addToLibrary(items: List<Manga>, categoryIds: List<Long>) {
        items.forEach { libraryAdder.addToBatch(it.id, categoryIds) }
    }

    override suspend fun isInLibrary(item: Manga): Boolean = libraryAdder.isInLibrary(item.id)
}
