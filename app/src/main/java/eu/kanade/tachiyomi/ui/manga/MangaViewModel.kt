package eu.kanade.tachiyomi.ui.manga

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.util.fastAny
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import eu.kanade.core.preference.asState
import eu.kanade.core.util.addOrRemove
import eu.kanade.domain.chapter.interactor.GetAvailableScanlators
import eu.kanade.domain.chapter.interactor.SetReadStatus
import eu.kanade.domain.chapter.model.applyFilters
import eu.kanade.domain.manga.interactor.GetExcludedScanlators
import eu.kanade.domain.manga.interactor.GetPagePreviews
import eu.kanade.domain.manga.interactor.SetExcludedScanlators
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.manga.model.PagePreview
import eu.kanade.domain.manga.model.chaptersFiltered
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.domain.track.interactor.RefreshTracks
import eu.kanade.domain.track.interactor.TrackChapter
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.manga.DownloadAction
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.model.TrackMangaMetadata
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.PagePreviewSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.getNameForMangaInfo
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.MetadataSource
import eu.kanade.tachiyomi.source.online.all.EHentai
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.chapter.getNextUnread
import exh.debug.DebugToggles
import exh.eh.EHentaiUpdateHelper
import exh.favorites.removeGallery
import exh.metadata.metadata.RaisedSearchMetadata
import exh.metadata.metadata.base.FlatMetadata
import exh.source.ExhPreferences
import exh.source.getMainSource
import exh.source.isEhBasedManga
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.domain.chapter.interactor.FilterChaptersForDownload
import mihon.domain.source.interactor.UpdateMangaFromRemote
import reikai.data.coil.extractCoverColor
import reikai.data.coil.seedColor
import reikai.data.updateerror.refreshFailureMessage
import reikai.domain.chapter.ChapterNumberEdit
import reikai.domain.chapter.ChapterNumberHint
import reikai.domain.chapter.DownloadCandidates
import reikai.domain.chapter.EditChapterNumber
import reikai.domain.chapter.ReadingOrder
import reikai.domain.chapter.hiddenKey
import reikai.domain.download.downloadStateOf
import reikai.domain.entry.EntryId
import reikai.domain.entry.ResetEntryInfo
import reikai.domain.library.ContentType
import reikai.domain.library.chapterSwipeActions
import reikai.domain.manga.GetTracksInGroup
import reikai.domain.manga.MangaChapterSettings
import reikai.domain.manga.MangaMergeManager
import reikai.domain.manga.MangaPreferences
import reikai.domain.manga.MergedChapterProvider
import reikai.domain.manga.RemoveMangaFromLibrary
import reikai.domain.manga.downloadedChapterIds
import reikai.domain.manga.inReadingOrder
import reikai.domain.manga.withGroupChapterFlags
import reikai.domain.merge.ChapterGap
import reikai.domain.merge.DetailsRemoval
import reikai.domain.merge.DownloadTargets
import reikai.domain.merge.GroupChapterFlags
import reikai.domain.merge.MergeScope
import reikai.domain.merge.gapPresent
import reikai.domain.merge.refreshMergeGroup
import reikai.domain.merge.toGapNeighbour
import reikai.domain.recommendation.PrepareRecommendationAssembly
import reikai.domain.recommendation.RecommendationAssembly
import reikai.domain.recommendation.ReikaiRecommendationPreferences
import reikai.domain.recommendation.RelatedMangaCache
import reikai.domain.recommendation.RelatedMangaCandidate
import reikai.domain.recommendation.RelatedMangasLoader
import reikai.domain.recommendation.RelatedPlacement
import reikai.domain.recommendation.RelatedPool
import reikai.domain.recommendation.localIdOf
import reikai.domain.recommendation.taste.RefreshTrackerLibrary
import reikai.domain.track.EntryTrackPorts
import reikai.domain.track.RemoteFirstRemoval
import reikai.domain.track.autobind.AutoBindTrackers
import reikai.domain.track.autobind.TrackingButtonState
import reikai.domain.track.autobind.offerTrackers
import reikai.domain.track.autobind.trackingButtonState
import reikai.presentation.browse.AddOutcome
import reikai.presentation.browse.DuplicatePrompt
import reikai.presentation.browse.MangaLibraryAdder
import reikai.presentation.browse.addEntry
import reikai.presentation.browse.finishAdd
import reikai.presentation.details.AddToLibraryOffer
import reikai.presentation.details.ClearDownloadsTarget
import reikai.presentation.details.EntryAutoTrackOnMarkRead
import reikai.presentation.details.EntryEditInfoUi
import reikai.presentation.details.EntryManageSourceInfo
import reikai.presentation.details.EntryMergeActionHost
import reikai.presentation.details.EntryMergeGroupHost
import reikai.presentation.details.EntryMergeSource
import reikai.presentation.details.EntryWebPage
import reikai.presentation.details.ScanlatorFilterView
import reikai.presentation.details.ShownWebPage
import reikai.presentation.details.buildTrackerAutofillCandidates
import reikai.presentation.details.downloadFolderOwner
import reikai.presentation.details.headerNamesWholeGroup
import reikai.presentation.details.hiddenChapterIdsIn
import reikai.presentation.details.loadThenRenderOn
import reikai.presentation.details.offerToDeleteDownloads
import reikai.presentation.details.overridesOver
import reikai.presentation.details.resolveHiddenChapterView
import reikai.presentation.details.scanlatorFilterView
import reikai.presentation.details.scanlatorWrites
import reikai.presentation.details.unifiedViewMember
import reikai.presentation.details.webPageIn
import reikai.presentation.library.sourceKeyQuery
import reikai.presentation.selection.EntrySelection
import reikai.presentation.selection.SelectionState
import reikai.util.runCatchingCancellable
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.interactor.SetMangaDefaultChapterFlags
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetCustomMangaInfo
import tachiyomi.domain.manga.interactor.GetFlatMetadataById
import tachiyomi.domain.manga.interactor.GetMangaWithChapters
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.interactor.SetCustomMangaInfo
import tachiyomi.domain.manga.interactor.SetMangaChapterFlags
import tachiyomi.domain.manga.model.CustomMangaInfo
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaWithChapterCount
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.model.Track
import tachiyomi.i18n.MR
import tachiyomi.source.local.isLocal
import kotlin.math.floor
import kotlin.time.Duration.Companion.seconds

// RK: max related candidates shown in the details carousel; the full pool is kept in the cache for
// the "See all" browse grid.
private const val CAROUSEL_CAP = 30

