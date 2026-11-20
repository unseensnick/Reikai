package eu.kanade.tachiyomi.ui.manga

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation3.runtime.NavKey
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import eu.kanade.domain.manga.model.hasCustomCover
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.presentation.manga.ChapterSettingsDialog
import eu.kanade.presentation.manga.components.ScanlatorFilterDialog
import eu.kanade.presentation.theme.TachiyomiTheme
import eu.kanade.tachiyomi.source.isLocalOrStub
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.online.MetadataSource
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import exh.pagepreview.PagePreviewRoute
import exh.pagepreview.openPreviewPage
import exh.source.configurableSource
import exh.source.getMainSource
import exh.ui.metadata.MetadataViewRoute
import kotlinx.coroutines.launch
import mihon.app.di.appGraph
import mihon.core.navigation.CategoryRoute
import mihon.core.navigation.LocalAssistUrl
import mihon.core.navigation.LocalNavigator
import mihon.presentation.core.util.isTabletUi
import reikai.domain.entry.EntryId
import reikai.domain.entry.withCustomInfo // RK
import reikai.domain.library.ContentType
import reikai.domain.source.SourceKey
import reikai.presentation.browse.components.EntryDuplicateDialog
import reikai.presentation.browse.components.toDuplicateCard
import reikai.presentation.browse.source.preferencesRoute
import reikai.presentation.details.DETAILS_ADAPTER_KEY // RK
import reikai.presentation.details.DETAILS_SETTINGS_KEY // RK
import reikai.presentation.details.DetailsSettings // RK
import reikai.presentation.details.DetailsViewedSource
import reikai.presentation.details.DuplicateMarking // RK
import reikai.presentation.details.EntryDetailsContent
import reikai.presentation.details.EntryDetailsDialog
import reikai.presentation.details.EntryDetailsDialogHost
import reikai.presentation.details.EntryDetailsNavigation
import reikai.presentation.details.EntryDetailsNotLoaded
import reikai.presentation.details.EntryDetailsScreenState
import reikai.presentation.details.EntryEditInfoUi
import reikai.presentation.details.MangaEntryAdapter
import reikai.presentation.details.chapterToOpen
import reikai.presentation.details.detailsRoute
import reikai.presentation.details.detailsVolumeMoves // RK
import reikai.presentation.details.editInfoDialog
import reikai.presentation.details.keptForScreen // RK
import reikai.presentation.details.openDownloadFolder
import reikai.presentation.manga.EhRemoveFavoriteDialog
import reikai.presentation.migrate.flow.EntryMigrateFor
import reikai.presentation.recommendation.browse.RelatedMangasBrowseRoute
import reikai.presentation.recommendation.relatedDestination
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.manga.model.MangaWithChapterCount

