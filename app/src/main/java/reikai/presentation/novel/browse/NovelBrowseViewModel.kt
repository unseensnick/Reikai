package reikai.presentation.novel.browse

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import eu.kanade.core.preference.asState
import eu.kanade.domain.source.service.SourcePreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mihon.core.viewmodel.StateViewModel
import reikai.domain.entry.EntryId
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelWithChapterCount
import reikai.domain.source.ReikaiSourcePreferences
import reikai.novel.host.NovelItem
import reikai.novel.install.LnPluginInstaller
import reikai.novel.source.NovelSource
import reikai.novel.source.NovelSourceManager
import reikai.presentation.browse.components.EntrySourceLabel
import reikai.presentation.migrate.flow.MigrationPickHandoff
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.domain.category.model.Category
import uy.kohesive.injekt.injectLazy

/**
 * Per-source light-novel browse state holder. The source is pre-picked (the Browse Sources tab is the
 * picker), so this jumps straight to a catalog and mirrors the manga browse's listing model: a
 * Popular / Latest toggle plus a filters draft and a search query, paged manually (lnreader plugins
 * return a bare page list with no `hasNextPage`, so an empty page marks the end). The screen is a pure
 * renderer over [NovelBrowseState].
 */
class NovelBrowseViewModel(
    private val sourceId: String,
    private val initialQuery: String = "",
) : StateViewModel<NovelBrowseState>(NovelBrowseState()) {

    private val installer: LnPluginInstaller by injectLazy()
    private val manager: NovelSourceManager by injectLazy()
    private val novelRepository: NovelRepository by injectLazy()
    private val libraryAdder: NovelLibraryAdder by injectLazy()

    // RK: where a migration-target pick is left for the screen that asked for it.
    private val pickHandoff: MigrationPickHandoff by injectLazy()
    private val reikaiSourcePreferences: ReikaiSourcePreferences by injectLazy()
    private val sourcePreferences: SourcePreferences by injectLazy()

    /** Compose-observable display mode (comfortable / compact / list), persisted via [ReikaiSourcePreferences]. */
    var displayMode by reikaiSourcePreferences.novelBrowseDisplayMode.asState(viewModelScope)

    // Same preference and same load-time snapshot as manga browse (BrowseSourceViewModel).
    private val hideInLibraryItems = sourcePreferences.hideInLibraryItems.get()

    /** Drop already-favorited results when Hide-entries-already-in-library is on, against the live
     *  favorited-key set (the same check the in-library badge uses). */
    private fun List<NovelItem>.dropInLibrary(): List<NovelItem> {
        if (!hideInLibraryItems) return this
        val keys = state.value.favoritedKeys
        return filterNot { (sourceId to it.path) in keys }
    }

    init {
        // In-library marking: favorited (source, url) keys so results already saved are dimmed +
        // badged like the manga catalogue. Read-only; nothing written back.
        viewModelScope.launchIO {
            novelRepository.getFavoritedKeysAsFlow().collectLatest { keys ->
                mutableState.update { it.copy(favoritedKeys = keys) }
            }
        }
        viewModelScope.launchIO {
            try {
                installer.ensureLoaded()
            } catch (_: Throwable) {}
            val source = manager.get(sourceId)
            if (source == null) {
                mutableState.update { it.copy(error = "Source not installed: $sourceId") }
                return@launchIO
            }
            mutableState.update {
                it.copy(source = source, filterValues = defaultFilterValues(source.filters))
            }
            // Opened from global search with a query: jump straight to those results; else the listing.
            if (initialQuery.isNotBlank()) search(initialQuery) else fetchFirstPage(source)
        }
    }

    /** Switch the Popular / Latest listing, clearing any active search, and refetch from page 1. */
    fun setListing(listing: NovelBrowseState.Listing) {
        val source = state.value.source ?: return
        if (state.value.loading) return
        mutableState.update { it.copy(listing = listing, query = "") }
        fetchFirstPage(source)
    }

    /** Run a search; a blank query falls back to the current Popular / Latest listing. */
    fun search(query: String) {
        val source = state.value.source ?: return
        if (state.value.loading) return
        if (query.isBlank()) {
            mutableState.update { it.copy(query = "") }
            fetchFirstPage(source)
            return
        }
        mutableState.update { it.copy(loading = true, error = null, query = query) }
        viewModelScope.launchIO {
            runFetch(error = { e -> mutableState.update { it.copy(loading = false, error = errorText(e)) } }) {
                val novels = source.searchNovels(query, 1)
                val more = hasMore(novels, 1) { p -> source.searchNovels(query, p) }
                mutableState.update {
                    it.copy(loading = false, novels = novels.dropInLibrary(), page = 1, endReached = !more)
                }
                continuePastHiddenFirstPage()
            }
        }
    }

    /** Re-fetch the current Popular / Latest listing with the filter draft applied. */
    fun applyFilters() {
        if (state.value.query.isNotBlank()) {
            mutableState.update { it.copy(query = "") }
        }
        state.value.source?.let { fetchFirstPage(it) }
    }

    fun setFilterValue(key: String, value: JsonElement) =
        mutableState.update { it.copy(filterValues = it.filterValues + (key to value)) }

    fun resetFilters() =
        mutableState.update { it.copy(filterValues = defaultFilterValues(it.source?.filters)) }

    fun openFilterSheet() = mutableState.update { it.copy(filterSheetOpen = true) }
    fun closeFilterSheet() = mutableState.update { it.copy(filterSheetOpen = false) }
    fun openSettingsSheet() = mutableState.update { it.copy(settingsSheetOpen = true) }
    fun closeSettingsSheet() = mutableState.update { it.copy(settingsSheetOpen = false) }

    // --- Favorite from browse (long-press), via the shared [NovelLibraryAdder] ---

    fun onLongClickItem(item: NovelItem) {
        viewModelScope.launchIO {
            val dialog = libraryAdder.onLongClick(item, sourceId, state.value.favoritedKeys)
            mutableState.update { it.copy(dialog = dialog) }
        }
    }

    // RK -->

    /**
     * Report [item] back as the migration target for the novel with id [entryRawId], then run
     * [onPicked] (which pops back to the migration screen waiting for it).
     *
     * The row is stored first, unfavorited, because a migration target has to be something the
     * library can point at; the migrate step does the rest.
     */
    fun pickAsMigrationTarget(item: NovelItem, entryRawId: Long, onPicked: () -> Unit) {
        viewModelScope.launchIO {
            val stored = libraryAdder.materialize(item, sourceId)
            if (stored != null) {
                pickHandoff.offer(EntryId.Novel(entryRawId), stored.id)
            }
            withUIContext { onPicked() }
        }
    }
    // RK <--

    /** "Add anyway" from the duplicates dialog: add despite the similarly-named entries. */
    fun addFromDuplicate(item: NovelItem) {
        viewModelScope.launchIO {
            mutableState.update { it.copy(dialog = libraryAdder.addToLibrary(item, sourceId)) }
        }
    }

    /** Materialize the browsed result as a target row, then raise the migrate dialog on it. The
     *  materialize is a source round trip, so it runs here rather than in a composable's own scope. */
    fun startMigrate(duplicateId: Long, item: NovelItem) {
        viewModelScope.launchIO {
            val target = libraryAdder.materialize(item, sourceId) ?: return@launchIO
            mutableState.update {
                it.copy(dialog = NovelBrowseDialog.Migrate(currentId = duplicateId, targetId = target.id))
            }
        }
    }

    /** "Add to existing group": add, then merge it with the duplicates the user picked. */
    fun addToExistingGroup(item: NovelItem, selectedIds: List<Long>) {
        viewModelScope.launchIO {
            val dialog = libraryAdder.addToExistingGroup(item, sourceId, selectedIds)
            mutableState.update { it.copy(dialog = dialog) }
        }
    }

    fun applyCategories(target: NovelCategoryTarget, categoryIds: List<Long>) {
        viewModelScope.launchIO {
            libraryAdder.confirmCategories(target, categoryIds)
            mutableState.update { it.copy(dialog = null) }
        }
    }

    fun confirmRemove(item: NovelItem) {
        viewModelScope.launchIO {
            libraryAdder.confirmRemove(item, sourceId)
            mutableState.update { it.copy(dialog = null) }
        }
    }

    fun dismissDialog() = mutableState.update { it.copy(dialog = null) }

    /** Re-run the current listing (popular/latest) or search after an error. */
    fun retry() {
        val source = state.value.source ?: run {
            // Source never resolved: re-attempt the whole init path.
            viewModelScope.launchIO {
                try {
                    installer.ensureLoaded()
                } catch (_: Throwable) {}
                manager.get(sourceId)?.let { s ->
                    mutableState.update {
                        it.copy(source = s, error = null, filterValues = defaultFilterValues(s.filters))
                    }
                    fetchFirstPage(s)
                }
            }
            return
        }
        if (state.value.query.isBlank()) fetchFirstPage(source) else search(state.value.query)
    }

    // Cached result of an eager next-page probe (see [hasMore]), reused by the matching [loadMore].
    private data class ProbeEntry(val page: Int, val novels: List<NovelItem>, val at: Long)
    private var probe: ProbeEntry? = null

    /**
     * Whether more pages likely follow [fetched] (page [page]). lnreader plugins report no
     * hasNextPage, so a full page assumes more without a network hit; a short page is confirmed by
     * eagerly probing the next page, whose result is cached so the matching [loadMore] reuses it
     * instead of re-fetching. A failed probe stays optimistic, so a transient error doesn't wrongly
     * end the list. Ported from tsundoku's inferHasNextPage.
     */
    private suspend fun hasMore(
        fetched: List<NovelItem>,
        page: Int,
        fetchPage: suspend (Int) -> List<NovelItem>,
    ): Boolean {
        probe = null
        if (fetched.isEmpty()) return false
        if (fetched.size >= PAGE_SIZE) return true
        val next = page + 1
        val probed = try {
            fetchPage(next)
        } catch (_: Throwable) {
            return true
        }
        if (probed.isEmpty()) return false
        probe = ProbeEntry(next, probed, System.currentTimeMillis())
        return true
    }

    /** Fetch and append the next page of the active listing. An empty page exhausts it; an error leaves
     *  the page retryable (the error snackbar offers a retry, and scrolling re-triggers it) rather than
     *  killing pagination for good, and never wipes the results already shown. */
    fun loadMore() {
        val source = state.value.source ?: return
        val current = state.value
        if (current.loading || current.loadingMore || current.endReached) return
        mutableState.update { it.copy(loadingMore = true) }
        viewModelScope.launchIO {
            try {
                val fetchPage: suspend (Int) -> List<NovelItem> = if (current.query.isBlank()) {
                    { p ->
                        source.popularNovels(p, buildOptions(source.filters, current.filterValues, current.showLatest))
                    }
                } else {
                    { p -> source.searchNovels(current.query, p) }
                }
                // Loop: a page that hide-in-library filters down to nothing must not stall paging (the
                // scroll trigger only re-arms when the visible list changes), so keep fetching until
                // something visible lands or the catalog ends. One pass when nothing is hidden.
                var next = current.page + 1
                while (true) {
                    // Reuse the eager probe fetched for this page, if still fresh, instead of re-fetching.
                    val cached = probe
                        ?.takeIf { it.page == next && System.currentTimeMillis() - it.at < PROBE_TTL_MS }
                        ?.novels
                    val more = cached ?: fetchPage(next)
                    if (more.isEmpty()) {
                        probe = null
                        mutableState.update { it.copy(loadingMore = false, endReached = true) }
                        break
                    }
                    val end = !hasMore(more, next, fetchPage)
                    var appended = false
                    mutableState.update {
                        // Dedupe by path so a source repeating entries across a page boundary doesn't
                        // produce duplicate LazyGrid keys.
                        val seen = it.novels.mapTo(HashSet()) { n -> n.path }
                        val fresh = more.filter { n -> seen.add(n.path) }.dropInLibrary()
                        appended = fresh.isNotEmpty()
                        it.copy(
                            loadingMore = !(appended || end),
                            novels = it.novels + fresh,
                            page = next,
                            endReached = end,
                        )
                    }
                    if (appended || end) break
                    next++
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Don't latch endReached on a transient error: a single network hiccup mid-scroll must
                // not permanently kill paging. Keep the page retryable and surface the error instead.
                mutableState.update { it.copy(loadingMore = false, error = errorText(e)) }
            }
        }
    }

    private fun fetchFirstPage(source: NovelSource) {
        mutableState.update { it.copy(loading = true, error = null) }
        viewModelScope.launchIO {
            runFetch(error = { e -> mutableState.update { it.copy(loading = false, error = errorText(e)) } }) {
                val opts = buildOptions(source.filters, state.value.filterValues, state.value.showLatest)
                val novels = source.popularNovels(1, opts)
                val more = hasMore(novels, 1) { p -> source.popularNovels(p, opts) }
                mutableState.update {
                    it.copy(loading = false, novels = novels.dropInLibrary(), page = 1, endReached = !more)
                }
                continuePastHiddenFirstPage()
            }
        }
    }

    /** A first page that hide-in-library filtered down to nothing leaves the grid empty with more
     *  pages available, and the scroll trigger never fires on an empty grid; hand off to [loadMore],
     *  whose loop keeps paging until something visible lands or the catalog ends. */
    private fun continuePastHiddenFirstPage() {
        if (state.value.novels.isEmpty() && !state.value.endReached) loadMore()
    }

    private inline fun runFetch(error: (Throwable) -> Unit, block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            error(e)
        }
    }

    private fun errorText(e: Throwable) = "${e.javaClass.simpleName}: ${e.message ?: ""}"

    companion object {
        // A novel source id is the plugin's String id, not the Long the manga side uses, so the keys
        // are Reikai's own rather than the upstream browse model's.
        val SOURCE_ID_KEY = CreationExtras.Key<String>()
        val INITIAL_QUERY_KEY = CreationExtras.Key<String>()

        val Factory = viewModelFactory {
            initializer {
                NovelBrowseViewModel(
                    sourceId = get(SOURCE_ID_KEY)!!,
                    initialQuery = get(INITIAL_QUERY_KEY).orEmpty(),
                )
            }
        }

        // lnreader plugins don't report hasNextPage, so a full page (this many items or more) is taken
        // as "more may follow"; a shorter page is confirmed by probing the next one. 20 is the common
        // lnreader page size.
        private const val PAGE_SIZE = 20

        // How long a cached eager-probe stays usable before loadMore re-fetches instead, since the
        // source's listing may have shifted in the meantime.
        private const val PROBE_TTL_MS = 60_000L
    }
}

