package reikai.presentation.novel.details

import android.app.Application
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.palette.graphics.Palette
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import eu.kanade.domain.track.model.AutoTrackState
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.manga.DownloadAction
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.tachiyomi.data.coil.getBestColor
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.util.system.getBitmapOrNull
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import reikai.data.coil.NovelCover
import reikai.data.novel.NovelStatusCode
import reikai.data.novel.refreshNovelFromSource
import reikai.data.novel.syncChaptersWithNovelSource
import reikai.data.novel.toNovel
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.novel.NovelChapterAggregation
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelMergeManager
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.interactor.DeleteNovelChaptersAfterRead
import reikai.domain.novel.interactor.GetNovelCategories
import reikai.domain.novel.interactor.GetNovelTracks
import reikai.domain.novel.interactor.RefreshNovelTracks
import reikai.domain.novel.interactor.SetNovelCategories
import reikai.domain.novel.interactor.SetNovelChapterFlags
import reikai.domain.novel.interactor.UpdateNovel
import reikai.domain.novel.track.PropagateNovelTrackerLinks
import reikai.domain.novel.track.TrackNovelChapter
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelCategory
import reikai.domain.novel.model.NovelChapter
import reikai.domain.novel.model.NovelChapterFlags
import reikai.domain.novel.model.NovelEditFlags
import reikai.domain.novel.model.NovelUpdate
import reikai.domain.novel.model.effectiveBookmarkedFilter
import reikai.domain.novel.model.effectiveDownloadedFilter
import reikai.domain.novel.model.effectiveHideChapterTitles
import reikai.domain.novel.model.effectiveReadFilter
import reikai.domain.novel.model.effectiveSortDescending
import reikai.domain.novel.model.effectiveSorting
import reikai.domain.novel.model.mergeRefreshedNovel
import reikai.domain.novel.model.setEditedFlag
import reikai.domain.novel.model.sortedAndFiltered
import reikai.novel.download.NovelDownload
import reikai.novel.download.NovelDownloadManager
import reikai.novel.install.LnPluginInstaller
import reikai.novel.source.NovelSource
import reikai.novel.source.NovelSourceManager
import reikai.presentation.novel.selectChaptersForDownloadAction
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.launchUI
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.data.Database
import tachiyomi.i18n.MR
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.MangaCover
import uy.kohesive.injekt.injectLazy

/**
 * Single-source light-novel details state holder, the B1 port of Yōkai's `NovelDetailsScreenModel`
 * re-typed onto the S1 repos. DB-first: the stored novel + its chapters drive the screen; the source
 * is hit only on first open (no local chapters) or an explicit [refresh]. Owns favorite, categories,
 * edit-info, chapter sort/filter/display, multi-select read/bookmark, and the cover-tint seed.
 *
 * Merge (the [NovelDetailsState.Loaded.displayNovel] seam), the reader (chapter tap), and downloads
 * are stubbed here and wired at S8 / S4 / S5.
 */
