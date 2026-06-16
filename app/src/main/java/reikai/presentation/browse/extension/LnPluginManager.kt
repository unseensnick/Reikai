package reikai.presentation.browse.extension

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.GetApp
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import reikai.novel.install.canonicalizePluginUrl
import reikai.novel.registry.LnRegistryEntry
import reikai.novel.source.NovelSource
import reikai.novel.update.LnPluginUpdate
import reikai.presentation.browse.components.BrowseSectionHeader
import reikai.presentation.browse.components.NovelSourceRow
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.FastScrollLazyColumn
import tachiyomi.presentation.core.components.material.topSmallPaddingValues
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.EmptyScreenAction
import tachiyomi.presentation.core.util.plus

/**
 * Light-novel plugin manager (Browse → Extensions, Novels chip). Mirrors Mihon's
 * [eu.kanade.presentation.browse.ExtensionScreen] section layout (Updates / Installed / Available)
 * over the S2 plugin host, reusing Mihon's `ext_*` strings where they fit. The sections are exposed
 * as a [LazyListScope] extension so the unified "All" Browse view can compose them inline.
 */
@Composable
fun LnPluginManager(
    state: LnPluginManagerScreenModel.State,
    contentPadding: PaddingValues,
    onInstall: (LnRegistryEntry) -> Unit,
    onUpdate: (LnPluginUpdate) -> Unit,
    onUninstall: (NovelSource) -> Unit,
    onUpdateAll: () -> Unit,
    onAddRepo: () -> Unit,
    isSearching: Boolean = false,
) {
    if (state.isEmpty) {
        EmptyScreen(
            stringRes = if (isSearching) MR.strings.no_results_found else MR.strings.ln_no_repos,
            modifier = Modifier.padding(contentPadding),
            actions = listOf(
                EmptyScreenAction(
                    stringRes = MR.strings.ln_repos,
                    icon = Icons.Outlined.Settings,
                    onClick = onAddRepo,
                ),
            ),
        )
        return
    }

    FastScrollLazyColumn(contentPadding = contentPadding + topSmallPaddingValues) {
        lnPluginManagerItems(
            state = state,
            onInstall = onInstall,
            onUpdate = onUpdate,
            onUninstall = onUninstall,
            onUpdateAll = onUpdateAll,
        )
    }
}

/** The Updates / Installed / Available sections, for composition into any parent lazy list. */
fun LazyListScope.lnPluginManagerItems(
    state: LnPluginManagerScreenModel.State,
    onInstall: (LnRegistryEntry) -> Unit,
    onUpdate: (LnPluginUpdate) -> Unit,
    onUninstall: (NovelSource) -> Unit,
    onUpdateAll: () -> Unit,
) {
    if (state.updates.isNotEmpty()) {
        item(key = "ln-updates-header") {
            BrowseSectionHeader(
                title = stringResource(MR.strings.ext_updates_pending),
                action = {
                    Button(onClick = onUpdateAll) {
                        Text(text = stringResource(MR.strings.ext_update_all))
                    }
                },
            )
        }
        items(items = state.updates, key = { "ln-update-${it.entry.id}" }) { update ->
            val key = canonicalizePluginUrl(update.entry.url)
            NovelSourceRow(
                name = update.entry.name,
                lang = update.entry.lang,
                iconUrl = update.entry.iconUrl,
                subtitle = "v${update.installedVersion} → v${update.entry.version}",
                action = {
                    RowAction(inProgress = key in state.inProgress) {
                        IconButton(onClick = { onUpdate(update) }) {
                            Icon(
                                imageVector = Icons.Outlined.GetApp,
                                contentDescription = stringResource(MR.strings.ext_update),
                            )
                        }
                    }
                },
            )
        }
    }

    if (state.installed.isNotEmpty()) {
        item(key = "ln-installed-header") {
            BrowseSectionHeader(title = stringResource(MR.strings.ext_installed))
        }
        items(items = state.installed, key = { "ln-installed-${it.id}" }) { source ->
            NovelSourceRow(
                name = source.name,
                lang = source.lang,
                iconUrl = source.iconUrl,
                action = {
                    IconButton(onClick = { onUninstall(source) }) {
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = stringResource(MR.strings.ext_uninstall),
                        )
                    }
                },
            )
        }
    }

    if (state.available.isNotEmpty()) {
        item(key = "ln-available-header") {
            BrowseSectionHeader(title = stringResource(MR.strings.ln_available))
        }
        items(items = state.available, key = { "ln-available-${it.id}" }) { entry ->
            val key = canonicalizePluginUrl(entry.url)
            NovelSourceRow(
                name = entry.name,
                lang = entry.lang,
                iconUrl = entry.iconUrl,
                subtitle = state.errors[key],
                action = {
                    RowAction(inProgress = key in state.inProgress) {
                        TextButton(onClick = { onInstall(entry) }) {
                            Text(text = stringResource(MR.strings.action_install))
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun RowAction(
    inProgress: Boolean,
    button: @Composable () -> Unit,
) {
    if (inProgress) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
    } else {
        button()
    }
}
