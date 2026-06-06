package yokai.presentation.novel.browse

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.data.database.models.NovelCategory
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.ui.library.LibraryItem
import eu.kanade.tachiyomi.util.system.launchIO
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonElement
import uy.kohesive.injekt.injectLazy
import yokai.domain.novel.NovelPreferences
import yokai.domain.novel.NovelRepository
import yokai.domain.novel.interactor.GetNovelCategories
import yokai.domain.novel.interactor.SetNovelCategories
import yokai.domain.novel.models.Novel
import yokai.domain.ui.UiPreferences
import yokai.novel.host.NovelItem
import yokai.novel.install.LnPluginInstaller
import yokai.novel.source.NovelSource
import yokai.novel.source.NovelSourceManager

/**
 * Browse state holder. Lifts what used to be a fistful of `remember { mutableStateOf }` in the
 * composable into a single [StateScreenModel], so the screen becomes a pure renderer over
 * [NovelBrowseState] (Compose-port checklist items 2, 4, 6) and DI no longer reaches into the
 * composable (item 3).
 *
 * [initialSourceId] is the pre-picked source from a "Browse -> Light novel sources" tap; when set
 * the model jumps straight to that source's catalog and the picker UI is never shown.
 */
class NovelBrowseScreenModel(
    private val initialSourceId: String?,
) : StateScreenModel<NovelBrowseState>(NovelBrowseState()) {

    private val installer: LnPluginInstaller by injectLazy()
    private val manager: NovelSourceManager by injectLazy()
    private val preferences: PreferencesHelper by injectLazy()
    private val uiPreferences: UiPreferences by injectLazy()
    private val novelPreferences: NovelPreferences by injectLazy()
    private val novelRepository: NovelRepository by injectLazy()
    private val getNovelCategories: GetNovelCategories by injectLazy()
    private val setNovelCategories: SetNovelCategories by injectLazy()

    /** One-shot effects the screen turns into a category sheet / snackbar (it owns the Activity +
     *  SnackbarHost). Buffered so emits from [screenModelScope] never suspend. */
    private val _events = MutableSharedFlow<BrowseEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<BrowseEvent> = _events.asSharedFlow()

    init {
        // Seed synchronously so the first frame isn't an empty picker, then keep following the flow.
        mutableState.update { it.copy(sources = manager.getAll()) }
        screenModelScope.launchIO {
            manager.sources.collectLatest { list ->
                mutableState.update { it.copy(sources = list) }
            }
        }
        // In-library marking: keep a set of favorited (source, url) keys so browse results already in
        // the library are dimmed/badged, matching the manga catalogue. Read-only; no rows written.
        screenModelScope.launchIO {
            novelRepository.getLibraryNovelAsFlow().collectLatest { library ->
                val keys = library.mapTo(HashSet()) { favoriteKey(it.novel.source, it.novel.url) }
                mutableState.update { it.copy(favoritedKeys = keys) }
            }
        }
        // Browse display: a browse-only list/grid toggle, but grid sizing + density borrowed from the
        // manga catalogue prefs (gridSize, libraryLayout, outlineOnCovers) so the LN browse matches
        // the manga browse and stays independent of the Novels library layout. Read here, exposed as
        // state (Compose-port item 5).
        screenModelScope.launchIO {
            combine(
                novelPreferences.novelBrowseAsList().changes(),
                preferences.libraryLayout().changes(),
                preferences.gridSize().changes(),
                uiPreferences.outlineOnCovers().changes(),
            ) { asList, layout, gridSize, outline ->
                // libraryLayout may be LAYOUT_LIST; the browse grid only needs a grid density, so
                // fall back to comfortable when the manga library is itself in list mode.
                val gridLayout = if (layout == LibraryItem.LAYOUT_LIST) LibraryItem.LAYOUT_COMFORTABLE_GRID else layout
                NovelBrowseState.Display(asList, gridLayout, gridSize, outline)
            }.collectLatest { display -> mutableState.update { it.copy(display = display) } }
        }
        screenModelScope.launchIO {
            try { installer.ensureLoaded() } catch (_: Throwable) {}
            val id = initialSourceId ?: return@launchIO
            manager.get(id)?.let { pickSource(it) }
        }
    }

    /** Flip the browse-only list/grid toggle. Independent of the Novels library layout. */
    fun toggleListGrid() {
        val pref = novelPreferences.novelBrowseAsList()
        pref.set(!pref.get())
    }

    fun pickSource(source: NovelSource) {
        if (state.value.loading) return
        val defaults = defaultFilterValues(source.filters)
        mutableState.update { it.copy(loading = true, error = null, lastAttemptedSource = source) }
        screenModelScope.launchIO {
            try {
                val novels = source.popularNovels(1, buildOptions(source.filters, defaults, false))
                mutableState.update {
                    it.copy(
                        loading = false,
                        mode = NovelBrowseState.Mode.BrowsingNovels(
                            source = source,
                            novels = novels,
                            filterValues = defaults,
                        ),
                    )
                }
            } catch (e: Throwable) {
                mutableState.update { it.copy(loading = false, error = errorText(e)) }
            }
        }
    }

    fun applyFilters() {
        val mode = state.value.mode as? NovelBrowseState.Mode.BrowsingNovels ?: return
        if (state.value.loading) return
        mutableState.update { it.copy(loading = true, error = null) }
        screenModelScope.launchIO {
            try {
                val novels = mode.source.popularNovels(
                    1,
                    buildOptions(mode.source.filters, mode.filterValues, mode.showLatest),
                )
                updateBrowsing { it.copy(novels = novels, query = "", page = 1, endReached = false) }
            } catch (e: Throwable) {
                mutableState.update { it.copy(loading = false, error = errorText(e)) }
            }
        }
    }

    fun runSearch(query: String) {
        val mode = state.value.mode as? NovelBrowseState.Mode.BrowsingNovels ?: return
        if (state.value.loading) return
        mutableState.update { it.copy(loading = true, error = null) }
        screenModelScope.launchIO {
            try {
                val novels = if (query.isBlank()) {
                    mode.source.popularNovels(1, buildOptions(mode.source.filters, mode.filterValues, mode.showLatest))
                } else {
                    mode.source.searchNovels(query, 1)
                }
                updateBrowsing { it.copy(novels = novels, query = query, page = 1, endReached = false) }
            } catch (e: Throwable) {
                mutableState.update { it.copy(loading = false, error = errorText(e)) }
            }
        }
    }

    /** Fetch and append the next page of the current listing (popular or search). An empty page marks
     *  the listing exhausted. Failures stop pagination for this listing (a fresh search/filter resets
     *  it) rather than wiping the results already shown. */
    fun loadMore() {
        val mode = state.value.mode as? NovelBrowseState.Mode.BrowsingNovels ?: return
        if (state.value.loading || state.value.loadingMore || mode.endReached) return
        val next = mode.page + 1
        mutableState.update { it.copy(loadingMore = true) }
        screenModelScope.launchIO {
            try {
                val more = if (mode.query.isBlank()) {
                    mode.source.popularNovels(next, buildOptions(mode.source.filters, mode.filterValues, mode.showLatest))
                } else {
                    mode.source.searchNovels(mode.query, next)
                }
                mutableState.update {
                    val m = it.mode as? NovelBrowseState.Mode.BrowsingNovels ?: return@update it.copy(loadingMore = false)
                    if (more.isEmpty()) {
                        it.copy(loadingMore = false, mode = m.copy(endReached = true))
                    } else {
                        // Dedupe by path so a source repeating entries across a page boundary doesn't
                        // duplicate keys (LazyGrid keys must be unique).
                        val seen = m.novels.mapTo(HashSet()) { n -> n.path }
                        val appended = m.novels + more.filter { n -> seen.add(n.path) }
                        it.copy(loadingMore = false, mode = m.copy(novels = appended, page = next))
                    }
                }
            } catch (e: Throwable) {
                mutableState.update {
                    val m = it.mode as? NovelBrowseState.Mode.BrowsingNovels ?: return@update it.copy(loadingMore = false)
                    it.copy(loadingMore = false, error = errorText(e), mode = m.copy(endReached = true))
                }
            }
        }
    }

    fun setFilterValue(key: String, value: JsonElement) =
        updateBrowsing { it.copy(filterValues = it.filterValues + (key to value)) }

    fun setShowLatest(show: Boolean) = updateBrowsing { it.copy(showLatest = show) }

    fun resetFilters() =
        updateBrowsing { it.copy(filterValues = defaultFilterValues(it.source.filters), showLatest = false) }

    fun goBackToPicker() = mutableState.update { it.copy(mode = NovelBrowseState.Mode.PickingSource, error = null) }

    /** Re-attempt the last fetch after an error: re-pick the source (early pick failure), or re-run
     *  the current listing (popular or search). */
    fun retry() {
        when (val mode = state.value.mode) {
            is NovelBrowseState.Mode.PickingSource -> state.value.lastAttemptedSource?.let { pickSource(it) }
            is NovelBrowseState.Mode.BrowsingNovels ->
                if (mode.query.isBlank()) applyFilters() else runSearch(mode.query)
        }
    }

    /**
     * Long-press a result: add it to the library if not already in (favorite + default-category
     * routing), otherwise remove it with an undo. The in-library badge updates reactively via the
     * library flow, so nothing is pushed back to the UI beyond the snackbar / sheet event.
     */
    fun onLongPress(item: NovelItem) {
        val mode = state.value.mode as? NovelBrowseState.Mode.BrowsingNovels ?: return
        val sourceId = mode.source.id
        screenModelScope.launchIO {
            val existing = novelRepository.getByUrlAndSource(item.path, sourceId)
            if (existing?.favorite == true) {
                novelRepository.update(existing.copy(favorite = false))
                _events.tryEmit(BrowseEvent.RemovedFromLibrary(existing))
                return@launchIO
            }
            // Reuse a pre-existing (e.g. previously-opened) row, else insert a lightweight shadow;
            // details/refresh enriches it later. insertOrGet funnels concurrent inserts to one row.
            val row = (existing ?: novelRepository.insertOrGet(shadowNovel(item, sourceId))) ?: return@launchIO
            val favorited = row.copy(
                favorite = true,
                dateAdded = if (row.dateAdded == 0L) System.currentTimeMillis() else row.dateAdded,
            )
            novelRepository.update(favorited)
            routeDefaultCategory(favorited)
        }
    }

    /** Re-favorite after an undo. */
    fun undoRemove(novel: Novel) = screenModelScope.launchIO {
        novelRepository.update(novel.copy(favorite = true))
    }

    /** Assign categories per the Default-category pref (-2 last used, -1 ask, 0 Default, >0 specific),
     *  then signal the screen to show the sheet (ask) or an "added" snackbar (auto-filed). */
    private suspend fun routeDefaultCategory(novel: Novel) {
        val id = novel.id ?: return
        when (val default = novelPreferences.novelDefaultCategory().get()) {
            DEFAULT_CATEGORY_ASK -> _events.tryEmit(BrowseEvent.ShowCategorySheet(novel))
            DEFAULT_CATEGORY_DEFAULT -> {
                setNovelCategories.await(id, emptyList())
                _events.tryEmit(BrowseEvent.AddedToLibrary(novel))
            }
            DEFAULT_CATEGORY_LAST_USED -> {
                val existingIds = getNovelCategories.await().mapNotNull { it.id }.toSet()
                val ids = NovelCategory.lastCategoriesAddedTo.filter { it in existingIds }.map { it.toLong() }
                setNovelCategories.await(id, ids)
                _events.tryEmit(BrowseEvent.AddedToLibrary(novel))
            }
            else -> {
                setNovelCategories.await(id, listOf(default.toLong()))
                _events.tryEmit(BrowseEvent.AddedToLibrary(novel))
            }
        }
    }

    private fun shadowNovel(item: NovelItem, sourceId: String) = Novel(
        id = null,
        source = sourceId,
        url = item.path,
        title = item.name,
        author = null,
        artist = null,
        description = null,
        genres = null,
        status = 0,
        thumbnailUrl = item.cover,
        favorite = false,
        lastUpdate = 0L,
        initialized = false,
        chapterFlags = 0,
        dateAdded = 0L,
        updateStrategy = 0,
        coverLastModified = 0L,
    )

    /** Apply [block] to the BrowsingNovels mode (clearing the loading flag), or no-op otherwise. */
    private inline fun updateBrowsing(block: (NovelBrowseState.Mode.BrowsingNovels) -> NovelBrowseState.Mode.BrowsingNovels) {
        mutableState.update {
            val mode = it.mode as? NovelBrowseState.Mode.BrowsingNovels ?: return@update it
            it.copy(loading = false, mode = block(mode))
        }
    }

    private fun errorText(e: Throwable) = "${e.javaClass.simpleName}: ${e.message ?: ""}"

    companion object {
        // novelDefaultCategory sentinels, mirroring the manga defaultCategory pref.
        const val DEFAULT_CATEGORY_LAST_USED = -2
        const val DEFAULT_CATEGORY_ASK = -1
        const val DEFAULT_CATEGORY_DEFAULT = 0
    }
}

