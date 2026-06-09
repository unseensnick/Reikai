package eu.kanade.tachiyomi.ui.library

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastAll
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.presentation.library.DeleteLibraryMangaDialog
import eu.kanade.presentation.library.LibrarySettingsDialog
import eu.kanade.presentation.library.components.LibraryContent
import eu.kanade.presentation.library.components.LibraryToolbar
import eu.kanade.presentation.manga.components.LibraryBottomActionMenu
import eu.kanade.presentation.more.onboarding.GETTING_STARTED_URL
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.library.LibraryUpdateJob
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreen
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import mihon.feature.migration.config.MigrationConfigScreen
// RK -->
import reikai.presentation.library.ReikaiCategoryHopper
import reikai.presentation.library.ReikaiCategoryPickerSheet
import reikai.presentation.library.ReikaiLibraryContent
import reikai.presentation.library.updateerror.UpdateErrorsScreen
import reikai.presentation.library.reikaiCategoryHeaderIndices
import reikai.presentation.library.reikaiIsCollapsed
import reikai.presentation.library.reikaiSortCategories
// RK <--
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.EmptyScreenAction
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.source.local.isLocal

data object LibraryTab : Tab {

    override val options: TabOptions
        @Composable
        get() {
            val isSelected = LocalTabNavigator.current.current.key == key
            val image = AnimatedImageVector.animatedVectorResource(R.drawable.anim_library_enter)
            return TabOptions(
                index = 0u,
                title = stringResource(MR.strings.label_library),
                icon = rememberAnimatedVectorPainter(image, isSelected),
            )
        }

    override suspend fun onReselect(navigator: Navigator) {
        requestOpenSettingsSheet()
    }

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val haptic = LocalHapticFeedback.current

        val screenModel = rememberScreenModel { LibraryScreenModel() }
        val settingsScreenModel = rememberScreenModel { LibrarySettingsScreenModel() }
        val state by screenModel.state.collectAsState()

        val snackbarHostState = remember { SnackbarHostState() }

        // RK --> hopper + jump-to-category picker drive both views through a single `hopperTarget`.
        // Single-list prev/next jump instantly (categories can hold hundreds of items, so animating
        // across them stutters) and stay snappy under rapid taps; the picker animates a smooth slide;
        // the tabbed pager animates its page transition.
        val singleListGridState = rememberLazyGridState()
        val pagerState = rememberPagerState(initialPage = state.coercedActiveCategoryIndex) {
            state.displayedCategories.size
        }
        var pickerOpen by remember { mutableStateOf(false) }
        var hopperTarget by remember { mutableStateOf<Int?>(null) }
        var instantHopperScroll by remember { mutableStateOf(false) }
        var hopperDragAccum by remember { mutableFloatStateOf(0f) }
        fun reikaiHeaderIndices(): List<Int> = reikaiCategoryHeaderIndices(
            categories = state.displayedCategories,
            hasSearchItem = !state.searchQuery.isNullOrEmpty(),
            isCollapsed = {
                reikaiIsCollapsed(it, state.reikai.collapsedCategories, state.reikai.collapsedDynamicCategories)
            },
            itemCount = { state.getItemsForCategory(it).size },
        )
        fun currentCategoryIndex(): Int = if (state.reikai.showAllCategories) {
            reikaiHeaderIndices().indexOfLast { it <= singleListGridState.firstVisibleItemIndex }.coerceAtLeast(0)
        } else {
            pagerState.currentPage
        }
        LaunchedEffect(hopperTarget) {
            val target = hopperTarget ?: return@LaunchedEffect
            if (state.reikai.showAllCategories) {
                reikaiHeaderIndices().getOrNull(target)?.let { itemIndex ->
                    if (instantHopperScroll) {
                        // Hopper prev/next: jump instantly. Categories here can hold hundreds of
                        // items, so animating a scroll across them would have to compose everything
                        // in between and stutter; an instant jump keeps single and rapid taps snappy.
                        singleListGridState.scrollToItem(itemIndex)
                    } else {
                        // Picker jump: snap to just ABOVE the target, then animate DOWN onto it.
                        // Animating downward composes items smoothly; animating up onto an
                        // uncomposed target (e.g. jumping to the first category) stutters.
                        val current = singleListGridState.firstVisibleItemIndex
                        if (kotlin.math.abs(itemIndex - current) > 12) {
                            singleListGridState.scrollToItem((itemIndex - 8).coerceAtLeast(0))
                        }
                        singleListGridState.animateScrollToItem(itemIndex)
                    }
                }
            } else {
                pagerState.animateScrollToPage(target)
            }
            hopperTarget = null
        }
        // RK <--

