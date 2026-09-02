package reikai.presentation.browse

import androidx.compose.runtime.Composable
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import reikai.domain.library.ContentType
import reikai.presentation.browse.catalogue.EntryBrowseDialog
import reikai.presentation.browse.components.EntryDuplicateDialog
import reikai.presentation.browse.components.EntryRemoveDialog
import reikai.presentation.migrate.flow.EntryMigrateFor

/**
 * What a long press on a browse result can ask: take it out of the library, choose its categories,
 * confirm it against duplicates, or migrate onto one. Shared so a surface listing entries renders the
 * same four questions rather than writing its own; which question to ask is the caller's, decided by
 * `decideAdd` and answered through its own adapters.
 *
 * Dialogs a surface owns alone (a filter sheet, a bulk category choice) stay with that surface.
 */
@Composable
fun Screen.EntryAddDialogs(
    dialog: EntryBrowseDialog?,
    contentType: ContentType,
    onDismissRequest: () -> Unit,
    onConfirmRemove: () -> Unit,
    onConfirmCategories: (List<Long>) -> Unit,
    onConfirmAddDuplicate: () -> Unit,
    onAddToGroup: (List<Long>) -> Unit,
    onStartMigrate: (Long) -> Unit,
    onOpenEntryById: (Long) -> Unit,
) {
    val navigator = LocalNavigator.currentOrThrow
    when (dialog) {
        null, EntryBrowseDialog.Filter, is EntryBrowseDialog.SelectionCategories -> Unit
        is EntryBrowseDialog.Remove -> EntryRemoveDialog(
            title = dialog.title,
            onDismissRequest = onDismissRequest,
            onConfirm = onConfirmRemove,
        )
        is EntryBrowseDialog.ChangeCategory -> ChangeCategoryDialog(
            initialSelection = dialog.initialSelection,
            onDismissRequest = onDismissRequest,
            onEditCategories = { navigator.push(CategoryScreen()) },
            onConfirm = { include, _ -> onConfirmCategories(include) },
        )
        is EntryBrowseDialog.AddDuplicate -> EntryDuplicateDialog(
            duplicates = dialog.duplicates,
            toUi = { it },
            onDismissRequest = onDismissRequest,
            onConfirm = onConfirmAddDuplicate,
            onOpen = { onOpenEntryById(it.id) },
            onMigrate = { onStartMigrate(it.id) },
            groupIdByEntryId = dialog.groupIdByEntryId,
            onAddToGroup = onAddToGroup.takeIf { dialog.suggestGroup },
        )
        is EntryBrowseDialog.Migrate -> EntryMigrateFor(
            contentType = contentType,
            currentId = dialog.currentId,
            targetId = dialog.targetId,
            onDismissRequest = onDismissRequest,
        )
    }
}
