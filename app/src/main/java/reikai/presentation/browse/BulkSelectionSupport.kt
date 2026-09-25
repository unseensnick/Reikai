package reikai.presentation.browse

import androidx.compose.runtime.Composable
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import reikai.domain.source.SourceKey
import reikai.novel.host.NovelItem
import reikai.presentation.browse.globalsearch.BrowseSearchRow
import reikai.presentation.browse.globalsearch.EntrySearchState
import reikai.presentation.novel.browse.NovelBulkFavoriteViewModel
import reikai.presentation.novel.browse.SelectedNovel
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource

// What a batch add owes on any surface that lists both content types: what select-all acts on, how
// the selection is named, and how the categories are asked for. Shared so the two surfaces cannot
// answer any of them differently.

/**
 * Every result [this] lists, split back into the two halves each bulk model owns, for select-all and
 * invert. Unwrapped with filterIsInstance rather than a cast: the entries are typed Any, so a wrong
 * cast would compile and only fail once a source returned rows.
 */
fun List<BrowseSearchRow>.listedEntries(): Pair<List<Manga>, List<SelectedNovel>> {
    val manga = mutableListOf<Manga>()
    val novels = mutableListOf<SelectedNovel>()
    forEach { row ->
        val results = (row.state as? EntrySearchState.Success)?.entries.orEmpty()
        when (val key = row.key) {
            is SourceKey.Manga -> manga += results.filterIsInstance<Manga>()
            is SourceKey.Novel -> novels += results.filterIsInstance<NovelItem>().map { SelectedNovel(key.id, it) }
        }
    }
    return manga to novels
}

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
    when {
        mangaDialog != null -> BulkCategoryDialog(
            bulk = mangaBulk,
            dialog = mangaDialog,
            title = stringResource(MR.strings.categories_for_type, stringResource(MR.strings.content_type_manga))
                .takeIf { namePrompts },
        )
        novelDialog != null -> BulkCategoryDialog(
            bulk = novelBulk,
            dialog = novelDialog,
            title = stringResource(MR.strings.categories_for_type, stringResource(MR.strings.content_type_novels))
                .takeIf { namePrompts },
        )
    }
}

/** One content type's batch category prompt, for a surface that lists that type alone or both. */
@Composable
fun <T : Any> BulkCategoryDialog(
    bulk: EntryBulkFavoriteViewModel<T>,
    dialog: EntryBulkFavoriteViewModel.Dialog<T>,
    title: String? = null,
) {
    val navigator = LocalNavigator.currentOrThrow
    when (dialog) {
        is EntryBulkFavoriteViewModel.Dialog.ChangeCategory -> ChangeCategoryDialog(
            initialSelection = dialog.initialSelection,
            onDismissRequest = { bulk.setDialog(null) },
            onEditCategories = { navigator.push(CategoryScreen()) },
            onConfirm = { include, _ -> bulk.setCategories(dialog.items, include) },
            title = title,
        )
    }
}
