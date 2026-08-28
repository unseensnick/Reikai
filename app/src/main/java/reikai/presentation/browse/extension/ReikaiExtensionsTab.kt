package reikai.presentation.browse.extension

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.GetApp
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.browse.ExtensionItem
import eu.kanade.presentation.browse.ExtensionTrustDialog
import eu.kanade.presentation.browse.ExtensionUninstallConfirmation
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.TabContent
import eu.kanade.presentation.components.WarningBanner
import eu.kanade.presentation.more.settings.screen.browse.ExtensionStoresScreen
import eu.kanade.presentation.util.rememberRequestPackageInstallsPermissionState
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.ui.browse.extension.ExtensionFilterScreen
import eu.kanade.tachiyomi.ui.browse.extension.ExtensionUiModel
import eu.kanade.tachiyomi.ui.browse.extension.ExtensionsViewModel
import eu.kanade.tachiyomi.ui.browse.extension.details.ExtensionDetailsScreen
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import eu.kanade.tachiyomi.util.system.isPackageInstalled
import eu.kanade.tachiyomi.util.system.launchRequestPackageInstallsPermission
import reikai.domain.library.ContentType
import reikai.novel.install.canonicalizePluginUrl
import reikai.novel.registry.LnRegistryEntry
import reikai.novel.source.NovelSource
import reikai.novel.update.LnPluginUpdate
import reikai.presentation.browse.ReikaiBrowseViewModel
import reikai.presentation.browse.browseLanguageLabel
import reikai.presentation.browse.components.BrowseSectionHeader
import reikai.presentation.browse.components.ContentTypeBadge
import reikai.presentation.browse.components.NovelSourceRow
import reikai.presentation.components.ContentTypeFilterChips
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.FastScrollLazyColumn
import tachiyomi.presentation.core.components.material.PullRefresh
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.components.material.topSmallPaddingValues
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.EmptyScreenAction
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.plus

/**
 * The Browse "Extensions" tab: one list of every manga extension and light-novel plugin, with the
 * content-type chip as a filter over it rather than a switch between two lists.
 *
 * Assembly, sectioning, search and every value describing the list live in [ExtensionsEngine]; this
 * draws what it is given and routes a tap. Replaces Mihon's `extensionsTab()` via a `// RK` island at
 * its call site; the replaced builder is deleted (see the off-path manifest).
 */
