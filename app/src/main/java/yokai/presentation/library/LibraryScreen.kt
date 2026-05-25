package yokai.presentation.library

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import dev.icerock.moko.resources.compose.stringResource
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.tachiyomi.core.storage.preference.collectAsState
import eu.kanade.tachiyomi.data.database.models.seriesType
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.data.track.TrackManager
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.ui.library.filter.FilterBottomSheet
import eu.kanade.tachiyomi.source.LocalSource
import eu.kanade.tachiyomi.ui.manga.MangaDetailsController
import eu.kanade.tachiyomi.ui.migration.manga.design.PreMigrationController
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.util.chapter.ChapterSort
import eu.kanade.tachiyomi.util.compose.LocalRouter
import eu.kanade.tachiyomi.util.moveCategories
import eu.kanade.tachiyomi.util.view.withFadeTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import yokai.domain.chapter.interactor.GetChapter
import yokai.domain.track.interactor.GetTrack
import yokai.i18n.MR
import yokai.presentation.library.manga.MangaLibraryFilter
import yokai.presentation.library.manga.MangaLibraryFilter.MangaFilterState
import yokai.presentation.library.manga.MangaLibraryScreenModel
import yokai.presentation.library.manga.MangaLibrarySearch
import yokai.presentation.library.settings.tabs.columnsForGridValue
import yokai.util.lang.getString

/**
 * Phase 1+ single-tab manga library host. Phase 8 expands this into a tabbed shell with manga
 * and novel tabs sharing a common `LibraryTabContent` composable.
 *
 * Reads filter preferences reactively (`changes().collectAsState`) and pipes the library through
 * `MangaLibrarySearch` then `MangaLibraryFilter` before handing off to `LibraryContent`. Owns
 * the Display options sheet and overflow menu state; both are pure UI concerns kept local.
 */
class LibraryScreen : Screen {

