package reikai.presentation.novel.details

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.theme.TachiyomiTheme
import eu.kanade.presentation.util.Screen
import eu.kanade.presentation.util.isTabletUi
import eu.kanade.tachiyomi.ui.setting.SettingsScreen
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import eu.kanade.tachiyomi.util.system.copyToClipboard
import reikai.data.coil.NovelCover
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.withCustomInfo
import reikai.presentation.details.EntryDetailsContent
import reikai.presentation.details.EntryDetailsDialog
import reikai.presentation.details.EntryDetailsDialogHost
import reikai.presentation.details.EntryDetailsNavigation
import reikai.presentation.details.EntryDetailsScreenState
import reikai.presentation.details.EntryEditInfoUi
import reikai.presentation.details.EntryManageSourceInfo
import reikai.presentation.details.NovelEntryAdapter
import reikai.presentation.novel.browse.DuplicateNovelDialog
import reikai.presentation.novel.globalsearch.NovelGlobalSearchScreen
import reikai.presentation.novel.migrate.NovelMigrateHost
import reikai.presentation.novel.migrate.NovelMigrationSourcePickScreen
import reikai.presentation.novel.migrate.rememberNovelMigrateController
import reikai.presentation.novel.notes.NovelNotesScreen
import reikai.presentation.novel.reader.NovelReaderScreen
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen

/**
 * Light-novel details screen, the novel twin of `MangaScreen`. Builds a [NovelEntryAdapter] over the
 * novel model and delegates the whole body to the shared [EntryDetailsContent], so a details change is
 * written once and reaches both content types. Only novel-specific navigation (the reader, notes, the
 * page selector) and the per-type dialogs live here; the shared dialogs go through [EntryDetailsDialogHost].
 */
class NovelScreen(
    private val sourceId: String,
    private val novelUrl: String,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val screenModel = rememberScreenModel { NovelDetailsScreenModel(sourceId, novelUrl) }
        // Lifecycle-aware so collection pauses when the screen is not resumed (parity with MangaScreen).
        val state by screenModel.state.collectAsStateWithLifecycle()
        val adapter = remember(screenModel) { NovelEntryAdapter(screenModel) }
        val neutralState by adapter.state.collectAsStateWithLifecycle()

        when (val s = state) {
            NovelDetailsState.Loading -> LoadingScreen()
            is NovelDetailsState.Failed -> Scaffold(
                topBar = { AppBar(title = null, navigateUp = navigator::pop, scrollBehavior = it) },
            ) { padding -> EmptyScreen(message = s.message, modifier = Modifier.padding(padding)) }
            is NovelDetailsState.Loaded -> TachiyomiTheme(seedColor = s.seedColor) {
                // Back clears an active chapter selection before popping the screen (mirrors MangaScreen).
                BackHandler(enabled = s.selectionMode) { screenModel.clearSelection() }

                val onWebView: () -> Unit = {
                    s.novelWebUrl?.takeIf { it.isNotBlank() }?.let { url ->
                        navigator.push(WebViewScreen(url = url, initialTitle = s.sourceName, sourceId = null))
                    }
                }
                val onShare: () -> Unit = {
                    s.novelWebUrl?.takeIf { it.isNotBlank() }?.let { url ->
                        context.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, url)
                                },
                                null,
                            ),
                        )
                    }
                }

                (neutralState as? EntryDetailsScreenState.Loaded)?.let { loaded ->
                    EntryDetailsContent(
                        behavior = adapter,
                        state = loaded,
                        snackbarHostState = screenModel.snackbarHostState,
                        isTabletUi = isTabletUi(),
                        chapterSwipeStartAction = s.chapterSwipeStartAction,
                        chapterSwipeEndAction = s.chapterSwipeEndAction,
                        nav = EntryDetailsNavigation(
                            navigateUp = navigator::pop,
                            onOpenChapter = { chapterId ->
                                // Route to the chapter's own source (a unified-list row keeps its owning
                                // novelId). The All chip opens group scope; a source chip opens source scope.
                                s.chapters.firstOrNull { it.id == chapterId }?.let { ch ->
                                    navigator.push(
                                        NovelReaderScreen(
                                            ch.novelId,
                                            ch.id,
                                            sourceScoped = s.selectedSourceNovelId != null,
                                        ),
                                    )
                                }
                            },
                            onSearch = { query, _ -> navigator.push(NovelGlobalSearchScreen(query)) },
                            onTagSearch = { navigator.push(NovelGlobalSearchScreen(it)) },
                            onCopyTag = { context.copyToClipboard(it, it) },
                            onTracking = {
                                if (screenModel.hasLoggedInTrackers()) {
                                    screenModel.showTrackDialog()
                                } else {
                                    navigator.push(SettingsScreen(SettingsScreen.Destination.Tracking))
                                }
                            },
                            onEditNotes = {
                                navigator.push(NovelNotesScreen(s.novel.id, s.novel.title, s.novel.notes))
                            },
                            onOpenFilterSettings = screenModel::showChapterSettingsDialog,
                            // Novels keep Share in the action row too (matching LNReader) since the manga
                            // action row's smart-update interval button isn't available for novels yet.
                            onActionRowShare = s.novelWebUrl?.let { { onShare() } },
                            onToolbarShare = s.novelWebUrl?.let { { onShare() } },
                            onOpenWebView = s.novelWebUrl?.let { { onWebView() } },
                            // Migration only re-homes a library novel, so it shows only when favorited.
                            onMigrate = if (s.novel.favorite) {
                                { navigator.push(NovelMigrationSourcePickScreen(listOf(s.displayNovel.id))) }
                            } else {
                                null
                            },
                            onOpenPageSelector = screenModel::showPageSelectorDialog,
                        ),
                    )
                }

                EntryDetailsDialogHost(s.toSharedDetailsDialog(), adapter, screenModel::dismissDialog)
                NovelDetailsDialogs(s, screenModel)
            }
        }
    }
}

