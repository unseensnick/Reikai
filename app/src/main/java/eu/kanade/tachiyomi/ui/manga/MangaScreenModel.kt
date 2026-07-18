package eu.kanade.tachiyomi.ui.manga

import android.content.Context
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.util.fastAny
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.palette.graphics.Palette
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import eu.kanade.core.preference.asState
import eu.kanade.core.util.addOrRemove
import eu.kanade.core.util.insertSeparators
import eu.kanade.domain.chapter.interactor.GetAvailableScanlators
import eu.kanade.domain.chapter.interactor.SetReadStatus
import eu.kanade.domain.manga.interactor.GetExcludedScanlators
import eu.kanade.domain.manga.interactor.GetPagePreviews
import eu.kanade.domain.manga.interactor.SetExcludedScanlators
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.manga.model.PagePreview
import eu.kanade.domain.manga.model.chaptersFiltered
import eu.kanade.domain.manga.model.downloadedFilter
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.domain.track.interactor.AddTracks
import eu.kanade.domain.track.interactor.RefreshTracks
import eu.kanade.domain.track.interactor.TrackChapter
import eu.kanade.domain.track.model.AutoTrackState
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.manga.DownloadAction
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.presentation.util.formattedMessage
import eu.kanade.tachiyomi.data.coil.getBestColor
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.track.EnhancedTracker
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.model.TrackMangaMetadata
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.PagePreviewSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.MetadataSource
import eu.kanade.tachiyomi.source.online.all.EHentai
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.chapter.getNextUnread
import eu.kanade.tachiyomi.util.removeCovers
import eu.kanade.tachiyomi.util.system.getBitmapOrNull
import eu.kanade.tachiyomi.util.system.toast
import exh.metadata.metadata.EHentaiSearchMetadata
import exh.metadata.metadata.RaisedSearchMetadata
import exh.metadata.metadata.base.FlatMetadata
import exh.source.ExhPreferences
import exh.source.getMainSource
import exh.source.isEhBasedManga
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.domain.chapter.interactor.FilterChaptersForDownload
import mihon.domain.manga.model.toDomainManga
import mihon.domain.source.interactor.UpdateMangaFromRemote
import reikai.domain.manga.MangaMergeManager
import reikai.domain.manga.MangaPreferences
import reikai.domain.manga.MergedChapterProvider
import reikai.domain.recommendation.BuildRecommendationHideFilter
import reikai.domain.recommendation.RECOMMENDS_SOURCE
import reikai.domain.recommendation.RecommendationHideFilter
import reikai.domain.recommendation.ReikaiRecommendationPreferences
import reikai.domain.recommendation.RelatedMangaCache
import reikai.domain.recommendation.RelatedMangaCandidate
import reikai.domain.recommendation.RelatedMangasLoader
import reikai.domain.recommendation.taste.GetTasteProfile
import reikai.domain.recommendation.taste.RefreshTrackerLibrary
import reikai.domain.recommendation.taste.TasteProfile
import reikai.presentation.browse.MangaLibraryAdder
import reikai.presentation.details.EntryEditInfoUi
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.preference.mapAsCheckboxState
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.interactor.SetMangaDefaultChapterFlags
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.model.NoChaptersException
import tachiyomi.domain.chapter.service.calculateChapterGap
import tachiyomi.domain.chapter.service.getChapterSort
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetCustomMangaInfo
import tachiyomi.domain.manga.interactor.GetDuplicateLibraryManga
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.GetFlatMetadataById
import tachiyomi.domain.manga.interactor.GetMangaWithChapters
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.interactor.SetCustomMangaInfo
import tachiyomi.domain.manga.interactor.SetMangaChapterFlags
import tachiyomi.domain.manga.model.CustomMangaInfo
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaWithChapterCount
import tachiyomi.domain.manga.model.applyFilter
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.model.Track
import tachiyomi.i18n.MR
import tachiyomi.source.local.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import kotlin.math.floor

// RK: max related candidates shown in the details carousel; the full pool is kept in the cache for
// the "See all" browse grid.
private const val CAROUSEL_CAP = 30

