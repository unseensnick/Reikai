package reikai.presentation.details

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.kanade.presentation.manga.DownloadAction
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.model.TrackMangaMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import reikai.domain.chapter.ChapterNumberEdit
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.track.model.Track

/**
 * How far a chapter's download has got, where the engine can say. A typed slot rather than a number, as
 * recents' RecentsDownloadProgress is: novels cannot answer, and a zero there reads as a stalled download.
 */
sealed interface EntryDownloadProgress {
    fun interface Live : EntryDownloadProgress {
        fun percentOf(chapterId: Long): Int
    }

    /** The novel downloader, which tracks no per-chapter progress. */
    data object Unsupported : EntryDownloadProgress
}

/**
 * [source] mapped to the neutral state, off the main thread since every chapter row is mapped. Seeded
 * with the current mapped value so a screen renders without a Loading frame. WhileSubscribed, not
 * Eagerly: an adapter is rebuilt per composition entry while this sharing coroutine lives in the
 * model's scope, so an eager start left one orphaned mapper running per re-entry (rotation, reader
 * round-trips).
 */
internal fun <S> ViewModel.neutralDetailsState(
    source: StateFlow<S>,
    toNeutral: (S) -> EntryDetailsScreenState,
): StateFlow<EntryDetailsScreenState> =
    source.map(toNeutral)
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), toNeutral(source.value))

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

    /** Read by each row's indicator alone, so a progress tick redraws the ring rather than the list. */
    val downloadProgress: EntryDownloadProgress

    // Chapter selection.
    fun toggleSelection(chapterId: Long, fromLongPress: Boolean)
    fun selectAll()
    fun invertSelection()
    fun clearSelection()

    // Mark read / bookmark. markPreviousRead is selection-based (mark everything before the single
    // selected chapter), not per-chapter, matching both models. It takes no flag: neither content type's UI
    // offers a "mark previous as unread", and the manga engine only supports marking previous as read.
    fun markSelectedRead(read: Boolean)
    fun bookmarkSelected(bookmark: Boolean)
    fun markPreviousRead()

    // Download.
    fun runDownloadAction(action: DownloadAction)
    fun onChapterDownloadAction(chapterId: Long, action: ChapterDownloadAction)
    fun downloadSelected()
    fun deleteSelected()

    /** Confirm the bulk delete (the chapter ids captured when the confirm dialog opened). */
    fun deleteChapters(chapterIds: List<Long>)

    /** Ask to clear downloads for what the screen is showing: the selected merge source, or every
     *  source when the unified view is on. */
    fun showClearDownloadsDialog()

    /** Confirm that clear for [entryIds]. Chapters, read state and history are untouched; only files are removed. */
    fun clearDownloads(entryIds: List<Long>)

    /** Sets smart update's interval to [days], or back to the predicted one for 0. */
    fun setFetchInterval(days: Int)
    fun chapterSwipe(chapterId: Long, action: LibraryPreferences.ChapterSwipeAction)

    // Hidden chapters.
    fun hideSelected()
    fun unhideSelected()
    fun toggleShowHidden()

    // Chapter number.

    /** Open the number dialog for the one selected chapter. */
    fun showChapterNumberDialog()

    /** Open the number dialog for [chapterId], from its out-of-line marker. */
    fun showChapterNumberDialog(chapterId: Long)

    /** Correct [edit]'s chapter to [number], or put the source's own back for null. */
    fun saveChapterNumber(edit: ChapterNumberEdit, number: Double?)

    // Categories.
    fun showChangeCategoryDialog()

    // Cover and custom-info edit.
    fun showCoverDialog()

    /**
     * The per-type full-cover ViewModel, resolved by the shared dialog host. Built for the entry the
     * source chip is showing, so the cover matches the page you opened it from.
     */
    fun createCoverViewModel(): EntryCoverViewModel<*>

    /** Identity of the entry [createCoverViewModel] builds for, so a chip switch gets its own model. */
    fun coverKey(): String
    fun showEditInfoDialog()
    fun saveInfo(edited: EntryEditInfoUi)
    fun resetInfo()

    // Tracking (the two suspend calls back the shared "Fill from tracker" button).
    fun showTrackDialog()
    suspend fun autofillCandidates(): List<Pair<Track, Tracker>>
    suspend fun fetchTrackerMetadata(track: Track, tracker: Tracker): TrackMangaMetadata

    // Favorite.
    fun toggleFavorite()

    /** Take [entryIds] out of the library once the heart's remove has settled them. Unlike
     *  [removeSourcesFromLibrary], their group stays, so a re-add rejoins it. */
    fun removeFromLibrary(entryIds: List<Long>)

    // Merge / multi-source. Keyed on Long entry ids on both sides, so no EntryId parameterization.
    // selectSource takes a nullable id: null selects the unified ("All") view, non-null a single source.
    fun selectSource(entryId: Long?)
    fun showManageSourcesDialog()
    fun reorderSources(orderedIds: List<Long>)
    fun resetSourceOrder()
    fun splitSources(targetIds: List<Long>)
    fun removeSourcesFromLibrary(targetIds: List<Long>)
    fun removeAllSourcesFromLibrary()

    fun refresh()
}
