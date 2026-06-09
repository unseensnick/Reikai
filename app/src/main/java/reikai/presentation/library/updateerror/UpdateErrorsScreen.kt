package reikai.presentation.library.updateerror

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SelectAll
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
import tachiyomi.domain.manga.model.MangaCover as MangaCoverData
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.selectedBackground

class UpdateErrorsScreen : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { UpdateErrorsScreenModel() }
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
                    actions = {
                        if (successState.selectionMode) {
                            AppBarActions(
                                listOf(
                                    AppBar.Action(
                                        title = stringResource(MR.strings.action_select_all),
                                        icon = Icons.Outlined.SelectAll,
                                        onClick = screenModel::selectAll,
                                    ),
                                    AppBar.Action(
                                        title = stringResource(MR.strings.action_delete),
                                        icon = Icons.Outlined.Delete,
                                        onClick = screenModel::dismissSelected,
                                    ),
                                ),
                            )
                        } else if (!successState.isEmpty) {
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
            if (successState.isEmpty) {
                EmptyScreen(
                    stringRes = MR.strings.info_empty_update_errors,
                    modifier = Modifier.padding(paddingValues),
                )
                return@Scaffold
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = paddingValues,
            ) {
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
                        key = { "error-${it.error.errorId}" },
                    ) { item ->
                        UpdateErrorRow(
                            item = item,
                            isSelected = item.error.errorId in successState.selected,
                            onClick = {
                                if (successState.selectionMode) {
                                    screenModel.toggleSelection(item.error.errorId)
                                } else {
                                    navigator.push(MangaScreen(item.error.mangaId))
                                }
                            },
                            onLongClick = { screenModel.toggleSelection(item.error.errorId) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdateErrorRow(
    item: UpdateErrorItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .selectedBackground(isSelected)
            .height(56.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MangaCover.Book(
            data = MangaCoverData(
                mangaId = item.error.mangaId,
                sourceId = item.error.sourceId,
                isMangaFavorite = true,
                url = item.error.thumbnailUrl,
                lastModified = item.error.coverLastModified,
            ),
            modifier = Modifier.fillMaxHeight(),
        )
        Column(modifier = Modifier.padding(start = MaterialTheme.padding.medium)) {
            Text(
                text = item.error.mangaTitle,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.sourceName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
