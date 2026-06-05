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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import eu.kanade.tachiyomi.util.compose.LocalBackPress
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import yokai.novel.host.NovelItem
import yokai.novel.install.LnPluginInstaller
import yokai.novel.source.NovelSource
import yokai.novel.source.NovelSourceManager
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
}

/**
 * Source catalogue browse. Tapping a result routes to the shared [NovelDetailsScreen] via
 * [onSelectNovel] (wired by the host controller to push NovelDetailsController), so browse, library,
 * and global search all open the same polished details + reader flow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovelBrowseScreen(
    initialSourceId: String? = null,
    onSelectNovel: (sourceId: String, novelUrl: String) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val installer = remember { Injekt.get<LnPluginInstaller>() }
    val manager = remember { Injekt.get<NovelSourceManager>() }
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
        }
    }

    // System back traverses the in-screen stack (catalog -> source list) the same way the toolbar
    // arrow does, instead of popping the whole controller. Disabled only at the exit points (the
    // source picker, or a pre-picked source's catalog) so back then leaves the screen.
    val canGoBackInternally = when (state) {
        is BrowseState.PickingSource -> false
        is BrowseState.BrowsingNovels -> initialSourceId == null
    }
    BackHandler(enabled = canGoBackInternally) { goBack() }

    val title = when (val s = state) {
        is BrowseState.PickingSource -> "Browse LN sources"
        is BrowseState.BrowsingNovels -> s.source.name
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
                    val s = state
                    if (s is BrowseState.BrowsingNovels) {
                        if (!s.source.pluginSettings.isNullOrEmpty()) {
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
                    onPick = { item -> onSelectNovel(s.source.id, item.path) },
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