class MangaScreenModel(
    private val context: Context,
    private val lifecycle: Lifecycle,
    private val mangaId: Long,
    private val isFromSource: Boolean,
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
    trackPreferences: TrackPreferences = Injekt.get(),
    readerPreferences: ReaderPreferences = Injekt.get(),
    private val trackerManager: TrackerManager = Injekt.get(),
    private val trackChapter: TrackChapter = Injekt.get(),
    private val downloadManager: DownloadManager = Injekt.get(),
    private val downloadCache: DownloadCache = Injekt.get(),
    private val getMangaAndChapters: GetMangaWithChapters = Injekt.get(),
    private val getDuplicateLibraryManga: GetDuplicateLibraryManga = Injekt.get(),
    private val getAvailableScanlators: GetAvailableScanlators = Injekt.get(),
    private val getExcludedScanlators: GetExcludedScanlators = Injekt.get(),
    private val setExcludedScanlators: SetExcludedScanlators = Injekt.get(),
    private val setMangaChapterFlags: SetMangaChapterFlags = Injekt.get(),
    private val setMangaDefaultChapterFlags: SetMangaDefaultChapterFlags = Injekt.get(),
    private val setReadStatus: SetReadStatus = Injekt.get(),
    private val updateChapter: UpdateChapter = Injekt.get(),
    private val updateManga: UpdateManga = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),
    private val getTracks: GetTracks = Injekt.get(),
    private val addTracks: AddTracks = Injekt.get(),
    private val setMangaCategories: SetMangaCategories = Injekt.get(),
    private val mangaRepository: MangaRepository = Injekt.get(),
    private val filterChaptersForDownload: FilterChaptersForDownload = Injekt.get(),
    private val updateMangaFromRemote: UpdateMangaFromRemote = Injekt.get(),
    // RK -->
    private val mergeManager: MangaMergeManager = Injekt.get(),
    private val mangaLibraryAdder: MangaLibraryAdder = Injekt.get(),
    private val mergedChapterProvider: MergedChapterProvider = Injekt.get(),
    private val mangaPreferences: MangaPreferences = Injekt.get(),
    private val relatedMangasLoader: RelatedMangasLoader = Injekt.get(),
    private val recommendationPreferences: ReikaiRecommendationPreferences = Injekt.get(),
    private val relatedMangaCache: RelatedMangaCache = Injekt.get(),
    private val getTasteProfile: GetTasteProfile = Injekt.get(),
    private val refreshTrackerLibrary: RefreshTrackerLibrary = Injekt.get(),
    private val buildRecommendationHideFilter: BuildRecommendationHideFilter = Injekt.get(),
    private val getFavorites: GetFavorites = Injekt.get(),
    private val networkToLocalManga: NetworkToLocalManga = Injekt.get(),
    private val uiPreferences: UiPreferences = Injekt.get(),
    private val getFlatMetadataById: GetFlatMetadataById = Injekt.get(),
    private val getPagePreviews: GetPagePreviews = Injekt.get(),
    // RK: manga custom-info overlay. getCustomMangaInfo drives the non-destructive display overlay;
    // setCustomMangaInfo persists edits from the shared edit-info dialog.
    private val getCustomMangaInfo: GetCustomMangaInfo = Injekt.get(),
    private val setCustomMangaInfo: SetCustomMangaInfo = Injekt.get(),
    // RK <--
    val snackbarHostState: SnackbarHostState = SnackbarHostState(),
) : StateScreenModel<MangaScreenModel.State>(State.Loading) {

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

    val chapterSwipeStartAction = libraryPreferences.swipeToEndAction.get()
    val chapterSwipeEndAction = libraryPreferences.swipeToStartAction.get()
    var autoTrackState = trackPreferences.autoUpdateTrackOnMarkRead.get()

    private val skipFiltered by readerPreferences.skipFiltered.asState(screenModelScope)

    val isUpdateIntervalEnabled =
        LibraryPreferences.MANGA_OUTSIDE_RELEASE_PERIOD in libraryPreferences.autoUpdateMangaRestrictions.get()

    private val selectedPositions: Array<Int> = arrayOf(-1, -1) // first and last selected index in list
    private val selectedChapterIds: HashSet<Long> = HashSet()

    // RK --> merge group ids for this manga (just its own id when not grouped). Drives the combined
    // chapter list; updated on open (same-title + manual merges, healed) and after a split.
    private val relatedMangaIds = MutableStateFlow(longArrayOf(mangaId))

    // The grouped source the user is viewing via the chips, or null for the unified merged list.
    private val selectedSourceMangaId = MutableStateFlow<Long?>(null)

    // Hide/unhide chapters (twin of the novel mechanism). The pref is the persisted/backed-up set of
    // hidden chapter keys; showHiddenFlow is the transient "temporarily reveal hidden chapters" toggle.
    private val hiddenChaptersPref = mangaPreferences.hiddenChapters()
    private val showHiddenFlow = MutableStateFlow(false)
    // RK <--

    /**
     * Helper function to update the UI state only if it's currently in success state
     */
    private inline fun updateSuccessState(func: (State.Success) -> State.Success) {
        mutableState.update {
            when (it) {
                State.Loading -> it
                is State.Success -> func(it)
            }
        }
    }

    // RK --> cover-based theming (Y11)
    val themeCoverBased = uiPreferences.themeCoverBased.get()

    /**
     * Seed the details theme from the cover's vibrant color. Reuses the color a prior Library/Browse
     * load already cached; otherwise loads the cover through Coil and extracts it, so a non-library
     * manga opened straight from browsing still tints on first open (mirrors Komikku setPaletteColor).
     */
    fun updateSeedColor() {
        // Computed regardless of the themeCoverBased pref: the page only applies it when the pref is on
        // (MangaScreen), but the shared edit-info dialog always tints from the cover, so the seed must be
        // available either way.
        val cover = manga?.asMangaCover() ?: return
        cover.vibrantCoverColor?.let { color ->
            updateSuccessState { it.copy(seedColor = Color(color)) }
            return
        }
        screenModelScope.launchIO {
            val request = ImageRequest.Builder(context)
                .data(cover)
                .allowHardware(false) // Palette can't read hardware bitmaps
                .build()
            val bitmap = context.imageLoader.execute(request).image
                ?.asDrawable(context.resources)
                ?.getBitmapOrNull() ?: return@launchIO
            val color = Palette.from(bitmap).generate().getBestColor() ?: return@launchIO
            cover.vibrantCoverColor = color
            updateSuccessState { it.copy(seedColor = Color(color)) }
        }
    }
    // RK <--

    init {
        screenModelScope.launchIO {
            // RK --> when the manga is part of a merge group, the chapter list is the aggregated
            // union of every grouped source; otherwise it stays the single-source list.
            combine(
                combine(
                    getMangaAndChapters.subscribe(mangaId, applyScanlatorFilter = true).distinctUntilChanged(),
                    relatedMangaIds,
                    selectedSourceMangaId,
                    downloadCache.changes,
                    downloadManager.queueState,
                ) { mangaAndChapters, relatedIds, selectedSource, _, _ ->
                    ChapterInputs(mangaAndChapters.first, mangaAndChapters.second, relatedIds, selectedSource)
                },
                // Re-emit so a hide/unhide or the show-hidden toggle rebuilds the chapter list.
                hiddenChaptersPref.changes(),
                showHiddenFlow,
            ) { inputs, _, _ -> inputs }
                .flatMapLatest { (manga, ownChapters, relatedIds, selectedSource) ->
                    when {
                        selectedSource != null && relatedIds.size > 1 ->
                            singleSourceChaptersFlow(manga, selectedSource)
                        relatedIds.size <= 1 ->
                            flowOf(MergedChapters(manga, ownChapters, emptyMap()))
                        else ->
                            mergedChaptersFlow(manga, relatedIds)
                    }
                }
                .flowWithLifecycle(lifecycle)
                .collectLatest { mc ->
                    val items = mc.chapters.toChapterListItems(mc.manga, mc.mangaBySource)
                    val hidden = applyHiddenChapters(items, mc.manga, mc.mangaBySource)
                    updateSuccessState {
                        it.copy(
                            manga = mc.manga,
                            chapters = hidden.chapters,
                            showHidden = hidden.showHidden,
                            hasHiddenChapters = hidden.hasHiddenChapters,
                            hiddenChapterIds = hidden.hiddenChapterIds,
                            mergedMangaById = mc.mangaBySource,
                            mergeDisplayManga = mc.displayManga,
                            mergeDisplaySource = mc.displaySource,
                        )
                    }
                }
            // RK <--
        }

        screenModelScope.launchIO {
            getExcludedScanlators.subscribe(mangaId)
                .flowWithLifecycle(lifecycle)
                .distinctUntilChanged()
                .collectLatest { excludedScanlators ->
                    updateSuccessState {
                        it.copy(excludedScanlators = excludedScanlators)
                    }
                }
        }

        screenModelScope.launchIO {
            getAvailableScanlators.subscribe(mangaId)
                .flowWithLifecycle(lifecycle)
                .distinctUntilChanged()
                .collectLatest { availableScanlators ->
                    updateSuccessState {
                        it.copy(availableScanlators = availableScanlators)
                    }
                }
        }

        // RK: mirror the manga's custom-info overlay into state; a save re-emits and the display layer
        // re-applies it via Manga.withCustomInfo (the raw `manga` field stays source-accurate).
        screenModelScope.launchIO {
            getCustomMangaInfo.subscribe(mangaId)
                .flowWithLifecycle(lifecycle)
                .distinctUntilChanged()
                .collectLatest { customInfo ->
                    updateSuccessState { it.copy(customInfo = customInfo) }
                }
        }

        observeDownloads()

        // RK --> keep the source-switcher chip list + selection mirrored into state. The eager
        // load below seeds the initial chips into State.Success; this handles later changes (splits).
        screenModelScope.launchIO {
            relatedMangaIds.collectLatest { ids ->
                val chips = buildMergeSources(ids)
                updateSuccessState { it.copy(mergeSources = chips) }
            }
        }
        screenModelScope.launchIO {
            selectedSourceMangaId.collectLatest { selected ->
                updateSuccessState { it.copy(selectedSourceMangaId = selected) }
            }
        }
        // Reactively load the active source's gallery metadata (primary when unified) so the tag
        // chips + info box stay in sync. Crucial on a first open: the source fetch stores the
        // metadata AFTER State.Success is built, and this upgrades the flat view to the rich one
        // without needing to back out and re-enter. Also refreshes on a source-chip switch or when
        // a gallery-update rewrites the metadata.
        screenModelScope.launchIO {
            selectedSourceMangaId
                .flatMapLatest { selected ->
                    val targetId = selected ?: mangaId
                    getFlatMetadataById.subscribe(targetId).map { flat -> targetId to flat }
                }
                .flowWithLifecycle(lifecycle)
                .collectLatest { (targetId, flat) ->
                    updateSuccessState { it.copy(galleryMetadata = raiseMetadata(flat, targetId)) }
                }
        }
        // RK <--

        screenModelScope.launchIO {
            val manga = getMangaAndChapters.awaitManga(mangaId)
            // RK --> resolve the merge group so the combined chapter list builds on open
            val related = mergeManager.computeRelatedMangaIds(mangaId)
            relatedMangaIds.value = related
            val mergeChips = buildMergeSources(related)
            // RK <--
            val chapterItems = getMangaAndChapters.awaitChapters(mangaId, applyScanlatorFilter = true)
                .toChapterListItems(manga)
            // RK: seed the hidden-chapters filter on first render so hidden chapters never flash in.
            val hidden = applyHiddenChapters(chapterItems, manga, emptyMap())

            if (!manga.favorite) {
                setMangaDefaultChapterFlags.await(manga)
            }

            val needRefreshInfo = !manga.initialized
            val needRefreshChapter = chapterItems.isEmpty()

            // Show what we have earlier
            // RK: seed the primary source's gallery metadata too; same first-render race as the chips.
            val galleryMetadata = loadGalleryMetadata(mangaId)
            val source = Injekt.get<SourceManager>().getOrStub(manga.source)
            // RK: kick off the page-preview fetch for supporting sources before building state.
            val supportsPagePreview = source.getMainSource<PagePreviewSource>() != null
            if (supportsPagePreview) {
                getPagePreviews(manga, source)
            }
            mutableState.update {
                State.Success(
                    manga = manga,
                    source = source,
                    isFromSource = isFromSource,
                    chapters = hidden.chapters,
                    // RK: hide/unhide chapters seed
                    showHidden = hidden.showHidden,
                    hasHiddenChapters = hidden.hasHiddenChapters,
                    hiddenChapterIds = hidden.hiddenChapterIds,
                    availableScanlators = getAvailableScanlators.await(mangaId),
                    excludedScanlators = getExcludedScanlators.await(mangaId),
                    isRefreshingData = needRefreshInfo || needRefreshChapter,
                    dialog = null,
                    hideMissingChapters = libraryPreferences.hideMissingChapters.get(),
                    // RK: seed the merge chips so they show on first render (avoids a race where the
                    // chip collector fired before State.Success existed)
                    mergeSources = mergeChips,
                    galleryMetadata = galleryMetadata,
                    // RK: page-preview thumbnails + row count (0 = off) for adult sources.
                    pagePreviewsState = if (supportsPagePreview) {
                        PagePreviewState.Loading
                    } else {
                        PagePreviewState.Unused
                    },
                    previewsRowCount = uiPreferences.previewsRowCount.get(),
                    // RK: seed the custom-info overlay so it shows on first render (before the reactive
                    // collector fires), same pattern as the scanlator seeds above.
                    customInfo = getCustomMangaInfo.subscribe(mangaId).first(),
                )
            }

            // Start observe tracking since it only needs mangaId
            observeTrackers()

            // Fetch info-chapters when needed
            if ((needRefreshInfo || needRefreshChapter) && screenModelScope.isActive) {
                fetchAllFromSource(
                    manualFetch = false,
                    fetchDetails = needRefreshInfo,
                    fetchChapters = needRefreshChapter,
                )
            }

            // Initial loading finished
            updateSuccessState { it.copy(isRefreshingData = false) }
        }
    }

    // RK --> load the first page of gallery page previews for sources that support it.
    private fun getPagePreviews(manga: Manga, source: Source) {
        screenModelScope.launchIO {
            when (val result = getPagePreviews.await(manga, source, 1)) {
                is GetPagePreviews.Result.Error -> updateSuccessState {
                    it.copy(pagePreviewsState = PagePreviewState.Error(result.error))
                }
                is GetPagePreviews.Result.Success -> updateSuccessState {
                    it.copy(pagePreviewsState = PagePreviewState.Success(result.pagePreviews))
                }
                GetPagePreviews.Result.Unused -> updateSuccessState {
                    it.copy(pagePreviewsState = PagePreviewState.Unused)
                }
            }
        }
    }
    // RK <--

    fun fetchAllFromSource(manualFetch: Boolean = true) {
        screenModelScope.launch {
            updateSuccessState { it.copy(isRefreshingData = true) }
            fetchAllFromSource(
                manualFetch = manualFetch,
                fetchDetails = true,
                fetchChapters = true,
            )
            updateSuccessState { it.copy(isRefreshingData = false) }
        }
    }

    private suspend fun fetchAllFromSource(
        manualFetch: Boolean,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ) {
        val state = successState ?: return
        // RK: refresh every source in a merged group, not just the primary. A source merged in via
        //     long-press "add from another source" never fetched at add time, so without this its
        //     chip stays stale on refresh; each member goes through its own source's fetch (the same
        //     path Browse uses), populating details, chapters and gallery metadata. Just the primary
        //     when not merged, so non-grouped entries behave exactly as before.
        val groupIds = relatedMangaIds.value
        try {
            withUIContext {
                val newChapters = mutableListOf<Chapter>()
                var firstError: Exception? = null
                for (id in groupIds) {
                    val result = if (id == state.manga.id) {
                        updateMangaFromRemote(
                            source = state.source,
                            manga = state.manga,
                            fetchDetails = fetchDetails,
                            fetchChapters = fetchChapters,
                            manualFetch = manualFetch,
                        )
                    } else {
                        updateMangaFromRemote(
                            manga = getMangaAndChapters.awaitManga(id),
                            fetchDetails = fetchDetails,
                            fetchChapters = fetchChapters,
                            manualFetch = manualFetch,
                        )
                    }
                    result.fold(
                        onSuccess = { newChapters += it.newChapters },
                        onFailure = { if (firstError == null && it is Exception) firstError = it },
                    )
                }

                if (manualFetch) {
                    downloadNewChapters(newChapters)
                }
                firstError?.let { throw it }
            }
        } catch (_: CancellationException) {
            // ignore
        } catch (e: Exception) {
            val message = if (e is NoChaptersException) {
                context.stringResource(MR.strings.no_chapters_error)
            } else {
                logcat(LogPriority.ERROR, e)
                with(context) { e.formattedMessage }
            }

            screenModelScope.launch {
                snackbarHostState.showSnackbar(message = message)
            }
        }
    }

    // Manga info - start

    fun toggleFavorite() {
        // RK: removing a favorited E-Hentai gallery with backup enabled goes through a confirm
        //     dialog (DeletableTracker-style), so the user can opt to also remove it from the account.
        val manga = successState?.manga
        if (manga != null && isFavorited && shouldConfirmEhRemoveFromAccount(manga)) {
            updateSuccessState { it.copy(dialog = Dialog.EhRemoveFavorite(manga)) }
            return
        }
        toggleFavorite(onRemoved = ::promptDeleteDownloadsOnRemoved)
    }

    // RK: extracted so the E-Hentai "remove from account" confirm can reuse the same downloads prompt.
    private fun promptDeleteDownloadsOnRemoved() {
        screenModelScope.launch {
            if (!hasDownloads()) return@launch
            val result = snackbarHostState.showSnackbar(
                message = context.stringResource(MR.strings.delete_downloads_for_manga),
                actionLabel = context.stringResource(MR.strings.action_delete),
                withDismissAction = true,
            )
            if (result == SnackbarResult.ActionPerformed) {
                deleteDownloads()
            }
        }
    }

    /**
     * Update favorite status of manga, (removes / adds) manga (to / from) library.
     */
    fun toggleFavorite(
        onRemoved: () -> Unit,
        checkDuplicate: Boolean = true,
    ) {
        val state = successState ?: return
        screenModelScope.launchIO {
            val manga = state.manga

            if (isFavorited) {
                // Remove from library
                if (updateManga.awaitUpdateFavorite(manga.id, false)) {
                    // Remove covers and update last modified in db
                    if (manga.removeCovers() != manga) {
                        updateManga.awaitUpdateCoverLastModified(manga.id)
                    }
                    withUIContext { onRemoved() }
                }
            } else {
                // Add to library
                // First, check if duplicate exists if callback is provided
                if (checkDuplicate) {
                    val duplicates = getDuplicateLibraryManga(manga)

                    if (duplicates.isNotEmpty()) {
                        val groupIdByMangaId = mergeManager.groupIdsFor(duplicates.map { it.manga.id })
                        updateSuccessState {
                            it.copy(
                                dialog = Dialog.DuplicateManga(
                                    manga,
                                    duplicates,
                                    mergeManager.suggestGroupingOnAdd,
                                    groupIdByMangaId,
                                ),
                            )
                        }
                        return@launchIO
                    }
                }

                // Now check if user previously set categories, when available
                val categories = getCategories()
                val defaultCategoryId = libraryPreferences.defaultCategory.get().toLong()
                val defaultCategory = categories.find { it.id == defaultCategoryId }
                when {
                    // Default category set
                    defaultCategory != null -> {
                        val result = updateManga.awaitUpdateFavorite(manga.id, true)
                        if (!result) return@launchIO
                        moveMangaToCategory(defaultCategory)
                    }

                    // Automatic 'Default' or no categories
                    defaultCategoryId == 0L || categories.isEmpty() -> {
                        val result = updateManga.awaitUpdateFavorite(manga.id, true)
                        if (!result) return@launchIO
                        moveMangaToCategory(null)
                    }

                    // Choose a category
                    else -> showChangeCategoryDialog()
                }

                // Finally match with enhanced tracking when available
                addTracks.bindEnhancedTrackers(manga, state.source)
                // RK: back up newly-favorited E-Hentai galleries to the account.
                maybeBackupFavoriteToAccount(manga)
            }
        }
    }

    // RK: add-time grouping. Merge the manga with the duplicates the user picked, then file it. Only the
    // picks: the duplicate list is fuzzy, so merging every match would fuse distinct series. Seeding first
    // is what makes a deferred category choice open on the group's own categories.
    //
    // Favorites up front (like the novel side), before the possible category choice, so an abandoned
    // choice can't strand the just-merged member. Membership isn't favorite-filtered, so a
    // merged-but-unfavorited copy would feed chapters into the group while staying invisible in the
    // library. The category step below is then non-gating: the favorite has already landed.
    fun addToExistingGroup(selectedIds: List<Long>) {
        val state = successState ?: return
        val manga = state.manga
        screenModelScope.launchIO {
            if (!updateManga.awaitUpdateFavorite(manga.id, true)) return@launchIO
            mergeManager.mergeManga(listOf(manga.id) + selectedIds)
            mangaLibraryAdder.seedCategoriesFromGroup(manga.id, selectedIds)
            addTracks.bindEnhancedTrackers(manga, state.source)
            maybeBackupFavoriteToAccount(manga)

            val categories = getCategories()
            val defaultCategoryId = libraryPreferences.defaultCategory.get().toLong()
            val defaultCategory = categories.find { it.id == defaultCategoryId }
            when {
                defaultCategory != null -> moveMangaToCategory(defaultCategory)
                defaultCategoryId == 0L || categories.isEmpty() -> moveMangaToCategory(null)
                else -> showChangeCategoryDialog()
            }
        }
    }

    // RK -->
    private val exhPreferences: ExhPreferences by injectLazy()

    private fun shouldConfirmEhRemoveFromAccount(manga: Manga): Boolean {
        return manga.isEhBasedManga() &&
            exhPreferences.enableExhentai().get() &&
            exhPreferences.exhBackupFavoritesToAccount().get()
    }

    fun confirmEhRemoveFromLibrary(removeFromAccount: Boolean) {
        val manga = successState?.manga
        dismissDialog()
        if (manga == null) return
        toggleFavorite(onRemoved = ::promptDeleteDownloadsOnRemoved)
        if (removeFromAccount) {
            screenModelScope.launchIO { removeFromEhAccount(manga) }
        }
    }

    private suspend fun removeFromEhAccount(manga: Manga) {
        val source = Injekt.get<SourceManager>().get(manga.source) as? EHentai ?: return
        runCatching {
            source.removeFavorites(listOf(EHentaiSearchMetadata.galleryId(manga.url)))
        }.onFailure { logcat(LogPriority.ERROR, it) { "Failed to remove E-Hentai favorite remotely" } }
    }

    // NOTE: if the user favorites via the category picker and then cancels it, this still pushes
    //       (the picker commits the favorite later in moveMangaToCategoriesAndAddToLibrary).
    //       Benign: the account is the disposable backstop, so a stray entry is the safe direction.
    private suspend fun maybeBackupFavoriteToAccount(manga: Manga) {
        if (!manga.isEhBasedManga() ||
            !exhPreferences.enableExhentai().get() ||
            !exhPreferences.exhBackupFavoritesToAccount().get()
        ) {
            return
        }
        val source = Injekt.get<SourceManager>().get(manga.source) as? EHentai ?: return
        runCatching {
            source.addFavorite(
                EHentaiSearchMetadata.galleryId(manga.url),
                EHentaiSearchMetadata.galleryToken(manga.url),
                exhPreferences.exhFavoritesBackupSlot().get(),
            )
        }.onFailure { logcat(LogPriority.ERROR, it) { "Failed to back up E-Hentai favorite to account" } }
    }
    // RK <--

    fun showChangeCategoryDialog() {
        val manga = successState?.manga ?: return
        screenModelScope.launch {
            val categories = getCategories()
            val selection = getMangaCategoryIds(manga)
            updateSuccessState { successState ->
                successState.copy(
                    dialog = Dialog.ChangeCategory(
                        manga = manga,
                        initialSelection = categories.mapAsCheckboxState { it.id in selection },
                    ),
                )
            }
        }
    }

    fun showSetFetchIntervalDialog() {
        val manga = successState?.manga ?: return
        updateSuccessState {
            it.copy(dialog = Dialog.SetFetchInterval(manga))
        }
    }

    fun setFetchInterval(manga: Manga, interval: Int) {
        screenModelScope.launchIO {
            if (
                updateManga.awaitUpdateFetchInterval(
                    // Custom intervals are negative
                    manga.copy(fetchInterval = -interval),
                )
            ) {
                val updatedManga = mangaRepository.getMangaById(manga.id)
                updateSuccessState { it.copy(manga = updatedManga) }
            }
        }
    }

    /**
     * Returns true if the manga has any downloads.
     */
    private fun hasDownloads(): Boolean {
        val manga = successState?.manga ?: return false
        return downloadManager.getDownloadCount(manga) > 0
    }

    /**
     * Deletes all the downloads for the manga.
     */
    private fun deleteDownloads() {
        val state = successState ?: return
        downloadManager.deleteManga(state.manga, state.source)
    }

    /**
     * Get user categories.
     *
     * @return List of categories, not including the default category
     */
    suspend fun getCategories(): List<Category> {
        return getCategories.await().filterNot { it.isSystemCategory }
    }

    /**
     * Gets the category id's the manga is in, if the manga is not in a category, returns the default id.
     *
     * @param manga the manga to get categories from.
     * @return Array of category ids the manga is in, if none returns default id
     */
    private suspend fun getMangaCategoryIds(manga: Manga): List<Long> {
        return getCategories.await(manga.id)
            .map { it.id }
    }

    fun moveMangaToCategoriesAndAddToLibrary(manga: Manga, categories: List<Long>) {
        moveMangaToCategory(categories)
        if (manga.favorite) return

        screenModelScope.launchIO {
            updateManga.awaitUpdateFavorite(manga.id, true)
        }
    }

    /**
     * Move the given manga to categories.
     *
     * @param categories the selected categories.
     */
    private fun moveMangaToCategories(categories: List<Category>) {
        val categoryIds = categories.map { it.id }
        moveMangaToCategory(categoryIds)
    }

    private fun moveMangaToCategory(categoryIds: List<Long>) {
        screenModelScope.launchIO {
            setMangaCategories.await(mangaId, categoryIds)
        }
    }

    /**
     * Move the given manga to the category.
     *
     * @param category the selected category, or null for default category.
     */
    private fun moveMangaToCategory(category: Category?) {
        moveMangaToCategories(listOfNotNull(category))
    }

    // Manga info - end

    // Chapters list - start

    private fun observeDownloads() {
        screenModelScope.launchIO {
            downloadManager.statusFlow()
                .filter { it.manga.id == successState?.manga?.id }
                .catch { error -> logcat(LogPriority.ERROR, error) }
                .flowWithLifecycle(lifecycle)
                .collect {
                    withUIContext {
                        updateDownloadState(it)
                    }
                }
        }

        screenModelScope.launchIO {
            downloadManager.progressFlow()
                .filter { it.manga.id == successState?.manga?.id }
                .catch { error -> logcat(LogPriority.ERROR, error) }
                .flowWithLifecycle(lifecycle)
                .collect {
                    withUIContext {
                        updateDownloadState(it)
                    }
                }
        }
    }

    private fun updateDownloadState(download: Download) {
        updateSuccessState { successState ->
            val modifiedIndex = successState.chapters.indexOfFirst { it.id == download.chapter.id }
            if (modifiedIndex < 0) return@updateSuccessState successState

            val newChapters = successState.chapters.toMutableList().apply {
                val item = removeAt(modifiedIndex)
                    .copy(downloadState = download.status, downloadProgress = download.progress)
                add(modifiedIndex, item)
            }
            successState.copy(chapters = newChapters)
        }
    }

    private fun List<Chapter>.toChapterListItems(
        manga: Manga,
        // RK: for merged groups, each chapter's own source-manga, so download status resolves
        // against the source it actually came from (key: mangaId). Empty for non-merged manga.
        mangaBySource: Map<Long, Manga> = emptyMap(),
    ): List<ChapterList.Item> {
        return map { chapter ->
            val owner = mangaBySource[chapter.mangaId] ?: manga
            val isLocal = owner.isLocal()
            val activeDownload = if (isLocal) {
                null
            } else {
                downloadManager.getQueuedDownloadOrNull(chapter.id)
            }
            val downloaded = if (isLocal) {
                true
            } else {
                downloadManager.isChapterDownloaded(
                    chapter.name,
                    chapter.scanlator,
                    chapter.url,
                    owner.title,
                    owner.source,
                )
            }
            val downloadState = when {
                activeDownload != null -> activeDownload.status
                downloaded -> Download.State.DOWNLOADED
                else -> Download.State.NOT_DOWNLOADED
            }

            ChapterList.Item(
                chapter = chapter,
                downloadState = downloadState,
                downloadProgress = activeDownload?.progress ?: 0,
                selected = chapter.id in selectedChapterIds,
            )
        }
    }

    // RK -->

    /** Combine inputs for the chapter flow. */
    private data class ChapterInputs(
        val manga: Manga,
        val ownChapters: List<Chapter>,
        val relatedIds: LongArray,
        val selectedSource: Long?,
    )

    /** Display payload for the chapter flow: the screen manga, the (possibly merged) chapter list,
     *  and the per-source manga for merged groups (empty when not merged). */
    private data class MergedChapters(
        val manga: Manga,
        val chapters: List<Chapter>,
        val mangaBySource: Map<Long, Manga>,
        // RK: per-source metadata shown in the info box when a source chip is active (null = unified).
        // Kept separate from [manga] so favorite / tracking / chapter-flag actions stay on the primary.
        val displayManga: Manga? = null,
        val displaySource: Source? = null,
    )

    /** Chapters of a single grouped source (chip selection), keyed for download by its own manga. */
    private suspend fun singleSourceChaptersFlow(displayManga: Manga, sourceMangaId: Long): Flow<MergedChapters> {
        val sourceManager = Injekt.get<SourceManager>()
        return getMangaAndChapters.subscribe(sourceMangaId, applyScanlatorFilter = true)
            .map { (sourceManga, chapters) ->
                MergedChapters(
                    manga = displayManga,
                    chapters = chapters,
                    mangaBySource = mapOf(sourceManga.id to sourceManga),
                    displayManga = sourceManga,
                    displaySource = sourceManager.getOrStub(sourceManga.source),
                )
            }
    }

    /** Expand [chapters] to include the matching chapter (same recognized number) from every
     *  grouped source, so read / bookmark applies across the whole merge group. No-op when not
     *  merged or when none of the chapters have a recognized number. */
    private suspend fun expandToGroup(chapters: List<Chapter>): List<Chapter> {
        val ids = relatedMangaIds.value
        if (ids.size <= 1) return chapters
        val numbers = chapters.asSequence().filter { it.isRecognizedNumber }.map { it.chapterNumber }.toHashSet()
        if (numbers.isEmpty()) return chapters
        val result = chapters.toMutableList()
        val seen = chapters.mapTo(HashSet()) { it.id }
        for (sibId in ids) {
            getMangaAndChapters.awaitChapters(sibId).forEach { c ->
                if (c.isRecognizedNumber && c.chapterNumber in numbers && seen.add(c.id)) result += c
            }
        }
        return result
    }

    /** Raise the stored gallery metadata for a source's manga, mirroring MetadataViewScreenModel.
     *  Returns null when the source has no metadata support or nothing is stored. */
    private suspend fun loadGalleryMetadata(targetMangaId: Long): RaisedSearchMetadata? {
        return raiseMetadata(getFlatMetadataById.await(targetMangaId), targetMangaId)
    }

    /** Raise a [FlatMetadata] row into its source's typed metadata; null when the source isn't a
     *  MetadataSource or nothing was stored. Shared by the seed and the reactive metadata flow. */
    private suspend fun raiseMetadata(flatMetadata: FlatMetadata?, targetMangaId: Long): RaisedSearchMetadata? {
        if (flatMetadata == null) return null
        val targetManga = getMangaAndChapters.awaitManga(targetMangaId)
        val metadataSource = Injekt.get<SourceManager>().get(targetManga.source)
            ?.getMainSource<MetadataSource<*, *>>() ?: return null
        return flatMetadata.raise(metadataSource.metaClass)
    }

    /** Resolve the source-switcher chips for the full group (empty when not merged). */
    private suspend fun buildMergeSources(ids: LongArray): List<MergeSourceInfo> {
        if (ids.size <= 1) return emptyList()
        val sourceManager = Injekt.get<SourceManager>()
        return ids.map { id ->
            val sourceManga = getMangaAndChapters.awaitManga(id)
            MergeSourceInfo(id, sourceManager.getOrStub(sourceManga.source).name, id == mangaId)
        }
    }

    /** Combine every grouped source's chapters into one aggregated, deduped, reading-ordered list.
     *  Suspend because [GetMangaWithChapters.subscribe] is; called from the suspend flatMapLatest. */
    private suspend fun mergedChaptersFlow(displayManga: Manga, relatedIds: LongArray): Flow<MergedChapters> {
        val perSibling = mutableListOf<Flow<Triple<Long, Manga, List<Chapter>>>>()
        for (id in relatedIds) {
            perSibling += getMangaAndChapters.subscribe(id, applyScanlatorFilter = true)
                .map { (manga, chapters) -> Triple(id, manga, chapters) }
        }
        return combine(perSibling) { siblings ->
            val mangaBySource = siblings.associate { (id, manga, _) -> id to manga }
            val chaptersBySource = siblings.associate { (id, _, chapters) -> id to chapters }
            val sourceIdByManga = siblings.associate { (id, manga, _) -> id to manga.source }
            // The aggregate + reading-order policy is shared with the reader via MergedChapterProvider.
            val aggregated = mergedChapterProvider.aggregate(chaptersBySource, sourceIdByManga)
            MergedChapters(displayManga, aggregated, mangaBySource)
        }
    }

    // Hide/unhide chapters (manga twin of the novel details mechanism). The hidden set is a pref of
    // restore-stable "<source>|<chapterUrl>" keys; it filters Success.chapters at assembly, so hidden
    // chapters also drop from the resume FAB and download-all (which read that list). The in-app manga
    // reader excludes them too (ReaderViewModel.chapterList), so next/prev navigation skips hidden.

    /** Restore-stable hidden-chapter key: the chapter's own source (per-source for a merged group). */
    private fun hiddenKey(chapter: Chapter, manga: Manga, mangaBySource: Map<Long, Manga>): String =
        "${(mangaBySource[chapter.mangaId] ?: manga).source}|${chapter.url}"

    private data class HiddenChapters(
        val chapters: List<ChapterList.Item>,
        val showHidden: Boolean,
        val hasHiddenChapters: Boolean,
        val hiddenChapterIds: Set<Long>,
    )

    /** Drop hidden chapters from [items] unless the user is temporarily showing them, and compute the
     *  hide-related state. "Showing hidden" only holds while hidden chapters still exist, so unhiding
     *  the last one collapses the mode instead of leaving a stale toggle. */
    private fun applyHiddenChapters(
        items: List<ChapterList.Item>,
        manga: Manga,
        mangaBySource: Map<Long, Manga>,
    ): HiddenChapters {
        val hidden = hiddenChaptersPref.get()
        val hasHidden = hidden.isNotEmpty() && items.any { hiddenKey(it.chapter, manga, mangaBySource) in hidden }
        val showHidden = showHiddenFlow.value && hasHidden
        val chapters = if (showHidden || !hasHidden) {
            items
        } else {
            items.filterNot { hiddenKey(it.chapter, manga, mangaBySource) in hidden }
        }
        val hiddenChapterIds = if (showHidden) {
            chapters.filter { hiddenKey(it.chapter, manga, mangaBySource) in hidden }.mapTo(HashSet()) { it.id }
        } else {
            emptySet()
        }
        return HiddenChapters(chapters, showHidden, hasHidden, hiddenChapterIds)
    }

    /** Hide the selected chapters, then clear the selection. */
    fun hideSelected() {
        val state = successState ?: return
        val keys = state.chapters.filter { it.selected }
            .map { hiddenKey(it.chapter, state.manga, state.mergedMangaById) }
        if (keys.isEmpty()) return
        hiddenChaptersPref.set(hiddenChaptersPref.get() + keys)
        toggleAllSelection(false)
    }

    /** Unhide the selected chapters (reachable while showing hidden), then clear the selection. */
    fun unhideSelected() {
        val state = successState ?: return
        val keys = state.chapters.filter { it.selected }
            .mapTo(HashSet()) { hiddenKey(it.chapter, state.manga, state.mergedMangaById) }
        if (keys.isEmpty()) return
        hiddenChaptersPref.set(hiddenChaptersPref.get().filterNotTo(HashSet()) { it in keys })
        toggleAllSelection(false)
    }

    /** Toggle temporarily showing hidden chapters (dimmed) in the list. */
    fun toggleShowHidden() {
        showHiddenFlow.value = !showHiddenFlow.value
    }
    // RK <--

    /**
     * @throws IllegalStateException if the swipe action is [LibraryPreferences.ChapterSwipeAction.Disabled]
     */
    fun chapterSwipe(chapterItem: ChapterList.Item, swipeAction: LibraryPreferences.ChapterSwipeAction) {
        screenModelScope.launch {
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
            LibraryPreferences.ChapterSwipeAction.ToggleRead -> {
                markChaptersRead(listOf(chapter), !chapter.read)
            }
            LibraryPreferences.ChapterSwipeAction.ToggleBookmark -> {
                bookmarkChapters(listOf(chapter), !chapter.bookmark)
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
        val successState = successState ?: return null
        // RK: never resume into a hidden chapter, even while temporarily showing hidden ones.
        return successState.chapters
            .filterNot { it.id in successState.hiddenChapterIds }
            .getNextUnread(successState.manga)
    }

    private fun getUnreadChapters(): List<Chapter> {
        // RK: hidden chapters are never bulk-downloaded (they are in the list only while showing hidden).
        val hidden = successState?.hiddenChapterIds.orEmpty()
        val chapterItems = if (skipFiltered) filteredChapters.orEmpty() else allChapters.orEmpty()
        return chapterItems
            .filterNot { it.id in hidden }
            .filter { (chapter, dlStatus) -> !chapter.read && dlStatus == Download.State.NOT_DOWNLOADED }
            .map { it.chapter }
    }

    private fun getUnreadChaptersSorted(): List<Chapter> {
        val manga = successState?.manga ?: return emptyList()
        val chaptersSorted = getUnreadChapters().sortedWith(getChapterSort(manga))
        return if (manga.sortDescending()) chaptersSorted.reversed() else chaptersSorted
    }

    private fun getBookmarkedChapters(): List<Chapter> {
        val hidden = successState?.hiddenChapterIds.orEmpty()
        val chapterItems = if (skipFiltered) filteredChapters.orEmpty() else allChapters.orEmpty()
        return chapterItems
            .filterNot { it.id in hidden }
            .filter { (chapter, dlStatus) -> chapter.bookmark && dlStatus == Download.State.NOT_DOWNLOADED }
            .map { it.chapter }
    }

    private fun startDownload(
        chapters: List<Chapter>,
        startNow: Boolean,
    ) {
        val successState = successState ?: return

        screenModelScope.launchNonCancellable {
            if (startNow) {
                val chapterId = chapters.singleOrNull()?.id ?: return@launchNonCancellable
                downloadManager.startDownloadNow(chapterId)
            } else {
                downloadChapters(chapters)
            }

            if (!isFavorited && !successState.hasPromptedToAddBefore) {
                updateSuccessState { state ->
                    state.copy(hasPromptedToAddBefore = true)
                }
                val result = snackbarHostState.showSnackbar(
                    message = context.stringResource(MR.strings.snack_add_to_library),
                    actionLabel = context.stringResource(MR.strings.action_add),
                    withDismissAction = true,
                )
                if (result == SnackbarResult.ActionPerformed && !isFavorited) {
                    toggleFavorite()
                }
            }
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
        val chaptersToDownload = when (action) {
            DownloadAction.NEXT_1_CHAPTER -> getUnreadChaptersSorted().take(1)
            DownloadAction.NEXT_5_CHAPTERS -> getUnreadChaptersSorted().take(5)
            DownloadAction.NEXT_10_CHAPTERS -> getUnreadChaptersSorted().take(10)
            DownloadAction.NEXT_25_CHAPTERS -> getUnreadChaptersSorted().take(25)
            DownloadAction.UNREAD_CHAPTERS -> getUnreadChapters()
            DownloadAction.BOOKMARKED_CHAPTERS -> getBookmarkedChapters()
        }
        if (chaptersToDownload.isNotEmpty()) {
            startDownload(chaptersToDownload, false)
        }
    }

    private fun cancelDownload(chapterId: Long) {
        val activeDownload = downloadManager.getQueuedDownloadOrNull(chapterId) ?: return
        downloadManager.cancelQueuedDownloads(listOf(activeDownload))
        updateDownloadState(activeDownload.apply { status = Download.State.NOT_DOWNLOADED })
    }

    fun markPreviousChapterRead(pointer: Chapter) {
        val manga = successState?.manga ?: return
        val chapters = filteredChapters.orEmpty().map { it.chapter }
        val prevChapters = if (manga.sortDescending()) chapters.asReversed() else chapters
        val pointerPos = prevChapters.indexOf(pointer)
        if (pointerPos != -1) markChaptersRead(prevChapters.take(pointerPos), true)
    }

    /**
     * Mark the selected chapter list as read/unread.
     * @param chapters the list of selected chapters.
     * @param read whether to mark chapters as read or unread.
     */
    fun markChaptersRead(chapters: List<Chapter>, read: Boolean) {
        toggleAllSelection(false)
        if (chapters.isEmpty()) return
        screenModelScope.launchIO {
            setReadStatus.await(
                read = read,
                // RK: also mark the matching chapter in every grouped source
                chapters = expandToGroup(chapters).toTypedArray(),
            )

            if (!read || successState?.hasLoggedInTrackers == false || autoTrackState == AutoTrackState.NEVER) {
                return@launchIO
            }

            refreshTrackers()

            val tracks = getTracks.await(mangaId)
            val maxChapterNumber = chapters.maxOf { it.chapterNumber }
            val shouldPromptTrackingUpdate = tracks.any { track -> maxChapterNumber > track.lastChapterRead }

            if (!shouldPromptTrackingUpdate) return@launchIO
            if (autoTrackState == AutoTrackState.ALWAYS) {
                trackChapter.await(context, mangaId, maxChapterNumber)
                withUIContext {
                    context.toast(context.stringResource(MR.strings.trackers_updated_summary, maxChapterNumber.toInt()))
                }
                return@launchIO
            }

            val result = snackbarHostState.showSnackbar(
                message = context.stringResource(MR.strings.confirm_tracker_update, maxChapterNumber.toInt()),
                actionLabel = context.stringResource(MR.strings.action_ok),
                duration = SnackbarDuration.Short,
                withDismissAction = true,
            )

            if (result == SnackbarResult.ActionPerformed) {
                trackChapter.await(context, mangaId, maxChapterNumber)
            }
        }
    }

    private suspend fun refreshTrackers(
        refreshTracks: RefreshTracks = Injekt.get(),
    ) {
        refreshTracks.await(mangaId)
            .filter { it.first != null }
            .forEach { (track, e) ->
                logcat(LogPriority.ERROR, e) {
                    "Failed to refresh track data mangaId=$mangaId for service ${track!!.id}"
                }
                withUIContext {
                    context.toast(
                        context.stringResource(
                            MR.strings.track_error,
                            track!!.name,
                            e.message ?: "",
                        ),
                    )
                }
            }
    }

    /**
     * Downloads the given list of chapters with the manager.
     * @param chapters the list of chapters to download.
     */
    private fun downloadChapters(chapters: List<Chapter>) {
        val state = successState ?: return
        // RK --> in a merged group, download each chapter from its own source-manga
        if (state.mergedMangaById.isNotEmpty()) {
            chapters.groupBy { it.mangaId }.forEach { (mangaId, group) ->
                downloadManager.downloadChapters(state.mergedMangaById[mangaId] ?: state.manga, group)
            }
        } else {
            downloadManager.downloadChapters(state.manga, chapters)
        }
        // RK <--
        toggleAllSelection(false)
    }

    /**
     * Bookmarks the given list of chapters.
     * @param chapters the list of chapters to bookmark.
     */
    fun bookmarkChapters(chapters: List<Chapter>, bookmarked: Boolean) {
        screenModelScope.launchIO {
            // RK: bookmark the matching chapter in every grouped source too
            expandToGroup(chapters)
                .filterNot { it.bookmark == bookmarked }
                .map { ChapterUpdate(id = it.id, bookmark = bookmarked) }
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
        screenModelScope.launchNonCancellable {
            try {
                successState?.let { state ->
                    // RK --> in a merged group, delete each chapter's download from its own source
                    if (state.mergedMangaById.isNotEmpty()) {
                        val sourceManager = Injekt.get<SourceManager>()
                        chapters.groupBy { it.mangaId }.forEach { (mangaId, group) ->
                            val owner = state.mergedMangaById[mangaId] ?: state.manga
                            downloadManager.deleteChapters(group, owner, sourceManager.getOrStub(owner.source))
                        }
                    } else {
                        downloadManager.deleteChapters(chapters, state.manga, state.source)
                    }
                    // RK <--
                }
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e)
            }
        }
    }

    private fun downloadNewChapters(chapters: List<Chapter>) {
        screenModelScope.launchNonCancellable {
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
        screenModelScope.launchNonCancellable {
            setMangaChapterFlags.awaitSetUnreadFilter(manga, flag)
        }
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

        screenModelScope.launchNonCancellable {
            setMangaChapterFlags.awaitSetDownloadedFilter(manga, flag)
        }
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

        screenModelScope.launchNonCancellable {
            setMangaChapterFlags.awaitSetBookmarkFilter(manga, flag)
        }
    }

    /**
     * Sets the active display mode.
     * @param mode the mode to set.
     */
    fun setDisplayMode(mode: Long) {
        val manga = successState?.manga ?: return

        screenModelScope.launchNonCancellable {
            setMangaChapterFlags.awaitSetDisplayMode(manga, mode)
        }
    }

    /**
     * Sets the sorting method and requests an UI update.
     * @param sort the sorting mode.
     */
    fun setSorting(sort: Long) {
        val manga = successState?.manga ?: return

        screenModelScope.launchNonCancellable {
            setMangaChapterFlags.awaitSetSortingModeOrFlipOrder(manga, sort)
        }
    }

    fun setCurrentSettingsAsDefault(applyToExisting: Boolean) {
        val manga = successState?.manga ?: return
        screenModelScope.launchNonCancellable {
            libraryPreferences.setChapterSettingsDefault(manga)
            if (applyToExisting) {
                setMangaDefaultChapterFlags.awaitAll()
            }
            snackbarHostState.showSnackbar(message = context.stringResource(MR.strings.chapter_settings_updated))
        }
    }

    fun resetToDefaultSettings() {
        val manga = successState?.manga ?: return
        screenModelScope.launchNonCancellable {
            setMangaDefaultChapterFlags.await(manga)
        }
    }

    fun toggleSelection(
        item: ChapterList.Item,
        selected: Boolean,
        fromLongPress: Boolean = false,
    ) {
        updateSuccessState { successState ->
            val newChapters = successState.processedChapters.toMutableList().apply {
                val selectedIndex = successState.processedChapters.indexOfFirst { it.id == item.chapter.id }
                if (selectedIndex < 0) return@apply

                val selectedItem = get(selectedIndex)
                if ((selectedItem.selected && selected) || (!selectedItem.selected && !selected)) return@apply

                val firstSelection = none { it.selected }
                set(selectedIndex, selectedItem.copy(selected = selected))
                selectedChapterIds.addOrRemove(item.id, selected)

                if (selected && fromLongPress) {
                    if (firstSelection) {
                        selectedPositions[0] = selectedIndex
                        selectedPositions[1] = selectedIndex
                    } else {
                        // Try to select the items in-between when possible
                        val range: IntRange
                        if (selectedIndex < selectedPositions[0]) {
                            range = selectedIndex + 1..<selectedPositions[0]
                            selectedPositions[0] = selectedIndex
                        } else if (selectedIndex > selectedPositions[1]) {
                            range = (selectedPositions[1] + 1)..<selectedIndex
                            selectedPositions[1] = selectedIndex
                        } else {
                            // Just select itself
                            range = IntRange.EMPTY
                        }

                        range.forEach {
                            val inbetweenItem = get(it)
                            if (!inbetweenItem.selected) {
                                selectedChapterIds.add(inbetweenItem.id)
                                set(it, inbetweenItem.copy(selected = true))
                            }
                        }
                    }
                } else if (!fromLongPress) {
                    if (!selected) {
                        if (selectedIndex == selectedPositions[0]) {
                            selectedPositions[0] = indexOfFirst { it.selected }
                        } else if (selectedIndex == selectedPositions[1]) {
                            selectedPositions[1] = indexOfLast { it.selected }
                        }
                    } else {
                        if (selectedIndex < selectedPositions[0]) {
                            selectedPositions[0] = selectedIndex
                        } else if (selectedIndex > selectedPositions[1]) {
                            selectedPositions[1] = selectedIndex
                        }
                    }
                }
            }
            successState.copy(chapters = newChapters)
        }
    }

    fun toggleAllSelection(selected: Boolean) {
        updateSuccessState { successState ->
            val newChapters = successState.chapters.map {
                selectedChapterIds.addOrRemove(it.id, selected)
                it.copy(selected = selected)
            }
            selectedPositions[0] = -1
            selectedPositions[1] = -1
            successState.copy(chapters = newChapters)
        }
    }

    fun invertSelection() {
        updateSuccessState { successState ->
            val newChapters = successState.chapters.map {
                selectedChapterIds.addOrRemove(it.id, !it.selected)
                it.copy(selected = !it.selected)
            }
            selectedPositions[0] = -1
            selectedPositions[1] = -1
            successState.copy(chapters = newChapters)
        }
    }

    // Chapters list - end

    // Track sheet - start

    private fun observeTrackers() {
        val manga = successState?.manga ?: return

        screenModelScope.launchIO {
            combine(
                getTracks.subscribe(manga.id).catch { logcat(LogPriority.ERROR, it) },
                trackerManager.loggedInTrackersFlow(),
            ) { mangaTracks, loggedInTrackers ->
                // Show only if the service supports this manga's source
                val supportedTrackers = loggedInTrackers.filter { (it as? EnhancedTracker)?.accept(source!!) ?: true }
                val supportedTrackerIds = supportedTrackers.map { it.id }.toHashSet()
                val supportedTrackerTracks = mangaTracks.filter { it.trackerId in supportedTrackerIds }
                supportedTrackerTracks.size to supportedTrackers.isNotEmpty()
            }
                .flowWithLifecycle(lifecycle)
                .distinctUntilChanged()
                .collectLatest { (trackingCount, hasLoggedInTrackers) ->
                    updateSuccessState {
                        it.copy(
                            trackingCount = trackingCount,
                            hasLoggedInTrackers = hasLoggedInTrackers,
                        )
                    }
                }
        }
    }

    // Track sheet - end

    sealed interface Dialog {
        data class ChangeCategory(
            val manga: Manga,
            val initialSelection: List<CheckboxState<Category>>,
        ) : Dialog
        data class DeleteChapters(val chapters: List<Chapter>) : Dialog

        // RK: suggestGroup gates the "add to existing group" action (the same-title suggestion pref);
        // groupIdByMangaId collapses same-group duplicates into one card.
        data class DuplicateManga(
            val manga: Manga,
            val duplicates: List<MangaWithChapterCount>,
            val suggestGroup: Boolean,
            val groupIdByMangaId: Map<Long, Long>,
        ) : Dialog
        data class Migrate(val target: Manga, val current: Manga) : Dialog
        data class SetFetchInterval(val manga: Manga) : Dialog
        data object SettingsSheet : Dialog
        data object TrackSheet : Dialog
        data object FullCover : Dialog

        // RK: manage the grouped sources (reorder / split / remove). Rows arrive trunk-first (primary on
        // top); isOverridden gates the reset action.
        data class ManageSources(
            val sources: List<MergeSourceInfo>,
            val isOverridden: Boolean,
        ) : Dialog

        // RK: confirm removing a favorited E-Hentai gallery, with an opt-in "also remove from account".
        data class EhRemoveFavorite(val manga: Manga) : Dialog

        // RK: shared edit-info editor; carries the raw source manga (each field is saved only when it
        // differs from these).
        data class EditMangaInfo(val manga: Manga) : Dialog
    }

    // RK: a grouped source row shown in the Manage sources dialog. chapterCount is the coverage hint,
    // resolved only when the dialog opens (0 in the chip-row uses that don't need it).
    data class MergeSourceInfo(
        val mangaId: Long,
        val sourceName: String,
        val isCurrent: Boolean,
        val chapterCount: Int = 0,
    )

    // RK: a related-carousel candidate plus whether it already resolves to a favorited library entry.
    data class RelatedMangaItem(val candidate: RelatedMangaCandidate, val inLibrary: Boolean)

    fun dismissDialog() {
        updateSuccessState { it.copy(dialog = null) }
    }

    fun showDeleteChapterDialog(chapters: List<Chapter>) {
        updateSuccessState { it.copy(dialog = Dialog.DeleteChapters(chapters)) }
    }

    // RK -->

    fun showEditMangaInfoDialog() {
        val manga = successState?.manga ?: return
        updateSuccessState { it.copy(dialog = Dialog.EditMangaInfo(manga)) }
    }

    /** Persist edits as a non-destructive per-field override against the raw source [manga]. */
    fun saveMangaInfo(manga: Manga, edited: EntryEditInfoUi) {
        screenModelScope.launchNonCancellable {
            setCustomMangaInfo.set(edited.toCustomMangaInfo(manga))
        }
        dismissDialog()
    }

    /** Clear every override, so all fields track the source again. */
    fun resetMangaInfo(manga: Manga) {
        screenModelScope.launchNonCancellable {
            setCustomMangaInfo.set(CustomMangaInfo(mangaId = manga.id))
        }
        dismissDialog()
    }

    /** Bound trackers eligible for "Fill from tracker" (self-hosted enhanced trackers can't autofill). */
    suspend fun autofillCandidates(): List<Pair<Track, Tracker>> =
        getTracks.await(mangaId)
            .mapNotNull { track -> trackerManager.get(track.trackerId)?.let { track to it } }
            .filterNot { (_, tracker) -> tracker is EnhancedTracker }

    suspend fun fetchTrackerMetadata(track: Track, tracker: Tracker): TrackMangaMetadata =
        tracker.getMangaMetadata(track)

    /** Switch the chapter list to a single grouped source, or null for the unified merged view. */
    fun selectSource(sourceMangaId: Long?) {
        selectedSourceMangaId.value = sourceMangaId
    }

    fun showManageSourcesDialog() {
        val state = successState ?: return
        // Use the full group (stable) so the dialog works even while viewing a single source chip.
        if (state.mergeSources.size <= 1) return
        screenModelScope.launchIO {
            val ids = state.mergeSources.map { it.mangaId }
            // Order the rows by the same ranking aggregation uses, so the primary source opens on top even
            // under the global order (no override). memberRanking non-empty == override on.
            val memberRanking = mergeManager.overrideRankingMemberIds(mangaId)
            val chaptersBySource = ids.associateWith {
                getMangaAndChapters.awaitChapters(it, applyScanlatorFilter = true)
            }
            val sourceIdByManga = ids.associateWith { getMangaAndChapters.awaitManga(it).source }
            val ranked = mergedChapterProvider.rankedMemberIds(chaptersBySource, sourceIdByManga, memberRanking)
            val orderedSources = ranked.mapNotNull { id ->
                state.mergeSources.find { it.mangaId == id }
                    ?.copy(chapterCount = chaptersBySource[id]?.size ?: 0)
            }
            updateSuccessState {
                it.copy(dialog = Dialog.ManageSources(orderedSources, memberRanking.isNotEmpty()))
            }
        }
    }

    /** Persist a manage-sources drag as the group's source order, then nudge the chapter flow to
     *  re-aggregate so the new trunk leads the list live (a fresh array re-emits the StateFlow). */
    fun reorderSources(orderedIds: List<Long>) {
        screenModelScope.launchIO {
            mergeManager.setSourceOrder(orderedIds)
            relatedMangaIds.value = relatedMangaIds.value.copyOf()
        }
    }

    /** Clear the per-group source-order override (back to the global ranking) and re-aggregate live. */
    fun resetSourceOrder() {
        dismissDialog()
        screenModelScope.launchIO {
            mergeManager.clearSourceOrder(mangaId)
            relatedMangaIds.value = relatedMangaIds.value.copyOf()
        }
    }

    /**
     * Split [targetIds] out of the merge group, with an Undo that restores the prior merge prefs.
     * Selecting every source dissolves the whole group (each manga becomes standalone, still in the
     * library) instead of no-opping. Undo restores the prefs + group either way.
     */
    fun splitSources(targetIds: List<Long>) {
        if (targetIds.isEmpty()) return
        val prevRelated = relatedMangaIds.value
        selectedSourceMangaId.value = null
        dismissDialog()
        screenModelScope.launchIO {
            val newIds = mergeManager.splitOrDissolve(prevRelated, targetIds)
            relatedMangaIds.value = if (newIds.isEmpty()) longArrayOf(mangaId) else newIds
            val result = snackbarHostState.showSnackbar(
                message = context.stringResource(MR.strings.merge_sources_split),
                actionLabel = context.stringResource(MR.strings.action_undo),
                duration = SnackbarDuration.Short,
                withDismissAction = true,
            )
            if (result == SnackbarResult.ActionPerformed) {
                // Undo re-merges the original group; the split wrote to the group tables, not prefs.
                mergeManager.mergeManga(prevRelated.toList())
                relatedMangaIds.value = prevRelated
            }
        }
    }

    /** Split [targetIds] out and unfavorite them, with an Undo that re-favorites and re-groups. */
    fun removeSourcesFromLibrary(targetIds: List<Long>) {
        if (targetIds.isEmpty()) return
        val prevRelated = relatedMangaIds.value
        selectedSourceMangaId.value = null
        dismissDialog()
        screenModelScope.launchNonCancellable {
            targetIds.forEach { updateManga.awaitUpdateFavorite(it, false) }
        }
        screenModelScope.launchIO {
            relatedMangaIds.value = mergeManager.removeFromGroup(prevRelated, targetIds)
            val result = snackbarHostState.showSnackbar(
                message = context.stringResource(MR.strings.merge_sources_removed),
                actionLabel = context.stringResource(MR.strings.action_undo),
                duration = SnackbarDuration.Short,
                withDismissAction = true,
            )
            if (result == SnackbarResult.ActionPerformed) {
                // Undo re-merges the original group and re-favorites the removed sources.
                mergeManager.mergeManga(prevRelated.toList())
                relatedMangaIds.value = prevRelated
                screenModelScope.launchNonCancellable {
                    targetIds.forEach { updateManga.awaitUpdateFavorite(it, true) }
                }
            }
        }
    }
    // RK <--

    // RK: remove the whole merge group from the library at once (Manage Sources shortcut)
    fun removeAllSourcesFromLibrary() {
        removeSourcesFromLibrary(relatedMangaIds.value.toList())
    }

    // RK --> related-mangas carousel (recommendations)
    private var relatedLoadStarted = false

    /**
     * Suspend until the initial details/chapter fetch settles. Returns at once when nothing is
     * refreshing (a library entry that needed no fetch). `fetchAllFromSource` swallows its own
     * failures, so the flag always clears and this cannot stall the carousel.
     */
    private suspend fun awaitOwnDataLoaded() {
        state.first { it !is State.Success || !it.isRefreshingData }
    }

    /** Load the related carousel once per screen open. Serves a fresh cache hit instantly; otherwise
     *  streams source-native related, marking which candidates are already in the library. */
    fun loadRelatedMangas() {
        if (relatedLoadStarted) return
        // Gate before any work: the carousel self-hides on an empty pool, so an early return both
        // hides the row and spares the source every request the load would have made.
        if (!recommendationPreferences.enableRelatedMangas.get()) return
        val state = successState ?: return
        val source = state.source as? CatalogueSource ?: return
        relatedLoadStarted = true
        // Bootstrap / refresh the taste cache out of band (never on the carousel's critical path);
        // the profile read below uses whatever is already cached, the pull lands for the next open.
        screenModelScope.launchIO { refreshTrackerLibrary.refreshIfStale() }
        screenModelScope.launchIO {
            val favorites = getFavorites.await()
            val favoriteKeys = favorites.mapTo(HashSet()) { it.url to it.source }
            // Anti-echo: opt-in filter that hides suggestions the user already has/tracks (by id, then
            // title). No-op when no filter is enabled.
            val hideFilter = buildRecommendationHideFilter.await()
            val cached = relatedMangaCache.get(state.manga.id)
            if (cached != null) {
                applyRelated(cached.fullPool, favoriteKeys, hideFilter)
                if (cached.isComplete && relatedMangaCache.isFresh(cached)) return@launchIO
            } else {
                updateSuccessState { it.copy(relatedLoading = true) }
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
                tracks = getTracks.await(state.manga.id),
                ranker = recommendationPreferences.buildRanker(),
                // Rerank off -> empty profile, which collapses the ranker to popularity order.
                taste = if (recommendationPreferences.enableRecommendationRerank.get()) {
                    getTasteProfile.await()
                } else {
                    TasteProfile.EMPTY
                },
                currentGenres = state.manga.genre.orEmpty(),
                onUpdate = {
                    // Cache each streamed snapshot (incomplete) so "See all" works before the load
                    // finishes; the final put below marks it complete.
                    relatedMangaCache.put(mangaId, it.take(CAROUSEL_CAP), it, isComplete = false)
                    applyRelated(it, favoriteKeys, hideFilter)
                },
            )
            relatedMangaCache.put(mangaId, pool.take(CAROUSEL_CAP), pool)
            applyRelated(pool, favoriteKeys, hideFilter)
            updateSuccessState { it.copy(relatedLoading = false) }
        }
    }

    private fun applyRelated(
        pool: List<RelatedMangaCandidate>,
        favoriteKeys: Set<Pair<String, Long>>,
        hideFilter: RecommendationHideFilter,
    ) {
        val items = pool
            .filterNot { hideFilter.shouldHide(it) }
            .map { RelatedMangaItem(it, (it.manga.url to it.sourceId) in favoriteKeys) }
        // Cap the carousel; the full pool stays in the cache for the "See all" browse grid.
        updateSuccessState { it.copy(relatedItems = items.take(CAROUSEL_CAP), relatedTotalCount = items.size) }
    }

    /** Resolve a tapped candidate to a local manga id to open, or null for a tracker-origin card
     *  (whose URL belongs to no installed source) so the caller can route it through global search. */
    suspend fun resolveRelatedToLocalId(candidate: RelatedMangaCandidate): Long? {
        if (candidate.sourceId == RECOMMENDS_SOURCE) return null
        return networkToLocalManga(candidate.manga.toDomainManga(candidate.sourceId)).id
    }
    // RK <--

    fun showSettingsDialog() {
        updateSuccessState { it.copy(dialog = Dialog.SettingsSheet) }
    }

    fun showTrackDialog() {
        updateSuccessState { it.copy(dialog = Dialog.TrackSheet) }
    }

    fun showCoverDialog() {
        updateSuccessState { it.copy(dialog = Dialog.FullCover) }
    }

    fun showMigrateDialog(duplicate: Manga) {
        val manga = successState?.manga ?: return
        updateSuccessState { it.copy(dialog = Dialog.Migrate(target = manga, current = duplicate)) }
    }

    fun setExcludedScanlators(excludedScanlators: Set<String>) {
        screenModelScope.launchIO {
            setExcludedScanlators.await(mangaId, excludedScanlators)
        }
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
            val mergeSources: List<MergeSourceInfo> = emptyList(),
            val selectedSourceMangaId: Long? = null,
            // RK: per-source metadata for the info box when a chip is active (null = unified -> primary).
            val mergeDisplayManga: Manga? = null,
            val mergeDisplaySource: Source? = null,
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
            val hasPromptedToAddBefore: Boolean = false,
            val hideMissingChapters: Boolean = false,
            // RK: hide/unhide chapters. showHidden is the transient reveal toggle; hiddenChapterIds are
            // the currently-shown hidden rows (for dimming), only populated while showing hidden.
            val showHidden: Boolean = false,
            val hasHiddenChapters: Boolean = false,
            val hiddenChapterIds: Set<Long> = emptySet(),
            // RK: the manga's custom-info overlay (null = none), applied at the display layer via
            // Manga.withCustomInfo. Never folded into the raw `manga` field above, which stays
            // source-accurate for tracker search, refresh, duplicate detection, downloads, etc.
            val customInfo: CustomMangaInfo? = null,
            // RK: cover-derived theming color (Y11), null when off or not yet extracted.
            val seedColor: Color? = null,
            // RK: page-preview thumbnails (adult sources) + how many rows to show (0 = off).
            val pagePreviewsState: PagePreviewState = PagePreviewState.Unused,
            val previewsRowCount: Int = 0,
        ) : State {
            // RK -->
            // EH/EXH galleries are tags-as-content with no description, so default the info box
            // to expanded in the library too (Mihon only auto-expands when arriving from a source).
            val isMetadataSource: Boolean
                get() = source.getMainSource<MetadataSource<*, *>>() != null
            // RK <--

            val processedChapters by lazy {
                chapters.applyFilters(manga).toList()
            }

            val isAnySelected by lazy {
                chapters.fastAny { it.selected }
            }

            val chapterListItems by lazy {
                if (hideMissingChapters) {
                    return@lazy processedChapters
                }

                processedChapters.insertSeparators { before, after ->
                    val (lowerChapter, higherChapter) = if (manga.sortDescending()) {
                        after to before
                    } else {
                        before to after
                    }
                    if (higherChapter == null) return@insertSeparators null

                    if (lowerChapter == null) {
                        floor(higherChapter.chapter.chapterNumber)
                            .toInt()
                            .minus(1)
                            .coerceAtLeast(0)
                    } else {
                        calculateChapterGap(higherChapter.chapter, lowerChapter.chapter)
                    }
                        .takeIf { it > 0 }
                        ?.let { missingCount ->
                            ChapterList.MissingCount(
                                id = "${lowerChapter?.id}-${higherChapter.id}",
                                count = missingCount,
                            )
                        }
                }
            }

            val scanlatorFilterActive: Boolean
                get() = excludedScanlators.intersect(availableScanlators).isNotEmpty()

            val filterActive: Boolean
                get() = scanlatorFilterActive || manga.chaptersFiltered()

            /**
             * Applies the view filters to the list of chapters obtained from the database.
             * @return an observable of the list of chapters filtered and sorted.
             */
            private fun List<ChapterList.Item>.applyFilters(manga: Manga): Sequence<ChapterList.Item> {
                val isLocalManga = manga.isLocal()
                val unreadFilter = manga.unreadFilter
                val downloadedFilter = manga.downloadedFilter
                val bookmarkedFilter = manga.bookmarkedFilter
                return asSequence()
                    .filter { (chapter) -> applyFilter(unreadFilter) { !chapter.read } }
                    .filter { (chapter) -> applyFilter(bookmarkedFilter) { chapter.bookmark } }
                    .filter { applyFilter(downloadedFilter) { it.isDownloaded || isLocalManga } }
                    .sortedWith { (chapter1), (chapter2) -> getChapterSort(manga).invoke(chapter1, chapter2) }
            }
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

/**
 * RK: per-field override, store a value only when it differs from the current source value; a blank field
 * (or "Unknown" status) stores nothing, so that field tracks the source again.
 */
private fun EntryEditInfoUi.toCustomMangaInfo(source: Manga) = CustomMangaInfo(
    mangaId = source.id,
    title = title.trim().takeIf { it.isNotEmpty() && it != source.title },
    author = author.trim().takeIf { it.isNotEmpty() && it != source.author.orEmpty() },
    artist = artist.trim().takeIf { it.isNotEmpty() && it != source.artist.orEmpty() },
    description = description.takeIf { it.isNotBlank() && it != source.description.orEmpty() },
    genre = genre.takeIf { it.isNotEmpty() && it != source.genre.orEmpty() },
    status = status.takeIf { it != source.status && it != SManga.UNKNOWN.toLong() },
    thumbnailUrl = thumbnailUrl.trim().takeIf { it.isNotEmpty() && it != source.thumbnailUrl.orEmpty() },
)