@Composable
fun Screen.reikaiExtensionsTab(
    extensionsViewModel: ExtensionsViewModel,
    browseViewModel: ReikaiBrowseViewModel,
): TabContent {
    val navigator = LocalNavigator.currentOrThrow
    val lnModel = metroViewModel<LnPluginManagerViewModel>()
    val providers = remember(extensionsViewModel, lnModel) {
        listOf(MangaExtensionsProvider(extensionsViewModel), NovelExtensionsProvider(lnModel))
    }
    val engine = assistedMetroViewModel<ExtensionsEngine, ExtensionsEngine.Factory> {
        create(providers, browseViewModel.searchQuery)
    }
    val state by engine.state.collectAsStateWithLifecycle()
    // The badge reads the dedicated count flows, never the whole state: collecting the full state out
    // here would hold the extension subscription open for the tab strip, which is what upstream's
    // WhileSubscribed conversion exists to stop (mihonapp/mihon#3729).
    val updatesCount by extensionsViewModel.updatesCount.collectAsStateWithLifecycle()
    val lnCount by browseViewModel.lnUpdatesCount.collectAsStateWithLifecycle()
    val openRepos = { navigator.push(ExtensionStoresScreen()) }

    return TabContent(
        titleRes = MR.strings.label_extensions,
        badgeNumber = (updatesCount + lnCount).takeIf { it > 0 },
        searchEnabled = true,
        actions = listOfNotNull(
            // Mihon's manga extension-language filter; it does nothing for novel plugins, so hide it
            // on the Novels chip, matching the Sources tab's content-type-aware filter.
            AppBar.OverflowAction(
                title = stringResource(MR.strings.action_filter),
                onClick = { navigator.push(ExtensionFilterScreen()) },
            ).takeIf { state.contentType != ContentType.NOVELS },
            AppBar.OverflowAction(
                title = stringResource(MR.strings.repos),
                onClick = openRepos,
            ),
            // Re-check both verticals: re-scan installed manga extensions and re-evaluate trust
            // against the current repos (recovers extensions stuck Untrusted after their repo was
            // added post-startup), and force-reload the installed novel plugins.
            AppBar.OverflowAction(
                title = stringResource(MR.strings.action_recheck_extensions),
                onClick = {
                    extensionsViewModel.reloadInstalledExtensions()
                    lnModel.reloadInstalled()
                },
            ),
        ),
        content = { contentPadding, _ ->
            BackHandler(enabled = state.query != null) {
                browseViewModel.search(null)
            }

            Column {
                ContentTypeFilterChips(
                    selected = state.contentType,
                    onSelect = engine::setContentType,
                    // Show where the pending updates are: a count on the Manga / Novels chip. The All
                    // chip stays clean; the tab badge already carries the combined total.
                    badges = mapOf(
                        ContentType.MANGA to updatesCount,
                        ContentType.NOVELS to lnCount,
                    ),
                )
                PullRefresh(
                    refreshing = state.isRefreshing,
                    onRefresh = engine::refresh,
                    enabled = !state.isLoading,
                ) {
                    when {
                        state.isLoading -> LoadingScreen(Modifier.padding(contentPadding))
                        state.isEmpty -> ExtensionsEmptyScreen(
                            state = state,
                            contentPadding = contentPadding,
                            onRetry = engine::refresh,
                            onOpenRepos = openRepos,
                        )
                        else -> ExtensionsList(
                            state = state,
                            showContentType = state.contentType == ContentType.ALL,
                            contentPadding = contentPadding,
                            extensionsViewModel = extensionsViewModel,
                            lnModel = lnModel,
                            onUpdateAll = engine::updateAll,
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun ExtensionsEmptyScreen(
    state: ExtensionsEngine.State,
    contentPadding: PaddingValues,
    onRetry: () -> Unit,
    onOpenRepos: () -> Unit,
) {
    EmptyScreen(
        stringRes = when {
            state.isSearching -> MR.strings.no_results_found
            // A repo is added but nothing loaded (empty or unreachable) reads differently from
            // having no repos at all.
            state.hasRepos -> MR.strings.ext_repos_empty
            else -> MR.strings.ext_no_repos
        },
        modifier = Modifier.padding(contentPadding),
        actions = buildList {
            if (state.hasRepos && !state.isSearching) {
                add(
                    EmptyScreenAction(
                        stringRes = MR.strings.action_retry,
                        icon = Icons.Outlined.Refresh,
                        onClick = onRetry,
                    ),
                )
            }
            add(
                EmptyScreenAction(
                    stringRes = MR.strings.repos,
                    icon = Icons.Outlined.Settings,
                    onClick = onOpenRepos,
                ),
            )
        },
    )
}

@Composable
private fun ExtensionsList(
    state: ExtensionsEngine.State,
    showContentType: Boolean,
    contentPadding: PaddingValues,
    extensionsViewModel: ExtensionsViewModel,
    lnModel: LnPluginManagerViewModel,
    onUpdateAll: () -> Unit,
) {
    val context = LocalContext.current
    val installGranted = rememberRequestPackageInstallsPermissionState(initialValue = true)
    val lnState by lnModel.state.collectAsStateWithLifecycle()
    // Hosted here rather than in the row: a dialog owned by a lazy item is disposed the moment that
    // row scrolls out of the list.
    var trustState by remember { mutableStateOf<Extension.Untrusted?>(null) }
    var privateExtensionToUninstall by remember { mutableStateOf<Extension?>(null) }
    var pluginToUninstall by remember { mutableStateOf<NovelSource?>(null) }

    FastScrollLazyColumn(contentPadding = contentPadding + topSmallPaddingValues) {
        if (!installGranted && state.needsInstallPermission) {
            item(key = "extension-permissions-warning") {
                WarningBanner(
                    textRes = MR.strings.ext_permission_install_apps_warning,
                    modifier = Modifier.clickable { context.launchRequestPackageInstallsPermission() },
                )
            }
        }

        items(
            items = state.items,
            contentType = {
                when (it) {
                    is ExtensionsListItem.Header -> "header"
                    is ExtensionsListItem.Row -> "item"
                }
            },
            key = {
                when (it) {
                    is ExtensionsListItem.Header -> "header-${it.section}"
                    // Section-qualified: a provider that let one source into two sections would
                    // otherwise take the list down rather than merely look wrong.
                    is ExtensionsListItem.Row -> "extension-${it.row.section}-${it.row.key}"
                }
            },
        ) { item ->
            when (item) {
                is ExtensionsListItem.Header ->
                    ExtensionsSectionHeader(item.section, onUpdateAll, Modifier.animateItem())
                is ExtensionsListItem.Row -> {
                    val badge: @Composable () -> Unit = {
                        if (showContentType) ContentTypeBadge(item.row.key.contentType)
                    }
                    when (item.row.key) {
                        is ExtensionKey.Manga -> MangaExtensionRow(
                            modifier = Modifier.animateItem(),
                            item = item.row.payload as ExtensionUiModel.Item,
                            model = extensionsViewModel,
                            badge = badge,
                            onUntrusted = { trustState = it },
                            onConfirmPrivateUninstall = { privateExtensionToUninstall = it },
                        )
                        is ExtensionKey.Novel -> NovelExtensionRow(
                            row = item.row,
                            state = lnState,
                            model = lnModel,
                            badge = badge,
                            onConfirmUninstall = { pluginToUninstall = it },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }

    trustState?.let { extension ->
        ExtensionTrustDialog(
            onClickConfirm = {
                extensionsViewModel.trustExtension(extension)
                trustState = null
            },
            onClickDismiss = {
                extensionsViewModel.uninstallExtension(extension)
                trustState = null
            },
            onDismissRequest = { trustState = null },
        )
    }

    privateExtensionToUninstall?.let { extension ->
        ExtensionUninstallConfirmation(
            extensionName = extension.name,
            onClickConfirm = { extensionsViewModel.uninstallExtension(extension) },
            onDismissRequest = { privateExtensionToUninstall = null },
        )
    }

    // A plugin is not a package the system can remove, so its long press is confirmed here, the
    // way a privately installed manga extension is.
    pluginToUninstall?.let { plugin ->
        ExtensionUninstallConfirmation(
            extensionName = plugin.name,
            onClickConfirm = { lnModel.uninstall(plugin) },
            onDismissRequest = { pluginToUninstall = null },
        )
    }
}

@Composable
private fun ExtensionsSectionHeader(
    section: ExtensionSection,
    onUpdateAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BrowseSectionHeader(
        modifier = modifier,
        title = when (section) {
            ExtensionSection.Updates -> stringResource(MR.strings.ext_updates_pending)
            ExtensionSection.Installed -> stringResource(MR.strings.ext_installed)
            is ExtensionSection.Available -> browseLanguageLabel(section.lang, LocalContext.current)
        },
        action = {
            // One Update all, spanning both content types, because there is one Updates section.
            if (section == ExtensionSection.Updates) {
                Button(onClick = onUpdateAll) {
                    Text(text = stringResource(MR.strings.ext_update_all))
                }
            }
        },
    )
}

@Composable
private fun MangaExtensionRow(
    item: ExtensionUiModel.Item,
    model: ExtensionsViewModel,
    badge: @Composable () -> Unit,
    onUntrusted: (Extension.Untrusted) -> Unit,
    onConfirmPrivateUninstall: (Extension) -> Unit,
    modifier: Modifier = Modifier,
) {
    val navigator = LocalNavigator.currentOrThrow
    val context = LocalContext.current

    ExtensionItem(
        modifier = modifier,
        item = item,
        badge = badge,
        onClickItem = {
            when (it) {
                is Extension.Available -> model.installExtension(it)
                is Extension.Installed -> navigator.push(ExtensionDetailsScreen(it.pkgName))
                is Extension.Untrusted -> onUntrusted(it)
            }
        },
        onLongClickItem = {
            when (it) {
                is Extension.Available -> model.installExtension(it)
                // A privately installed extension is not a package the system can remove, so it is
                // confirmed here rather than uninstalled on the long press.
                else -> if (context.isPackageInstalled(it.pkgName)) {
                    model.uninstallExtension(it)
                } else {
                    onConfirmPrivateUninstall(it)
                }
            }
        },
        onClickItemCancel = model::cancelInstallUpdateExtension,
        onClickItemAction = {
            when (it) {
                is Extension.Available -> model.installExtension(it)
                is Extension.Installed -> if (it.hasUpdate) {
                    model.updateExtension(it)
                } else {
                    navigator.push(ExtensionDetailsScreen(it.pkgName))
                }
                is Extension.Untrusted -> onUntrusted(it)
            }
        },
        onClickItemSecondaryAction = {
            when (it) {
                is Extension.Available -> it.sources.getOrNull(0)?.let { source ->
                    navigator.push(
                        WebViewScreen(url = source.baseUrl, initialTitle = source.name, sourceId = source.id),
                    )
                }
                is Extension.Installed -> navigator.push(ExtensionDetailsScreen(it.pkgName))
                else -> {}
            }
        },
    )
}

@Composable
private fun NovelExtensionRow(
    row: BrowseExtensionRow,
    state: LnPluginManagerViewModel.State,
    model: LnPluginManagerViewModel,
    badge: @Composable () -> Unit,
    onConfirmUninstall: (NovelSource) -> Unit,
    modifier: Modifier = Modifier,
) {
    val navigator = LocalNavigator.currentOrThrow
    when (val payload = row.payload) {
        is LnPluginUpdate -> NovelSourceRow(
            modifier = modifier,
            name = payload.entry.name,
            lang = row.lang,
            iconUrl = payload.entry.iconUrl,
            subtitle = "v${payload.installedVersion} -> v${payload.entry.version}",
            badge = badge,
            action = {
                NovelRowAction(inProgress = canonicalizePluginUrl(payload.entry.url) in state.inProgress) {
                    IconButton(onClick = { model.update(payload) }) {
                        Icon(
                            imageVector = Icons.Outlined.GetApp,
                            contentDescription = stringResource(MR.strings.ext_update),
                        )
                    }
                }
            },
        )
        is NovelSource -> NovelSourceRow(
            modifier = modifier,
            name = payload.name,
            lang = row.lang,
            iconUrl = payload.iconUrl,
            version = state.installedVersions[payload.id],
            onLongClickItem = { onConfirmUninstall(payload) },
            badge = badge,
            action = {
                IconButton(onClick = { model.uninstall(payload) }) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = stringResource(MR.strings.ext_uninstall),
                    )
                }
            },
        )
        is LnRegistryEntry -> {
            val key = canonicalizePluginUrl(payload.url)
            NovelSourceRow(
                modifier = modifier,
                name = payload.name,
                lang = row.lang,
                iconUrl = payload.iconUrl,
                subtitle = state.errors[key],
                badge = badge,
                action = {
                    // The same pair of icon buttons the manga rows use, so one list reads as one
                    // list. Both go while an install runs, as they do on the manga side.
                    NovelRowAction(inProgress = key in state.inProgress) {
                        if (payload.site.isNotEmpty()) {
                            IconButton(onClick = { navigator.push(webViewFor(payload)) }) {
                                Icon(
                                    imageVector = Icons.Outlined.Public,
                                    contentDescription = stringResource(MR.strings.action_open_in_web_view),
                                )
                            }
                        }
                        IconButton(onClick = { model.install(payload) }) {
                            Icon(
                                imageVector = Icons.Outlined.GetApp,
                                contentDescription = stringResource(MR.strings.ext_install),
                            )
                        }
                    }
                },
            )
        }
    }
}

/**
 * A plugin's site in the WebView. No source id goes with it: that only carries an HTTP source's own
 * request headers, which a JavaScript plugin does not have.
 */
private fun webViewFor(entry: LnRegistryEntry) =
    WebViewScreen(url = entry.site, initialTitle = entry.name)

/**
 * A novel row's trailing buttons, or a spinner while its install runs.
 *
 * The Row and its spacing are the manga rows' own, so the two kinds line their buttons up in the
 * same columns; emitting the buttons bare leaves the group narrower and shifts all but the last.
 */
@Composable
private fun NovelRowAction(inProgress: Boolean, buttons: @Composable RowScope.() -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
        if (inProgress) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        } else {
            buttons()
        }
    }
}