/**
 * Per-source browse state. The source is always pre-picked; [novels] is the current listing
 * (popular/latest or search results). [filterValues] is the filter-sheet draft, seeded from the
 * plugin's declared defaults and applied on demand.
 */
data class NovelBrowseState(
    val source: NovelSource? = null,
    val listing: Listing = Listing.Popular,
    val novels: List<NovelItem> = emptyList(),
    /** Empty for the popular/latest listing, non-empty when a search is active. */
    val query: String = "",
    val filterValues: Map<String, JsonElement> = emptyMap(),
    /** Highest page fetched so far; [NovelBrowseViewModel.loadMore] requests page+1. */
    val page: Int = 1,
    val endReached: Boolean = false,
    val loading: Boolean = false,
    /** Next-page fetch in flight (footer spinner), distinct from the first-page [loading]. */
    val loadingMore: Boolean = false,
    val error: String? = null,
    /** (source, url) pairs in the library, for in-library marking of results. */
    val favoritedKeys: Set<Pair<String, String>> = emptySet(),
    val filterSheetOpen: Boolean = false,
    val settingsSheetOpen: Boolean = false,
    /** Active long-press dialog (add-duplicate / category picker / remove), or null. */
    val dialog: NovelBrowseDialog? = null,
) {
    val showLatest: Boolean get() = listing == Listing.Latest

    /** The filter draft differs from the source's declared defaults, i.e. a filter is applied.
     *  Drives the Filter chip's active highlight, mirroring manga's `listing is Listing.Search`
     *  (novels fold filters into the Popular/Latest listing, so there is no Search listing to test). */
    val hasActiveFilters: Boolean
        get() = source?.let { filterValues != defaultFilterValues(it.filters) } ?: false

    enum class Listing { Popular, Latest }
}

