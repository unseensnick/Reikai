package eu.kanade.presentation.category

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import eu.kanade.presentation.category.components.CategoryFloatingActionButton
import eu.kanade.presentation.category.components.CategoryListItem
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.tachiyomi.ui.category.CategoryScreenState
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
    snackbarHostState: SnackbarHostState,
    onToggleSelection: (Category) -> Unit,
    onSelectAll: () -> Unit,
    onInvertSelection: () -> Unit,
    onClearSelection: () -> Unit,
    onDeleteSelected: () -> Unit,
    // RK <--
    // RK: optional content-type chip (Manga/Novels) rendered above the list; null = manga path unchanged
    header: (@Composable () -> Unit)? = null,
) {
    val lazyListState = rememberLazyListState()
    // RK: in selection mode the drag handle is hidden (selection-tap shouldn't fight a drag-grab)
    val reorderable = state.categorySortOrder == 0 && !state.selectionMode
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
        // RK --> with the chip header, consume the top inset into a Column so the chip sits below the
        // toolbar and the list gets only the bottom inset (the manga path keeps the original layout).
        if (header != null) {
            Column(modifier = Modifier.padding(top = paddingValues.calculateTopPadding())) {
                header()
                val innerPadding = PaddingValues(bottom = paddingValues.calculateBottomPadding())
                if (state.isEmpty) {
                    EmptyScreen(
                        stringRes = MR.strings.information_empty_category,
                        modifier = Modifier.padding(innerPadding),
                    )
                } else {
                    CategoryContent(
                        categories = state.categories,
                        lazyListState = lazyListState,
                        paddingValues = innerPadding,
                        onClickRename = onClickRename,
                        onClickDelete = onClickDelete,
                        onClickToggleHidden = onClickToggleHidden,
                        onChangeOrder = onChangeOrder,
                        reorderable = reorderable,
                        selection = state.selection,
                        selectionMode = state.selectionMode,
                        onToggleSelection = onToggleSelection,
                    )
                }
            }
            return@Scaffold
        }
        // RK <--
        if (state.isEmpty) {
            EmptyScreen(
                stringRes = MR.strings.information_empty_category,
                modifier = Modifier.padding(paddingValues),
            )
            return@Scaffold
        }

        CategoryContent(
            categories = state.categories,
            lazyListState = lazyListState,
            paddingValues = paddingValues,
            onClickRename = onClickRename,
            onClickDelete = onClickDelete,
            onClickToggleHidden = onClickToggleHidden,
            onChangeOrder = onChangeOrder,
            // RK: drag-reorder only in Manual (off) mode and outside selection; sorted/selecting = no drag
            reorderable = reorderable,
            selection = state.selection,
            selectionMode = state.selectionMode,
            onToggleSelection = onToggleSelection,
        )
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
                    onLongClick = { onToggleSelection(category) },
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
