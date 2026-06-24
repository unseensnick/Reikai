package reikai.presentation.novel.details

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallExtendedFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.animateFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.NavigatorAdaptiveSheet
import eu.kanade.presentation.components.relativeDateText
import eu.kanade.presentation.manga.EditCoverAction
import eu.kanade.presentation.manga.components.ChapterHeader
import eu.kanade.presentation.manga.components.ExpandableMangaDescription
import eu.kanade.presentation.manga.components.MangaBottomActionMenu
import eu.kanade.presentation.manga.components.MangaChapterListItem
import eu.kanade.presentation.theme.TachiyomiTheme
import eu.kanade.presentation.util.Screen
import eu.kanade.presentation.util.isTabletUi
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.ui.setting.SettingsScreen
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import eu.kanade.tachiyomi.util.system.copyToClipboard
import reikai.data.coil.NovelCover
import reikai.domain.novel.model.NovelChapter
import reikai.presentation.novel.globalsearch.NovelGlobalSearchScreen
import reikai.presentation.novel.migrate.NovelMigrationListScreen
import reikai.presentation.novel.notes.NovelNotesScreen
import reikai.presentation.novel.reader.NovelReaderScreen
import reikai.presentation.novel.track.NovelTrackInfoDialogHomeScreen
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.TwoPanelBox
import tachiyomi.presentation.core.components.material.PullRefresh
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.shouldExpandFAB

/**
 * Light-novel details screen, the novel twin of `MangaScreen` (single-source for now). Mirrors the
 * manga small/large layouts: the phone toolbar fades its title + background as you scroll past the
 * cover; the tablet keeps a two-pane split with the bottom action menu anchored to the chapter pane.
 * Reader, downloads, and merge are stubbed (S4 / S5 / S8).
 */
class NovelScreen(
    private val sourceId: String,
    private val novelUrl: String,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val screenModel = rememberScreenModel { NovelDetailsScreenModel(sourceId, novelUrl) }
        val state by screenModel.state.collectAsState()

        when (val s = state) {
            NovelDetailsState.Loading -> LoadingScreen()
            is NovelDetailsState.Failed -> Scaffold(
                topBar = { AppBar(title = null, navigateUp = navigator::pop, scrollBehavior = it) },
            ) { padding -> EmptyScreen(message = s.message, modifier = Modifier.padding(padding)) }
            is NovelDetailsState.Loaded -> TachiyomiTheme(seedColor = s.seedColor) {
                val onWebView: () -> Unit = {
                    s.novelWebUrl?.takeIf { it.isNotBlank() }?.let { url ->
                        navigator.push(WebViewScreen(url = url, initialTitle = s.sourceName, sourceId = null))
                    }
                }
                val onShare: () -> Unit = {
                    s.novelWebUrl?.takeIf { it.isNotBlank() }?.let { url ->
                        context.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, url)
                                },
                                null,
                            ),
                        )
                    }
                }
                val onSearch: (String) -> Unit = { query -> navigator.push(NovelGlobalSearchScreen(query)) }
                // RK: migration only re-homes a library novel, so the action shows only when favorited.
                val onMigrate: (() -> Unit)? = if (s.novel.favorite) {
                    { navigator.push(NovelMigrationListScreen(listOf(s.novel.id))) }
                } else {
                    null
                }
                // RK: tracking action; mirrors MangaScreen (no logged-in trackers -> Settings > Tracking).
                val onTracking: () -> Unit = {
                    if (screenModel.hasLoggedInTrackers()) {
                        screenModel.showTrackDialog()
                    } else {
                        navigator.push(SettingsScreen(SettingsScreen.Destination.Tracking))
                    }
                }
                // RK: open the full-screen notes editor for the favorited (anchor) novel.
                val onEditNotes: () -> Unit = {
                    navigator.push(NovelNotesScreen(s.novel.id, s.novel.title, s.novel.notes))
                }
                val onCopy: (String) -> Unit = { text -> context.copyToClipboard(text, text) }
                val onChapterClick: (NovelChapter) -> Unit = { chapter ->
                    // Route to the chapter's own source (a unified-list row keeps its owning novelId)
                    // and hand the reader the chapters in READING order (ascending sourceOrder, the
                    // restamped cross-source order for a merged group), independent of the details
                    // display sort, so Next advances forward like the manga reader + library resume.
                    navigator.push(
                        NovelReaderScreen(
                            chapter.novelId,
                            chapter.id,
                            s.chapters.sortedBy { it.sourceOrder }.map { it.id }.toLongArray(),
                        ),
                    )
                }

                if (isTabletUi()) {
                    NovelDetailsLargeImpl(s, screenModel, navigator::pop, onWebView, onShare, onMigrate, onTracking, onEditNotes, onSearch, onCopy, onChapterClick)
                } else {
                    NovelDetailsSmallImpl(s, screenModel, navigator::pop, onWebView, onShare, onMigrate, onTracking, onEditNotes, onSearch, onCopy, onChapterClick)
                }

                NovelDetailsDialogs(s, screenModel)
                NovelCoverDialogHost(s, screenModel::dismissDialog)
            }
        }
    }
}

