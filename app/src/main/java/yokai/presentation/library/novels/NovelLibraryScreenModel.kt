package yokai.presentation.library.novels

import android.app.Application
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.core.preference.Preference
import eu.kanade.tachiyomi.data.database.models.LibraryNovel
import eu.kanade.tachiyomi.data.database.models.NovelCategory
import eu.kanade.tachiyomi.data.database.models.NovelCategoryImpl
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.ui.library.LibraryGroup
import eu.kanade.tachiyomi.ui.library.LibrarySort
import eu.kanade.tachiyomi.ui.library.models.LibraryItem
import eu.kanade.tachiyomi.util.mapStatus
import eu.kanade.tachiyomi.util.system.launchIO
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.update
import kotlin.random.Random
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import yokai.domain.base.BasePreferences
import yokai.domain.novel.NovelChapterRepository
import yokai.domain.novel.NovelPreferences
import yokai.domain.novel.NovelRepository
import yokai.domain.novel.NovelTrackRepository
import yokai.domain.novel.interactor.GetNovelCategories
import yokai.domain.novel.interactor.ReorderNovelCategories
import yokai.domain.novel.models.Novel
import yokai.domain.novel.models.NovelCategoryUpdate
import yokai.domain.novel.models.NovelChapter
import yokai.domain.ui.UiPreferences
import yokai.i18n.MR
import yokai.novel.source.NovelSourceManager
import yokai.presentation.library.LibraryTabState
import yokai.presentation.library.novels.actions.NovelLibraryActions
import yokai.util.lang.getString

