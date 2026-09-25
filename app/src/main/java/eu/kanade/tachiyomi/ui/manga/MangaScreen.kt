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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import eu.kanade.domain.manga.model.hasCustomCover
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.presentation.manga.ChapterSettingsDialog
import eu.kanade.presentation.manga.components.ScanlatorFilterDialog
import eu.kanade.presentation.theme.TachiyomiTheme
import eu.kanade.presentation.util.AssistContentScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.presentation.util.isTabletUi
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.isLocalOrStub
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.online.MetadataSource
import eu.kanade.tachiyomi.ui.browse.extension.details.SourcePreferencesScreen
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.manga.notes.MangaNotesScreen
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.setting.SettingsScreen
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import eu.kanade.tachiyomi.util.system.copyToClipboard
import eu.kanade.tachiyomi.util.system.toShareIntent
import eu.kanade.tachiyomi.util.system.toast
import exh.pagepreview.PagePreviewScreen
import exh.source.configurableSource
import exh.source.getMainSource
import exh.ui.metadata.MetadataViewScreen
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.app.di.appGraph
import reikai.domain.library.ContentType
import reikai.domain.source.SourceKey
import reikai.presentation.browse.catalogue.EntryCatalogueScreen
import reikai.presentation.browse.components.EntryDuplicateDialog
import reikai.presentation.browse.components.toDuplicateCard
import reikai.presentation.browse.globalsearch.EntryGlobalSearchScreen
import reikai.presentation.details.EntryDetailsContent
import reikai.presentation.details.EntryDetailsDialog
import reikai.presentation.details.EntryDetailsDialogHost
import reikai.presentation.details.EntryDetailsNavigation
import reikai.presentation.details.EntryDetailsScreenState
import reikai.presentation.details.EntryDetailsSkeleton
import reikai.presentation.details.EntryEditInfoUi
import reikai.presentation.details.MangaEntryAdapter
import reikai.presentation.details.openDownloadFolder
import reikai.presentation.details.searchGenreFromDetails
import reikai.presentation.manga.EhRemoveFavoriteDialog
import reikai.presentation.migrate.flow.EntryMigrateFor
import reikai.presentation.migrate.flow.EntryMigrationSourcePickScreen
import reikai.presentation.recommendation.browse.RelatedMangasBrowseScreen
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.manga.model.withCustomInfo // RK