@Composable
private fun NovelDetailsSmallImpl(
    state: NovelDetailsState.Loaded,
    screenModel: NovelDetailsScreenModel,
    onBack: () -> Unit,
    onWebView: () -> Unit,
    onShare: () -> Unit,
    onMigrate: (() -> Unit)?,
    onTracking: () -> Unit,
    onEditNotes: () -> Unit,
    onSearch: (String) -> Unit,
    onCopy: (String) -> Unit,
    onChapterClick: (NovelChapter) -> Unit,
) {
    val listState = rememberLazyListState()
    val isFirstItemVisible by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
    val isFirstItemScrolled by remember { derivedStateOf { listState.firstVisibleItemScrollOffset > 0 } }
    val titleAlpha by animateFloatAsState(if (!isFirstItemVisible) 1f else 0f, label = "title")
    val backgroundAlpha by animateFloatAsState(if (!isFirstItemVisible || isFirstItemScrolled) 1f else 0f, label = "bg")

    Scaffold(
        topBar = {
            NovelDetailsToolbar(
                state = state,
                screenModel = screenModel,
                onBack = onBack,
                onShare = onShare,
                onMigrate = onMigrate,
                onEditNotes = onEditNotes,
                titleAlphaProvider = { titleAlpha },
                backgroundAlphaProvider = { backgroundAlpha },
            )
        },
        bottomBar = { NovelSelectionBar(state, screenModel, Modifier.fillMaxWidth()) },
        snackbarHost = { SnackbarHost(screenModel.snackbarHostState) },
        floatingActionButton = { NovelResumeFab(state, listState, onChapterClick) },
    ) { contentPadding ->
        val layoutDirection = LocalLayoutDirection.current
        PullRefresh(
            refreshing = state.isRefreshing,
            onRefresh = screenModel::refresh,
            enabled = !state.selectionMode,
            indicatorPadding = PaddingValues(top = contentPadding.calculateTopPadding()),
        ) {
            // Drop the top inset so the info-box backdrop bleeds edge-to-edge behind the toolbar (matches
            // the manga detail). The info box itself offsets its content by the app-bar padding instead.
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(
                    start = contentPadding.calculateStartPadding(layoutDirection),
                    end = contentPadding.calculateEndPadding(layoutDirection),
                    bottom = contentPadding.calculateBottomPadding(),
                ),
            ) {
                novelInfoItems(
                    state, screenModel, onWebView, onShare, onTracking, onEditNotes, onSearch, onCopy,
                    isTabletUi = false,
                    appBarPadding = contentPadding.calculateTopPadding(),
                )
                novelChapterHeaderItems(state, screenModel)
                novelChapterItems(state, screenModel, onChapterClick)
            }
        }
    }
}

