package eu.kanade.tachiyomi.ui.manga

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.net.toFile
import co.touchlab.kermit.Logger
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.hippo.unifile.UniFile
import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.database.models.Category
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.History
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.database.models.bookmarkedFilter
import eu.kanade.tachiyomi.data.database.models.chapterOrder
import eu.kanade.tachiyomi.data.database.models.copyFrom
import eu.kanade.tachiyomi.data.database.models.create
import eu.kanade.tachiyomi.data.database.models.downloadedFilter
import eu.kanade.tachiyomi.data.database.models.prepareCoverUpdate
import eu.kanade.tachiyomi.data.database.models.readFilter
import eu.kanade.tachiyomi.data.database.models.removeCover
import eu.kanade.tachiyomi.data.database.models.sortDescending
import eu.kanade.tachiyomi.data.database.models.updateCoverLastModified
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.download.model.DownloadQueue
import eu.kanade.tachiyomi.data.library.CustomMangaManager
import eu.kanade.tachiyomi.data.library.LibraryUpdateJob
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.data.recommendation.RECOMMENDS_SOURCE
import eu.kanade.tachiyomi.data.recommendation.RecommendationRanker
import eu.kanade.tachiyomi.data.recommendation.RecommendationsFetcher
import eu.kanade.tachiyomi.data.recommendation.TasteCandidateFetcher
import eu.kanade.tachiyomi.data.track.EnhancedTrackService
import eu.kanade.tachiyomi.data.track.TrackManager
import eu.kanade.tachiyomi.data.track.TrackService
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.NetworkPreferences
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.LocalSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.SourceNotFoundException
import eu.kanade.tachiyomi.source.getExtension
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.base.presenter.BaseCoroutinePresenter
import eu.kanade.tachiyomi.ui.manga.chapter.ChapterItem
import eu.kanade.tachiyomi.ui.manga.related.RelatedMangaCandidate
import eu.kanade.tachiyomi.ui.manga.track.TrackItem
import eu.kanade.tachiyomi.ui.manga.track.TrackingBottomSheet
import eu.kanade.tachiyomi.ui.security.SecureActivityDelegate
import eu.kanade.tachiyomi.util.chapter.ChapterFilter
import eu.kanade.tachiyomi.util.chapter.ChapterSort
import eu.kanade.tachiyomi.util.chapter.ChapterUtil
import eu.kanade.tachiyomi.util.chapter.syncChaptersWithSource
import eu.kanade.tachiyomi.util.chapter.syncChaptersWithTrackServiceTwoWay
import eu.kanade.tachiyomi.util.chapter.updateTrackChapterMarkedAsRead
import eu.kanade.tachiyomi.util.isLocal
import eu.kanade.tachiyomi.util.lang.trimOrNull
import eu.kanade.tachiyomi.util.manga.MangaShortcutManager
import eu.kanade.tachiyomi.util.manga.MangaUtil
import eu.kanade.tachiyomi.util.shouldDownloadNewChapters
import eu.kanade.tachiyomi.util.storage.DiskUtil
import eu.kanade.tachiyomi.util.system.ImageUtil
import eu.kanade.tachiyomi.util.system.e
import eu.kanade.tachiyomi.util.system.launchIO
import eu.kanade.tachiyomi.util.system.launchNonCancellableIO
import eu.kanade.tachiyomi.util.system.launchNow
import eu.kanade.tachiyomi.util.system.launchUI
import eu.kanade.tachiyomi.util.system.withIOContext
import eu.kanade.tachiyomi.util.system.withUIContext
import eu.kanade.tachiyomi.widget.TriStateCheckBox
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import yokai.domain.category.interactor.GetCategories
import yokai.domain.chapter.interactor.GetAvailableScanlators
import yokai.domain.chapter.interactor.GetChapter
import yokai.domain.chapter.interactor.UpdateChapter
import yokai.domain.history.interactor.GetHistory
import yokai.domain.library.custom.model.CustomMangaInfo
import yokai.domain.library.taste.interactor.ComputeTasteProfile
import yokai.domain.library.taste.interactor.GetTrackedEntries
import yokai.domain.library.taste.model.TasteProfile
import yokai.domain.manga.interactor.GetManga
import yokai.domain.manga.interactor.InsertManga
import yokai.domain.manga.interactor.UpdateManga
import yokai.domain.manga.models.MangaUpdate
import yokai.domain.manga.models.cover
import yokai.domain.storage.StorageManager
import yokai.domain.track.interactor.DeleteTrack
import yokai.domain.track.interactor.GetTrack
import yokai.domain.track.interactor.InsertTrack
import yokai.i18n.MR
import yokai.util.lang.getString