class MangaScreen(
    val mangaId: Long, // RK: exposed so the migrate flow can identity-check the screen below it
    val fromSource: Boolean = false,
) : Screen(), AssistContentScreen {

    private var assistUrl: String? = null

    override fun onProvideAssistUrl() = assistUrl

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val viewModel = assistedMetroViewModel<MangaViewModel, MangaViewModel.Factory> {
            create(mangaId = mangaId, isFromSource = fromSource)
        }

        val state by viewModel.state.collectAsStateWithLifecycle()

        if (state is MangaViewModel.State.Loading) {
            EntryDetailsSkeleton() // RK: the details page's shape while it loads, shared with novels
            return
        }

        val successState = state as MangaViewModel.State.Success
        val isHttpSource = remember { successState.source is HttpSource }

        LaunchedEffect(successState.manga, viewModel.source) {
            if (isHttpSource) {
                try {
                    withIOContext {
                        assistUrl = getMangaUrl(viewModel.manga, viewModel.source)
                    }
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "Failed to get manga URL" }
                }
            }
        }

        // RK: load the related-mangas carousel once the screen is open (idempotent per open).
        LaunchedEffect(successState.manga.id) {
            viewModel.loadRelatedMangas()
        }

        // RK: extract the cover's vibrant color to tint the screen (Y11).
        LaunchedEffect(successState.manga.id) {
            viewModel.updateSeedColor()
        }

        // RK: the shared details body renders through the manga adapter over the live model.
        val coverViewModelFactory = remember { context.appGraph.mangaCoverViewModelFactory }
        val adapter = remember(viewModel) { MangaEntryAdapter(viewModel, coverViewModelFactory) }
        val neutralState by adapter.state.collectAsStateWithLifecycle()

        // RK: tint the details screen from the cover color (Y11)
        TachiyomiTheme(seedColor = successState.seedColor.takeIf { viewModel.themeCoverBased }) {
            (neutralState as? EntryDetailsScreenState.Loaded)?.let { loaded ->
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
                        onOpenChapter = { chapterId ->
                            successState.chapters.firstOrNull { it.id == chapterId }?.chapter?.let {
                                openChapter(context, it, successState.selectedSourceMangaId != null)
                            }
                        },
                        onGlobalSearch = { scope.launch { performSearch(navigator, it, global = true) } },
                        onTagSearch = { scope.launch { performGenreSearch(navigator, it, viewModel.source!!) } },
                        onCopyTag = { if (it.isNotEmpty()) context.copyToClipboard(it, it) },
                        onTracking = {
                            if (!successState.hasLoggedInTrackers) {
                                navigator.push(SettingsScreen(SettingsScreen.Destination.Tracking))
                            } else {
                                viewModel.showTrackDialog()
                            }
                        },
                        onEditNotes = { navigator.push(MangaNotesScreen(manga = successState.manga)) },
                        onOpenFilterSettings = viewModel::showSettingsDialog,
                        // Share lives in the toolbar overflow for manga.
                        // RK: view-only surfaces (share / WebView / copy URL) follow the selected
                        // source chip like novels; writes (migrate, covers) stay anchor-scoped.
                        onToolbarShare = {
                            shareManga(
                                context,
                                successState.mergeDisplayManga ?: viewModel.manga,
                                successState.mergeDisplaySource ?: viewModel.source,
                            )
                        }.takeIf { isHttpSource },
                        onOpenWebView = {
                            openMangaInWebView(
                                navigator,
                                successState.mergeDisplayManga ?: viewModel.manga,
                                successState.mergeDisplaySource ?: viewModel.source,
                            )
                        }.takeIf { isHttpSource },
                        onOpenWebViewLong = {
                            copyMangaUrl(
                                context,
                                successState.mergeDisplayManga ?: viewModel.manga,
                                successState.mergeDisplaySource ?: viewModel.source,
                            )
                        }.takeIf { isHttpSource },
                        onMigrate = {
                            // Source picker first, so a merged manga can pick which source to migrate.
                            navigator.push(
                                EntryMigrationSourcePickScreen(ContentType.MANGA, listOf(successState.manga.id)),
                            )
                        }.takeIf { successState.manga.favorite },
                        onEditInterval = viewModel::showSetFetchIntervalDialog
                            .takeIf { successState.manga.favorite },
                        // Open a related card; a tracker-origin card (no installed source) goes to search.
                        onRelatedClick = { candidate ->
                            scope.launch {
                                val id = viewModel.resolveRelatedToLocalId(candidate)
                                if (id != null) {
                                    navigator.push(MangaScreen(id))
                                } else {
                                    navigator.push(
                                        EntryGlobalSearchScreen(
                                            candidate.manga.title,
                                            scopedContentType = ContentType.MANGA,
                                        ),
                                    )
                                }
                            }
                        },
                        onRelatedSeeAll = {
                            navigator.push(RelatedMangasBrowseScreen(successState.manga.id, successState.manga.title))
                        },
                        // Recommendations moved to the overflow menu (Settings > Library > Recommendations):
                        // open the same browse grid "See all" uses. Null keeps it off when placed inline.
                        onRecommendations = {
                            navigator.push(RelatedMangasBrowseScreen(successState.manga.id, successState.manga.title))
                        }.takeIf { viewModel.recommendationsInMenu },
                        onOpenPagePreview = { page ->
                            openPagePreview(
                                context,
                                successState.chapters.minByOrNull { it.chapter.sourceOrder }?.chapter,
                                page,
                            )
                        },
                        onMorePreviews = { navigator.push(PagePreviewScreen(successState.manga.id)) },
                        onLibrarySearch = { query ->
                            scope.launch { performSearch(navigator, query, global = false) }
                        },
                        // The viewed source, and null on a stub: there is no catalogue to open for an
                        // extension that is not installed.
                        onBrowseSource = (successState.mergeDisplaySource ?: successState.source)
                            .takeIf { !it.isLocalOrStub() }
                            ?.let { source -> { navigator.push(EntryCatalogueScreen(SourceKey.Manga(source.id))) } },
                        onOpenFolder = { scope.launch { openDownloadFolder(context, viewModel.viewedDownloadDir()) } },
                        // The viewed source, as the metadata viewer resolves it, so a merged entry
                        // opens the settings of the source its chip is showing.
                        onOpenSourceSettings = (successState.mergeDisplaySource ?: successState.source)
                            .takeIf { it.configurableSource() != null }
                            ?.let { source -> { navigator.push(SourcePreferencesScreen(source.id)) } },
                        // Gallery metadata viewer, only for adult/metadata sources; follows the viewed source
                        // (the selected chip), so enhanced-MangaDex "More info" shows even when the merge is
                        // anchored on a non-metadata source.
                        onMetadataViewer = {
                            val displayManga = successState.mergeDisplayManga ?: successState.manga
                            val displaySource = successState.mergeDisplaySource ?: successState.source
                            navigator.push(
                                MetadataViewScreen(
                                    mangaId = displayManga.id,
                                    sourceId = displaySource.id,
                                    seedColor = successState.seedColor?.toArgb(),
                                ),
                            )
                        }.takeIf {
                            (successState.mergeDisplaySource ?: successState.source)
                                .getMainSource<MetadataSource<*, *>>() != null
                        },
                    ),
                )
            }
        } // RK: end cover-based theme wrap

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
                is MangaViewModel.Dialog.ChangeCategory -> {
                    ChangeCategoryDialog(
                        initialSelection = dialog.initialSelection,
                        onDismissRequest = onDismissRequest,
                        onEditCategories = { navigator.push(CategoryScreen()) },
                        onConfirm = { include, _ ->
                            viewModel.moveMangaToCategoriesAndAddToLibrary(dialog.manga, include, dialog.joinGroup)
                        },
                    )
                }
                is MangaViewModel.Dialog.DuplicateManga -> {
                    EntryDuplicateDialog(
                        duplicates = dialog.duplicates,
                        toUi = { it.toDuplicateCard(dialog.sourceLabels) },
                        onDismissRequest = onDismissRequest,
                        onConfirm = { viewModel.toggleFavorite(onRemoved = {}, checkDuplicate = false) },
                        onOpen = { navigator.push(MangaScreen(it.manga.id)) },
                        onMigrate = { viewModel.showMigrateDialog(it.manga) },
                        // RK: offer grouping when the same-title suggestion pref is on.
                        groupIdByEntryId = dialog.groupIdByMangaId,
                        onAddToGroup = { selectedIds: List<Long> -> viewModel.addToExistingGroup(selectedIds) }
                            .takeIf { dialog.suggestGroup },
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
                MangaViewModel.Dialog.SettingsSheet -> ChapterSettingsDialog(
                    onDismissRequest = onDismissRequest,
                    manga = successState.manga,
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
                        onConfirm = viewModel::confirmEhRemoveFromLibrary,
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

    // RK: open the reader at a specific page from a gallery page preview.
    private fun openPagePreview(context: Context, chapter: Chapter?, page: Int) {
        chapter ?: return
        context.startActivity(ReaderActivity.newIntent(context, chapter.mangaId, chapter.id, page))
    }

    private fun getMangaUrl(manga_: Manga?, source_: Source?): String? {
        val manga = manga_ ?: return null
        val source = source_ as? HttpSource ?: return null

        return try {
            source.getMangaUrl(manga.toSManga())
        } catch (e: Exception) {
            null
        }
    }

    private fun openMangaInWebView(navigator: Navigator, manga_: Manga?, source_: Source?) {
        getMangaUrl(manga_, source_)?.let { url ->
            navigator.push(
                WebViewScreen(
                    url = url,
                    initialTitle = manga_?.title,
                    sourceId = source_?.id,
                ),
            )
        }
    }

    private fun shareManga(context: Context, manga_: Manga?, source_: Source?) {
        try {
            getMangaUrl(manga_, source_)?.let { url ->
                val intent = url.toUri().toShareIntent(context, type = "text/plain")
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            context.toast(e.message)
        }
    }

    /**
     * Perform a search using the provided query.
     *
     * @param query the search query to the parent controller
     */
    // RK --> Walks the back stack rather than looking one screen back, which is upstream's shape: a
    //        details screen reached from history, updates, a deep link or a related hop has no library
    //        directly behind it, so the search silently did nothing. It walks past any catalogue so it
    //        always lands on the library, and carries this entry's content type, because the library
    //        searches whichever type chip is open. A catalogue is only a genre's target, see below.
    private suspend fun performSearch(navigator: Navigator, query: String, global: Boolean) {
        if (global) {
            navigator.push(EntryGlobalSearchScreen(query, scopedContentType = ContentType.MANGA))
            return
        }

        if (navigator.size < 2) {
            return
        }

        navigator.popUntil { it is HomeScreen }
        (navigator.lastItem as? HomeScreen)?.search(query, ContentType.MANGA)
    }
    // RK <--

    /**
     * Performs a genre search using the provided genre name.
     *
     * @param genreName the search genre to the parent controller
     */
    // RK: the walk novels share, which only returns to this source's own catalogue.
    private suspend fun performGenreSearch(navigator: Navigator, genreName: String, source: Source) {
        navigator.searchGenreFromDetails(
            genreName,
            SourceKey.Manga(source.id),
            ContentType.MANGA,
            filterable = source is HttpSource,
        )
    }

    /**
     * Copy Manga URL to Clipboard
     */
    private fun copyMangaUrl(context: Context, manga_: Manga?, source_: Source?) {
        val manga = manga_ ?: return
        val source = source_ as? HttpSource ?: return
        val url = source.getMangaUrl(manga.toSManga())
        context.copyToClipboard(url, url)
    }
}

// RK: seed the shared edit-info dialog from a manga's effective (overlaid) values.
internal fun Manga.toEntryEditInfoUi() = EntryEditInfoUi(
    title = title,
    author = author.orEmpty(),
    artist = artist.orEmpty(),
    description = description.orEmpty(),
    genre = genre.orEmpty(),
    status = status,
    thumbnailUrl = thumbnailUrl.orEmpty(),
)

// RK: map a manga dialog to the shared union for the dialogs both content types render (EntryDetailsDialogHost);
// the per-type ones (change-category, duplicate, chapter-settings, migrate, fetch-interval, ...) stay above.
private fun MangaViewModel.State.Success.toSharedDetailsDialog(isUpdateIntervalEnabled: Boolean): EntryDetailsDialog? =
    when (val d = dialog) {
        is MangaViewModel.Dialog.SetFetchInterval -> EntryDetailsDialog.SetFetchInterval(
            interval = d.manga.fetchInterval,
            nextUpdate = d.manga.expectedNextUpdate,
            editable = isUpdateIntervalEnabled,
        )
        is MangaViewModel.Dialog.EditMangaInfo -> EntryDetailsDialog.EditInfo(
            // Seed with the effective (overlaid) values; save diffs each field against the raw source manga.
            initial = d.manga.withCustomInfo(customInfo).toEntryEditInfoUi(),
            source = d.manga.toEntryEditInfoUi(),
            seedColor = seedColor,
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
        MangaViewModel.Dialog.FullCover -> EntryDetailsDialog.Cover
        is MangaViewModel.Dialog.ManageSources -> EntryDetailsDialog.ManageSources(
            sources = d.sources,
            isOverridden = d.isOverridden,
        )
        MangaViewModel.Dialog.TrackSheet -> EntryDetailsDialog.TrackSheet(
            entryId = manga.id,
            entryTitle = manga.title,
            sourceId = source.id,
            isNovel = false,
        )
        is MangaViewModel.Dialog.DeleteChapters -> EntryDetailsDialog.DeleteChapters(d.chapters.map { it.id })
        is MangaViewModel.Dialog.ClearDownloads -> EntryDetailsDialog.ClearDownloads(d.sourceName)
        else -> null
    }
