package eu.kanade.presentation.manga

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastAll
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastMap
import eu.kanade.presentation.components.relativeDateText
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.presentation.manga.components.ChapterHeader
import eu.kanade.presentation.manga.components.ExpandableMangaDescription
import eu.kanade.presentation.manga.components.GalleryInfoBox // RK
import eu.kanade.presentation.manga.components.MangaBottomActionMenu
import eu.kanade.presentation.manga.components.MangaChapterListItem
import eu.kanade.presentation.manga.components.MissingChapterCountListItem
import eu.kanade.presentation.manga.components.PagePreviews // RK
import eu.kanade.presentation.manga.components.SearchMetadataChips // RK
import eu.kanade.presentation.util.formatChapterNumber
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.source.getNameForMangaInfo
import eu.kanade.tachiyomi.ui.manga.ChapterList
import eu.kanade.tachiyomi.ui.manga.MangaScreenModel
import eu.kanade.tachiyomi.ui.manga.PagePreviewState // RK
import eu.kanade.tachiyomi.util.system.copyToClipboard
import reikai.domain.recommendation.RelatedMangaCandidate // RK
import reikai.presentation.details.EntryActionRow // RK
import reikai.presentation.details.EntryDetailsScaffold // RK
import reikai.presentation.details.EntryDetailsTwoPaneScaffold // RK
import reikai.presentation.details.EntryDetailsUiState // RK
import reikai.presentation.details.EntryInfoBox // RK
import reikai.presentation.details.EntryToolbar // RK
import reikai.presentation.details.entryInfoItems // RK
import reikai.presentation.details.toEntryHeader // RK
import reikai.presentation.manga.MergeSourceChips // RK
import reikai.presentation.recommendation.RelatedMangaCarousel // RK
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.service.missingChaptersCount
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.withCustomInfo // RK
import tachiyomi.domain.source.model.StubSource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.source.local.isLocal
import java.time.Instant

