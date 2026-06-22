package eu.kanade.tachiyomi.ui.download

import android.view.LayoutInflater
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallExtendedFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.animateFloatingActionButton
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.databinding.DownloadListBinding
import reikai.domain.library.ContentType
import reikai.presentation.components.ContentTypeFilterChips
import reikai.presentation.download.DownloadQueueSortKey
import reikai.presentation.download.DownloadQueueSortSheet
import reikai.presentation.download.NovelDownloadQueueList
import reikai.presentation.download.NovelDownloadQueueScreenModel
import tachiyomi.core.common.util.lang.launchUI
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.Pill
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import kotlin.math.roundToInt

object DownloadQueueScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val screenModel = rememberScreenModel { DownloadQueueScreenModel() }
        val downloadList by screenModel.state.collectAsState()
        val downloadCount by remember {
            derivedStateOf { downloadList.sumOf { it.subItems.size } }
        }

        // RK --> novel side of the unified queue + the content-type chip (P5 S5)
        val novelModel = rememberScreenModel { NovelDownloadQueueScreenModel() }
        val novelItems by novelModel.state.collectAsState()
        val contentType by novelModel.contentType.collectAsState()
        val novelCount = novelItems.size
        val showManga = contentType != ContentType.NOVELS
        val showNovels = contentType != ContentType.MANGA
        val shownCount = (if (showManga) downloadCount else 0) + (if (showNovels) novelCount else 0)
        // sort acts on whichever queue(s) are visible, so in the ALL view one pick sorts both
        val mangaSortable = showManga && downloadList.isNotEmpty()
        val novelSortable = showNovels && novelItems.isNotEmpty()
        var showSortSheet by remember { mutableStateOf(false) }
        // Default to chapter number ascending (the natural download order), shown active in the sheet.
        var sortKey by remember { mutableStateOf(DownloadQueueSortKey.CHAPTER_NUMBER) }
        var sortDescending by remember { mutableStateOf(false) }
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
                                            if (mangaSortable) screenModel.clearQueue()
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
                // RK: the FAB pauses/resumes the manga downloader; novels drain on a foreground worker
                if (showManga) {
                    val isRunning by screenModel.isDownloaderRunning.collectAsState()
                    SmallExtendedFloatingActionButton(
                        text = {
                            val id = if (isRunning) {
                                MR.strings.action_pause
                            } else {
                                MR.strings.action_resume
                            }
                            Text(text = stringResource(id))
                        },
                        icon = {
                            val icon = if (isRunning) {
                                Icons.Outlined.Pause
                            } else {
                                Icons.Filled.PlayArrow
                            }
                            Icon(imageVector = icon, contentDescription = null)
                        },
                        onClick = {
                            if (isRunning) {
                                screenModel.pauseDownloads()
                            } else {
                                screenModel.startDownloads()
                            }
                        },
                        expanded = fabExpanded,
                        modifier = Modifier.animateFloatingActionButton(
                            visible = downloadList.isNotEmpty(),
                            alignment = Alignment.BottomEnd,
                        ),
                    )
                }
            },
        ) { contentPadding ->
            // RK --> chip + content-type-switched body: manga RecyclerView vs novel Compose list (P5 S5)
            val layoutDirection = LocalLayoutDirection.current
            // Launch the manga progress collectors once, even though a chip switch can recreate the view.
            val collectorsStarted = remember { BooleanArray(1) }

            val mangaBody: @Composable (PaddingValues) -> Unit = { pad ->
                if (downloadList.isEmpty()) {
                    EmptyScreen(
                        stringRes = MR.strings.information_no_downloads,
                        modifier = Modifier.padding(pad),
                    )
                } else {
                    val density = LocalDensity.current
                    val left = with(density) { pad.calculateLeftPadding(layoutDirection).toPx().roundToInt() }
                    val top = with(density) { pad.calculateTopPadding().toPx().roundToInt() }
                    val right = with(density) { pad.calculateRightPadding(layoutDirection).toPx().roundToInt() }
                    val bottom = with(density) { pad.calculateBottomPadding().toPx().roundToInt() }
                    Box(modifier = Modifier.nestedScroll(nestedScrollConnection)) {
                        AndroidView(
                            modifier = Modifier.fillMaxWidth(),
                            factory = { context ->
                                screenModel.controllerBinding =
                                    DownloadListBinding.inflate(LayoutInflater.from(context))
                                screenModel.adapter = DownloadAdapter(screenModel.listener)
                                screenModel.controllerBinding.root.adapter = screenModel.adapter
                                screenModel.adapter?.isHandleDragEnabled = true
                                screenModel.controllerBinding.root.layoutManager = LinearLayoutManager(context)

                                ViewCompat.setNestedScrollingEnabled(screenModel.controllerBinding.root, true)

                                if (!collectorsStarted[0]) {
                                    collectorsStarted[0] = true
                                    scope.launchUI {
                                        screenModel.getDownloadStatusFlow()
                                            .collect(screenModel::onStatusChange)
                                    }
                                    scope.launchUI {
                                        screenModel.getDownloadProgressFlow()
                                            .collect(screenModel::onUpdateDownloadedPages)
                                    }
                                }

                                screenModel.controllerBinding.root
                            },
                            update = {
                                screenModel.controllerBinding.root
                                    .updatePadding(
                                        left = left,
                                        top = top,
                                        right = right,
                                        bottom = bottom,
                                    )

                                screenModel.adapter?.updateDataSet(downloadList)
                            },
                        )
                    }
                }
            }

            val novelBody: @Composable (PaddingValues) -> Unit = { pad ->
                if (novelItems.isEmpty()) {
                    EmptyScreen(
                        stringRes = MR.strings.information_no_downloads,
                        modifier = Modifier.padding(pad),
                    )
                } else {
                    NovelDownloadQueueList(
                        items = novelItems,
                        onReorder = novelModel::reorder,
                        onCancel = novelModel::cancel,
                        contentPadding = pad,
                    )
                }
            }

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
                when (contentType) {
                    ContentType.MANGA -> Box(modifier = Modifier.weight(1f)) { mangaBody(bodyPadding) }
                    ContentType.NOVELS -> Box(modifier = Modifier.weight(1f)) { novelBody(bodyPadding) }
                    ContentType.ALL -> when {
                        downloadList.isEmpty() && novelItems.isEmpty() ->
                            Box(modifier = Modifier.weight(1f)) {
                                EmptyScreen(
                                    stringRes = MR.strings.information_no_downloads,
                                    modifier = Modifier.padding(bodyPadding),
                                )
                            }
                        novelItems.isEmpty() -> Box(modifier = Modifier.weight(1f)) { mangaBody(bodyPadding) }
                        downloadList.isEmpty() -> Box(modifier = Modifier.weight(1f)) { novelBody(bodyPadding) }
                        else -> {
                            Box(modifier = Modifier.weight(1f)) { mangaBody(bodyPadding) }
                            HorizontalDivider()
                            Box(modifier = Modifier.weight(1f)) { novelBody(bodyPadding) }
                        }
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
                                if (mangaSortable) {
                                    screenModel.reorderQueue({ it.download.chapter.dateUpload }, newDescending)
                                }
                                if (novelSortable) novelModel.sort({ it.dateUpload }, newDescending)
                            }
                            DownloadQueueSortKey.CHAPTER_NUMBER -> {
                                if (mangaSortable) {
                                    screenModel.reorderQueue({ it.download.chapter.chapterNumber }, newDescending)
                                }
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
