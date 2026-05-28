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
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.tachiyomi.core.storage.preference.collectAsState
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.ui.library.models.LibraryItem
import eu.kanade.tachiyomi.util.compose.LocalRouter
import eu.kanade.tachiyomi.util.moveCategories
import eu.kanade.tachiyomi.util.view.withFadeTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import yokai.i18n.MR
import yokai.presentation.library.novels.NovelLibraryScreenModel
import yokai.presentation.library.settings.tabs.columnsForGridValue
import yokai.util.lang.getString

/**
 * Novel tab body. Mirrors `MangaLibraryTabContent` for novels: collects novel + shared
 * display prefs, runs the library through `NovelLibrarySearch` then `NovelLibraryFilter`,
 * threads the filtered map into the generic [LibraryContent] with novel-side renderer
 * lambdas + callbacks routed through [NovelLibraryScreenModel].
 *
 * Lives at top level (not nested in [LibraryScreen]) so it can be hosted from either the
 * Compose library's tabbed shell or the legacy library's `LibraryHostController` child
 * router. Self-contained: every dependency is injected inside via Injekt / Koin or read
 * from Compose locals, so the same composable body serves both call sites.
 *
 * Diverges from manga in places locked by Phase 7 decisions:
 * - **No migrate** (Decision #1): `onMigrate` no-ops; `selectionHasRemoteSources` always
 *   false so the menu entry stays hidden.
 * - **No mangaType / contentType / NSFW filter** (Decision #3): `NovelLibraryFilter` has no
 *   such state; `detectedMangaTypes` is empty.
 * - **No language filter dimension** (no language field on Novel).
 * - **Download-unread stub** (Decision #4): dispatches the screen-model action which is a
 *   no-op until novel downloads ship.
 *
 * @param tabRow Pass an empty `{}` when the Manga / Novels switch lives outside the
 *               composable (e.g. the legacy library's `activityBinding.mainTabs`).
 */
@Composable
internal fun NovelLibraryTabContent(
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

    // Display prefs: route through the manga prefs by default (shared mode), or through
    // novelPrefs when the user has flipped Settings -> Advanced -> Share library display
    // settings off. Manga-only features (hopper quartet, showCategoryInTitle) are
    // hardcoded to inert defaults here since they don't apply to the novel library.
    val basePrefs = remember { Injekt.get<yokai.domain.base.BasePreferences>() }
    val uiPreferences = remember { Injekt.get<yokai.domain.ui.UiPreferences>() }
    val useSharedLibraryDisplayPrefs by basePrefs.useSharedLibraryDisplayPrefs().collectAsState()

    val mangaLibraryLayout by preferences.libraryLayout().collectAsState()
    val novelLibraryLayout by novelPrefs.novelLibraryLayout().collectAsState()
    val libraryLayout = if (useSharedLibraryDisplayPrefs) mangaLibraryLayout else novelLibraryLayout

    val mangaUniformGrid by uiPreferences.uniformGrid().collectAsState()
    val novelUniformGrid by novelPrefs.novelUniformGrid().collectAsState()
    val uniformGrid = if (useSharedLibraryDisplayPrefs) mangaUniformGrid else novelUniformGrid

    val mangaUseStaggeredGrid by preferences.useStaggeredGrid().collectAsState()
    val novelUseStaggeredGrid by novelPrefs.novelUseStaggeredGrid().collectAsState()
    val useStaggeredGrid = if (useSharedLibraryDisplayPrefs) mangaUseStaggeredGrid else novelUseStaggeredGrid

    // Manga-only feature; the novel library has no "show current category in title" path.
    val showCategoryInTitle = false

    val mangaShowCategoryItemCounts by preferences.categoryNumberOfItems().collectAsState()
    val novelShowCategoryItemCounts by novelPrefs.novelCategoryNumberOfItems().collectAsState()
    val showCategoryItemCounts = if (useSharedLibraryDisplayPrefs) mangaShowCategoryItemCounts else novelShowCategoryItemCounts

    // Hopper quartet is manga-only UI; novel library doesn't render it. LibraryContent still
    // requires these params, so feed inert values that disable / hide the hopper.
    val hideHopper = true
    val autohideHopper = false
    val hopperLongPressAction = 0
    val hopperGravity = 1

    val mangaOutlineOnCovers by uiPreferences.outlineOnCovers().collectAsState()
    val novelOutlineOnCovers by novelPrefs.novelOutlineOnCovers().collectAsState()
    val outlineOnCovers = if (useSharedLibraryDisplayPrefs) mangaOutlineOnCovers else novelOutlineOnCovers

    val mangaShowDownloadBadge by preferences.downloadBadge().collectAsState()
    val novelShowDownloadBadge by novelPrefs.novelDownloadBadge().collectAsState()
    val showDownloadBadge = if (useSharedLibraryDisplayPrefs) mangaShowDownloadBadge else novelShowDownloadBadge

    val mangaShowLanguageBadge by preferences.languageBadge().collectAsState()
    val novelShowLanguageBadge by novelPrefs.novelLanguageBadge().collectAsState()
    val showLanguageBadge = if (useSharedLibraryDisplayPrefs) mangaShowLanguageBadge else novelShowLanguageBadge

    val mangaUnreadBadgeType by preferences.unreadBadgeType().collectAsState()
    val novelUnreadBadgeType by novelPrefs.novelUnreadBadgeType().collectAsState()
    val unreadBadgeType = if (useSharedLibraryDisplayPrefs) mangaUnreadBadgeType else novelUnreadBadgeType

    val mangaHideStartReadingButton by preferences.hideStartReadingButton().collectAsState()
    val novelHideStartReadingButton by novelPrefs.novelHideStartReadingButton().collectAsState()
    val hideStartReadingButton = if (useSharedLibraryDisplayPrefs) mangaHideStartReadingButton else novelHideStartReadingButton

    val mangaGridSize by preferences.gridSize().collectAsState()
    val novelGridSize by novelPrefs.novelGridSize().collectAsState()
    val gridSizePref = if (useSharedLibraryDisplayPrefs) mangaGridSize else novelGridSize
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
        // transitional: legacy NovelDetailsController until NovelDetailsScreen ports
        router.pushController(
            eu.kanade.tachiyomi.ui.novel.NovelDetailsController(
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
        isAnyFilterActive = filterState.isAnyActive,
        showAllCategories = showAllCategories,
        isRunning = isRunning,
        inQueueCategoryIds = inQueueCategoryIds,
        snackbarHostState = snackbarHostState,
        sheetOpen = sheetOpen,
        sheetTab = sheetTab,
        isNovelTab = true,
        overflowOpen = overflowOpen,
        detectedMangaTypes = detectedMangaTypes,
        loggedTrackerNames = loggedTrackerNames,
        selection = selection,
        onSearchActiveChange = { searchActive = it },
        onSearchQueryChange = { searchQuery = it },
        onHopperGravityChange = {
            // Novel library doesn't render a hopper, so this writer is unreachable. Leaving
            // it as a no-op keeps the LibraryContent contract simple.
        },
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
            // Mirror the manga path at :1401-onwards: open SetNovelCategoriesSheet via the
            // List<Novel>.moveCategories extension. Use the merged-siblings variant so
            // moving a leader carries its merge-group members with it (parity with
            // selectedMangaListWithMergedSiblings on the manga side).
            val novels = screenModel.selectedNovelListWithMergedSiblings()
            if (novels.isEmpty()) return@LibraryContent
            val activity = router.activity ?: return@LibraryContent
            coroutineScope.launch {
                novels.moveCategories(activity) {
                    screenModel.clearSelection()
                }
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