/** Long-press dialogs for the novel browse grid, the novel twin of `BrowseSourceViewModel.Dialog`. */
sealed interface NovelBrowseDialog {
    data class AddDuplicate(
        val item: NovelItem,
        /** The source the result came from, so the confirm acts on the right one (varies in global search). */
        val sourceId: String,
        val duplicates: List<NovelWithChapterCount>,
        /** Source id -> its label for each duplicate (resolved in the model, so the dialog is DI-free). */
        val sourceLabels: Map<String, EntrySourceLabel>,
        /** Source id -> site, for the cover's Referer; null when the source didn't resolve. */
        val sourceSites: Map<String, String?>,
        /** Whether to offer add-time grouping (the same-title suggestion pref plus the master switch). */
        val suggestGroup: Boolean,
        /** Novel id -> group id, so same-group duplicates collapse into one card. */
        val groupIdByNovelId: Map<Long, Long>,
    ) : NovelBrowseDialog
    data class ChangeCategory(
        val target: NovelCategoryTarget,
        val allCategories: List<Category>,
        val currentCategoryIds: Set<Long>,
    ) : NovelBrowseDialog
    data class RemoveNovel(val item: NovelItem, val sourceId: String) : NovelBrowseDialog

    /** Migrating the library's copy onto the one just browsed to, both already stored by id. Replaces
     *  [AddDuplicate] in the same slot, as the manga twin does. */
    data class Migrate(val currentId: Long, val targetId: Long) : NovelBrowseDialog
}

