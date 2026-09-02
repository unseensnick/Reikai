package reikai.presentation.browse

import androidx.compose.runtime.Composable
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import reikai.presentation.novel.browse.NovelBulkFavoriteViewModel
import reikai.presentation.novel.browse.SelectedNovel
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource

// What a batch add owes on any surface that lists both content types: how the selection is named, and
// how the categories are asked for. Shared so the two surfaces cannot answer either one differently.

/** "3 Manga, 1 Novel" while the selection holds both, otherwise the plain count the bar shows. */
@Composable
fun selectionTitle(mangaCount: Int, novelCount: Int): String? =
    if (mangaCount > 0 && novelCount > 0) {
        stringResource(
            MR.strings.bulk_selected_types,
            pluralStringResource(MR.plurals.bulk_selected_manga, mangaCount, mangaCount),
            pluralStringResource(MR.plurals.bulk_selected_novels, novelCount, novelCount),
        )
    } else {
        null
    }

/**
 * The batch category prompts. Each content type files into its own categories, so a mixed batch is
 * asked once per type rather than offered a merged list where half the choices would not apply.
 */
@Composable
fun BulkCategoryDialogs(
    mangaBulk: BulkFavoriteViewModel,
    novelBulk: NovelBulkFavoriteViewModel,
    mangaDialog: EntryBulkFavoriteViewModel.Dialog<Manga>?,
    novelDialog: EntryBulkFavoriteViewModel.Dialog<SelectedNovel>?,
    /** Whether the batch spans both types, so each prompt says which one it is filing. */
    namePrompts: Boolean,
) {
    val navigator = LocalNavigator.currentOrThrow
    when {
        mangaDialog is EntryBulkFavoriteViewModel.Dialog.ChangeCategory -> ChangeCategoryDialog(
            initialSelection = mangaDialog.initialSelection,
            onDismissRequest = { mangaBulk.setDialog(null) },
            onEditCategories = { navigator.push(CategoryScreen()) },
            onConfirm = { include, _ -> mangaBulk.setCategories(mangaDialog.items, include) },
            title = stringResource(MR.strings.categories_for_type, stringResource(MR.strings.content_type_manga))
                .takeIf { namePrompts },
        )
        novelDialog is EntryBulkFavoriteViewModel.Dialog.ChangeCategory -> ChangeCategoryDialog(
            initialSelection = novelDialog.initialSelection,
            onDismissRequest = { novelBulk.setDialog(null) },
            onEditCategories = { navigator.push(CategoryScreen()) },
            onConfirm = { include, _ -> novelBulk.setCategories(novelDialog.items, include) },
            title = stringResource(MR.strings.categories_for_type, stringResource(MR.strings.content_type_novels))
                .takeIf { namePrompts },
        )
    }
}
