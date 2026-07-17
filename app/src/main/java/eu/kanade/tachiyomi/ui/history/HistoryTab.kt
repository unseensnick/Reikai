package eu.kanade.tachiyomi.ui.history

import android.content.Context
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.presentation.history.HistoryUiModel
import eu.kanade.presentation.history.components.HistoryDeleteAllDialog
import eu.kanade.presentation.history.components.HistoryDeleteDialog
import eu.kanade.presentation.manga.DuplicateMangaDialog
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import mihon.feature.migration.dialog.MigrateMangaDialog
import reikai.presentation.components.ContentTypeFilterChips
import reikai.presentation.history.NovelHistoryScreenModel
import reikai.presentation.history.ReikaiHistoryScreen
import reikai.presentation.novel.browse.DuplicateNovelDialog
import reikai.presentation.novel.details.NovelCategoryDialog
import reikai.presentation.novel.details.NovelDetailsDialog
import reikai.presentation.novel.details.NovelScreen
import reikai.presentation.novel.reader.NovelReaderScreen
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

data object HistoryTab : Tab {

    private val snackbarHostState = SnackbarHostState()

    private val resumeLastChapterReadEvent = Channel<Unit>()

    override val options: TabOptions
        @Composable
        get() {
            val isSelected = LocalTabNavigator.current.current.key == key
            val image = AnimatedImageVector.animatedVectorResource(R.drawable.anim_history_enter)
            return TabOptions(
                index = 2u,
                title = stringResource(MR.strings.label_recent_manga),
                icon = rememberAnimatedVectorPainter(image, isSelected),
            )
        }

    override suspend fun onReselect(navigator: Navigator) {
        resumeLastChapterReadEvent.send(Unit)
    }

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val screenModel = rememberScreenModel { HistoryScreenModel() }
        val state by screenModel.state.collectAsState()
        // RK -->
        val novelScreenModel = rememberScreenModel { NovelHistoryScreenModel() }
        val novelState by novelScreenModel.state.collectAsState()
        val contentType by novelScreenModel.contentType.collectAsState()
        val chip: @Composable () -> Unit = {
            ContentTypeFilterChips(selected = contentType, onSelect = novelScreenModel::setContentType)
        }

        // All three chips render through one consolidated Reikai screen; manga stays Mihon's untouched
        // HistoryScreenModel (passed in), so its behavior is unchanged.
        ReikaiHistoryScreen(
            contentType = contentType,
            mangaModel = screenModel,
            novelModel = novelScreenModel,
            snackbarHostState = snackbarHostState,
            chip = chip,
            onClickMangaCover = { navigator.push(MangaScreen(it)) },
            onClickMangaResume = screenModel::getNextChapterForManga,
            onClickMangaFavorite = screenModel::addFavorite,
            onClickNovelCover = novelScreenModel::openDetails,
            onClickNovelResume = novelScreenModel::resume,
            onClickNovelFavorite = novelScreenModel::addFavorite,
        )
        // RK <--

        val onDismissRequest = { screenModel.setDialog(null) }
        when (val dialog = state.dialog) {
            is HistoryScreenModel.Dialog.Delete -> {
                HistoryDeleteDialog(
                    onDismissRequest = onDismissRequest,
                    onDelete = { all ->
                        if (all) {
                            screenModel.removeAllFromHistory(dialog.history.mangaId)
                        } else {
                            screenModel.removeFromHistory(dialog.history)
                        }
                    },
                )
            }
            is HistoryScreenModel.Dialog.DeleteAll -> {
                HistoryDeleteAllDialog(
                    onDismissRequest = onDismissRequest,
                    onDelete = screenModel::removeAllHistory,
                )
            }
            is HistoryScreenModel.Dialog.DuplicateManga -> {
                DuplicateMangaDialog(
                    duplicates = dialog.duplicates,
                    onDismissRequest = onDismissRequest,
                    onConfirm = { screenModel.addFavorite(dialog.manga) },
                    onOpenManga = { navigator.push(MangaScreen(it.id)) },
                    onMigrate = { screenModel.showMigrateDialog(dialog.manga, it) },
                )
            }
            is HistoryScreenModel.Dialog.ChangeCategory -> {
                ChangeCategoryDialog(
                    initialSelection = dialog.initialSelection,
                    onDismissRequest = onDismissRequest,
                    onEditCategories = { navigator.push(CategoryScreen()) },
                    onConfirm = { include, _ ->
                        screenModel.moveMangaToCategoriesAndAddToLibrary(dialog.manga, include)
                    },
                )
            }
            is HistoryScreenModel.Dialog.Migrate -> {
                MigrateMangaDialog(
                    current = dialog.current,
                    target = dialog.target,
                    // Initiated from the context of [dialog.target] so we show [dialog.current].
                    onClickTitle = { navigator.push(MangaScreen(dialog.current.id)) },
                    onDismissRequest = onDismissRequest,
                )
            }
            null -> {}
        }

