package yokai.presentation.library

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HeartBroken
import androidx.compose.material.icons.outlined.CallMerge
import androidx.compose.material.icons.outlined.CallSplit
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.data.database.models.Category
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.ui.library.LibrarySort
import eu.kanade.tachiyomi.ui.library.LibraryItem.Companion.LAYOUT_COMFORTABLE_GRID
import eu.kanade.tachiyomi.ui.library.LibraryItem.Companion.LAYOUT_COMPACT_GRID
import eu.kanade.tachiyomi.ui.library.LibraryItem.Companion.LAYOUT_COVER_ONLY_GRID
import eu.kanade.tachiyomi.ui.library.LibraryItem.Companion.LAYOUT_LIST
import eu.kanade.tachiyomi.ui.library.models.LibraryItem
import eu.kanade.tachiyomi.util.isLocal
import eu.kanade.tachiyomi.util.system.getResourceColor
import eu.kanade.tachiyomi.util.system.openInBrowser
import eu.kanade.tachiyomi.widget.EmptyView
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlinx.coroutines.launch
import yokai.domain.manga.models.cover
import yokai.i18n.MR
import yokai.presentation.library.components.ActiveCategoryChip
import yokai.presentation.library.components.CategoryHopper
import yokai.presentation.library.components.CategoryPickerSheet
import yokai.presentation.library.components.CategorySortSheet
import yokai.presentation.library.components.LazyLibraryGrid
import yokai.presentation.library.components.LazyLibraryList
import yokai.presentation.library.components.LazyLibraryStaggeredGrid
import yokai.presentation.component.EmptyScreen
import yokai.presentation.library.components.LibraryCategoryHeader
import yokai.presentation.library.components.LibraryOverflowMenu
import yokai.presentation.library.components.SelectionAppBar
import yokai.presentation.library.settings.LibraryDisplayOptionsSheet
import yokai.presentation.library.manga.MangaLibraryGridCell
import yokai.presentation.library.manga.MangaLibraryListItem
import yokai.presentation.manga.components.MangaCoverRatio

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryContent(
    library: Map<Category, List<LibraryItem.Manga>>,
    allCategories: List<Category>,
    categoryItemCounts: Map<Int, Int>,
    /**
     * Per-category item count for the in-grid header. Sourced from the post-filter,
     * pre-collapse library so collapsing a category does not zero out its header count.
     */
    displayedHeaderCounts: Map<Int, Int>,
    /** Category IDs the user has collapsed via the header chevron. */
    collapsedIds: Set<Int>,
    /** Whether the header chevron is interactive (false for dynamic groupings). */
    collapsibleHeaders: Boolean,
    showCategoryItemCounts: Boolean,
    columns: Int,
    libraryLayout: Int,
    uniformGrid: Boolean,
    useStaggeredGrid: Boolean,
    searchActive: Boolean,
    searchQuery: String,
    showCategoryInTitle: Boolean,
    hideHopper: Boolean,
    autohideHopper: Boolean,
    hopperGravity: Int,
    outlineOnCovers: Boolean,
    showDownloadBadge: Boolean,
    showLanguageBadge: Boolean,
    unreadBadgeType: Int,
    /** When true, suppress the continue-reading button overlay on covers with unread chapters. */
    hideStartReadingButton: Boolean,
    /** Mirrors `preferences.useLargeToolbar()` — large title that collapses on scroll. */
    useLargeToolbar: Boolean,
    isAnyFilterActive: Boolean,
    /**
     * `preferences.showAllCategories()`. Gates per-header refresh-icon visibility
     * (legacy `isSingleCategory = ... || !showAllCategories` at LibraryCategoryAdapter.kt:64-65).
     */
    showAllCategories: Boolean,
    /**
     * True when only the active category is being rendered (legacy `showAllCategories = false`
     * + grouped by default + >1 category). When true, [library] contains exactly one entry, the
     * per-category header is skipped, and the hopper / picker switch which category renders by
     * writing through [onActiveCategoryChange] instead of scrolling.
     */
    singleCategoryMode: Boolean,
    /** Whether [eu.kanade.tachiyomi.data.library.LibraryUpdateJob] is currently running. */
    isRunning: Boolean,
    /** Category IDs currently mid-update; drives the spinner on each header's refresh icon. */
    inQueueCategoryIds: Set<Int>,
    /** Shared host owned by [LibraryScreen]. Snackbars for PTR + per-category refresh land here. */
    snackbarHostState: SnackbarHostState,
    sheetOpen: Boolean,
    sheetTab: Int,
    overflowOpen: Boolean,
    detectedMangaTypes: Set<Int>,
    loggedTrackerNames: List<String>,
    /**
     * Currently selected manga ids. Non-empty switches the top bar to [SelectionAppBar] and
     * tints each selected cover via the cell's `isSelected` flag.
     */
    selection: Set<Long>,
    onSearchActiveChange: (Boolean) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onHopperGravityChange: (Int) -> Unit,
    onToggleCategoryCollapse: (Category) -> Unit,
    /**
     * Pref value (0..4) for the hopper center-button long-press. Matches the legacy options:
     * 0 = search, 1 = expand/collapse all, 2 = display options, 3 = group by, 4 = random.
     */
    hopperLongPressAction: Int,
    onExpandCollapseAllCategories: () -> Unit,
    /** Open the Display options sheet on a specific tab (0 filter / 1 display / 2 badges / 3 categories). */
    onOpenSheetAt: (Int) -> Unit,
    /** Navigate to a random series from the entire library (global). Matches legacy hopper long-press index 5. */
    onOpenRandomSeries: () -> Unit,
    /**
     * Navigate to a random series within the currently-active category. Matches legacy hopper
     * long-press index 4 (`openRandomManga(false)`). Receiver gets the active category and is
     * expected to no-op when it can't resolve one (search active, empty library).
     */
    onOpenRandomInCategory: (Category?) -> Unit,
    /**
     * Open the standalone Group library by picker dialog. Matches legacy hopper long-press
     * index 3 (`showGroupOptions()`), which opens just the small picker rather than the full
     * Display options sheet.
     */
    onOpenGroupByPicker: () -> Unit,
    /** Invoked when the user taps the continue-reading button on a cover with unread chapters. */
    onContinueReading: (Manga) -> Unit,
    /** Tap on a manga cell (grid cover or list row). Routes to the manga details screen. */
    onMangaClick: (Manga) -> Unit,
    onOpenFilter: () -> Unit,
    onOpenOverflow: () -> Unit,
    onDismissSheet: () -> Unit,
    onDismissOverflow: () -> Unit,
    onSheetTabChange: (Int) -> Unit,
    /**
     * Fires when the first visible category changes due to scroll, hopper navigation, or
     * picker selection. Receiver persists this to `preferences.lastUsedCategory()`, matching
     * legacy `LibraryController.kt:347-351` where the scroll listener writes the same pref.
     * This keeps `presenter.currentCategory` / Compose's `currentCategoryOrder` in sync with
     * the visible category across both libraries.
     */
    onActiveCategoryChange: (Category) -> Unit,
    /**
     * Pull-to-refresh dispatcher. Receiver decides the target (active category vs all) per
     * legacy `setSwipeRefresh` branch and shows the appropriate snackbar.
     */
    onPullToRefresh: () -> Unit,
    /**
     * Per-category refresh dispatcher (tap on a category header's refresh icon). Receiver
     * branches snackbar wording on already-in-queue / adding-to-queue / starting state, then
     * dispatches the update if not already queued.
     */
    onRefreshCategory: (Category) -> Unit,
    /**
     * Phase 6: user picked a sort mode for [Category] from the per-category sort sheet. Routed
     * through `MangaLibraryScreenModel.setSort` which handles direction toggle + write routing.
     */
    onSortChange: (Category, LibrarySort) -> Unit,
    /**
     * Reikai-fork `preferences.categorySortOrder` (0 manual, 1 A→Z, 2 Z→A). Passed through so
     * iteration-order-dependent `remember(library, ...)` calls inside this composable invalidate
     * when the sort pref changes. `Map<Category, ...>.equals` is content-only and ignores
     * iteration order, so without this key the remembers (e.g. `categoryOffsets`) would keep
     * returning the previous order's value after a sort toggle.
     */
    categorySortOrder: Int,
    /** Toggle long-pressed manga in the selection set. C2 onward. */
    onToggleSelection: (Long) -> Unit,
    /** Clear the selection set. Wired to the SelectionAppBar close icon and BackHandler. */
    onClearSelection: () -> Unit,
    /** C3: open Android share sheet for selected manga URLs. */
    onShareSelection: () -> Unit,
    /** C3: silent bulk download of unread chapters for the selection. */
    onDownloadUnread: () -> Unit,
    /** C3: show the mark-all-as-read confirmation dialog. */
    onConfirmAndMarkRead: () -> Unit,
    /** C3: show the mark-all-as-unread confirmation dialog. */
    onConfirmAndMarkUnread: () -> Unit,
    /** C4: open the SetCategoriesSheet for the selection (bridges to legacy dialog). */
    onMoveToCategories: () -> Unit,
    /** C5: show the delete confirmation dialog (Remove from library / also delete downloads). */
    onConfirmAndDelete: () -> Unit,
    /** C6: push PreMigrationController for the non-local manga in selection. */
    onMigrate: () -> Unit,
    /**
     * C6: true when at least one selected manga is from a remote (HttpSource) source. Used to
     * hide the migrate menu entry when every selection is `LocalSource`, matching legacy at
     * LibraryController.kt:2042.
     */
    selectionHasRemoteSources: Boolean,
    /** C7: merge the current selection into one group. */
    onMerge: () -> Unit,
    /** C7: split the current selection out of its existing merge group(s). */
    onUnmerge: () -> Unit,
    /** F4: toggle every manga in a category in/out of the selection (header circle tap). */
    onToggleCategorySelection: (Int) -> Unit,
    /**
     * C7: true when the selection has 2+ items (merge requires at least two to combine).
     * Drives the merge menu entry's `enabled` flag.
     */
    canMerge: Boolean,
    /**
     * C7: true when every selected manga is part of an existing manual-merge group. Used to
     * show / hide the unmerge menu entry (no point offering unmerge when nothing is grouped).
     */
    canUnmerge: Boolean,
    modifier: Modifier = Modifier,
) {
    // Selection clear takes priority over search close: both are back-press affordances, but a
    // user mid-action with a selected set probably wants to clear that first before exiting
    // search. Compose stacks BackHandlers in declaration order with the latest declared at the
    // top of the stack, so declare selection AFTER search and selection wins.
    // Per-category sort sheet target. Set to the tapped category's id when the user taps a
    // header's sort affordance; cleared on dismiss or after a mode is picked. Top-level state
    // so a single sheet hosts all category headers across the three layout variants.
    //
    // Keying on id (not the Category instance) so the sheet always renders against the freshest
    // Category snapshot from `library` on every recomposition. Otherwise capturing the Category
    // reference at tap-time would let the sheet show stale sortingMode / isAscending values
    // after a setSort write propagates between sheet sessions.
    var sortingCategoryId by remember { mutableStateOf<Int?>(null) }

    BackHandler(enabled = searchActive) {
        onSearchQueryChange("")
        onSearchActiveChange(false)
    }
    BackHandler(enabled = selection.isNotEmpty()) {
        onClearSelection()
    }

    // F10: legacy parity with MainActivity.setUndoSnackBar dismissal triggers
    // (MainActivity.kt:227-237, :680, :998, :1390-1418). When the undo snackbar dismisses,
    // the existing showSnackbar coroutine returns SnackbarResult.Dismissed and the cleanup
    // branch runs (confirmDeletion / confirmMarkReadStatus), queued on screenModelScope which
    // survives the LibraryScreen composition. Three triggers added:
    //
    //   1. ON_PAUSE (lifecycle observer below): app going to background or another activity
    //      being launched (Reader, share chooser) dismisses the snackbar.
    //   2. Tap outside snackbar bounds (pointerInput wrapper on content): mirrors legacy's
    //      dispatchTouchEvent-driven dismissal after a 1-second grace.
    //   3. Navigation (LibraryScreen-side): each router.pushController call dismisses first.
    //      Handled in LibraryScreen's callbacks, not here.
    var snackbarShownAtMs by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(snackbarHostState.currentSnackbarData) {
        snackbarShownAtMs = if (snackbarHostState.currentSnackbarData != null) {
            System.currentTimeMillis()
        } else {
            null
        }
    }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner, snackbarHostState) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) {
                snackbarHostState.currentSnackbarData?.dismiss()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val isList = libraryLayout == LAYOUT_LIST
    // uniformGrid wins over useStaggeredGrid: the legacy display sheet greys out the staggered
    // switch when uniformGrid is on (LibraryDisplayView.initGeneralPreferences sets
    // staggeredGrid.isEnabled = !uniformGrid). Mirror that here so a leftover useStaggeredGrid
    // pref does not silently override a user who later turned uniformGrid back on.
    val isStaggered = !isList && useStaggeredGrid && !uniformGrid
    val gridState = rememberLazyGridState()
    val staggeredGridState = rememberLazyStaggeredGridState()
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // Precomputed map of (lazy-scroll header index) -> Category. Each layout emits one header
    // item followed by N item entries per category, so each header's index = sum of
    // (1 + items.size) for all preceding categories regardless of container. Includes
    // categorySortOrder as a key because library equality is content-only and ignores
    // iteration order; without this, the offsets keep the old category order after a
    // categorySortOrder toggle.
    val categoryOffsets = remember(library, categorySortOrder) {
        var offset = 0
        library.map { (cat, items) ->
            val start = offset
            offset += 1 + items.size
            start to cat
        }
    }

    // Generalized accessors so the hopper / chip code stays layout-agnostic.
    val firstVisibleItemIndex by remember(isList, isStaggered) {
        derivedStateOf {
            when {
                isList -> listState.firstVisibleItemIndex
                isStaggered -> staggeredGridState.firstVisibleItemIndex
                else -> gridState.firstVisibleItemIndex
            }
        }
    }
    val firstVisibleItemScrollOffset by remember(isList, isStaggered) {
        derivedStateOf {
            when {
                isList -> listState.firstVisibleItemScrollOffset
                isStaggered -> staggeredGridState.firstVisibleItemScrollOffset
                else -> gridState.firstVisibleItemScrollOffset
            }
        }
    }
    val scrollInteractionSource = when {
        isList -> listState.interactionSource
        isStaggered -> staggeredGridState.interactionSource
        else -> gridState.interactionSource
    }
    val scrollTo: suspend (Int) -> Unit = { idx ->
        when {
            isList -> listState.scrollToItem(idx)
            isStaggered -> staggeredGridState.scrollToItem(idx)
            else -> gridState.scrollToItem(idx)
        }
    }
    val isScrollInProgress by remember(isList, isStaggered) {
        derivedStateOf {
            when {
                isList -> listState.isScrollInProgress
                isStaggered -> staggeredGridState.isScrollInProgress
                else -> gridState.isScrollInProgress
            }
        }
    }

    val activeCategory by remember(categoryOffsets) {
        derivedStateOf {
            categoryOffsets.lastOrNull { it.first <= firstVisibleItemIndex }?.second
        }
    }

    // Mirror legacy LibraryController.kt:347-351 (scroll listener) + :1405 (scrollToHeader
    // direct write): every time the visible category changes (natural scroll, hopper picker
    // tap, hopper up/down nav), write its order to preferences.lastUsedCategory(). This is the
    // backing for presenter.currentCategory in legacy, so keeping it in sync via Compose lets
    // pull-to-refresh's currentCategoryOrder lookup target the right category AND ensures the
    // legacy library opens on the right category if the user toggles back.
    LaunchedEffect(activeCategory) {
        activeCategory?.let { onActiveCategoryChange(it) }
    }

    // singleCategoryMode keeps the chip visible because library.size == 1 in that mode but the
    // user still needs to see which of their categories is active (and tap it to switch).
    val showChip = showCategoryInTitle &&
        (library.size > 1 || singleCategoryMode) &&
        activeCategory != null &&
        !searchActive

    // Autohide tracks the full drag → fling → settle lifecycle so the hopper stays hidden
    // through the fling after the user lifts their finger, matching the legacy RecyclerView
    // scroll listener behavior. Just watching isScrollInProgress would also hide the hopper
    // for programmatic scrolls (hopper button taps), so we latch on drag start and clear on
    // scroll settle: the hopper's scrollToItem calls are instant and never set the drag bit,
    // so userInitiatedScroll stays false for those.
    val isUserDragging by scrollInteractionSource.collectIsDraggedAsState()
    var userInitiatedScroll by remember { mutableStateOf(false) }
    LaunchedEffect(isUserDragging) {
        if (isUserDragging) userInitiatedScroll = true
    }
    LaunchedEffect(isScrollInProgress) {
        if (!isScrollInProgress) userInitiatedScroll = false
    }
    // Hopper appears whenever the user has >1 category to switch between, whether they're all
    // rendered (show-all mode, library.size > 1) or only one at a time (single mode,
    // allCategories.size > 1 but library.size == 1).
    val hopperVisible by remember(hideHopper, autohideHopper, library, allCategories, searchActive) {
        derivedStateOf {
            val multipleCategories = library.size > 1 || allCategories.size > 1
            val base = !searchActive && !hideHopper && multipleCategories
            if (autohideHopper) base && !userInitiatedScroll else base
        }
    }

    // Absolute X positioning for the hopper instead of Modifier.align. Lets us animate the
    // hopper smoothly between gravity positions (legacy "flick from position to position"
    // feel) without the visual jump that an align swap would cause, and lets us clamp the
    // drag to keep the hopper fully on-screen regardless of gravity. Parent width comes from
    // the Box's onSizeChanged below; hopper width from the CategoryHopper's onSizeChanged.
    var parentWidthPx by remember { mutableIntStateOf(0) }
    var hopperWidthPx by remember { mutableIntStateOf(0) }

    // Instant scrolls so rapid clicks don't queue. animateScrollToItem serializes via the
    // grid's scroll Mutex, and each animation takes ~300ms; rapid taps stacked 5 animations
    // sequentially, and the next-target computation kept reading mid-animation
    // firstVisibleItemIndex so each queued click resolved to a stale target. scrollToItem
    // resolves in a single frame, so each tap immediately advances firstVisibleItemIndex and
    // the next tap reads the updated state. Matches the legacy scrollToPositionWithOffset.
    val onHopperUp = if (singleCategoryMode) {
        // Switch the active category by writing through onActiveCategoryChange instead of
        // scrolling. No-wrap matches legacy: at the first category, up is a no-op.
        {
            val active = activeCategory
            val currentIdx = if (active != null) allCategories.indexOfFirst { it.id == active.id } else -1
            if (currentIdx > 0) {
                onActiveCategoryChange(allCategories[currentIdx - 1])
            }
            Unit
        }
    } else {
        {
            val activeIdx = categoryOffsets.indexOfLast { it.first <= firstVisibleItemIndex }
            if (activeIdx >= 0) {
                val activeHeaderIdx = categoryOffsets[activeIdx].first
                val pastHeader = firstVisibleItemIndex > activeHeaderIdx ||
                    firstVisibleItemScrollOffset > 0
                val target = when {
                    pastHeader -> activeHeaderIdx
                    activeIdx > 0 -> categoryOffsets[activeIdx - 1].first
                    else -> null
                }
                target?.let { idx ->
                    coroutineScope.launch { scrollTo(idx) }
                }
            }
            Unit
        }
    }
    val onHopperDown = if (singleCategoryMode) {
        {
            val active = activeCategory
            val currentIdx = if (active != null) allCategories.indexOfFirst { it.id == active.id } else -1
            if (currentIdx >= 0 && currentIdx < allCategories.lastIndex) {
                onActiveCategoryChange(allCategories[currentIdx + 1])
            }
            Unit
        }
    } else {
        {
            val activeIdx = categoryOffsets.indexOfLast { it.first <= firstVisibleItemIndex }
            val nextHeader = categoryOffsets.getOrNull(activeIdx + 1)?.first
            nextHeader?.let { idx ->
                coroutineScope.launch { scrollTo(idx) }
            }
            Unit
        }
    }

    var pickerOpen by remember { mutableStateOf(false) }

    // Drag-and-fling gravity. Mirrors LibraryGestureDetector.onFling: a horizontal fling that
    // exceeds both distance and velocity thresholds steps the hopper one gravity position
    // toward the swipe direction (0 <-> 1 <-> 2). The Animatable holds the hopper's absolute
    // X within the parent Box; a LaunchedEffect animates it whenever the gravity preference
    // (and therefore targetX) changes, giving the legacy smooth slide between positions.
    val density = LocalDensity.current
    val edgePaddingPx = with(density) { 20.dp.toPx() }
    val velocityThresholdPx = with(density) { 100.dp.toPx() }
    val distanceThresholdPx = with(density) { 50.dp.toPx() }

    fun xForGravity(g: Int): Float = when {
        parentWidthPx == 0 || hopperWidthPx == 0 -> 0f
        g == 0 -> edgePaddingPx
        g == 2 -> (parentWidthPx - hopperWidthPx - edgePaddingPx).coerceAtLeast(0f)
        else -> ((parentWidthPx - hopperWidthPx) / 2f).coerceAtLeast(0f)
    }

    val targetX = xForGravity(hopperGravity)
    val hopperX = remember { Animatable(targetX) }
    LaunchedEffect(targetX) {
        if (hopperX.value != targetX) {
            hopperX.animateTo(targetX, animationSpec = tween(250))
        }
    }

    val draggableState = rememberDraggableState { delta ->
        coroutineScope.launch {
            val minX = edgePaddingPx
            val maxX = (parentWidthPx - hopperWidthPx - edgePaddingPx).coerceAtLeast(minX)
            hopperX.snapTo((hopperX.value + delta).coerceIn(minX, maxX))
        }
    }
    // Vertical fling state for the hopper: accumulates raw drag pixels so onDragStopped can
    // check the legacy thresholds (50 px distance + 100 px/s velocity). No drag-follow; legacy
    // doesn't translate the hopper vertically either, only horizontally for gravity feedback.
    var hopperVerticalDragPx by remember { mutableFloatStateOf(0f) }
    val hopperVerticalDraggableState = rememberDraggableState { delta ->
        hopperVerticalDragPx += delta
    }

    // Topbar scroll behavior matches the legacy CoordinatorLayout setup:
    //   - useLargeToolbar = true → two-stage behavior matching legacy
    //     scroll|enterAlways|enterAlwaysCollapsed flags:
    //       Stage 1: LargeTopAppBar collapses from large to small via
    //                exitUntilCollapsedScrollBehavior.
    //       Stage 2: Once fully collapsed, the bar slides further up by its own collapsed
    //                height (hideOffsetPx), driven by the custom NestedScrollConnection
    //                below. On reverse scroll, the small bar reappears first (enterAlways),
    //                then the large title expands back at the top.
    //   - useLargeToolbar = false → small TopAppBar fully hides on scroll
    //     (enterAlwaysScrollBehavior), matching the legacy plain-toolbar scroll|enterAlways
    //     flags.
    //   - searchActive overrides both: pin the bar so the keyboard target stays put.
    val collapseBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val enterAlwaysBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    // Measured at the topBar wrapper below via onSizeChanged. The hide stage slides the bar
    // up by this amount; using the measured value (instead of the 64.dp collapsed-content
    // height) is critical because TopAppBarDefaults.windowInsets adds the system status-bar
    // inset above the bar content, and that extra strip must scroll off too. Tracks the
    // CURRENT collapsed bar height: while the bar is mid-collapse this would over-hide, but
    // hideOffsetPx only goes negative once collapseBehavior has saturated, by which point the
    // bar is at its small height.
    var collapsedBarHeightPx by remember { mutableFloatStateOf(0f) }
    var hideOffsetPx by remember { mutableFloatStateOf(0f) }
    val layoutDirection = LocalLayoutDirection.current

    // Stage-2 connection: chains the collapse and hide phases.
    //
    // Scroll-up (dy < 0): the LargeTopAppBar's collapseBehavior consumes first via its
    // onPreScroll (it only intercepts negative y). Once the bar is fully collapsed, that
    // handler returns Zero; the leftover drives hideOffsetPx toward -collapsedBarHeightPx,
    // sliding the small bar off-screen.
    //
    // Scroll-down (dy > 0): collapseBehavior's onPreScroll is a no-op on positive y; expansion
    // happens in its onPostScroll. So we handle un-hide (hideOffsetPx → 0) in pre-scroll
    // ourselves, then delegate post-scroll wholesale. Once un-hidden, the collapseBehavior's
    // own onPostScroll expands the large title when the lazy grid hits the top and there is
    // leftover positive available.y to consume.
    val twoStageConnection = remember(collapseBehavior) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val dy = available.y
                if (dy == 0f) return Offset.Zero
                return if (dy < 0f) {
                    val consumed = collapseBehavior.nestedScrollConnection.onPreScroll(available, source)
                    val remaining = dy - consumed.y
                    if (remaining == 0f) {
                        consumed
                    } else {
                        val before = hideOffsetPx
                        hideOffsetPx = (before + remaining).coerceIn(-collapsedBarHeightPx, 0f)
                        Offset(0f, consumed.y + (hideOffsetPx - before))
                    }
                } else {
                    // dy > 0: un-hide first. Expansion of the large title happens in post-
                    // scroll, not here, since collapseBehavior.onPreScroll returns Zero for
                    // positive y.
                    if (hideOffsetPx < 0f) {
                        val before = hideOffsetPx
                        hideOffsetPx = (before + dy).coerceAtMost(0f)
                        Offset(0f, hideOffsetPx - before)
                    } else {
                        Offset.Zero
                    }
                }
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                // Delegate post-scroll to collapseBehavior: scroll-up keeps heightOffset in
                // sync via the consumed delta, scroll-down expands the large title when the
                // lazy grid overscrolls at the top.
                return collapseBehavior.nestedScrollConnection.onPostScroll(consumed, available, source)
            }

            override suspend fun onPreFling(available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
                return collapseBehavior.nestedScrollConnection.onPreFling(available)
            }

            override suspend fun onPostFling(
                consumed: androidx.compose.ui.unit.Velocity,
                available: androidx.compose.ui.unit.Velocity,
            ): androidx.compose.ui.unit.Velocity {
                return collapseBehavior.nestedScrollConnection.onPostFling(consumed, available)
            }
        }
    }

    val scrollBehavior: TopAppBarScrollBehavior? = when {
        searchActive -> null
        useLargeToolbar -> collapseBehavior
        else -> enterAlwaysBehavior
    }
    val activeNestedScroll: NestedScrollConnection? = when {
        searchActive -> null
        useLargeToolbar -> twoStageConnection
        else -> enterAlwaysBehavior.nestedScrollConnection
    }
    // Single source of truth for top bar colors so all three variants (LargeTopAppBar +
    // small TopAppBar + search bar) share the same surface, and that surface matches the
    // legacy library content area exactly. library_controller.xml sets
    // android:background="?background", which is a custom Reikai theme attr (R.attr.background
    // in eu.kanade.tachiyomi). createMdc3Theme does not surface that attr as any M3
    // ColorScheme token, so we read it straight off the theme; text/icons read
    // ?actionBarTintColor (the legacy toolbar tint attr) for the same reason. Both pull from
    // the activity's resources so theme switches, follow-system dark mode, and pure-black
    // propagate via the activity's recreate() pass — same path the legacy library uses.
    val barContext = LocalContext.current
    val barContainerColor = remember(barContext) {
        Color(barContext.getResourceColor(eu.kanade.tachiyomi.R.attr.background))
    }
    val barContentColor = remember(barContext) {
        Color(barContext.getResourceColor(eu.kanade.tachiyomi.R.attr.actionBarTintColor))
    }
    val libraryTopBarColors = TopAppBarDefaults.topAppBarColors(
        containerColor = barContainerColor,
        scrolledContainerColor = barContainerColor,
        titleContentColor = barContentColor,
        navigationIconContentColor = barContentColor,
        actionIconContentColor = barContentColor,
    )
    // F12: snackbar action button text reads `?attr/colorPrimary` directly so the Undo /
    // Cancel button on every library snackbar matches the user's selected theme, instead of
    // M3's colorScheme.inversePrimary default (which is derived from primary but renders
    // differently in some Reikai themes).
    val snackbarActionColor = remember(barContext) {
        Color(barContext.getResourceColor(eu.kanade.tachiyomi.R.attr.colorPrimary))
    }

    // Per-header refresh-icon visibility mirrors LibraryHeaderHolder.notifyStatus:
    //  - hide entirely when category.id is null,
    //  - hide when the library has only one category OR showAllCategories = false
    //    (legacy `isSingleCategory = ... || !showAllCategories`),
    //  - BUT still show while that category is mid-refresh, matching legacy's `isReloading`
    //    branch where `updateButton.isVisible = true` is unconditional.
    // C10: removed the `id <= 0` short-circuit that previously hid the affordance on Default
    // (id == 0) and dynamic (id < 0) categories. Legacy's `notifyStatus` doesn't gate by id
    // sign — only by single-category mode + reloading state — and Phase 6 wired the refresh
    // dispatch to handle both id ranges.
    val isSingleCategoryGate = library.size <= 1 || !showAllCategories
    val showHeaderRefreshIcon: (Category) -> Boolean = remember(library.size, showAllCategories, inQueueCategoryIds) {
        gate@{ category ->
            val id = category.id ?: return@gate false
            if (isSingleCategoryGate && id !in inQueueCategoryIds) return@gate false
            true
        }
    }

    Scaffold(
        modifier = if (activeNestedScroll != null) {
            modifier.nestedScroll(activeNestedScroll)
        } else {
            modifier
        },
        snackbarHost = {
            // Reserve space above the hopper when it is visible so snackbars don't slide under
            // it. Mirrors legacy `anchorView = binding.categoryHopperFrame` floating behavior.
            // Hopper-hidden states (hideHopper pref or autohide engaged) drop the reserve to 0.
            // F12: custom snackbar slot so the Undo / Cancel action button text pulls from the
            // user's selected `?attr/colorPrimary` (matches legacy snackbars across themes)
            // instead of M3's colorScheme.inversePrimary default.
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.padding(bottom = if (hopperVisible) 80.dp else 0.dp),
                snackbar = { data ->
                    androidx.compose.material3.Snackbar(
                        snackbarData = data,
                        actionColor = snackbarActionColor,
                    )
                },
            )
        },
        topBar = {
            if (selection.isNotEmpty()) {
                // Contextual bar preempts search / large / small. Search state can stay alive
                // underneath: once the user clears selection the search bar reappears if they
                // had toggled it on. Mirrors how the legacy ActionMode replaces the toolbar
                // without dismissing the search query.
                //
                // Action order matches the legacy library_selection.xml row order for the
                // showAsAction="never" group: download-unread, mark-read, mark-unread, share.
                // Bar: Move | Delete | Merge | Unmerge (conditional) | overflow.
                // Merge is always visible (dimmed when < 2 selected); Unmerge appears only
                // when every selected manga is in a merge group, matching the legacy
                // visibility gate. Migrate stays in the overflow.
                val actionsList = buildList {
                    add(
                        yokai.presentation.library.components.SelectionAction(
                            label = stringResource(MR.strings.move_to_categories),
                            icon = Icons.Outlined.Label,
                            onClick = onMoveToCategories,
                        ),
                    )
                    add(
                        yokai.presentation.library.components.SelectionAction(
                            label = stringResource(MR.strings.remove),
                            icon = Icons.Outlined.Delete,
                            onClick = onConfirmAndDelete,
                        ),
                    )
                    add(
                        yokai.presentation.library.components.SelectionAction(
                            label = stringResource(MR.strings.merge_selected),
                            icon = Icons.Outlined.CallMerge,
                            enabled = canMerge,
                            onClick = onMerge,
                        ),
                    )
                    if (canUnmerge) {
                        add(
                            yokai.presentation.library.components.SelectionAction(
                                label = stringResource(MR.strings.unmerge_selected),
                                icon = Icons.Outlined.CallSplit,
                                onClick = onUnmerge,
                            ),
                        )
                    }
                    if (selectionHasRemoteSources) {
                        add(
                            yokai.presentation.library.components.SelectionAction(
                                label = stringResource(MR.strings.migrate),
                                onClick = onMigrate,
                            ),
                        )
                    }
                    add(
                        yokai.presentation.library.components.SelectionAction(
                            label = stringResource(MR.strings.download_unread),
                            onClick = onDownloadUnread,
                        ),
                    )
                    add(
                        yokai.presentation.library.components.SelectionAction(
                            label = stringResource(MR.strings.mark_as_read),
                            onClick = onConfirmAndMarkRead,
                        ),
                    )
                    add(
                        yokai.presentation.library.components.SelectionAction(
                            label = stringResource(MR.strings.mark_as_unread),
                            onClick = onConfirmAndMarkUnread,
                        ),
                    )
                    add(
                        yokai.presentation.library.components.SelectionAction(
                            label = stringResource(MR.strings.share),
                            onClick = onShareSelection,
                        ),
                    )
                }
                SelectionAppBar(
                    selectionCount = selection.size,
                    onClose = onClearSelection,
                    colors = libraryTopBarColors,
                    actions = actionsList,
                )
            } else if (searchActive) {
                LibrarySearchBar(
                    query = searchQuery,
                    onQueryChange = onSearchQueryChange,
                    onClose = {
                        onSearchQueryChange("")
                        onSearchActiveChange(false)
                    },
                    colors = libraryTopBarColors,
                )
            } else if (useLargeToolbar) {
                // Wrap in an offset Box so the entire bar can slide up by hideOffsetPx after
                // the LargeTopAppBar has fully collapsed. The bar's measured height stays
                // unchanged so the Scaffold's contentPadding remains stable; the content
                // compensates by adjusting its own top padding below. We measure the wrapper
                // continuously so hideOffsetPx's lower bound tracks the bar's CURRENT visible
                // height — which is the collapsed-content height + status-bar inset added by
                // TopAppBarDefaults.windowInsets. A hardcoded 64.dp left the status-bar strip
                // visible on devices with non-trivial top insets (tablets, notch phones).
                Box(
                    modifier = Modifier
                        .offset { IntOffset(0, hideOffsetPx.roundToInt()) }
                        .onSizeChanged { size ->
                            // Only record the FINAL collapsed height: while the LargeTopAppBar
                            // is mid-collapse (heightOffset > heightOffsetLimit) the measured
                            // size is larger than the collapsed state. We're only interested
                            // in the collapsed-state height so we keep the smallest size we
                            // have seen so far.
                            val newHeight = size.height.toFloat()
                            if (collapsedBarHeightPx == 0f || newHeight < collapsedBarHeightPx) {
                                collapsedBarHeightPx = newHeight
                            }
                        },
                ) {
                    LargeTopAppBar(
                        title = { Text(stringResource(MR.strings.library)) },
                        scrollBehavior = scrollBehavior,
                        colors = libraryTopBarColors,
                        actions = {
                            LibraryToolbarActions(
                                isAnyFilterActive = isAnyFilterActive,
                                onSearch = { onSearchActiveChange(true) },
                                onOpenFilter = onOpenFilter,
                                onOpenOverflow = onOpenOverflow,
                            )
                        },
                    )
                }
            } else {
                TopAppBar(
                    title = { Text(stringResource(MR.strings.library)) },
                    scrollBehavior = scrollBehavior,
                    colors = libraryTopBarColors,
                    actions = {
                        LibraryToolbarActions(
                            isAnyFilterActive = isAnyFilterActive,
                            onSearch = { onSearchActiveChange(true) },
                            onOpenFilter = onOpenFilter,
                            onOpenOverflow = onOpenOverflow,
                        )
                    },
                )
            }
        },
    ) { contentPadding ->
        // In two-stage mode (useLargeToolbar), compensate the content's top padding by
        // hideOffsetPx so the content slides up to fill the gap left by the sliding bar. The
        // Scaffold's contentPadding does not move with the offset Box wrapper above, so
        // without this adjustment a gap would appear above the grid as the bar hides.
        val adjustedContentPadding = if (useLargeToolbar && hideOffsetPx < 0f) {
            val topPx = with(density) { contentPadding.calculateTopPadding().toPx() }
            val adjustedTop = with(density) {
                (topPx + hideOffsetPx).coerceAtLeast(0f).toDp()
            }
            PaddingValues(
                start = contentPadding.calculateStartPadding(layoutDirection),
                top = adjustedTop,
                end = contentPadding.calculateEndPadding(layoutDirection),
                bottom = contentPadding.calculateBottomPadding(),
            )
        } else {
            contentPadding
        }
        // Pull-to-refresh wraps all three lazy branches. Gate `enabled` on:
        //  - !isRunning: legacy `setSwipeRefresh` early-returns when LibraryUpdateJob.isRunning,
        //  - !pickerOpen: legacy disables swipeRefresh while the category picker is up
        //    (LibraryController.kt:1351 `binding.swipeRefresh.isEnabled = !show`).
        // Indicator colors match the legacy `SwipeRefreshLayout.setStyle()` extension
        // (ViewExtensions.kt:336-339): arrow = ?attr/actionBarTintColor, container =
        // ?attr/colorPrimaryVariant. createMdc3Theme doesn't surface those legacy custom attrs
        // as M3 ColorScheme tokens, so we read them straight off the activity theme via the
        // same `Context.getResourceColor(...)` pattern Phase 3 uses for the top bar background.
        val pullToRefreshState = rememberPullToRefreshState()
        val ptrEnabled = !isRunning && !pickerOpen
        val ptrArrowColor = remember(barContext) {
            Color(barContext.getResourceColor(eu.kanade.tachiyomi.R.attr.actionBarTintColor))
        }
        val ptrContainerColor = remember(barContext) {
            Color(barContext.getResourceColor(eu.kanade.tachiyomi.R.attr.colorPrimaryVariant))
        }
        PullToRefreshBox(
            isRefreshing = isRunning,
            onRefresh = { if (ptrEnabled) onPullToRefresh() },
            state = pullToRefreshState,
            modifier = Modifier
                .padding(adjustedContentPadding)
                // F10: tap anywhere in the content area dismisses the undo snackbar after the
                // 1-second grace (matches MainActivity.dispatchTouchEvent at MainActivity.kt:1390
                // -1418). Initial-pass + requireUnconsumed=false observes without consuming, so
                // the underlying lazy grid / hopper / chip still handle the same touch normally.
                // Taps that land on the snackbar itself are intercepted by Scaffold's z-ordered
                // snackbarHost slot before this pointerInput sees them.
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        val data = snackbarHostState.currentSnackbarData
                        val shownAt = snackbarShownAtMs
                        if (data != null && shownAt != null &&
                            System.currentTimeMillis() - shownAt > 1000L
                        ) {
                            data.dismiss()
                        }
                    }
                },
            indicator = {
                PullToRefreshDefaults.Indicator(
                    modifier = Modifier.align(Alignment.TopCenter),
                    isRefreshing = isRunning,
                    state = pullToRefreshState,
                    containerColor = ptrContainerColor,
                    color = ptrArrowColor,
                )
            },
        ) {
        // Empty-state gate, mirroring legacy LibraryController.onNextLibraryUpdate:
        //   - actually empty library → broken-heart icon + "Library is empty" + Getting started
        //     guide button (opens the tachiyomi docs URL via the activity Context).
        //   - empty due to active filter or non-empty search query → broken-heart + "No matches
        //     for filters", no CTA.
        // Search-narrowing is treated as a filter for the purposes of this gate (legacy
        // doesn't, since search runs through a separate path, but we render search hits inline
        // and the most useful message in the empty-search-results case is "no matches").
        val emptyLibrary = library.values.all { it.isEmpty() }
        val isNarrowing = isAnyFilterActive || searchQuery.isNotEmpty()
        // EmptyScreen's tablet branch lays the icon and message out in a row, which on a Fold-6
        // sized tablet leaves the icon floating left of a paragraph of text. Legacy renders the
        // empty library as a centered column on every form factor (icon above text above CTA);
        // force that layout here regardless of screen width so the empty state reads the same.
        val isTabletMode = false
        // Single-category-mode horizontal swipe to switch active category. Faithful port of
        // legacy LibraryCategoryGestureDetector (refs/yokai + Reikai, files identical):
        //
        //  1. Resistance curve, not 1:1 finger-follow. translationX =
        //     abs(distance/50)^1.7 * sign(distance). At a 150px drag the grid only moves ~6.5px;
        //     at 600px it moves ~67px. Gives the legacy "rubber band" feel rather than dragging
        //     a sheet of paper. Earlier polish iteration shipped 1:1 follow and users perceived
        //     it as wrong vs legacy.
        //  2. Raw-pixel thresholds (not dp). MotionEvent distances in legacy are raw pixels;
        //     thresholds 150 (= SWIPE_THRESHOLD * 3) and 100 (= SWIPE_VELOCITY_THRESHOLD) are
        //     raw px, density-independent. Matching this preserves the exact gesture cadence
        //     across device densities.
        //  3. Direction-change cancel. Legacy commit 32222476e0 added
        //     `sign(diffX) == sign(velocityX)` so a swipe that flicks back at the end (drag
        //     right, then accelerate left) does not trigger a switch.
        //  4. Hopper touch-start exclusion (legacy onDown at lines 25-38). Achieved here by
        //     mounting Modifier.draggable on the INNER offset Box rather than the outer Box:
        //     the hopper card and ActiveCategoryChip are siblings of the inner Box and absorb
        //     pointer events first via Compose z-order, so swipes starting on them never reach
        //     the draggable. The top app bar lives in the Scaffold's topBar slot, already
        //     outside this content area.
        //
        // Deferred (matches Phase 3 hopper-drag deferral): RTL flip
        // (legacy `(diffX >= 0).xor(isLTR)`) and Compose's drag-slop window vs legacy's 50px
        // vertical-abort. Compose's Orientation.Horizontal slop ownership handles the
        // vertical-abort intent at a different threshold; revisit if anyone reports it.
        val resistancePivotPx = 50f
        val resistancePower = 1.7f
        val swipeDistanceThresholdPx = 150f
        val swipeVelocityThresholdPx = 100f
        var rawDragDistancePx by remember { mutableFloatStateOf(0f) }
        val swipeOffset = remember { Animatable(0f) }
        val swipeDraggableState = rememberDraggableState { delta ->
            rawDragDistancePx += delta
            val raw = rawDragDistancePx
            val displayed = if (raw == 0f) {
                0f
            } else {
                (abs(raw) / resistancePivotPx).pow(resistancePower) * sign(raw)
            }
            // Animatable.snapTo is suspend; launch on the existing coroutineScope. Per-delta
            // launches serialize via the draggable's internal mutex, so order is preserved.
            coroutineScope.launch { swipeOffset.snapTo(displayed) }
        }
        val swipeStateForCallback = androidx.compose.runtime.rememberUpdatedState(
            Triple(activeCategory, allCategories, onActiveCategoryChange),
        )
        // Safety-net snap-back. The onDragStopped lambda also animates swipeOffset back to 0,
        // but on a successful swipe the dispatch triggers a pref write → screen-model state
        // emission → recomposition cycle that can interrupt the in-flight Animatable (covers
        // observed landing at the post-drag offset after the swap). Keying a LaunchedEffect on
        // activeCategory guarantees that whenever the visible category changes (whether from
        // swipe, hopper picker, or hopper nav), the offset resets to 0 in a stable scope that
        // recomposition can't tear down.
        LaunchedEffect(activeCategory) {
            if (swipeOffset.value != 0f) {
                swipeOffset.animateTo(0f, tween(durationMillis = 150))
            }
        }
        Box(
            modifier = Modifier
                .onSizeChanged { parentWidthPx = it.width },
        ) {
          Box(
            // Drag-follow with resistance curve (see comment block above). Reading
            // swipeOffset.value inside Modifier.offset registers a state read in the
            // deferred-offset lambda so the translate updates per frame without
            // recomposing the content tree. Modifier.draggable lives on THIS inner Box
            // (not the outer) so the chip and hopper, which are siblings rendered above
            // this Box, intercept touches before the draggable sees them.
            //
            // fillMaxSize: without it the Box wraps the lazy grid's content size, so a
            // category with only a few items leaves the empty space below outside the
            // swipe hit area (legacy works from anywhere on the library; on-device
            // feedback flagged the "must drag on cover" feel). With fillMaxSize the gesture
            // surface spans the full available height; the chip and hopper sit above this
            // Box via z-order so their touch handlers still win first.
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(swipeOffset.value.roundToInt(), 0) }
                .draggable(
                    state = swipeDraggableState,
                    orientation = Orientation.Horizontal,
                    enabled = singleCategoryMode,
                    onDragStarted = {
                        rawDragDistancePx = 0f
                        // Cancel any in-flight snap-back so a quick follow-up swipe starts
                        // from exactly where the finger lands.
                        swipeOffset.stop()
                    },
                    onDragStopped = { velocity ->
                        val totalDistance = rawDragDistancePx
                        rawDragDistancePx = 0f
                        // Direction-change cancel: legacy
                        // `sign(diffX) == sign(velocityX)` at LibraryCategoryGestureDetector.kt:87.
                        // sign(0) is 0; the threshold check below rules out zero-distance cases.
                        val sameDirection = sign(totalDistance) == sign(velocity)
                        if (sameDirection &&
                            abs(totalDistance) > swipeDistanceThresholdPx &&
                            abs(velocity) > swipeVelocityThresholdPx
                        ) {
                            val (active, cats, dispatch) = swipeStateForCallback.value
                            val activeId = active?.id
                            if (activeId != null) {
                                val currentIdx = cats.indexOfFirst { it.id == activeId }
                                if (currentIdx >= 0) {
                                    // Swipe-left (totalDistance < 0) → next category, matches
                                    // legacy `jumpToNextCategory(next=true)` under LTR.
                                    val targetIdx = if (totalDistance < 0) currentIdx + 1 else currentIdx - 1
                                    cats.getOrNull(targetIdx)?.let(dispatch)
                                }
                            }
                        }
                        // Always animate back to 0, whether or not the switch fired. Matches
                        // LibraryCategoryGestureDetector.kt:91-94.
                        swipeOffset.animateTo(0f, tween(durationMillis = 150))
                    },
                ),
          ) {
            when {
                emptyLibrary && isNarrowing -> EmptyScreen(
                    image = Icons.Filled.HeartBroken,
                    message = stringResource(MR.strings.no_matches_for_filters),
                    isTablet = isTabletMode,
                )
                emptyLibrary -> {
                    val emptyContext = LocalContext.current
                    EmptyScreen(
                        image = Icons.Filled.HeartBroken,
                        message = stringResource(MR.strings.library_is_empty_add_from_browse),
                        isTablet = isTabletMode,
                        actions = listOf(
                            // Matches legacy LibraryController.onNextLibraryUpdate (line 1154).
                            EmptyView.Action(MR.strings.getting_started_guide) {
                                emptyContext.openInBrowser(
                                    "https://tachiyomi.org/docs/guides/getting-started#_2-adding-sources",
                                )
                            },
                        ),
                    )
                }
                isList -> {
                    LazyLibraryList(
                        state = listState,
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        library.forEach { (category, mangaItems) ->
                            // Match legacy: in single-category mode the header is rendered with
                            // blank text + hidden chevron + hidden refresh icon (LibraryHeaderHolder
                            // at refs/yokai/.../LibraryHeaderHolder.kt:181-185, :384-405), so users
                            // see nothing above the grid. Compose-proper equivalent: skip the item
                            // entirely. Reclaims the vertical space for covers.
                            if (!singleCategoryMode) {
                                item(
                                    // Sort char + ascending bit are folded into the key so the
                                    // header slot is rebuilt when the user changes per-category
                                    // sort. Without this, LazyColumn reuses the slot's stored
                                    // composition (the captured Category reference is the same
                                    // after an in-place mangaSort mutation), and the sort label
                                    // stays on the previous mode even though the library re-sorts.
                                    key = "header:${category.id ?: 0}:${category.sortingMode()?.categoryValue ?: '-'}:${category.isAscending()}:${category.isHidden}",
                                    contentType = "library_category_header",
                                ) {
                                    val allSelectedInCategory = mangaItems.isNotEmpty() &&
                                        mangaItems.all { it.libraryManga.manga.id in selection }
                                    LibraryCategoryHeader(
                                        name = category.name,
                                        // animateItem smooths header reflow when collapse /
                                        // sort / refresh state changes shift headers up/down.
                                        modifier = Modifier.animateItem(),
                                        itemCount = displayedHeaderCounts[category.id ?: 0] ?: 0,
                                        showItemCount = showCategoryItemCounts,
                                        isCollapsed = if (category.isDynamic) category.isHidden else (category.id != null && category.id in collapsedIds),
                                        collapsible = collapsibleHeaders,
                                        onClick = { onToggleCategoryCollapse(category) },
                                        isRefreshing = category.id != null && category.id in inQueueCategoryIds,
                                        onRefreshClick = if (showHeaderRefreshIcon(category)) {
                                            { onRefreshCategory(category) }
                                        } else {
                                            null
                                        },
                                        selectionActive = selection.isNotEmpty(),
                                        allSelected = allSelectedInCategory,
                                        onToggleCategorySelection = {
                                            category.id?.let { onToggleCategorySelection(it) }
                                        },
                                        sortMode = category.sortingMode() ?: LibrarySort.DragAndDrop,
                                        sortAscending = category.isAscending(),
                                        sortIsDynamic = category.isDynamic,
                                        onSortClick = { category.id?.let { sortingCategoryId = it } },
                                    )
                                }
                            }
                            items(
                                items = mangaItems,
                                // Composite key so the same manga can legitimately appear in
                                // multiple dynamic categories (BY_TAG / BY_AUTHOR) without the
                                // LazyColumn complaining about duplicate keys. Per-category
                                // disambiguation prefix: "<categoryId>:<mangaId>".
                                key = { "${category.id ?: 0}:${it.libraryManga.manga.id ?: 0L}" },
                                contentType = { "library_list_item" },
                            ) { item ->
                                MangaLibraryListItem(
                                    item = item,
                                    selection = selection,
                                    showDownloadBadge = showDownloadBadge,
                                    showLanguageBadge = showLanguageBadge,
                                    unreadBadgeType = unreadBadgeType,
                                    onMangaClick = onMangaClick,
                                    onToggleSelection = onToggleSelection,
                                )
                            }
                        }
                    }
                }
                isStaggered -> {
                    LazyLibraryStaggeredGrid(
                        columns = columns,
                        state = staggeredGridState,
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        library.forEach { (category, mangaItems) ->
                            // Header suppressed in single-category mode; see list-branch note above.
                            if (!singleCategoryMode) {
                                item(
                                    // Sort char + ascending bit are folded into the key so the
                                    // header slot is rebuilt when the user changes per-category
                                    // sort (see list-branch note above for the rationale).
                                    key = "header:${category.id ?: 0}:${category.sortingMode()?.categoryValue ?: '-'}:${category.isAscending()}:${category.isHidden}",
                                    span = StaggeredGridItemSpan.FullLine,
                                    contentType = "library_category_header",
                                ) {
                                    val allSelectedInCategory = mangaItems.isNotEmpty() &&
                                        mangaItems.all { it.libraryManga.manga.id in selection }
                                    LibraryCategoryHeader(
                                        name = category.name,
                                        // animateItem smooths header reflow when collapse /
                                        // sort / refresh state changes shift headers up/down.
                                        modifier = Modifier.animateItem(),
                                        itemCount = displayedHeaderCounts[category.id ?: 0] ?: 0,
                                        showItemCount = showCategoryItemCounts,
                                        isCollapsed = if (category.isDynamic) category.isHidden else (category.id != null && category.id in collapsedIds),
                                        collapsible = collapsibleHeaders,
                                        onClick = { onToggleCategoryCollapse(category) },
                                        isRefreshing = category.id != null && category.id in inQueueCategoryIds,
                                        onRefreshClick = if (showHeaderRefreshIcon(category)) {
                                            { onRefreshCategory(category) }
                                        } else {
                                            null
                                        },
                                        selectionActive = selection.isNotEmpty(),
                                        allSelected = allSelectedInCategory,
                                        onToggleCategorySelection = {
                                            category.id?.let { onToggleCategorySelection(it) }
                                        },
                                        sortMode = category.sortingMode() ?: LibrarySort.DragAndDrop,
                                        sortAscending = category.isAscending(),
                                        sortIsDynamic = category.isDynamic,
                                        onSortClick = { category.id?.let { sortingCategoryId = it } },
                                    )
                                }
                            }
                            items(
                                items = mangaItems,
                                // Composite key so the same manga can legitimately appear in
                                // multiple dynamic categories (BY_TAG / BY_AUTHOR) without the
                                // LazyColumn complaining about duplicate keys. Per-category
                                // disambiguation prefix: "<categoryId>:<mangaId>".
                                key = { "${category.id ?: 0}:${it.libraryManga.manga.id ?: 0L}" },
                                contentType = { "library_grid_item" },
                            ) { item ->
                                // Staggered + uniformGrid=false: drop the cover aspect-ratio so
                                // each cell sizes to its image's intrinsic ratio.
                                val mangaId = item.libraryManga.manga.id
                                MangaLibraryGridCell(
                                    item = item,
                                    libraryLayout = libraryLayout,
                                    outlineOnCovers = outlineOnCovers,
                                    showDownloadBadge = showDownloadBadge,
                                    showLanguageBadge = showLanguageBadge,
                                    unreadBadgeType = unreadBadgeType,
                                    hideStartReadingButton = hideStartReadingButton,
                                    isSelected = mangaId != null && mangaId in selection,
                                    // animateItem on the grid cell makes merge/unmerge/sort
                                    // changes slide rather than pop-jump: removed siblings fade
                                    // out, surviving cards translate into their new positions.
                                    modifier = Modifier.animateItem(),
                                    selectionActive = selection.isNotEmpty(),
                                    onMangaClick = onMangaClick,
                                    onMangaLongClick = { m -> m.id?.let(onToggleSelection) },
                                    onContinueReading = onContinueReading,
                                    coverAspectRatio = null,
                                )
                            }
                        }
                    }
                }
                else -> {
                    LazyLibraryGrid(
                        columns = columns,
                        state = gridState,
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        library.forEach { (category, mangaItems) ->
                            // Header suppressed in single-category mode; see list-branch note above.
                            if (!singleCategoryMode) {
                                item(
                                    // Sort char + ascending bit are folded into the key so the
                                    // header slot is rebuilt when the user changes per-category
                                    // sort (see list-branch note above for the rationale).
                                    key = "header:${category.id ?: 0}:${category.sortingMode()?.categoryValue ?: '-'}:${category.isAscending()}:${category.isHidden}",
                                    span = { GridItemSpan(maxLineSpan) },
                                    contentType = "library_category_header",
                                ) {
                                    val allSelectedInCategory = mangaItems.isNotEmpty() &&
                                        mangaItems.all { it.libraryManga.manga.id in selection }
                                    LibraryCategoryHeader(
                                        name = category.name,
                                        // animateItem smooths header reflow when collapse /
                                        // sort / refresh state changes shift headers up/down.
                                        modifier = Modifier.animateItem(),
                                        itemCount = displayedHeaderCounts[category.id ?: 0] ?: 0,
                                        showItemCount = showCategoryItemCounts,
                                        isCollapsed = if (category.isDynamic) category.isHidden else (category.id != null && category.id in collapsedIds),
                                        collapsible = collapsibleHeaders,
                                        onClick = { onToggleCategoryCollapse(category) },
                                        isRefreshing = category.id != null && category.id in inQueueCategoryIds,
                                        onRefreshClick = if (showHeaderRefreshIcon(category)) {
                                            { onRefreshCategory(category) }
                                        } else {
                                            null
                                        },
                                        selectionActive = selection.isNotEmpty(),
                                        allSelected = allSelectedInCategory,
                                        onToggleCategorySelection = {
                                            category.id?.let { onToggleCategorySelection(it) }
                                        },
                                        sortMode = category.sortingMode() ?: LibrarySort.DragAndDrop,
                                        sortAscending = category.isAscending(),
                                        sortIsDynamic = category.isDynamic,
                                        onSortClick = { category.id?.let { sortingCategoryId = it } },
                                    )
                                }
                            }
                            items(
                                items = mangaItems,
                                // Composite key so the same manga can legitimately appear in
                                // multiple dynamic categories (BY_TAG / BY_AUTHOR) without the
                                // LazyColumn complaining about duplicate keys. Per-category
                                // disambiguation prefix: "<categoryId>:<mangaId>".
                                key = { "${category.id ?: 0}:${it.libraryManga.manga.id ?: 0L}" },
                                contentType = { "library_grid_item" },
                            ) { item ->
                                // Faithful port of AutofitRecyclerView.useStaggered + the
                                // adjustViewBounds path in LibraryGridHolder: when uniform is
                                // on, force every cell to BOOK; when uniform is off, drop the
                                // outer ratio so the cell wraps to the cover's intrinsic size.
                                // The actual stable-height fallback lives inside `MangaCover`,
                                // which reads the legacy `MangaCoverMetadata` cache and falls
                                // back to BOOK before the first paint, so the cell never
                                // collapses to 0 height during async image load.
                                val mangaId = item.libraryManga.manga.id
                                MangaLibraryGridCell(
                                    item = item,
                                    libraryLayout = libraryLayout,
                                    outlineOnCovers = outlineOnCovers,
                                    showDownloadBadge = showDownloadBadge,
                                    showLanguageBadge = showLanguageBadge,
                                    unreadBadgeType = unreadBadgeType,
                                    hideStartReadingButton = hideStartReadingButton,
                                    isSelected = mangaId != null && mangaId in selection,
                                    // See above (comfortable grid path); same animateItem
                                    // rationale for the staggered grid's LazyStaggeredGridItemScope.
                                    modifier = Modifier.animateItem(),
                                    selectionActive = selection.isNotEmpty(),
                                    onMangaClick = onMangaClick,
                                    onMangaLongClick = { m -> m.id?.let(onToggleSelection) },
                                    onContinueReading = onContinueReading,
                                    coverAspectRatio = if (uniformGrid) MangaCoverRatio.BOOK else null,
                                )
                            }
                        }
                    }
                }
            }
          }
            if (showChip) {
                ActiveCategoryChip(
                    name = activeCategory!!.name,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 6.dp),
                )
            }
            // fillMaxWidth on AnimatedVisibility gives the hopper's graphics-layer offset
            // somewhere to land without clipping (previous bug: at gravity = 0 the 20.dp
            // edge-padding offset put the right rounded corner just past the wrapper's
            // intrinsic bounds, and the enter/exit pipeline clipped it during the autohide
            // transition). The CategoryHopper itself then has wrapContentWidth so the Surface
            // sizes to its 3 button row instead of filling the now-wide constraints — without
            // that, the hopper stretched across the screen since the Surface defaults to
            // filling the available width.
            AnimatedVisibility(
                visible = hopperVisible,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                CategoryHopper(
                    onUpClick = onHopperUp,
                    onCenterClick = { pickerOpen = true },
                    onDownClick = onHopperDown,
                    // Long-press up = jump to the first item (matches legacy
                    // hopper.upCategory.setOnLongClickListener). In single-category mode, jump
                    // to the first user category instead, since there is no scroll axis to
                    // navigate.
                    onUpLongClick = if (singleCategoryMode) {
                        { allCategories.firstOrNull()?.let(onActiveCategoryChange) }
                    } else {
                        { coroutineScope.launch { scrollTo(0) } }
                    },
                    // Long-press down = jump to the last item. categoryOffsets tracks the
                    // header index + items.size for each category, so the total item count is
                    // the last offset + 1 + items.size of the trailing category. In single
                    // mode, jump to the last user category instead.
                    onDownLongClick = if (singleCategoryMode) {
                        { allCategories.lastOrNull()?.let(onActiveCategoryChange) }
                    } else {
                        {
                            val lastEntry = library.entries.lastOrNull() ?: return@CategoryHopper
                            val lastOffset = (categoryOffsets.lastOrNull()?.first ?: 0) + lastEntry.value.size
                            coroutineScope.launch { scrollTo(lastOffset) }
                        }
                    },
                    // Long-press center dispatches by user pref. Indices match
                    // CategoriesTab.hopperLongPressEntries and legacy LibraryController.kt:779
                    // dispatch order.
                    onCenterLongClick = {
                        when (hopperLongPressAction) {
                            0 -> onSearchActiveChange(true)
                            1 -> onExpandCollapseAllCategories()
                            // Display options sheet tab index 1 = Display tab.
                            2 -> onOpenSheetAt(1)
                            // Opens the standalone Group library by dialog, matching legacy
                            // showGroupOptions() rather than the full Categories tab.
                            3 -> onOpenGroupByPicker()
                            // In-category random matches legacy `openRandomManga(false)`.
                            4 -> onOpenRandomInCategory(activeCategory)
                            // Global random matches legacy `openRandomManga(true)`.
                            5 -> onOpenRandomSeries()
                        }
                    },
                    modifier = Modifier
                        .wrapContentWidth(align = Alignment.Start)
                        .offset { IntOffset(hopperX.value.roundToInt(), 0) }
                        .onSizeChanged { hopperWidthPx = it.width }
                        .draggable(
                            orientation = Orientation.Horizontal,
                            state = draggableState,
                            onDragStopped = { velocity ->
                                val rest = xForGravity(hopperGravity)
                                val distance = hopperX.value - rest
                                val swipingRight = distance > 0
                                if (abs(distance) > distanceThresholdPx && abs(velocity) > velocityThresholdPx) {
                                    val newGravity = when (hopperGravity) {
                                        0 -> if (swipingRight) 1 else 0
                                        2 -> if (swipingRight) 2 else 1
                                        else -> if (swipingRight) 2 else 0
                                    }
                                    if (newGravity != hopperGravity) {
                                        // LaunchedEffect on targetX will animate the slide; no
                                        // manual animateTo needed here.
                                        onHopperGravityChange(newGravity)
                                    } else {
                                        hopperX.animateTo(rest, animationSpec = tween(200))
                                    }
                                } else {
                                    hopperX.animateTo(rest, animationSpec = tween(200))
                                }
                            },
                        )
                        // Vertical fling on the hopper opens the Display options sheet, matching
                        // legacy LibraryGestureDetector.onFling at refs/yokai/.../LibraryGestureDetector.kt:53-58.
                        // Same raw-pixel thresholds (50 px distance, 100 px/s velocity). Down-fling
                        // is a no-op: legacy hides a peeked filter sheet, but Compose's Display
                        // options is a full ModalBottomSheet, not peeked, so there's nothing to
                        // hide via this gesture. Coexists with the horizontal gravity-reposition
                        // draggable above via Compose orientation-lock: only one wins per gesture
                        // based on the initial motion direction past touch slop.
                        .draggable(
                            orientation = Orientation.Vertical,
                            state = hopperVerticalDraggableState,
                            onDragStarted = { hopperVerticalDragPx = 0f },
                            onDragStopped = { velocity ->
                                val totalDistance = hopperVerticalDragPx
                                hopperVerticalDragPx = 0f
                                if (totalDistance < 0f &&
                                    abs(totalDistance) > 50f &&
                                    abs(velocity) > 100f
                                ) {
                                    // Sheet tab 0 = Filter, matches legacy controller.showSheet()
                                    // which opens the filter bottom sheet.
                                    onOpenSheetAt(0)
                                }
                            },
                        ),
                )
            }
        }
        }
        if (pickerOpen) {
            CategoryPickerSheet(
                categories = allCategories,
                itemCounts = categoryItemCounts,
                showItemCounts = showCategoryItemCounts,
                activeCategoryId = activeCategory?.id,
                onSelect = { category ->
                    if (singleCategoryMode) {
                        // Re-render the library with a different active category instead of
                        // scrolling (no scroll axis exists when only one category is rendered).
                        onActiveCategoryChange(category)
                    } else {
                        val target = categoryOffsets.firstOrNull { it.second.id == category.id }?.first
                        target?.let { idx ->
                            // Instant jump matches legacy scrollToHeader (uses
                            // scrollToPositionWithOffset). animateScrollToItem jitters when items
                            // between source and target need to be measured.
                            coroutineScope.launch { scrollTo(idx) }
                        }
                    }
                    pickerOpen = false
                },
                onDismiss = { pickerOpen = false },
            )
        }
        if (sheetOpen) {
            LibraryDisplayOptionsSheet(
                initialTab = sheetTab,
                onDismiss = onDismissSheet,
                detectedMangaTypes = detectedMangaTypes,
                loggedTrackerNames = loggedTrackerNames,
            )
        }
        LibraryOverflowMenu(
            expanded = overflowOpen,
            onDismiss = onDismissOverflow,
        )
        sortingCategoryId?.let { catId ->
            // Look up the category from the latest `library` map rather than capturing the
            // Category instance at tap-time. The library map is re-passed on every state
            // emission, so `target` here is always the freshest snapshot of the category, even
            // if Compose's LazyColumn item lambda captured an older reference.
            val target = library.keys.firstOrNull { it.id == catId }
            if (target == null) {
                sortingCategoryId = null
            } else {
                CategorySortSheet(
                    currentMode = target.sortingMode(),
                    currentAscending = target.isAscending(),
                    isDynamic = target.isDynamic,
                    onModeSelected = { mode ->
                        onSortChange(target, mode)
                        sortingCategoryId = null
                    },
                    onDismiss = { sortingCategoryId = null },
                )
            }
        }
    }
}

