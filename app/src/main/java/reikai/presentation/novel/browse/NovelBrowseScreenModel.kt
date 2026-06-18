package reikai.presentation.novel.browse

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.core.preference.asState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.interactor.GetNovelCategories
import reikai.domain.novel.interactor.SetNovelCategories
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelCategory
import reikai.domain.novel.model.NovelWithChapterCount
import reikai.domain.source.ReikaiSourcePreferences
import reikai.novel.host.NovelItem
import reikai.novel.install.LnPluginInstaller
import reikai.novel.source.NovelSource
import reikai.novel.source.NovelSourceManager
import tachiyomi.core.common.util.lang.launchIO
import uy.kohesive.injekt.injectLazy

/**
 * Per-source light-novel browse state holder. The source is pre-picked (the Browse Sources tab is the
 * picker), so this jumps straight to a catalog and mirrors the manga browse's listing model: a
 * Popular / Latest toggle plus a filters draft and a search query, paged manually (lnreader plugins
 * return a bare page list with no `hasNextPage`, so an empty page marks the end). The screen is a pure
 * renderer over [NovelBrowseState].
 */
class NovelBrowseScreenModel(
    private val sourceId: String,
    private val initialQuery: String = "",
) : StateScreenModel<NovelBrowseState>(NovelBrowseState()) {

    private val installer: LnPluginInstaller by injectLazy()
    private val manager: NovelSourceManager by injectLazy()
    private val novelRepository: NovelRepository by injectLazy()
    private val getNovelCategories: GetNovelCategories by injectLazy()
    private val setNovelCategories: SetNovelCategories by injectLazy()
    private val reikaiSourcePreferences: ReikaiSourcePreferences by injectLazy()

    /** Compose-observable display mode (comfortable / compact / list), persisted via [ReikaiSourcePreferences]. */
    var displayMode by reikaiSourcePreferences.novelBrowseDisplayMode.asState(screenModelScope)

    init {
        // In-library marking: favorited (source, url) keys so results already saved are dimmed +
        // badged like the manga catalogue. Read-only; nothing written back.
        screenModelScope.launchIO {
            novelRepository.getFavoritedKeysAsFlow().collectLatest { keys ->
                mutableState.update { it.copy(favoritedKeys = keys) }
            }
        }
        screenModelScope.launchIO {
            try { installer.ensureLoaded() } catch (_: Throwable) {}
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
        screenModelScope.launchIO {
            runFetch(error = { e -> mutableState.update { it.copy(loading = false, error = errorText(e)) } }) {
                val novels = source.searchNovels(query, 1)
                mutableState.update {
                    it.copy(loading = false, novels = novels, page = 1, endReached = novels.isEmpty())
                }
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

    // --- Favorite from browse (long-press) ---

    /** Long-press a result: if it's already in the library offer to remove it; otherwise check for
     *  similarly-named library novels (the manga "possible duplicates" flow) before adding. */
    fun onLongClickItem(item: NovelItem) {
        screenModelScope.launchIO {
            if ((sourceId to item.path) in state.value.favoritedKeys) {
                mutableState.update { it.copy(dialog = NovelBrowseDialog.RemoveNovel(item)) }
                return@launchIO
            }
            // -1: the item isn't favorited yet, so there's no library row to exclude (a non-favorite
            // shadow row is excluded by the query's favorite=1 filter anyway).
            val duplicates = novelRepository.getDuplicateLibraryNovel(-1L, item.name)
            if (duplicates.isNotEmpty()) {
                // Resolve names + sites here (sources are loaded by browse init) so the dialog stays DI-free.
                val resolved = duplicates.associate { it.novel.source to manager.get(it.novel.source) }
                val sourceNames = resolved.mapValues { (id, src) -> src?.name ?: id }
                val sourceSites = resolved.mapValues { (_, src) -> src?.site }
                mutableState.update {
                    it.copy(dialog = NovelBrowseDialog.AddDuplicate(item, duplicates, sourceNames, sourceSites))
                }
            } else {
                addToLibrary(item)
            }
        }
    }

    /** "Add anyway" from the duplicates dialog: add despite the similarly-named entries. */
    fun addFromDuplicate(item: NovelItem) {
        screenModelScope.launchIO { addToLibrary(item) }
    }

    /** Favorite the browse item (persisting a minimal row, full metadata fills on first details
     *  open) and, if the user has categories, open the picker. insertOrGet may return a non-favorite
     *  shadow row from a prior details open, so favorite is applied as a follow-up update. */
    private suspend fun addToLibrary(item: NovelItem) {
        val base = Novel.create().copy(
            source = sourceId,
            url = item.path,
            title = item.name,
            thumbnailUrl = item.cover,
        )
        val stored = novelRepository.insertOrGet(base) ?: return
        if (!stored.favorite) {
            novelRepository.update(stored.copy(favorite = true, dateAdded = System.currentTimeMillis()))
        }
        val categories = getNovelCategories.await().filter { it.id > 0L }
        if (categories.isNotEmpty()) {
            val current = getNovelCategories.awaitByNovelId(stored.id).map { it.id }.toSet()
            mutableState.update { it.copy(dialog = NovelBrowseDialog.ChangeCategory(stored.id, categories, current)) }
        } else {
            mutableState.update { it.copy(dialog = null) }
        }
    }

    fun applyCategories(novelId: Long, categoryIds: List<Long>) {
        screenModelScope.launchIO {
            setNovelCategories.await(novelId, categoryIds)
            mutableState.update { it.copy(dialog = null) }
        }
    }

    /** Remove a favorited result from the library (keeps the row + read state, like the manga side). */
    fun confirmRemove(item: NovelItem) {
        screenModelScope.launchIO {
            novelRepository.getByUrlAndSource(item.path, sourceId)?.let {
                novelRepository.update(it.copy(favorite = false))
            }
            mutableState.update { it.copy(dialog = null) }
        }
    }

    fun dismissDialog() = mutableState.update { it.copy(dialog = null) }

    /** Re-run the current listing (popular/latest) or search after an error. */
    fun retry() {
        val source = state.value.source ?: run {
            // Source never resolved: re-attempt the whole init path.
            screenModelScope.launchIO {
                try { installer.ensureLoaded() } catch (_: Throwable) {}
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

    /** Fetch and append the next page of the active listing. An empty page exhausts it; an error stops
     *  pagination for this listing (a fresh search/filter resets it) without wiping shown results. */
    fun loadMore() {
        val source = state.value.source ?: return
        val current = state.value
        if (current.loading || current.loadingMore || current.endReached) return
        val next = current.page + 1
        mutableState.update { it.copy(loadingMore = true) }
        screenModelScope.launchIO {
            try {
                val more = if (current.query.isBlank()) {
                    source.popularNovels(next, buildOptions(source.filters, current.filterValues, current.showLatest))
                } else {
                    source.searchNovels(current.query, next)
                }
                mutableState.update {
                    if (more.isEmpty()) {
                        it.copy(loadingMore = false, endReached = true)
                    } else {
                        // Dedupe by path so a source repeating entries across a page boundary doesn't
                        // produce duplicate LazyGrid keys.
                        val seen = it.novels.mapTo(HashSet()) { n -> n.path }
                        it.copy(
                            loadingMore = false,
                            novels = it.novels + more.filter { n -> seen.add(n.path) },
                            page = next,
                        )
                    }
                }
            } catch (e: Throwable) {
                mutableState.update { it.copy(loadingMore = false, endReached = true, error = errorText(e)) }
            }
        }
    }

    private fun fetchFirstPage(source: NovelSource) {
        mutableState.update { it.copy(loading = true, error = null) }
        screenModelScope.launchIO {
            runFetch(error = { e -> mutableState.update { it.copy(loading = false, error = errorText(e)) } }) {
                val opts = buildOptions(source.filters, state.value.filterValues, state.value.showLatest)
                val novels = source.popularNovels(1, opts)
                mutableState.update {
                    it.copy(loading = false, novels = novels, page = 1, endReached = novels.isEmpty())
                }
            }
        }
    }

    private inline fun runFetch(error: (Throwable) -> Unit, block: () -> Unit) {
        try { block() } catch (e: Throwable) { error(e) }
    }

    private fun errorText(e: Throwable) = "${e.javaClass.simpleName}: ${e.message ?: ""}"
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
    /** Highest page fetched so far; [NovelBrowseScreenModel.loadMore] requests page+1. */
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

    enum class Listing { Popular, Latest }
}

/** Long-press dialogs for the novel browse grid, the novel twin of `BrowseSourceScreenModel.Dialog`. */
sealed interface NovelBrowseDialog {
    data class AddDuplicate(
        val item: NovelItem,
        val duplicates: List<NovelWithChapterCount>,
        /** Source id -> display name for each duplicate's source (resolved in the model, dialog is DI-free). */
        val sourceNames: Map<String, String>,
        /** Source id -> site, for the cover's Referer; null when the source didn't resolve. */
        val sourceSites: Map<String, String?>,
    ) : NovelBrowseDialog
    data class ChangeCategory(
        val novelId: Long,
        val allCategories: List<NovelCategory>,
        val currentCategoryIds: Set<Long>,
    ) : NovelBrowseDialog
    data class RemoveNovel(val item: NovelItem) : NovelBrowseDialog
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
