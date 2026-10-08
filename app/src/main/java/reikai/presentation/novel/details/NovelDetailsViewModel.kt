package reikai.presentation.novel.details

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.manga.DownloadAction
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.presentation.util.formattedMessage
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.model.TrackMangaMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import reikai.data.coil.extractCoverColor
import reikai.data.coil.seedColor
import reikai.data.novel.NovelStatusCode
import reikai.data.novel.insertOpenedNovel
import reikai.data.novel.refreshNovelFromSource
import reikai.data.novel.storeRefreshedNovel
import reikai.data.novel.syncChaptersWithNovelSource
import reikai.data.novel.syncOpenedChapters
import reikai.data.novel.toNovel
import reikai.data.novel.updateNovelFetchInterval
import reikai.data.updateerror.refreshFailureMessage
import reikai.domain.chapter.ChapterNumberEdit
import reikai.domain.chapter.ChapterNumberHint
import reikai.domain.chapter.ChapterNumberOverrideRepository
import reikai.domain.chapter.DownloadCandidates
import reikai.domain.chapter.EditChapterNumber
import reikai.domain.chapter.ReadingOrder
import reikai.domain.chapter.hiddenChapterKey
import reikai.domain.download.downloadStateOf
import reikai.domain.download.rowDownloadChapters
import reikai.domain.download.runChapterAction
import reikai.domain.download.swipeDownloadAction
import reikai.domain.entry.EntryId
import reikai.domain.entry.ResetEntryInfo
import reikai.domain.library.ChapterSwipeActions
import reikai.domain.library.ContentType
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.library.chapterSwipeActions
import reikai.domain.merge.ChapterUnit
import reikai.domain.merge.DetailsRemoval
import reikai.domain.merge.DownloadTargets
import reikai.domain.merge.GroupChapterFlags
import reikai.domain.merge.GroupMarks
import reikai.domain.merge.refreshMergeGroup
import reikai.domain.novel.NovelChapterAggregation
import reikai.domain.novel.NovelChapterListEntry
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelChapterSettings
import reikai.domain.novel.NovelMergeManager
import reikai.domain.novel.NovelMergedChapterProvider
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.buildNovelChapterListEntries
import reikai.domain.novel.downloadedChapterIds
import reikai.domain.novel.gapPresent
import reikai.domain.novel.hiddenKey
import reikai.domain.novel.interactor.FilterNovelChaptersForDownload
import reikai.domain.novel.interactor.GetCustomNovelInfo
import reikai.domain.novel.interactor.GetNovelTracks
import reikai.domain.novel.interactor.RefreshNovelTracks
import reikai.domain.novel.interactor.RemoveNovelsFromLibrary
import reikai.domain.novel.interactor.SetCustomNovelInfo
import reikai.domain.novel.interactor.SetNovelChapterFlags
import reikai.domain.novel.interactor.SetNovelReadStatus
import reikai.domain.novel.interactor.UpdateNovel
import reikai.domain.novel.model.CustomNovelInfo
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.domain.novel.model.NovelChapterFlags
import reikai.domain.novel.model.NovelTrack
import reikai.domain.novel.model.NovelUpdate
import reikai.domain.novel.model.NovelWithChapterCount
import reikai.domain.novel.model.asNovelCover
import reikai.domain.novel.model.effectiveBookmarkedFilter
import reikai.domain.novel.model.effectiveDownloadedFilter
import reikai.domain.novel.model.effectiveHideChapterTitles
import reikai.domain.novel.model.effectiveReadFilter
import reikai.domain.novel.model.effectiveSortDescending
import reikai.domain.novel.model.effectiveSorting
import reikai.domain.novel.model.readingOrderComparator
import reikai.domain.novel.novelMissingChapterCount
import reikai.domain.novel.ownersOf
import reikai.domain.novel.text.NovelWords
import reikai.domain.novel.track.TrackNovelChapter
import reikai.domain.novel.track.toUiTrack
import reikai.domain.reader.ChapterListFilters
import reikai.domain.reader.novelChapterListFilters
import reikai.domain.source.healedCover
import reikai.domain.source.keptCover
import reikai.domain.track.EntryTrackPorts
import reikai.domain.track.autobind.AutoBindTrackers
import reikai.domain.track.autobind.TrackingButtonState
import reikai.domain.track.autobind.offerTrackers
import reikai.domain.track.autobind.trackingButtonState
import reikai.domain.track.source.SourceTrackerDispatcher
import reikai.novel.content.NovelWordDensity
import reikai.novel.download.NovelDownload
import reikai.novel.download.NovelDownloadCache
import reikai.novel.download.NovelDownloadManager
import reikai.novel.download.NovelDownloadedTexts
import reikai.novel.download.toDownloadState
import reikai.novel.install.LnPluginInstaller
import reikai.novel.source.NovelSource
import reikai.novel.source.NovelSourceManager
import reikai.presentation.browse.AddFavoriteResult
import reikai.presentation.browse.DuplicatePrompt
import reikai.presentation.details.AddToLibraryOffer
import reikai.presentation.details.ClearDownloadsTarget
import reikai.presentation.details.EntryAutoTrackOnMarkRead
import reikai.presentation.details.EntryEditInfoUi
import reikai.presentation.details.EntryManageSourceInfo
import reikai.presentation.details.EntryMergeActionHost
import reikai.presentation.details.EntryMergeGroupHost
import reikai.presentation.details.EntryMergeSource
import reikai.presentation.details.EntrySourceState
import reikai.presentation.details.EntryWebPage
import reikai.presentation.details.ShownWebPage
import reikai.presentation.details.buildTrackerAutofillCandidates
import reikai.presentation.details.downloadFolderOwner
import reikai.presentation.details.headerNamesWholeGroup
import reikai.presentation.details.hiddenChapterIdsIn
import reikai.presentation.details.offerToDeleteDownloads
import reikai.presentation.details.overridesOver
import reikai.presentation.details.unifiedViewMember
import reikai.presentation.details.webPageOf
import reikai.presentation.library.sourceKeyQuery
import reikai.presentation.novel.browse.NovelLibraryAdder
import reikai.presentation.novel.selectChaptersForDownloadAction
import reikai.presentation.selection.EntrySelection
import reikai.presentation.selection.SelectionState
import reikai.util.runCatchingCancellable
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.launchUI
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.track.model.Track
import tachiyomi.i18n.MR
import kotlin.time.Duration.Companion.seconds

/**
 * Light-novel details state holder, re-typed from Yōkai's `NovelDetailsViewModel` onto the Mihon
 * repos. DB-first: the stored novel + its chapters drive the screen; the source is hit only on first
 * open (no local chapters) or an explicit [refresh]. Owns favorite, categories, edit-info, chapter
 * sort/filter/display, multi-select read/bookmark, and the cover-tint seed. A merged series is
 * surfaced through the [NovelDetailsState.Loaded.displayNovel] seam.
 */
