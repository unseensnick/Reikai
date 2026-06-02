package yokai.presentation.novel.details

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import yokai.domain.novel.models.Novel
import yokai.domain.novel.models.NovelChapter
import yokai.novel.host.LnPluginHost
import yokai.novel.install.LnPluginInstaller
import yokai.novel.source.NovelSource
import yokai.novel.source.NovelSourceManager
import yokai.presentation.component.ReikaiTopBar
import yokai.presentation.manga.components.MangaCover
import yokai.presentation.manga.components.MangaCoverRatio
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

        Scaffold(
            topBar = {
                ReikaiTopBar(
                    title = { Text(title, style = MaterialTheme.typography.titleMedium) },
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
                        } else if (state is NovelDetailsState.Loaded) {
                            IconButton(onClick = { screenModel.refresh() }) {
                                Icon(Icons.Outlined.Refresh, contentDescription = "Refresh")
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(),
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                if (readerLoading) {
                    Box(modifier = Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                val reading = readingChapter
                val data = readerData
                if (reading != null && data != null) {
                    ChapterReader(
                        paragraphs = data.paragraphs,
                        chapterId = data.chapterId,
                        initialProgress = data.initialProgress,
                        chapterRepo = chapterRepo,
                        settingsOpen = readerSettingsOpen,
                        onSettingsOpenChange = { readerSettingsOpen = it },
                    )
                } else {
                    when (val s = state) {
                        is NovelDetailsState.Loading -> Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) { CircularProgressIndicator() }
                        is NovelDetailsState.Loaded -> NovelDetailsBody(
                            novel = s.novel,
                            chapters = s.chapters,
                            isRefreshing = s.isRefreshing,
                            onPickChapter = { openChapter(it) },
                        )
                        is NovelDetailsState.Failed -> FailedBody(message = s.message, source = resolvedSource, context = context)
                    }
                }
            }
        }
    }
}

@Composable
private fun NovelDetailsBody(
    novel: Novel,
    chapters: List<NovelChapter>,
    isRefreshing: Boolean,
    onPickChapter: (NovelChapter) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        item {
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                if (isRefreshing) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Refreshing…", style = MaterialTheme.typography.bodySmall)
                    }
                }
                novel.thumbnailUrl?.takeIf { it.isNotBlank() }?.let {
                    Box(modifier = Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally)) {
                        MangaCover(data = it, ratio = MangaCoverRatio.BOOK, modifier = Modifier.width(180.dp))
                    }
                    Spacer(Modifier.height(12.dp))
                }
                novel.author?.takeIf { it.isNotBlank() }?.let {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("by ", style = MaterialTheme.typography.bodySmall)
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
                statusLabel(novel.status)?.let { Text("Status: $it", style = MaterialTheme.typography.bodySmall) }
                novel.genres?.takeIf { it.isNotEmpty() }?.let {
                    Text("Genres: ${it.joinToString()}", style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(8.dp))
                novel.description?.takeIf { it.isNotBlank() }?.let {
                    SelectionContainer { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "${chapters.size} chapter${if (chapters.size == 1) "" else "s"}",
                    style = MaterialTheme.typography.titleSmall,
                )
                HorizontalDivider()
            }
        }
        items(items = chapters, key = { it.id ?: it.url.hashCode().toLong() }) { chapter ->
            ChapterRow(chapter = chapter, onClick = { onPickChapter(chapter) })
        }
    }
}

@Composable
private fun ChapterRow(chapter: NovelChapter, onClick: () -> Unit) {
    val nameColor = if (chapter.read) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = chapter.name,
            style = MaterialTheme.typography.bodyMedium,
            color = nameColor,
            modifier = Modifier.weight(1f),
        )
        if (chapter.bookmark) {
            Icon(
                Icons.Default.Bookmark,
                contentDescription = "Bookmarked",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
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
