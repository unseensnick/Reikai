package yokai.presentation.novel.details

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.BookmarkRemove
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.RemoveDone
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.ui.reader.compose.ReaderController
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import eu.kanade.tachiyomi.util.chapter.ChapterUtil
import eu.kanade.tachiyomi.util.compose.LocalBackPress
import eu.kanade.tachiyomi.util.compose.LocalRouter
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.view.withFadeTransaction
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import yokai.data.novel.NovelStatusCode
import yokai.domain.novel.models.NovelChapter
import yokai.novel.install.LnPluginInstaller
import yokai.novel.source.NovelSource
import yokai.novel.source.NovelSourceManager
import yokai.presentation.component.ReikaiTopBar
import yokai.presentation.details.ChangeCategoryDialog
import yokai.presentation.details.CoverDialog
import yokai.presentation.details.DetailsChapterRow
import yokai.presentation.details.DetailsDownloadState
import yokai.presentation.details.EditInfoDialog
import yokai.presentation.details.DetailsContent
import yokai.presentation.details.DetailsFilterSortSheet
import yokai.presentation.core.util.shouldExpandFAB
import yokai.presentation.details.DetailsSourceTab
import yokai.presentation.details.HandleDetailsEvents
import yokai.presentation.details.detailsResumeLabel
import yokai.presentation.details.ManageSourcesDialog
import yokai.presentation.library.components.SelectionAction
import yokai.presentation.library.components.SelectionAppBar
import yokai.util.Screen

/**
 * Database-first details for a saved (library) novel (Phase 7). Renders the stored novel + chapters
 * from the DB via [NovelDetailsScreenModel] (works offline, shows read/bookmark); the source plugin is
 * hit only on the first-ever open or an explicit refresh. New chapters arrive in the background via
 * `NovelUpdateJob` and surface through the reactive chapter Flow, mirroring the manga side.
 *
 * The plugin host needs an Activity Context and outlives configuration changes in the ScreenModel,
 * so it (and source resolution) live here in the composable; the resolved source is handed to the
 * ScreenModel via [NovelDetailsScreenModel.onSourceReady].
 */