@Composable
fun MangaScreen(
    state: MangaScreenModel.State.Success,
    snackbarHostState: SnackbarHostState,
    nextUpdate: Instant?,
    isTabletUi: Boolean,
    chapterSwipeStartAction: LibraryPreferences.ChapterSwipeAction,
    chapterSwipeEndAction: LibraryPreferences.ChapterSwipeAction,
    navigateUp: () -> Unit,
    onChapterClicked: (Chapter) -> Unit,
    onDownloadChapter: ((List<ChapterList.Item>, ChapterDownloadAction) -> Unit)?,
    onAddToLibraryClicked: () -> Unit,
    onWebViewClicked: (() -> Unit)?,
    onWebViewLongClicked: (() -> Unit)?,
    onTrackingClicked: () -> Unit,

    // For tags menu
    onTagSearch: (String) -> Unit,

    onFilterButtonClicked: () -> Unit,
    onRefresh: () -> Unit,
    onContinueReading: () -> Unit,
    onSearch: (query: String, global: Boolean) -> Unit,

    // For cover dialog
    onCoverClicked: () -> Unit,

    // For top action menu
    onShareClicked: (() -> Unit)?,
    onDownloadActionClicked: ((DownloadAction) -> Unit)?,
    onEditCategoryClicked: (() -> Unit)?,
    onEditInfoClicked: (() -> Unit)?, // RK
    onEditFetchIntervalClicked: (() -> Unit)?,
    onMigrateClicked: (() -> Unit)?,
    onEditNotesClicked: () -> Unit,
    // RK: merge-group source management; null when the manga is not part of a group
    onManageSourcesClicked: (() -> Unit)? = null,
    // RK: opens the gallery metadata viewer (adult/metadata sources only)
    onMetadataViewerClicked: (() -> Unit)? = null,
    // RK: hide/unhide the selected chapters + toggle temporarily showing hidden ones
    onHideSelected: (() -> Unit)? = null,
    onUnhideSelected: (() -> Unit)? = null,
    onToggleShowHidden: (() -> Unit)? = null,
    // RK: tap a page-preview thumbnail -> open the reader at that page
    onOpenPagePreview: (Int) -> Unit = {},
    // RK: open the full-screen page-preview gallery
    onMorePreviewsClicked: () -> Unit = {},
    onSelectSource: (Long?) -> Unit = {},
    onSplitSource: (Long) -> Unit = {},
    // RK: related-mangas carousel card tap
    onRelatedClick: (RelatedMangaCandidate) -> Unit = {},
    // RK: related-mangas carousel "See all" -> browse grid
    onRelatedSeeAll: () -> Unit = {},

    // For bottom action menu
    onMultiBookmarkClicked: (List<Chapter>, bookmarked: Boolean) -> Unit,
    onMultiMarkAsReadClicked: (List<Chapter>, markAsRead: Boolean) -> Unit,
    onMarkPreviousAsReadClicked: (Chapter) -> Unit,
    onMultiDeleteClicked: (List<Chapter>) -> Unit,

    // For chapter swipe
    onChapterSwipe: (ChapterList.Item, LibraryPreferences.ChapterSwipeAction) -> Unit,

    // Chapter selection
    onChapterSelected: (ChapterList.Item, Boolean, Boolean) -> Unit,
    onAllChapterSelected: (Boolean) -> Unit,
    onInvertSelection: () -> Unit,
) {
    val context = LocalContext.current
    val onCopyTagToClipboard: (tag: String) -> Unit = {
        if (it.isNotEmpty()) {
            context.copyToClipboard(it, it)
        }
    }

    if (!isTabletUi) {
        MangaScreenSmallImpl(
            state = state,
            snackbarHostState = snackbarHostState,
            nextUpdate = nextUpdate,
            chapterSwipeStartAction = chapterSwipeStartAction,
            chapterSwipeEndAction = chapterSwipeEndAction,
            navigateUp = navigateUp,
            onChapterClicked = onChapterClicked,
            onDownloadChapter = onDownloadChapter,
            onAddToLibraryClicked = onAddToLibraryClicked,
            onWebViewClicked = onWebViewClicked,
            onWebViewLongClicked = onWebViewLongClicked,
            onTrackingClicked = onTrackingClicked,
            onTagSearch = onTagSearch,
            onCopyTagToClipboard = onCopyTagToClipboard,
            onFilterClicked = onFilterButtonClicked,
            onRefresh = onRefresh,
            onContinueReading = onContinueReading,
            onSearch = onSearch,
            onCoverClicked = onCoverClicked,
            onShareClicked = onShareClicked,
            onDownloadActionClicked = onDownloadActionClicked,
            onEditCategoryClicked = onEditCategoryClicked,
            onEditInfoClicked = onEditInfoClicked, // RK
            onEditIntervalClicked = onEditFetchIntervalClicked,
            onMigrateClicked = onMigrateClicked,
            onEditNotesClicked = onEditNotesClicked,
            onManageSourcesClicked = onManageSourcesClicked,
            onMetadataViewerClicked = onMetadataViewerClicked,
            onHideSelected = onHideSelected,
            onUnhideSelected = onUnhideSelected,
            onToggleShowHidden = onToggleShowHidden,
            onOpenPagePreview = onOpenPagePreview,
            onMorePreviewsClicked = onMorePreviewsClicked,
            onSelectSource = onSelectSource,
            onSplitSource = onSplitSource,
            onRelatedClick = onRelatedClick,
            onRelatedSeeAll = onRelatedSeeAll,
            onMultiBookmarkClicked = onMultiBookmarkClicked,
            onMultiMarkAsReadClicked = onMultiMarkAsReadClicked,
            onMarkPreviousAsReadClicked = onMarkPreviousAsReadClicked,
            onMultiDeleteClicked = onMultiDeleteClicked,
            onChapterSwipe = onChapterSwipe,
            onChapterSelected = onChapterSelected,
            onAllChapterSelected = onAllChapterSelected,
            onInvertSelection = onInvertSelection,
        )
    } else {
        MangaScreenLargeImpl(
            state = state,
            snackbarHostState = snackbarHostState,
            chapterSwipeStartAction = chapterSwipeStartAction,
            chapterSwipeEndAction = chapterSwipeEndAction,
            nextUpdate = nextUpdate,
            navigateUp = navigateUp,
            onChapterClicked = onChapterClicked,
            onDownloadChapter = onDownloadChapter,
            onAddToLibraryClicked = onAddToLibraryClicked,
            onWebViewClicked = onWebViewClicked,
            onWebViewLongClicked = onWebViewLongClicked,
            onTrackingClicked = onTrackingClicked,
            onTagSearch = onTagSearch,
            onCopyTagToClipboard = onCopyTagToClipboard,
            onFilterButtonClicked = onFilterButtonClicked,
            onRefresh = onRefresh,
            onContinueReading = onContinueReading,
            onSearch = onSearch,
            onCoverClicked = onCoverClicked,
            onShareClicked = onShareClicked,
            onDownloadActionClicked = onDownloadActionClicked,
            onEditCategoryClicked = onEditCategoryClicked,
            onEditInfoClicked = onEditInfoClicked, // RK
            onEditIntervalClicked = onEditFetchIntervalClicked,
            onMigrateClicked = onMigrateClicked,
            onEditNotesClicked = onEditNotesClicked,
            onManageSourcesClicked = onManageSourcesClicked,
            onMetadataViewerClicked = onMetadataViewerClicked,
            onHideSelected = onHideSelected,
            onUnhideSelected = onUnhideSelected,
            onToggleShowHidden = onToggleShowHidden,
            onOpenPagePreview = onOpenPagePreview,
            onMorePreviewsClicked = onMorePreviewsClicked,
            onSelectSource = onSelectSource,
            onSplitSource = onSplitSource,
            onRelatedClick = onRelatedClick,
            onRelatedSeeAll = onRelatedSeeAll,
            onMultiBookmarkClicked = onMultiBookmarkClicked,
            onMultiMarkAsReadClicked = onMultiMarkAsReadClicked,
            onMarkPreviousAsReadClicked = onMarkPreviousAsReadClicked,
            onMultiDeleteClicked = onMultiDeleteClicked,
            onChapterSwipe = onChapterSwipe,
            onChapterSelected = onChapterSelected,
            onAllChapterSelected = onAllChapterSelected,
            onInvertSelection = onInvertSelection,
        )
    }
}