@Composable
fun MangaScreen(
    mangaId: Long,
    fromSource: Boolean,
) {
    val navigator = LocalNavigator.current
    val setAssistUrl = LocalAssistUrl.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // RK: no haptic read; upstream's MangaScreen composable that took it is replaced below
    val viewModel = assistedMetroViewModel<MangaViewModel, MangaViewModel.Factory> {
        create(mangaId = mangaId, isFromSource = fromSource)
    }

    // RK: the E-Hentai root redirect opens the gallery's favorited copy in this screen's place
    LaunchedEffect(viewModel) {
        viewModel.exhRootRedirects.collect { navigator.replace(EntryId.Manga(it).detailsRoute()) }
    }

    val state by viewModel.state.collectAsStateWithLifecycle()

    // RK --> the shared details body renders through the manga adapter over the live model.
    val coverViewModelFactory = remember { context.appGraph.mangaCoverViewModelFactory }
    val adapter = remember(viewModel) {
        viewModel.keptForScreen(DETAILS_ADAPTER_KEY) {
            val graph = context.appGraph
            val duplicates = DuplicateMarking(
                ContentType.MANGA,
                graph.chapterCopyMarks,
                graph.reikaiLibraryPreferences.showSkippedDuplicates,
                graph.readerPreferences.skipDupe,
                viewModel.viewModelScope,
                graph.appCoroutineScope,
            )
            MangaEntryAdapter(viewModel, coverViewModelFactory, duplicates, graph.volumePicking)
        }
    }
    val settings = remember(viewModel) {
        viewModel.keptForScreen(DETAILS_SETTINGS_KEY) {
            context.appGraph.run { DetailsSettings(uiPreferences, reikaiLibraryPreferences, viewModel.viewModelScope) }
        }
    }
    val neutralState by adapter.state.collectAsStateWithLifecycle()

    // The details page's shape while it loads, or its not-found page, shared with novels, decided by the
    // neutral state alone: it is mapped from the model's, so a loaded one means the model's is Success.
    val loaded = neutralState as? EntryDetailsScreenState.Loaded
    if (loaded == null) {
        EntryDetailsNotLoaded(neutralState, navigator::pop)
        return
    }
    // RK <--

    val successState = state as MangaViewModel.State.Success
    // RK: the assistant link is the shown member's page, which the model resolves (EntryWebPage)
    LaunchedEffect(successState.webPage) { setAssistUrl(successState.webPage?.url) }

    // RK: load the related-mangas carousel once the screen is open (idempotent per open).
    LaunchedEffect(successState.manga.id) {
        viewModel.loadRelatedMangas()
    }

    // RK: extract the cover's vibrant color to tint the screen, again when the cover changes.
    LaunchedEffect(successState.manga.id, successState.manga.thumbnailUrl, successState.manga.coverLastModified) {
        viewModel.updateSeedColor()
    }

    // RK --> the shared details body, over the adapter built above
    // RK: tint the details screen from the cover color
    TachiyomiTheme(seedColor = successState.seedColor.takeIf { viewModel.themeCoverBased }) {
        EntryDetailsContent(
            behavior = adapter,
            state = loaded,
            snackbarHostState = viewModel.snackbarHostState,
            isTabletUi = isTabletUi(),
            chapterSwipeStartAction = viewModel.chapterSwipeStartAction,
            chapterSwipeEndAction = viewModel.chapterSwipeEndAction,
            nav = EntryDetailsNavigation(
                navigateUp = navigator::pop,
                // A specific source chip opens source scope; the All chip (null) opens group scope.
                // Reads the model's state at the tap rather than capturing this emission's, so the
                // lambda outlives an emission and the chapter rows can skip recomposing.
                onOpenChapter = { chapterId ->
                    (viewModel.state.value as? MangaViewModel.State.Success)?.let { live ->
                        val listed = live.chapters.map { it.chapter }
                        chapterToOpen(chapterId, listed, live.resumeChapter, Chapter::id)?.let {
                            openChapter(context, it, live.selectedSourceMangaId != null)
                        }
                    }
                },
                // The viewed source, since the tags on screen are the selected chip's. A stub has no
                // catalogue to browse, and a source with no filters searches a genre as text.
                viewedSource = successState.shownSource.let { source ->
                    DetailsViewedSource(
                        SourceKey.Manga(source.id),
                        browsable = !source.isLocalOrStub(),
                        filterable = source is HttpSource,
                    )
                },
                onOpenFilterSettings = viewModel::showSettingsDialog,
                onEditInterval = viewModel::showSetFetchIntervalDialog,
                onRelatedClick = { candidate ->
                    scope.launch {
                        val id = viewModel.resolveRelatedToLocalId(candidate)
                        navigator.push(relatedDestination(candidate, id))
                    }
                },
                onRelatedSeeAll = {
                    navigator.push(RelatedMangasBrowseRoute(successState.manga.id))
                },
                // Recommendations moved to the overflow menu (Settings > Library > Recommendations):
                // open the same browse grid "See all" uses. Null keeps it off when placed inline.
                onRecommendations = {
                    navigator.push(RelatedMangasBrowseRoute(successState.manga.id))
                }.takeIf { viewModel.recommendationsInMenu },
                onOpenPagePreview = { page ->
                    scope.launch { viewModel.pagePreviewChapter()?.let { context.openPreviewPage(it, page) } }
                },
                onMorePreviews = { navigator.push(PagePreviewRoute(successState.manga.id)) },
                onOpenFolder = { scope.launch { openDownloadFolder(context, viewModel.viewedDownloadDir()) } },
                // The viewed source, as the metadata viewer resolves it, so a merged entry
                // opens the settings of the source its chip is showing.
                onOpenSourceSettings = successState.shownSource
                    .takeIf { it.configurableSource() != null }
                    ?.let { source -> { navigator.push(SourceKey.Manga(source.id).preferencesRoute()) } },
                // Gallery metadata viewer, only for adult/metadata sources; follows the viewed source
                // (the selected chip), so enhanced-MangaDex "More info" shows even when the merge is
                // anchored on a non-metadata source.
                onMetadataViewer = {
                    navigator.push(
                        MetadataViewRoute(
                            mangaId = successState.shownManga.id,
                            sourceId = successState.shownSource.id,
                        ),
                    )
                }.takeIf { successState.shownSource.getMainSource<MetadataSource<*, *>>() != null },
            ),
            settings = settings,
            volumeMoves = remember(viewModel) { viewModel.detailsVolumeMoves(viewModel.snackbarHostState) },
        )
    } // RK: end cover-based theme wrap
    // RK <--

    var showScanlatorsDialog by remember { mutableStateOf(false) }

    val onDismissRequest = { viewModel.dismissDialog() }
    // RK: tint every details dialog from the cover (when that theme is on), matching the details
    // content and the novel side; the content wrap above ends before the dialogs, so re-apply it.
    TachiyomiTheme(seedColor = successState.seedColor.takeIf { viewModel.themeCoverBased }) {
        EntryDetailsDialogHost(
            successState.toSharedDetailsDialog(viewModel.isUpdateIntervalEnabled),
            adapter,
            onDismissRequest,
        )
        when (val dialog = successState.dialog) {
            // RK --> shared duplicate and migrate dialogs; a category pick can also join a merge group
            is MangaViewModel.Dialog.ChangeCategory -> {
                ChangeCategoryDialog(
                    initialSelection = dialog.initialSelection,
                    onDismissRequest = onDismissRequest,
                    onEditCategories = { navigator.push(CategoryRoute) },
                    onConfirm = { include, _ ->
                        viewModel.moveMangaToCategoriesAndAddToLibrary(dialog.manga, include)
                    },
                )
            }
            is MangaViewModel.Dialog.DuplicateManga -> {
                EntryDuplicateDialog(
                    prompt = dialog.prompt,
                    card = MangaWithChapterCount::toDuplicateCard,
                    onDismissRequest = onDismissRequest,
                    onConfirm = { viewModel.toggleFavorite(checkDuplicate = false) },
                    onOpen = { navigator.push(EntryId.Manga(it.manga.id).detailsRoute()) },
                    onMigrate = { viewModel.showMigrateDialog(it.manga) },
                    onAddToGroup = viewModel::addToExistingGroup,
                )
            }

            is MangaViewModel.Dialog.Migrate -> {
                EntryMigrateFor(
                    contentType = ContentType.MANGA,
                    currentId = dialog.current.id,
                    targetId = dialog.target.id,
                    onDismissRequest = onDismissRequest,
                )
            }
            // RK <--
            MangaViewModel.Dialog.SettingsSheet -> ChapterSettingsDialog(
                onDismissRequest = onDismissRequest,
                manga = successState.manga,
                downloadedOnly = successState.downloadedOnly,
                onDownloadFilterChanged = viewModel::setDownloadedFilter,
                onUnreadFilterChanged = viewModel::setUnreadFilter,
                onBookmarkedFilterChanged = viewModel::setBookmarkedFilter,
                onSortModeChanged = viewModel::setSorting,
                onDisplayModeChanged = viewModel::setDisplayMode,
                onSetAsDefault = viewModel::setCurrentSettingsAsDefault,
                onResetToDefault = viewModel::resetToDefaultSettings,
                scanlatorFilterActive = successState.scanlatorFilterActive,
                onScanlatorFilterClicked = { showScanlatorsDialog = true },
            )
            // RK -->
            is MangaViewModel.Dialog.EhRemoveFavorite -> {
                EhRemoveFavoriteDialog(
                    onDismissRequest = onDismissRequest,
                    onConfirm = { viewModel.confirmEhRemoveFromLibrary(dialog.manga, it) },
                )
            }
            // RK <--
            else -> {}
        }

        if (showScanlatorsDialog) {
            ScanlatorFilterDialog(
                availableScanlators = successState.availableScanlators,
                excludedScanlators = successState.excludedScanlators,
                onDismissRequest = { showScanlatorsDialog = false },
                onConfirm = viewModel::setExcludedScanlators,
            )
        }
    } // RK: end dialogs cover-theme wrap
}

