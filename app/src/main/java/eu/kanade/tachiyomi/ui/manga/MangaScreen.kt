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
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.core.util.ifSourcesLoaded
import eu.kanade.domain.manga.model.hasCustomCover
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.presentation.manga.ChapterSettingsDialog
import eu.kanade.presentation.manga.DuplicateMangaDialog
import eu.kanade.presentation.manga.components.ScanlatorFilterDialog
import eu.kanade.presentation.manga.components.SetIntervalDialog
import eu.kanade.presentation.theme.TachiyomiTheme
import eu.kanade.presentation.util.AssistContentScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.presentation.util.isTabletUi
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.isLocalOrStub
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.online.MetadataSource
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreen
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
import exh.source.getMainSource
import exh.ui.metadata.MetadataViewScreen
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.feature.migration.dialog.MigrateMangaDialog
import reikai.presentation.details.EntryDetailsContent
import reikai.presentation.details.EntryDetailsDialog
import reikai.presentation.details.EntryDetailsDialogHost
import reikai.presentation.details.EntryDetailsNavigation
import reikai.presentation.details.EntryDetailsScreenState
import reikai.presentation.details.EntryEditInfoUi
import reikai.presentation.details.MangaEntryAdapter
import reikai.presentation.manga.EhRemoveFavoriteDialog
import reikai.presentation.manga.MangaMigrationSourcePickScreen
import reikai.presentation.recommendation.browse.RelatedMangasBrowseScreen
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.manga.model.withCustomInfo // RK
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.screens.LoadingScreen

