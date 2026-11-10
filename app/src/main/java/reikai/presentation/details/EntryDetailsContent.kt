package reikai.presentation.details

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.manga.components.ChapterHeader
import eu.kanade.presentation.manga.components.GalleryInfoBox
import eu.kanade.presentation.manga.components.MangaBottomActionMenu
import eu.kanade.presentation.manga.components.MangaChapterListItem
import eu.kanade.presentation.manga.components.MissingChapterCountListItem
import eu.kanade.presentation.manga.components.PagePreviews
import eu.kanade.presentation.manga.components.SearchMetadataChips
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.launch
import mihon.app.di.appGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.KeyboardArrowDown
import reikai.data.local.LocalMoveResult
import reikai.domain.chapter.CopyMark
import reikai.domain.details.ReadButtonSide
import reikai.domain.download.whereDownloadOffered
import reikai.domain.recommendation.RelatedMangaCandidate
import reikai.presentation.components.ManageMergeSourceRow
import reikai.presentation.components.MergeSourceChips
import reikai.presentation.components.chapterRowDate
import reikai.presentation.components.readProgressLabel
import reikai.presentation.components.reportVolumeMove
import reikai.presentation.novel.details.novelPageText
import reikai.presentation.reader.chapterRowTitle
import reikai.presentation.reader.chapterTitleWords
import reikai.presentation.recents.NO_PROGRESS
import reikai.presentation.recommendation.RelatedMangaCarousel
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

/** Dim level for a hidden chapter row shown via "Show hidden chapters". */
private const val HIDDEN_CHAPTER_ALPHA = 0.4f

/**
 * Navigation and per-type actions the shared details body cannot express through [EntryDetailsBehavior]:
 * opening the reader, the per-type filter-settings sheet, and the manga-only capability taps (related
 * cards, page previews, gallery viewer). Each screen builds one and passes it in; a manga-only slot's
 * callback is simply absent for novels. The web actions are the body's own, from [EntryWebPage].
 */
data class EntryDetailsNavigation(
    val navigateUp: () -> Unit,
    val onOpenChapter: (chapterId: Long) -> Unit,
    /** Header title / author / artist tap: a global search, scoped to this entry's type. */
    val onGlobalSearch: (query: String) -> Unit,
    val onTagSearch: (String) -> Unit,
    /** A tag's Show only / Hide this tag, which set the library's tag filter; filled by the shared body. */
    val onFilterLibraryByTag: ((tag: String, show: Boolean) -> Unit)? = null,
    /** The Tracking tap with no tracker logged in; with one, the body opens the track sheet itself. */
    val onOpenTrackingSettings: () -> Unit,
    val onEditNotes: () -> Unit,
    val onOpenFilterSettings: () -> Unit,
    /** Library entries only: the body drops it for an entry outside the library ([forState]). */
    val onMigrate: (() -> Unit)? = null,
    /** Smart update's interval editor; the body drops it outside the library ([forState]), and null
     *  leaves the button doing nothing. */
    val onEditInterval: (() -> Unit)? = null,
    /** Opens the novel page/volume selector sheet; novel-only. */
    val onOpenPageSelector: (() -> Unit)? = null,
    /** Search and word count over the chapter text on the device; novel-only, since a manga chapter on
     *  disk is images. Offered only while that text is there: downloads, or a local series' files. */
    val onSearchText: (() -> Unit)? = null,
    val onWordCount: (() -> Unit)? = null,
    /** Writes the chapters on the device as an EPUB; novel-only, since manga keeps CBZ for that. */
    val onExport: (() -> Unit)? = null,
    // Manga capability taps.
    val onRelatedClick: (RelatedMangaCandidate) -> Unit = {},
    val onRelatedSeeAll: () -> Unit = {},
    val onOpenPagePreview: (Int) -> Unit = {},
    val onMorePreviews: () -> Unit = {},
    val onMetadataViewer: (() -> Unit)? = null,
    /** "Recommendations" overflow action; non-null only when related suggestions are placed in the menu. */
    val onRecommendations: (() -> Unit)? = null,
    /** "Source settings" overflow action; non-null only when the viewed source exposes any. */
    val onOpenSourceSettings: (() -> Unit)? = null,
    /** Hands the viewed source's download folder to a file manager. */
    val onOpenFolder: (() -> Unit)? = null,
    /** Header long-press: search the library, forced to this entry's type. The source row passes a
     *  `srcid:` query for its source rather than the name it shows. */
    val onLibrarySearch: (query: String) -> Unit,
    /** Header source row: browse that source. Null on a stub, where there is nothing to browse. */
    val onBrowseSource: (() -> Unit)? = null,
)

