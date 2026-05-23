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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import eu.kanade.tachiyomi.ui.library.LibraryItem.Companion.LAYOUT_COMPACT_GRID
import eu.kanade.tachiyomi.ui.library.LibraryItem.Companion.LAYOUT_COVER_ONLY_GRID
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
import yokai.presentation.manga.components.MangaComfortableGridItem
import yokai.presentation.manga.components.MangaCompactGridItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryContent(
    library: Map<Category, List<LibraryItem.Manga>>,
    columns: Int,
    libraryLayout: Int,
    searchActive: Boolean,
    searchQuery: String,
    showCategoryInTitle: Boolean,
    hideHopper: Boolean,
    autohideHopper: Boolean,
    hopperGravity: Int,
    onSearchActiveChange: (Boolean) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onHopperGravityChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // System back closes search before exiting the library.
    BackHandler(enabled = searchActive) {
        onSearchQueryChange("")
        onSearchActiveChange(false)
    }

    val gridState = rememberLazyGridState()
    val coroutineScope = rememberCoroutineScope()

    // Precomputed map of (lazy-grid header index) -> Category. The lazy-grid scope below emits
    // one header item followed by N grid items per category, so each header's index = sum of
    // (1 + items.size) for all preceding categories.
    val categoryOffsets = remember(library) {
        var offset = 0
        library.map { (cat, items) ->
            val start = offset
            offset += 1 + items.size
            start to cat
        }
    }

    val activeCategory by remember(categoryOffsets) {
        derivedStateOf {
            val firstVisible = gridState.firstVisibleItemIndex
            categoryOffsets.lastOrNull { it.first <= firstVisible }?.second
        }
    }

    val showChip = showCategoryInTitle &&
        library.size > 1 &&
        activeCategory != null &&
        !searchActive

    val hopperVisible by remember(hideHopper, autohideHopper, library, searchActive) {
        derivedStateOf {
            val base = !searchActive && !hideHopper && library.size > 1
            if (autohideHopper) base && !gridState.isScrollInProgress else base
        }
    }

    val hopperAlignment = when (hopperGravity) {
        0 -> Alignment.BottomStart
        2 -> Alignment.BottomEnd
        else -> Alignment.BottomCenter
    }

    val onHopperUp = {
        val activeIdx = categoryOffsets.indexOfLast { it.first <= gridState.firstVisibleItemIndex }
        if (activeIdx >= 0) {
            val activeHeaderIdx = categoryOffsets[activeIdx].first
            val pastHeader = gridState.firstVisibleItemIndex > activeHeaderIdx ||
                gridState.firstVisibleItemScrollOffset > 0
            val target = when {
                pastHeader -> activeHeaderIdx
                activeIdx > 0 -> categoryOffsets[activeIdx - 1].first
                else -> null
            }
            target?.let { idx ->
                coroutineScope.launch { gridState.animateScrollToItem(idx) }
            }
        }
        Unit
    }
    val onHopperDown = {
        val activeIdx = categoryOffsets.indexOfLast { it.first <= gridState.firstVisibleItemIndex }
        val nextHeader = categoryOffsets.getOrNull(activeIdx + 1)?.first
        nextHeader?.let { idx ->
            coroutineScope.launch { gridState.animateScrollToItem(idx) }
        }
        Unit
    }

    var pickerOpen by remember { mutableStateOf(false) }

    // Drag-to-snap gravity. Mirrors LibraryGestureDetector.onFling in the legacy: a horizontal
    // fling that exceeds both distance and velocity thresholds steps the hopper one gravity
    // position toward the swipe direction (0 <-> 1 <-> 2). translationX animates back to 0 on
    // release whether or not a gravity change was applied.
    val density = LocalDensity.current
    val velocityThresholdPx = with(density) { 100.dp.toPx() }
    val distanceThresholdPx = with(density) { 80.dp.toPx() }
    val hopperTranslationX = remember { Animatable(0f) }
    val draggableState = rememberDraggableState { delta ->
        coroutineScope.launch {
            hopperTranslationX.snapTo(hopperTranslationX.value + delta)
        }
    }

    Scaffold(
        modifier = modifier,
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
            } else {
                TopAppBar(
                    title = { Text(stringResource(MR.strings.library)) },
                    actions = {
                        IconButton(onClick = { onSearchActiveChange(true) }) {
                            Icon(
                                imageVector = Icons.Outlined.Search,
                                contentDescription = stringResource(MR.strings.search),
                            )
                        }
                    },
                )
            }
        },
    ) { contentPadding ->
        Box(modifier = Modifier.padding(contentPadding)) {
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
                        Text(
                            text = category.name,
                            modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 4.dp),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    items(
                        items = mangaItems,
                        key = { it.libraryManga.manga.id ?: 0L },
                        contentType = { "library_grid_item" },
                    ) { item ->
                        val manga = item.libraryManga.manga
                        // Avoid recomputing the cover wrapper and the title getter (which hits the
                        // Injekt-backed CustomMangaManager for favorited manga) on every recompose
                        // triggered by Coil state updates. Each manga.id is unique within the lazy
                        // grid scope so it is a stable cache key.
                        val coverData = remember(manga.id) { manga.cover() }
                        val title = remember(manga.id) { manga.title }
                        // Skip the per-cover loading indicator. With large libraries each Coil state
                        // transition triggers a recompose, which adds up to noticeable cold-start
                        // lag; the cover placeholder color is enough visual cue while loading.
                        when (libraryLayout) {
                            LAYOUT_COMPACT_GRID, LAYOUT_COVER_ONLY_GRID -> {
                                MangaCompactGridItem(
                                    coverData = coverData,
                                    title = title,
                                    showLoadingIndicator = false,
                                )
                            }
                            else -> {
                                // LAYOUT_COMFORTABLE_GRID and LAYOUT_LIST (list mode falls back to
                                // comfortable until a list item composable lands in a later phase).
                                MangaComfortableGridItem(
                                    coverData = coverData,
                                    title = title,
                                    showLoadingIndicator = false,
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
                    .align(hopperAlignment)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                CategoryHopper(
                    onUpClick = onHopperUp,
                    onCenterClick = { pickerOpen = true },
                    onDownClick = onHopperDown,
                    modifier = Modifier
                        .offset { IntOffset(hopperTranslationX.value.roundToInt(), 0) }
                        .draggable(
                            orientation = Orientation.Horizontal,
                            state = draggableState,
                            onDragStopped = { velocity ->
                                val absDistance = abs(hopperTranslationX.value)
                                val absVelocity = abs(velocity)
                                if (absDistance > distanceThresholdPx && absVelocity > velocityThresholdPx) {
                                    val swipingRight = hopperTranslationX.value > 0
                                    val newGravity = when (hopperGravity) {
                                        0 -> if (swipingRight) 1 else 0
                                        2 -> if (swipingRight) 2 else 1
                                        else -> if (swipingRight) 2 else 0
                                    }
                                    if (newGravity != hopperGravity) {
                                        onHopperGravityChange(newGravity)
                                    }
                                }
                                hopperTranslationX.animateTo(0f, animationSpec = tween(150))
                            },
                        ),
                )
            }
        }
        if (pickerOpen) {
            CategoryPickerSheet(
                categories = library.keys.toList(),
                activeCategoryId = activeCategory?.id,
                onSelect = { category ->
                    val target = categoryOffsets.firstOrNull { it.second.id == category.id }?.first
                    target?.let { idx ->
                        coroutineScope.launch { gridState.animateScrollToItem(idx) }
                    }
                    pickerOpen = false
                },
                onDismiss = { pickerOpen = false },
            )
        }
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
