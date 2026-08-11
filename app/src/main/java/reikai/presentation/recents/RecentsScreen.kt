package reikai.presentation.recents

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.presentation.components.relativeDateText
import eu.kanade.presentation.updates.updatesLastUpdatedItem
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.util.lang.toTimestampString
import mihon.feature.upcoming.UpcomingScreen
import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType
import reikai.presentation.components.ContentTypeFilterChips
import reikai.presentation.history.EntryHistoryRow
import reikai.presentation.history.EntryHistoryRowUi
import reikai.presentation.updates.EntryUpdatesRow
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withUIContext
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
fun RecentsScreen(
    engine: RecentsEngine,
    title: String,
    onFilterClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val navigator = LocalNavigator.currentOrThrow
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val mode by engine.mode.collectAsState()
    val contentType by engine.contentType.collectAsState()
    val query by engine.query.collectAsState()
    val refreshing by engine.refreshing.collectAsState()
    // Collected, never read as `value`: shared while subscribed, it answers its seed to nobody.
    val lastUpdated by engine.lastUpdated.collectAsState()
    val filterActive by engine.filterActive.collectAsState()
    val selection by engine.selection.collectAsState()
    val groupBySeries by engine.groupBySeries.collectAsState()
    val expandedGroups by engine.expandedGroups.collectAsState()
    // The assembly lags a chip flip by one emission, so it is drawn only once its tag catches up.
    val assembled = engine.assembled.collectAsState().value?.takeIf { it.chip == contentType }

    val rows = remember(mode, assembled, groupBySeries, expandedGroups) {
        assembled?.let { renderRows(mode, it, groupBySeries, expandedGroups) }.orEmpty()
    }

    fun open(item: RecentsItem) {
        scope.launchIO {
            when (val target = engine.open(item)) {
                // Every path that opens a chapter says so when there is none left; this one is the
                // only one a recents row has.
                null -> withUIContext {
                    snackbarHostState.showSnackbar(context.stringResource(MR.strings.no_next_chapter))
                }
                is RecentsOpen.ReaderIntent -> withUIContext { context.startActivity(target.intent) }
                is RecentsOpen.ReaderScreen -> withUIContext { navigator.push(target.screen) }
            }
        }
    }

    fun openDetails(entry: EntryId) {
        scope.launchIO {
            val screen = engine.detailsScreen(entry) ?: return@launchIO
            withUIContext { navigator.push(screen) }
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

    Scaffold(
        modifier = modifier,
        topBar = { scrollBehavior ->
            RecentsToolbar(
                title = title,
                query = query,
                onQueryChange = engine::search,
                selectionCount = selection.size,
                onCancelSelection = engine::clearSelection,
                showsFilter = mode.can(RecentsCapability.CHAPTER_FILTER),
                filterActive = filterActive,
                onFilterClicked = onFilterClicked,
                showsCalendar = contentType != ContentType.NOVELS && RecentsLaneKind.UPDATED in mode.lanes,
                onCalendarClicked = { navigator.push(UpcomingScreen()) },
                onRefresh = ::refresh,
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { contentPadding ->
        val layoutDirection = LocalLayoutDirection.current
        Column(modifier = Modifier.padding(top = contentPadding.calculateTopPadding())) {
            ContentTypeFilterChips(selected = contentType, onSelect = engine::setContentType)
            val bodyPadding = PaddingValues(
                start = contentPadding.calculateStartPadding(layoutDirection),
                end = contentPadding.calculateEndPadding(layoutDirection),
                bottom = contentPadding.calculateBottomPadding(),
            )
            Box(modifier = Modifier.weight(1f)) {
                when {
                    assembled == null || assembled.loading -> LoadingScreen(Modifier.padding(bodyPadding))
                    rows.isEmpty() -> RecentsEmptyState(
                        query = query,
                        filterActive = filterActive,
                        onFilterClicked = onFilterClicked.takeIf { mode.can(RecentsCapability.CHAPTER_FILTER) },
                        modifier = Modifier.padding(bodyPadding),
                    )
                    else -> PullRefresh(
                        refreshing = refreshing,
                        onRefresh = ::refresh,
                        enabled = selection.isEmpty(),
                        indicatorPadding = bodyPadding,
                    ) {
                        FastScrollLazyColumn(contentPadding = bodyPadding) {
                            if (RecentsLaneKind.UPDATED in mode.lanes) {
                                updatesLastUpdatedItem(lastUpdated)
                            }
                            recentsRows(
                                rows = rows,
                                engine = engine,
                                onOpen = ::open,
                                onOpenDetails = ::openDetails,
                            )
                        }
                    }
                }
            }
        }
    }
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
    showsFilter: Boolean,
    filterActive: Boolean,
    onFilterClicked: () -> Unit,
    showsCalendar: Boolean,
    onCalendarClicked: () -> Unit,
    onRefresh: () -> Unit,
    scrollBehavior: TopAppBarScrollBehavior?,
) {
    if (selectionCount > 0) {
        AppBar(
            title = title,
            actionModeCounter = selectionCount,
            onCancelActionMode = onCancelSelection,
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
                    if (showsFilter) {
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
                    }
                    if (showsCalendar) {
                        add(
                            AppBar.Action(
                                title = stringResource(MR.strings.action_view_upcoming),
                                icon = Icons.Outlined.CalendarMonth,
                                onClick = onCalendarClicked,
                            ),
                        )
                    }
                    add(
                        AppBar.Action(
                            title = stringResource(MR.strings.action_update_library),
                            icon = Icons.Outlined.Refresh,
                            onClick = onRefresh,
                        ),
                    )
                },
            )
        },
        scrollBehavior = scrollBehavior,
    )
}

/** Why the feed is empty, which the replaced screens could not always say. */
@Composable
private fun RecentsEmptyState(
    query: String?,
    filterActive: Boolean,
    onFilterClicked: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    EmptyScreen(
        stringRes = when {
            !query.isNullOrEmpty() -> MR.strings.no_results_found
            filterActive -> MR.strings.information_no_recent_filtered
            else -> MR.strings.information_no_recent
        },
        modifier = modifier,
        actions = onFilterClicked
            ?.takeIf { filterActive }
            ?.let { listOf(EmptyScreenAction(MR.strings.action_filter, Icons.Outlined.FilterList, it)) },
    )
}

private fun renderRows(
    mode: RecentsMode,
    assembled: RecentsAssembled,
    groupBySeries: Boolean,
    expandedGroups: Set<String>,
): List<RecentsRow> = when (mode) {
    RecentsMode.UPDATES -> updatesRows(assembled.items, groupBySeries, assembled.membership, expandedGroups)
    RecentsMode.HISTORY -> historyRows(assembled.items, assembled.membership)
    RecentsMode.FEED -> flatRecentsRows(assembled.items, assembled.membership)
    RecentsMode.DIGEST -> digestRows(assembled.items, assembled.membership)
}

private fun LazyListScope.recentsRows(
    rows: List<RecentsRow>,
    engine: RecentsEngine,
    onOpen: (RecentsItem) -> Unit,
    onOpenDetails: (EntryId) -> Unit,
) {
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
                onOpen = onOpen,
                onOpenDetails = onOpenDetails,
                modifier = Modifier.animateItem(),
            )
            is RecentsRow.Group -> {
                val first = row.members.first()
                val ui = engine.rowUi(first)
                RecentsGroupRow(
                    cover = ui.cover,
                    title = ui.title,
                    count = row.members.size,
                    expanded = row.expanded,
                    selected = false,
                    anyUnread = row.members.any { !engine.rowUi(it).isRead() },
                    onClick = { engine.toggleGroupExpanded(row.key) },
                    onLongClick = {},
                    onClickCover = { onOpenDetails(first.entryId) },
                    modifier = Modifier.animateItem(),
                )
            }
            is RecentsRow.Child -> {
                val chapter = engine.rowUi(row.item).chapter
                if (chapter is RecentsChapterUi.Named) {
                    RecentsGroupChildRow(
                        chapter = chapter,
                        selected = false,
                        download = engine.downloadUi(row.item),
                        onClick = { onOpen(row.item) },
                        onLongClick = {},
                        onDownloadClick = null,
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
    onOpen: (RecentsItem) -> Unit,
    onOpenDetails: (EntryId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ui = engine.rowUi(item)
    when (val chapter = ui.chapter) {
        is RecentsChapterUi.Named -> {
            val download = engine.downloadUi(item)
            EntryUpdatesRow(
                cover = ui.cover,
                title = ui.title,
                chapterName = chapter.name,
                read = chapter.read,
                bookmark = chapter.bookmark,
                selected = false,
                readProgress = readProgressLabel(chapter.progress),
                onClick = { onOpen(item) },
                onLongClick = {},
                onClickCover = { onOpenDetails(item.entryId) },
                onDownloadChapter = null,
                downloadStateProvider = download?.state ?: NOT_DOWNLOADED,
                downloadProgressProvider = download?.progress?.asProvider() ?: NO_DOWNLOAD_PROGRESS,
                modifier = modifier,
            )
        }
        else -> EntryHistoryRow(
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
            onClickResume = { onOpen(item) },
            onClickDelete = { engine.openDialog(RecentsDialog.RemoveHistory(item)) },
            onClickFavorite = { engine.addToLibrary(item.entryId) },
            modifier = modifier,
        )
    }
}

private fun RecentsRowUi.isRead(): Boolean = (chapter as? RecentsChapterUi.Named)?.read ?: true

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
