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
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material3.DropdownMenuItem
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
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.presentation.components.NestedMenuItem
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.databinding.DownloadListBinding
import reikai.domain.library.ContentType
import reikai.presentation.components.ContentTypeFilterChips
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
        val novelGroups by novelModel.state.collectAsState()
        val contentType by novelModel.contentType.collectAsState()
        val novelCount by remember { derivedStateOf { novelGroups.sumOf { it.items.size } } }
        val showManga = contentType != ContentType.NOVELS
        val showNovels = contentType != ContentType.MANGA
        val shownCount = (if (showManga) downloadCount else 0) + (if (showNovels) novelCount else 0)
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
                        // RK: sort + reorder act on the manga queue; gate to the manga/all view
                        if (showManga && downloadList.isNotEmpty()) {
                            var sortExpanded by remember { mutableStateOf(false) }
                            val onDismissRequest = { sortExpanded = false }
                            DropdownMenu(
                                expanded = sortExpanded,
                                onDismissRequest = onDismissRequest,
                            ) {
                                NestedMenuItem(
                                    text = { Text(text = stringResource(MR.strings.action_order_by_upload_date)) },
                                    children = { closeMenu ->
                                        DropdownMenuItem(
                                            text = { Text(text = stringResource(MR.strings.action_newest)) },
                                            onClick = {
                                                screenModel.reorderQueue(
                                                    { it.download.chapter.dateUpload },
                                                    true,
                                                )
                                                closeMenu()
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text(text = stringResource(MR.strings.action_oldest)) },
                                            onClick = {
                                                screenModel.reorderQueue(
                                                    { it.download.chapter.dateUpload },
                                                    false,
                                                )
                                                closeMenu()
                                            },
                                        )
                                    },
                                )
                                NestedMenuItem(
                                    text = { Text(text = stringResource(MR.strings.action_order_by_chapter_number)) },
                                    children = { closeMenu ->
                                        DropdownMenuItem(
                                            text = { Text(text = stringResource(MR.strings.action_asc)) },
                                            onClick = {
                                                screenModel.reorderQueue(
                                                    { it.download.chapter.chapterNumber },
                                                    false,
                                                )
                                                closeMenu()
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text(text = stringResource(MR.strings.action_desc)) },
                                            onClick = {
                                                screenModel.reorderQueue(
                                                    { it.download.chapter.chapterNumber },
                                                    true,
                                                )
                                                closeMenu()
                                            },
                                        )
                                    },
                                )
                            }

                            AppBarActions(
                                listOf(
                                    AppBar.Action(
                                        title = stringResource(MR.strings.action_sort),
                                        icon = Icons.AutoMirrored.Outlined.Sort,
                                        onClick = { sortExpanded = true },
                                    ),
                                    AppBar.OverflowAction(
                                        title = stringResource(MR.strings.action_cancel_all),
                                        // RK: clear the novel queue too when both are shown
                                        onClick = {
                                            screenModel.clearQueue()
                                            if (showNovels) novelModel.cancelAll()
                                        },
                                    ),
                                ),
                            )
                        } else if (showNovels && novelGroups.isNotEmpty()) {
                            // RK: novels-only view, just a cancel-all (no reorder/sort for text downloads)
                            AppBarActions(
                                listOf(
                                    AppBar.OverflowAction(
                                        title = stringResource(MR.strings.action_cancel_all),
                                        onClick = { novelModel.cancelAll() },
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
                if (novelGroups.isEmpty()) {
                    EmptyScreen(
                        stringRes = MR.strings.information_no_downloads,
                        modifier = Modifier.padding(pad),
                    )
                } else {
                    NovelDownloadQueueList(
                        groups = novelGroups,
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
                        downloadList.isEmpty() && novelGroups.isEmpty() ->
                            Box(modifier = Modifier.weight(1f)) {
                                EmptyScreen(
                                    stringRes = MR.strings.information_no_downloads,
                                    modifier = Modifier.padding(bodyPadding),
                                )
                            }
                        novelGroups.isEmpty() -> Box(modifier = Modifier.weight(1f)) { mangaBody(bodyPadding) }
                        downloadList.isEmpty() -> Box(modifier = Modifier.weight(1f)) { novelBody(bodyPadding) }
                        else -> {
                            Box(modifier = Modifier.weight(1f)) { mangaBody(bodyPadding) }
                            HorizontalDivider()
                            Box(modifier = Modifier.weight(1f)) { novelBody(bodyPadding) }
                        }
                    }
                }
            }
            // RK <--
        }
    }
}
