package reikai.presentation.browse.migrate

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Numbers
import androidx.compose.material.icons.outlined.SortByAlpha
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.source.interactor.SetMigrateSorting
import eu.kanade.presentation.browse.MigrateSourceScreen
import eu.kanade.presentation.browse.components.BaseSourceItem
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.TabContent
import eu.kanade.tachiyomi.ui.browse.migration.manga.MigrateMangaScreen
import eu.kanade.tachiyomi.ui.browse.migration.sources.MigrateSourceScreenModel
import reikai.domain.library.ContentType
import reikai.presentation.browse.ReikaiBrowseScreenModel
import reikai.presentation.browse.components.BrowseSectionHeader
import reikai.presentation.browse.components.NovelSourceRow
import reikai.presentation.components.ContentTypeFilterChips
import tachiyomi.domain.source.model.Source
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.Badge
import tachiyomi.presentation.core.components.BadgeGroup
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.Scroller.STICKY_HEADER_KEY_PREFIX
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.components.material.topSmallPaddingValues
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.theme.header
import tachiyomi.presentation.core.util.plus

/**
 * Reikai wrapper for the Browse "Migration" tab: the sticky content-type chip over Mihon's manga
 * migrate-source list (reused verbatim) and a net-new novel migrate-source list. Manga and Novels
 * each show their sources with a favorite count; All interleaves both under type headers. Swapped in
 * for Mihon's `migrateSourceTab()` at the [eu.kanade.tachiyomi.ui.browse.BrowseTab] call site via a
 * `// RK` island. Mirrors [reikai.presentation.browse.source.reikaiSourcesTab].
 */
@Composable
fun Screen.reikaiMigrateSourceTab(browseScreenModel: ReikaiBrowseScreenModel): TabContent {
    val uriHandler = LocalUriHandler.current
    val navigator = LocalNavigator.currentOrThrow
    val mangaModel = rememberScreenModel { MigrateSourceScreenModel() }
    val mangaState by mangaModel.state.collectAsState()
    val novelModel = rememberScreenModel { MigrateNovelSourcesScreenModel() }
    val novelState by novelModel.state.collectAsState()
    val contentType by browseScreenModel.contentType.collectAsState()

    return TabContent(
        titleRes = MR.strings.label_migration,
        actions = listOf(
            AppBar.Action(
                title = stringResource(MR.strings.migration_help_guide),
                icon = Icons.AutoMirrored.Outlined.HelpOutline,
                onClick = { uriHandler.openUri("https://mihon.app/docs/guides/source-migration") },
            ),
        ),
        content = { contentPadding, _ ->
            Column {
                ContentTypeFilterChips(
                    selected = contentType,
                    onSelect = browseScreenModel::setContentType,
                )
                when (contentType) {
                    ContentType.MANGA -> MigrateSourceScreen(
                        state = mangaState,
                        contentPadding = contentPadding,
                        onClickItem = { navigator.push(MigrateMangaScreen(it.id)) },
                        onToggleSortingDirection = mangaModel::toggleSortingDirection,
                        onToggleSortingMode = mangaModel::toggleSortingMode,
                    )
                    ContentType.NOVELS -> NovelMigrateSourceList(
                        state = novelState,
                        contentPadding = contentPadding,
                        onClickItem = { navigator.push(MigrateNovelScreen(it.id)) },
                        onToggleSortingMode = novelModel::toggleSortingMode,
                        onToggleSortingDirection = novelModel::toggleSortingDirection,
                    )
                    ContentType.ALL -> CombinedMigrateSourcesContent(
                        mangaState = mangaState,
                        novelState = novelState,
                        contentPadding = contentPadding,
                        onClickMangaItem = { navigator.push(MigrateMangaScreen(it.id)) },
                        onClickNovelItem = { navigator.push(MigrateNovelScreen(it.id)) },
                    )
                }
            }
        },
    )
}