@AssistedInject
class NovelDetailsViewModel(
    /** A novel source id is a plugin string, so this is Reikai's shape rather than upstream's Long. */
    @Assisted private val sourceId: String,
    @Assisted private val novelUrl: String,
    @Assisted private val listingCover: String?,
    @Assisted val isFromSource: Boolean,
    private val novelRepo: NovelRepository,
    private val updateNovel: UpdateNovel,
    private val sourceTracker: SourceTrackerDispatcher,
    private val coverCache: CoverCache,
    private val resetEntryInfo: ResetEntryInfo,
    private val setNovelChapterFlags: SetNovelChapterFlags,
    private val chapterSettings: NovelChapterSettings,
    private val chapterRepo: NovelChapterRepository,
    private val downloadManagerProvider: () -> NovelDownloadManager,
    private val novelDownloadCache: NovelDownloadCache,
    private val sourceManager: NovelSourceManager,
    private val installer: LnPluginInstaller,
    private val filterChaptersForDownload: FilterNovelChaptersForDownload,
    private val novelLibraryAdder: NovelLibraryAdder,
    private val setNovelReadStatus: SetNovelReadStatus,
    private val novelPreferences: NovelPreferences,
    private val uiPreferences: UiPreferences,
    private val mergeManager: NovelMergeManager,
    private val mergedChapterProvider: NovelMergedChapterProvider,
    private val reikaiLibraryPreferences: ReikaiLibraryPreferences,
    private val libraryPreferences: LibraryPreferences,
    private val chapterNumberOverrides: ChapterNumberOverrideRepository,
    private val editChapterNumber: EditChapterNumber,
    private val context: Context,
    // Non-destructive custom-info overlay (edits never touch the novels row, so Reset is clean).
    private val getCustomNovelInfo: GetCustomNovelInfo,
    private val setCustomNovelInfo: SetCustomNovelInfo,
    private val getNovelTracks: GetNovelTracks,
    private val refreshNovelTracks: RefreshNovelTracks,
    private val trackNovelChapter: TrackNovelChapter,
    private val trackerManager: TrackerManager,
    private val trackPreferences: TrackPreferences,
    private val basePreferences: BasePreferences,
    private val removeNovelsFromLibrary: RemoveNovelsFromLibrary,
    private val trackPorts: EntryTrackPorts,
    private val autoBindTrackers: AutoBindTrackers,
    private val downloadedTexts: NovelDownloadedTexts,
) : ViewModel() {

    // Building the manager restores the persisted queue and can start the download worker, so it is
    // resolved on first read rather than at construction: every read sits inside a coroutine, which
    // keeps that work off the thread the screen opens on and skips it when nothing reads it at all.
    private val downloadManager: NovelDownloadManager get() = downloadManagerProvider()

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(
            sourceId: String,
            novelUrl: String,
            listingCover: String?,
            isFromSource: Boolean,
        ): NovelDetailsViewModel
    }

    /** Hosts the merge split/remove Undo snackbars; wired into the details Scaffold. */
    val snackbarHostState = SnackbarHostState()
    private val addToLibraryOffer = AddToLibraryOffer(snackbarHostState, context)

    // Every holder sits above the state and the init block: the state captures them, and init starts work
    // writing them, while the constructor is still running, and a holder declared below would be null then.

    /** The opened novel's plugin as the plugin host answered for it; source-dependent ops defer until set. */
    private val anchorSource = MutableStateFlow<SourceLookup>(SourceLookup.Pending)

    private val resolvedSource: NovelSource? get() = (anchorSource.value as? SourceLookup.Resolved)?.source

    /** Why the opened novel could not load. Shown only while there is no stored novel to show instead. */
    private val failure = MutableStateFlow<String?>(null)

    private var refreshJob: Job? = null
    private var seedExtracted = false

    /** The opened novel's id (the merge "current" source); set once the anchor resolves. */
    @Volatile
    private var anchorNovelId = -1L

    /** The page (index into [NovelDetailsState.Loaded.pages]) the chapter list is showing. */
    private val pageIndex = MutableStateFlow(0)

    /** novelId -> resolved source for every grouped sibling (unified rank, chips, reader routing). Novel-only
     *  (manga has no analogue), so it stays here and is populated inside the host's source resolver. */
    private val siblingSources = MutableStateFlow<Map<Long, NovelSource>>(emptyMap())

    /** novelId -> stored source for every grouped member, installed plugin or not: the hidden-chapter key. */
    @Volatile
    private var memberSources: Map<Long, String> = emptyMap()

    // The chapter multi-select. The state shows it retained to the visible rows; the verbs retain it before
    // they write, so a range anchor the list dropped stays dropped.
    private val chapterSelection = MutableStateFlow(SelectionState<Long>())

    /**
     * Shared merge read/observe wiring: the group ids, the selected source chip, the membership observer,
     * and the switcher chips. Written once in [EntryMergeGroupHost] (the manga model composes the same host),
     * so the read wiring can't drift between content types the way it did before. The two per-type seams:
     * [EntryMergeGroupHost.observe]'s anchor flow resolves the novel's anchor from url + source (and updates
     * [anchorNovelId]) before recomputing the group, and the source resolver does the async plugin-load,
     * builds [siblingSources], and returns the chips.
     */
    private val mergeGroup = EntryMergeGroupHost(
        mergeManager = mergeManager,
        initialIds = longArrayOf(),
        anchorChanges = novelRepo.getByUrlAndSourceAsFlow(novelUrl, sourceId)
            .filterNotNull()
            .onEach { anchorNovelId = it.id }
            .map { it.id },
        onSourceChange = { from, to ->
            chapterSelection.update { EntrySelection.afterChipFlip(it, from, to) }
            pageIndex.value = 0
        },
        resolveSources = { ids -> resolveMergeSources(ids) },
    )

    /** User-hidden chapters, keyed `"<source>|<chapterUrl>"` (restore-stable). Filtered out of the
     *  list unless [showHiddenFlow] is on (then shown dimmed). */
    private val hiddenChaptersPref = novelPreferences.hiddenChapters()
    private val numberHintMemo = ChapterNumberHint.Memo<NovelChapter>()

    /** Whether hidden chapters are temporarily shown (dimmed) so they can be unhidden. */
    private val showHiddenFlow = MutableStateFlow(false)

    /** Paged keys already lazily fetched, so an empty page doesn't re-fetch on every flow emission. */
    private val triedPages = java.util.Collections.synchronizedSet(HashSet<String>())

    private val dialog = MutableStateFlow<NovelDetailsDialog?>(null)

    private val isRefreshing = MutableStateFlow(false)

    private val isPageLoading = MutableStateFlow(false)

    /** Cover-derived tint, extracted once; see [updateSeedColor]. */
    private val seedColor = MutableStateFlow<Color?>(null)

    private val storedAnchor = novelRepo.getByUrlAndSourceAsFlow(novelUrl, sourceId)

    private val anchorIdChanges = storedAnchor.map { it?.id }.distinctUntilChanged()

    // DB-first: the stored anchor novel + the resolved merge group drive the chapter list. The unified
    // ("All") view pools every grouped source's chapters into one list (no page bar); a selected source
    // chip (or a non-merged novel) keeps its own per-page lazy list. Null while the novel has no stored row.
    private val chapterList: Flow<NovelDetailsState.Loaded?> = combine(
        combine(
            storedAnchor,
            mergeGroup.state,
            pageIndex,
            // In the combine only to rebuild with the chips once they resolve.
            mergeGroup.chips,
        ) { anchor, group, idx, _ -> ChapterInputs(anchor, group, idx) },
        // Rebuild on a hide/unhide or the show-hidden toggle.
        hiddenChaptersPref.changes(),
        showHiddenFlow,
    ) { inputs, _, _ -> inputs }
        .flatMapLatest { (stored, group, idx) ->
            // Sort, filter and display are the group's shared settings, the settings owner's (GroupChapterSettings).
            val anchor = stored?.let { chapterSettings.shown(it, group.ids.asList()) }
            when {
                anchor == null -> flowOf(null)
                group.ids.size > 1 && group.selected == null -> unifiedChapters(anchor, group)
                else -> singleChapters(anchor, group, idx)
            }
        }
        .onEach { it?.let { list -> updateSeedColor(list.displayNovel) } }

    /** The viewed member's page, keyed by (novel id, url, source). Never awaited by the list: a plugin
     *  answers every call behind one lock, so another call holding it would hold the whole page back. */
    private val webPages = ShownWebPage<Triple<Long, String, NovelSource>>(viewModelScope) { (id, url, source) ->
        source.webPageOf(id, url)
    }

    // Kept apart from the rows, so a plugin lookup or a page address landing after the page shows marks it
    // without a rebuild.
    private val sourcedChapterList = combine(chapterList, anchorSource, webPages.pages) { list, lookup, _ ->
        list?.withSource(lookup)
    }

    /** The live queue's states. Only the active ones (queued/downloading/error) live here; a finished download
     *  is read from [NovelDetailsState.Loaded.downloadedChapterIds] (disk-derived) instead. */
    private val downloadStates = flow { emitAll(downloadManager.queueState) }
        .map { queue -> queue.associate { it.chapterId to it.state.toDownloadState() } }

    /** The action-row Tracking button, counted by the tracking sheet's own offer rule, the one the manga
     *  details screen runs too. */
    private val trackingButton = anchorIdChanges
        .flatMapLatest { novelId ->
            if (novelId == null) {
                flowOf(TrackingButtonState(count = 0, hasTrackers = false))
            } else {
                // The port's read spans the merge group, so a track bound on a sibling source counts.
                val port = trackPorts.of(EntryId.Novel(novelId))
                combine(port.tracks(), trackerManager.loggedInTrackersFlow()) { tracks, loggedIn ->
                    val offered = offerTrackers(port, loggedIn, autoBindTrackers).offered
                    trackingButtonState(tracks.map { it.trackerId }, offered)
                }
            }
        }
        .distinctUntilChanged()
        .onStart { emit(TrackingButtonState(count = 0, hasTrackers = false)) }

    /** The novel's custom-info overlay, so the header, description, tags, and cover show the user's edits (the
     *  raw novel stays source-accurate). A write to custom_novel_info re-emits and the display follows. */
    private val customInfo = anchorIdChanges
        .flatMapLatest { novelId -> if (novelId == null) flowOf(null) else getCustomNovelInfo.subscribe(novelId) }

    val state: StateFlow<NovelDetailsState> = combine(
        sourcedChapterList,
        combine(downloadStates, trackingButton, customInfo, ::Triple),
        combine(chapterSelection, dialog, isRefreshing, isPageLoading, seedColor, ::Overlay),
        failure,
    ) { list, (downloads, tracking, info), overlay, failure ->
        list?.copy(
            downloadStates = downloads,
            trackingCount = tracking.count,
            hasLoggedInTrackers = tracking.hasTrackers,
            customInfo = info,
            dialog = overlay.dialog,
            selection = EntrySelection.retain(overlay.selection, list.chapters.map { it.id }).selection,
            isRefreshing = overlay.isRefreshing,
            isPageLoading = overlay.isPageLoading,
            seedColor = overlay.seedColor,
        )
            ?: failure?.let(NovelDetailsState::Failed)
            ?: NovelDetailsState.Loading
    }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5.seconds), NovelDetailsState.Loading)

    init {
        // Eager, unlike the state: the merge verbs read the group synchronously.
        mergeGroup.observe(viewModelScope)
        resolveSource()
        healPlaceholderCover()
    }

    /**
     * A row stored with no cover or a placeholder takes the cover of the listing that opened it, once it
     * exists: a details page can serve a placeholder where its listing served the cover.
     */
    private fun healPlaceholderCover() {
        if (keptCover(null, listingCover) == null) return
        viewModelScope.launchIO {
            val stored = novelRepo.getByUrlAndSourceAsFlow(novelUrl, sourceId).filterNotNull().first()
            healedCover(stored.thumbnailUrl, listingCover)?.let {
                novelRepo.update(NovelUpdate(stored.id) { thumbnailUrl = it })
            }
        }
    }

    /** Looks the opened novel's plugin up, then makes the first fetch when nothing is stored to show. */
    private fun resolveSource() {
        viewModelScope.launchIO {
            runCatchingCancellable { installer.ensureLoaded() }
            val lookup = sourceManager.get(sourceId)?.let(SourceLookup::Resolved) ?: SourceLookup.Missing
            anchorSource.value = lookup
            firstFetch(lookup)
        }
    }

    /**
     * Fetches a novel with no stored row (opened from browsing) or no stored chapters, once. Decided from the
     * stored rows, not the shown ones, so a filter hiding every chapter does not fetch again; see
     * viewmodel-migration.md.
     */
    private suspend fun firstFetch(lookup: SourceLookup) {
        val stored = novelRepo.getByUrlAndSource(novelUrl, sourceId)
        when (lookup) {
            SourceLookup.Pending -> Unit
            SourceLookup.Missing -> if (stored == null) {
                failure.value = context.stringResource(MR.strings.source_not_installed, sourceManager.nameOf(sourceId))
            }
            is SourceLookup.Resolved -> if (stored == null || chapterRepo.getByNovelId(stored.id).isEmpty()) {
                runCatchingCancellable { fetchAndSync(lookup.source, stored) }
                    .onFailure { e -> failure.value = with(context) { e.formattedMessage } }
            }
        }
    }

    /**
     * The viewed member's source fields: a sibling's own source, the anchor's once its lookup answered. The
     * All view downloads and opens its page through [servedNovelOf] instead, while the anchor's plugin is gone.
     */
    private suspend fun NovelDetailsState.Loaded.withSource(lookup: SourceLookup): NovelDetailsState.Loaded {
        val anchorPlugin = (lookup as? SourceLookup.Resolved)?.source
        val siblings = siblingSources.value
        val viewSource = viewedNovelSource(displayNovel.id, novel.id, siblings, anchorPlugin)
        val served = servedNovelOf(siblings, anchorPlugin)
        val serving = served ?: displayNovel
        val servingSource = served?.let { siblings[it.id] } ?: viewSource
        return copy(
            sourceName = viewSource?.name ?: sourceManager.nameOf(displayNovel.source),
            webPage = servingSource?.let { webPages.of(Triple(serving.id, serving.url, it)) },
            sourceHasSettings = viewSource?.settings != null,
            browsableSourceId = viewSource?.id,
            sourceState = novelSourceState(servingSource, serving.id == novel.id, lookup == SourceLookup.Missing),
            servedNovel = served,
        )
    }

    /** The All view's [unifiedViewMember] when it is not the anchor, in the chips' order, which is the group's. */
    private suspend fun NovelDetailsState.Loaded.servedNovelOf(
        siblings: Map<Long, NovelSource>,
        anchorPlugin: NovelSource?,
    ): Novel? {
        if (!headerNamesWholeGroup(mergeSources.size, selectedSourceNovelId)) return null
        val servedId = unifiedViewMember(novel.id, mergeSources.map { it.id }) { id ->
            id in siblings || (id == novel.id && anchorPlugin != null)
        }
        return if (servedId == novel.id) null else novelRepo.getById(servedId)
    }

    private data class ChapterInputs(
        val anchor: Novel?,
        val group: EntryMergeGroupHost.GroupState,
        val pageIndex: Int,
    )

    /** What the page lays over the chapter list: written by the verbs, never derived from the rows. */
    private data class Overlay(
        val selection: SelectionState<Long>,
        val dialog: NovelDetailsDialog?,
        val isRefreshing: Boolean,
        val isPageLoading: Boolean,
        val seedColor: Color?,
    )

    /** The anchor's plugin as the host answered: not asked yet, not installed, or loaded. */
    private sealed interface SourceLookup {
        data object Pending : SourceLookup
        data object Missing : SourceLookup
        data class Resolved(val source: NovelSource) : SourceLookup
    }

    /**
     * Resolve each grouped source + build the switcher chips (the host's per-type source resolver). Async:
     * the plugin host must be loaded before a source resolves. Also populates [siblingSources] (the map the
     * unified ranking + reader routing read), which has no manga analogue, before returning the chips, so the
     * chips-change that re-emits the chapter combine already sees the up-to-date map. Empty (and clears the
     * sibling map) when not merged.
     */
    private suspend fun resolveMergeSources(ids: LongArray): List<EntryMergeSource> {
        if (ids.size <= 1) {
            memberSources = emptyMap()
            siblingSources.value = emptyMap()
            return emptyList()
        }
        val members = ids.toList().mapNotNull { novelRepo.getById(it) }
        memberSources = members.associate { it.id to it.source }
        runCatchingCancellable { installer.ensureLoaded() }
        val resolved = HashMap<Long, NovelSource>()
        val chips = mutableListOf<EntryMergeSource>()
        for (novel in members) {
            val id = novel.id
            val src = sourceManager.get(novel.source)
            if (src != null) resolved[id] = src
            chips += EntryMergeSource(id, sourceManager.nameOf(novel.source))
        }
        siblingSources.value = resolved
        return chips
    }

    /** Unified ("All") view: pool every grouped source's chapters into one aggregated, reading-ordered
     *  list (no pagination, pages don't align across sources). Each chapter keeps its own novelId. */
    private fun unifiedChapters(anchor: Novel, group: EntryMergeGroupHost.GroupState): Flow<NovelDetailsState.Loaded> {
        val related = group.ids
        val flows = related.map { id -> chapterRepo.getByNovelIdAsFlow(id).map { id to it } }
        // Fold the download cache's change signal in so a download/delete rebuilds the list (the
        // downloaded state is disk-derived now, not a chapter-row flow).
        return combine(
            combine(flows) { pairs -> pairs.toMap() },
            novelDownloadCache.changes,
        ) { byNovel, _ -> byNovel }.mapLatest { byNovel ->
            // Read off the stored stitch, the same rows the library badge counts, rather than
            // stitching again here where the two could come to different answers.
            val pooled = byNovel.values.flatten()
            val stitch = mergedChapterProvider.stitchOf(anchor.id)
            val ordered = mergedChapterProvider.merged(pooled, stitch)
            val flags = group.novelRowFlags(pooled, ordered, stitch)
            val members = related.toList().mapNotNull { id -> if (id == anchor.id) anchor else novelRepo.getById(id) }
            // The chips resolve the plugins before this list is rebuilt for them, so the map is current here.
            val installed = siblingSources.value
            buildLoaded(
                group,
                anchor,
                anchor,
                ordered,
                emptyList(),
                0,
                flags.downloadedIds,
                downloadFolderOwnerOf(null, members, anchor),
                flags.marks,
                // Off each source's own list: [ordered] is restamped and keeps one copy per chapter.
                pooled.numberHints(),
            ).copy(
                downloadTargets = DownloadTargets.of(group.mergeScope, pooled, ordered, stitch, { it.id }) {
                    it.novelId in installed
                },
            )
        }
    }

    /** Which of [chapters] are on disk, each under its own novel: a unified merged list spans several. */
    private suspend fun downloadedIdsFor(chapters: List<NovelChapter>): Set<Long> =
        novelDownloadCache.downloadedChapterIds(chapters, novelRepo.ownersOf(chapters))

    /** Disk state is probed over every copy in [pooled], so a merged chapter reads as downloaded when any
     *  of the group's copies holds the file, not only the copy [shown] lists. */
    private suspend fun EntryMergeGroupHost.GroupState.novelRowFlags(
        pooled: List<NovelChapter>,
        shown: List<NovelChapter>,
        stitch: List<ChapterUnit>,
    ): GroupChapterFlags<NovelChapter> {
        val downloaded = downloadedIdsFor(pooled)
        return rowFlags(pooled, shown, stitch, { it.id }, { it.read }, { it.bookmark }) { downloaded }
    }

    /** Single-source view: the anchor (non-merged or its own chip) or a selected sibling, with that
     *  novel's own per-page lazy list. A page is fetched only for the anchor (its source is resolved);
     *  a selected sibling shows what's stored until a refresh-all fills it. */
    private suspend fun singleChapters(
        anchor: Novel,
        group: EntryMergeGroupHost.GroupState,
        idx: Int,
    ): Flow<NovelDetailsState.Loaded> {
        val selected = group.selected
        val isAnchorView = selected == null || selected == anchor.id
        val viewNovel = if (isAnchorView) anchor else (novelRepo.getById(selected!!) ?: anchor)
        val pages = computePages(viewNovel)
        if (pages.isNotEmpty() && idx >= pages.size) {
            // Written where the page count is known; the reset re-runs the list on the first page.
            pageIndex.value = 0
            return emptyFlow()
        }
        val pageKey = pages.getOrNull(idx)
        val chapterFlow = if (pageKey == null) {
            chapterRepo.getByNovelIdAsFlow(viewNovel.id)
        } else {
            chapterRepo.getByNovelIdAndPageAsFlow(viewNovel.id, pageKey)
        }
        // The siblings' rows are not shown here, but a chapter read on one of them still reads as read
        // on this chip, so they are watched too rather than read once: reading elsewhere has to reach
        // this list the same way it reaches the unified one.
        val siblingFlows = group.ids
            .filter { it != viewNovel.id }
            .map { chapterRepo.getByNovelIdAsFlow(it) }
        val siblingChapters = if (siblingFlows.isEmpty()) {
            flowOf(emptyList())
        } else {
            combine(siblingFlows) { rows -> rows.toList().flatten() }
        }
        // Fold the download cache's change signal in so a download/delete rebuilds the list, and the plugin's
        // answer so a page shown before it loaded is still fetched once it has.
        return combine(
            chapterFlow,
            novelDownloadCache.changes,
            siblingChapters,
            anchorSource,
        ) { chapters, _, siblings, _ -> chapters to siblings }.mapLatest { (chapters, siblings) ->
            val stitch = mergedChapterProvider.stitchOf(viewNovel.id)
            val flags = group.novelRowFlags(chapters + siblings, chapters, stitch)
            buildLoaded(
                group,
                anchor,
                viewNovel,
                chapters,
                pages,
                idx,
                flags.downloadedIds,
                downloadFolderOwnerOf(viewNovel, listOf(viewNovel), anchor),
                flags.marks,
                chapters.numberHints(),
            )
        }.onEach { loaded ->
            // Part of the shown list, so a page is fetched only while the page is on screen.
            if (pageKey != null && isAnchorView && loaded.storedRows.isEmpty()) {
                maybeFetchPage(viewNovel, pageKey, loaded.chapterFilters)
            }
        }
    }

    /** Page keys for the selector: "1".."N" for a `parsePage` source, distinct volume labels for a
     *  label-grouped one, or empty (single unpaged list, no selector). */
    private suspend fun computePages(novel: Novel): List<String> = when {
        novel.totalPages > 1L -> (1..novel.totalPages).map { it.toString() }
        else -> chapterRepo.getDistinctPages(novel.id).takeIf { it.size > 1 } ?: emptyList()
    }

    /** Build the list's half of [NovelDetailsState.Loaded] from the [anchor] (identity, favorite, chapter-view
     *  flags) and the [viewNovel] whose metadata the header shows (== anchor for the unified view, the
     *  selected sibling otherwise). Sort/filter follow the anchor's flags, which carry the group's owner's.
     *  The chips and the picked chip come from [group], the one the rows were built for: the live group may
     *  have moved on. */
    private fun buildLoaded(
        group: EntryMergeGroupHost.GroupState,
        anchor: Novel,
        viewNovel: Novel,
        chapters: List<NovelChapter>,
        pages: List<String>,
        pageIndex: Int,
        downloadedChapterIds: Set<Long>,
        downloadFolderOwner: Novel?,
        marks: GroupMarks,
        numberHints: Map<Long, ChapterNumberHint.Hint>,
    ): NovelDetailsState.Loaded {
        val hidden = hiddenChaptersPref.get()
        val rows = shownRows(anchor, chapters, hidden, downloadedChapterIds, marks)
        val view = rows.view
        val display = view.visible
        val sortDescending = anchor.effectiveSortDescending(novelPreferences)
        // When showing hidden, mark which displayed rows are hidden (dimmed + drives Hide/Unhide).
        val hiddenChapterIds = hiddenChapterIdsIn(display, hidden, view.showHidden, ::hiddenKey) { it.id }
        // Counted against every chapter, filtered out or hidden, so hiding one never makes a gap.
        val present = chapters.gapPresent()
        val isHiddenRow = { chapter: NovelChapter -> chapter.id in hiddenChapterIds }
        // A paged list holds one page, so its ends border the other pages, not chapters that are missing.
        val paged = pages.isNotEmpty()
        // The header total covers what the list itself would mark, so the two can never disagree.
        // Always shown when > 0; the inline rows are pref-gated.
        val missingChapterCount = novelMissingChapterCount(display, sortDescending, present, paged, isHiddenRow)
        val chapterListEntries = if (novelPreferences.hideMissingChapters().get()) {
            display.map { NovelChapterListEntry.Item(it) }
        } else {
            buildNovelChapterListEntries(display, sortDescending, present, paged, isHiddenRow)
        }
        return NovelDetailsState.Loaded(
            novel = anchor,
            displayNovel = viewNovel,
            chapters = display,
            chapterListEntries = chapterListEntries,
            missingChapterCount = missingChapterCount,
            numberHints = numberHints,
            showHidden = view.showHidden,
            hiddenChapterIds = hiddenChapterIds,
            hasHiddenChapters = view.hasHidden,
            pages = pages,
            pageIndex = if (pages.isEmpty()) 0 else pageIndex.coerceIn(0, pages.lastIndex),
            downloadedChapterIds = downloadedChapterIds,
            downloadFolderOwner = downloadFolderOwner,
            marks = marks,
            resumeChapter = rows.resume,
            hasStarted = chapters.any { marks.isRead(it.id, it.read) },
            sorting = anchor.effectiveSorting(novelPreferences),
            sortDescending = sortDescending,
            readFilter = anchor.effectiveReadFilter(novelPreferences),
            bookmarkedFilter = anchor.effectiveBookmarkedFilter(novelPreferences),
            downloadedFilter = anchor.effectiveDownloadedFilter(novelPreferences),
            downloadedFilterLocked = basePreferences.downloadedOnly.get(),
            hideChapterTitles = anchor.effectiveHideChapterTitles(novelPreferences),
            mergeSources = mergeGroup.chipsOf(group),
            selectedSourceNovelId = group.selected,
            chapterSwipeActions = libraryPreferences.chapterSwipeActions(),
            storedRows = chapters,
        )
    }

    /** Whether the screen takes its tint from the cover. Read once, like manga's. */
    val themeCoverBased = uiPreferences.themeCoverBased.get()

    /**
     * Seed the header tint from the cover once. Computed whatever [themeCoverBased] says, as manga's is,
     * because the edit-info dialog tints from the cover either way.
     */
    private fun updateSeedColor(novel: Novel) {
        if (seedExtracted) return
        val url = novel.thumbnailUrl?.takeIf { it.isNotBlank() } ?: return
        if (novel.id <= 0L) return
        seedExtracted = true
        val cover = novel.asNovelCover(url)
        viewModelScope.launchIO {
            val color = EntryId.Novel(novel.id).seedColor { context.extractCoverColor(cover) } ?: return@launchIO
            seedColor.value = Color(color)
        }
    }

    /** parseNovel + persist the source's metadata + sync the first page's chapters, then
     *  predict the next update. The reactive flow then re-emits the updated novel/chapter list. A novel
     *  opened from Browse is inserted non-favorite. Returns the persisted novel (carries the refreshed
     *  `totalPages`). */
    private suspend fun fetchAndSync(src: NovelSource, existing: Novel?): Novel? {
        val sourceNovel = src.parseNovel(existing?.url ?: novelUrl)
        if (existing == null) {
            return insertOpenedNovel(
                sourceNovel,
                src.id,
                novelRepo,
                chapterRepo,
                libraryPreferences,
                chapterNumberOverrides,
                downloadManager,
            )
        }
        val parsed = sourceNovel.toNovel(sourceId = src.id, favorite = existing.favorite)
        val target = storeRefreshedNovel(existing, parsed, novelRepo, libraryPreferences, downloadManager, coverCache)
        syncOpenedChapters(
            sourceNovel,
            target,
            novelRepo,
            chapterRepo,
            libraryPreferences,
            chapterNumberOverrides,
            downloadManager,
        )
        return target
    }

    /** Lazily fetch a paged source's page when it has no stored rows yet. Skipped while a filter is
     *  active (0 rows may just mean the filter hid them, not that the page is unfetched) and once a
     *  page has been tried (an empty page must not re-fetch on every emission). */
    private fun maybeFetchPage(novel: Novel, pageKey: String, filters: ChapterListFilters) {
        val src = resolvedSource ?: return
        if (filters.isActive) return
        if (!triedPages.add(pageKey)) return
        viewModelScope.launchIO {
            isPageLoading.value = true
            try {
                src.parsePage(novel.url, pageKey)?.chapters?.takeIf { it.isNotEmpty() }?.let {
                    syncChaptersWithNovelSource(
                        it,
                        novel,
                        chapterRepo,
                        novelRepo,
                        libraryPreferences,
                        chapterNumberOverrides,
                        page = pageKey,
                        novelDownloadManager = downloadManager,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
            } finally {
                isPageLoading.value = false
            }
        }
    }

    fun selectPage(index: Int) {
        val loaded = state.value as? NovelDetailsState.Loaded ?: return
        if (index < 0 || index >= loaded.pages.size || index == loaded.pageIndex) return
        clearSelection()
        pageIndex.value = index
        dismissDialog()
    }

    fun showPageSelectorDialog() = showDialog(NovelDetailsDialog.PageSelector)

    /** The download directory the details overflow's Open folder opens. */
    fun viewedDownloadDir() = (state.value as? NovelDetailsState.Loaded)
        ?.downloadFolderOwner
        ?.let(downloadManager::findNovelDir)

    /** Counted off the download cache, which is in memory, so running it on every emission is cheap. */
    private fun downloadFolderOwnerOf(viewed: Novel?, group: List<Novel>, anchor: Novel): Novel? =
        downloadFolderOwner(viewed, group, { it.id == anchor.id }, { downloadManager.getDownloadCount(it) > 0 })

    /** Clears downloads for what the screen shows ([EntryMergeGroupHost.clearDownloadsTarget]). */
    fun showClearDownloadsDialog() {
        val loaded = state.value as? NovelDetailsState.Loaded ?: return
        showDialog(NovelDetailsDialog.ClearDownloads(mergeGroup.clearDownloadsTarget(loaded.novel.id)))
    }

    fun clearDownloads(novelIds: List<Long>) {
        viewModelScope.launchNonCancellable {
            novelIds.forEach { id -> novelRepo.getById(id)?.let { downloadManager.awaitDeleteNovel(it) } }
        }
    }

    /** Opens the viewed source's settings: the selected chip's source on a merged novel, else its own. */
    fun showSourceSettings() {
        val loaded = state.value as? NovelDetailsState.Loaded ?: return
        val viewed =
            viewedNovelSource(loaded.displayNovel.id, loaded.novel.id, siblingSources.value, resolvedSource) ?: return
        if (viewed.settings == null) return
        showDialog(NovelDetailsDialog.SourceSettings(viewed))
    }

    // Shared split / remove / reorder actions. showManageSourcesDialog stays below: its body genuinely
    // diverges.
    private val mergeActions = EntryMergeActionHost(
        scope = viewModelScope,
        snackbarHostState = snackbarHostState,
        context = context,
        group = mergeGroup,
        anchorId = { anchorNovelId },
        mergeManager = mergeManager,
        dismissDialog = ::dismissDialog,
        removal = removeNovelsFromLibrary,
        offerToDeleteDownloads = ::promptDeleteDownloadsOnRemoved,
    )

    /** Switch the chapter view between the unified list (null) and a single grouped source's list. */
    fun selectSource(novelId: Long?) = mergeGroup.selectSource(novelId)

    /** Header source label: the localized unified ("All") label for the merged all-view, else the source
     *  name. Resolved here (the model has the context) so the neutral-state mapping needs no composable. */
    fun headerSourceName(loaded: NovelDetailsState.Loaded): String =
        if (headerNamesWholeGroup(loaded.mergeSources.size, loaded.selectedSourceNovelId)) {
            context.stringResource(MR.strings.merge_unified)
        } else {
            loaded.sourceName
        }

    /** The library query for the header's source, or null where the header names the whole merged group. */
    fun headerSourceQuery(loaded: NovelDetailsState.Loaded): String? =
        if (headerNamesWholeGroup(loaded.mergeSources.size, loaded.selectedSourceNovelId)) {
            null
        } else {
            sourceKeyQuery(loaded.displayNovel.source)
        }

    fun showManageSourcesDialog() {
        viewModelScope.launchIO {
            val loaded = state.value as? NovelDetailsState.Loaded ?: return@launchIO
            if (loaded.mergeSources.size <= 1) return@launchIO
            // Per-source chapters, resolved on open for both the coverage-hint counts and the trunk rank.
            val chaptersByNovel = loaded.mergeSources.associate { it.id to chapterRepo.getByNovelId(it.id) }
            val withCounts = loaded.mergeSources.associateBy({ it.id }) {
                EntryManageSourceInfo(it.id, it.sourceName, chaptersByNovel[it.id]?.size ?: 0)
            }
            // Order the rows by the same ranking aggregation uses, so the primary source opens on top even
            // under the global order (no override). memberRanking non-empty == override on.
            val memberRanking = mergeManager.overrideRankingMemberIds(anchorNovelId)
            val siblings = siblingSources.value
            val sourceIdByNovel = chaptersByNovel.keys.associateWith { siblings[it]?.id.orEmpty() }
            val ranked = NovelChapterAggregation.rankedMemberIds(
                chaptersByNovel,
                sourceIdByNovel,
                reikaiLibraryPreferences.preferredNovelSources.get(),
                memberRanking,
            )
            val orderedSources = ranked.mapNotNull { withCounts[it] }
            showDialog(NovelDetailsDialog.ManageSources(orderedSources, memberRanking.isNotEmpty()))
        }
    }

    fun reorderSources(orderedIds: List<Long>) = mergeActions.reorderSources(orderedIds)

    fun resetSourceOrder() = mergeActions.resetSourceOrder()

    fun splitSources(targetIds: List<Long>) = mergeActions.splitSources(targetIds)

    fun removeSourcesFromLibrary(targetIds: List<Long>) = mergeActions.removeSourcesFromLibrary(targetIds)

    fun removeAllSourcesFromLibrary() = mergeActions.removeAllSourcesFromLibrary()

    /** Stale-then-fresh: the cached list stays under a spinner while the sync runs, then the flow
     *  swaps in fresh rows. Read/bookmark are preserved by the sync. Deduped against concurrent runs.
     *
     *  For a paged source: [refreshNovelFromSource] refreshes page 1 + the page count and re-fetches
     *  the previously-last page through any newly-opened ones, and the page the user is viewing is
     *  re-fetched too if the walk didn't already cover it. Bounded, never a full fetch-all. */
    fun refresh() {
        val loaded = state.value as? NovelDetailsState.Loaded ?: return
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launchIO {
            isRefreshing.value = true
            try {
                val toDownload = mutableListOf<NovelChapter>()
                // The anchor's refreshed novel drives the viewed-page fix below.
                var anchorSrc = resolvedSource
                var anchorUpdated: Novel? = null
                val firstError = refreshMergeGroup(
                    anchor = {
                        runCatchingCancellable {
                            val src = anchorSrc ?: sourceManager.getOrThrow(loaded.novel.source)
                            anchorSrc = src
                            refreshNovel(src, loaded.novel, toDownload).also { anchorUpdated = it }
                        }
                    },
                    siblings = mergeGroup.relatedIds
                        .filter { it != loaded.novel.id }
                        .mapNotNull { novelRepo.getById(it) },
                    sourceOf = { siblingSources.value[it.id] },
                    refresh = { novel, src -> runCatchingCancellable { refreshNovel(src, novel, toDownload) } },
                )
                if (toDownload.isNotEmpty()) downloadManager.downloadChapters(toDownload)
                // Force-refresh the viewed page when viewing the anchor's own (paged) list and the walk
                // skipped it (a middle page); the unified view has no pages so this is a no-op there.
                val viewedAnchor = anchorSrc
                val updatedAnchor = anchorUpdated
                if (viewedAnchor != null && updatedAnchor != null &&
                    (loaded.selectedSourceNovelId == null || loaded.selectedSourceNovelId == loaded.novel.id)
                ) {
                    forceRefreshViewedPage(loaded, updatedAnchor, viewedAnchor)
                }
                firstError?.let { e ->
                    val message = with(context) { e.refreshFailureMessage() }
                    viewModelScope.launchUI { snackbarHostState.showSnackbar(message) }
                }
            } finally {
                isRefreshing.value = false
            }
        }
    }

    /** Shared favorite-refresh: parseNovel + merge + sync page 1 + walk newly-opened pages. Bounded,
     *  never a full fetch-all. Returns the refreshed novel and adds the new chapters the
     *  download-new-chapters setting takes to [toDownload]; a failure is the caller's to report. */
    private suspend fun refreshNovel(src: NovelSource, novel: Novel, toDownload: MutableList<NovelChapter>): Novel =
        refreshNovelFromSource(
            novel,
            src,
            chapterRepo,
            novelRepo,
            libraryPreferences,
            chapterNumberOverrides,
            coverCache,
            novelDownloadManager = downloadManager,
            manualFetch = true,
        ).also { toDownload += filterChaptersForDownload.await(it.novel, it.newChapters) }.novel
    private suspend fun forceRefreshViewedPage(loaded: NovelDetailsState.Loaded, updated: Novel, src: NovelSource) {
        val newTotalPages = updated.totalPages
        if (newTotalPages <= 1L) return
        val walkFrom = maxOf(2L, loaded.novel.totalPages)
        val curPage = loaded.pages.getOrNull(loaded.pageIndex)?.toLongOrNull() ?: return
        if (curPage > 1L && curPage !in walkFrom..newTotalPages) {
            val key = curPage.toString()
            runCatchingCancellable {
                src.parsePage(updated.url, key)?.chapters?.takeIf { it.isNotEmpty() }?.let {
                    syncChaptersWithNovelSource(
                        it,
                        updated,
                        chapterRepo,
                        novelRepo,
                        libraryPreferences,
                        chapterNumberOverrides,
                        page = key,
                        novelDownloadManager = downloadManager,
                    )
                }
            }
        }
    }

    fun toggleFavorite() {
        viewModelScope.launchIO {
            val novel = (state.value as? NovelDetailsState.Loaded)?.novel ?: return@launchIO
            if (!novel.favorite) {
                // Warn on a similarly-named library novel before adding (mirrors MangaViewModel, pinned by
                // duplicatePrompt, which both adders' findDuplicates build through).
                novelLibraryAdder.findDuplicates(novel.id, novel.title)?.let { prompt ->
                    showDialog(NovelDetailsDialog.DuplicateNovel(prompt))
                    return@launchIO
                }
                addToLibrary(novel)
            } else {
                val removal = mergeGroup.removal(novel.id)
                if (removal.asksForGroup) {
                    showDialog(NovelDetailsDialog.RemoveFromLibrary(removal))
                } else {
                    removeFromLibrary(removal.targets(removeGrouped = false))
                }
            }
        }
    }

    fun removeFromLibrary(novelIds: List<Long>) {
        viewModelScope.launchIO {
            promptDeleteDownloadsOnRemoved(removeNovelsFromLibrary.await(novelIds))
        }
    }

    private suspend fun promptDeleteDownloadsOnRemoved(removedIds: List<Long>) {
        snackbarHostState.offerToDeleteDownloads(
            context = context,
            removed = removedIds.mapNotNull { novelRepo.getById(it) },
            hasDownloads = { downloadManager.getDownloadCount(it) > 0 },
        ) { downloadManager.awaitDeleteNovel(it) }
    }

    /** Proceed with the add after the possible-duplicate dialog's "Add anyway". */
    fun addFavoriteAnyway() {
        viewModelScope.launchIO {
            val novel = (state.value as? NovelDetailsState.Loaded)?.novel ?: return@launchIO
            addToLibrary(novel)
        }
    }

    /**
     * Add-time grouping, through the adder's shared add sequence: the group's categories win, then the
     * default, and a picker it has to raise writes nothing until its confirm. Only the picks the user
     * chose: the duplicate list is fuzzy, so merging every match would fuse distinct series.
     */
    fun addToExistingGroup(selectedIds: List<Long>) {
        viewModelScope.launchIO {
            val novel = (state.value as? NovelDetailsState.Loaded)?.novel ?: return@launchIO
            showPickerIfAsked(novelLibraryAdder.addToExistingGroup(novel.id, selectedIds), joinGroup = selectedIds)
        }
    }

    // A picker defers both writes to applyCategories, so backing out adds nothing.
    private suspend fun addToLibrary(novel: Novel) =
        showPickerIfAsked(novelLibraryAdder.addStoredToLibrary(novel.id), joinGroup = emptyList())

    private fun showPickerIfAsked(result: AddFavoriteResult, joinGroup: List<Long>) {
        if (result !is AddFavoriteResult.NeedsCategoryChoice) return
        showDialog(NovelDetailsDialog.ChangeCategory(result.initialSelection, joinGroup))
    }

    fun showChangeCategoryDialog() {
        viewModelScope.launchIO {
            val novel = (state.value as? NovelDetailsState.Loaded)?.novel ?: return@launchIO
            // No early return on an empty list: the shared picker answers that case with the prompt to
            // go and make one, where bailing here left the action doing nothing at all.
            val selection = novelLibraryAdder.categoryPickerPrompt(novel.id)
            showDialog(NovelDetailsDialog.ChangeCategory(selection))
        }
    }

    /**
     * The picker's confirm. It owes the favorite when the add deferred it here, and the same dialog
     * also serves an in-library novel changing its categories, which [NovelLibraryAdder.favoriteForAdd]
     * leaves alone rather than re-writing. A group add's favorite joins [joinGroup]'s group as one unit.
     */
    fun applyCategories(categoryIds: List<Long>, joinGroup: List<Long>) {
        viewModelScope.launchIO {
            val novel = (state.value as? NovelDetailsState.Loaded)?.novel ?: return@launchIO
            if (joinGroup.isNotEmpty()) {
                novelLibraryAdder.confirmGroupCategories(novel.id, joinGroup, categoryIds)
            } else {
                novelLibraryAdder.confirmAddCategories(novel.id, categoryIds)
            }
            dismissDialog()
        }
    }

    fun showEditNovelInfoDialog() {
        showDialog(NovelDetailsDialog.EditInfo)
    }

    /** Apply Edit-info as a non-destructive overlay: store a value only when it differs from the source
     *  row (a blank field, or an Unknown status, stores nothing, so that field tracks the source again).
     *  The novels row is never touched, so Reset restores the source cleanly. Takes the neutral
     *  [EntryEditInfoUi] (as the manga side already does) and runs non-cancellable, so a mid-write screen
     *  close does not drop the edit (mirrors MangaViewModel.saveMangaInfo, pinned by [overridesOver]). */
    fun saveNovelInfo(edited: EntryEditInfoUi) {
        val n = (state.value as? NovelDetailsState.Loaded)?.novel ?: return
        viewModelScope.launchNonCancellable {
            setCustomNovelInfo.set(edited.toCustomNovelInfo(n))
        }
        dismissDialog()
    }

    /** Clear every override; the source row shows through again (no re-fetch needed, it was never overwritten). */
    fun resetNovelInfo() {
        val n = (state.value as? NovelDetailsState.Loaded)?.novel ?: return
        // Non-cancellable, as the save and manga's reset are, so leaving the screen cannot stop it halfway.
        viewModelScope.launchNonCancellable { resetEntryInfo.await(EntryId.Novel(n.id)) }
        dismissDialog()
    }

    /** Bound trackers eligible for "Fill from tracker", spanning the merge group (mirrors RefreshNovelTracks). */
    suspend fun autofillCandidates(): List<Pair<Track, Tracker>> {
        val novelId = (state.value as? NovelDetailsState.Loaded)?.novel?.id ?: return emptyList()
        return buildTrackerAutofillCandidates(getNovelTracks.awaitGroup(novelId).map { it.toUiTrack() }, trackerManager)
    }

    suspend fun fetchTrackerMetadata(track: Track, tracker: Tracker): TrackMangaMetadata =
        tracker.getMangaMetadata(track)

    fun setSortMode(sort: Long) =
        changeChapterSettings { setNovelChapterFlags.awaitSetSortingModeOrFlipOrder(it, sort) }

    fun setFilters(read: Long, bookmarked: Long, downloaded: Long) =
        changeChapterSettings { setNovelChapterFlags.awaitSetFilters(it, read, bookmarked, downloaded) }

    fun setHideChapterTitles(hide: Boolean) =
        changeChapterSettings { setNovelChapterFlags.awaitSetHideTitles(it, hide) }

    /**
     * Write the current view as the global chapter-settings default and drop this novel's overrides, and
     * with [applyToLibrary] every library novel's, as manga's setCurrentSettingsAsDefault does.
     */
    fun setChapterSettingsAsDefault(applyToLibrary: Boolean) {
        val loaded = state.value as? NovelDetailsState.Loaded ?: return
        viewModelScope.launchNonCancellable {
            novelPreferences.defaultChapterSortOrder().set(loaded.sorting)
            novelPreferences.defaultChapterSortDescending().set(loaded.sortDescending)
            novelPreferences.defaultChapterHideTitles().set(loaded.hideChapterTitles)
            novelPreferences.defaultChapterFilterUnread().set(loaded.readFilter)
            novelPreferences.defaultChapterFilterBookmarked().set(loaded.bookmarkedFilter)
            novelPreferences.defaultChapterFilterDownloaded().set(loaded.downloadedFilter)
            chapterSettings.change(loaded.novel.id, mergeGroup.relatedIds.asList()) {
                setNovelChapterFlags.awaitClearLocalOverrides(loaded.novel)
            }
            if (applyToLibrary) setNovelChapterFlags.awaitClearLibraryLocalOverrides()
            snackbarHostState.showSnackbar(message = context.stringResource(MR.strings.chapter_settings_updated))
        }
    }

    fun resetChapterSettings() =
        changeChapterSettings { setNovelChapterFlags.awaitClearLocalOverrides(it) }

    /** Runs [change] on the novel as shown, carrying its group's shared settings, then gives every other
     *  member the result, so a merged series keeps one setting (GroupChapterSettings). */
    private fun changeChapterSettings(change: suspend (Novel) -> Unit) {
        viewModelScope.launchIO {
            val n = (state.value as? NovelDetailsState.Loaded)?.novel ?: return@launchIO
            chapterSettings.change(n.id, mergeGroup.relatedIds.asList()) { change(n) }
        }
    }

    fun showChapterSettingsDialog() = showDialog(NovelDetailsDialog.ChapterSettings)

    fun showSetFetchIntervalDialog() = showDialog(NovelDetailsDialog.SetFetchInterval)

    /** Whether the interval can be chosen, which manga ties to the release-period restriction being on.
     *  Read once, as manga's is, rather than on every recomposition that builds the dialog. */
    val isUpdateIntervalEnabled =
        LibraryPreferences.MANGA_OUTSIDE_RELEASE_PERIOD in novelPreferences.novelUpdateRestrictions().get()

    /** A user-set interval is stored negative, as manga's; [days] 0 hands it back to the prediction. */
    fun setFetchInterval(days: Int) {
        val novel = (state.value as? NovelDetailsState.Loaded)?.intervalNovel ?: return
        viewModelScope.launchIO {
            updateNovelFetchInterval(
                novel.copy(fetchInterval = -days),
                { chapterRepo.getByNovelId(novel.id) },
                novelRepo,
            )
        }
    }

    fun showCoverDialog() = showDialog(NovelDetailsDialog.FullCover)

    /** A rebuilt chapter list drops whatever it no longer holds, the range anchor included, so a write
     *  starts from the selection retained to the rows the page shows. */
    private inline fun updateSelection(
        crossinline transform: (SelectionState<Long>, List<Long>) -> SelectionState<Long>,
    ) {
        val shown = (state.value as? NovelDetailsState.Loaded)?.chapters?.map { it.id } ?: return
        chapterSelection.update { transform(EntrySelection.retain(it, shown), shown) }
    }

    fun toggleSelection(chapterId: Long, fromLongPress: Boolean) = updateSelection { selection, shown ->
        if (fromLongPress) {
            EntrySelection.rangeOrToggle(selection, chapterId, shown)
        } else {
            EntrySelection.toggle(selection, chapterId)
        }
    }

    fun selectAll() = updateSelection { selection, shown -> EntrySelection.selectAll(selection, shown) }

    fun invertSelection() = updateSelection { selection, shown -> EntrySelection.invert(selection, shown) }

    fun clearSelection() {
        chapterSelection.value = EntrySelection.clear()
    }

    // Read from the selection itself, since the state catches up with a selection only after it is made.
    private fun NovelDetailsState.Loaded.selectedRows(): List<NovelChapter> =
        chapterSelection.value.let { held -> chapters.filter { it.id in held } }

    /** A merged chapter is keyed by its own novel's stored source, an unmerged one by the anchor's. */
    private fun hiddenKey(chapter: NovelChapter): String =
        chapter.hiddenKey(memberSources) ?: hiddenChapterKey(sourceId, chapter.url)

    private fun List<NovelChapter>.numberHints(): Map<Long, ChapterNumberHint.Hint> {
        val hidden = hiddenChaptersPref.get()
        return numberHintMemo.get(this, hidden) {
            ChapterNumberHint.forOwners(
                this,
                id = { it.id },
                owner = { it.novelId },
                sourceOrder = { it.sourceOrder },
                number = { it.chapterNumber },
                name = { it.name },
                dateUpload = { it.dateUpload },
                isHidden = { hiddenKey(it) in hidden },
            )
        }
    }

    fun hideSelected() = withSelection { chapters ->
        hiddenChaptersPref.set(hiddenChaptersPref.get() + chapters.map { hiddenKey(it) })
    }

    fun unhideSelected() = withSelection { chapters ->
        val keys = chapters.mapTo(HashSet()) { hiddenKey(it) }
        hiddenChaptersPref.set(hiddenChaptersPref.get().filterNotTo(HashSet()) { it in keys })
    }

    fun toggleShowHidden() {
        showHiddenFlow.value = !showHiddenFlow.value
    }

    /** The one selected chapter's number dialog, by the rule manga shares ([EditChapterNumber]). */
    fun showChapterNumberDialog() {
        val loaded = state.value as? NovelDetailsState.Loaded ?: return
        loaded.selectedRows().singleOrNull()?.let { showChapterNumberDialog(it.id) }
    }

    /** A marked chapter's dialog opens on its hint's suggestion. */
    fun showChapterNumberDialog(chapterId: Long) {
        val loaded = state.value as? NovelDetailsState.Loaded ?: return
        val chapter = loaded.chapters.firstOrNull { it.id == chapterId } ?: return
        viewModelScope.launchIO {
            val edit = editChapterNumber.edit(
                ContentType.NOVELS,
                chapter.novelId,
                chapter.url,
                chapter.name,
                chapter.chapterNumber,
                loaded.numberHints[chapterId]?.suggestion,
            )
            showDialog(NovelDetailsDialog.ChapterNumber(edit))
        }
    }

    fun saveChapterNumber(edit: ChapterNumberEdit, number: Double?) {
        viewModelScope.launchNonCancellable { editChapterNumber.save(edit, number) }
        clearSelection()
    }

    fun markSelectedRead(read: Boolean) = withSelection { chapters -> setRead(chapters, read) }

    fun bookmarkSelected(bookmark: Boolean) = withSelection { chapters ->
        chapterRepo.setBookmarkBulk(expandToGroup(chapters).map { it.id }, bookmark)
    }

    /** Mark every chapter the list shows before the earliest selected one read, across all fetched pages
     *  of a paged source; expandToGroup folds it across the group. */
    fun markPreviousRead() {
        viewModelScope.launchIO {
            val loaded = state.value as? NovelDetailsState.Loaded ?: return@launchIO
            val shown = if (loaded.pages.isEmpty()) loaded.chapters else shownAcrossPages(loaded)
            val selected = loaded.selectedRows().mapTo(HashSet()) { it.id }
            val previous = ReadingOrder.before(ReadingOrder.of(shown, loaded.sortDescending)) { it.id in selected }
            if (previous.isNotEmpty()) setRead(previous, read = true)
            clearSelection()
        }
    }

    /** A paged source's list holds one page, so its stored rows stand in for every page, shown as it would. */
    private suspend fun shownAcrossPages(loaded: NovelDetailsState.Loaded): List<NovelChapter> {
        val viewNovel = loaded.displayNovel
        val group = mergeGroup.state.value
        val chapters = chapterRepo.getByNovelId(viewNovel.id)
        val siblings = group.ids.filter { it != viewNovel.id }.flatMap { chapterRepo.getByNovelId(it) }
        val flags = group.novelRowFlags(chapters + siblings, chapters, mergedChapterProvider.stitchOf(viewNovel.id))
        val hidden = hiddenChaptersPref.get()
        return shownRows(loaded.novel, chapters, hidden, flags.downloadedIds, flags.marks).view.visible
    }

    private fun shownRows(
        anchor: Novel,
        chapters: List<NovelChapter>,
        hidden: Set<String>,
        downloadedChapterIds: Set<Long>,
        marks: GroupMarks,
    ) = novelShownRows(
        chapters,
        anchor,
        novelPreferences,
        hidden,
        showHiddenFlow.value,
        ::hiddenKey,
        downloadedChapterIds,
        marks,
        downloadedOnly = basePreferences.downloadedOnly.get(),
    )

    fun toggleChapterBookmark(chapter: NovelChapter) {
        viewModelScope.launchIO {
            // Toggled against what the row shows, which on a merged entry is the group's own state.
            val marks = (state.value as? NovelDetailsState.Loaded)?.marks ?: GroupMarks.NONE
            val target = !marks.isBookmarked(chapter.id, chapter.bookmark)
            chapterRepo.setBookmarkBulk(expandToGroup(listOf(chapter)).map { it.id }, target)
        }
    }

    fun markChapterRead(chapter: NovelChapter, read: Boolean) = setRead(listOf(chapter), read)

    /** Expand [chapters] to every grouped source's copy of the same merged chapters, so read /
     *  bookmark applies across the whole group. No-op when not merged. Mirrors
     *  MangaViewModel.expandToGroup, pinned by EntryMergeGroupHost.expandToGroup over the same stored stitch. */
    private suspend fun expandToGroup(chapters: List<NovelChapter>): List<NovelChapter> =
        mergeGroup.expandToGroup(chapters, { it.id }, ::groupStitch, ::groupChaptersIn)

    private suspend fun expandForDelete(chapters: List<NovelChapter>): List<NovelChapter> =
        mergeGroup.expandForDelete(chapters, { it.id }, ::groupStitch, ::groupChaptersIn)

    private suspend fun groupStitch() = mergedChapterProvider.stitchOf(mergeGroup.relatedIds.first())

    private suspend fun groupChaptersIn(ids: Set<Long>): List<NovelChapter> =
        mergeGroup.relatedIds.flatMap { chapterRepo.getByNovelId(it) }.filter { it.id in ids }

    fun showTrackDialog() = showDialog(NovelDetailsDialog.TrackSheet)

    /**
     * Marks [chapters] read or unread across the merge group, then pushes a read to bound trackers, through
     * [EntryAutoTrackOnMarkRead], the step shared with the manga details model. Its own coroutine, as
     * manga's is, so an Ask snackbar does not hold the selection open.
     */
    private fun setRead(chapters: List<NovelChapter>, read: Boolean) {
        val novel = (state.value as? NovelDetailsState.Loaded)?.novel ?: return
        viewModelScope.launchIO { autoTrackOnMarkRead.setRead(novel.id, chapters, read) }
    }

    private val autoTrackOnMarkRead = EntryAutoTrackOnMarkRead<NovelChapter>(
        context = context,
        snackbarHostState = snackbarHostState,
        trackerManager = trackerManager,
        trackPreferences = trackPreferences,
        expandToGroup = { expandToGroup(it) },
        writeRead = { chapters, read -> setNovelReadStatus.await(read, chapters) },
        chapterNumber = NovelChapter::chapterNumber,
        refresh = { refreshNovelTracks.await(it) },
        lastReadPerTracker = { getNovelTracks.awaitGroup(it).map(NovelTrack::lastChapterRead) },
        pushProgress = { id, chapterNumber -> trackNovelChapter.await(context, id, chapterNumber) },
    )

    /** Row swipe, dispatched by the configured [LibraryPreferences.ChapterSwipeAction] (mirrors the
     *  manga path's `executeChapterSwipeAction`; the download mapping is [swipeDownloadAction]). No pin:
     *  the manga dispatch is upstream code, which keeps its own copy of that mapping. */
    fun chapterSwipe(chapter: NovelChapter, action: LibraryPreferences.ChapterSwipeAction) {
        when (action) {
            // Toggled against the row's shown state, so a chapter read on a grouped source turns unread.
            LibraryPreferences.ChapterSwipeAction.ToggleRead -> {
                val marks = (state.value as? NovelDetailsState.Loaded)?.marks ?: GroupMarks.NONE
                markChapterRead(chapter, !marks.isRead(chapter.id, chapter.read))
            }
            LibraryPreferences.ChapterSwipeAction.ToggleBookmark -> toggleChapterBookmark(chapter)
            LibraryPreferences.ChapterSwipeAction.Download -> {
                val loaded = state.value as? NovelDetailsState.Loaded
                val downloadState = loaded?.downloadStateOf(chapter.id) ?: Download.State.NOT_DOWNLOADED
                onChapterDownloadAction(chapter, downloadState.swipeDownloadAction())
            }
            LibraryPreferences.ChapterSwipeAction.Disabled -> {}
        }
    }

    private inline fun withSelection(crossinline block: suspend (List<NovelChapter>) -> Unit) {
        viewModelScope.launchIO {
            val loaded = state.value as? NovelDetailsState.Loaded ?: return@launchIO
            val chapters = loaded.selectedRows()
            if (chapters.isNotEmpty()) block(chapters)
            clearSelection()
        }
    }

    fun onChapterDownloadAction(chapter: NovelChapter, action: ChapterDownloadAction) {
        viewModelScope.launchIO {
            val chapters = rowDownloadChapters(action, listOf(chapter), downloadCopiesOf(listOf(chapter))) { it.id }
            downloadManager.runChapterAction(action, chapters) { expandForDelete(listOf(chapter)) }
            if (action == ChapterDownloadAction.START || action == ChapterDownloadAction.START_NOW) {
                promptAddToLibraryOnFirstDownload()
            }
        }
    }

    fun downloadSelected() = withSelection {
        downloadManager.downloadChapters(downloadCopiesOf(it))
        promptAddToLibraryOnFirstDownload()
    }

    private fun promptAddToLibraryOnFirstDownload() {
        viewModelScope.launchIO {
            addToLibraryOffer.afterDownload(
                isInLibrary = { (state.value as? NovelDetailsState.Loaded)?.novel?.favorite != false },
                add = ::toggleFavorite,
            )
        }
    }

    /**
     * Every row the viewed list holds, unfiltered and in its shown order. A paged source's view holds one
     * page, so its own stored rows stand in for every page; an unpaged view already holds them all.
     */
    private suspend fun storedViewRows(loaded: NovelDetailsState.Loaded): List<NovelChapter> {
        val rows = if (loaded.pages.isEmpty()) loaded.storedRows else chapterRepo.getByNovelId(loaded.displayNovel.id)
        val ascending = rows.sortedWith(readingOrderComparator(loaded.novel, novelPreferences))
        return if (loaded.sortDescending) ascending.asReversed() else ascending
    }

    /** Toolbar download dropdown. Selection logic is shared with the library. */
    fun runDownloadAction(action: DownloadAction) {
        viewModelScope.launchIO {
            val loaded = state.value as? NovelDetailsState.Loaded ?: return@launchIO
            // The view's rows, not the anchor's own: on a merged entry the All chip lists whichever
            // source won the dedup, so downloading the anchor's chapters fetched ones the user was not
            // looking at and left every visible row undownloaded.
            val hidden = hiddenChaptersPref.get()
            val available = DownloadCandidates.rows(
                shown = loaded.chapters,
                stored = storedViewRows(loaded),
                skipFiltered = novelPreferences.readerSkipFiltered().get(),
            )
            // The view's set also holds a merged chapter's copies on other sources; the probe adds the
            // pages a paged source is not showing.
            val downloadedIds = loaded.downloadedChapterIds + downloadedIdsFor(available)
            val queuedIds = downloadManager.queueState.value.mapTo(HashSet()) { it.chapterId }
            val targets = selectChaptersForDownloadAction(
                available,
                loaded.sortDescending,
                action,
                downloadedIds + queuedIds,
                loaded.marks,
            ) { hiddenKey(it) in hidden }
            if (targets.isNotEmpty()) {
                downloadManager.downloadChapters(downloadCopiesOf(targets))
                promptAddToLibraryOnFirstDownload()
            }
        }
    }

    /** [chapters] as the copies a download fetches: a row whose plugin is gone takes an installed one's copy. */
    private suspend fun downloadCopiesOf(chapters: List<NovelChapter>): List<NovelChapter> {
        val targets = (state.value as? NovelDetailsState.Loaded)?.downloadTargets ?: DownloadTargets.OWN
        return targets.of(chapters, { it.id }, ::groupChaptersIn)
    }

    /** Confirm before bulk-deleting the selected downloads (parity with manga); confirm calls
     *  [deleteChapters]. */
    fun deleteSelected() {
        val loaded = state.value as? NovelDetailsState.Loaded ?: return
        val chapters = loaded.selectedRows()
        if (chapters.isNotEmpty()) showDialog(NovelDetailsDialog.DeleteChapters(chapters))
    }

    fun deleteChapters(chapters: List<NovelChapter>) {
        // Expanded first: the row is downloaded when ANY copy holds the file, so deleting only the
        // shown copy would leave the row still reading as downloaded.
        viewModelScope.launchIO { downloadManager.deleteChapters(expandForDelete(chapters)) }
        clearSelection()
        dismissDialog()
    }

    fun dismissDialog() {
        wordCountJob?.cancel()
        dialog.value = null
    }

    private var wordCountJob: Job? = null

    /**
     * Counts the words of every downloaded chapter in the scope the reader opens (the chip's source, or
     * the whole group), off the main thread, showing progress and then the result in the dialog.
     */
    fun showWordCountDialog() {
        val loaded = state.value as? NovelDetailsState.Loaded ?: return
        wordCountJob?.cancel()
        showDialog(NovelDetailsDialog.WordCount(checkedChapters = 0, chaptersToCount = null))
        wordCountJob = viewModelScope.launchIO {
            val scope = downloadedTexts.chaptersOf(
                loaded.selectedSourceNovelId ?: loaded.novel.id,
                sourceScoped = loaded.selectedSourceNovelId != null,
            )
            updateWordCount { it.copy(chaptersToCount = scope.onDisk.size) }
            var totalWords = 0L
            var counted = 0
            var unreadable = 0
            var checked = 0
            downloadedTexts.readEach(scope.onDisk) { _, text ->
                if (text != null) {
                    totalWords += NovelWords.countSpaced(text)
                    counted++
                } else {
                    unreadable++
                }
                checked++
                updateWordCount { it.copy(checkedChapters = checked) }
            }
            val result = NovelWordDensity(totalWords, counted, scope.total, unreadable)
            updateWordCount { it.copy(result = result) }
        }
    }

    private inline fun updateWordCount(
        crossinline transform: (NovelDetailsDialog.WordCount) -> NovelDetailsDialog.WordCount,
    ) = dialog.update { (it as? NovelDetailsDialog.WordCount)?.let(transform) ?: it }

    /** Raise the migrate dialog for the duplicate the user picked, onto this novel. Both rows already
     *  exist, so there is nothing to materialize first. */
    fun startMigrate(duplicateId: Long) {
        val loaded = state.value as? NovelDetailsState.Loaded ?: return
        showDialog(NovelDetailsDialog.Migrate(currentId = duplicateId, targetId = loaded.novel.id))
    }

    /** A dialog opens over a loaded page only, which every verb raising one assumes. */
    private fun showDialog(next: NovelDetailsDialog) {
        if (state.value is NovelDetailsState.Loaded) dialog.value = next
    }
}

private fun EntryEditInfoUi.toCustomNovelInfo(source: Novel) =
    overridesOver(source.toEntryEditInfoUi(), NovelStatusCode.UNKNOWN.toLong()).let {
        CustomNovelInfo(
            novelId = source.id,
            title = it.title,
            author = it.author,
            artist = it.artist,
            description = it.description,
            genre = it.genre,
            status = it.status,
            thumbnailUrl = it.thumbnailUrl,
        )
    }

sealed interface NovelDetailsState {
    data object Loading : NovelDetailsState
    data class Failed(val message: String) : NovelDetailsState

    @Immutable
    data class Loaded(
        /** Identity + favorite key. */
        val novel: Novel,
        /** Metadata shown in the header. Equals [novel] for a single source; the merge seam
         *  repoints it at the selected source. */
        val displayNovel: Novel,
        val chapters: List<NovelChapter>,
        /** The rendered chapter list: chapters interleaved with "N missing chapters" separators.
         *  When the hide-missing pref is on, holds only the chapters. */
        val chapterListEntries: List<NovelChapterListEntry> = emptyList(),
        /** Total missing chapters across the whole visible list; drives the header warning (> 0). */
        val missingChapterCount: Int = 0,
        /** The chapters whose number is out of line with their own source's list, by chapter id. */
        val numberHints: Map<Long, ChapterNumberHint.Hint> = emptyMap(),
        /** True while hidden chapters are temporarily shown (dimmed). */
        val showHidden: Boolean = false,
        /** Ids of the displayed rows that are hidden (only non-empty when [showHidden]); drives dimming
         *  and whether the selection offers Hide vs Unhide. */
        val hiddenChapterIds: Set<Long> = emptySet(),
        /** Any chapter in this novel is hidden; gates the "Show hidden chapters" toolbar toggle. */
        val hasHiddenChapters: Boolean = false,
        /** Page keys for the selector; empty when the source is single/unpaged (selector hidden). */
        val pages: List<String> = emptyList(),
        val pageIndex: Int = 0,
        val isPageLoading: Boolean = false,
        val isRefreshing: Boolean = false,
        /** Live download-queue states by chapter id (queued/downloading/error only; a finished
         *  download is read from [downloadedChapterIds]). */
        val downloadStates: Map<Long, Download.State> = emptyMap(),
        /** Chapter ids downloaded on disk, from NovelDownloadCache (replaces the old is_downloaded flag).
         *  A finished download shows DOWNLOADED via membership here, not a queue state. */
        val downloadedChapterIds: Set<Long> = emptySet(),
        /** Whose folder Open folder opens; null hides it and Clear downloads. See downloadFolderOwner. */
        val downloadFolderOwner: Novel? = null,
        /** Read and bookmarked as the merge group answers them, so a chapter read on another source reads
         *  as read here. */
        val marks: GroupMarks = GroupMarks.NONE,
        /** Bound tracks on trackers the sheet offers; drives the details action-row Tracking button. */
        val trackingCount: Int = 0,
        /** Whether the sheet offers any tracker; without one the Tracking button opens tracker settings. */
        val hasLoggedInTrackers: Boolean = false,
        /** Non-destructive edit-info overlay; the display applies it over [displayNovel] (the raw novel
         *  stays source-accurate). Null when the novel has no edits. */
        val customInfo: CustomNovelInfo? = null,
        val dialog: NovelDetailsDialog? = null,
        val selection: Set<Long> = emptySet(),
        val resumeChapter: NovelChapter? = null,
        val hasStarted: Boolean = false,
        /** Cover-derived tint, null until extracted. Always extracted, since edit info tints from it; the
         *  screen applies it only when cover theming is on. */
        val seedColor: Color? = null,
        /** Resolved source name, and [webPage], the viewed member's own page as its source addresses it. */
        val sourceName: String = "",
        val webPage: EntryWebPage? = null,
        /** Whether the viewed source exposes settings; gates the overflow item that opens them. */
        val sourceHasSettings: Boolean = false,
        /** The viewed source's id when its plugin is installed; null hides the header's Browse. */
        val browsableSourceId: String? = null,
        /** Whether the viewed member's plugin is installed; see [novelSourceState]. */
        val sourceState: EntrySourceState = EntrySourceState.Installed,
        /** The installed member the All view serves in place of an anchor whose plugin is gone, else null;
         *  see [unifiedViewMember]. */
        val servedNovel: Novel? = null,
        /** The copy each row's download fetches; only the All view moves one off a missing plugin. */
        val downloadTargets: DownloadTargets = DownloadTargets.OWN,
        // Resolved (per-novel or global-default) chapter view settings.
        val sorting: Long = NovelChapterFlags.SORTING_SOURCE,
        val sortDescending: Boolean = true,
        val readFilter: Long = 0L,
        val bookmarkedFilter: Long = 0L,
        val downloadedFilter: Long = 0L,
        /** The global Downloaded only switch is on, which forces the downloaded filter and locks it. */
        val downloadedFilterLocked: Boolean = false,
        val hideChapterTitles: Boolean = false,
        /** Source-switcher chips for a merged group (empty/single = not merged, chips hidden). */
        val mergeSources: List<EntryMergeSource> = emptyList(),
        /** The selected source chip's novelId; null = the unified ("All") view. */
        val selectedSourceNovelId: Long? = null,
        val chapterSwipeActions: ChapterSwipeActions = ChapterSwipeActions.DISABLED,
        /** The viewed list's rows before hiding, filters and sort (one page on a paged source). Not drawn:
         *  the download action reads it. */
        val storedRows: List<NovelChapter> = emptyList(),
    ) : NovelDetailsState {
        val selectionMode: Boolean get() = selection.isNotEmpty()

        /** Whose update interval the page shows and sets: the All view's served member, else the anchor. */
        val intervalNovel: Novel get() = servedNovel ?: novel

        /** The filters the list applies, with the Downloaded only switch folded in. */
        val chapterFilters: ChapterListFilters
            get() = novelChapterListFilters(readFilter, bookmarkedFilter, downloadedFilter, downloadedFilterLocked)

        /** A chapter's download state: a live queue state if present, else DOWNLOADED / NOT_DOWNLOADED
         *  from the on-disk cache. */
        fun downloadStateOf(chapterId: Long): Download.State {
            // A row whose download fetches another source's copy follows that copy through the queue.
            val queued = downloadTargets.queuedFor(chapterId, downloadStates::get)
            return downloadStateOf(queued) { chapterId in downloadedChapterIds }
        }
    }
}

sealed interface NovelDetailsDialog {
    data class ChangeCategory(
        val initialSelection: List<CheckboxState.State<Category>>,
        /** The group of the duplicate dialog's picks, when the add joins one. */
        val joinGroup: List<Long> = emptyList(),
    ) : NovelDetailsDialog

    data object EditInfo : NovelDetailsDialog

    data class DuplicateNovel(val prompt: DuplicatePrompt<NovelWithChapterCount, String>) : NovelDetailsDialog

    data class DeleteChapters(val chapters: List<NovelChapter>) : NovelDetailsDialog

    data class ChapterNumber(val edit: ChapterNumberEdit) : NovelDetailsDialog

    data object ChapterSettings : NovelDetailsDialog
    data object SetFetchInterval : NovelDetailsDialog
    data object PageSelector : NovelDetailsDialog
    data class SourceSettings(val source: NovelSource) : NovelDetailsDialog

    /** [chaptersToCount] is null until the chapters on disk are known, [result] until they are counted. */
    data class WordCount(
        val checkedChapters: Int,
        val chaptersToCount: Int?,
        val result: NovelWordDensity? = null,
    ) : NovelDetailsDialog

    data class ClearDownloads(val target: ClearDownloadsTarget) : NovelDetailsDialog
    data class RemoveFromLibrary(val removal: DetailsRemoval) : NovelDetailsDialog
    data object FullCover : NovelDetailsDialog
    data class ManageSources(
        val sources: List<EntryManageSourceInfo>,
        val isOverridden: Boolean,
    ) : NovelDetailsDialog

    // Rendered as a NavigatorAdaptiveSheet, mirroring Mihon's manga sheet.
    data object TrackSheet : NovelDetailsDialog

    /** Migrating the library's copy onto this one, both already stored by id. Replaces
     *  [DuplicateNovel] in the same slot, as the manga twin does. */
    data class Migrate(val currentId: Long, val targetId: Long) : NovelDetailsDialog
}

/**
 * The viewed member's own source: a sibling's from [siblings], which holds only installed plugins, and
 * [anchorSource] only for the anchor itself. A sibling whose plugin is gone has none, never the anchor's.
 */
internal fun <S> viewedNovelSource(viewedId: Long, anchorId: Long, siblings: Map<Long, S>, anchorSource: S?): S? =
    siblings[viewedId] ?: anchorSource.takeIf { viewedId == anchorId }

/**
 * A sibling's plugins are looked up before its chip can be picked, so no source there means missing.
 * The anchor's is looked up after the page first loads, so it counts as missing only once [anchorMissing].
 */
internal fun <S> novelSourceState(viewedSource: S?, isAnchorView: Boolean, anchorMissing: Boolean): EntrySourceState =
    if (viewedSource == null &&
        (!isAnchorView || anchorMissing)
    ) {
        EntrySourceState.Missing
    } else {
        EntrySourceState.Installed
    }
