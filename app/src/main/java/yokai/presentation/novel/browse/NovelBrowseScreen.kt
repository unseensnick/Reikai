package yokai.presentation.novel.browse

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import eu.kanade.tachiyomi.util.compose.LocalBackPress
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import eu.kanade.tachiyomi.core.storage.preference.collectAsState
import eu.kanade.tachiyomi.util.chapter.syncChaptersWithNovelSource
import yokai.data.DatabaseHandler
import yokai.data.novel.toNovel
import yokai.data.novel.toNovelChapter
import yokai.domain.novel.NovelChapterRepository
import yokai.domain.novel.NovelPreferences
import yokai.domain.novel.NovelRepository
import yokai.novel.host.ChapterItem
import yokai.novel.host.NovelItem
import yokai.novel.host.SourceNovel
import yokai.novel.install.LnPluginInstaller
import yokai.novel.source.NovelSource
import yokai.novel.source.NovelSourceManager
import yokai.novel.text.htmlToParagraphs
import yokai.presentation.component.ReikaiTopBar
import yokai.presentation.manga.components.MangaCover
import yokai.presentation.manga.components.MangaCoverRatio

private sealed interface BrowseState {
    object PickingSource : BrowseState
    /**
     * Browsing a source's listing. [query] is empty for popularNovels results, non-empty when
     * the search bar is active. Carrying it on the state lets back navigation restore the
     * exact same listing (popular vs search) without re-fetching.
     */
    data class BrowsingNovels(
        val source: NovelSource,
        val novels: List<NovelItem>,
        val query: String = "",
    ) : BrowseState
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
fun NovelBrowseScreen(initialSourceId: String? = null) {
    val context = LocalContext.current
    val installer = remember { Injekt.get<LnPluginInstaller>() }
    val manager = remember { Injekt.get<NovelSourceManager>() }
    val novelRepo = remember { Injekt.get<NovelRepository>() }
    val chapterRepo = remember { Injekt.get<NovelChapterRepository>() }
    val scope = rememberCoroutineScope()
    val backPress = LocalBackPress.current

    val sources by manager.sources.collectAsState(initial = manager.getAll())

    var state by remember { mutableStateOf<BrowseState>(BrowseState.PickingSource) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Remembered separately from `state` because pickSource failures leave the screen in
    // PickingSource (we never transitioned), but the user still needs an affordance to clear
    // CF on the source they actually tried.
    var lastAttemptedSource by remember { mutableStateOf<NovelSource?>(null) }
    // Hoisted so the TopAppBar action toggles the same sheet ChapterReader renders.
    var readerSettingsOpen by remember { mutableStateOf(false) }
    // Current source-filter values + popular/latest toggle, edited in the filter sheet and applied
    // to popularNovels. Reset to the plugin's declared defaults each time a source is picked.
    var filterValues by remember { mutableStateOf<Map<String, JsonElement>>(emptyMap()) }
    var showLatest by remember { mutableStateOf(false) }
    var filterSheetOpen by remember { mutableStateOf(false) }
    var settingsSheetOpen by remember { mutableStateOf(false) }

    fun pickSource(source: NovelSource) {
        if (loading) return
        lastAttemptedSource = source
        val defaults = defaultFilterValues(source.filters)
        filterValues = defaults
        showLatest = false
        scope.launch {
            loading = true; error = null
            try {
                val novels = source.popularNovels(1, buildOptions(source.filters, defaults, false))
                state = BrowseState.BrowsingNovels(source, novels, query = "")
            } catch (e: Throwable) {
                error = "${e.javaClass.simpleName}: ${e.message ?: ""}"
            } finally { loading = false }
        }
    }

    fun applyFilters(current: BrowseState.BrowsingNovels) {
        if (loading) return
        filterSheetOpen = false
        scope.launch {
            loading = true; error = null
            try {
                val novels = current.source.popularNovels(
                    1,
                    buildOptions(current.source.filters, filterValues, showLatest),
                )
                state = BrowseState.BrowsingNovels(current.source, novels, query = "")
            } catch (e: Throwable) {
                error = "${e.javaClass.simpleName}: ${e.message ?: ""}"
            } finally { loading = false }
        }
    }

    LaunchedEffect(Unit) {
        try { installer.ensureLoaded() } catch (_: Throwable) {}
        // When entered with a pre-picked source (Browse → Light novel sources tap), skip the
        // PickingSource UI and jump straight to the source's catalog.
        val id = initialSourceId ?: return@LaunchedEffect
        manager.get(id)?.let { pickSource(it) }
    }

    fun runSearch(current: BrowseState.BrowsingNovels, query: String) {
        if (loading) return
        scope.launch {
            loading = true; error = null
            try {
                val novels = if (query.isBlank()) {
                    current.source.popularNovels(1, buildOptions(current.source.filters, filterValues, showLatest))
                } else {
                    current.source.searchNovels(query, 1)
                }
                state = BrowseState.BrowsingNovels(current.source, novels, query = query)
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
                val read = loadChapterForReading(
                    source = parent.parent.source,
                    novel = parent.novel,
                    chapter = chapter,
                    novelRepo = novelRepo,
                    chapterRepo = chapterRepo,
                )
                state = BrowseState.ReadingChapter(
                    parent = parent,
                    chapter = chapter,
                    chapterId = read.chapterId,
                    initialProgress = read.initialProgress,
                    paragraphs = read.paragraphs,
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
            is BrowseState.BrowsingNovels -> {
                // Pre-picked entry has no PickingSource to fall back to: pop the controller so
                // back returns to the Light novel sources list that launched this screen.
                if (initialSourceId != null) { backPress?.invoke(); return }
                BrowseState.PickingSource
            }
            is BrowseState.ViewingNovel -> s.parent
            is BrowseState.ReadingChapter -> s.parent
        }
    }

    // System back traverses the in-screen stack (reader -> novel -> catalog -> source list) the same
    // way the toolbar arrow does, instead of popping the whole controller. Disabled only at the exit
    // points (the source picker, or a pre-picked source's catalog) so back then leaves the screen.
    val canGoBackInternally = when (state) {
        is BrowseState.PickingSource -> false
        is BrowseState.BrowsingNovels -> initialSourceId == null
        is BrowseState.ViewingNovel -> true
        is BrowseState.ReadingChapter -> true
    }
    BackHandler(enabled = canGoBackInternally) { goBack() }

    val title = when (val s = state) {
        is BrowseState.PickingSource -> "Browse LN sources"
        is BrowseState.BrowsingNovels -> s.source.name
        is BrowseState.ViewingNovel -> s.novel.name ?: "Novel"
        is BrowseState.ReadingChapter -> s.chapter.name
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
                    when (val s = state) {
                        is BrowseState.BrowsingNovels -> {
                            if (!s.source.pluginSettings.isNullOrEmpty()) {
                                IconButton(onClick = { settingsSheetOpen = true }) {
                                    Icon(Icons.Default.Settings, contentDescription = "Source settings")
                                }
                            }
                            IconButton(onClick = { filterSheetOpen = true }) {
                                Icon(Icons.Default.Tune, contentDescription = "Filters")
                            }
                        }
                        is BrowseState.ReadingChapter ->
                            IconButton(onClick = { readerSettingsOpen = true }) {
                                Icon(Icons.Default.Settings, contentDescription = "Reader settings")
                            }
                        else -> {}
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
            if (loading) {
                Box(modifier = Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            error?.let {
                Text(text = it, color = MaterialTheme.colorScheme.error)
                // Prefer the source from current state (it's the one the user is interacting
                // with); fall back to the last attempted source when an early-fail left the
                // screen in PickingSource with no state-side reference.
                val cfTarget = when (val s = state) {
                    is BrowseState.PickingSource -> lastAttemptedSource
                    is BrowseState.BrowsingNovels -> s.source
                    is BrowseState.ViewingNovel -> s.parent.source
                    is BrowseState.ReadingChapter -> s.parent.parent.source
                }
                cfTarget?.site?.takeIf { it.isNotBlank() }?.let { siteUrl ->
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(onClick = {
                        context.startActivity(
                            WebViewActivity.newIntent(context, siteUrl, null, cfTarget.name),
                        )
                    }) { Text("Open ${cfTarget.name} in WebView") }
                }
                Spacer(Modifier.height(8.dp))
            }
            when (val s = state) {
                is BrowseState.PickingSource -> {
                    if (initialSourceId == null) {
                        SourcePicker(sources = sources, onPick = ::pickSource)
                    }
                }
                is BrowseState.BrowsingNovels -> NovelList(
                    novels = s.novels,
                    query = s.query,
                    onSearch = { runSearch(s, it) },
                    onPick = { item -> pickNovel(s, item) },
                )
                is BrowseState.ViewingNovel -> NovelDetails(
                    source = s.parent.source,
                    novel = s.novel,
                    repo = novelRepo,
                    chapterRepo = chapterRepo,
                    onPickChapter = { chapter -> pickChapter(s, chapter) },
                )
                is BrowseState.ReadingChapter -> ChapterReader(
                    paragraphs = s.paragraphs,
                    chapterId = s.chapterId,
                    initialProgress = s.initialProgress,
                    chapterRepo = chapterRepo,
                    settingsOpen = readerSettingsOpen,
                    onSettingsOpenChange = { readerSettingsOpen = it },
                )
            }

            val current = state
            if (filterSheetOpen && current is BrowseState.BrowsingNovels) {
                NovelSourceFilterSheet(
                    filters = current.source.filters,
                    values = filterValues,
                    showLatest = showLatest,
                    onValueChange = { key, value -> filterValues = filterValues + (key to value) },
                    onShowLatestChange = { showLatest = it },
                    onApply = { applyFilters(current) },
                    onReset = {
                        filterValues = defaultFilterValues(current.source.filters)
                        showLatest = false
                    },
                    onDismiss = { filterSheetOpen = false },
                )
            }
            if (settingsSheetOpen && current is BrowseState.BrowsingNovels) {
                NovelSourceSettingsSheet(
                    source = current.source,
                    onDismiss = { settingsSheetOpen = false },
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
private fun NovelList(
    novels: List<NovelItem>,
    query: String,
    onSearch: (String) -> Unit,
    onPick: (NovelItem) -> Unit,
) {
    var queryDraft by remember(query) { mutableStateOf(query) }
    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = queryDraft,
            onValueChange = { queryDraft = it },
            label = { Text("Search") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (queryDraft.isNotEmpty()) {
                    IconButton(onClick = {
                        queryDraft = ""
                        // Empty submit resets the listing to popularNovels in the screen-level
                        // handler; we don't run a search-with-empty-query.
                        onSearch("")
                    }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear")
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch(queryDraft) }),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        if (novels.isEmpty()) {
            Text(
                text = if (query.isBlank()) "Empty result." else "No matches for \"$query\".",
                style = MaterialTheme.typography.bodyMedium,
            )
            return@Column
        }
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(items = novels, key = { it.path }) { item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(item) }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MangaCover(
                    data = item.cover,
                    ratio = MangaCoverRatio.BOOK,
                    modifier = Modifier.width(56.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = item.path,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider()
            }
        }
    }
}

/**
 * Result of the chapter-load pipeline: ensures novel + chapter rows exist (upsert) so progress
 * has somewhere to live, then fetches and paragraph-parses the chapter HTML.
 */
internal data class ChapterRead(
    val chapterId: Long,
    val initialProgress: Int,
    val paragraphs: List<String>,
)

internal suspend fun loadChapterForReading(
    source: NovelSource,
    novel: SourceNovel,
    chapter: ChapterItem,
    novelRepo: NovelRepository,
    chapterRepo: NovelChapterRepository,
): ChapterRead {
    val novelId = novelRepo.insertOrGet(novel.toNovel(sourceId = source.id, favorite = false))?.id
        ?: error("failed to insert novel")
    val existingChapter = chapterRepo.getByUrlAndNovelId(chapter.path, novelId)
    val chapterId = existingChapter?.id
        ?: chapterRepo.insert(chapter.toNovelChapter(novelId))
        ?: error("failed to insert chapter")
    val html = source.parseChapter(chapter.path)
    val paragraphs = htmlToParagraphs(html)
    return ChapterRead(
        chapterId = chapterId,
        initialProgress = existingChapter?.lastTextProgress ?: 0,
        paragraphs = paragraphs,
    )
}

@Composable
internal fun NovelDetails(
    source: NovelSource,
    novel: SourceNovel,
    repo: NovelRepository,
    chapterRepo: NovelChapterRepository,
    onPickChapter: (ChapterItem) -> Unit,
) {
    val chapters = novel.chapters ?: emptyList()
    // Probe screen (yokai/presentation/novel/*) is exempt from the compose-port DI rule, so
    // Injekt here is acceptable. Needed to persist chapters at add-to-library time.
    val handler = remember { Injekt.get<DatabaseHandler>() }
    val savedNovel by remember(novel.path, source.id) {
        repo.getByUrlAndSourceAsFlow(novel.path, source.id)
    }.collectAsState(initial = null)
    val inLibrary = savedNovel?.favorite == true
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }

    // Persist a library novel's chapter list whenever its details open, so the library badge
    // populates without a manual pull-to-refresh or removing/re-adding it (mirrors LNReader
    // caching chapters on open). syncChaptersWithNovelSource diffs against the DB, so re-running
    // per open is cheap and idempotent. Non-library novels are skipped: the library view only
    // counts favorites, so there's no badge to feed and no reason to write their rows yet.
    val persistedFavorite = savedNovel?.takeIf { it.favorite }
    LaunchedEffect(persistedFavorite?.id, chapters.size) {
        val target = persistedFavorite ?: return@LaunchedEffect
        if (chapters.isEmpty()) return@LaunchedEffect
        runCatching {
            syncChaptersWithNovelSource(
                rawSourceChapters = chapters,
                novel = target,
                source = source,
                novelChapterRepository = chapterRepo,
                novelRepository = repo,
                handler = handler,
            )
        }
    }

    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        item {
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                novel.cover?.takeIf { it.isNotBlank() }?.let {
                    Box(
                        modifier = Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally),
                    ) {
                        MangaCover(
                            data = it,
                            ratio = MangaCoverRatio.BOOK,
                            modifier = Modifier.width(180.dp),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }
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
                                    // Resolve the row FRESH (not from the stale collectAsState value)
                                    // so a concurrent/just-created row is reused instead of duplicated.
                                    val resolved = repo.insertOrGet(novel.toNovel(sourceId = source.id, favorite = true))
                                    val persisted = when {
                                        resolved == null -> null
                                        resolved.favorite -> resolved
                                        else -> resolved.copy(favorite = true).also { repo.update(it) }
                                    }
                                    // Persist the already-parsed chapters so the library badge has
                                    // something to count (library_novel_view sums novel_chapters).
                                    // Mirrors LNReader insertNovelAndChapters at add time; the
                                    // chapters are in memory so no extra fetch is needed. Best-effort:
                                    // a sync failure must not block the favorite toggle.
                                    if (persisted != null && chapters.isNotEmpty()) {
                                        runCatching {
                                            syncChaptersWithNovelSource(
                                                rawSourceChapters = chapters,
                                                novel = persisted,
                                                source = source,
                                                novelChapterRepository = chapterRepo,
                                                novelRepository = repo,
                                                handler = handler,
                                            )
                                        }
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
@OptIn(FlowPreview::class, ExperimentalMaterial3Api::class)
@Composable
internal fun ChapterReader(
    paragraphs: List<String>,
    chapterId: Long,
    initialProgress: Int,
    chapterRepo: NovelChapterRepository,
    settingsOpen: Boolean,
    onSettingsOpenChange: (Boolean) -> Unit,
) {
    if (paragraphs.isEmpty()) {
        Text("(no readable text in chapter)")
        return
    }
    val prefs = remember { Injekt.get<NovelPreferences>() }
    val fontSize by prefs.readerFontSize().collectAsState()
    val lineSpacing by prefs.readerLineSpacing().collectAsState()
    val themeMode by prefs.readerTheme().collectAsState()

    val systemDark = isSystemInDarkTheme()
    val (bg, fg) = readerColors(themeMode, systemDark)

    val lazyListState = rememberLazyListState()
    val sheetState = rememberModalBottomSheetState()

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

    Box(modifier = Modifier.fillMaxSize().background(bg)) {
        SelectionContainer(modifier = Modifier.fillMaxSize()) {
            LazyColumn(state = lazyListState, modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp)) {
                items(items = paragraphs, key = { it.hashCode() }) { p ->
                    Text(
                        text = p,
                        color = fg,
                        fontSize = fontSize.sp,
                        lineHeight = (fontSize * lineSpacing).sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                    )
                }
            }
        }
    }

    if (settingsOpen) {
        ModalBottomSheet(
            onDismissRequest = { onSettingsOpenChange(false) },
            sheetState = sheetState,
        ) {
            ReaderSettingsSheet(
                fontSize = fontSize,
                onFontSize = { v -> prefs.readerFontSize().set(v) },
                lineSpacing = lineSpacing,
                onLineSpacing = { v -> prefs.readerLineSpacing().set(v) },
                theme = themeMode,
                onTheme = { v -> prefs.readerTheme().set(v) },
            )
        }
    }
}

@Composable
private fun ReaderSettingsSheet(
    fontSize: Int,
    onFontSize: (Int) -> Unit,
    lineSpacing: Float,
    onLineSpacing: (Float) -> Unit,
    theme: Int,
    onTheme: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
        Text("Font size: ${fontSize}sp", style = MaterialTheme.typography.titleSmall)
        Slider(
            value = fontSize.toFloat(),
            onValueChange = { onFontSize(it.toInt()) },
            valueRange = 12f..24f,
            steps = 11,
        )
        Spacer(Modifier.height(8.dp))
        Text("Line spacing: ${"%.1f".format(lineSpacing)}x", style = MaterialTheme.typography.titleSmall)
        Slider(
            value = lineSpacing,
            onValueChange = { onLineSpacing(it) },
            valueRange = 1.0f..2.5f,
            steps = 14,
        )
        Spacer(Modifier.height(12.dp))
        Text("Theme", style = MaterialTheme.typography.titleSmall)
        listOf(0 to "Follow system", 1 to "Light", 2 to "Dark").forEach { (code, label) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onTheme(code) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = theme == code, onClick = { onTheme(code) })
                Spacer(Modifier.width(8.dp))
                Text(label)
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** Background / foreground pair for the reader based on theme mode + system dark setting. */
@Composable
private fun readerColors(themeMode: Int, systemDark: Boolean): Pair<Color, Color> = when (themeMode) {
    1 -> Color.White to Color.Black
    2 -> Color(0xFF101010) to Color(0xFFE0E0E0)
    else -> if (systemDark) Color(0xFF101010) to Color(0xFFE0E0E0) else Color.White to Color.Black
}

/**
 * Current value for each filter, seeded from the plugin's declared `value`. Drives both the filter
 * sheet's initial state and the options sent to [popularNovels] when a source is first opened.
 */
internal fun defaultFilterValues(filters: JsonObject?): Map<String, JsonElement> {
    if (filters == null) return emptyMap()
    return buildMap {
        filters.forEach { (key, schema) ->
            if (schema is JsonObject) schema["value"]?.let { put(key, it) }
        }
    }
}

/**
 * Builds a `popularNovels` options JSON from the plugin's filter schema and the user's current
 * [values]. Each filter is wrapped as `{key: {value: <current-or-default>}}` inside the top-level
 * `filters` object so the plugin body can read `options.filters.X.value`. [showLatest] maps to
 * lnreader's `showLatestNovels`. Sources without filters get `{filters: {}}` plus the toggle.
 */
internal fun buildOptions(
    filters: JsonObject?,
    values: Map<String, JsonElement>,
    showLatest: Boolean,
): String {
    val opts = buildJsonObject {
        put(
            "filters",
            buildJsonObject {
                filters?.forEach { (key, schema) ->
                    if (schema is JsonObject) {
                        val value = values[key] ?: schema["value"]
                        if (value != null) put(key, buildJsonObject { put("value", value) })
                    }
                }
            },
        )
        put("showLatestNovels", showLatest)
    }
    return opts.toString()
}