@Composable
private fun NovelDetailsLargeImpl(
    state: NovelDetailsState.Loaded,
    screenModel: NovelDetailsScreenModel,
    onBack: () -> Unit,
    onWebView: () -> Unit,
    onShare: () -> Unit,
    onMigrate: (() -> Unit)?,
    onTracking: () -> Unit,
    onEditNotes: () -> Unit,
    onSearch: (String) -> Unit,
    onCopy: (String) -> Unit,
    onChapterClick: (NovelChapter) -> Unit,
) {
    val chapterListState = rememberLazyListState()
    Scaffold(
        topBar = {
            NovelDetailsToolbar(
                state = state,
                screenModel = screenModel,
                onBack = onBack,
                onShare = onShare,
                onMigrate = onMigrate,
                onEditNotes = onEditNotes,
                titleAlphaProvider = { 1f },
                backgroundAlphaProvider = { 1f },
            )
        },
        bottomBar = {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomEnd) {
                NovelSelectionBar(state, screenModel, Modifier.fillMaxWidth(0.5f))
            }
        },
        snackbarHost = { SnackbarHost(screenModel.snackbarHostState) },
        floatingActionButton = { NovelResumeFab(state, chapterListState, onChapterClick) },
    ) { contentPadding ->
        PullRefresh(
            refreshing = state.isRefreshing,
            onRefresh = screenModel::refresh,
            enabled = !state.selectionMode,
            indicatorPadding = PaddingValues(top = contentPadding.calculateTopPadding()),
        ) {
            TwoPanelBox(
                startContent = {
                    LazyColumn(contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding())) {
                        // The start pane only pads the bottom, so the info box clears the app bar itself.
                        novelInfoItems(
                            state,
                            screenModel,
                            onWebView,
                            onShare,
                            onTracking,
                            onEditNotes,
                            onSearch,
                            onCopy,
                            isTabletUi = true,
                            appBarPadding = contentPadding.calculateTopPadding(),
                        )
                    }
                },
                endContent = {
                    // Chips + chapter header sit atop the chapter pane (mirrors MangaScreen's tablet layout).
                    LazyColumn(state = chapterListState, contentPadding = contentPadding) {
                        novelChapterHeaderItems(state, screenModel)
                        novelChapterItems(state, screenModel, onChapterClick)
                    }
                },
            )
        }
    }
}

@Composable
private fun NovelDetailsToolbar(
    state: NovelDetailsState.Loaded,
    screenModel: NovelDetailsScreenModel,
    onBack: () -> Unit,
    onShare: () -> Unit,
    onMigrate: (() -> Unit)?,
    onEditNotes: () -> Unit,
    titleAlphaProvider: () -> Float,
    backgroundAlphaProvider: () -> Float,
) {
    NovelToolbar(
        title = state.novel.title,
        hasFilters = state.readFilter != 0L || state.bookmarkedFilter != 0L,
        navigateUp = onBack,
        onClickFilter = screenModel::showChapterSettingsDialog,
        onClickRefresh = screenModel::refresh,
        onClickEditCategory = screenModel::showChangeCategoryDialog,
        onClickEditInfo = screenModel::showEditNovelInfoDialog,
        onClickEditNotes = onEditNotes,
        onClickShare = state.novelWebUrl?.let { { onShare() } },
        onClickManageSources = if (state.mergeSources.size > 1) screenModel::showManageSourcesDialog else null,
        onClickMigrate = onMigrate,
        onClickDownload = screenModel::runDownloadAction,
        actionModeCounter = state.selection.size,
        onCancelActionMode = screenModel::clearSelection,
        onSelectAll = screenModel::selectAll,
        onInvertSelection = screenModel::invertSelection,
        showHidden = state.showHidden,
        hasHiddenChapters = state.hasHiddenChapters,
        allHiddenSelected = state.showHidden && state.selection.isNotEmpty() &&
            state.selection.all { it in state.hiddenChapterIds },
        onHide = screenModel::hideSelected,
        onUnhide = screenModel::unhideSelected,
        onToggleShowHidden = screenModel::toggleShowHidden,
        titleAlphaProvider = titleAlphaProvider,
        backgroundAlphaProvider = backgroundAlphaProvider,
    )
}

/** Resume/Start reading FAB, the novel twin of `MangaScreen`'s: jumps to the first unread chapter,
 *  collapses to an icon on scroll, and hides when everything is read or in selection mode. */