/**
 * The one details body both content types render, over the neutral [EntryDetailsScreenState.Loaded] and
 * [EntryDetailsBehavior]. Replaces the twin manga/novel screen impls: a body change now reaches both types.
 * The shared info group ([entryInfoItems]), toolbar, selection bar and chapter rows are driven by the
 * behaviour; per-type extras (merge chips, related carousel, page previews, gallery) render only when their
 * capability is present, and per-type navigation flows through [nav].
 */
@Composable
fun EntryDetailsContent(
    behavior: EntryDetailsBehavior,
    state: EntryDetailsScreenState.Loaded,
    snackbarHostState: SnackbarHostState,
    isTabletUi: Boolean,
    chapterSwipeStartAction: LibraryPreferences.ChapterSwipeAction,
    chapterSwipeEndAction: LibraryPreferences.ChapterSwipeAction,
    nav: EntryDetailsNavigation,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val web = rememberEntryWebActions(state.webPage, state.details.header.title)
    val navigator = LocalNavigator.currentOrThrow
    val libraryPreferences = remember { context.appGraph.reikaiLibraryPreferences }
    val libraryNav = nav.forState(state).copy(
        onFilterLibraryByTag = { tag, show ->
            scope.launch { navigator.filterLibraryByTag(libraryPreferences, tag, show) }
        },
    )
    var onlyCopies by rememberSaveable { mutableStateOf(false) }
    val copies = CopiesFilter(on = onlyCopies && !state.chapters.duplicates?.copies.isNullOrEmpty()) {
        onlyCopies = !onlyCopies
    }
    val filtered = if (copies.on) state.withOnlyCopies() else state
    val type = state.entryId.contentType
    val picks by behavior.volumePicking.picks(type).collectAsState()
    val savedVolumes by behavior.volumePicking.saved(type).collectAsState()
    val volumes = remember(filtered.chapters.items, picks, savedVolumes) {
        filtered.chapters.items.inVolumes(picks[state.entryId.rawId], savedVolumes)
    }
    val shown = volumes?.let { filtered.copy(chapters = filtered.chapters.copy(items = it.items)) } ?: filtered
    var reorderingVolumes by remember { mutableStateOf(false) }
    var volumeDialog by remember { mutableStateOf<VolumeDialog?>(null) }
    var deletingVolumes by remember { mutableStateOf<List<String>?>(null) }
    val ownLocal = state.ownsLocalFiles
    val extras = ChapterListExtras(
        copies = copies,
        volumes = volumes,
        onPickVolume = { behavior.volumePicking.pick(state.entryId, it) },
        onReorderVolumes = { reorderingVolumes = true }.takeIf { volumes != null },
        onAddVolumes = { volumeDialog = VolumeDialog.AddVolumes }.takeIf { ownLocal },
        onSplitOut = { volumeDialog = VolumeDialog.SplitOut }.takeIf { ownLocal && volumes != null },
        onRenameFiles = { volumeDialog = VolumeDialog.RenameFiles }.takeIf { ownLocal },
        onDeleteVolumes = { keys: List<String>? -> deletingVolumes = keys ?: volumes?.chips?.map { it.key } }
            .takeIf { ownLocal && volumes != null },
        onKeepDuplicates = { renames: List<Pair<String, String>> ->
            val series = volumes?.chips?.firstOrNull()?.series
            if (series !=
                null
            ) {
                renames.forEach { (key, label) -> behavior.volumePicking.rename(type, series, key, label) }
            }
        }.takeIf { ownLocal },
    )
    // Read here once for both types, as the description's image setting is.
    val uiPreferences = remember { context.appGraph.uiPreferences }
    val chrome = DetailsChrome(
        jumpsInMenu = uiPreferences.detailsScrollActionsInMenu.collectAsState().value,
        readButtonSide = uiPreferences.readButtonSide.collectAsState().value,
        onReadButtonDocked = uiPreferences.readButtonSide::set,
    )
    // Edit info saves the title before the screen shows it, so the files take the title it sent.
    var renameTitle by remember { mutableStateOf<String?>(null) }
    if (ownLocal) {
        LaunchedEffect(behavior.volumePicking) {
            behavior.volumePicking.fileRenameRequests.collect {
                renameTitle = it
                volumeDialog = VolumeDialog.RenameFiles
            }
        }
    }
    if (isTabletUi) {
        EntryDetailsLargeContent(
            behavior,
            shown,
            snackbarHostState,
            chapterSwipeStartAction,
            chapterSwipeEndAction,
            libraryNav,
            web,
            extras,
            chrome,
        )
    } else {
        EntryDetailsSmallContent(
            behavior,
            shown,
            snackbarHostState,
            chapterSwipeStartAction,
            chapterSwipeEndAction,
            libraryNav,
            web,
            extras,
            chrome,
        )
    }
    if (reorderingVolumes && volumes != null) {
        val series = volumes.chips.first().series
        ReorderVolumesDialog(
            volumes = volumes.chips,
            canRenameFiles = ownLocal,
            onDismissRequest = { renameFiles ->
                reorderingVolumes = false
                if (renameFiles) volumeDialog = VolumeDialog.RenameFiles
            },
            onReorder = { keys -> behavior.volumePicking.reorder(type, series, keys, behavior::refresh) },
            onRename = { key, label -> behavior.volumePicking.rename(type, series, key, label) },
        )
    }
    when (volumeDialog) {
        VolumeDialog.AddVolumes -> AddVolumesDialog(
            title = state.details.header.title,
            load = { behavior.volumePicking.library(type).filterNot { it.id == state.entryId.rawId } },
            onDismissRequest = { volumeDialog = null },
            onConfirm = { others ->
                volumeDialog = null
                scope.launch {
                    val own = behavior.volumePicking.series(state.entryId) ?: return@launch
                    val move = behavior.volumePicking.addVolumes(type, own, others)
                    behavior.refresh()
                    val message = context.stringResource(MR.strings.local_volumes_combined, own.title)
                    snackbarHostState.reportVolumeMove(context, move, message)
                    behavior.refresh()
                }
            },
        )
        VolumeDialog.SplitOut -> volumes?.let { view ->
            SplitOutDialog(
                title = state.details.header.title,
                volumes = view.chips,
                onDismissRequest = { volumeDialog = null },
                onConfirm = { keys, title ->
                    volumeDialog = null
                    scope.launch {
                        val own = behavior.volumePicking.series(state.entryId) ?: return@launch
                        val move = behavior.volumePicking.splitOut(type, own, keys, title)
                        behavior.refresh()
                        val message = context.stringResource(MR.strings.local_volume_split_out, title)
                        snackbarHostState.reportVolumeMove(context, move, message)
                        behavior.refresh()
                    }
                },
            )
        }
        VolumeDialog.RenameFiles -> RenameFilesDialog(
            title = renameTitle ?: state.details.header.title,
            volumes = volumes?.chips.orEmpty(),
            plan = { title, labels -> behavior.volumePicking.renamePlan(state.entryId, title, labels) },
            onDismissRequest = {
                volumeDialog = null
                renameTitle = null
            },
            onConfirm = { series, names, newTitle ->
                volumeDialog = null
                renameTitle = null
                scope.launch {
                    val move = behavior.volumePicking.renameFiles(type, series, names, newTitle)
                    behavior.refresh()
                    snackbarHostState.reportVolumeMove(
                        context,
                        move,
                        context.stringResource(MR.strings.local_files_renamed),
                    )
                    behavior.refresh()
                }
            },
        )
        null -> {}
    }
    deletingVolumes?.let { keys ->
        DeleteVolumesDialog(
            volumes = volumes?.chips.orEmpty().filter { it.key in keys },
            onDismissRequest = { deletingVolumes = null },
            onConfirm = { picked ->
                deletingVolumes = null
                scope.launch {
                    val result = behavior.volumePicking.deleteVolumes(state.entryId, picked)
                    behavior.refresh()
                    if (result is LocalMoveResult.Failed) context.toast(MR.strings.delete_volumes_failed)
                }
            },
        )
    }
}

