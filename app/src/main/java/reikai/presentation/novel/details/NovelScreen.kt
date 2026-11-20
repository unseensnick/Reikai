package reikai.presentation.novel.details

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation3.runtime.NavKey
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.presentation.theme.TachiyomiTheme
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import mihon.app.di.appGraph
import mihon.core.navigation.CategoryRoute
import mihon.core.navigation.LocalAssistUrl
import mihon.core.navigation.LocalNavigator
import mihon.presentation.core.util.isTabletUi
import reikai.data.novel.expectedNextUpdate
import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.domain.novel.model.NovelWithChapterCount
import reikai.domain.novel.model.asNovelCover
import reikai.domain.novel.model.withCustomInfo
import reikai.domain.source.SourceKey
import reikai.novel.local.LocalNovelSource
import reikai.novel.source.NovelSettings
import reikai.presentation.browse.components.EntryDuplicateDialog
import reikai.presentation.browse.components.toDuplicateCard
import reikai.presentation.browse.source.preferencesRoute
import reikai.presentation.details.DETAILS_ADAPTER_KEY
import reikai.presentation.details.DETAILS_SETTINGS_KEY
import reikai.presentation.details.DetailsSettings
import reikai.presentation.details.DetailsViewedSource
import reikai.presentation.details.DuplicateMarking
import reikai.presentation.details.EntryDetailsContent
import reikai.presentation.details.EntryDetailsDialog
import reikai.presentation.details.EntryDetailsDialogHost
import reikai.presentation.details.EntryDetailsNavigation
import reikai.presentation.details.EntryDetailsNotLoaded
import reikai.presentation.details.EntryDetailsScreenState
import reikai.presentation.details.EntryEditInfoUi
import reikai.presentation.details.NovelEntryAdapter
import reikai.presentation.details.chapterToOpen
import reikai.presentation.details.detailsRoute
import reikai.presentation.details.detailsVolumeMoves
import reikai.presentation.details.editInfoDialog
import reikai.presentation.details.keptForScreen
import reikai.presentation.details.openDownloadFolder
import reikai.presentation.migrate.flow.EntryMigrateFor
import reikai.presentation.novel.browse.NovelSourceSettingsSheet
import reikai.presentation.novel.search.NovelChapterSearchRoute

/**
 * Light-novel details screen, the novel twin of `MangaScreen`, pinned by [EntryDetailsContent]. Builds a
 * [NovelEntryAdapter] over the novel model and delegates the whole body to the shared content, so a
 * details change is written once and reaches both content types. Only novel-specific navigation (the
 * reader, the page selector, chapter text search) and the per-type dialogs live here; the shared dialogs go through
 * [EntryDetailsDialogHost].
 */
@Serializable
data class NovelRoute(
    val novelId: Long,
    // Opened from a source listing: starts the synopsis expanded, and closes when incognito ends.
    val fromSource: Boolean = false,
) : NavKey