class MangaScreen(
    private val mangaId: Long,
    val fromSource: Boolean = false,
) : Screen(), AssistContentScreen {

    private var assistUrl: String? = null

    override fun onProvideAssistUrl() = assistUrl

    @Composable
    override fun Content() {
        if (!ifSourcesLoaded()) {
            LoadingScreen()
            return
        }

        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val lifecycleOwner = LocalLifecycleOwner.current
        val screenModel = rememberScreenModel {
            MangaScreenModel(context, lifecycleOwner.lifecycle, mangaId, fromSource)
        }

        val state by screenModel.state.collectAsStateWithLifecycle()

        if (state is MangaScreenModel.State.Loading) {
            LoadingScreen()
            return
        }

        val successState = state as MangaScreenModel.State.Success
        val isHttpSource = remember { successState.source is HttpSource }

        LaunchedEffect(successState.manga, screenModel.source) {
            if (isHttpSource) {
                try {
                    withIOContext {
                        assistUrl = getMangaUrl(screenModel.manga, screenModel.source)
                    }
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "Failed to get manga URL" }
                }
            }
        }

        // RK: load the related-mangas carousel once the screen is open (idempotent per open).
        LaunchedEffect(successState.manga.id) {
            screenModel.loadRelatedMangas()
        }

        // RK: extract the cover's vibrant color to tint the screen (Y11).
        LaunchedEffect(successState.manga.id) {
            screenModel.updateSeedColor()
        }

        // RK: the shared details body renders through the manga adapter over the live model.
        val adapter = remember(screenModel) { MangaEntryAdapter(screenModel) }
        val neutralState by adapter.state.collectAsStateWithLifecycle()

        // RK: tint the details screen from the cover color (Y11)
        TachiyomiTheme(seedColor = successState.seedColor.takeIf { screenModel.themeCoverBased }) {
            (neutralState as? EntryDetailsScreenState.Loaded)?.let { loaded ->
                EntryDetailsContent(
                    behavior = adapter,
                    state = loaded,
                    snackbarHostState = screenModel.snackbarHostState,
                    isTabletUi = isTabletUi(),
                    chapterSwipeStartAction = screenModel.chapterSwipeStartAction,
                    chapterSwipeEndAction = screenModel.chapterSwipeEndAction,
                    nav = EntryDetailsNavigation(
                        navigateUp = navigator::pop,
                        // A specific source chip opens source scope; the All chip (null) opens group scope.
                        onOpenChapter = { chapterId ->
                            successState.chapters.firstOrNull { it.id == chapterId }?.chapter?.let {
                                openChapter(context, it, successState.selectedSourceMangaId != null)
                            }
                        },
                        onSearch = { query, global -> scope.launch { performSearch(navigator, query, global) } },
                        onTagSearch = { scope.launch { performGenreSearch(navigator, it, screenModel.source!!) } },
                        onCopyTag = { if (it.isNotEmpty()) context.copyToClipboard(it, it) },
                        onTracking = {
                            if (!successState.hasLoggedInTrackers) {
                                navigator.push(SettingsScreen(SettingsScreen.Destination.Tracking))
                            } else {
                                screenModel.showTrackDialog()
                            }
                        },
                        onEditNotes = { navigator.push(MangaNotesScreen(manga = successState.manga)) },
                        onOpenFilterSettings = screenModel::showSettingsDialog,
                        // Share lives in the toolbar overflow for manga.
                        onToolbarShare = {
                            shareManga(context, screenModel.manga, screenModel.source)
                        }.takeIf { isHttpSource },
                        onOpenWebView = {
                            openMangaInWebView(navigator, screenModel.manga, screenModel.source)
                        }.takeIf { isHttpSource },
                        onOpenWebViewLong = {
                            copyMangaUrl(context, screenModel.manga, screenModel.source)
                        }.takeIf { isHttpSource },
                        onMigrate = {
                            // Source picker first, so a merged manga can pick which source to migrate.
                            navigator.push(MangaMigrationSourcePickScreen(listOf(successState.manga.id)))
                        }.takeIf { successState.manga.favorite },
                        onEditInterval = screenModel::showSetFetchIntervalDialog
                            .takeIf { successState.manga.favorite },
                        // Open a related card; a tracker-origin card (no installed source) goes to search.
                        onRelatedClick = { candidate ->
                            scope.launch {
                                val id = screenModel.resolveRelatedToLocalId(candidate)
                                if (id != null) {
                                    navigator.push(MangaScreen(id))
                                } else {
                                    navigator.push(GlobalSearchScreen(candidate.manga.title))
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
                        }.takeIf { screenModel.recommendationsInMenu },
                        onOpenPagePreview = { page ->
                            openPagePreview(
                                context,
                                successState.chapters.minByOrNull { it.chapter.sourceOrder }?.chapter,
                                page,
                            )
                        },
                        onMorePreviews = { navigator.push(PagePreviewScreen(successState.manga.id)) },
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

        val onDismissRequest = { screenModel.dismissDialog() }
        // RK: tint every details dialog from the cover (when that theme is on), matching the details
        // content and the novel side; the content wrap above ends before the dialogs, so re-apply it.
        TachiyomiTheme(seedColor = successState.seedColor.takeIf { screenModel.themeCoverBased }) {
            EntryDetailsDialogHost(successState.toSharedDetailsDialog(), adapter, onDismissRequest)
            when (val dialog = successState.dialog) {
                is MangaScreenModel.Dialog.ChangeCategory -> {
                    ChangeCategoryDialog(
                        initialSelection = dialog.initialSelection,
                        onDismissRequest = onDismissRequest,
                        onEditCategories = { navigator.push(CategoryScreen()) },
                        onConfirm = { include, _ ->
                            screenModel.moveMangaToCategoriesAndAddToLibrary(dialog.manga, include)
                        },
                    )
                }
                is MangaScreenModel.Dialog.DuplicateManga -> {
                    DuplicateMangaDialog(
                        duplicates = dialog.duplicates,
                        onDismissRequest = onDismissRequest,
                        onConfirm = { screenModel.toggleFavorite(onRemoved = {}, checkDuplicate = false) },
                        onOpenManga = { navigator.push(MangaScreen(it.id)) },
                        onMigrate = { screenModel.showMigrateDialog(it) },
                        // RK: offer grouping when the same-title suggestion pref is on.
                        groupIdByMangaId = dialog.groupIdByMangaId,
                        onAddToGroup = { selectedIds: List<Long> -> screenModel.addToExistingGroup(selectedIds) }
                            .takeIf { dialog.suggestGroup },
                    )
                }

                is MangaScreenModel.Dialog.Migrate -> {
                    MigrateMangaDialog(
                        current = dialog.current,
                        target = dialog.target,
                        // Initiated from the context of [dialog.target] so we show [dialog.current].
                        onClickTitle = { navigator.push(MangaScreen(dialog.current.id)) },
                        onDismissRequest = onDismissRequest,
                    )
                }
                MangaScreenModel.Dialog.SettingsSheet -> ChapterSettingsDialog(
                    onDismissRequest = onDismissRequest,
                    manga = successState.manga,
                    onDownloadFilterChanged = screenModel::setDownloadedFilter,
                    onUnreadFilterChanged = screenModel::setUnreadFilter,
                    onBookmarkedFilterChanged = screenModel::setBookmarkedFilter,
                    onSortModeChanged = screenModel::setSorting,
                    onDisplayModeChanged = screenModel::setDisplayMode,
                    onSetAsDefault = screenModel::setCurrentSettingsAsDefault,
                    onResetToDefault = screenModel::resetToDefaultSettings,
                    scanlatorFilterActive = successState.scanlatorFilterActive,
                    onScanlatorFilterClicked = { showScanlatorsDialog = true },
                )
                is MangaScreenModel.Dialog.SetFetchInterval -> {
                    SetIntervalDialog(
                        interval = dialog.manga.fetchInterval,
                        nextUpdate = dialog.manga.expectedNextUpdate,
                        onDismissRequest = onDismissRequest,
                        onValueChanged = { interval: Int -> screenModel.setFetchInterval(dialog.manga, interval) }
                            .takeIf { screenModel.isUpdateIntervalEnabled },
                    )
                }
                // RK -->
                is MangaScreenModel.Dialog.EhRemoveFavorite -> {
                    EhRemoveFavoriteDialog(
                        onDismissRequest = onDismissRequest,
                        onConfirm = screenModel::confirmEhRemoveFromLibrary,
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
                    onConfirm = screenModel::setExcludedScanlators,
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
    private suspend fun performSearch(navigator: Navigator, query: String, global: Boolean) {
        if (global) {
            navigator.push(GlobalSearchScreen(query))
            return
        }

        if (navigator.size < 2) {
            return
        }

        when (val previousController = navigator.items[navigator.size - 2]) {
            is HomeScreen -> {
                navigator.pop()
                previousController.search(query)
            }
            is BrowseSourceScreen -> {
                navigator.pop()
                previousController.search(query)
            }
        }
    }

    /**
     * Performs a genre search using the provided genre name.
     *
     * @param genreName the search genre to the parent controller
     */
    private suspend fun performGenreSearch(navigator: Navigator, genreName: String, source: Source) {
        if (navigator.size < 2) {
            return
        }

        val previousController = navigator.items[navigator.size - 2]
        if (previousController is BrowseSourceScreen && source is HttpSource) {
            navigator.pop()
            previousController.searchGenre(genreName)
        } else {
            performSearch(navigator, genreName, global = false)
        }
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
private fun Manga.toEntryEditInfoUi() = EntryEditInfoUi(
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
private fun MangaScreenModel.State.Success.toSharedDetailsDialog(): EntryDetailsDialog? =
    when (val d = dialog) {
        is MangaScreenModel.Dialog.EditMangaInfo -> EntryDetailsDialog.EditInfo(
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
        MangaScreenModel.Dialog.FullCover -> EntryDetailsDialog.Cover
        is MangaScreenModel.Dialog.ManageSources -> EntryDetailsDialog.ManageSources(
            sources = d.sources,
            isOverridden = d.isOverridden,
        )
        MangaScreenModel.Dialog.TrackSheet -> EntryDetailsDialog.TrackSheet(
            entryId = manga.id,
            entryTitle = manga.title,
            sourceId = source.id,
            isNovel = false,
        )
        is MangaScreenModel.Dialog.DeleteChapters -> EntryDetailsDialog.DeleteChapters(d.chapters.map { it.id })
        else -> null
    }