/**
 * What a category picker's confirm has left to write. A browse add reaches the picker before anything
 * is written, so backing out of it adds nothing and confirming owes the whole add; add-time grouping
 * favorites up front by design and owes only the filing.
 */
sealed interface NovelCategoryTarget {
    data class Stored(val novelId: Long) : NovelCategoryTarget
    data class Pending(val item: NovelItem, val sourceId: String) : NovelCategoryTarget
}

/**
 * Current value for each filter, seeded from the plugin's declared `value`. Drives the filter sheet's
 * initial state and the options sent to [NovelSource.popularNovels] before the user touches anything.
 */
internal fun defaultFilterValues(filters: JsonObject?): Map<String, JsonElement> {
    if (filters == null) return emptyMap()
    return buildMap {
        filters.forEach { (key, schema) ->
            if (schema is JsonObject) schema["value"]?.let { put(key, it) }
        }
    }
}

/**
 * Builds a `popularNovels` options JSON from the plugin's filter schema and the user's current
 * [values]. Each filter is wrapped as `{key: {value: <current-or-default>}}` inside the top-level
 * `filters` object so the plugin body can read `options.filters.X.value`. [showLatest] maps to
 * lnreader's `showLatestNovels`. Sources without filters get `{filters: {}}` plus the toggle.
 */
internal fun buildOptions(
    filters: JsonObject?,
    values: Map<String, JsonElement>,
    showLatest: Boolean,
): String {
    val opts = buildJsonObject {
        put(
            "filters",
            buildJsonObject {
                filters?.forEach { (key, schema) ->
                    if (schema is JsonObject) {
                        val value = values[key] ?: schema["value"]
                        if (value != null) put(key, buildJsonObject { put("value", value) })
                    }
                }
            },
        )
        put("showLatestNovels", showLatest)
    }
    return opts.toString()
}