@Composable
fun NovelScreen(novelId: Long, fromSource: Boolean) {
    val navigator = LocalNavigator.current
    val setAssistUrl = LocalAssistUrl.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val viewModel = assistedMetroViewModel<NovelDetailsViewModel, NovelDetailsViewModel.Factory> {
        create(novelId = novelId, isFromSource = fromSource)
    }
    // Lifecycle-aware so collection pauses when the screen is not resumed (parity with MangaScreen).
    val state by viewModel.state.collectAsStateWithLifecycle()
    val coverViewModelFactory = remember { context.appGraph.novelCoverViewModelFactory }
    val adapter = remember(viewModel) {
        viewModel.keptForScreen(DETAILS_ADAPTER_KEY) {
            val graph = context.appGraph
            val duplicates = DuplicateMarking(
                ContentType.NOVELS,
                graph.chapterCopyMarks,
                graph.reikaiLibraryPreferences.showSkippedDuplicates,
                graph.novelPreferences.readerSkipDuplicateChapters(),
                viewModel.viewModelScope,
                graph.appCoroutineScope,
            )
            NovelEntryAdapter(viewModel, coverViewModelFactory, duplicates, graph.volumePicking)
        }
    }
    val settings = remember(viewModel) {
        viewModel.keptForScreen(DETAILS_SETTINGS_KEY) {
            context.appGraph.run { DetailsSettings(uiPreferences, reikaiLibraryPreferences, viewModel.viewModelScope) }
        }
    }
    val neutralState by adapter.state.collectAsStateWithLifecycle()

    // Decided by the neutral state alone, as on manga's page: it is mapped from the model's, so a loaded one means
    // the model's is Loaded too.
    val loaded = neutralState as? EntryDetailsScreenState.Loaded
    if (loaded == null) {
        EntryDetailsNotLoaded(neutralState, navigator::pop)
        return
    }
    val s = state as NovelDetailsState.Loaded
    TachiyomiTheme(
        seedColor = s.seedColor.takeIf {
            viewModel.themeCoverBased
        },
    ) {
        // The page WebView and Share open, so a merged novel offers the selected chip's.
        LaunchedEffect(s.webPage) { setAssistUrl(s.webPage?.url) }

        EntryDetailsContent(
            behavior = adapter,
            state = loaded,
            snackbarHostState = viewModel.snackbarHostState,
            isTabletUi = isTabletUi(),
            chapterSwipeStartAction = s.chapterSwipeActions.start,
            chapterSwipeEndAction = s.chapterSwipeActions.end,
            nav = EntryDetailsNavigation(
                navigateUp = navigator::pop,
                onOpenChapter = { chapterId ->
                    // Route to the chapter's own source (a unified-list row keeps its owning
                    // novelId). The All chip opens group scope; a source chip opens source scope.
                    // The model's state at the tap, so the lambda outlives an emission (as manga's).
                    val live = viewModel.state.value as? NovelDetailsState.Loaded
                    live?.let {
                        chapterToOpen(chapterId, it.chapters, it.resumeChapter, NovelChapter::id)
                    }?.let { ch ->
                        context.startActivity(
                            ReaderActivity.newNovelIntent(
                                context = context,
                                novelId = ch.novelId,
                                chapterId = ch.id,
                                sourceScoped = live.selectedSourceNovelId != null,
                            ),
                        )
                    }
                },
                // Not browsable for an uninstalled plugin, whose catalogue would open on nothing;
                // a genre then searches under the anchor's source.
                viewedSource = DetailsViewedSource(
                    SourceKey.Novel(s.browsableSourceId ?: s.novel.source),
                    browsable = s.browsableSourceId != null,
                ),
                onOpenFilterSettings = viewModel::showChapterSettingsDialog,
                onEditInterval = viewModel::showSetFetchIntervalDialog,
                onOpenPageSelector = viewModel::showPageSelectorDialog,
                // The scope a chapter tapped here opens in: the chip's source, else the group.
                onSearchText = {
                    navigator.push(
                        NovelChapterSearchRoute(
                            novelId = s.selectedSourceNovelId ?: s.novel.id,
                            sourceScoped = s.selectedSourceNovelId != null,
                        ),
                    )
                },
                onWordCount = viewModel::showWordCountDialog,
                // A local novel's books are EPUB files on the device already.
                onExport = viewModel::showExportDialog.takeIf { !LocalNovelSource.isLocal(s.novel.source) },
                onOpenFolder = {
                    scope.launch { openDownloadFolder(context, viewModel.viewedDownloadDir()) }
                },
                onOpenSourceSettings = viewModel::showSourceSettings
                    .takeIf { s.sourceHasSettings },
            ),
            settings = settings,
            volumeMoves = remember(viewModel) { viewModel.detailsVolumeMoves(viewModel.snackbarHostState) },
        )

        EntryDetailsDialogHost(
            s.toSharedDetailsDialog(viewModel.isUpdateIntervalEnabled),
            adapter,
            viewModel::dismissDialog,
        )
        NovelDetailsDialogs(s, viewModel)
    }
}

/** The novel dialogs that stay per-type (their data genuinely diverges); the shared ones go through
 *  [EntryDetailsDialogHost]. */
