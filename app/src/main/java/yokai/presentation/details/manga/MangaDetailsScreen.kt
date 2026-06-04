package yokai.presentation.details.manga

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import dev.icerock.moko.resources.compose.pluralStringResource
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.data.recommendation.RECOMMENDS_SOURCE
import eu.kanade.tachiyomi.ui.manga.related.browse.RelatedMangasBrowseController
import eu.kanade.tachiyomi.ui.migration.manga.design.PreMigrationController
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.source.globalsearch.GlobalSearchController
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import eu.kanade.tachiyomi.util.chapter.ChapterUtil
import eu.kanade.tachiyomi.util.chapter.ChapterUtil.Companion.preferredChapterName
import eu.kanade.tachiyomi.util.compose.LocalBackPress
import eu.kanade.tachiyomi.util.compose.LocalRouter
import eu.kanade.tachiyomi.util.compose.currentOrThrow
import eu.kanade.tachiyomi.util.isLocal
import eu.kanade.tachiyomi.util.mapStatus
import eu.kanade.tachiyomi.util.view.withFadeTransaction
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import yokai.domain.chapter.services.calculateChapterGap
import yokai.domain.manga.models.MangaCover
import yokai.domain.manga.models.cover
import yokai.i18n.MR
import yokai.util.lang.getString
import yokai.presentation.component.ReikaiTopBar
import yokai.presentation.core.util.shouldExpandFAB
import yokai.presentation.details.ChangeCategoryDialog
import yokai.presentation.details.HandleDetailsEvents
import yokai.presentation.details.CoverDialog
import yokai.presentation.details.EditInfoDialog
import yokai.presentation.details.detailsResumeLabel
import yokai.presentation.details.DetailsChapterRow
import yokai.presentation.details.DetailsContent
import yokai.presentation.details.DetailsDownloadState
import yokai.presentation.details.DetailsFilterSortSheet
import yokai.presentation.details.DetailsRelatedItem
import yokai.presentation.details.DetailsSourceTab
import yokai.presentation.details.ManageSourcesDialog
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
        // Non-null while the "remove this source from the group?" confirm dialog is up; holds the
        // long-pressed source chip's manga id.
        var sourceToRemove by remember { mutableStateOf<Long?>(null) }

        val snackbarHostState = remember { SnackbarHostState() }
        // One-shot screen effects from the ScreenModel: undo snackbars (mark-read, merge split/remove),
        // cover-share, and post-split navigation to a sibling source. Shared with the novel screen.
        HandleDetailsEvents(
            events = screenModel.events,
            snackbarHostState = snackbarHostState,
            context = context,
            onNavigateToSibling = { navigator.replace(MangaDetailsScreen(it)) },
        )
        // A mark-read tracker push lands a few seconds later (and off this screen's scope), so re-sync
        // the tracking sheet from remote once it completes instead of waiting for a close/reopen.
        LaunchedEffect(Unit) {
            screenModel.trackRefresh.collect { trackModel.refresh() }
        }

        val isHttpSource = remember(loaded?.displayManga?.source) { screenModel.isHttpSource() }
        val chapterSwipeEnabled = remember { screenModel.isChapterSwipeEnabled() }
        val clipboard = LocalClipboardManager.current
        var showCoverDialog by remember { mutableStateOf(false) }
        val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { screenModel.setCustomCover(it) }
        }
        // Label + stub flag follow the displayed source (state-driven), so they switch with the chips.
        val sourceName = loaded?.sourceLabel.orEmpty()
        val isStubSource = loaded?.isStubSource ?: false
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
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                if (loaded != null && selectionActive) {
                    val selectedChapters = loaded.chapters.filter { it.id in loaded.selection }
                    // Show "Remove bookmark" only when every selected chapter is already bookmarked;
                    // an empty/none or mixed selection adds bookmarks.
                    val allBookmarked = selectedChapters.isNotEmpty() && selectedChapters.all { it.bookmark }
                    // Likewise the read toggle flips to "Mark as unread" only when every selected
                    // chapter is already read; a mixed selection keeps "Mark as read".
                    val allSelectedRead = selectedChapters.isNotEmpty() && selectedChapters.all { it.read }
                    SelectionAppBar(
                        selectionCount = loaded.selection.size,
                        onClose = { screenModel.clearSelection() },
                        colors = TopAppBarDefaults.topAppBarColors(),
                        actions = buildList {
                            add(
                                if (allSelectedRead) {
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
                            add(SelectionAction("Download", icon = Icons.Outlined.Download) { screenModel.downloadSelected() })
                            add(SelectionAction("Delete", icon = Icons.Outlined.Delete) { screenModel.deleteSelected() })
                            add(SelectionAction("Mark previous as read") { screenModel.markPreviousRead(true) })
                            add(SelectionAction("Mark previous as unread") { screenModel.markPreviousRead(false) })
                            // Single-chapter web actions, mirroring the legacy per-chapter long-press menu.
                            if (loaded.selection.size == 1 && isHttpSource) {
                                val chapterId = loaded.selection.first()
                                add(
                                    SelectionAction("Open in browser") {
                                        screenModel.getChapterUrl(chapterId)?.let { url ->
                                            navigator.push(WebViewScreen(url = url, initialTitle = loaded.manga.title, sourceId = loaded.manga.source))
                                        }
                                    },
                                )
                                add(
                                    SelectionAction("Share URL") {
                                        screenModel.getChapterUrl(chapterId)?.let { url ->
                                            val intent = Intent(Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(Intent.EXTRA_TEXT, url)
                                            }
                                            context.startActivity(Intent.createChooser(intent, null))
                                        }
                                    },
                                )
                            }
                            add(SelectionAction("Select all") { screenModel.selectAll() })
                            add(SelectionAction("Invert selection") { screenModel.invertSelection() })
                        },
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
                                            HorizontalDivider()
                                            DropdownMenuItem(
                                                text = { Text("Remove all downloads") },
                                                onClick = { downloadMenuOpen = false; screenModel.removeAllDownloads() },
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Remove read downloads") },
                                                onClick = { downloadMenuOpen = false; screenModel.removeReadDownloads() },
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Remove non-bookmarked downloads") },
                                                onClick = { downloadMenuOpen = false; screenModel.removeNonBookmarkedDownloads() },
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
                                                // Only meaningful for a merged title (more than one
                                                // grouped source); hidden for single-source manga.
                                                if (loaded.relatedMangaIds.size > 1) {
                                                    DropdownMenuItem(
                                                        text = { Text("Manage sources") },
                                                        onClick = { overflowOpen = false; screenModel.showManageSourcesDialog() },
                                                    )
                                                }
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
                // When everything is read the FAB re-reads the most recent chapter (legacy disables
                // its header button here; a re-read entry point is friendlier for a FAB).
                val fabTarget = resume ?: loaded?.chapters?.maxByOrNull { it.chapter_number }
                if (loaded != null && fabTarget != null && !selectionActive) {
                    val label = detailsResumeLabel(context, resume?.chapter_number, (resume?.last_page_read ?: 0) > 0)
                    ExtendedFloatingActionButton(
                        text = { Text(label) },
                        icon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                        expanded = listState.shouldExpandFAB(),
                        onClick = {
                            scope.launch {
                                val origin = screenModel.mangaForChapter(fabTarget) ?: loaded.manga
                                context.startActivity(ReaderActivity.newIntent(context, origin, fabTarget))
                            }
                        },
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
                    // Header renders the displayed source (anchor in unified, the chip's source in a
                    // per-source view); favorite / FAB / reader still use the anchor `manga`.
                    val displayManga = s.displayManga
                    val coverData = remember(displayManga.id, displayManga.cover_last_modified) { displayManga.cover() }
                    val genres = remember(displayManga.id, displayManga.genre) {
                        displayManga.genre?.split(",")?.mapNotNull { it.trim().ifBlank { null } }.orEmpty()
                    }
                    // Key on the content hash, not s.chapters: Chapter.equals is url-only, so a
                    // read/bookmark change leaves the list "equal" and the rows would go stale.
                    val rows = remember(s.chapterStateHash, s.hideChapterTitles, s.downloads, s.selection, searchQuery, s.sorting) {
                        // Filter to the visible chapters first (with display names), then map to rows so a
                        // row's "missing N before it" gap is measured against the previous *visible* chapter.
                        val visible = s.chapters.mapNotNull { ch ->
                            val name = ch.preferredChapterName(context, s.hideChapterTitles)
                            if (searchQuery.isNotBlank() && !name.contains(searchQuery, ignoreCase = true)) null else ch to name
                        }
                        // Gaps only make sense when sorted by chapter number; under source/date order
                        // the adjacent difference is noise, so skip it.
                        val byNumber = s.sorting == Manga.CHAPTER_SORTING_NUMBER
                        visible.mapIndexed { index, (ch, name) ->
                            val info = s.downloads[ch.id]
                            val missing = if (byNumber && index > 0) {
                                val prev = visible[index - 1].first
                                calculateChapterGap(
                                    maxOf(ch.chapter_number, prev.chapter_number).toDouble(),
                                    minOf(ch.chapter_number, prev.chapter_number).toDouble(),
                                ).coerceAtLeast(0)
                            } else {
                                0
                            }
                            DetailsChapterRow(
                                id = ch.id ?: 0L,
                                name = name,
                                read = ch.read,
                                bookmark = ch.bookmark,
                                downloadState = info?.state.toDetailsDownloadState(),
                                downloadProgress = info?.progress ?: 0,
                                selected = ch.id in s.selection,
                                date = ChapterUtil.relativeDate(ch),
                                readProgress = if (!ch.read && ch.last_page_read > 0) {
                                    context.getString(MR.strings.page_x_of_y, ch.last_page_read + 1, ch.pages_left + ch.last_page_read)
                                } else {
                                    null
                                },
                                scanlator = ch.scanlator?.takeIf { text -> text.isNotBlank() },
                                missingCount = missing,
                            )
                        }
                    }
                    LaunchedEffect(manga.id) {
                        screenModel.loadRelatedMangaIds()
                        screenModel.loadAccentColor()
                    }
                    // Reload the carousel when the displayed source changes (chip switch) or the
                    // group resolves (so Unified flips from the anchor's recs to the pooled set). The
                    // leading delay debounces rapid chip-tapping: each switch relaunches this effect and
                    // cancels the pending delay, so only the settled source fetches. Without it a burst
                    // of uncached per-source fetches piles up against the tracker rate-limiter and
                    // starves the chapter pipeline's dispatcher, stalling the list swap by seconds.
                    LaunchedEffect(manga.id, s.sourceView, s.relatedMangaIds) {
                        delay(350)
                        screenModel.loadRelatedMangas()
                    }
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
                    val pullRefreshState = rememberPullToRefreshState()
                    PullToRefreshBox(
                        isRefreshing = s.isRefreshing,
                        // Gate on selection so a pull mid-multi-select doesn't fire a fetch.
                        onRefresh = { if (!selectionActive) screenModel.fetchChaptersFromSource() },
                        state = pullRefreshState,
                        indicator = {
                            PullToRefreshDefaults.Indicator(
                                modifier = Modifier.align(Alignment.TopCenter),
                                isRefreshing = s.isRefreshing,
                                state = pullRefreshState,
                            )
                        },
                    ) {
                    DetailsContent(
                        coverData = coverData,
                        title = displayManga.title,
                        author = displayManga.author,
                        artist = displayManga.artist,
                        status = displayManga.status,
                        statusText = context.mapStatus(displayManga.status),
                        sourceName = sourceName,
                        isStubSource = isStubSource,
                        accentColor = s.accentColor?.let { Color(it) },
                        onCoverClick = { showCoverDialog = true },
                        description = displayManga.description,
                        genres = genres,
                        isFavorited = manga.favorite,
                        onFavoriteClick = { screenModel.toggleFavorite() },
                        onEditCategoryClick = if (manga.favorite) ({ screenModel.showChangeCategoryDialog() }) else null,
                        onRemoveFromLibrary = if (manga.favorite) ({ screenModel.toggleFavorite() }) else null,
                        onRemoveAllSources = if (manga.favorite && s.relatedMangaIds.size > 1) ({ screenModel.removeAllSourcesFromLibrary() }) else null,
                        trackingActive = isTracked,
                        onTrackingClick = { showTrackingSheet = true; trackModel.refresh() },
                        onWebViewClick = if (isHttpSource) ({
                            screenModel.getMangaUrl()?.let { url ->
                                navigator.push(
                                    WebViewScreen(url = url, initialTitle = displayManga.title, sourceId = displayManga.source),
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
                        filtersActive = s.readFilter != 0 || s.downloadedFilter != 0 ||
                            s.bookmarkedFilter != 0 || s.filteredScanlators.isNotEmpty(),
                        // transitional: legacy GlobalSearchController until global search ports
                        onSearch = { query -> router?.pushController(GlobalSearchController(query).withFadeTransaction()) },
                        onCopy = { text ->
                            clipboard.setText(AnnotatedString(text))
                            scope.launch { snackbarHostState.showSnackbar("Copied to clipboard") }
                        },
                        chapters = rows,
                        onChapterClick = { id ->
                            s.chapters.find { it.id == id }?.let { chapter ->
                                // Open against the chapter's own source: a merged list mixes sources,
                                // and the reader errors if handed a chapter the given manga doesn't own.
                                scope.launch {
                                    val origin = screenModel.mangaForChapter(chapter) ?: manga
                                    context.startActivity(ReaderActivity.newIntent(context, origin, chapter))
                                }
                            }
                        },
                        listState = listState,
                        topInset = padding.calculateTopPadding(),
                        bottomInset = padding.calculateBottomPadding(),
                        // Local titles have nothing to download; hide the row indicator (matches legacy).
                        onDownloadClick = if (displayManga.isLocal()) null else screenModel::downloadAction,
                        downloadMenuEnabled = true,
                        chapterSwipeEnabled = chapterSwipeEnabled,
                        onSwipeToRead = screenModel::toggleChapterRead,
                        onSwipeToBookmark = screenModel::toggleChapterBookmark,
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
                        sourceTabs = s.sourceTabs.map { DetailsSourceTab(it.mangaId, it.sourceName) },
                        selectedSourceView = s.sourceView,
                        onSourceViewChange = { screenModel.setSourceView(it) },
                        onSourceRemove = { sourceToRemove = it },
                        currentSourceId = mangaId,
                    )
                    }
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

        sourceToRemove?.let { id ->
            val name = loaded?.sourceTabs?.find { it.mangaId == id }?.sourceName.orEmpty()
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

        if (showTrackingSheet) {
            TrackInfoDialog(
                state = trackState,
                onSearchQueryChange = trackModel::onSearchQueryChange,
                onSearch = trackModel::search,
                onOpenSearch = trackModel::addTracking,
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

        if (showCoverDialog && loaded != null) {
            val dm = loaded.displayManga
            val cover = remember(dm.id, dm.cover_last_modified) { dm.cover() }
            val custom = remember(dm.id, dm.cover_last_modified) { screenModel.isCustomCover() }
            CoverDialog(
                coverData = cover,
                isCustomCover = custom,
                canEdit = loaded.manga.favorite,
                onShare = { screenModel.shareCover() },
                onSave = { screenModel.saveCover() },
                onEditCover = { coverPicker.launch("image/*") },
                onResetCover = { screenModel.resetCover() },
                onDismiss = { showCoverDialog = false },
            )
        }

        when (val dialog = loaded?.dialog) {
            is MangaDetailsDialog.ChangeCategory -> ChangeCategoryDialog(
                allCategories = dialog.allCategories,
                currentCategoryIds = dialog.currentCategoryIds,
                onDismiss = { screenModel.dismissDialog() },
                onConfirm = { screenModel.moveMangaToCategoriesAndAddToLibrary(it) },
            )
            is MangaDetailsDialog.EditMangaInfo -> EditInfoDialog(
                initialTitle = dialog.manga.title,
                initialAuthor = dialog.manga.author.orEmpty(),
                initialArtist = dialog.manga.artist.orEmpty(),
                initialDescription = dialog.manga.description.orEmpty(),
                initialGenre = dialog.manga.genre.orEmpty(),
                statusOptions = remember { listOf(0, 1, 2, 3, 4, 5, 6).map { it to (context.mapStatus(it) ?: "Unknown") } },
                initialStatus = dialog.manga.status,
                onReset = { screenModel.resetMangaInfo() },
                onDismiss = { screenModel.dismissDialog() },
                onConfirm = { title, author, artist, description, genre, status ->
                    screenModel.updateMangaInfo(title, author, artist, description, genre, status)
                },
            )
            is MangaDetailsDialog.ManageSources -> ManageSourcesDialog(
                sources = dialog.sources,
                onSplit = { screenModel.splitSources(it) },
                onRemoveFromLibrary = { screenModel.removeSourcesFromLibrary(it) },
                onDismiss = { screenModel.dismissDialog() },
            )
            is MangaDetailsDialog.ConfirmRemovedDownloads -> {
                val names = remember(dialog.chapters) {
                    dialog.chapters.take(5).joinToString("\n") { it.name } +
                        if (dialog.chapters.size > 5) "\n…" else ""
                }
                AlertDialog(
                    onDismissRequest = { screenModel.dismissDialog() },
                    title = { Text(stringResource(MR.strings.chapters_removed)) },
                    text = {
                        Text(
                            pluralStringResource(
                                MR.plurals.deleted_chapters,
                                quantity = dialog.chapters.size,
                                dialog.chapters.size,
                                names,
                            ),
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = { screenModel.deleteRemovedDownloads(dialog.chapters, dialog.manga) }) {
                            Text(stringResource(MR.strings.delete))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { screenModel.dismissDialog() }) {
                            Text(stringResource(MR.strings.cancel))
                        }
                    },
                )
            }
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
