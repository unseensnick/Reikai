package eu.kanade.tachiyomi.ui.category

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.util.fastMap
import cafe.adriel.voyager.core.model.rememberScreenModel
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
import tachiyomi.core.common.i18n.pluralStringResource
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.category.model.Category
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.screens.LoadingScreen

// RK: one list spanning both libraries; each row carries the content type it applies to.
class CategoryScreen : Screen() {

    @Composable
    override fun Content() {
        val screenModel = rememberScreenModel { CategoryScreenModel() }
        val state by screenModel.state.collectAsState()
        CategoryManager(
            state = state,
            events = screenModel.events,
            onClickCreate = { screenModel.showDialog(CategoryDialog.Create) },
            onClickRename = { screenModel.showDialog(CategoryDialog.Rename(it)) },
            onClickDelete = { screenModel.showDialog(CategoryDialog.Delete(it)) },
            onToggleHidden = screenModel::toggleHidden,
            onChangeOrder = screenModel::changeOrder,
            onDismissDialog = screenModel::dismissDialog,
            onCreate = screenModel::createCategory,
            onRename = screenModel::renameCategory,
            onDelete = screenModel::deleteCategory,
            onToggleSelection = { screenModel.toggleSelection(it.id) },
            onSelectAll = screenModel::selectAll,
            onInvertSelection = screenModel::invertSelection,
            onClearSelection = screenModel::clearSelection,
            onDeleteSelected = screenModel::deleteSelected,
            onUndoDelete = screenModel::undoPendingDelete,
            onCommitDelete = screenModel::commitPendingDelete,
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
            categories = successState.categories.fastMap { it.name },
        )
        is CategoryDialog.Rename -> CategoryRenameDialog(
            onDismissRequest = onDismissDialog,
            onRename = { onRename(dialog.category, it) },
            categories = successState.categories.fastMap { it.name },
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