@Composable
private fun NovelDetailsDialogs(state: NovelDetailsState.Loaded, viewModel: NovelDetailsViewModel) {
    val navigator = LocalNavigator.current
    when (val dialog = state.dialog) {
        is NovelDetailsDialog.ChangeCategory -> ChangeCategoryDialog(
            initialSelection = dialog.initialSelection,
            onDismissRequest = viewModel::dismissDialog,
            onEditCategories = { navigator.push(CategoryRoute) },
            onConfirm = { include, _ -> viewModel.applyCategories(include) },
        )
        is NovelDetailsDialog.DuplicateNovel -> EntryDuplicateDialog(
            prompt = dialog.prompt,
            card = NovelWithChapterCount::toDuplicateCard,
            onDismissRequest = viewModel::dismissDialog,
            onConfirm = viewModel::addFavoriteAnyway,
            onOpen = { navigator.push(EntryId.Novel(it.novel.id).detailsRoute()) },
            onMigrate = { viewModel.startMigrate(it.novel.id) },
            onAddToGroup = viewModel::addToExistingGroup,
        )
        NovelDetailsDialog.ChapterSettings -> NovelChapterSettingsDialog(
            sorting = state.sorting,
            sortDescending = state.sortDescending,
            filters = state.chapterFilters,
            downloadedFilterLocked = state.downloadedFilterLocked,
            hideChapterTitles = state.hideChapterTitles,
            onDismiss = viewModel::dismissDialog,
            onSortModeChange = viewModel::setSortMode,
            onReadFilterChange = viewModel::setReadFilter,
            onBookmarkedFilterChange = viewModel::setBookmarkedFilter,
            onDownloadedFilterChange = viewModel::setDownloadedFilter,
            onDisplayChange = viewModel::setHideChapterTitles,
            onSetAsDefault = viewModel::setChapterSettingsAsDefault,
            onReset = viewModel::resetChapterSettings,
        )
        is NovelDetailsDialog.SourceSettings -> when (val settings = dialog.source.settings) {
            is NovelSettings.LnSchema -> NovelSourceSettingsSheet(settings, onDismiss = viewModel::dismissDialog)
            // A screen of its own rather than a sheet, so the dialog only hands over to it.
            is NovelSettings.PreferenceScreen -> LaunchedEffect(dialog) {
                viewModel.dismissDialog()
                navigator.push(SourceKey.Novel(dialog.source.id).preferencesRoute())
            }
            null -> {}
        }
        is NovelDetailsDialog.WordCount -> NovelWordCountDialog(dialog, onDismissRequest = viewModel::dismissDialog)
        is NovelDetailsDialog.ExportEpub -> NovelExportDialog(
            dialog = dialog,
            fileName = state.novel.title,
            onDownloadMissing = { viewModel.downloadMissingForExport(dialog.missing) },
            onExport = viewModel::exportEpub,
            onDismissRequest = viewModel::dismissDialog,
        )
        NovelDetailsDialog.PageSelector -> NovelPageSelectorSheet(
            pages = state.pages,
            selectedIndex = state.pageIndex,
            onSelect = viewModel::selectPage,
            onDismiss = viewModel::dismissDialog,
        )
        is NovelDetailsDialog.Migrate -> EntryMigrateFor(
            contentType = ContentType.NOVELS,
            currentId = dialog.currentId,
            targetId = dialog.targetId,
            onDismissRequest = viewModel::dismissDialog,
        )
        else -> {}
    }
}

// Map a novel dialog to the shared union for the dialogs both content types render (EntryDetailsDialogHost);
// the per-type ones (change-category, duplicate, chapter-settings, source-settings, page-selector, word
// count, migrate)
// stay in NovelDetailsDialogs.
private fun NovelDetailsState.Loaded.toSharedDetailsDialog(isUpdateIntervalEnabled: Boolean): EntryDetailsDialog? =
    when (val d = dialog) {
        is NovelDetailsDialog.RelinkSeries -> EntryDetailsDialog.RelinkSeries(d.title, d.candidates)
        NovelDetailsDialog.SetFetchInterval -> EntryDetailsDialog.SetFetchInterval(
            interval = intervalNovel.fetchInterval,
            nextUpdate = intervalNovel.expectedNextUpdate(),
            editable = isUpdateIntervalEnabled,
        )
        // Seed from the ANCHOR, not displayNovel: save diffs each field against the anchor row, so
        // seeding from a selected merge sibling would persist its every differing field as an
        // override on an untouched Save. Matches the manga side (seed and diff share one entry).
        NovelDetailsDialog.EditInfo -> editInfoDialog(
            novel,
            customInfo,
            Novel::withCustomInfo,
            Novel::toEntryEditInfoUi,
            coverModel = { url -> novel.asNovelCover(url.ifBlank { null }) },
        )
        is NovelDetailsDialog.FullCover -> EntryDetailsDialog.Cover(d.volume)
        is NovelDetailsDialog.ManageSources -> EntryDetailsDialog.ManageSources(
            sources = d.sources,
            isOverridden = d.isOverridden,
        )
        is NovelDetailsDialog.DeleteChapters -> EntryDetailsDialog.DeleteChapters(d.chapters.map { it.id })
        is NovelDetailsDialog.ClearDownloads -> EntryDetailsDialog.ClearDownloads(d.target)
        is NovelDetailsDialog.RemoveFromLibrary -> EntryDetailsDialog.RemoveFromLibrary(
            d.removal,
            novel.withCustomInfo(customInfo).title,
        )
        is NovelDetailsDialog.ChapterNumber -> EntryDetailsDialog.ChapterNumber(d.edit)
        else -> null
    }

/** Seed the shared edit-info dialog from a novel's effective (edited) values. */
internal fun Novel.toEntryEditInfoUi(altTitles: List<String>) = EntryEditInfoUi(
    title = title,
    author = author.orEmpty(),
    artist = artist.orEmpty(),
    description = description.orEmpty(),
    genre = genre.orEmpty(),
    status = status,
    thumbnailUrl = thumbnailUrl.orEmpty(),
    altTitles = altTitles,
)
