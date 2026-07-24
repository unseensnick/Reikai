package reikai.presentation.novel.browse

import androidx.compose.runtime.Immutable
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastDistinctBy
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.mutate
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.update
import reikai.domain.category.GetNovelCategories
import reikai.domain.novel.NovelPreferences
import reikai.novel.host.NovelItem
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.category.model.Category
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Selection state and bulk "add to library" for the novel browse surfaces (per-source browse + global
 * search), the novel twin of [reikai.presentation.browse.BulkFavoriteScreenModel]. Reuses
 * [NovelLibraryAdder] for the favoriting so the category behaviour matches the single long-press path.
 * A browse result is a bare [NovelItem] with no id, so selection keys on (sourceId, path); global search
 * carries a source per result, per-source browse a fixed one. Mirrors the manga model: one category
 * choice for the whole batch, already-favorited entries skipped, no per-duplicate prompt.
 */
class NovelBulkFavoriteScreenModel(
    private val libraryAdder: NovelLibraryAdder = Injekt.get(),
    private val getNovelCategories: GetNovelCategories = Injekt.get(),
    private val novelPreferences: NovelPreferences = Injekt.get(),
) : StateScreenModel<NovelBulkFavoriteScreenModel.State>(State()) {

    fun backHandler() = toggleSelectionMode(false)

    fun toggleSelectionMode(newMode: Boolean? = null) {
        mutableState.update { state ->
            val mode = newMode ?: !state.selectionMode
            state.copy(
                selectionMode = mode,
                selection = if (mode) state.selection else persistentListOf(),
            )
        }
    }

    fun select(sourceId: String, item: NovelItem) = toggleSelection(sourceId, item, toSelectedState = true)

    /** @param toSelectedState `true` to only select, `false` to only unselect, null to toggle. */
    fun toggleSelection(sourceId: String, item: NovelItem, toSelectedState: Boolean? = null) {
        val target = sourceId to item.path
        mutableState.update { state ->
            val newSelection = state.selection.mutate { list ->
                val isSelected = list.fastAny { it.key == target }
                val shouldSelect = toSelectedState ?: !isSelected
                if (shouldSelect && !isSelected) {
                    list.add(SelectedNovel(sourceId, item))
                } else if (!shouldSelect && isSelected) {
                    list.removeAll { it.key == target }
                }
            }
            state.copy(selection = newSelection, selectionMode = newSelection.isNotEmpty())
        }
    }

    fun reverseSelection(items: List<SelectedNovel>) {
        mutableState.update { state ->
            val newSelection = items
                .filterNot { candidate -> state.selection.fastAny { it.key == candidate.key } }
                .fastDistinctBy { it.key }
                .toPersistentList()
            state.copy(selection = newSelection, selectionMode = newSelection.isNotEmpty())
        }
    }

    /**
     * Add the selected, not-yet-favorited entries. Adds directly when a default category is set (or none
     * exist), otherwise opens a one-shot category picker for the batch. [favoritedKeys] comes from the
     * host screen (a NovelItem has no favorite flag), so already-in-library entries are skipped.
     */
    fun addFavorite(favoritedKeys: Set<Pair<String, String>>) {
        screenModelScope.launchIO {
            val items = state.value.selection.filterNot { it.key in favoritedKeys }
            if (items.isEmpty()) {
                toggleSelectionMode(false)
                return@launchIO
            }
            val categories = getNovelCategories.await().filter { it.id > 0L }
            val defaultId = novelPreferences.defaultNovelCategory().get()
            val defaultCategory = categories.find { it.id == defaultId.toLong() }
            when {
                defaultCategory != null -> addToLibrary(items, listOf(defaultCategory.id))
                defaultId == 0 || categories.isEmpty() -> addToLibrary(items, emptyList())
                else -> setDialog(Dialog.ChangeCategory(items, categories))
            }
        }
    }

    /** Apply the chosen categories to the batch and favorite them (from the category dialog). */
    fun setNovelsCategories(items: List<SelectedNovel>, categoryIds: List<Long>) {
        screenModelScope.launchIO { addToLibrary(items, categoryIds) }
    }

    private suspend fun addToLibrary(items: List<SelectedNovel>, categoryIds: List<Long>) {
        items.forEach { selected ->
            val id = libraryAdder.favoriteReturningId(selected.item, selected.sourceId) ?: return@forEach
            libraryAdder.applyCategories(id, categoryIds)
        }
        setDialog(null)
        toggleSelectionMode(false)
    }

    fun setDialog(dialog: Dialog?) {
        mutableState.update { it.copy(dialog = dialog) }
    }

    sealed interface Dialog {
        data class ChangeCategory(
            val items: List<SelectedNovel>,
            val categories: List<Category>,
        ) : Dialog
    }

    @Immutable
    data class State(
        val dialog: Dialog? = null,
        val selection: PersistentList<SelectedNovel> = persistentListOf(),
        val selectionMode: Boolean = false,
    )
}

/** A picked browse result: the item plus the source it came from (per-source browse has one source,
 *  global search one per row). [key] is the (sourceId, path) pair used for selection membership. */
@Immutable
data class SelectedNovel(val sourceId: String, val item: NovelItem) {
    val key get() = sourceId to item.path
}
