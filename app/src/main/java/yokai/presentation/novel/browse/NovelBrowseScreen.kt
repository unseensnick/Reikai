package yokai.presentation.novel.browse

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items as staggeredItems
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import eu.kanade.tachiyomi.ui.library.LibraryItem
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import eu.kanade.tachiyomi.util.compose.LocalBackPress
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import yokai.novel.host.NovelItem
import yokai.novel.source.NovelSource
import yokai.presentation.component.ReikaiTopBar
import yokai.presentation.library.settings.tabs.columnsForGridValue
import yokai.presentation.manga.components.MangaCover
import yokai.presentation.manga.components.MangaCoverRatio
import yokai.util.Screen

/**
 * Source catalogue browse. Tapping a result routes to the shared [NovelDetailsScreen] via
 * [onSelectNovel] (wired by the host controller to push NovelDetailsController), so browse, library,
 * and global search all open the same polished details + reader flow. State lives in
 * [NovelBrowseScreenModel]; this screen is the renderer.
 */
class NovelBrowseScreen(
    private val initialSourceId: String? = null,
    private val onSelectNovel: (sourceId: String, novelUrl: String) -> Unit = { _, _ -> },
) : Screen() {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val backPress = LocalBackPress.current
        val screenModel = rememberScreenModel { NovelBrowseScreenModel(initialSourceId) }
        val state by screenModel.state.collectAsState()

        // Pure-UI sheet visibility; the sheet contents read filter/source state from the model.
        var filterSheetOpen by remember { mutableStateOf(false) }
        var settingsSheetOpen by remember { mutableStateOf(false) }

        fun goBack() {
            when (state.mode) {
                is NovelBrowseState.Mode.PickingSource -> backPress?.invoke()
                is NovelBrowseState.Mode.BrowsingNovels -> {
                    // Pre-picked entry has no PickingSource to fall back to: pop the controller so
                    // back returns to the Light novel sources list that launched this screen.
                    if (initialSourceId != null) backPress?.invoke() else screenModel.goBackToPicker()
                }
            }
        }

        // System back traverses the in-screen stack (catalog -> source list) the same way the toolbar
        // arrow does, instead of popping the whole controller. Disabled at the exit points (the source
        // picker, or a pre-picked source's catalog) so back then leaves the screen.
        val canGoBackInternally = state.mode is NovelBrowseState.Mode.BrowsingNovels && initialSourceId == null
        BackHandler(enabled = canGoBackInternally) { goBack() }

        val title = when (val m = state.mode) {
            is NovelBrowseState.Mode.PickingSource -> "Browse LN sources"
            is NovelBrowseState.Mode.BrowsingNovels -> m.source.name
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
                        val m = state.mode
                        if (m is NovelBrowseState.Mode.BrowsingNovels) {
                            val isList = state.display.layout == LibraryItem.LAYOUT_LIST
                            IconButton(onClick = { screenModel.toggleListGrid() }) {
                                Icon(
                                    imageVector = if (isList) Icons.Default.GridView else Icons.AutoMirrored.Filled.ViewList,
                                    contentDescription = if (isList) "Switch to grid" else "Switch to list",
                                )
                            }
                            if (!m.source.pluginSettings.isNullOrEmpty()) {
                                IconButton(onClick = { settingsSheetOpen = true }) {
                                    Icon(Icons.Default.Settings, contentDescription = "Source settings")
                                }
                            }
                            IconButton(onClick = { filterSheetOpen = true }) {
                                Icon(Icons.Default.Tune, contentDescription = "Filters")
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
                if (state.loading) {
                    Box(modifier = Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                state.error?.let {
                    Text(text = it, color = MaterialTheme.colorScheme.error)
                    // Prefer the source from current mode (the one the user is interacting with); fall
                    // back to the last attempted source when an early-fail left the screen in the picker.
                    val cfTarget = when (val m = state.mode) {
                        is NovelBrowseState.Mode.PickingSource -> state.lastAttemptedSource
                        is NovelBrowseState.Mode.BrowsingNovels -> m.source
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
                when (val m = state.mode) {
                    is NovelBrowseState.Mode.PickingSource -> {
                        if (initialSourceId == null) {
                            SourcePicker(sources = state.sources, onPick = screenModel::pickSource)
                        }
                    }
                    is NovelBrowseState.Mode.BrowsingNovels -> NovelList(
                        novels = m.novels,
                        query = m.query,
                        display = state.display,
                        onSearch = { screenModel.runSearch(it) },
                        onPick = { item -> onSelectNovel(m.source.id, item.path) },
                        onLongPick = { /* long-press add-to-library lands in B2e */ },
                    )
                }

                val current = state.mode
                if (filterSheetOpen && current is NovelBrowseState.Mode.BrowsingNovels) {
                    NovelSourceFilterSheet(
                        filters = current.source.filters,
                        values = current.filterValues,
                        showLatest = current.showLatest,
                        onValueChange = { key, value -> screenModel.setFilterValue(key, value) },
                        onShowLatestChange = { screenModel.setShowLatest(it) },
                        onApply = {
                            screenModel.applyFilters()
                            filterSheetOpen = false
                        },
                        onReset = { screenModel.resetFilters() },
                        onDismiss = { filterSheetOpen = false },
                    )
                }
                if (settingsSheetOpen && current is NovelBrowseState.Mode.BrowsingNovels) {
                    NovelSourceSettingsSheet(
                        source = current.source,
                        onDismiss = { settingsSheetOpen = false },
                    )
                }
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
    display: NovelBrowseState.Display,
    onSearch: (String) -> Unit,
    onPick: (NovelItem) -> Unit,
    onLongPick: (NovelItem) -> Unit,
) {
    var queryDraft by remember(query) { mutableStateOf(query) }
    Column(modifier = Modifier.fillMaxSize()) {
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
        when {
            display.layout == LibraryItem.LAYOUT_LIST -> LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                items(items = novels, key = { it.path }) { item ->
                    NovelResultRow(item, onClick = { onPick(item) }, onLongClick = { onLongPick(item) })
                    HorizontalDivider()
                }
            }
            display.staggered && !display.uniformGrid -> {
                val columns = columnsForGridValue(display.gridSize, LocalConfiguration.current.screenWidthDp)
                LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Fixed(columns),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalItemSpacing = 4.dp,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    staggeredItems(items = novels, key = { it.path }) { item ->
                        NovelBrowseGridCell(
                            item = item,
                            inLibrary = false,
                            libraryLayout = display.layout,
                            outlineOnCovers = display.outlineOnCovers,
                            coverAspectRatio = null,
                            onClick = { onPick(item) },
                            onLongClick = { onLongPick(item) },
                        )
                    }
                }
            }
            else -> {
                val columns = columnsForGridValue(display.gridSize, LocalConfiguration.current.screenWidthDp)
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    gridItems(items = novels, key = { it.path }) { item ->
                        NovelBrowseGridCell(
                            item = item,
                            inLibrary = false,
                            libraryLayout = display.layout,
                            outlineOnCovers = display.outlineOnCovers,
                            coverAspectRatio = if (display.uniformGrid) MangaCoverRatio.BOOK else null,
                            onClick = { onPick(item) },
                            onLongClick = { onLongPick(item) },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NovelResultRow(item: NovelItem, onClick: () -> Unit, onLongClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
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
}

/**
 * Current value for each filter, seeded from the plugin's declared `value`. Drives both the filter
 * sheet's initial state and the options sent to [NovelSource.popularNovels] when a source is first opened.
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
