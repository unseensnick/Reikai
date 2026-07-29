package reikai.presentation.library

import androidx.compose.ui.util.fastAll
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.presentation.manga.DownloadAction
import eu.kanade.tachiyomi.ui.library.LibraryScreenModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.source.local.isLocal
import uy.kohesive.injekt.injectLazy

/**
 * Adapts the live Mihon [LibraryScreenModel] to the neutral [LibraryBehavior]. The model stays live and
 * upstream-tracked (never made to implement a Reikai interface); this maps its state into the neutral
 * [LibraryScreenState] and forwards each neutral action to the model's own methods. Symmetric with
 * [NovelLibraryAdapter], so one shared library tab drives both content types through this seam.
 */
class MangaLibraryAdapter(
    private val model: LibraryScreenModel,
) : LibraryProvider {

    // Lazy, so constructing the adapter in a composable never touches the DI container.
    private val getCategories: GetCategories by injectLazy()

    override val contentType = ContentType.MANGA

    override val state: StateFlow<LibraryScreenState> =
        model.state
            .map { it.toNeutral() }
            .stateIn(model.screenModelScope, SharingStarted.Eagerly, model.state.value.toNeutral())

    private fun LibraryScreenModel.State.toNeutral() = LibraryScreenState(
        categories = displayedCategories,
        isLoading = isLoading,
        isLibraryEmpty = isLibraryEmpty,
        searchQuery = searchQuery,
        hasActiveFilters = hasActiveFilters,
        collapsedCategories = reikai.collapsedCategories,
        collapsedDynamicCategories = reikai.collapsedDynamicCategories,
        coercedActiveCategoryIndex = coercedActiveCategoryIndex,
        showContinueButton = showMangaContinueButton,
        itemsForCategory = this::getItemsForCategory,
        itemCountForCategory = this::getItemCountForCategory,
    )

    override fun search(query: String?) {
        model.search(query)
    }
    override fun toggleDefaultCategoryCollapse(headerKey: String) {
        model.toggleDefaultCategoryCollapse(headerKey)
    }
    override fun toggleDynamicCategoryCollapse(headerKey: String) {
        model.toggleDynamicCategoryCollapse(headerKey)
    }
    override fun toggleAllCategoriesCollapsed(
        categories: List<Category>,
    ) {
        model.toggleAllCategoriesCollapsed(categories)
    }

    // Each verb takes the neutral selection and hands the model only the raw ids of its own content
    // type, so a mixed selection never reaches a provider that cannot act on it.
    private fun Set<EntryId>.ownIds() = filterIsInstance<EntryId.Manga>().map { it.rawId }

    override fun markReadSelection(entries: Set<EntryId>, read: Boolean) {
        model.markReadSelection(entries.ownIds(), read)
    }
    override fun performDownloadAction(entries: Set<EntryId>, action: DownloadAction) {
        model.performDownloadAction(entries.ownIds(), action)
    }
    override fun mergeSelection(entries: Set<EntryId>) {
        model.mergeSelection(entries.ownIds())
    }
    override fun unmergeSelection(entries: Set<EntryId>) {
        model.unmergeSelection(entries.ownIds())
    }

    // The model expands merge groups itself for categories; delete expands only on request, and wants the
    // manga rather than their ids, so resolving from state here saves it a DB round-trip.
    override fun setCategories(
        entries: Set<EntryId>,
        addCategories: List<Long>,
        removeCategories: List<Long>,
    ) {
        model.setMangaCategories(model.state.value.mangaFor(entries.ownIds()), addCategories, removeCategories)
    }

    override fun deleteEntries(
        entries: Set<EntryId>,
        deleteFromLibrary: Boolean,
        deleteDownloads: Boolean,
        removeGroupedSources: Boolean,
    ) {
        model.removeMangas(
            model.state.value.mangaFor(entries.ownIds()),
            deleteFromLibrary,
            deleteDownloads,
            removeGroupedSources,
        )
    }

    override fun containsMerged(entries: Set<EntryId>) =
        model.state.value.containsMerged(entries.ownIds())
    override fun canDownload(entries: Set<EntryId>) =
        model.state.value.mangaFor(entries.ownIds()).fastAll { !it.isLocal() }

    override fun groupedSourceCount(entries: Set<EntryId>): Int {
        val ids = entries.ownIds()
        val state = model.state.value
        return if (state.containsMerged(ids)) state.memberIdsFor(ids).size else 0
    }

    override fun containsLocal(entries: Set<EntryId>) =
        model.state.value.mangaFor(entries.ownIds()).any { it.isLocal() }

    override suspend fun assignableCategories() =
        getCategories.await().filterNot { it.isSystemCategory }

    override suspend fun categoryIdsFor(entries: Set<EntryId>): List<Set<Long>> =
        model.state.value.memberIdsFor(entries.ownIds())
            .map { id -> getCategories.await(id).mapTo(mutableSetOf()) { it.id } }

    override fun updateActiveCategoryIndex(index: Int) {
        model.updateActiveCategoryIndex(index)
    }
}
