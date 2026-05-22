package yokai.presentation.novel.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.util.compose.LocalBackPress
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import yokai.data.novel.toNovel
import yokai.domain.novel.NovelRepository
import yokai.novel.host.ChapterItem
import yokai.novel.host.LnPluginHost
import yokai.novel.host.NovelItem
import yokai.novel.host.SourceNovel
import yokai.novel.install.LnPluginInstaller
import yokai.novel.source.NovelSource
import yokai.novel.source.NovelSourceManager

private sealed interface BrowseState {
    object PickingSource : BrowseState
    data class BrowsingNovels(val source: NovelSource, val novels: List<NovelItem>) : BrowseState
    data class ViewingNovel(val parent: BrowsingNovels, val novel: SourceNovel) : BrowseState
    data class ReadingChapter(val parent: ViewingNovel, val chapter: ChapterItem, val text: String) : BrowseState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovelBrowseScreen() {
    val context = LocalContext.current
    val networkHelper = remember { Injekt.get<NetworkHelper>() }
    val installer = remember { Injekt.get<LnPluginInstaller>() }
    val manager = remember { Injekt.get<NovelSourceManager>() }
    val repo = remember { Injekt.get<NovelRepository>() }
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
                val text = parent.parent.source.parseChapter(chapter.path)
                state = BrowseState.ReadingChapter(parent, chapter, text)
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
                    repo = repo,
                    onPickChapter = { chapter -> pickChapter(s, chapter) },
                )
                is BrowseState.ReadingChapter -> ChapterReader(s.chapter, s.text)
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
    // Reactive library state: if the novel is already saved we show "In library ✓" and let the
    // user remove it; otherwise show "Save to library" and insert on tap.
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

@Composable
private fun ChapterReader(chapter: ChapterItem, text: String) {
    // The text payload is raw HTML the plugin returns from parseChapter. Item 8 (the real text
    // reader) will render it properly; for now show the first 16 KB inline so the soak operator
    // can sanity-check that fetch + parse worked end-to-end.
    val truncated = text.length > 16_000
    val toShow = if (truncated) text.take(16_000) else text
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "length=${text.length} chars" + if (truncated) " (truncated to 16k)" else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        SelectionContainer(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = toShow,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
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
