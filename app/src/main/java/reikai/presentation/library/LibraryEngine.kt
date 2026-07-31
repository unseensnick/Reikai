package reikai.presentation.library

import android.app.Application
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.core.preference.PreferenceMutableState
import eu.kanade.core.preference.asState
import eu.kanade.presentation.manga.DownloadAction
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.ui.library.LibraryItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.i18n.MR
import uy.kohesive.injekt.injectLazy

/**
 * Orchestrates the library over its per-type [LibraryProvider]s: it owns the selection and everything
 * derived from it, dispatches the bulk actions, and decides which provider drives a view, so the tab does
 * none of that itself.
 *
 * The selection lives here rather than in either content type's model because a combined list can hold
 * entries of both types at once, and a range-select can span them, which neither model can compute since
 * neither sees the other's rows. Entries are identified by [EntryId] for the same reason: a manga and a
 * novel can share a raw row id. Each provider narrows a dispatched selection to its own content type, so
 * handing every provider the whole selection is always safe.
 *
 * Shaped for a mixed list: [providersFor] answers with every provider whose rows belong in a view, which
 * is one provider for Manga or Novels and both for [ContentType.ALL]. [behaviorFor] still fails loudly on
 * ALL by design, because a single behaviour cannot answer for two content types; everything a mixed view
 * needs (the assembly, the settings binding, the dialogs) has a real ALL answer instead, and only explicit
 * per-type callers reach [behaviorFor].
 */
class LibraryEngine(private val providers: List<LibraryProvider>) : ScreenModel {

    private val reikaiLibraryPreferences: ReikaiLibraryPreferences by injectLazy()
    private val libraryPreferences: LibraryPreferences by injectLazy()
    private val categoryRepository: CategoryRepository by injectLazy()

    // Only the dynamic-grouping assembly needs these: the group labels and the track-status ordering.
    private val context: Application by injectLazy()
    private val trackerManager: TrackerManager by injectLazy()

    // These two are `by lazy` rather than plain vals so constructing the engine touches neither the DI
    // container nor the coroutine scope. Selection is pure maths and is unit-tested by direct
    // construction; only the view ever reads a preference-backed flow.

