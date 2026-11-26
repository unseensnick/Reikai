package reikai.presentation.details

import androidx.compose.material3.SnackbarHostState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.kanade.presentation.manga.DownloadAction
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.model.TrackMangaMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import mihon.app.di.AppGraph
import reikai.domain.chapter.ChapterNumberEdit
import reikai.domain.library.StoredType
import reikai.presentation.components.DownloadProgress
import reikai.presentation.components.VolumeMoves
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.track.model.Track

/**
 * [source] and [duplicates] (which no model state carries) mapped to the neutral state with its volume views
 * from [volumes], off the main thread since every chapter row is mapped. Never seeded by mapping, which ran the
 * whole list on Main in composition, so a first open shows Loading; a re-entry (rotation, a screen popped back
 * to) reads the same adapter ([keptForScreen]) and renders its last state at once. WhileSubscribed, so the
 * mapper stops while the screen is gone.
 */
internal fun <S, D> ViewModel.neutralDetailsState(
    source: StateFlow<S>,
    duplicates: StateFlow<D>,
    volumes: DetailsVolumeInputs,
    toNeutral: (S, D) -> EntryDetailsScreenState,
): StateFlow<EntryDetailsScreenState> =
    combine(source, duplicates, volumes.values) { s, d, v -> toNeutral(s, d).withVolumeViews(v) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), EntryDetailsScreenState.Loading)

/**
 * What [build] made for this model's screen under [key], held in the model's own store, so every re-entry to
 * the screen reuses it: each build starts sharing coroutines in the model's scope, which outlive the entry.
 */
internal fun <T : Any> ViewModel.keptForScreen(key: String, build: () -> T): T =
    (getCloseable<KeptForScreen<T>>(key) ?: KeptForScreen(build()).also { addCloseable(key, it) }).value

private class KeptForScreen<T>(val value: T) : AutoCloseable {
    override fun close() = Unit
}

/** The key both details screens keep their adapter under. */
internal const val DETAILS_ADAPTER_KEY = "reikai.details.adapter"

/** The settings of this model's details screen, kept with the model, as both screens build them. */
internal fun ViewModel.detailsSettings(graph: AppGraph): DetailsSettings = keptForScreen("reikai.details.settings") {
    DetailsSettings(graph.uiPreferences, graph.reikaiLibraryPreferences, viewModelScope)
}

/** The duplicate marking of this model's [type] details screen, over that type's reader switch [skipDuplicates]. */
internal fun ViewModel.detailsDuplicateMarking(
    graph: AppGraph,
    type: StoredType,
    skipDuplicates: Preference<Boolean>,
): DuplicateMarking = DuplicateMarking(
    type,
    graph.chapterCopyMarks,
    graph.reikaiLibraryPreferences.showSkippedDuplicates,
    skipDuplicates,
    viewModelScope,
    graph.appCoroutineScope,
)

/** The volume moves of this model's details screen, kept with the model so a rotation keeps their Undo. */
internal fun ViewModel.detailsVolumeMoves(snackbarHostState: SnackbarHostState): VolumeMoves =
    keptForScreen("reikai.details.volumeMoves") { VolumeMoves(viewModelScope, snackbarHostState) }

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

    /** Read by [chapterId]'s row indicator alone, so a progress tick redraws the ring rather than the list. */
    fun downloadProgress(chapterId: Long): DownloadProgress

    // Chapter selection, over [shown]: the rows on screen, in their order, which the screen's own filters
    // (Only copies, a picked volume) narrow below the model's list.
    fun toggleSelection(chapterId: Long, fromLongPress: Boolean, shown: List<Long>)
    fun selectAll(shown: List<Long>)
    fun invertSelection(shown: List<Long>)
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

    /** Links a local series whose folder is gone to [folder], which holds its files, then refreshes it there. */
    fun relinkSeries(folder: String)
    fun chapterSwipe(chapterId: Long, action: LibraryPreferences.ChapterSwipeAction)

    // Hidden chapters.
    fun hideSelected()
    fun unhideSelected()
    fun toggleShowHidden()

    // Hand-made corrections to the duplicate rule, offered while the list shows duplicates.
    val duplicateMarking: DuplicateMarking

    /** The selected chapters as a copy mark keys them. */
    fun selectedMarkable(): List<MarkableChapter>

    // A local series split into volumes: the chip it is left on, and a volume header's actions.
    val volumePicking: VolumePicking

    /** Shows only the chapters with copies, or every chapter; the page keeps the switch and hands it here. */
    fun setOnlyCopies(on: Boolean)

    /** Selects all of [chapterIds], or clears them when every one already is. */
    fun toggleChapterBlock(chapterIds: List<Long>)

    fun markChaptersRead(chapterIds: List<Long>, read: Boolean)

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

    /** The cover viewer, for [volume]'s own cover when a local volume is picked. */
    fun showCoverDialog(volume: EntryChapterListItem.Volume?)

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

    /** Every tag the merged series' sources carry, for Collate tags; null when the entry is not merged. */
    suspend fun mergedSourceTags(): List<String>?

    // Tracking: the two suspend calls back the shared "Fill from tracker" button.
    suspend fun autofillCandidates(): List<Pair<Track, Tracker>>
    suspend fun fetchTrackerMetadata(track: Track, tracker: Tracker): TrackMangaMetadata

    // Favorite.
    fun toggleFavorite()

    /** Take [entryIds] out of the library once the heart's remove has settled them. Unlike
     *  [EntryMergeActionHost.removeSourcesFromLibrary], their group stays, so a re-add rejoins it. */
    fun removeFromLibrary(entryIds: List<Long>)

    // Merge / multi-source. Keyed on Long entry ids on both sides, so no EntryId parameterization.
    // selectSource takes a nullable id: null selects the unified ("All") view, non-null a single source.
    fun selectSource(entryId: Long?)
    fun showManageSourcesDialog()

    /** Manage sources' actions, one shared host over the type's merge manager. */
    val mergeActions: EntryMergeActionHost

    fun refresh()
}
