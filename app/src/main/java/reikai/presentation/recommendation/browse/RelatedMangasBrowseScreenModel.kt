package reikai.presentation.recommendation.browse

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.manga.interactor.UpdateManga
import kotlinx.coroutines.flow.update
import mihon.domain.manga.model.toDomainManga
import reikai.domain.recommendation.BuildRecommendationHideFilter
import reikai.domain.recommendation.RECOMMENDS_SOURCE
import reikai.domain.recommendation.RelatedMangaCache
import reikai.domain.recommendation.RelatedMangaCandidate
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.preference.mapAsCheckboxState
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * "See all" browse grid for the related-mangas carousel. Re-reads the full ranked pool from
 * [RelatedMangaCache] by manga id (never passed through the Voyager constructor, which only takes
 * serializable args) and offers multi-select bulk add-to-library + an origin-grouping toggle.
 *
 * The pool is in-memory only, so after process death the cache is empty and the screen shows an
 * empty state (the user reopens the manga to repopulate it).
 */
class RelatedMangasBrowseScreenModel(
    private val mangaId: Long,
    private val context: Context,
    private val relatedMangaCache: RelatedMangaCache = Injekt.get(),
    private val getFavorites: GetFavorites = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),
    private val setMangaCategories: SetMangaCategories = Injekt.get(),
    private val updateManga: UpdateManga = Injekt.get(),
    private val networkToLocalManga: NetworkToLocalManga = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
    private val buildRecommendationHideFilter: BuildRecommendationHideFilter = Injekt.get(),
) : StateScreenModel<RelatedMangasBrowseScreenModel.State>(State()) {

    val snackbarHostState = SnackbarHostState()

    // Anchor for range selection (the last item toggled), mirroring the library's range-select.
    private var lastSelectedUrl: String? = null

    init {
        screenModelScope.launchIO {
            val favoriteKeys = currentFavoriteKeys()
            val hideFilter = buildRecommendationHideFilter.await()
            // Render live off the cache so a grid opened mid-load (the menu placement opens it before the
            // background load finishes) fills to the full pool as it streams, instead of freezing on the
            // partial snapshot present at open. "See all" is tapped after the carousel, so it starts full.
            relatedMangaCache.observe(mangaId).collect { entry ->
                val pool = entry?.fullPool.orEmpty()
                val items = pool.map {
                    BrowseItem(it, (it.manga.url to it.sourceId) in favoriteKeys, hidden = hideFilter.shouldHide(it))
                }
                mutableState.update {
                    it.copy(items = items, loading = pool.isEmpty() && entry?.isComplete != true)
                }
            }
        }
    }

    fun toggleShowHidden() = mutableState.update { it.copy(showHidden = !it.showHidden) }

    /** Span count from the shared library grid-size prefs, mirroring browse-source. */
    fun getColumns(orientation: Int): GridCells {
        val columns = if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            libraryPreferences.landscapeColumns
        } else {
            libraryPreferences.portraitColumns
        }.get()
        return if (columns == 0) GridCells.Adaptive(128.dp) else GridCells.Fixed(columns)
    }

    fun toggleGrouping() = mutableState.update { it.copy(grouped = !it.grouped) }

    /** Enter selection mode from the toolbar Select button (long-press is the other entry point). */
    fun enterSelectionMode() = mutableState.update { it.copy(selectionMode = true) }

    fun toggleSelection(url: String) = mutableState.update { st ->
        val selection = st.selectedUrls.toMutableSet()
        if (!selection.add(url)) selection.remove(url)
        lastSelectedUrl = url.takeIf { selection.isNotEmpty() }
        st.copy(selectedUrls = selection)
    }

    /** Select every item between the last-toggled anchor and [url] (inclusive), in display order. */
    fun toggleRangeSelection(url: String) = mutableState.update { st ->
        val urls = st.visibleItems().map { it.candidate.manga.url }
        val anchorIndex = lastSelectedUrl?.let(urls::indexOf) ?: -1
        val currentIndex = urls.indexOf(url)
        val selection = st.selectedUrls.toMutableSet()
        if (anchorIndex < 0 || currentIndex < 0) {
            selection.add(url)
        } else {
            val range = if (anchorIndex <= currentIndex) anchorIndex..currentIndex else currentIndex..anchorIndex
            range.forEach { selection.add(urls[it]) }
        }
        lastSelectedUrl = url
        st.copy(selectedUrls = selection, selectionMode = true)
    }

    fun selectAll() = mutableState.update { st ->
        st.copy(selectedUrls = st.visibleItems().mapTo(HashSet()) { it.candidate.manga.url })
    }

    fun clearSelection() = mutableState.update {
        lastSelectedUrl = null
        it.copy(selectedUrls = emptySet(), selectionMode = false)
    }

    fun dismissDialog() = mutableState.update { it.copy(dialog = null) }

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

        screenModelScope.launchIO {
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
            val defaultCategoryId = libraryPreferences.defaultCategory.get()
            val defaultCategory = categories.find { it.id == defaultCategoryId.toLong() }
            when {
                defaultCategory != null -> {
                    applyAdd(resolved, listOf(defaultCategory.id))
                    finishAdd(resolved.size, trackerOrigin.size)
                }
                defaultCategoryId == 0 || categories.isEmpty() -> {
                    applyAdd(resolved, emptyList())
                    finishAdd(resolved.size, trackerOrigin.size)
                }
                else -> mutableState.update {
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
        screenModelScope.launchIO {
            applyAdd(target, include)
            finishAdd(target.size, skipped)
        }
    }

    private suspend fun applyAdd(mangas: List<Manga>, categoryIds: List<Long>) {
        mangas.forEach { manga ->
            updateManga.awaitUpdateFavorite(manga.id, true)
            setMangaCategories.await(manga.id, categoryIds)
        }
    }

    private suspend fun finishAdd(added: Int, skipped: Int) {
        val favoriteKeys = currentFavoriteKeys()
        mutableState.update { st ->
            st.copy(
                items = st.items.map {
                    it.copy(
                        inLibrary =
                        (it.candidate.manga.url to it.candidate.sourceId) in favoriteKeys,
                    )
                },
                selectedUrls = emptySet(),
                selectionMode = false,
                dialog = null,
            )
        }
        val message = if (skipped > 0) {
            context.stringResource(MR.strings.bulk_added_with_skipped, added, skipped)
        } else {
            context.stringResource(MR.strings.bulk_added_to_library, added)
        }
        snackbarHostState.showSnackbar(message)
    }

    private suspend fun currentFavoriteKeys(): Set<Pair<String, Long>> =
        getFavorites.await().mapTo(HashSet()) { it.url to it.source }

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
        // RK: explicit so the toolbar Select button can enter selection with nothing selected yet
        val selectionMode: Boolean = false,
    ) {
        val hasHidden: Boolean get() = items.any { it.hidden }

        /** Grouping only makes sense with more than one origin (else it's a single "From this source"). */
        val hasMultipleOrigins: Boolean get() = items.mapTo(HashSet()) { it.candidate.origin }.size > 1

        /** Items shown given the show-hidden toggle (hidden = already in library / tracked as filtered). */
        fun visibleItems(): List<BrowseItem> = if (showHidden) items else items.filterNot { it.hidden }
    }

    sealed interface Dialog {
        data class ChangeCategory(
            val target: List<Manga>,
            val initialSelection: List<CheckboxState.State<Category>>,
            val skippedTrackerCount: Int,
        ) : Dialog
    }
}
