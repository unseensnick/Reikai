package yokai.presentation.extension.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.LibraryBooks
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.model.rememberScreenModel
import coil3.compose.AsyncImage
import cafe.adriel.voyager.core.screen.Screen
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import eu.kanade.tachiyomi.util.system.LocaleHelper
import eu.kanade.tachiyomi.util.system.getResourceColor
import eu.kanade.tachiyomi.util.system.toast
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import yokai.novel.registry.LnRegistryEntry

/**
 * Aggregate "available LN plugins" list rendered inside the Browse → Extensions bottom sheet's
 * Light novels sub-tab (Phase 8 follow-up CR6). Reads from [LnPluginBrowseScreenModel] which
 * fetches every URL in `NovelPreferences.addedRepoUrls` and groups by language.
 *
 * Install / uninstall / clear-data are delegated to the screen model, which uses the app-scoped
 * [LnPluginHost] (Koin `single`); this composable no longer owns a host.
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
        val installErrors by screenModel.installErrors.collectAsState()
        // Tap on an installed row opens this overflow sheet; null means no sheet shown.
        var openOverflowForEntry by remember { mutableStateOf<LnRegistryEntry?>(null) }
        val context = LocalContext.current

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

        // The bottom-sheet shell is themed `?attr/colorPrimaryVariant` (purple), but its
        // content area is the manga RV which sets `android:background="?attr/background"`
        // (recycler_with_scroller.xml:13), the dark app background. The LN page is the
        // peer of that RV, so it should match the same surface, not the sheet's chrome
        // color. `?attr/actionBarTintColor` stays as the content tint: in the dark theme it
        // resolves to the same light value `?android:textColorPrimary` does, and the
        // existing getResourceColor helper can't read ColorStateList attrs (textColorPrimary
        // is one), so this is the closest direct-color we can grab.
        val sheetContainer = remember(context) {
            Color(context.getResourceColor(eu.kanade.tachiyomi.R.attr.background))
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
                            installErrors = installErrors,
                            onInstall = { entry -> screenModel.install(entry) },
                            onTapInstalled = { entry -> openOverflowForEntry = entry },
                        )
                    }
                }
            }
        }
        }  // Surface

        openOverflowForEntry?.let { entry ->
            InstalledPluginOverflowSheet(
                entry = entry,
                onDismiss = { openOverflowForEntry = null },
                onOpenSite = {
                    val site = entry.site.takeIf { it.isNotBlank() }
                    if (site != null) {
                        context.startActivity(WebViewActivity.newIntent(context, site, null, entry.name))
                    }
                    openOverflowForEntry = null
                },
                onClearData = {
                    screenModel.clearPluginData(entry)
                    context.toast("Cleared ${entry.name} data")
                    openOverflowForEntry = null
                },
                onUninstall = {
                    screenModel.uninstall(entry)
                    openOverflowForEntry = null
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InstalledPluginOverflowSheet(
    entry: LnRegistryEntry,
    onDismiss: () -> Unit,
    onOpenSite: () -> Unit,
    onClearData: () -> Unit,
    onUninstall: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Text(
            text = entry.name,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        ListItem(
            modifier = Modifier.clickable(onClick = onOpenSite),
            leadingContent = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null) },
            headlineContent = { Text("Open site") },
        )
        ListItem(
            modifier = Modifier.clickable(onClick = onClearData),
            leadingContent = { Icon(Icons.Outlined.DeleteSweep, contentDescription = null) },
            headlineContent = { Text("Clear data") },
        )
        ListItem(
            modifier = Modifier.clickable(onClick = onUninstall),
            leadingContent = { Icon(Icons.Outlined.DeleteForever, contentDescription = null) },
            headlineContent = { Text("Uninstall") },
        )
        Spacer(Modifier.height(12.dp))
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
    installErrors: Map<String, String>,
    onInstall: (LnRegistryEntry) -> Unit,
    onTapInstalled: (LnRegistryEntry) -> Unit,
) {
    // Flatten the language-grouped map into a single list of items including language headers
    // so LazyColumn's items() can drive both rows + headers off one source. The filter runs on
    // the entry list within each language; headers are skipped if all their entries were
    // filtered out.
    val needle = search.trim().lowercase()
    val installedFiltered = remember(state.installed, needle) {
        if (needle.isEmpty()) state.installed
        else state.installed.filter { it.entry.name.lowercase().contains(needle) }
    }
    val sections = remember(state.byLanguage, needle) {
        state.byLanguage.entries.mapNotNull { (lang, rows) ->
            val filtered = if (needle.isEmpty()) rows
            else rows.filter { it.entry.name.lowercase().contains(needle) }
            if (filtered.isEmpty()) null else lang to filtered
        }
    }
    if (installedFiltered.isEmpty() && sections.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text("No plugins match the search")
        }
        return
    }
    // Keys are stable across the installed / language sections so LazyColumn can anchor scroll
    // position to a row whose section changed after install / update. (The `i:` / `lang:` prefix
    // on the section headers is fine, since installed and per-language item sets are mutually
    // exclusive — a row is in exactly one of them — so the entry-level key won't collide.)
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (installedFiltered.isNotEmpty()) {
            item(key = "section:installed") { SectionHeader(text = "Installed") }
            items(items = installedFiltered, key = { it.entry.id + ":" + it.entry.url }) { row ->
                PluginRow(
                    entry = row.entry,
                    installed = row.installed,
                    outdated = row.outdated,
                    busy = row.entry.id in busyEntryIds,
                    installError = installErrors[row.entry.id],
                    onInstall = { onInstall(row.entry) },
                    onTapInstalled = { onTapInstalled(row.entry) },
                )
                HorizontalDivider()
            }
        }
        sections.forEach { (lang, rows) ->
            item(key = "lang:$lang") { SectionHeader(text = lang) }
            items(items = rows, key = { it.entry.id + ":" + it.entry.url }) { row ->
                PluginRow(
                    entry = row.entry,
                    installed = row.installed,
                    outdated = row.outdated,
                    busy = row.entry.id in busyEntryIds,
                    installError = installErrors[row.entry.id],
                    onInstall = { onInstall(row.entry) },
                    onTapInstalled = { onTapInstalled(row.entry) },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    // Inherit the parent Surface's contentColor (set to ?attr/actionBarTintColor at the
    // LnPluginBrowseContent root). Don't read android.R.attr.textColorPrimary directly:
    // getResourceColor goes through typedArray.getColor() which only handles direct color
    // attrs and returns 0 (transparent) for the ColorStateList textColorPrimary actually is.
    // Don't use MaterialTheme.colorScheme.primary either: that's the accent purple, not the
    // primary-text color the manga RV header uses.
    // Match the manga ext header (extension_card_header.xml): 20dp marginTop + 8dp marginBottom.
    // The manga side uses ?textAppearanceHeadlineMedium which carries a taller intrinsic line
    // height than M3 titleMedium, so bump both margins a notch to recover the same gap.
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontSize = 16.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 12.dp),
    )
}

@Composable
private fun PluginRow(
    entry: LnRegistryEntry,
    installed: Boolean,
    outdated: Boolean,
    busy: Boolean,
    installError: String?,
    onInstall: () -> Unit,
    onTapInstalled: () -> Unit,
) {
    // Match the manga row's hard 64dp container height (extension_card_item.xml:11) by using
    // heightIn min with no vertical padding. Rows grow past 64dp only when the optional
    // install-error text needs the extra line.
    val rowModifier = Modifier
        .fillMaxWidth()
        .heightIn(min = 64.dp)
        .then(if (installed) Modifier.clickable(onClick = onTapInstalled) else Modifier)
        .padding(horizontal = 16.dp)
    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
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
                style = MaterialTheme.typography.bodyMedium,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${LocaleHelper.getLocalizedDisplayName(entry.lang)}  •  v${entry.version}",
                style = MaterialTheme.typography.bodySmall,
                // See EmptyState comment: LocalContentColor with alpha matches the sheet's
                // foreground tint instead of the activity-scoped onSurfaceVariant.
                color = LocalContentColor.current.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (installError != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = installError,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                )
            }
        }
        when {
            busy -> CircularProgressIndicator(modifier = Modifier.size(16.dp))
            // Outdated takes precedence over the installed check so users have a direct path to
            // update without waiting for the next periodic LnPluginUpdateJob run. Reinstall via
            // the same install() flow; persistence overwrites the stored version.
            installed && outdated -> OutlinedButton(onClick = onInstall) { Text("Update") }
            installed -> Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = "Installed",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            else -> OutlinedButton(onClick = onInstall) { Text("Install") }
        }
    }
}
