package yokai.presentation.library

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.tachiyomi.ui.library.models.LibraryItem
import eu.kanade.tachiyomi.util.compose.LocalRouter
import eu.kanade.tachiyomi.util.moveCategories
import eu.kanade.tachiyomi.util.view.withFadeTransaction
import kotlinx.coroutines.launch
import yokai.i18n.MR
import yokai.presentation.library.novels.NovelLibraryScreenModel
import yokai.presentation.library.settings.tabs.columnsForGridValue
import yokai.util.lang.getString

/**
 * Novel tab body. Mirrors `MangaLibraryTabContent` for novels: a thin renderer over
 * [NovelLibraryScreenModel]'s [LibraryTabState]. Search, filter, and the shared-vs-independent
 * display prefs live in the screen model (Tier 2 phase 2C); this composable reads the resulting
 * `filteredLibrary` + pref fields off state, applies the cheap category-visibility / collapse /
 * single-category passes, and threads the result into the generic [LibraryContent] with
 * novel-side renderer lambdas + callbacks routed through the screen model.
 *
 * Lives at top level (not nested in [LibraryScreen]) so it can be hosted from either the
 * Compose library's tabbed shell or the legacy library's `LibraryHostController` child
 * router. No DI inside the composable: the screen model owns every dependency.
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
 * @param contentToggle Pass an empty `{}` when the Manga / Novels switch lives outside the
 *               composable (e.g. the legacy library's `activityBinding.mainTabs`).
 */
@Composable
internal fun NovelLibraryTabContent(
    screenModel: NovelLibraryScreenModel,
    contentToggle: @Composable () -> Unit,
) {
    val state by screenModel.state.collectAsState()
    val router = LocalRouter.currentOrThrow
    val coroutineScope = rememberCoroutineScope()

    // Display / badge / layout / category prefs come from the screen model state (Tier 2 phase
    // 2C); the composable no longer reads PreferencesHelper / NovelPreferences itself. The
    // shared-vs-independent resolution (manga pref vs novel pref) happens in the screen model.
    val loaded = state as? LibraryTabState.Loaded
    val libraryLayout = loaded?.libraryLayout ?: 0
    val uniformGrid = loaded?.uniformGrid ?: true
    val useStaggeredGrid = loaded?.useStaggeredGrid ?: false
    val outlineOnCovers = loaded?.outlineOnCovers ?: true
    val showDownloadBadge = loaded?.showDownloadBadge ?: false
    val showLanguageBadge = loaded?.showLanguageBadge ?: false
    val unreadBadgeType = loaded?.unreadBadgeType ?: 0
    val hideStartReadingButton = loaded?.hideStartReadingButton ?: false
    val showCategoryItemCounts = loaded?.showCategoryItemCounts ?: false
    val groupLibraryBy = loaded?.groupLibraryBy ?: eu.kanade.tachiyomi.ui.library.LibraryGroup.BY_DEFAULT
    val collapsedCategories = loaded?.collapsedCategories ?: emptySet()
    val showAllCategories = loaded?.showAllCategories ?: true
    val showEmptyCategoriesWhileFiltering = loaded?.showEmptyCategoriesWhileFiltering ?: false

    // Hopper prefs come from the screen model state (independent novel hopper prefs). The novel
    // toolbar has no current-category title, so showCategoryInTitle stays inert.
    val showCategoryInTitle = false
    val hideHopper = loaded?.hideHopper ?: false
    val autohideHopper = loaded?.autohideHopper ?: true
    val hopperLongPressAction = loaded?.hopperLongPressAction ?: 0
    val hopperGravity = loaded?.hopperGravity ?: 1

    // Column count derived from the gridSize pref (now in state). Stays composable-local because
    // it needs the current screen width.
    val sliderValue = (((loaded?.gridSize ?: 0f) + 0.5f) * 2f).coerceIn(0f, 7f)
    val columns = columnsForGridValue(sliderValue, LocalConfiguration.current.screenWidthDp)

    // Search now runs in the screen model (Tier 2 phase 2C). The query text stays composable-
    // local so the field stays caret-stable (driving the value off the model's StateFlow
    // round-trips each keystroke and reverses input); every edit is pushed to the model, which
    // owns the filtering. Seeded from the model so a config-change survivor keeps its query, and
    // re-pushed on entry so a process-death restore re-filters.
    var searchActive by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf(screenModel.searchQuery.value) }
    LaunchedEffect(Unit) { screenModel.setSearchQuery(searchQuery) }

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

    val novelManualMerges = loaded?.manualMerges ?: emptySet()
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

    // Search + filter result and filter-sheet metadata now come from the screen model (Tier 2
    // phase 2C). detectedTypes / loggedTrackerNames are always empty for novels (no series-type
    // or logged-tracker dimension), but read from state for parity with the manga renderer.
    val effectiveCategorySortOrder = loaded?.categorySortOrder ?: 0
    val filteredLibrary = loaded?.filteredLibrary ?: emptyMap()
    val isAnyFilterActive = loaded?.isAnyFilterActive ?: false
    val detectedMangaTypes = loaded?.detectedTypes ?: emptySet()
    val loggedTrackerNames = loaded?.loggedTrackerNames ?: emptyList()

    val allCategories = remember(library, effectiveCategorySortOrder) { library.keys.toList() }
    val categoryItemCounts = remember(library, effectiveCategorySortOrder) {
        library.entries.associate { (cat, items) -> (cat.id ?: 0) to items.size }
    }

    val rekeyedLibrary = library.mapValues { (cat, _) -> filteredLibrary[cat].orEmpty() }
    val postFilterLibrary = when {
        showAllCategories -> rekeyedLibrary
        showEmptyCategoriesWhileFiltering &&
            (searchQuery.isNotEmpty() || isAnyFilterActive) -> rekeyedLibrary
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

    val lastUsedCategoryOrder = loaded?.lastUsedCategoryOrder ?: 0
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
        // Single-category mode: one pager page per user category (parity with the manga tab).
        // Empty in show-all mode so the single-grid path drives instead.
        categoryPages = if (singleCategoryMode) allCategories.map { it to (displayedLibrary[it].orEmpty()) } else emptyList(),
        contentToggle = contentToggle,
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
        isAnyFilterActive = isAnyFilterActive,
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
        onSearchQueryChange = { searchQuery = it; screenModel.setSearchQuery(it) },
        onHopperGravityChange = { screenModel.setHopperGravity(it) },
        onToggleCategoryCollapse = { category ->
            if (category.isDynamic) {
                screenModel.toggleDynamicCategoryCollapse(category)
            } else {
                val id = category.id?.toString() ?: return@LibraryContent
                screenModel.toggleDefaultCategoryCollapse(id)
            }
        },
        hopperLongPressAction = hopperLongPressAction,
        onExpandCollapseAllCategories = {
            screenModel.expandOrCollapseAllDefaultCategories()
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
            screenModel.setLastUsedCategory(category.order)
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
            // Queues the selected novels' unread chapters (merged-sibling aware). Selection clears
            // immediately; the download notification surfaces progress.
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
            onSelect = { screenModel.setGroupLibraryBy(it) },
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
