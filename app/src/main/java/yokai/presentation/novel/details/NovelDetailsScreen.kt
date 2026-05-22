package yokai.presentation.novel.details

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.TopAppBar
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
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import eu.kanade.tachiyomi.util.compose.LocalBackPress
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import yokai.domain.novel.NovelChapterRepository
import yokai.domain.novel.NovelRepository
import yokai.novel.host.ChapterItem
import yokai.novel.host.LnPluginHost
import yokai.novel.host.SourceNovel
import yokai.novel.install.LnPluginInstaller
import yokai.novel.source.NovelSource
import yokai.novel.source.NovelSourceManager
import yokai.presentation.novel.browse.ChapterRead
import yokai.presentation.novel.browse.ChapterReader
import yokai.presentation.novel.browse.NovelDetails
import yokai.presentation.novel.browse.loadChapterForReading

private sealed interface DetailsState {
    /** Initial state: source/novel resolution + parseNovel call in flight. */
    object Loading : DetailsState
    /** parseNovel succeeded — show details + chapter list. */
    data class Viewing(val source: NovelSource, val novel: SourceNovel) : DetailsState
    /** User picked a chapter — show paragraphs. */
    data class Reading(val parent: Viewing, val chapter: ChapterItem, val read: ChapterRead) : DetailsState
    /** Source not installed, parseNovel failed, or other terminal error. */
    data class Failed(val message: String, val source: NovelSource? = null) : DetailsState
}

/**
 * Library entry point into the novel details + reader flow. The browse screen has its own
 * 4-state machine that includes source-picker and novel-list states; this screen skips those
 * and starts at parseNovel(url) for a known source.
 *
 * Reuses the [NovelDetails] and [ChapterReader] composables from the browse package.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovelDetailsScreen(sourceId: String, novelUrl: String) {
    val context = LocalContext.current
    val networkHelper = remember { Injekt.get<NetworkHelper>() }
    val installer = remember { Injekt.get<LnPluginInstaller>() }
    val manager = remember { Injekt.get<NovelSourceManager>() }
    val novelRepo = remember { Injekt.get<NovelRepository>() }
    val chapterRepo = remember { Injekt.get<NovelChapterRepository>() }
    val host = remember { LnPluginHost(context, networkHelper.client) }
    val scope = rememberCoroutineScope()
    val backPress = LocalBackPress.current

    DisposableEffect(host) { onDispose { host.destroy() } }

    var state by remember { mutableStateOf<DetailsState>(DetailsState.Loading) }
    var loading by remember { mutableStateOf(false) }
    // Hoisted so the TopAppBar action toggles the same sheet ChapterReader renders.
    var readerSettingsOpen by remember { mutableStateOf(false) }

    // Hydrate plugin host, then look up the source, then fetch the novel. All in one effect so
    // it triggers exactly once per screen open.
    LaunchedEffect(sourceId, novelUrl) {
        var resolvedSource: NovelSource? = null
        try {
            installer.loadInstalled(host)
            val source = manager.get(sourceId)
                ?: throw IllegalStateException("source not installed: $sourceId")
            resolvedSource = source
            val novel = source.parseNovel(novelUrl)
            state = DetailsState.Viewing(source, novel)
        } catch (e: Throwable) {
            state = DetailsState.Failed("${e.javaClass.simpleName}: ${e.message ?: ""}", resolvedSource)
        }
    }

    fun pickChapter(parent: DetailsState.Viewing, chapter: ChapterItem) {
        if (loading) return
        scope.launch {
            loading = true
            try {
                val read = loadChapterForReading(
                    source = parent.source,
                    novel = parent.novel,
                    chapter = chapter,
                    novelRepo = novelRepo,
                    chapterRepo = chapterRepo,
                )
                state = DetailsState.Reading(parent, chapter, read)
            } catch (e: Throwable) {
                state = DetailsState.Failed("${e.javaClass.simpleName}: ${e.message ?: ""}", parent.source)
            } finally { loading = false }
        }
    }

    fun goBack() {
        state = when (val s = state) {
            is DetailsState.Reading -> s.parent
            // Loading, Viewing, Failed: back exits the screen.
            else -> { backPress?.invoke(); return }
        }
    }

    val title = when (val s = state) {
        is DetailsState.Loading -> "Loading…"
        is DetailsState.Viewing -> s.novel.name ?: "Novel"
        is DetailsState.Reading -> s.chapter.name
        is DetailsState.Failed -> "Error"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = { goBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state is DetailsState.Reading) {
                        IconButton(onClick = { readerSettingsOpen = true }) {
                            Icon(Icons.Default.Settings, contentDescription = "Reader settings")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            if (loading) {
                Box(modifier = Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            when (val s = state) {
                is DetailsState.Loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
                is DetailsState.Viewing -> NovelDetails(
                    source = s.source,
                    novel = s.novel,
                    repo = novelRepo,
                    onPickChapter = { chapter -> pickChapter(s, chapter) },
                )
                is DetailsState.Reading -> ChapterReader(
                    paragraphs = s.read.paragraphs,
                    chapterId = s.read.chapterId,
                    initialProgress = s.read.initialProgress,
                    chapterRepo = chapterRepo,
                    settingsOpen = readerSettingsOpen,
                    onSettingsOpenChange = { readerSettingsOpen = it },
                )
                is DetailsState.Failed -> {
                    Text(text = s.message, color = MaterialTheme.colorScheme.error)
                    s.source?.site?.takeIf { it.isNotBlank() }?.let { siteUrl ->
                        Spacer(Modifier.height(4.dp))
                        OutlinedButton(onClick = {
                            context.startActivity(
                                WebViewActivity.newIntent(context, siteUrl, null, s.source.name),
                            )
                        }) { Text("Open ${s.source.name} in WebView") }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "If the source was uninstalled, reinstall it from Debug, LN plugin repo browse.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
