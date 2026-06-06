package yokai.presentation.novel.browse

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.ui.library.LibraryItem
import eu.kanade.tachiyomi.util.system.launchIO
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonElement
import uy.kohesive.injekt.injectLazy
import yokai.domain.novel.NovelPreferences
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

    init {
        // Seed synchronously so the first frame isn't an empty picker, then keep following the flow.
        mutableState.update { it.copy(sources = manager.getAll()) }
        screenModelScope.launchIO {
            manager.sources.collectLatest { list ->
                mutableState.update { it.copy(sources = list) }
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
                updateBrowsing { it.copy(novels = novels, query = "") }
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
                updateBrowsing { it.copy(novels = novels, query = query) }
            } catch (e: Throwable) {
                mutableState.update { it.copy(loading = false, error = errorText(e)) }
            }
        }
    }

    fun setFilterValue(key: String, value: JsonElement) =
        updateBrowsing { it.copy(filterValues = it.filterValues + (key to value)) }

    fun setShowLatest(show: Boolean) = updateBrowsing { it.copy(showLatest = show) }

    fun resetFilters() =
        updateBrowsing { it.copy(filterValues = defaultFilterValues(it.source.filters), showLatest = false) }

    fun goBackToPicker() = mutableState.update { it.copy(mode = NovelBrowseState.Mode.PickingSource, error = null) }

    /** Apply [block] to the BrowsingNovels mode (clearing the loading flag), or no-op otherwise. */
    private inline fun updateBrowsing(block: (NovelBrowseState.Mode.BrowsingNovels) -> NovelBrowseState.Mode.BrowsingNovels) {
        mutableState.update {
            val mode = it.mode as? NovelBrowseState.Mode.BrowsingNovels ?: return@update it
            it.copy(loading = false, mode = block(mode))
        }
    }

    private fun errorText(e: Throwable) = "${e.javaClass.simpleName}: ${e.message ?: ""}"
}

/**
 * Whole-screen browse state. [Mode] is the in-screen stack: the source picker, or a source's
 * catalog. Cross-cutting flags ([loading], [error]) sit at the top level since an early
 * pick-source failure leaves the screen in [Mode.PickingSource] but still needs to surface an error.
 */
data class NovelBrowseState(
    val sources: List<NovelSource> = emptyList(),
    val mode: Mode = Mode.PickingSource,
    val loading: Boolean = false,
    val error: String? = null,
    /** Source of the most recent pick attempt; drives the CF/WebView affordance even when a
     *  failure left us in [Mode.PickingSource] with no mode-side source reference. */
    val lastAttemptedSource: NovelSource? = null,
    val display: Display = Display(),
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
        ) : Mode
    }
}
