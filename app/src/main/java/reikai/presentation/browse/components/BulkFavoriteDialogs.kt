package reikai.presentation.browse.components

import androidx.compose.runtime.Composable
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import reikai.presentation.browse.BulkFavoriteViewModel
import reikai.presentation.browse.EntryBulkFavoriteViewModel
import tachiyomi.domain.manga.model.Manga

/**
 * Renders the dialogs owned by [BulkFavoriteViewModel]. Shared by every manga browse surface that
 * hosts bulk selection (per-source Browse, global search, the MangaDex follows screen).
 */
@Composable
fun BulkFavoriteDialogs(
    bulkFavoriteViewModel: BulkFavoriteViewModel,
    dialog: EntryBulkFavoriteViewModel.Dialog<Manga>?,
) {
    val navigator = LocalNavigator.currentOrThrow
    when (dialog) {
        is EntryBulkFavoriteViewModel.Dialog.ChangeCategory -> {
            ChangeCategoryDialog(
                initialSelection = dialog.initialSelection,
                onDismissRequest = { bulkFavoriteViewModel.setDialog(null) },
                onEditCategories = { navigator.push(CategoryScreen()) },
                onConfirm = { include, _ ->
                    bulkFavoriteViewModel.setCategories(dialog.items, include)
                },
            )
        }
        null -> {}
    }
}
