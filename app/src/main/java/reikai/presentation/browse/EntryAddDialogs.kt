package reikai.presentation.browse

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.tachiyomi.util.system.toast
import mihon.core.navigation.CategoryRoute
import mihon.core.navigation.LocalNavigator
import reikai.presentation.browse.components.EntryDuplicateDialog
import reikai.presentation.browse.components.EntryRemoveDialog
import reikai.presentation.migrate.flow.EntryMigrateFor
import tachiyomi.i18n.MR

/**
 * The questions a long press on a browse result asks, drawn the same way for both content types and
 * answered through [flow]. Dialogs a surface owns alone (a filter sheet, a bulk category choice) stay
 * with that surface.
 */
@Composable
fun EntryAddDialogs(flow: EntryAddFlow<*>) {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    LaunchedEffect(flow) {
        flow.failures.collect { context.toast(MR.strings.add_to_library_failed) }
    }
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
            onEditCategories = { navigator.push(CategoryRoute) },
            onConfirm = { include, _ -> flow.confirmCategories(include) },
        )
        is EntryAddDialog.AddDuplicate -> EntryDuplicateDialog(
            duplicates = current.duplicates,
            toUi = { it },
            onDismissRequest = flow::dismiss,
            onConfirm = flow::confirmAddDuplicate,
            onOpen = { card -> navigator.push(flow.duplicateScreen(card.id)) },
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
