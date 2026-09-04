package reikai.presentation.library

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import eu.kanade.core.preference.PreferenceMutableState
import eu.kanade.core.preference.asState
import eu.kanade.presentation.manga.DownloadAction
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.ui.library.LibraryItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import reikai.domain.category.CategoryContentType
import reikai.domain.category.categoryDiff
import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType
import reikai.domain.library.LibrarySortFields
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.library.librarySortComparator
import reikai.domain.library.toSortMode
import reikai.presentation.selection.EntrySelection
import reikai.presentation.selection.SelectionState
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.i18n.MR
import kotlin.time.Duration.Companion.seconds

/**
 * Orchestrates the library over its per-type [LibraryProvider]s: owns the selection, dispatches the
 * bulk actions, and decides which provider drives a view. The selection lives here because a combined
 * list holds both types and a range-select can span them, which neither model can compute; entries are
 * keyed by [EntryId] for the same reason, a manga and a novel being able to share a raw row id.
 * [providersFor] answers with every provider whose rows belong in a view, both under [ContentType.ALL];
 * [behaviorFor] still fails loudly there, since one behaviour cannot answer for two content types.
 */
@AssistedInject
class LibraryEngine(
    // Assisted: each provider wraps a ViewModel the screen has already resolved, so the list can only
    // be built at the call site.
    @Assisted private val providers: List<LibraryProvider>,
    // Constructor parameters rather than lazy lookups, so a test can drive the assembly. Selection is
    // not pure maths any more: it is pruned to what the assembly kept, and a rule about the assembly
    // can only be pinned by a test that can actually run one.
    private val reikaiLibraryPreferences: ReikaiLibraryPreferences,
    private val libraryPreferences: LibraryPreferences,
    private val categoryRepository: CategoryRepository,
    // Only the dynamic-grouping assembly needs these: the group labels and the track-status ordering.
    private val context: Context,
    private val trackerManager: TrackerManager,
) : ViewModel() {

    /**
     * Only the first [viewModel] call for a given store builds the engine; later calls return that
     * instance and ignore this factory. That is what keeps exactly one adapter pair alive, so do not
     * "fix" it into something that expects to run per composition.
     */
    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(providers: List<LibraryProvider>): LibraryEngine
    }

    // The flows below are `by lazy` so constructing the engine touches no coroutine scope: only the
    // view ever reads a preference-backed flow, and a selection test that never renders should not
    // start one.

    /**
     * The Manga / Novels chip. It lives here rather than on either provider because it decides which
     * provider drives the view, which is the engine's job and not one content type's.
     */
    val contentType: StateFlow<ContentType> by lazy {
        reikaiLibraryPreferences.libraryContentType.changes()
            .stateIn(viewModelScope, SharingStarted.Eagerly, reikaiLibraryPreferences.libraryContentType.get())
    }

    /**
     * The library-wide display config, hoisted off the manga model because it is one setting for the
     * whole library rather than one per content type.
     */
    val display: StateFlow<LibraryDisplayState> by lazy {
        combine(
            reikaiLibraryPreferences.libraryStateFlow(),
            libraryPreferences.categoryTabs.changes(),
            libraryPreferences.categoryNumberOfItems.changes(),
        ) { reikai, showCategoryTabs, showItemCounts ->
            LibraryDisplayState(reikai, showCategoryTabs, showItemCounts)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, LibraryDisplayState())
    }

    /**
     * The assembled list the tab renders: the providers' row flows concatenated per the chip, then either
     * bucketed into real categories by [assembleLibrary] or into dynamic groups by
     * [assembleDynamicGroups], whichever the one library-wide grouping preference asks for. Null only
     * before the first emission, where the tab falls back to the active provider's own list.
     * `by lazy` like every preference-backed member: eager resolution breaks direct construction in tests.
     */
    val assembled: StateFlow<LibraryAssembled?> by lazy {
        // Two halves, because kotlin's typed `combine` caps at five inputs. AssemblyPrefs is built in the
        // outer transform where both are in scope; a partial one from either half would be a trap.
        val prefsFlow = combine(
            combine(
                libraryPreferences.sortingMode.changes(),
                libraryPreferences.randomSortSeed.changes(),
                reikaiLibraryPreferences.showHiddenCategories.changes(),
                reikaiLibraryPreferences.categorySortOrder.changes(),
                libraryPreferences.categoryNumberOfItems.changes(),
                ::ListPrefs,
            ),
            combine(
                reikaiLibraryPreferences.groupLibraryBy.changes(),
                reikaiLibraryPreferences.collapsedDynamicCategories.changes(),
                reikaiLibraryPreferences.collapsedDynamicAtBottom.changes(),
                ::GroupPrefs,
            ),
        ) { list, group ->
            AssemblyPrefs(
                sort = list.sort,
                seed = list.seed.toLong(),
                showHidden = list.showHidden,
                categorySortOrder = list.categorySortOrder,
                showCounts = list.showCounts,
                groupBy = group.groupBy,
                collapsedDynamic = group.collapsedDynamic,
                collapsedDynamicAtBottom = group.collapsedDynamicAtBottom,
            )
        }
        combine(
            contentType,
            combine(providers.map { it.rows }) { it.toList() },
            // Only what the assembly consumes from the provider states: the query (the
            // search-forces-counts rule) and the overlay key (the per-item display read applies the
            // custom-info overlay, so an edit must re-emit the assembled list to repaint). Taking
            // the whole states re-ran the full bucket-and-sort on every state tick, page swipes and
            // loading flags included.
            combine(
                providers.map { p -> p.state.map { it.searchQuery to it.overlayKey }.distinctUntilChanged() },
            ) { it.toList() },
            categoryRepository.getUnfilteredAsFlow(),
            prefsFlow,
        ) { chip, rowsPerProvider, queryAndOverlay, allCategories, prefs ->
            // Piggybacked here rather than a collector of its own so it runs exactly when the list
            // changes: a selected entry the assembly dropped must leave the selection too, or the
            // toolbar count promises more than the verbs will touch. Pruned after the assembly, not
            // before it, because a filter is not the only thing that can drop an entry: a hidden
            // category, an emptied bucket and a lagging dynamic group all do, and those leave the
            // entry in the providers' rows, where the verbs would still find and act on it.
            assembleFor(chip, rowsPerProvider, queryAndOverlay.map { it.first }, allCategories, prefs)
                .also { pruneSelection(it.presentIds) }
        }
            // The transform sorts and buckets the whole library; keep it off the main thread.
            .flowOn(Dispatchers.Default)
            // Shared while subscribed, because this is what holds both providers' feeds open: eager here
            // means the models query for as long as the app runs. The chip and the display config below
            // stay eager instead, on the rule the recents engine states: a value a verb reads
            // synchronously is not shared while subscribed. `randomEntry` reads this one, and it holds
            // because a share that has emitted keeps its last value after the window closes.
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5.seconds), null)
    }

    private suspend fun assembleFor(
        chip: ContentType,
        rowsPerProvider: List<List<LibraryItem>>,
        searchQueryPerProvider: List<String?>,
        allCategories: List<Category>,
        prefs: AssemblyPrefs,
    ): LibraryAssembled {
        val active = providersFor(chip)
        val rows = providers.indices
            .filter { providers[it] in active }
            .flatMap { rowsPerProvider[it] }
        val searchActive = active.any {
            !searchQueryPerProvider[providers.indexOf(it)].isNullOrEmpty()
        }
        // Lazy per provider, so only a view actually sorting by tracker score pays the computation.
        val means = active.associate { it.contentType to lazy(it::trackerMeans) }
        val fields = mixedLibraryItemSortFields { item ->
            means[item.entryId.contentType]?.value?.get(item.id) ?: -1.0
        }
        val assembledList = if (prefs.groupBy == LibraryGroup.BY_DEFAULT) {
            val categories = allCategories.filter { category ->
                when (chip) {
                    ContentType.MANGA -> category.contentType != CategoryContentType.NOVEL
                    ContentType.NOVELS -> category.contentType != CategoryContentType.MANGA
                    ContentType.ALL -> true
                }
            }
            val inputs = LibraryAssemblyInputs(
                globalSort = prefs.sort,
                randomSeed = prefs.seed,
                showHiddenCategories = prefs.showHidden,
                categorySortOrder = prefs.categorySortOrder,
            )
            assembleLibrary(rows, categories, inputs, fields)
        } else {
            assembleDynamicGroups(active, rows, prefs, fields)
        }
        val itemsByKey = assembledList.associate { it.first.key to it.second }
        val byType = providers.associateBy { it.contentType }
        val showCounts = prefs.showCounts || searchActive
        return LibraryAssembled(
            chip = chip,
            buckets = assembledList.map { it.first },
            presentIds = presentIdsOf(assembledList),
            items = { bucket ->
                itemsByKey[bucket.key].orEmpty().map { item ->
                    byType[item.entryId.contentType]?.overlaid(item) ?: item
                }
            },
            counts = { bucket -> if (showCounts) itemsByKey[bucket.key]?.size else null },
        )
    }

    /**
     * Dynamic grouping over the union: concatenate the active providers' feeds and run the shared kernel
     * ONCE, so a tag or tracking status shared by a manga and a novel lands in one bucket. The feeds are
     * EntryId-keyed and the two id spaces are disjoint, so plain list and map concatenation is safe.
     * Two deliberate divergences from the category path, both matching the per-type builders: hidden
     * categories are not consulted (a synthetic group cannot be hidden), and the category-sort-order
     * preference goes to the kernel, which orders the groups itself.
     */
    private suspend fun assembleDynamicGroups(
        active: List<LibraryProvider>,
        rows: List<LibraryItem>,
        prefs: AssemblyPrefs,
        fields: LibrarySortFields<LibraryItem>,
    ): List<Pair<LibraryBucket, List<LibraryItem>>> {
        val feeds = active.map { it.dynamicGroupingFeed(prefs.groupBy) }
        val grouped = LibraryDynamicGrouping.build(
            items = feeds.flatMap { it.items },
            groupType = prefs.groupBy,
            collapsedDynamicCategories = prefs.collapsedDynamic,
            collapsedDynamicAtBottom = prefs.collapsedDynamicAtBottom,
            unknownLabel = context.stringResource(MR.strings.unknown),
            notTrackedLabel = context.stringResource(MR.strings.not_tracked),
            ungroupedLabel = context.stringResource(MR.strings.group_ungrouped),
            categorySortOrder = prefs.categorySortOrder,
            sourceMeta = feeds.fold(emptyMap()) { acc, feed -> acc + feed.sourceMeta },
            trackStatuses = feeds.fold(emptyMap()) { acc, feed -> acc + feed.trackStatuses },
            languageCodes = feeds.fold(emptyMap()) { acc, feed -> acc + feed.languageCodes },
            statusNames = feeds.fold(emptyMap()) { acc, feed -> acc + feed.statusNames },
            languageDisplay = ::displayLanguage,
            // Built from every logged-in tracker, not one type's: ranking by one side's trackers would
            // drop the other's statuses to the fallback rank. The kernel only calls it for track status.
            trackingStatusOrder = if (prefs.groupBy == LibraryGroup.BY_TRACK_STATUS) {
                LibraryTrackingStatusOrder.build(trackerManager.loggedInTrackers()) { context.stringResource(it) }
            } else {
                { it }
            },
        )
        // Dynamic groups carry no per-category override, so every bucket sorts by the global sort.
        val comparator = librarySortComparator(
            prefs.sort.type.toSortMode(),
            prefs.sort.isAscending,
            prefs.seed,
            fields,
        )
        // The feed reads a state snapshot that can lag the rows emission by a tick, so an id with no row
        // is dropped and the next emission reconciles. A bucket left empty then hides, as everywhere else.
        val rowsByEntryId = rows.associateBy { it.entryId }
        return grouped.mapNotNull { (bucket, ids) ->
            val items = ids.mapNotNull { rowsByEntryId[it] }
            if (items.isEmpty()) null else bucket to items.sortedWith(comparator)
        }
    }

    private data class ListPrefs(
        val sort: LibrarySort,
        val seed: Int,
        val showHidden: Boolean,
        val categorySortOrder: Int,
        val showCounts: Boolean,
    )

    private data class GroupPrefs(
        val groupBy: Int,
        val collapsedDynamic: Set<String>,
        val collapsedDynamicAtBottom: Boolean,
    )

    private data class AssemblyPrefs(
        val sort: LibrarySort,
        val seed: Long,
        val showHidden: Boolean,
        val categorySortOrder: Int,
        val showCounts: Boolean,
        val groupBy: Int,
        val collapsedDynamic: Set<String>,
        val collapsedDynamicAtBottom: Boolean,
    )

    /** Grid shape, the other half of the display config. Both are library-wide, not per content type. */
    fun displayMode(): PreferenceMutableState<LibraryDisplayMode> =
        libraryPreferences.displayMode.asState(viewModelScope)

    fun columnsForOrientation(isLandscape: Boolean): PreferenceMutableState<Int> =
        (if (isLandscape) libraryPreferences.landscapeColumns else libraryPreferences.portraitColumns)
            .asState(viewModelScope)

    private val mutableSelection = MutableStateFlow<Set<EntryId>>(emptySet())
    val selection: StateFlow<Set<EntryId>> = mutableSelection.asStateFlow()

    /**
     * Drop selected ids the assembly no longer holds. What the assembly excluded is gone from this
     * view until a setting changes; what navigation hides is not pruned, because a collapsed
     * category and an off-screen pager page are one gesture from being back and their entries are
     * still the library's.
     */
    private fun pruneSelection(present: Set<EntryId>) {
        mutableSelection.update { selection ->
            if (selection.isEmpty()) return@update selection
            val pruned = selection.filterTo(HashSet()) { it in present }
            if (pruned.size == selection.size) selection else pruned
        }
    }

    private val mutableDialog = MutableStateFlow<LibraryDialog?>(null)
    val dialog: StateFlow<LibraryDialog?> = mutableDialog.asStateFlow()

    /** Anchor for range-select; not reactive, it only decides how the next long-press behaves. */
    private var lastSelectionBucket: String? = null

    /** Every provider contributing rows to a [contentType] view. Both of them for [ContentType.ALL]. */
    fun providersFor(contentType: ContentType): List<LibraryProvider> =
        providers.filter { contentType == ContentType.ALL || it.contentType == contentType }

    /** Of these providers, the ones that actually own an entry in [entries]. */
    private fun List<LibraryProvider>.owning(entries: Set<EntryId>): List<LibraryProvider> =
        filter { provider -> entries.any { it.contentType == provider.contentType } }

    /** The behaviour driving a [contentType] view. */
    fun behaviorFor(contentType: ContentType): LibraryBehavior =
        providersFor(contentType).singleOrNull()
            ?: error("A mixed $contentType library needs a behaviour combining both providers' state")

    /**
     * Search every provider in view. Fanning out keeps both models' queries in sync under All, so the
     * assembled rows from each side are filtered by the same text.
     */
    fun search(contentType: ContentType, query: String?) {
        providersFor(contentType).forEach { it.search(query) }
    }

    /** The active category page, fanned out so each model's own coercion stays consistent. */
    fun updateActiveCategoryIndex(contentType: ContentType, index: Int) {
        providersFor(contentType).forEach { it.updateActiveCategoryIndex(index) }
        // Persisted per chip: the three pagers index different category lists, so one shared key
        // meant a swipe under All rewrote the Manga chip's restore point with a foreign index.
        when (contentType) {
            ContentType.MANGA -> libraryPreferences.lastUsedCategory.set(index)
            ContentType.NOVELS -> reikaiLibraryPreferences.lastUsedNovelCategory.set(index)
            ContentType.ALL -> reikaiLibraryPreferences.lastUsedAllCategory.set(index)
        }
    }

    /** The restore page for a chip's pager, read once at pager construction. */
    fun initialPageFor(contentType: ContentType): Int = when (contentType) {
        ContentType.MANGA -> libraryPreferences.lastUsedCategory.get()
        ContentType.NOVELS -> reikaiLibraryPreferences.lastUsedNovelCategory.get()
        ContentType.ALL -> reikaiLibraryPreferences.lastUsedAllCategory.get()
    }

    /** The one library-wide global sort (chip-free since the sort preferences unified). */
    val globalSort: StateFlow<LibrarySort> by lazy {
        libraryPreferences.sortingMode.changes()
            .stateIn(viewModelScope, SharingStarted.Eagerly, libraryPreferences.sortingMode.get())
    }

    /**
     * The settings sheet a [contentType] describes. Since the filter unification every axis writes a
     * library-wide preference, so the All description is the manga binding (the axis superset: novels
     * only omit the debug interval axis) with the remaining per-type member answered for a mixed
     * view: a union category list, re-sorted by the category-sort-order preference.
     */
    fun settingsFor(contentType: ContentType): LibrarySettingsBinding =
        providersFor(contentType).singleOrNull()?.settings ?: allSettings

    private val allSettings: LibrarySettingsBinding by lazy {
        val manga = providersFor(ContentType.MANGA).single()
        val novel = providersFor(ContentType.NOVELS).single()
        manga.settings.copy(
            // Re-apply the category-sort-order pref after the union: both inputs arrive pref-sorted,
            // but the order-column re-sort (needed to interleave the two lists) discards it, which
            // left the All sheet in manual order while the other chips honoured A-Z / Z-A.
            categories = combine(
                manga.settings.categories,
                novel.settings.categories,
                reikaiLibraryPreferences.categorySortOrder.changes(),
            ) { m, n, sortOrder ->
                reikaiSortCategories((m + n).distinctBy { it.id }.sortedBy { it.order }, sortOrder)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptyList()),
        )
    }

    // Category collapse, library-wide rather than per provider: a collapsed category is one row in one
    // list, and a dynamic group is one bucket that will hold both content types once the chips are only
    // filters, so the collapse belongs to the row and not to whichever chip is filtering the view. Both
    // sets are persisted, and the tab reads them back through [display].

    fun toggleDefaultCategoryCollapse(headerKey: String) {
        reikaiLibraryPreferences.toggleCategoryCollapsed(headerKey)
    }

    fun toggleDynamicCategoryCollapse(headerKey: String) {
        reikaiLibraryPreferences.toggleDynamicCategoryCollapsed(headerKey)
    }

    fun toggleAllCategoriesCollapsed(buckets: List<LibraryBucket>) {
        reikaiLibraryPreferences.toggleAllCategoriesCollapsed(buckets)
    }

    /**
     * Switch the chip. The selection is dropped because it is shared across content types, so keeping it
     * would carry rows into a view that does not list them, leaving a count on the action bar and actions
     * that hit nothing. Revisit when the All chip lands: All -> Manga could keep the manga part.
     */
    fun setContentType(type: ContentType) {
        clearSelection()
        reikaiLibraryPreferences.libraryContentType.set(type)
    }

    // Per-type verbs the view dispatches for whatever the chip is showing.

    /** Start a library update, returning true when any provider started one. Under All, one
     *  side's update already running must not report the other's fresh start as already-running. */
    fun refresh(contentType: ContentType, category: Category?): Boolean =
        providersFor(contentType).map { it.refresh(category) }.any { it }

    /**
     * A random entry from [bucketKey], or the whole library when null, drawn from the assembled list.
     * Reading the assembly rather than the providers means the pick is from what is actually on screen,
     * so a dynamic group resolves and rows in hidden or emptied categories are excluded.
     *
     * Null when nothing is pickable: no assembly yet, a chip flip the assembly has not caught up with
     * (it lags by one emission), or a key that is not in the current list. The caller shows a snackbar.
     */
    fun randomEntry(contentType: ContentType, bucketKey: String?): EntryId? {
        val current = assembled.value?.takeIf { it.chip == contentType } ?: return null
        if (bucketKey != null) {
            val bucket = current.buckets.find { it.key == bucketKey } ?: return null
            return current.itemsFor(bucket).randomOrNull()?.entryId
        }
        // Distinct: an entry in several categories appears in several buckets, and without this it
        // would be that many times likelier to come up.
        return current.buckets
            .flatMap { current.itemsFor(it) }
            .distinctBy { it.entryId }
            .randomOrNull()
            ?.entryId
    }

    // Selection. Every op that needs to know what is on screen takes the category's entries in display
    // order, so the engine never has to resolve rows itself and stays free of per-type lookups.

    /** Selection plus its range anchor. `mutableSelection` mirrors the set for the screen to collect. */
    private var selectionState = SelectionState<EntryId>()

    private fun apply(next: SelectionState<EntryId>, bucketKey: String? = null) {
        selectionState = next
        lastSelectionBucket = bucketKey
        mutableSelection.value = next.selection
    }

    fun clearSelection() = apply(EntrySelection.clear())

    fun toggleSelection(bucketKey: String, entry: EntryId) {
        val next = EntrySelection.toggle(selectionState, entry)
        apply(next, bucketKey.takeIf { next.selection.isNotEmpty() })
    }

    /**
     * Select every entry between [entry] and the last selected one, within one bucket. A long press in
     * a different bucket has no usable anchor, because a range that spanned two categories would pick
     * up rows the user never saw between them, so it selects [entry] alone.
     */
    fun toggleRangeSelection(bucketKey: String, entry: EntryId, ordered: List<EntryId>) {
        val from =
            selectionState.takeIf { lastSelectionBucket == bucketKey } ?: SelectionState(selectionState.selection)
        apply(EntrySelection.rangeOrToggle(from, entry, ordered), bucketKey)
    }

    fun selectAll(ordered: List<EntryId>) = apply(EntrySelection.selectAll(selectionState, ordered))

    /** Select every entry in one category, or deselect them when all are already selected. */
    fun selectAllInCategory(ordered: List<EntryId>) {
        val allPicked = ordered.isNotEmpty() && ordered.all { it in selectionState }
        apply(
            if (allPicked) {
                SelectionState(selectionState.selection - ordered.toSet())
            } else {
                EntrySelection.selectAll(selectionState, ordered)
            },
        )
    }

    fun invertSelection(ordered: List<EntryId>) = apply(EntrySelection.invert(selectionState, ordered))

    // Bulk actions. Each is handed to every provider in the view, which narrows it to its own entries,
    // so one call covers a selection spanning both content types.

    fun markReadSelection(contentType: ContentType, read: Boolean) =
        dispatchAndClear(contentType) { it.markReadSelection(selection.value, read) }

    fun performDownloadAction(contentType: ContentType, action: DownloadAction) {
        // Skip providers whose part of the selection cannot download (all-local manga): forwarding
        // anyway would queue downloads for entries the action button was hidden for.
        val selection = selection.value
        providersFor(contentType)
            .filter { it.canDownload(selection) }
            .forEach { it.performDownloadAction(selection, action) }
        clearSelection()
    }

    fun mergeSelection(contentType: ContentType) =
        dispatchAndClear(contentType) { it.mergeSelection(selection.value) }

    fun unmergeSelection(contentType: ContentType) =
        dispatchAndClear(contentType) { it.unmergeSelection(selection.value) }

    // Dialogs. The selection stays until the dialog resolves, and each dialog carries the entries it was
    // built from, because both dialog composables dismiss before they confirm.

    fun dismissDialog() {
        mutableDialog.value = null
    }

    /**
     * A category must be assignable to *every* content type in the selection, so the lists are intersected
     * rather than merged. Nothing validates `content_type` on either join table, so assigning a manga-only
     * category to a novel would write a row that appears in no picker and that no confirm can remove (the
     * exclude list only ever holds ids the picker showed). A single-type selection intersects one list, so
     * this is exactly the per-type list there.
     */
    fun openChangeCategoryDialog(contentType: ContentType) {
        val entries = selection.value
        val targets = providersFor(contentType).owning(entries)
        if (targets.isEmpty()) return
        viewModelScope.launchIO {
            val assignable = targets
                .map { it.assignableCategories() }
                .reduce { acc, next ->
                    val ids = next.mapTo(HashSet()) { it.id }
                    acc.filter { it.id in ids }
                }
            val ordered = reikaiSortCategories(assignable, reikaiLibraryPreferences.categorySortOrder.get())
            val (common, mix) = categoryDiff(targets.flatMap { it.categoryIdsFor(entries) })
            val initialSelection = ordered.map {
                when (it.id) {
                    in common -> CheckboxState.State.Checked(it)
                    in mix -> CheckboxState.TriState.Exclude(it)
                    else -> CheckboxState.State.None(it)
                }
            }
            mutableDialog.value = LibraryDialog.ChangeCategory(entries, initialSelection)
        }
    }

    fun openDeleteDialog(contentType: ContentType) {
        val entries = selection.value
        val targets = providersFor(contentType).owning(entries)
        if (targets.isEmpty()) return
        mutableDialog.value = LibraryDialog.Delete(
            entries = entries,
            groupedSourceCount = targets.sumOf { it.groupedSourceCount(entries) },
            containsLocal = targets.any { it.containsLocal(entries) },
        )
    }

    /** Opens the sheet for the view; [settingsFor] answers every chip since the filter unification. */
    fun openSettingsDialog(contentType: ContentType, categoryId: Long? = null, initialTab: Int = 0) {
        mutableDialog.value = LibraryDialog.Settings(contentType, categoryId, initialTab)
    }

    // Dialog confirms. Dispatched by the entries' own content types rather than the view's, since the
    // dialog outlives neither the selection nor the chip it was opened from.

    fun setCategories(entries: Set<EntryId>, addCategories: List<Long>, removeCategories: List<Long>) {
        val live = stillSelected(entries)
        providers.owning(live).forEach { it.setCategories(live, addCategories, removeCategories) }
    }

    fun deleteEntries(
        entries: Set<EntryId>,
        deleteFromLibrary: Boolean,
        deleteDownloads: Boolean,
        removeGroupedSources: Boolean,
    ) {
        val live = stillSelected(entries)
        providers.owning(live).forEach {
            it.deleteEntries(live, deleteFromLibrary, deleteDownloads, removeGroupedSources)
        }
    }

    /**
     * The entries a confirm may act on: those a dialog was opened with that the selection still holds.
     * A dialog carries the set it was opened with, and the prune cannot reach a captured copy, so an
     * entry the library dropped while the dialog sat open would otherwise still be deleted or refiled.
     */
    private fun stillSelected(entries: Set<EntryId>): Set<EntryId> =
        entries.intersect(selection.value)

    /** Any selected entry is a merge group; drives the bulk Unmerge action. */
    fun selectionContainsMerged(contentType: ContentType): Boolean =
        providersFor(contentType).any { it.containsMerged(selection.value) }

    /** The bulk Download action applies when ANY provider's part of the selection can download,
     *  so a mixed local-manga + novel selection keeps the action; dispatch skips the rest. */
    fun canDownloadSelection(contentType: ContentType): Boolean =
        providersFor(contentType).any { it.canDownload(selection.value) }

    private fun dispatchAndClear(contentType: ContentType, action: (LibraryProvider) -> Unit) {
        providersFor(contentType).forEach(action)
        clearSelection()
    }
}
