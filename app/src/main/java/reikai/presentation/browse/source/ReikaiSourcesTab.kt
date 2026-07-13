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
import androidx.compose.ui.draw.alpha
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
import reikai.novel.source.NovelSource
import reikai.presentation.browse.EntrySourceOptionsDialog
import reikai.presentation.browse.ReikaiBrowseScreenModel
import reikai.presentation.browse.components.BrowseSectionHeader
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
import tachiyomi.source.local.isLocal

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

    // Open a novel source's browse grid, recording it as last-used so the sources list populates.
    val openNovelSource: (String) -> Unit = { id ->
        browseScreenModel.setLastUsedNovelSource(id)
        navigator.push(NovelBrowseScreen(id))
    }

    return TabContent(
        titleRes = MR.strings.label_sources,
        actions = listOfNotNull(
            AppBar.Action(
                title = stringResource(MR.strings.action_global_search),
                icon = Icons.Outlined.TravelExplore,
                // Content-type-aware: the Novels chip searches LN sources; Manga / All use Mihon's.
                onClick = {
                    navigator.push(
                        if (contentType == ContentType.NOVELS) NovelGlobalSearchScreen() else GlobalSearchScreen(),
                    )
                },
            ),
            // Filter opens Mihon's manga sources-filter screen; novels have no language enable/disable
            // model to populate it, so it would be empty and misleading. Hide it on the Novels chip.
            AppBar.Action(
                title = stringResource(MR.strings.action_filter),
                icon = Icons.Outlined.FilterList,
                onClick = { navigator.push(SourcesFilterScreen()) },
            ).takeIf { contentType != ContentType.NOVELS },
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
                        onClickItem = openNovelSource,
                        onClickPin = novelModel::togglePin,
                        onLongClickItem = novelModel::showSourceDialog,
                    )
                    ContentType.ALL -> CombinedSourcesContent(
                        sourcesState = sourcesState,
                        novelState = novelState,
                        sourcesModel = sourcesModel,
                        contentPadding = contentPadding,
                        onClickMangaItem = { source, listing ->
                            navigator.push(BrowseSourceScreen(source.id, listing.query))
                        },
                        onClickNovelItem = openNovelSource,
                        onClickNovelPin = novelModel::togglePin,
                        onLongClickNovelItem = novelModel::showSourceDialog,
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

            novelState.dialog?.let { dialog ->
                EntrySourceOptionsDialog(
                    title = dialog.source.name,
                    isPinned = dialog.isPinned,
                    showToggleDisable = true,
                    isDisabled = dialog.isDisabled,
                    onClickPin = {
                        novelModel.togglePin(dialog.source.id)
                        novelModel.closeDialog()
                    },
                    onClickToggleDisable = {
                        novelModel.toggleDisable(dialog.source.id)
                        novelModel.closeDialog()
                    },
                    onDismiss = novelModel::closeDialog,
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
    onClickItem: (String) -> Unit,
    onClickPin: (String) -> Unit,
    onLongClickItem: (NovelSource) -> Unit,
) {
    when {
        state.isLoading -> LoadingScreen(Modifier.padding(contentPadding))
        state.isEmpty -> EmptyScreen(
            stringRes = MR.strings.ln_no_sources,
            modifier = Modifier.padding(contentPadding),
        )
        else -> ScrollbarLazyColumn(contentPadding = contentPadding + topSmallPaddingValues) {
            novelSourceItems(
                models = state.items,
                onClickItem = onClickItem,
                onClickPin = onClickPin,
                onLongClickItem = onLongClickItem,
            )
        }
    }
}

/**
 * The unified "All" Sources list: Mihon's manga source rows (reused verbatim) under a Manga header,
 * then installed novel sources under a Novels header, and finally the local "Other" group at the
 * bottom (Manga -> Novels -> Other), since the local source belongs to neither content type.
 */
@Composable
private fun CombinedSourcesContent(
    sourcesState: SourcesScreenModel.State,
    novelState: NovelSourcesScreenModel.State,
    sourcesModel: SourcesScreenModel,
    contentPadding: PaddingValues,
    onClickMangaItem: (Source, Listing) -> Unit,
    onClickNovelItem: (String) -> Unit,
    onClickNovelPin: (String) -> Unit,
    onLongClickNovelItem: (NovelSource) -> Unit,
) {
    val groups = parseSourceGroups(sourcesState.items)
    val otherGroups = groups.filter { group -> group.sources.any { it.isLocal() } }
    val mangaGroups = groups - otherGroups.toSet()

    ScrollbarLazyColumn(contentPadding = contentPadding + topSmallPaddingValues) {
        if (mangaGroups.isNotEmpty()) {
            item(key = "all-manga-header") {
                BrowseSectionHeader(title = stringResource(MR.strings.content_type_manga))
            }
            mangaSourceGroups(mangaGroups, onClickMangaItem, sourcesModel::showSourceDialog, sourcesModel::togglePin)
        }

        if (novelState.items.isNotEmpty()) {
            item(key = "all-novels-header") {
                BrowseSectionHeader(title = stringResource(MR.strings.content_type_novels))
            }
            novelSourceItems(
                models = novelState.items,
                onClickItem = onClickNovelItem,
                onClickPin = onClickNovelPin,
                onLongClickItem = onLongClickNovelItem,
            )
        }

        // Other (local source) sinks to the bottom, after both content types.
        mangaSourceGroups(otherGroups, onClickMangaItem, sourcesModel::showSourceDialog, sourcesModel::togglePin)
    }
}

private data class SourceGroup(val language: String, val sources: List<Source>)

/** Collapse the flat [SourceUiModel] header/item stream back into per-language groups. */
private fun parseSourceGroups(items: List<SourceUiModel>): List<SourceGroup> {
    val groups = mutableListOf<SourceGroup>()
    var language: String? = null
    val sources = mutableListOf<Source>()
    items.forEach { model ->
        when (model) {
            is SourceUiModel.Header -> {
                language?.let { groups.add(SourceGroup(it, sources.toList())) }
                language = model.language
                sources.clear()
            }
            is SourceUiModel.Item -> sources.add(model.source)
        }
    }
    language?.let { groups.add(SourceGroup(it, sources.toList())) }
    return groups
}

private fun LazyListScope.mangaSourceGroups(
    groups: List<SourceGroup>,
    onClickItem: (Source, Listing) -> Unit,
    onLongClickItem: (Source) -> Unit,
    onClickPin: (Source) -> Unit,
) {
    groups.forEach { group ->
        item(key = "all-manga-header-${group.language}") {
            BrowseSectionHeader(
                title = LocaleHelper.getSourceDisplayName(group.language, LocalContext.current),
            )
        }
        items(group.sources, key = { "all-manga-source-${it.key()}" }) { source ->
            SourceItem(
                source = source,
                onClickItem = onClickItem,
                onLongClickItem = onLongClickItem,
                onClickPin = onClickPin,
            )
        }
    }
}

/** Alpha applied to a disabled novel source row so it reads as inactive but stays tappable. */
private const val DISABLED_SOURCE_ALPHA = 0.38f

private fun LazyListScope.novelSourceItems(
    models: List<NovelSourceUiModel>,
    onClickItem: (String) -> Unit,
    onClickPin: (String) -> Unit,
    onLongClickItem: (NovelSource) -> Unit,
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
                modifier = if (model.isDisabled) Modifier.alpha(DISABLED_SOURCE_ALPHA) else Modifier,
                name = model.source.name,
                lang = "",
                iconUrl = model.source.iconUrl,
                onClickItem = { onClickItem(model.source.id) },
                onLongClickItem = { onLongClickItem(model.source) },
                action = {
                    NovelSourcePinButton(
                        isPinned = model.isPinned,
                        onClick = { onClickPin(model.source.id) },
                    )
                },
            )
        }
    }
}

@Composable
private fun NovelSourceLanguageHeader(language: String) {
    val context = LocalContext.current
    BrowseSectionHeader(title = LocaleHelper.getSourceDisplayName(language, context))
}
