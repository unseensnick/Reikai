package eu.kanade.tachiyomi.ui.download

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallExtendedFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.animateFloatingActionButton
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.util.Screen
import reikai.domain.library.ContentType
import reikai.presentation.components.ContentTypeFilterChips
import reikai.presentation.download.DownloadQueueSortKey
import reikai.presentation.download.DownloadQueueSortSheet
import reikai.presentation.download.EntryDownloadCardList
import reikai.presentation.download.MangaDownloadQueueScreenModel
import reikai.presentation.download.NovelDownloadQueueScreenModel
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.Pill
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen

object DownloadQueueScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        // RK: both content types now render on the shared Compose card list. The manga side runs on
        // reikai's MangaDownloadQueueScreenModel (aggregating by series), and Mihon's own
        // DownloadQueueScreenModel + its RecyclerView adapter/holders are the parked per-chapter view.
        val mangaModel = rememberScreenModel { MangaDownloadQueueScreenModel() }
        val mangaItems by mangaModel.state.collectAsState()
        val novelModel = rememberScreenModel { NovelDownloadQueueScreenModel() }
        val novelItems by novelModel.state.collectAsState()
        val contentType by novelModel.contentType.collectAsState()
        // Cards aggregate by series, so a queue-size count is the pending chapters across them.
        val mangaCount = mangaItems.sumOf { it.totalChapters - it.downloadedChapters }
        val novelCount = novelItems.sumOf { it.totalChapters - it.downloadedChapters }
        val showManga = contentType != ContentType.NOVELS
        val showNovels = contentType != ContentType.MANGA
        val shownCount = (if (showManga) mangaCount else 0) + (if (showNovels) novelCount else 0)
        // sort acts on whichever queue(s) are visible, so in the ALL view one pick sorts both
        val mangaSortable = showManga && mangaItems.isNotEmpty()
        val novelSortable = showNovels && novelItems.isNotEmpty()
        var showSortSheet by remember { mutableStateOf(false) }
        // Default to chapter number ascending (the natural download order), shown active in the sheet.
        var sortKey by remember { mutableStateOf(DownloadQueueSortKey.CHAPTER_NUMBER) }
        var sortDescending by remember { mutableStateOf(false) }

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
                            if (shownCount > 0) {
                                val pillAlpha = if (isSystemInDarkTheme()) 0.12f else 0.08f
                                Pill(
                                    text = "$shownCount",
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
                        // RK: a standard sort modal (matching the library / chapter sort sheets) over
                        // whichever queue(s) are visible, so in the ALL view one pick sorts both.
                        if (mangaSortable || novelSortable) {
                            AppBarActions(
                                listOf(
                                    AppBar.Action(
                                        title = stringResource(MR.strings.action_sort),
                                        icon = Icons.Outlined.FilterList,
                                        onClick = { showSortSheet = true },
                                    ),
                                    AppBar.OverflowAction(
                                        title = stringResource(MR.strings.action_cancel_all),
                                        // RK: clear whichever queues are shown
                                        onClick = {
                                            if (mangaSortable) mangaModel.cancelAll()
                                            if (novelSortable) novelModel.cancelAll()
                                        },
                                    ),
                                ),
                            )
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
            },
            floatingActionButton = {
                // RK: one FAB pauses/resumes the visible content's downloader(s). In the All view it
                // drives both, reflecting a combined running state; the empty-queue side no-ops.
                val mangaRunning by mangaModel.isDownloaderRunning.collectAsState()
                val novelRunning by novelModel.isDownloaderRunning.collectAsState()
                val running = (showManga && mangaRunning) || (showNovels && novelRunning)
                val hasQueue = (showManga && mangaItems.isNotEmpty()) ||
                    (showNovels && novelItems.isNotEmpty())
                SmallExtendedFloatingActionButton(
                    text = {
                        val id = if (running) MR.strings.action_pause else MR.strings.action_resume
                        Text(text = stringResource(id))
                    },
                    icon = {
                        val icon = if (running) Icons.Outlined.Pause else Icons.Filled.PlayArrow
                        Icon(imageVector = icon, contentDescription = null)
                    },
                    onClick = {
                        if (running) {
                            if (showManga && mangaRunning) mangaModel.pauseDownloads()
                            if (showNovels && novelRunning) novelModel.pauseDownloads()
                        } else {
                            if (showManga && mangaItems.isNotEmpty()) mangaModel.startDownloads()
                            if (showNovels && novelItems.isNotEmpty()) novelModel.startDownloads()
                        }
                    },
                    expanded = fabExpanded,
                    modifier = Modifier.animateFloatingActionButton(
                        visible = hasQueue,
                        alignment = Alignment.BottomEnd,
                    ),
                )
            },
        ) { contentPadding ->
            // RK --> chip + one shared card list for manga, novels, or both (the All view)
            val layoutDirection = LocalLayoutDirection.current

            Column(modifier = Modifier.padding(top = contentPadding.calculateTopPadding())) {
                ContentTypeFilterChips(
                    selected = contentType,
                    onSelect = novelModel::setContentType,
                )
                // Top inset is consumed by the chip above; the body keeps the side + bottom insets.
                val bodyPadding = PaddingValues(
                    start = contentPadding.calculateStartPadding(layoutDirection),
                    end = contentPadding.calculateEndPadding(layoutDirection),
                    bottom = contentPadding.calculateBottomPadding(),
                )
                // One continuous list: the All view stacks the manga cards then the novel cards instead
                // of splitting the screen, and drag / cancel route back to each type's own queue.
                val shownItems = when (contentType) {
                    ContentType.MANGA -> mangaItems
                    ContentType.NOVELS -> novelItems
                    ContentType.ALL -> mangaItems + novelItems
                }
                Box(modifier = Modifier.weight(1f)) {
                    if (shownItems.isEmpty()) {
                        EmptyScreen(
                            stringRes = MR.strings.information_no_downloads,
                            modifier = Modifier.padding(bodyPadding),
                        )
                    } else {
                        EntryDownloadCardList(
                            items = shownItems,
                            onReorder = { type, ids ->
                                when (type) {
                                    ContentType.MANGA -> mangaModel.reorderBySeries(ids)
                                    ContentType.NOVELS -> novelModel.reorderBySeries(ids)
                                    ContentType.ALL -> Unit
                                }
                            },
                            onCancel = { type, id ->
                                when (type) {
                                    ContentType.MANGA -> mangaModel.cancelSeries(id)
                                    ContentType.NOVELS -> novelModel.cancelSeries(id)
                                    ContentType.ALL -> Unit
                                }
                            },
                            contentPadding = bodyPadding,
                            // Feed the FAB-collapse-on-scroll connection.
                            modifier = Modifier.nestedScroll(nestedScrollConnection),
                        )
                    }
                }
            }

            if (showSortSheet) {
                DownloadQueueSortSheet(
                    sortKey = sortKey,
                    sortDescending = sortDescending,
                    onSort = { key ->
                        val newDescending = if (key == sortKey) !sortDescending else sortDescending
                        sortKey = key
                        sortDescending = newDescending
                        when (key) {
                            DownloadQueueSortKey.UPLOAD_DATE -> {
                                if (mangaSortable) mangaModel.sort({ it.chapter.dateUpload }, newDescending)
                                if (novelSortable) novelModel.sort({ it.dateUpload }, newDescending)
                            }
                            DownloadQueueSortKey.CHAPTER_NUMBER -> {
                                if (mangaSortable) mangaModel.sort({ it.chapter.chapterNumber }, newDescending)
                                if (novelSortable) novelModel.sort({ it.chapterNumber }, newDescending)
                            }
                        }
                    },
                    onDismissRequest = { showSortSheet = false },
                )
            }
            // RK <--
        }
    }
}
