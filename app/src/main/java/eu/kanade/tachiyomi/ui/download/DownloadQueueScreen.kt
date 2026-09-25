package eu.kanade.tachiyomi.ui.download

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallExtendedFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.animateFloatingActionButton
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.more.DownloadQueueState // RK
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.FilterList
import mihon.icons.materialsymbols.roundedfilled.Pause
import mihon.icons.materialsymbols.roundedfilled.PlayArrow
import reikai.presentation.download.DownloadQueueSort
import reikai.presentation.download.DownloadQueueSortSheet
import reikai.presentation.download.EntryDownloadCardList
import reikai.presentation.download.EntryDownloadQueueViewModel
import reikai.presentation.download.EntryDownloadSeriesSheet
import reikai.presentation.download.next
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.Pill
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen

object DownloadQueueScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        // RK --> one list over both content types, on the shared card list (see EntryDownloadQueueViewModel)
        val screenModel = metroViewModel<EntryDownloadQueueViewModel>()
        val state by screenModel.state.collectAsStateWithLifecycle()
        val queueState by screenModel.queueState.collectAsStateWithLifecycle()
        val sheet by screenModel.sheet.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()
        val isRunning = queueState is DownloadQueueState.Downloading
        val hasQueue = state.cards.isNotEmpty()
        var showSortSheet by remember { mutableStateOf(false) }
        var sort by remember { mutableStateOf<DownloadQueueSort?>(null) }
        // RK <--

        val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
        var fabExpanded by remember { mutableStateOf(true) }
        val nestedScrollConnection = remember {
            // All this lines just for fab state :/
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    fabExpanded = available.y >= 0
                    return scrollBehavior.nestedScrollConnection.onPreScroll(available, source)
                }

                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    return scrollBehavior.nestedScrollConnection.onPostScroll(consumed, available, source)
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    return scrollBehavior.nestedScrollConnection.onPreFling(available)
                }

                override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                    return scrollBehavior.nestedScrollConnection.onPostFling(consumed, available)
                }
            }
        }

        Scaffold(
            topBar = {
                AppBar(
                    titleContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(MR.strings.label_download_queue),
                                maxLines = 1,
                                modifier = Modifier.weight(1f, false),
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (state.pendingChapters > 0) {
                                val pillAlpha = if (isSystemInDarkTheme()) 0.12f else 0.08f
                                Pill(
                                    text = "${state.pendingChapters}",
                                    modifier = Modifier.padding(start = 4.dp),
                                    color = MaterialTheme.colorScheme.onBackground
                                        .copy(alpha = pillAlpha),
                                    fontSize = 14.sp,
                                )
                            }
                        }
                    },
                    navigateUp = navigator::pop,
                    actions = {
                        // RK: a standard sort modal (matching the library / chapter sort sheets); a sort
                        // orders chapters within each series, for both content types at once.
                        if (hasQueue) {
                            AppBarActions(
                                listOf(
                                    AppBar.Action(
                                        title = stringResource(MR.strings.action_sort),
                                        icon = MaterialSymbols.Rounded.FilterList,
                                        onClick = { showSortSheet = true },
                                    ),
                                    AppBar.OverflowAction(
                                        title = stringResource(MR.strings.action_cancel_all),
                                        onClick = screenModel::cancelAll,
                                    ),
                                ),
                            )
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
            },
            floatingActionButton = {
                // RK: one FAB pauses every running downloader, or starts every one with a queue.
                SmallExtendedFloatingActionButton(
                    text = {
                        val id = if (isRunning) MR.strings.action_pause else MR.strings.action_resume
                        Text(text = stringResource(id))
                    },
                    icon = {
                        val icon = if (isRunning) {
                            MaterialSymbols.RoundedFilled.Pause
                        } else {
                            MaterialSymbols.RoundedFilled.PlayArrow
                        }
                        Icon(imageVector = icon, contentDescription = null)
                    },
                    onClick = screenModel::togglePause,
                    expanded = fabExpanded,
                    modifier = Modifier.animateFloatingActionButton(
                        visible = hasQueue,
                        alignment = Alignment.BottomEnd,
                    ),
                )
            },
        ) { contentPadding ->
            // RK --> one shared card list for every queued series, manga and novels together
            if (!hasQueue) {
                EmptyScreen(
                    stringRes = MR.strings.information_no_downloads,
                    modifier = Modifier.padding(contentPadding),
                )
            } else {
                EntryDownloadCardList(
                    items = state.cards,
                    showTypeBadge = state.showTypeBadge,
                    onReorder = screenModel::reorder,
                    onCancel = screenModel::cancel,
                    onOpen = screenModel::openSeries,
                    contentPadding = contentPadding,
                    // Feed the FAB-collapse-on-scroll connection.
                    modifier = Modifier.nestedScroll(nestedScrollConnection),
                )
            }

            sheet?.let { opened ->
                EntryDownloadSeriesSheet(
                    sheet = opened,
                    onShowEntry = {
                        scope.launchIO {
                            val screen = screenModel.detailsScreen(opened.card) ?: return@launchIO
                            withUIContext {
                                screenModel.closeSeries()
                                navigator.push(screen)
                            }
                        }
                    },
                    onDownloadNow = { screenModel.downloadNow(opened.card.contentType, it) },
                    onMoveToBottom = { screenModel.moveChapterToBottom(opened.card.contentType, it) },
                    onCancel = { screenModel.cancelChapter(opened.card.contentType, it) },
                    onDismissRequest = screenModel::closeSeries,
                )
            }

            if (showSortSheet) {
                DownloadQueueSortSheet(
                    sort = sort,
                    onSort = { key ->
                        val applied = sort.next(key)
                        sort = applied
                        screenModel.sort(applied.key, applied.descending)
                    },
                    onDismissRequest = { showSortSheet = false },
                )
            }
            // RK <--
        }
    }
}
