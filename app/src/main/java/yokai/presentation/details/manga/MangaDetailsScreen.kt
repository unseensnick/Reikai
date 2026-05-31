package yokai.presentation.details.manga

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Sync
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
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.ui.migration.manga.design.PreMigrationController
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import eu.kanade.tachiyomi.util.chapter.ChapterUtil.Companion.preferredChapterName
import eu.kanade.tachiyomi.util.compose.LocalBackPress
import eu.kanade.tachiyomi.util.compose.LocalRouter
import eu.kanade.tachiyomi.util.compose.currentOrThrow
import eu.kanade.tachiyomi.util.isLocal
import eu.kanade.tachiyomi.util.mapStatus
import yokai.domain.manga.models.cover
import yokai.presentation.component.ReikaiTopBar
import yokai.presentation.details.ChangeCategoryDialog
import yokai.presentation.details.DetailsChapterRow
import yokai.presentation.details.DetailsContent
import yokai.presentation.details.DetailsDownloadState
import yokai.presentation.details.DetailsFilterSortSheet
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

        val loaded = state as? MangaDetailsState.Loaded
        val selectionActive = loaded?.selection?.isNotEmpty() == true
        var overflowOpen by remember { mutableStateOf(false) }
        var showSheet by remember { mutableStateOf(false) }
        var showTrackingSheet by remember { mutableStateOf(false) }

        val isHttpSource = remember(loaded?.manga?.source) { screenModel.isHttpSource() }

        // Refresh on resume so returning from the reader Activity picks up read/bookmark changes,
        // mirroring the legacy controller's onActivityResumed -> fetchChapters.
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { screenModel.refresh() }

        BackHandler(enabled = selectionActive) { screenModel.clearSelection() }

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
                        title = { Text(loaded?.manga?.title.orEmpty(), style = MaterialTheme.typography.titleMedium) },
                        navigationIcon = {
                            IconButton(onClick = { backPress?.invoke() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                            }
                        },
                        actions = {
                            if (loaded != null) {
                                IconButton(onClick = { screenModel.toggleFavorite() }) {
                                    Icon(
                                        if (loaded.manga.favorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                        contentDescription = if (loaded.manga.favorite) "Remove from library" else "Add to library",
                                        tint = if (loaded.manga.favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                                val isTracked = trackState.items.any { it.track != null }
                                IconButton(onClick = { showTrackingSheet = true; trackModel.refresh() }) {
                                    Icon(
                                        if (isTracked) Icons.Filled.Sync else Icons.Outlined.Sync,
                                        contentDescription = "Tracking",
                                    )
                                }
                                IconButton(onClick = { showSheet = true }) {
                                    Icon(Icons.Outlined.FilterList, contentDescription = null)
                                }
                                IconButton(onClick = { overflowOpen = true }) {
                                    Icon(Icons.Filled.MoreVert, contentDescription = null)
                                }
                                DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                                    DropdownMenuItem(
                                        text = { Text("Mark all as read") },
                                        onClick = { overflowOpen = false; screenModel.markAllRead(true) },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Mark all as unread") },
                                        onClick = { overflowOpen = false; screenModel.markAllRead(false) },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Download next") },
                                        onClick = { overflowOpen = false; screenModel.downloadNext(1) },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Download next 5") },
                                        onClick = { overflowOpen = false; screenModel.downloadNext(5) },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Download unread") },
                                        onClick = { overflowOpen = false; screenModel.downloadUnread() },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Download all") },
                                        onClick = { overflowOpen = false; screenModel.downloadAll() },
                                    )
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
                                    if (isHttpSource) {
                                        DropdownMenuItem(
                                            text = { Text("Share") },
                                            onClick = {
                                                overflowOpen = false
                                                screenModel.getMangaUrl()?.let { url ->
                                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                                        type = "text/plain"
                                                        putExtra(Intent.EXTRA_TEXT, url)
                                                    }
                                                    context.startActivity(Intent.createChooser(intent, null))
                                                }
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Open in web view") },
                                            onClick = {
                                                overflowOpen = false
                                                screenModel.getMangaUrl()?.let { url ->
                                                    navigator.push(
                                                        WebViewScreen(
                                                            url = url,
                                                            initialTitle = loaded.manga.title,
                                                            sourceId = loaded.manga.source,
                                                        ),
                                                    )
                                                }
                                            },
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
                    val rows = remember(s.chapterStateHash, s.hideChapterTitles, s.downloads, s.selection) {
                        s.chapters.map {
                            val info = s.downloads[it.id]
                            DetailsChapterRow(
                                id = it.id ?: 0L,
                                name = it.preferredChapterName(context, s.hideChapterTitles),
                                read = it.read,
                                bookmark = it.bookmark,
                                downloadState = info?.state.toDetailsDownloadState(),
                                downloadProgress = info?.progress ?: 0,
                                selected = it.id in s.selection,
                            )
                        }
                    }
                    DetailsContent(
                        coverData = coverData,
                        title = manga.title,
                        author = manga.author,
                        statusText = context.mapStatus(manga.status),
                        description = manga.description,
                        genres = genres,
                        isFavorited = manga.favorite,
                        onFavoriteClick = { screenModel.toggleFavorite() },
                        chapters = rows,
                        onChapterClick = { id ->
                            s.chapters.find { it.id == id }?.let { chapter ->
                                context.startActivity(ReaderActivity.newIntent(context, manga, chapter))
                            }
                        },
                        modifier = Modifier.padding(padding),
                        onDownloadClick = { id -> screenModel.downloadAction(id) },
                        selectionActive = selectionActive,
                        onToggleSelection = { id, sel, long -> screenModel.toggleSelection(id, sel, long) },
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
                filterMatchesDefault = loaded.filterMatchesDefault,
                onFiltersChanged = { r, d, b -> screenModel.setFilters(r, d, b) },
                onHideChapterTitlesChanged = { screenModel.setHideChapterTitles(it) },
                onSetFilterDefault = {
                    screenModel.setGlobalFilters(loaded.readFilter, loaded.downloadedFilter, loaded.bookmarkedFilter)
                },
                onResetFilterDefault = { screenModel.resetFilterToDefault() },
                sorting = loaded.sorting,
                sortDescending = loaded.sortDescending,
                sortMatchesDefault = loaded.sortMatchesDefault,
                onSortChanged = { sort, descend -> screenModel.setSortOrder(sort, descend) },
                onSetSortDefault = { screenModel.setGlobalSort(loaded.sorting, loaded.sortDescending) },
                onResetSortDefault = { screenModel.resetSortToDefault() },
                allScanlators = loaded.allScanlators,
                filteredScanlators = loaded.filteredScanlators,
                onScanlatorFilterChanged = { screenModel.setScanlatorFilter(it) },
                onDismiss = { showSheet = false },
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
