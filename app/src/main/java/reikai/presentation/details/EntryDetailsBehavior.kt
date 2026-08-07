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
 * action set the shared toolbar, action row, info column and chapter rows call, so a body change
 * reaches manga and novels at once. Only genuinely shared actions live here; novel-only actions and
 * per-type navigation stay on the concrete adapter or the thin screen, so the shared spine never rots
 * into no-op methods. Ids are neutral `Long`s, and each adapter fans them back out to its model's own
 * shapes.
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
    // selected chapter), not per-chapter, matching both models. It takes no flag: neither content type's UI
    // offers a "mark previous as unread", and the manga engine only supports marking previous as read.
    fun markSelectedRead(read: Boolean)
    fun bookmarkSelected(bookmark: Boolean)
    fun markPreviousRead()
    fun markChapterRead(chapterId: Long, read: Boolean)
    fun toggleChapterBookmark(chapterId: Long)

    // Download.
    fun runDownloadAction(action: DownloadAction)
    fun onChapterDownloadAction(chapterId: Long, action: ChapterDownloadAction)
    fun downloadSelected()
    fun deleteSelected()

    /** Confirm the bulk delete (the chapter ids captured when the confirm dialog opened). */
    fun deleteChapters(chapterIds: List<Long>)
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

    /** The per-type full-cover ViewModel, resolved by the shared dialog host. */
    fun createCoverViewModel(): EntryCoverViewModel<*>
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