/** Whether the list shows only chapters with copies, from the duplicate count under the chapter header. */
@Immutable
private data class CopiesFilter(val on: Boolean, val toggle: () -> Unit)

/** What the chapter list is narrowed to above its rows: the copies filter, and a local series' volumes. */
@Immutable
private data class ChapterListExtras(
    val copies: CopiesFilter,
    val volumes: VolumeView?,
    val onPickVolume: (String?) -> Unit,
    val onReorderVolumes: (() -> Unit)?,
    val onAddVolumes: (() -> Unit)?,
    val onSplitOut: (() -> Unit)?,
    val onRenameFiles: (() -> Unit)?,
    // Null keys offers every volume; the duplicate notice offers only the ones that clash.
    val onDeleteVolumes: ((keys: List<String>?) -> Unit)?,
    val onKeepDuplicates: ((renames: List<Pair<String, String>>) -> Unit)?,
)

/** Where the details chrome sits: the scroll actions' place and the Start/Resume button's side. */
@Immutable
private data class DetailsChrome(
    val jumpsInMenu: Boolean,
    val readButtonSide: ReadButtonSide,
    val onReadButtonDocked: (ReadButtonSide) -> Unit,
)

private enum class VolumeDialog { AddVolumes, SplitOut, RenameFiles }