class NovelDetailsScreenModel(
    private val sourceId: String,
    private val novelUrl: String,
) : StateScreenModel<NovelDetailsState>(NovelDetailsState.Loading) {

    private val novelRepo: NovelRepository by injectLazy()
    private val updateNovel: UpdateNovel by injectLazy()
    private val setNovelChapterFlags: SetNovelChapterFlags by injectLazy()
    private val chapterRepo: NovelChapterRepository by injectLazy()
    private val database: Database by injectLazy()
    private val downloadManager: NovelDownloadManager by injectLazy()
    private val sourceManager: NovelSourceManager by injectLazy()
    private val installer: LnPluginInstaller by injectLazy()
    private val getNovelCategories: GetNovelCategories by injectLazy()
    private val setNovelCategories: SetNovelCategories by injectLazy()
    private val deleteNovelChaptersAfterRead: DeleteNovelChaptersAfterRead by injectLazy()
    private val novelPreferences: NovelPreferences by injectLazy()
    private val uiPreferences: UiPreferences by injectLazy()
    private val mergeManager: NovelMergeManager by injectLazy()
    private val reikaiLibraryPreferences: ReikaiLibraryPreferences by injectLazy()
    private val libraryPreferences: LibraryPreferences by injectLazy()
    private val context: Application by injectLazy()

    // novel trackers (Active #8)
    private val getNovelTracks: GetNovelTracks by injectLazy()
    private val refreshNovelTracks: RefreshNovelTracks by injectLazy()
    private val trackNovelChapter: TrackNovelChapter by injectLazy()
    private val trackerManager: TrackerManager by injectLazy()
    private val trackPreferences: TrackPreferences by injectLazy()
    private val propagateNovelTrackerLinks: PropagateNovelTrackerLinks by injectLazy()

    /** Hosts the merge split/remove Undo snackbars; wired into the details Scaffold. */
    val snackbarHostState = SnackbarHostState()

    /** Resolved once the plugin host loads it; source-dependent ops defer until set. */
    @Volatile
    private var source: NovelSource? = null

    /** A first-open fetch (no stored chapters) runs at most once. */
    private var firstFetchTried = false
    private var refreshJob: Job? = null
    private var seedExtracted = false

    /** The opened novel's id (the merge "current" source); set once the anchor resolves. */
    @Volatile
    private var anchorNovelId = -1L

    /** The page (index into [NovelDetailsState.Loaded.pages]) the chapter list is showing. */
    private val pageIndex = MutableStateFlow(0)

    /** Merge-group novel ids (this novel + grouped siblings); 0/1 element when not merged. */
    private val relatedNovelIds = MutableStateFlow(longArrayOf())

    /** The grouped source chip the user is viewing; null = the unified ("All") list. */
    private val selectedSourceNovelId = MutableStateFlow<Long?>(null)

    /** novelId -> resolved source for every grouped sibling (unified rank, chips, reader routing). */
    private val siblingSources = MutableStateFlow<Map<Long, NovelSource>>(emptyMap())

    /** Source-switcher chips for the current group; held here (not only in state) so the chapter
     *  rebuild always reads them regardless of which collector resolves first. Empty when not merged. */
    private val mergeChips = MutableStateFlow<List<NovelMergeSourceInfo>>(emptyList())

    /** User-hidden chapters, keyed `"<source>|<chapterUrl>"` (restore-stable). Filtered out of the
     *  list unless [showHiddenFlow] is on (then shown dimmed). */
    private val hiddenChaptersPref = novelPreferences.hiddenChapters()

    /** Whether hidden chapters are temporarily shown (dimmed) so they can be unhidden. */
    private val showHiddenFlow = MutableStateFlow(false)

    /** Paged keys already lazily fetched, so an empty page doesn't re-fetch on every flow emission. */
    private val triedPages = java.util.Collections.synchronizedSet(HashSet<String>())

    /** Range-select anchor into the displayed chapter order; -1 when no selection. */
    private val selectionAnchor = intArrayOf(-1, -1)

    /** Latest observed bound-tracker count, held outside state so the first [NovelDetailsState.Loaded]
     *  built picks it up even when the observer emitted while the screen was still loading. */
    @Volatile
    private var currentTrackingCount = 0

    init {
        observeMergeGroup()
        observeMergeSourceChips()
        observeChapters()
        observeDownloadQueue()
        observeTrackingCount()
        resolveSource()
    }

    /** Mirror the bound-tracker count (on logged-in services) into [NovelDetailsState.Loaded.trackingCount],
     *  so the action-row Tracking button shows the count + flips its icon, like the manga header. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeTrackingCount() {
        screenModelScope.launchIO {
            novelRepo.getByUrlAndSourceAsFlow(novelUrl, sourceId)
                .map { it?.id }
                .distinctUntilChanged()
                .flatMapLatest { novelId ->
                    if (novelId == null) {
                        flowOf(0)
                    } else {
                        // subscribeGroup spans the merge group, so a track bound on a sibling source counts.
                        combine(
                            getNovelTracks.subscribeGroup(novelId),
                            trackerManager.loggedInTrackersFlow(),
                        ) { tracks, loggedIn ->
                            val loggedInIds = loggedIn.mapTo(HashSet()) { it.id }
                            tracks.count { it.trackerId in loggedInIds }
                        }
                    }
                }
                .collectLatest { count ->
                    currentTrackingCount = count
                    mutableState.update { (it as? NovelDetailsState.Loaded)?.copy(trackingCount = count) ?: it }
                }
        }
    }

    /** Mirror the live download queue into [NovelDetailsState.Loaded.downloadStates]. Only the active
     *  queue states (queued/downloading/error) live here; a finished download is read from the
     *  chapter row's `isDownloaded` flag instead (see the chapter list's downloadStateProvider). */
    private fun observeDownloadQueue() {
        screenModelScope.launchIO {
            downloadManager.queueState.collectLatest { queue ->
                val map = queue.associate { it.chapterId to it.state.toDownloadState() }
                mutableState.update { (it as? NovelDetailsState.Loaded)?.copy(downloadStates = map) ?: it }
            }
        }
    }

    private fun resolveSource() {
        screenModelScope.launchIO {
            try {
                installer.ensureLoaded()
            } catch (_: Throwable) {}
            val resolved = sourceManager.get(sourceId)
            if (resolved == null) {
                if (state.value !is NovelDetailsState.Loaded) {
                    mutableState.value = NovelDetailsState.Failed("Source not installed: $sourceId")
                }
            } else {
                source = resolved
                mutableState.update {
                    (it as? NovelDetailsState.Loaded)?.let { l ->
                        l.copy(
                            sourceName = resolved.name,
                            sourceUrl = resolved.site,
                            novelWebUrl = resolved.webUrl(l.displayNovel.url),
                        )
                    } ?: it
                }
                val loaded = state.value as? NovelDetailsState.Loaded
                if (loaded == null || loaded.chapters.isEmpty()) maybeFirstFetch(loaded?.novel)
            }
        }
    }

    private data class ChapterInputs(
        val anchor: Novel?,
        val related: LongArray,
        val selected: Long?,
        val pageIndex: Int,
    )

    // DB-first: the stored anchor novel + the resolved merge group drive the chapter list. The unified
    // ("All") view pools every grouped source's chapters into one list (no page bar); a selected source
    // chip (or a non-merged novel) keeps its own per-page lazy list (the S3c/S3d behavior). A change to
    // chapterFlags / page / group / selection re-runs this via the combine.
    private fun observeChapters() {
        screenModelScope.launchIO {
            combine(
                combine(
                    novelRepo.getByUrlAndSourceAsFlow(novelUrl, sourceId),
                    relatedNovelIds,
                    selectedSourceNovelId,
                    pageIndex,
                    // In the combine only to re-emit (re-running rebuildLoaded with the chips) once they resolve.
                    mergeChips,
                ) { anchor, related, selected, idx, _ -> ChapterInputs(anchor, related, selected, idx) },
                // Re-emit so a hide/unhide or the show-hidden toggle rebuilds the chapter list.
                hiddenChaptersPref.changes(),
                showHiddenFlow,
            ) { inputs, _, _ -> inputs }
                .collectLatest { (anchor, related, selected, idx) ->
                    if (anchor == null) {
                        maybeFirstFetch(null)
                        return@collectLatest
                    }
                    if (related.size > 1 && selected == null) {
                        observeUnifiedChapters(anchor, related)
                    } else {
                        observeSingleChapters(anchor, selected, idx)
                    }
                }
        }
    }

    /** Resolve the merge group whenever the anchor or the merge prefs change (the author guard is
     *  re-applied each time = metadata healing). Drives [relatedNovelIds]; chips + chapters react. */
    private fun observeMergeGroup() {
        screenModelScope.launchIO {
            combine(
                novelRepo.getByUrlAndSourceAsFlow(novelUrl, sourceId),
                reikaiLibraryPreferences.novelManualMerges.changes(),
                reikaiLibraryPreferences.novelManualUnmerges.changes(),
                reikaiLibraryPreferences.novelAutoMergeSameTitle.changes(),
                reikaiLibraryPreferences.novelAutoMergeRequireAuthor.changes(),
            ) { anchor, _, _, _, _ -> anchor }
                .collectLatest { anchor ->
                    if (anchor == null) return@collectLatest
                    anchorNovelId = anchor.id
                    relatedNovelIds.value = mergeManager.computeRelatedNovelIds(anchor.id, anchor.title, anchor.author)
                }
        }
    }

    /** Resolve each grouped source + build the switcher chips whenever the group changes. */
    private fun observeMergeSourceChips() {
        screenModelScope.launchIO {
            relatedNovelIds.collectLatest { ids ->
                if (ids.size <= 1) {
                    siblingSources.value = emptyMap()
                    mergeChips.value = emptyList()
                    return@collectLatest
                }
                runCatching { installer.ensureLoaded() }
                val resolved = HashMap<Long, NovelSource>()
                val chips = mutableListOf<NovelMergeSourceInfo>()
                for (id in ids) {
                    val novel = novelRepo.getById(id) ?: continue
                    val src = sourceManager.get(novel.source)
                    if (src != null) resolved[id] = src
                    chips += NovelMergeSourceInfo(id, src?.name ?: novel.source, id == anchorNovelId)
                }
                // Sources first, then chips: the chips change re-emits the chapter combine, which reads
                // the now-populated siblingSources for the unified ranking.
                siblingSources.value = resolved
                mergeChips.value = chips
            }
        }
    }

    /** Unified ("All") view: pool every grouped source's chapters into one aggregated, reading-ordered
     *  list (no pagination, pages don't align across sources). Each chapter keeps its own novelId. */
    private suspend fun observeUnifiedChapters(anchor: Novel, related: LongArray) {
        val flows = related.map { id -> chapterRepo.getByNovelIdAsFlow(id).map { id to it } }
        combine(flows) { pairs -> pairs.toMap() }.collectLatest { byNovel ->
            val sources = siblingSources.value
            val sourceIdByNovel = byNovel.keys.associateWith { id -> sources[id]?.id.orEmpty() }
            val aggregated = NovelChapterAggregation.aggregate(
                byNovel,
                sourceIdByNovel,
                reikaiLibraryPreferences.preferredNovelSources.get(),
            )
            rebuildLoaded(anchor, anchor, restampReadingOrder(aggregated), emptyList(), 0)
        }
    }

    /** Single-source view: the anchor (non-merged or its own chip) or a selected sibling, with that
     *  novel's own per-page lazy list. Auto-fetch only runs for the anchor (its [source] is resolved);
     *  a selected sibling shows what's stored until a refresh-all (slice D) fills it. */
    private suspend fun observeSingleChapters(anchor: Novel, selected: Long?, idx: Int) {
        val isAnchorView = selected == null || selected == anchor.id
        val viewNovel = if (isAnchorView) anchor else (novelRepo.getById(selected!!) ?: anchor)
        val pages = computePages(viewNovel)
        if (pages.isNotEmpty() && idx >= pages.size) {
            pageIndex.value = 0
            return
        }
        val pageKey = pages.getOrNull(idx)
        val chapterFlow = if (pageKey == null) {
            chapterRepo.getByNovelIdAsFlow(viewNovel.id)
        } else {
            chapterRepo.getByNovelIdAndPageAsFlow(viewNovel.id, pageKey)
        }
        chapterFlow.collectLatest { chapters ->
            rebuildLoaded(anchor, viewNovel, chapters, pages, idx)
            if (chapters.isEmpty() && isAnchorView) {
                if (pageKey == null) maybeFirstFetch(viewNovel) else maybeFetchPage(viewNovel, pageKey)
            }
        }
    }

    /** Page keys for the selector: "1".."N" for a `parsePage` source, distinct volume labels for a
     *  label-grouped one, or empty (single unpaged list, no selector). */
    private suspend fun computePages(novel: Novel): List<String> = when {
        novel.totalPages > 1L -> (1..novel.totalPages).map { it.toString() }
        else -> chapterRepo.getDistinctPages(novel.id).takeIf { it.size > 1 } ?: emptyList()
    }

    /** Build [NovelDetailsState.Loaded] from the [anchor] (identity, favorite, chapter-view flags) and
     *  the [viewNovel] whose metadata + source the header shows (== anchor for the unified view, the
     *  selected sibling otherwise). Sort/filter always follow the anchor's flags. */
    private fun rebuildLoaded(
        anchor: Novel,
        viewNovel: Novel,
        chapters: List<NovelChapter>,
        pages: List<String>,
        pageIndex: Int,
    ) {
        val hidden = hiddenChaptersPref.get()
        val showHidden = showHiddenFlow.value
        val hasHiddenChapters = hidden.isNotEmpty() && chapters.any { hiddenKey(it) in hidden }
        // Drop hidden chapters from the list (and the resume target / reader order, which feed off it)
        // unless the user is temporarily showing them.
        val visible = if (showHidden || hidden.isEmpty()) chapters else chapters.filterNot { hiddenKey(it) in hidden }
        val display = visible.sortedAndFiltered(anchor, novelPreferences)
        val resume = visible.sortedBy { it.sourceOrder }.firstOrNull { !it.read }
        // When showing hidden, mark which displayed rows are hidden (dimmed + drives Hide/Unhide).
        val hiddenChapterIds = if (showHidden) display.filter { hiddenKey(it) in hidden }.mapTo(HashSet()) { it.id } else emptySet()
        val viewSource = siblingSources.value[viewNovel.id]
        mutableState.update { prev ->
            val loaded = prev as? NovelDetailsState.Loaded
            NovelDetailsState.Loaded(
                novel = anchor,
                displayNovel = viewNovel,
                chapters = display,
                showHidden = showHidden,
                hiddenChapterIds = hiddenChapterIds,
                hasHiddenChapters = hasHiddenChapters,
                pages = pages,
                pageIndex = if (pages.isEmpty()) 0 else pageIndex.coerceIn(0, pages.lastIndex),
                isPageLoading = loaded?.isPageLoading ?: false,
                isRefreshing = loaded?.isRefreshing ?: false,
                downloadStates = loaded?.downloadStates.orEmpty(),
                trackingCount = currentTrackingCount,
                dialog = loaded?.dialog,
                selection = loaded?.selection.orEmpty().filterTo(HashSet()) { id -> chapters.any { it.id == id } },
                resumeChapter = resume,
                hasStarted = chapters.any { it.read },
                seedColor = loaded?.seedColor,
                sourceName = viewSource?.name ?: source?.name ?: loaded?.sourceName ?: sourceId,
                sourceUrl = viewSource?.site ?: source?.site ?: loaded?.sourceUrl,
                novelWebUrl = (viewSource ?: source)?.webUrl(viewNovel.url) ?: loaded?.novelWebUrl,
                sorting = anchor.effectiveSorting(novelPreferences),
                sortDescending = anchor.effectiveSortDescending(novelPreferences),
                readFilter = anchor.effectiveReadFilter(novelPreferences),
                bookmarkedFilter = anchor.effectiveBookmarkedFilter(novelPreferences),
                downloadedFilter = anchor.effectiveDownloadedFilter(novelPreferences),
                hideChapterTitles = anchor.effectiveHideChapterTitles(novelPreferences),
                mergeSources = mergeChips.value,
                selectedSourceNovelId = selectedSourceNovelId.value,
                chapterSwipeStartAction = libraryPreferences.swipeToStartAction.get(),
                chapterSwipeEndAction = libraryPreferences.swipeToEndAction.get(),
            )
        }
        updateSeedColor(viewNovel)
    }

    /** Extract the cover's vibrant color once and seed the header tint. Keyed by `-novel.id` so the
     *  shared (manga) color cache never collides a novel with a same-id manga. */
    private fun updateSeedColor(novel: Novel) {
        if (seedExtracted || !uiPreferences.themeCoverBased.get()) return
        val url = novel.thumbnailUrl?.takeIf { it.isNotBlank() } ?: return
        if (novel.id <= 0L) return
        seedExtracted = true
        // Color cache keyed by the negated id so a novel never collides with a same-id manga's color.
        val cover = MangaCover(
            mangaId = -novel.id,
            sourceId = 0L,
            isMangaFavorite = false,
            url = url,
            lastModified = novel.coverLastModified,
        )
        cover.vibrantCoverColor?.let { color ->
            mutableState.update { (it as? NovelDetailsState.Loaded)?.copy(seedColor = Color(color)) ?: it }
            return
        }
        screenModelScope.launchIO {
            // Load through NovelCoverFetcher (it sends the site Referer some LN cover hosts require) so a
            // non-library novel opened from browsing still tints on first open. Mirrors the manga re-extract.
            val request = ImageRequest.Builder(context)
                .data(
                    NovelCover(
                        url = url,
                        site = source?.site,
                        isNovelFavorite = novel.favorite,
                        lastModified = novel.coverLastModified,
                        novelId = novel.id,
                    ),
                )
                .allowHardware(false) // Palette can't read hardware bitmaps
                .build()
            val bitmap = context.imageLoader.execute(request).image
                ?.asDrawable(context.resources)
                ?.getBitmapOrNull() ?: return@launchIO
            val color = Palette.from(bitmap).generate().getBestColor() ?: return@launchIO
            cover.vibrantCoverColor = color
            mutableState.update { (it as? NovelDetailsState.Loaded)?.copy(seedColor = Color(color)) ?: it }
        }
    }

    private fun maybeFirstFetch(existing: Novel?) {
        if (firstFetchTried) return
        val src = source ?: return // defer until resolveSource sets it
        firstFetchTried = true
        screenModelScope.launchIO {
            runCatching { fetchAndSync(src, existing) }.onFailure { e ->
                if (state.value !is NovelDetailsState.Loaded) {
                    mutableState.value = NovelDetailsState.Failed(e.message ?: "Failed to load novel")
                }
            }
        }
    }

    /** parseNovel + persist metadata (edit-lock + blank safe) + sync the first page's chapters. The
     *  reactive flow then re-emits the updated novel/chapter list. A novel opened from Browse is
     *  inserted non-favorite. Returns the persisted novel (carries the refreshed `totalPages`). */
    private suspend fun fetchAndSync(src: NovelSource, existing: Novel?): Novel? {
        val sourceNovel = src.parseNovel(existing?.url ?: novelUrl)
        val target = if (existing != null) {
            val parsed = sourceNovel.toNovel(sourceId = src.id, favorite = existing.favorite)
            val merged = mergeRefreshedNovel(existing, parsed)
            if (merged != existing) novelRepo.update(merged)
            merged
        } else {
            // Non-favorite shadow row so a browse-opened novel is viewable without being silently
            // added; insertOrGet reuses a concurrently-created row instead of duplicating.
            novelRepo.insertOrGet(sourceNovel.toNovel(sourceId = src.id, favorite = false)) ?: return null
        }
        val chapters = sourceNovel.chapters.orEmpty()
        if (chapters.isNotEmpty()) {
            // A paged source's first page is page "1"; tag it so the page-"1" query finds these rows.
            val pageTag = if (sourceNovel.totalPages > 1) "1" else null
            syncChaptersWithNovelSource(chapters, target, chapterRepo, novelRepo, database, page = pageTag)
        }
        return target
    }

    /** Lazily fetch a paged source's page when it has no stored rows yet. Skipped while a filter is
     *  active (0 rows may just mean the filter hid them, not that the page is unfetched) and once a
     *  page has been tried (an empty page must not re-fetch on every emission). */
    private fun maybeFetchPage(novel: Novel, pageKey: String) {
        val src = source ?: return
        val loaded = state.value as? NovelDetailsState.Loaded
        if (loaded != null && (loaded.readFilter != 0L || loaded.bookmarkedFilter != 0L || loaded.downloadedFilter != 0L)) return
        if (!triedPages.add(pageKey)) return
        screenModelScope.launchIO {
            mutableState.update { (it as? NovelDetailsState.Loaded)?.copy(isPageLoading = true) ?: it }
            try {
                src.parsePage(novel.url, pageKey)?.chapters?.takeIf { it.isNotEmpty() }?.let {
                    syncChaptersWithNovelSource(it, novel, chapterRepo, novelRepo, database, page = pageKey)
                }
            } catch (_: Throwable) {
            } finally {
                mutableState.update { (it as? NovelDetailsState.Loaded)?.copy(isPageLoading = false) ?: it }
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

    fun showPageSelectorDialog() = updateLoaded { it.copy(dialog = NovelDetailsDialog.PageSelector) }

    // --- Merge: source switcher + split ---

    /** Switch the chapter view between the unified list (null) and a single grouped source's list. */
    fun selectSource(novelId: Long?) {
        if (selectedSourceNovelId.value == novelId) return
        clearSelection()
        pageIndex.value = 0
        selectedSourceNovelId.value = novelId
    }

    fun showManageSourcesDialog() {
        screenModelScope.launchIO {
            val loaded = state.value as? NovelDetailsState.Loaded ?: return@launchIO
            if (loaded.mergeSources.size <= 1) return@launchIO
            // Per-source chapter counts (the coverage hint), resolved on open so the user can see which
            // source is most complete before splitting.
            val withCounts = loaded.mergeSources.map { it.copy(chapterCount = chapterRepo.getByNovelId(it.novelId).size) }
            updateLoaded { it.copy(dialog = NovelDetailsDialog.ManageSources(withCounts)) }
        }
    }

    /** Split the given grouped sources out (subset split; full dissolve if they cover the group), with
     *  an Undo that restores the prior merge prefs + group. */
    fun splitSources(targetIds: List<Long>) {
        if (targetIds.isEmpty()) return
        val prevMerges = reikaiLibraryPreferences.novelManualMerges.get()
        val prevUnmerges = reikaiLibraryPreferences.novelManualUnmerges.get()
        val prevRelated = relatedNovelIds.value
        // copy the group's trackers onto each member so a split source keeps them (Active #8).
        // Explicit ids (not a re-resolve), so it's race-free against the synchronous split below.
        screenModelScope.launchIO { propagateNovelTrackerLinks.distribute(prevRelated.toList()) }
        val newIds = mergeManager.splitOrDissolve(prevRelated, targetIds)
        relatedNovelIds.value = if (newIds.isEmpty()) longArrayOf(anchorNovelId) else newIds
        selectedSourceNovelId.value = null
        dismissDialog()
        screenModelScope.launchUI {
            val result = snackbarHostState.showSnackbar(
                message = context.stringResource(MR.strings.merge_sources_split),
                actionLabel = context.stringResource(MR.strings.action_undo),
                duration = SnackbarDuration.Short,
                withDismissAction = true,
            )
            if (result == SnackbarResult.ActionPerformed) {
                reikaiLibraryPreferences.novelManualMerges.set(prevMerges)
                reikaiLibraryPreferences.novelManualUnmerges.set(prevUnmerges)
                relatedNovelIds.value = prevRelated
            }
        }
    }

    /** Split [targetIds] out and unfavorite them, with an Undo that re-favorites + re-groups. */
    fun removeSourcesFromLibrary(targetIds: List<Long>) {
        if (targetIds.isEmpty()) return
        val prevMerges = reikaiLibraryPreferences.novelManualMerges.get()
        val prevUnmerges = reikaiLibraryPreferences.novelManualUnmerges.get()
        val prevRelated = relatedNovelIds.value
        relatedNovelIds.value = mergeManager.removeFromGroup(prevRelated, targetIds)
        selectedSourceNovelId.value = null
        dismissDialog()
        screenModelScope.launchNonCancellable { setFavorites(targetIds, false) }
        screenModelScope.launchUI {
            val result = snackbarHostState.showSnackbar(
                message = context.stringResource(MR.strings.merge_sources_removed),
                actionLabel = context.stringResource(MR.strings.action_undo),
                duration = SnackbarDuration.Short,
                withDismissAction = true,
            )
            if (result == SnackbarResult.ActionPerformed) {
                reikaiLibraryPreferences.novelManualMerges.set(prevMerges)
                reikaiLibraryPreferences.novelManualUnmerges.set(prevUnmerges)
                relatedNovelIds.value = prevRelated
                screenModelScope.launchNonCancellable { setFavorites(targetIds, true) }
            }
        }
    }

    /** Remove the whole merge group from the library at once (Manage Sources "Remove all"). */
    fun removeAllSourcesFromLibrary() {
        removeSourcesFromLibrary(relatedNovelIds.value.toList())
    }

    // Favorite-only (not awaitUpdateFavorite): the merge-source undo restores a removed group, so the
    // original dateAdded must survive instead of being re-stamped.
    private suspend fun setFavorites(ids: List<Long>, favorite: Boolean) {
        ids.forEach { id -> updateNovel.await(NovelUpdate(id = id, favorite = favorite)) }
    }

    /** Renumber sourceOrder over the unified list (ascending by chapter number = reading order) so a
     *  "by source order" sort doesn't interleave sources. Copies; each source's own order is untouched. */
    private fun restampReadingOrder(chapters: List<NovelChapter>): List<NovelChapter> =
        chapters.sortedBy { it.chapterNumber }.mapIndexed { index, chapter -> chapter.copy(sourceOrder = index.toLong()) }

    /** Stale-then-fresh: the cached list stays under a spinner while the sync runs, then the flow
     *  swaps in fresh rows. Read/bookmark are preserved by the sync. Deduped against concurrent runs.
     *
     *  For a paged source: [refreshNovelFromSource] refreshes page 1 + the page count and re-fetches
     *  the previously-last page through any newly-opened ones, and the page the user is viewing is
     *  re-fetched too if the walk didn't already cover it. Bounded, never a full fetch-all. */
    fun refresh() {
        val loaded = state.value as? NovelDetailsState.Loaded ?: return
        val anchorSrc = source ?: return
        if (refreshJob?.isActive == true) return
        refreshJob = screenModelScope.launchIO {
            mutableState.update { (it as? NovelDetailsState.Loaded)?.copy(isRefreshing = true) ?: it }
            try {
                // Refresh the anchor first (its refreshed novel drives the viewed-page fix below), then
                // every other grouped source so the unified list picks up new chapters everywhere.
                val anchorUpdated = refreshNovel(anchorSrc, loaded.novel)
                for (id in relatedNovelIds.value) {
                    if (id == loaded.novel.id) continue
                    val novel = novelRepo.getById(id) ?: continue
                    val src = siblingSources.value[id] ?: continue
                    refreshNovel(src, novel)
                }
                // Force-refresh the viewed page when viewing the anchor's own (paged) list and the walk
                // skipped it (a middle page); the unified view has no pages so this is a no-op there.
                if (loaded.selectedSourceNovelId == null || loaded.selectedSourceNovelId == loaded.novel.id) {
                    forceRefreshViewedPage(loaded, anchorUpdated, anchorSrc)
                }
            } finally {
                mutableState.update { (it as? NovelDetailsState.Loaded)?.copy(isRefreshing = false) ?: it }
            }
        }
    }

    /** Shared favorite-refresh: parseNovel + merge + sync page 1 + walk newly-opened pages. Bounded,
     *  never a full fetch-all. Keeps the current novel on failure; returns the refreshed novel. */
    private suspend fun refreshNovel(src: NovelSource, novel: Novel): Novel =
        runCatching { refreshNovelFromSource(novel, src, chapterRepo, novelRepo, database) }.getOrNull() ?: novel

    private suspend fun forceRefreshViewedPage(loaded: NovelDetailsState.Loaded, updated: Novel, src: NovelSource) {
        val newTotalPages = updated.totalPages
        if (newTotalPages <= 1L) return
        val walkFrom = maxOf(2L, loaded.novel.totalPages)
        val curPage = loaded.pages.getOrNull(loaded.pageIndex)?.toLongOrNull() ?: return
        if (curPage > 1L && curPage !in walkFrom..newTotalPages) {
            val key = curPage.toString()
            runCatching {
                src.parsePage(updated.url, key)?.chapters?.takeIf { it.isNotEmpty() }?.let {
                    syncChaptersWithNovelSource(it, updated, chapterRepo, novelRepo, database, page = key)
                }
            }
        }
    }

    // --- Favorite / categories ---

    fun toggleFavorite() {
        screenModelScope.launchIO {
            val novel = (state.value as? NovelDetailsState.Loaded)?.novel ?: return@launchIO
            if (!novel.favorite) {
                updateNovel.awaitUpdateFavorite(novel.id, favorite = true)
                val categories = getNovelCategories.await().filter { it.id > 0L }
                if (categories.isNotEmpty()) {
                    val current = getNovelCategories.awaitByNovelId(novel.id).map { it.id }.toSet()
                    updateLoaded { it.copy(dialog = NovelDetailsDialog.ChangeCategory(categories, current)) }
                }
            } else {
                updateNovel.awaitUpdateFavorite(novel.id, favorite = false)
            }
        }
    }

    fun showChangeCategoryDialog() {
        screenModelScope.launchIO {
            val novel = (state.value as? NovelDetailsState.Loaded)?.novel ?: return@launchIO
            val categories = getNovelCategories.await().filter { it.id > 0L }
            if (categories.isEmpty()) return@launchIO
            val current = getNovelCategories.awaitByNovelId(novel.id).map { it.id }.toSet()
            updateLoaded { it.copy(dialog = NovelDetailsDialog.ChangeCategory(categories, current)) }
        }
    }

    fun applyCategories(categoryIds: List<Long>) {
        screenModelScope.launchIO {
            val novel = (state.value as? NovelDetailsState.Loaded)?.novel ?: return@launchIO
            setNovelCategories.await(novel.id, categoryIds)
            dismissDialog()
        }
    }

    // --- Edit info ---

    fun showEditNovelInfoDialog() {
        val n = (state.value as? NovelDetailsState.Loaded)?.displayNovel ?: return
        updateLoaded {
            it.copy(
                dialog = NovelDetailsDialog.EditInfo(
                    title = n.title,
                    author = n.author.orEmpty(),
                    artist = n.artist.orEmpty(),
                    description = n.description.orEmpty(),
                    genre = n.genre?.joinToString(", ").orEmpty(),
                    status = n.status,
                ),
            )
        }
    }

    /** Apply Edit-info. A field differing from the stored value sets its lock bit (so it survives a
     *  refresh); a status of UNKNOWN clears the lock. Title needs no lock (refresh never touches it). */
    fun updateNovelInfo(
        title: String,
        author: String,
        artist: String,
        description: String,
        genre: String,
        status: Long,
    ) {
        screenModelScope.launchIO {
            val n = (state.value as? NovelDetailsState.Loaded)?.novel ?: return@launchIO
            var flags = n.editedFlags
            flags = setEditedFlag(flags, NovelEditFlags.AUTHOR, author != n.author.orEmpty())
            flags = setEditedFlag(flags, NovelEditFlags.ARTIST, artist != n.artist.orEmpty())
            flags = setEditedFlag(flags, NovelEditFlags.DESCRIPTION, description != n.description.orEmpty())
            flags = setEditedFlag(flags, NovelEditFlags.GENRES, genre != n.genre?.joinToString(", ").orEmpty())
            flags =
                setEditedFlag(
                    flags,
                    NovelEditFlags.STATUS,
                    status != NovelStatusCode.UNKNOWN.toLong() && status != n.status,
                )
            novelRepo.update(
                n.copy(
                    title = title.ifBlank { n.title },
                    author = author.ifBlank { null },
                    artist = artist.ifBlank { null },
                    description = description.ifBlank { null },
                    genre = genre.split(",").map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { null },
                    status = if (status != NovelStatusCode.UNKNOWN.toLong()) status else n.status,
                    editedFlags = flags,
                ),
            )
            dismissDialog()
        }
    }

    /** Clear every override and re-fetch source values. */
    fun resetNovelInfo() {
        screenModelScope.launchIO {
            val n = (state.value as? NovelDetailsState.Loaded)?.novel ?: return@launchIO
            val cleared = n.copy(editedFlags = 0L)
            novelRepo.update(cleared)
            dismissDialog()
            source?.let { runCatching { fetchAndSync(it, cleared) } }
        }
    }

    // --- Chapter sort / filter / display ---

    fun setSortOrder(sort: Long, descending: Boolean) =
        withLoadedNovel { setNovelChapterFlags.awaitSetSortOrder(it, sort, descending) }

    fun setFilters(read: Long, bookmarked: Long, downloaded: Long) =
        withLoadedNovel { setNovelChapterFlags.awaitSetFilters(it, read, bookmarked, downloaded) }

    fun setHideChapterTitles(hide: Boolean) =
        withLoadedNovel { setNovelChapterFlags.awaitSetHideTitles(it, hide) }

    /** Write the current view as the global chapter-settings default and drop this novel's overrides. */
    fun setChapterSettingsAsDefault() {
        screenModelScope.launchIO {
            val loaded = state.value as? NovelDetailsState.Loaded ?: return@launchIO
            novelPreferences.defaultChapterSortOrder().set(loaded.sorting)
            novelPreferences.defaultChapterSortDescending().set(loaded.sortDescending)
            novelPreferences.defaultChapterHideTitles().set(loaded.hideChapterTitles)
            novelPreferences.defaultChapterFilterUnread().set(loaded.readFilter)
            novelPreferences.defaultChapterFilterBookmarked().set(loaded.bookmarkedFilter)
            novelPreferences.defaultChapterFilterDownloaded().set(loaded.downloadedFilter)
            setNovelChapterFlags.awaitClearLocalOverrides(loaded.novel)
        }
    }

    /** Drop this novel's overrides so the global default applies. */
    fun resetChapterSettings() =
        withLoadedNovel { setNovelChapterFlags.awaitClearLocalOverrides(it) }

    private fun withLoadedNovel(block: suspend (Novel) -> Unit) {
        screenModelScope.launchIO {
            val n = (state.value as? NovelDetailsState.Loaded)?.novel ?: return@launchIO
            block(n)
        }
    }

    fun showChapterSettingsDialog() = updateLoaded { it.copy(dialog = NovelDetailsDialog.ChapterSettings) }

    fun showCoverDialog() = updateLoaded { it.copy(dialog = NovelDetailsDialog.FullCover) }

    // --- Selection + read / bookmark ---

    fun toggleSelection(chapterId: Long, fromLongPress: Boolean) {
        updateLoaded { loaded ->
            val index = loaded.chapters.indexOfFirst { it.id == chapterId }
            if (index < 0) return@updateLoaded loaded
            val sel = loaded.selection.toMutableSet()
            if (fromLongPress && loaded.selection.isNotEmpty() && selectionAnchor[0] >= 0) {
                val from = minOf(selectionAnchor[0], index)
                val to = maxOf(selectionAnchor[0], index)
                for (i in from..to) sel.add(loaded.chapters[i].id)
                selectionAnchor[1] = index
            } else {
                if (chapterId in sel) sel.remove(chapterId) else sel.add(chapterId)
                selectionAnchor[0] = index
                selectionAnchor[1] = index
            }
            loaded.copy(selection = sel)
        }
    }

    fun selectAll() = updateLoaded { it.copy(selection = it.chapters.mapTo(HashSet()) { ch -> ch.id }) }

    fun invertSelection() = updateLoaded { loaded ->
        loaded.copy(selection = loaded.chapters.mapNotNull { it.id.takeIf { id -> id !in loaded.selection } }.toSet())
    }

    fun clearSelection() {
        selectionAnchor[0] = -1
        selectionAnchor[1] = -1
        updateLoaded { it.copy(selection = emptySet()) }
    }

    /** Restore-stable hidden-chapter key: source + chapter url, no local novel id (so it survives a
     *  backup restore). Source resolved per the chapter's own novelId for a merged group, else the
     *  anchor's source. */
    private fun hiddenKey(chapter: NovelChapter): String =
        "${siblingSources.value[chapter.novelId]?.id ?: sourceId}|${chapter.url}"

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

    fun markSelectedRead(read: Boolean) = withSelection { chapters ->
        chapterRepo.setReadBulk(chapters.map { it.id }, read)
        if (read) onMarkedRead(chapters)
    }

    fun bookmarkSelected(bookmark: Boolean) = withSelection { chapters ->
        chapters.forEach { chapterRepo.setBookmark(it.id, bookmark) }
    }

    /** Mark every chapter before the earliest selected one (in source order) read/unread. Spans all
     *  fetched pages (operates on stored rows), not just the page on screen. */
    fun markPreviousRead(read: Boolean) {
        screenModelScope.launchIO {
            val loaded = state.value as? NovelDetailsState.Loaded ?: return@launchIO
            val ascending = chapterRepo.getByNovelId(loaded.novel.id).sortedBy { it.sourceOrder }
            val earliest = ascending.indexOfFirst { it.id in loaded.selection }
            if (earliest > 0) {
                val previous = ascending.subList(0, earliest)
                chapterRepo.setReadBulk(previous.map { it.id }, read)
                if (read) onMarkedRead(previous)
            }
            clearSelection()
        }
    }

    fun markAllRead(read: Boolean) {
        screenModelScope.launchIO {
            val loaded = state.value as? NovelDetailsState.Loaded ?: return@launchIO
            val all = chapterRepo.getByNovelId(loaded.novel.id)
            chapterRepo.setReadBulk(all.map { it.id }, read)
            if (read) onMarkedRead(all)
        }
    }

    fun toggleChapterBookmark(chapter: NovelChapter) {
        screenModelScope.launchIO { chapterRepo.setBookmark(chapter.id, !chapter.bookmark) }
    }

    fun markChapterRead(chapter: NovelChapter, read: Boolean) {
        screenModelScope.launchIO {
            chapterRepo.setReadBulk(listOf(chapter.id), read)
            if (read) onMarkedRead(listOf(chapter))
        }
    }

    /** Run the on-mark-read side effects: push to trackers + delete the downloaded copies when the
     *  "delete after marked as read" pref is on. */
    private fun onMarkedRead(chapters: List<NovelChapter>) {
        autoTrackOnMarkRead(chapters)
        val novelId = (state.value as? NovelDetailsState.Loaded)?.novel?.id ?: return
        screenModelScope.launchIO { deleteNovelChaptersAfterRead.await(novelId, chapters) }
    }

    // novel trackers (Active #8)
    /** True if any tracker is logged in; gates the toolbar action (sheet vs Settings > Tracking). */
    fun hasLoggedInTrackers(): Boolean = trackerManager.loggedInTrackers().isNotEmpty()

    fun showTrackDialog() = updateLoaded { it.copy(dialog = NovelDetailsDialog.TrackSheet) }

    /**
     * Hook 2: after chapters are marked read from the details list, push progress to bound trackers,
     * honouring the AutoTrackState pref (never / always / ask), mirroring [MangaScreenModel.markChaptersRead].
     */
    private fun autoTrackOnMarkRead(chapters: List<NovelChapter>) {
        if (chapters.isEmpty() || trackerManager.loggedInTrackers().isEmpty()) return
        val autoTrackState = trackPreferences.autoUpdateTrackOnMarkRead.get()
        if (autoTrackState == AutoTrackState.NEVER) return
        val novel = (state.value as? NovelDetailsState.Loaded)?.novel ?: return
        val maxChapterNumber = chapters.maxOf { it.chapterNumber }
        screenModelScope.launchIO {
            refreshNovelTracks.await(novel.id)
            val tracks = getNovelTracks.await(novel.id)
            if (tracks.none { maxChapterNumber > it.lastChapterRead }) return@launchIO
            if (autoTrackState == AutoTrackState.ALWAYS) {
                trackNovelChapter.await(context, novel.id, maxChapterNumber)
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
                trackNovelChapter.await(context, novel.id, maxChapterNumber)
            }
        }
    }

    /** Row swipe, dispatched by the configured [LibraryPreferences.ChapterSwipeAction] (mirrors the
     *  manga path's `executeChapterSwipeAction`, with the same download-state to action mapping). */
    fun chapterSwipe(chapter: NovelChapter, action: LibraryPreferences.ChapterSwipeAction) {
        when (action) {
            LibraryPreferences.ChapterSwipeAction.ToggleRead -> markChapterRead(chapter, !chapter.read)
            LibraryPreferences.ChapterSwipeAction.ToggleBookmark -> toggleChapterBookmark(chapter)
            LibraryPreferences.ChapterSwipeAction.Download -> {
                val downloadState = (state.value as? NovelDetailsState.Loaded)?.downloadStates?.get(chapter.id)
                    ?: if (chapter.isDownloaded) Download.State.DOWNLOADED else Download.State.NOT_DOWNLOADED
                val downloadAction = when (downloadState) {
                    Download.State.NOT_DOWNLOADED, Download.State.ERROR -> ChapterDownloadAction.START_NOW
                    Download.State.QUEUE, Download.State.DOWNLOADING -> ChapterDownloadAction.CANCEL
                    Download.State.DOWNLOADED -> ChapterDownloadAction.DELETE
                }
                onChapterDownloadAction(chapter, downloadAction)
            }
            LibraryPreferences.ChapterSwipeAction.Disabled -> {}
        }
    }

    private inline fun withSelection(crossinline block: suspend (List<NovelChapter>) -> Unit) {
        screenModelScope.launchIO {
            val loaded = state.value as? NovelDetailsState.Loaded ?: return@launchIO
            val chapters = loaded.chapters.filter { it.id in loaded.selection }
            if (chapters.isNotEmpty()) block(chapters)
            clearSelection()
        }
    }

    // --- Downloads ---

    fun onChapterDownloadAction(chapter: NovelChapter, action: ChapterDownloadAction) {
        when (action) {
            ChapterDownloadAction.START -> downloadManager.downloadChapters(listOf(chapter))
            ChapterDownloadAction.START_NOW -> {
                downloadManager.downloadChapters(listOf(chapter))
                downloadManager.startDownloadNow(chapter.id)
            }
            ChapterDownloadAction.CANCEL -> downloadManager.cancelDownloads(listOf(chapter.id))
            ChapterDownloadAction.DELETE -> downloadManager.deleteChapters(listOf(chapter))
        }
    }

    fun downloadSelected() = withSelection { downloadManager.downloadChapters(it) }

    /** Toolbar download dropdown. Operates on the full stored chapter list (all fetched pages), not the
     *  page on screen, the same way [markAllRead] does. Selection logic is shared with the library. */
    fun runDownloadAction(action: DownloadAction) {
        screenModelScope.launchIO {
            val loaded = state.value as? NovelDetailsState.Loaded ?: return@launchIO
            val targets = selectChaptersForDownloadAction(chapterRepo.getByNovelId(loaded.novel.id), action)
            if (targets.isNotEmpty()) downloadManager.downloadChapters(targets)
        }
    }

    fun deleteSelected() = withSelection { downloadManager.deleteChapters(it) }

    private fun NovelDownload.State.toDownloadState(): Download.State = when (this) {
        NovelDownload.State.QUEUE -> Download.State.QUEUE
        NovelDownload.State.DOWNLOADING -> Download.State.DOWNLOADING
        NovelDownload.State.ERROR -> Download.State.ERROR
    }

    fun dismissDialog() = updateLoaded { it.copy(dialog = null) }

    private inline fun updateLoaded(crossinline transform: (NovelDetailsState.Loaded) -> NovelDetailsState.Loaded) {
        mutableState.update { (it as? NovelDetailsState.Loaded)?.let(transform) ?: it }
    }
}

sealed interface NovelDetailsState {
    data object Loading : NovelDetailsState
    data class Failed(val message: String) : NovelDetailsState

    @Immutable
    data class Loaded(
        /** Identity + favorite key. */
        val novel: Novel,
        /** Metadata shown in the header. Equals [novel] for a single source; the S8 merge seam
         *  repoints it at the selected source. */
        val displayNovel: Novel,
        val chapters: List<NovelChapter>,
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
        /** A lazy page fetch is in flight. */
        val isPageLoading: Boolean = false,
        val isRefreshing: Boolean = false,
        /** Live download-queue states by chapter id (queued/downloading/error only; a finished
         *  download is signalled by the chapter row's `isDownloaded` flag). */
        val downloadStates: Map<Long, Download.State> = emptyMap(),
        /** Bound trackers on a logged-in service; drives the details action-row Tracking button (Active #8). */
        val trackingCount: Int = 0,
        val dialog: NovelDetailsDialog? = null,
        val selection: Set<Long> = emptySet(),
        val resumeChapter: NovelChapter? = null,
        val hasStarted: Boolean = false,
        /** Cover-derived header tint; null when off or not yet extracted. */
        val seedColor: Color? = null,
        /** Resolved source name + homepage. [sourceUrl] is the source SITE (used as the cover-load
         *  Referer); [novelWebUrl] is this novel's own page (site + path), for WebView and Share. */
        val sourceName: String = "",
        val sourceUrl: String? = null,
        val novelWebUrl: String? = null,
        // Resolved (per-novel or global-default) chapter view settings.
        val sorting: Long = NovelChapterFlags.SORTING_SOURCE,
        val sortDescending: Boolean = true,
        val readFilter: Long = 0L,
        val bookmarkedFilter: Long = 0L,
        val downloadedFilter: Long = 0L,
        val hideChapterTitles: Boolean = false,
        /** Source-switcher chips for a merged group (empty/single = not merged, chips hidden). */
        val mergeSources: List<NovelMergeSourceInfo> = emptyList(),
        /** The selected source chip's novelId; null = the unified ("All") view. */
        val selectedSourceNovelId: Long? = null,
        /** Chapter swipe actions, read from the shared (manga) library prefs so novels match manga. */
        val chapterSwipeStartAction: LibraryPreferences.ChapterSwipeAction = LibraryPreferences.ChapterSwipeAction.Disabled,
        val chapterSwipeEndAction: LibraryPreferences.ChapterSwipeAction = LibraryPreferences.ChapterSwipeAction.Disabled,
    ) : NovelDetailsState {
        val selectionMode: Boolean get() = selection.isNotEmpty()

        /** More than one page/volume to choose between, so the page selector is shown. */
        val isPaged: Boolean get() = pages.size > 1
    }
}

/** One grouped source of a merged novel. [isCurrent] marks the opened (anchor) source; [chapterCount]
 *  is the coverage hint shown in the Manage-sources dialog (0 in the lighter chip list). */
@Immutable
data class NovelMergeSourceInfo(
    val novelId: Long,
    val sourceName: String,
    val isCurrent: Boolean,
    val chapterCount: Int = 0,
)

sealed interface NovelDetailsDialog {
    data class ChangeCategory(
        val allCategories: List<NovelCategory>,
        val currentCategoryIds: Set<Long>,
    ) : NovelDetailsDialog

    data class EditInfo(
        val title: String,
        val author: String,
        val artist: String,
        val description: String,
        val genre: String,
        val status: Long,
    ) : NovelDetailsDialog

    data object ChapterSettings : NovelDetailsDialog
    data object PageSelector : NovelDetailsDialog
    data object FullCover : NovelDetailsDialog
    data class ManageSources(val sources: List<NovelMergeSourceInfo>) : NovelDetailsDialog

    // novel trackers (Active #8). Rendered as a NavigatorAdaptiveSheet, mirroring Mihon's manga sheet.
    data object TrackSheet : NovelDetailsDialog
}
