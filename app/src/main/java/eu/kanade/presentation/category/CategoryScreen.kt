package eu.kanade.presentation.category

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FlipToFront
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import eu.kanade.presentation.category.components.CategoryFloatingActionButton
import eu.kanade.presentation.category.components.CategoryListItem
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.tachiyomi.ui.category.CategoryScreenState
import reikai.domain.library.ContentType
import reikai.presentation.components.ContentTypeFilterChips
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import tachiyomi.domain.category.model.Category
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.components.material.topSmallPaddingValues
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.util.plus

@Composable
fun CategoryScreen(
    state: CategoryScreenState.Success,
    onClickCreate: () -> Unit,
    onClickRename: (Category) -> Unit,
    onClickDelete: (Category) -> Unit,
    // RK: toggle a category's hidden flag bit
    onClickToggleHidden: (Category) -> Unit,
    onChangeOrder: (Category, Int) -> Unit,
    navigateUp: () -> Unit,
    // RK --> multi-select: the action-mode toolbar + the undo snackbar live here
    onSelectContentType: (ContentType) -> Unit,
    snackbarHostState: SnackbarHostState,
    onToggleSelection: (Category) -> Unit,
    onToggleRangeSelection: (Category) -> Unit,
    onSelectAll: () -> Unit,
    onInvertSelection: () -> Unit,
    onClearSelection: () -> Unit,
    onDeleteSelected: () -> Unit,
    // RK <--
) {
    val lazyListState = rememberLazyListState()
    // RK: in selection mode the drag handle is hidden (selection-tap shouldn't fight a drag-grab), and
    // so is it under a content-type chip: a drop index comes from the list on screen while the reorder
    // renumbers the whole table, so dragging inside a narrowed list would land the row elsewhere.
    val reorderable = state.categorySortOrder == 0 &&
        !state.selectionMode &&
        state.contentType == ContentType.ALL
    Scaffold(
        topBar = { scrollBehavior ->
            AppBar(
                title = stringResource(MR.strings.action_edit_categories),
                navigateUp = navigateUp,
                // RK --> switch to Mihon's built-in action mode when categories are selected
                actionModeCounter = state.selection.size,
                onCancelActionMode = onClearSelection,
                actionModeActions = {
                    AppBarActions(
                        listOf(
                            AppBar.Action(
                                title = stringResource(MR.strings.action_select_all),
                                icon = Icons.Outlined.SelectAll,
                                onClick = onSelectAll,
                            ),
                            AppBar.Action(
                                title = stringResource(MR.strings.action_select_inverse),
                                icon = Icons.Outlined.FlipToFront,
                                onClick = onInvertSelection,
                            ),
                            AppBar.Action(
                                title = stringResource(MR.strings.action_delete),
                                icon = Icons.Outlined.Delete,
                                onClick = onDeleteSelected,
                            ),
                        ),
                    )
                },
                // RK <--
                scrollBehavior = scrollBehavior,
            )
        },
        // RK: host the bulk-delete undo snackbar
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        floatingActionButton = {
            // RK: hide the create FAB while selecting (the action-mode toolbar owns the bar)
            if (!state.selectionMode) {
                CategoryFloatingActionButton(
                    lazyListState = lazyListState,
                    onCreate = onClickCreate,
                )
            }
        },
    ) { paddingValues ->
        // RK --> the chip stays above the list, including when its filter empties it, so the user can
        // always switch back. Body padding drops the top, which the chip row now occupies.
        val layoutDirection = LocalLayoutDirection.current
        Column(modifier = Modifier.padding(top = paddingValues.calculateTopPadding())) {
            ContentTypeFilterChips(selected = state.contentType, onSelect = onSelectContentType)
            val bodyPadding = PaddingValues(
                start = paddingValues.calculateStartPadding(layoutDirection),
                end = paddingValues.calculateEndPadding(layoutDirection),
                bottom = paddingValues.calculateBottomPadding(),
            )
            Box(modifier = Modifier.weight(1f)) {
                if (state.isEmpty) {
                    EmptyScreen(
                        stringRes = if (state.contentType == ContentType.ALL) {
                            MR.strings.information_empty_category
                        } else {
                            MR.strings.information_empty_category_filtered
                        },
                        modifier = Modifier.padding(bodyPadding),
                    )
                } else {
                    CategoryContent(
                        categories = state.categories,
                        lazyListState = lazyListState,
                        paddingValues = bodyPadding,
                        onClickRename = onClickRename,
                        onClickDelete = onClickDelete,
                        onClickToggleHidden = onClickToggleHidden,
                        onChangeOrder = onChangeOrder,
                        // RK: drag only in Manual mode, outside selection, and only under the All chip
                        reorderable = reorderable,
                        selection = state.selection,
                        selectionMode = state.selectionMode,
                        onToggleSelection = onToggleSelection,
                        onToggleRangeSelection = onToggleRangeSelection,
                    )
                }
            }
        }
        // RK <--
    }
}

@Composable
private fun CategoryContent(
    categories: List<Category>,
    lazyListState: LazyListState,
    paddingValues: PaddingValues,
    onClickRename: (Category) -> Unit,
    onClickDelete: (Category) -> Unit,
    onClickToggleHidden: (Category) -> Unit,
    onChangeOrder: (Category, Int) -> Unit,
    // RK: false hides the drag handle so the (sorted) list can't be manually reordered
    reorderable: Boolean = true,
    // RK --> multi-select
    selection: Set<Long> = emptySet(),
    selectionMode: Boolean = false,
    onToggleSelection: (Category) -> Unit = {},
    onToggleRangeSelection: (Category) -> Unit = {},
    // RK <--
) {
    val categoriesState = remember { categories.toMutableStateList() }
    val reorderableState = rememberReorderableLazyListState(lazyListState, paddingValues) { from, to ->
        val item = categoriesState.removeAt(from.index)
        categoriesState.add(to.index, item)
        onChangeOrder(item, to.index)
    }

    LaunchedEffect(categories) {
        if (!reorderableState.isAnyItemDragging) {
            categoriesState.clear()
            categoriesState.addAll(categories)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = lazyListState,
        contentPadding = paddingValues +
            topSmallPaddingValues +
            PaddingValues(horizontal = MaterialTheme.padding.medium),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
    ) {
        items(
            items = categoriesState,
            key = { category -> category.key },
        ) { category ->
            ReorderableItem(reorderableState, category.key) {
                CategoryListItem(
                    modifier = Modifier.animateItem(),
                    category = category,
                    // RK --> in selection mode a tap toggles; a long-press always enters/toggles selection
                    selected = category.id in selection,
                    selectionMode = selectionMode,
                    onClick = {
                        if (selectionMode) onToggleSelection(category) else onClickRename(category)
                    },
                    onLongClick = { onToggleRangeSelection(category) },
                    // RK <--
                    onRename = { onClickRename(category) },
                    onDelete = { onClickDelete(category) },
                    onToggleHidden = { onClickToggleHidden(category) },
                    // RK: hide the drag handle (and thus disable drag) when auto-sorted or selecting
                    showDragHandle = reorderable,
                )
            }
        }
    }
}

private val Category.key inline get() = "category-$id"
