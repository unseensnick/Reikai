package reikai.presentation.library.updateerror

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.SwapCalls
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import mihon.feature.migration.config.MigrationConfigScreen
import reikai.data.coil.NovelCover
import reikai.domain.library.ContentType
import reikai.presentation.components.ContentTypeFilterChips
import reikai.presentation.novel.details.NovelScreen
import reikai.presentation.novel.migrate.NovelMigrationConfigScreen
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.selectedBackground
import tachiyomi.domain.manga.model.MangaCover as MangaCoverData

/** Opens on the [initialContentType] chip (e.g. Novels when reached from the novel library). */
class UpdateErrorsScreen(
    private val initialContentType: ContentType = ContentType.ALL,
) : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { UpdateErrorsScreenModel(initialContentType) }
        val state by screenModel.state.collectAsState()

        if (state is UpdateErrorsScreenState.Loading) {
            LoadingScreen()
            return
        }
        val successState = state as UpdateErrorsScreenState.Success

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(MR.strings.label_update_errors),
                    navigateUp = navigator::pop,
                    actionModeCounter = successState.selected.size,
                    onCancelActionMode = screenModel::clearSelection,
                    actionModeActions = {
                        AppBarActions(
                            listOf(
                                AppBar.Action(
                                    title = stringResource(MR.strings.action_select_all),
                                    icon = Icons.Outlined.SelectAll,
                                    onClick = screenModel::selectAll,
                                ),
                                AppBar.Action(
                                    title = stringResource(MR.strings.action_migrate),
                                    icon = Icons.Outlined.SwapCalls,
                                    enabled = successState.selectionIsSingleVertical,
                                    onClick = {
                                        val mangaIds = screenModel.selectedMangaIds()
                                        val novelIds = screenModel.selectedNovelIds()
                                        if (mangaIds.isNotEmpty()) {
                                            navigator.push(MigrationConfigScreen(mangaIds))
                                        } else if (novelIds.isNotEmpty()) {
                                            navigator.push(NovelMigrationConfigScreen(novelIds))
                                        }
                                        screenModel.clearSelection()
                                    },
                                ),
                                AppBar.Action(
                                    title = stringResource(MR.strings.action_delete),
                                    icon = Icons.Outlined.Delete,
                                    onClick = screenModel::dismissSelected,
                                ),
                            ),
                        )
                    },
                    actions = {
                        if (!successState.isEmpty) {
                            AppBarActions(
                                listOf(
                                    AppBar.Action(
                                        title = stringResource(MR.strings.action_update_library),
                                        icon = Icons.Outlined.Refresh,
                                        onClick = { screenModel.retry(context) },
                                    ),
                                    AppBar.OverflowAction(
                                        title = stringResource(MR.strings.action_clear_all),
                                        onClick = screenModel::dismissAll,
                                    ),
                                ),
                            )
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { paddingValues ->
            Column(modifier = Modifier.padding(paddingValues)) {
                ContentTypeFilterChips(
                    selected = successState.contentType,
                    onSelect = screenModel::setContentType,
                )

                if (successState.isEmpty) {
                    EmptyScreen(
                        stringRes = MR.strings.info_empty_update_errors,
                        modifier = Modifier.fillMaxSize(),
                    )
                    return@Column
                }

                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    successState.groups.forEach { group ->
                        item(key = "header-${group.message}") {
                            Text(
                                text = group.message,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(
                                    horizontal = MaterialTheme.padding.medium,
                                    vertical = MaterialTheme.padding.small,
                                ),
                            )
                        }
                        items(
                            items = group.errors,
                            key = { "error-${it.key}" },
                        ) { entry ->
                            UpdateErrorRow(
                                entry = entry,
                                isSelected = entry.key in successState.selected,
                                onClick = {
                                    if (successState.selectionMode) {
                                        screenModel.toggleSelection(entry.key)
                                    } else {
                                        when (entry) {
                                            is UpdateErrorEntry.Manga ->
                                                navigator.push(MangaScreen(entry.error.mangaId))
                                            is UpdateErrorEntry.Novel ->
                                                navigator.push(NovelScreen(entry.error.source, entry.error.novelUrl))
                                        }
                                    }
                                },
                                onLongClick = { screenModel.toggleSelection(entry.key) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdateErrorRow(
    entry: UpdateErrorEntry,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val coverData: Any = when (entry) {
        is UpdateErrorEntry.Manga -> MangaCoverData(
            mangaId = entry.error.mangaId,
            sourceId = entry.error.sourceId,
            isMangaFavorite = true,
            url = entry.error.thumbnailUrl,
            lastModified = entry.error.coverLastModified,
        )
        is UpdateErrorEntry.Novel -> NovelCover(
            url = entry.error.thumbnailUrl,
            site = null,
            isNovelFavorite = true,
            lastModified = entry.error.coverLastModified,
            novelId = entry.error.novelId,
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectedBackground(isSelected)
            .height(56.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MangaCover.Book(
            data = coverData,
            modifier = Modifier.fillMaxHeight(),
        )
        Column(modifier = Modifier.padding(start = MaterialTheme.padding.medium)) {
            Text(
                text = entry.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = entry.sourceName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
