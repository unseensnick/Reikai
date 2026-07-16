package reikai.presentation.recommendation.browse

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreen
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import kotlinx.coroutines.launch
import reikai.presentation.browse.components.BulkSelectionToolbar
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen

/**
 * RK: "See all" browse grid for a manga's related-mangas pool. Constructor takes serializable args
 * only ([mangaId] + [mangaTitle]); the candidate pool is re-read from [reikai.domain.recommendation
 * .RelatedMangaCache] inside the screen model.
 */
class RelatedMangasBrowseScreen(
    private val mangaId: Long,
    private val mangaTitle: String,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val configuration = LocalConfiguration.current
        val screenModel = rememberScreenModel { RelatedMangasBrowseScreenModel(mangaId, context) }
        val state by screenModel.state.collectAsState()

        Scaffold(
            topBar = { scrollBehavior ->
                // RK: selection toolbar while bulk-selecting, otherwise the normal toolbar with a
                //     Select button (long-press an item is the other way into selection mode).
                if (state.selectionMode) {
                    BulkSelectionToolbar(
                        selectedCount = state.selectedUrls.size,
                        onClickClearSelection = screenModel::clearSelection,
                        onChangeCategoryClick = screenModel::addSelectedToLibrary,
                        onSelectAll = screenModel::selectAll,
                    )
                } else {
                    AppBar(
                        title = mangaTitle,
                        subtitle = stringResource(MR.strings.related_mangas),
                        navigateUp = navigator::pop,
                        actions = {
                            if (state.hasHidden) {
                                IconButton(onClick = screenModel::toggleShowHidden) {
                                    Icon(
                                        imageVector = Icons.Outlined.Visibility,
                                        contentDescription = stringResource(MR.strings.recs_show_hidden),
                                        tint = if (state.showHidden) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            LocalContentColor.current
                                        },
                                    )
                                }
                            }
                            if (state.hasMultipleOrigins) {
                                IconButton(onClick = screenModel::toggleGrouping) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Outlined.ViewList,
                                        contentDescription = stringResource(MR.strings.recs_group_toggle),
                                        tint = if (state.grouped) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            LocalContentColor.current
                                        },
                                    )
                                }
                            }
                            IconButton(onClick = screenModel::enterSelectionMode) {
                                Icon(
                                    imageVector = Icons.Outlined.Checklist,
                                    contentDescription = stringResource(MR.strings.action_bulk_select),
                                )
                            }
                        },
                        scrollBehavior = scrollBehavior,
                    )
                }
            },
            snackbarHost = { SnackbarHost(screenModel.snackbarHostState) },
        ) { contentPadding ->
            when {
                state.loading -> LoadingScreen(Modifier.padding(contentPadding))
                state.items.isEmpty() -> EmptyScreen(
                    stringRes = MR.strings.recs_browse_empty,
                    modifier = Modifier.padding(contentPadding),
                )
                else -> RelatedMangasBrowseContent(
                    items = state.visibleItems(),
                    columns = screenModel.getColumns(configuration.orientation),
                    selectedUrls = state.selectedUrls,
                    grouped = state.grouped,
                    contentPadding = contentPadding,
                    onItemClick = { item ->
                        if (state.selectionMode) {
                            screenModel.toggleSelection(item.candidate.manga.url)
                        } else {
                            scope.launch {
                                val id = screenModel.resolveToLocalId(item.candidate)
                                if (id != null) {
                                    navigator.push(MangaScreen(id))
                                } else {
                                    navigator.push(GlobalSearchScreen(item.candidate.manga.title))
                                }
                            }
                        }
                    },
                    onItemLongClick = { item -> screenModel.toggleRangeSelection(item.candidate.manga.url) },
                )
            }
        }

        when (val dialog = state.dialog) {
            is RelatedMangasBrowseScreenModel.Dialog.ChangeCategory -> {
                ChangeCategoryDialog(
                    initialSelection = dialog.initialSelection,
                    onDismissRequest = screenModel::dismissDialog,
                    onEditCategories = { navigator.push(CategoryScreen()) },
                    onConfirm = { include, _ ->
                        screenModel.confirmCategories(dialog.target, include, dialog.skippedTrackerCount)
                    },
                )
            }
            null -> {}
        }
    }
}