// Each set's copies kept together, in the list's own order, since a date sort can part them. A marked
// chapter stays too, so a Never skip can be found and cleared.
private fun EntryDetailsScreenState.Loaded.withOnlyCopies() = copy(
    chapters = chapters.copy(
        items = chapters.items.filterIsInstance<EntryChapterListItem.Chapter>()
            .filter { chapters.duplicates?.run { it.id in copies || it.id in marks } == true }
            .groupBy { it.chapterNumber }.values.flatten(),
    ),
)

/** [this] with the actions that only act on a library entry dropped while [state]'s entry is outside it. */
internal fun EntryDetailsNavigation.forState(state: EntryDetailsScreenState.Loaded) = copy(
    onMigrate = onMigrate?.takeIf { state.details.favorite },
    onEditInterval = onEditInterval?.takeIf { state.details.favorite },
)

/** The Tracking button: the track sheet once a tracker is logged in, else tracker settings to log in to one. */
internal fun trackingTap(
    state: EntryDetailsScreenState.Loaded,
    behavior: EntryDetailsBehavior,
    nav: EntryDetailsNavigation,
): () -> Unit = if (state.details.hasLoggedInTrackers) behavior::showTrackDialog else nav.onOpenTrackingSettings

@Composable
private fun EntryDetailsSmallContent(
    behavior: EntryDetailsBehavior,
    state: EntryDetailsScreenState.Loaded,
    snackbarHostState: SnackbarHostState,
    chapterSwipeStartAction: LibraryPreferences.ChapterSwipeAction,
    chapterSwipeEndAction: LibraryPreferences.ChapterSwipeAction,
    nav: EntryDetailsNavigation,
    web: EntryWebActions,
    extras: ChapterListExtras,
    chrome: DetailsChrome,
) {
    val listState = rememberLazyListState()
    val jumps = rememberEntryChapterJumps(listState, state)
    val haptic = LocalHapticFeedback.current
    val onAddToLibrary = {
        behavior.toggleFavorite()
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    EntryDetailsScaffold(
        listState = listState,
        snackbarHostState = snackbarHostState,
        isAnySelected = state.selectionMode,
        isRefreshing = state.isRefreshing,
        onRefresh = behavior::refresh,
        onCancelSelection = behavior::clearSelection,
        fabVisible = state.resumeChapterId != null && !state.selectionMode,
        fabIsResume = state.hasStarted,
        onFabClick = { state.resumeChapterId?.let(nav.onOpenChapter) },
        fabSide = chrome.readButtonSide,
        onFabDocked = chrome.onReadButtonDocked,
        topBar = { titleAlpha, backgroundAlpha ->
            EntryDetailsToolbar(state, behavior, nav, web, extras, jumps, chrome, titleAlpha, backgroundAlpha)
        },
        bottomActionMenu = { EntryDetailsSelectionBar(state, behavior, fillFraction = 1f) },
    ) { appBarPadding ->
        entryInfoBlock(
            state,
            behavior,
            nav,
            web,
            isTabletUi = false,
            appBarPadding = appBarPadding,
            onAddToLibrary = onAddToLibrary,
        )
        if (state.isMerged) mergeSourceChipsItem(state, behavior)
        state.capabilities.mangaRelatedCarousel?.let { relatedCarouselItem(it, nav, topDivider = true) }
        state.capabilities.mangaPagePreviews?.let { pagePreviewsItem(it, nav) }
        chapterHeaderItem(state, nav, extras.copies)
        extras.volumes?.let {
            volumeChipsItem(
                it,
                extras.onPickVolume,
                extras.onKeepDuplicates,
                extras.onDeleteVolumes?.let { d ->
                    { keys -> d(keys) }
                },
            )
        }
        entryChapterItems(state, behavior, chapterSwipeStartAction, chapterSwipeEndAction, nav.onOpenChapter)
    }
}

@Composable
private fun EntryDetailsLargeContent(
    behavior: EntryDetailsBehavior,
    state: EntryDetailsScreenState.Loaded,
    snackbarHostState: SnackbarHostState,
    chapterSwipeStartAction: LibraryPreferences.ChapterSwipeAction,
    chapterSwipeEndAction: LibraryPreferences.ChapterSwipeAction,
    nav: EntryDetailsNavigation,
    web: EntryWebActions,
    extras: ChapterListExtras,
    chrome: DetailsChrome,
) {
    val chapterListState = rememberLazyListState()
    val jumps = rememberEntryChapterJumps(chapterListState, state)
    val haptic = LocalHapticFeedback.current
    val onAddToLibrary = {
        behavior.toggleFavorite()
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    EntryDetailsTwoPaneScaffold(
        chapterListState = chapterListState,
        snackbarHostState = snackbarHostState,
        isAnySelected = state.selectionMode,
        isRefreshing = state.isRefreshing,
        onRefresh = behavior::refresh,
        onCancelSelection = behavior::clearSelection,
        fabVisible = state.resumeChapterId != null && !state.selectionMode,
        fabIsResume = state.hasStarted,
        onFabClick = { state.resumeChapterId?.let(nav.onOpenChapter) },
        fabSide = chrome.readButtonSide,
        onFabDocked = chrome.onReadButtonDocked,
        topBar = { modifier ->
            EntryDetailsToolbar(state, behavior, nav, web, extras, jumps, chrome, { 1f }, { 1f }, modifier)
        },
        bottomActionMenu = { modifier -> EntryDetailsSelectionBar(state, behavior, fillFraction = 0.5f, modifier) },
        startContent = { appBarPadding ->
            entryInfoBlock(
                state,
                behavior,
                nav,
                web,
                isTabletUi = true,
                appBarPadding = appBarPadding,
                onAddToLibrary = onAddToLibrary,
            )
            state.capabilities.mangaPagePreviews?.let { pagePreviewsItem(it, nav) }
        },
        endContent = {
            state.capabilities.mangaRelatedCarousel?.let { relatedCarouselItem(it, nav, topDivider = false) }
            if (state.isMerged) mergeSourceChipsItem(state, behavior)
            chapterHeaderItem(state, nav, extras.copies)
            extras.volumes?.let {
                volumeChipsItem(
                    it,
                    extras.onPickVolume,
                    extras.onKeepDuplicates,
                    extras.onDeleteVolumes?.let { d ->
                        { keys -> d(keys) }
                    },
                )
            }
            entryChapterItems(state, behavior, chapterSwipeStartAction, chapterSwipeEndAction, nav.onOpenChapter)
        },
    )
}

@Composable
private fun EntryDetailsToolbar(
    state: EntryDetailsScreenState.Loaded,
    behavior: EntryDetailsBehavior,
    nav: EntryDetailsNavigation,
    web: EntryWebActions,
    extras: ChapterListExtras,
    jumps: ChapterJumps,
    chrome: DetailsChrome,
    titleAlphaProvider: () -> Float,
    backgroundAlphaProvider: () -> Float,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val openOwnFolder: () -> Unit = {
        scope.launch { openDownloadFolder(context, behavior.volumePicking.seriesFolder(state.entryId)) }
    }
    EntryToolbar(
        modifier = modifier,
        title = state.details.header.title,
        hasFilters = state.hasActiveFilter,
        navigateUp = nav.navigateUp,
        onClickFilter = nav.onOpenFilterSettings,
        onClickRefresh = behavior::refresh,
        onClickEditCategory = { behavior.showChangeCategoryDialog() }.takeIf { state.details.favorite },
        onClickEditInfo = { behavior.showEditInfoDialog() }.takeIf { state.details.favorite },
        onClickEditNotes = nav.onEditNotes,
        onClickShare = web.share,
        onClickManageSources = { behavior.showManageSourcesDialog() }.takeIf { state.isMerged },
        onClickMigrate = nav.onMigrate,
        onClickDownload = if (state.chaptersDownloadable) behavior::runDownloadAction else null,
        onClickMetadataViewer = nav.onMetadataViewer,
        onClickSourceSettings = nav.onOpenSourceSettings,
        // Hidden with nothing to clear, and on a source whose downloads are the series itself.
        onClickClearDownloads = { behavior.showClearDownloadsDialog() }.takeIf {
            state.chaptersDownloadable && state.hasViewedDownloads
        },
        // A local series opens its own folder, where its files are; others their downloads, once there are some.
        onClickOpenFolder = if (state.ownsLocalFiles) {
            openOwnFolder
        } else {
            nav.onOpenFolder?.takeIf { state.chaptersDownloadable && state.hasViewedDownloads }
        },
        // A local series' text is on the device without being a download, so it searches and counts too.
        onClickSearchText = nav.onSearchText?.takeIf { state.textOnDevice },
        onClickWordCount = nav.onWordCount?.takeIf { state.textOnDevice },
        onClickExport = nav.onExport,
        onClickReorderVolumes = extras.onReorderVolumes,
        onClickAddVolumes = extras.onAddVolumes,
        onClickSplitOut = extras.onSplitOut,
        onClickRenameFiles = extras.onRenameFiles,
        onClickDeleteVolumes = extras.onDeleteVolumes?.let { delete -> { delete(null) } },
        onClickRecommendations = nav.onRecommendations,
        jumps = jumps,
        jumpsInMenu = chrome.jumpsInMenu,
        onHide = behavior::hideSelected,
        onUnhide = behavior::unhideSelected,
        onToggleShowHidden = behavior::toggleShowHidden,
        onCorrectChapterNumber = { behavior.showChapterNumberDialog() }.takeIf { state.selection.size == 1 },
        copyMarkActions = state.chapters.duplicates?.let { duplicates ->
            val selectedMarks = state.selection.map { duplicates.marks[it] }

            // Each closes the selection afterwards, as Hide does.
            fun onSelected(write: DuplicateMarking.(List<MarkableChapter>) -> Unit) = {
                behavior.duplicateMarking.write(behavior.selectedMarkable())
                behavior.clearSelection()
            }
            CopyMarkActions(
                neverSkip = onSelected(DuplicateMarking::neverSkip).takeIf {
                    selectedMarks.any { it != CopyMark.NeverSkip }
                },
                markAsCopies = onSelected(DuplicateMarking::markAsCopies).takeIf { state.selection.size > 1 },
                clear = onSelected(DuplicateMarking::clear).takeIf { selectedMarks.any { it != null } },
            )
        },
        showHidden = state.chapters.showHidden,
        hasHiddenChapters = state.chapters.hasHiddenChapters,
        allHiddenSelected = state.chapters.showHidden && state.selection.isNotEmpty() &&
            state.selection.all { it in state.chapters.hiddenChapterIds },
        actionModeCounter = state.selection.size,
        onCancelActionMode = behavior::clearSelection,
        onSelectAll = behavior::selectAll,
        onInvertSelection = behavior::invertSelection,
        titleAlphaProvider = titleAlphaProvider,
        backgroundAlphaProvider = backgroundAlphaProvider,
    )
}

@Composable
private fun EntryDetailsSelectionBar(
    state: EntryDetailsScreenState.Loaded,
    behavior: EntryDetailsBehavior,
    fillFraction: Float,
    modifier: Modifier = Modifier,
) {
    val selected = state.selectedChapters
    val offers = state.selectionOffers
    MangaBottomActionMenu(
        visible = selected.isNotEmpty(),
        modifier = modifier.fillMaxWidth(fillFraction),
        onBookmarkClicked = { behavior.bookmarkSelected(true) }.takeIf { offers.bookmark },
        onRemoveBookmarkClicked = { behavior.bookmarkSelected(false) }.takeIf { offers.removeBookmark },
        onMarkAsReadClicked = { behavior.markSelectedRead(true) }.takeIf { offers.markRead },
        onMarkAsUnreadClicked = { behavior.markSelectedRead(false) }.takeIf { offers.markUnread },
        onMarkPreviousAsReadClicked = { behavior.markPreviousRead() }.takeIf { selected.size == 1 },
        // A local or stub source has nothing to fetch, which upstream says by passing no download action.
        onDownloadClicked = { behavior.downloadSelected() }.takeIf { state.chaptersDownloadable && offers.download },
        onDeleteClicked = { behavior.deleteSelected() }.takeIf { offers.delete },
    )
}

private fun LazyListScope.entryInfoBlock(
    state: EntryDetailsScreenState.Loaded,
    behavior: EntryDetailsBehavior,
    nav: EntryDetailsNavigation,
    web: EntryWebActions,
    isTabletUi: Boolean,
    appBarPadding: Dp,
    onAddToLibrary: () -> Unit,
) {
    val gallery = state.capabilities.mangaGallery
    entryInfoItems(
        isTabletUi = isTabletUi,
        appBarPadding = appBarPadding,
        state = state.details,
        onCoverClick = behavior::showCoverDialog,
        onGlobalSearch = nav.onGlobalSearch,
        librarySearch = nav.onLibrarySearch,
        onBrowseSource = nav.onBrowseSource,
        onAddToLibraryClicked = onAddToLibrary,
        onTrackingClicked = trackingTap(state, behavior, nav),
        onEditCategory = { behavior.showChangeCategoryDialog() }.takeIf { state.details.favorite },
        onEditIntervalClicked = nav.onEditInterval,
        onWebViewClicked = web.openWebView,
        onWebViewLongClicked = web.copyUrl,
        onTagSearch = nav.onTagSearch,
        onFilterLibraryByTag = nav.onFilterLibraryByTag,
        onCopyTagToClipboard = web.copyTag,
        onEditNotes = nav.onEditNotes,
        // Namespaced, grouped tag chips for the active source's gallery metadata (or its namespaced genre).
        searchMetadataChips = gallery?.let { SearchMetadataChips(it.metadata, it.sourceId, it.rawGenre, it.tagQuery) },
        // Per-source gallery-info card above the description, once the metadata object has loaded.
        aboveDescription = gallery?.metadata?.let { meta ->
            {
                GalleryInfoBox(
                    metadata = meta,
                    onMoreInfoClick = nav.onMetadataViewer,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
    )
}

private fun LazyListScope.mergeSourceChipsItem(
    state: EntryDetailsScreenState.Loaded,
    behavior: EntryDetailsBehavior,
) {
    item(key = "rk-merge-source-chips") {
        MergeSourceChips(
            sources = state.mergeSources.map { ManageMergeSourceRow(id = it.id, sourceName = it.sourceName) },
            selectedId = state.selectedSourceId,
            onSelect = behavior::selectSource,
            onSplitSource = { behavior.mergeActions.splitSources(listOf(it)) },
        )
    }
}

private fun LazyListScope.relatedCarouselItem(
    capability: MangaRelatedCarouselCapability,
    nav: EntryDetailsNavigation,
    topDivider: Boolean,
) {
    item(key = "rk-related-carousel") {
        RelatedMangaCarousel(
            items = capability.items,
            loading = capability.isLoading,
            totalCount = capability.totalCount,
            onClick = nav.onRelatedClick,
            onSeeAll = nav.onRelatedSeeAll,
            topDivider = topDivider,
        )
    }
}

private fun LazyListScope.pagePreviewsItem(
    capability: MangaPagePreviewsCapability,
    nav: EntryDetailsNavigation,
) {
    item(key = "rk-page-previews") {
        PagePreviews(
            pagePreviewState = capability.state,
            onOpenPage = nav.onOpenPagePreview,
            onMorePreviewsClicked = nav.onMorePreviews,
            rowCount = capability.rowCount,
        )
    }
}

private fun LazyListScope.chapterHeaderItem(
    state: EntryDetailsScreenState.Loaded,
    nav: EntryDetailsNavigation,
    copies: CopiesFilter,
) {
    item(key = "entry-chapter-header") {
        Column {
            ChapterHeader(
                enabled = !state.selectionMode,
                chapterCount = state.chapters.items.count { it is EntryChapterListItem.Chapter },
                missingChapterCount = state.chapters.missingChapterCount,
                onClick = nav.onOpenFilterSettings,
            )
            // Only with "Show duplicate chapters the reader skips" on, so the rule's work is visible at a glance.
            state.chapters.duplicates?.copies?.size?.takeIf { it > 0 }?.let { count ->
                val action = MR.strings.run {
                    if (copies.on) chapter_duplicate_show_all else chapter_duplicate_show_only
                }
                Text(
                    text = stringResource(MR.strings.chapter_duplicate_copies_count, count) + " • " +
                        stringResource(action),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !state.selectionMode, onClick = copies.toggle)
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            // A paged novel's page bar sits under the header, opening the page selector. The
            // count above is the current page's, so the paged scope stays visible (sort/filter are paged).
            state.capabilities.novelPageSelector?.let { page ->
                NovelPageBar(
                    text = novelPageText(page.pages[page.pageIndex], page.pages.size),
                    isLoading = page.isPageLoading,
                    enabled = !state.selectionMode,
                    onClick = { nav.onOpenPageSelector?.invoke() },
                )
            }
        }
    }
}

private fun LazyListScope.entryChapterItems(
    state: EntryDetailsScreenState.Loaded,
    behavior: EntryDetailsBehavior,
    chapterSwipeStartAction: LibraryPreferences.ChapterSwipeAction,
    chapterSwipeEndAction: LibraryPreferences.ChapterSwipeAction,
    onOpenChapter: (Long) -> Unit,
) {
    // The row lambdas capture these and the typed chapter below, never the whole state, so a row whose
    // own values did not change skips recomposing when another row changes.
    val selectionMode = state.selectionMode
    val chaptersDownloadable = state.chaptersDownloadable
    items(
        items = state.chapters.items,
        key = { item ->
            when (item) {
                is EntryChapterListItem.Missing -> "missing-${item.id}"
                is EntryChapterListItem.Volume -> "volume-${item.key}"
                is EntryChapterListItem.Chapter -> "chapter-${item.id}"
            }
        },
        contentType = { if (it is EntryChapterListItem.Volume) "entry-volume" else "entry-chapter" },
    ) { item ->
        when (item) {
            is EntryChapterListItem.Missing -> MissingChapterCountListItem(count = item.count)
            is EntryChapterListItem.Volume -> VolumeHeader(
                volume = item,
                onSelect = { behavior.toggleChapterBlock(item.chapterIds) },
                onMarkRead = { read -> behavior.markChaptersRead(item.chapterIds, read) },
                coverOf = { behavior.volumePicking.cover(state.entryId.contentType, item.series, item.key) },
            )
            is EntryChapterListItem.Chapter -> {
                // Typed as the stable Chapter, so the lambdas compare it by value rather than by instance.
                val chapter: EntryChapterListItem.Chapter = item
                val haptic = LocalHapticFeedback.current
                val context = LocalContext.current
                val titleWords = remember(context) { context.chapterTitleWords() }
                val isSelected = chapter.id in state.selection
                val offersDownload = state.rowOffersDownload(chapter.id, chapter.downloadState)
                MangaChapterListItem(
                    modifier = Modifier.alpha(
                        if (chapter.id in state.chapters.hiddenChapterIds) HIDDEN_CHAPTER_ALPHA else 1f,
                    ),
                    title = chapterRowTitle(
                        chapter.name,
                        chapter.chapterNumber,
                        state.showChapterNumberOnly,
                        titleWords,
                    ),
                    date = chapterRowDate(chapter.dateUpload, state.chapters.undatedChapterDate),
                    readProgress = readProgressLabel(chapter.progress),
                    scanlator = duplicateSubtitle(chapter.subtitle, chapter.id, state.chapters.duplicates),
                    read = chapter.read,
                    bookmark = chapter.bookmark,
                    selected = isSelected,
                    downloadIndicatorEnabled = !selectionMode && chaptersDownloadable,
                    downloadStateProvider = { chapter.downloadState },
                    downloadProgressProvider = when (val progress = behavior.downloadProgress) {
                        is EntryDownloadProgress.Live -> {
                            { progress.percentOf(chapter.id) }
                        }
                        EntryDownloadProgress.Unsupported -> NO_PROGRESS
                    },
                    chapterSwipeStartAction = chapterSwipeStartAction.whereDownloadOffered(offersDownload),
                    chapterSwipeEndAction = chapterSwipeEndAction.whereDownloadOffered(offersDownload),
                    onLongClick = {
                        behavior.toggleSelection(chapter.id, true)
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                    onClick = {
                        if (selectionMode) behavior.toggleSelection(chapter.id, false) else onOpenChapter(chapter.id)
                    },
                    onDownloadClick = if (chaptersDownloadable) {
                        { behavior.onChapterDownloadAction(chapter.id, it) }
                    } else {
                        null
                    },
                    onChapterSwipe = { behavior.chapterSwipe(chapter.id, it) },
                    downloadIndicatorShown = offersDownload,
                    onNumberHintClick = if (chapter.numberHinted) {
                        { behavior.showChapterNumberDialog(chapter.id) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

/** The jumps over this screen's chapter rows, toasting when the Start/Resume chapter is not among them. */
@Composable
private fun rememberEntryChapterJumps(listState: LazyListState, state: EntryDetailsScreenState.Loaded): ChapterJumps {
    val context = LocalContext.current
    return rememberChapterJumps(listState, state.chapters.items, state.resumeChapterId) {
        context.toast(MR.strings.no_unread_chapter_in_list)
    }
}

/** Compact row naming a paged novel's current page under the chapter header; opens the page selector sheet. */
@Composable
private fun NovelPageBar(
    text: String,
    isLoading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        }
        Icon(
            imageVector = MaterialSymbols.Rounded.KeyboardArrowDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

/** A row's subtitle with the number of copies skip-duplicate folds it into, when the list shows them. */
@Composable
private fun duplicateSubtitle(subtitle: String?, chapterId: Long, duplicates: DuplicateMarks?): String? {
    val mark = when (duplicates?.marks?.get(chapterId)) {
        CopyMark.NeverSkip -> stringResource(MR.strings.chapter_never_skip)
        is CopyMark.Group -> stringResource(MR.strings.chapter_marked_copy)
        null -> null
    }
    val copies = duplicates?.copies?.get(chapterId)?.let { stringResource(MR.strings.chapter_duplicate_copies, it) }
    return listOfNotNull(subtitle, copies, mark).joinToString(" • ").ifEmpty { null }
}
