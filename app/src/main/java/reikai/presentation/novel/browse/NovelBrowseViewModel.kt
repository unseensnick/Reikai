package reikai.presentation.novel.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.filter
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.domain.source.service.SourcePreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import reikai.domain.entry.EntryId
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.NovelWithChapterCount
import reikai.domain.source.ReikaiSourcePreferences
import reikai.domain.source.SourceKey
import reikai.novel.host.NovelItem
import reikai.novel.install.LnPluginInstaller
import reikai.novel.source.NovelListingPagingSource
import reikai.novel.source.NovelSearchPagingSource
import reikai.novel.source.NovelSource
import reikai.novel.source.NovelSourceManager
import reikai.presentation.browse.catalogue.trackDisplayMode
import reikai.presentation.browse.components.EntrySourceLabel
import reikai.presentation.migrate.flow.MigrationPickHandoff
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibraryDisplayMode

/**
 * Per-source light-novel browse state holder. The source is pre-picked (the Browse Sources tab is the
 * picker), so this jumps straight to a catalog and mirrors the manga browse's listing model: a
 * Popular / Latest toggle plus a filters draft and a search query, paged through Paging 3 over a
 * [reikai.novel.source.BaseNovelPagingSource]. The screen is a pure renderer over [NovelBrowseState]
 * plus the pager flow.
 */