@Composable
private fun NovelResumeFab(
    state: NovelDetailsState.Loaded,
    listState: LazyListState,
    onChapterClick: (NovelChapter) -> Unit,
) {
    SmallExtendedFloatingActionButton(
        text = {
            Text(stringResource(if (state.hasStarted) MR.strings.action_resume else MR.strings.action_start))
        },
        icon = { Icon(imageVector = Icons.Filled.PlayArrow, contentDescription = null) },
        onClick = { state.resumeChapter?.let(onChapterClick) },
        expanded = listState.shouldExpandFAB(),
        modifier = Modifier.animateFloatingActionButton(
            visible = state.resumeChapter != null && !state.selectionMode,
            alignment = Alignment.BottomEnd,
        ),
    )
}

@Composable
private fun NovelSelectionBar(
    state: NovelDetailsState.Loaded,
    screenModel: NovelDetailsScreenModel,
    modifier: Modifier,
) {
    MangaBottomActionMenu(
        visible = state.selectionMode,
        modifier = modifier,
        onBookmarkClicked = { screenModel.bookmarkSelected(true) },
        onRemoveBookmarkClicked = { screenModel.bookmarkSelected(false) },
        onMarkAsReadClicked = { screenModel.markSelectedRead(true) },
        onMarkAsUnreadClicked = { screenModel.markSelectedRead(false) },
        onMarkPreviousAsReadClicked = { screenModel.markPreviousRead(true) },
        onDownloadClicked = { screenModel.downloadSelected() },
        onDeleteClicked = { screenModel.deleteSelected() },
    )
}

@Composable
private fun NovelDetailsDialogs(state: NovelDetailsState.Loaded, screenModel: NovelDetailsScreenModel) {
    when (val dialog = state.dialog) {
        is NovelDetailsDialog.ChangeCategory -> NovelCategoryDialog(
            dialog = dialog,
            onDismiss = screenModel::dismissDialog,
            onConfirm = screenModel::applyCategories,
        )
        is NovelDetailsDialog.EditInfo -> EditNovelInfoDialog(
            dialog = dialog,
            onDismiss = screenModel::dismissDialog,
            onReset = screenModel::resetNovelInfo,
            onConfirm = screenModel::updateNovelInfo,
        )
        NovelDetailsDialog.ChapterSettings -> NovelChapterSettingsDialog(
            sorting = state.sorting,
            sortDescending = state.sortDescending,
            readFilter = state.readFilter,
            bookmarkedFilter = state.bookmarkedFilter,
            hideChapterTitles = state.hideChapterTitles,
            onDismiss = screenModel::dismissDialog,
            onSortChange = screenModel::setSortOrder,
            onFilterChange = screenModel::setFilters,
            onDisplayChange = screenModel::setHideChapterTitles,
            onSetAsDefault = screenModel::setChapterSettingsAsDefault,
            onReset = screenModel::resetChapterSettings,
        )
        NovelDetailsDialog.PageSelector -> NovelPageSelectorSheet(
            pages = state.pages,
            selectedIndex = state.pageIndex,
            onSelect = screenModel::selectPage,
            onDismiss = screenModel::dismissDialog,
        )
        is NovelDetailsDialog.ManageSources -> NovelManageSourcesDialog(
            sources = dialog.sources,
            onDismissRequest = screenModel::dismissDialog,
            onSplit = screenModel::splitSources,
            onRemoveFromLibrary = screenModel::removeSourcesFromLibrary,
            onRemoveAll = screenModel::removeAllSourcesFromLibrary,
        )
        // RK: tracking sheet (Active #8). Remember by novel id so the merge collectors' frequent
        // recompositions don't rebuild it and reset its navigator mid-write (the manga side hit an
        // InsertTrack JobCancellationException here).
        NovelDetailsDialog.TrackSheet -> {
            val trackScreen = remember(state.novel.id) {
                NovelTrackInfoDialogHomeScreen(novelId = state.novel.id, novelTitle = state.novel.title)
            }
            NavigatorAdaptiveSheet(
                screen = trackScreen,
                enableSwipeDismiss = { it.lastItem is NovelTrackInfoDialogHomeScreen },
                onDismissRequest = screenModel::dismissDialog,
            )
        }
        // Rendered by NovelCoverDialogHost: rememberScreenModel needs the Screen receiver, absent here.
        NovelDetailsDialog.FullCover -> Unit
        null -> {}
    }
}

/** Full-cover dialog host. A `Screen` extension so `rememberScreenModel` resolves (it needs a Screen
 *  receiver); the manga side renders its FullCover dialog inside its Voyager screen for the same reason. */
