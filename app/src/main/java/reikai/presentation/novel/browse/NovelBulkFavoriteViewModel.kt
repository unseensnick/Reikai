package reikai.presentation.novel.browse

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import reikai.novel.host.NovelItem
import reikai.presentation.browse.BatchCategories
import reikai.presentation.browse.EntryBulkFavoriteViewModel

/**
 * The novel facade of [EntryBulkFavoriteViewModel], used by the novel browse surfaces (per-source
 * browse + global search). Reuses [NovelLibraryAdder] for the favoriting so the category behaviour
 * matches the single long-press path. A browse result is a bare [NovelItem] with no id, so selection
 * keys on (sourceId, path); global search carries a source per result, per-source browse a fixed one.
 */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class NovelBulkFavoriteViewModel(
    private val libraryAdder: NovelLibraryAdder,
) : EntryBulkFavoriteViewModel<SelectedNovel>() {

    override fun keyOf(item: SelectedNovel): Any = item.key

    override suspend fun resolveBatchCategories(): BatchCategories = libraryAdder.resolveBatchCategories()

    override suspend fun addToLibrary(items: List<SelectedNovel>, categoryIds: List<Long>) {
        items.forEach { libraryAdder.addToBatch(it.item, it.sourceId, categoryIds) }
    }

    override suspend fun isInLibrary(item: SelectedNovel): Boolean = libraryAdder.isInLibrary(item.item, item.sourceId)

    fun toggleSelection(sourceId: String, item: NovelItem) = toggleSelection(SelectedNovel(sourceId, item))
}

/** A picked browse result: the item plus the source it came from (per-source browse has one source,
 *  global search one per row). [key] is the (sourceId, path) pair used for selection membership. */
@Immutable
data class SelectedNovel(val sourceId: String, val item: NovelItem) {
    val key get() = sourceId to item.path
}