@AssistedInject
class NovelBrowseViewModel(
    @Assisted private val sourceId: String,
    @Assisted private val initialQuery: String,
    @Assisted private val startLatest: Boolean,
    private val installer: LnPluginInstaller,
    private val manager: NovelSourceManager,
    private val novelRepository: NovelRepository,
    private val libraryAdder: NovelLibraryAdder,
    // Where a migration-target pick is left for the screen that asked for it.
    private val pickHandoff: MigrationPickHandoff,
    private val reikaiSourcePreferences: ReikaiSourcePreferences,
    private val sourcePreferences: SourcePreferences,
    private val getIncognitoState: GetIncognitoState,
) : ViewModel() {

    val state: StateFlow<NovelBrowseState>
        field = MutableStateFlow<NovelBrowseState>(NovelBrowseState())

    /** The grid layout (comfortable / compact / list), persisted via [ReikaiSourcePreferences] and
     *  carried in [NovelBrowseState] by [trackDisplayMode], which states why. */
    private val displayModePreference = reikaiSourcePreferences.novelBrowseDisplayMode

    fun setDisplayMode(mode: LibraryDisplayMode) = displayModePreference.set(mode)

    // Same preference and same load-time snapshot as manga browse (BrowseSourceViewModel).
    private val hideInLibraryItems = sourcePreferences.hideInLibraryItems.get()

    /**
     * Flow of Pager flow tied to what the state says is being paged. Rebuilt only when those inputs
     * change, so editing the filter draft refetches nothing until Apply writes it.
     */
    val novelPagerFlowFlow: StateFlow<Flow<PagingData<NovelItem>>> = state
        .map { it.pagerInput }
        .distinctUntilChanged()
        .map { input ->
            if (input == null) {
                emptyFlow()
            } else {
                Pager(PagingConfig(pageSize = PAGE_SIZE)) {
                    if (input.query.isBlank()) {
                        NovelListingPagingSource(input.source, input.optionsJson)
                    } else {
                        NovelSearchPagingSource(input.source, input.query)
                    }
                }.flow
                    // Drop already-favorited results when Hide-entries-already-in-library is on,
                    // against the live favorited-key set (the same check the in-library badge uses).
                    .map { data ->
                        data.filter { !hideInLibraryItems || (sourceId to it.path) !in state.value.favoritedKeys }
                    }
                    .cachedIn(viewModelScope)
            }
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyFlow())

    init {
        // Shared with the manga catalogue, which owes the same invariant.
        displayModePreference.trackDisplayMode(viewModelScope) { mode ->
            state.update { it.copy(displayMode = mode) }
        }

        // In-library marking: favorited (source, url) keys so results already saved are dimmed +
        // badged like the manga catalogue. Read-only; nothing written back.
        viewModelScope.launchIO {
            novelRepository.getFavoritedKeysAsFlow().collectLatest { keys ->
                state.update { it.copy(favoritedKeys = keys) }
            }
        }
        viewModelScope.launchIO { loadSource() }
    }

    /** Resolve the plugin and seed the first listing. Opened from global search with a query, it
     *  jumps straight to those results instead. */
    private suspend fun loadSource() {
        try {
            installer.ensureLoaded()
        } catch (_: Throwable) {}
        val source = manager.get(sourceId)
        if (source == null) {
            state.update { it.copy(sourceError = "Source not installed: $sourceId") }
            return
        }
        // Recorded here rather than at the Sources row, so every route into a catalogue marks it
        // the way manga's does: global search, a details source link and the migration picker too.
        // Incognito is checked globally, since a novel source has no Mihon source id to scope it by.
        if (!getIncognitoState.await(null)) {
            reikaiSourcePreferences.lastUsedSource.set(SourceKey.Novel(sourceId))
        }
        val filterValues = defaultFilterValues(source.filters)
        // Seeded here rather than switched once the screen is up: the Sources row's Latest button
        // would otherwise page Popular first and throw that fetch away.
        val listing = if (startLatest && source.supportsLatest) {
            NovelBrowseState.Listing.Latest
        } else {
            NovelBrowseState.Listing.Popular
        }
        state.update {
            it.copy(
                source = source,
                sourceError = null,
                filterValues = filterValues,
                query = initialQuery,
                listing = listing,
                appliedOptions = buildOptions(
                    source.filters,
                    filterValues,
                    showLatest = listing == NovelBrowseState.Listing.Latest,
                ),
            )
        }
    }

    /** Switch the Popular / Latest listing, clearing any active search, and page from the start. */
    fun setListing(listing: NovelBrowseState.Listing) {
        val source = state.value.source ?: return
        state.update {
            it.copy(
                listing = listing,
                query = "",
                appliedOptions = buildOptions(
                    source.filters,
                    it.filterValues,
                    listing == NovelBrowseState.Listing.Latest,
                ),
                filtersApplied = false,
            )
        }
    }

    /** Run a search; a blank query falls back to the current Popular / Latest listing. */
    fun search(query: String) {
        state.update { it.copy(query = query) }
    }

    /** Re-page the current Popular / Latest listing with the filter draft applied. */
    fun applyFilters() {
        val source = state.value.source ?: return
        state.update {
            it.copy(
                query = "",
                appliedOptions = buildOptions(source.filters, it.filterValues, it.showLatest),
                filtersApplied = true,
            )
        }
    }

    fun setFilterValue(key: String, value: JsonElement) =
        state.update { it.copy(filterValues = it.filterValues + (key to value)) }

    fun resetFilters() =
        state.update { it.copy(filterValues = defaultFilterValues(it.source?.filters)) }

    fun openFilterSheet() = state.update { it.copy(filterSheetOpen = true) }
    fun closeFilterSheet() = state.update { it.copy(filterSheetOpen = false) }
    fun openSettingsSheet() = state.update { it.copy(settingsSheetOpen = true) }
    fun closeSettingsSheet() = state.update { it.copy(settingsSheetOpen = false) }

    /** Re-attempt the plugin resolution that never completed. A fetch that failed is retried through
     *  the pager instead, which owns that error. */
    fun retryLoadSource() {
        if (state.value.source != null) return
        viewModelScope.launchIO { loadSource() }
    }

    // --- Favorite from browse (long-press), via the shared [NovelLibraryAdder] ---

    fun onLongClickItem(item: NovelItem) {
        viewModelScope.launchIO {
            val dialog = libraryAdder.onLongClick(item, sourceId, state.value.favoritedKeys)
            state.update { it.copy(dialog = dialog) }
        }
    }

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

    /** "Add anyway" from the duplicates dialog: add despite the similarly-named entries. */
    fun addFromDuplicate(item: NovelItem) {
        viewModelScope.launchIO {
            state.update { it.copy(dialog = libraryAdder.addToLibrary(item, sourceId)) }
        }
    }

    /** Materialize the browsed result as a target row, then raise the migrate dialog on it. The
     *  materialize is a source round trip, so it runs here rather than in a composable's own scope. */
    fun startMigrate(duplicateId: Long, item: NovelItem) {
        viewModelScope.launchIO {
            val target = libraryAdder.materialize(item, sourceId) ?: return@launchIO
            state.update {
                it.copy(dialog = NovelBrowseDialog.Migrate(currentId = duplicateId, targetId = target.id))
            }
        }
    }

    /** "Add to existing group": add, then merge it with the duplicates the user picked. */
    fun addToExistingGroup(item: NovelItem, selectedIds: List<Long>) {
        viewModelScope.launchIO {
            val dialog = libraryAdder.addToExistingGroup(item, sourceId, selectedIds)
            state.update { it.copy(dialog = dialog) }
        }
    }

    fun applyCategories(target: NovelCategoryTarget, categoryIds: List<Long>) {
        viewModelScope.launchIO {
            libraryAdder.confirmCategories(target, categoryIds)
            state.update { it.copy(dialog = null) }
        }
    }

    fun confirmRemove(item: NovelItem) {
        viewModelScope.launchIO {
            libraryAdder.confirmRemove(item, sourceId)
            state.update { it.copy(dialog = null) }
        }
    }

    fun dismissDialog() = state.update { it.copy(dialog = null) }

    // A novel source id is the plugin's String id, not the Long the manga side uses.
    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(sourceId: String, initialQuery: String, startLatest: Boolean): NovelBrowseViewModel
    }

    companion object {
        // 20 is the common lnreader page size, so this keeps Paging's prefetch aligned with what a
        // plugin actually returns per request.
        private const val PAGE_SIZE = 20
    }
}

