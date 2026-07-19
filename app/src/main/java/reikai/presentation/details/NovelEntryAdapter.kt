package reikai.presentation.details

import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.presentation.manga.DownloadAction
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.model.TrackMangaMetadata
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import reikai.domain.entry.EntryId
import reikai.domain.novel.NovelChapterListEntry
import reikai.domain.novel.model.NovelChapter
import reikai.domain.novel.model.withCustomInfo
import reikai.presentation.novel.details.NovelDetailsScreenModel
import reikai.presentation.novel.details.NovelDetailsState
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.track.model.Track

/**
 * Adapts the live [NovelDetailsScreenModel] to the neutral [EntryDetailsBehavior]. The model keeps its own
 * novel-typed state machine; this maps its [NovelDetailsState] to the neutral [EntryDetailsScreenState] and
 * forwards the neutral action calls to the model's methods, resolving a neutral chapter id back to a
 * [NovelChapter] where a model method needs one. Symmetric with the manga adapter, so a single shared
 * `EntryDetailsContent` can drive both content types through this interface.
 *
 * Novel-only actions ([selectPage]) stay off the shared interface. Consumed by the shared Content once the
 * details screen unifies; today it exists to compile-prove the interface against the real novel model.
 */
class NovelEntryAdapter(
    private val model: NovelDetailsScreenModel,
) : EntryDetailsBehavior {

    override val state: StateFlow<EntryDetailsScreenState> =
        model.state
            .map { it.toNeutral() }
            .stateIn(model.screenModelScope, SharingStarted.Eagerly, EntryDetailsScreenState.Loading)

    private fun NovelDetailsState.toNeutral(): EntryDetailsScreenState = when (this) {
        NovelDetailsState.Loading -> EntryDetailsScreenState.Loading
        is NovelDetailsState.Failed -> EntryDetailsScreenState.Failed(message)
        is NovelDetailsState.Loaded -> toNeutralLoaded()
    }

    private fun NovelDetailsState.Loaded.toNeutralLoaded(): EntryDetailsScreenState.Loaded {
        val display = displayNovel.withCustomInfo(customInfo)
        return EntryDetailsScreenState.Loaded(
            entryId = EntryId.Novel(novel.id),
            details = EntryDetailsUiState(
                header = display.toEntryHeader(sourceName = model.headerSourceName(this), sourceSite = sourceUrl),
                favorite = novel.favorite,
                trackingCount = trackingCount,
                showIntervalButton = false,
                nextUpdate = null,
                isUserIntervalMode = false,
                description = display.description,
                tags = display.genre,
                notes = novel.notes,
                descriptionDefaultExpanded = false,
            ),
            chapters = EntryChapterListUiState(
                items = chapterListEntries.map { it.toNeutralItem(this) },
                missingChapterCount = missingChapterCount,
                showHidden = showHidden,
                hasHiddenChapters = hasHiddenChapters,
                hiddenChapterIds = hiddenChapterIds,
            ),
            capabilities = EntryCapabilities(
                novelPageSelector = if (pages.size > 1) {
                    NovelPageSelectorCapability(pages = pages, pageIndex = pageIndex, isPageLoading = isPageLoading)
                } else {
                    null
                },
            ),
            hasActiveFilter = readFilter != 0L || bookmarkedFilter != 0L || downloadedFilter != 0L,
            isRefreshing = isRefreshing,
            selection = selection,
            resumeChapterId = resumeChapter?.id,
            seedColor = seedColor,
        )
    }

    private fun NovelChapterListEntry.toNeutralItem(loaded: NovelDetailsState.Loaded): EntryChapterListItem =
        when (this) {
            is NovelChapterListEntry.Item -> EntryChapterListItem.Chapter(
                id = chapter.id,
                name = chapter.name,
                scanlator = null,
                read = chapter.read,
                bookmark = chapter.bookmark,
                dateUpload = chapter.dateUpload,
                chapterNumber = chapter.chapterNumber,
                sourceOrder = chapter.sourceOrder,
                // The novel row's own format: a hundredths scroll percent, shown only while unread + started.
                readProgress = (chapter.lastTextProgress / 100L).toInt()
                    .takeIf { !chapter.read && it > 0 }?.let { "$it%" },
                downloadState = loaded.downloadStateOf(chapter.id),
                downloadProgress = 0,
            )
            is NovelChapterListEntry.Missing -> EntryChapterListItem.Missing(id = id, count = count)
        }

    private fun chapterById(id: Long): NovelChapter? =
        (model.state.value as? NovelDetailsState.Loaded)?.chapters?.firstOrNull { it.id == id }

    // --- EntryDetailsBehavior: forward to the model (resolving id -> NovelChapter where needed). ---

    override fun toggleSelection(chapterId: Long, fromLongPress: Boolean) {
        model.toggleSelection(chapterId, fromLongPress)
    }

    override fun selectAll() {
        model.selectAll()
    }
    override fun invertSelection() {
        model.invertSelection()
    }
    override fun clearSelection() {
        model.clearSelection()
    }
    override fun markSelectedRead(read: Boolean) {
        model.markSelectedRead(read)
    }
    override fun bookmarkSelected(bookmark: Boolean) {
        model.bookmarkSelected(bookmark)
    }
    override fun markPreviousRead() {
        model.markPreviousRead(true)
    }

    override fun markChapterRead(chapterId: Long, read: Boolean) {
        chapterById(chapterId)?.let { model.markChapterRead(it, read) }
    }

    override fun toggleChapterBookmark(chapterId: Long) {
        chapterById(chapterId)?.let { model.toggleChapterBookmark(it) }
    }

    override fun runDownloadAction(action: DownloadAction) {
        model.runDownloadAction(action)
    }

    override fun onChapterDownloadAction(chapterId: Long, action: ChapterDownloadAction) {
        chapterById(chapterId)?.let { model.onChapterDownloadAction(it, action) }
    }

    override fun downloadSelected() {
        model.downloadSelected()
    }
    override fun deleteSelected() {
        model.deleteSelected()
    }

    override fun chapterSwipe(chapterId: Long, action: LibraryPreferences.ChapterSwipeAction) {
        chapterById(chapterId)?.let { model.chapterSwipe(it, action) }
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
    override fun applyCategories(categoryIds: List<Long>) {
        model.applyCategories(categoryIds)
    }
    override fun showCoverDialog() {
        model.showCoverDialog()
    }
    override fun showEditInfoDialog() {
        model.showEditNovelInfoDialog()
    }
    override fun saveInfo(edited: EntryEditInfoUi) {
        model.saveNovelInfo(edited)
    }
    override fun resetInfo() {
        model.resetNovelInfo()
    }
    override fun showTrackDialog() {
        model.showTrackDialog()
    }

    override suspend fun autofillCandidates(): List<Pair<Track, Tracker>> = model.autofillCandidates()

    override suspend fun fetchTrackerMetadata(track: Track, tracker: Tracker): TrackMangaMetadata =
        model.fetchTrackerMetadata(track, tracker)

    override fun toggleFavorite() {
        model.toggleFavorite()
    }
    override fun addFavoriteAnyway() {
        model.addFavoriteAnyway()
    }
    override fun selectSource(entryId: Long?) {
        model.selectSource(entryId)
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
        model.refresh()
    }
    override fun dismissDialog() {
        model.dismissDialog()
    }

    // --- Novel-only, off the shared interface. ---

    fun selectPage(index: Int) {
        model.selectPage(index)
    }
}