/** One-shot effects from [NovelBrowseScreenModel] that need the Activity / SnackbarHost the screen owns. */
sealed interface BrowseEvent {
    /** Default category is "always ask": show the category sheet for [novel] (already favorited). */
    data class ShowCategorySheet(val novel: Novel) : BrowseEvent

    /** [novel] was added + auto-filed; show an "added" snackbar with a Change action. */
    data class AddedToLibrary(val novel: Novel) : BrowseEvent

    /** [novel] was removed; show a "removed" snackbar with an Undo action. */
    data class RemovedFromLibrary(val novel: Novel) : BrowseEvent
}

/** Stable key for the in-library lookup, matching a novel row's (source, url) identity. */
internal fun favoriteKey(source: String, url: String): Pair<String, String> = source to url

/**
 * Whole-screen browse state. [Mode] is the in-screen stack: the source picker, or a source's
 * catalog. Cross-cutting flags ([loading], [error]) sit at the top level since an early
 * pick-source failure leaves the screen in [Mode.PickingSource] but still needs to surface an error.
 */
data class NovelBrowseState(
    val sources: List<NovelSource> = emptyList(),
    val mode: Mode = Mode.PickingSource,
    val loading: Boolean = false,
    /** Next-page fetch in flight (footer spinner), distinct from the first-page [loading]. */
    val loadingMore: Boolean = false,
    val error: String? = null,
    /** Source of the most recent pick attempt; drives the CF/WebView affordance even when a
     *  failure left us in [Mode.PickingSource] with no mode-side source reference. */
    val lastAttemptedSource: NovelSource? = null,
    val display: Display = Display(),
    /** (source, url) pairs currently in the library, for in-library marking of results. */
    val favoritedKeys: Set<Pair<String, String>> = emptySet(),
) {
    /**
     * Browse display. [asList] is the browse-only list/grid toggle; [gridLayout] (comfortable /
     * compact / cover-only), [gridSize], and [outlineOnCovers] borrow the manga catalogue prefs so
     * the LN browse grid matches the manga browse.
     */
    data class Display(
        val asList: Boolean = false,
        val gridLayout: Int = LibraryItem.LAYOUT_COMFORTABLE_GRID,
        val gridSize: Float = 1f,
        val outlineOnCovers: Boolean = true,
    )

    sealed interface Mode {
        data object PickingSource : Mode

        /**
         * Browsing a source's listing. [query] is empty for popularNovels results, non-empty when
         * the search bar is active. [filterValues] + [showLatest] are the current (possibly
         * unapplied) filter-sheet draft, seeded from the plugin's declared defaults on pick.
         */
        data class BrowsingNovels(
            val source: NovelSource,
            val novels: List<NovelItem>,
            val query: String = "",
            val filterValues: Map<String, JsonElement> = emptyMap(),
            val showLatest: Boolean = false,
            /** Highest page fetched so far; [loadMore] requests page+1. */
            val page: Int = 1,
            /** Set once a page comes back empty (or pagination errors): no more pages to fetch. */
            val endReached: Boolean = false,
        ) : Mode
    }
}