@Composable
private fun Screen.NovelCoverDialogHost(state: NovelDetailsState.Loaded, onDismiss: () -> Unit) {
    if (state.dialog !is NovelDetailsDialog.FullCover) return
    val context = LocalContext.current
    val display = state.displayNovel
    val coverScreenModel = rememberScreenModel { NovelCoverScreenModel(display.url, display.source, state.sourceUrl) }
    val novel by coverScreenModel.state.collectAsState()
    novel?.let { n ->
        val getContent = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) coverScreenModel.editCover(context, uri)
        }
        NovelCoverDialog(
            cover = NovelCover(
                url = n.thumbnailUrl,
                site = state.sourceUrl,
                isNovelFavorite = n.favorite,
                lastModified = n.coverLastModified,
                novelId = n.id,
            ),
            isCustomCover = remember(n) { coverScreenModel.hasCustomCover() },
            snackbarHostState = coverScreenModel.snackbarHostState,
            onShareClick = { coverScreenModel.shareCover(context) },
            onSaveClick = { coverScreenModel.saveCover(context) },
            onEditClick = {
                when (it) {
                    EditCoverAction.EDIT -> getContent.launch("image/*")
                    EditCoverAction.DELETE -> coverScreenModel.deleteCustomCover(context)
                }
            },
            onDismissRequest = onDismiss,
        )
    }
}

/** Info pane items: cover/title/source, the action row, and the description. On tablet these are the
 *  start pane; on phone they lead the single column. Metadata follows the viewed source (the selected
 *  chip, else the anchor); favorite + library actions stay on the anchor novel. */
private fun LazyListScope.novelInfoItems(
    state: NovelDetailsState.Loaded,
    screenModel: NovelDetailsScreenModel,
    onWebView: () -> Unit,
    onShare: () -> Unit,
    onTracking: () -> Unit,
    onEditNotes: () -> Unit,
    onSearch: (String) -> Unit,
    onCopy: (String) -> Unit,
    isTabletUi: Boolean,
    appBarPadding: Dp,
) {
    val display = state.displayNovel
    item(key = "info") {
        NovelInfoBox(
            isTabletUi = isTabletUi,
            appBarPadding = appBarPadding,
            novel = display,
            // RK: a merged group viewed via the "All" chip shows the unified label, mirroring the
            // manga header. A specific source chip keeps that source's resolved name.
            sourceName = if (state.mergeSources.size > 1 && state.selectedSourceNovelId == null) {
                stringResource(MR.strings.merge_unified)
            } else {
                state.sourceName
            },
            sourceSite = state.sourceUrl,
            onCoverClick = screenModel::showCoverDialog,
            onSearch = onSearch,
            onCopy = onCopy,
        )
    }
    item(key = "actions") {
        NovelActionRow(
            favorite = state.novel.favorite,
            trackingCount = state.trackingCount,
            onAddToLibraryClicked = screenModel::toggleFavorite,
            onWebViewClicked = state.novelWebUrl?.let { { onWebView() } },
            onShareClicked = state.novelWebUrl?.let { { onShare() } },
            onTrackingClicked = onTracking,
        )
    }
    item(key = "description") {
        ExpandableMangaDescription(
            defaultExpandState = false,
            description = display.description,
            tagsProvider = { display.genre },
            // RK: notes are a user annotation on the favorited anchor row, not the viewed source's metadata.
            notes = state.novel.notes,
            onTagSearch = onSearch,
            onCopyTagToClipboard = { onCopy(it) },
            onEditNotes = onEditNotes,
        )
    }
}

/** Source-switcher chips (merged only) + the chapter header + page bar. Sits atop the chapter pane on
 *  tablet and between the info and chapters on phone, mirroring MangaScreen's chip placement. */
