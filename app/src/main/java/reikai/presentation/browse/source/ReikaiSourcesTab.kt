package reikai.presentation.browse.source

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.browse.SourceItem
import eu.kanade.presentation.browse.SourceOptionsDialog
import eu.kanade.presentation.browse.SourceUiModel
import eu.kanade.presentation.browse.SourcesScreen
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.TabContent
import eu.kanade.tachiyomi.ui.browse.source.SourcesFilterScreen
import eu.kanade.tachiyomi.ui.browse.source.SourcesScreenModel
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreenModel.Listing
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreen
import eu.kanade.tachiyomi.util.system.LocaleHelper
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import reikai.domain.library.ContentType
import reikai.presentation.browse.ReikaiBrowseScreenModel
import reikai.presentation.browse.components.BrowseSectionHeader
import reikai.presentation.browse.components.NovelSourceRow
import reikai.presentation.components.ContentTypeFilterChips
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.material.topSmallPaddingValues
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.domain.source.model.Source
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.plus

/**
 * Reikai wrapper for the Browse "Sources" tab (P5 S3a): the sticky content-type chip over Mihon's
 * manga sources list and a net-new installed-novel-sources list. Manga reuses [SourcesScreen]
 * verbatim, Novels shows the LN list, All interleaves both under type headers. No badge, no install
 * affordance (those live on the Extensions tab). Swapped in for Mihon's `sourcesTab()` at the
 * [eu.kanade.tachiyomi.ui.browse.BrowseTab] call site via a `// RK` island.
 */
@Composable
fun Screen.reikaiSourcesTab(browseScreenModel: ReikaiBrowseScreenModel): TabContent {
    val navigator = LocalNavigator.currentOrThrow
    val sourcesModel = rememberScreenModel { SourcesScreenModel() }
    val sourcesState by sourcesModel.state.collectAsState()
    val novelModel = rememberScreenModel { NovelSourcesScreenModel() }
    val novelState by novelModel.state.collectAsState()
    val contentType by browseScreenModel.contentType.collectAsState()

    return TabContent(
        titleRes = MR.strings.label_sources,
        actions = listOf(
            AppBar.Action(
                title = stringResource(MR.strings.action_global_search),
                icon = Icons.Outlined.TravelExplore,
                onClick = { navigator.push(GlobalSearchScreen()) },
            ),
            AppBar.Action(
                title = stringResource(MR.strings.action_filter),
                icon = Icons.Outlined.FilterList,
                onClick = { navigator.push(SourcesFilterScreen()) },
            ),
        ),
        content = { contentPadding, snackbarHostState ->
            Column {
                ContentTypeFilterChips(
                    selected = contentType,
                    onSelect = browseScreenModel::setContentType,
                )
                when (contentType) {
                    ContentType.MANGA -> SourcesScreen(
                        state = sourcesState,
                        contentPadding = contentPadding,
                        onClickItem = { source, listing ->
                            navigator.push(BrowseSourceScreen(source.id, listing.query))
                        },
                        onClickPin = sourcesModel::togglePin,
                        onLongClickItem = sourcesModel::showSourceDialog,
                    )
                    ContentType.NOVELS -> NovelSourcesList(
                        state = novelState,
                        contentPadding = contentPadding,
                        onClickItem = { /* TODO(S3b): open NovelBrowseScreen */ },
                    )
                    ContentType.ALL -> CombinedSourcesContent(
                        sourcesState = sourcesState,
                        novelState = novelState,
                        sourcesModel = sourcesModel,
                        contentPadding = contentPadding,
                        onClickMangaItem = { source, listing ->
                            navigator.push(BrowseSourceScreen(source.id, listing.query))
                        },
                        onClickNovelItem = { /* TODO(S3b): open NovelBrowseScreen */ },
                    )
                }
            }

            sourcesState.dialog?.let { dialog ->
                val source = dialog.source
                SourceOptionsDialog(
                    source = source,
                    onClickPin = {
                        sourcesModel.togglePin(source)
                        sourcesModel.closeDialog()
                    },
                    onClickDisable = {
                        sourcesModel.toggleSource(source)
                        sourcesModel.closeDialog()
                    },
                    onDismiss = sourcesModel::closeDialog,
                )
            }

            val internalErrString = stringResource(MR.strings.internal_error)
            LaunchedEffect(Unit) {
                sourcesModel.events.collectLatest { event ->
                    when (event) {
                        SourcesScreenModel.Event.FailedFetchingSources ->
                            launch { snackbarHostState.showSnackbar(internalErrString) }
                    }
                }
            }
        },
    )
}