        val onClickRefresh: (Category?) -> Boolean = { category ->
            val started = LibraryUpdateJob.startNow(context, category)
            scope.launch {
                val msgRes = when {
                    !started -> MR.strings.update_already_running
                    category != null -> MR.strings.updating_category
                    else -> MR.strings.updating_library
                }
                snackbarHostState.showSnackbar(context.stringResource(msgRes))
            }
            started
        }

        Scaffold(
            topBar = { scrollBehavior ->
                val title = state.getToolbarTitle(
                    defaultTitle = stringResource(MR.strings.label_library),
                    defaultCategoryTitle = stringResource(MR.strings.label_default),
                    // RK: single-list tracks the visible category on scroll, so the title follows it
                    page = if (state.reikai.showAllCategories) {
                        currentCategoryIndex()
                    } else {
                        state.coercedActiveCategoryIndex
                    },
                )
                LibraryToolbar(
                    hasActiveFilters = state.hasActiveFilters,
                    selectedCount = state.selection.size,
                    title = title,
                    onClickUnselectAll = screenModel::clearSelection,
                    onClickSelectAll = screenModel::selectAll,
                    onClickInvertSelection = screenModel::invertSelection,
                    onClickFilter = { screenModel.showSettingsDialog() },
                    onClickRefresh = { onClickRefresh(state.activeCategory) },
                    onClickGlobalUpdate = { onClickRefresh(null) },
                    onClickOpenRandomManga = {
                        scope.launch {
                            val randomItem = screenModel.getRandomLibraryItemForCurrentCategory()
                            if (randomItem != null) {
                                navigator.push(MangaScreen(randomItem.libraryManga.manga.id))
                            } else {
                                snackbarHostState.showSnackbar(
                                    context.stringResource(MR.strings.information_no_entries_found),
                                )
                            }
                        }
                    },
                    // RK: opt-in Update errors screen (hidden unless the Advanced toggle is on)
                    onClickUpdateErrors = if (state.reikai.trackUpdateErrors) {
                        { navigator.push(UpdateErrorsScreen()) }
                    } else {
                        null
                    },
                    searchQuery = state.searchQuery,
                    onSearchQueryChange = screenModel::search,
                    // For scroll overlay when no tab
                    scrollBehavior = scrollBehavior.takeIf { !state.showCategoryTabs },
                )
            },
            bottomBar = {
                LibraryBottomActionMenu(
                    visible = state.selectionMode,
                    onChangeCategoryClicked = screenModel::openChangeCategoryDialog,
                    onMarkAsReadClicked = { screenModel.markReadSelection(true) },
                    onMarkAsUnreadClicked = { screenModel.markReadSelection(false) },
                    onDownloadClicked = screenModel::performDownloadAction
                        .takeIf { state.selectedManga.fastAll { !it.isLocal() } },
                    onDeleteClicked = screenModel::openDeleteMangaDialog,
                    onMigrateClicked = {
                        val selection = state.selection
                        screenModel.clearSelection()
                        navigator.push(MigrationConfigScreen(selection))
                    },
                )
            },
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        ) { contentPadding ->
            when {
                state.isLoading -> {
                    LoadingScreen(Modifier.padding(contentPadding))
                }
                state.searchQuery.isNullOrEmpty() && !state.hasActiveFilters && state.isLibraryEmpty -> {
                    val handler = LocalUriHandler.current
                    EmptyScreen(
                        stringRes = MR.strings.information_empty_library,
                        modifier = Modifier.padding(contentPadding),
                        actions = listOf(
                            EmptyScreenAction(
                                stringRes = MR.strings.getting_started_guide,
                                icon = Icons.AutoMirrored.Outlined.HelpOutline,
                                onClick = { handler.openUri(GETTING_STARTED_URL) },
                            ),
                        ),
                    )
                }
                else -> {
                    // RK --> both library views (pager + single-list) with hopper + picker overlaid
                    Box(modifier = Modifier.fillMaxSize()) {
                        if (state.reikai.showAllCategories) {
                            val isLandscape =
                                LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
                            val columns by screenModel.getColumnsForOrientation(isLandscape)
                            val displayMode by screenModel.getDisplayMode()
                            ReikaiLibraryContent(
                                categories = state.displayedCategories,
                                getItemsForCategory = { state.getItemsForCategory(it) },
                                collapsedCategories = state.reikai.collapsedCategories,
                                collapsedDynamicCategories = state.reikai.collapsedDynamicCategories,
                                showItemCounts = state.showMangaCount,
                                displayMode = displayMode,
                                columns = columns,
                                selection = state.selection,
                                searchQuery = state.searchQuery,
                                gridState = singleListGridState,
                                contentPadding = contentPadding,
                                onClickManga = { category, manga ->
                                    if (state.selectionMode) {
                                        screenModel.toggleSelection(category, manga)
                                    } else {
                                        navigator.push(MangaScreen(manga.id))
                                    }
                                },
                                onLongClickManga = { category, manga ->
                                    screenModel.toggleSelection(category, manga)
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                },
                                onToggleDefaultCollapse = screenModel::toggleDefaultCategoryCollapse,
                                onToggleDynamicCollapse = screenModel::toggleDynamicCategoryCollapse,
                                onGlobalSearchClicked = {
                                    navigator.push(GlobalSearchScreen(screenModel.state.value.searchQuery ?: ""))
                                },
                                // RK 4.6: per-category header sort (Sort tab scoped to it), refresh, select-all
                                onClickCategorySort = { category ->
                                    screenModel.showSettingsDialog(initialTab = 1, categoryId = category.id)
                                },
                                onRefreshCategory = { category -> onClickRefresh(category) },
                                onSelectAllInCategory = screenModel::selectAllInCategory,
                            )
                        } else {
                            LibraryContent(
                                categories = state.displayedCategories,
                                searchQuery = state.searchQuery,
                                selection = state.selection,
                                contentPadding = contentPadding,
                                pagerState = pagerState,
                                hasActiveFilters = state.hasActiveFilters,
                                showPageTabs = state.showCategoryTabs || !state.searchQuery.isNullOrEmpty(),
                                onChangeCurrentPage = screenModel::updateActiveCategoryIndex,
                                onClickManga = { navigator.push(MangaScreen(it)) },
                                onContinueReadingClicked = { it: LibraryManga ->
                                    scope.launchIO {
                                        val chapter = screenModel.getNextUnreadChapter(it.manga)
                                        if (chapter != null) {
                                            context.startActivity(
                                                ReaderActivity.newIntent(context, chapter.mangaId, chapter.id),
                                            )
                                        } else {
                                            snackbarHostState.showSnackbar(context.stringResource(MR.strings.no_next_chapter))
                                        }
                                    }
                                    Unit
                                }.takeIf { state.showMangaContinueButton },
                                onToggleSelection = screenModel::toggleSelection,
                                onToggleRangeSelection = { category, manga ->
                                    screenModel.toggleRangeSelection(category, manga)
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                },
                                onRefresh = { onClickRefresh(state.activeCategory) },
                                onGlobalSearchClicked = {
                                    navigator.push(GlobalSearchScreen(screenModel.state.value.searchQuery ?: ""))
                                },
                                getItemCountForCategory = { state.getItemCountForCategory(it) },
                                getDisplayMode = { screenModel.getDisplayMode() },
                                getColumnsForOrientation = { screenModel.getColumnsForOrientation(it) },
                                getItemsForCategory = { state.getItemsForCategory(it) },
                            )
                        }

                        if (!state.reikai.hideHopper && state.displayedCategories.isNotEmpty()) {
                            val hopperAlignment = when (state.reikai.hopperGravity) {
                                0 -> Alignment.BottomStart
                                2 -> Alignment.BottomEnd
                                else -> Alignment.BottomCenter
                            }
                            // Autohide: fade the hopper out while the single-list is scrolling,
                            // bring it back when it settles. No effect in the pager (its grid state
                            // isn't this one), where the hopper stays put.
                            val hopperVisible = !state.reikai.autohideHopper ||
                                !singleListGridState.isScrollInProgress
                            AnimatedVisibility(
                                visible = hopperVisible,
                                enter = fadeIn(),
                                exit = fadeOut(),
                                modifier = Modifier
                                    .align(hopperAlignment)
                                    .padding(horizontal = 12.dp)
                                    .padding(bottom = contentPadding.calculateBottomPadding() + 12.dp),
                            ) {
                            ReikaiCategoryHopper(
                                modifier = Modifier
                                    // Drag the hopper left/right to move it between start / center / end.
                                    .pointerInput(state.reikai.hopperGravity) {
                                        val gravity = state.reikai.hopperGravity
                                        detectHorizontalDragGestures(
                                            onDragStart = { hopperDragAccum = 0f },
                                            onDragEnd = {
                                                val next = when {
                                                    hopperDragAccum > 48f -> (gravity + 1).coerceAtMost(2)
                                                    hopperDragAccum < -48f -> (gravity - 1).coerceAtLeast(0)
                                                    else -> gravity
                                                }
                                                if (next != gravity) screenModel.setHopperGravity(next)
                                            },
                                        ) { change, dragAmount ->
                                            change.consume()
                                            hopperDragAccum += dragAmount
                                        }
                                    },
                                onUpClick = {
                                    val last = state.displayedCategories.lastIndex.coerceAtLeast(0)
                                    instantHopperScroll = true
                                    hopperTarget = ((hopperTarget ?: currentCategoryIndex()) - 1).coerceIn(0, last)
                                },
                                onCenterClick = { pickerOpen = true },
                                onCenterLongClick = {
                                    when (state.reikai.hopperLongPressAction) {
                                        0 -> screenModel.search("")
                                        1 -> screenModel.toggleAllCategoriesCollapsed(state.displayedCategories)
                                        2 -> screenModel.showSettingsDialog(initialTab = 2)
                                        3 -> screenModel.showSettingsDialog(initialTab = 3)
                                        4 -> scope.launch {
                                            val item = screenModel.getRandomLibraryItemForCurrentCategory()
                                            if (item != null) navigator.push(MangaScreen(item.libraryManga.manga.id))
                                        }
                                        5 -> scope.launch {
                                            val item = screenModel.getRandomLibraryItem()
                                            if (item != null) navigator.push(MangaScreen(item.libraryManga.manga.id))
                                        }
                                    }
                                },
                                onDownClick = {
                                    val last = state.displayedCategories.lastIndex.coerceAtLeast(0)
                                    instantHopperScroll = true
                                    hopperTarget = ((hopperTarget ?: currentCategoryIndex()) + 1).coerceIn(0, last)
                                },
                            )
                            }
                        }
                    }

                    if (pickerOpen) {
                        ReikaiCategoryPickerSheet(
                            categories = state.displayedCategories,
                            getItemCount = { state.getItemCountForCategory(it) },
                            showItemCounts = state.showMangaCount,
                            activeCategoryId = state.displayedCategories.getOrNull(currentCategoryIndex())?.id,
                            onSelect = { category ->
                                instantHopperScroll = false
                                hopperTarget = state.displayedCategories.indexOf(category)
                                pickerOpen = false
                            },
                            onDismiss = { pickerOpen = false },
                        )
                    }
                    // RK <--
                }
            }
        }