    /**
     * The Manga / Novels chip. It lives here rather than on either provider because it decides which
     * provider drives the view, which is the engine's job and not one content type's.
     */
    val contentType: StateFlow<ContentType> by lazy {
        reikaiLibraryPreferences.libraryContentType.changes()
            .stateIn(screenModelScope, SharingStarted.Eagerly, reikaiLibraryPreferences.libraryContentType.get())
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
        }.stateIn(screenModelScope, SharingStarted.Eagerly, LibraryDisplayState())
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
            combine(providers.map { it.state }) { it.toList() },
            categoryRepository.getUnfilteredAsFlow(),
            prefsFlow,
        ) { chip, rowsPerProvider, statesPerProvider, allCategories, prefs ->
            assembleFor(chip, rowsPerProvider, statesPerProvider, allCategories, prefs)
        }.stateIn(screenModelScope, SharingStarted.Eagerly, null)
    }

    private fun assembleFor(
        chip: ContentType,
        rowsPerProvider: List<List<LibraryItem>>,
        statesPerProvider: List<LibraryScreenState>,
        allCategories: List<Category>,
        prefs: AssemblyPrefs,
    ): LibraryAssembled {
        val active = providersFor(chip)
        val rows = providers.indices
            .filter { providers[it] in active }
            .flatMap { rowsPerProvider[it] }
        val searchActive = active.any {
            !statesPerProvider[providers.indexOf(it)].searchQuery.isNullOrEmpty()
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
        val bucketsById = assembledList.associate { it.first.id to it.second }
        val byType = providers.associateBy { it.contentType }
        val showCounts = prefs.showCounts || searchActive
        return LibraryAssembled(
            chip = chip,
            categories = assembledList.map { it.first },
            items = { category ->
                bucketsById[category.id].orEmpty().map { item ->
                    byType[item.entryId.contentType]?.overlaid(item) ?: item
                }
            },
            counts = { category -> if (showCounts) bucketsById[category.id]?.size else null },
        )
    }

    /**
     * Dynamic grouping over the union: concatenate the active providers' feeds and run the shared kernel
     * ONCE, so a tag or tracking status shared by a manga and a novel lands in one bucket. The feeds are
     * EntryId-keyed and the two id spaces are disjoint, so plain list and map concatenation is safe.
     *
     * Two deliberate divergences from the category path: hidden categories are not consulted (a synthetic
     * group cannot be hidden), and the category-sort-order preference goes to the kernel, which orders the
     * groups itself. Both match what the per-type builders did.
     */
    private fun assembleDynamicGroups(
        active: List<LibraryProvider>,
        rows: List<LibraryItem>,
        prefs: AssemblyPrefs,
        fields: LibrarySortFields<LibraryItem>,
    ): List<Pair<Category, List<LibraryItem>>> {
        val feeds = active.map { it.dynamicGroupingFeed(prefs.groupBy) }
        val grouped = LibraryDynamicGrouping.build(
            items = feeds.flatMap { it.items },
            groupType = prefs.groupBy,
            inheritedSortFlag = prefs.sort.flag,
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
        return grouped.mapNotNull { (category, ids) ->
            val items = ids.mapNotNull { rowsByEntryId[it] }
            if (items.isEmpty()) null else category to items.sortedWith(comparator)
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
        libraryPreferences.displayMode.asState(screenModelScope)

    fun columnsForOrientation(isLandscape: Boolean): PreferenceMutableState<Int> =
        (if (isLandscape) libraryPreferences.landscapeColumns else libraryPreferences.portraitColumns)
            .asState(screenModelScope)

    private val mutableSelection = MutableStateFlow<Set<EntryId>>(emptySet())
    val selection: StateFlow<Set<EntryId>> = mutableSelection.asStateFlow()

    private val mutableDialog = MutableStateFlow<LibraryDialog?>(null)
    val dialog: StateFlow<LibraryDialog?> = mutableDialog.asStateFlow()

    /** Anchor for range-select; not reactive, it only decides how the next long-press behaves. */
    private var lastSelectionCategory: Long? = null

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
    }

    /** The one library-wide global sort (chip-free since the sort preferences unified). */
    val globalSort: StateFlow<LibrarySort> by lazy {
        libraryPreferences.sortingMode.changes()
            .stateIn(screenModelScope, SharingStarted.Eagerly, libraryPreferences.sortingMode.get())
    }

    /**
     * The settings sheet a [contentType] describes. Since the filter unification every axis writes a
     * library-wide preference, so the All description is the manga binding (the axis superset: novels
     * only omit the debug interval axis) with the two remaining per-type members answered for a mixed
     * view: a union category list, and no Group tab until dynamic grouping is assembled under All.
     */
    fun settingsFor(contentType: ContentType): LibrarySettingsBinding =
        providersFor(contentType).singleOrNull()?.settings ?: allSettings

    private val allSettings: LibrarySettingsBinding by lazy {
        val manga = providersFor(ContentType.MANGA).single()
        val novel = providersFor(ContentType.NOVELS).single()
        manga.settings.copy(
            categories = combine(manga.settings.categories, novel.settings.categories) { m, n ->
                (m + n).distinctBy { it.id }.sortedBy { it.order }
            }.stateIn(screenModelScope, SharingStarted.WhileSubscribed(), emptyList()),
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

    fun toggleAllCategoriesCollapsed(categories: List<Category>) {
        reikaiLibraryPreferences.toggleAllCategoriesCollapsed(categories)
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

    /** Start a library update, returning false when one is already running. */
    fun refresh(contentType: ContentType, category: Category?): Boolean =
        providersFor(contentType).map { it.refresh(category) }.all { it }

    /**
     * A random entry from [categoryId], or the whole library when null, across every provider in view.
     * Picking one per provider and then one of those weights the content types equally rather than by
     * how many entries each holds, which only matters once a mixed view is reachable.
     */
    fun randomEntry(contentType: ContentType, categoryId: Long?): EntryId? =
        providersFor(contentType).mapNotNull { it.randomEntry(categoryId) }.randomOrNull()

    // Selection. Every op that needs to know what is on screen takes the category's entries in display
    // order, so the engine never has to resolve rows itself and stays free of per-type lookups.

    fun clearSelection() {
        lastSelectionCategory = null
        mutableSelection.value = emptySet()
    }

    fun toggleSelection(categoryId: Long, entry: EntryId) {
        mutableSelection.update { if (entry in it) it - entry else it + entry }
        lastSelectionCategory = categoryId.takeIf { mutableSelection.value.isNotEmpty() }
    }

    /**
     * Select every entry between [entry] and the last selected one, within one category. Falls back to
     * selecting just [entry] when there is no usable anchor, which is what a long-press in a different
     * category (or on a row that is no longer listed) means.
     */
    fun toggleRangeSelection(categoryId: Long, entry: EntryId, ordered: List<EntryId>) {
        mutableSelection.update { current ->
            val anchor = current.lastOrNull()
            val from = ordered.indexOf(anchor)
            val to = ordered.indexOf(entry)
            if (lastSelectionCategory != categoryId || anchor == null || from < 0 || to < 0) {
                current + entry
            } else {
                current + ordered.subList(minOf(from, to), maxOf(from, to) + 1)
            }
        }
        lastSelectionCategory = categoryId
    }

    fun selectAll(ordered: List<EntryId>) {
        lastSelectionCategory = null
        mutableSelection.update { it + ordered }
    }

    /** Select every entry in one category, or deselect them when all are already selected. */
    fun selectAllInCategory(ordered: List<EntryId>) {
        lastSelectionCategory = null
        mutableSelection.update { current ->
            if (ordered.isNotEmpty() && ordered.all { it in current }) {
                current - ordered.toSet()
            } else {
                current + ordered
            }
        }
    }

    fun invertSelection(ordered: List<EntryId>) {
        lastSelectionCategory = null
        mutableSelection.update { current ->
            val (toRemove, toAdd) = ordered.partition { it in current }
            current - toRemove.toSet() + toAdd
        }
    }

    // Bulk actions. Each is handed to every provider in the view, which narrows it to its own entries,
    // so one call covers a selection spanning both content types.

    fun markReadSelection(contentType: ContentType, read: Boolean) =
        dispatchAndClear(contentType) { it.markReadSelection(selection.value, read) }

    fun performDownloadAction(contentType: ContentType, action: DownloadAction) =
        dispatchAndClear(contentType) { it.performDownloadAction(selection.value, action) }

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
        screenModelScope.launchIO {
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

    fun setCategories(entries: Set<EntryId>, addCategories: List<Long>, removeCategories: List<Long>) =
        providers.owning(entries).forEach { it.setCategories(entries, addCategories, removeCategories) }

    fun deleteEntries(
        entries: Set<EntryId>,
        deleteFromLibrary: Boolean,
        deleteDownloads: Boolean,
        removeGroupedSources: Boolean,
    ) = providers.owning(entries).forEach {
        it.deleteEntries(entries, deleteFromLibrary, deleteDownloads, removeGroupedSources)
    }

    /** Any selected entry is a merge group; drives the bulk Unmerge action. */
    fun selectionContainsMerged(contentType: ContentType): Boolean =
        providersFor(contentType).any { it.containsMerged(selection.value) }

    /** The bulk Download action applies (manga hides it when every selected entry is local). */
    fun canDownloadSelection(contentType: ContentType): Boolean =
        providersFor(contentType).all { it.canDownload(selection.value) }

    private fun dispatchAndClear(contentType: ContentType, action: (LibraryProvider) -> Unit) {
        providersFor(contentType).forEach(action)
        clearSelection()
    }
}
