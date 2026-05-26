package yokai.presentation.library

import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import eu.kanade.tachiyomi.util.system.getResourceColor
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
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.ui.library.models.LibraryItem
import yokai.presentation.library.manga.MangaLibraryFilter
import yokai.presentation.library.manga.MangaLibraryFilter.MangaFilterState
import yokai.presentation.library.manga.MangaLibraryGridCell
import yokai.presentation.library.manga.MangaLibraryListItem
import yokai.presentation.library.manga.MangaLibraryScreenModel
import yokai.presentation.library.manga.MangaLibrarySearch
import yokai.presentation.library.novels.NovelLibraryScreenModel
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
        val uiPrefs = remember { Injekt.get<yokai.domain.ui.UiPreferences>() }
        // Both screen models live at the LibraryScreen scope so a tab switch does not destroy
        // the inactive one (Voyager's rememberScreenModel is keyed per-Screen instance).
        // Selection, scroll position, filters, and search state per-tab survive a switch +
        // come back exactly as the user left them.
        val mangaScreenModel = rememberScreenModel { MangaLibraryScreenModel() }
        val novelScreenModel = rememberScreenModel { NovelLibraryScreenModel() }
        var activeTab by rememberSaveable {
            mutableIntStateOf(uiPrefs.libraryActiveTab().get().coerceIn(0, 1))
        }
        // Persist the active tab so cold start lands on whichever the user last viewed (Phase 8
        // C1 added the underlying int pref). LaunchedEffect writes on every change; the pref
        // store debounces if needed.
        LaunchedEffect(activeTab) {
            uiPrefs.libraryActiveTab().set(activeTab)
        }

        // Tab row hoisted to a shared composable. Each per-tab content passes it through to
        // LibraryContent.topBarBelow so the tabs render flush under the Scaffold's TopAppBar
        // instead of floating as a separate row above it. Selection / search modes still
        // preempt the whole top chrome (LibraryContent hides topBarBelow then), matching the
        // legacy ActionMode convention that also hides tabs during selection.
        val tabRow: @Composable () -> Unit = {
            PrimaryTabRow(selectedTabIndex = activeTab) {
                Tab(
                    selected = activeTab == 0,
                    onClick = { activeTab = 0 },
                    text = { Text(stringResource(MR.strings.manga)) },
                )
                Tab(
                    selected = activeTab == 1,
                    onClick = { activeTab = 1 },
                    text = { Text(stringResource(MR.strings.light_novels)) },
                )
            }
        }
        when (activeTab) {
            0 -> MangaLibraryTabContent(mangaScreenModel, tabRow)
            else -> NovelLibraryTabContent(novelScreenModel, tabRow)
        }
    }

    /**
     * Novel tab body. Mirrors [MangaLibraryTabContent] for novels: collects novel + shared
     * display prefs, runs the library through [NovelLibrarySearch] then [NovelLibraryFilter],
     * threads the filtered map into the generic [LibraryContent] with novel-side renderer
     * lambdas + callbacks routed through [NovelLibraryScreenModel] / [NovelLibraryActions].
     *
     * Diverges from manga in places locked by Phase 7 decisions:
     * - **No migrate** (Decision #1): `onMigrate` no-ops; `selectionHasRemoteSources` always
     *   false so the menu entry stays hidden.
     * - **No mangaType / contentType / NSFW filter** (Decision #3): [NovelLibraryFilter] has no
     *   such state; `detectedMangaTypes` is empty.
     * - **No language filter dimension** (no language field on Novel).
     * - **Download-unread stub** (Decision #4): dispatches the screen-model action which is a
     *   no-op until novel downloads ship.
     * - **No `moveCategories` bridge** yet on the novel side; `onMoveToCategories` shows a
     *   "not yet wired" snackbar so the menu entry exists but doesn't lie about working.
     */
    @Composable
    private fun NovelLibraryTabContent(
        screenModel: NovelLibraryScreenModel,
        tabRow: @Composable () -> Unit,
    ) {
        val state by screenModel.state.collectAsState()
        val preferences: PreferencesHelper = remember { Injekt.get() }
        val novelPrefs: yokai.domain.novel.NovelPreferences = remember {
            org.koin.core.context.GlobalContext.get().get()
        }
        val novelSourceManager: yokai.novel.source.NovelSourceManager = remember {
            org.koin.core.context.GlobalContext.get().get()
        }
        val novelTrackRepository: yokai.domain.novel.NovelTrackRepository = remember {
            org.koin.core.context.GlobalContext.get().get()
        }
        val router = LocalRouter.currentOrThrow
        val coroutineScope = rememberCoroutineScope()

        // Shared display prefs (same as manga side — these are visual prefs that apply
        // uniformly to library cells regardless of content type).
        val libraryLayout by preferences.libraryLayout().collectAsState()
        val uniformGrid by remember { Injekt.get<yokai.domain.ui.UiPreferences>().uniformGrid() }.collectAsState()
        val useStaggeredGrid by preferences.useStaggeredGrid().collectAsState()
        val showCategoryInTitle by preferences.showCategoryInTitle().collectAsState()
        val showCategoryItemCounts by preferences.categoryNumberOfItems().collectAsState()
        val hideHopper by preferences.hideHopper().collectAsState()
        val autohideHopper by preferences.autohideHopper().collectAsState()
        val hopperLongPressAction by preferences.hopperLongPressAction().collectAsState()
        val outlineOnCovers by remember { Injekt.get<yokai.domain.ui.UiPreferences>().outlineOnCovers() }.collectAsState()
        val showDownloadBadge by preferences.downloadBadge().collectAsState()
        val showLanguageBadge by preferences.languageBadge().collectAsState()
        val unreadBadgeType by preferences.unreadBadgeType().collectAsState()
        val hideStartReadingButton by preferences.hideStartReadingButton().collectAsState()
        val useLargeToolbar by preferences.useLargeToolbar().collectAsState()
        val hopperGravityPref = remember { preferences.hopperGravity() }
        val hopperGravity by hopperGravityPref.changes()
            .collectAsState(initial = hopperGravityPref.get())

        val gridSizePref by preferences.gridSize().collectAsState()
        val sliderValue = ((gridSizePref + 0.5f) * 2f).coerceIn(0f, 7f)
        val columns = columnsForGridValue(sliderValue, LocalConfiguration.current.screenWidthDp)

        // Novel-specific prefs (Phase 7 C23). Filter / sort / grouping / show-all-categories
        // live on NovelPreferences so the manga + novel libraries stay independently
        // configurable.
        val groupLibraryBy by novelPrefs.groupLibraryBy().collectAsState()
        val collapsedCategories by novelPrefs.collapsedCategories().collectAsState()
        val collapsedCategoriesPref = remember { novelPrefs.collapsedCategories() }
        val showEmptyCategoriesWhileFiltering by novelPrefs.showEmptyCategoriesWhileFiltering().collectAsState()
        val showAllCategories by novelPrefs.showAllCategories().collectAsState()

        val filterUnread by novelPrefs.filterUnread().collectAsState()
        val filterDownloaded by novelPrefs.filterDownloaded().collectAsState()
        val filterCompleted by novelPrefs.filterCompleted().collectAsState()
        val filterBookmarked by novelPrefs.filterBookmarked().collectAsState()
        val filterTracked by novelPrefs.filterTracked().collectAsState()

        var searchActive by rememberSaveable { mutableStateOf(false) }
        var searchQuery by rememberSaveable { mutableStateOf("") }

        var sheetOpen by rememberSaveable { mutableStateOf(false) }
        var sheetTab by rememberSaveable { mutableIntStateOf(0) }
        var overflowOpen by remember { mutableStateOf(false) }
        var groupByDialogOpen by remember { mutableStateOf(false) }

        var markReadConfirmFor by remember { mutableStateOf<Boolean?>(null) }
        var deleteConfirmOpen by remember { mutableStateOf(false) }

        val library = when (val s = state) {
            is LibraryTabState.Loading -> emptyMap()
            is LibraryTabState.Loaded -> s.library
        }
        val isRunning = (state as? LibraryTabState.Loaded)?.isRunning ?: false
        val inQueueCategoryIds = (state as? LibraryTabState.Loaded)?.inQueueCategoryIds ?: emptySet()
        val currentCategoryOrder = (state as? LibraryTabState.Loaded)?.currentCategoryOrder ?: 0
        val selection = (state as? LibraryTabState.Loaded)?.selection ?: emptySet()

        // Decision #1: novels have no migrate path, so the gate is always false (menu entry
        // stays hidden via LibraryContent's `selectionHasRemoteSources` consumer).
        val selectionHasRemoteSources = false

        val novelManualMerges by novelPrefs.novelManualMerges().changes()
            .collectAsState(initial = novelPrefs.novelManualMerges().get())
        val canMerge = selection.size >= 2
        val canUnmerge = remember(selection, novelManualMerges) {
            if (selection.isEmpty()) {
                false
            } else {
                val allMergedIds = novelManualMerges
                    .asSequence()
                    .flatMap { entry -> entry.split(",").asSequence() }
                    .mapNotNull { it.trim().toLongOrNull() }
                    .toSet()
                selection.all { it in allMergedIds }
            }
        }

        val snackbarHostState = remember { SnackbarHostState() }
        val dismissPendingSnackbar = { snackbarHostState.currentSnackbarData?.dismiss(); Unit }

        // Dialog colors pinned to legacy theme attrs (mirrors manga path C12 / F12).
        val themeContext = LocalContext.current
        val dialogContainerColor = remember(themeContext) {
            Color(themeContext.getResourceColor(eu.kanade.tachiyomi.R.attr.colorSurface))
        }
        val dialogAccentColor = remember(themeContext) {
            Color(themeContext.getResourceColor(eu.kanade.tachiyomi.R.attr.colorPrimary))
        }
        val dialogOnAccentColor = remember(themeContext) {
            Color(themeContext.getResourceColor(eu.kanade.tachiyomi.R.attr.colorOnPrimary))
        }
        val dialogCheckboxColors = androidx.compose.material3.CheckboxDefaults.colors(
            checkedColor = dialogAccentColor,
            checkmarkColor = dialogOnAccentColor,
        )
        val dialogButtonColors = androidx.compose.material3.ButtonDefaults.textButtonColors(
            contentColor = dialogAccentColor,
        )

        val updatingLibraryText = stringResource(MR.strings.updating_library)
        val updatingCategoryFmt = stringResource(MR.strings.updating_)
        val addingToQueueFmt = stringResource(MR.strings.adding_category_to_queue)
        val alreadyInQueueFmt = stringResource(MR.strings._already_in_queue)
        val cancelText = stringResource(MR.strings.cancel)
        val markedAsReadText = stringResource(MR.strings.marked_as_read)
        val markedAsUnreadText = stringResource(MR.strings.marked_as_unread)
        val undoText = stringResource(MR.strings.undo)
        val removedFromLibraryText = stringResource(MR.strings.removed_from_library)
        val removeText = stringResource(MR.strings.remove)
        val removeFromLibraryLabel = stringResource(MR.strings.remove_from_library)
        val removeDownloadsLabel = stringResource(MR.strings.remove_downloads)

        // Novel source names map (lnreader plugin id → display name). Keyed by String per
        // Decision #1 (lnreader source ids are strings, unlike manga's Long).
        val sourceNames = remember(library) {
            library.values
                .asSequence()
                .flatten()
                .map { it.libraryNovel.novel.source }
                .distinct()
                .associateWith { novelSourceManager.get(it)?.name.orEmpty() }
        }

        val effectiveCategorySortOrder = (state as? LibraryTabState.Loaded)?.categorySortOrder ?: 0
        val searchedLibrary = remember(library, effectiveCategorySortOrder, searchQuery, sourceNames) {
            yokai.presentation.library.novels.NovelLibrarySearch.search(library, searchQuery, sourceNames)
        }

        val filterState = yokai.presentation.library.novels.NovelLibraryFilter.NovelFilterState(
            downloaded = filterDownloaded,
            unread = filterUnread,
            completed = filterCompleted,
            tracked = filterTracked,
            bookmarked = filterBookmarked,
            tracker = "",
        )
        // No `TrackManager` on the novel side yet (Decision #5). Pass an empty
        // logged-services map; the tracked filter then matches purely on presence/absence of
        // novel_tracks rows. When novel tracker UI ships, swap in a real services map.
        val loggedServiceNames: Map<Long, String> = emptyMap()
        val filteredLibrary by androidx.compose.runtime.produceState(
            initialValue = searchedLibrary,
            key1 = searchedLibrary,
            key2 = filterState,
            key3 = showAllCategories,
        ) {
            value = if (!filterState.isAnyActive) searchedLibrary
            else kotlinx.coroutines.withContext(Dispatchers.Default) {
                yokai.presentation.library.novels.NovelLibraryFilter.filter(
                    library = searchedLibrary,
                    state = filterState,
                    loggedServiceNames = loggedServiceNames,
                    // No novel download manager yet (Decision #4); fall back to the cached
                    // item.downloadCount only. The filter helper already prefers item.downloadCount
                    // when it's not -1, so this lambda is rarely consulted.
                    getDownloadCount = { 0 },
                    getTracks = { novelId -> novelTrackRepository.getByNovelId(novelId) },
                    keepEmptyCategories = showAllCategories,
                )
            }
        }

        val detectedMangaTypes = emptySet<Int>()
        val loggedTrackerNames = emptyList<String>()

        val allCategories = remember(library, effectiveCategorySortOrder) { library.keys.toList() }
        val categoryItemCounts = remember(library, effectiveCategorySortOrder) {
            library.entries.associate { (cat, items) -> (cat.id ?: 0) to items.size }
        }

        val rekeyedLibrary = library.mapValues { (cat, _) -> filteredLibrary[cat].orEmpty() }
        val postFilterLibrary = when {
            showAllCategories -> rekeyedLibrary
            showEmptyCategoriesWhileFiltering &&
                (searchQuery.isNotEmpty() || filterState.isAnyActive) -> rekeyedLibrary
            else -> rekeyedLibrary.filterValues { it.isNotEmpty() }
        }

        val displayedHeaderCounts = remember(postFilterLibrary) {
            postFilterLibrary.entries.associate { (cat, items) -> (cat.id ?: 0) to items.size }
        }

        val collapsibleHeaders = true
        val collapsedIds = remember(collapsedCategories) {
            collapsedCategories.mapNotNullTo(HashSet()) { it.toIntOrNull() }
        }
        val displayedLibrary = postFilterLibrary.mapValues { (cat, items) ->
            val collapsed = if (cat.isDynamic) {
                cat.isHidden
            } else {
                cat.id != null && cat.id in collapsedIds
            }
            if (collapsed) emptyList() else items
        }

        val lastUsedCategoryOrder by novelPrefs.lastUsedNovelCategory().collectAsState()
        val singleCategoryMode = !showAllCategories &&
            groupLibraryBy == eu.kanade.tachiyomi.ui.library.LibraryGroup.BY_DEFAULT &&
            allCategories.size > 1
        val activeCategoryInSingleMode = if (singleCategoryMode) {
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

        // Per-tab callbacks. Novel "open details" routes to the existing NovelDetailsController
        // (Phase 7 debug entry). When a Compose-side novel details Voyager screen lands, swap
        // this for a Navigator.push.
        val onNovelClick: (yokai.domain.novel.models.Novel) -> Unit = { novel ->
            dismissPendingSnackbar()
            router.pushController(
                eu.kanade.tachiyomi.ui.setting.controllers.debug.NovelDetailsController(
                    sourceId = novel.source,
                    novelUrl = novel.url,
                ).withFadeTransaction(),
            )
        }

        val novelListItemRenderer: @Composable (LibraryItem.Novel, Boolean, Boolean, Modifier) -> Unit = { item, isSelected, selectionActive, modifier ->
            yokai.presentation.library.novels.NovelLibraryListItem(
                item = item,
                isSelected = isSelected,
                selectionActive = selectionActive,
                modifier = modifier,
                showDownloadBadge = showDownloadBadge,
                showLanguageBadge = showLanguageBadge,
                unreadBadgeType = unreadBadgeType,
                onNovelClick = onNovelClick,
                onToggleSelection = { id -> screenModel.toggleSelection(id) },
            )
        }
        val novelGridItemRenderer: @Composable (LibraryItem.Novel, Boolean, Boolean, Modifier, Float?) -> Unit = { item, isSelected, selectionActive, modifier, coverAspectRatio ->
            yokai.presentation.library.novels.NovelLibraryGridCell(
                item = item,
                libraryLayout = libraryLayout,
                outlineOnCovers = outlineOnCovers,
                showDownloadBadge = showDownloadBadge,
                showLanguageBadge = showLanguageBadge,
                unreadBadgeType = unreadBadgeType,
                isSelected = isSelected,
                modifier = modifier,
                selectionActive = selectionActive,
                onNovelClick = onNovelClick,
                onNovelLongClick = { n -> n.id?.let { screenModel.toggleSelection(it) } },
                coverAspectRatio = coverAspectRatio,
            )
        }

        LibraryContent(
            library = finalLibrary,
            topBarBelow = tabRow,
            singleCategoryMode = singleCategoryMode,
            allCategories = allCategories,
            categoryItemCounts = categoryItemCounts,
            displayedHeaderCounts = displayedHeaderCounts,
            collapsedIds = collapsedIds,
            collapsibleHeaders = collapsibleHeaders,
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
                if (category.isDynamic) {
                    screenModel.toggleDynamicCategoryCollapse(category)
                } else {
                    val id = category.id?.toString() ?: return@LibraryContent
                    val current = collapsedCategoriesPref.get().toMutableSet()
                    if (!current.add(id)) current.remove(id)
                    collapsedCategoriesPref.set(current)
                }
            },
            hopperLongPressAction = hopperLongPressAction,
            onExpandCollapseAllCategories = {
                val all = library.keys.mapNotNull { it.id?.toString() }.toSet()
                val current = collapsedCategoriesPref.get()
                collapsedCategoriesPref.set(if (current.isEmpty()) all else emptySet())
            },
            onOpenSheetAt = { tabIndex ->
                sheetTab = tabIndex
                sheetOpen = true
            },
            onOpenRandomSeries = {
                val pool = displayedLibrary.values.asSequence().flatten().toList()
                if (pool.isNotEmpty()) {
                    val random = pool.random().libraryNovel.novel
                    dismissPendingSnackbar()
                    onNovelClick(random)
                }
            },
            onOpenRandomInCategory = { category ->
                val pool = when {
                    category != null -> displayedLibrary[category].orEmpty()
                    else -> displayedLibrary.values.asSequence().flatten().toList()
                }
                if (pool.isNotEmpty()) {
                    val random = pool.random().libraryNovel.novel
                    dismissPendingSnackbar()
                    onNovelClick(random)
                }
            },
            onOpenGroupByPicker = { groupByDialogOpen = true },
            listItemRenderer = novelListItemRenderer,
            gridItemRenderer = novelGridItemRenderer,
            onOpenFilter = { sheetTab = 0; sheetOpen = true },
            onOpenOverflow = { overflowOpen = true },
            onDismissSheet = { sheetOpen = false },
            onDismissOverflow = { overflowOpen = false },
            onSheetTabChange = { sheetTab = it },
            onActiveCategoryChange = { category ->
                if (category.order >= 0) {
                    novelPrefs.lastUsedNovelCategory().set(category.order)
                }
            },
            onPullToRefresh = {
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
                // Decision #4: stub. The screen-model action is a no-op until novel downloads
                // ship. UI surface stays consistent with manga so the menu entry is reachable
                // (and a future undefer wires real behavior without a UI change).
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
                screenModel.mergeSelection()
                screenModel.clearSelection()
            },
            onUnmerge = {
                screenModel.unmergeSelection()
                screenModel.clearSelection()
            },
            onMigrate = {
                // Decision #1: novels don't migrate. Gate above keeps the menu entry hidden.
            },
            onMoveToCategories = {
                // No novel-side moveCategories bridge yet (manga side bridges to the legacy
                // SetCategoriesSheet via List<Manga>.moveCategories). Show a snackbar so the
                // menu entry stays reachable but doesn't lie about working. Phase 8b /
                // follow-up wires the actual bridge.
                coroutineScope.launch {
                    snackbarHostState.showSnackbar(
                        message = "Move to category — coming for novels",
                        duration = SnackbarDuration.Short,
                    )
                }
            },
            categorySortOrder = effectiveCategorySortOrder,
            onRefreshCategory = { category ->
                val inQueue = screenModel.isCategoryInQueue(category.id)
                val wasRunning = screenModel.isRunning()
                val message = when {
                    inQueue -> alreadyInQueueFmt.format(category.name)
                    wasRunning -> addingToQueueFmt.format(category.name)
                    else -> updatingCategoryFmt.format(category.name)
                }
                if (!inQueue) {
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
            onSortChange = { category, mode -> screenModel.setSort(category, mode) },
        )

        if (groupByDialogOpen) {
            yokai.presentation.library.components.GroupLibraryByDialog(
                selected = groupLibraryBy,
                entries = yokai.presentation.library.components.rememberGroupByEntries(),
                onSelect = { novelPrefs.groupLibraryBy().set(it) },
                onDismiss = { groupByDialogOpen = false },
            )
        }

        markReadConfirmFor?.let { markRead ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { markReadConfirmFor = null },
                containerColor = dialogContainerColor,
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
                        colors = dialogButtonColors,
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
                    androidx.compose.material3.TextButton(
                        colors = dialogButtonColors,
                        onClick = { markReadConfirmFor = null },
                    ) {
                        androidx.compose.material3.Text(text = cancelText)
                    }
                },
            )
        }

        if (deleteConfirmOpen) {
            var removeFromLibrary by remember { mutableStateOf(true) }
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { deleteConfirmOpen = false },
                containerColor = dialogContainerColor,
                title = { androidx.compose.material3.Text(text = removeText) },
                text = {
                    androidx.compose.foundation.layout.Column(
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(
                            yokai.presentation.theme.Size.small,
                        ),
                    ) {
                        androidx.compose.foundation.layout.Row(
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(
                                yokai.presentation.theme.Size.small,
                            ),
                            modifier = Modifier
                                .alpha(0.5f)
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clickable(role = androidx.compose.ui.semantics.Role.Checkbox) {},
                        ) {
                            androidx.compose.material3.Checkbox(
                                checked = true,
                                onCheckedChange = null,
                                colors = dialogCheckboxColors,
                            )
                            androidx.compose.material3.Text(text = removeDownloadsLabel)
                        }
                        androidx.compose.foundation.layout.Row(
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(
                                yokai.presentation.theme.Size.small,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clickable(role = androidx.compose.ui.semantics.Role.Checkbox) {
                                    removeFromLibrary = !removeFromLibrary
                                },
                        ) {
                            androidx.compose.material3.Checkbox(
                                checked = removeFromLibrary,
                                onCheckedChange = null,
                                colors = dialogCheckboxColors,
                            )
                            androidx.compose.material3.Text(text = removeFromLibraryLabel)
                        }
                    }
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(
                        colors = dialogButtonColors,
                        onClick = {
                            deleteConfirmOpen = false
                            val novels = screenModel.selectedNovelListWithMergedSiblings()
                            if (novels.isEmpty()) return@TextButton
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
                                        screenModel.reAddToLibrary(novels)
                                    } else {
                                        screenModel.confirmDeletion(novels, coverCacheToo = true)
                                    }
                                }
                            } else {
                                screenModel.confirmDeletion(novels, coverCacheToo = false)
                                screenModel.clearSelection()
                            }
                        },
                    ) {
                        androidx.compose.material3.Text(text = removeText)
                    }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(
                        colors = dialogButtonColors,
                        onClick = { deleteConfirmOpen = false },
                    ) {
                        androidx.compose.material3.Text(text = cancelText)
                    }
                },
            )
        }
    }

    @Composable
    private fun MangaLibraryTabContent(
        screenModel: MangaLibraryScreenModel,
        tabRow: @Composable () -> Unit,
    ) {
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

        // F12: direct reads of the user's selected XML theme attrs for AlertDialog +
        // Checkbox + TextButton colors. Compose M3 AlertDialog applies tonal-elevation via
        // colorScheme.surfaceTint (defaults to colorScheme.primary), which produces a
        // different tint than the legacy elevationOverlayColor = ?colorSecondary at
        // styles.xml:200. Pinning the container + content colors to direct attr reads keeps
        // dialogs visually consistent with the legacy library across every user theme.
        val themeContext = LocalContext.current
        val dialogContainerColor = remember(themeContext) {
            Color(themeContext.getResourceColor(eu.kanade.tachiyomi.R.attr.colorSurface))
        }
        val dialogAccentColor = remember(themeContext) {
            Color(themeContext.getResourceColor(eu.kanade.tachiyomi.R.attr.colorPrimary))
        }
        val dialogOnAccentColor = remember(themeContext) {
            Color(themeContext.getResourceColor(eu.kanade.tachiyomi.R.attr.colorOnPrimary))
        }
        val dialogCheckboxColors = androidx.compose.material3.CheckboxDefaults.colors(
            checkedColor = dialogAccentColor,
            checkmarkColor = dialogOnAccentColor,
        )
        val dialogButtonColors = androidx.compose.material3.ButtonDefaults.textButtonColors(
            contentColor = dialogAccentColor,
        )
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

        // `Map<Category, ...>.equals` is content-only (ignores iteration order) and
        // `CategoryImpl.equals` is name-based, so two libraries that differ only by category
        // order compare equal. `remember(library, ...)` therefore returns cached values across a
        // pure `categorySortOrder` change, even though the iteration order is meaningfully
        // different. Read the pref off the current state and include it as a remember key for
        // every library-derived val whose contents depend on iteration order, so a sort-order
        // toggle correctly invalidates the cache.
        val effectiveCategorySortOrder = (state as? LibraryTabState.Loaded)?.categorySortOrder ?: 0
        val searchedLibrary = remember(library, effectiveCategorySortOrder, searchQuery, sourceNames, seriesTypes) {
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

        val allCategories = remember(library, effectiveCategorySortOrder) { library.keys.toList() }
        val categoryItemCounts = remember(library, effectiveCategorySortOrder) {
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
        // Always re-key via state.library so iteration order AND category instance freshness
        // come from the latest state, not from the produceState-cached filteredLibrary. The
        // cache is content-equal across iteration-order or isHidden-only changes (Map.equals
        // is order-independent and CategoryImpl.equals is name-based), so using filteredLibrary
        // directly would render with stale synthetic categories after a dynamic collapse toggle
        // or a categorySortOrder flip. Empty categories are dropped only when neither the
        // showAllCategories pref nor the showEmptyCategoriesWhileFiltering escape applies.
        val rekeyedLibrary = library.mapValues { (cat, _) -> filteredLibrary[cat].orEmpty() }
        val postFilterLibrary = when {
            showAllCategories -> rekeyedLibrary
            showEmptyCategoriesWhileFiltering &&
                (searchQuery.isNotEmpty() || filterState.isAnyActive) -> rekeyedLibrary
            else -> rekeyedLibrary.filterValues { it.isNotEmpty() }
        }

        // Header counts are sourced from the pre-collapse, post-filter map so collapsing a
        // category does not zero out its header. Counts shown on the header therefore reflect
        // how many of the user's filtered items live in that category.
        val displayedHeaderCounts = remember(postFilterLibrary) {
            postFilterLibrary.entries.associate { (cat, items) -> (cat.id ?: 0) to items.size }
        }

        // Header collapse is available for both default and dynamic groupings as of Phase 6 C9.
        // Default categories key their collapsed state on Category.id (collapsedCategories pref);
        // dynamic categories carry `isHidden` directly, populated by MangaLibraryDynamicGrouping
        // from preferences.collapsedDynamicCategories.
        val collapsibleHeaders = true
        val collapsedIds = remember(collapsedCategories) {
            collapsedCategories.mapNotNullTo(HashSet()) { it.toIntOrNull() }
        }
        val displayedLibrary = postFilterLibrary.mapValues { (cat, items) ->
            val collapsed = if (cat.isDynamic) {
                cat.isHidden
            } else {
                cat.id != null && cat.id in collapsedIds
            }
            if (collapsed) emptyList() else items
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

        // Per-tab callbacks lifted out so the renderer lambdas (below) can close over them.
        // continueReading: load all chapters via ChapterSort, pick next-unread, launch reader.
        // Manga with no remaining unread chapters silently no-op (the button is gated on
        // unread > 0 upstream so this is only reachable in a race where the count changes).
        val onContinueReading: (Manga) -> Unit = { manga ->
            coroutineScope.launch {
                val chapters = getChapter.awaitAll(manga)
                val next = ChapterSort(manga).getNextUnreadChapter(chapters, false)
                    ?: return@launch
                val activity = router.activity ?: return@launch
                dismissPendingSnackbar()
                activity.startActivity(ReaderActivity.newIntent(activity, manga, next))
            }
        }
        // mangaClick: same destination + transition as legacy LibraryController.openManga.
        // Routes through the existing Conductor router for now since no Compose-side manga
        // details Voyager screen exists yet.
        val onMangaClick: (Manga) -> Unit = { manga ->
            dismissPendingSnackbar()
            router.pushController(MangaDetailsController(manga).withFadeTransaction())
        }

        // Per-tab renderer lambdas. Phase 8 C7c: LibraryContent is generic; these capture the
        // manga-side cell helpers + callbacks + badge prefs. Mirror lambdas for the novel tab
        // land in Phase 8 C9 when the tabbed shell wraps both tabs.
        val mangaListItemRenderer: @Composable (LibraryItem.Manga, Boolean, Boolean, Modifier) -> Unit = { item, isSelected, selectionActive, modifier ->
            MangaLibraryListItem(
                item = item,
                isSelected = isSelected,
                selectionActive = selectionActive,
                modifier = modifier,
                showDownloadBadge = showDownloadBadge,
                showLanguageBadge = showLanguageBadge,
                unreadBadgeType = unreadBadgeType,
                onMangaClick = onMangaClick,
                onToggleSelection = { id -> screenModel.toggleSelection(id) },
            )
        }
        val mangaGridItemRenderer: @Composable (LibraryItem.Manga, Boolean, Boolean, Modifier, Float?) -> Unit = { item, isSelected, selectionActive, modifier, coverAspectRatio ->
            MangaLibraryGridCell(
                item = item,
                libraryLayout = libraryLayout,
                outlineOnCovers = outlineOnCovers,
                showDownloadBadge = showDownloadBadge,
                showLanguageBadge = showLanguageBadge,
                unreadBadgeType = unreadBadgeType,
                hideStartReadingButton = hideStartReadingButton,
                isSelected = isSelected,
                modifier = modifier,
                selectionActive = selectionActive,
                onMangaClick = onMangaClick,
                onMangaLongClick = { m -> m.id?.let { screenModel.toggleSelection(it) } },
                onContinueReading = onContinueReading,
                coverAspectRatio = coverAspectRatio,
            )
        }

        LibraryContent(
            library = finalLibrary,
            topBarBelow = tabRow,
            singleCategoryMode = singleCategoryMode,
            allCategories = allCategories,
            categoryItemCounts = categoryItemCounts,
            displayedHeaderCounts = displayedHeaderCounts,
            collapsedIds = collapsedIds,
            collapsibleHeaders = collapsibleHeaders,
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
                // Dynamic categories use the dynamicHeaderKey string set; default categories
                // use the legacy Category.id-as-string set. Route accordingly.
                if (category.isDynamic) {
                    screenModel.toggleDynamicCategoryCollapse(category)
                } else {
                    val id = category.id?.toString() ?: return@LibraryContent
                    val current = collapsedCategoriesPref.get().toMutableSet()
                    if (!current.add(id)) current.remove(id)
                    collapsedCategoriesPref.set(current)
                }
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
            listItemRenderer = mangaListItemRenderer,
            gridItemRenderer = mangaGridItemRenderer,
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
                //   - !showAllCategories + dynamic grouping → legacy refreshes the focused
                //     dynamic bucket (updateCategory(0) — first visible header). C10 still
                //     falls back to a whole-library refresh in this sub-case; a per-bucket
                //     PTR dispatch needs the focused synthetic Category instance which we
                //     don't track via allCategories. Known minor gap, acceptable because the
                //     user can still tap the per-header refresh icon for surgical control.
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
                // F13/D2: expanded to include merged-group siblings so moving a leader carries
                // the rest of the group along, matching legacy `LibraryController.kt:2253-2262`.
                val mangas = screenModel.selectedMangaListWithMergedSiblings()
                if (mangas.isEmpty()) return@LibraryContent
                val activity = router.activity ?: return@LibraryContent
                coroutineScope.launch {
                    mangas.moveCategories(activity) {
                        screenModel.clearSelection()
                    }
                }
            },
            categorySortOrder = effectiveCategorySortOrder,
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
                    // C10: screenModel.refresh handles both real and dynamic (synthetic-id)
                    // categories — for dynamic, it looks up the bucket's manga list from the
                    // current state and passes it through as mangaToUse.
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
            // C6: per-category sort picker dispatch. The screen-model handles the same-mode
            // direction flip, the Random reseed, and the routing per category type (regular →
            // DB, default → defaultMangaOrder pref, dynamic → library-wide sort prefs).
            onSortChange = { category, mode -> screenModel.setSort(category, mode) },
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
                containerColor = dialogContainerColor,
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
                        colors = dialogButtonColors,
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
                    androidx.compose.material3.TextButton(
                        colors = dialogButtonColors,
                        onClick = { markReadConfirmFor = null },
                    ) {
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
                containerColor = dialogContainerColor,
                title = { androidx.compose.material3.Text(text = removeText) },
                text = {
                    // F11 / F12 visual: row geometry mirrors the existing LabeledCheckbox
                    // pattern at component/LabeledCheckbox.kt:27-50 (heightIn min 48dp,
                    // Arrangement.spacedBy(Size.small) for checkbox-to-text gap). Column gets
                    // vertical spacing between rows so they don't visually crowd each other,
                    // matching the legacy ListView's per-row vertical breathing room.
                    androidx.compose.foundation.layout.Column(
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(
                            yokai.presentation.theme.Size.small,
                        ),
                    ) {
                        androidx.compose.foundation.layout.Row(
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(
                                yokai.presentation.theme.Size.small,
                            ),
                            modifier = Modifier
                                .alpha(0.5f)
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clickable(role = androidx.compose.ui.semantics.Role.Checkbox) {},
                        ) {
                            androidx.compose.material3.Checkbox(
                                checked = true,
                                onCheckedChange = null,
                                colors = dialogCheckboxColors,
                            )
                            androidx.compose.material3.Text(text = removeDownloadsLabel)
                        }
                        androidx.compose.foundation.layout.Row(
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(
                                yokai.presentation.theme.Size.small,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clickable(role = androidx.compose.ui.semantics.Role.Checkbox) {
                                    removeFromLibrary = !removeFromLibrary
                                },
                        ) {
                            androidx.compose.material3.Checkbox(
                                checked = removeFromLibrary,
                                onCheckedChange = null,
                                colors = dialogCheckboxColors,
                            )
                            androidx.compose.material3.Text(text = removeFromLibraryLabel)
                        }
                    }
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(
                        colors = dialogButtonColors,
                        onClick = {
                            deleteConfirmOpen = false
                            // F13/D3: expanded so the snackbar's undo / cleanup branches
                            // cover every member of any merge group the selection touches.
                            // The downstream removeFromLibrary() also uses the expanded
                            // resolver internally; the two converge on the same set.
                            val mangas = screenModel.selectedMangaListWithMergedSiblings()
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
                    androidx.compose.material3.TextButton(
                        colors = dialogButtonColors,
                        onClick = { deleteConfirmOpen = false },
                    ) {
                        androidx.compose.material3.Text(text = cancelText)
                    }
                },
            )
        }
    }
}