@AssistedInject
class MangaViewModel(
    private val context: Context,
    @Assisted private val mangaId: Long,
    @Assisted private val isFromSource: Boolean,
    private val libraryPreferences: LibraryPreferences,
    trackPreferences: TrackPreferences,
    readerPreferences: ReaderPreferences,
    private val trackerManager: TrackerManager,
    private val trackChapter: TrackChapter,
    private val refreshTracks: RefreshTracks, // RK: upstream lists this last
    private val downloadManager: DownloadManager,
    private val downloadCache: DownloadCache,
    private val getMangaAndChapters: GetMangaWithChapters,
    // RK: getDuplicateLibraryManga moved to MangaLibraryAdder.findDuplicates, which toggleFavorite asks
    private val getAvailableScanlators: GetAvailableScanlators,
    private val getExcludedScanlators: GetExcludedScanlators,
    private val setExcludedScanlators: SetExcludedScanlators,
    private val setMangaChapterFlags: SetMangaChapterFlags,
    private val setMangaDefaultChapterFlags: SetMangaDefaultChapterFlags,
    private val setReadStatus: SetReadStatus,
    private val updateChapter: UpdateChapter,
    private val updateManga: UpdateManga,
    // RK: coverCache moved out, the heart's covers to RemoveMangaFromLibrary and Reset all's to ResetEntryInfo
    private val resetEntryInfo: ResetEntryInfo,
    // RK --> a tracker bound on one source of a merged series counts for the whole group, so every read
    // here goes through GetTracksInGroup instead of Mihon's per-manga GetTracks.
    private val getTracksInGroup: GetTracksInGroup,
    // RK <--
    private val filterChaptersForDownload: FilterChaptersForDownload,
    private val updateMangaFromRemote: UpdateMangaFromRemote,
    // RK -->
    private val mergeManager: MangaMergeManager,
    private val mangaLibraryAdder: MangaLibraryAdder,
    private val removeMangaFromLibrary: RemoveMangaFromLibrary,
    private val mergedChapterProvider: MergedChapterProvider,
    private val mangaPreferences: MangaPreferences,
    private val relatedMangasLoader: RelatedMangasLoader,
    private val recommendationPreferences: ReikaiRecommendationPreferences,
    private val relatedMangaCache: RelatedMangaCache,
    private val refreshTrackerLibrary: RefreshTrackerLibrary,
    private val prepareRecommendationAssembly: PrepareRecommendationAssembly,
    private val networkToLocalManga: NetworkToLocalManga,
    private val uiPreferences: UiPreferences,
    private val getFlatMetadataById: GetFlatMetadataById,
    private val getPagePreviews: GetPagePreviews,
    // RK: manga custom-info overlay. getCustomMangaInfo drives the non-destructive display overlay;
    // setCustomMangaInfo persists edits from the shared edit-info dialog.
    private val getCustomMangaInfo: GetCustomMangaInfo,
    private val setCustomMangaInfo: SetCustomMangaInfo,
    private val sourceManager: SourceManager,
    private val exhPreferences: ExhPreferences,
    private val updateHelper: EHentaiUpdateHelper,
    private val trackPorts: EntryTrackPorts,
    private val autoBindTrackers: AutoBindTrackers,
    private val remoteFirstRemoval: RemoteFirstRemoval,
    private val editChapterNumber: EditChapterNumber,
    private val chapterSettings: MangaChapterSettings,
    // RK <--
) : ViewModel() {

    val snackbarHostState = SnackbarHostState()
    private val addToLibraryOffer = AddToLibraryOffer(snackbarHostState, context) // RK

    // RK: a gallery id to open in this screen's place, see observeExhRootRedirect
    private val exhRootRedirect = Channel<Long>(Channel.CONFLATED)
    val exhRootRedirects: Flow<Long> = exhRootRedirect.receiveAsFlow()

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(mangaId: Long, isFromSource: Boolean): MangaViewModel
    }

    // RK: snackbarHostState moved above state
    private val successState: State.Success?
        get() = state.value as? State.Success

    val manga: Manga?
        get() = successState?.manga

    val source: Source?
        get() = successState?.source

    private val isFavorited: Boolean
        get() = manga?.favorite ?: false

    private val allChapters: List<ChapterList.Item>?
        get() = successState?.chapters

    private val filteredChapters: List<ChapterList.Item>?
        get() = successState?.processedChapters

    // RK: the crossed preference names resolve in the one kernel every chapter list reads.
    val chapterSwipeStartAction = libraryPreferences.chapterSwipeActions().start
    val chapterSwipeEndAction = libraryPreferences.chapterSwipeActions().end

    // RK: autoTrackState moved to EntryAutoTrackOnMarkRead, which reads the preference itself.

    private val skipFiltered by readerPreferences.skipFiltered.asState(viewModelScope)

    val isUpdateIntervalEnabled =
        LibraryPreferences.MANGA_OUTSIDE_RELEASE_PERIOD in libraryPreferences.autoUpdateMangaRestrictions.get()

    // RK --> every holder sits above the seed and the state: the seed starts running, and the state captures
    // them, while the constructor is still running, and a holder declared below would still be null then.

    // Chapter multi-select, shared with novels through the kernel. It replaces upstream's first/last
    // index window, which could disagree with the selection it described. The state shows it retained
    // to the visible rows; the verbs retain it before they write.
    private val chapterSelection = MutableStateFlow(SelectionState<Long>())

    // Shared merge read/observe wiring: the group ids (just this manga when ungrouped), the selected
    // source chip, the membership observer, and the switcher chips. Written once in EntryMergeGroupHost so a
    // manga/novel drift like the old missing-refresh bug can't recur; the novel model composes the same host.
    // Manga's anchor is constant, so anchorChanges is mangaId alone and the host re-resolves on group
    // changes; source resolution is the synchronous getOrStub in buildMergeSources.
    private val mergeGroup = EntryMergeGroupHost(
        mergeManager = mergeManager,
        initialIds = longArrayOf(mangaId),
        anchorChanges = flowOf(mangaId),
        onSourceChange = { from, to -> chapterSelection.update { EntrySelection.afterChipFlip(it, from, to) } },
        resolveSources = { ids -> buildMergeSources(ids) },
    )

    // Hide/unhide chapters (twin of the novel mechanism). The pref is the persisted/backed-up set of
    // hidden chapter keys; showHiddenFlow is the transient "temporarily reveal hidden chapters" toggle.
    private val hiddenChaptersPref = mangaPreferences.hiddenChapters()
    private val showHiddenFlow = MutableStateFlow(false)

    // The page-preview subscription the seed starts, replaced when a refresh loads the previews again.
    private var pagePreviewsJob: Job? = null
    // RK <--

    private val dialog = MutableStateFlow<Dialog?>(null)

    private val isRefreshingData = MutableStateFlow(false)

    private val downloadStates = MutableStateFlow(emptyMap<Long, DownloadProgress>())

    // A finished chapter leaves the queue, and its last status change can be lost with it, so its state is left to
    // the queried item once it's no longer queued
    private val queuedDownloadStates = combine(downloadStates, downloadManager.queueState) { states, queue ->
        val queuedChapterIds = queue.mapTo(HashSet()) { it.chapter.id }
        states.filterKeys { it in queuedChapterIds }
    }

    private val hideMissingChapters = libraryPreferences.hideMissingChapters.get()

    // RK: what the page loads on its own rather than reads from the database: the cover tint, the page
    // previews and the related carousel. previewsRowCount is read once, as before.
    private val extras = MutableStateFlow(Extras(previewsRowCount = uiPreferences.previewsRowCount.get()))

    // RK --> upstream's defaultChapterFlagsJob, which also seeds what the first state must already show: the
    // merge group (so a merged series never shows its own source's list first), the refresh flag (which the
    // related carousel waits on) and the page-preview spinner. Every input of the state waits for it.
    private val seed = viewModelScope.async(Dispatchers.IO) {
        val manga = getMangaAndChapters.awaitManga(mangaId)
        // So an entry outside the library isn't shown with its old chapter settings first
        if (!manga.favorite) {
            setMangaDefaultChapterFlags.await(manga)
        }
        mergeGroup.refresh(mangaId)
        val needRefreshInfo = !manga.initialized
        val needRefreshChapter = getMangaAndChapters.awaitChapters(mangaId, applyScanlatorFilter = true).isEmpty()
        isRefreshingData.value = needRefreshInfo || needRefreshChapter
        val source = sourceManager.getOrStub(manga.source)
        if (source.getMainSource<PagePreviewSource>() != null) {
            extras.update { it.copy(pagePreviewsState = PagePreviewState.Loading) }
            getPagePreviews(manga, source)
        }
        FirstFetch(details = needRefreshInfo, chapters = needRefreshChapter)
    }

    /** [flow] once [seed] has run, so no input reads the unseeded group or flags. */
    private fun <T> seeded(flow: Flow<T>): Flow<T> = flow {
        seed.await()
        emitAll(flow)
    }

    // When the manga is part of a merge group, the chapter list is the aggregated union of every grouped
    // source; otherwise it stays the single-source list.
    private val chapterView = seeded(
        combine(
            flow { emitAll(getMangaAndChapters.subscribe(mangaId, applyScanlatorFilter = true)) }
                .distinctUntilChanged(),
            mergeGroup.state,
        ) { mangaAndChapters, group -> ChapterInputs(mangaAndChapters.first, mangaAndChapters.second, group) },
    )
        // A download, the queue or a hide only re-renders the loaded rows, as upstream's combine does.
        .loadThenRenderOn(
            merge(
                downloadCache.changes,
                downloadManager.queueState,
                hiddenChaptersPref.changes(),
                showHiddenFlow,
            ),
        ) { (manga, ownChapters, group) ->
            val selectedSource = group.selected
            when {
                selectedSource != null && group.ids.size > 1 ->
                    singleSourceChaptersFlow(manga, selectedSource, group)
                group.ids.size <= 1 ->
                    flowOf(
                        MergedChapters(
                            manga = manga,
                            chapters = ownChapters,
                            mangaBySource = emptyMap(),
                            flags = { ownFlags(ownChapters, manga) },
                            numberHints = { ownChapters.numberHints(emptyMap(), manga) },
                        ),
                    )
                else ->
                    mergedChaptersFlow(manga, group)
            }
        }
        .map { mc ->
            val items = mc.chapters.toChapterListItems(mc.manga, mc.flags(), mc.mangaBySource, mc.downloadTargets)
            ChapterView(
                merged = mc,
                source = sourceManager.getOrStub(mc.manga.source),
                hidden = applyHiddenChapters(items, mc.manga, mc.mangaBySource),
                numberHints = mc.numberHints(),
                downloadFolderOwner = downloadFolderOwnerOf(
                    mc.displayManga,
                    mc.mangaBySource.values.ifEmpty { listOf(mc.manga) },
                ),
            )
        }

    // The filter covers the sources on screen: the chip's, or every source of a merged series under All,
    // whose unified list shows all their chapters (see GroupState.viewedIds).
    private val scanlators = seeded(mergeGroup.state)
        .flatMapLatest { group ->
            val targets = group.viewedIds(mangaId)
            val perTarget = targets.map { id ->
                combine(
                    getAvailableScanlators.subscribe(id),
                    getExcludedScanlators.subscribe(id),
                ) { available, excluded -> Triple(id, available, excluded) }
            }
            combine(perTarget) { rows ->
                scanlatorFilterView(
                    targets,
                    availableById = rows.associate { (id, available, _) -> id to available },
                    excludedById = rows.associate { (id, _, excluded) -> id to excluded },
                )
            }
        }
        .distinctUntilChanged()

    // Resolved from the group's ids by the same resolver the host's own chips use, so the first state
    // carries the chips of the seeded group rather than waiting on the host's collector.
    private val mergeChips = seeded(mergeGroup.state)
        .map { it.ids }
        .distinctUntilChanged()
        .map { buildMergeSources(it) }

    // The active source's gallery metadata (primary when unified), so the tag chips and info box follow a
    // source-chip switch, the first open's fetch storing it, and a gallery update rewriting it.
    private val galleryMetadata = mergeGroup.selectedSourceChanges
        .flatMapLatest { selected ->
            val targetId = selected ?: mangaId
            getFlatMetadataById.subscribe(targetId).map { flat -> raiseMetadata(flat, targetId) }
        }

    private val mergeInputs = combine(
        scanlators,
        // The overlay is applied at the display layer via Manga.withCustomInfo; the raw manga stays
        // source-accurate.
        getCustomMangaInfo.subscribe(mangaId).distinctUntilChanged(),
        mergeChips,
        mergeGroup.selectedSourceChanges,
        galleryMetadata,
        ::MergeInputs,
    )

    // Counted by the tracking sheet's own offer rule, the one the novel details screen runs too; the
    // port's read spans the merge group.
    private val trackers = flow { emit(trackPorts.of(EntryId.Manga(mangaId))) }
        .flatMapLatest { port ->
            combine(
                port.tracks().catch { logcat(LogPriority.ERROR, it) },
                trackerManager.loggedInTrackersFlow(),
            ) { mangaTracks, loggedInTrackers ->
                val offered = offerTrackers(port, loggedInTrackers, autoBindTrackers).offered
                trackingButtonState(mangaTracks.map { it.trackerId }, offered)
            }
        }
        .distinctUntilChanged()
        .onStart { emit(TrackingButtonState(count = 0, hasTrackers = false)) }

    // The shown member's web page, asked of the extension only when that member changes. The extension
    // answers without suspending, so the page is in hand on the pass that first shows the member.
    private val shownWebPage = ShownWebPage<Pair<Manga, Source>>(viewModelScope) { (manga, source) ->
        manga.webPageIn(source)
    }
    // RK <--

    val state: StateFlow<State> = combine(
        chapterView,
        mergeInputs, // RK
        trackers,
        combine(chapterSelection, queuedDownloadStates, ::Pair),
        combine(dialog, isRefreshingData, extras, ::Triple), // RK: extras
    ) {
            view,
            merge,
            tracking,
            (selection, downloads),
            (dialog, isRefreshingData, extras),
        ->
        val mc = view.merged
        // RK --> a rebuilt list drops selected rows its filters no longer show, as novels do
        val withDownloads = view.hidden.chapters.map { item ->
            // A row whose download fetches another source's copy follows that copy through the queue.
            val download = mc.downloadTargets.queuedFor(item.id, downloads::get) ?: return@map item
            item.copy(downloadState = download.status, downloadProgress = download.progress)
        }
        val selectedIds = if (selection.isEmpty) {
            emptySet()
        } else {
            EntrySelection.retain(selection, withDownloads.applyFilters(mc.manga).map { it.id }.toList()).selection
        }
        // RK <--
        State.Success(
            manga = mc.manga,
            source = view.source,
            isFromSource = isFromSource,
            chapters = if (selectedIds.isEmpty()) {
                withDownloads
            } else {
                withDownloads.map { it.copy(selected = it.id in selectedIds) }
            },
            // RK -->
            mergedMangaById = mc.mangaBySource,
            mergeSources = merge.chips,
            selectedSourceMangaId = merge.selectedSource,
            mergeDisplayManga = mc.displayManga,
            mergeDisplaySource = mc.displaySource,
            mergeServedManga = mc.servedManga,
            mergeServedSource = mc.servedSource,
            downloadTargets = mc.downloadTargets,
            downloadFolderOwner = view.downloadFolderOwner,
            galleryMetadata = merge.galleryMetadata,
            relatedItems = extras.relatedItems,
            relatedTotalCount = extras.relatedTotalCount,
            relatedLoading = extras.relatedLoading,
            // RK <--
            availableScanlators = merge.scanlators.available,
            excludedScanlators = merge.scanlators.excluded,
            trackingCount = tracking.count,
            hasLoggedInTrackers = tracking.hasTrackers,
            isRefreshingData = isRefreshingData,
            dialog = dialog,
            hideMissingChapters = hideMissingChapters,
            // RK -->
            showHidden = view.hidden.showHidden,
            hasHiddenChapters = view.hidden.hasHiddenChapters,
            hiddenChapterIds = view.hidden.hiddenChapterIds,
            gapPresent = view.hidden.gapPresent,
            numberHints = view.numberHints,
            resumeChapter = view.hidden.resumeChapter,
            customInfo = merge.customInfo,
            seedColor = extras.seedColor,
            pagePreviewsState = extras.pagePreviewsState,
            previewsRowCount = extras.previewsRowCount,
            webPage = shownWebPage.of(
                (mc.displayManga ?: mc.servedManga ?: mc.manga) to (mc.displaySource ?: mc.servedSource ?: view.source),
            ),
            // RK <--
        )
    }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5.seconds), State.Loading)

    // RK --> cover-based theming
    val themeCoverBased = uiPreferences.themeCoverBased.get()

    // RK: recommendations are enabled but placed in the three-dot menu, so the screen shows a
    // "Recommendations" overflow action instead of the inline carousel. Read once, like themeCoverBased.
    val recommendationsInMenu = recommendationPreferences.enableRelatedMangas.get() &&
        recommendationPreferences.relatedPlacement.get() == RelatedPlacement.MENU

    /** Seed the details theme from the cover, through the kernel novels use too (mirrors Komikku setPaletteColor). */
    fun updateSeedColor() {
        // Computed regardless of the themeCoverBased pref: the page only applies it when the pref is on
        // (MangaScreen), but the shared edit-info dialog always tints from the cover, so the seed must be
        // available either way.
        val manga = manga ?: return
        viewModelScope.launchIO {
            val color = EntryId.Manga(manga.id).seedColor { context.extractCoverColor(manga.asMangaCover()) }
                ?: return@launchIO
            extras.update { it.copy(seedColor = Color(color)) }
        }
    }
    // RK <--

    init {
        viewModelScope.launchIO {
            // RK: a merged series lists every grouped source's chapters, so their downloads are followed too
            merge(downloadManager.statusFlow(), downloadManager.progressFlow())
                .filter { it.manga.id in mergeGroup.relatedIds }
                .catch { logcat(LogPriority.ERROR, it) }
                .collect(::updateDownloadState)
        }

        // RK --> the host's membership observer stays eager: the merge verbs read its group synchronously.
        mergeGroup.observe(viewModelScope)
        observeExhRootRedirect()
        // RK <--

        viewModelScope.launchIO {
            // RK: decided by the seed, before the first state, rather than from the loaded state as upstream
            // does, so the refresh flag the first frame shows is already the right one
            val fetch = seed.await()
            // Fetch info-chapters when needed
            if (fetch.details || fetch.chapters) {
                fetchAllFromSource(
                    manga = getMangaAndChapters.awaitManga(mangaId),
                    manualFetch = false,
                    fetchDetails = fetch.details,
                    fetchChapters = fetch.chapters,
                )
                isRefreshingData.value = false
            }
        }
    }

    // RK --> load the first page of gallery page previews for sources that support it, and again when expired
    // links drop the cached list. Synchronized because the seed starts it on IO and a refresh restarts it.
    @Synchronized
    private fun getPagePreviews(manga: Manga, source: Source) {
        pagePreviewsJob?.cancel()
        pagePreviewsJob = viewModelScope.launchIO {
            getPagePreviews.subscribe(manga, source, 1).collect { result ->
                val previews = when (result) {
                    is GetPagePreviews.Result.Error -> PagePreviewState.Error(result.error)
                    is GetPagePreviews.Result.Success -> PagePreviewState.Success(result.pagePreviews)
                    GetPagePreviews.Result.Unused -> PagePreviewState.Unused
                }
                extras.update { it.copy(pagePreviewsState = previews) }
            }
        }
    }
    // RK <--

    fun fetchAllFromSource(manualFetch: Boolean = true) {
        viewModelScope.launch {
            isRefreshingData.value = true
            fetchAllFromSource(
                manga = getMangaAndChapters.awaitManga(mangaId),
                manualFetch = manualFetch,
                fetchDetails = true,
                fetchChapters = true,
            )
            isRefreshingData.value = false
            // RK --> previews too, after the chapter fetch whose ids key the cached list
            val manga = getMangaAndChapters.awaitManga(mangaId)
            getPagePreviews(manga, sourceManager.getOrStub(manga.source))
            // RK <--
        }
    }

    private suspend fun fetchAllFromSource(
        manga: Manga,
        manualFetch: Boolean,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ) {
        // RK: refresh every source in a merged group, not just the primary. A source merged in via
        //     long-press "add from another source" never fetched at add time, so without this its
        //     chip stays stale on refresh; each member goes through its own source's fetch (the same
        //     path Browse uses), populating details, chapters and gallery metadata. Just the primary
        //     when not merged, so non-grouped entries behave exactly as before.
        val groupIds = mergeGroup.relatedIds
        try {
            withUIContext {
                // RK --> one fetch per grouped source by the rule novels share; the first failure is rethrown
                // once all have run
                val newChapters = mutableListOf<Chapter>()
                suspend fun fetch(source: Source, manga: Manga) = updateMangaFromRemote(
                    source = source,
                    manga = manga,
                    fetchDetails = fetchDetails,
                    fetchChapters = fetchChapters,
                    manualFetch = manualFetch,
                ).onSuccess { newChapters += it.newChapters }
                val firstError = refreshMergeGroup(
                    anchor = { fetch(sourceManager.getOrStub(manga.source), manga) },
                    siblings = groupIds.filter { it != manga.id }.map { getMangaAndChapters.awaitManga(it) },
                    sourceOf = { sourceManager.get(it.source) },
                    refresh = { manga, source -> fetch(source, manga) },
                )

                if (manualFetch) {
                    downloadNewChapters(newChapters)
                }
                firstError?.let { throw it }
                // RK <--
            }
        } catch (_: CancellationException) {
            // ignore
        } catch (e: Exception) {
            // RK: the wording is shared with the novel details refresh
            val message = with(context) { e.refreshFailureMessage() }

            viewModelScope.launch {
                snackbarHostState.showSnackbar(message = message)
            }
        }
    }

    // Manga info - start

    fun toggleFavorite() {
        toggleFavorite(checkDuplicate = true) // RK: a remove reports itself, see removeFromLibrary
    }

    // RK --> the heart's remove. Which entries leave is the details removal rule novels share
    //        (DetailsRemoval); the E-Hentai account confirm and the downloads prompt see exactly those.
    fun removeFromLibrary(mangaIds: List<Long>) {
        viewModelScope.launchIO {
            val targets = mangaIds.map { getMangaAndChapters.awaitManga(it) }
            // The account confirm is per gallery, so only a remove of one gallery asks it.
            val gallery = targets.singleOrNull()?.takeIf(::shouldConfirmEhRemoveFromAccount)
            if (gallery != null) {
                dialog.value = Dialog.EhRemoveFavorite(gallery)
                return@launchIO
            }
            removeNow(targets)
        }
    }

    private suspend fun removeNow(targets: List<Manga>) {
        promptDeleteDownloadsOnRemoved(removeMangaFromLibrary.await(targets.map { it.id }))
    }

    // On the page's own scope: the account removal reaches here on an app-wide one.
    private fun promptDeleteDownloadsOnRemoved(removedIds: List<Long>) {
        viewModelScope.launchIO {
            snackbarHostState.offerToDeleteDownloads(
                context = context,
                removed = removedIds.map { getMangaAndChapters.awaitManga(it) },
                hasDownloads = { downloadManager.getDownloadCount(it) > 0 },
            ) { downloadManager.deleteManga(it, sourceManager.getOrStub(it.source)) }
        }
    }
    // RK <--

    /**
     * Update favorite status of manga, (removes / adds) manga (to / from) library.
     */
    fun toggleFavorite(
        // RK: onRemoved dropped, the remove reporting the entries it actually took out instead
        checkDuplicate: Boolean,
    ) {
        val state = successState ?: return
        viewModelScope.launchIO {
            val manga = state.manga

            if (isFavorited) {
                // Remove from library
                // RK --> asks first on a merged entry's All view, and takes a source chip's own entry
                val removal = mergeGroup.removal(manga.id)
                if (removal.asksForGroup) {
                    dialog.value = Dialog.RemoveFromLibrary(removal)
                } else {
                    removeFromLibrary(removal.targets(removeGrouped = false))
                }
                // RK <--
            } else {
                // Add to library
                // First, check if duplicate exists if callback is provided
                if (checkDuplicate) {
                    // RK --> the merge-aware duplicate prompt every add path takes from the shared adder
                    val prompt = mangaLibraryAdder.findDuplicates(manga)

                    if (prompt != null) {
                        dialog.value = Dialog.DuplicateManga(manga, prompt)
                        return@launchIO
                    }
                    // RK <--
                }

                // RK: the shared add sequence, so no add path can drift from the others: decide,
                // favorite, file, and abandon the whole add if the favorite write fails.
                val outcome = addEntry(
                    resolveCategories = { mangaLibraryAdder.resolveDefaultCategories() },
                    favorite = { mangaLibraryAdder.favoriteForAdd(manga.id) },
                    fileCategories = { _, categoryIds -> mangaLibraryAdder.moveToCategories(manga, categoryIds) },
                )
                // RK: the favorite step binds the trackers and, through the source-tracker hook, backs an
                //     E-Hentai gallery up to the account, so a picker's dismiss binds and pushes nothing.
                if (outcome == AddOutcome.NeedsCategoryChoice) showChangeCategoryDialog()
            }
        }
    }

    // RK: add-time grouping, through the shared add sequence: the group's categories win, then the
    // default, and a picker it has to raise writes nothing until its confirm. Only the picks the user
    // chose: the duplicate list is fuzzy, so merging every match would fuse distinct series.
    fun addToExistingGroup(selectedIds: List<Long>) {
        val manga = successState?.manga ?: return
        viewModelScope.launchIO {
            val outcome = addEntry(
                resolveCategories = { mangaLibraryAdder.groupOrDefaultCategories(selectedIds) },
                favorite = { mangaLibraryAdder.joinGroup(manga, selectedIds) },
                fileCategories = { _, categoryIds -> mangaLibraryAdder.moveToCategories(manga, categoryIds) },
            )
            if (outcome == AddOutcome.NeedsCategoryChoice) showChangeCategoryDialog(joinGroup = selectedIds)
        }
    }

    // RK -->

    // Komikku's root redirect: opening a gallery whose version chain holds an older favorited copy
    // reconciles the chain and opens that copy. Sequential, so a reconcile is never cut off halfway.
    private fun observeExhRootRedirect() {
        if (!DebugToggles.ENABLE_EXH_ROOT_REDIRECT.enabled) return
        viewModelScope.launchIO {
            // Only a gallery has a version chain; watching any other entry would hold its chapter query open for the
            // model's whole life.
            if (!getMangaAndChapters.awaitManga(mangaId).isEhBasedManga()) return@launchIO
            val root = getMangaAndChapters.subscribe(mangaId, applyScanlatorFilter = true)
                .distinctUntilChanged()
                .mapNotNull { (manga, chapters) -> favoritedRootOf(manga, chapters) }
                .first()
            exhRootRedirect.send(root)
        }
    }

    private suspend fun favoritedRootOf(manga: Manga, chapters: List<Chapter>): Long? {
        if (chapters.isEmpty() || !manga.isEhBasedManga()) return null
        val accepted = runCatchingCancellable {
            updateHelper.findAcceptedRootAndDiscardOthers(manga.source, chapters)?.first
        }
            .onFailure { logcat(LogPriority.ERROR, it) { "Error loading accepted chapter chain" } }
            .getOrNull() ?: return null
        return accepted.manga.id.takeIf { it != manga.id && accepted.manga.favorite }
    }

    private fun shouldConfirmEhRemoveFromAccount(manga: Manga): Boolean {
        return manga.isEhBasedManga() && exhPreferences.isFavoritesBackupOn()
    }

    // A failed account removal keeps the gallery in the library. The removal runs on an app-wide scope
    // so leaving the page mid-request cannot drop the library half; the downloads prompt needs the page.
    fun confirmEhRemoveFromLibrary(gallery: Manga, removeFromAccount: Boolean) {
        dismissDialog()
        viewModelScope.launchIO {
            val source = sourceManager.get(gallery.source) as? EHentai ?: return@launchIO removeNow(listOf(gallery))
            source.removeGallery(remoteFirstRemoval, gallery, removeFromAccount) { removeNow(listOf(gallery)) }
        }
    }
    // RK <--

    fun showChangeCategoryDialog(joinGroup: List<Long> = emptyList()) { // RK: joinGroup
        val manga = successState?.manga ?: return
        viewModelScope.launch {
            // RK: the adder's picker, ordered by the category sort-order pref like the library's pickers.
            val selection = mangaLibraryAdder.categoryPickerSelection(manga.id)
            dialog.value = Dialog.ChangeCategory(
                manga = manga,
                initialSelection = selection,
                joinGroup = joinGroup, // RK
            )
        }
    }

    fun showSetFetchIntervalDialog() {
        val manga = successState?.intervalManga ?: return // RK: the All view's served member
        dialog.value = Dialog.SetFetchInterval(manga)
    }

    fun setFetchInterval(manga: Manga, interval: Int) {
        viewModelScope.launchIO {
            updateManga.awaitUpdateFetchInterval(
                // Custom intervals are negative
                manga.copy(fetchInterval = -interval),
            )
        }
    }

    // RK: hasDownloads / deleteDownloads moved into promptDeleteDownloadsOnRemoved, over the entries removed

    // RK --> The download directory the details overflow's Open folder opens.
    suspend fun viewedDownloadDir(): UniFile? {
        val owner = successState?.downloadFolderOwner ?: return null
        return downloadManager.findMangaDir(owner, sourceManager.getOrStub(owner.source))
    }

    // Counted off the download cache, which is in memory, so running it on every emission is cheap.
    private fun downloadFolderOwnerOf(viewed: Manga?, group: Collection<Manga>): Manga? =
        downloadFolderOwner(viewed, group.toList(), { it.id == mangaId }, { downloadManager.getDownloadCount(it) > 0 })
    // RK <--

    // RK --> Clear downloads for what the screen shows (EntryMergeGroupHost.clearDownloadsTarget).
    //        Distinct from promptDeleteDownloadsOnRemoved, which covers the entries a remove took out.
    fun showClearDownloadsDialog() {
        dialog.value = Dialog.ClearDownloads(mergeGroup.clearDownloadsTarget(mangaId))
    }

    fun clearDownloads(mangaIds: List<Long>) {
        viewModelScope.launchNonCancellable {
            mangaIds.map { getMangaAndChapters.awaitManga(it) }
                .forEach { downloadManager.deleteManga(it, sourceManager.getOrStub(it.source)) }
        }
    }
    // RK <--

    // RK: the picker's confirm owes both writes the add deferred, in the shared order, so backing out
    // of the picker adds nothing and a failed favorite leaves no categories behind. A group add's
    // favorite joins [joinGroup]'s group as one unit.
    fun moveMangaToCategoriesAndAddToLibrary(
        manga: Manga,
        categories: List<Long>,
        joinGroup: List<Long>,
    ) {
        viewModelScope.launchIO {
            finishAdd(
                categoryIds = categories,
                favorite = {
                    if (joinGroup.isNotEmpty()) {
                        mangaLibraryAdder.joinGroup(manga, joinGroup)
                    } else {
                        if (manga.favorite) manga.id else mangaLibraryAdder.favoriteForAdd(manga.id)
                    }
                },
                fileCategories = { _, categoryIds -> mangaLibraryAdder.moveToCategories(manga, categoryIds) },
            )
        }
    }

    // Manga info - end

    // Chapters list - start

    private fun updateDownloadState(download: Download) {
        val chapterId = download.chapter.id
        downloadStates.update {
            // Terminal states are derived by the queried item itself, so drop the override instead
            // of letting it outlive reality, e.g. showing a since deleted chapter as downloaded.
            if (download.status == Download.State.NOT_DOWNLOADED || download.status == Download.State.DOWNLOADED) {
                it - chapterId
            } else {
                it + (chapterId to DownloadProgress(download.status, download.progress))
            }
        }
    }

    // RK --> merged groups: each row resolves its own source's manga and the group's cross-source state
    private fun List<Chapter>.toChapterListItems(
        manga: Manga,
        // RK: the view's read, bookmarked and on-disk answers, in the view's merge scope.
        flags: GroupChapterFlags<Chapter>,
        // RK: for merged groups, each chapter's own source-manga, so download status resolves
        // against the source it actually came from (key: mangaId). Empty for non-merged manga.
        mangaBySource: Map<Long, Manga> = emptyMap(),
        // RK: the copy each row's download fetches, whose queue state the row shows
        downloadTargets: DownloadTargets,
    ): List<ChapterList.Item> {
        val queuedDownloads = downloadManager.getQueuedDownloadsByChapterId()
        return map { chapter ->
            val owner = mangaBySource[chapter.mangaId] ?: manga
            val activeDownload = if (owner.isLocal()) {
                null
            } else {
                downloadTargets.queuedFor(chapter.id, queuedDownloads::get)
            }
            // The rule every Reikai row reads, novels' details list included.
            val downloadState = downloadStateOf(activeDownload?.status) { flags.isDownloaded(chapter) }
            // RK <--

            ChapterList.Item(
                chapter = chapter,
                downloadState = downloadState,
                downloadProgress = activeDownload?.progress ?: 0,
                isRead = flags.isRead(chapter), // RK
                isBookmarked = flags.isBookmarked(chapter), // RK
            )
        }
    }

    // RK -->

    /** Combine inputs for the chapter flow. */
    private data class ChapterInputs(
        val manga: Manga,
        val ownChapters: List<Chapter>,
        val group: EntryMergeGroupHost.GroupState,
    )

    /** The chapter flow's rows, built and hidden, with what the list derives from them. */
    private data class ChapterView(
        val merged: MergedChapters,
        val source: Source,
        val hidden: HiddenChapters,
        val numberHints: Map<Long, ChapterNumberHint.Hint>,
        val downloadFolderOwner: Manga?,
    )

    private data class MergeInputs(
        val scanlators: ScanlatorFilterView,
        val customInfo: CustomMangaInfo?,
        val chips: List<EntryMergeSource>,
        val selectedSource: Long?,
        val galleryMetadata: RaisedSearchMetadata?,
    )

    private data class Extras(
        val seedColor: Color? = null,
        val pagePreviewsState: PagePreviewState = PagePreviewState.Unused,
        val previewsRowCount: Int,
        val relatedItems: List<RelatedMangaItem> = emptyList(),
        val relatedTotalCount: Int = 0,
        val relatedLoading: Boolean = false,
    )

    private data class DownloadProgress(val status: Download.State, val progress: Int)

    /** What the first open fetches, decided by the seed. */
    private data class FirstFetch(val details: Boolean, val chapters: Boolean)

    /** Display payload for the chapter flow: the screen manga, the (possibly merged) chapter list,
     *  and the per-source manga for merged groups (empty when not merged). */
    private data class MergedChapters(
        val manga: Manga,
        val chapters: List<Chapter>,
        val mangaBySource: Map<Long, Manga>,
        // RK: read, bookmarked and on disk as the view's merge scope answers them for [chapters]. Built per
        // render, since the flags cache their disk probe and a download tick re-renders without reloading.
        val flags: () -> GroupChapterFlags<Chapter>,
        // RK: the out-of-line markers, read off each source's own list: a merged [chapters] is restamped.
        // Built per render, since a hide re-renders without reloading and hidden rows are not judged.
        val numberHints: () -> Map<Long, ChapterNumberHint.Hint>,
        // RK: per-source metadata shown in the info box when a source chip is active (null = unified).
        // Kept separate from [manga] so favorite / tracking / chapter-flag actions stay on the primary.
        val displayManga: Manga? = null,
        val displaySource: Source? = null,
        // RK: the installed member the All view serves in place of an anchor whose source is gone
        // (unifiedViewMember), null otherwise. Downloads, the web page and the interval follow it.
        val servedManga: Manga? = null,
        val servedSource: Source? = null,
        // RK: the copy each row's download fetches; only the All view moves one off a missing source
        val downloadTargets: DownloadTargets = DownloadTargets.OWN,
    )

    /** Chapters of a single grouped source (chip selection), keyed for download by its own manga.
     *  Subscribes to the whole group even though it only shows one source's chapters, so a chapter
     *  read on a sibling still reads as read here: "have I read this chapter" is a property of the
     *  story, not of the source's own row, and the All view would otherwise disagree with the chip. */
    private suspend fun singleSourceChaptersFlow(
        displayManga: Manga,
        sourceMangaId: Long,
        group: EntryMergeGroupHost.GroupState,
    ): Flow<MergedChapters> {
        val sourceManager = sourceManager
        val perSibling = group.ids.map { id ->
            getMangaAndChapters.subscribe(id, applyScanlatorFilter = true)
                .distinctUntilChanged()
                .map { (manga, chapters) -> Triple(id, manga, chapters) }
        }
        return combine(perSibling) { siblings ->
            val chaptersBySource = siblings.associate { (id, _, chapters) -> id to chapters }
            val sourceManga = siblings.first { (id, _, _) -> id == sourceMangaId }.second
            val ownChapters = chaptersBySource[sourceMangaId].orEmpty()
            val pooled = chaptersBySource.values.flatten()
            val mangaBySource = siblings.associate { (id, manga, _) -> id to manga }
            val stitch = mergedChapterProvider.stitchOf(sourceMangaId)
            MergedChapters(
                manga = displayManga.withGroupChapterFlags(siblings.map { it.second }),
                chapters = ownChapters,
                mangaBySource = mapOf(sourceManga.id to sourceManga),
                displayManga = sourceManga,
                displaySource = sourceManager.getOrStub(sourceManga.source),
                // The chip shows one source, but a chapter read on a sibling still reads as read.
                flags = {
                    group.rowFlags(pooled, ownChapters, stitch, { it.id }, { it.read }, { it.bookmark }) {
                        downloadedIdsOf(pooled, mangaBySource, sourceManga)
                    }
                },
                numberHints = { ownChapters.numberHints(emptyMap(), sourceManga) },
            )
        }
    }

    /** Resolved once per emission over every copy, since the same probe answers three questions here. */
    private fun downloadedIdsOf(
        chapters: List<Chapter>,
        mangaBySource: Map<Long, Manga>,
        fallback: Manga,
    ): Set<Long> = downloadManager.downloadedChapterIds(chapters) { mangaBySource[it.mangaId] ?: fallback }

    /** Expand [chapters] to every grouped source's copy of the same merged chapters, so read /
     *  bookmark applies across the whole group. No-op when not merged.
     *
     *  Reads the stored stitch rather than matching chapter numbers, which two sources of one series
     *  count differently: comparing them reached a chapter several along on the sibling source. */
    private suspend fun expandToGroup(chapters: List<Chapter>): List<Chapter> =
        mergeGroup.expandToGroup(chapters, { it.id }, { mergedChapterProvider.stitchOf(mangaId) }, ::groupChaptersIn)

    private suspend fun expandForDelete(chapters: List<Chapter>): List<Chapter> =
        mergeGroup.expandForDelete(chapters, { it.id }, { mergedChapterProvider.stitchOf(mangaId) }, ::groupChaptersIn)

    private suspend fun groupChaptersIn(ids: Set<Long>): List<Chapter> =
        mergeGroup.relatedIds.flatMap { getMangaAndChapters.awaitChapters(it) }.filter { it.id in ids }

    /** An unmerged entry's rows, each answering for itself. */
    private fun ownFlags(chapters: List<Chapter>, manga: Manga) =
        GroupChapterFlags(MergeScope.Group, chapters, chapters, emptyList(), { it.id }, { it.read }, { it.bookmark }) {
            downloadedIdsOf(chapters, emptyMap(), manga)
        }

    /** Raise a [FlatMetadata] row into its source's typed metadata; null when the source isn't a
     *  MetadataSource or nothing was stored. */
    private suspend fun raiseMetadata(flatMetadata: FlatMetadata?, targetMangaId: Long): RaisedSearchMetadata? {
        if (flatMetadata == null) return null
        val targetManga = getMangaAndChapters.awaitManga(targetMangaId)
        val metadataSource = sourceManager.get(targetManga.source)
            ?.getMainSource<MetadataSource<*, *>>() ?: return null
        return flatMetadata.raise(metadataSource.metaClass)
    }

    /** Resolve the source-switcher chips for the full group (empty when not merged). */
    private suspend fun buildMergeSources(ids: LongArray): List<EntryMergeSource> {
        if (ids.size <= 1) return emptyList()
        val sourceManager = sourceManager
        return ids.map { id ->
            val sourceManga = getMangaAndChapters.awaitManga(id)
            EntryMergeSource(id, sourceManager.getOrStub(sourceManga.source).name)
        }
    }

    /** Combine every grouped source's chapters into one aggregated, deduped, reading-ordered list.
     *  Suspend because [GetMangaWithChapters.subscribe] is; called from the suspend flatMapLatest. */
    private suspend fun mergedChaptersFlow(
        displayManga: Manga,
        group: EntryMergeGroupHost.GroupState,
    ): Flow<MergedChapters> {
        val perSibling = mutableListOf<Flow<Triple<Long, Manga, List<Chapter>>>>()
        for (id in group.ids) {
            perSibling += getMangaAndChapters.subscribe(id, applyScanlatorFilter = true)
                .distinctUntilChanged()
                .map { (manga, chapters) -> Triple(id, manga, chapters) }
        }
        return combine(perSibling) { siblings ->
            val mangaBySource = siblings.associate { (id, manga, _) -> id to manga }
            val chaptersBySource = siblings.associate { (id, _, chapters) -> id to chapters }
            // Read off the stored stitch, the same rows the library badge counts.
            val pooled = chaptersBySource.values.flatten()
            val stitch = mergedChapterProvider.stitchOf(displayManga.id)
            val merged = mergedChapterProvider.merged(pooled, stitch)
            val sources = mangaBySource.values.associate { it.id to sourceManager.getOrStub(it.source) }
            val isInstalled = { mangaId: Long -> sources[mangaId].let { it != null && it !is StubSource } }
            val served = unifiedViewMember(displayManga.id, group.ids.asList(), isInstalled)
            MergedChapters(
                manga = displayManga.withGroupChapterFlags(mangaBySource.values),
                chapters = merged,
                mangaBySource = mangaBySource,
                servedManga = mangaBySource[served]?.takeIf { served != displayManga.id },
                servedSource = sources[served]?.takeIf { served != displayManga.id },
                downloadTargets = DownloadTargets.of(group.mergeScope, pooled, merged, stitch, { it.id }) {
                    isInstalled(it.mangaId)
                },
                flags = {
                    group.rowFlags(pooled, merged, stitch, { it.id }, { it.read }, { it.bookmark }) {
                        downloadedIdsOf(pooled, mangaBySource, displayManga)
                    }
                },
                numberHints = { pooled.numberHints(mangaBySource, displayManga) },
            )
        }
    }

    // Hide/unhide chapters (manga twin of the novel details mechanism). The hidden set is a pref of
    // restore-stable "<source>|<chapterUrl>" keys; it filters Success.chapters at assembly, so hidden
    // chapters also drop from download-all, and Resume opens one only when nothing else is unread. The
    // in-app manga reader excludes them too (ReaderViewModel.chapterList), so next/prev skips hidden.

    private data class HiddenChapters(
        val chapters: List<ChapterList.Item>,
        val showHidden: Boolean,
        val hasHiddenChapters: Boolean,
        val hiddenChapterIds: Set<Long>,
        val gapPresent: ChapterGap.Present,
        val resumeChapter: Chapter?,
    )

    /** Drop hidden chapters from [items] unless the user is temporarily showing them, and compute the
     *  hide-related state. "Showing hidden" only holds while hidden chapters still exist, so unhiding
     *  the last one collapses the mode instead of leaving a stale toggle. The gap numbers are read
     *  before the drop, so a hidden chapter's number is never counted missing. */
    private fun applyHiddenChapters(
        items: List<ChapterList.Item>,
        manga: Manga,
        mangaBySource: Map<Long, Manga>,
    ): HiddenChapters {
        val hidden = hiddenChaptersPref.get()
        val keyOf = { item: ChapterList.Item -> item.chapter.hiddenKey(mangaBySource[item.chapter.mangaId] ?: manga) }
        val view = resolveHiddenChapterView(items, hidden, showHiddenFlow.value, keyOf)
        val hiddenChapterIds = hiddenChapterIdsIn(view.visible, hidden, view.showHidden, keyOf) { it.id }
        val gapPresent = items.map { it.chapter }.gapPresent()
        // Over every row, hidden ones last, which only this step still has.
        val resumeChapter = items.getNextUnread(manga) { keyOf(it) in hidden }
        return HiddenChapters(
            view.visible,
            view.showHidden,
            view.hasHidden,
            hiddenChapterIds,
            gapPresent,
            resumeChapter,
        )
    }

    fun hideSelected() {
        val state = successState ?: return
        val keys = state.selectedRows()
            .map { it.chapter.hiddenKey(state.mergedMangaById[it.chapter.mangaId] ?: state.manga) }
        if (keys.isEmpty()) return
        hiddenChaptersPref.set(hiddenChaptersPref.get() + keys)
        toggleAllSelection(false)
    }

    /** Only reachable while hidden chapters are being shown. */
    fun unhideSelected() {
        val state = successState ?: return
        val keys = state.selectedRows()
            .mapTo(HashSet()) { it.chapter.hiddenKey(state.mergedMangaById[it.chapter.mangaId] ?: state.manga) }
        if (keys.isEmpty()) return
        hiddenChaptersPref.set(hiddenChaptersPref.get().filterNotTo(HashSet()) { it in keys })
        toggleAllSelection(false)
    }

    fun toggleShowHidden() {
        showHiddenFlow.value = !showHiddenFlow.value
    }

    // The one selected chapter's number dialog, by the rule novels share (EditChapterNumber).
    fun showChapterNumberDialog() {
        successState?.selectedRows()?.singleOrNull()?.let { showChapterNumberDialog(it.id) }
    }

    // A marked chapter's dialog opens on its hint's suggestion.
    fun showChapterNumberDialog(chapterId: Long) {
        val state = successState ?: return
        val chapter = state.chapters.firstOrNull { it.id == chapterId }?.chapter ?: return
        viewModelScope.launchIO {
            val edit = editChapterNumber.edit(
                ContentType.MANGA,
                chapter.mangaId,
                chapter.url,
                chapter.name,
                chapter.chapterNumber,
                state.numberHints[chapterId]?.suggestion,
            )
            dialog.value = Dialog.ChapterNumber(edit)
        }
    }

    private fun List<Chapter>.numberHints(
        mangaBySource: Map<Long, Manga>,
        manga: Manga,
    ): Map<Long, ChapterNumberHint.Hint> {
        val hidden = hiddenChaptersPref.get()
        return ChapterNumberHint.forOwners(
            this,
            id = { it.id },
            owner = { it.mangaId },
            sourceOrder = { it.sourceOrder },
            number = { it.chapterNumber },
            name = { it.name },
            dateUpload = { it.dateUpload },
            isHidden = { it.hiddenKey(mangaBySource[it.mangaId] ?: manga) in hidden },
        )
    }

    fun saveChapterNumber(edit: ChapterNumberEdit, number: Double?) {
        viewModelScope.launchNonCancellable { editChapterNumber.save(edit, number) }
        toggleAllSelection(false)
    }
    // RK <--

    /**
     * @throws IllegalStateException if the swipe action is [LibraryPreferences.ChapterSwipeAction.Disabled]
     */
    fun chapterSwipe(chapterItem: ChapterList.Item, swipeAction: LibraryPreferences.ChapterSwipeAction) {
        viewModelScope.launch {
            executeChapterSwipeAction(chapterItem, swipeAction)
        }
    }

    /**
     * @throws IllegalStateException if the swipe action is [LibraryPreferences.ChapterSwipeAction.Disabled]
     */
    private fun executeChapterSwipeAction(
        chapterItem: ChapterList.Item,
        swipeAction: LibraryPreferences.ChapterSwipeAction,
    ) {
        val chapter = chapterItem.chapter
        when (swipeAction) {
            // RK: toggled against what the row shows, which on a merged entry is the group's state.
            LibraryPreferences.ChapterSwipeAction.ToggleRead -> {
                markChaptersRead(listOf(chapter), !chapterItem.isRead)
            }
            LibraryPreferences.ChapterSwipeAction.ToggleBookmark -> {
                bookmarkChapters(listOf(chapter), !chapterItem.isBookmarked)
            }
            LibraryPreferences.ChapterSwipeAction.Download -> {
                val downloadAction: ChapterDownloadAction = when (chapterItem.downloadState) {
                    Download.State.ERROR,
                    Download.State.NOT_DOWNLOADED,
                    -> ChapterDownloadAction.START_NOW
                    Download.State.QUEUE,
                    Download.State.DOWNLOADING,
                    -> ChapterDownloadAction.CANCEL
                    Download.State.DOWNLOADED -> ChapterDownloadAction.DELETE
                }
                runChapterDownloadActions(
                    items = listOf(chapterItem),
                    action = downloadAction,
                )
            }
            LibraryPreferences.ChapterSwipeAction.Disabled -> throw IllegalStateException()
        }
    }

    /**
     * Returns the next unread chapter or null if everything is read.
     */
    fun getNextUnreadChapter(): Chapter? {
        // RK: picked when the list is built, the one step that still has the hidden rows.
        return successState?.resumeChapter
    }

    // RK -->
    // The rows a bulk download picks from, one rule with novels.
    private fun downloadCandidates(): List<ChapterList.Item> =
        DownloadCandidates.rows(filteredChapters.orEmpty(), allChapters.orEmpty(), skipFiltered)
    // RK <--

    private fun startDownload(
        chapters: List<Chapter>,
        startNow: Boolean,
    ) {
        val state = successState ?: return // RK

        viewModelScope.launchNonCancellable {
            // RK: a row whose source is gone fetches an installed source's copy of it, or nothing
            val copies = state.downloadTargets.of(chapters, { it.id }, ::groupChaptersIn)
            if (startNow) {
                val chapterId = copies.singleOrNull()?.id ?: return@launchNonCancellable // RK
                downloadManager.startDownloadNow(chapterId)
            } else {
                downloadChapters(copies) // RK
            }

            // RK: the prompt and its once-per-screen rule are written once with novels
            addToLibraryOffer.afterDownload(isInLibrary = { isFavorited }) { toggleFavorite() }
        }
    }

    fun runChapterDownloadActions(
        items: List<ChapterList.Item>,
        action: ChapterDownloadAction,
    ) {
        when (action) {
            ChapterDownloadAction.START -> {
                startDownload(items.map { it.chapter }, false)
                if (items.any { it.downloadState == Download.State.ERROR }) {
                    downloadManager.startDownloads()
                }
            }
            ChapterDownloadAction.START_NOW -> {
                val chapter = items.singleOrNull()?.chapter ?: return
                startDownload(listOf(chapter), true)
            }
            ChapterDownloadAction.CANCEL -> {
                val chapterId = items.singleOrNull()?.id ?: return
                cancelDownload(chapterId)
            }
            ChapterDownloadAction.DELETE -> {
                deleteChapters(items.map { it.chapter })
            }
        }
    }

    fun runDownloadAction(action: DownloadAction) {
        // RK --> the shared candidate rows and the selection rule novels run too, in the order the
        // reader pages in, so "next N" queues the chapters it steps into. isRead and isBookmarked are
        // the any-source flags, so a chapter a grouped source has read or holds is not queued.
        val manga = successState?.manga ?: return
        val hidden = successState?.hiddenChapterIds.orEmpty()
        val items = downloadCandidates().associateBy { it.id }
        val chaptersToDownload = DownloadCandidates.forAction(
            items.values.map { it.chapter }.inReadingOrder(manga),
            action,
            isRead = { items.getValue(it.id).isRead },
            isBookmarked = { items.getValue(it.id).isBookmarked },
            isHidden = { it.id in hidden },
            isExcluded = { items.getValue(it.id).downloadState != Download.State.NOT_DOWNLOADED },
        )
        // RK <--
        if (chaptersToDownload.isNotEmpty()) {
            startDownload(chaptersToDownload, false)
        }
    }

    private fun cancelDownload(chapterId: Long) {
        // RK: the row shows the queue state of the copy its download fetches
        val activeDownload = (successState?.downloadTargets ?: DownloadTargets.OWN)
            .queuedFor(chapterId, downloadManager::getQueuedDownloadOrNull) ?: return
        downloadManager.cancelQueuedDownloads(listOf(activeDownload))
        updateDownloadState(activeDownload.apply { status = Download.State.NOT_DOWNLOADED })
    }

    fun markPreviousChapterRead(pointer: Chapter) {
        val manga = successState?.manga ?: return
        val shown = filteredChapters.orEmpty().map { it.chapter }
        // RK: the shared rule for "the rows above this one", which novels mark by too.
        val previous = ReadingOrder.before(ReadingOrder.of(shown, manga.sortDescending())) { it == pointer }
        if (previous.isNotEmpty()) markChaptersRead(previous, true)
    }

    /**
     * Mark the selected chapter list as read/unread.
     * @param chapters the list of selected chapters.
     * @param read whether to mark chapters as read or unread.
     */
    fun markChaptersRead(chapters: List<Chapter>, read: Boolean) {
        toggleAllSelection(false)
        if (chapters.isEmpty()) return
        viewModelScope.launchIO {
            // RK: the write across the merge group and the tracker push are the step both details models run
            autoTrackOnMarkRead.setRead(mangaId, chapters, read)
        }
    }

    // RK --> shared with the novel details model, so a change to the tracker push reaches both
    private val autoTrackOnMarkRead = EntryAutoTrackOnMarkRead<Chapter>(
        context = context,
        snackbarHostState = snackbarHostState,
        trackerManager = trackerManager,
        trackPreferences = trackPreferences,
        expandToGroup = { expandToGroup(it) },
        writeRead = { chapters, read -> setReadStatus.await(read = read, chapters = chapters.toTypedArray()) },
        chapterNumber = Chapter::chapterNumber,
        refresh = { refreshTracks.await(it) },
        lastReadPerTracker = { getTracksInGroup.await(it).map(Track::lastChapterRead) },
        pushProgress = { id, chapterNumber -> trackChapter.await(context, id, chapterNumber) },
    )
    // RK <--

    /**
     * Downloads the given list of chapters with the manager.
     * @param chapters the list of chapters to download.
     */
    private suspend fun downloadChapters(chapters: List<Chapter>) {
        // RK: read to resolve each chapter's owner below
        val state = successState ?: return
        // RK --> in a merged group, download each chapter from its own source-manga
        chapters.groupBy { it.mangaId }.forEach { (mangaId, group) ->
            downloadManager.downloadChapters(ownerOf(mangaId, state), group)
        }
        // RK <--
        toggleAllSelection(false)
    }

    // RK --> chapter owner lookup for merged groups

    /**
     * RK: the manga a chapter belongs to. Read from the database when the screen's own map cannot
     * answer, which a source chip makes routine: it narrows that map to one source while an expanded
     * action carries the group's other copies, and resolving those to the screen's manga sent a delete
     * into the wrong source's download folder.
     */
    private suspend fun ownerOf(chapterMangaId: Long, state: State.Success): Manga =
        state.mergedMangaById[chapterMangaId]
            ?: state.manga.takeIf { it.id == chapterMangaId }
            ?: getMangaAndChapters.awaitManga(chapterMangaId)
    // RK <--

    /**
     * Bookmarks the given list of chapters.
     * @param chapters the list of chapters to bookmark.
     */
    fun bookmarkChapters(chapters: List<Chapter>, bookmarked: Boolean) {
        viewModelScope.launchIO {
            // RK: bookmark the matching chapter in every grouped source too
            expandToGroup(chapters)
                .filterNot { it.bookmark == bookmarked }
                .map { ChapterUpdate(it.id) { bookmark = bookmarked } }
                .let { updateChapter.awaitAll(it) }
        }
        toggleAllSelection(false)
    }

    /**
     * Deletes the given list of chapter.
     *
     * @param chapters the list of chapters to delete.
     */
    fun deleteChapters(chapters: List<Chapter>) {
        viewModelScope.launchNonCancellable {
            try {
                successState?.let { state ->
                    // RK --> in a merged group, delete each chapter's download from its own source.
                    // Expanded first: the row is downloaded when ANY copy holds the file, so deleting
                    // only the shown copy would leave the row still reading as downloaded.
                    val sourceManager = sourceManager
                    expandForDelete(chapters).groupBy { it.mangaId }.forEach { (mangaId, group) ->
                        val owner = ownerOf(mangaId, state)
                        downloadManager.deleteChapters(group, owner, sourceManager.getOrStub(owner.source))
                    }
                    // RK <--
                }
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e)
            }
        }
    }

    private fun downloadNewChapters(chapters: List<Chapter>) {
        viewModelScope.launchNonCancellable {
            val manga = successState?.manga ?: return@launchNonCancellable
            val chaptersToDownload = filterChaptersForDownload.await(manga, chapters)

            if (chaptersToDownload.isNotEmpty()) {
                downloadChapters(chaptersToDownload)
            }
        }
    }

    /**
     * Sets the read filter and requests an UI update.
     * @param state whether to display only unread chapters or all chapters.
     */
    fun setUnreadFilter(state: TriState) {
        val manga = successState?.manga ?: return

        val flag = when (state) {
            TriState.DISABLED -> Manga.SHOW_ALL
            TriState.ENABLED_IS -> Manga.CHAPTER_SHOW_UNREAD
            TriState.ENABLED_NOT -> Manga.CHAPTER_SHOW_READ
        }
        changeChapterSettings(manga) { setMangaChapterFlags.awaitSetUnreadFilter(manga, flag) } // RK
    }

    /**
     * Sets the download filter and requests an UI update.
     * @param state whether to display only downloaded chapters or all chapters.
     */
    fun setDownloadedFilter(state: TriState) {
        val manga = successState?.manga ?: return

        val flag = when (state) {
            TriState.DISABLED -> Manga.SHOW_ALL
            TriState.ENABLED_IS -> Manga.CHAPTER_SHOW_DOWNLOADED
            TriState.ENABLED_NOT -> Manga.CHAPTER_SHOW_NOT_DOWNLOADED
        }

        changeChapterSettings(manga) { setMangaChapterFlags.awaitSetDownloadedFilter(manga, flag) } // RK
    }

    /**
     * Sets the bookmark filter and requests an UI update.
     * @param state whether to display only bookmarked chapters or all chapters.
     */
    fun setBookmarkedFilter(state: TriState) {
        val manga = successState?.manga ?: return

        val flag = when (state) {
            TriState.DISABLED -> Manga.SHOW_ALL
            TriState.ENABLED_IS -> Manga.CHAPTER_SHOW_BOOKMARKED
            TriState.ENABLED_NOT -> Manga.CHAPTER_SHOW_NOT_BOOKMARKED
        }

        changeChapterSettings(manga) { setMangaChapterFlags.awaitSetBookmarkFilter(manga, flag) } // RK
    }

    /**
     * Sets the active display mode.
     * @param mode the mode to set.
     */
    fun setDisplayMode(mode: Long) {
        val manga = successState?.manga ?: return

        changeChapterSettings(manga) { setMangaChapterFlags.awaitSetDisplayMode(manga, mode) } // RK
    }

    /**
     * Sets the sorting method and requests an UI update.
     * @param sort the sorting mode.
     */
    fun setSorting(sort: Long) {
        val manga = successState?.manga ?: return

        changeChapterSettings(manga) { setMangaChapterFlags.awaitSetSortingModeOrFlipOrder(manga, sort) } // RK
    }

    fun setCurrentSettingsAsDefault(applyToExisting: Boolean) {
        val manga = successState?.manga ?: return
        viewModelScope.launchNonCancellable {
            libraryPreferences.setChapterSettingsDefault(manga)
            if (applyToExisting) {
                setMangaDefaultChapterFlags.awaitAll()
            }
            snackbarHostState.showSnackbar(message = context.stringResource(MR.strings.chapter_settings_updated))
        }
    }

    fun resetToDefaultSettings() {
        val manga = successState?.manga ?: return
        changeChapterSettings(manga) { setMangaDefaultChapterFlags.await(manga) } // RK
    }

    // RK --> a merged series shares one chapter setting, so [change] writes the opened manga from the
    // setting it shows, the lead's, and every other member takes the result (GroupChapterSettings)
    private fun changeChapterSettings(manga: Manga, change: suspend () -> Unit) {
        viewModelScope.launchNonCancellable {
            chapterSettings.change(manga.id, mergeGroup.relatedIds.asList(), change)
        }
    }
    // RK <--

    // RK --> chapter selection routes through the shared kernel, so manga, novels and every other
    // multi-select surface answer a range the same way. A long press ranges from the last row you
    // touched; a tap toggles one row. Each verb starts from the selection the rows show, retained to them.
    fun toggleSelection(item: ChapterList.Item, fromLongPress: Boolean = false) {
        val visible = successState?.processedChapters?.map { it.id } ?: return
        chapterSelection.update { held ->
            val shown = EntrySelection.retain(held, visible)
            if (fromLongPress) {
                EntrySelection.rangeOrToggle(shown, item.id, visible)
            } else {
                EntrySelection.toggle(shown, item.id)
            }
        }
    }

    fun toggleAllSelection(selected: Boolean) {
        if (!selected) {
            chapterSelection.value = EntrySelection.clear()
            return
        }
        val visible = successState?.processedChapters?.map { it.id } ?: return
        chapterSelection.update { EntrySelection.selectAll(EntrySelection.retain(it, visible), visible) }
    }

    fun invertSelection() {
        val visible = successState?.processedChapters?.map { it.id } ?: return
        chapterSelection.update { EntrySelection.invert(EntrySelection.retain(it, visible), visible) }
    }

    // Read from the selection, as the state catches up with a selection only after it's been made
    private fun State.Success.selectedRows(): List<ChapterList.Item> =
        chapterSelection.value.let { held -> processedChapters.filter { it.id in held } }
    // RK <--

    // Chapters list - end

    sealed interface Dialog {
        data class ChangeCategory(
            val manga: Manga,
            val initialSelection: List<CheckboxState<Category>>,
            // RK: the group of the duplicate dialog's picks, when the add joins one.
            val joinGroup: List<Long> = emptyList(),
        ) : Dialog
        data class DeleteChapters(val chapters: List<Chapter>) : Dialog

        // RK: confirm clearing downloads, the target captured when it opened
        data class ClearDownloads(val target: ClearDownloadsTarget) : Dialog

        // RK: correct one chapter's number, the chapter read when it opened
        data class ChapterNumber(val edit: ChapterNumberEdit) : Dialog

        // RK: the adder's whole prompt in place of duplicates, so its groups, labels and grouping offer reach
        // the shared dialog as one value
        data class DuplicateManga(val manga: Manga, val prompt: DuplicatePrompt<MangaWithChapterCount, Long>) : Dialog
        data class Migrate(val target: Manga, val current: Manga) : Dialog
        data class SetFetchInterval(val manga: Manga) : Dialog
        data object SettingsSheet : Dialog
        data object TrackSheet : Dialog
        data object FullCover : Dialog

        // RK: manage the grouped sources (reorder / split / remove). Rows arrive trunk-first (primary on
        // top); isOverridden gates the reset action.
        data class ManageSources(
            val sources: List<EntryManageSourceInfo>,
            val isOverridden: Boolean,
        ) : Dialog

        // RK: confirm removing a favorited E-Hentai gallery, with an opt-in "also remove from account".
        data class EhRemoveFavorite(val manga: Manga) : Dialog

        // RK: the heart's remove asking about every grouped source, rendered by the shared host.
        data class RemoveFromLibrary(val removal: DetailsRemoval) : Dialog

        // RK: shared edit-info editor; carries the raw source manga (each field is saved only when it
        // differs from these).
        data class EditMangaInfo(val manga: Manga) : Dialog
    }

    // RK: a related-carousel candidate plus whether the library already holds that series.
    data class RelatedMangaItem(val candidate: RelatedMangaCandidate, val inLibrary: Boolean)

    fun dismissDialog() {
        dialog.value = null
    }

    fun showDeleteChapterDialog(chapters: List<Chapter>) {
        dialog.value = Dialog.DeleteChapters(chapters)
    }

    // RK -->

    fun showEditMangaInfoDialog() {
        val manga = successState?.manga ?: return
        dialog.value = Dialog.EditMangaInfo(manga)
    }

    /** Persist edits as a non-destructive per-field override against the raw source [manga]. */
    fun saveMangaInfo(manga: Manga, edited: EntryEditInfoUi) {
        viewModelScope.launchNonCancellable {
            setCustomMangaInfo.set(edited.toCustomMangaInfo(manga))
        }
        dismissDialog()
    }

    /** Clear every override, so all fields track the source again. */
    fun resetMangaInfo(manga: Manga) {
        viewModelScope.launchNonCancellable { resetEntryInfo.await(EntryId.Manga(manga.id)) }
        dismissDialog()
    }

    /** Bound trackers eligible for "Fill from tracker" (self-hosted enhanced trackers can't autofill). */
    suspend fun autofillCandidates(): List<Pair<Track, Tracker>> =
        buildTrackerAutofillCandidates(getTracksInGroup.await(mangaId), trackerManager)

    suspend fun fetchTrackerMetadata(track: Track, tracker: Tracker): TrackMangaMetadata =
        tracker.getMangaMetadata(track)

    // RK: shared source split / remove / reorder actions (the snackbar-with-undo logic both details
    // models run). showManageSourcesDialog stays here: its body genuinely diverges.
    private val mergeActions = EntryMergeActionHost(
        scope = viewModelScope,
        snackbarHostState = snackbarHostState,
        context = context,
        group = mergeGroup,
        anchorId = { mangaId },
        mergeManager = mergeManager,
        dismissDialog = ::dismissDialog,
        removal = removeMangaFromLibrary,
        offerToDeleteDownloads = ::promptDeleteDownloadsOnRemoved,
    )

    /** Switch the chapter list to a single grouped source, or null for the unified merged view. */
    fun selectSource(sourceMangaId: Long?) = mergeGroup.selectSource(sourceMangaId)

    /** Header source label: the localized unified ("All") label for the merged all-view, else the active
     *  source's display name. Resolved here (the model has the context) so MangaEntryAdapter's neutral-state
     *  mapping needs no composable. Mirrors NovelDetailsViewModel.headerSourceName. */
    fun headerSourceName(state: State.Success): String =
        if (headerNamesWholeGroup(state.mergeSources.size, state.selectedSourceMangaId)) {
            context.stringResource(MR.strings.merge_unified)
        } else {
            state.shownSource.getNameForMangaInfo()
        }

    /** The library query for the header's source, or null where the header names the whole merged group. */
    fun headerSourceQuery(state: State.Success): String? =
        if (headerNamesWholeGroup(state.mergeSources.size, state.selectedSourceMangaId)) {
            null
        } else {
            sourceKeyQuery(state.shownSource.id.toString())
        }

    fun showManageSourcesDialog() {
        val state = successState ?: return
        // Use the full group (stable) so the dialog works even while viewing a single source chip.
        if (state.mergeSources.size <= 1) return
        viewModelScope.launchIO {
            val ids = state.mergeSources.map { it.id }
            // Order the rows by the same ranking aggregation uses, so the primary source opens on top even
            // under the global order (no override). memberRanking non-empty == override on.
            val memberRanking = mergeManager.overrideRankingMemberIds(mangaId)
            val chaptersBySource = ids.associateWith {
                getMangaAndChapters.awaitChapters(it, applyScanlatorFilter = true)
            }
            val sourceIdByManga = ids.associateWith { getMangaAndChapters.awaitManga(it).source }
            val ranked = mergedChapterProvider.rankedMemberIds(chaptersBySource, sourceIdByManga, memberRanking)
            val orderedSources = ranked.mapNotNull { id ->
                state.mergeSources.find { it.id == id }
                    ?.let { EntryManageSourceInfo(it.id, it.sourceName, chaptersBySource[id]?.size ?: 0) }
            }
            dialog.value = Dialog.ManageSources(orderedSources, memberRanking.isNotEmpty())
        }
    }

    fun reorderSources(orderedIds: List<Long>) = mergeActions.reorderSources(orderedIds)

    fun resetSourceOrder() = mergeActions.resetSourceOrder()

    fun splitSources(targetIds: List<Long>) = mergeActions.splitSources(targetIds)

    fun removeSourcesFromLibrary(targetIds: List<Long>) = mergeActions.removeSourcesFromLibrary(targetIds)

    fun removeAllSourcesFromLibrary() = mergeActions.removeAllSourcesFromLibrary()
    // RK <--

    // RK --> related-mangas carousel (recommendations)
    private var relatedLoadStarted = false

    /**
     * Suspend until the initial details/chapter fetch settles. Returns at once when nothing is
     * refreshing (a library entry that needed no fetch). `fetchAllFromSource` swallows its own
     * failures, so the flag always clears and this cannot stall the carousel.
     */
    private suspend fun awaitOwnDataLoaded() {
        isRefreshingData.first { !it }
    }

    /** Load the related carousel once per screen open. Serves a fresh cache hit instantly; otherwise
     *  streams source-native related, marking which candidates are already in the library. */
    fun loadRelatedMangas() {
        if (relatedLoadStarted) return
        // Gate before any work: the carousel self-hides on an empty pool, so an early return both
        // hides the row and spares the source every request the load would have made. In-menu placement
        // still loads (the recommendations screen reads the same cache); only the inline row is hidden.
        if (!recommendationPreferences.enableRelatedMangas.get()) return
        val state = successState ?: return
        val source = state.source as? CatalogueSource ?: return
        relatedLoadStarted = true
        // An empty, incomplete entry marks the load as running, so "See all" spins only while one is.
        val cached = relatedMangaCache.get(state.manga.id)
        if (cached == null) relatedMangaCache.put(state.manga.id, RelatedPool.EMPTY, isComplete = false)
        // Bootstrap / refresh the taste cache out of band (never on the carousel's critical path);
        // the profile read below uses whatever is already cached, the pull lands for the next open.
        viewModelScope.launchIO { refreshTrackerLibrary.refreshIfStale() }
        viewModelScope.launchIO {
            // Stream switches, hide filter, ranker and taste, applied on read so a settings change is never
            // baked into the cache.
            val assembly = prepareRecommendationAssembly.await()
            if (cached != null) {
                applyRelated(cached.pool, assembly)
                if (cached.isComplete && relatedMangaCache.isFresh(cached)) return@launchIO
            } else {
                extras.update { it.copy(relatedLoading = true) }
            }
            // The entry's own details and chapters come first: both hit the same host, and a source
            // that paces its requests would otherwise spend them on suggestions while the reader is
            // still waiting for the chapter list. Flagging the load above first means the skeleton
            // holds the row's space meanwhile, so nothing shifts when the results land.
            awaitOwnDataLoaded()
            val mangaId = state.manga.id
            val pool = relatedMangasLoader.load(
                manga = state.manga.toSManga(),
                source = source,
                tracks = getTracksInGroup.await(state.manga.id),
                currentGenres = state.manga.genre.orEmpty(),
                // Each snapshot is cached (incomplete) so "See all" fills mid-load. Both puts show what the
                // cache kept, so a stale refresh never shrinks a full pool mid-stream or empties it.
                onUpdate = {
                    val kept = relatedMangaCache.put(mangaId, it, isComplete = false)
                    applyRelated(kept.pool, assembly)
                },
            )
            applyRelated(relatedMangaCache.put(mangaId, pool).pool, assembly)
            extras.update { it.copy(relatedLoading = false) }
        }
    }

    private fun applyRelated(pool: RelatedPool, assembly: RecommendationAssembly) {
        val items = assembly.assemble(pool, cap = CAROUSEL_CAP)
            .map { RelatedMangaItem(it, assembly.hideFilter.isInLibrary(it)) }
        // The count is of everything "See all" shows, which is the same assembly without the cap.
        val total = pool.candidates.count(assembly::shows)
        extras.update { it.copy(relatedItems = items, relatedTotalCount = total) }
    }

    /** See [localIdOf], which the See-all grid shares. */
    suspend fun resolveRelatedToLocalId(candidate: RelatedMangaCandidate): Long? =
        networkToLocalManga.localIdOf(candidate)
    // RK <--

    fun showSettingsDialog() {
        dialog.value = Dialog.SettingsSheet
    }

    fun showTrackDialog() {
        dialog.value = Dialog.TrackSheet
    }

    fun showCoverDialog() {
        dialog.value = Dialog.FullCover
    }

    fun showMigrateDialog(duplicate: Manga) {
        val manga = successState?.manga ?: return
        dialog.value = Dialog.Migrate(target = manga, current = duplicate)
    }

    fun setExcludedScanlators(excludedScanlators: Set<String>) {
        // RK --> written to each source on screen, applying only what the dialog changed
        val shown = successState?.excludedScanlators ?: return
        val targets = mergeGroup.state.value.viewedIds(mangaId)
        viewModelScope.launchIO {
            val current = targets.associateWith { getExcludedScanlators.await(it) }
            scanlatorWrites(targets, current, shown, excludedScanlators).forEach { (id, excluded) ->
                setExcludedScanlators.await(id, excluded)
            }
        }
        // RK <--
    }

    sealed interface State {
        @Immutable
        data object Loading : State

        @Immutable
        data class Success(
            val manga: Manga,
            val source: Source,
            val isFromSource: Boolean,
            val chapters: List<ChapterList.Item>,
            // RK: per-source manga for a merged group (key: mangaId), empty when not merged. Lets
            // chapter actions (download/delete) target each chapter's own source.
            val mergedMangaById: Map<Long, Manga> = emptyMap(),
            // RK: the grouped sources for the switcher chips, and the selected one (null = unified).
            val mergeSources: List<EntryMergeSource> = emptyList(),
            val selectedSourceMangaId: Long? = null,
            // RK: per-source metadata for the info box when a chip is active (null = unified -> primary).
            val mergeDisplayManga: Manga? = null,
            val mergeDisplaySource: Source? = null,
            // RK: the All view's installed stand-in for an anchor whose source is gone (MergedChapters.servedManga)
            val mergeServedManga: Manga? = null,
            val mergeServedSource: Source? = null,
            // RK: the copy each row's download fetches (MergedChapters.downloadTargets)
            val downloadTargets: DownloadTargets = DownloadTargets.OWN,
            // RK: whose folder Open folder opens, null hiding it and Clear downloads (downloadFolderOwner).
            val downloadFolderOwner: Manga? = null,
            // RK: the active source's raised gallery metadata (adult/metadata sources), drives the
            // namespaced tag chips + gallery-info block; null when the source has no metadata.
            val galleryMetadata: RaisedSearchMetadata? = null,
            // RK: related-mangas carousel (recommendations), loaded lazily when the screen opens.
            // relatedItems is capped to CAROUSEL_CAP; relatedTotalCount is the full filtered pool size
            // behind the "See all (N)" affordance.
            val relatedItems: List<RelatedMangaItem> = emptyList(),
            val relatedTotalCount: Int = 0,
            val relatedLoading: Boolean = false,
            val availableScanlators: Set<String>,
            val excludedScanlators: Set<String>,
            val trackingCount: Int = 0,
            val hasLoggedInTrackers: Boolean = false,
            val isRefreshingData: Boolean = false,
            val dialog: Dialog? = null,
            // RK: hasPromptedToAddBefore moved to AddToLibraryOffer, shared with novels
            val hideMissingChapters: Boolean = false,
            // RK: hide/unhide chapters. showHidden is the transient reveal toggle; hiddenChapterIds are
            // the currently-shown hidden rows (for dimming), only populated while showing hidden.
            val showHidden: Boolean = false,
            val hasHiddenChapters: Boolean = false,
            val hiddenChapterIds: Set<Long> = emptySet(),
            // RK: the numbers the missing-chapter markers count against, taken before hidden rows and the
            // filters drop any, so hiding a chapter never makes a gap.
            val gapPresent: ChapterGap.Present = ChapterGap.Present.NONE,
            // RK: the chapters whose number is out of line with their own source's list, by chapter id.
            val numberHints: Map<Long, ChapterNumberHint.Hint> = emptyMap(),
            // RK: where Resume opens, hidden chapters last (getNextUnread); null when everything is read.
            val resumeChapter: Chapter? = null,
            // RK: the manga's custom-info overlay (null = none), applied at the display layer via
            // Manga.withCustomInfo. Never folded into the raw `manga` field above, which stays
            // source-accurate for tracker search, refresh, duplicate detection, downloads, etc.
            val customInfo: CustomMangaInfo? = null,
            // RK: cover-derived tint, null until extracted. Always extracted, since edit info tints from it;
            // the screen applies it only when cover theming is on.
            val seedColor: Color? = null,
            // RK: page-preview thumbnails (adult sources) + how many rows to show (0 = off).
            val pagePreviewsState: PagePreviewState = PagePreviewState.Unused,
            val previewsRowCount: Int = 0,
            // RK: the shown member's web page (ShownWebPage), null hiding WebView, Share and Copy link
            val webPage: EntryWebPage? = null,
        ) : State {
            // RK -->
            // EH/EXH galleries are tags-as-content with no description, so default the info box
            // to expanded in the library too (Mihon only auto-expands when arriving from a source).
            val isMetadataSource: Boolean
                get() = source.getMainSource<MetadataSource<*, *>>() != null

            // Whose queued downloads the rows follow. A merged series lists chapters of every source
            // it shows, so matching [manga] alone left a sibling source's row on a stale mark.
            fun showsChaptersOf(mangaId: Long) = mangaId == manga.id || mangaId in mergedMangaById

            // The member the page shows, and its source: the selected chip's, else the anchor's. Writes
            // (favourite, tracking, migrate) stay on [manga].
            val shownManga: Manga get() = mergeDisplayManga ?: manga
            val shownSource: Source get() = mergeDisplaySource ?: source

            // What downloads and opens the web page go through, and whose interval the page shows and sets:
            // the shown member, except that the All view serves an installed one for a missing anchor.
            val servingSource: Source get() = mergeDisplaySource ?: mergeServedSource ?: source
            val intervalManga: Manga get() = mergeServedManga ?: manga
            // RK <--

            val processedChapters by lazy {
                chapters.applyFilters(manga).toList()
            }

            val chapterListItems by lazy {
                if (hideMissingChapters) {
                    return@lazy processedChapters
                }

                // RK -->
                // The shared rule places the marker for novels too, and declines a gap whose two
                // sides come from different sources of a group or whose number the name does not
                // support.
                ChapterGap.withMarkers(
                    processedChapters,
                    neighbourOf = { it.gapNeighbour },
                    isHidden = { it.id in hiddenChapterIds },
                    present = gapPresent,
                    descending = manga.sortDescending(),
                    row = { it },
                ) { before, after, missingCount ->
                    ChapterList.MissingCount(
                        id = "${before?.id}-${after?.id}",
                        count = missingCount,
                    )
                }
                // RK <--
            }

            // RK: the header's count, which covers what the markers would and stays when the pref hides them
            val missingChapterCount by lazy {
                ChapterGap.total(
                    processedChapters,
                    { it.gapNeighbour },
                    { it.id in hiddenChapterIds },
                    gapPresent,
                    manga.sortDescending(),
                )
            }

            val scanlatorFilterActive: Boolean
                get() = excludedScanlators.intersect(availableScanlators).isNotEmpty()

            val filterActive: Boolean
                get() = scanlatorFilterActive || manga.chaptersFiltered()

            // RK: upstream's private copy of the top-level applyFilters is deleted, so the list runs the
            //     one copy MergedChapterFilterConformanceTest pins rather than a twin that could drift.
        }
    }
}

