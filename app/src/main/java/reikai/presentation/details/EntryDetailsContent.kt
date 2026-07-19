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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.relativeDateText
import eu.kanade.presentation.manga.components.ChapterHeader
import eu.kanade.presentation.manga.components.GalleryInfoBox
import eu.kanade.presentation.manga.components.MangaBottomActionMenu
import eu.kanade.presentation.manga.components.MangaChapterListItem
import eu.kanade.presentation.manga.components.MissingChapterCountListItem
import eu.kanade.presentation.manga.components.PagePreviews
import eu.kanade.presentation.manga.components.SearchMetadataChips
import eu.kanade.presentation.util.formatChapterNumber
import eu.kanade.tachiyomi.data.download.model.Download
import reikai.domain.recommendation.RelatedMangaCandidate
import reikai.presentation.components.ManageMergeSourceRow
import reikai.presentation.components.MergeSourceChips
import reikai.presentation.recommendation.RelatedMangaCarousel
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/** Dim level for a hidden chapter row shown via "Show hidden chapters". */
private const val HIDDEN_CHAPTER_ALPHA = 0.4f

/**
 * Navigation and per-type actions the shared details body cannot express through [EntryDetailsBehavior]:
 * opening the reader, share / WebView intents, the per-type filter-settings sheet, and the manga-only
 * capability taps (related cards, page previews, gallery viewer). Each screen builds one and passes it in;
 * a manga-only slot's callback is simply absent for novels.
 */
