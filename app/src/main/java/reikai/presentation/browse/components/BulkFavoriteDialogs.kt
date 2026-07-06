package reikai.presentation.browse.components

import androidx.compose.runtime.Composable
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import reikai.presentation.browse.BulkFavoriteScreenModel

/**
 * Renders the dialogs owned by [BulkFavoriteScreenModel]. Shared by every browse surface that hosts
 * bulk selection (per-source Browse, global search, the MangaDex follows screen).
 */
@Composable
fun BulkFavoriteDialogs(
    bulkFavoriteScreenModel: BulkFavoriteScreenModel,
    dialog: BulkFavoriteScreenModel.Dialog?,
) {
    val navigator = LocalNavigator.currentOrThrow
    when (dialog) {
        is BulkFavoriteScreenModel.Dialog.ChangeCategory -> {
            ChangeCategoryDialog(
                initialSelection = dialog.initialSelection,
                onDismissRequest = { bulkFavoriteScreenModel.setDialog(null) },
                onEditCategories = { navigator.push(CategoryScreen()) },
                onConfirm = { include, _ ->
                    bulkFavoriteScreenModel.setMangasCategories(dialog.mangas, include)
                },
            )
        }
        null -> {}
    }
}