@Composable
private fun MangaScreenSmallImpl(
    state: MangaScreenModel.State.Success,
    snackbarHostState: SnackbarHostState,
    nextUpdate: Instant?,
    chapterSwipeStartAction: LibraryPreferences.ChapterSwipeAction,
    chapterSwipeEndAction: LibraryPreferences.ChapterSwipeAction,
    navigateUp: () -> Unit,
    onChapterClicked: (Chapter) -> Unit,
    onDownloadChapter: ((List<ChapterList.Item>, ChapterDownloadAction) -> Unit)?,
    onAddToLibraryClicked: () -> Unit,
    onWebViewClicked: (() -> Unit)?,
    onWebViewLongClicked: (() -> Unit)?,
    onTrackingClicked: () -> Unit,

    // For tags menu
    onTagSearch: (String) -> Unit,
    onCopyTagToClipboard: (tag: String) -> Unit,

    onFilterClicked: () -> Unit,
    onRefresh: () -> Unit,
    onContinueReading: () -> Unit,
    onSearch: (query: String, global: Boolean) -> Unit,

    // For cover dialog
    onCoverClicked: () -> Unit,

    // For top action menu
    onShareClicked: (() -> Unit)?,
    onDownloadActionClicked: ((DownloadAction) -> Unit)?,
    onEditCategoryClicked: (() -> Unit)?,
    onEditInfoClicked: (() -> Unit)?, // RK
    onEditIntervalClicked: (() -> Unit)?,
    onMigrateClicked: (() -> Unit)?,
    onEditNotesClicked: () -> Unit,
    // RK: merge-group source management; null when the manga is not part of a group
    onManageSourcesClicked: (() -> Unit)? = null,
    // RK: opens the gallery metadata viewer (adult/metadata sources only)
    onMetadataViewerClicked: (() -> Unit)? = null,
    // RK: hide/unhide the selected chapters + toggle temporarily showing hidden ones
    onHideSelected: (() -> Unit)? = null,
    onUnhideSelected: (() -> Unit)? = null,
    onToggleShowHidden: (() -> Unit)? = null,
    // RK: tap a page-preview thumbnail -> open the reader at that page
    onOpenPagePreview: (Int) -> Unit = {},
    // RK: open the full-screen page-preview gallery
    onMorePreviewsClicked: () -> Unit = {},
    onSelectSource: (Long?) -> Unit = {},
    onSplitSource: (Long) -> Unit = {},
    // RK: related-mangas carousel card tap
    onRelatedClick: (RelatedMangaCandidate) -> Unit = {},
    // RK: related-mangas carousel "See all" -> browse grid
    onRelatedSeeAll: () -> Unit = {},

    // For bottom action menu
    onMultiBookmarkClicked: (List<Chapter>, bookmarked: Boolean) -> Unit,
    onMultiMarkAsReadClicked: (List<Chapter>, markAsRead: Boolean) -> Unit,
    onMarkPreviousAsReadClicked: (Chapter) -> Unit,
    onMultiDeleteClicked: (List<Chapter>) -> Unit,

    // For chapter swipe
    onChapterSwipe: (ChapterList.Item, LibraryPreferences.ChapterSwipeAction) -> Unit,

    // Chapter selection
    onChapterSelected: (ChapterList.Item, Boolean, Boolean) -> Unit,
    onAllChapterSelected: (Boolean) -> Unit,
    onInvertSelection: () -> Unit,
) {
    val chapterListState = rememberLazyListState()

    val (chapters, listItem, isAnySelected) = remember(state) {
        Triple(
            first = state.processedChapters,
            second = state.chapterListItems,
            third = state.isAnySelected,
        )
    }

    // RK: build the shared neutral header/description state in composable scope so the merge-unified
    //     label resolves via stringResource before the shell emits the info group.
    val entrySourceName = if (state.mergeSources.size > 1 && state.selectedSourceMangaId == null) {
        stringResource(MR.strings.merge_unified)
    } else {
        (state.mergeDisplaySource ?: state.source).getNameForMangaInfo()
    }
    // RK: overlay the manga's custom-info edits for DISPLAY only; actions keep reading raw state.manga.
    val displayManga = state.manga.withCustomInfo(state.customInfo)
    val entryState = EntryDetailsUiState(
        header = (state.mergeDisplayManga ?: displayManga).toEntryHeader(
            sourceName = entrySourceName,
            isStubSource = (state.mergeDisplaySource ?: state.source) is StubSource,
        ),
        favorite = state.manga.favorite,
        trackingCount = state.trackingCount,
        showIntervalButton = true,
        nextUpdate = nextUpdate,
        isUserIntervalMode = state.manga.fetchInterval < 0,
        description = displayManga.description,
        tags = displayManga.genre,
        notes = state.manga.notes,
        // expand by default for EH/EXH galleries (tags are the content, no description)
        descriptionDefaultExpanded = state.isFromSource || state.isMetadataSource,
    )

    // RK: shared details shell for manga + novels (replaces the per-type Scaffold + FAB + pull-refresh)
    EntryDetailsScaffold(
        listState = chapterListState,
        snackbarHostState = snackbarHostState,
        isAnySelected = isAnySelected,
        isRefreshing = state.isRefreshingData,
        onRefresh = onRefresh,
        onCancelSelection = { onAllChapterSelected(false) },
        fabVisible = chapters.fastAny { !it.chapter.read && it.id !in state.hiddenChapterIds } && !isAnySelected,
        fabIsResume = state.chapters.fastAny { it.chapter.read },
        onFabClick = onContinueReading,
        topBar = { titleAlpha, backgroundAlpha ->
            // RK: shared details toolbar for manga + novels (replaces the per-type MangaToolbar)
            EntryToolbar(
                title = displayManga.title,
                hasFilters = state.filterActive,
                navigateUp = navigateUp,
                onClickFilter = onFilterClicked,
                onClickShare = onShareClicked,
                onClickDownload = onDownloadActionClicked,
                onClickEditCategory = onEditCategoryClicked,
                onClickEditInfo = onEditInfoClicked, // RK
                onClickRefresh = onRefresh,
                onClickMigrate = onMigrateClicked,
                onClickEditNotes = onEditNotesClicked,
                onClickManageSources = onManageSourcesClicked,
                onClickMetadataViewer = onMetadataViewerClicked,
                onHide = onHideSelected,
                onUnhide = onUnhideSelected,
                onToggleShowHidden = onToggleShowHidden,
                showHidden = state.showHidden,
                hasHiddenChapters = state.hasHiddenChapters,
                allHiddenSelected = state.showHidden && chapters.any { it.selected } &&
                    chapters.none { it.selected && it.id !in state.hiddenChapterIds },
                actionModeCounter = chapters.count { it.selected },
                onCancelActionMode = { onAllChapterSelected(false) },
                onSelectAll = { onAllChapterSelected(true) },
                onInvertSelection = { onInvertSelection() },
                titleAlphaProvider = titleAlpha,
                backgroundAlphaProvider = backgroundAlpha,
            )
        },
        bottomActionMenu = {
            SharedMangaBottomActionMenu(
                selected = chapters.filter { it.selected },
                onMultiBookmarkClicked = onMultiBookmarkClicked,
                onMultiMarkAsReadClicked = onMultiMarkAsReadClicked,
                onMarkPreviousAsReadClicked = onMarkPreviousAsReadClicked,
                onDownloadChapter = onDownloadChapter,
                onMultiDeleteClicked = onMultiDeleteClicked,
                fillFraction = 1f,
            )
        },
    ) { appBarPadding ->
        entryInfoItems(
            isTabletUi = false,
            appBarPadding = appBarPadding,
            state = entryState,
            onCoverClick = onCoverClicked,
            doSearch = onSearch,
            onAddToLibraryClicked = onAddToLibraryClicked,
            onTrackingClicked = onTrackingClicked,
            onEditCategory = onEditCategoryClicked,
            onEditIntervalClicked = onEditIntervalClicked,
            onWebViewClicked = onWebViewClicked,
            onWebViewLongClicked = onWebViewLongClicked,
            onShareClicked = null,
            onTagSearch = onTagSearch,
            onGlobalSearch = { onSearch(it, true) },
            onCopyTagToClipboard = onCopyTagToClipboard,
            onEditNotes = onEditNotesClicked,
            // RK: namespaced, color-weighted chips for the active source's gallery metadata
            searchMetadataChips = SearchMetadataChips(
                state.galleryMetadata,
                (state.mergeDisplaySource ?: state.source).id,
                state.manga.genre,
            ),
            // RK: per-source gallery-info card for adult/metadata sources, above the description
            aboveDescription = state.galleryMetadata?.let { meta ->
                {
                    GalleryInfoBox(
                        metadata = meta,
                        onMoreInfoClick = onMetadataViewerClicked,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
        )

        // RK: source-switcher chips for a merged group
        if (state.mergeSources.size > 1) {
            item(key = "rk-merge-source-chips") {
                MergeSourceChips(
                    sources = state.mergeSources,
                    selectedSourceMangaId = state.selectedSourceMangaId,
                    onSelect = onSelectSource,
                    onSplitSource = onSplitSource,
                )
            }
        }

        // RK: related-mangas carousel (recommendations)
        item(key = "rk-related-carousel") {
            RelatedMangaCarousel(
                items = state.relatedItems,
                loading = state.relatedLoading,
                totalCount = state.relatedTotalCount,
                onClick = onRelatedClick,
                onSeeAll = onRelatedSeeAll,
                topDivider = true,
            )
        }

        // RK: page-preview thumbnails for adult sources (0 rows = off);
        // on phone this sits below the related carousel
        if (state.pagePreviewsState !is PagePreviewState.Unused && state.previewsRowCount > 0) {
            item(key = "rk-page-previews") {
                PagePreviews(
                    pagePreviewState = state.pagePreviewsState,
                    onOpenPage = onOpenPagePreview,
                    onMorePreviewsClicked = onMorePreviewsClicked,
                    rowCount = state.previewsRowCount,
                )
            }
        }

        item(
            key = MangaScreenItem.CHAPTER_HEADER,
            contentType = MangaScreenItem.CHAPTER_HEADER,
        ) {
            val missingChapterCount = remember(chapters) {
                chapters.map { it.chapter.chapterNumber }.missingChaptersCount()
            }
            ChapterHeader(
                enabled = !isAnySelected,
                chapterCount = chapters.size,
                missingChapterCount = missingChapterCount,
                onClick = onFilterClicked,
            )
        }

        sharedChapterItems(
            manga = state.manga,
            chapters = listItem,
            isAnyChapterSelected = chapters.fastAny { it.selected },
            hiddenChapterIds = state.hiddenChapterIds,
            chapterSwipeStartAction = chapterSwipeStartAction,
            chapterSwipeEndAction = chapterSwipeEndAction,
            onChapterClicked = onChapterClicked,
            onDownloadChapter = onDownloadChapter,
            onChapterSelected = onChapterSelected,
            onChapterSwipe = onChapterSwipe,
        )
    }
}

@Composable
fun MangaScreenLargeImpl(
    state: MangaScreenModel.State.Success,
    snackbarHostState: SnackbarHostState,
    nextUpdate: Instant?,
    chapterSwipeStartAction: LibraryPreferences.ChapterSwipeAction,
    chapterSwipeEndAction: LibraryPreferences.ChapterSwipeAction,
    navigateUp: () -> Unit,
    onChapterClicked: (Chapter) -> Unit,
    onDownloadChapter: ((List<ChapterList.Item>, ChapterDownloadAction) -> Unit)?,
    onAddToLibraryClicked: () -> Unit,
    onWebViewClicked: (() -> Unit)?,
    onWebViewLongClicked: (() -> Unit)?,
    onTrackingClicked: () -> Unit,

    // For tags menu
    onTagSearch: (String) -> Unit,
    onCopyTagToClipboard: (tag: String) -> Unit,

    onFilterButtonClicked: () -> Unit,
    onRefresh: () -> Unit,
    onContinueReading: () -> Unit,
    onSearch: (query: String, global: Boolean) -> Unit,

    // For cover dialog
    onCoverClicked: () -> Unit,

    // For top action menu
    onShareClicked: (() -> Unit)?,
    onDownloadActionClicked: ((DownloadAction) -> Unit)?,
    onEditCategoryClicked: (() -> Unit)?,
    onEditInfoClicked: (() -> Unit)?, // RK
    onEditIntervalClicked: (() -> Unit)?,
    onMigrateClicked: (() -> Unit)?,
    onEditNotesClicked: () -> Unit,
    // RK: merge-group source management; null when the manga is not part of a group
    onManageSourcesClicked: (() -> Unit)? = null,
    // RK: opens the gallery metadata viewer (adult/metadata sources only)
    onMetadataViewerClicked: (() -> Unit)? = null,
    // RK: hide/unhide the selected chapters + toggle temporarily showing hidden ones
    onHideSelected: (() -> Unit)? = null,
    onUnhideSelected: (() -> Unit)? = null,
    onToggleShowHidden: (() -> Unit)? = null,
    // RK: tap a page-preview thumbnail -> open the reader at that page
    onOpenPagePreview: (Int) -> Unit = {},
    // RK: open the full-screen page-preview gallery
    onMorePreviewsClicked: () -> Unit = {},
    onSelectSource: (Long?) -> Unit = {},
    onSplitSource: (Long) -> Unit = {},
    // RK: related-mangas carousel card tap
    onRelatedClick: (RelatedMangaCandidate) -> Unit = {},
    // RK: related-mangas carousel "See all" -> browse grid
    onRelatedSeeAll: () -> Unit = {},

    // For bottom action menu
    onMultiBookmarkClicked: (List<Chapter>, bookmarked: Boolean) -> Unit,
    onMultiMarkAsReadClicked: (List<Chapter>, markAsRead: Boolean) -> Unit,
    onMarkPreviousAsReadClicked: (Chapter) -> Unit,
    onMultiDeleteClicked: (List<Chapter>) -> Unit,

    // For swipe actions
    onChapterSwipe: (ChapterList.Item, LibraryPreferences.ChapterSwipeAction) -> Unit,

    // Chapter selection
    onChapterSelected: (ChapterList.Item, Boolean, Boolean) -> Unit,
    onAllChapterSelected: (Boolean) -> Unit,
    onInvertSelection: () -> Unit,
) {
    val (chapters, listItem, isAnySelected) = remember(state) {
        Triple(
            first = state.processedChapters,
            second = state.chapterListItems,
            third = state.isAnySelected,
        )
    }
    val chapterListState = rememberLazyListState()

    // RK: build the shared neutral header/description state (composable scope); the wide layout expands
    //     the description by default.
    val entrySourceName = if (state.mergeSources.size > 1 && state.selectedSourceMangaId == null) {
        stringResource(MR.strings.merge_unified)
    } else {
        (state.mergeDisplaySource ?: state.source).getNameForMangaInfo()
    }
    // RK: overlay the manga's custom-info edits for DISPLAY only; actions keep reading raw state.manga.
    val displayManga = state.manga.withCustomInfo(state.customInfo)
    val entryState = EntryDetailsUiState(
        header = (state.mergeDisplayManga ?: displayManga).toEntryHeader(
            sourceName = entrySourceName,
            isStubSource = (state.mergeDisplaySource ?: state.source) is StubSource,
        ),
        favorite = state.manga.favorite,
        trackingCount = state.trackingCount,
        showIntervalButton = true,
        nextUpdate = nextUpdate,
        isUserIntervalMode = state.manga.fetchInterval < 0,
        description = displayManga.description,
        tags = displayManga.genre,
        notes = state.manga.notes,
        descriptionDefaultExpanded = true,
    )

    // RK: shared two-pane details shell for manga + novels (replaces the per-type tablet Scaffold)
    EntryDetailsTwoPaneScaffold(
        chapterListState = chapterListState,
        snackbarHostState = snackbarHostState,
        isAnySelected = isAnySelected,
        isRefreshing = state.isRefreshingData,
        onRefresh = onRefresh,
        onCancelSelection = { onAllChapterSelected(false) },
        fabVisible = chapters.fastAny { !it.chapter.read && it.id !in state.hiddenChapterIds } && !isAnySelected,
        fabIsResume = state.chapters.fastAny { it.chapter.read },
        onFabClick = onContinueReading,
        topBar = { modifier ->
            // RK: shared details toolbar for manga + novels (replaces the per-type MangaToolbar)
            EntryToolbar(
                modifier = modifier,
                title = displayManga.title,
                hasFilters = state.filterActive,
                navigateUp = navigateUp,
                onClickFilter = onFilterButtonClicked,
                onClickShare = onShareClicked,
                onClickDownload = onDownloadActionClicked,
                onClickEditCategory = onEditCategoryClicked,
                onClickEditInfo = onEditInfoClicked, // RK
                onClickRefresh = onRefresh,
                onClickMigrate = onMigrateClicked,
                onClickEditNotes = onEditNotesClicked,
                onClickManageSources = onManageSourcesClicked,
                onClickMetadataViewer = onMetadataViewerClicked,
                onHide = onHideSelected,
                onUnhide = onUnhideSelected,
                onToggleShowHidden = onToggleShowHidden,
                showHidden = state.showHidden,
                hasHiddenChapters = state.hasHiddenChapters,
                allHiddenSelected = state.showHidden && chapters.any { it.selected } &&
                    chapters.none { it.selected && it.id !in state.hiddenChapterIds },
                onCancelActionMode = { onAllChapterSelected(false) },
                actionModeCounter = chapters.count { it.selected },
                onSelectAll = { onAllChapterSelected(true) },
                onInvertSelection = { onInvertSelection() },
                titleAlphaProvider = { 1f },
                backgroundAlphaProvider = { 1f },
            )
        },
        bottomActionMenu = {
            SharedMangaBottomActionMenu(
                selected = chapters.filter { it.selected },
                onMultiBookmarkClicked = onMultiBookmarkClicked,
                onMultiMarkAsReadClicked = onMultiMarkAsReadClicked,
                onMarkPreviousAsReadClicked = onMarkPreviousAsReadClicked,
                onDownloadChapter = onDownloadChapter,
                onMultiDeleteClicked = onMultiDeleteClicked,
                fillFraction = 0.5f,
            )
        },
        startContent = { appBarPadding ->
            entryInfoItems(
                isTabletUi = true,
                appBarPadding = appBarPadding,
                state = entryState,
                onCoverClick = onCoverClicked,
                doSearch = onSearch,
                onAddToLibraryClicked = onAddToLibraryClicked,
                onTrackingClicked = onTrackingClicked,
                onEditCategory = onEditCategoryClicked,
                onEditIntervalClicked = onEditIntervalClicked,
                onWebViewClicked = onWebViewClicked,
                onWebViewLongClicked = onWebViewLongClicked,
                onShareClicked = null,
                onTagSearch = onTagSearch,
                onGlobalSearch = { onSearch(it, true) },
                onCopyTagToClipboard = onCopyTagToClipboard,
                onEditNotes = onEditNotesClicked,
                // RK: namespaced, color-weighted chips for the active source's gallery metadata
                searchMetadataChips = SearchMetadataChips(
                    state.galleryMetadata,
                    (state.mergeDisplaySource ?: state.source).id,
                    state.manga.genre,
                ),
                // RK: per-source gallery-info card for adult/metadata sources
                aboveDescription = state.galleryMetadata?.let { meta ->
                    {
                        GalleryInfoBox(
                            metadata = meta,
                            onMoreInfoClick = onMetadataViewerClicked,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                },
            )
            // RK: page-preview thumbnails for adult sources (0 rows = off)
            if (state.pagePreviewsState !is PagePreviewState.Unused && state.previewsRowCount > 0) {
                item(key = "rk-page-previews") {
                    PagePreviews(
                        pagePreviewState = state.pagePreviewsState,
                        onOpenPage = onOpenPagePreview,
                        onMorePreviewsClicked = onMorePreviewsClicked,
                        rowCount = state.previewsRowCount,
                    )
                }
            }
        },
        endContent = {
            // RK: related-mangas carousel at the top of the chapter pane (Komikku-style)
            item(key = "rk-related-carousel") {
                RelatedMangaCarousel(
                    items = state.relatedItems,
                    loading = state.relatedLoading,
                    totalCount = state.relatedTotalCount,
                    onClick = onRelatedClick,
                    onSeeAll = onRelatedSeeAll,
                )
            }
            // RK: source-switcher chips for a merged group
            if (state.mergeSources.size > 1) {
                item(key = "rk-merge-source-chips") {
                    MergeSourceChips(
                        sources = state.mergeSources,
                        selectedSourceMangaId = state.selectedSourceMangaId,
                        onSelect = onSelectSource,
                        onSplitSource = onSplitSource,
                    )
                }
            }
            item(
                key = MangaScreenItem.CHAPTER_HEADER,
                contentType = MangaScreenItem.CHAPTER_HEADER,
            ) {
                val missingChapterCount = remember(chapters) {
                    chapters.map { it.chapter.chapterNumber }.missingChaptersCount()
                }
                ChapterHeader(
                    enabled = !isAnySelected,
                    chapterCount = chapters.size,
                    missingChapterCount = missingChapterCount,
                    onClick = onFilterButtonClicked,
                )
            }
            sharedChapterItems(
                manga = state.manga,
                chapters = listItem,
                isAnyChapterSelected = chapters.fastAny { it.selected },
                hiddenChapterIds = state.hiddenChapterIds,
                chapterSwipeStartAction = chapterSwipeStartAction,
                chapterSwipeEndAction = chapterSwipeEndAction,
                onChapterClicked = onChapterClicked,
                onDownloadChapter = onDownloadChapter,
                onChapterSelected = onChapterSelected,
                onChapterSwipe = onChapterSwipe,
            )
        },
    )
}

@Composable
private fun SharedMangaBottomActionMenu(
    selected: List<ChapterList.Item>,
    onMultiBookmarkClicked: (List<Chapter>, bookmarked: Boolean) -> Unit,
    onMultiMarkAsReadClicked: (List<Chapter>, markAsRead: Boolean) -> Unit,
    onMarkPreviousAsReadClicked: (Chapter) -> Unit,
    onDownloadChapter: ((List<ChapterList.Item>, ChapterDownloadAction) -> Unit)?,
    onMultiDeleteClicked: (List<Chapter>) -> Unit,
    fillFraction: Float,
    modifier: Modifier = Modifier,
) {
    MangaBottomActionMenu(
        visible = selected.isNotEmpty(),
        modifier = modifier.fillMaxWidth(fillFraction),
        onBookmarkClicked = {
            onMultiBookmarkClicked.invoke(selected.fastMap { it.chapter }, true)
        }.takeIf { selected.fastAny { !it.chapter.bookmark } },
        onRemoveBookmarkClicked = {
            onMultiBookmarkClicked.invoke(selected.fastMap { it.chapter }, false)
        }.takeIf { selected.fastAll { it.chapter.bookmark } },
        onMarkAsReadClicked = {
            onMultiMarkAsReadClicked(selected.fastMap { it.chapter }, true)
        }.takeIf { selected.fastAny { !it.chapter.read } },
        onMarkAsUnreadClicked = {
            onMultiMarkAsReadClicked(selected.fastMap { it.chapter }, false)
        }.takeIf { selected.fastAny { it.chapter.read || it.chapter.lastPageRead > 0L } },
        onMarkPreviousAsReadClicked = {
            onMarkPreviousAsReadClicked(selected[0].chapter)
        }.takeIf { selected.size == 1 },
        onDownloadClicked = {
            onDownloadChapter!!(selected.toList(), ChapterDownloadAction.START)
        }.takeIf {
            onDownloadChapter != null && selected.fastAny { it.downloadState != Download.State.DOWNLOADED }
        },
        onDeleteClicked = {
            onMultiDeleteClicked(selected.fastMap { it.chapter })
        }.takeIf {
            selected.fastAny { it.downloadState == Download.State.DOWNLOADED }
        },
    )
}

// RK: opacity of a hidden chapter row when temporarily shown (matches the novel details screen).
private const val HIDDEN_CHAPTER_ALPHA = 0.4f

private fun LazyListScope.sharedChapterItems(
    manga: Manga,
    chapters: List<ChapterList>,
    isAnyChapterSelected: Boolean,
    // RK: chapters shown dimmed because they are hidden (only non-empty while showing hidden).
    hiddenChapterIds: Set<Long> = emptySet(),
    chapterSwipeStartAction: LibraryPreferences.ChapterSwipeAction,
    chapterSwipeEndAction: LibraryPreferences.ChapterSwipeAction,
    onChapterClicked: (Chapter) -> Unit,
    onDownloadChapter: ((List<ChapterList.Item>, ChapterDownloadAction) -> Unit)?,
    onChapterSelected: (ChapterList.Item, Boolean, Boolean) -> Unit,
    onChapterSwipe: (ChapterList.Item, LibraryPreferences.ChapterSwipeAction) -> Unit,
) {
    items(
        items = chapters,
        key = { item ->
            when (item) {
                is ChapterList.MissingCount -> "missing-count-${item.id}"
                is ChapterList.Item -> "chapter-${item.id}"
            }
        },
        contentType = { MangaScreenItem.CHAPTER },
    ) { item ->
        val haptic = LocalHapticFeedback.current

        when (item) {
            is ChapterList.MissingCount -> {
                MissingChapterCountListItem(count = item.count)
            }
            is ChapterList.Item -> {
                MangaChapterListItem(
                    // RK: dim rows that are hidden (shown only while "Show hidden chapters" is on).
                    modifier = Modifier.alpha(if (item.id in hiddenChapterIds) HIDDEN_CHAPTER_ALPHA else 1f),
                    title = if (manga.displayMode == Manga.CHAPTER_DISPLAY_NUMBER) {
                        stringResource(
                            MR.strings.display_mode_chapter,
                            formatChapterNumber(item.chapter.chapterNumber),
                        )
                    } else {
                        item.chapter.name
                    },
                    date = relativeDateText(item.chapter.dateUpload),
                    readProgress = item.chapter.lastPageRead
                        .takeIf { !item.chapter.read && it > 0L }
                        ?.let {
                            stringResource(
                                MR.strings.chapter_progress,
                                it + 1,
                            )
                        },
                    scanlator = item.chapter.scanlator.takeIf { !it.isNullOrBlank() },
                    read = item.chapter.read,
                    bookmark = item.chapter.bookmark,
                    selected = item.selected,
                    downloadIndicatorEnabled = !isAnyChapterSelected && !manga.isLocal(),
                    downloadStateProvider = { item.downloadState },
                    downloadProgressProvider = { item.downloadProgress },
                    chapterSwipeStartAction = chapterSwipeStartAction,
                    chapterSwipeEndAction = chapterSwipeEndAction,
                    onLongClick = {
                        onChapterSelected(item, !item.selected, true)
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                    onClick = {
                        onChapterItemClick(
                            chapterItem = item,
                            isAnyChapterSelected = isAnyChapterSelected,
                            onToggleSelection = { onChapterSelected(item, !item.selected, false) },
                            onChapterClicked = onChapterClicked,
                        )
                    },
                    onDownloadClick = if (onDownloadChapter != null) {
                        { onDownloadChapter(listOf(item), it) }
                    } else {
                        null
                    },
                    onChapterSwipe = {
                        onChapterSwipe(item, it)
                    },
                )
            }
        }
    }
}

private fun onChapterItemClick(
    chapterItem: ChapterList.Item,
    isAnyChapterSelected: Boolean,
    onToggleSelection: (Boolean) -> Unit,
    onChapterClicked: (Chapter) -> Unit,
) {
    when {
        chapterItem.selected -> onToggleSelection(false)
        isAnyChapterSelected -> onToggleSelection(true)
        else -> onChapterClicked(chapterItem.chapter)
    }
}
