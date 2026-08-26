package reikai.presentation.browse.extension

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.browse.ExtensionItem
import eu.kanade.presentation.browse.ExtensionTrustDialog
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.TabContent
import eu.kanade.presentation.more.settings.screen.browse.ExtensionStoresScreen
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.ui.browse.extension.ExtensionFilterScreen
import eu.kanade.tachiyomi.ui.browse.extension.ExtensionUninstallConfirmation
import eu.kanade.tachiyomi.ui.browse.extension.ExtensionsViewModel
import eu.kanade.tachiyomi.ui.browse.extension.details.ExtensionDetailsScreen
import eu.kanade.tachiyomi.ui.browse.extension.extensionsTab
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import eu.kanade.tachiyomi.util.system.isPackageInstalled
import reikai.domain.library.ContentType
import reikai.presentation.browse.ReikaiBrowseViewModel
import reikai.presentation.browse.components.BrowseSectionHeader
import reikai.presentation.components.ContentTypeFilterChips
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.FastScrollLazyColumn
import tachiyomi.presentation.core.components.material.topSmallPaddingValues
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.plus

/**
 * Reikai wrapper for the Browse "Extensions" tab. Adds the sticky content-type chip and a
 * light-novel plugin manager alongside Mihon's manga extensions: the Manga chip reuses Mihon's tab
 * content verbatim, Novels shows [LnPluginManager], All interleaves both under type headers. The tab
 * badge combines Mihon's manga update count with the LN plugin update count. Swapped in for Mihon's
 * `extensionsTab()` at the [eu.kanade.tachiyomi.ui.browse.BrowseTab] call site via a `// RK` island.
 */
@Composable
fun Screen.reikaiExtensionsTab(
    extensionsViewModel: ExtensionsViewModel,
    browseViewModel: ReikaiBrowseViewModel,
): TabContent {
    val navigator = LocalNavigator.currentOrThrow
    val mihonTab = extensionsTab(extensionsViewModel)
    // The badge reads the dedicated count flow, never the whole state: collecting the full state out
    // here would hold the extension subscription open for the tab strip, which is what upstream's
    // WhileSubscribed conversion exists to stop (mihonapp/mihon#3729).
    val updatesCount by extensionsViewModel.updatesCount.collectAsStateWithLifecycle()
    val contentType by browseViewModel.contentType.collectAsState()
    val lnCount by browseViewModel.lnUpdatesCount.collectAsState()
    val lnModel = metroViewModel<LnPluginManagerViewModel>()
    val lnState by lnModel.state.collectAsState()
    val openRepos = { navigator.push(ExtensionStoresScreen()) }

    return mihonTab.copy(
        badgeNumber = (updatesCount + lnCount).takeIf { it > 0 },
        // Single "Repos" action -> Mihon's ExtensionStoresScreen, now extended to manage both manga
        // and light-novel repos; keep the Filter action.
        actions = listOfNotNull(
            // Filter is Mihon's manga extension-language filter; it does nothing for novel plugins, so
            // hide it on the Novels chip (matches the Sources tab's content-type-aware filter).
            AppBar.OverflowAction(
                title = stringResource(MR.strings.action_filter),
                onClick = { navigator.push(ExtensionFilterScreen()) },
            ).takeIf { contentType != ContentType.NOVELS },
            AppBar.OverflowAction(
                title = stringResource(MR.strings.repos),
                onClick = openRepos,
            ),
            // Re-check both verticals on this unified tab: re-scan installed manga extensions and
            // re-evaluate trust against the current repos (recovers extensions stuck Untrusted after
            // their repo was added post-startup), and force-reload the installed novel plugins
            // (retrying any that failed to load).
            AppBar.OverflowAction(
                title = stringResource(MR.strings.action_recheck_extensions),
                onClick = {
                    extensionsViewModel.reloadInstalledExtensions()
                    lnModel.reloadInstalled()
                },
            ),
        ),
        content = { contentPadding, snackbarHostState ->
            val extState by extensionsViewModel.state.collectAsStateWithLifecycle()
            // The shared Browse search bar drives extensionsViewModel (manga only); apply the same
            // query to the novel plugin list so searching filters both sides, not just manga.
            val query = extState.searchQuery
            val filteredLnState = lnState.filteredBy(query)
            Column {
                ContentTypeFilterChips(
                    selected = contentType,
                    onSelect = browseViewModel::setContentType,
                    // Show where the pending updates are: a count on the Manga / Novels chip. The All
                    // chip stays clean; the tab badge already carries the combined total.
                    badges = mapOf(
                        ContentType.MANGA to extState.updates,
                        ContentType.NOVELS to lnCount,
                    ),
                )
                when (contentType) {
                    ContentType.MANGA -> mihonTab.content(contentPadding, snackbarHostState)
                    ContentType.NOVELS -> LnPluginManager(
                        state = filteredLnState,
                        contentPadding = contentPadding,
                        isSearching = !query.isNullOrBlank(),
                        onInstall = lnModel::install,
                        onUpdate = lnModel::update,
                        onUninstall = lnModel::uninstall,
                        onUpdateAll = lnModel::updateAll,
                        onAddRepo = openRepos,
                        onRetry = lnModel::refresh,
                    )
                    ContentType.ALL -> CombinedExtensionsContent(
                        extState = extState,
                        lnState = filteredLnState,
                        extensionsViewModel = extensionsViewModel,
                        lnModel = lnModel,
                        contentPadding = contentPadding,
                    )
                }
            }
        },
    )
}

