package reikai.presentation.details

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.manga.components.ChapterHeader
import eu.kanade.presentation.manga.components.GalleryInfoBox
import eu.kanade.presentation.manga.components.MangaBottomActionMenu
import eu.kanade.presentation.manga.components.MangaChapterListItem
import eu.kanade.presentation.manga.components.MissingChapterCountListItem
import eu.kanade.presentation.manga.components.PagePreviews
import eu.kanade.presentation.manga.components.SearchMetadataChips
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.KeyboardArrowDown
import reikai.domain.download.whereDownloadOffered
import reikai.domain.recommendation.RelatedMangaCandidate
import reikai.presentation.components.ManageMergeSourceRow
import reikai.presentation.components.MergeSourceChips
import reikai.presentation.components.chapterRowDate
import reikai.presentation.components.readProgressLabel
import reikai.presentation.novel.details.novelPageText
import reikai.presentation.reader.chapterRowTitle
import reikai.presentation.reader.chapterTitleWords
import reikai.presentation.recommendation.RelatedMangaCarousel
import reikai.presentation.selection.chapterSelectionOffers
import tachiyomi.domain.library.service.LibraryPreferences

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
    val onTracking: () -> Unit,
    val onEditNotes: () -> Unit,
    val onOpenFilterSettings: () -> Unit,
    val onMigrate: (() -> Unit)? = null,
    /** Smart update's interval editor; null hides the button's action, as for an entry not in the library. */
    val onEditInterval: (() -> Unit)? = null,
    /** Opens the novel page/volume selector sheet; novel-only. */
    val onOpenPageSelector: (() -> Unit)? = null,
    /** Search and word count over the downloaded chapters' text; novel-only, since a manga chapter on
     *  disk is images. Offered only while what the screen shows has downloads. */
    val onSearchText: (() -> Unit)? = null,
    val onWordCount: (() -> Unit)? = null,
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
    val web = rememberEntryWebActions(state.webPage, state.details.header.title)
    if (isTabletUi) {
        EntryDetailsLargeContent(
            behavior,
            state,
            snackbarHostState,
            chapterSwipeStartAction,
            chapterSwipeEndAction,
            nav,
            web,
        )
    } else {
        EntryDetailsSmallContent(
            behavior,
            state,
            snackbarHostState,
            chapterSwipeStartAction,
            chapterSwipeEndAction,
            nav,
            web,
        )
    }
}

@Composable
private fun EntryDetailsSmallContent(
    behavior: EntryDetailsBehavior,
    state: EntryDetailsScreenState.Loaded,
    snackbarHostState: SnackbarHostState,
    chapterSwipeStartAction: LibraryPreferences.ChapterSwipeAction,
    chapterSwipeEndAction: LibraryPreferences.ChapterSwipeAction,
    nav: EntryDetailsNavigation,
    web: EntryWebActions,
) {
    val listState = rememberLazyListState()
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
        topBar = { titleAlpha, backgroundAlpha ->
            EntryDetailsToolbar(state, behavior, nav, web, titleAlpha, backgroundAlpha)
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
        chapterHeaderItem(state, nav)
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
) {
    val chapterListState = rememberLazyListState()
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
        topBar = { modifier ->
            EntryDetailsToolbar(state, behavior, nav, web, { 1f }, { 1f }, modifier)
        },
        bottomActionMenu = { EntryDetailsSelectionBar(state, behavior, fillFraction = 0.5f) },
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
            chapterHeaderItem(state, nav)
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
    titleAlphaProvider: () -> Float,
    backgroundAlphaProvider: () -> Float,
    modifier: Modifier = Modifier,
) {
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
        onClickOpenFolder = nav.onOpenFolder?.takeIf { state.chaptersDownloadable && state.hasViewedDownloads },
        onClickSearchText = nav.onSearchText?.takeIf { state.chaptersDownloadable && state.hasViewedDownloads },
        onClickWordCount = nav.onWordCount?.takeIf { state.chaptersDownloadable && state.hasViewedDownloads },
        onClickRecommendations = nav.onRecommendations,
        onHide = behavior::hideSelected,
        onUnhide = behavior::unhideSelected,
        onToggleShowHidden = behavior::toggleShowHidden,
        onCorrectChapterNumber = { behavior.showChapterNumberDialog() }.takeIf { state.selection.size == 1 },
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
    val selected = state.chapters.items
        .filterIsInstance<EntryChapterListItem.Chapter>()
        .filter { it.id in state.selection }
    val offers = chapterSelectionOffers(selected, selected.map { it.downloadState })
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
        onTrackingClicked = nav.onTracking,
        onEditCategory = { behavior.showChangeCategoryDialog() }.takeIf { state.details.favorite },
        onEditIntervalClicked = nav.onEditInterval,
        onWebViewClicked = web.openWebView,
        onWebViewLongClicked = web.copyUrl,
        onTagSearch = nav.onTagSearch,
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
            onSplitSource = { behavior.splitSources(listOf(it)) },
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
) {
    item(key = "entry-chapter-header") {
        Column {
            ChapterHeader(
                enabled = !state.selectionMode,
                chapterCount = state.chapters.items.count { it is EntryChapterListItem.Chapter },
                missingChapterCount = state.chapters.missingChapterCount,
                onClick = nav.onOpenFilterSettings,
            )
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
                is EntryChapterListItem.Chapter -> "chapter-${item.id}"
            }
        },
        contentType = { "entry-chapter" },
    ) { item ->
        when (item) {
            is EntryChapterListItem.Missing -> MissingChapterCountListItem(count = item.count)
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
                    scanlator = chapter.subtitle,
                    read = chapter.read,
                    bookmark = chapter.bookmark,
                    selected = isSelected,
                    downloadIndicatorEnabled = !selectionMode && chaptersDownloadable,
                    downloadStateProvider = { chapter.downloadState },
                    downloadProgressProvider = { chapter.downloadProgress },
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