@Composable
private fun NovelSourcesList(
    state: NovelSourcesScreenModel.State,
    contentPadding: PaddingValues,
    onClickItem: () -> Unit,
) {
    when {
        state.isLoading -> LoadingScreen(Modifier.padding(contentPadding))
        state.isEmpty -> EmptyScreen(
            stringRes = MR.strings.ln_no_sources,
            modifier = Modifier.padding(contentPadding),
        )
        else -> ScrollbarLazyColumn(contentPadding = contentPadding + topSmallPaddingValues) {
            novelSourceItems(models = state.items, onClickItem = onClickItem)
        }
    }
}

/**
 * The unified "All" Sources list: Mihon's manga source rows (reused verbatim) under a Manga header,
 * then installed novel sources under a Novels header.
 */
@Composable
private fun CombinedSourcesContent(
    sourcesState: SourcesScreenModel.State,
    novelState: NovelSourcesScreenModel.State,
    sourcesModel: SourcesScreenModel,
    contentPadding: PaddingValues,
    onClickMangaItem: (Source, Listing) -> Unit,
    onClickNovelItem: () -> Unit,
) {
    val context = LocalContext.current
    ScrollbarLazyColumn(contentPadding = contentPadding + topSmallPaddingValues) {
        if (sourcesState.items.isNotEmpty()) {
            item(key = "all-manga-header") {
                BrowseSectionHeader(title = stringResource(MR.strings.content_type_manga))
            }
            items(
                items = sourcesState.items,
                key = {
                    when (it) {
                        is SourceUiModel.Header -> "all-manga-header-${it.hashCode()}"
                        is SourceUiModel.Item -> "all-manga-source-${it.source.key()}"
                    }
                },
            ) { model ->
                when (model) {
                    is SourceUiModel.Header -> BrowseSectionHeader(
                        title = LocaleHelper.getSourceDisplayName(model.language, context),
                    )
                    is SourceUiModel.Item -> SourceItem(
                        source = model.source,
                        onClickItem = onClickMangaItem,
                        onLongClickItem = sourcesModel::showSourceDialog,
                        onClickPin = sourcesModel::togglePin,
                    )
                }
            }
        }

        if (novelState.items.isNotEmpty()) {
            item(key = "all-novels-header") {
                BrowseSectionHeader(title = stringResource(MR.strings.content_type_novels))
            }
            novelSourceItems(models = novelState.items, onClickItem = onClickNovelItem)
        }
    }
}

private fun LazyListScope.novelSourceItems(
    models: List<NovelSourceUiModel>,
    onClickItem: () -> Unit,
) {
    items(
        items = models,
        key = {
            when (it) {
                is NovelSourceUiModel.Header -> "ln-source-header-${it.language}"
                is NovelSourceUiModel.Item -> "ln-source-${it.source.id}"
            }
        },
    ) { model ->
        when (model) {
            is NovelSourceUiModel.Header -> NovelSourceLanguageHeader(model.language)
            is NovelSourceUiModel.Item -> NovelSourceRow(
                name = model.source.name,
                lang = "",
                iconUrl = model.source.iconUrl,
                onClickItem = onClickItem,
            )
        }
    }
}

@Composable
private fun NovelSourceLanguageHeader(language: String) {
    val context = LocalContext.current
    BrowseSectionHeader(title = LocaleHelper.getSourceDisplayName(language, context))
}
