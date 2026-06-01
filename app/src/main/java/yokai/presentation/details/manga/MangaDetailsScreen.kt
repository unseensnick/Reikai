package yokai.presentation.details.manga

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.BookmarkAdd
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.recommendation.RECOMMENDS_SOURCE
import eu.kanade.tachiyomi.ui.manga.related.browse.RelatedMangasBrowseController
import eu.kanade.tachiyomi.ui.migration.manga.design.PreMigrationController
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.source.globalsearch.GlobalSearchController
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import eu.kanade.tachiyomi.util.chapter.ChapterUtil.Companion.preferredChapterName
import eu.kanade.tachiyomi.util.compose.LocalBackPress
import eu.kanade.tachiyomi.util.compose.LocalRouter
import eu.kanade.tachiyomi.util.compose.currentOrThrow
import eu.kanade.tachiyomi.util.isLocal
import eu.kanade.tachiyomi.util.mapStatus
import eu.kanade.tachiyomi.util.view.withFadeTransaction
import kotlinx.coroutines.launch
import yokai.domain.manga.models.MangaCover
import yokai.domain.manga.models.cover
import yokai.presentation.component.ReikaiTopBar
import yokai.presentation.details.ChangeCategoryDialog
import yokai.presentation.details.DetailsChapterRow
import yokai.presentation.details.DetailsContent
import yokai.presentation.details.DetailsDownloadState
import yokai.presentation.details.DetailsFilterSortSheet
import yokai.presentation.details.DetailsRelatedItem
import yokai.presentation.details.track.TrackInfoDialog
import yokai.presentation.details.track.TrackInfoScreenModel
import yokai.presentation.library.components.SelectionAction
import yokai.presentation.library.components.SelectionAppBar
import yokai.util.Screen

class MangaDetailsScreen(private val mangaId: Long) : Screen() {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val screenModel = rememberScreenModel { MangaDetailsScreenModel(mangaId) }
        val state by screenModel.state.collectAsState()
        val trackModel = rememberScreenModel { TrackInfoScreenModel(mangaId) }
        val trackState by trackModel.state.collectAsState()
        val backPress = LocalBackPress.current
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val router = LocalRouter.current
        val scope = rememberCoroutineScope()
        val listState = rememberLazyListState()
        // Top bar fades from transparent (over the header backdrop) to opaque as the header scrolls
        // away, mirroring Komikku's scroll-driven toolbar alpha. Pinned scroll behavior can't do this
        // here: it reports the content drawn behind the bar as already "overlapped", so the bar would
        // be opaque from the start.
        val barFraction by remember {
            derivedStateOf {
                if (listState.firstVisibleItemIndex > 0) {
                    1f
                } else {
                    (listState.firstVisibleItemScrollOffset / 500f).coerceIn(0f, 1f)
                }
            }
        }

        val loaded = state as? MangaDetailsState.Loaded
        val selectionActive = loaded?.selection?.isNotEmpty() == true
        var overflowOpen by remember { mutableStateOf(false) }
        var downloadMenuOpen by remember { mutableStateOf(false) }
        var showSheet by remember { mutableStateOf(false) }
        var showTrackingSheet by remember { mutableStateOf(false) }
        var searchActive by rememberSaveable { mutableStateOf(false) }
        var searchQuery by rememberSaveable { mutableStateOf("") }
        val searchFocus = remember { FocusRequester() }
        val focusManager = LocalFocusManager.current
        var confirmMarkAllRead by remember { mutableStateOf(false) }

        val isHttpSource = remember(loaded?.manga?.source) { screenModel.isHttpSource() }
        val sourceName = remember(loaded?.manga?.source) { screenModel.sourceName() }
        val isStubSource = remember(loaded?.manga?.source) { screenModel.isStubSource() }
        val isTracked = trackState.items.any { it.track != null }
        val allRead = loaded?.chapters?.let { it.isNotEmpty() && it.all { c -> c.read } } == true

        // Refresh on resume so returning from the reader Activity picks up read/bookmark changes,
        // mirroring the legacy controller's onActivityResumed -> fetchChapters.
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { screenModel.refresh() }

        BackHandler(enabled = selectionActive) { screenModel.clearSelection() }
        BackHandler(enabled = searchActive && !selectionActive) { searchActive = false; searchQuery = "" }
        LaunchedEffect(searchActive) { if (searchActive) searchFocus.requestFocus() }
        // Scrolling the chapter list while searching dismisses the keyboard (and defocuses the
        // field), so it doesn't cover the results being scrolled.
        LaunchedEffect(searchActive, listState.isScrollInProgress) {
            if (searchActive && listState.isScrollInProgress) focusManager.clearFocus()
        }

