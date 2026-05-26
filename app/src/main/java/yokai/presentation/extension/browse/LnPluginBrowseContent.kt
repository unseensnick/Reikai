package yokai.presentation.extension.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LibraryBooks
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import coil3.compose.AsyncImage
import cafe.adriel.voyager.core.screen.Screen
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.util.system.getResourceColor
import eu.kanade.tachiyomi.util.system.toast
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import yokai.novel.host.LnPluginHost
import yokai.novel.registry.LnRegistryEntry

/**
 * Aggregate "available LN plugins" list rendered inside the Browse → Extensions bottom sheet's
 * Light novels sub-tab (Phase 8 follow-up CR6). Reads from [LnPluginBrowseScreenModel] which
 * fetches every URL in `NovelPreferences.addedRepoUrls` and groups by language.
 *
 * Owns the [LnPluginHost] lifecycle (Context-dependent WebView + Coil scope) via `remember` +
 * `DisposableEffect`. The screen model only orchestrates data; install / uninstall pass the
 * host down so the model stays Context-free.
 *
 * Hosted via a [ComposeView] from the legacy `ExtensionBottomSheet`, NOT from a Voyager
 * `Screen`. Uses an anonymous Screen wrapper for `rememberScreenModel` to work.
 */
@Composable
fun LnPluginBrowseContent(
    searchQuery: String,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    // Voyager rememberScreenModel needs a Screen scope. Wrap in a no-op anonymous Screen.
    // The Screen is never navigated to; we just need its scope for the screen model lifecycle
    // to align with the composable's parent recompose lifecycle.
    val screenAnchor = remember {
        object : Screen {
            override val key: String = "ln-plugin-browse-anchor"
            @Composable override fun Content() {}
        }
    }
    with(screenAnchor) {
        val screenModel = rememberScreenModel { LnPluginBrowseScreenModel() }
        val state by screenModel.state.collectAsState()
        val busyEntryIds by screenModel.busyEntryIds.collectAsState()
        val context = LocalContext.current
        val networkHelper = remember { Injekt.get<NetworkHelper>() }
        val host = remember { LnPluginHost(context, networkHelper.client) }
        DisposableEffect(host) { onDispose { host.destroy() } }

        var lastFailedToastCount by remember { mutableStateOf(0) }

        // Surface a one-shot toast when a fresh derive pass had failed repos. Only fires on the
        // transition zero → nonzero so a sticky-failed-repo doesn't toast every recompose.
        LaunchedEffect(state) {
            val failed = (state as? LnPluginBrowseScreenModel.State.Success)?.failedRepoCount ?: 0
            if (failed > 0 && failed != lastFailedToastCount) {
                context.toast("$failed repo${if (failed == 1) "" else "s"} failed to load")
            }
            lastFailedToastCount = failed
        }

        // The legacy ExtensionBottomSheet uses `?attr/colorPrimaryVariant` as its background +
        // `?attr/actionBarTintColor` as the foreground tint — both unrelated to the M3
        // ColorScheme YokaiTheme derives from `?android:textColorPrimary`. Wrap content in a
        // Surface with those colors so Text composables (which default to LocalContentColor =
        // surfaceTint-derived) render against the actual sheet surface, not against the
        // activity's primary surface. Same direct-attr pattern as Phase 6 F12 used for the
        // library dialogs.
        val sheetContainer = remember(context) {
            Color(context.getResourceColor(eu.kanade.tachiyomi.R.attr.colorPrimaryVariant))
        }
        val sheetContent = remember(context) {
            Color(context.getResourceColor(eu.kanade.tachiyomi.R.attr.actionBarTintColor))
        }
        Surface(
            modifier = modifier.fillMaxSize(),
            color = sheetContainer,
            contentColor = sheetContent,
        ) {
        Column(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
            when (val s = state) {
                LnPluginBrowseScreenModel.State.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
                is LnPluginBrowseScreenModel.State.Success -> {
                    if (s.isEmpty) {
                        EmptyState(modifier = Modifier.fillMaxSize())
                    } else {
                        PluginList(
                            state = s,
                            search = searchQuery,
                            busyEntryIds = busyEntryIds,
                            onInstall = { entry -> screenModel.install(host, entry) },
                            onUninstall = { entry -> screenModel.uninstall(entry) },
                        )
                    }
                }
            }
        }
        }  // Surface
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "No Light novel repos added",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Add a repo under Browse → Extension repos → Light novels to install plugins.",
                style = MaterialTheme.typography.bodyMedium,
                // LocalContentColor with alpha mirrors onSurfaceVariant against the bottom
                // sheet's content color (which we override via the Surface above to match
                // ?attr/actionBarTintColor). M3's onSurfaceVariant alone uses the activity's
                // primary surface scheme and wouldn't read correctly on this darker sheet.
                color = LocalContentColor.current.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun PluginList(
    state: LnPluginBrowseScreenModel.State.Success,
    search: String,
    busyEntryIds: Set<String>,
    onInstall: (LnRegistryEntry) -> Unit,
    onUninstall: (LnRegistryEntry) -> Unit,
) {
    // Flatten the language-grouped map into a single list of items including language headers
    // so LazyColumn's items() can drive both rows + headers off one source. The filter runs on
    // the entry list within each language; headers are skipped if all their entries were
    // filtered out.
    val sections = remember(state.byLanguage, search) {
        val needle = search.trim().lowercase()
        state.byLanguage.entries.mapNotNull { (lang, rows) ->
            val filtered = if (needle.isEmpty()) rows
            else rows.filter { it.entry.name.lowercase().contains(needle) }
            if (filtered.isEmpty()) null else lang to filtered
        }
    }
    if (sections.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text("No plugins match the search")
        }
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        sections.forEach { (lang, rows) ->
            item(key = "lang:$lang") {
                Text(
                    text = lang,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            items(items = rows, key = { it.entry.id + ":" + it.entry.url }) { row ->
                PluginRow(
                    entry = row.entry,
                    installed = row.installed,
                    busy = row.entry.id in busyEntryIds,
                    onInstall = { onInstall(row.entry) },
                    onUninstall = { onUninstall(row.entry) },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun PluginRow(
    entry: LnRegistryEntry,
    installed: Boolean,
    busy: Boolean,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AsyncImage(
            model = entry.iconUrl,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            contentScale = ContentScale.Fit,
            fallback = rememberVectorPainter(Icons.Outlined.LibraryBooks),
            error = rememberVectorPainter(Icons.Outlined.LibraryBooks),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${entry.id}  •  v${entry.version}",
                style = MaterialTheme.typography.bodySmall,
                // See EmptyState comment: LocalContentColor with alpha matches the sheet's
                // foreground tint instead of the activity-scoped onSurfaceVariant.
                color = LocalContentColor.current.copy(alpha = 0.7f),
            )
            if (installed) {
                Spacer(Modifier.height(2.dp))
                AssistChip(
                    onClick = {},
                    enabled = false,
                    label = { Text("Installed", style = MaterialTheme.typography.labelSmall) },
                    colors = AssistChipDefaults.assistChipColors(
                        disabledLabelColor = MaterialTheme.colorScheme.primary,
                    ),
                )
            }
        }
        if (installed) {
            OutlinedButton(enabled = !busy, onClick = onUninstall) {
                if (busy) CircularProgressIndicator(modifier = Modifier.size(16.dp))
                else Text("Uninstall")
            }
        } else {
            Button(enabled = !busy, onClick = onInstall) {
                if (busy) CircularProgressIndicator(modifier = Modifier.size(16.dp))
                else Text("Install")
            }
        }
    }
}
