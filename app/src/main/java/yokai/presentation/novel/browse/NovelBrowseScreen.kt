package yokai.presentation.novel.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.util.compose.LocalBackPress
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import yokai.data.novel.toNovel
import yokai.data.novel.toNovelChapter
import yokai.domain.novel.NovelChapterRepository
import yokai.domain.novel.NovelRepository
import yokai.novel.host.ChapterItem
import yokai.novel.host.LnPluginHost
import yokai.novel.host.NovelItem
import yokai.novel.host.SourceNovel
import yokai.novel.install.LnPluginInstaller
import yokai.novel.source.NovelSource
import yokai.novel.source.NovelSourceManager
import yokai.novel.text.htmlToParagraphs

private sealed interface BrowseState {
    object PickingSource : BrowseState
    data class BrowsingNovels(val source: NovelSource, val novels: List<NovelItem>) : BrowseState
    data class ViewingNovel(val parent: BrowsingNovels, val novel: SourceNovel) : BrowseState
    /**
     * Slice H state. Carries the persisted chapter row id + initial scroll progress so the
     * reader can resume; paragraphs are pre-parsed at transition time so the composable just
     * renders them.
     */
    data class ReadingChapter(
        val parent: ViewingNovel,
        val chapter: ChapterItem,
        val chapterId: Long,
        val initialProgress: Int,
        val paragraphs: List<String>,
    ) : BrowseState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovelBrowseScreen() {
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

    LaunchedEffect(host) {
        try { installer.loadInstalled(host) } catch (_: Throwable) {}
    }

    val sources by manager.sources.collectAsState(initial = manager.getAll())

    var state by remember { mutableStateOf<BrowseState>(BrowseState.PickingSource) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun pickSource(source: NovelSource) {
        if (loading) return
        scope.launch {
            loading = true; error = null
            try {
                val novels = source.popularNovels(1, buildDefaultOptions(source.filters))
                state = BrowseState.BrowsingNovels(source, novels)
            } catch (e: Throwable) {
                error = "${e.javaClass.simpleName}: ${e.message ?: ""}"
            } finally { loading = false }
        }
    }

    fun pickNovel(parent: BrowseState.BrowsingNovels, item: NovelItem) {
        if (loading) return
        scope.launch {
            loading = true; error = null
            try {
                val novel = parent.source.parseNovel(item.path)
                state = BrowseState.ViewingNovel(parent, novel)
            } catch (e: Throwable) {
                error = "${e.javaClass.simpleName}: ${e.message ?: ""}"
            } finally { loading = false }
        }
    }

    fun pickChapter(parent: BrowseState.ViewingNovel, chapter: ChapterItem) {
        if (loading) return
        scope.launch {
            loading = true; error = null
            try {
                val source = parent.parent.source
                // 1. Upsert novel row. Non-favorited if not in library yet — we need a row to
                //    anchor the chapter's foreign key.
                val existingNovel = novelRepo.getByUrlAndSource(parent.novel.path, source.id)
                val novelId = existingNovel?.id
                    ?: novelRepo.insert(parent.novel.toNovel(sourceId = source.id, favorite = false))
                    ?: error("failed to insert novel")
                // 2. Upsert chapter row so saved progress has somewhere to live across sessions.
                val existingChapter = chapterRepo.getByUrlAndNovelId(chapter.path, novelId)
                val chapterId = existingChapter?.id
                    ?: chapterRepo.insert(chapter.toNovelChapter(novelId))
                    ?: error("failed to insert chapter")
                // 3. Fetch + parse.
                val html = source.parseChapter(chapter.path)
                val paragraphs = htmlToParagraphs(html)
                state = BrowseState.ReadingChapter(
                    parent = parent,
                    chapter = chapter,
                    chapterId = chapterId,
                    initialProgress = existingChapter?.lastTextProgress ?: 0,
                    paragraphs = paragraphs,
                )
            } catch (e: Throwable) {
                error = "${e.javaClass.simpleName}: ${e.message ?: ""}"
            } finally { loading = false }
        }
    }

    fun goBack() {
        error = null
        state = when (val s = state) {
            is BrowseState.PickingSource -> { backPress?.invoke(); return }
            is BrowseState.BrowsingNovels -> BrowseState.PickingSource
            is BrowseState.ViewingNovel -> s.parent
            is BrowseState.ReadingChapter -> s.parent
        }
    }

    val title = when (val s = state) {
        is BrowseState.PickingSource -> "Browse LN sources"
        is BrowseState.BrowsingNovels -> s.source.name
        is BrowseState.ViewingNovel -> s.novel.name ?: "Novel"
        is BrowseState.ReadingChapter -> s.chapter.name
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
            error?.let {
                Text(text = it, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
            }
            when (val s = state) {
                is BrowseState.PickingSource -> SourcePicker(sources = sources, onPick = ::pickSource)
                is BrowseState.BrowsingNovels -> NovelList(s.novels) { item -> pickNovel(s, item) }
                is BrowseState.ViewingNovel -> NovelDetails(
                    source = s.parent.source,
                    novel = s.novel,
                    repo = novelRepo,
                    onPickChapter = { chapter -> pickChapter(s, chapter) },
                )
                is BrowseState.ReadingChapter -> ChapterReader(
                    paragraphs = s.paragraphs,
                    chapterId = s.chapterId,
                    initialProgress = s.initialProgress,
                    chapterRepo = chapterRepo,
                )
            }
        }
    }
}

@Composable
private fun SourcePicker(sources: List<NovelSource>, onPick: (NovelSource) -> Unit) {
    if (sources.isEmpty()) {
        Text("No LN sources installed. Use Debug → LN plugin repo browse to install one.")
        return
    }
    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        items(items = sources, key = { it.id }) { source ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(source) }
                    .padding(vertical = 12.dp),
            ) {
                Text(source.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "${source.id}  •  v${source.version}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun NovelList(novels: List<NovelItem>, onPick: (NovelItem) -> Unit) {
    if (novels.isEmpty()) {
        Text("Empty result.")
        return
    }
    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        items(items = novels, key = { it.path }) { item ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(item) }
                    .padding(vertical = 10.dp),
            ) {
                Text(item.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = item.path,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun NovelDetails(
    source: NovelSource,
    novel: SourceNovel,
    repo: NovelRepository,
    onPickChapter: (ChapterItem) -> Unit,
) {
    val chapters = novel.chapters ?: emptyList()
    val savedNovel by remember(novel.path, source.id) {
        repo.getByUrlAndSourceAsFlow(novel.path, source.id)
    }.collectAsState(initial = null)
    val inLibrary = savedNovel?.favorite == true
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }

    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        item {
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                novel.author?.takeIf { it.isNotBlank() }?.let {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("by ", style = MaterialTheme.typography.bodySmall)
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
                novel.status?.let { Text("Status: $it", style = MaterialTheme.typography.bodySmall) }
                novel.genres?.takeIf { it.isNotBlank() }?.let { Text("Genres: $it", style = MaterialTheme.typography.bodySmall) }
                Spacer(Modifier.height(8.dp))
                novel.summary?.takeIf { it.isNotBlank() }?.let {
                    SelectionContainer { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
                Spacer(Modifier.height(12.dp))
                if (inLibrary) {
                    OutlinedButton(
                        enabled = !busy,
                        onClick = {
                            val current = savedNovel ?: return@OutlinedButton
                            scope.launch {
                                busy = true
                                try { repo.update(current.copy(favorite = false)) }
                                finally { busy = false }
                            }
                        },
                    ) { Text("In library ✓  •  tap to remove") }
                } else {
                    Button(
                        enabled = !busy,
                        onClick = {
                            scope.launch {
                                busy = true
                                try {
                                    val existing = savedNovel
                                    if (existing == null) {
                                        repo.insert(novel.toNovel(sourceId = source.id, favorite = true))
                                    } else {
                                        repo.update(existing.copy(favorite = true))
                                    }
                                } finally { busy = false }
                            }
                        },
                    ) { Text("Save to library") }
                }
                Spacer(Modifier.height(12.dp))
                Text("${chapters.size} chapter${if (chapters.size == 1) "" else "s"}", style = MaterialTheme.typography.titleSmall)
                HorizontalDivider()
            }
        }
        itemsIndexed(items = chapters, key = { idx, c -> "$idx:${c.path}" }) { _, chapter ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPickChapter(chapter) }
                    .padding(vertical = 10.dp),
            ) {
                Text(chapter.name, style = MaterialTheme.typography.bodyMedium)
                chapter.releaseTime?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider()
        }
    }
}

/**
 * Renders parseChapter output as a scrollable column of paragraphs. Progress (first-visible
 * paragraph index, expressed as 0..10000 hundredths of a percent) auto-saves on a 1-second
 * debounce so re-opening the chapter scrolls back to where the user left off.
 *
 * Font / theme / size controls live in a future polish slice; this one's about the rendering
 * + persistence loop.
 */
@OptIn(FlowPreview::class)
@Composable
private fun ChapterReader(
    paragraphs: List<String>,
    chapterId: Long,
    initialProgress: Int,
    chapterRepo: NovelChapterRepository,
) {
    if (paragraphs.isEmpty()) {
        Text("(no readable text in chapter)")
        return
    }
    val lazyListState = rememberLazyListState()

    // Restore scroll position on first composition (or when the chapter changes).
    LaunchedEffect(chapterId, paragraphs.size) {
        val targetIdx = (initialProgress.toLong() * paragraphs.size / 10_000L).toInt()
            .coerceIn(0, paragraphs.lastIndex)
        if (targetIdx > 0) lazyListState.scrollToItem(targetIdx)
    }

    // Auto-save scroll progress while reading.
    LaunchedEffect(chapterId, paragraphs.size) {
        snapshotFlow { lazyListState.firstVisibleItemIndex }
            .debounce(1_000)
            .distinctUntilChanged()
            .collect { idx ->
                val progress = (idx.toLong() * 10_000L / paragraphs.size.coerceAtLeast(1)).toInt()
                chapterRepo.setLastTextProgress(chapterId, progress)
            }
    }

    SelectionContainer(modifier = Modifier.fillMaxSize()) {
        LazyColumn(state = lazyListState, modifier = Modifier.fillMaxSize()) {
            items(items = paragraphs, key = { it.hashCode() }) { p ->
                Text(
                    text = p,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                )
            }
        }
    }
}

/**
 * Builds a `popularNovels` options JSON from a plugin's filter schema. Each entry in
 * `source.filters` exposes its default `value`; we wrap each as `{key: {value: <default>}}`
 * inside the top-level `filters` object so the plugin's body can dereference
 * `options.filters.X.value` without crashing.
 *
 * Sources that don't declare filters get `{}` plus `showLatestNovels=false`.
 */
private fun buildDefaultOptions(filters: JsonObject?): String {
    val opts = buildJsonObject {
        put(
            "filters",
            buildJsonObject {
                filters?.forEach { (key, schema) ->
                    if (schema is JsonObject) {
                        val defaultValue = schema["value"]
                        if (defaultValue != null) {
                            put(key, buildJsonObject { put("value", defaultValue) })
                        }
                    }
                }
            },
        )
        put("showLatestNovels", false)
    }
    return opts.toString()
}