/**
 * Novel-side parallel of [yokai.presentation.library.manga.MangaLibraryScreenModel]. Collects
 * categories, library novels, the [NovelLibraryUpdater] running flag, and the
 * `lastUsedNovelCategory` preference; chains on merge / sort / grouping prefs; folds the result
 * through [NovelLibrarySectioner] (C17), [NovelLibraryGrouping] (C21), and [NovelLibrarySort]
 * (C20) for BY_DEFAULT, or through [NovelLibraryDynamicGrouping] (C22) for dynamic groupings.
 *
 * **C25** wires the full pipeline. C26+ adds selection / actions on top.
 *
 * Diverges from the manga template in three places (all locked by Phase 7 decisions):
 *
 * - **4-arg first combine, not 5** — no `downloadCache.changes` because novel downloads are
 *   stubbed (Decision #4).
 * - **BY_LANGUAGE is rejected** — [NovelLibraryDynamicGrouping] (C22) returns empty for it
 *   because Novel has no language field. Treat as BY_DEFAULT here so the user lands somewhere
 *   sensible if they had BY_LANGUAGE selected on the manga side.
 * - **No `defaultMangaOrder`-equivalent pref** — novels rely on per-category sort on the
 *   novel_categories rows (Decision #6); the synthetic Default category just inherits the
 *   library-wide sort.
 *
 * Koin DI throughout (`KoinComponent` + `by inject()`) — every novel-side dep is Koin-only.
 * Same lesson as C14d.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class NovelLibraryScreenModel :
    StateScreenModel<LibraryTabState<LibraryItem.Novel, NovelCategory>>(LibraryTabState.Loading), KoinComponent {

    private val context: Application by inject()
    private val preferences: PreferencesHelper by inject()
    private val novelPreferences: NovelPreferences by inject()
    private val basePreferences: BasePreferences by inject()
    private val uiPreferences: UiPreferences by inject()
    private val getNovelCategories: GetNovelCategories by inject()
    private val novelRepository: NovelRepository by inject()
    private val novelChapterRepository: NovelChapterRepository by inject()
    private val novelTrackRepository: NovelTrackRepository by inject()
    private val novelSourceManager: NovelSourceManager by inject()
    private val novelLibraryUpdater: NovelLibraryUpdater by inject()
    private val reorderNovelCategories: ReorderNovelCategories by inject()

    /**
     * Library search query. Owned here so the search + filter pipeline can re-derive
     * [LibraryTabState.Loaded.filteredLibrary] reactively off it. The composable collects
     * [searchQuery] for its text field and pushes edits via [setSearchQuery]. Mirrors manga
     * at MangaLibraryScreenModel.kt:97.
     */
    private val searchQueryFlow = MutableStateFlow("")
    val searchQuery: StateFlow<String> = searchQueryFlow.asStateFlow()

    fun setSearchQuery(query: String) {
        searchQueryFlow.value = query
    }

    /**
     * Pre-collapse libraryNovel snapshot used by [selectedNovelListWithMergedSiblings] to
     * resolve merge-group sibling [Novel] objects that [NovelLibraryGrouping.collapse] dropped
     * from the rendered library (only leaders survive). Updated alongside every state emission
     * below. Kept off [LibraryTabState] because no composable consumes it — mirrors manga side
     * (Phase 8 C4).
     */
    private var libraryNovelForResolve: List<LibraryNovel> = emptyList()

    init {
        screenModelScope.launchIO {
            val rawFlow = combine(
                getNovelCategories.subscribe(),
                novelRepository.getLibraryNovelAsFlow(),
                novelLibraryUpdater.isRunningFlow(),
                novelPreferences.lastUsedNovelCategory().changes(),
            ) { categories, libraryNovel, isRunning, currentCategoryOrder ->
                // Placeholders overwritten by the chained .combine() calls below before the
                // snapshot reaches collectLatest (combine waits on every upstream flow).
                Snapshot(
                    categories = categories,
                    libraryNovel = libraryNovel,
                    isRunning = isRunning,
                    currentCategoryOrder = currentCategoryOrder,
                    manualMerges = emptySet(),
                    manualUnmerges = emptySet(),
                    autoMergeSameTitle = true,
                    sortPrefs = SortPrefs.DEFAULT,
                    categorySortOrder = 0,
                    groupingPrefs = GroupingPrefs.DEFAULT,
                )
            }
                .combine(novelPreferences.novelManualMerges().changes()) { snap, merges ->
                    snap.copy(manualMerges = merges)
                }
                .combine(novelPreferences.novelManualUnmerges().changes()) { snap, unmerges ->
                    snap.copy(manualUnmerges = unmerges)
                }
                .combine(sharedOrNovel(preferences.autoMergeSameTitle(), novelPreferences.autoMergeSameTitle())) { snap, auto ->
                    snap.copy(autoMergeSameTitle = auto)
                }
                .combine(sortPrefsFlow()) { snap, sortPrefs ->
                    snap.copy(sortPrefs = sortPrefs)
                }
                .combine(sharedOrNovel(preferences.categorySortOrder(), novelPreferences.categorySortOrder())) { snap, cso ->
                    snap.copy(categorySortOrder = cso)
                }
                .combine(groupingPrefsFlow()) { snap, gp ->
                    snap.copy(groupingPrefs = gp)
                }
                .mapLatest { snap ->
                    val library: Map<NovelCategory, List<LibraryItem.Novel>> =
                        if (snap.groupingPrefs.groupLibraryBy == LibraryGroup.BY_DEFAULT ||
                            snap.groupingPrefs.groupLibraryBy == LibraryGroup.BY_LANGUAGE
                        ) {
                            // BY_LANGUAGE falls through to BY_DEFAULT because
                            // NovelLibraryDynamicGrouping (C22) drops that mode entirely (Novel
                            // has no language field). Without this fallback the user would see
                            // an empty library after switching tabs if the manga side had
                            // BY_LANGUAGE set.
                            buildDefaultGrouping(snap)
                        } else {
                            buildDynamicGrouping(snap)
                        }

                    val inQueue = if (snap.isRunning) {
                        library.keys.mapNotNullTo(HashSet()) { cat ->
                            cat.id?.takeIf { novelLibraryUpdater.isCategoryInQueue(it) }
                        }
                    } else {
                        emptySet()
                    }

                    libraryNovelForResolve = snap.libraryNovel
                    // Precompute source names (lnreader plugin id -> display name) for
                    // NovelLibrarySearch. String-keyed per NovelLibrarySearch.search (lnreader
                    // source ids are strings, unlike manga's Long).
                    val sourceNames = library.values
                        .asSequence()
                        .flatten()
                        .map { it.libraryNovel.novel.source }
                        .distinct()
                        .associateWith { novelSourceManager.get(it)?.name.orEmpty() }
                    RawData(
                        library = library,
                        isRunning = snap.isRunning,
                        inQueue = inQueue,
                        currentCategoryOrder = snap.currentCategoryOrder,
                        categorySortOrder = snap.categorySortOrder,
                        collapsedDynamicCategories = snap.groupingPrefs.collapsedDynamicCategories,
                        collapsedDynamicAtBottom = snap.groupingPrefs.collapsedDynamicAtBottom,
                        sourceNames = sourceNames,
                    )
                }
                // Collapse identical rebuilds + debounce rebuild storms before the suspend filter
                // re-runs track I/O. Search rides a separate flow (filterInputsFlow) so it stays
                // instant. Mirrors manga at MangaLibraryScreenModel.kt:349.
                .distinctUntilChanged()
                .debounce(250L)

            // Search + filter run downstream of the raw-library build, gated so the suspend filter
            // (track I/O) only re-runs when the raw library OR a filter input changes, never on a
            // display/badge pref change (those land in displayPrefsFlow below). Mirrors manga at
            // MangaLibraryScreenModel.kt:360.
            combine(rawFlow, filterInputsFlow().distinctUntilChanged()) { raw, inputs -> raw to inputs }
                .mapLatest { (raw, inputs) ->
                    val searched = NovelLibrarySearch.search(raw.library, inputs.query, raw.sourceNames)
                    val filtered = if (inputs.filterState.isAnyActive) {
                        NovelLibraryFilter.filter(
                            library = searched,
                            state = inputs.filterState,
                            // Decision #5: no novel TrackManager, so no logged-services map; the
                            // tracked filter matches purely on presence/absence of novel_tracks.
                            loggedServiceNames = emptyMap(),
                            // Decision #4: no novel download manager; the filter prefers the
                            // cached item.downloadCount and only consults this when it's -1.
                            getDownloadCount = { 0 },
                            getTracks = { novelId -> novelTrackRepository.getByNovelId(novelId) },
                            keepEmptyCategories = inputs.showAllCategories,
                        )
                    } else {
                        searched
                    }
                    Rendered(raw, inputs.filterState.isAnyActive, filtered)
                }
                // Display prefs join AFTER the filter stage: a cosmetic change re-emits with the
                // last (cached) Rendered, so the render updates without re-running the filter.
                .combine(displayPrefsFlow()) { rendered, display -> rendered to display }
                .collectLatest { (rendered, display) ->
                    mutableState.update { current ->
                        // Preserve selection + sortEpoch across reload emissions (Phase 6 lesson:
                        // a reactive re-emit must not wipe in-progress multi-select state).
                        val raw = rendered.raw
                        val loaded = current as? LibraryTabState.Loaded
                        LibraryTabState.Loaded(
                            library = raw.library,
                            totalItemCount = raw.library.values.sumOf { it.size },
                            isRunning = raw.isRunning,
                            inQueueCategoryIds = raw.inQueue,
                            currentCategoryOrder = raw.currentCategoryOrder,
                            selection = loaded?.selection.orEmpty(),
                            sortEpoch = loaded?.sortEpoch ?: 0,
                            categorySortOrder = raw.categorySortOrder,
                            collapsedDynamicCategories = raw.collapsedDynamicCategories,
                            collapsedDynamicAtBottom = raw.collapsedDynamicAtBottom,
                            filteredLibrary = rendered.filteredLibrary,
                            // Novel divergences: no series-type dimension, no logged trackers.
                            detectedTypes = emptySet(),
                            loggedTrackerNames = emptyList(),
                            isAnyFilterActive = rendered.isAnyFilterActive,
                            libraryLayout = display.layout.libraryLayout,
                            uniformGrid = display.layout.uniformGrid,
                            useStaggeredGrid = display.layout.useStaggeredGrid,
                            gridSize = display.layout.gridSize,
                            outlineOnCovers = display.layout.outlineOnCovers,
                            showDownloadBadge = display.badge.showDownloadBadge,
                            showLanguageBadge = display.badge.showLanguageBadge,
                            unreadBadgeType = display.badge.unreadBadgeType,
                            hideStartReadingButton = display.badge.hideStartReadingButton,
                            showCategoryItemCounts = display.misc.showCategoryItemCounts,
                            manualMerges = display.misc.manualMerges,
                            groupLibraryBy = display.category.groupLibraryBy,
                            collapsedCategories = display.category.collapsedCategories,
                            showAllCategories = display.category.showAllCategories,
                            showEmptyCategoriesWhileFiltering = display.category.showEmptyCategoriesWhileFiltering,
                            lastUsedCategoryOrder = display.category.lastUsedCategoryOrder,
                            hideHopper = display.hopper.hideHopper,
                            autohideHopper = display.hopper.autohideHopper,
                            hopperGravity = display.hopper.hopperGravity,
                            hopperLongPressAction = display.hopper.hopperLongPressAction,
                            // show-category-in-title is manga-only UI (writes the category name
                            // into the toolbar title, which the novel toolbar doesn't surface).
                            showCategoryInTitle = false,
                        )
                    }
                }
        }

        // updateFlow's null emission signals job completion: clear inQueueCategoryIds so headers
        // stop spinning the moment WorkManager flips isRunning -> false. Mid-update category
        // drops are handled by the main combine's isRunning ticks (each tick re-walks
        // categoryInQueue). Mirrors manga's secondary collector at MangaLibraryScreenModel.kt:307.
        screenModelScope.launchIO {
            novelLibraryUpdater.updateFlow.collectLatest { novelId ->
                if (novelId == null) {
                    mutableState.update { current ->
                        if (current is LibraryTabState.Loaded) {
                            current.copy(inQueueCategoryIds = emptySet(), isRunning = false)
                        } else {
                            current
                        }
                    }
                }
            }
        }
    }

    private fun buildDefaultGrouping(snap: Snapshot): Map<NovelCategory, List<LibraryItem.Novel>> {
        // Re-create per emission so a locale change between subscriptions picks up the
        // re-translated "Default" string, matching the manga side's behavior.
        val defaultCategory = NovelCategory.createDefault(context).apply { order = -1 }
        val sectioned = NovelLibrarySectioner.section(
            libraryNovel = snap.libraryNovel,
            userCategories = snap.categories,
            defaultCategory = defaultCategory,
            categorySortOrder = snap.categorySortOrder,
        )
        val grouped = NovelLibraryGrouping.collapse(
            library = sectioned,
            manualMerges = snap.manualMerges,
            manualUnmerges = snap.manualUnmerges,
            autoMergeSameTitle = snap.autoMergeSameTitle,
        )
        return NovelLibrarySort.sort(
            library = grouped,
            libraryDefaultMode = snap.sortPrefs.mode,
            libraryDefaultAscending = snap.sortPrefs.ascending,
            randomSeed = snap.sortPrefs.randomSeed,
            removeArticles = snap.sortPrefs.removeArticles,
        )
    }

    private suspend fun buildDynamicGrouping(snap: Snapshot): Map<NovelCategory, List<LibraryItem.Novel>> {
        val groupType = snap.groupingPrefs.groupLibraryBy
        val unknownLabel = context.getString(MR.strings.unknown)
        val notTrackedLabel = context.getString(MR.strings.not_tracked)

        // Pre-resolve the metadata maps only for the active group type so the common
        // BY_SOURCE / BY_TAG / BY_AUTHOR / BY_STATUS paths stay synchronous.
        val sourceMeta: Map<Long, Pair<String, String>> = if (groupType == LibraryGroup.BY_SOURCE) {
            snap.libraryNovel.mapNotNull { ln ->
                val id = ln.novel.id ?: return@mapNotNull null
                val source = novelSourceManager.get(ln.novel.source) ?: return@mapNotNull null
                id to (source.name to source.id)
            }.toMap()
        } else {
            emptyMap()
        }

        val statusNames: Map<Long, String> = if (groupType == LibraryGroup.BY_STATUS) {
            snap.libraryNovel.mapNotNull { ln ->
                val id = ln.novel.id ?: return@mapNotNull null
                // NovelStatusCode constants share the same int values as SManga.* so the
                // existing Context.mapStatus extension (manga side) renders novel statuses too.
                id to context.mapStatus(ln.novel.status)
            }.toMap()
        } else {
            emptyMap()
        }

        val trackStatuses: Map<Long, String> = if (groupType == LibraryGroup.BY_TRACK_STATUS) {
            snap.libraryNovel.mapNotNull { ln ->
                val novelId = ln.novel.id ?: return@mapNotNull null
                val tracks = novelTrackRepository.getByNovelId(novelId)
                // Phase 7 has no novel TrackManager / logged-services map (Decision #5 defers
                // tracker reconciliation). Bucket by the raw status int rendered as a string;
                // the future Compose tracker UI can swap this for a service-aware lookup once
                // the novel tracker manager ships.
                tracks.firstOrNull()?.let { novelId to it.status.toString() }
            }.toMap()
        } else {
            emptyMap()
        }

        val dynamic = NovelLibraryDynamicGrouping.build(
            libraryNovel = snap.libraryNovel,
            groupType = groupType,
            librarySortingMode = snap.sortPrefs.mode.mainValue,
            librarySortingAscending = snap.sortPrefs.ascending,
            collapsedDynamicCategories = snap.groupingPrefs.collapsedDynamicCategories,
            collapsedDynamicAtBottom = snap.groupingPrefs.collapsedDynamicAtBottom,
            unknownLabel = unknownLabel,
            notTrackedLabel = notTrackedLabel,
            sourceMeta = sourceMeta,
            trackStatuses = trackStatuses,
            statusNames = statusNames,
            trackingStatusOrder = ::mapTrackingOrder,
        )

        return NovelLibrarySort.sort(
            library = dynamic,
            libraryDefaultMode = snap.sortPrefs.mode,
            libraryDefaultAscending = snap.sortPrefs.ascending,
            randomSeed = snap.sortPrefs.randomSeed,
            removeArticles = snap.sortPrefs.removeArticles,
        )
    }

    // ----------------------------------------------------------------------------------------
    // Selection state + mutators (C26). Pure state updates; no DB touches.
    // ----------------------------------------------------------------------------------------

    fun toggleSelection(novelId: Long) {
        mutableState.update { current ->
            if (current is LibraryTabState.Loaded) {
                val next = if (novelId in current.selection) {
                    current.selection - novelId
                } else {
                    current.selection + novelId
                }
                current.copy(selection = next)
            } else {
                current
            }
        }
    }

    fun clearSelection() {
        mutableState.update { current ->
            if (current is LibraryTabState.Loaded && current.selection.isNotEmpty()) {
                current.copy(selection = emptySet())
            } else {
                current
            }
        }
    }

    fun setSelection(novelIds: Set<Long>) {
        mutableState.update { current ->
            if (current is LibraryTabState.Loaded) {
                current.copy(selection = novelIds)
            } else {
                current
            }
        }
    }

    /**
     * Toggle every novel in a category in / out of the selection set. Mirrors manga's
     * select-all-in-category checkbox: if every novel in the category is already selected,
     * remove them all; otherwise add the missing ones (covers the partial-already-selected
     * case without first deselecting).
     */
    fun toggleCategorySelection(categoryId: Int) {
        mutableState.update { current ->
            if (current !is LibraryTabState.Loaded) return@update current
            val categoryEntry = current.library.entries.firstOrNull { it.key.id == categoryId }
                ?: return@update current
            val categoryIds = categoryEntry.value.mapNotNull { it.libraryNovel.novel.id }.toSet()
            if (categoryIds.isEmpty()) return@update current
            val allSelected = categoryIds.all { it in current.selection }
            val newSelection = if (allSelected) {
                current.selection - categoryIds
            } else {
                current.selection + categoryIds
            }
            current.copy(selection = newSelection)
        }
    }

    /**
     * Expand the current selection to include every member of any merge group the selection
     * touches. Walks the rendered library, finds items whose id is in the selection, and
     * unions in each item's [yokai.presentation.library.novels.LibraryItem.Novel.relatedNovelIds]
     * (stamped by [NovelLibraryGrouping.collapse] for collapsed leaders). Items without
     * related ids contribute just their own id, so the helper is a no-op for selections that
     * don't include a merged-group leader.
     *
     * **Synchronous-capture rule (Phase 6 C11).** Callers MUST capture this synchronously
     * BEFORE any `launchIO` block:
     *
     * ```kotlin
     * val expanded = expandSelectionWithMergedSiblings().toList()  // sync capture
     * if (expanded.isEmpty()) return
     * screenModelScope.launchIO { NovelLibraryActions.foo(expanded, ...) }
     * ```
     *
     * Why: the UI typically calls `clearSelection()` immediately after dispatching an action.
     * If expansion happens inside `launchIO`, the clear races and wins — the coroutine reads
     * an empty selection and the action no-ops silently. Mirrors the manga-side fix at
     * MangaLibraryScreenModel.kt:712 (Phase 6 commit 84a49f59c).
     */
    private fun expandSelectionWithMergedSiblings(): Set<Long> {
        val loaded = state.value as? LibraryTabState.Loaded ?: return emptySet()
        val selection = loaded.selection
        if (selection.isEmpty()) return emptySet()
        return loaded.library.values
            .asSequence()
            .flatten()
            .filter { it.libraryNovel.novel.id in selection }
            .flatMap { item ->
                if (item.relatedNovelIds.isNotEmpty()) {
                    item.relatedNovelIds.toList()
                } else {
                    listOfNotNull(item.libraryNovel.novel.id)
                }
            }
            .toSet()
    }

    /**
     * Like [selectedNovelList] but pulls in every member of any merge group the selection
     * touches. Used by delete + move-to-category so a merged-group leader doesn't get an
     * action applied to it in isolation, orphaning the siblings (Phase 6 lesson).
     *
     * Resolves [Novel] objects from the screen-model-private `libraryNovelForResolve` field
     * (the pre-collapse list) rather than from `library.values` — merge-group siblings are
     * dropped by [NovelLibraryGrouping.collapse] and only their leaders survive in the
     * rendered list, so a filter against `library.values` would silently return only the
     * leader and let downstream delete / move operations skip every sibling. Same
     * synchronous-capture rule applies (see [expandSelectionWithMergedSiblings]).
     */
    fun selectedNovelListWithMergedSiblings(): List<Novel> {
        if (state.value !is LibraryTabState.Loaded) return emptyList()
        val expanded = expandSelectionWithMergedSiblings()
        if (expanded.isEmpty()) return emptyList()
        return libraryNovelForResolve
            .asSequence()
            .map { it.novel }
            .filter { it.id in expanded }
            .distinctBy { it.id }
            .toList()
    }

    /**
     * Resolve current selection ids to their backing [Novel] entries via state.library. No DB
     * call needed: every selected novel is, by definition, currently rendered in the grid.
     *
     * For multi-select actions that need to operate on every member of a merged group (delete,
     * move-to-categories), use [selectedNovelListWithMergedSiblings] instead — calling this
     * resolver on a collapsed-leader selection silently returns only the leader.
     */
    fun selectedNovelList(): List<Novel> {
        val loaded = state.value as? LibraryTabState.Loaded ?: return emptyList()
        val ids = loaded.selection
        if (ids.isEmpty()) return emptyList()
        return loaded.library.values
            .asSequence()
            .flatten()
            .map { it.libraryNovel.novel }
            .filter { it.id in ids }
            .toList()
    }

    // ----------------------------------------------------------------------------------------
    // Multi-select action wiring (C32). All callers that consume a selection capture it
    // SYNCHRONOUSLY before any launchIO block — see expandSelectionWithMergedSiblings KDoc for
    // the Phase 6 race-loss rationale. removeFromLibrary + confirmDeletion go through the
    // merged-siblings resolver so deleting a collapsed leader pulls every sibling along.
    // ----------------------------------------------------------------------------------------

    fun shareSelection(): List<String> =
        NovelLibraryActions.share(selectedNovelList(), novelSourceManager)

    /**
     * Stub passthrough per Decision #4. Capture the selection synchronously before launchIO
     * so the wiring matches the live actions; the underlying [NovelLibraryActions.downloadUnread]
     * is a no-op until novel downloads ship.
     *
     * TODO(unseensnick): when Decision #4 undefers (novel downloads ship), swap
     * [selectedNovelList] for [selectedNovelListWithMergedSiblings] so a download on a
     * merged-group leader pulls every sibling. This is the same Phase 6 C11 lesson
     * [removeFromLibrary] already inherits. Latent today because downloads are a no-op.
     */
    fun downloadUnreadSelection() {
        val novels = selectedNovelList()
        screenModelScope.launchIO {
            NovelLibraryActions.downloadUnread(novels)
        }
    }

    /**
     * Apply read / unread to every chapter of the selection. Returns the pre-update snapshot
     * so the caller can offer Undo via [undoMarkReadStatus] or commit cleanup via
     * [confirmMarkReadStatus]. Suspends because the underlying call iterates chapters per novel.
     */
    suspend fun markReadStatus(markRead: Boolean): Map<Novel, List<NovelChapter>> =
        NovelLibraryActions.markReadStatus(
            novels = selectedNovelList(),
            markRead = markRead,
            novelChapterRepository = novelChapterRepository,
        )

    fun undoMarkReadStatus(snapshot: Map<Novel, List<NovelChapter>>) {
        screenModelScope.launchIO {
            NovelLibraryActions.undoMarkReadStatus(snapshot, novelChapterRepository)
        }
    }

    fun confirmMarkReadStatus(snapshot: Map<Novel, List<NovelChapter>>, markRead: Boolean) {
        // Body is a no-op until novel downloads ship (Decision #4). Kept on the surface so the
        // wiring matches manga and the future Compose binding has a stable call to make.
        NovelLibraryActions.confirmMarkReadStatus(
            snapshot = snapshot,
            markRead = markRead,
            removeAfterMarkedAsRead = preferences.removeAfterMarkedAsRead().get(),
        )
    }

    /**
     * Path 1 (full nuke). Flip `favorite = false` immediately; return the captured (expanded)
     * selection so the caller can offer Undo via [reAddToLibrary] or commit destructive
     * cleanup via [confirmDeletion]. State reload happens reactively via
     * [NovelRepository.getLibraryNovelAsFlow]. Phase 6 sibling-expansion lesson: a delete on a
     * merged-group leader pulls every sibling along; the expanded list flows through the
     * snackbar so every member gets undone / cleaned up uniformly.
     */
    fun removeFromLibrary(): List<Novel> {
        val novels = selectedNovelListWithMergedSiblings()
        screenModelScope.launchIO {
            NovelLibraryActions.removeFromLibrary(novels, novelRepository)
        }
        return novels
    }

    fun reAddToLibrary(novels: List<Novel>) {
        screenModelScope.launchIO {
            NovelLibraryActions.reAddToLibrary(novels, novelRepository)
        }
    }

    /**
     * Destructive cleanup. Calls [NovelTrackRepository.deleteAllForNovel] for each novel per
     * Decision #5. The `coverCacheToo` parameter is accepted for call-site symmetry with the
     * manga side but has no effect today — novels have no cover cache or download cleanup
     * surface yet (Decision #4 + no novel CoverCache).
     */
    @Suppress("UNUSED_PARAMETER")
    fun confirmDeletion(novels: List<Novel>, coverCacheToo: Boolean = true) {
        screenModelScope.launchIO {
            NovelLibraryActions.confirmDeletion(novels, novelTrackRepository)
        }
    }

    /**
     * Capture the expanded id set SYNCHRONOUSLY before launching the IO coroutine — Phase 6
     * lesson, see [expandSelectionWithMergedSiblings] KDoc. The action-bar handler in the
     * future Novels tab will call `mergeSelection()` followed immediately by
     * `clearSelection()`; without the sync capture, the clear races and the expansion sees an
     * empty set, silently dropping the merge.
     *
     * Decision #5: no [NovelLibraryActions.merge] tracker-reconciliation follow-up — novel
     * tracker reconciliation is deferred. The sorted-ids return value is currently unused but
     * preserved on the API surface for future snackbar / undo wiring.
     */
    fun mergeSelection() {
        val expandedIds = expandSelectionWithMergedSiblings().toList()
        if (expandedIds.size < 2) return
        screenModelScope.launchIO {
            NovelLibraryActions.merge(expandedIds, novelPreferences)
        }
    }

    fun unmergeSelection() {
        // Unmerge takes the raw selection (not the expanded set): picking any single member of
        // a merged group is enough — NovelLibraryActions.unmerge dissolves the whole group it
        // belongs to.
        val ids = (state.value as? LibraryTabState.Loaded)?.selection?.toList().orEmpty()
        if (ids.isEmpty()) return
        screenModelScope.launchIO {
            NovelLibraryActions.unmerge(ids, novelPreferences)
        }
    }

    // ----------------------------------------------------------------------------------------
    // Refresh + dynamic-category collapse (C29).
    // ----------------------------------------------------------------------------------------

    /**
     * Dispatch a refresh for [category] (or all categories when null). Mirrors manga's
     * `MangaLibraryScreenModel.refresh`:
     *
     * - Real categories (`id >= 0`, `isDynamic == false`): forwarded as-is. The worker
     *   resolves the bucket from the DB via `novel.category == id`.
     * - Dynamic categories (`isDynamic == true`, synthetic negative id): the worker can't
     *   resolve a synthetic id back to a DB filter, so we look up the bucket in the current
     *   loaded library and hand the worker the novel list directly via `novelsToUse`.
     *
     * **Optimistic spinner** (Phase 6 lesson): as soon as we dispatch, we add the category id
     * to `inQueueCategoryIds` and flip `isRunning` so the per-header spinner appears without
     * waiting for the [NovelLibraryUpdater.isRunningFlow] tick + reactive snap re-derivation.
     * Without this, the spinner lags a few hundred milliseconds after tap. The reactive
     * derivation later replaces the set with the worker-populated
     * [NovelUpdateJob.categoryInQueue], which includes synthetic ids thanks to the C14d
     * `!= -1` sentinel check.
     */
    fun refresh(category: NovelCategory?): Boolean {
        val novelsToUse = if (category?.isDynamic == true) {
            val loaded = state.value as? LibraryTabState.Loaded ?: return false
            loaded.library.entries
                .firstOrNull { it.key.id == category.id }
                ?.value
                ?.map { it.libraryNovel }
                ?: return false
        } else {
            null
        }
        val started = novelLibraryUpdater.startNow(category, novelsToUse = novelsToUse)
        category?.id?.let { catId ->
            mutableState.update { current ->
                if (current is LibraryTabState.Loaded) {
                    current.copy(
                        inQueueCategoryIds = current.inQueueCategoryIds + catId,
                        isRunning = true,
                    )
                } else {
                    current
                }
            }
        }
        return started
    }

    fun stopRefresh() = novelLibraryUpdater.stop()

    fun isCategoryInQueue(categoryId: Int?): Boolean = novelLibraryUpdater.isCategoryInQueue(categoryId)

    fun isRunning(): Boolean = novelLibraryUpdater.isRunning()

    /**
     * Toggle the collapse state of a dynamic category. Writes to
     * [NovelPreferences.collapsedDynamicCategories] keyed by [NovelCategory.dynamicHeaderKey].
     * Default-grouping categories use a separate `collapsedCategories` pref keyed by integer
     * category id (handled by the future Compose `onToggleCategoryCollapse` callback in the
     * Novels tab).
     */
    fun toggleDynamicCategoryCollapse(category: NovelCategory) {
        if (!category.isDynamic) return
        val key = category.dynamicHeaderKey()
        val current = novelPreferences.collapsedDynamicCategories().get().toMutableSet()
        if (!current.add(key)) current.remove(key)
        novelPreferences.collapsedDynamicCategories().set(current)
    }

    fun setGroupLibraryBy(value: Int) {
        // Shareable display pref: write to the manga key in shared mode so both libraries follow.
        sharedOrNovelWrite(preferences.groupLibraryBy(), novelPreferences.groupLibraryBy()).set(value)
    }

    fun setHopperGravity(gravity: Int) {
        sharedOrNovelWrite(preferences.hopperGravity(), novelPreferences.novelHopperGravity()).set(gravity)
    }

    fun setLastUsedCategory(order: Int) {
        if (order >= 0) novelPreferences.lastUsedNovelCategory().set(order)
    }

    /**
     * Toggle a default (non-dynamic) category's collapsed state, keyed by its integer id as a
     * string in `collapsedCategories`. Mirrors the composable's previous inline pref write.
     */
    fun toggleDefaultCategoryCollapse(categoryId: String) {
        val current = novelPreferences.collapsedCategories().get().toMutableSet()
        if (!current.add(categoryId)) current.remove(categoryId)
        novelPreferences.collapsedCategories().set(current)
    }

    /**
     * Collapse all default categories if none are collapsed; otherwise expand all. Category ids
     * come from the current loaded library keys. Mirrors the composable's previous inline write.
     */
    fun expandOrCollapseAllDefaultCategories() {
        val loaded = state.value as? LibraryTabState.Loaded ?: return
        val all = loaded.library.keys.mapNotNull { it.id?.toString() }.toSet()
        val current = novelPreferences.collapsedCategories().get()
        novelPreferences.collapsedCategories().set(if (current.isEmpty()) all else emptySet())
    }

    // ----------------------------------------------------------------------------------------
    // Sort writes (C28). Per-category SQL update for regular categories; library-wide pref
    // write for synthetic (default + dynamic) ones; optimistic state update + sortEpoch bump
    // so the UI re-renders without waiting for the reactive DB / pref round-trip.
    // ----------------------------------------------------------------------------------------

    /**
     * Set [category]'s sort to [mode]. Mirrors the manga side's `setSort` verbatim with two
     * Phase 7 adaptations:
     *
     * - **No `defaultMangaOrder`-equivalent pref** (Decision #6). The synthetic Default
     *   category has no DB row to persist sort to; tapping a sort on it writes the
     *   library-wide pref instead, same path as id == -1 / dynamic categories.
     * - **Per-category writes go through [ReorderNovelCategories]** (the C12 interactor) with
     *   a [NovelCategoryUpdate], not the manga `UpdateCategories` / `CategoryUpdate` pair.
     */
    fun setSort(category: NovelCategory, mode: LibrarySort) {
        val catId = category.id ?: return
        val current = (state.value as? LibraryTabState.Loaded)?.library?.keys
            ?.firstOrNull { it.id == catId }
            ?: category

        if (mode == LibrarySort.Random) {
            novelPreferences.randomSortSeed().set(Random.nextInt())
        }
        val ascending = if (mode == current.sortingMode() && mode.isDirectional) {
            !current.isAscending()
        } else {
            !mode.hasInvertedSort
        }
        val newSortChar = if (ascending) mode.categoryValue else mode.categoryValueDescending

        when {
            current.id == -1 || current.id == 0 || current.isDynamic -> {
                novelPreferences.librarySortingMode().set(mode.mainValue)
                novelPreferences.librarySortingAscending().set(ascending)
            }
            else -> {
                screenModelScope.launchIO {
                    reorderNovelCategories.awaitOne(
                        NovelCategoryUpdate(
                            id = catId.toLong(),
                            novelOrder = newSortChar.toString(),
                        ),
                    )
                }
            }
        }

        if (mode == LibrarySort.Random) return
        val loaded = state.value as? LibraryTabState.Loaded ?: return
        val clonedCategory = NovelCategoryImpl().also {
            it.id = current.id
            it.name = current.name
            it.order = current.order
            it.flags = current.flags
            it.novelOrder = current.novelOrder
            it.novelSort = newSortChar
            it.isAlone = current.isAlone
            it.isHidden = current.isHidden
            it.isDynamic = current.isDynamic
            it.sourceId = current.sourceId
            it.langId = current.langId
            it.isSystem = current.isSystem
        }
        val replacedLibrary = loaded.library.mapKeys { (cat, _) ->
            if (cat.id == catId) clonedCategory else cat
        }
        val resorted = NovelLibrarySort.sort(
            library = replacedLibrary,
            libraryDefaultMode = LibrarySort.valueOf(novelPreferences.librarySortingMode().get())
                ?: LibrarySort.Title,
            libraryDefaultAscending = novelPreferences.librarySortingAscending().get(),
            randomSeed = novelPreferences.randomSortSeed().get().toLong(),
            removeArticles = preferences.removeArticles().get(),
        )
        // Bump sortEpoch so StateFlow.update emits even if the new library map compares equal
        // to the old (NovelCategoryImpl.equals is by name; a cloned NovelCategory reads as
        // equal to the original, and a same-order sorted list reads as equal to the previous).
        mutableState.update { c ->
            if (c is LibraryTabState.Loaded) c.copy(
                library = resorted,
                sortEpoch = c.sortEpoch + 1,
            ) else c
        }
    }

    // ----------------------------------------------------------------------------------------
    // Combine-chain helper flows + helpers below.
    // ----------------------------------------------------------------------------------------

    /**
     * Bundles the sort-related prefs so the main combine chain stays shallow. Drops manga's
     * `defaultMangaOrder` (novels rely on per-category sort on the novel_categories rows per
     * Decision #6); reads `randomSortSeed` from [NovelPreferences] (added in C28) so a Random
     * re-roll from [setSort] propagates through this flow on the next emission.
     */
    private fun sortPrefsFlow() = combine(
        novelPreferences.librarySortingMode().changes(),
        novelPreferences.librarySortingAscending().changes(),
        novelPreferences.randomSortSeed().changes(),
        preferences.removeArticles().changes(),
    ) { modeInt, ascending, seed, removeArticles ->
        SortPrefs(
            mode = LibrarySort.valueOf(modeInt) ?: LibrarySort.Title,
            ascending = ascending,
            randomSeed = seed.toLong(),
            removeArticles = removeArticles,
        )
    }

    /**
     * Bundles the three dynamic-grouping prefs so the main combine chain stays shallow.
     */
    // groupLibraryBy + collapsedDynamicAtBottom are shareable display prefs (honor the shared
    // toggle); collapsedDynamicCategories stays novel-only because its keys (source/tag/author
    // names) differ between the two libraries' content.
    private fun groupingPrefsFlow() = combine(
        sharedOrNovel(preferences.groupLibraryBy(), novelPreferences.groupLibraryBy()),
        novelPreferences.collapsedDynamicCategories().changes(),
        sharedOrNovel(preferences.collapsedDynamicAtBottom(), novelPreferences.collapsedDynamicAtBottom()),
    ) { groupBy, collapsed, atBottom -> GroupingPrefs(groupBy, collapsed, atBottom) }

    /**
     * Bundles the search query + the five novel filter prefs into one [FilterInputs] stream.
     * Held separate from the raw-library pipeline so a change here re-runs only the search +
     * suspend filter (gated downstream by `distinctUntilChanged`), not the grouping/sort build.
     * Drops manga's `contentType` / `mangaType` (Decision #3: no such filter row on novels).
     * Mirrors manga at MangaLibraryScreenModel.kt:951.
     */
    private fun filterInputsFlow() = combine(
        searchQueryFlow,
        novelPreferences.filterUnread().changes(),
        novelPreferences.filterDownloaded().changes(),
        novelPreferences.filterCompleted().changes(),
        novelPreferences.filterTracked().changes(),
    ) { query, unread, downloaded, completed, tracked ->
        FilterAcc(query, unread, downloaded, completed, tracked)
    }
        .combine(novelPreferences.filterBookmarked().changes()) { acc, bookmarked ->
            acc.copy(bookmarked = bookmarked)
        }
        .combine(sharedOrNovel(preferences.showAllCategories(), novelPreferences.showAllCategories())) { acc, showAll ->
            FilterInputs(
                query = acc.query,
                filterState = NovelLibraryFilter.NovelFilterState(
                    downloaded = acc.downloaded,
                    unread = acc.unread,
                    completed = acc.completed,
                    tracked = acc.tracked,
                    bookmarked = acc.bookmarked,
                    // Decision #5: no novel tracker-service picker yet; match on presence only.
                    tracker = "",
                ),
                showAllCategories = showAll,
            )
        }

    /**
     * Resolves a display pref reactively against the shared-vs-independent toggle. When
     * `useSharedLibraryDisplayPrefs` is on, the novel library mirrors the manga pref; otherwise
     * it reads its own. Re-resolves whenever the toggle flips (novel-only wrinkle; the manga
     * side has no such duality).
     */
    private fun <T> sharedOrNovel(mangaPref: Preference<T>, novelPref: Preference<T>): Flow<T> =
        basePreferences.useSharedLibraryDisplayPrefs().changes().flatMapLatest { shared ->
            if (shared) mangaPref.changes() else novelPref.changes()
        }

    /**
     * Write counterpart of [sharedOrNovel]: picks the pref to write based on the shared toggle's
     * current value, so a write from a novel-side action (hopper group-by dialog, hopper drag)
     * lands on the same key the reactive read resolves. Reads the toggle synchronously at write
     * time rather than reactively.
     */
    private fun <T> sharedOrNovelWrite(mangaPref: Preference<T>, novelPref: Preference<T>): Preference<T> =
        if (basePreferences.useSharedLibraryDisplayPrefs().get()) mangaPref else novelPref

    /**
     * Display / badge / layout / category preferences (Tier 2 phase 2C). Bundled into sub-groups
     * to stay within the typed 5-arg `combine` limit. Combined downstream of the search/filter
     * stage so a cosmetic change updates the render without re-running the suspend filter.
     * Mirrors manga at MangaLibraryScreenModel.kt:1025.
     */
    private fun displayPrefsFlow() = combine(
        layoutPrefsFlow(),
        badgePrefsFlow(),
        categoryPrefsFlow(),
        miscPrefsFlow(),
        hopperPrefsFlow(),
    ) { layout, badge, category, misc, hopper ->
        DisplayPrefs(layout, badge, category, misc, hopper)
    }

    private fun layoutPrefsFlow() = combine(
        sharedOrNovel(preferences.libraryLayout(), novelPreferences.novelLibraryLayout()),
        sharedOrNovel(uiPreferences.uniformGrid(), novelPreferences.novelUniformGrid()),
        sharedOrNovel(preferences.useStaggeredGrid(), novelPreferences.novelUseStaggeredGrid()),
        sharedOrNovel(preferences.gridSize(), novelPreferences.novelGridSize()),
        sharedOrNovel(uiPreferences.outlineOnCovers(), novelPreferences.novelOutlineOnCovers()),
    ) { layout, uniform, staggered, gridSize, outline ->
        LayoutPrefs(layout, uniform, staggered, gridSize, outline)
    }

    private fun badgePrefsFlow() = combine(
        sharedOrNovel(preferences.downloadBadge(), novelPreferences.novelDownloadBadge()),
        sharedOrNovel(preferences.languageBadge(), novelPreferences.novelLanguageBadge()),
        sharedOrNovel(preferences.unreadBadgeType(), novelPreferences.novelUnreadBadgeType()),
        sharedOrNovel(preferences.hideStartReadingButton(), novelPreferences.novelHideStartReadingButton()),
    ) { download, language, unread, hideStart ->
        BadgePrefs(download, language, unread, hideStart)
    }

    // groupLibraryBy / showAllCategories / showEmptyCategoriesWhileFiltering are shareable
    // display prefs (honor the shared toggle). collapsedCategories (by category id) and
    // lastUsedNovelCategory stay novel-only: both reference this library's specific category set.
    private fun categoryPrefsFlow() = combine(
        sharedOrNovel(preferences.groupLibraryBy(), novelPreferences.groupLibraryBy()),
        novelPreferences.collapsedCategories().changes(),
        sharedOrNovel(preferences.showAllCategories(), novelPreferences.showAllCategories()),
        sharedOrNovel(preferences.showEmptyCategoriesWhileFiltering(), novelPreferences.showEmptyCategoriesWhileFiltering()),
        novelPreferences.lastUsedNovelCategory().changes(),
    ) { groupBy, collapsed, showAll, showEmpty, lastUsed ->
        CategoryPrefs(groupBy, collapsed, showAll, showEmpty, lastUsed)
    }

    private fun miscPrefsFlow() = combine(
        sharedOrNovel(preferences.categoryNumberOfItems(), novelPreferences.novelCategoryNumberOfItems()),
        novelPreferences.novelManualMerges().changes(),
    ) { itemCounts, merges ->
        MiscPrefs(itemCounts, merges)
    }

    // Category-hopper prefs honor the shared display toggle: shared mode mirrors the manga
    // hopper, independent mode keeps the novel hopper's own settings.
    private fun hopperPrefsFlow() = combine(
        sharedOrNovel(preferences.hideHopper(), novelPreferences.novelHideHopper()),
        sharedOrNovel(preferences.autohideHopper(), novelPreferences.novelAutohideHopper()),
        sharedOrNovel(preferences.hopperGravity(), novelPreferences.novelHopperGravity()),
        sharedOrNovel(preferences.hopperLongPressAction(), novelPreferences.novelHopperLongPressAction()),
    ) { hide, autohide, gravity, longPress ->
        HopperPrefs(hide, autohide, gravity, longPress)
    }

    /**
     * Maps a tracker status name to a sort prefix so BY_TRACK_STATUS orders by intent
     * (Reading first, Dropped last) rather than alphabetically. Mirrors manga's
     * `MangaLibraryScreenModel.mapTrackingOrder` (MR string lookups). When novel-side tracker
     * statuses materialise (Decision #5 deferral), replace the raw `Int.toString()` path in
     * [buildDynamicGrouping] with a status-name lookup and let this map order them.
     */
    private fun mapTrackingOrder(status: String): String = with(context) {
        when (status) {
            getString(MR.strings.reading), getString(MR.strings.currently_reading) -> "1"
            getString(MR.strings.rereading) -> "2"
            getString(MR.strings.plan_to_read), getString(MR.strings.want_to_read) -> "3"
            getString(MR.strings.on_hold), getString(MR.strings.paused) -> "4"
            getString(MR.strings.completed) -> "5"
            getString(MR.strings.dropped) -> "6"
            else -> "7"
        }
    }

    private data class GroupingPrefs(
        val groupLibraryBy: Int,
        val collapsedDynamicCategories: Set<String>,
        val collapsedDynamicAtBottom: Boolean,
    ) {
        companion object {
            val DEFAULT = GroupingPrefs(
                groupLibraryBy = LibraryGroup.BY_DEFAULT,
                collapsedDynamicCategories = emptySet(),
                collapsedDynamicAtBottom = false,
            )
        }
    }

    private data class SortPrefs(
        val mode: LibrarySort,
        val ascending: Boolean,
        val randomSeed: Long,
        val removeArticles: Boolean,
    ) {
        companion object {
            val DEFAULT = SortPrefs(
                mode = LibrarySort.Title,
                ascending = true,
                randomSeed = 0L,
                removeArticles = false,
            )
        }
    }

    private data class Snapshot(
        val categories: List<NovelCategory>,
        val libraryNovel: List<LibraryNovel>,
        val isRunning: Boolean,
        val currentCategoryOrder: Int,
        val manualMerges: Set<String>,
        val manualUnmerges: Set<String>,
        val autoMergeSameTitle: Boolean,
        val sortPrefs: SortPrefs,
        val categorySortOrder: Int,
        val groupingPrefs: GroupingPrefs,
    )

    private data class FilterAcc(
        val query: String,
        val unread: Int,
        val downloaded: Int,
        val completed: Int,
        val tracked: Int,
        val bookmarked: Int = 0,
    )

    private data class FilterInputs(
        val query: String,
        val filterState: NovelLibraryFilter.NovelFilterState,
        val showAllCategories: Boolean,
    )

    /** Raw-library pipeline output, before search + filter. */
    private data class RawData(
        val library: Map<NovelCategory, List<LibraryItem.Novel>>,
        val isRunning: Boolean,
        val inQueue: Set<Int>,
        val currentCategoryOrder: Int,
        val categorySortOrder: Int,
        val collapsedDynamicCategories: Set<String>,
        val collapsedDynamicAtBottom: Boolean,
        val sourceNames: Map<String, String>,
    )

    /** Raw data + the search/filter result for one emission. */
    private data class Rendered(
        val raw: RawData,
        val isAnyFilterActive: Boolean,
        val filteredLibrary: Map<NovelCategory, List<LibraryItem.Novel>>,
    )

    private data class LayoutPrefs(
        val libraryLayout: Int,
        val uniformGrid: Boolean,
        val useStaggeredGrid: Boolean,
        val gridSize: Float,
        val outlineOnCovers: Boolean,
    )

    private data class BadgePrefs(
        val showDownloadBadge: Boolean,
        val showLanguageBadge: Boolean,
        val unreadBadgeType: Int,
        val hideStartReadingButton: Boolean,
    )

    private data class CategoryPrefs(
        val groupLibraryBy: Int,
        val collapsedCategories: Set<String>,
        val showAllCategories: Boolean,
        val showEmptyCategoriesWhileFiltering: Boolean,
        val lastUsedCategoryOrder: Int,
    )

    private data class MiscPrefs(
        val showCategoryItemCounts: Boolean,
        val manualMerges: Set<String>,
    )

    private data class HopperPrefs(
        val hideHopper: Boolean,
        val autohideHopper: Boolean,
        val hopperGravity: Int,
        val hopperLongPressAction: Int,
    )

    private data class DisplayPrefs(
        val layout: LayoutPrefs,
        val badge: BadgePrefs,
        val category: CategoryPrefs,
        val misc: MiscPrefs,
        val hopper: HopperPrefs,
    )
}
