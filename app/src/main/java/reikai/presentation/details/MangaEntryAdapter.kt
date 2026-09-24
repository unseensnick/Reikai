package reikai.presentation.details

import androidx.compose.runtime.Immutable
import androidx.lifecycle.viewModelScope
import eu.kanade.presentation.manga.DownloadAction
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.model.TrackMangaMetadata
import eu.kanade.tachiyomi.source.isLocalOrStub
import eu.kanade.tachiyomi.ui.manga.ChapterList
import eu.kanade.tachiyomi.ui.manga.MangaViewModel
import eu.kanade.tachiyomi.ui.manga.PagePreviewState
import exh.metadata.metadata.RaisedSearchMetadata
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import reikai.domain.entry.EntryId
import reikai.domain.merge.ChapterGap
import reikai.presentation.components.chapterSubtitle
import reikai.presentation.components.mergeSourceLabels
import reikai.presentation.selection.EntrySelection
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.withCustomInfo
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.track.model.Track

/**
 * Adapts the live [MangaViewModel] to the neutral [EntryDetailsBehavior]. Mihon's model stays
 * upstream-tracked and is never made to implement a Reikai interface, so every shape mismatch
 * reconciles HERE: this maps its state to [EntryDetailsScreenState] and forwards neutral actions to
 * the model's own methods. Symmetric with [NovelEntryAdapter], so one shared `EntryDetailsContent`
 * drives both types; manga-only actions stay off the shared interface.
 */
