package reikai.presentation.novel.browse

import androidx.compose.runtime.Immutable
import reikai.domain.novel.NovelPreferences
import reikai.novel.host.NovelItem
import reikai.presentation.browse.EntryBulkFavoriteViewModel
import reikai.presentation.browse.finishAdd
import tachiyomi.domain.category.model.Category
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * The novel facade of [EntryBulkFavoriteViewModel], used by the novel browse surfaces (per-source
 * browse + global search). Reuses [NovelLibraryAdder] for the favoriting so the category behaviour
 * matches the single long-press path. A browse result is a bare [NovelItem] with no id, so selection
 * keys on (sourceId, path); global search carries a source per result, per-source browse a fixed one.
 */
class NovelBulkFavoriteViewModel(
    private val libraryAdder: NovelLibraryAdder = Injekt.get(),
    private val novelPreferences: NovelPreferences = Injekt.get(),
) : EntryBulkFavoriteViewModel<SelectedNovel>() {

    override fun keyOf(item: SelectedNovel): Any = item.key

    override suspend fun userCategories(): List<Category> = libraryAdder.userCategories()

    override suspend fun defaultCategoryId(): Int = novelPreferences.defaultNovelCategory().get()

    override suspend fun addToLibrary(items: List<SelectedNovel>, categoryIds: List<Long>) {
        items.forEach { selected ->
            finishAdd(
                categoryIds = categoryIds,
                favorite = { libraryAdder.favoriteReturningId(selected.item, selected.sourceId) },
                fileCategories = { id, ids -> libraryAdder.applyCategories(id, ids) },
            )
        }
    }

    fun select(sourceId: String, item: NovelItem) = select(SelectedNovel(sourceId, item))

    fun toggleSelection(sourceId: String, item: NovelItem) = toggleSelection(SelectedNovel(sourceId, item))

    /** [favoritedKeys] comes from the host screen (a NovelItem has no favorite flag), so
     *  already-in-library entries are skipped. */
    fun addFavorite(favoritedKeys: Set<Pair<String, String>>) =
        addFavoriteFiltered { it.key in favoritedKeys }
}

/** A picked browse result: the item plus the source it came from (per-source browse has one source,
 *  global search one per row). [key] is the (sourceId, path) pair used for selection membership. */
@Immutable
data class SelectedNovel(val sourceId: String, val item: NovelItem) {
    val key get() = sourceId to item.path
}