// RK: sourceScoped opens just the active source chip's own list; group scope (the All chip, so
// no chip selected) opens the whole merge group.
private fun openChapter(context: Context, chapter: Chapter, sourceScoped: Boolean) {
    context.startActivity(
        ReaderActivity.newIntent(context, chapter.mangaId, chapter.id, sourceScoped = sourceScoped),
    )
}

// RK: getMangaUrl, openMangaInWebView and shareManga moved to EntryWebPage, shared with novels

// RK: performSearch and performGenreSearch moved to the shared details body (withSharedPushes), which
// walks the back stack to the library or the viewed source's catalogue for both types.

// RK: copyMangaUrl moved to EntryWebPage (rememberEntryWebActions), which cannot throw

// RK: seed the shared edit-info dialog from a manga's effective (overlaid) values.
internal fun Manga.toEntryEditInfoUi(altTitles: List<String>) = EntryEditInfoUi(
    title = title,
    author = author.orEmpty(),
    artist = artist.orEmpty(),
    description = description.orEmpty(),
    genre = genre.orEmpty(),
    status = status,
    thumbnailUrl = thumbnailUrl.orEmpty(),
    altTitles = altTitles,
)

// RK: map a manga dialog to the shared union for the dialogs both content types render (EntryDetailsDialogHost);
// the per-type ones (change-category, duplicate, chapter-settings, migrate, fetch-interval, ...) stay above.
private fun MangaViewModel.State.Success.toSharedDetailsDialog(isUpdateIntervalEnabled: Boolean): EntryDetailsDialog? =
    when (val d = dialog) {
        is MangaViewModel.Dialog.RelinkSeries -> EntryDetailsDialog.RelinkSeries(d.title, d.candidates) // RK
        is MangaViewModel.Dialog.SetFetchInterval -> EntryDetailsDialog.SetFetchInterval(
            interval = d.manga.fetchInterval,
            nextUpdate = d.manga.expectedNextUpdate,
            editable = isUpdateIntervalEnabled,
        )
        is MangaViewModel.Dialog.EditMangaInfo -> editInfoDialog(
            d.manga,
            customInfo,
            Manga::withCustomInfo,
            Manga::toEntryEditInfoUi,
            coverModel = { url ->
                MangaCover(
                    mangaId = d.manga.id,
                    sourceId = d.manga.source,
                    isMangaFavorite = d.manga.favorite,
                    url = url.ifBlank { null },
                    lastModified = d.manga.coverLastModified,
                )
            },
        )
        is MangaViewModel.Dialog.FullCover -> EntryDetailsDialog.Cover(d.volume) // RK
        is MangaViewModel.Dialog.ManageSources -> EntryDetailsDialog.ManageSources(
            sources = d.sources,
            isOverridden = d.isOverridden,
        )
        is MangaViewModel.Dialog.DeleteChapters -> EntryDetailsDialog.DeleteChapters(d.chapters.map { it.id })
        is MangaViewModel.Dialog.ClearDownloads -> EntryDetailsDialog.ClearDownloads(d.target)
        is MangaViewModel.Dialog.RemoveFromLibrary -> EntryDetailsDialog.RemoveFromLibrary(
            d.removal,
            manga.withCustomInfo(customInfo).title,
        )
        is MangaViewModel.Dialog.ChapterNumber -> EntryDetailsDialog.ChapterNumber(d.edit)
        else -> null
    }
