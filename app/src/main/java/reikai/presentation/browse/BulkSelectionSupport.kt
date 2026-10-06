package reikai.presentation.browse

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.icerock.moko.resources.StringResource
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import reikai.domain.source.SourceKey
import reikai.presentation.browse.catalogue.EntryBrowseRow
import reikai.presentation.browse.globalsearch.BrowseSearchRow
import reikai.presentation.browse.globalsearch.EntrySearchState
import reikai.presentation.novel.browse.NovelBulkFavoriteViewModel
import reikai.presentation.novel.browse.SelectedNovel
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Every result [this] lists, split back into the two halves each bulk model owns, for select-all and
 * invert. Each result is unwrapped under its own row's source key, the only thing that says which
 * type a payload is.
 */
fun List<BrowseSearchRow>.listedEntries(): Pair<List<Manga>, List<SelectedNovel>> {
    val manga = mutableListOf<Manga>()
    val novels = mutableListOf<SelectedNovel>()
    forEach { row ->
        val results = (row.state as? EntrySearchState.Success)?.entries.orEmpty()
        when (val key = row.key) {
            is SourceKey.Manga -> manga += results.map { it.manga }
            is SourceKey.Novel -> novels += results.map { SelectedNovel(key.id, it.item) }
        }
    }
    return manga to novels
}

/**
 * The one selection global search and the feed hold over both content types, kept as each type's own
 * bulk model so the add verbs stay per-type. It answers what a pick, select-all and invert reach, how
 * the selection is named and how the categories are asked for, so the two surfaces cannot differ.
 */
@Stable
class MixedBulkSelection internal constructor(
    private val manga: BulkFavoriteViewModel,
    private val novels: NovelBulkFavoriteViewModel,
    private val mangaState: State<EntryBulkFavoriteViewModel.State<Manga>>,
    private val novelState: State<EntryBulkFavoriteViewModel.State<SelectedNovel>>,
) {
    val selectionMode: Boolean
        get() = mangaState.value.selectionMode || novelState.value.selectionMode

    val count: Int
        get() = mangaState.value.selection.size + novelState.value.selection.size

    /** The picks as the result rows key them. Derived, so a source landing hands every row the same
     *  set rather than a new one that redraws it. */
    val selectedKeys: Set<String> by derivedStateOf {
        mangaState.value.selection.mapTo(mutableSetOf(), ::mangaRowKey) +
            novelState.value.selection.map { novelRowKey(it.sourceId, it.item.path) }
    }

    /**
     * Whether each category prompt says which type it files. Decided when the batch is dispatched:
     * the first prompt resolving empties its own selection, and re-reading that would leave the
     * second one unlabelled.
     */
    var namePrompts: Boolean by mutableStateOf(false)
        private set

    fun start() = manga.toggleSelectionMode(true)

    fun clear() {
        manga.toggleSelectionMode(false)
        novels.toggleSelectionMode(false)
    }

    /** Picks or unpicks [row] of the source at [sourceKey], in its own type's selection. */
    fun toggle(row: EntryBrowseRow, sourceKey: SourceKey) = when (sourceKey) {
        is SourceKey.Manga -> manga.toggleSelection(row.manga)
        is SourceKey.Novel -> novels.toggleSelection(SelectedNovel(sourceKey.id, row.item))
    }

    fun selectAll(rows: List<BrowseSearchRow>) {
        val (listedManga, listedNovels) = rows.listedEntries()
        listedManga.forEach { manga.select(it) }
        listedNovels.forEach { novels.select(it) }
    }

    fun invert(rows: List<BrowseSearchRow>) {
        val (listedManga, listedNovels) = rows.listedEntries()
        manga.reverseSelection(listedManga)
        novels.reverseSelection(listedNovels)
    }

    fun add() {
        namePrompts = mangaState.value.selection.isNotEmpty() && novelState.value.selection.isNotEmpty()
        manga.addFavorite()
        novels.addFavorite()
    }

    /** "3 Manga, 1 Novel" while the selection holds both, otherwise null for the bar's plain count. */
    @Composable
    fun title(): String? {
        val mangaCount = mangaState.value.selection.size
        val novelCount = novelState.value.selection.size
        if (mangaCount == 0 || novelCount == 0) return null
        return stringResource(
            MR.strings.bulk_selected_types,
            pluralStringResource(MR.plurals.bulk_selected_manga, mangaCount, mangaCount),
            pluralStringResource(MR.plurals.bulk_selected_novels, novelCount, novelCount),
        )
    }

    /**
     * The batch category prompts. Each type files into its own categories, so a mixed batch is asked
     * once per type, one at a time: resolving the manga prompt reveals the novel one.
     */
    @Composable
    fun Dialogs() {
        val mangaDialog = mangaState.value.dialog
        val novelDialog = novelState.value.dialog
        when {
            mangaDialog != null -> BulkCategoryDialog(manga, mangaDialog, promptTitle(MR.strings.content_type_manga))
            novelDialog != null -> BulkCategoryDialog(novels, novelDialog, promptTitle(MR.strings.content_type_novels))
        }
    }

    @Composable
    private fun promptTitle(type: StringResource): String? =
        stringResource(MR.strings.categories_for_type, stringResource(type)).takeIf { namePrompts }
}

/** The [MixedBulkSelection] over the bulk models of the screen this is called in. */
@Composable
fun rememberMixedBulkSelection(): MixedBulkSelection {
    val manga = metroViewModel<BulkFavoriteViewModel>()
    val novels = metroViewModel<NovelBulkFavoriteViewModel>()
    val mangaState = manga.state.collectAsStateWithLifecycle()
    val novelState = novels.state.collectAsStateWithLifecycle()
    return remember(manga, novels, mangaState, novelState) {
        MixedBulkSelection(manga, novels, mangaState, novelState)
    }
}

/** The batch category prompt [bulk] has raised, if any, for a surface that lists that type alone. */
@Composable
fun <T : Any> PendingBulkCategoryDialog(bulk: EntryBulkFavoriteViewModel<T>) {
    val dialog = bulk.state.collectAsState().value.dialog ?: return
    BulkCategoryDialog(bulk, dialog)
}

/**
 * One content type's batch category prompt. It confirms with the batch it was drawn with, since the
 * dialog dismisses (clearing the model's copy) before it confirms.
 */
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