/**
 * Per-source browse state. The source is always pre-picked; the results themselves live in the
 * pager rather than here. [filterValues] is the filter-sheet draft, seeded from the plugin's declared
 * defaults and applied on demand.
 */
data class NovelBrowseState(
    val source: NovelSource? = null,
    val listing: Listing = Listing.Popular,
    /** Empty for the popular/latest listing, non-empty when a search is active. */
    val query: String = "",
    val filterValues: Map<String, JsonElement> = emptyMap(),
    /** The filter draft as the pager last received it, written by Apply and by a listing switch. */
    val appliedOptions: String = "",
    /** Whether Apply has run since the last listing switch, which drives the Filter chip.
     *  Deliberately on upstream's terms rather than on whether the values differ from the
     *  defaults: manga lights the chip for any search-shaped listing, so a reset-then-apply keeps
     *  it lit there, and this keeps the two types answering alike. */
    val filtersApplied: Boolean = false,
    /** Set when the plugin itself never resolved, which the pager cannot report on. */
    val sourceError: String? = null,
    /** (source, url) pairs in the library, for in-library marking of results. */
    val favoritedKeys: Set<Pair<String, String>> = emptySet(),
    val filterSheetOpen: Boolean = false,
    val settingsSheetOpen: Boolean = false,
    /** Active long-press dialog (add-duplicate / category picker / remove), or null. */
    val dialog: NovelBrowseDialog? = null,
    /** The grid layout. Held here for the same reason the manga state holds it: the catalogue
     *  screen renders from this flow, so a value it cannot observe never reaches the grid. */
    val displayMode: LibraryDisplayMode = LibraryDisplayMode.default,
) {
    val showLatest: Boolean get() = listing == Listing.Latest

    /** What the pager pages, or null until the plugin resolves. The filter draft is deliberately
     *  absent: only [appliedOptions] reaches the pager, so editing filters refetches nothing. */
    internal val pagerInput: NovelPagerInput?
        get() = source?.let { NovelPagerInput(it, query, appliedOptions) }

    enum class Listing { Popular, Latest }
}

/** The pager's inputs. Equality decides when paging restarts, so a [NovelSource] compares by identity,
 *  which is what we want: it is resolved once per screen. */
internal data class NovelPagerInput(
    val source: NovelSource,
    val query: String,
    val optionsJson: String,
)

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
        val initialSelection: List<CheckboxState.State<Category>>,
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
