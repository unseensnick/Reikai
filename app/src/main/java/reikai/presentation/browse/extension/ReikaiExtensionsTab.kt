package reikai.presentation.browse.extension

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.browse.ExtensionItem
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.TabContent
import eu.kanade.presentation.more.settings.screen.browse.ExtensionStoresScreen
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.ui.browse.extension.ExtensionFilterScreen
import eu.kanade.tachiyomi.ui.browse.extension.ExtensionsScreenModel
import eu.kanade.tachiyomi.ui.browse.extension.details.ExtensionDetailsScreen
import eu.kanade.tachiyomi.ui.browse.extension.extensionsTab
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import reikai.domain.library.ContentType
import reikai.presentation.browse.ReikaiBrowseScreenModel
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
    extensionsScreenModel: ExtensionsScreenModel,
    browseScreenModel: ReikaiBrowseScreenModel,
): TabContent {
    val navigator = LocalNavigator.currentOrThrow
    val mihonTab = extensionsTab(extensionsScreenModel)
    val extState by extensionsScreenModel.state.collectAsState()
    val contentType by browseScreenModel.contentType.collectAsState()
    val lnCount by browseScreenModel.lnUpdatesCount.collectAsState()
    val lnModel = rememberScreenModel { LnPluginManagerScreenModel() }
    val lnState by lnModel.state.collectAsState()
    val openRepos = { navigator.push(ExtensionStoresScreen()) }

    return mihonTab.copy(
        badgeNumber = (extState.updates + lnCount).takeIf { it > 0 },
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
                    extensionsScreenModel.reloadInstalledExtensions()
                    lnModel.reloadInstalled()
                },
            ),
        ),
        content = { contentPadding, snackbarHostState ->
            // The shared Browse search bar drives extensionsScreenModel (manga only); apply the same
            // query to the novel plugin list so searching filters both sides, not just manga.
            val query = extState.searchQuery
            val filteredLnState = lnState.filteredBy(query)
            Column {
                ContentTypeFilterChips(
                    selected = contentType,
                    onSelect = browseScreenModel::setContentType,
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
                        extensionsScreenModel = extensionsScreenModel,
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
 * header, then the LN plugin sections under a Novels header. The overview wires the core manga
 * actions (install / update / open / uninstall); deeper flows (trust prompt, private-uninstall
 * confirmation) stay on the Manga chip and the extension details screen.
 */
@Composable
private fun CombinedExtensionsContent(
    extState: ExtensionsScreenModel.State,
    lnState: LnPluginManagerScreenModel.State,
    extensionsScreenModel: ExtensionsScreenModel,
    lnModel: LnPluginManagerScreenModel,
    contentPadding: PaddingValues,
) {
    val navigator = LocalNavigator.currentOrThrow
    val mangaItems = extState.items.values.flatten()

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
                            is Extension.Available -> extensionsScreenModel.installExtension(extension)
                            is Extension.Installed -> navigator.push(ExtensionDetailsScreen(extension.pkgName))
                            is Extension.Untrusted -> navigator.push(ExtensionDetailsScreen(extension.pkgName))
                        }
                    },
                    onLongClickItem = { extension ->
                        when (extension) {
                            is Extension.Available -> extensionsScreenModel.installExtension(extension)
                            else -> extensionsScreenModel.uninstallExtension(extension)
                        }
                    },
                    onClickItemCancel = extensionsScreenModel::cancelInstallUpdateExtension,
                    onClickItemAction = { extension ->
                        when (extension) {
                            is Extension.Available -> extensionsScreenModel.installExtension(extension)
                            is Extension.Installed -> if (extension.hasUpdate) {
                                extensionsScreenModel.updateExtension(extension)
                            } else {
                                navigator.push(ExtensionDetailsScreen(extension.pkgName))
                            }
                            is Extension.Untrusted -> navigator.push(ExtensionDetailsScreen(extension.pkgName))
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
}

/** Filter the novel plugin sections by the Browse search query, matching the manga side's behavior. */
private fun LnPluginManagerScreenModel.State.filteredBy(query: String?): LnPluginManagerScreenModel.State {
    val q = query?.trim().orEmpty()
    if (q.isEmpty()) return this
    return copy(
        updates = updates.filter { it.entry.name.contains(q, ignoreCase = true) },
        installed = installed.filter { it.name.contains(q, ignoreCase = true) },
        available = available.filter { it.name.contains(q, ignoreCase = true) },
    )
}