/** The novel dialogs that stay per-type (their data genuinely diverges); the shared ones go through
 *  [EntryDetailsDialogHost]. A `Screen` extension so the duplicate dialog can resolve the migrate controller. */
@Composable
private fun Screen.NovelDetailsDialogs(state: NovelDetailsState.Loaded, screenModel: NovelDetailsScreenModel) {
    val navigator = LocalNavigator.currentOrThrow
    val migrateController = rememberNovelMigrateController()
    when (val dialog = state.dialog) {
        is NovelDetailsDialog.ChangeCategory -> NovelCategoryDialog(
            dialog = dialog,
            onDismiss = screenModel::dismissDialog,
            onConfirm = screenModel::applyCategories,
        )
        is NovelDetailsDialog.DuplicateNovel -> DuplicateNovelDialog(
            duplicates = dialog.duplicates,
            sourceNames = dialog.sourceNames,
            sourceSites = dialog.sourceSites,
            onDismissRequest = screenModel::dismissDialog,
            onConfirm = screenModel::addFavoriteAnyway,
            onOpenNovel = { navigator.push(NovelScreen(it.source, it.url)) },
            onMigrate = { migrateController.start(current = it, target = state.novel) },
            groupIdByNovelId = dialog.groupIdByNovelId,
            onAddToGroup = { selectedIds: List<Long> ->
                screenModel.addToExistingGroup(selectedIds)
            }.takeIf { dialog.suggestGroup },
        )
        NovelDetailsDialog.ChapterSettings -> NovelChapterSettingsDialog(
            sorting = state.sorting,
            sortDescending = state.sortDescending,
            readFilter = state.readFilter,
            bookmarkedFilter = state.bookmarkedFilter,
            downloadedFilter = state.downloadedFilter,
            hideChapterTitles = state.hideChapterTitles,
            onDismiss = screenModel::dismissDialog,
            onSortChange = screenModel::setSortOrder,
            onFilterChange = screenModel::setFilters,
            onDisplayChange = screenModel::setHideChapterTitles,
            onSetAsDefault = screenModel::setChapterSettingsAsDefault,
            onReset = screenModel::resetChapterSettings,
        )
        NovelDetailsDialog.PageSelector -> NovelPageSelectorSheet(
            pages = state.pages,
            selectedIndex = state.pageIndex,
            onSelect = screenModel::selectPage,
            onDismiss = screenModel::dismissDialog,
        )
        else -> {}
    }
    NovelMigrateHost(migrateController)
}

// Map a novel dialog to the shared union for the dialogs both content types render (EntryDetailsDialogHost);
// the per-type ones (change-category, duplicate, chapter-settings, page-selector) stay in NovelDetailsDialogs.
private fun NovelDetailsState.Loaded.toSharedDetailsDialog(): EntryDetailsDialog? =
    when (val d = dialog) {
        NovelDetailsDialog.EditInfo -> EntryDetailsDialog.EditInfo(
            initial = displayNovel.withCustomInfo(customInfo).toEntryEditInfoUi(),
            source = novel.toEntryEditInfoUi(),
            seedColor = seedColor,
            coverModel = { url ->
                NovelCover(
                    url = url.ifBlank { null },
                    site = sourceUrl,
                    isNovelFavorite = novel.favorite,
                    lastModified = novel.coverLastModified,
                    novelId = novel.id,
                )
            },
        )
        NovelDetailsDialog.FullCover -> EntryDetailsDialog.Cover
        is NovelDetailsDialog.ManageSources -> EntryDetailsDialog.ManageSources(
            sources = d.sources.map { EntryManageSourceInfo(it.novelId, it.sourceName, it.chapterCount) },
            isOverridden = d.isOverridden,
        )
        NovelDetailsDialog.TrackSheet -> EntryDetailsDialog.TrackSheet(
            entryId = novel.id,
            entryTitle = novel.title,
            sourceId = null,
            isNovel = true,
        )
        is NovelDetailsDialog.DeleteChapters -> EntryDetailsDialog.DeleteChapters(d.chapters.map { it.id })
        else -> null
    }

/** Seed the shared edit-info dialog from a novel's effective (edited) values. */
private fun Novel.toEntryEditInfoUi() = EntryEditInfoUi(
    title = title,
    author = author.orEmpty(),
    artist = artist.orEmpty(),
    description = description.orEmpty(),
    genre = genre.orEmpty(),
    status = status,
    thumbnailUrl = thumbnailUrl.orEmpty(),
)