class MangaDetailsPresenter(
    val mangaId: Long,
    val sourceManager: SourceManager = Injekt.get(),
    val preferences: PreferencesHelper = Injekt.get(),
    val coverCache: CoverCache = Injekt.get(),
    private val downloadManager: DownloadManager = Injekt.get(),
    private val chapterFilter: ChapterFilter = Injekt.get(),
    private val storageManager: StorageManager = Injekt.get(),
    relatedMangaIds: LongArray = LongArray(0),
) : BaseCoroutinePresenter<MangaDetailsController>(),
    DownloadQueue.Listener {
    private val getAvailableScanlators: GetAvailableScanlators by injectLazy()
    private val getCategories: GetCategories by injectLazy()
    private val getChapter: GetChapter by injectLazy()
    private val getManga: GetManga by injectLazy()
    private val insertManga: InsertManga by injectLazy()
    private val updateChapter: UpdateChapter by injectLazy()
    private val updateManga: UpdateManga by injectLazy()
    private val deleteTrack: DeleteTrack by injectLazy()
    private val getTrack: GetTrack by injectLazy()
    private val insertTrack: InsertTrack by injectLazy()
    private val getHistory: GetHistory by injectLazy()

    private val networkPreferences: NetworkPreferences by injectLazy()

    /**
     * IDs of all library entries grouped with this manga (auto-grouped same-title or manually
     * merged). Mutable so the controller can refresh on attach — see [refreshRelatedMangaIds].
     */
    var relatedMangaIds: LongArray = relatedMangaIds
        private set

    /**
     * Source-suggested mangas shown in the carousel below the description. Populated lazily on
     * first attach via [fetchRelatedMangasFromSource] and never refetched for the lifetime of
     * this presenter (matches Komikku's per-instance cache shape).
     *
     * Each entry carries the source id it should be opened with on tap — the current source for
     * source-native / keyword suggestions, [eu.kanade.tachiyomi.data.recommendation.RECOMMENDS_SOURCE]
     * for tracker recommendations whose URL doesn't belong to any installed extension.
     */
    var relatedMangas: List<RelatedMangaCandidate> = emptyList()
        private set
    var relatedMangasLoading: Boolean = false
        private set
    private var relatedMangasFetched: Boolean = false
    private val relatedMangasMutex = Mutex()

//    private val currentMangaInternal: MutableStateFlow<Manga?> = MutableStateFlow(null)
//    val currentManga get() = currentMangaInternal.asStateFlow()

    lateinit var manga: Manga
    fun isMangaLateInitInitialized() = ::manga.isInitialized

    private val customMangaManager: CustomMangaManager by injectLazy()
    private val mangaShortcutManager: MangaShortcutManager by injectLazy()

    val source: Source by lazy { sourceManager.getOrStub(manga.source) }

    private lateinit var chapterSort: ChapterSort
    val extension by lazy { (source as? HttpSource)?.getExtension() }

    var isLockedFromSearch = false
    var hasRequested = false
    var isLoading = false
    var scrollType = 0

    private val loggedServices by lazy { Injekt.get<TrackManager>().services.filter { it.isLogged } }
    private var tracks = emptyList<Track>()

    var trackList: List<TrackItem> = emptyList()

    var chapters: List<ChapterItem> = emptyList()
        private set

    var allChapters: List<ChapterItem> = emptyList()
        private set

    var allHistory: List<History> = emptyList()
        private set

    val headerItem: MangaHeaderItem by lazy { MangaHeaderItem(mangaId, view?.fromCatalogue == true)}
    var tabletChapterHeaderItem: MangaHeaderItem? = null
        get() {
            when (view?.isTablet) {
                true -> if (field == null) {
                    field = MangaHeaderItem(mangaId, false).apply {
                        isChapterHeader = true
                    }
                }
                else -> if (field != null) {
                    field = null
                }
            }
            return field
        }
        private set

    var allChapterScanlators: Set<String> = emptySet()

    override val progressJobs: MutableMap<Download, Job> = mutableMapOf()
    override val queueListenerScope get() = presenterScope

    override fun onCreate() {
        val controller = view ?: return

        isLockedFromSearch = controller.shouldLockIfNeeded && SecureActivityDelegate.shouldBeLocked()
        if (!::manga.isInitialized) runBlocking { refreshMangaFromDb() }
        syncData()

        presenterScope.launchUI {
            downloadManager.statusFlow()
                .filter { it.manga.id == mangaId }
                .catch { error -> Logger.e(error) }
                .collect(::onStatusChange)
        }
        presenterScope.launchUI {
            downloadManager.progressFlow()
                .filter { it.manga.id == mangaId }
                .catch { error -> Logger.e(error) }
                .collect(::onQueueUpdate)
        }
        presenterScope.launchIO {
            downloadManager.queueState.collectLatest(::onQueueUpdate)
        }

        runBlocking {
            tracks = getTrack.awaitAllByMangaId(mangaId)
        }
    }

    /**
     * onCreate but executed after UI layout is ready otherwise it'd only show blank screen
     */
    fun onCreateLate() {
        val controller = view ?: return

        LibraryUpdateJob.updateFlow
            .filter { it == mangaId }
            .onEach { onUpdateManga() }
            .launchIn(presenterScope)

        val fetchMangaNeeded = !manga.initialized
        val fetchChaptersNeeded = runBlocking { getChaptersNow() }.isEmpty()

        presenterScope.launch {
            isLoading = true
            withUIContext {
                controller.updateHeader()
            }
            val tasks = listOf(
                async { if (fetchMangaNeeded) fetchMangaFromSource() },
                async { if (fetchChaptersNeeded) fetchChaptersFromSource(false) },
            )
            tasks.awaitAll()
            isLoading = false
            withUIContext {
                controller.updateChapters()
            }

            setTrackItems()
        }

        refreshTracking(false)
    }

    fun fetchChapters(andTracking: Boolean = true) {
        presenterScope.launch {
            getChapters()
            if (andTracking) fetchTracks()
            withUIContext { view?.updateChapters() }
            getHistory()
        }
    }

    fun setCurrentManga(manga: Manga?) {
//        currentMangaInternal.update { manga }
        this.manga = manga!!
    }

    // TODO: Use flow to "sync" data instead
    fun syncData() {
        chapterSort = ChapterSort(manga, chapterFilter, preferences)
        headerItem.apply {
            isTablet = view?.isTablet == true
            isLocked = isLockedFromSearch
        }
    }

    suspend fun getChaptersNow(): List<ChapterItem> {
        getChapters()
        return chapters
    }

    private suspend fun getChapters(queue: List<Download> = downloadManager.queueState.value) {
        val chapters = getChapter.awaitAll(mangaId, isScanlatorFiltered()).map { it.toModel() }
        allChapters = if (!isScanlatorFiltered()) chapters else getChapter.awaitAll(mangaId, false).map { it.toModel() }

        // Find downloaded chapters
        setDownloadedChapters(chapters, queue)
        allChapterScanlators = allChapters.mapNotNull { it.chapter.scanlator }.toSet()

        this.chapters = applyChapterFilters(chapters)
    }

    private suspend fun getHistory() {
        allHistory = getHistory.awaitAllByMangaId(mangaId)
    }

    /**
     * Finds and assigns the list of downloaded chapters.
     *
     * @param chapters the list of chapter from the database.
     */
    private fun setDownloadedChapters(chapters: List<ChapterItem>, queue: List<Download>) {
        for (chapter in chapters) {
            if (downloadManager.isChapterDownloaded(chapter, manga)) {
                chapter.status = Download.State.DOWNLOADED
            } else if (queue.isNotEmpty()) {
                chapter.status = queue.find { it.chapter.id == chapter.id }
                    ?.status ?: Download.State.default
            }
        }
    }

    /**
     * Converts a chapter from the database to an extended model, allowing to store new fields.
     */
    private fun Chapter.toModel(): ChapterItem {
        // Create the model object.
        val model = ChapterItem(this, manga)
        model.isLocked = isLockedFromSearch

        // Find an active download for this chapter.
        val download = downloadManager.queueState.value.find { it.chapter.id == id }

        if (download != null) {
            // If there's an active download, assign it.
            model.download = download
        }
        return model
    }

    /**
     * Whether the sorting method is descending or ascending.
     */
    fun sortDescending() = manga.sortDescending(preferences)

    fun sortingOrder() = manga.chapterOrder(preferences)

    /**
     * Applies the view filters to the list of chapters obtained from the database.
     * @param chapterList the list of chapters from the database
     * @return an observable of the list of chapters filtered and sorted.
     */
    private fun applyChapterFilters(chapterList: List<ChapterItem>): List<ChapterItem> {
        if (isLockedFromSearch) {
            return chapterList
        }
        getScrollType(chapterList)
        return chapterSort.getChaptersSorted(chapterList)
    }

    fun getChapterUrl(chapter: Chapter): String? {
        val source = source as? HttpSource ?: return null
        val chapterUrl = try { source.getChapterUrl(chapter) } catch (_: Exception) { null }
        return chapterUrl.takeIf { !it.isNullOrBlank() }
            ?: try { source.getChapterUrl(manga, chapter) } catch (_: Exception) { null }
    }

    private fun getScrollType(chapters: List<ChapterItem>) {
        scrollType = when {
            ChapterUtil.hasMultipleVolumes(chapters) -> MULTIPLE_VOLUMES
            ChapterUtil.hasMultipleSeasons(chapters) -> MULTIPLE_SEASONS
            ChapterUtil.hasTensOfChapters(chapters) -> TENS_OF_CHAPTERS
            else -> 0
        }
    }

    /**
     * Returns the next unread chapter or null if everything is read.
     */
    fun getNextUnreadChapter(): ChapterItem? {
        return chapterSort.getNextUnreadChapter(chapters)
    }

    fun anyRead(): Boolean = allChapters.any { it.read }
    fun hasBookmark(): Boolean = allChapters.any { it.bookmark }
    fun hasDownloads(): Boolean = allChapters.any { it.isDownloaded }

    fun getUnreadChaptersSorted() =
        chapters.filter { !it.read && it.status == Download.State.NOT_DOWNLOADED }.distinctBy { it.name }
            .sortedWith(chapterSort.sortComparator(true))

    fun startDownloadingNow(chapter: Chapter) {
        downloadManager.startDownloadNow(chapter)
    }

    /**
     * Downloads the given list of chapters with the manager.
     * @param chapters the list of chapters to download.
     */
    fun downloadChapters(chapters: List<ChapterItem>) {
        downloadManager.downloadChapters(manga, chapters.filter { !it.isDownloaded })
    }

    /**
     * Deletes the given list of chapter.
     * @param chapter the chapter to delete.
     */
    fun deleteChapter(chapter: ChapterItem) {
        this.chapters.find { it.id == chapter.id }?.apply {
            if (chapter.chapter.bookmark && !preferences.removeBookmarkedChapters().get()) return@apply
            status = Download.State.NOT_DOWNLOADED
            download = null
        }

        view?.updateChapters()

        downloadManager.deleteChapters(listOf(chapter), manga, source, true)
    }

    /**
     * Deletes the given list of chapter.
     * @param chapters the list of chapters to delete.
     */
    fun deleteChapters(chapters: List<ChapterItem>, update: Boolean = true, isEverything: Boolean = false) {
        chapters.forEach { chapter ->
            this.chapters.find { it.id == chapter.id }?.apply {
                if (chapter.chapter.bookmark && !preferences.removeBookmarkedChapters().get() && !isEverything) return@apply
                status = Download.State.NOT_DOWNLOADED
                download = null
            }
        }

        if (update) view?.updateChapters()

        if (isEverything) {
            downloadManager.deleteManga(manga, source)
        } else {
            downloadManager.deleteChapters(chapters, manga, source)
        }
    }

    suspend fun refreshMangaFromDb(): Manga {
        val dbManga = getManga.awaitById(mangaId)!!
        setCurrentManga(dbManga)
        return dbManga
    }

    private suspend fun fetchMangaFromSource() {
        try {
            withIOContext {
                val networkManga = source.getMangaDetails(manga.copy())
                manga.prepareCoverUpdate(coverCache, networkManga, false)
                manga.copyFrom(networkManga)
                manga.initialized = true

                updateManga.await(manga.toMangaUpdate())

                presenterScope.launchNonCancellableIO {
                    val request =
                        ImageRequest.Builder(preferences.context).data(manga.cover())
                            .memoryCachePolicy(CachePolicy.DISABLED)
                            .diskCachePolicy(CachePolicy.WRITE_ONLY)
                            .build()

                    if (preferences.context.imageLoader.execute(request) is SuccessResult) {
                        withUIContext {
                            view?.setPaletteColor()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (e is HttpException && e.code == 103) return
            withUIContext {
                view?.showError(trimException(e))
            }
        }
    }

    private suspend fun fetchChaptersFromSource(manualFetch: Boolean = true) {
        try {
            withIOContext {
                val chapters = source.getChapterList(manga.copy())
                val (added, removed) = withIOContext { syncChaptersWithSource(chapters, manga, source) }
                if (added.isNotEmpty()) {
                    if (manga.shouldDownloadNewChapters(preferences) && manualFetch) {
                        downloadChapters(
                            added.sortedBy { it.chapter_number }
                                .map { it.toModel() },
                        )
                    }
                    view?.view?.context?.let { mangaShortcutManager.updateShortcuts(it) }
                }
                if (removed.isNotEmpty()) {
                    val removedChaptersId = removed.map { it.id }
                    val removedChapters = this@MangaDetailsPresenter.chapters.filter {
                        it.id in removedChaptersId && it.isDownloaded
                    }
                    if (removedChapters.isNotEmpty()) {
                        withUIContext {
                            view?.showChaptersRemovedPopup(removedChapters)
                        }
                    }
                }
                getChapters()
                getHistory()
            }
        } catch (e: Exception) {
            withUIContext {
                view?.showError(trimException(e))
            }
        }
    }

    /**
     * Fetch related/suggested mangas for the carousel. One-shot per presenter instance — repeat
     * calls are no-ops. Three input streams feed the same pool: source-native related mangas
     * (Komikku's `getRelatedMangaList` API on [CatalogueSource]), the keyword-search fallback
     * (also from the source API), and tracker-backed recommendations (AniList / MyAnimeList via
     * Jikan / MangaUpdates). Tracker batches are tagged with [RECOMMENDS_SOURCE] so the carousel
     * can route their clicks through Global Search instead of trying to resolve a tracker URL
     * against the current source.
     *
     * Dedup is by SManga url within the pool; tracker URLs and source URLs are in distinct URL
     * spaces so they don't collide. Cap is [RELATED_MANGAS_LIMIT] across the merged pool.
     */
    fun fetchRelatedMangasFromSource() {
        if (relatedMangasFetched) return
        val catalogueSource = source as? CatalogueSource ?: return
        if (catalogueSource.disableRelatedMangas) return
        relatedMangasFetched = true

        presenterScope.launch {
            relatedMangasLoading = true
            withUIContext { view?.updateHeader() }

            // Phase 6 inputs computed once up-front, captured by the per-push closure below.
            // These are read-only after this point so no lock is needed when the ranker reads them.
            val rerankEnabled = preferences.enableRecommendationRerank().get()
            val taste: TasteProfile = runCatching {
                val entries = Injekt.get<GetTrackedEntries>().await()
                Injekt.get<ComputeTasteProfile>().invoke(entries)
            }.getOrElse {
                Logger.e(it) { "Taste profile load failed; ranker will run with empty profile" }
                TasteProfile.EMPTY
            }
            val libraryUrls: Set<Pair<Long, String>> = runCatching {
                getManga.awaitFavorites().mapTo(HashSet()) { it.source to it.url }
            }.getOrElse {
                Logger.e(it) { "Favorites lookup failed; rerank anti-echo will be a no-op" }
                emptySet()
            }
            val ranker = RecommendationRanker()

            val accumulated = LinkedHashSet<RelatedMangaCandidate>()
            // Cross-pool dedup: tracker URLs (anilist.co/...) and source URLs (mangadex.org/...)
            // live in distinct namespaces, so url-keyed dedup inside `accumulated` can't catch a
            // manga that appears via both. Normalized title acts as a second key spanning all
            // streams. First-arriving entry wins, which naturally prefers source-origin candidates
            // (single fast call) over tracker entries (slower, multiple calls).
            val seenTitleKeys = HashSet<String>().apply { add(normalizeTitleForDedup(manga.title)) }
            val excludedUrl = manga.url
            val exceptionHandler: (Throwable) -> Unit = { e ->
                Logger.e(e) { "Related-mangas sub-task failed for ${manga.title}" }
            }

            // Same accumulator for both input streams — only the sourceId attached to each batch
            // differs. Mutex guards concurrent inserts since source-native + tracker fetchers
            // race each other.
            fun makePushResults(
                sourceId: Long,
            ): suspend (Pair<String, List<SManga>>, Boolean) -> Unit = { pair, _ ->
                // For tracker pushes (sourceId == RECOMMENDS_SOURCE) the bucket label is the
                // tracker name (e.g. "AniList") and lets the merge step round-robin slots fairly.
                // For source pushes it's the keyword/extension label and isn't needed downstream.
                val trackerName = pair.first.takeIf { sourceId == RECOMMENDS_SOURCE }
                val changed = relatedMangasMutex.withLock {
                    val before = accumulated.size
                    pair.second.forEach { m ->
                        if (m.url == excludedUrl) return@forEach
                        val titleKey = normalizeTitleForDedup(m.title)
                        if (!seenTitleKeys.add(titleKey)) return@forEach
                        accumulated.add(RelatedMangaCandidate(sourceId, trackerName, m))
                    }
                    if (accumulated.size != before) {
                        val merged = mergeForDisplay(accumulated)
                        // Phase 6: anti-echo runs in both modes (it's not really taste — just
                        // "don't suggest what's already in the library"). Full rerank gates on
                        // the user-facing toggle so the carousel can be returned to Phase 5
                        // ordering by flipping one preference.
                        relatedMangas = if (rerankEnabled) {
                            ranker.rank(merged, taste, libraryUrls)
                        } else {
                            merged.filterNot { c ->
                                c.sourceId != RECOMMENDS_SOURCE &&
                                    libraryUrls.contains(c.sourceId to c.manga.url)
                            }
                        }
                        true
                    } else {
                        false
                    }
                }
                if (changed) withUIContext { view?.updateHeader() }
            }

            runCatching {
                coroutineScope {
                    launch {
                        catalogueSource.getRelatedMangaList(
                            manga = manga,
                            exceptionHandler = exceptionHandler,
                            pushResults = makePushResults(catalogueSource.id),
                        )
                    }
                    launch {
                        RecommendationsFetcher().fetch(
                            manga = manga,
                            exceptionHandler = exceptionHandler,
                            pushResults = makePushResults(RECOMMENDS_SOURCE),
                        )
                    }
                    launch {
                        TasteCandidateFetcher().fetch(
                            source = catalogueSource,
                            exceptionHandler = exceptionHandler,
                            pushResults = makePushResults(catalogueSource.id),
                        )
                    }
                }
            }.onFailure {
                Logger.e(it) { "Related-mangas fetch failed for ${manga.title}" }
            }

            relatedMangasLoading = false
            withUIContext { view?.updateHeader() }
        }
    }

    /**
     * Slice the accumulated pool into the carousel-visible list. Reserves up to
     * [RELATED_MANGAS_TRACKER_RESERVE] slots for tracker-origin entries so they aren't starved
     * when source-native fills the cap first; either side cedes unfilled capacity to the other.
     *
     * Within the tracker slots, round-robin across trackers so one fast tracker doesn't dominate
     * (e.g. MAL/Jikan returning 90+ items shouldn't squeeze AniList and MangaUpdates out).
     */
    private fun mergeForDisplay(pool: LinkedHashSet<RelatedMangaCandidate>): List<RelatedMangaCandidate> {
        val sourceList = pool.filterNot { it.sourceId == RECOMMENDS_SOURCE }
        val trackerLists = pool
            .filter { it.sourceId == RECOMMENDS_SOURCE }
            .groupBy { it.trackerName }
            .values
            .toList()
        val trackerTotal = trackerLists.sumOf { it.size }
        val initialTracker = minOf(trackerTotal, RELATED_MANGAS_TRACKER_RESERVE)
        val sourceTake = minOf(sourceList.size, RELATED_MANGAS_LIMIT - initialTracker)
        val trackerCap = minOf(trackerTotal, RELATED_MANGAS_LIMIT - sourceTake)
        return sourceList.take(sourceTake) + roundRobin(trackerLists, trackerCap)
    }

    /**
     * Interleave [lists] in round-robin order, stopping at [limit]. Empty lists are skipped
     * automatically; uneven list sizes mean longer lists drain into remaining iterations once
     * shorter ones are exhausted.
     */
    private fun <T> roundRobin(lists: List<List<T>>, limit: Int): List<T> {
        if (limit <= 0 || lists.isEmpty()) return emptyList()
        val iterators = lists.map { it.iterator() }.filter { it.hasNext() }.toMutableList()
        val out = ArrayList<T>(limit)
        while (out.size < limit && iterators.isNotEmpty()) {
            val it = iterators.iterator()
            while (it.hasNext() && out.size < limit) {
                val cur = it.next()
                out.add(cur.next())
                if (!cur.hasNext()) it.remove()
            }
        }
        return out
    }

    /**
     * Resolve a source-side [SManga] to a local [Manga] DB row (creating one if it doesn't exist
     * yet), suitable for navigating to its details page. Mirrors GlobalSearchPresenter's
     * `networkToLocalManga` so behavior matches what a Global Search tap does.
     */
    suspend fun toLocalManga(sManga: SManga, sourceId: Long): Manga? {
        var localManga = getManga.awaitByUrlAndSource(sManga.url, sourceId)
        if (localManga == null) {
            val newManga = try {
                Manga.create(sManga.url, sManga.title, sourceId)
            } catch (_: UninitializedPropertyAccessException) {
                return null
            }
            newManga.copyFrom(sManga)
            newManga.id = insertManga.await(newManga)
            localManga = newManga
        } else if (!localManga.favorite) {
            localManga.title = try {
                sManga.title
            } catch (_: UninitializedPropertyAccessException) {
                return localManga
            }
        }
        return localManga
    }

    /** Refresh Manga Info and Chapter List (not tracking) */
    fun refreshAll() {
        val isLocal by lazy { manga.isLocal() }
        if (view?.isNotOnline() == true && !isLocal) return

        presenterScope.launch {
            isLoading = true
            val tasks = listOf(
                async { fetchMangaFromSource() },
                async { fetchChaptersFromSource() },
            )
            tasks.awaitAll()
            isLoading = false
            withUIContext {
                view?.updateChapters()
            }
        }
    }

    private fun trimException(e: java.lang.Exception): String {
        return (
            if (e !is SourceNotFoundException &&
                e.message?.contains(": ") == true
            ) {
                e.message?.split(": ")?.drop(1)
                    ?.joinToString(": ")
            } else {
                e.message
            }
            ) ?: view?.view?.context?.getString(MR.strings.unknown_error) ?: ""
    }

    /**
     * Bookmarks the given list of chapters.
     * @param selectedChapters the list of chapters to bookmark.
     */
    fun bookmarkChapters(selectedChapters: List<ChapterItem>, bookmarked: Boolean) {
        presenterScope.launchNonCancellableIO {
            val updates = selectedChapters.map {
                it.bookmark = bookmarked
                it.toProgressUpdate()
            }
            updateChapter.awaitAll(updates)
            getChapters()
            withUIContext { view?.updateChapters() }
        }
    }

    /**
     * Mark the selected chapter list as read/unread.
     * @param selectedChapters the list of selected chapters.
     * @param read whether to mark chapters as read or unread.
     */
    fun markChaptersRead(
        selectedChapters: List<ChapterItem>,
        read: Boolean,
        deleteNow: Boolean = true,
        lastRead: Int? = null,
        pagesLeft: Int? = null,
    ) {
        presenterScope.launchNonCancellableIO {
            val updates = selectedChapters.map {
                it.read = read
                if (!read) {
                    it.last_page_read = lastRead ?: 0
                    it.pages_left = pagesLeft ?: 0
                }
                it.toProgressUpdate()
            }
            updateChapter.awaitAll(updates)
            if (read && deleteNow && preferences.removeAfterMarkedAsRead().get()) {
                deleteChapters(selectedChapters, false)
            }
            getChapters()
            withUIContext { view?.updateChapters() }
            if (read && deleteNow) {
                val latestReadChapter = selectedChapters.maxByOrNull { it.chapter_number.toInt() }?.chapter
                updateTrackChapterMarkedAsRead(preferences, latestReadChapter, manga.id) {
                    fetchTracks()
                }
            }
        }
    }

    /**
     * Sets the sorting order and requests an UI update.
     */
    fun setSortOrder(sort: Int, descend: Boolean) {
        manga.setChapterOrder(sort, if (descend) Manga.CHAPTER_SORT_DESC else Manga.CHAPTER_SORT_ASC)
        if (mangaSortMatchesDefault()) {
            manga.setSortToGlobal()
        }
        presenterScope.launchNonCancellableIO { asyncUpdateMangaAndChapters() }
    }

    fun setGlobalChapterSort(sort: Int, descend: Boolean) {
        preferences.sortChapterOrder().set(sort)
        preferences.chaptersDescAsDefault().set(descend)
        manga.setSortToGlobal()
        presenterScope.launchNonCancellableIO { asyncUpdateMangaAndChapters() }
    }

    fun mangaSortMatchesDefault(): Boolean {
        return (
            manga.sortDescending == preferences.chaptersDescAsDefault().get() &&
                manga.sorting == preferences.sortChapterOrder().get()
            ) || !manga.usesLocalSort
    }

    fun mangaFilterMatchesDefault(): Boolean {
        return (
            manga.readFilter == preferences.filterChapterByRead().get() &&
                manga.downloadedFilter == preferences.filterChapterByDownloaded().get() &&
                manga.bookmarkedFilter == preferences.filterChapterByBookmarked().get() &&
                manga.hideChapterTitles == preferences.hideChapterTitlesByDefault().get()
            ) || !manga.usesLocalFilter
    }

    fun resetSortingToDefault() {
        manga.setSortToGlobal()
        presenterScope.launchNonCancellableIO { asyncUpdateMangaAndChapters() }
    }

    /**
     * Removes all filters and requests an UI update.
     */
    fun setFilters(
        unread: TriStateCheckBox.State,
        downloaded: TriStateCheckBox.State,
        bookmarked: TriStateCheckBox.State,
    ) {
        manga.readFilter = when (unread) {
            TriStateCheckBox.State.CHECKED -> Manga.CHAPTER_SHOW_UNREAD
            TriStateCheckBox.State.IGNORE -> Manga.CHAPTER_SHOW_READ
            else -> Manga.SHOW_ALL
        }
        manga.downloadedFilter = when (downloaded) {
            TriStateCheckBox.State.CHECKED -> Manga.CHAPTER_SHOW_DOWNLOADED
            TriStateCheckBox.State.IGNORE -> Manga.CHAPTER_SHOW_NOT_DOWNLOADED
            else -> Manga.SHOW_ALL
        }
        manga.bookmarkedFilter = when (bookmarked) {
            TriStateCheckBox.State.CHECKED -> Manga.CHAPTER_SHOW_BOOKMARKED
            TriStateCheckBox.State.IGNORE -> Manga.CHAPTER_SHOW_NOT_BOOKMARKED
            else -> Manga.SHOW_ALL
        }
        manga.setFilterToLocal()
        if (mangaFilterMatchesDefault()) {
            manga.setFilterToGlobal()
        }
        presenterScope.launchNonCancellableIO { asyncUpdateMangaAndChapters() }
    }

    /**
     * Sets the active display mode.
     * @param hide set title to hidden
     */
    fun hideTitle(hide: Boolean) {
        manga.displayMode = if (hide) Manga.CHAPTER_DISPLAY_NUMBER else Manga.CHAPTER_DISPLAY_NAME
        manga.setFilterToLocal()
        presenterScope.launchNonCancellableIO { updateManga.await(MangaUpdate(manga.id!!, chapterFlags = manga.chapter_flags)) }
        if (mangaFilterMatchesDefault()) {
            manga.setFilterToGlobal()
        }
        view?.refreshAdapter()
    }

    fun resetFilterToDefault() {
        manga.setFilterToGlobal()
        presenterScope.launchNonCancellableIO { asyncUpdateMangaAndChapters() }
    }

    fun setGlobalChapterFilters(
        unread: TriStateCheckBox.State,
        downloaded: TriStateCheckBox.State,
        bookmarked: TriStateCheckBox.State,
    ) {
        preferences.filterChapterByRead().set(
            when (unread) {
                TriStateCheckBox.State.CHECKED -> Manga.CHAPTER_SHOW_UNREAD
                TriStateCheckBox.State.IGNORE -> Manga.CHAPTER_SHOW_READ
                else -> Manga.SHOW_ALL
            },
        )
        preferences.filterChapterByDownloaded().set(
            when (downloaded) {
                TriStateCheckBox.State.CHECKED -> Manga.CHAPTER_SHOW_DOWNLOADED
                TriStateCheckBox.State.IGNORE -> Manga.CHAPTER_SHOW_NOT_DOWNLOADED
                else -> Manga.SHOW_ALL
            },
        )
        preferences.filterChapterByBookmarked().set(
            when (bookmarked) {
                TriStateCheckBox.State.CHECKED -> Manga.CHAPTER_SHOW_BOOKMARKED
                TriStateCheckBox.State.IGNORE -> Manga.CHAPTER_SHOW_NOT_BOOKMARKED
                else -> Manga.SHOW_ALL
            },
        )
        preferences.hideChapterTitlesByDefault().set(manga.hideChapterTitles)
        manga.setFilterToGlobal()
        presenterScope.launchNonCancellableIO { asyncUpdateMangaAndChapters() }
    }

    private suspend fun asyncUpdateMangaAndChapters(justChapters: Boolean = false) {
        if (!justChapters) updateManga.await(MangaUpdate(manga.id!!, chapterFlags = manga.chapter_flags))
        getChapters()
        withUIContext { view?.updateChapters() }
    }

    private fun isScanlatorFiltered() = manga.filtered_scanlators?.isNotEmpty() == true

    fun currentFilters(): String {
        val filtersId = mutableListOf<StringResource?>()
        filtersId.add(if (manga.readFilter(preferences) == Manga.CHAPTER_SHOW_READ) MR.strings.read else null)
        filtersId.add(if (manga.readFilter(preferences) == Manga.CHAPTER_SHOW_UNREAD) MR.strings.unread else null)
        filtersId.add(if (manga.downloadedFilter(preferences) == Manga.CHAPTER_SHOW_DOWNLOADED) MR.strings.downloaded else null)
        filtersId.add(if (manga.downloadedFilter(preferences) == Manga.CHAPTER_SHOW_NOT_DOWNLOADED) MR.strings.not_downloaded else null)
        filtersId.add(if (manga.bookmarkedFilter(preferences) == Manga.CHAPTER_SHOW_BOOKMARKED) MR.strings.bookmarked else null)
        filtersId.add(if (manga.bookmarkedFilter(preferences) == Manga.CHAPTER_SHOW_NOT_BOOKMARKED) MR.strings.not_bookmarked else null)
        filtersId.add(if (isScanlatorFiltered()) MR.strings.scanlators else null)
        return filtersId.filterNotNull()
            .joinToString(", ") { view?.view?.context?.getString(it) ?: "" }
    }

    fun setScanlatorFilter(filteredScanlators: Set<String>) {
        presenterScope.launchNonCancellableIO {
            val manga = manga
            MangaUtil.setScanlatorFilter(
                updateManga,
                manga,
                if (filteredScanlators.size == allChapterScanlators.size) emptySet() else filteredScanlators
            )
            asyncUpdateMangaAndChapters(true)
        }
    }

    /**
     * Get user categories.
     *
     * @return List of categories, not including the default category
     */
    fun getCategories(): List<Category> {
        return runBlocking { getCategories.await() }
    }

    fun confirmDeletion() {
        preferences.invalidateTrackerReconciliationFor(listOf(manga.id!!))
        presenterScope.launchNonCancellableIO {
            manga.removeCover(coverCache)
            customMangaManager.saveMangaInfo(CustomMangaInfo(
                mangaId = manga.id!!,
                title = null,
                author = null,
                artist = null,
                description = null,
                genre = null,
                status = null,
            ))
            downloadManager.deleteManga(manga, source)
            deleteTrack.awaitForMangaAll(manga.id!!)
            asyncUpdateMangaAndChapters(true)
        }
    }

    suspend fun removeAllRelatedFromLibrary() {
        val allIds = relatedMangaIds.toMutableList().apply { add(manga.id!!) }
        val updates = allIds.mapNotNull { id ->
            val target = getManga.awaitById(id) ?: return@mapNotNull null
            if (!target.favorite) null else MangaUpdate(id = id, favorite = false)
        }
        if (updates.isNotEmpty()) updateManga.awaitAll(updates)
        preferences.invalidateTrackerReconciliationFor(allIds)
        presenterScope.launchNonCancellableIO {
            allIds.forEach { id ->
                val target = getManga.awaitById(id) ?: return@forEach
                target.removeCover(coverCache)
                downloadManager.deleteManga(target, sourceManager.getOrStub(target.source))
                deleteTrack.awaitForMangaAll(id)
            }
        }
    }

    private fun onUpdateManga() = fetchChapters()

    fun shareManga() {
        val context = Injekt.get<Application>()

        val destDir = UniFile.fromFile(context.cacheDir)!!.createDirectory("shared_image")!!

        presenterScope.launchIO {
            try {
                val uri = saveCover(destDir)
                withUIContext {
                    view?.shareManga(uri.uri.toFile())
                }
            } catch (_: java.lang.Exception) {
            }
        }
    }

    private fun saveImage(cover: Bitmap, directory: File, manga: Manga): File? {
        directory.mkdirs()

        // Build destination file.
        val filename = DiskUtil.buildValidFilename("${manga.title} - Cover.jpg")

        val destFile = File(directory, filename)
        val stream: OutputStream = FileOutputStream(destFile)
        cover.compress(Bitmap.CompressFormat.JPEG, 75, stream)
        stream.flush()
        stream.close()
        return destFile
    }

    fun updateManga(
        title: String?,
        author: String?,
        artist: String?,
        uri: Uri?,
        description: String?,
        tags: Array<String>?,
        status: Int?,
        seriesType: Int?,
        lang: String?,
        resetCover: Boolean = false,
    ) {
        if (manga.isLocal()) {
            manga.title = if (title.isNullOrBlank()) manga.url else title.trim()
            manga.author = author?.trimOrNull()
            manga.artist = artist?.trimOrNull()
            manga.description = description?.trimOrNull()
            val tagsString = tags?.joinToString(", ") { tag ->
                tag.replaceFirstChar {
                    it.uppercase(Locale.getDefault())
                }
            }
            manga.genre = if (tags.isNullOrEmpty()) null else tagsString?.trim()
            if (seriesType != null) {
                manga.genre = setSeriesType(seriesType, manga.genre).joinToString(", ") {
                    it.replaceFirstChar { genre ->
                        genre.titlecase(Locale.getDefault())
                    }
                }
                manga.viewer_flags = -1
                presenterScope.launchIO { updateManga.await(MangaUpdate(manga.id!!, viewerFlags = manga.viewer_flags)) }
            }
            manga.status = status ?: SManga.UNKNOWN
            LocalSource(downloadManager.context).updateMangaInfo(manga, lang)
            presenterScope.launchIO {
                updateManga.await(
                    MangaUpdate(
                        manga.id!!,
                        title = manga.ogTitle,
                        author = manga.originalAuthor,
                        artist = manga.originalArtist,
                        description = manga.originalDescription,
                        genres = manga.originalGenre?.split(", ").orEmpty(),
                        status = manga.ogStatus,
                    )
                )
            }
        } else {
            var genre = if (!tags.isNullOrEmpty() && tags.joinToString(", ") != manga.originalGenre) {
                tags.map { tag -> tag.replaceFirstChar { it.titlecase(Locale.getDefault()) } }
                    .toTypedArray()
            } else {
                null
            }
            if (seriesType != null) {
                genre = setSeriesType(seriesType, genre?.joinToString())
                manga.viewer_flags = -1
                presenterScope.launchIO { updateManga.await(MangaUpdate(manga.id!!, viewerFlags = manga.viewer_flags)) }
            }
            val manga = CustomMangaInfo(
                mangaId = manga.id!!,
                title?.trimOrNull(),
                author?.trimOrNull(),
                artist?.trimOrNull(),
                description?.trimOrNull(),
                genre?.joinToString(),
                if (status != this.manga.ogStatus) status else null,
            )
            launchNow {
                customMangaManager.saveMangaInfo(manga)
            }
        }
        if (uri != null) {
            editCoverWithStream(uri)
        } else if (resetCover) {
            coverCache.deleteCustomCover(manga)
            presenterScope.launchIO { manga.updateCoverLastModified() }
            view?.setPaletteColor()
        }
        view?.updateHeader()
    }

    private fun setSeriesType(seriesType: Int, genres: String? = null): Array<String> {
        val tags = (genres ?: manga.genre)?.split(",")?.map { it.trim() }?.toMutableList() ?: mutableListOf()
        tags.removeAll { manga.isSeriesTag(it) }
        when (seriesType) {
            Manga.TYPE_MANGA -> tags.add("Manga")
            Manga.TYPE_MANHUA -> tags.add("Manhua")
            Manga.TYPE_MANHWA -> tags.add("Manhwa")
            Manga.TYPE_COMIC -> tags.add("Comic")
            Manga.TYPE_WEBTOON -> tags.add("Webtoon")
        }
        return tags.toTypedArray()
    }

    fun editCoverWithStream(uri: Uri): Boolean {
        val inputStream =
            downloadManager.context.contentResolver.openInputStream(uri) ?: return false
        if (manga.isLocal()) {
            LocalSource.updateCover(manga, inputStream)
            presenterScope.launchNonCancellableIO { manga.updateCoverLastModified() }
            view?.setPaletteColor()
            return true
        }

        if (manga.favorite) {
            coverCache.setCustomCoverToCache(manga, inputStream)
            presenterScope.launchNonCancellableIO { manga.updateCoverLastModified() }
            view?.setPaletteColor()
            return true
        }
        return false
    }

    fun shareCover(): Uri? {
        return try {
            val destDir = UniFile.fromFile(coverCache.context.cacheDir)!!.createDirectory("shared_image")!!
            val file = saveCover(destDir)
            file.uri
        } catch (e: Exception) {
            null
        }
    }

    fun saveCover(): Boolean {
        return try {
            val directory = if (preferences.folderPerManga().get()) {
                storageManager.getCoversDirectory()!!.createDirectory(DiskUtil.buildValidFilename(manga.title))!!
            } else {
                storageManager.getCoversDirectory()!!
            }
            val file = saveCover(directory)
            DiskUtil.scanMedia(preferences.context, file)
            true
        } catch (e: Exception) {
            if (networkPreferences.verboseLogging().get()) Logger.e(e) { "Unable to save cover" }
            false
        }
    }

    private fun saveCover(directory: UniFile): UniFile {
        val cover = coverCache.getCustomCoverFile(manga).takeIf { it.exists() } ?: coverCache.getCoverFile(manga.thumbnail_url, !manga.favorite)
        val type = cover?.let { ImageUtil.findImageType(it.inputStream()) }
            ?: throw Exception("Not an image")

        // Build destination file.
        val filename = DiskUtil.buildValidFilename("${manga.title}.${type.extension}")

        val destFile = directory.createFile(filename)!!
        cover.inputStream().use { input ->
            destFile.openOutputStream().use { output ->
                input.copyTo(output)
            }
        }
        return destFile
    }

    fun isTracked(): Boolean =
        loggedServices.any { service -> tracks.any { it.sync_id == service.id } }

    fun hasTrackers(): Boolean = loggedServices.isNotEmpty()

    // Tracking
    private fun setTrackItems() {
        trackList = loggedServices.filter { service ->
            if (service !is EnhancedTrackService) return@filter true
            service.accept(source)
        }.map { service ->
            TrackItem(tracks.find { it.sync_id == service.id }, service)
        }
    }

    suspend fun fetchTracks() {
        tracks = withContext(Dispatchers.IO) { getTrack.awaitAllByMangaId(manga.id!!) }
        setTrackItems()
        withContext(Dispatchers.Main) { view?.refreshTracking(trackList) }
    }

    fun refreshTracking(showOfflineSnack: Boolean = false, trackIndex: Int? = null) {
        if (view?.isNotOnline(showOfflineSnack) == false) {
            presenterScope.launch {
                val asyncList = (trackIndex?.let { listOf(trackList[it]) } ?: trackList.filter { it.track != null })
                    .map { item ->
                        async(Dispatchers.IO) {
                            val trackItem = try {
                                item.service.refresh(item.track!!)
                            } catch (e: Exception) {
                                trackError(e)
                                null
                            }
                            if (trackItem != null) {
                                insertTrack.await(trackItem)
                                syncChaptersWithTrackServiceTwoWay(chapters, trackItem, item.service)
                                trackItem
                            } else {
                                item.track
                            }
                        }
                    }
                asyncList.awaitAll()
                fetchTracks()
            }
        }
    }

    fun trackSearch(query: String, service: TrackService) {
        if (view?.isNotOnline() == false) {
            presenterScope.launch(Dispatchers.IO) {
                val results = try {
                    service.search(query)
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { view?.trackSearchError(e) }
                    return@launch
                }
                withContext(Dispatchers.Main) { view?.onTrackSearchResults(results) }
            }
        }
    }

    fun registerTracking(item: Track?, service: TrackService) {
        if (item != null) {
            item.manga_id = manga.id!!

            presenterScope.launch {
                val binding = try {
                    service.bind(item)
                } catch (e: Exception) {
                    trackError(e)
                    null
                }
                withContext(Dispatchers.IO) {
                    if (binding != null) {
                        insertTrack.await(binding)
                        propagateTrackToSiblings(binding)
                    }

                    syncChaptersWithTrackServiceTwoWay(chapters, item, service)
                }
                fetchTracks()
            }
        }
    }

    /**
     * Mirrors [binding] onto every still-favorited sibling in [relatedMangaIds] so that adding a
     * tracker to one source in a multi-source group automatically links the others. Behind the
     * `syncTrackerLinksGrouped` preference. Skips siblings that are no longer favorited (per the
     * favorite filter applied elsewhere in `availableSources`).
     */
    private suspend fun propagateTrackToSiblings(binding: Track) {
        if (!preferences.syncTrackerLinksGrouped().get()) return
        if (relatedMangaIds.isEmpty()) return
        relatedMangaIds.forEach { siblingId ->
            if (siblingId == binding.manga_id) return@forEach
            val sibling = getManga.awaitById(siblingId) ?: return@forEach
            if (!sibling.favorite) return@forEach
            insertTrack.await(binding.copyForSibling(siblingId))
        }
    }

    fun removeTracker(trackItem: TrackItem, removeFromService: Boolean) {
        presenterScope.launch {
            withContext(Dispatchers.IO) {
                deleteTrack.awaitForManga(manga.id!!, trackItem.service.id)
                if (removeFromService && trackItem.service.canRemoveFromService()) {
                    trackItem.service.removeFromService(trackItem.track!!)
                }
            }
            fetchTracks()
        }
    }

    private fun updateRemote(track: Track, service: TrackService) {
        presenterScope.launch {
            val binding = try {
                service.update(track)
            } catch (e: Exception) {
                trackError(e)
                null
            }
            if (binding != null) {
                withContext(Dispatchers.IO) { insertTrack.await(binding) }
                fetchTracks()
            } else {
                trackRefreshDone()
            }
        }
    }

    private fun trackRefreshDone() {
        presenterScope.launch(Dispatchers.Main) { view?.trackRefreshDone() }
    }

    private fun trackError(error: Exception) {
        presenterScope.launch(Dispatchers.Main) { view?.trackRefreshError(error) }
    }

    fun setStatus(item: TrackItem, index: Int) {
        val track = item.track!!
        track.status = item.service.getStatusList()[index]
        if (item.service.isCompletedStatus(index) && track.total_chapters > 0L) {
            track.last_chapter_read = track.total_chapters.toFloat()
        }
        updateRemote(track, item.service)
    }

    fun setScore(item: TrackItem, index: Int) {
        val track = item.track!!
        track.score = item.service.indexToScore(index)
        updateRemote(track, item.service)
    }

    fun setLastChapterRead(item: TrackItem, chapterNumber: Int) {
        val track = item.track!!
        track.last_chapter_read = chapterNumber.toFloat()
        updateRemote(track, item.service)
    }

    fun setTrackerStartDate(item: TrackItem, date: Long) {
        val track = item.track!!
        track.started_reading_date = date
        updateRemote(track, item.service)
    }

    fun setTrackerFinishDate(item: TrackItem, date: Long) {
        val track = item.track!!
        track.finished_reading_date = date
        updateRemote(track, item.service)
    }

    suspend fun getSuggestedDate(readingDate: TrackingBottomSheet.ReadingDate): Long? {
        val chapters = getHistory.awaitAllByMangaId(manga.id ?: 0L)
        val date = when (readingDate) {
            TrackingBottomSheet.ReadingDate.Start -> chapters.minOfOrNull { it.last_read }
            TrackingBottomSheet.ReadingDate.Finish -> chapters.maxOfOrNull { it.last_read }
        } ?: return null
        return if (date <= 0L) null else date
    }

    override fun onStatusChange(download: Download) {
        super.onStatusChange(download)
        chapters.find { it.id == download.chapter.id }?.status = download.status
        onPageProgressUpdate(download)
    }

    private suspend fun onQueueUpdate(queue: List<Download>) = withIOContext {
        getChapters(queue)
        withUIContext {
            view?.updateChapters()
        }
    }

    override fun onQueueUpdate(download: Download) {
        // already handled by onStatusChange
    }

    override fun onProgressUpdate(download: Download) {
        // already handled by onStatusChange
    }

    override fun onPageProgressUpdate(download: Download) {
        chapters.find { it.id == download.chapter.id }?.download = download
        view?.updateChapterDownload(download)
    }

    // ── Source-group management ───────────────────────────────────────────────

    /**
     * Recomputes [relatedMangaIds] from the current library state — same logic
     * `LibraryPresenter.applySourceGrouping` uses, but for a single manga. Returns true if the
     * set of IDs changed, so the caller can decide whether a re-render is needed. Used when the
     * details screen re-attaches (e.g. returning from Global Search after the user added another
     * source for the same title) so the source-switcher chips reflect the new sibling without
     * waiting for a Library round-trip.
     */
    suspend fun refreshRelatedMangaIds(): Boolean {
        val targetId = manga.id ?: return false
        val targetTitle = manga.title.lowercase().trim()
        if (targetTitle.isEmpty()) return false

        val mergesPrefs = preferences.mangaManualMerges().get()
        val unmergesPrefs = preferences.mangaManualUnmerges().get()

        val merged = mergesPrefs.flatMap { entry ->
            val ids = entry.split(",").mapNotNull { it.trim().toLongOrNull() }
            if (targetId in ids) ids else emptyList()
        }.toSet()

        val sameTitle = getManga.awaitFavorites()
            .asSequence()
            .filter { it.id != null && it.title.lowercase().trim() == targetTitle }
            .mapNotNull { it.id }
            .toSet()

        val candidates = (merged + sameTitle + targetId)
        val filtered = candidates
            .filter { id ->
                if (id == targetId) return@filter true
                val pair = if (targetId < id) "$targetId,$id" else "$id,$targetId"
                pair !in unmergesPrefs
            }
            .sorted()
            .toLongArray()

        val current = relatedMangaIds.sortedArray()
        if (filtered.contentEquals(current)) return false
        relatedMangaIds = filtered
        return true
    }

    suspend fun availableSources(): List<Pair<Long, eu.kanade.tachiyomi.source.Source>> {
        val unmerges = preferences.mangaManualUnmerges().get()
        return relatedMangaIds.filter { otherId ->
            if (otherId == mangaId) return@filter true
            val pair = if (mangaId < otherId) "$mangaId,$otherId" else "$otherId,$mangaId"
            pair !in unmerges
        }.mapNotNull { id ->
            val m = getManga.awaitById(id) ?: return@mapNotNull null
            if (!m.favorite) return@mapNotNull null
            id to sourceManager.getOrStub(m.source)
        }
    }

    fun removeFromGroup(targetId: Long) {
        val others = relatedMangaIds.filter { it != targetId }
        if (others.isEmpty()) return

        // Record that targetId must not auto-group with any of the others
        val unmerges = preferences.mangaManualUnmerges().get().toMutableSet()
        for (otherId in others) {
            val pair = if (targetId < otherId) "$targetId,$otherId" else "$otherId,$targetId"
            unmerges.add(pair)
        }
        preferences.mangaManualUnmerges().set(unmerges)

        // Remove entries containing targetId, then re-add one for the remaining IDs
        // so the rest of the group stays explicitly merged together
        val merges = preferences.mangaManualMerges().get().toMutableSet()
        merges.removeAll { entry ->
            entry.split(",").any { it.trim().toLongOrNull() == targetId }
        }
        if (others.size >= 2) {
            merges.add(others.sorted().joinToString(","))
        }
        preferences.mangaManualMerges().set(merges)
    }

    fun removeFromGroup(targetIds: List<Long>) {
        targetIds.forEach { removeFromGroup(it) }
    }

    suspend fun removeFromLibrary(targetIds: List<Long>) {
        val updates = targetIds.mapNotNull { id ->
            val target = getManga.awaitById(id) ?: return@mapNotNull null
            if (!target.favorite) null else MangaUpdate(id = id, favorite = false)
        }
        if (updates.isNotEmpty()) updateManga.awaitAll(updates)
        preferences.invalidateTrackerReconciliationFor(targetIds)
        presenterScope.launchNonCancellableIO {
            targetIds.forEach { id ->
                val target = getManga.awaitById(id) ?: return@forEach
                target.removeCover(coverCache)
                downloadManager.deleteManga(target, sourceManager.getOrStub(target.source))
                deleteTrack.awaitForMangaAll(id)
            }
        }
    }

    companion object {
        const val MULTIPLE_VOLUMES = 1
        const val TENS_OF_CHAPTERS = 2
        const val MULTIPLE_SEASONS = 3
        private const val RELATED_MANGAS_LIMIT = 30

        /**
         * Minimum number of carousel slots reserved for tracker-origin recommendations so they
         * aren't starved when source-native + keyword-search return enough to fill the cap
         * before trackers respond. Either side cedes unfilled capacity to the other.
         */
        private const val RELATED_MANGAS_TRACKER_RESERVE = 12

        /**
         * Minimal title normalization used to dedup across pool streams (source-native, keyword,
         * trackers, taste-driven). Trims, lowercases, and collapses internal whitespace —
         * enough to catch "Solo Leveling" vs "SOLO LEVELING" vs "Solo  Leveling " from different
         * sources. Deliberately does not strip punctuation, diacritics, or transliterate scripts;
         * those collapses risk false positives across legitimately distinct titles. Revisit if
         * observation shows v1 misses common cases.
         */
        private val DEDUP_WHITESPACE = Regex("\\s+")
        internal fun normalizeTitleForDedup(title: String): String =
            title.lowercase().trim().replace(DEDUP_WHITESPACE, " ")
    }
}