        val onDismissRequest = screenModel::closeDialog
        when (val dialog = state.dialog) {
            is LibraryScreenModel.Dialog.SettingsSheet -> run {
                LibrarySettingsDialog(
                    onDismissRequest = onDismissRequest,
                    screenModel = settingsScreenModel,
                    // RK: a single-list header scopes the sheet to its category (Sort tab). Resolve the
                    // live category by id so the Sort tab reflects the current sort after it changes.
                    category = dialog.categoryId?.let { id -> state.libraryData.categories.find { it.id == id } }
                        ?: state.activeCategory,
                    // RK --> full category list for the include/exclude filter (sorted per R3) + route to category manager
                    categories = reikaiSortCategories(state.libraryData.categories, state.reikai.categorySortOrder),
                    onManageCategories = {
                        onDismissRequest()
                        navigator.push(CategoryScreen())
                    },
                    initialTab = dialog.initialTab,
                    // RK <--
                )
            }
            is LibraryScreenModel.Dialog.ChangeCategory -> {
                ChangeCategoryDialog(
                    initialSelection = dialog.initialSelection,
                    onDismissRequest = onDismissRequest,
                    onEditCategories = {
                        screenModel.clearSelection()
                        navigator.push(CategoryScreen())
                    },
                    onConfirm = { include, exclude ->
                        screenModel.clearSelection()
                        screenModel.setMangaCategories(dialog.manga, include, exclude)
                    },
                )
            }
            is LibraryScreenModel.Dialog.DeleteManga -> {
                DeleteLibraryMangaDialog(
                    containsLocalManga = dialog.manga.any(Manga::isLocal),
                    onDismissRequest = onDismissRequest,
                    onConfirm = { deleteManga, deleteChapter ->
                        screenModel.removeMangas(dialog.manga, deleteManga, deleteChapter)
                        screenModel.clearSelection()
                    },
                )
            }
            null -> {}
        }

        BackHandler(enabled = state.selectionMode || state.searchQuery != null) {
            when {
                state.selectionMode -> screenModel.clearSelection()
                state.searchQuery != null -> screenModel.search(null)
            }
        }

        LaunchedEffect(state.selectionMode, state.dialog) {
            HomeScreen.showBottomNav(!state.selectionMode)
        }

        LaunchedEffect(state.isLoading) {
            if (!state.isLoading) {
                (context as? MainActivity)?.ready = true
            }
        }

        LaunchedEffect(Unit) {
            launch { queryEvent.receiveAsFlow().collect(screenModel::search) }
            launch { requestSettingsSheetEvent.receiveAsFlow().collectLatest { screenModel.showSettingsDialog() } }
        }
    }

    // For invoking search from other screen
    private val queryEvent = Channel<String>()
    suspend fun search(query: String) = queryEvent.send(query)

    // For opening settings sheet in LibraryController
    private val requestSettingsSheetEvent = Channel<Unit>()
    private suspend fun requestOpenSettingsSheet() = requestSettingsSheetEvent.send(Unit)
}
