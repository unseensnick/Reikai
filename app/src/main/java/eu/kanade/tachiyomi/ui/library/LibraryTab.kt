package eu.kanade.tachiyomi.ui.library

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.surfaceColorAtElevation
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
import reikai.domain.library.ContentType
import reikai.presentation.components.ContentTypeFilterChips
import reikai.presentation.library.ReikaiCategoryHopper
import reikai.presentation.library.ReikaiCategoryPickerSheet
import reikai.presentation.library.ReikaiLibraryContent
import reikai.presentation.library.novels.NovelLibraryScreenModel
import reikai.presentation.library.novels.NovelLibrarySettingsDialog
import reikai.presentation.library.updateerror.UpdateErrorsScreen
import reikai.presentation.library.reikaiCategoryHeaderIndices
import reikai.presentation.library.reikaiIsCollapsed
import reikai.presentation.library.reikaiSortCategories
import reikai.domain.novel.model.NovelCategory
import reikai.presentation.novel.details.NovelScreen
import reikai.presentation.novel.reader.NovelReaderScreen
// RK <--
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withUIContext
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

        // RK --> novels in the library behind the Manga/Novels chip (P5 S6). The "active" locals fall
        // through to the manga `state` when the chip is on Manga, so the manga path is unchanged; on
        // Novels they read the novel screen model, feeding the same views a disguised item list.
        val novelModel = rememberScreenModel { NovelLibraryScreenModel() }
        val novelState by novelModel.state.collectAsState()
        val libraryContentType by novelModel.contentType.collectAsState()
        val isNovels = libraryContentType == ContentType.NOVELS
        val activeCategories = if (isNovels) novelState.displayedCategories else state.displayedCategories
        val activeSelection = if (isNovels) novelState.selection else state.selection
        val activeSearchQuery = if (isNovels) novelState.searchQuery else state.searchQuery
        val activeIsLibraryEmpty = if (isNovels) novelState.isLibraryEmpty else state.isLibraryEmpty
        val activeIsLoading = if (isNovels) novelState.isLoading else state.isLoading
        val activeCollapsedCategories =
            if (isNovels) novelState.collapsedCategories else state.reikai.collapsedCategories
        val activeCoercedActiveIndex =
            if (isNovels) novelState.coercedActiveCategoryIndex else state.coercedActiveCategoryIndex
        val activeGetItems: (Category) -> List<LibraryItem> =
            if (isNovels) novelState::getItemsForCategory else state::getItemsForCategory
        val activeGetItemCount: (Category) -> Int? =
            if (isNovels) novelState::getItemCountForCategory else state::getItemCountForCategory
        val onSearch: (String?) -> Unit = if (isNovels) novelModel::search else screenModel::search
        val activeSelectionMode = if (isNovels) novelState.selectionMode else state.selectionMode
        val activeHasActiveFilters = if (isNovels) novelState.hasActiveFilters else state.hasActiveFilters
        // RK <--

        val snackbarHostState = remember { SnackbarHostState() }

        // RK --> hopper + jump-to-category picker drive both views through a single `hopperTarget`.
        // Single-list prev/next jump instantly (categories can hold hundreds of items, so animating
        // across them stutters) and stay snappy under rapid taps; the picker animates a smooth slide;
        // the tabbed pager animates its page transition.
        val singleListGridState = rememberLazyGridState()
        val pagerState = rememberPagerState(initialPage = activeCoercedActiveIndex) {
            activeCategories.size
        }
        var pickerOpen by remember { mutableStateOf(false) }
        var hopperTarget by remember { mutableStateOf<Int?>(null) }
        var instantHopperScroll by remember { mutableStateOf(false) }
        var hopperDragAccum by remember { mutableFloatStateOf(0f) }
        fun reikaiHeaderIndices(): List<Int> = reikaiCategoryHeaderIndices(
            categories = activeCategories,
            hasSearchItem = !activeSearchQuery.isNullOrEmpty(),
            isCollapsed = {
                reikaiIsCollapsed(it, activeCollapsedCategories, state.reikai.collapsedDynamicCategories)
            },
            itemCount = { activeGetItems(it).size },
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

        // RK: shared manga continue-reading handler, used by both the pager and the single-list view.
        val onMangaContinueReading: (LibraryManga) -> Unit = { item ->
            scope.launchIO {
                val chapter = screenModel.getNextUnreadChapter(item.manga)
                if (chapter != null) {
                    context.startActivity(ReaderActivity.newIntent(context, chapter.mangaId, chapter.id))
                } else {
                    snackbarHostState.showSnackbar(context.stringResource(MR.strings.no_next_chapter))
                }
            }
        }
        // RK: novel continue-reading (both views). The disguised item carries a negative id, so the
        // real novel id is -manga.id. For a merged novel the resume spans the whole group (the unified
        // reading order): open the unread chapter from its OWN source + hand the reader the merged list.
        val onNovelContinueReading: (LibraryManga) -> Unit = { item ->
            scope.launchIO {
                val resume = novelModel.getResume(-item.manga.id)
                if (resume != null) {
                    withUIContext {
                        navigator.push(
                            NovelReaderScreen(resume.chapter.novelId, resume.chapter.id, resume.chapterIds.toLongArray()),
                        )
                    }
                } else {
                    snackbarHostState.showSnackbar(context.stringResource(MR.strings.no_next_chapter))
                }
            }
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
                // RK: stack the content-type chip under the toolbar so the Scaffold sizes
                // contentPadding to include it and both library views render below it untouched.
                // RK: match the toolbar's container color so the chip strip reads as part of it.
                // Rest = surfaceColorAtElevation(0/3dp) (3dp in selection, mirroring AppBar's
                // isActionMode); on scroll the toolbar tints to its scrolledContainerColor
                // (M3 default surfaceContainer), only when it actually collapses (no category tabs).
                val chipBackground by animateColorAsState(
                    targetValue = if (!state.showCategoryTabs && scrollBehavior.state.overlappedFraction > 0.01f) {
                        MaterialTheme.colorScheme.surfaceContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceColorAtElevation(if (state.selectionMode) 3.dp else 0.dp)
                    },
                    label = "libraryChipBackground",
                )
                Column {
                LibraryToolbar(
                    // RK: filter indicator + selection actions follow the active (manga/novel) model
                    hasActiveFilters = activeHasActiveFilters,
                    selectedCount = activeSelection.size,
                    title = title,
                    onClickUnselectAll = if (isNovels) novelModel::clearSelection else screenModel::clearSelection,
                    onClickSelectAll = if (isNovels) novelModel::selectAll else screenModel::selectAll,
                    onClickInvertSelection = if (isNovels) novelModel::invertSelection else screenModel::invertSelection,
                    onClickFilter = {
                        if (isNovels) {
                            novelModel.openSettingsDialog(
                                novelState.activeCategory?.id ?: reikai.domain.novel.model.NovelCategory.UNCATEGORIZED_ID,
                                0,
                            )
                        } else {
                            screenModel.showSettingsDialog()
                        }
                    },
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
                    searchQuery = activeSearchQuery,
                    onSearchQueryChange = onSearch,
                    // For scroll overlay when no tab
                    scrollBehavior = scrollBehavior.takeIf { !state.showCategoryTabs },
                )
                ContentTypeFilterChips(
                    selected = libraryContentType,
                    onSelect = novelModel::setContentType,
                    types = listOf(ContentType.MANGA, ContentType.NOVELS),
                    modifier = Modifier.background(chipBackground),
                )
                }
            },
            bottomBar = {
                // RK: novels get their own action bar (download menu, mark read/unread, change
                // category, delete); merge + migrate stay manga-only (deferred / S8).
                if (isNovels) {
                    LibraryBottomActionMenu(
                        visible = novelState.selectionMode,
                        onChangeCategoryClicked = novelModel::openChangeCategoryDialog,
                        onMarkAsReadClicked = { novelModel.markReadSelection(true) },
                        onMarkAsUnreadClicked = { novelModel.markReadSelection(false) },
                        onDownloadClicked = novelModel::performDownloadAction,
                        onDeleteClicked = novelModel::openDeleteDialog,
                        onMigrateClicked = null,
                        // RK: manual merge of the selected novels (needs at least two) + unmerge (P5 S8)
                        onMergeClicked = novelModel::mergeSelection.takeIf { novelState.selection.size >= 2 },
                        onUnmergeClicked = novelModel::unmergeSelection.takeIf { novelState.selectionContainsMerged },
                    )
                } else {
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
                        // RK: manual merge of the selected manga (needs at least two)
                        onMergeClicked = screenModel::mergeSelection.takeIf { state.selection.size >= 2 },
                        // RK: unmerge selected manga (only when the selection includes a merged one)
                        onUnmergeClicked = screenModel::unmergeSelection.takeIf { state.selectionContainsMerged },
                    )
                }
            },
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        ) { contentPadding ->
            when {
                activeIsLoading -> {
                    LoadingScreen(Modifier.padding(contentPadding))
                }
                activeSearchQuery.isNullOrEmpty() && !isNovels && !state.hasActiveFilters && activeIsLibraryEmpty -> {
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
                isNovels && activeSearchQuery.isNullOrEmpty() && activeIsLibraryEmpty -> {
                    EmptyScreen(
                        stringRes = MR.strings.information_empty_library,
                        modifier = Modifier.padding(contentPadding),
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
                                categories = activeCategories,
                                getItemsForCategory = activeGetItems,
                                collapsedCategories = activeCollapsedCategories,
                                collapsedDynamicCategories = if (isNovels) emptySet() else state.reikai.collapsedDynamicCategories,
                                showItemCounts = state.showMangaCount,
                                displayMode = displayMode,
                                columns = columns,
                                selection = activeSelection,
                                searchQuery = activeSearchQuery,
                                gridState = singleListGridState,
                                contentPadding = contentPadding,
                                onClickManga = { category, manga ->
                                    if (isNovels) {
                                        if (novelState.selectionMode) {
                                            novelModel.toggleSelection(category.id, manga.id)
                                        } else {
                                            novelState.routeFor(manga.id)?.let { navigator.push(NovelScreen(it.source, it.url)) }
                                        }
                                    } else if (state.selectionMode) {
                                        screenModel.toggleSelection(category, manga)
                                    } else {
                                        navigator.push(MangaScreen(manga.id))
                                    }
                                },
                                onLongClickManga = { category, manga ->
                                    // RK: range-select (incl. the in-between) like the tabbed view,
                                    // instead of toggling only the long-pressed manga.
                                    if (isNovels) {
                                        novelModel.toggleRangeSelection(category.id, manga.id)
                                    } else {
                                        screenModel.toggleRangeSelection(category, manga)
                                    }
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                },
                                onToggleDefaultCollapse = if (isNovels) novelModel::toggleCategoryCollapse else screenModel::toggleDefaultCategoryCollapse,
                                onToggleDynamicCollapse = screenModel::toggleDynamicCategoryCollapse,
                                onGlobalSearchClicked = {
                                    navigator.push(GlobalSearchScreen(activeSearchQuery ?: ""))
                                },
                                // RK 4.6: per-category header sort (Sort tab scoped to it), refresh, select-all
                                onClickCategorySort = { category ->
                                    if (isNovels) {
                                        novelModel.openSettingsDialog(category.id, 1)
                                    } else {
                                        screenModel.showSettingsDialog(initialTab = 1, categoryId = category.id)
                                    }
                                },
                                onRefreshCategory = { category -> onClickRefresh(category) },
                                onSelectAllInCategory = { category ->
                                    if (isNovels) novelModel.selectAllInCategory(category.id) else screenModel.selectAllInCategory(category)
                                },
                                onClickContinueReading = if (isNovels) {
                                    onNovelContinueReading.takeIf { novelState.showContinueButton }
                                } else {
                                    onMangaContinueReading.takeIf { state.showMangaContinueButton }
                                },
                            )
                        } else {
                            LibraryContent(
                                categories = activeCategories,
                                searchQuery = activeSearchQuery,
                                selection = activeSelection,
                                contentPadding = contentPadding,
                                pagerState = pagerState,
                                hasActiveFilters = activeHasActiveFilters,
                                showPageTabs = state.showCategoryTabs || !activeSearchQuery.isNullOrEmpty(),
                                onChangeCurrentPage = if (isNovels) novelModel::updateActiveCategoryIndex else screenModel::updateActiveCategoryIndex,
                                onClickManga = {
                                    if (isNovels) {
                                        novelState.routeFor(it)?.let { r -> navigator.push(NovelScreen(r.source, r.url)) }
                                    } else {
                                        navigator.push(MangaScreen(it))
                                    }
                                },
                                onContinueReadingClicked = if (isNovels) {
                                    onNovelContinueReading.takeIf { novelState.showContinueButton }
                                } else {
                                    onMangaContinueReading.takeIf { state.showMangaContinueButton }
                                },
                                onToggleSelection = { category, manga ->
                                    if (isNovels) novelModel.toggleSelection(category.id, manga.id) else screenModel.toggleSelection(category, manga)
                                },
                                onToggleRangeSelection = { category, manga ->
                                    if (isNovels) {
                                        novelModel.toggleRangeSelection(category.id, manga.id)
                                    } else {
                                        screenModel.toggleRangeSelection(category, manga)
                                    }
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                },
                                onRefresh = { onClickRefresh(state.activeCategory) },
                                onGlobalSearchClicked = {
                                    navigator.push(GlobalSearchScreen(activeSearchQuery ?: ""))
                                },
                                getItemCountForCategory = activeGetItemCount,
                                getDisplayMode = { screenModel.getDisplayMode() },
                                getColumnsForOrientation = { screenModel.getColumnsForOrientation(it) },
                                getItemsForCategory = activeGetItems,
                            )
                        }

                        if (!state.reikai.hideHopper && activeCategories.isNotEmpty()) {
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
                                    val last = activeCategories.lastIndex.coerceAtLeast(0)
                                    instantHopperScroll = true
                                    hopperTarget = ((hopperTarget ?: currentCategoryIndex()) - 1).coerceIn(0, last)
                                },
                                onCenterClick = { pickerOpen = true },
                                // RK: every hopper long-press action is content-aware (novel vs manga).
                                onCenterLongClick = {
                                    when (state.reikai.hopperLongPressAction) {
                                        0 -> if (isNovels) novelModel.search("") else screenModel.search("")
                                        1 -> if (isNovels) {
                                            novelModel.toggleAllCategoriesCollapsed(novelState.displayedCategories)
                                        } else {
                                            screenModel.toggleAllCategoriesCollapsed(state.displayedCategories)
                                        }
                                        2 -> if (isNovels) {
                                            novelModel.openSettingsDialog(
                                                novelState.activeCategory?.id ?: NovelCategory.UNCATEGORIZED_ID,
                                                2,
                                            )
                                        } else {
                                            screenModel.showSettingsDialog(initialTab = 2)
                                        }
                                        3 -> if (isNovels) {
                                            novelModel.openSettingsDialog(
                                                novelState.activeCategory?.id ?: NovelCategory.UNCATEGORIZED_ID,
                                                3,
                                            )
                                        } else {
                                            screenModel.showSettingsDialog(initialTab = 3)
                                        }
                                        4 -> scope.launch {
                                            if (isNovels) {
                                                novelState.randomRouteInCategory(novelState.activeCategory?.id)
                                                    ?.let { navigator.push(NovelScreen(it.source, it.url)) }
                                            } else {
                                                screenModel.getRandomLibraryItemForCurrentCategory()
                                                    ?.let { navigator.push(MangaScreen(it.libraryManga.manga.id)) }
                                            }
                                        }
                                        5 -> scope.launch {
                                            if (isNovels) {
                                                novelState.randomRoute()
                                                    ?.let { navigator.push(NovelScreen(it.source, it.url)) }
                                            } else {
                                                screenModel.getRandomLibraryItem()
                                                    ?.let { navigator.push(MangaScreen(it.libraryManga.manga.id)) }
                                            }
                                        }
                                    }
                                },
                                onDownClick = {
                                    val last = activeCategories.lastIndex.coerceAtLeast(0)
                                    instantHopperScroll = true
                                    hopperTarget = ((hopperTarget ?: currentCategoryIndex()) + 1).coerceIn(0, last)
                                },
                            )
                            }
                        }
                    }

                    if (pickerOpen) {
                        ReikaiCategoryPickerSheet(
                            categories = activeCategories,
                            getItemCount = activeGetItemCount,
                            showItemCounts = state.showMangaCount,
                            activeCategoryId = activeCategories.getOrNull(currentCategoryIndex())?.id,
                            onSelect = { category ->
                                instantHopperScroll = false
                                hopperTarget = activeCategories.indexOf(category)
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

        // RK --> novel library dialogs (change-category / delete / settings)
        when (val novelDialog = novelModel.dialog.collectAsState().value) {
            is NovelLibraryScreenModel.Dialog.ChangeCategory -> {
                ChangeCategoryDialog(
                    initialSelection = novelDialog.preselected,
                    onDismissRequest = novelModel::dismissDialog,
                    onEditCategories = {
                        novelModel.dismissDialog()
                        navigator.push(CategoryScreen(novels = true))
                    },
                    onConfirm = { include, exclude ->
                        novelModel.setNovelCategories(novelDialog.novelIds, include, exclude)
                    },
                )
            }
            is NovelLibraryScreenModel.Dialog.Delete -> {
                DeleteLibraryMangaDialog(
                    containsLocalManga = false,
                    onDismissRequest = novelModel::dismissDialog,
                    onConfirm = { deleteFromLibrary, deleteDownloads ->
                        novelModel.removeNovels(novelDialog.novelIds, deleteFromLibrary, deleteDownloads)
                    },
                )
            }
            is NovelLibraryScreenModel.Dialog.Settings -> {
                NovelLibrarySettingsDialog(
                    onDismissRequest = novelModel::dismissDialog,
                    screenModel = novelModel,
                    settingsScreenModel = settingsScreenModel,
                    categoryId = novelDialog.categoryId,
                    initialTab = novelDialog.initialTab,
                )
            }
            null -> {}
        }
        // RK <--

        BackHandler(enabled = activeSelectionMode || activeSearchQuery != null) {
            when {
                activeSelectionMode -> if (isNovels) novelModel.clearSelection() else screenModel.clearSelection()
                activeSearchQuery != null -> onSearch(null)
            }
        }

        LaunchedEffect(activeSelectionMode, state.dialog) {
            HomeScreen.showBottomNav(!activeSelectionMode)
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