data class EntryDetailsNavigation(
    val navigateUp: () -> Unit,
    val onOpenChapter: (chapterId: Long) -> Unit,
    val onSearch: (query: String, global: Boolean) -> Unit,
    val onTagSearch: (String) -> Unit,
    val onCopyTag: (String) -> Unit,
    val onTracking: () -> Unit,
    val onEditNotes: () -> Unit,
    val onOpenFilterSettings: () -> Unit,
    /** Share button in the action row; null hides it (manga shares from the toolbar only). */
    val onActionRowShare: (() -> Unit)? = null,
    /** Share item in the toolbar overflow. */
    val onToolbarShare: (() -> Unit)? = null,
    val onOpenWebView: (() -> Unit)? = null,
    val onOpenWebViewLong: (() -> Unit)? = null,
    val onMigrate: (() -> Unit)? = null,
    /** Fetch-interval editor; manga-only. */
    val onEditInterval: (() -> Unit)? = null,
    /** Opens the novel page/volume selector sheet; novel-only. */
    val onOpenPageSelector: (() -> Unit)? = null,
    // Manga capability taps.
    val onRelatedClick: (RelatedMangaCandidate) -> Unit = {},
    val onRelatedSeeAll: () -> Unit = {},
    val onOpenPagePreview: (Int) -> Unit = {},
    val onMorePreviews: () -> Unit = {},
    val onMetadataViewer: (() -> Unit)? = null,
    /** "Recommendations" overflow action; non-null only when related suggestions are placed in the menu. */
    val onRecommendations: (() -> Unit)? = null,
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
    if (isTabletUi) {
        EntryDetailsLargeContent(
            behavior,
            state,
            snackbarHostState,
            chapterSwipeStartAction,
            chapterSwipeEndAction,
            nav,
        )
    } else {
        EntryDetailsSmallContent(
            behavior,
            state,
            snackbarHostState,
            chapterSwipeStartAction,
            chapterSwipeEndAction,
            nav,
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
            EntryDetailsToolbar(state, behavior, nav, titleAlpha, backgroundAlpha)
        },
        bottomActionMenu = { EntryDetailsSelectionBar(state, behavior, fillFraction = 1f) },
    ) { appBarPadding ->
        entryInfoBlock(
            state,
            behavior,
            nav,
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
            EntryDetailsToolbar(state, behavior, nav, { 1f }, { 1f }, modifier)
        },
        bottomActionMenu = { EntryDetailsSelectionBar(state, behavior, fillFraction = 0.5f) },
        startContent = { appBarPadding ->
            entryInfoBlock(
                state,
                behavior,
                nav,
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
        onClickShare = nav.onToolbarShare,
        onClickManageSources = { behavior.showManageSourcesDialog() }.takeIf { state.isMerged },
        onClickMigrate = nav.onMigrate,
        onClickDownload = if (state.chaptersDownloadable) behavior::runDownloadAction else null,
        onClickMetadataViewer = nav.onMetadataViewer,
        onClickRecommendations = nav.onRecommendations,
        onHide = behavior::hideSelected,
        onUnhide = behavior::unhideSelected,
        onToggleShowHidden = behavior::toggleShowHidden,
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
    MangaBottomActionMenu(
        visible = selected.isNotEmpty(),
        modifier = modifier.fillMaxWidth(fillFraction),
        onBookmarkClicked = { behavior.bookmarkSelected(true) }
            .takeIf { selected.any { !it.bookmark } },
        onRemoveBookmarkClicked = { behavior.bookmarkSelected(false) }
            .takeIf { selected.isNotEmpty() && selected.all { it.bookmark } },
        onMarkAsReadClicked = { behavior.markSelectedRead(true) }
            .takeIf { selected.any { !it.read } },
        onMarkAsUnreadClicked = { behavior.markSelectedRead(false) }
            .takeIf { selected.any { it.read || it.readProgress != null } },
        onMarkPreviousAsReadClicked = { behavior.markPreviousRead() }
            .takeIf { selected.size == 1 },
        onDownloadClicked = { behavior.downloadSelected() }
            .takeIf { state.chaptersDownloadable && selected.any { it.downloadState != Download.State.DOWNLOADED } },
        onDeleteClicked = { behavior.deleteSelected() }
            .takeIf { selected.any { it.downloadState == Download.State.DOWNLOADED } },
    )
}

private fun LazyListScope.entryInfoBlock(
    state: EntryDetailsScreenState.Loaded,
    behavior: EntryDetailsBehavior,
    nav: EntryDetailsNavigation,
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
        doSearch = nav.onSearch,
        onAddToLibraryClicked = onAddToLibrary,
        onTrackingClicked = nav.onTracking,
        onEditCategory = { behavior.showChangeCategoryDialog() }.takeIf { state.details.favorite },
        onEditIntervalClicked = nav.onEditInterval,
        onWebViewClicked = nav.onOpenWebView,
        onWebViewLongClicked = nav.onOpenWebViewLong,
        onShareClicked = nav.onActionRowShare,
        onTagSearch = nav.onTagSearch,
        onGlobalSearch = { nav.onSearch(it, true) },
        onCopyTagToClipboard = nav.onCopyTag,
        onEditNotes = nav.onEditNotes,
        // Namespaced, grouped tag chips for the active source's gallery metadata (or its namespaced genre).
        searchMetadataChips = gallery?.let { SearchMetadataChips(it.metadata, it.sourceId, it.rawGenre) },
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
            // A paged novel's "Page n / N" bar sits under the header, opening the page selector. The
            // count above is the current page's, so the paged scope stays visible (sort/filter are paged).
            state.capabilities.novelPageSelector?.let { page ->
                NovelPageBar(
                    pageIndex = page.pageIndex,
                    pageCount = page.pages.size,
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
                val haptic = LocalHapticFeedback.current
                val isSelected = item.id in state.selection
                MangaChapterListItem(
                    modifier = Modifier.alpha(
                        if (item.id in state.chapters.hiddenChapterIds) HIDDEN_CHAPTER_ALPHA else 1f,
                    ),
                    title = if (state.showChapterNumberOnly && item.isRecognizedNumber) {
                        stringResource(MR.strings.display_mode_chapter, formatChapterNumber(item.chapterNumber))
                    } else {
                        item.name
                    },
                    date = item.dateUpload.takeIf { it > 0L }?.let { relativeDateText(it) },
                    readProgress = item.readProgress,
                    scanlator = item.scanlator?.takeIf { it.isNotBlank() },
                    read = item.read,
                    bookmark = item.bookmark,
                    selected = isSelected,
                    downloadIndicatorEnabled = !state.selectionMode && state.chaptersDownloadable,
                    downloadStateProvider = { item.downloadState },
                    downloadProgressProvider = { item.downloadProgress },
                    chapterSwipeStartAction = chapterSwipeStartAction,
                    chapterSwipeEndAction = chapterSwipeEndAction,
                    onLongClick = {
                        behavior.toggleSelection(item.id, true)
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                    onClick = {
                        if (state.selectionMode) behavior.toggleSelection(item.id, false) else onOpenChapter(item.id)
                    },
                    onDownloadClick = if (state.chaptersDownloadable) {
                        { behavior.onChapterDownloadAction(item.id, it) }
                    } else {
                        null
                    },
                    onChapterSwipe = { behavior.chapterSwipe(item.id, it) },
                )
            }
        }
    }
}

/** Compact "Page n / N" row under the chapter header for a paged novel; opens the page selector sheet. */
@Composable
private fun NovelPageBar(
    pageIndex: Int,
    pageCount: Int,
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
            text = "Page ${pageIndex + 1} / $pageCount",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        }
        Icon(
            imageVector = Icons.Default.ArrowDropDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}