class MangaEntryAdapter(
    private val model: MangaViewModel,
    private val coverViewModelFactory: MangaEntryCoverViewModel.Factory,
) : EntryDetailsBehavior {

    override val state: StateFlow<EntryDetailsScreenState> =
        model.state
            .map { it.toNeutral() }
            // Seed with the current mapped value so a screen collecting this renders without a
            // Loading frame. WhileSubscribed, not Eagerly: the adapter is rebuilt per composition
            // entry while its sharing coroutine lives in the model's scope, so an eager start left
            // one orphaned mapper running per re-entry (rotation, reader round-trips).
            .stateIn(model.viewModelScope, SharingStarted.WhileSubscribed(), model.state.value.toNeutral())

    private fun MangaViewModel.State.toNeutral(): EntryDetailsScreenState = when (this) {
        MangaViewModel.State.Loading -> EntryDetailsScreenState.Loading
        is MangaViewModel.State.Success -> toNeutralLoaded()
    }

    private fun MangaViewModel.State.Success.toNeutralLoaded(): EntryDetailsScreenState.Loaded {
        // Overlay the custom-info edits for DISPLAY only (header/description/tags); actions keep reading the
        // raw `manga`. The merge-display anchor collapses to a single entry + source, mirroring the manga UI.
        val displayManga = manga.withCustomInfo(customInfo)
        val displaySource = mergeDisplaySource ?: source
        // The inline carousel shows only for inline placement; in-menu still loads the pool, just hides it.
        val showInlineRelated = !model.recommendationsInMenu && (relatedLoading || relatedItems.isNotEmpty())
        return EntryDetailsScreenState.Loaded(
            entryId = EntryId.Manga(manga.id),
            details = EntryDetailsUiState(
                // The overlay applies in chip view too (matching novels): a custom title must not
                // vanish from the header just because a source chip is selected.
                header = (mergeDisplayManga?.withCustomInfo(customInfo) ?: displayManga).toEntryHeader(
                    sourceName = model.headerSourceName(this),
                    isStubSource = displaySource is StubSource,
                    sourceQuery = model.headerSourceQuery(this),
                ),
                favorite = manga.favorite,
                trackingCount = trackingCount,
                nextUpdate = manga.expectedNextUpdate,
                isUserIntervalMode = manga.fetchInterval < 0,
                description = displayManga.description,
                tags = displayManga.genre,
                notes = manga.notes,
                // Expand by default for EH/EXH galleries (tags are the content, no description).
                descriptionDefaultExpanded = isFromSource || isMetadataSource,
            ),
            chapters = EntryChapterListUiState(
                items = chapterListItems.map { it.toNeutralItem(chapterSourceNames()) },
                // The same rule the inline markers use, so the two cannot disagree, and unchanged when
                // the "hide missing" pref drops the separators from the rows.
                missingChapterCount = ChapterGap.total(
                    processedChapters.map {
                        ChapterGap.Neighbour(it.chapter.chapterNumber, it.chapter.name, it.chapter.mangaId)
                    },
                    descending = manga.sortDescending(),
                ),
                showHidden = showHidden,
                hasHiddenChapters = hasHiddenChapters,
                hiddenChapterIds = hiddenChapterIds,
                undatedChapterDate = UndatedChapterDate.NotApplicable,
            ),
            capabilities = EntryCapabilities(
                mangaPagePreviews = if (pagePreviewsState !is PagePreviewState.Unused && previewsRowCount > 0) {
                    MangaPagePreviewsCapability(state = pagePreviewsState, rowCount = previewsRowCount)
                } else {
                    null
                },
                mangaRelatedCarousel = if (showInlineRelated) {
                    MangaRelatedCarouselCapability(relatedItems, relatedTotalCount, relatedLoading)
                } else {
                    null
                },
                // Always present for manga: the chips render from a namespaced genre even before the
                // metadata object loads, and return nothing for a normal manga.
                mangaGallery = MangaGalleryCapability(
                    sourceId = displaySource.id,
                    rawGenre = manga.genre,
                    metadata = galleryMetadata,
                ),
            ),
            mergeSources = mergeSources,
            selectedSourceId = selectedSourceMangaId,
            hasActiveFilter = filterActive,
            isRefreshing = isRefreshingData,
            selection = visibleSelection(),
            resumeChapterId = model.getNextUnreadChapter()?.id,
            hasStarted = chapters.any { it.isRead },
            chaptersDownloadable = !source.isLocalOrStub(),
            hasViewedDownloads = downloadFolderOwner != null,
            showChapterNumberOnly = manga.displayMode == Manga.CHAPTER_DISPLAY_NUMBER,
            seedColor = seedColor,
        )
    }

    private fun MangaViewModel.State.Success.chapterSourceNames(): Map<Long, String> =
        mergeSourceLabels(mergeSources.associate { it.id to it.sourceName })

    private fun ChapterList.toNeutralItem(sourceNames: Map<Long, String>): EntryChapterListItem = when (this) {
        is ChapterList.Item -> EntryChapterListItem.Chapter(
            id = chapter.id,
            name = chapter.name,
            subtitle = chapterSubtitle(sourceNames[chapter.mangaId], chapter.scanlator),
            read = isRead,
            bookmark = isBookmarked,
            dateUpload = chapter.dateUpload,
            chapterNumber = chapter.chapterNumber,
            sourceOrder = chapter.sourceOrder,
            readProgress = model.readProgressLabel(chapter),
            downloadState = downloadState,
            downloadProgress = downloadProgress,
        )
        is ChapterList.MissingCount -> EntryChapterListItem.Missing(id = id, count = count)
    }

    private fun successState(): MangaViewModel.State.Success? =
        model.state.value as? MangaViewModel.State.Success

    private fun itemById(id: Long): ChapterList.Item? =
        successState()?.chapters?.firstOrNull { it.id == id }

    private fun chapterById(id: Long): Chapter? = itemById(id)?.chapter

    /** The selection over the filtered rows on screen, never over the full chapter list behind them. */
    private fun MangaViewModel.State.Success.visibleSelection(): Set<Long> = EntrySelection.selectedAmong(
        chapters.filter { it.selected }.mapTo(HashSet()) { it.id },
        processedChapters.map { it.id },
    )

    private fun selectedItems(): List<ChapterList.Item> {
        val state = successState() ?: return emptyList()
        val ids = state.visibleSelection()
        return state.chapters.filter { it.id in ids }
    }

    private fun selectedChapters(): List<Chapter> = selectedItems().map { it.chapter }

    // --- EntryDetailsBehavior: forward to the model (reconciling neutral ids -> the model's shapes). ---

    override fun toggleSelection(chapterId: Long, fromLongPress: Boolean) {
        val item = itemById(chapterId) ?: return
        model.toggleSelection(item, fromLongPress)
    }

    override fun selectAll() {
        model.toggleAllSelection(true)
    }
    override fun invertSelection() {
        model.invertSelection()
    }
    override fun clearSelection() {
        model.toggleAllSelection(false)
    }

    override fun markSelectedRead(read: Boolean) {
        model.markChaptersRead(selectedChapters(), read)
    }
    override fun bookmarkSelected(bookmark: Boolean) {
        model.bookmarkChapters(selectedChapters(), bookmark)
    }

    override fun markPreviousRead() {
        // The manga engine marks everything before a single pointer chapter as read; the pointer is the
        // selected chapter, matching the manga UI's own wiring.
        selectedItems().firstOrNull()?.let { model.markPreviousChapterRead(it.chapter) }
    }

    override fun runDownloadAction(action: DownloadAction) {
        model.runDownloadAction(action)
    }

    override fun onChapterDownloadAction(chapterId: Long, action: ChapterDownloadAction) {
        itemById(chapterId)?.let { model.runChapterDownloadActions(listOf(it), action) }
    }

    override fun downloadSelected() {
        val items = selectedItems()
        if (items.isNotEmpty()) model.runChapterDownloadActions(items, ChapterDownloadAction.START)
    }

    override fun deleteSelected() {
        model.showDeleteChapterDialog(selectedChapters())
    }

    override fun deleteChapters(chapterIds: List<Long>) {
        model.toggleAllSelection(false)
        model.deleteChapters(chapterIds.mapNotNull { chapterById(it) })
    }

    override fun chapterSwipe(chapterId: Long, action: LibraryPreferences.ChapterSwipeAction) {
        itemById(chapterId)?.let { model.chapterSwipe(it, action) }
    }

    override fun hideSelected() {
        model.hideSelected()
    }
    override fun unhideSelected() {
        model.unhideSelected()
    }
    override fun toggleShowHidden() {
        model.toggleShowHidden()
    }

    override fun showChangeCategoryDialog() {
        model.showChangeCategoryDialog()
    }

    override fun showCoverDialog() {
        model.showCoverDialog()
    }
    override fun createCoverViewModel(): EntryCoverViewModel<*> =
        coverViewModelFactory.create(shownCover()?.id ?: 0L)

    override fun coverKey(): String = (shownCover()?.id ?: 0L).toString()

    override fun isCoverAnchored(): Boolean =
        successState()?.let { it.mergeDisplayManga == null || it.mergeDisplayManga?.id == it.manga.id } != false

    /** The entry whose cover the page is showing: the selected chip's, falling back to the group's. */
    private fun shownCover(): Manga? = successState()?.let { it.mergeDisplayManga ?: it.manga }
    override fun showEditInfoDialog() {
        model.showEditMangaInfoDialog()
    }
    override fun saveInfo(edited: EntryEditInfoUi) {
        val manga = successState()?.manga ?: return
        model.saveMangaInfo(manga, edited)
    }
    override fun resetInfo() {
        val manga = successState()?.manga ?: return
        model.resetMangaInfo(manga)
    }

    override suspend fun autofillCandidates(): List<Pair<Track, Tracker>> = model.autofillCandidates()

    override suspend fun fetchTrackerMetadata(track: Track, tracker: Tracker): TrackMangaMetadata =
        model.fetchTrackerMetadata(track, tracker)

    override fun toggleFavorite() {
        model.toggleFavorite()
    }
    override fun selectSource(entryId: Long?) {
        model.selectSource(entryId)
    }
    override fun showClearDownloadsDialog() {
        model.showClearDownloadsDialog()
    }

    override fun clearDownloads() {
        model.clearDownloads()
    }

    override fun setFetchInterval(days: Int) {
        val manga = (model.state.value as? MangaViewModel.State.Success)?.manga ?: return
        model.setFetchInterval(manga, days)
    }

    override fun showManageSourcesDialog() {
        model.showManageSourcesDialog()
    }
    override fun reorderSources(orderedIds: List<Long>) {
        model.reorderSources(orderedIds)
    }
    override fun resetSourceOrder() {
        model.resetSourceOrder()
    }
    override fun splitSources(targetIds: List<Long>) {
        model.splitSources(targetIds)
    }
    override fun removeSourcesFromLibrary(targetIds: List<Long>) {
        model.removeSourcesFromLibrary(targetIds)
    }
    override fun removeAllSourcesFromLibrary() {
        model.removeAllSourcesFromLibrary()
    }

    override fun refresh() {
        model.fetchAllFromSource()
    }
    override fun dismissDialog() {
        model.dismissDialog()
    }
}

/** Manga page-preview thumbnails (adult sources). Filled only when the source actually supplies previews. */
@Immutable
data class MangaPagePreviewsCapability(
    val state: PagePreviewState,
    val rowCount: Int,
)

/** The related-manga (recommendations) carousel. Filled while loading or once candidates resolve. */
@Immutable
data class MangaRelatedCarouselCapability(
    val items: List<MangaViewModel.RelatedMangaItem>,
    val totalCount: Int,
    val isLoading: Boolean,
)

/**
 * A manga's namespaced-tag + gallery inputs. Present for every manga; the grouped tag chips build from
 * [rawGenre] (an adult source stores genre as "namespace:tag") even before [metadata] loads, and the
 * gallery-info card renders only once [metadata] is non-null. Both produce nothing for a normal manga.
 */
@Immutable
data class MangaGalleryCapability(
    val sourceId: Long,
    val rawGenre: List<String>?,
    val metadata: RaisedSearchMetadata?,
)
