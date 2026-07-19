package reikai.presentation.novel.details

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.NavigatorAdaptiveSheet
import eu.kanade.presentation.components.relativeDateText
import eu.kanade.presentation.manga.EditCoverAction
import eu.kanade.presentation.manga.components.ChapterHeader
import eu.kanade.presentation.manga.components.DeleteChaptersDialog
import eu.kanade.presentation.manga.components.MangaBottomActionMenu
import eu.kanade.presentation.manga.components.MangaChapterListItem
import eu.kanade.presentation.manga.components.MissingChapterCountListItem
import eu.kanade.presentation.theme.TachiyomiTheme
import eu.kanade.presentation.util.Screen
import eu.kanade.presentation.util.isTabletUi
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.ui.setting.SettingsScreen
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import eu.kanade.tachiyomi.util.system.copyToClipboard
import reikai.data.coil.NovelCover
import reikai.domain.novel.NovelChapterListEntry
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.domain.novel.model.withCustomInfo
import reikai.presentation.components.EntryCoverDialog
import reikai.presentation.components.ManageMergeSourceRow
import reikai.presentation.components.ManageMergeSourcesDialog
import reikai.presentation.components.MergeSourceChips
import reikai.presentation.details.EntryDetailsScaffold
import reikai.presentation.details.EntryDetailsTwoPaneScaffold
import reikai.presentation.details.EntryDetailsUiState
import reikai.presentation.details.EntryEditInfoDialog
import reikai.presentation.details.EntryEditInfoUi
import reikai.presentation.details.EntryToolbar
import reikai.presentation.details.TrackerAutofill
import reikai.presentation.details.entryInfoItems
import reikai.presentation.details.toEntryHeader
import reikai.presentation.novel.browse.DuplicateNovelDialog
import reikai.presentation.novel.globalsearch.NovelGlobalSearchScreen
import reikai.presentation.novel.migrate.NovelMigrateHost
import reikai.presentation.novel.migrate.NovelMigrationSourcePickScreen
import reikai.presentation.novel.migrate.rememberNovelMigrateController
import reikai.presentation.novel.notes.NovelNotesScreen
import reikai.presentation.novel.reader.NovelReaderScreen
import reikai.presentation.track.EntryTrackInfoDialogHomeScreen
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen

/**
 * Light-novel details screen, the novel twin of `MangaScreen`. Mirrors the manga small/large
 * layouts: the phone toolbar fades its title + background as you scroll past the cover; the tablet
 * keeps a two-pane split with the bottom action menu anchored to the chapter pane.
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
        // Lifecycle-aware so collection pauses when the screen is not resumed (parity with MangaScreen).
        val state by screenModel.state.collectAsStateWithLifecycle()

        when (val s = state) {
            NovelDetailsState.Loading -> LoadingScreen()
            is NovelDetailsState.Failed -> Scaffold(
                topBar = { AppBar(title = null, navigateUp = navigator::pop, scrollBehavior = it) },
            ) { padding -> EmptyScreen(message = s.message, modifier = Modifier.padding(padding)) }
            is NovelDetailsState.Loaded -> TachiyomiTheme(seedColor = s.seedColor) {
                // Back clears an active chapter selection before popping the screen (mirrors MangaScreen).
                BackHandler(enabled = s.selectionMode) { screenModel.clearSelection() }
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
                // migration only re-homes a library novel, so the action shows only when favorited.
                val onMigrate: (() -> Unit)? = if (s.novel.favorite) {
                    // displayNovel = the source you're viewing, so the picker pre-checks it for a merge.
                    { navigator.push(NovelMigrationSourcePickScreen(listOf(s.displayNovel.id))) }
                } else {
                    null
                }
                // tracking action; mirrors MangaScreen (no logged-in trackers -> Settings > Tracking).
                val onTracking: () -> Unit = {
                    if (screenModel.hasLoggedInTrackers()) {
                        screenModel.showTrackDialog()
                    } else {
                        navigator.push(SettingsScreen(SettingsScreen.Destination.Tracking))
                    }
                }
                // open the full-screen notes editor for the favorited (anchor) novel.
                val onEditNotes: () -> Unit = {
                    navigator.push(NovelNotesScreen(s.novel.id, s.novel.title, s.novel.notes))
                }
                val onCopy: (String) -> Unit = { text -> context.copyToClipboard(text, text) }
                val onChapterClick: (NovelChapter) -> Unit = { chapter ->
                    // Route to the chapter's own source (a unified-list row keeps its owning novelId).
                    // The All chip opens group scope (the reader resolves + aggregates the merge group);
                    // a specific source chip opens source scope (that source's own list), matching the
                    // manga reader.
                    navigator.push(
                        NovelReaderScreen(
                            chapter.novelId,
                            chapter.id,
                            sourceScoped = s.selectedSourceNovelId != null,
                        ),
                    )
                }

                // Build the shared header/description state once (in composable scope, so the
                // merge-unified label resolves via stringResource). Metadata follows the viewed source
                // (the selected chip, else the anchor); favorite + notes stay on the anchor novel.
                val display = s.displayNovel.withCustomInfo(s.customInfo)
                val entrySourceName = if (s.mergeSources.size > 1 && s.selectedSourceNovelId == null) {
                    stringResource(MR.strings.merge_unified)
                } else {
                    s.sourceName
                }
                val entryState = EntryDetailsUiState(
                    header = display.toEntryHeader(sourceName = entrySourceName, sourceSite = s.sourceUrl),
                    favorite = s.novel.favorite,
                    trackingCount = s.trackingCount,
                    showIntervalButton = false,
                    nextUpdate = null,
                    isUserIntervalMode = false,
                    description = display.description,
                    tags = display.genre,
                    notes = s.novel.notes,
                    descriptionDefaultExpanded = false,
                )

                if (isTabletUi()) {
                    NovelDetailsLargeImpl(
                        s,
                        screenModel,
                        entryState,
                        navigator::pop,
                        onWebView,
                        onShare,
                        onMigrate,
                        onTracking,
                        onEditNotes,
                        onSearch,
                        onCopy,
                        onChapterClick,
                    )
                } else {
                    NovelDetailsSmallImpl(
                        s,
                        screenModel,
                        entryState,
                        navigator::pop,
                        onWebView,
                        onShare,
                        onMigrate,
                        onTracking,
                        onEditNotes,
                        onSearch,
                        onCopy,
                        onChapterClick,
                    )
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
    entryState: EntryDetailsUiState,
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

    EntryDetailsScaffold(
        listState = listState,
        snackbarHostState = screenModel.snackbarHostState,
        isAnySelected = state.selectionMode,
        isRefreshing = state.isRefreshing,
        onRefresh = screenModel::refresh,
        onCancelSelection = screenModel::clearSelection,
        fabVisible = state.resumeChapter != null && !state.selectionMode,
        fabIsResume = state.hasStarted,
        onFabClick = { state.resumeChapter?.let(onChapterClick) },
        topBar = { titleAlpha, backgroundAlpha ->
            NovelDetailsToolbar(
                state = state,
                screenModel = screenModel,
                onBack = onBack,
                onShare = onShare,
                onMigrate = onMigrate,
                onEditNotes = onEditNotes,
                titleAlphaProvider = titleAlpha,
                backgroundAlphaProvider = backgroundAlpha,
            )
        },
        bottomActionMenu = { NovelSelectionBar(state, screenModel, Modifier.fillMaxWidth()) },
    ) { appBarPadding ->
        entryInfoItems(
            isTabletUi = false,
            appBarPadding = appBarPadding,
            state = entryState,
            onCoverClick = screenModel::showCoverDialog,
            doSearch = { query, _ -> onSearch(query) },
            onAddToLibraryClicked = screenModel::toggleFavorite,
            onTrackingClicked = onTracking,
            // long-press favorite -> categories, only while in library (parity with manga)
            onEditCategory = screenModel::showChangeCategoryDialog.takeIf { state.novel.favorite },
            onEditIntervalClicked = null,
            onWebViewClicked = state.novelWebUrl?.let { { onWebView() } },
            onWebViewLongClicked = null,
            onShareClicked = state.novelWebUrl?.let { { onShare() } },
            onTagSearch = onSearch,
            onGlobalSearch = null,
            onCopyTagToClipboard = { onCopy(it) },
            onEditNotes = onEditNotes,
        )
        novelChapterHeaderItems(state, screenModel)
        novelChapterItems(state, screenModel, onChapterClick)
    }
}

@Composable
private fun NovelDetailsLargeImpl(
    state: NovelDetailsState.Loaded,
    screenModel: NovelDetailsScreenModel,
    entryState: EntryDetailsUiState,
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

    EntryDetailsTwoPaneScaffold(
        chapterListState = chapterListState,
        snackbarHostState = screenModel.snackbarHostState,
        isAnySelected = state.selectionMode,
        isRefreshing = state.isRefreshing,
        onRefresh = screenModel::refresh,
        onCancelSelection = screenModel::clearSelection,
        fabVisible = state.resumeChapter != null && !state.selectionMode,
        fabIsResume = state.hasStarted,
        onFabClick = { state.resumeChapter?.let(onChapterClick) },
        topBar = { modifier ->
            NovelDetailsToolbar(
                modifier = modifier,
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
        bottomActionMenu = { NovelSelectionBar(state, screenModel, Modifier.fillMaxWidth(0.5f)) },
        startContent = { appBarPadding ->
            entryInfoItems(
                isTabletUi = true,
                appBarPadding = appBarPadding,
                state = entryState,
                onCoverClick = screenModel::showCoverDialog,
                doSearch = { query, _ -> onSearch(query) },
                onAddToLibraryClicked = screenModel::toggleFavorite,
                onTrackingClicked = onTracking,
                onEditCategory = screenModel::showChangeCategoryDialog.takeIf { state.novel.favorite },
                onEditIntervalClicked = null,
                onWebViewClicked = state.novelWebUrl?.let { { onWebView() } },
                onWebViewLongClicked = null,
                onShareClicked = state.novelWebUrl?.let { { onShare() } },
                onTagSearch = onSearch,
                onGlobalSearch = null,
                onCopyTagToClipboard = { onCopy(it) },
                onEditNotes = onEditNotes,
            )
        },
        endContent = {
            novelChapterHeaderItems(state, screenModel)
            novelChapterItems(state, screenModel, onChapterClick)
        },
    )
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
    modifier: Modifier = Modifier,
) {
    EntryToolbar(
        modifier = modifier,
        title = state.novel.title,
        hasFilters = state.readFilter != 0L || state.bookmarkedFilter != 0L || state.downloadedFilter != 0L,
        navigateUp = onBack,
        onClickFilter = screenModel::showChapterSettingsDialog,
        onClickRefresh = screenModel::refresh,
        onClickEditCategory = screenModel::showChangeCategoryDialog,
        // Editing display info is a library action (parity with manga): the overflow item hides
        // until the novel is favorited, matching MangaScreen's takeIf { manga.favorite } gate.
        onClickEditInfo = screenModel::showEditNovelInfoDialog.takeIf { state.novel.favorite },
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

@Composable
private fun NovelSelectionBar(
    state: NovelDetailsState.Loaded,
    screenModel: NovelDetailsScreenModel,
    modifier: Modifier,
) {
    // Only offer the actions that apply to the current selection, mirroring the manga selection bar
    // (SharedMangaBottomActionMenu) instead of always showing every icon.
    val selected = state.chapters.filter { it.id in state.selection }
    fun isDownloaded(chapter: NovelChapter): Boolean =
        state.downloadStateOf(chapter.id) == Download.State.DOWNLOADED

    MangaBottomActionMenu(
        visible = state.selectionMode,
        modifier = modifier,
        onBookmarkClicked = { screenModel.bookmarkSelected(true) }
            .takeIf { selected.any { !it.bookmark } },
        onRemoveBookmarkClicked = { screenModel.bookmarkSelected(false) }
            .takeIf { selected.isNotEmpty() && selected.all { it.bookmark } },
        onMarkAsReadClicked = { screenModel.markSelectedRead(true) }
            .takeIf { selected.any { !it.read } },
        onMarkAsUnreadClicked = { screenModel.markSelectedRead(false) }
            .takeIf { selected.any { it.read || it.lastTextProgress > 0L } },
        onMarkPreviousAsReadClicked = { screenModel.markPreviousRead(true) }
            .takeIf { selected.size == 1 },
        onDownloadClicked = { screenModel.downloadSelected() }
            .takeIf { selected.any { !isDownloaded(it) } },
        onDeleteClicked = { screenModel.deleteSelected() }
            .takeIf { selected.any { isDownloaded(it) } },
    )
}

@Composable
private fun Screen.NovelDetailsDialogs(state: NovelDetailsState.Loaded, screenModel: NovelDetailsScreenModel) {
    val navigator = LocalNavigator.currentOrThrow
    val migrateController = rememberNovelMigrateController()
    when (val dialog = state.dialog) {
        is NovelDetailsDialog.ChangeCategory -> NovelCategoryDialog(
            dialog = dialog,
            onDismiss = screenModel::dismissDialog,
            onConfirm = screenModel::applyCategories,
        )
        is NovelDetailsDialog.DuplicateNovel -> DuplicateNovelDialog(
            duplicates = dialog.duplicates,
            sourceNames = dialog.sourceNames,
            sourceSites = dialog.sourceSites,
            onDismissRequest = screenModel::dismissDialog,
            onConfirm = screenModel::addFavoriteAnyway,
            onOpenNovel = { navigator.push(NovelScreen(it.source, it.url)) },
            onMigrate = { migrateController.start(current = it, target = state.novel) },
            groupIdByNovelId = dialog.groupIdByNovelId,
            onAddToGroup = { selectedIds: List<Long> ->
                screenModel.addToExistingGroup(selectedIds)
            }.takeIf { dialog.suggestGroup },
        )
        NovelDetailsDialog.EditInfo -> EntryEditInfoDialog(
            initial = state.displayNovel.withCustomInfo(state.customInfo).toEntryEditInfoUi(),
            source = state.novel.toEntryEditInfoUi(),
            seedColor = state.seedColor,
            coverModel = { url ->
                NovelCover(
                    url = url.ifBlank { null },
                    site = state.sourceUrl,
                    isNovelFavorite = state.novel.favorite,
                    lastModified = state.novel.coverLastModified,
                    novelId = state.novel.id,
                )
            },
            onDismissRequest = screenModel::dismissDialog,
            onSave = { screenModel.saveNovelInfo(it) },
            onResetAll = screenModel::resetNovelInfo,
            autofill = TrackerAutofill(
                candidates = screenModel::autofillCandidates,
                fetch = screenModel::fetchTrackerMetadata,
            ),
        )
        is NovelDetailsDialog.DeleteChapters -> DeleteChaptersDialog(
            onDismissRequest = screenModel::dismissDialog,
            onConfirm = { screenModel.deleteChapters(dialog.chapters) },
        )
        NovelDetailsDialog.ChapterSettings -> NovelChapterSettingsDialog(
            sorting = state.sorting,
            sortDescending = state.sortDescending,
            readFilter = state.readFilter,
            bookmarkedFilter = state.bookmarkedFilter,
            downloadedFilter = state.downloadedFilter,
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
        is NovelDetailsDialog.ManageSources -> ManageMergeSourcesDialog(
            sources = dialog.sources.map {
                ManageMergeSourceRow(
                    id = it.novelId,
                    sourceName = it.sourceName,
                    // The novel coverage hint: how many chapters this source contributes.
                    subtitle = pluralStringResource(MR.plurals.manga_num_chapters, it.chapterCount, it.chapterCount),
                )
            },
            isOverridden = dialog.isOverridden,
            onDismissRequest = screenModel::dismissDialog,
            onReorder = screenModel::reorderSources,
            onResetOrder = screenModel::resetSourceOrder,
            onSplit = screenModel::splitSources,
            onRemoveFromLibrary = screenModel::removeSourcesFromLibrary,
            onRemoveAll = screenModel::removeAllSourcesFromLibrary,
        )
        // tracking sheet. Remember by novel id so the merge collectors' frequent
        // recompositions don't rebuild it and reset its navigator mid-write (the manga side hit an
        // InsertTrack JobCancellationException here).
        NovelDetailsDialog.TrackSheet -> {
            val trackScreen = remember(state.novel.id) {
                EntryTrackInfoDialogHomeScreen(
                    entryId = state.novel.id,
                    entryTitle = state.novel.title,
                    sourceId = null,
                    isNovel = true,
                )
            }
            NavigatorAdaptiveSheet(
                screen = trackScreen,
                enableSwipeDismiss = { it.lastItem is EntryTrackInfoDialogHomeScreen },
                onDismissRequest = screenModel::dismissDialog,
            )
        }
        // Rendered by NovelCoverDialogHost: rememberScreenModel needs the Screen receiver, absent here.
        NovelDetailsDialog.FullCover -> Unit
        null -> {}
    }
    NovelMigrateHost(migrateController)
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
        EntryCoverDialog(
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

/** Source-switcher chips (merged only) + the chapter header + page bar. Sits atop the chapter pane on
 *  tablet and between the info and chapters on phone, mirroring MangaScreen's chip placement. */