private fun LazyListScope.novelChapterHeaderItems(
    state: NovelDetailsState.Loaded,
    screenModel: NovelDetailsScreenModel,
) {
    if (state.mergeSources.size > 1) {
        item(key = "source-chips") {
            NovelMergeSourceChips(
                sources = state.mergeSources,
                selectedSourceNovelId = state.selectedSourceNovelId,
                onSelect = screenModel::selectSource,
                onSplitSource = { screenModel.splitSources(listOf(it)) },
            )
        }
    }
    item(key = "chapter-header") {
        Column {
            ChapterHeader(
                enabled = !state.selectionMode,
                chapterCount = state.chapters.size,
                missingChapterCount = 0,
                onClick = screenModel::showChapterSettingsDialog,
            )
            if (state.isPaged) {
                NovelPageBar(
                    pageIndex = state.pageIndex,
                    pageCount = state.pages.size,
                    isLoading = state.isPageLoading,
                    enabled = !state.selectionMode,
                    onClick = screenModel::showPageSelectorDialog,
                )
            }
        }
    }
}

/** Compact "Page n / N" row under the chapter header, opening the page selector sheet. The chapter
 *  count above it is the current page's count, so the scope is visible (sort/filter are page-scoped). */
@Composable
private fun NovelPageBar(
    pageIndex: Int,
    pageCount: Int,
    isLoading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Page ${pageIndex + 1} / $pageCount",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        }
        Icon(
            imageVector = Icons.Default.ArrowDropDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

/** Dim level for a hidden chapter row shown via "Show hidden chapters". */
private const val HIDDEN_CHAPTER_ALPHA = 0.4f

private fun LazyListScope.novelChapterItems(
    state: NovelDetailsState.Loaded,
    screenModel: NovelDetailsScreenModel,
    onChapterClick: (NovelChapter) -> Unit,
) {
    // In the unified merged view, label each pooled chapter with its origin source (the scanlator
    // slot). Single-source / per-source-chip views show nothing (the label would be redundant).
    val showSource = state.mergeSources.size > 1 && state.selectedSourceNovelId == null
    val sourceNames = if (showSource) state.mergeSources.associate { it.novelId to it.sourceName } else emptyMap()
    items(items = state.chapters, key = { "chapter-${it.id}" }) { chapter ->
        MangaChapterListItem(
            title = chapterTitle(chapter, state.hideChapterTitles),
            date = chapter.dateUpload.takeIf { it > 0L }?.let { relativeDateText(it) },
            readProgress = (chapter.lastTextProgress / 100L).toInt().takeIf { !chapter.read && it > 0 }?.let { "$it%" },
            scanlator = sourceNames[chapter.novelId],
            read = chapter.read,
            bookmark = chapter.bookmark,
            selected = chapter.id in state.selection,
            downloadIndicatorEnabled = !state.selectionMode,
            downloadStateProvider = {
                state.downloadStates[chapter.id]
                    ?: if (chapter.isDownloaded) Download.State.DOWNLOADED else Download.State.NOT_DOWNLOADED
            },
            downloadProgressProvider = { 0 },
            chapterSwipeStartAction = state.chapterSwipeStartAction,
            chapterSwipeEndAction = state.chapterSwipeEndAction,
            onLongClick = { screenModel.toggleSelection(chapter.id, fromLongPress = true) },
            onClick = {
                if (state.selectionMode) {
                    screenModel.toggleSelection(
                        chapter.id,
                        fromLongPress = false,
                    )
                } else {
                    onChapterClick(chapter)
                }
            },
            onDownloadClick = { screenModel.onChapterDownloadAction(chapter, it) },
            onChapterSwipe = { screenModel.chapterSwipe(chapter, it) },
            // Hidden chapters only appear while "Show hidden" is on; dim them to mark the state.
            modifier = Modifier.alpha(if (chapter.id in state.hiddenChapterIds) HIDDEN_CHAPTER_ALPHA else 1f),
            // Without a source label these rows are usually a single line; center so the title and
            // download icon line up. The unified merged view (source label shown) keeps top alignment.
            verticalAlignment = if (showSource) Alignment.Top else Alignment.CenterVertically,
        )
    }
}

private fun chapterTitle(chapter: NovelChapter, hideTitles: Boolean): String =
    if (hideTitles && chapter.chapterNumber >= 0.0) {
        "Chapter ${formatChapterNumber(chapter.chapterNumber)}"
    } else {
        chapter.name
    }

private fun formatChapterNumber(number: Double): String =
    if (number % 1.0 == 0.0) number.toInt().toString() else number.toString()