/**
 * The unified "All" Extensions list: Mihon's manga extension rows (reused verbatim) under a Manga
 * header, then the LN plugin sections under a Novels header. Mihon's list owns its own scroll
 * container, so the row click routing is re-wired here, and it has to carry Mihon's two row dialogs
 * with it: an untrusted row routed to the details screen instead does nothing at all, because that
 * screen only knows installed extensions and pops straight back off an untrusted package.
 */
@Composable
private fun CombinedExtensionsContent(
    extState: ExtensionsViewModel.State,
    lnState: LnPluginManagerViewModel.State,
    extensionsViewModel: ExtensionsViewModel,
    lnModel: LnPluginManagerViewModel,
    contentPadding: PaddingValues,
) {
    val navigator = LocalNavigator.currentOrThrow
    val context = LocalContext.current
    val mangaItems = extState.items.values.flatten()
    var trustState by remember { mutableStateOf<Extension.Untrusted?>(null) }
    var privateExtensionToUninstall by remember { mutableStateOf<Extension?>(null) }

    FastScrollLazyColumn(contentPadding = contentPadding + topSmallPaddingValues) {
        if (mangaItems.isNotEmpty()) {
            item(key = "all-manga-header") {
                BrowseSectionHeader(title = stringResource(MR.strings.content_type_manga))
            }
            items(items = mangaItems, key = { "all-manga-${it.hashCode()}" }) { item ->
                ExtensionItem(
                    item = item,
                    onClickItem = { extension ->
                        when (extension) {
                            is Extension.Available -> extensionsViewModel.installExtension(extension)
                            is Extension.Installed -> navigator.push(ExtensionDetailsScreen(extension.pkgName))
                            is Extension.Untrusted -> trustState = extension
                        }
                    },
                    onLongClickItem = { extension ->
                        when (extension) {
                            is Extension.Available -> extensionsViewModel.installExtension(extension)
                            // A privately installed extension is not a package the system can remove,
                            // so it is confirmed here rather than uninstalled on the long press.
                            else -> if (context.isPackageInstalled(extension.pkgName)) {
                                extensionsViewModel.uninstallExtension(extension)
                            } else {
                                privateExtensionToUninstall = extension
                            }
                        }
                    },
                    onClickItemCancel = extensionsViewModel::cancelInstallUpdateExtension,
                    onClickItemAction = { extension ->
                        when (extension) {
                            is Extension.Available -> extensionsViewModel.installExtension(extension)
                            is Extension.Installed -> if (extension.hasUpdate) {
                                extensionsViewModel.updateExtension(extension)
                            } else {
                                navigator.push(ExtensionDetailsScreen(extension.pkgName))
                            }
                            is Extension.Untrusted -> trustState = extension
                        }
                    },
                    onClickItemSecondaryAction = { extension ->
                        when (extension) {
                            is Extension.Available -> extension.sources.getOrNull(0)?.let {
                                navigator.push(
                                    WebViewScreen(url = it.baseUrl, initialTitle = it.name, sourceId = it.id),
                                )
                            }
                            is Extension.Installed -> navigator.push(ExtensionDetailsScreen(extension.pkgName))
                            else -> {}
                        }
                    },
                )
            }
        }

        if (!lnState.isEmpty) {
            item(key = "all-novels-header") {
                BrowseSectionHeader(title = stringResource(MR.strings.content_type_novels))
            }
            lnPluginManagerItems(
                state = lnState,
                onInstall = lnModel::install,
                onUpdate = lnModel::update,
                onUninstall = lnModel::uninstall,
                onUpdateAll = lnModel::updateAll,
            )
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
}

/** Filter the novel plugin sections by the Browse search query, matching the manga side's behavior. */
private fun LnPluginManagerViewModel.State.filteredBy(query: String?): LnPluginManagerViewModel.State {
    val q = query?.trim().orEmpty()
    if (q.isEmpty()) return this
    return copy(
        updates = updates.filter { it.entry.name.contains(q, ignoreCase = true) },
        installed = installed.filter { it.name.contains(q, ignoreCase = true) },
        available = available.filter { it.name.contains(q, ignoreCase = true) },
    )
}