@Immutable
sealed class ChapterList {
    @Immutable
    data class MissingCount(
        val id: String,
        val count: Int,
    ) : ChapterList()

    @Immutable
    data class Item(
        val chapter: Chapter,
        val downloadState: Download.State,
        val downloadProgress: Int,
        val selected: Boolean = false,
        // RK: read and bookmarked as the user sees them, the merge group's answer (GroupMarks), so a
        // chapter read on any source reads as read here, matching the library's unread count. Kept apart
        // from chapter.read, which stays the row's own DB truth because tracker sync, delete-after-read
        // and mark-unread act on the real row.
        val isRead: Boolean,
        val isBookmarked: Boolean, // RK
        // RK: read from the name once, when the row is built, since a download's progress copies the row
        // many times a second and the missing-chapter markers ask every row on each copy.
        val gapNeighbour: ChapterGap.Neighbour = chapter.toGapNeighbour(),
    ) : ChapterList() {
        val id = chapter.id
        val isDownloaded = downloadState == Download.State.DOWNLOADED
    }
}

// RK: page-preview thumbnail state for the details screen (adult/EXH sources).
sealed interface PagePreviewState {
    data object Unused : PagePreviewState
    data object Loading : PagePreviewState
    data class Success(val pagePreviews: List<PagePreview>) : PagePreviewState
    data class Error(val error: Throwable) : PagePreviewState
}

// RK: the per-field overrides, by the rule edit info shares with novels.
private fun EntryEditInfoUi.toCustomMangaInfo(source: Manga) =
    overridesOver(source.toEntryEditInfoUi(), SManga.UNKNOWN.toLong()).let {
        CustomMangaInfo(
            mangaId = source.id,
            title = it.title,
            author = it.author,
            artist = it.artist,
            description = it.description,
            genre = it.genre,
            status = it.status,
            thumbnailUrl = it.thumbnailUrl,
        )
    }
