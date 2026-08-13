package reikai.presentation.recents

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.FlipToBack
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.presentation.components.relativeDateText
import eu.kanade.presentation.history.components.HistoryDeleteAllDialog
import eu.kanade.presentation.history.components.HistoryDeleteDialog
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.presentation.manga.components.ChapterDownloadIndicator
import eu.kanade.presentation.manga.components.MangaBottomActionMenu
import eu.kanade.presentation.updates.UpdatesDeleteConfirmationDialog
import eu.kanade.presentation.util.formatChapterNumber
import eu.kanade.presentation.util.isTabletUi
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.tachiyomi.util.lang.toTimestampString
import mihon.feature.upcoming.UpcomingScreen
import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType
import reikai.presentation.browse.components.EntryDuplicateDialog
import reikai.presentation.components.ContentTypeFilterChips
import reikai.presentation.history.EntryHistoryRow
import reikai.presentation.history.EntryHistoryRowUi
import reikai.presentation.migrate.flow.EntryMigrateFor
import reikai.presentation.updates.EntryUpdatesRow
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.domain.library.service.LibraryPreferences.ChapterSwipeAction
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.FastScrollLazyColumn
import tachiyomi.presentation.core.components.ListGroupHeader
import tachiyomi.presentation.core.components.material.PullRefresh
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.EmptyScreenAction
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.theme.active
import java.util.Date

/**
 * The one recent-activity screen, rendering whichever mode its [engine] is on. Both tabs call it and
 * keep their own identity: the bottom nav selects by tab class, so a single shared Tab would light
 * both entries at once. Everything describing the list comes off the engine; this file decides only
 * how it looks. Record: content-layer-recents-surface.md.
 */