class NovelDetailsScreen(
    private val sourceId: String,
    private val novelUrl: String,
) : Screen() {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val installer = remember { Injekt.get<LnPluginInstaller>() }
        val manager = remember { Injekt.get<NovelSourceManager>() }
        val backPress = LocalBackPress.current
        val navigator = LocalNavigator.current
        val router = LocalRouter.current
        val scope = rememberCoroutineScope()
        val listState = rememberLazyListState()
        // Top bar fades from transparent (over the header backdrop) to opaque as the header scrolls
        // away, mirroring the manga details screen. Read inside the topBar so only the bar recomposes.
        val barFraction by remember {
            derivedStateOf {
                if (listState.firstVisibleItemIndex > 0) {
                    1f
                } else {
                    (listState.firstVisibleItemScrollOffset / 500f).coerceIn(0f, 1f)
                }
            }
        }

        val screenModel = rememberScreenModel { NovelDetailsScreenModel(sourceId, novelUrl) }
        val state by screenModel.state.collectAsState()
        val chapterSwipeEnabled = remember { screenModel.isChapterSwipeEnabled() }
        val clipboard = LocalClipboardManager.current

        // Resolve the source once and hand it to the ScreenModel.
        var resolvedSource by remember { mutableStateOf<NovelSource?>(null) }
        LaunchedEffect(sourceId, novelUrl) {
            try {
                installer.ensureLoaded()
                val source = manager.get(sourceId)
                    ?: throw IllegalStateException("source not installed: $sourceId")
                resolvedSource = source
                screenModel.onSourceReady(source)
            } catch (e: Throwable) {
                screenModel.onSourceFailed("${e.javaClass.simpleName}: ${e.message ?: ""}")
            }
        }

        // Chapter search (composable-local, survives process death) + mark-all confirmation.
        var searchActive by rememberSaveable { mutableStateOf(false) }
        var searchQuery by rememberSaveable { mutableStateOf("") }
        val searchFocus = remember { FocusRequester() }
        var confirmMarkAll by remember { mutableStateOf(false) }
        var overflowOpen by remember { mutableStateOf(false) }
        var downloadMenuOpen by remember { mutableStateOf(false) }
        var showSheet by remember { mutableStateOf(false) }
        // A grouped source pending long-press removal from the group (confirmation gate).
        var sourceToRemove by remember { mutableStateOf<Long?>(null) }
        var showCoverDialog by remember { mutableStateOf(false) }

        fun openChapter(chapter: NovelChapter) {
            val id = chapter.id ?: return
            router?.pushController(
                ReaderController(screenModel.orderedChapterIds(), id).withFadeTransaction(),
            )
        }

        fun goBack() {
            backPress?.invoke()
        }

        val title = when {
            state is NovelDetailsState.Loaded -> (state as NovelDetailsState.Loaded).novel.title
            state is NovelDetailsState.Failed -> "Error"
            else -> "Loading…"
        }
        // Only the Loaded details body draws the cover backdrop, so only then should the bar go
        // transparent; loading / error keep an opaque bar.
        val showBackdrop = state is NovelDetailsState.Loaded
        val loaded = state as? NovelDetailsState.Loaded
        val selectionActive = loaded?.selection?.isNotEmpty() == true
        val allRead = loaded?.chapters?.let { it.isNotEmpty() && it.all { c -> c.read } } == true

        LaunchedEffect(searchActive) { if (searchActive) searchFocus.requestFocus() }

        // Back priority (last enabled handler wins): clear selection, then close search, before the
        // default pop to the library.
        BackHandler(enabled = searchActive) { searchActive = false; searchQuery = "" }
        BackHandler(enabled = selectionActive) { screenModel.clearSelection() }

        val snackbarHostState = remember { SnackbarHostState() }
        // One-shot screen effects from the ScreenModel (undo snackbars, cover share, post-split
        // navigation to a sibling source). Shared with the manga screen.
        HandleDetailsEvents(
            events = screenModel.events,
            snackbarHostState = snackbarHostState,
            context = context,
            onNavigateToSibling = { id ->
                screenModel.siblingRoute(id)?.let { (src, url) -> navigator?.replace(NovelDetailsScreen(src, url)) }
            },
        )

        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                if (selectionActive) {
                    val selectedChapters = loaded?.chapters?.filter { it.id in loaded.selection }.orEmpty()
                    val allBookmarked = selectedChapters.isNotEmpty() && selectedChapters.all { it.bookmark }
                    val allReadSelected = selectedChapters.isNotEmpty() && selectedChapters.all { it.read }
                    val allDownloadedSelected = selectedChapters.isNotEmpty() && selectedChapters.all { it.isDownloaded }
                    // Only offer Unhide when every selected row is already hidden (reachable via the
                    // "Show hidden chapters" view); otherwise the action hides them.
                    val allHiddenSelected = loaded?.showHidden == true && selectedChapters.isNotEmpty() &&
                        selectedChapters.all { it.id in loaded.hiddenChapterIds }
                    SelectionAppBar(
                        selectionCount = loaded?.selection?.size ?: 0,
                        onClose = { screenModel.clearSelection() },
                        colors = TopAppBarDefaults.topAppBarColors(),
                        actions = buildList {
                            // Stateful read toggle: flips to "unread" once every selected chapter is read
                            // (mirrors the bookmark action). A dedicated overflow "Mark as unread" is added
                            // below for mixed/none-read selections, where this icon shows "Mark as read".
                            add(
                                if (allReadSelected) {
                                    SelectionAction("Mark as unread", icon = Icons.Outlined.RemoveDone) { screenModel.markSelectedRead(false) }
                                } else {
                                    SelectionAction("Mark as read", icon = Icons.Outlined.Done) { screenModel.markSelectedRead(true) }
                                },
                            )
                            add(
                                if (allBookmarked) {
                                    SelectionAction("Remove bookmark", icon = Icons.Outlined.BookmarkRemove) { screenModel.bookmarkSelected(false) }
                                } else {
                                    SelectionAction("Bookmark", icon = Icons.Outlined.BookmarkAdd) { screenModel.bookmarkSelected(true) }
                                },
                            )
                            add(
                                if (allDownloadedSelected) {
                                    SelectionAction("Delete download", icon = Icons.Outlined.Delete) { screenModel.deleteSelectedDownloads() }
                                } else {
                                    SelectionAction("Download", icon = Icons.Outlined.Download) { screenModel.downloadSelected() }
                                },
                            )
                            if (!allReadSelected) add(SelectionAction("Mark as unread") { screenModel.markSelectedRead(false) })
                            add(SelectionAction("Mark previous as read") { screenModel.markPreviousRead(true) })
                            add(SelectionAction("Mark previous as unread") { screenModel.markPreviousRead(false) })
                            if (allHiddenSelected) {
                                add(SelectionAction("Unhide") { screenModel.unhideSelected() })
                            } else {
                                add(SelectionAction("Hide") { screenModel.hideSelected() })
                            }
                            add(SelectionAction("Select all") { screenModel.selectAll() })
                            add(SelectionAction("Invert selection") { screenModel.invertSelection() })
                        },
                    )
                } else {
                    val barAlpha = if (showBackdrop) barFraction else 1f
                    ReikaiTopBar(
                        title = {
                            if (searchActive) {
                                TextField(
                                    value = searchQuery,
                                    onValueChange = { searchQuery = it },
                                    placeholder = { Text("Search chapters") },
                                    singleLine = true,
                                    colors = TextFieldDefaults.colors(
                                        focusedContainerColor = Color.Transparent,
                                        unfocusedContainerColor = Color.Transparent,
                                        focusedIndicatorColor = Color.Transparent,
                                        unfocusedIndicatorColor = Color.Transparent,
                                    ),
                                    modifier = Modifier.fillMaxWidth().focusRequester(searchFocus),
                                )
                            } else {
                                Text(
                                    title,
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.alpha(barAlpha),
                                )
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = {
                                if (searchActive) { searchActive = false; searchQuery = "" } else goBack()
                            }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        },
                        actions = {
                            when {
                                searchActive -> if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(Icons.Outlined.Clear, contentDescription = "Clear")
                                    }
                                }
                                loaded != null -> {
                                    IconButton(onClick = { searchActive = true }) {
                                        Icon(Icons.Outlined.Search, contentDescription = "Search chapters")
                                    }
                                    // Box-wrapped so the menu anchors to the icon (a bare DropdownMenu
                                    // in the actions RowScope mis-anchors).
                                    Box {
                                        IconButton(onClick = { downloadMenuOpen = true }) {
                                            Icon(Icons.Outlined.Download, contentDescription = "Download")
                                        }
                                        DropdownMenu(
                                            expanded = downloadMenuOpen,
                                            onDismissRequest = { downloadMenuOpen = false },
                                            containerColor = MaterialTheme.colorScheme.surface,
                                            tonalElevation = 0.dp,
                                        ) {
                                            if (loaded.downloads.isNotEmpty()) {
                                                DropdownMenuItem(
                                                    text = { Text("Cancel downloads") },
                                                    onClick = { downloadMenuOpen = false; screenModel.cancelDownloads() },
                                                )
                                            }
                                            DropdownMenuItem(
                                                text = { Text("Download next") },
                                                onClick = { downloadMenuOpen = false; screenModel.downloadNext(1) },
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Download next 5") },
                                                onClick = { downloadMenuOpen = false; screenModel.downloadNext(5) },
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Download next 10") },
                                                onClick = { downloadMenuOpen = false; screenModel.downloadNext(10) },
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Download unread") },
                                                onClick = { downloadMenuOpen = false; screenModel.downloadUnread() },
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Download all") },
                                                onClick = { downloadMenuOpen = false; screenModel.downloadAll() },
                                            )
                                        }
                                    }
                                    IconButton(onClick = { confirmMarkAll = true }) {
                                        Icon(
                                            if (allRead) Icons.Outlined.RemoveDone else Icons.Outlined.DoneAll,
                                            contentDescription = if (allRead) "Mark all as unread" else "Mark all as read",
                                        )
                                    }
                                    // Box-wrapped so the menu anchors to the icon (a bare DropdownMenu
                                    // in the actions RowScope mis-anchors).
                                    Box {
                                        IconButton(onClick = { overflowOpen = true }) {
                                            Icon(Icons.Filled.MoreVert, contentDescription = "More")
                                        }
                                        DropdownMenu(
                                            expanded = overflowOpen,
                                            onDismissRequest = { overflowOpen = false },
                                            containerColor = MaterialTheme.colorScheme.surface,
                                            tonalElevation = 0.dp,
                                        ) {
                                            if (loaded.novel.favorite) {
                                                DropdownMenuItem(
                                                    text = { Text("Edit categories") },
                                                    onClick = { overflowOpen = false; screenModel.showChangeCategoryDialog() },
                                                )
                                            }
                                            DropdownMenuItem(
                                                text = { Text("Edit info") },
                                                onClick = { overflowOpen = false; screenModel.showEditNovelInfoDialog() },
                                            )
                                            if (loaded.sourceTabs.size > 1) {
                                                DropdownMenuItem(
                                                    text = { Text("Manage sources") },
                                                    onClick = { overflowOpen = false; screenModel.showManageSourcesDialog() },
                                                )
                                            }
                                            if (loaded.hasHiddenChapters || loaded.showHidden) {
                                                DropdownMenuItem(
                                                    text = { Text(if (loaded.showHidden) "Hide hidden chapters" else "Show hidden chapters") },
                                                    onClick = { overflowOpen = false; screenModel.toggleShowHidden() },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = barAlpha),
                            scrolledContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = barAlpha),
                            titleContentColor = MaterialTheme.colorScheme.onSurface,
                            navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                            actionIconContentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                    )
                }
            },
            floatingActionButton = {
                val resume = loaded?.resumeChapter
                // When everything is read the FAB re-reads the most recent chapter (matches manga).
                val fabTarget = resume ?: loaded?.chapters?.maxByOrNull { it.chapterNumber }
                if (loaded != null && fabTarget != null && !selectionActive) {
                    val label = detailsResumeLabel(context, resume?.chapterNumber, (resume?.lastTextProgress ?: 0) > 0)
                    ExtendedFloatingActionButton(
                        text = { Text(label) },
                        icon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                        expanded = listState.shouldExpandFAB(),
                        onClick = { openChapter(fabTarget) },
                        containerColor = MaterialTheme.colorScheme.primary,
                    )
                }
            },
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize()) {
                when (val s = state) {
                    is NovelDetailsState.Loading -> Box(
                        modifier = Modifier.fillMaxSize().padding(padding),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                    is NovelDetailsState.Loaded -> {
                        LaunchedEffect(s.novel.id) { screenModel.loadAccentColor() }
                        val pullRefreshState = rememberPullToRefreshState()
                        PullToRefreshBox(
                            isRefreshing = s.isRefreshing,
                            onRefresh = { screenModel.refresh() },
                            state = pullRefreshState,
                            indicator = {
                                PullToRefreshDefaults.Indicator(
                                    modifier = Modifier.align(Alignment.TopCenter),
                                    isRefreshing = s.isRefreshing,
                                    state = pullRefreshState,
                                )
                            },
                        ) {
                            val rows = remember(s.chapters, s.selection, searchQuery, s.hideChapterTitles, s.hiddenChapterIds, s.downloads) {
                                s.chapters.mapNotNull { ch ->
                                    val displayName = if (s.hideChapterTitles && ch.chapterNumber > 0f) {
                                        val num = ch.chapterNumber
                                        "Chapter " + (if (num % 1f == 0f) num.toInt().toString() else num.toString())
                                    } else {
                                        ch.name
                                    }
                                    if (searchQuery.isNotBlank() &&
                                        !displayName.contains(searchQuery, ignoreCase = true)
                                    ) {
                                        return@mapNotNull null
                                    }
                                    DetailsChapterRow(
                                        id = ch.id ?: 0L,
                                        name = displayName,
                                        read = ch.read,
                                        bookmark = ch.bookmark,
                                        downloadState = s.downloads[ch.id]
                                            ?: if (ch.isDownloaded) DetailsDownloadState.DOWNLOADED else DetailsDownloadState.NONE,
                                        selected = ch.id in s.selection,
                                        dimmed = ch.id in s.hiddenChapterIds,
                                        date = ChapterUtil.relativeDate(ch.dateUpload),
                                        // lastTextProgress is hundredths-of-a-percent (0..10000).
                                        readProgress = if (!ch.read && ch.lastTextProgress > 0) {
                                            "${ch.lastTextProgress / 100}% read"
                                        } else {
                                            null
                                        },
                                    )
                                }
                            }
                            DetailsContent(
                                // Header metadata follows the viewed source (displayNovel); favorite
                                // + identity below still key off the anchor (s.novel).
                                coverData = s.displayNovel.thumbnailUrl,
                                title = s.displayNovel.title,
                                author = s.displayNovel.author,
                                artist = s.displayNovel.artist,
                                status = s.displayStatus,
                                statusText = statusLabel(s.displayStatus),
                                sourceName = s.sourceLabel,
                                isStubSource = false,
                                accentColor = s.accentColor?.let { Color(it) },
                                onCoverClick = { showCoverDialog = true },
                                description = s.displayNovel.description,
                                genres = s.displayNovel.genres.orEmpty(),
                                chapters = rows,
                                onChapterClick = { id ->
                                    s.chapters.find { it.id == id }?.let { openChapter(it) }
                                },
                                // Novels keep the simple single-tap toggle (no Start-now/Cancel menu).
                                onDownloadClick = { id, action -> screenModel.downloadAction(id, action) },
                                downloadMenuEnabled = true,
                                selectionActive = s.selection.isNotEmpty(),
                                onToggleSelection = { id, sel, long -> screenModel.toggleSelection(id, sel, long) },
                                onRangeSelect = { a, b -> screenModel.selectRange(a, b) },
                                chapterSwipeEnabled = chapterSwipeEnabled,
                                onSwipeToRead = { screenModel.toggleChapterRead(it) },
                                onSwipeToBookmark = { screenModel.toggleChapterBookmark(it) },
                                onCopy = { clipboard.setText(AnnotatedString(it)) },
                                onFilterClick = { showSheet = true },
                                listState = listState,
                                topInset = padding.calculateTopPadding(),
                                bottomInset = padding.calculateBottomPadding(),
                                isFavorited = s.novel.favorite,
                                onFavoriteClick = { screenModel.toggleFavorite() },
                                onEditCategoryClick = if (s.novel.favorite) {
                                    { screenModel.showChangeCategoryDialog() }
                                } else {
                                    null
                                },
                                onRemoveAllSources = if (s.novel.favorite && s.sourceTabs.size > 1) {
                                    { screenModel.removeAllSourcesFromLibrary() }
                                } else {
                                    null
                                },
                                onWebViewClick = {
                                    scope.launch {
                                        val url = screenModel.novelWebUrl() ?: return@launch
                                        context.startActivity(
                                            WebViewActivity.newIntent(context, url, null, resolvedSource?.name ?: ""),
                                        )
                                    }
                                },
                                onShareClick = {
                                    scope.launch {
                                        val url = screenModel.novelWebUrl() ?: return@launch
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
                                },
                                sourceTabs = s.sourceTabs.map { DetailsSourceTab(it.novelId, it.label) },
                                selectedSourceView = s.sourceView,
                                onSourceViewChange = { screenModel.setSourceView(it) },
                                onSourceRemove = { sourceToRemove = it },
                                currentSourceId = s.novel.id,
                            )
                        }
                    }
                    is NovelDetailsState.Failed -> Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(16.dp),
                    ) { FailedBody(message = s.message, source = resolvedSource, context = context) }
                }
            }
        }

        (state as? NovelDetailsState.Loaded)?.dialog?.let { dialog ->
            when (dialog) {
                is NovelDetailsDialog.ChangeCategory -> ChangeCategoryDialog(
                    allCategories = dialog.allCategories,
                    currentCategoryIds = dialog.currentCategoryIds,
                    onDismiss = { screenModel.dismissDialog() },
                    onConfirm = { screenModel.applyCategories(it) },
                )
                is NovelDetailsDialog.EditInfo -> EditInfoDialog(
                    initialTitle = dialog.title,
                    initialAuthor = dialog.author,
                    initialArtist = dialog.artist,
                    initialDescription = dialog.description,
                    initialGenre = dialog.genre,
                    statusOptions = remember { (0..6).map { it to (statusLabel(it) ?: "Unknown") } },
                    initialStatus = dialog.status,
                    onReset = { screenModel.resetNovelInfo() },
                    onDismiss = { screenModel.dismissDialog() },
                    onConfirm = { title, author, artist, description, genre, status ->
                        screenModel.updateNovelInfo(title, author, artist, description, genre, status)
                    },
                )
                is NovelDetailsDialog.ManageSources -> ManageSourcesDialog(
                    sources = dialog.sources,
                    onSplit = { screenModel.splitSources(it) },
                    onRemoveFromLibrary = { screenModel.removeSourcesFromLibrary(it) },
                    onDismiss = { screenModel.dismissDialog() },
                )
                is NovelDetailsDialog.ConfirmRemovedDownloads -> {
                    val count = dialog.chapters.size
                    AlertDialog(
                        onDismissRequest = { screenModel.dismissDialog() },
                        title = { Text("Chapters removed") },
                        text = {
                            Text(
                                if (count == 1) "1 downloaded chapter was removed from the source. Delete it from your device?"
                                else "$count downloaded chapters were removed from the source. Delete them from your device?",
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = { screenModel.deleteRemovedDownloads(dialog.chapters) }) { Text("Delete") }
                        },
                        dismissButton = {
                            TextButton(onClick = { screenModel.dismissDialog() }) { Text("Cancel") }
                        },
                    )
                }
            }
        }

        // Long-press a source chip to split it out of the group (confirmation gate).
        sourceToRemove?.let { id ->
            val name = loaded?.sourceTabs?.find { it.novelId == id }?.label.orEmpty()
            AlertDialog(
                onDismissRequest = { sourceToRemove = null },
                text = { Text("Remove $name from this group?") },
                confirmButton = {
                    TextButton(onClick = { sourceToRemove = null; screenModel.splitSources(listOf(id)) }) {
                        Text("Remove")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { sourceToRemove = null }) { Text("Cancel") }
                },
            )
        }

        if (showCoverDialog && loaded != null) {
            // No custom-cover edit/reset for novels (no novel CoverCache); zoom + save + share only.
            CoverDialog(
                coverData = loaded.displayNovel.thumbnailUrl,
                isCustomCover = false,
                canEdit = false,
                onShare = { screenModel.shareCover() },
                onSave = { screenModel.saveCover() },
                onEditCover = {},
                onResetCover = {},
                onDismiss = { showCoverDialog = false },
            )
        }

        if (showSheet && loaded != null) {
            DetailsFilterSortSheet(
                readFilter = loaded.readFilter,
                downloadedFilter = Manga.SHOW_ALL,
                bookmarkedFilter = loaded.bookmarkedFilter,
                hideChapterTitles = loaded.hideChapterTitles,
                showDownloadedFilter = false,
                onFiltersChanged = { read, _, bookmarked -> screenModel.setFilters(read, bookmarked) },
                onHideChapterTitlesChanged = { screenModel.setHideChapterTitles(it) },
                onSetFilterDefault = { screenModel.setGlobalFilters() },
                onResetFilterDefault = { screenModel.resetFilterToDefault() },
                sorting = loaded.sorting,
                sortDescending = loaded.sortDescending,
                onSortChanged = { sort, descend -> screenModel.setSortOrder(sort, descend) },
                onSetSortDefault = { screenModel.setGlobalSort() },
                onResetSortDefault = { screenModel.resetSortToDefault() },
                allScanlators = emptySet(),
                filteredScanlators = emptySet(),
                onScanlatorFilterChanged = {},
                onDismiss = { showSheet = false },
            )
        }

        if (confirmMarkAll) {
            val markRead = !allRead
            AlertDialog(
                onDismissRequest = { confirmMarkAll = false },
                title = { Text(if (markRead) "Mark all as read?" else "Mark all as unread?") },
                confirmButton = {
                    TextButton(onClick = { confirmMarkAll = false; screenModel.markAllRead(markRead) }) { Text("OK") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmMarkAll = false }) { Text("Cancel") }
                },
            )
        }
    }
}

@Composable
private fun FailedBody(message: String, source: NovelSource?, context: android.content.Context) {
    Text(text = message, color = MaterialTheme.colorScheme.error)
    source?.site?.takeIf { it.isNotBlank() }?.let { siteUrl ->
        Spacer(Modifier.height(4.dp))
        OutlinedButton(onClick = {
            context.startActivity(WebViewActivity.newIntent(context, siteUrl, null, source.name))
        }) { Text("Open ${source.name} in WebView") }
    }
    Spacer(Modifier.height(8.dp))
    Text(
        text = "If the source was uninstalled, reinstall it from Debug, LN plugin repo browse.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun statusLabel(status: Int): String? = when (status) {
    NovelStatusCode.ONGOING -> "Ongoing"
    NovelStatusCode.COMPLETED -> "Completed"
    NovelStatusCode.LICENSED -> "Licensed"
    NovelStatusCode.PUBLISHING_FINISHED -> "Publishing finished"
    NovelStatusCode.CANCELLED -> "Cancelled"
    NovelStatusCode.ON_HIATUS -> "On hiatus"
    else -> null
}
