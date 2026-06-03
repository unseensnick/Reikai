package yokai.presentation.novel.details

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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import eu.kanade.tachiyomi.util.compose.LocalBackPress
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import yokai.data.novel.NovelStatusCode
import yokai.domain.novel.NovelChapterRepository
import yokai.domain.novel.models.NovelChapter
import yokai.novel.host.LnPluginHost
import yokai.novel.install.LnPluginInstaller
import yokai.novel.source.NovelSource
import yokai.novel.source.NovelSourceManager
import yokai.presentation.component.ReikaiTopBar
import yokai.presentation.details.ChangeCategoryDialog
import yokai.presentation.details.DetailsChapterRow
import yokai.presentation.details.DetailsContent
import yokai.presentation.novel.browse.ChapterRead
import yokai.presentation.novel.browse.ChapterReader
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
        val networkHelper = remember { Injekt.get<NetworkHelper>() }
        val installer = remember { Injekt.get<LnPluginInstaller>() }
        val manager = remember { Injekt.get<NovelSourceManager>() }
        val chapterRepo = remember { Injekt.get<NovelChapterRepository>() }
        val host = remember { LnPluginHost(context, networkHelper.client) }
        val backPress = LocalBackPress.current
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

        DisposableEffect(host) { onDispose { host.destroy() } }

        // Resolve the source once and hand it to the ScreenModel (host construction needs a Context).
        var resolvedSource by remember { mutableStateOf<NovelSource?>(null) }
        LaunchedEffect(sourceId, novelUrl) {
            try {
                installer.loadInstalled(host)
                val source = manager.get(sourceId)
                    ?: throw IllegalStateException("source not installed: $sourceId")
                resolvedSource = source
                screenModel.onSourceReady(source)
            } catch (e: Throwable) {
                screenModel.onSourceFailed("${e.javaClass.simpleName}: ${e.message ?: ""}")
            }
        }

        // Transient reader state: reading a chapter is always a source fetch, not cached.
        var readingChapter by remember { mutableStateOf<NovelChapter?>(null) }
        var readerData by remember { mutableStateOf<ChapterRead?>(null) }
        var readerLoading by remember { mutableStateOf(false) }
        var readerSettingsOpen by remember { mutableStateOf(false) }

        fun openChapter(chapter: NovelChapter) {
            if (readerLoading) return
            scope.launch {
                readerLoading = true
                try {
                    readerData = screenModel.loadChapterText(chapter)
                    readingChapter = chapter
                } catch (e: Throwable) {
                    context.toast("Failed to open chapter: ${e.message ?: ""}")
                } finally {
                    readerLoading = false
                }
            }
        }

        fun goBack() {
            if (readingChapter != null) {
                readingChapter = null
                readerData = null
                return
            }
            backPress?.invoke()
        }

        val isReading = readingChapter != null
        val title = when {
            isReading -> readingChapter?.name.orEmpty()
            state is NovelDetailsState.Loaded -> (state as NovelDetailsState.Loaded).novel.title
            state is NovelDetailsState.Failed -> "Error"
            else -> "Loading…"
        }
        // Only the Loaded details body draws the cover backdrop, so only then should the bar go
        // transparent; reader / loading / error keep an opaque bar.
        val showBackdrop = !isReading && state is NovelDetailsState.Loaded

        // While the in-screen reader is open, intercept system/gesture back so it returns to the
        // details page instead of popping the whole screen back to the library.
        BackHandler(enabled = isReading) { goBack() }

        Scaffold(
            topBar = {
                val barAlpha = if (showBackdrop) barFraction else 1f
                ReikaiTopBar(
                    title = {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.alpha(barAlpha),
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { goBack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        if (isReading) {
                            IconButton(onClick = { readerSettingsOpen = true }) {
                                Icon(Icons.Default.Settings, contentDescription = "Reader settings")
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
            },
        ) { padding ->
            val reading = readingChapter
            val data = readerData
            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    reading != null && data != null -> Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        ChapterReader(
                            paragraphs = data.paragraphs,
                            chapterId = data.chapterId,
                            initialProgress = data.initialProgress,
                            chapterRepo = chapterRepo,
                            settingsOpen = readerSettingsOpen,
                            onSettingsOpenChange = { readerSettingsOpen = it },
                        )
                    }
                    else -> when (val s = state) {
                        is NovelDetailsState.Loading -> Box(
                            modifier = Modifier.fillMaxSize().padding(padding),
                            contentAlignment = Alignment.Center,
                        ) { CircularProgressIndicator() }
                        is NovelDetailsState.Loaded -> {
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
                                val rows = s.chapters.map { ch ->
                                    DetailsChapterRow(
                                        id = ch.id ?: 0L,
                                        name = ch.name,
                                        read = ch.read,
                                        bookmark = ch.bookmark,
                                    )
                                }
                                DetailsContent(
                                    coverData = s.novel.thumbnailUrl,
                                    title = s.novel.title,
                                    author = s.novel.author,
                                    artist = s.novel.artist,
                                    status = s.novel.status,
                                    statusText = statusLabel(s.novel.status),
                                    sourceName = resolvedSource?.name.orEmpty(),
                                    isStubSource = false,
                                    description = s.novel.description,
                                    genres = s.novel.genres.orEmpty(),
                                    chapters = rows,
                                    onChapterClick = { id ->
                                        s.chapters.find { it.id == id }?.let { openChapter(it) }
                                    },
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
                if (readerLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(padding)
                            .padding(8.dp),
                        contentAlignment = Alignment.TopCenter,
                    ) { CircularProgressIndicator() }
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
            }
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
