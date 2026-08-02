package reikai.presentation.browse.components

import androidx.compose.runtime.Composable
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import reikai.presentation.browse.EntryBulkFavoriteScreenModel

/**
 * Renders the dialogs owned by [EntryBulkFavoriteScreenModel], shared by every browse surface that
 * hosts bulk selection on either content type (per-source Browse, global search, the MangaDex
 * follows screen, and the novel twins of the first two). Generic over the facade's selection item,
 * so a dialog change here reaches manga and novels alike.
 */
@Composable
fun <T : Any> BulkFavoriteDialogs(
    bulkFavoriteScreenModel: EntryBulkFavoriteScreenModel<T>,
    dialog: EntryBulkFavoriteScreenModel.Dialog<T>?,
) {
    val navigator = LocalNavigator.currentOrThrow
    when (dialog) {
        is EntryBulkFavoriteScreenModel.Dialog.ChangeCategory -> {
            ChangeCategoryDialog(
                initialSelection = dialog.initialSelection,
                onDismissRequest = { bulkFavoriteScreenModel.setDialog(null) },
                onEditCategories = { navigator.push(CategoryScreen()) },
                onConfirm = { include, _ ->
                    bulkFavoriteScreenModel.setCategories(dialog.items, include)
                },
            )
        }
        null -> {}
    }
}
