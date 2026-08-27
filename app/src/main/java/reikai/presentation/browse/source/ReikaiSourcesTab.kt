package reikai.presentation.browse.source

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.browse.SourceItem
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.TabContent
import eu.kanade.tachiyomi.ui.browse.source.SourcesFilterScreen
import eu.kanade.tachiyomi.ui.browse.source.SourcesViewModel
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreen
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import reikai.domain.library.ContentType
import reikai.domain.source.SourceKey
import reikai.novel.source.NovelSource
import reikai.presentation.browse.EntrySourceOptionsDialog
import reikai.presentation.browse.ReikaiBrowseViewModel
import reikai.presentation.browse.browseLanguageLabel
import reikai.presentation.browse.components.BrowseSectionHeader
import reikai.presentation.browse.components.ContentTypeBadge
import reikai.presentation.browse.components.NovelSourcePinButton
import reikai.presentation.browse.components.NovelSourceRow
import reikai.presentation.components.ContentTypeFilterChips
import reikai.presentation.novel.browse.NovelBrowseScreen
import reikai.presentation.novel.globalsearch.NovelGlobalSearchScreen
import tachiyomi.domain.source.model.Source
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.material.topSmallPaddingValues
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.plus

/**
 * The Browse "Sources" tab: one list of every enabled source, manga and light novel together, with
 * the content-type chip as a filter over it rather than a switch between two lists.
 *
 * Assembly, sectioning and the row dialog live in [SourcesEngine]; this draws what it is given and
 * routes a tap. Replaces Mihon's `sourcesTab()` via a `// RK` island at its call site; the replaced
 * builder is deleted (see the off-path manifest).
 */
@Composable
fun Screen.reikaiSourcesTab(browseViewModel: ReikaiBrowseViewModel): TabContent {
    val navigator = LocalNavigator.currentOrThrow
    val mangaModel = metroViewModel<SourcesViewModel>()
    val novelModel = metroViewModel<NovelSourcesViewModel>()
    val providers = remember(mangaModel, novelModel) {
        listOf(MangaSourcesProvider(mangaModel), NovelSourcesProvider(novelModel))
    }
    val engine = assistedMetroViewModel<SourcesEngine, SourcesEngine.Factory> { create(providers) }
    val state by engine.state.collectAsStateWithLifecycle()

    return TabContent(
        titleRes = MR.strings.label_sources,
        actions = listOfNotNull(
            AppBar.Action(
                title = stringResource(MR.strings.action_global_search),
                icon = Icons.Outlined.TravelExplore,
                // Content-type-aware: the Novels chip searches LN sources; Manga / All use Mihon's.
                onClick = {
                    navigator.push(
                        if (state.contentType == ContentType.NOVELS) {
                            NovelGlobalSearchScreen()
                        } else {
                            GlobalSearchScreen()
                        },
                    )
                },
            ),
            // Content-type-aware filter: the Novels chip opens the LN per-source filter, Manga / All
            // open Mihon's manga sources filter (per-language + per-source).
            AppBar.Action(
                title = stringResource(MR.strings.action_filter),
                icon = Icons.Outlined.FilterList,
                onClick = {
                    navigator.push(
                        if (state.contentType == ContentType.NOVELS) {
                            NovelSourcesFilterScreen()
                        } else {
                            SourcesFilterScreen()
                        },
                    )
                },
            ),
        ),
        content = { contentPadding, snackbarHostState ->
            Column {
                ContentTypeFilterChips(
                    selected = state.contentType,
                    onSelect = engine::setContentType,
                )
                when {
                    state.isLoading -> LoadingScreen(Modifier.padding(contentPadding))
                    state.isEmpty -> EmptyScreen(
                        stringRes = MR.strings.source_empty_screen,
                        modifier = Modifier.padding(contentPadding),
                    )
                    else -> SourcesList(
                        items = state.items,
                        showContentType = state.contentType == ContentType.ALL,
                        contentPadding = contentPadding,
                        onClickItem = { row, query ->
                            when (row.key) {
                                is SourceKey.Manga ->
                                    navigator.push(BrowseSourceScreen((row.source as Source).id, query))
                                is SourceKey.Novel ->
                                    navigator.push(NovelBrowseScreen((row.source as NovelSource).id))
                            }
                        },
                        onClickPin = engine::togglePin,
                        onLongClickItem = engine::showDialog,
                    )
                }
            }

            state.dialog?.let { dialog ->
                EntrySourceOptionsDialog(
                    title = dialog.row.name,
                    isPinned = dialog.row.isPinned,
                    showToggleDisable = dialog.canDisable,
                    // A disabled source is not listed, so a row that is here is never disabled.
                    isDisabled = false,
                    onClickPin = {
                        engine.togglePin(dialog.row)
                        engine.closeDialog()
                    },
                    onClickToggleDisable = {
                        engine.toggleDisable(dialog.row)
                        engine.closeDialog()
                    },
                    onDismiss = engine::closeDialog,
                )
            }

            val internalErrString = stringResource(MR.strings.internal_error)
            LaunchedEffect(Unit) {
                mangaModel.events.collectLatest { event ->
                    when (event) {
                        SourcesViewModel.Event.FailedFetchingSources ->
                            launch { snackbarHostState.showSnackbar(internalErrString) }
                    }
                }
            }
        },
    )
}