@Composable
fun Screen.RecentsScreen(
    engine: RecentsEngine,
    title: String,
    modifier: Modifier = Modifier,
    // Hoisted so a tab that speaks for itself can be heard: History's reselect resume answers "no next
    // chapter" from outside this screen, and a host of its own would render nowhere.
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val context = LocalContext.current
    val navigator = LocalNavigator.currentOrThrow
    val scope = rememberCoroutineScope()
    var filterSheetOpen by rememberSaveable { mutableStateOf(false) }

    val mode by engine.mode.collectAsState()
    val contentType by engine.contentType.collectAsState()
    val query by engine.query.collectAsState()
    // The three flows the engine shares with WhileSubscribed are read with a lifecycle, so the window
    // can actually close: a plain collectAsState runs for as long as this tab stays composed, which
    // is the whole time the app is backgrounded, and the providers behind them never stop.
    val refreshing by engine.refreshing.collectAsStateWithLifecycle()
    // Collected, never read as `value`: shared while subscribed, it answers its seed to nobody.
    val lastUpdated by engine.lastUpdated.collectAsStateWithLifecycle()
    val filterActive by engine.filterActive.collectAsState()
    val selection by engine.selection.collectAsState()
    val swipeActions by engine.swipeActions.collectAsState()
    // Null until the assembly catches up with the chip, which is drawn as loading.
    val rendered by engine.rendered.collectAsStateWithLifecycle()

    val rows = rendered?.rows.orEmpty()
    val selectionEnabled = mode.can(RecentsCapability.SELECTION)
    // The order a sweep runs along: both types interleaved, grouping and collapsing already applied.
    // The same list the engine prunes the selection to, so the two cannot disagree.
    val orderedRefs = remember(rows) { rows.orderedChapterRefs() }
    val showsUpdated = RecentsLaneKind.UPDATED in mode.lanes
    val showsRead = RecentsLaneKind.READ in mode.lanes

    fun open(item: RecentsItem) {
        scope.launchIO {
            val target = engine.open(item)
            withUIContext {
                // Every path that opens a chapter says so when there is none left; this one is the
                // only one a recents row has.
                target.launch(context, navigator) {
                    snackbarHostState.showSnackbar(context.stringResource(MR.strings.no_next_chapter))
                }
            }
        }
    }

    fun openDetails(entry: EntryId) {
        scope.launchIO {
            val screen = engine.detailsScreen(entry) ?: return@launchIO
            withUIContext { navigator.push(screen) }
        }
    }

    fun clearHistory() {
        scope.launchIO {
            // Only announced once it has actually happened: the wipe can fail, and this is the one
            // message about it the user has no way of checking.
            if (!engine.clearHistory()) return@launchIO
            withUIContext {
                snackbarHostState.showSnackbar(context.stringResource(MR.strings.clear_history_completed))
            }
        }
    }

    fun refresh() {
        val started = engine.refresh()
        scope.launchIO {
            val message = when {
                !started -> MR.strings.update_already_running
                contentType == ContentType.ALL -> MR.strings.updating_both_libraries
                else -> MR.strings.updating_library
            }
            withUIContext { snackbarHostState.showSnackbar(context.stringResource(message)) }
        }
    }

    BackHandler(enabled = selection.isNotEmpty(), onBack = engine::clearSelection)

    Scaffold(
        modifier = modifier,
        // The Scaffold's pinned scroll behaviour is deliberately dropped rather than passed on. All it
        // does here is tint the bar once content slides under it, and with the mode strip between the
        // bar and the list the outer nested scroll never sees the list return to the top, so the tint
        // stays after scrolling back up. Browse ignores it the same way.
        topBar = {
            RecentsToolbar(
                title = title,
                query = query,
                onQueryChange = engine::search,
                selectionCount = selection.size,
                onCancelSelection = engine::clearSelection,
                onSelectAll = { engine.selectAll(orderedRefs) },
                onInvertSelection = { engine.invertSelection(orderedRefs) },
                filterActive = filterActive,
                onFilterClicked = { filterSheetOpen = true },
                showsCalendar = contentType != ContentType.NOVELS && showsUpdated,
                onCalendarClicked = { navigator.push(UpcomingScreen()) },
                // Both are the updated lane's: History has never offered either, and a takeover that
                // added them would be inventing an affordance rather than carrying one across.
                showsRefresh = showsUpdated,
                onRefresh = ::refresh,
                showsClearHistory = showsRead,
                onClearHistory = { engine.openDialog(RecentsDialog.ClearHistory) },
                scrollBehavior = null,
            )
        },
        bottomBar = {
            RecentsBottomBar(
                engine = engine,
                // Off the drawn rows, not the assembly: a row the surface no longer shows must not
                // answer for what the bar offers, any more than it stays in the selection.
                selected = rows.selectableItems().filter { it.lane.chapterRef in selection },
                onDeleteDownloads = { engine.openDialog(RecentsDialog.DeleteDownloads(selection)) },
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { contentPadding ->
        val layoutDirection = LocalLayoutDirection.current
        Column(modifier = Modifier.padding(top = contentPadding.calculateTopPadding())) {
            val modes = remember(engine.modes) { RECENTS_MODE_ORDER.filter { it in engine.modes } }
            if (modes.size > 1) {
                RecentsModeTabs(modes = modes, selected = mode, onSelect = engine::setMode)
            }
            ContentTypeFilterChips(selected = contentType, onSelect = engine::setContentType)
            val bodyPadding = PaddingValues(
                start = contentPadding.calculateStartPadding(layoutDirection),
                end = contentPadding.calculateEndPadding(layoutDirection),
                bottom = contentPadding.calculateBottomPadding(),
            )
            Box(modifier = Modifier.weight(1f)) {
                when {
                    rendered == null || rendered?.loading == true -> LoadingScreen(Modifier.padding(bodyPadding))
                    rows.isEmpty() -> RecentsEmptyState(
                        mode = mode,
                        query = query,
                        filterActive = filterActive,
                        onFilterClicked = { filterSheetOpen = true },
                        modifier = Modifier.padding(bodyPadding),
                    )
                    else -> {
                        val feed: @Composable () -> Unit = {
                            FastScrollLazyColumn(contentPadding = bodyPadding) {
                                if (showsUpdated) {
                                    lastUpdatedItem(lastUpdated)
                                }
                                recentsRows(
                                    rows = rows,
                                    engine = engine,
                                    mode = mode,
                                    selection = selection,
                                    selectionEnabled = selectionEnabled,
                                    orderedRefs = orderedRefs,
                                    swipeActions = swipeActions,
                                    onOpen = ::open,
                                    onOpenDetails = ::openDetails,
                                )
                            }
                        }
                        // Pull-to-refresh belongs to the feed a library update actually changes.
                        if (showsUpdated) {
                            PullRefresh(
                                refreshing = refreshing,
                                onRefresh = ::refresh,
                                enabled = selection.isEmpty(),
                                indicatorPadding = bodyPadding,
                            ) { feed() }
                        } else {
                            feed()
                        }
                    }
                }
            }
        }
    }

    if (filterSheetOpen) {
        RecentsFilterSheet(
            surface = engine.surface,
            onDismissRequest = { filterSheetOpen = false },
        )
    }

    RecentsDialogs(
        engine = engine,
        onOpenDetails = ::openDetails,
        onClearHistory = ::clearHistory,
    )
}

/**
 * Search and selection cannot share one bar: [SearchToolbar] has no action mode, and [AppBar] hides
 * its normal actions the moment the counter rises. So the bar swaps rather than growing a branch.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecentsToolbar(
    title: String,
    query: String?,
    onQueryChange: (String?) -> Unit,
    selectionCount: Int,
    onCancelSelection: () -> Unit,
    onSelectAll: () -> Unit,
    onInvertSelection: () -> Unit,
    filterActive: Boolean,
    onFilterClicked: () -> Unit,
    showsCalendar: Boolean,
    onCalendarClicked: () -> Unit,
    showsRefresh: Boolean,
    onRefresh: () -> Unit,
    showsClearHistory: Boolean,
    onClearHistory: () -> Unit,
    scrollBehavior: TopAppBarScrollBehavior?,
) {
    if (selectionCount > 0) {
        AppBar(
            title = title,
            actionModeCounter = selectionCount,
            onCancelActionMode = onCancelSelection,
            actionModeActions = {
                AppBarActions(
                    listOf(
                        AppBar.Action(
                            title = stringResource(MR.strings.action_select_all),
                            icon = Icons.Outlined.SelectAll,
                            onClick = onSelectAll,
                        ),
                        AppBar.Action(
                            title = stringResource(MR.strings.action_select_inverse),
                            icon = Icons.Outlined.FlipToBack,
                            onClick = onInvertSelection,
                        ),
                    ),
                )
            },
            scrollBehavior = scrollBehavior,
        )
        return
    }
    SearchToolbar(
        titleContent = { AppBarTitle(title) },
        searchQuery = query,
        onChangeSearchQuery = onQueryChange,
        actions = {
            AppBarActions(
                actions = buildList {
                    // Every mode has a category filter, so every mode gets the way in; what the sheet
                    // then draws is the mode's business.
                    add(
                        AppBar.Action(
                            title = stringResource(MR.strings.action_filter),
                            icon = Icons.Outlined.FilterList,
                            iconTint = if (filterActive) {
                                MaterialTheme.colorScheme.active
                            } else {
                                LocalContentColor.current
                            },
                            onClick = onFilterClicked,
                        ),
                    )
                    // Upcoming, update library and clear history live in the overflow at every width.
                    // Search and Filter are what this screen is driven by, and the mode strip below
                    // already carries a row of targets; a second row of icons beside them reads as
                    // clutter on a tablet as much as on a phone.
                    if (showsCalendar) {
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(MR.strings.action_view_upcoming),
                                onClick = onCalendarClicked,
                            ),
                        )
                    }
                    if (showsRefresh) {
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(MR.strings.action_update_library),
                                onClick = onRefresh,
                            ),
                        )
                    }
                    if (showsClearHistory) {
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(MR.strings.pref_clear_history),
                                onClick = onClearHistory,
                            ),
                        )
                    }
                },
            )
        },
        scrollBehavior = scrollBehavior,
    )
}

/** Why the feed is empty, which the replaced screens could not always say. */
@Composable
private fun RecentsEmptyState(
    mode: RecentsMode,
    query: String?,
    filterActive: Boolean,
    onFilterClicked: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    EmptyScreen(
        stringRes = when {
            !query.isNullOrEmpty() -> MR.strings.no_results_found
            filterActive -> MR.strings.information_no_recent_filtered
            else -> mode.emptyRes
        },
        modifier = modifier,
        actions = onFilterClicked
            ?.takeIf { filterActive }
            ?.let { listOf(EmptyScreenAction(MR.strings.action_filter, Icons.Outlined.FilterList, it)) },
    )
}

/**
 * One row of a feed showing several lanes. The lane decides the subtitle and what the row can offer:
 * an update downloads, a read row deletes its record and can be added to the library, and a newly
 * added row offers none of it, having no chapter to download and no read record to remove. Offering
 * it one anyway is what made the delete on a newly added row do nothing at all.
 */
@Composable
private fun RecentsMixedLaneRow(
    item: RecentsItem,
    engine: RecentsEngine,
    ui: RecentsRowUi,
    selected: Boolean,
    selectionActive: Boolean,
    swipeActions: RecentsSwipeActions,
    onPress: (RecentsItem) -> Unit,
    onLongPress: (RecentsItem) -> Unit,
    onOpenDetails: (EntryId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = ui.state
    val download = engine.downloadUi(item)
    val ref = item.lane.chapterRef
    // Swipe stays on the updated lane by ruling rather than by capability: the read lane carries its
    // own chapter state now, so it could answer one, but giving History swipe is its own decision.
    val swipeable = item.lane is RecentsLane.Updated
    val swipe = if (swipeable) swipeActions else DISABLED_SWIPE
    // Only resolved where a gesture or an indicator draws it. The read lane answers this by asking
    // the queue and the on-disk index, so invoking it per row would put that lookup in every
    // composition to feed a control that lane does not draw.
    val downloadState = when {
        swipeable -> download?.state?.invoke() ?: Download.State.NOT_DOWNLOADED
        else -> Download.State.NOT_DOWNLOADED
    }

    RecentsCombinedRow(
        cover = ui.cover,
        title = ui.title,
        chapterLine = mixedLaneChapter(ui.chapter),
        timeLine = mixedLaneTime(item),
        progressLine = readProgressLabel(state?.progress),
        // A newly added row has no chapter, so nothing about it is read.
        read = state?.read == true,
        bookmark = state?.bookmark == true,
        selected = selected,
        onClick = { onPress(item) },
        onLongClick = { onLongPress(item) },
        onClickCover = { onOpenDetails(item.entryId) }.takeIf { !selectionActive },
        chapterSwipeStartAction = swipe.start,
        chapterSwipeEndAction = swipe.end,
        onChapterSwipe = { action ->
            if (state != null && ref != null) {
                engine.runChapterSwipe(ref, state, { downloadState }, action)
            }
        },
        downloadState = downloadState,
        modifier = modifier,
        trailing = {
            when (item.lane) {
                // Always drawn, even where the engine behind it cannot report a state: every update
                // row carrying the same control is the point, and one row silently missing it is the
                // raggedness this row shape exists to remove.
                is RecentsLane.Updated -> ChapterDownloadIndicator(
                    enabled = ref != null && !selectionActive,
                    modifier = Modifier.padding(start = 4.dp),
                    downloadStateProvider = download?.state ?: NOT_DOWNLOADED,
                    downloadProgressProvider = download?.progress?.asProvider() ?: NO_DOWNLOAD_PROGRESS,
                    onClick = { action -> ref?.let { engine.download(setOf(it), action) } },
                )
                // Both go quiet during a sweep, like every other control on this row: the read lane
                // is not favorite-gated, so a row here may be an entry the library does not hold.
                is RecentsLane.Read -> {
                    if (!ui.isFavorite) {
                        IconButton(
                            onClick = { engine.addToLibrary(item.entryId) },
                            enabled = !selectionActive,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.FavoriteBorder,
                                contentDescription = stringResource(MR.strings.add_to_library),
                            )
                        }
                    }
                    // A read row names a real chapter, so it can download exactly like an update row,
                    // and the selection menu has always offered it. Withholding the row control while
                    // the same action sat two taps away was an asymmetry, not a capability limit.
                    ChapterDownloadIndicator(
                        enabled = ref != null && !selectionActive,
                        modifier = Modifier.padding(start = 4.dp),
                        downloadStateProvider = download?.state ?: NOT_DOWNLOADED,
                        downloadProgressProvider = download?.progress?.asProvider() ?: NO_DOWNLOAD_PROGRESS,
                        onClick = { action -> ref?.let { engine.download(setOf(it), action) } },
                    )
                    IconButton(
                        onClick = { engine.openDialog(RecentsDialog.RemoveHistory(item)) },
                        enabled = !selectionActive,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = stringResource(MR.strings.action_delete),
                        )
                    }
                }
                RecentsLane.Added -> Unit
            }
        },
    )
}

/**
 * The chapter a row is about, or null on the newly added lane, which names none. A source that
 * numbered nothing gives a negative number and is treated the same way: there is no chapter to name.
 */
@Composable
private fun mixedLaneChapter(chapter: RecentsChapterUi?): String? = when (chapter) {
    is RecentsChapterUi.Named -> chapter.name
    is RecentsChapterUi.Number ->
        chapter.value
            .takeIf { it > -1 }
            ?.let { stringResource(MR.strings.recents_row_chapter, formatChapterNumber(it)) }
    null -> null
}

/**
 * When the activity happened, carrying the verb its lane implies. Feed mixes the three lanes into one
 * list, so a bare timestamp there cannot say whether a row is something you read or something that
 * arrived; the section headers answer that in Grouped, and nothing does in Feed.
 */
@Composable
private fun mixedLaneTime(item: RecentsItem): String {
    val time = remember(item.timestamp) { Date(item.timestamp).toTimestampString() }
    val res = when (item.lane) {
        is RecentsLane.Read -> MR.strings.recents_row_read
        is RecentsLane.Updated -> MR.strings.recents_row_updated
        RecentsLane.Added -> MR.strings.recents_row_added
    }
    return stringResource(res, time)
}

private val DISABLED_SWIPE = RecentsSwipeActions(
    start = ChapterSwipeAction.Disabled,
    end = ChapterSwipeAction.Disabled,
)

private fun LazyListScope.recentsRows(
    rows: List<RecentsRow>,
    engine: RecentsEngine,
    mode: RecentsMode,
    selection: Set<ChapterRef>,
    selectionEnabled: Boolean,
    orderedRefs: List<ChapterRef>,
    swipeActions: RecentsSwipeActions,
    onOpen: (RecentsItem) -> Unit,
    onOpenDetails: (EntryId) -> Unit,
) {
    // A tap selects instead of opening once a selection exists, which is what makes a sweep possible
    // without a mode switch. A long press starts one, and extends it to the row pressed.
    fun press(item: RecentsItem) {
        val ref = item.lane.chapterRef
        if (selectionEnabled && ref != null && selection.isNotEmpty()) engine.toggleSelection(ref) else onOpen(item)
    }

    fun longPress(item: RecentsItem) {
        val ref = item.lane.chapterRef ?: return
        if (selectionEnabled) engine.toggleRangeSelection(ref, orderedRefs)
    }

    items(
        items = rows,
        contentType = { it.contentTypeKey() },
        key = { it.listKey() },
    ) { row ->
        when (row) {
            is RecentsRow.DateHeader -> ListGroupHeader(
                text = relativeDateText(row.date),
                modifier = Modifier.animateItem(),
            )
            is RecentsRow.SectionHeader -> RecentsSectionHeader(
                section = row.section,
                modifier = Modifier.animateItem(),
            )
            is RecentsRow.SectionFooter -> RecentsSectionFooter(
                onClick = { engine.setMode(row.section.singleLaneMode()) },
                modifier = Modifier.animateItem(),
            )
            is RecentsRow.Entry -> RecentsEntryRow(
                item = row.item,
                engine = engine,
                mode = mode,
                selected = row.item.lane.chapterRef in selection,
                selectionActive = selection.isNotEmpty(),
                swipeActions = swipeActions,
                onPress = ::press,
                onLongPress = ::longPress,
                onOpenDetails = onOpenDetails,
                modifier = Modifier.animateItem(),
            )
            is RecentsRow.Group -> {
                val first = row.members.first()
                val ui = engine.rowUi(first)
                val refs = row.members.mapNotNull { it.lane.chapterRef }
                val allSelected = refs.isNotEmpty() && refs.all { it in selection }
                val toggleAll = {
                    if (allSelected) refs.forEach(engine::toggleSelection) else engine.selectAll(refs)
                }
                RecentsGroupRow(
                    cover = ui.cover,
                    title = ui.title,
                    count = row.members.size,
                    expanded = row.expanded,
                    selected = allSelected,
                    anyUnread = row.members.any { !engine.rowUi(it).isRead() },
                    onClick = {
                        if (selectionEnabled &&
                            selection.isNotEmpty()
                        ) {
                            toggleAll()
                        } else {
                            engine.toggleGroupExpanded(row.key)
                        }
                    },
                    onLongClick = { if (selectionEnabled) toggleAll() },
                    // Quiet during a sweep, as the flat rows are: navigating away mid-selection takes
                    // the selection with it.
                    onClickCover = { onOpenDetails(first.entryId) }.takeIf { selection.isEmpty() },
                    modifier = Modifier.animateItem(),
                )
            }
            is RecentsRow.Child -> {
                val childUi = engine.rowUi(row.item)
                val chapter = childUi.chapter
                val state = childUi.state
                if (chapter is RecentsChapterUi.Named && state != null) {
                    val download = engine.downloadUi(row.item)
                    val ref = row.item.lane.chapterRef
                    RecentsGroupChildRow(
                        chapter = chapter,
                        state = state,
                        selected = ref in selection,
                        download = download,
                        onClick = { press(row.item) },
                        onLongClick = { longPress(row.item) },
                        onDownloadClick = ref
                            ?.let { { action: ChapterDownloadAction -> engine.download(setOf(it), action) } }
                            ?.takeIf { selection.isEmpty() },
                        chapterSwipeStartAction = swipeActions.start,
                        chapterSwipeEndAction = swipeActions.end,
                        onChapterSwipe = { action ->
                            if (ref != null) {
                                engine.runChapterSwipe(
                                    ref = ref,
                                    state = state,
                                    downloadState = download?.state ?: NOT_DOWNLOADED,
                                    action = action,
                                )
                            }
                        },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }
}

/**
 * One row of the feed, shaped by what its chapter half turned out to be rather than by which mode is
 * on screen: an updated row names a chapter, a read row numbers one, and a newly added row has none.
 * That is what lets the combined modes draw a mixed list without asking any row its content type.
 */
@Composable
private fun RecentsEntryRow(
    item: RecentsItem,
    engine: RecentsEngine,
    mode: RecentsMode,
    selected: Boolean,
    selectionActive: Boolean,
    swipeActions: RecentsSwipeActions,
    onPress: (RecentsItem) -> Unit,
    onLongPress: (RecentsItem) -> Unit,
    onOpenDetails: (EntryId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ui = engine.rowUi(item)
    if (mode.lanes.size > 1) {
        RecentsMixedLaneRow(
            item = item,
            engine = engine,
            ui = ui,
            selected = selected,
            selectionActive = selectionActive,
            swipeActions = swipeActions,
            onPress = onPress,
            onLongPress = onLongPress,
            onOpenDetails = onOpenDetails,
            modifier = modifier,
        )
        return
    }
    val state = ui.state
    when (val chapter = ui.chapter) {
        is RecentsChapterUi.Named -> {
            val download = engine.downloadUi(item)
            val ref = item.lane.chapterRef
            EntryUpdatesRow(
                cover = ui.cover,
                title = ui.title,
                chapterName = chapter.name,
                read = state?.read == true,
                bookmark = state?.bookmark == true,
                selected = selected,
                readProgress = readProgressLabel(state?.progress),
                onClick = { onPress(item) },
                onLongClick = { onLongPress(item) },
                // Both go quiet during a sweep, as the replaced screen and upstream's row do: a cover
                // tap mid-selection navigates away and takes the selection with it.
                onClickCover = { onOpenDetails(item.entryId) }.takeIf { !selectionActive },
                onDownloadChapter = ref
                    ?.let { { action: ChapterDownloadAction -> engine.download(setOf(it), action) } }
                    ?.takeIf { !selectionActive },
                downloadStateProvider = download?.state ?: NOT_DOWNLOADED,
                downloadProgressProvider = download?.progress?.asProvider() ?: NO_DOWNLOAD_PROGRESS,
                chapterSwipeStartAction = swipeActions.start,
                chapterSwipeEndAction = swipeActions.end,
                // A Named row only ever comes off the updated lane, which only a surface holding the
                // updates model collects, so the provider behind it always answers these verbs.
                onChapterSwipe = { action ->
                    if (ref != null && state != null) {
                        engine.runChapterSwipe(
                            ref = ref,
                            state = state,
                            downloadState = download?.state ?: NOT_DOWNLOADED,
                            action = action,
                        )
                    }
                },
                modifier = modifier,
            )
        }
        else -> {
            val download = engine.downloadUi(item)
            val ref = item.lane.chapterRef
            EntryHistoryRow(
                ui = EntryHistoryRowUi(
                    cover = ui.cover,
                    title = ui.title,
                    // A newly added row has no chapter at all, and this row reads a negative number as
                    // "say nothing about chapters" rather than needing a second flag.
                    chapterNumber = (chapter as? RecentsChapterUi.Number)?.value ?: -1.0,
                    readAt = Date(item.timestamp).toTimestampString(),
                    isFavorite = ui.isFavorite,
                ),
                onClickCover = { onOpenDetails(item.entryId) },
                onClickResume = { onPress(item) },
                onClickDelete = { engine.openDialog(RecentsDialog.RemoveHistory(item)) },
                onClickFavorite = { engine.addToLibrary(item.entryId) },
                modifier = modifier,
                selected = selected,
                onLongClick = { onLongPress(item) },
                // Only where the row names a chapter. A newly added row reaches this branch with
                // none, and download is the one control here that needs one.
                downloadStateProvider = (download?.state ?: NOT_DOWNLOADED).takeIf { ref != null },
                downloadProgressProvider = download?.progress?.asProvider() ?: NO_DOWNLOAD_PROGRESS,
                onDownloadClick = ref
                    ?.let { { action: ChapterDownloadAction -> engine.download(setOf(it), action) } }
                    ?.takeIf { !selectionActive },
            )
        }
    }
}

/**
 * The bulk actions, each drawn only where the selection can answer for it. The predicates read the
 * selected rows' own projections rather than a per-type row object, which is what lets one bar serve
 * a selection spanning both content types.
 */
@Composable
private fun RecentsBottomBar(
    engine: RecentsEngine,
    selected: List<RecentsItem>,
    onDeleteDownloads: () -> Unit,
) {
    val chapters = selected.mapNotNull { engine.rowUi(it).state }
    val downloads = selected.mapNotNull { engine.downloadUi(it) }
    MangaBottomActionMenu(
        visible = selected.isNotEmpty(),
        modifier = Modifier.fillMaxWidth(),
        onBookmarkClicked = { engine.setBookmarkSelection(true) }
            .takeIf { chapters.any { chapter -> !chapter.bookmark } },
        // Guarded on non-empty, unlike its five siblings: `all` is vacuously true over nothing, so a
        // selection that answers for no chapter would offer this one action and no other.
        onRemoveBookmarkClicked = { engine.setBookmarkSelection(false) }
            .takeIf { chapters.isNotEmpty() && chapters.all { chapter -> chapter.bookmark } },
        onMarkAsReadClicked = { engine.markReadSelection(true) }
            .takeIf { chapters.any { chapter -> !chapter.read } },
        // Started counts as readable-back: progress survives only where reading stopped short, so one
        // expression covers what the two screens each spelled out in their own unit.
        onMarkAsUnreadClicked = { engine.markReadSelection(false) }
            .takeIf { chapters.any { chapter -> chapter.read || chapter.progress != null } },
        onDownloadClicked = { engine.downloadSelection() }
            .takeIf { downloads.any { it.state() != Download.State.DOWNLOADED } },
        onDeleteClicked = onDeleteDownloads
            .takeIf { downloads.any { it.state() == Download.State.DOWNLOADED } },
    )
}

/** The surface's one dialog slot, drawn wherever the engine raised it. */
@Composable
private fun Screen.RecentsDialogs(
    engine: RecentsEngine,
    onOpenDetails: (EntryId) -> Unit,
    onClearHistory: () -> Unit,
) {
    val navigator = LocalNavigator.currentOrThrow
    val dialog by engine.dialog.collectAsState()
    val onDismiss = engine::dismissDialog
    when (val open = dialog) {
        null -> Unit
        RecentsDialog.ClearHistory -> HistoryDeleteAllDialog(
            onDismissRequest = onDismiss,
            onDelete = onClearHistory,
        )
        is RecentsDialog.RemoveHistory -> HistoryDeleteDialog(
            onDismissRequest = onDismiss,
            onDelete = { all ->
                if (all) {
                    engine.removeFromHistory(setOf(open.item.entryId))
                } else {
                    engine.removeHistoryRecord(open.item)
                }
            },
        )
        is RecentsDialog.DeleteDownloads -> UpdatesDeleteConfirmationDialog(
            onDismissRequest = onDismiss,
            onConfirm = { engine.deleteDownloads(open.chapters) },
        )
        is RecentsDialog.Duplicate -> EntryDuplicateDialog(
            duplicates = open.duplicates.duplicates,
            toUi = { it.card },
            onDismissRequest = onDismiss,
            onConfirm = { engine.addAnyway(open.entry) },
            onOpen = { onOpenDetails(it.entry) },
            onMigrate = { engine.migrateOntoEntry(open.entry, it.entry) },
            groupIdByEntryId = open.duplicates.groupIdByRawId,
            onAddToGroup = { picked: List<Long> ->
                engine.addToGroup(
                    open.entry,
                    open.duplicates.duplicates.filter { it.card.id in picked }.map { it.entry },
                )
            }.takeIf { open.duplicates.suggestGroup },
        )
        is RecentsDialog.ChangeCategory -> ChangeCategoryDialog(
            initialSelection = open.initialSelection,
            onDismissRequest = onDismiss,
            onEditCategories = { navigator.push(CategoryScreen()) },
            onConfirm = { include, _ -> engine.applyAddCategories(open.entry, include) },
        )
        is RecentsDialog.Migrate -> EntryMigrateFor(
            contentType = open.current.contentType,
            currentId = open.current.rawId,
            targetId = open.target.rawId,
            onDismissRequest = onDismiss,
        )
    }
}

private fun RecentsRowUi.isRead(): Boolean = state?.read ?: true

/** The mode a digest section's footer jumps to. Newly added has none, and draws no footer. */
private fun RecentsLaneKind.singleLaneMode(): RecentsMode = when (this) {
    RecentsLaneKind.UPDATED -> RecentsMode.UPDATES
    RecentsLaneKind.READ -> RecentsMode.HISTORY
    RecentsLaneKind.ADDED -> error("The newly added lane has no single-lane mode to jump to")
}

private fun RecentsRow.contentTypeKey(): String = when (this) {
    is RecentsRow.DateHeader -> "date"
    is RecentsRow.SectionHeader -> "section"
    is RecentsRow.SectionFooter -> "footer"
    is RecentsRow.Entry -> "entry"
    is RecentsRow.Group -> "group"
    is RecentsRow.Child -> "child"
}

/**
 * A key unique across the whole list. The item half carries the lane, because the digest collapses
 * within each section rather than across them, so one entry read and updated today is two rows and a
 * key built from its identity alone would repeat.
 */
private fun RecentsRow.listKey(): String = when (this) {
    is RecentsRow.DateHeader -> "date-$date"
    is RecentsRow.SectionHeader -> "section-$section"
    is RecentsRow.SectionFooter -> "footer-$section"
    is RecentsRow.Entry -> "entry-${item.key()}"
    is RecentsRow.Group -> "group-$key"
    is RecentsRow.Child -> "child-${item.key()}"
}

private fun RecentsItem.key(): String =
    "${entryId.contentType}-${entryId.rawId}-${lane.kind}-${lane.chapterRef?.chapterId ?: 0L}"

private val NOT_DOWNLOADED: () -> Download.State = { Download.State.NOT_DOWNLOADED }

private val NO_DOWNLOAD_PROGRESS: () -> Int = { 0 }
