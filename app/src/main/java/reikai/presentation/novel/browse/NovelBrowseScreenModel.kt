package reikai.presentation.novel.browse

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import reikai.domain.novel.NovelRepository
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
) : StateScreenModel<NovelBrowseState>(NovelBrowseState()) {

    private val installer: LnPluginInstaller by injectLazy()
    private val manager: NovelSourceManager by injectLazy()
    private val novelRepository: NovelRepository by injectLazy()

    init {
        // In-library marking: keep a set of favorited (source, url) keys so results already saved are
        // dimmed + badged like the manga catalogue. Read-only; nothing written back. The S6 library
        // view isn't built yet, so derive from the full novel list instead of a library query.
        screenModelScope.launchIO {
            novelRepository.getAllAsFlow().collectLatest { novels ->
                val keys = novels.asSequence()
                    .filter { it.favorite }
                    .mapTo(HashSet()) { it.source to it.url }
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
            fetchFirstPage(source)
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
) {
    val showLatest: Boolean get() = listing == Listing.Latest

    enum class Listing { Popular, Latest }
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