private fun LazyListScope.novelChapterHeaderItems(
    state: NovelDetailsState.Loaded,
    screenModel: NovelDetailsScreenModel,
) {
    if (state.mergeSources.size > 1) {
        item(key = "source-chips") {
            MergeSourceChips(
                sources = state.mergeSources.map {
                    ManageMergeSourceRow(id = it.novelId, sourceName = it.sourceName)
                },
                selectedId = state.selectedSourceNovelId,
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
                missingChapterCount = state.missingChapterCount,
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
    // The list interleaves chapters with "N missing chapters" separators (built in the ScreenModel and
    // gated by the hide-missing pref), so render per entry rather than straight over the chapter list.
    state.chapterListEntries.forEach { entry ->
        when (entry) {
            is NovelChapterListEntry.Missing -> item(key = "missing-${entry.id}") {
                MissingChapterCountListItem(entry.count)
            }
            is NovelChapterListEntry.Item -> item(key = "chapter-${entry.chapter.id}") {
                NovelChapterRow(
                    chapter = entry.chapter,
                    state = state,
                    screenModel = screenModel,
                    showSource = showSource,
                    sourceNames = sourceNames,
                    onChapterClick = onChapterClick,
                )
            }
        }
    }
}

@Composable
private fun NovelChapterRow(
    chapter: NovelChapter,
    state: NovelDetailsState.Loaded,
    screenModel: NovelDetailsScreenModel,
    showSource: Boolean,
    sourceNames: Map<Long, String>,
    onChapterClick: (NovelChapter) -> Unit,
) {
    MangaChapterListItem(
        title = chapterTitle(chapter, state.hideChapterTitles),
        date = chapter.dateUpload.takeIf { it > 0L }?.let { relativeDateText(it) },
        readProgress = (chapter.lastTextProgress / 100L).toInt().takeIf { !chapter.read && it > 0 }?.let { "$it%" },
        scanlator = sourceNames[chapter.novelId],
        read = chapter.read,
        bookmark = chapter.bookmark,
        selected = chapter.id in state.selection,
        downloadIndicatorEnabled = !state.selectionMode,
        downloadStateProvider = { state.downloadStateOf(chapter.id) },
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

private fun chapterTitle(chapter: NovelChapter, hideTitles: Boolean): String =
    if (hideTitles && chapter.chapterNumber >= 0.0) {
        "Chapter ${formatChapterNumber(chapter.chapterNumber)}"
    } else {
        chapter.name
    }

private fun formatChapterNumber(number: Double): String =
    if (number % 1.0 == 0.0) number.toInt().toString() else number.toString()

/** Seed the shared edit-info dialog from a novel's effective (edited) values. */
private fun Novel.toEntryEditInfoUi() = EntryEditInfoUi(
    title = title,
    author = author.orEmpty(),
    artist = artist.orEmpty(),
    description = description.orEmpty(),
    genre = genre.orEmpty(),
    status = status,
    thumbnailUrl = thumbnailUrl.orEmpty(),
)
