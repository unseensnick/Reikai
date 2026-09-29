package reikai.presentation.browse

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import reikai.presentation.browse.components.EntryDuplicateDialog
import reikai.presentation.browse.components.EntryRemoveDialog
import reikai.presentation.migrate.flow.EntryMigrateFor

/**
 * The questions a long press on a browse result asks, drawn the same way for both content types and
 * answered through [flow]. Dialogs a surface owns alone (a filter sheet, a bulk category choice) stay
 * with that surface.
 */
@Composable
fun Screen.EntryAddDialogs(flow: EntryAddFlow<*>) {
    val navigator = LocalNavigator.currentOrThrow
    val dialog by flow.dialog.collectAsState()
    when (val current = dialog) {
        null -> Unit
        is EntryAddDialog.Remove -> EntryRemoveDialog(
            title = current.title,
            onDismissRequest = flow::dismiss,
            onConfirm = flow::confirmRemove,
        )
        is EntryAddDialog.ChangeCategory -> ChangeCategoryDialog(
            initialSelection = current.initialSelection,
            onDismissRequest = flow::dismiss,
            onEditCategories = { navigator.push(CategoryScreen()) },
            onConfirm = { include, _ -> flow.confirmCategories(include) },
        )
        is EntryAddDialog.AddDuplicate -> EntryDuplicateDialog(
            duplicates = current.duplicates,
            toUi = { it },
            onDismissRequest = flow::dismiss,
            onConfirm = flow::confirmAddDuplicate,
            onOpen = { card -> flow.duplicateScreen(card.id)?.let(navigator::push) },
            onMigrate = { flow.startMigrate(it.id) },
            groupIdByEntryId = current.groupIdByEntryId,
            onAddToGroup = flow::addToGroup.takeIf { current.suggestGroup },
        )
        is EntryAddDialog.Migrate -> EntryMigrateFor(
            contentType = flow.contentType,
            currentId = current.currentId,
            targetId = current.targetId,
            onDismissRequest = flow::dismiss,
        )
    }
}