        // RK --> novel history dialogs (delete one / delete all from novel / clear all)
        val onDismissNovelDialog = { novelScreenModel.setDialog(null) }
        when (val dialog = novelState.dialog) {
            is NovelHistoryScreenModel.Dialog.Delete -> {
                HistoryDeleteDialog(
                    onDismissRequest = onDismissNovelDialog,
                    onDelete = { all ->
                        if (all) {
                            novelScreenModel.removeAllFromHistory(dialog.history.novelId)
                        } else {
                            novelScreenModel.removeFromHistory(dialog.history)
                        }
                    },
                )
            }
            is NovelHistoryScreenModel.Dialog.DeleteAll -> {
                HistoryDeleteAllDialog(
                    onDismissRequest = onDismissNovelDialog,
                    onDelete = novelScreenModel::removeAllHistory,
                )
            }
            is NovelHistoryScreenModel.Dialog.DuplicateNovel -> {
                DuplicateNovelDialog(
                    duplicates = dialog.duplicates,
                    sourceNames = dialog.sourceNames,
                    sourceSites = dialog.sourceSites,
                    onDismissRequest = onDismissNovelDialog,
                    onConfirm = { novelScreenModel.addFavoriteAnyway(dialog.novelId) },
                    onOpenNovel = { navigator.push(NovelScreen(it.source, it.url)) },
                )
            }
            is NovelHistoryScreenModel.Dialog.ChangeCategory -> {
                NovelCategoryDialog(
                    dialog = NovelDetailsDialog.ChangeCategory(dialog.categories, dialog.currentIds),
                    onDismiss = onDismissNovelDialog,
                    onConfirm = { ids -> novelScreenModel.applyCategories(dialog.novelId, ids) },
                )
            }
            null -> {}
        }
        // RK <--

        LaunchedEffect(state.list) {
            if (state.list != null) {
                (context as? MainActivity)?.ready = true
            }
        }

        LaunchedEffect(Unit) {
            screenModel.events.collectLatest { e ->
                when (e) {
                    HistoryScreenModel.Event.InternalError ->
                        snackbarHostState.showSnackbar(context.stringResource(MR.strings.internal_error))
                    HistoryScreenModel.Event.HistoryCleared ->
                        snackbarHostState.showSnackbar(context.stringResource(MR.strings.clear_history_completed))
                    is HistoryScreenModel.Event.OpenChapter -> openChapter(context, e.chapter)
                }
            }
        }

        // RK --> novel history events
        LaunchedEffect(Unit) {
            novelScreenModel.events.collectLatest { e ->
                when (e) {
                    NovelHistoryScreenModel.Event.InternalError ->
                        snackbarHostState.showSnackbar(context.stringResource(MR.strings.internal_error))
                    NovelHistoryScreenModel.Event.HistoryCleared ->
                        snackbarHostState.showSnackbar(context.stringResource(MR.strings.clear_history_completed))
                    is NovelHistoryScreenModel.Event.OpenNovel ->
                        navigator.push(NovelScreen(e.source, e.url))
                    is NovelHistoryScreenModel.Event.OpenChapter ->
                        if (e.chapterId != null) {
                            // RK: group scope (default) so a merged novel's prev/next spans every source
                            // instead of degrading to the one source of the history entry.
                            navigator.push(NovelReaderScreen(e.novelId, e.chapterId))
                        } else {
                            snackbarHostState.showSnackbar(context.stringResource(MR.strings.no_next_chapter))
                        }
                }
            }
        }
        // RK <--

        LaunchedEffect(Unit) {
            resumeLastChapterReadEvent.receiveAsFlow().collectLatest {
                // RK: resume the globally-latest read across manga + novel (both feeds are readAt-desc,
                // so each list's first item is its latest). Whichever is newer wins.
                val mangaLatest = state.list?.firstNotNullOfOrNull { (it as? HistoryUiModel.Item)?.item }
                val novelLatest = novelScreenModel.getLast()
                val mangaAt = mangaLatest?.readAt?.time ?: Long.MIN_VALUE
                val novelAt = novelLatest?.readAt ?: Long.MIN_VALUE
                when {
                    novelLatest != null && novelAt >= mangaAt -> novelScreenModel.resume(novelLatest)
                    mangaLatest != null -> screenModel.getNextChapterForManga(
                        mangaLatest.mangaId,
                        mangaLatest.chapterId,
                    )
                    else -> openChapter(context, null)
                }
            }
        }
    }

    private suspend fun openChapter(context: Context, chapter: Chapter?) {
        if (chapter != null) {
            val intent = ReaderActivity.newIntent(context, chapter.mangaId, chapter.id)
            context.startActivity(intent)
        } else {
            snackbarHostState.showSnackbar(context.stringResource(MR.strings.no_next_chapter))
        }
    }
}
