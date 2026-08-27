package reikai.presentation.browse.migrate

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.domain.source.interactor.SetMigrateSorting
import eu.kanade.presentation.browse.components.SourceIcon
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.TabContent
import eu.kanade.tachiyomi.ui.browse.migration.sources.MigrateSourceViewModel
import eu.kanade.tachiyomi.util.system.copyToClipboard
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import reikai.domain.library.ContentType
import reikai.domain.source.SourceKey
import reikai.presentation.browse.ReikaiBrowseViewModel
import reikai.presentation.browse.components.ContentTypeBadge
import reikai.presentation.browse.components.NovelSourceIcon
import reikai.presentation.components.ContentTypeFilterChips
import reikai.presentation.migrate.flow.EntryMigrationFavoritesScreen
import tachiyomi.core.common.Constants
import tachiyomi.domain.source.model.Source
import tachiyomi.i18n.MR
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
 * The Browse "Migration" tab: one list of every source holding favourites, manga and light novel
 * together, with the content-type chip as a filter over it rather than a switch between two lists.
 *
 * Assembly, the order and the sort header live in [MigrateSourcesEngine]; this draws what it is given
 * and routes a tap. Replaces Mihon's `migrateSourceTab()` via a `// RK` island at its call site; the
 * replaced builder and screen are deleted (see the off-path manifest).
 */
@Composable
fun Screen.reikaiMigrateSourceTab(browseViewModel: ReikaiBrowseViewModel): TabContent {
    val uriHandler = LocalUriHandler.current
    val navigator = LocalNavigator.currentOrThrow
    val mangaModel = metroViewModel<MigrateSourceViewModel>()
    val novelModel = metroViewModel<MigrateNovelSourcesViewModel>()
    val providers = remember(mangaModel, novelModel) {
        listOf(MangaMigrateSourcesProvider(mangaModel), NovelMigrateSourcesProvider(novelModel))
    }
    val engine = assistedMetroViewModel<MigrateSourcesEngine, MigrateSourcesEngine.Factory> {
        create(providers)
    }
    val state by engine.state.collectAsStateWithLifecycle()

    return TabContent(
        titleRes = MR.strings.label_migration,
        actions = listOf(
            AppBar.Action(
                title = stringResource(MR.strings.migration_help_guide),
                icon = Icons.AutoMirrored.Outlined.HelpOutline,
                onClick = { uriHandler.openUri("${Constants.URL_DOCS}/guides/source-migration") },
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
                        stringRes = MR.strings.information_empty_library,
                        modifier = Modifier.padding(contentPadding),
                    )
                    else -> MigrateSourcesList(
                        state = state,
                        showContentType = state.contentType == ContentType.ALL,
                        contentPadding = contentPadding,
                        onClickItem = { row ->
                            navigator.push(
                                EntryMigrationFavoritesScreen(row.key.contentType, row.key.migrationId),
                            )
                        },
                        onToggleSortingMode = engine::toggleSortingMode,
                        onToggleSortingDirection = engine::toggleSortingDirection,
                    )
                }
            }

            val internalErrString = stringResource(MR.strings.internal_error)
            LaunchedEffect(Unit) {
                mangaModel.channel.collectLatest { event ->
                    when (event) {
                        MigrateSourceViewModel.Event.FailedFetchingSourcesWithCount ->
                            launch { snackbarHostState.showSnackbar(internalErrString) }
                    }
                }
            }
        },
    )
}

/** The id the migration picker takes for this source: a manga source's number, a plugin's own id. */
private val SourceKey.migrationId: String
    get() = when (this) {
        is SourceKey.Manga -> id.toString()
        is SourceKey.Novel -> id
    }

@Composable
private fun MigrateSourcesList(
    state: MigrateSourcesEngine.State,
    showContentType: Boolean,
    contentPadding: PaddingValues,
    onClickItem: (BrowseMigrateRow) -> Unit,
    onToggleSortingMode: () -> Unit,
    onToggleSortingDirection: () -> Unit,
) {
    val context = LocalContext.current
    ScrollbarLazyColumn(contentPadding = contentPadding + topSmallPaddingValues) {
        stickyHeader(key = STICKY_HEADER_KEY_PREFIX) {
            MigrateSortHeader(
                sortingMode = state.sortingMode,
                sortingDirection = state.sortingDirection,
                onToggleSortingMode = onToggleSortingMode,
                onToggleSortingDirection = onToggleSortingDirection,
            )
        }
        items(items = state.items, key = { "migrate-${it.key}" }) { row ->
            EntryMigrateSourceRow(
                row = row,
                modifier = Modifier.animateItem(),
                onClickItem = { onClickItem(row) },
                // Copying the id is how a source with no name left is identified elsewhere.
                onLongClickItem = {
                    val id = row.key.migrationId
                    context.copyToClipboard(id, id)
                },
                badge = { if (showContentType) ContentTypeBadge(row.key.contentType) },
                icon = {
                    when (row.key) {
                        is SourceKey.Manga -> SourceIcon(source = row.source as Source)
                        is SourceKey.Novel -> NovelSourceIcon((row.source as NovelMigrateSource).iconUrl)
                    }
                },
            )
        }
    }
}

/** The "Select a source to migrate from" prompt plus the alpha/total and asc/desc sort toggles. */
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
