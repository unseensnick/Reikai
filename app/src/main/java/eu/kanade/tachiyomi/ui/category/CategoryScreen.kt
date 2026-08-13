package eu.kanade.tachiyomi.ui.category

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.util.fastMap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.category.CategoryScreen
import eu.kanade.presentation.category.components.CategoryCreateDialog
import eu.kanade.presentation.category.components.CategoryDeleteDialog
import eu.kanade.presentation.category.components.CategoryDeleteSelectedDialog
import eu.kanade.presentation.category.components.CategoryRenameDialog
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import reikai.domain.category.toCategoryContentType
import reikai.domain.library.ContentType
import tachiyomi.core.common.i18n.pluralStringResource
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.category.model.Category
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.screens.LoadingScreen

// RK: one list spanning both libraries; each row carries the content type it applies to.
class CategoryScreen : Screen() {

    @Composable
    override fun Content() {
        val viewModel = viewModel<CategoryViewModel>()
        val state by viewModel.state.collectAsStateWithLifecycle()
        CategoryManager(
            state = state,
            events = viewModel.events,
            onClickCreate = { viewModel.showDialog(CategoryDialog.Create) },
            onClickRename = { viewModel.showDialog(CategoryDialog.Rename(it)) },
            onClickDelete = { viewModel.showDialog(CategoryDialog.Delete(it)) },
            onToggleHidden = viewModel::toggleHidden,
            onChangeOrder = viewModel::changeOrder,
            onDismissDialog = viewModel::dismissDialog,
            onCreate = viewModel::createCategory,
            onRename = viewModel::renameCategory,
            onDelete = viewModel::deleteCategory,
            onSelectContentType = viewModel::setContentType,
            onToggleSelection = { viewModel.toggleSelection(it.id) },
            onSelectAll = viewModel::selectAll,
            onInvertSelection = viewModel::invertSelection,
            onClearSelection = viewModel::clearSelection,
            onDeleteSelected = viewModel::deleteSelected,
            onUndoDelete = viewModel::undoPendingDelete,
            onCommitDelete = viewModel::commitPendingDelete,
        )
    }
}

@Composable
private fun CategoryManager(
    state: CategoryScreenState,
    events: Flow<CategoryEvent>,
    onClickCreate: () -> Unit,
    onClickRename: (Category) -> Unit,
    onClickDelete: (Category) -> Unit,
    onToggleHidden: (Category) -> Unit,
    onChangeOrder: (Category, Int) -> Unit,
    onDismissDialog: () -> Unit,
    onSelectContentType: (ContentType) -> Unit,
    onCreate: (String, Long) -> Unit,
    onRename: (Category, String) -> Unit,
    onDelete: (Category) -> Unit,
    // RK --> multi-select + deferred bulk delete
    onToggleSelection: (Category) -> Unit,
    onSelectAll: () -> Unit,
    onInvertSelection: () -> Unit,
    onClearSelection: () -> Unit,
    onDeleteSelected: () -> Unit,
    onUndoDelete: () -> Unit,
    onCommitDelete: () -> Unit,
    // RK <--
) {
    val context = LocalContext.current
    val navigator = LocalNavigator.currentOrThrow
    // RK: the action-mode toolbar's Delete asks first; this gates the deferred delete
    val snackbarHostState = remember { SnackbarHostState() }
    var showDeleteSelectedConfirm by remember { mutableStateOf(false) }

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
        navigateUp = navigator::pop,
        // RK -->
        onSelectContentType = onSelectContentType,
        snackbarHostState = snackbarHostState,
        onToggleSelection = onToggleSelection,
        onSelectAll = onSelectAll,
        onInvertSelection = onInvertSelection,
        onClearSelection = onClearSelection,
        onDeleteSelected = { showDeleteSelectedConfirm = true },
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
    }

    // RK: bulk-delete confirmation -> deferred delete + undo snackbar
    if (showDeleteSelectedConfirm) {
        CategoryDeleteSelectedDialog(
            count = successState.selection.size,
            onDismissRequest = { showDeleteSelectedConfirm = false },
            onDelete = onDeleteSelected,
        )
    }

    // RK: leaving the surface commits any still-pending delete,
    // so it isn't silently dropped when the undo snackbar's coroutine is cancelled.
    DisposableEffect(Unit) {
        onDispose { onCommitDelete() }
    }

    LaunchedEffect(events) {
        events.collect { event ->
            when (event) {
                is CategoryEvent.LocalizedMessage -> context.toast(event.stringRes)
                // RK: a bulk delete was armed; commit on dismiss, restore on undo
                is CategoryEvent.ShowUndoSnackbar -> {
                    val result = snackbarHostState.showSnackbar(
                        message = context.pluralStringResource(MR.plurals.categories_deleted, event.count, event.count),
                        actionLabel = context.stringResource(MR.strings.action_undo),
                        duration = SnackbarDuration.Short,
                        withDismissAction = true,
                    )
                    if (result == SnackbarResult.ActionPerformed) onUndoDelete() else onCommitDelete()
                }
            }
        }
    }
}