    @Composable
    override fun Content() {
        val screenModel = rememberScreenModel { MangaLibraryScreenModel() }
        val state by screenModel.state.collectAsState()
        val preferences: PreferencesHelper = remember { Injekt.get() }
        val sourceManager: SourceManager = remember { Injekt.get() }
        val trackManager: TrackManager = remember { Injekt.get() }
        val downloadManager: DownloadManager = remember { Injekt.get() }
        val getTrack: GetTrack = remember { Injekt.get() }
        val getChapter: GetChapter = remember { Injekt.get() }
        val router = LocalRouter.currentOrThrow
        val coroutineScope = rememberCoroutineScope()

        val libraryLayout by preferences.libraryLayout().collectAsState()
        val uniformGrid by remember { Injekt.get<yokai.domain.ui.UiPreferences>().uniformGrid() }.collectAsState()
        val useStaggeredGrid by preferences.useStaggeredGrid().collectAsState()
        val groupLibraryBy by preferences.groupLibraryBy().collectAsState()
        val collapsedCategories by preferences.collapsedCategories().collectAsState()
        val collapsedCategoriesPref = remember { preferences.collapsedCategories() }
        val showCategoryInTitle by preferences.showCategoryInTitle().collectAsState()
        val showCategoryItemCounts by preferences.categoryNumberOfItems().collectAsState()
        val hideHopper by preferences.hideHopper().collectAsState()
        val autohideHopper by preferences.autohideHopper().collectAsState()
        val hopperLongPressAction by preferences.hopperLongPressAction().collectAsState()
        // Per-cover badge / outline prefs collected reactively so toggles in the Display
        // options sheet propagate to the grid immediately.
        val outlineOnCovers by remember { Injekt.get<yokai.domain.ui.UiPreferences>().outlineOnCovers() }.collectAsState()
        val showDownloadBadge by preferences.downloadBadge().collectAsState()
        val showLanguageBadge by preferences.languageBadge().collectAsState()
        val unreadBadgeType by preferences.unreadBadgeType().collectAsState()
        val showEmptyCategoriesWhileFiltering by preferences.showEmptyCategoriesWhileFiltering().collectAsState()
        val showAllCategories by preferences.showAllCategories().collectAsState()
        val hideStartReadingButton by preferences.hideStartReadingButton().collectAsState()
        val useLargeToolbar by preferences.useLargeToolbar().collectAsState()
        val hopperGravityPref = remember { preferences.hopperGravity() }
        val hopperGravity by hopperGravityPref.changes()
            .collectAsState(initial = hopperGravityPref.get())

        // Column count derived from preferences.gridSize() via the legacy formula (see
        // [columnsForGridValue] for the math). Pref Float maps to a slider int 0..7; we use the
        // current screen width to compute exactly the column count shown in the Display tab's
        // "Portrait: X • Landscape: Y" subtitle, so the user sees the grid render the same N
        // they pick in the picker.
        val gridSizePref by preferences.gridSize().collectAsState()
        val sliderValue = ((gridSizePref + 0.5f) * 2f).coerceIn(0f, 7f)
        val columns = columnsForGridValue(sliderValue, LocalConfiguration.current.screenWidthDp)

        // Filter prefs collected reactively so chip taps in the sheet flow through to the grid
        // without dismiss-and-reopen.
        val filterUnread by preferences.filterUnread().collectAsState()
        val filterDownloaded by preferences.filterDownloaded().collectAsState()
        val filterCompleted by preferences.filterCompleted().collectAsState()
        val filterBookmarked by preferences.filterBookmarked().collectAsState()
        val filterContentType by preferences.filterContentType().collectAsState()
        val filterMangaType by preferences.filterMangaType().collectAsState()
        val filterTracked by preferences.filterTracked().collectAsState()
        // FILTER_TRACKER is a JVM static (not a Flow); read it as initial state. The sheet
        // updates this var directly, and recomposes here happen via other-pref changes when
        // tracker-only changes also bump filterTracked.
        val filterTracker = remember(filterTracked) { FilterBottomSheet.FILTER_TRACKER }

        var searchActive by rememberSaveable { mutableStateOf(false) }
        var searchQuery by rememberSaveable { mutableStateOf("") }

        var sheetOpen by rememberSaveable { mutableStateOf(false) }
        var sheetTab by rememberSaveable { mutableIntStateOf(0) }
        var overflowOpen by remember { mutableStateOf(false) }
        // Standalone Group library by picker dialog (hopper long-press index 3). Matches legacy
        // showGroupOptions() which opens just the picker, not the full Display options sheet.
        var groupByDialogOpen by remember { mutableStateOf(false) }

        // C3: mark-as-read / mark-as-unread confirmation dialog state. Null = closed; non-null
        // carries the `markRead` flag the dialog dispatches on confirm.
        var markReadConfirmFor by remember { mutableStateOf<Boolean?>(null) }
        // C5: delete-from-library dialog state. True = open. Within the dialog, the user
        // toggles whether to remove from library (always also deletes downloads).
        var deleteConfirmOpen by remember { mutableStateOf(false) }

        val library = when (val s = state) {
            is LibraryTabState.Loading -> emptyMap()
            is LibraryTabState.Loaded -> s.library
        }
        val isRunning = (state as? LibraryTabState.Loaded)?.isRunning ?: false
        val inQueueCategoryIds = (state as? LibraryTabState.Loaded)?.inQueueCategoryIds ?: emptySet()
        val currentCategoryOrder = (state as? LibraryTabState.Loaded)?.currentCategoryOrder ?: 0
        val selection = (state as? LibraryTabState.Loaded)?.selection ?: emptySet()

        // C6: derived flag for migrate visibility. True when at least one selected manga has a
        // non-local source. Mirrors LibraryController.kt:2042
        // (`migrate.isVisible = selectedMangas.any { it.source != LocalSource.ID }`).
        val selectionHasRemoteSources = remember(selection, library) {
            if (selection.isEmpty()) {
                false
            } else {
                library.values.asSequence()
                    .flatten()
                    .map { it.libraryManga.manga }
                    .filter { it.id in selection }
                    .any { it.source != LocalSource.ID }
            }
        }

        // C7: merge requires 2+ selected; unmerge requires every selected manga to already be in
        // a manual-merge group. The unmerge gate reads mangaManualMerges reactively so toggling
        // a merge from another surface (manga details page) updates the menu without re-entering
        // the library.
        val mangaManualMerges by preferences.mangaManualMerges().changes()
            .collectAsState(initial = preferences.mangaManualMerges().get())
        val canMerge = selection.size >= 2
        val canUnmerge = remember(selection, mangaManualMerges) {
            if (selection.isEmpty()) {
                false
            } else {
                // Unmerge only when EVERY selected manga is in a merge group. If a standalone
                // is in the mix, the user probably wants to add it to the existing group via
                // Merge, not dissolve the group. mergeSelection() expands selection to include
                // every group member so the existing group survives the merge's "drop entries
                // containing any of these ids" pruning.
                val allMergedIds = mangaManualMerges
                    .asSequence()
                    .flatMap { entry -> entry.split(",").asSequence() }
                    .mapNotNull { it.trim().toLongOrNull() }
                    .toSet()
                selection.all { it in allMergedIds }
            }
        }

        val snackbarHostState = remember { SnackbarHostState() }
        // F10: dismissing the snackbar before navigating away triggers the showSnackbar
        // coroutine's Dismissed branch, which queues the cleanup commit (confirmDeletion /
        // confirmMarkReadStatus) on screenModelScope. Matches legacy's controller-change
        // listener at MainActivity.kt:680 which dismisses the undo snackbar on any push.
        val dismissPendingSnackbar = { snackbarHostState.currentSnackbarData?.dismiss(); Unit }
        // Cancel-action strings need a Composable scope for stringResource; capture once outside
        // the snackbar lambdas so we don't recompute per dispatch.
        val updatingLibraryText = stringResource(MR.strings.updating_library)
        val updatingCategoryFmt = stringResource(MR.strings.updating_)
        val addingToQueueFmt = stringResource(MR.strings.adding_category_to_queue)
        val alreadyInQueueFmt = stringResource(MR.strings._already_in_queue)
        val cancelText = stringResource(MR.strings.cancel)
        // C3 snackbar texts captured once at the Composable scope.
        val markedAsReadText = stringResource(MR.strings.marked_as_read)
        val markedAsUnreadText = stringResource(MR.strings.marked_as_unread)
        val undoText = stringResource(MR.strings.undo)
        // C5 dialog + snackbar texts.
        val removedFromLibraryText = stringResource(MR.strings.removed_from_library)
        val removeText = stringResource(MR.strings.remove)
        val removeFromLibraryLabel = stringResource(MR.strings.remove_from_library)
        val removeDownloadsLabel = stringResource(MR.strings.remove_downloads)

        val sourceNames = remember(library) {
            library.values
                .asSequence()
                .flatten()
                .map { it.libraryManga.manga.source }
                .distinct()
                .associateWith { sourceManager.getOrStub(it).name }
        }

        // seriesType is derived from genre tags + source name, so it changes when the library is
        // refreshed (genre updates can land via tracker sync or source-side metadata refreshes).
        // Recompute per-library map so it stays in sync with the rendered cells. Pure string
        // build per manga — cheap relative to the search itself.
        val seriesContext = preferences.context
        val seriesTypes = remember(library, seriesContext) {
            library.values
                .asSequence()
                .flatten()
                .mapNotNull { item ->
                    val m = item.libraryManga.manga
                    val id = m.id ?: return@mapNotNull null
                    id to m.seriesType(seriesContext, sourceManager)
                }
                .toMap()
        }

        val searchedLibrary = remember(library, searchQuery, sourceNames, seriesTypes) {
            MangaLibrarySearch.search(library, searchQuery, sourceNames, seriesTypes)
        }

        // The filter is suspend (track lookups) but we want a synchronous Compose result. Run
        // it on the IO dispatcher via a produceState-style coroutine and surface via state.
        val filterState = MangaFilterState(
            downloaded = filterDownloaded,
            unread = filterUnread,
            completed = filterCompleted,
            tracked = filterTracked,
            mangaType = filterMangaType,
            contentType = filterContentType,
            bookmarked = filterBookmarked,
            tracker = filterTracker,
        )
        val loggedServiceNames = remember(trackManager) {
            trackManager.services
                .filter { it.isLogged }
                .associate { it.id to preferences.context.getString(it.nameRes()) }
        }
        val filteredLibrary by androidx.compose.runtime.produceState(
            initialValue = searchedLibrary,
            key1 = searchedLibrary,
            key2 = filterState,
            key3 = showAllCategories,
        ) {
            value = if (!filterState.isAnyActive) searchedLibrary
            else kotlinx.coroutines.withContext(Dispatchers.Default) {
                MangaLibraryFilter.filter(
                    library = searchedLibrary,
                    state = filterState,
                    sourceManager = sourceManager,
                    loggedServiceNames = loggedServiceNames,
                    getDownloadCount = { manga -> downloadManager.getDownloadCount(manga) },
                    getTracks = { mangaId -> getTrack.awaitAllByMangaId(mangaId) },
                    keepEmptyCategories = showAllCategories,
                )
            }
        }

        val detectedMangaTypes = remember(library) {
            MangaLibraryFilter.detectMangaTypes(library, sourceManager)
        }
        val loggedTrackerNames = remember(loggedServiceNames) {
            loggedServiceNames.values.toList()
        }

        val allCategories = remember(library) { library.keys.toList() }
        val categoryItemCounts = remember(library) {
            library.entries.associate { (cat, items) -> (cat.id ?: 0) to items.size }
        }

        // Filter pipeline for category visibility:
        //
        //  - showAllCategories = true → always keep every category key, even if no manga match.
        //    The filter call already respects this via keepEmptyCategories, but search has its
        //    own .filterValues drop, so we also re-introduce keys at the screen level. This
        //    covers the search-only path where the filter never runs.
        //  - showAllCategories = false + showEmptyCategoriesWhileFiltering = true while
        //    actively narrowing (search query or active filter) → re-introduce empties so the
        //    user keeps category headers visible while they hunt for a specific result.
        //  - Otherwise → drop empties (current default).
        val postFilterLibrary = when {
            showAllCategories ->
                library.mapValues { (cat, _) -> filteredLibrary[cat].orEmpty() }
            showEmptyCategoriesWhileFiltering &&
                (searchQuery.isNotEmpty() || filterState.isAnyActive) ->
                library.mapValues { (cat, _) -> filteredLibrary[cat].orEmpty() }
            else -> filteredLibrary
        }

        // Header counts are sourced from the pre-collapse, post-filter map so collapsing a
        // category does not zero out its header. Counts shown on the header therefore reflect
        // how many of the user's filtered items live in that category.
        val displayedHeaderCounts = remember(postFilterLibrary) {
            postFilterLibrary.entries.associate { (cat, items) -> (cat.id ?: 0) to items.size }
        }

        // Collapse only kicks in for default grouping (groupLibraryBy = BY_DEFAULT). Dynamic
        // groupings have their own collapse pref (collapsedDynamicCategories) which Phase 6
        // will wire alongside multi-source grouping; until then the header is non-interactive
        // for dynamic groups.
        val collapsible = groupLibraryBy == eu.kanade.tachiyomi.ui.library.LibraryGroup.BY_DEFAULT
        val collapsedIds = remember(collapsedCategories) {
            collapsedCategories.mapNotNullTo(HashSet()) { it.toIntOrNull() }
        }
        val displayedLibrary = if (collapsible && collapsedIds.isNotEmpty()) {
            postFilterLibrary.mapValues { (cat, items) ->
                if (cat.id != null && cat.id in collapsedIds) emptyList() else items
            }
        } else {
            postFilterLibrary
        }

        // Faithful port of legacy LibraryPresenter.kt:327-340 / 352-372: when showAllCategories
        // is off AND the library is grouped by default (user categories) AND there is more than
        // one category, render only the active category's items. The active category is the one
        // whose order matches the last-used pref (same backing pref legacy reads). Hopper and
        // category picker dispatch through onActiveCategoryChange to switch which category is
        // rendered, mirroring presenter.setCurrentCategory in legacy.
        val lastUsedCategoryOrder by preferences.lastUsedCategory().collectAsState()
        val singleCategoryMode = !showAllCategories &&
            groupLibraryBy == eu.kanade.tachiyomi.ui.library.LibraryGroup.BY_DEFAULT &&
            allCategories.size > 1
        val activeCategoryInSingleMode = if (singleCategoryMode) {
            // Resolve the active category against the unfiltered user-category list so the pref
            // still picks a target even when the active category filtered to empty (legacy
            // renders the active category as empty in that case rather than jumping to another).
            allCategories.firstOrNull { it.order == lastUsedCategoryOrder }
                ?: allCategories.first()
        } else {
            null
        }
        val finalLibrary = if (activeCategoryInSingleMode != null) {
            mapOf(activeCategoryInSingleMode to (displayedLibrary[activeCategoryInSingleMode].orEmpty()))
        } else {
            displayedLibrary
        }

        LibraryContent(
            library = finalLibrary,
            singleCategoryMode = singleCategoryMode,
            allCategories = allCategories,
            categoryItemCounts = categoryItemCounts,
            displayedHeaderCounts = displayedHeaderCounts,
            collapsedIds = collapsedIds,
            collapsibleHeaders = collapsible,
            showCategoryItemCounts = showCategoryItemCounts,
            columns = columns,
            libraryLayout = libraryLayout,
            uniformGrid = uniformGrid,
            useStaggeredGrid = useStaggeredGrid,
            searchActive = searchActive,
            searchQuery = searchQuery,
            showCategoryInTitle = showCategoryInTitle,
            hideHopper = hideHopper,
            autohideHopper = autohideHopper,
            hopperGravity = hopperGravity,
            outlineOnCovers = outlineOnCovers,
            showDownloadBadge = showDownloadBadge,
            showLanguageBadge = showLanguageBadge,
            unreadBadgeType = unreadBadgeType,
            hideStartReadingButton = hideStartReadingButton,
            useLargeToolbar = useLargeToolbar,
            isAnyFilterActive = filterState.isAnyActive,
            showAllCategories = showAllCategories,
            isRunning = isRunning,
            inQueueCategoryIds = inQueueCategoryIds,
            snackbarHostState = snackbarHostState,
            sheetOpen = sheetOpen,
            sheetTab = sheetTab,
            overflowOpen = overflowOpen,
            detectedMangaTypes = detectedMangaTypes,
            loggedTrackerNames = loggedTrackerNames,
            selection = selection,
            onSearchActiveChange = { searchActive = it },
            onSearchQueryChange = { searchQuery = it },
            onHopperGravityChange = { hopperGravityPref.set(it) },
            onToggleCategoryCollapse = { category ->
                val id = category.id?.toString() ?: return@LibraryContent
                val current = collapsedCategoriesPref.get().toMutableSet()
                if (!current.add(id)) current.remove(id)
                collapsedCategoriesPref.set(current)
            },
            hopperLongPressAction = hopperLongPressAction,
            onExpandCollapseAllCategories = {
                // Mirror the Categories tab Expand/Collapse all toggle: when nothing is
                // collapsed, collapse every category id; otherwise clear the set. Only
                // meaningful under BY_DEFAULT grouping, but the long-press action is global so
                // we apply the toggle unconditionally; under dynamic grouping the pref simply
                // has no visible effect until the user switches back to default.
                val all = library.keys.mapNotNull { it.id?.toString() }.toSet()
                val current = collapsedCategoriesPref.get()
                collapsedCategoriesPref.set(if (current.isEmpty()) all else emptySet())
            },
            onOpenSheetAt = { tabIndex ->
                sheetTab = tabIndex
                sheetOpen = true
            },
            onOpenRandomSeries = {
                // Random pick from the post-collapse displayed library: collapsed categories
                // are implicitly hidden, so picking a manga the user just chose to hide would
                // feel inconsistent. Empty library (no favorited manga) silently no-ops.
                val pool = displayedLibrary.values.asSequence().flatten().toList()
                if (pool.isNotEmpty()) {
                    val random = pool.random().libraryManga.manga
                    dismissPendingSnackbar()
                    router.pushController(MangaDetailsController(random).withFadeTransaction())
                }
            },
            onOpenRandomInCategory = { category ->
                // In-category random for hopper long-press index 4. Falls back to the global
                // pool when no category is resolvable (search active, no items in scope).
                val pool = when {
                    category != null -> displayedLibrary[category].orEmpty()
                    else -> displayedLibrary.values.asSequence().flatten().toList()
                }
                if (pool.isNotEmpty()) {
                    val random = pool.random().libraryManga.manga
                    dismissPendingSnackbar()
                    router.pushController(MangaDetailsController(random).withFadeTransaction())
                }
            },
            onOpenGroupByPicker = { groupByDialogOpen = true },
            onContinueReading = { manga ->
                // Mirror legacy LibraryController.startReading: load all chapters, pick the
                // next-unread via ChapterSort, then launch ReaderActivity. Manga with no
                // remaining unread chapters silently no-op (the button is gated on unread > 0
                // upstream so this is only reachable in a race where the count changes).
                coroutineScope.launch {
                    val chapters = getChapter.awaitAll(manga)
                    val next = ChapterSort(manga).getNextUnreadChapter(chapters, false)
                        ?: return@launch
                    val activity = router.activity ?: return@launch
                    dismissPendingSnackbar()
                    activity.startActivity(ReaderActivity.newIntent(activity, manga, next))
                }
            },
            onMangaClick = { manga ->
                // Same destination + transition as legacy LibraryController.openManga (which
                // does router.pushController(MangaDetailsController(manga).withFadeTransaction())).
                // Routes through the existing Conductor router for now since no Compose-side
                // manga details Voyager screen exists yet.
                dismissPendingSnackbar()
                router.pushController(MangaDetailsController(manga).withFadeTransaction())
            },
            onOpenFilter = { sheetTab = 0; sheetOpen = true },
            onOpenOverflow = { overflowOpen = true },
            onDismissSheet = { sheetOpen = false },
            onDismissOverflow = { overflowOpen = false },
            onSheetTabChange = { sheetTab = it },
            onActiveCategoryChange = { category ->
                // Skip the default category (order = -1, injected by MangaLibrarySectioner when
                // a user has no real categories) so we never persist a synthetic order to the
                // legacy-shared pref.
                if (category.order >= 0) {
                    preferences.lastUsedCategory().set(category.order)
                }
            },
            onPullToRefresh = {
                // Faithful port of legacy LibraryController.setSwipeRefresh (lines 702-717):
                //   - !showAllCategories + BY_DEFAULT grouping → refresh currentCategory
                //   - !showAllCategories + dynamic grouping → updateCategory(0); unreachable in
                //     Compose Phase 4 because dynamic grouping is Phase 6 work, folded into the
                //     all-categories branch as a defensive default.
                //   - showAllCategories → refresh all categories.
                //
                // The isRunning guard happens upstream in LibraryContent (PTR is disabled while
                // running); legacy mirrors this via the `!LibraryUpdateJob.isRunning(context)`
                // check before dispatching.
                val target = if (showAllCategories) {
                    null
                } else {
                    allCategories.find { it.order == currentCategoryOrder }
                }
                screenModel.refresh(target)
                coroutineScope.launch {
                    val result = snackbarHostState.showSnackbar(
                        message = updatingLibraryText,
                        actionLabel = cancelText,
                        duration = SnackbarDuration.Short,
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        screenModel.stopRefresh()
                    }
                }
            },
            onToggleSelection = { id -> screenModel.toggleSelection(id) },
            onClearSelection = { screenModel.clearSelection() },
            onToggleCategorySelection = { categoryId -> screenModel.toggleCategorySelection(categoryId) },
            onShareSelection = {
                // Faithful port of legacy LibraryController.shareManga at LibraryController.kt:2174.
                // Local-source manga are filtered out inside MangaLibraryActions.share; if the
                // entire selection is local, the URL list is empty and we no-op (no chooser).
                // Unlike legacy (which leaves the action mode alive after a share, per scout
                // finding #3), the Compose port dismisses selection on dispatch for consistency
                // with every other action.
                val urls = screenModel.shareSelection()
                if (urls.isNotEmpty()) {
                    val activity = router.activity ?: return@LibraryContent
                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/*"
                        putExtra(android.content.Intent.EXTRA_TEXT, urls.joinToString("\n"))
                    }
                    val chooserTitle = activity.getString(MR.strings.share)
                    activity.startActivity(android.content.Intent.createChooser(intent, chooserTitle))
                }
                screenModel.clearSelection()
            },
            onDownloadUnread = {
                // Faithful port of legacy LibraryController:2088. Silent: no snackbar, no dialog;
                // the download notification surfaces progress. Selection clears immediately on
                // dispatch, matching every other action except mark-read / delete (which keep
                // selection alive until their undo snackbar resolves).
                screenModel.downloadUnreadSelection()
                screenModel.clearSelection()
            },
            onConfirmAndMarkRead = { markReadConfirmFor = true },
            onConfirmAndMarkUnread = { markReadConfirmFor = false },
            onConfirmAndDelete = { deleteConfirmOpen = true },
            selectionHasRemoteSources = selectionHasRemoteSources,
            canMerge = canMerge,
            canUnmerge = canUnmerge,
            onMerge = {
                // C7: merge dispatch. mergeSelection() guards on size >= 2 internally so the
                // disabled menu state is a UX hint, not the only enforcement.
                screenModel.mergeSelection()
                screenModel.clearSelection()
            },
            onUnmerge = {
                // C7: unmerge dispatch. Splits selected manga out of every merge group they
                // belong to; pair-unmerges block the same-title auto-grouping pass from
                // re-forming the group on next refreshRelatedMangaIds.
                screenModel.unmergeSelection()
                screenModel.clearSelection()
            },
            onMigrate = {
                // Faithful port of LibraryController.kt:2109-2117. Pure navigation, no presenter
                // call. Filter out LocalSource manga (already gated at the visibility layer, but
                // keep the inner filter defensive in case the gate races).
                val ids = screenModel.selectedMangaList()
                    .filter { it.source != LocalSource.ID }
                    .mapNotNull { it.id }
                if (ids.isEmpty()) return@LibraryContent
                dismissPendingSnackbar()
                PreMigrationController.navigateToMigration(
                    preferences.skipPreMigration().get(),
                    router,
                    ids,
                )
                screenModel.clearSelection()
            },
            onMoveToCategories = {
                // C4: bridge to legacy SetCategoriesSheet via the existing
                // `List<Manga>.moveCategories(activity, onMangaMoved)` extension at
                // MangaExtensions.kt:117. The sheet already handles common / mixed category
                // bucketing across multi-manga selections. updateLibrary is unnecessary in the
                // Compose path: the screen model collects getLibraryManga.subscribe(), which
                // re-emits when the category mapping changes.
                val mangas = screenModel.selectedMangaList()
                if (mangas.isEmpty()) return@LibraryContent
                val activity = router.activity ?: return@LibraryContent
                coroutineScope.launch {
                    mangas.moveCategories(activity) {
                        screenModel.clearSelection()
                    }
                }
            },
            onRefreshCategory = { category ->
                // Mirrors LibraryController.updateCategory (lines 1763-1802). Snackbar wording
                // is decided BEFORE the dispatch so the user sees "already in queue" or
                // "adding to queue" depending on the live job state.
                val inQueue = screenModel.isCategoryInQueue(category.id)
                val wasRunning = screenModel.isRunning()
                val message = when {
                    inQueue -> alreadyInQueueFmt.format(category.name)
                    wasRunning -> addingToQueueFmt.format(category.name)
                    else -> updatingCategoryFmt.format(category.name)
                }
                if (!inQueue) {
                    // mangaToUse is omitted here intentionally — dynamic categories aren't
                    // rendered in Compose Phase 4, so the dynamic branch is unreachable. Phase 6
                    // extends LibraryUpdater to thread that list through.
                    screenModel.refresh(category)
                }
                coroutineScope.launch {
                    val result = snackbarHostState.showSnackbar(
                        message = message,
                        actionLabel = cancelText,
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        screenModel.stopRefresh()
                    }
                }
            },
        )

        if (groupByDialogOpen) {
            yokai.presentation.library.components.GroupLibraryByDialog(
                selected = groupLibraryBy,
                entries = yokai.presentation.library.components.rememberGroupByEntries(),
                onSelect = { preferences.groupLibraryBy().set(it) },
                onDismiss = { groupByDialogOpen = false },
            )
        }

        // C3: mark-as-read / mark-as-unread confirmation dialog + undo snackbar. Faithful port of
        // LibraryController.kt:2091-2107 (the AlertDialog) + :2142-2172 (the snackbar with undo
        // and the dismissal-triggered confirm cleanup).
        markReadConfirmFor?.let { markRead ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { markReadConfirmFor = null },
                text = {
                    androidx.compose.material3.Text(
                        text = stringResource(
                            if (markRead) MR.strings.mark_all_chapters_as_read
                            else MR.strings.mark_all_chapters_as_unread,
                        ),
                    )
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(
                        onClick = {
                            markReadConfirmFor = null
                            coroutineScope.launch {
                                val snapshot = screenModel.markReadStatus(markRead = markRead)
                                screenModel.clearSelection()
                                val message = if (markRead) markedAsReadText else markedAsUnreadText
                                val result = snackbarHostState.showSnackbar(
                                    message = message,
                                    actionLabel = undoText,
                                    duration = SnackbarDuration.Long,
                                )
                                if (result == SnackbarResult.ActionPerformed) {
                                    screenModel.undoMarkReadStatus(snapshot)
                                } else {
                                    screenModel.confirmMarkReadStatus(snapshot, markRead)
                                }
                            }
                        },
                    ) {
                        androidx.compose.material3.Text(
                            text = stringResource(
                                if (markRead) MR.strings.mark_as_read else MR.strings.mark_as_unread,
                            ),
                        )
                    }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { markReadConfirmFor = null }) {
                        androidx.compose.material3.Text(text = cancelText)
                    }
                },
            )
        }

        // C5: delete confirmation dialog + undo snackbar. Faithful port of
        // LibraryController.kt:2057-2086 (dialog) + :2187-2222 (undo snackbar) + presenter
        // confirmDeletion at LibraryPresenter.kt:1465. Downloads are always deleted (the
        // checkbox is rendered disabled-but-checked to mirror legacy's disableItems); only
        // "Remove from library" is toggleable. Two outcomes:
        //   - Both checked (default): immediate favorite=false, snackbar with Undo; on Undo
        //     reAddToLibrary, on dismiss confirmDeletion(coverCacheToo=true) runs the full
        //     destructive cleanup (tracks, downloads, cover).
        //   - Library unchecked: confirmDeletion(coverCacheToo=false) runs immediately and
        //     wipes downloaded chapters only; no snackbar.
        if (deleteConfirmOpen) {
            var removeFromLibrary by remember { mutableStateOf(true) }
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { deleteConfirmOpen = false },
                title = { androidx.compose.material3.Text(text = removeText) },
                text = {
                    androidx.compose.foundation.layout.Column {
                        androidx.compose.foundation.layout.Row(
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            androidx.compose.material3.Checkbox(checked = true, enabled = false, onCheckedChange = null)
                            androidx.compose.material3.Text(text = removeDownloadsLabel)
                        }
                        androidx.compose.foundation.layout.Row(
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            androidx.compose.material3.Checkbox(
                                checked = removeFromLibrary,
                                onCheckedChange = { removeFromLibrary = it },
                            )
                            androidx.compose.material3.Text(text = removeFromLibraryLabel)
                        }
                    }
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(
                        onClick = {
                            deleteConfirmOpen = false
                            val mangas = screenModel.selectedMangaList()
                            if (mangas.isEmpty()) return@TextButton
                            if (removeFromLibrary) {
                                screenModel.removeFromLibrary()
                                screenModel.clearSelection()
                                coroutineScope.launch {
                                    val result = snackbarHostState.showSnackbar(
                                        message = removedFromLibraryText,
                                        actionLabel = undoText,
                                        duration = SnackbarDuration.Long,
                                    )
                                    if (result == SnackbarResult.ActionPerformed) {
                                        screenModel.reAddToLibrary(mangas)
                                    } else {
                                        screenModel.confirmDeletion(mangas, coverCacheToo = true)
                                    }
                                }
                            } else {
                                screenModel.confirmDeletion(mangas, coverCacheToo = false)
                                screenModel.clearSelection()
                            }
                        },
                    ) {
                        androidx.compose.material3.Text(text = removeText)
                    }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { deleteConfirmOpen = false }) {
                        androidx.compose.material3.Text(text = cancelText)
                    }
                },
            )
        }
    }
}