/**
 * Toolbar action cluster shared by both the small [TopAppBar] and [LargeTopAppBar] branches.
 * Search, filter (with active-state dot encoded in the icon's contentDescription), overflow.
 */
@Composable
private fun LibraryToolbarActions(
    isAnyFilterActive: Boolean,
    onSearch: () -> Unit,
    onOpenFilter: () -> Unit,
    onOpenOverflow: () -> Unit,
) {
    IconButton(onClick = onSearch) {
        Icon(
            imageVector = Icons.Outlined.Search,
            contentDescription = stringResource(MR.strings.search),
        )
    }
    Box {
        IconButton(onClick = onOpenFilter) {
            Icon(
                imageVector = Icons.Outlined.Tune,
                contentDescription = stringResource(
                    if (isAnyFilterActive) MR.strings.filters_active
                    else MR.strings.filter,
                ),
            )
        }
        if (isAnyFilterActive) {
            Surface(
                color = MaterialTheme.colorScheme.primary,
                shape = CircleShape,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 10.dp, end = 10.dp)
                    .size(8.dp),
            ) {}
        }
    }
    IconButton(onClick = onOpenOverflow) {
        Icon(
            imageVector = Icons.Outlined.MoreVert,
            contentDescription = stringResource(MR.strings.more),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibrarySearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    colors: androidx.compose.material3.TopAppBarColors,
) {
    val focusRequester = remember { FocusRequester() }
    // Auto-focus on first composition so the keyboard appears immediately when the user taps
    // the search icon. LaunchedEffect with Unit key fires once per entry into the searchActive
    // branch.
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    TopAppBar(
        colors = colors,
        navigationIcon = {
            // Visual lead, not interactive; tapping the magnifier here would be redundant since
            // the bar is already expanded. Use the X to close.
            Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = null,
                modifier = Modifier.padding(start = 16.dp),
            )
        },
        title = {
            TextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text(stringResource(MR.strings.library_search_hint)) },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )
        },
        actions = {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = stringResource(MR.strings.close),
                )
            }
        },
    )
}

