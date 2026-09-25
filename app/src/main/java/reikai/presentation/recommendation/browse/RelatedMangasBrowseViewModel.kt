package reikai.presentation.recommendation.browse

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import eu.kanade.domain.manga.interactor.UpdateManga
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import mihon.domain.manga.model.toDomainManga
import reikai.domain.category.resolveDefaultCategoryIds
import reikai.domain.recommendation.BuildRecommendationHideFilter
import reikai.domain.recommendation.RECOMMENDS_SOURCE
import reikai.domain.recommendation.RelatedMangaCache
import reikai.domain.recommendation.RelatedMangaCandidate
import reikai.presentation.browse.finishAdd
import reikai.presentation.selection.EntrySelection
import reikai.presentation.selection.SelectionState
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.preference.mapAsCheckboxState
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR

/**
 * "See all" browse grid for the related-mangas carousel. Re-reads the full ranked pool from
 * [RelatedMangaCache] by manga id (never passed through the Voyager constructor, which only takes
 * serializable args) and offers multi-select bulk add-to-library + an origin-grouping toggle.
 *
 * The pool is in-memory only, so after process death the cache is empty and the screen shows an
 * empty state (the user reopens the manga to repopulate it).
 */
@AssistedInject
class RelatedMangasBrowseViewModel(
    @Assisted private val mangaId: Long,
    // An Application, not the composition's Activity: a ViewModel outlives the Activity and would pin
    // it. Only used for two string lookups, and app locale is set app-wide, so the strings are the same.
    private val context: Context,
    private val relatedMangaCache: RelatedMangaCache,
    private val getFavorites: GetFavorites,
    private val getCategories: GetCategories,
    private val setMangaCategories: SetMangaCategories,
    private val updateManga: UpdateManga,
    private val networkToLocalManga: NetworkToLocalManga,
    private val libraryPreferences: LibraryPreferences,
    private val buildRecommendationHideFilter: BuildRecommendationHideFilter,
) : ViewModel() {

    val state: StateFlow<RelatedMangasBrowseViewModel.State>
        field = MutableStateFlow<RelatedMangasBrowseViewModel.State>(State())

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(mangaId: Long): RelatedMangasBrowseViewModel
    }

    val snackbarHostState = SnackbarHostState()

    // Selection plus its range anchor; `state.selectedUrls` mirrors the set for the grid.
    private var selectionState = SelectionState<String>()

    init {
        viewModelScope.launchIO {
            val hideFilter = buildRecommendationHideFilter.await()
            // Live off the cache, so a grid opened mid-load (the menu placement opens it before the load
            // finishes) fills as the pool streams, and off the library, so a title added here or anywhere
            // else stays marked through every later emission.
            val pool = relatedMangaCache.observe(mangaId)
            val library = pool.map { entry -> entry?.fullPool.orEmpty().librarySourceIds() }
                .distinctUntilChanged()
                .flatMapLatest(::libraryKeys)
            combine(pool, library) { entry, libraryKeys ->
                Triple(entry?.fullPool.orEmpty(), entry?.isComplete, libraryKeys)
            }.collect { (candidates, isComplete, libraryKeys) ->
                val items = candidates.map {
                    BrowseItem(it, (it.manga.url to it.sourceId) in libraryKeys, hidden = hideFilter.shouldHide(it))
                }
                // No entry is a finished, empty pool: a source with no related list, or one lost with the process.
                state.update { it.copy(items = items, loading = isComplete == false && candidates.isEmpty()) }
            }
        }
    }

    private fun List<RelatedMangaCandidate>.librarySourceIds() =
        map { it.sourceId }.filter { it != RECOMMENDS_SOURCE }.toSortedSet()

    private fun libraryKeys(sourceIds: Set<Long>): Flow<Set<Pair<String, Long>>> =
        if (sourceIds.isEmpty()) {
            flowOf(emptySet())
        } else {
            combine(sourceIds.map { getFavorites.subscribe(it) }) { perSource ->
                perSource.flatMap { favorites -> favorites.map { it.url to it.source } }.toSet()
            }
        }

    fun toggleShowHidden() = state.update { it.copy(showHidden = !it.showHidden) }

    /** Span count from the shared library grid-size prefs, mirroring browse-source. */
    fun getColumns(orientation: Int): GridCells {
        val columns = if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            libraryPreferences.landscapeColumns
        } else {
            libraryPreferences.portraitColumns
        }.get()
        return if (columns == 0) GridCells.Adaptive(128.dp) else GridCells.Fixed(columns)
    }

    fun toggleGrouping() = state.update { it.copy(grouped = !it.grouped) }

    /** Enter selection mode from the toolbar Select button (long-press is the other entry point). */
    fun enterSelectionMode() = state.update { it.copy(selectionMode = true) }

    fun toggleSelection(url: String) = applySelection(EntrySelection.toggle(selectionState, url))

    /** Select every item between the last-toggled anchor and [url] (inclusive), in display order. */
    fun toggleRangeSelection(url: String) {
        applySelection(EntrySelection.rangeOrToggle(selectionState, url, visibleUrls()))
        state.update { it.copy(selectionMode = true) }
    }

    fun selectAll() = applySelection(EntrySelection.selectAll(selectionState, visibleUrls()))

    fun clearSelection() {
        applySelection(EntrySelection.clear())
        state.update { it.copy(selectionMode = false) }
    }

    // The one writer of the selection, outside `update`: its block re-runs when a concurrent write
    // wins, and a toggle run twice undoes itself.
    private fun applySelection(next: SelectionState<String>) {
        selectionState = next
        state.update { it.copy(selectedUrls = next.selection) }
    }

    private fun visibleUrls() = state.value.visibleItems().map { it.candidate.manga.url }

    fun dismissDialog() = state.update { it.copy(dialog = null) }

    /** Resolve a tapped candidate to a local manga id to open, or null for a tracker-origin card
     *  (whose URL belongs to no installed source) so the caller can route it through global search. */
    suspend fun resolveToLocalId(candidate: RelatedMangaCandidate): Long? {
        if (candidate.sourceId == RECOMMENDS_SOURCE) return null
        return networkToLocalManga(candidate.manga.toDomainManga(candidate.sourceId)).id
    }

    fun addSelectedToLibrary() {
        val current = state.value
        val selected = current.items
            .filter { it.candidate.manga.url in current.selectedUrls }
            .map { it.candidate }
        if (selected.isEmpty()) return

        viewModelScope.launchIO {
            // Tracker-origin candidates resolve to no installed source, so they can't be favorited.
            val (trackerOrigin, addable) = selected.partition { it.sourceId == RECOMMENDS_SOURCE }
            val resolved = addable
                .map { networkToLocalManga(it.manga.toDomainManga(it.sourceId)) }
                .filterNot { it.favorite }
            if (resolved.isEmpty()) {
                finishAdd(added = 0, skipped = trackerOrigin.size)
                return@launchIO
            }

            val categories = getCategories.await().filterNot { it.isSystemCategory }
            val directIds = resolveDefaultCategoryIds(categories, libraryPreferences.defaultCategory.get())
            if (directIds != null) {
                applyAdd(resolved, directIds)
                finishAdd(resolved.size, trackerOrigin.size)
            } else {
                state.update {
                    // Freshly-added manga have no categories yet, so every checkbox starts unchecked.
                    it.copy(
                        dialog = Dialog.ChangeCategory(
                            resolved,
                            categories.mapAsCheckboxState {
                                false
                            },
                            trackerOrigin.size,
                        ),
                    )
                }
            }
        }
    }

    fun confirmCategories(target: List<Manga>, include: List<Long>, skipped: Int) {
        viewModelScope.launchIO {
            applyAdd(target, include)
            finishAdd(target.size, skipped)
        }
    }

    private suspend fun applyAdd(mangas: List<Manga>, categoryIds: List<Long>) {
        mangas.forEach { manga ->
            // The shared order per entry: one failing favorite write skips that entry's categories
            // rather than filing them against a row outside the library, and the rest still add.
            finishAdd(
                categoryIds = categoryIds,
                favorite = { manga.id.takeIf { updateManga.awaitUpdateFavorite(manga.id, true) } },
                fileCategories = { id, ids -> setMangaCategories.await(id, ids) },
            )
        }
    }

    private suspend fun finishAdd(added: Int, skipped: Int) {
        // On the main thread, where every other selection write happens.
        withUIContext { clearSelection() }
        state.update { it.copy(dialog = null) }
        val message = if (skipped > 0) {
            context.stringResource(MR.strings.bulk_added_with_skipped, added, skipped)
        } else {
            context.stringResource(MR.strings.bulk_added_to_library, added)
        }
        snackbarHostState.showSnackbar(message)
    }

    data class BrowseItem(
        val candidate: RelatedMangaCandidate,
        val inLibrary: Boolean,
        val hidden: Boolean = false,
    )

    data class State(
        val items: List<BrowseItem> = emptyList(),
        val selectedUrls: Set<String> = emptySet(),
        val grouped: Boolean = false,
        val showHidden: Boolean = false,
        val dialog: Dialog? = null,
        val loading: Boolean = true,
        // Explicit so the toolbar Select button can enter selection with nothing selected yet
        val selectionMode: Boolean = false,
    ) {
        val hasHidden: Boolean get() = items.any { it.hidden }

        /** Grouping only makes sense with more than one origin (else it's a single "From this source"). */
        val hasMultipleOrigins: Boolean get() = items.mapTo(HashSet()) { it.candidate.origin }.size > 1

        /** Items shown given the show-hidden toggle (hidden = already in library / tracked as filtered). */
        fun visibleItems(): List<BrowseItem> = if (showHidden) items else items.filterNot { it.hidden }

        /** What the grid shows, derived once so the empty state and the grid read the same list. */
        val content: Content
            get() {
                if (loading) return Content.Loading
                val visible = visibleItems()
                if (visible.isNotEmpty()) return Content.Items(visible)
                return Content.Empty(hiddenCount = items.count { it.hidden })
            }
    }

    sealed interface Content {
        data object Loading : Content
        data class Empty(val hiddenCount: Int) : Content
        data class Items(val items: List<BrowseItem>) : Content
    }

    sealed interface Dialog {
        data class ChangeCategory(
            val target: List<Manga>,
            val initialSelection: List<CheckboxState.State<Category>>,
            val skippedTrackerCount: Int,
        ) : Dialog
    }
}
