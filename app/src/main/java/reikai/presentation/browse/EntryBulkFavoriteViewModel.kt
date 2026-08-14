package reikai.presentation.browse

import androidx.compose.runtime.Immutable
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastDistinctBy
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.mutate
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import reikai.domain.category.resolveDefaultCategoryIds
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.preference.mapAsCheckboxState
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.category.model.Category

/**
 * The shared bulk "add to library" engine for every browse surface: selection state, the
 * default-category-or-prompt decision and the one-shot category dialog live here once, so the two
 * content types cannot drift. The per-type facades supply only what genuinely differs: the selection
 * key, the category source, the default-category preference and the add-to-library verb. One category
 * choice applies to the whole selection, already-favorited entries are skipped, and there is no
 * per-duplicate prompt.
 */
abstract class EntryBulkFavoriteViewModel<T : Any> :
    ViewModel() {

    val state: StateFlow<EntryBulkFavoriteViewModel.State<T>>
        field = MutableStateFlow<EntryBulkFavoriteViewModel.State<T>>(State())

    /** Selection identity: two items with the same key are the same selection entry. */
    protected abstract fun keyOf(item: T): Any

    /** The user-visible categories this content type can file into. */
    protected abstract suspend fun userCategories(): List<Category>

    /** The raw default-category preference for this content type. */
    protected abstract suspend fun defaultCategoryId(): Int

    /** Favorite [items] and file them into [categoryIds]; the per-type verb. */
    protected abstract suspend fun addToLibrary(items: List<T>, categoryIds: List<Long>)

    fun backHandler() = toggleSelectionMode(false)

    fun toggleSelectionMode(newMode: Boolean? = null) {
        state.update { state ->
            val mode = newMode ?: !state.selectionMode
            state.copy(
                selectionMode = mode,
                selection = if (mode) state.selection else persistentListOf(),
            )
        }
    }

    fun select(item: T) = toggleSelection(item, toSelectedState = true)

    /** @param toSelectedState `true` to only select, `false` to only unselect, null to toggle. */
    fun toggleSelection(item: T, toSelectedState: Boolean? = null) {
        val target = keyOf(item)
        state.update { state ->
            val newSelection = state.selection.mutate { list ->
                val isSelected = list.fastAny { keyOf(it) == target }
                val shouldSelect = toSelectedState ?: !isSelected
                if (shouldSelect && !isSelected) {
                    list.add(item)
                } else if (!shouldSelect && isSelected) {
                    list.removeAll { keyOf(it) == target }
                }
            }
            state.copy(selection = newSelection, selectionMode = newSelection.isNotEmpty())
        }
    }

    fun reverseSelection(items: List<T>) {
        state.update { state ->
            val newSelection = items
                .filterNot { candidate -> state.selection.fastAny { keyOf(it) == keyOf(candidate) } }
                .fastDistinctBy { keyOf(it) }
                .toPersistentList()
            state.copy(selection = newSelection, selectionMode = newSelection.isNotEmpty())
        }
    }

    /**
     * Add the selected, not-yet-favorited entries. Adds directly when a default category is set (or
     * none exist), otherwise opens a one-shot category picker for the batch. [isFavorited] comes from
     * the facade: manga items carry a favorite flag, while a novel browse item has no id, so its host
     * screen passes the live favorited-key set.
     */
    protected fun addFavoriteFiltered(isFavorited: (T) -> Boolean) {
        viewModelScope.launchIO {
            val items = state.value.selection.filterNot(isFavorited)
            if (items.isEmpty()) {
                toggleSelectionMode(false)
                return@launchIO
            }
            val categories = userCategories()
            val directIds = resolveDefaultCategoryIds(categories, defaultCategoryId())
            if (directIds != null) {
                addAndFinish(items, directIds)
            } else {
                setDialog(Dialog.ChangeCategory(items, categories.mapAsCheckboxState { false }))
            }
        }
    }

    /** Apply the chosen categories to the batch and favorite them (from the category dialog). */
    fun setCategories(items: List<T>, categoryIds: List<Long>) {
        viewModelScope.launchIO { addAndFinish(items, categoryIds) }
    }

    private suspend fun addAndFinish(items: List<T>, categoryIds: List<Long>) {
        addToLibrary(items, categoryIds)
        setDialog(null)
        toggleSelectionMode(false)
    }

    fun setDialog(dialog: Dialog<T>?) {
        state.update { it.copy(dialog = dialog) }
    }

    sealed interface Dialog<out T> {
        data class ChangeCategory<T>(
            val items: List<T>,
            val initialSelection: List<CheckboxState.State<Category>>,
        ) : Dialog<T>
    }

    @Immutable
    data class State<T>(
        val dialog: Dialog<T>? = null,
        val selection: PersistentList<T> = persistentListOf(),
        val selectionMode: Boolean = false,
    )
}