        Scaffold(
            topBar = {
                if (loaded != null && selectionActive) {
                    SelectionAppBar(
                        selectionCount = loaded.selection.size,
                        onClose = { screenModel.clearSelection() },
                        colors = TopAppBarDefaults.topAppBarColors(),
                        actions = listOf(
                            SelectionAction("Mark as read", icon = Icons.Outlined.Done) { screenModel.markSelectedRead(true) },
                            SelectionAction("Bookmark", icon = Icons.Outlined.BookmarkAdd) { screenModel.bookmarkSelected(true) },
                            SelectionAction("Download", icon = Icons.Outlined.Download) { screenModel.downloadSelected() },
                            SelectionAction("Delete", icon = Icons.Outlined.Delete) { screenModel.deleteSelected() },
                            SelectionAction("Mark as unread") { screenModel.markSelectedRead(false) },
                            SelectionAction("Remove bookmark") { screenModel.bookmarkSelected(false) },
                            SelectionAction("Mark previous as read") { screenModel.markPreviousRead(true) },
                            SelectionAction("Mark previous as unread") { screenModel.markPreviousRead(false) },
                            SelectionAction("Select all") { screenModel.selectAll() },
                            SelectionAction("Invert selection") { screenModel.invertSelection() },
                        ),
                    )
                } else {
                    ReikaiTopBar(
                        // Title fades in only once the header scrolls under the bar, so it doesn't
                        // double up with the large header title over the backdrop at rest. In search
                        // mode the title slot becomes the chapter-search field.
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
                                    loaded?.manga?.title.orEmpty(),
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.alpha(barFraction),
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = barFraction),
                            scrolledContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = barFraction),
                            titleContentColor = MaterialTheme.colorScheme.onSurface,
                            navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                            actionIconContentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                        navigationIcon = {
                            IconButton(onClick = {
                                if (searchActive) { searchActive = false; searchQuery = "" } else backPress?.invoke()
                            }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = null,
                                )
                            }
                        },
                        actions = {
                            if (loaded != null) {
                                if (searchActive) {
                                    if (searchQuery.isNotEmpty()) {
                                        IconButton(onClick = { searchQuery = "" }) {
                                            Icon(Icons.Outlined.Clear, contentDescription = "Clear")
                                        }
                                    }
                                } else {
                                    IconButton(onClick = { searchActive = true }) {
                                        Icon(Icons.Outlined.Search, contentDescription = "Search chapters")
                                    }
                                    // Box-wrapped so the menu anchors to the icon (a bare DropdownMenu
                                    // in the actions Row mis-anchors). tonalElevation = 0 drops the M3
                                    // elevation tint so the menu matches the theme surface, not grey.
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
                                            DropdownMenuItem(
                                                text = { Text("Download next") },
                                                onClick = { downloadMenuOpen = false; screenModel.downloadNext(1) },
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Download next 5") },
                                                onClick = { downloadMenuOpen = false; screenModel.downloadNext(5) },
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
                                    // Single toggle: marks all read, or all unread when everything is
                                    // already read. Both paths confirm first.
                                    IconButton(onClick = { confirmMarkAllRead = true }) {
                                        Icon(
                                            if (allRead) Icons.Outlined.RemoveDone else Icons.Outlined.DoneAll,
                                            contentDescription = if (allRead) "Mark all as unread" else "Mark all as read",
                                        )
                                    }
                                    val hasOverflow = loaded.manga.favorite || loaded.manga.isLocal()
                                    if (hasOverflow) {
                                        Box {
                                            IconButton(onClick = { overflowOpen = true }) {
                                                Icon(Icons.Filled.MoreVert, contentDescription = null)
                                            }
                                            DropdownMenu(
                                                expanded = overflowOpen,
                                                onDismissRequest = { overflowOpen = false },
                                                containerColor = MaterialTheme.colorScheme.surface,
                                                tonalElevation = 0.dp,
                                            ) {
                                                if (loaded.manga.favorite) {
                                                    DropdownMenuItem(
                                                        text = { Text("Edit categories") },
                                                        onClick = { overflowOpen = false; screenModel.showChangeCategoryDialog() },
                                                    )
                                                }
                                                if (loaded.manga.favorite || loaded.manga.isLocal()) {
                                                    DropdownMenuItem(
                                                        text = { Text("Edit info") },
                                                        onClick = { overflowOpen = false; screenModel.showEditMangaInfoDialog() },
                                                    )
                                                }
                                                if (loaded.manga.favorite && !loaded.manga.isLocal()) {
                                                    DropdownMenuItem(
                                                        text = { Text("Migrate") },
                                                        onClick = {
                                                            overflowOpen = false
                                                            router?.let {
                                                                // transitional: legacy PreMigrationController until migration ports
                                                                PreMigrationController.navigateToMigration(
                                                                    screenModel.skipPreMigration(),
                                                                    it,
                                                                    listOf(mangaId),
                                                                )
                                                            }
                                                        },
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        },
                    )
                }
            },
            floatingActionButton = {
                val resume = loaded?.resumeChapter
                if (loaded != null && resume != null && !selectionActive) {
                    ExtendedFloatingActionButton(
                        text = { Text(if (loaded.hasStarted) "Resume" else "Start reading") },
                        icon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                        onClick = { context.startActivity(ReaderActivity.newIntent(context, loaded.manga, resume)) },
                        containerColor = MaterialTheme.colorScheme.primary,
                    )
                }
            },
        ) { padding ->
            when (val s = state) {
                is MangaDetailsState.Loading -> Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                is MangaDetailsState.NotFound -> Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) { Text("Manga not found", color = MaterialTheme.colorScheme.error) }

                is MangaDetailsState.Loaded -> {
                    val manga = s.manga
                    val coverData = remember(manga.id) { manga.cover() }
                    val genres = remember(manga.id, manga.genre) {
                        manga.genre?.split(",")?.mapNotNull { it.trim().ifBlank { null } }.orEmpty()
                    }
                    // Key on the content hash, not s.chapters: Chapter.equals is url-only, so a
                    // read/bookmark change leaves the list "equal" and the rows would go stale.
                    val rows = remember(s.chapterStateHash, s.hideChapterTitles, s.downloads, s.selection, searchQuery) {
                        s.chapters.mapNotNull {
                            val name = it.preferredChapterName(context, s.hideChapterTitles)
                            if (searchQuery.isNotBlank() && !name.contains(searchQuery, ignoreCase = true)) {
                                return@mapNotNull null
                            }
                            val info = s.downloads[it.id]
                            DetailsChapterRow(
                                id = it.id ?: 0L,
                                name = name,
                                read = it.read,
                                bookmark = it.bookmark,
                                downloadState = info?.state.toDetailsDownloadState(),
                                downloadProgress = info?.progress ?: 0,
                                selected = it.id in s.selection,
                            )
                        }
                    }
                    LaunchedEffect(manga.id) { screenModel.loadRelatedMangas() }
                    val relatedItems = remember(s.relatedMangas) {
                        s.relatedMangas.map { candidate ->
                            DetailsRelatedItem(
                                key = candidate.manga.url,
                                title = candidate.manga.title,
                                coverData = MangaCover(
                                    mangaId = null,
                                    sourceId = candidate.sourceId,
                                    url = candidate.manga.thumbnail_url.orEmpty(),
                                    lastModified = 0L,
                                    inLibrary = false,
                                ),
                                provenanceLabel = screenModel.relatedProvenanceLabel(candidate),
                            )
                        }
                    }
                    DetailsContent(
                        coverData = coverData,
                        title = manga.title,
                        author = manga.author,
                        artist = manga.artist,
                        status = manga.status,
                        statusText = context.mapStatus(manga.status),
                        sourceName = sourceName,
                        isStubSource = isStubSource,
                        description = manga.description,
                        genres = genres,
                        isFavorited = manga.favorite,
                        onFavoriteClick = { screenModel.toggleFavorite() },
                        onEditCategoryClick = if (manga.favorite) ({ screenModel.showChangeCategoryDialog() }) else null,
                        trackingActive = isTracked,
                        onTrackingClick = { showTrackingSheet = true; trackModel.refresh() },
                        onWebViewClick = if (isHttpSource) ({
                            screenModel.getMangaUrl()?.let { url ->
                                navigator.push(
                                    WebViewScreen(url = url, initialTitle = manga.title, sourceId = manga.source),
                                )
                            }
                        }) else null,
                        onShareClick = if (isHttpSource) ({
                            screenModel.getMangaUrl()?.let { url ->
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, url)
                                }
                                context.startActivity(Intent.createChooser(intent, null))
                            }
                        }) else null,
                        onFilterClick = { showSheet = true },
                        chapters = rows,
                        onChapterClick = { id ->
                            s.chapters.find { it.id == id }?.let { chapter ->
                                context.startActivity(ReaderActivity.newIntent(context, manga, chapter))
                            }
                        },
                        listState = listState,
                        topInset = padding.calculateTopPadding(),
                        bottomInset = padding.calculateBottomPadding(),
                        onDownloadClick = { id -> screenModel.downloadAction(id) },
                        selectionActive = selectionActive,
                        onToggleSelection = { id, sel, long -> screenModel.toggleSelection(id, sel, long) },
                        relatedMangas = relatedItems,
                        relatedMangasTotal = s.relatedMangasTotal,
                        relatedMangasLoading = s.relatedMangasLoading,
                        onRelatedClick = { key ->
                            s.relatedMangas.find { it.manga.url == key }?.let { candidate ->
                                if (candidate.sourceId == RECOMMENDS_SOURCE) {
                                    // transitional: legacy GlobalSearchController until global search ports
                                    router?.pushController(GlobalSearchController(candidate.manga.title).withFadeTransaction())
                                } else {
                                    scope.launch {
                                        screenModel.relatedToLocalId(candidate)?.let { id ->
                                            navigator.push(MangaDetailsScreen(id))
                                        }
                                    }
                                }
                            }
                        },
                        onSeeAllClick = {
                            screenModel.stageBrowseHandoff()
                            // transitional: legacy RelatedMangasBrowseController until the browse view ports
                            router?.pushController(RelatedMangasBrowseController(mangaId).withFadeTransaction())
                        },
                    )
                }
            }
        }

        if (showSheet && loaded != null) {
            DetailsFilterSortSheet(
                readFilter = loaded.readFilter,
                downloadedFilter = loaded.downloadedFilter,
                bookmarkedFilter = loaded.bookmarkedFilter,
                hideChapterTitles = loaded.hideChapterTitles,
                onFiltersChanged = { r, d, b -> screenModel.setFilters(r, d, b) },
                onHideChapterTitlesChanged = { screenModel.setHideChapterTitles(it) },
                onSetFilterDefault = {
                    screenModel.setGlobalFilters(loaded.readFilter, loaded.downloadedFilter, loaded.bookmarkedFilter)
                },
                onResetFilterDefault = { screenModel.resetFilterToDefault() },
                sorting = loaded.sorting,
                sortDescending = loaded.sortDescending,
                onSortChanged = { sort, descend -> screenModel.setSortOrder(sort, descend) },
                onSetSortDefault = { screenModel.setGlobalSort(loaded.sorting, loaded.sortDescending) },
                onResetSortDefault = { screenModel.resetSortToDefault() },
                allScanlators = loaded.allScanlators,
                filteredScanlators = loaded.filteredScanlators,
                onScanlatorFilterChanged = { screenModel.setScanlatorFilter(it) },
                onDismiss = { showSheet = false },
            )
        }

        if (confirmMarkAllRead) {
            AlertDialog(
                onDismissRequest = { confirmMarkAllRead = false },
                title = { Text(if (allRead) "Mark all as unread" else "Mark all as read") },
                text = {
                    Text(
                        if (allRead) {
                            "Mark every chapter in this series as unread?"
                        } else {
                            "Mark every chapter in this series as read?"
                        },
                    )
                },
                confirmButton = {
                    TextButton(onClick = { confirmMarkAllRead = false; screenModel.markAllRead(!allRead) }) {
                        Text(if (allRead) "Mark as unread" else "Mark as read")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmMarkAllRead = false }) { Text("Cancel") }
                },
            )
        }

        if (showTrackingSheet) {
            TrackInfoDialog(
                state = trackState,
                onSearchQueryChange = trackModel::onSearchQueryChange,
                onSearch = trackModel::search,
                onOpenSearch = trackModel::openSearch,
                onRegister = trackModel::registerTracking,
                onSetPrivate = trackModel::setPrivate,
                onOpenStatus = trackModel::openStatus,
                onOpenScore = trackModel::openScore,
                onOpenChapters = trackModel::openChapters,
                onOpenDate = trackModel::openDate,
                onOpenRemove = trackModel::openRemove,
                onSetStatus = trackModel::setStatus,
                onSetScore = trackModel::setScore,
                onSetChapters = trackModel::setLastChapterRead,
                onSetDate = trackModel::setDate,
                onRemove = trackModel::removeTracking,
                onBack = trackModel::backToHome,
                onDismiss = { showTrackingSheet = false; trackModel.backToHome() },
            )
        }

        when (val dialog = loaded?.dialog) {
            is MangaDetailsDialog.ChangeCategory -> ChangeCategoryDialog(
                allCategories = dialog.allCategories,
                currentCategoryIds = dialog.currentCategoryIds,
                onDismiss = { screenModel.dismissDialog() },
                onConfirm = { screenModel.moveMangaToCategoriesAndAddToLibrary(it) },
            )
            is MangaDetailsDialog.EditMangaInfo -> EditMangaInfoDialog(
                manga = dialog.manga,
                onDismiss = { screenModel.dismissDialog() },
                onConfirm = { title, author, artist, description, genre ->
                    screenModel.updateMangaInfo(title, author, artist, description, genre)
                },
            )
            null -> Unit
        }
    }
}

private fun Download.State?.toDetailsDownloadState(): DetailsDownloadState = when (this) {
    Download.State.QUEUE, Download.State.CHECKED -> DetailsDownloadState.QUEUED
    Download.State.DOWNLOADING -> DetailsDownloadState.DOWNLOADING
    Download.State.DOWNLOADED -> DetailsDownloadState.DOWNLOADED
    Download.State.ERROR -> DetailsDownloadState.ERROR
    Download.State.NOT_DOWNLOADED, null -> DetailsDownloadState.NONE
}
