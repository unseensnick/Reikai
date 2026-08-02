package reikai.presentation.browse.components

import androidx.compose.runtime.Composable
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import reikai.presentation.browse.BulkFavoriteScreenModel
import reikai.presentation.browse.EntryBulkFavoriteScreenModel
import tachiyomi.domain.manga.model.Manga

/**
 * Renders the dialogs owned by [BulkFavoriteScreenModel]. Shared by every manga browse surface that
 * hosts bulk selection (per-source Browse, global search, the MangaDex follows screen).
 */
@Composable
fun BulkFavoriteDialogs(
    bulkFavoriteScreenModel: BulkFavoriteScreenModel,
    dialog: EntryBulkFavoriteScreenModel.Dialog<Manga>?,
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
