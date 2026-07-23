package eu.kanade.tachiyomi.ui.library

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import eu.kanade.presentation.manga.DownloadAction
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
import reikai.data.novel.update.NovelUpdateJob
import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType
import reikai.domain.library.sortForCategory
import reikai.domain.novel.model.NovelCategory
import reikai.domain.novel.model.NovelLibrarySort
import reikai.presentation.components.ContentTypeFilterChips
import reikai.presentation.library.LibraryBehavior
import reikai.presentation.library.LibraryEngine
import reikai.presentation.library.MangaLibraryAdapter
import reikai.presentation.library.NovelLibraryAdapter
import reikai.presentation.library.ReikaiCategoryHopper
import reikai.presentation.library.ReikaiCategoryPickerSheet
import reikai.presentation.library.ReikaiLibraryContent
import reikai.presentation.library.novels.NovelLibraryScreenModel
import reikai.presentation.library.novels.NovelLibrarySettingsDialog
import reikai.presentation.library.novels.novelSortLabelRes
import reikai.presentation.library.reikaiCategoryHeaderIndices
import reikai.presentation.library.reikaiIsCollapsed
import reikai.presentation.library.reikaiSortCategories
import reikai.presentation.library.sortLabelRes
import reikai.presentation.library.updateerror.UpdateErrorsScreen
import reikai.presentation.manga.MangaMigrationSourcePickScreen
import reikai.presentation.novel.details.NovelScreen
import reikai.presentation.novel.migrate.NovelMigrationSourcePickScreen
import reikai.presentation.novel.reader.NovelReaderScreen
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

        // RK --> novels in the library behind the Manga/Novels chip. Both models stay live; a per-type
        // adapter maps each onto the neutral LibraryScreenState / LibraryBehavior, so the tab reads one
        // `libState` and dispatches one `behavior` instead of branching manga-vs-novel per field. The chip
        // picks the active adapter; per-type navigation, dialog rendering and the hopper long-press stay
        // branched below (they need the navigator / per-type screen + dialog types). The `active*` locals
        // are kept as thin aliases over `libState` so every downstream view reads them unchanged.
        val novelModel = rememberScreenModel { NovelLibraryScreenModel() }
        val novelState by novelModel.state.collectAsState()
        val libraryContentType by novelModel.contentType.collectAsState()
        val isNovels = libraryContentType == ContentType.NOVELS
        val mangaAdapter = remember(screenModel) { MangaLibraryAdapter(screenModel) }
        val novelAdapter = remember(novelModel) { NovelLibraryAdapter(novelModel) }
        // The engine owns which provider drives the view, so the content type is decided in one place
        // rather than at each call site. It is shaped to merge both providers for an All view later.
        val engine = remember(mangaAdapter, novelAdapter) { LibraryEngine(listOf(mangaAdapter, novelAdapter)) }
        val behavior: LibraryBehavior = engine.behaviorFor(libraryContentType)
        // RK: collect BOTH adapters' state and pick synchronously, so flipping the chip switches
        // instantly. Collecting a single `behavior.state` over the switched adapter re-subscribes on the
        // flow change, holding the old value for a frame, which stutters the manga<->novel transition.
        // Both stateIn flows are eager, so the inactive side stays current for an instant swap.
        val mangaLibState by mangaAdapter.state.collectAsState()
        val novelLibState by novelAdapter.state.collectAsState()
        val libState = if (isNovels) novelLibState else mangaLibState
        val activeCategories = libState.categories
        val activeSelection = libState.selection
        val activeSearchQuery = libState.searchQuery
        val activeIsLibraryEmpty = libState.isLibraryEmpty
        val activeIsLoading = libState.isLoading
        val activeCollapsedCategories = libState.collapsedCategories
        val activeGetItems: (Category) -> List<LibraryItem> = libState.itemsForCategory
        val activeGetItemCount: (Category) -> Int? = libState.itemCountForCategory
        val onSearch: (String?) -> Unit = behavior::search
        val activeSelectionMode = libState.selectionMode
        val activeHasActiveFilters = libState.hasActiveFilters
        // The category on screen, from the active model. Category ids are per content type, so reading
        // this off the manga model while the Novels chip is up scopes an update to the wrong category.
        val activeCategory = activeCategories.getOrNull(libState.coercedActiveCategoryIndex)
        // RK <--

        val snackbarHostState = remember { SnackbarHostState() }

        // RK --> hopper + jump-to-category picker drive both views through a single `hopperTarget`.
        // Single-list jumps (prev/next and the picker) are instant: categories can hold hundreds of
        // items, so animating across them stutters, while an instant jump stays snappy under rapid
        // taps (this is what Yōkai does). The tabbed pager animates its page transition.
        // RK: one scroll state per content type, so toggling the Manga/Novels chip preserves each
        // view's own position instead of both sharing a single offset (upstream is manga-only). The
        // active pair falls through to the current type, like the other `active*` locals above.
        val mangaSingleListGridState = rememberLazyGridState()
        val novelSingleListGridState = rememberLazyGridState()
        val singleListGridState = if (isNovels) novelSingleListGridState else mangaSingleListGridState
        val mangaPagerState = rememberPagerState(initialPage = state.coercedActiveCategoryIndex) {
            state.displayedCategories.size
        }
        val novelPagerState = rememberPagerState(initialPage = novelState.coercedActiveCategoryIndex) {
            novelState.displayedCategories.size
        }
        val pagerState = if (isNovels) novelPagerState else mangaPagerState
        var pickerOpen by remember { mutableStateOf(false) }
        var hopperTarget by remember { mutableStateOf<Int?>(null) }
        var hopperDragAccum by remember { mutableFloatStateOf(0f) }
        fun reikaiHeaderIndices(): List<Int> = reikaiCategoryHeaderIndices(
            categories = activeCategories,
            hasSearchItem = !activeSearchQuery.isNullOrEmpty(),
            isCollapsed = {
                reikaiIsCollapsed(
                    it,
                    activeCollapsedCategories,
                    libState.collapsedDynamicCategories,
                )
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
                    // Jump instantly to the target category, the way Yōkai's hopper does. Categories
                    // here can hold hundreds of items, so a smooth scroll across them has to compose
                    // everything in between and stutters; an instant jump stays snappy under rapid
                    // taps. Land the header flush at the top: a negative offset to leave a gap would
                    // make firstVisibleItemIndex point at the previous category, so prev/next would
                    // read the current category one too low and stall after the first hop.
                    singleListGridState.scrollToItem(itemIndex)
                }
            } else {
                pagerState.animateScrollToPage(target)
            }
            hopperTarget = null
        }
        // RK <--

        // RK: route refresh to the right vertical's job. The novel job (KEEP-deduped) has no
        // already-running signal, so the Novels chip always reports "updating".
        val onClickRefresh: (Category?) -> Boolean = { category ->
            val started = if (isNovels) {
                NovelUpdateJob.startNow(context, category)
                true
            } else {
                LibraryUpdateJob.startNow(context, category)
            }
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

        // RK: open a random entry from the category on screen, for the toolbar overflow and the hopper's
        // long-press action. Shared so the two can't drift into opening different content types.
        val onOpenRandomInCurrentCategory: () -> Unit = {
            scope.launch {
                val opened = if (isNovels) {
                    novelState.randomRouteInCategory(novelState.activeCategory?.id)
                        ?.also { navigator.push(NovelScreen(it.source, it.url)) } != null
                } else {
                    screenModel.getRandomLibraryItemForCurrentCategory()
                        ?.also { navigator.push(MangaScreen(it.libraryManga.manga.id)) } != null
                }
                if (!opened) {
                    snackbarHostState.showSnackbar(context.stringResource(MR.strings.information_no_entries_found))
                }
            }
        }

        // RK: open an entry on its own details screen, routed by the ROW's content type rather than the
        // active chip. Navigation stays per-type (each type has its own screen), but the decision no
        // longer depends on ambient UI state, so a mixed list routes every row correctly.
        val openEntry: (EntryId) -> Unit = { entryId ->
            when (entryId) {
                is EntryId.Novel -> novelState.routeFor(entryId.rawId)?.let {
                    navigator.push(NovelScreen(it.source, it.url))
                }
                is EntryId.Manga -> navigator.push(MangaScreen(entryId.rawId))
            }
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
        // RK: novel continue-reading (both views). Resume opens the next unread chapter from its own
        // source in group scope, so the reader's prev/next spans the whole merge group (the reader
        // resolves it itself).
        val onNovelContinueReading: (LibraryManga) -> Unit = { item ->
            scope.launchIO {
                val resume = novelModel.getResume(item.manga.id)
                if (resume != null) {
                    withUIContext {
                        navigator.push(NovelReaderScreen(resume.novelId, resume.id))
                    }
                } else {
                    snackbarHostState.showSnackbar(context.stringResource(MR.strings.no_next_chapter))
                }
            }
        }
        // RK: the resume handler is per-type navigation, dispatched on the ROW's own content type rather
        // than the active chip, so a mixed list resumes each row in its own reader. The gate is neutral.
        val onContinueReading: ((LibraryItem) -> Unit)? = { item: LibraryItem ->
            when (item.entryId) {
                is EntryId.Novel -> onNovelContinueReading(item.libraryManga)
                is EntryId.Manga -> onMangaContinueReading(item.libraryManga)
            }
        }.takeIf { libState.showContinueButton }

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
                        onClickUnselectAll = behavior::clearSelection,
                        onClickSelectAll = behavior::selectAll,
                        onClickInvertSelection = behavior::invertSelection,
                        onClickFilter = {
                            // RK: the toolbar sort is GLOBAL (Model A); a null category scopes the sheet to
                            // the global sort, not a stale active category. Per-category overrides are set
                            // from each category header's sort in the single-list view.
                            behavior.openSettingsDialog(categoryId = null, initialTab = 0)
                        },
                        onClickRefresh = { onClickRefresh(activeCategory) },
                        onClickGlobalUpdate = { onClickRefresh(null) },
                        // RK: follows the content-type chip; it used to always open a manga.
                        onClickOpenRandomManga = onOpenRandomInCurrentCategory,
                        // RK: opt-in Update errors screen (hidden unless the matching Advanced toggle is on);
                        //     opens on the chip for the content type currently shown.
                        onClickUpdateErrors = run {
                            val enabled =
                                if (isNovels) state.reikai.trackNovelUpdateErrors else state.reikai.trackUpdateErrors
                            if (enabled) {
                                {
                                    val initial = if (isNovels) ContentType.NOVELS else ContentType.MANGA
                                    navigator.push(UpdateErrorsScreen(initial))
                                }
                            } else {
                                null
                            }
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
                // RK: one action bar for both content types (download menu, mark read/unread, change
                // category, delete, merge, unmerge). Only the batch-migrate nav stays per-type: it pushes a
                // per-type source-pick screen over each type's own id space.
                LibraryBottomActionMenu(
                    visible = activeSelectionMode,
                    onChangeCategoryClicked = { behavior.openChangeCategoryDialog(activeSelection) },
                    onMarkAsReadClicked = { behavior.markReadSelection(activeSelection, true) },
                    onMarkAsUnreadClicked = { behavior.markReadSelection(activeSelection, false) },
                    // RK: manga hides Download when every selected entry is local; novels never do.
                    onDownloadClicked = { action: DownloadAction ->
                        behavior.performDownloadAction(activeSelection, action)
                    }
                        .takeIf { behavior.canDownload(activeSelection) },
                    onDeleteClicked = { behavior.openDeleteDialog(activeSelection) },
                    onMigrateClicked = {
                        if (isNovels) {
                            val ids = novelState.selectedNovelIds
                            behavior.clearSelection()
                            navigator.push(NovelMigrationSourcePickScreen(ids))
                        } else {
                            val selection = state.selection
                            behavior.clearSelection()
                            // RK: source picker first (merged-manga member choice).
                            navigator.push(MangaMigrationSourcePickScreen(selection.toList()))
                        }
                    },
                    // RK: manual merge of the selected entries (needs at least two) + unmerge (only when the
                    // selection includes a merged one).
                    onMergeClicked = { behavior.mergeSelection(activeSelection) }
                        .takeIf { activeSelection.size >= 2 },
                    onUnmergeClicked = { behavior.unmergeSelection(activeSelection) }
                        .takeIf { behavior.containsMerged(activeSelection) },
                )
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
                            // RK: the global sort each non-overridden category follows (re-read per render;
                            // a global-sort change re-sorts the library, which recomposes this).
                            val mangaGlobalSort = settingsScreenModel.libraryPreferences.sortingMode.get()
                            val novelDefaultSort = NovelLibrarySort.fromFlag(
                                settingsScreenModel.reikaiLibraryPreferences.novelLibraryDefaultSort.get(),
                            )
                            ReikaiLibraryContent(
                                categories = activeCategories,
                                getItemsForCategory = activeGetItems,
                                collapsedCategories = activeCollapsedCategories,
                                collapsedDynamicCategories = libState.collapsedDynamicCategories,
                                showItemCounts = state.showMangaCount,
                                displayMode = displayMode,
                                columns = columns,
                                selection = activeSelection,
                                searchQuery = activeSearchQuery,
                                gridState = singleListGridState,
                                contentPadding = contentPadding,
                                onClickManga = { category, item ->
                                    if (libState.selectionMode) {
                                        behavior.toggleSelection(category, item.libraryManga)
                                    } else {
                                        // RK: navigation is per-type, routed by the ROW's own content
                                        // type rather than the active chip, so a mixed list opens each
                                        // row on its own screen.
                                        openEntry(item.entryId)
                                    }
                                },
                                onLongClickManga = { category, item ->
                                    // RK: range-select (incl. the in-between) like the tabbed view,
                                    // instead of toggling only the long-pressed manga.
                                    behavior.toggleRangeSelection(category, item.libraryManga)
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                },
                                onToggleDefaultCollapse = behavior::toggleDefaultCategoryCollapse,
                                onToggleDynamicCollapse = behavior::toggleDynamicCategoryCollapse,
                                onGlobalSearchClicked = {
                                    navigator.push(GlobalSearchScreen(activeSearchQuery ?: ""))
                                },
                                // RK: pull-to-refresh on the single-list updates the whole library (= overflow Update library).
                                onRefresh = { onClickRefresh(null) },
                                // RK: per-category header sort (Sort tab scoped to it), refresh, select-all
                                onClickCategorySort = { category ->
                                    behavior.openSettingsDialog(categoryId = category.id, initialTab = 1)
                                },
                                onRefreshCategory = { category -> onClickRefresh(category) },
                                onSelectAllInCategory = { category -> behavior.selectAllInCategory(category) },
                                // RK: the header shows each category's EFFECTIVE sort (its own override, or
                                // the global sort it follows), decoded per content type so the label + arrow
                                // match the actual ordering.
                                sortLabelFor = if (isNovels) {
                                    { category ->
                                        novelSortLabelRes(
                                            NovelLibrarySort.forCategory(category.flags, novelDefaultSort).type,
                                        )
                                    }
                                } else {
                                    { category -> sortLabelRes(sortForCategory(category.flags, mangaGlobalSort).type) }
                                },
                                sortAscendingFor = if (isNovels) {
                                    { category ->
                                        NovelLibrarySort.forCategory(category.flags, novelDefaultSort).isAscending
                                    }
                                } else {
                                    { category -> sortForCategory(category.flags, mangaGlobalSort).isAscending }
                                },
                                onClickContinueReading = onContinueReading,
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
                                onChangeCurrentPage = behavior::updateActiveCategoryIndex,
                                onClickManga = openEntry,
                                onContinueReadingClicked = onContinueReading,
                                onToggleSelection = { category, item ->
                                    behavior.toggleSelection(category, item.libraryManga)
                                },
                                onToggleRangeSelection = { category, item ->
                                    behavior.toggleRangeSelection(category, item.libraryManga)
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                },
                                onRefresh = { onClickRefresh(activeCategory) },
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
                                            4 -> onOpenRandomInCurrentCategory()
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
                    // live category by id so the Sort tab reflects the current sort after it changes. A
                    // toolbar open (null id) leaves it null = the GLOBAL sort scope (Model A), not a stale
                    // active category.
                    category = dialog.categoryId?.let { id -> state.libraryData.categories.find { it.id == id } },
                    // RK --> full category list for the include/exclude filter (sorted by the category order) + route to category manager
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
                    // RK: offer removing every grouped source when a merged cover is selected
                    groupedSourceCount = dialog.groupedSourceCount,
                    onDismissRequest = onDismissRequest,
                    onConfirm = { deleteManga, deleteChapter, removeGrouped ->
                        screenModel.removeMangas(dialog.manga, deleteManga, deleteChapter, removeGrouped)
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
                    // RK: offer removing every grouped source when a merged novel cover is selected
                    groupedSourceCount = novelDialog.groupedSourceCount,
                    onDismissRequest = novelModel::dismissDialog,
                    onConfirm = { deleteFromLibrary, deleteDownloads, removeGrouped ->
                        novelModel.removeNovels(novelDialog.novelIds, deleteFromLibrary, deleteDownloads, removeGrouped)
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
                    onManageCategories = {
                        novelModel.dismissDialog()
                        navigator.push(CategoryScreen(novels = true))
                    },
                )
            }
            null -> {}
        }
        // RK <--

        BackHandler(enabled = activeSelectionMode || activeSearchQuery != null) {
            when {
                activeSelectionMode -> behavior.clearSelection()
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
