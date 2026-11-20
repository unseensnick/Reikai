// RK: whole file, one category list for both content types, see docs/dev/subsystems/library.md
package eu.kanade.tachiyomi.ui.category

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.util.fastMap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.category.CategoryScreen
import eu.kanade.presentation.category.components.CategoryCreateDialog
import eu.kanade.presentation.category.components.CategoryDeleteDialog
import eu.kanade.presentation.category.components.CategoryDeleteSelectedDialog
import eu.kanade.presentation.category.components.CategoryRenameDialog
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import mihon.core.navigation.LocalNavigator
import reikai.domain.category.toCategoryContentType
import reikai.domain.library.ContentType
import tachiyomi.core.common.i18n.pluralStringResource
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.category.model.Category
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.screens.LoadingScreen

// RK: one list spanning both libraries; each row carries the content type it applies to.
@Composable
fun CategoryScreen() {
    val viewModel = metroViewModel<CategoryViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()
    CategoryManager(
        state = state,
        events = viewModel.events,
        onClickCreate = { viewModel.showDialog(CategoryDialog.Create) },
        onClickRename = { viewModel.showDialog(CategoryDialog.Rename(it)) },
        onClickDelete = { viewModel.showDialog(CategoryDialog.Delete(it)) },
        onClickDeleteSelected = { viewModel.showDialog(CategoryDialog.DeleteSelected) },
        onToggleHidden = viewModel::toggleHidden,
        onChangeOrder = viewModel::changeOrder,
        onMoveToEnd = viewModel::moveToEnd, // RK
        onDismissDialog = viewModel::dismissDialog,
        onCreate = viewModel::createCategory,
        onRename = viewModel::renameCategory,
        onDelete = viewModel::deleteCategory,
        onSelectContentType = viewModel::setContentType,
        onToggleSelection = { viewModel.toggleSelection(it.id) },
        onToggleRangeSelection = { viewModel.toggleRangeSelection(it.id) },
        onSelectAll = viewModel::selectAll,
        onInvertSelection = viewModel::invertSelection,
        onClearSelection = viewModel::clearSelection,
        onDeleteSelected = viewModel::deleteSelected,
        onUndoDelete = viewModel::undoPendingDelete,
        onCommitDelete = viewModel::commitPendingDelete,
    )
}

@Composable
private fun CategoryManager(
    state: CategoryScreenState,
    events: Flow<CategoryEvent>,
    onClickCreate: () -> Unit,
    onClickRename: (Category) -> Unit,
    onClickDelete: (Category) -> Unit,
    onClickDeleteSelected: () -> Unit,
    onToggleHidden: (Category) -> Unit,
    onChangeOrder: (Category, Int) -> Unit,
    onMoveToEnd: (Category, Int) -> Unit, // RK: the move menu, always to the whole table's ends
    onDismissDialog: () -> Unit,
    onSelectContentType: (ContentType) -> Unit,
    onCreate: (String, Long) -> Unit,
    onRename: (Category, String) -> Unit,
    onDelete: (Category) -> Unit,
    // RK --> multi-select + deferred bulk delete
    onToggleSelection: (Category) -> Unit,
    onToggleRangeSelection: (Category) -> Unit,
    onSelectAll: () -> Unit,
    onInvertSelection: () -> Unit,
    onClearSelection: () -> Unit,
    onDeleteSelected: () -> Unit,
    onUndoDelete: (batch: Long) -> Unit,
    onCommitDelete: (batch: Long) -> Unit,
    // RK <--
) {
    val context = LocalContext.current
    val navigator = LocalNavigator.current
    val snackbarHostState = remember { SnackbarHostState() }

    if (state is CategoryScreenState.Loading) {
        LoadingScreen()
        return
    }
    val successState = state as CategoryScreenState.Success

    CategoryScreen(
        state = successState,
        onClickCreate = onClickCreate,
        onClickRename = onClickRename,
        onClickDelete = onClickDelete,
        onClickToggleHidden = onToggleHidden,
        onChangeOrder = onChangeOrder,
        onMoveToEnd = onMoveToEnd, // RK
        navigateUp = navigator::pop,
        // RK -->
        onSelectContentType = onSelectContentType,
        snackbarHostState = snackbarHostState,
        onToggleSelection = onToggleSelection,
        onToggleRangeSelection = onToggleRangeSelection,
        onSelectAll = onSelectAll,
        onInvertSelection = onInvertSelection,
        onClearSelection = onClearSelection,
        onDeleteSelected = onClickDeleteSelected, // the action-mode toolbar's Delete asks first
        // RK <--
    )

    when (val dialog = successState.dialog) {
        null -> {}
        CategoryDialog.Create -> CategoryCreateDialog(
            onDismissRequest = onDismissDialog,
            onCreate = onCreate,
            // RK: every name, so the chip's narrowing cannot hide a clash; the new category starts on
            // the library the chip is showing.
            categories = successState.allNames,
            initialContentType = successState.contentType.toCategoryContentType(),
        )
        is CategoryDialog.Rename -> CategoryRenameDialog(
            onDismissRequest = onDismissDialog,
            onRename = { onRename(dialog.category, it) },
            categories = successState.allNames,
            category = dialog.category.name,
        )
        is CategoryDialog.Delete -> CategoryDeleteDialog(
            onDismissRequest = onDismissDialog,
            onDelete = { onDelete(dialog.category) },
            category = dialog.category.name,
        )
        // RK: bulk-delete confirmation -> deferred delete + undo snackbar
        CategoryDialog.DeleteSelected -> CategoryDeleteSelectedDialog(
            count = successState.selection.size,
            onDismissRequest = onDismissDialog,
            onDelete = onDeleteSelected,
        )
    }

    // RK: an armed delete: commit on dismiss, restore on undo. Keyed on the batch, so a recreated screen
    // offers the undo again, and one committed elsewhere (a drag, leaving) takes its snackbar down.
    val undoable = successState.undoableDeletes.firstOrNull()
    LaunchedEffect(undoable) {
        if (undoable == null) return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = context.pluralStringResource(MR.plurals.categories_deleted, undoable.count, undoable.count),
            actionLabel = context.stringResource(MR.strings.action_undo),
            duration = SnackbarDuration.Short,
            withDismissAction = true,
        )
        if (result == SnackbarResult.ActionPerformed) {
            onUndoDelete(undoable.batch)
        } else {
            onCommitDelete(undoable.batch)
        }
    }

    LaunchedEffect(events) {
        events.collect { event ->
            when (event) {
                is CategoryEvent.LocalizedMessage -> context.toast(event.stringRes)
            }
        }
    }
}