@Composable
private fun NovelMigrateSourceList(
    state: MigrateNovelSourcesScreenModel.State,
    contentPadding: PaddingValues,
    onClickItem: (NovelMigrateSource) -> Unit,
    onToggleSortingMode: () -> Unit,
    onToggleSortingDirection: () -> Unit,
) {
    when {
        state.isLoading -> LoadingScreen(Modifier.padding(contentPadding))
        state.isEmpty -> EmptyScreen(
            stringRes = MR.strings.information_empty_library,
            modifier = Modifier.padding(contentPadding),
        )
        else -> ScrollbarLazyColumn(contentPadding = contentPadding + topSmallPaddingValues) {
            stickyHeader(key = STICKY_HEADER_KEY_PREFIX) {
                MigrateSortHeader(
                    sortingMode = state.sortingMode,
                    sortingDirection = state.sortingDirection,
                    onToggleSortingMode = onToggleSortingMode,
                    onToggleSortingDirection = onToggleSortingDirection,
                )
            }
            novelMigrateSourceItems(state.items, onClickItem)
        }
    }
}

/**
 * The unified "All" migration list: manga migrate-sources under a Manga header, then novel
 * migrate-sources under a Novels header. No sort toggle here (matching the Sources-tab All view);
 * each single-type chip carries its own sort header.
 */
@Composable
private fun CombinedMigrateSourcesContent(
    mangaState: MigrateSourceScreenModel.State,
    novelState: MigrateNovelSourcesScreenModel.State,
    contentPadding: PaddingValues,
    onClickMangaItem: (Source) -> Unit,
    onClickNovelItem: (NovelMigrateSource) -> Unit,
) {
    ScrollbarLazyColumn(contentPadding = contentPadding + topSmallPaddingValues) {
        if (mangaState.items.isNotEmpty()) {
            item(key = "all-migrate-manga-header") {
                BrowseSectionHeader(title = stringResource(MR.strings.content_type_manga))
            }
            items(
                items = mangaState.items,
                key = { (source, _) -> "migrate-manga-${source.id}" },
            ) { (source, count) ->
                MangaMigrateSourceRow(source = source, count = count, onClick = { onClickMangaItem(source) })
            }
        }
        if (novelState.items.isNotEmpty()) {
            item(key = "all-migrate-novels-header") {
                BrowseSectionHeader(title = stringResource(MR.strings.content_type_novels))
            }
            novelMigrateSourceItems(novelState.items, onClickNovelItem)
        }
    }
}

private fun LazyListScope.novelMigrateSourceItems(
    items: List<NovelMigrateSource>,
    onClickItem: (NovelMigrateSource) -> Unit,
) {
    items(items = items, key = { "migrate-novel-${it.id}" }) { item ->
        NovelSourceRow(
            name = item.name,
            lang = item.lang,
            iconUrl = item.iconUrl,
            onClickItem = { onClickItem(item) },
            action = { BadgeGroup { Badge(text = "${item.count}") } },
        )
    }
}

@Composable
private fun MangaMigrateSourceRow(source: Source, count: Long, onClick: () -> Unit) {
    BaseSourceItem(
        source = source,
        showLanguageInContent = source.lang != "",
        onClickItem = onClick,
        action = { BadgeGroup { Badge(text = "$count") } },
    )
}

/** The "Select a source to migrate from" prompt plus the alpha/total and asc/desc sort toggles,
 *  mirroring Mihon's manga [MigrateSourceScreen] header so the Novels list sorts identically. */
@Composable
private fun MigrateSortHeader(
    sortingMode: SetMigrateSorting.Mode,
    sortingDirection: SetMigrateSorting.Direction,
    onToggleSortingMode: () -> Unit,
    onToggleSortingDirection: () -> Unit,
) {
    Row(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(start = MaterialTheme.padding.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(MR.strings.migration_selection_prompt),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.header,
        )
        IconButton(onClick = onToggleSortingMode) {
            when (sortingMode) {
                SetMigrateSorting.Mode.ALPHABETICAL -> Icon(
                    Icons.Outlined.SortByAlpha,
                    contentDescription = stringResource(MR.strings.action_sort_alpha),
                )
                SetMigrateSorting.Mode.TOTAL -> Icon(
                    Icons.Outlined.Numbers,
                    contentDescription = stringResource(MR.strings.action_sort_count),
                )
            }
        }
        IconButton(onClick = onToggleSortingDirection) {
            when (sortingDirection) {
                SetMigrateSorting.Direction.ASCENDING -> Icon(
                    Icons.Outlined.ArrowUpward,
                    contentDescription = stringResource(MR.strings.action_asc),
                )
                SetMigrateSorting.Direction.DESCENDING -> Icon(
                    Icons.Outlined.ArrowDownward,
                    contentDescription = stringResource(MR.strings.action_desc),
                )
            }
        }
    }
}
