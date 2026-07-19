package reikai.presentation.details

import eu.kanade.presentation.manga.DownloadAction
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.model.TrackMangaMetadata
import kotlinx.coroutines.flow.StateFlow
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.track.model.Track

/**
 * The neutral behaviour both content types expose to the shared details UI: the state stream plus the
 * action set the shared toolbar, action row, info column and chapter rows call. The shared screen shell
 * drives a details screen entirely through this, so a body change reaches manga and novels at once.
 *
 * Only genuinely shared actions live here. Novel-only actions (the page selector, plugin reload) and per-type
 * navigation (opening the reader, share and WebView intents) stay on the concrete adapter or the thin screen,
 * so the shared spine never rots into no-op methods. Each adapter maps its own model onto these signatures:
 * ids are neutral `Long`s and the adapter fans them back out to its model's own shapes (a manga chapter id to
 * a `Chapter`, the three-TriState filter call back to its packed form).
 */
interface EntryDetailsBehavior {
    val state: StateFlow<EntryDetailsScreenState>

    // Chapter selection.
    fun toggleSelection(chapterId: Long, fromLongPress: Boolean)
    fun selectAll()
    fun invertSelection()
    fun clearSelection()

    // Mark read / bookmark. The per-chapter calls key on the neutral chapter id; each adapter resolves it
    // back to its own chapter type. markPreviousRead is selection-based (mark everything before the single
    // selected chapter), not per-chapter, matching both models.
    fun markSelectedRead(read: Boolean)
    fun bookmarkSelected(bookmark: Boolean)
    fun markPreviousRead(read: Boolean)
    fun markChapterRead(chapterId: Long, read: Boolean)
    fun toggleChapterBookmark(chapterId: Long)

    // Download.
    fun runDownloadAction(action: DownloadAction)
    fun onChapterDownloadAction(chapterId: Long, action: ChapterDownloadAction)
    fun downloadSelected()
    fun deleteSelected()
    fun chapterSwipe(chapterId: Long, action: LibraryPreferences.ChapterSwipeAction)

    // Hidden chapters.
    fun hideSelected()
    fun unhideSelected()
    fun toggleShowHidden()

    // Categories.
    fun showChangeCategoryDialog()
    fun applyCategories(categoryIds: List<Long>)

    // Cover and custom-info edit.
    fun showCoverDialog()
    fun showEditInfoDialog()
    fun saveInfo(edited: EntryEditInfoUi)
    fun resetInfo()

    // Tracking (the two suspend calls back the shared "Fill from tracker" button).
    fun showTrackDialog()
    suspend fun autofillCandidates(): List<Pair<Track, Tracker>>
    suspend fun fetchTrackerMetadata(track: Track, tracker: Tracker): TrackMangaMetadata

    // Favorite and add-despite-duplicate.
    fun toggleFavorite()
    fun addFavoriteAnyway()

    // Merge / multi-source. Keyed on Long entry ids on both sides, so no EntryId parameterization.
    // selectSource takes a nullable id: null selects the unified ("All") view, non-null a single source.
    fun selectSource(entryId: Long?)
    fun showManageSourcesDialog()
    fun reorderSources(orderedIds: List<Long>)
    fun resetSourceOrder()
    fun splitSources(targetIds: List<Long>)
    fun removeSourcesFromLibrary(targetIds: List<Long>)
    fun removeAllSourcesFromLibrary()

    // Refresh and dialog dismiss.
    fun refresh()
    fun dismissDialog()
}
