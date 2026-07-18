package reikai.presentation.browse

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
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.preference.mapAsCheckboxState
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Selection state and bulk "add to library" for any manga browse surface (per-source Browse, global
 * search, the MangaDex follows screen). Reuses [MangaLibraryAdder] for the actual favoriting, so the
 * category / tracker / default-chapter-flags behaviour matches the single-tap long-press path.
 *
 * Ported from Komikku, trimmed: the category picker applies one choice to the whole selection (no
 * per-entry common/mix tri-state), and it does not prompt per duplicate (already-favourited entries
 * are simply skipped). Migrate stays a single-entry action on the details screen.
 */
class BulkFavoriteScreenModel(
    private val libraryAdder: MangaLibraryAdder = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
) : StateScreenModel<BulkFavoriteScreenModel.State>(State()) {

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

    fun select(manga: Manga) = toggleSelection(manga, toSelectedState = true)

    /** @param toSelectedState `true` to only select, `false` to only unselect, null to toggle. */
    fun toggleSelection(manga: Manga, toSelectedState: Boolean? = null) {
        mutableState.update { state ->
            val newSelection = state.selection.mutate { list ->
                val isSelected = list.fastAny { it.id == manga.id }
                val shouldSelect = toSelectedState ?: !isSelected
                if (shouldSelect && !isSelected) {
                    list.add(manga)
                } else if (!shouldSelect && isSelected) {
                    list.removeAll { it.id == manga.id }
                }
            }
            state.copy(selection = newSelection, selectionMode = newSelection.isNotEmpty())
        }
    }

    fun reverseSelection(mangas: List<Manga>) {
        mutableState.update { state ->
            val newSelection = mangas
                .filterNot { manga -> state.selection.fastAny { it.id == manga.id } }
                .fastDistinctBy { it.id }
                .toPersistentList()
            state.copy(selection = newSelection, selectionMode = newSelection.isNotEmpty())
        }
    }

    /**
     * Add the selected, not-yet-favourited entries to the library. Adds directly when a default
     * category is set (or none exist), otherwise opens a one-shot category picker for the batch.
     */
    fun addFavorite() {
        screenModelScope.launchIO {
            val mangaList = state.value.selection.filterNot { it.favorite }
            if (mangaList.isEmpty()) {
                toggleSelectionMode(false)
                return@launchIO
            }
            val categories = libraryAdder.getUserCategories()
            val defaultCategoryId = libraryPreferences.defaultCategory.get()
            val defaultCategory = categories.find { it.id == defaultCategoryId.toLong() }
            when {
                defaultCategory != null -> addToLibrary(mangaList, listOf(defaultCategory.id))
                defaultCategoryId == 0 || categories.isEmpty() -> addToLibrary(mangaList, emptyList())
                else -> setDialog(Dialog.ChangeCategory(mangaList, categories.mapAsCheckboxState { false }))
            }
        }
    }

    /** Apply the chosen categories to the batch and favourite them (from the category dialog). */
    fun setMangasCategories(mangas: List<Manga>, categoryIds: List<Long>) {
        screenModelScope.launchIO { addToLibrary(mangas, categoryIds) }
    }

    private suspend fun addToLibrary(mangas: List<Manga>, categoryIds: List<Long>) {
        mangas.forEach { manga ->
            libraryAdder.moveToCategories(manga, categoryIds)
            libraryAdder.changeFavorite(manga)
        }
        setDialog(null)
        toggleSelectionMode(false)
    }

    fun setDialog(dialog: Dialog?) {
        mutableState.update { it.copy(dialog = dialog) }
    }

    sealed interface Dialog {
        data class ChangeCategory(
            val mangas: List<Manga>,
            val initialSelection: List<CheckboxState.State<Category>>,
        ) : Dialog
    }

    @Immutable
    data class State(
        val dialog: Dialog? = null,
        val selection: PersistentList<Manga> = persistentListOf(),
        val selectionMode: Boolean = false,
    )
}