@Composable
private fun SourcesList(
    items: List<SourcesListItem>,
    showContentType: Boolean,
    contentPadding: PaddingValues,
    onClickItem: (BrowseSourceRow, String?) -> Unit,
    onClickPin: (BrowseSourceRow) -> Unit,
    onLongClickItem: (BrowseSourceRow) -> Unit,
) {
    val context = LocalContext.current
    ScrollbarLazyColumn(contentPadding = contentPadding + topSmallPaddingValues) {
        items(
            items = items,
            contentType = {
                when (it) {
                    is SourcesListItem.Header -> "header"
                    is SourcesListItem.Row -> "item"
                }
            },
            key = {
                when (it) {
                    is SourcesListItem.Header -> "header-${it.key}"
                    is SourcesListItem.Row -> "source-${it.row.key}-${it.row.isUsedLast}"
                }
            },
        ) { item ->
            when (item) {
                is SourcesListItem.Header -> BrowseSectionHeader(
                    title = browseLanguageLabel(item.key, context),
                )
                is SourcesListItem.Row -> SourceRow(
                    row = item.row,
                    showContentType = showContentType,
                    onClickItem = onClickItem,
                    onClickPin = onClickPin,
                    onLongClickItem = onLongClickItem,
                )
            }
        }
    }
}

/** One row, drawn by whichever leaf owns its content type. */
@Composable
private fun SourceRow(
    row: BrowseSourceRow,
    showContentType: Boolean,
    onClickItem: (BrowseSourceRow, String?) -> Unit,
    onClickPin: (BrowseSourceRow) -> Unit,
    onLongClickItem: (BrowseSourceRow) -> Unit,
) {
    val badge: @Composable () -> Unit = {
        if (showContentType) ContentTypeBadge(row.key.contentType)
    }
    when (row.key) {
        is SourceKey.Manga -> SourceItem(
            source = row.source as Source,
            onClickItem = { _, listing -> onClickItem(row, listing.query) },
            onLongClickItem = { onLongClickItem(row) },
            onClickPin = { onClickPin(row) },
            badge = badge,
        )
        is SourceKey.Novel -> NovelSourceRow(
            name = row.name,
            lang = row.lang,
            iconUrl = (row.source as NovelSource).iconUrl,
            onClickItem = { onClickItem(row, null) },
            onLongClickItem = { onLongClickItem(row) },
            badge = badge,
            action = {
                NovelSourcePinButton(
                    isPinned = row.isPinned,
                    onClick = { onClickPin(row) },
                )
            },
        )
    }
}
