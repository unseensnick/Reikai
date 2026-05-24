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
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import eu.kanade.tachiyomi.ui.library.LibraryItem.Companion.LAYOUT_COMFORTABLE_GRID
import eu.kanade.tachiyomi.ui.library.LibraryItem.Companion.LAYOUT_COMPACT_GRID
import eu.kanade.tachiyomi.ui.library.LibraryItem.Companion.LAYOUT_COVER_ONLY_GRID
import eu.kanade.tachiyomi.ui.library.LibraryItem.Companion.LAYOUT_LIST
import eu.kanade.tachiyomi.ui.library.models.LibraryItem
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import yokai.domain.manga.models.cover
import yokai.i18n.MR
import yokai.presentation.library.components.ActiveCategoryChip
import yokai.presentation.library.components.CategoryHopper
import yokai.presentation.library.components.CategoryPickerSheet
import yokai.presentation.library.components.LazyLibraryGrid
import yokai.presentation.library.components.LazyLibraryList
import yokai.presentation.library.components.LazyLibraryStaggeredGrid
import yokai.presentation.library.components.LibraryCategoryHeader
import yokai.presentation.library.components.LibraryOverflowMenu
import yokai.presentation.library.settings.LibraryDisplayOptionsSheet
import yokai.presentation.manga.components.Badge
import yokai.presentation.manga.components.BadgeSegments
import yokai.presentation.manga.components.MangaComfortableGridItem
import yokai.presentation.manga.components.MangaCompactGridItem
import yokai.presentation.manga.components.MangaCoverRatio
import yokai.presentation.manga.components.MangaListItem

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
    sheetOpen: Boolean,
    sheetTab: Int,
    overflowOpen: Boolean,
    detectedMangaTypes: Set<Int>,
    loggedTrackerNames: List<String>,
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
    /** Navigate to a random series from the library. No-op when the library is empty. */
    onOpenRandomSeries: () -> Unit,
    /** Invoked when the user taps the continue-reading button on a cover with unread chapters. */
    onContinueReading: (Manga) -> Unit,
    onOpenFilter: () -> Unit,
    onOpenOverflow: () -> Unit,
    onDismissSheet: () -> Unit,
    onDismissOverflow: () -> Unit,
    onSheetTabChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // System back closes search before exiting the library.
    BackHandler(enabled = searchActive) {
        onSearchQueryChange("")
        onSearchActiveChange(false)
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
    // (1 + items.size) for all preceding categories regardless of container.
    val categoryOffsets = remember(library) {
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

    val activeCategory by remember(categoryOffsets) {
        derivedStateOf {
            categoryOffsets.lastOrNull { it.first <= firstVisibleItemIndex }?.second
        }
    }

    val showChip = showCategoryInTitle &&
        library.size > 1 &&
        activeCategory != null &&
        !searchActive

    // Autohide on user drag only, not on any scroll. The hopper's own up/down/picker actions
    // trigger programmatic scrollToItem, which do not fire drag interactions; using
    // isScrollInProgress instead would hide the hopper on every tap and break rapid rhythm.
    val isUserDragging by scrollInteractionSource.collectIsDraggedAsState()
    val hopperVisible by remember(hideHopper, autohideHopper, library, searchActive) {
        derivedStateOf {
            val base = !searchActive && !hideHopper && library.size > 1
            if (autohideHopper) base && !isUserDragging else base
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
    val onHopperUp = {
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
    val onHopperDown = {
        val activeIdx = categoryOffsets.indexOfLast { it.first <= firstVisibleItemIndex }
        val nextHeader = categoryOffsets.getOrNull(activeIdx + 1)?.first
        nextHeader?.let { idx ->
            coroutineScope.launch { scrollTo(idx) }
        }
        Unit
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

    // Large-toolbar mode (preferences.useLargeToolbar()) renders a Material3 LargeTopAppBar
    // that collapses on scroll. The scrollBehavior bridges the bar with the lazy grid/list
    // via nestedScroll. Search swaps the whole bar for the small search overlay regardless of
    // the user's toolbar preference; collapsing a search bar mid-query would be jarring.
    val largeBarBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scrollBehavior: TopAppBarScrollBehavior? = if (useLargeToolbar && !searchActive) {
        largeBarBehavior
    } else {
        null
    }
    Scaffold(
        modifier = if (scrollBehavior != null) {
            modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
        } else {
            modifier
        },
        topBar = {
            if (searchActive) {
                LibrarySearchBar(
                    query = searchQuery,
                    onQueryChange = onSearchQueryChange,
                    onClose = {
                        onSearchQueryChange("")
                        onSearchActiveChange(false)
                    },
                )
            } else if (useLargeToolbar) {
                LargeTopAppBar(
                    title = { Text(stringResource(MR.strings.library)) },
                    scrollBehavior = scrollBehavior,
                    actions = {
                        LibraryToolbarActions(
                            isAnyFilterActive = isAnyFilterActive,
                            onSearch = { onSearchActiveChange(true) },
                            onOpenFilter = onOpenFilter,
                            onOpenOverflow = onOpenOverflow,
                        )
                    },
                )
            } else {
                TopAppBar(
                    title = { Text(stringResource(MR.strings.library)) },
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
        Box(
            modifier = Modifier
                .padding(contentPadding)
                .onSizeChanged { parentWidthPx = it.width },
        ) {
            when {
                isList -> {
                    LazyLibraryList(
                        state = listState,
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        library.forEach { (category, mangaItems) ->
                            item(
                                key = "header:${category.id ?: 0}",
                                contentType = "library_category_header",
                            ) {
                                LibraryCategoryHeader(
                                    name = category.name,
                                    itemCount = displayedHeaderCounts[category.id ?: 0] ?: 0,
                                    showItemCount = showCategoryItemCounts,
                                    isCollapsed = category.id != null && category.id in collapsedIds,
                                    collapsible = collapsibleHeaders,
                                    onClick = { onToggleCategoryCollapse(category) },
                                )
                            }
                            items(
                                items = mangaItems,
                                key = { it.libraryManga.manga.id ?: 0L },
                                contentType = { "library_list_item" },
                            ) { item ->
                                val manga = item.libraryManga.manga
                                val coverData = remember(manga.id) { manga.cover() }
                                val title = remember(manga.id) { manga.title }
                                val subtitle = remember(manga.id) {
                                    val author = manga.author?.trim().orEmpty()
                                    val artist = manga.artist?.trim().orEmpty()
                                    when {
                                        author.isEmpty() -> artist
                                        artist.isEmpty() || artist == author -> author
                                        author.contains(artist, true) -> author
                                        else -> "$author, $artist"
                                    }
                                }
                                // Same badge inputs as the grid; legacy list rows render the
                                // unread / download chips on the trailing side, mirroring
                                // manga_list_item.xml. The Badge component itself is reused so
                                // visuals match the grid badges.
                                val unreadCount = if (unreadBadgeType > 0) item.libraryManga.unread else 0
                                val unreadDot = unreadBadgeType == 2
                                val downloadCount = if (showDownloadBadge) {
                                    item.downloadCount.toInt().coerceAtLeast(0)
                                } else {
                                    0
                                }
                                val lang = if (showLanguageBadge) item.language.takeIf { it.isNotBlank() } else null
                                val segments = BadgeSegments(
                                    lang = lang,
                                    unreadCount = unreadCount,
                                    downloadCount = downloadCount,
                                    unreadDot = unreadDot,
                                )
                                MangaListItem(
                                    coverData = coverData,
                                    title = title,
                                    subtitle = subtitle.takeIf { it.isNotEmpty() },
                                    trailing = if (segments.isNotEmpty()) {
                                        { Badge(segments = segments) }
                                    } else {
                                        null
                                    },
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
                            item(
                                key = "header:${category.id ?: 0}",
                                span = StaggeredGridItemSpan.FullLine,
                                contentType = "library_category_header",
                            ) {
                                LibraryCategoryHeader(
                                    name = category.name,
                                    itemCount = displayedHeaderCounts[category.id ?: 0] ?: 0,
                                    showItemCount = showCategoryItemCounts,
                                    isCollapsed = category.id != null && category.id in collapsedIds,
                                    collapsible = collapsibleHeaders,
                                    onClick = { onToggleCategoryCollapse(category) },
                                )
                            }
                            items(
                                items = mangaItems,
                                key = { it.libraryManga.manga.id ?: 0L },
                                contentType = { "library_grid_item" },
                            ) { item ->
                                // Staggered + uniformGrid=false: drop the cover aspect-ratio so
                                // each cell sizes to its image's intrinsic ratio.
                                LibraryGridCell(
                                    item = item,
                                    libraryLayout = libraryLayout,
                                    outlineOnCovers = outlineOnCovers,
                                    showDownloadBadge = showDownloadBadge,
                                    showLanguageBadge = showLanguageBadge,
                                    unreadBadgeType = unreadBadgeType,
                                    hideStartReadingButton = hideStartReadingButton,
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
                            item(
                                key = "header:${category.id ?: 0}",
                                span = { GridItemSpan(maxLineSpan) },
                                contentType = "library_category_header",
                            ) {
                                LibraryCategoryHeader(
                                    name = category.name,
                                    itemCount = displayedHeaderCounts[category.id ?: 0] ?: 0,
                                    showItemCount = showCategoryItemCounts,
                                    isCollapsed = category.id != null && category.id in collapsedIds,
                                    collapsible = collapsibleHeaders,
                                    onClick = { onToggleCategoryCollapse(category) },
                                )
                            }
                            items(
                                items = mangaItems,
                                key = { it.libraryManga.manga.id ?: 0L },
                                contentType = { "library_grid_item" },
                            ) { item ->
                                LibraryGridCell(
                                    item = item,
                                    libraryLayout = libraryLayout,
                                    outlineOnCovers = outlineOnCovers,
                                    showDownloadBadge = showDownloadBadge,
                                    showLanguageBadge = showLanguageBadge,
                                    unreadBadgeType = unreadBadgeType,
                                    hideStartReadingButton = hideStartReadingButton,
                                    onContinueReading = onContinueReading,
                                )
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
            AnimatedVisibility(
                visible = hopperVisible,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(bottom = 12.dp),
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                CategoryHopper(
                    onUpClick = onHopperUp,
                    onCenterClick = { pickerOpen = true },
                    onDownClick = onHopperDown,
                    // Long-press up = jump to the first item (matches legacy
                    // hopper.upCategory.setOnLongClickListener).
                    onUpLongClick = { coroutineScope.launch { scrollTo(0) } },
                    // Long-press down = jump to the last item. categoryOffsets tracks the
                    // header index + items.size for each category, so the total item count is
                    // the last offset + 1 + items.size of the trailing category.
                    onDownLongClick = {
                        val lastEntry = library.entries.lastOrNull() ?: return@CategoryHopper
                        val lastOffset = (categoryOffsets.lastOrNull()?.first ?: 0) + lastEntry.value.size
                        coroutineScope.launch { scrollTo(lastOffset) }
                    },
                    // Long-press center dispatches by user pref. Indices match
                    // CategoriesTab.hopperLongPressEntries.
                    onCenterLongClick = {
                        when (hopperLongPressAction) {
                            0 -> onSearchActiveChange(true)
                            1 -> onExpandCollapseAllCategories()
                            // 1 = Display tab, 3 = Categories tab where the Group by row lives.
                            2 -> onOpenSheetAt(1)
                            3 -> onOpenSheetAt(3)
                            4 -> onOpenRandomSeries()
                        }
                    },
                    modifier = Modifier
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
                        ),
                )
            }
        }
        if (pickerOpen) {
            CategoryPickerSheet(
                categories = allCategories,
                itemCounts = categoryItemCounts,
                showItemCounts = showCategoryItemCounts,
                activeCategoryId = activeCategory?.id,
                onSelect = { category ->
                    val target = categoryOffsets.firstOrNull { it.second.id == category.id }?.first
                    target?.let { idx ->
                        // Instant jump matches legacy scrollToHeader (uses
                        // scrollToPositionWithOffset). animateScrollToItem jitters when items
                        // between source and target need to be measured.
                        coroutineScope.launch { scrollTo(idx) }
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
) {
    val focusRequester = remember { FocusRequester() }
    // Auto-focus on first composition so the keyboard appears immediately when the user taps
    // the search icon. LaunchedEffect with Unit key fires once per entry into the searchActive
    // branch.
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    TopAppBar(
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

/**
 * Per-cell rendering shared between [LazyLibraryGrid] and [LazyLibraryStaggeredGrid]. Resolves
 * the badge / cover prefs into the right [MangaCompactGridItem] / [MangaComfortableGridItem]
 * call without duplicating the body across the two grid scopes (their `items` overloads have
 * incompatible scope types so the call sites cannot share code directly).
 *
 * @param coverAspectRatio passed through to the underlying cell. Null lets the cover render at
 *   its image's intrinsic ratio (staggered grid with `uniformGrid` off); the default 2:3 is
 *   used by the fixed grid.
 */
@Composable
private fun LibraryGridCell(
    item: LibraryItem.Manga,
    libraryLayout: Int,
    outlineOnCovers: Boolean,
    showDownloadBadge: Boolean,
    showLanguageBadge: Boolean,
    unreadBadgeType: Int,
    hideStartReadingButton: Boolean,
    onContinueReading: (Manga) -> Unit,
    coverAspectRatio: Float? = MangaCoverRatio.BOOK,
) {
    val manga = item.libraryManga.manga
    // manga.id keys both lookups: cover() rebuilds a thin wrapper and title hits the
    // Injekt-backed CustomMangaManager for favorited entries; both are stable per row.
    val coverData = remember(manga.id) { manga.cover() }
    val title = remember(manga.id) { manga.title }
    // unreadBadgeType: -1 hide, 1 show count, 2 show dot. Pass 0 unread when hidden so the
    // badge slot collapses; otherwise the cell decides between count and dot via [unreadDot].
    val unreadCount = if (unreadBadgeType > 0) item.libraryManga.unread else 0
    val unreadDot = unreadBadgeType == 2
    val downloadCount = if (showDownloadBadge) {
        item.downloadCount.toInt().coerceAtLeast(0)
    } else {
        0
    }
    val lang = if (showLanguageBadge) item.language.takeIf { it.isNotBlank() } else null
    // Continue-reading button: only when the user has not hidden it AND the manga has unread
    // chapters. Skip the cover-only layout's button entirely so the cover stays unobstructed.
    val continueReadingClick = if (
        !hideStartReadingButton &&
        item.libraryManga.unread > 0 &&
        libraryLayout != LAYOUT_COVER_ONLY_GRID
    ) {
        { onContinueReading(manga) }
    } else {
        null
    }
    // Skip per-cover loading indicator: with large libraries each Coil state transition is a
    // recompose, and the cover placeholder color is enough visual cue.
    when (libraryLayout) {
        LAYOUT_COMFORTABLE_GRID -> MangaComfortableGridItem(
            coverData = coverData,
            title = title,
            lang = lang,
            unreadCount = unreadCount,
            downloadCount = downloadCount,
            showOutline = outlineOnCovers,
            coverAspectRatio = coverAspectRatio,
            unreadDot = unreadDot,
            onClickContinueReading = continueReadingClick,
            showLoadingIndicator = false,
        )
        LAYOUT_COVER_ONLY_GRID -> MangaCompactGridItem(
            coverData = coverData,
            title = title,
            lang = lang,
            unreadCount = unreadCount,
            downloadCount = downloadCount,
            showOutline = outlineOnCovers,
            showTitle = false,
            coverAspectRatio = coverAspectRatio,
            unreadDot = unreadDot,
            showLoadingIndicator = false,
        )
        // LAYOUT_COMPACT_GRID default; LAYOUT_LIST is rendered by the isList branch upstream.
        else -> MangaCompactGridItem(
            coverData = coverData,
            title = title,
            lang = lang,
            unreadCount = unreadCount,
            downloadCount = downloadCount,
            showOutline = outlineOnCovers,
            coverAspectRatio = coverAspectRatio,
            unreadDot = unreadDot,
            onClickContinueReading = continueReadingClick,
            showLoadingIndicator = false,
        )
    }
}
