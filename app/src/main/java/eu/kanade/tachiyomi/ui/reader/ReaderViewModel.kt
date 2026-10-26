package eu.kanade.tachiyomi.ui.reader

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.annotation.IntRange
import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ViewModelAssistedFactoryKey
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.chapter.interactor.SetReadStatus
import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.domain.manga.interactor.SetMangaViewerFlags
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.manga.model.readerOrientation
import eu.kanade.domain.manga.model.readingMode
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.domain.track.interactor.TrackChapter
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.saver.Image
import eu.kanade.tachiyomi.data.saver.ImageSaver
import eu.kanade.tachiyomi.data.saver.Location
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.chapter.ReaderChapterItem
import eu.kanade.tachiyomi.ui.reader.loader.DownloadPageLoader
import eu.kanade.tachiyomi.ui.reader.loader.HttpPageLoader
import eu.kanade.tachiyomi.ui.reader.loader.MergedChapterLoader
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.util.chapter.filterDownloaded
import eu.kanade.tachiyomi.util.editCover
import eu.kanade.tachiyomi.util.lang.byteSize
import eu.kanade.tachiyomi.util.storage.DiskUtil
import eu.kanade.tachiyomi.util.storage.cacheImageDir
import eu.kanade.tachiyomi.util.system.activeNetworkState
import exh.util.defaultReaderType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import logcat.LogPriority
import mihon.core.metro.AppCoroutineScope
import reikai.domain.chapter.hiddenKey
import reikai.domain.download.MangaChapterDownloadActions
import reikai.domain.download.rowDownloadChapters
import reikai.domain.entry.EntryId // RK
import reikai.domain.entry.withCustomInfo
import reikai.domain.library.ContentType
import reikai.domain.library.chapterSwipeActions
import reikai.domain.manga.MangaPreferences
import reikai.domain.manga.MergedChapterProvider
import reikai.domain.manga.chapterSettingsOf
import reikai.domain.manga.downloadedChapterIds
import reikai.domain.manga.inReadingOrder
import reikai.domain.merge.CopyToOpen
import reikai.domain.merge.DownloadTargets
import reikai.domain.merge.GroupChapterFlags
import reikai.domain.merge.MergeScope
import reikai.domain.merge.expandToUnits
import reikai.domain.merge.gapPresent
import reikai.domain.merge.withOpenedChapter
import reikai.domain.reader.ChapterIncognito // RK
import reikai.domain.reader.ChapterProgress
import reikai.domain.reader.ChapterRetryCooldown // RK
import reikai.domain.reader.ReadSessionClock
import reikai.domain.reader.ReaderPosition
import reikai.domain.reader.ReaderProgressSave
import reikai.domain.reader.ReaderProgressWrites
import reikai.domain.reader.chapterToDeleteBehind
import reikai.domain.reader.chaptersToDownloadAhead
import reikai.domain.reader.downloadedOrCurrent
import reikai.domain.reader.duplicatesOfRead
import reikai.domain.reader.isChapterComplete
import reikai.domain.reader.isForwardEligible
import reikai.domain.reader.navigableChapters
import reikai.domain.reader.neighbourChapters
import reikai.domain.reader.readerChapterFilters
import reikai.domain.readinglog.ReadingLogRepository
import reikai.domain.source.SourceKey // RK
import reikai.domain.source.isInstalled
import reikai.domain.track.TrackedChapterNumber
import reikai.domain.track.source.ChapterWrite // RK
import reikai.domain.track.source.SourceTrackerDispatcher // RK
import reikai.presentation.components.mergeSourceLabels
import reikai.presentation.reader.ChapterSwitches
import reikai.presentation.reader.ReaderLoadState
import reikai.presentation.reader.ReaderResume
import reikai.presentation.reader.unloadForReload
import tachiyomi.core.common.preference.toggle
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.history.interactor.GetNextChapters
import tachiyomi.domain.history.interactor.UpsertHistory
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetCustomMangaInfo
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.CustomMangaInfo
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.image.LocalCoverManager
import tachiyomi.source.local.isLocal
import kotlin.time.Clock

/**
 * Presenter used by the activity to perform background operations.
 */
@AssistedInject
class ReaderViewModel(
    @Assisted private val savedState: SavedStateHandle,
    @AppCoroutineScope private val appScope: CoroutineScope,
    private val context: Context,
    private val sourceManager: SourceManager,
    private val downloadManager: DownloadManager,
    private val downloadProvider: DownloadProvider,
    private val imageSaver: ImageSaver,
    val readerPreferences: ReaderPreferences,
    private val basePreferences: BasePreferences,
    private val downloadPreferences: DownloadPreferences,
    private val trackPreferences: TrackPreferences,
    private val trackChapter: TrackChapter,
    private val sourceTracker: SourceTrackerDispatcher, // RK
    private val getManga: GetManga,
    // RK: Edit info overrides, so auto-webtoon can classify from edited genres.
    private val getCustomMangaInfo: GetCustomMangaInfo,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val getNextChapters: GetNextChapters,
    private val upsertHistory: UpsertHistory,
    private val updateChapter: UpdateChapter,
    private val setMangaViewerFlags: SetMangaViewerFlags,
    private val getIncognitoState: GetIncognitoState,
    private val libraryPreferences: LibraryPreferences,
    private val coverManager: LocalCoverManager,
    private val updateManga: UpdateManga,
    private val coverCache: CoverCache,
    private val chapterCache: ChapterCache,
    private val downloadCache: DownloadCache,
    // RK -->
    private val mergedChapterProvider: MergedChapterProvider,
    private val mangaPreferences: MangaPreferences,
    private val setReadStatus: SetReadStatus,
    private val getChapter: GetChapter,
    private val chapterDownloadActions: MangaChapterDownloadActions,
    private val trackedChapterNumber: TrackedChapterNumber,
    private val readingLog: ReadingLogRepository,
    // RK <--
) : ViewModel() {

    @AssistedFactory
    @ViewModelAssistedFactoryKey(ReaderViewModel::class)
    @ContributesIntoMap(AppScope::class)
    fun interface Factory : ViewModelAssistedFactory {
        override fun create(extras: CreationExtras): ReaderViewModel {
            return create(extras.createSavedStateHandle())
        }

        fun create(@Assisted savedState: SavedStateHandle): ReaderViewModel
    }

    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()

    /**
     * Ids of the manga and chapter the reader was launched with, taken from the activity intent.
     */
    val mangaId = savedState.get<Long>("manga") ?: -1L
    private val initialChapterId = savedState.get<Long>("chapter") ?: -1L

    val hasValidArgs = mangaId != -1L && initialChapterId != -1L

    // RK: buffered rather than upstream's rendezvous, which drops from the two trySend senders
    // (preload and onPageSelected) whenever the single collector is not parked at receive. A lost
    // PageChanged does not heal: DisplayRefreshHost counts calls and flashes every nth, so one drop
    // shifts the e-ink flash phase for the rest of the session. A lost ReloadViewerChapters leaves a
    // preloaded chapter that never reaches the viewer. Upstream has the same shape.
    private val eventChannel = Channel<Event>(Channel.UNLIMITED)
    val eventFlow = eventChannel.receiveAsFlow()

    /**
     * The manga loaded in the reader. It can be null when instantiated for a short time.
     */
    val manga: Manga?
        get() = state.value.manga

    /** RK: [manga] carrying the chapter settings its merge group shares, its owner's (GroupChapterSettings). */
    val chapterSettings: Manga?
        get() = manga?.let { mergedGroup.chapterSettingsOf(it) }

    /** RK: the cover color the viewers and the loading spinner tint from; they gate on the pref themselves. */
    val coverSeedColor: Int?
        get() = manga?.asMangaCover()?.vibrantCoverColor

    /**
     * The chapter id of the currently loaded chapter. Used to restore from process kill.
     */
    private var chapterId = savedState.get<Long>("chapter_id") ?: -1L
        set(value) {
            savedState["chapter_id"] = value
            field = value
        }

    /**
     * The visible page index of the currently loaded chapter. Used to restore from process kill.
     */
    private var chapterPageIndex = savedState.get<Int>("page_index") ?: -1
        set(value) {
            savedState["page_index"] = value
            field = value
        }

    // RK -->

    /**
     * The page a launch extra asked to open at, for example a page preview jumping into a chapter.
     * It has its own key because it shares nothing but a shape with the restore index above: this is
     * a one-off jump, that is a position being kept up to date, and one slot could not be both.
     */
    private var launchPageIndex: Int? = savedState.get<Int>("launch_page")
        set(value) {
            savedState["launch_page"] = value
            field = value
        }

    /**
     * A restored index belongs to the chapter that was open when the process died, so only the first
     * chapter to load may take it. Without this the index was re-applied to every chapter that became
     * current, so each one briefly opened at the previous chapter's page.
     */
    private var restorePending = chapterPageIndex >= 0
    // RK <--

    /**
     * The chapter loader for the loaded manga. It'll be null until [manga] is set.
     */
    // RK: a merge-aware loader that routes each chapter to its own source's loader (a plain
    // single-source manga just gets one delegate, same behaviour as before).
    private var loader: MergedChapterLoader? = null

    // RK: the resolved merge group (member manga + unified chapter list). Null until [init] resolves
    // it; for an unmerged manga it holds just that manga and its own chapters.
    private var mergedGroup: MergedChapterProvider.Group? = null

    // RK: which copy of a merged chapter an open reads, one on disk over the one online. Resolved in
    // init with the group and null in source scope, which reads only the opened source's chapters.
    private var copiesToOpen: CopyToOpen<Chapter>? = null

    // RK: the copy a download of each chapter fetches, an installed source's in place of one whose source
    // is gone. Resolved in init with the group and left fetching each chapter's own in source scope.
    var downloadTargets: DownloadTargets = DownloadTargets.OWN
        private set

    // RK: the last failed preload per chapter, which ChapterRetryCooldown reads, as the novel window does.
    private val preloadFailures = ChapterRetryCooldown.Failures()

    // RK: the group's chapters whose own source is installed, the copies a download can fetch at all.
    var fetchableChapterIds: Set<Long> = emptySet()
        private set

    // RK: source scope narrows chapterList to the opened source's own chapters (Updates / a specific
    // source chip); group scope (default) shows the whole merge group. Read from the launching intent
    // like the ids above, so it survives a configuration change. mergedGroup stays full either way, so
    // the mark-duplicates-read pass over groupChapters still reaches sibling sources.
    private val sourceScoped = savedState.get<Boolean>("source_scoped") ?: false
    private val mergeScope = MergeScope.of(sourceScoped)

    // RK: resolve a chapter's own manga within the merge group, falling back to the opened manga when
    // unmerged or when the id isn't in the group. Per-chapter side effects (downloads, tracker,
    // delete-on-read) target the chapter's real source instead of the manga the reader was opened from.
    private fun mangaForChapterId(chapterMangaId: Long): Manga =
        mergedGroup?.mangaById?.get(chapterMangaId) ?: manga!!

    // RK: the chapters paging steps through from [current], by the kernel the novel reader runs too:
    // hidden ones dropped (the details screen's key), then duplicates when skip-duplicate is on, keeping
    // a copy on disk under Downloaded only, then one [isForwardEligible] lets a forward step land on.
    private fun navigable(
        chapters: List<Chapter>,
        current: Chapter,
        isForwardEligible: (Chapter) -> Boolean,
    ): List<Chapter> {
        val hidden = mangaPreferences.hiddenChapters().get()
        return chapters.navigableChapters(
            current,
            isHidden = { hidden.isNotEmpty() && it.hiddenKey(mangaForChapterId(it.mangaId)) in hidden },
            isForwardEligible = isForwardEligible,
            downloadedOnlyIds = { if (basePreferences.downloadedOnly.get()) onDiskIds(chapters) else null },
            skipDuplicates = readerPreferences.skipDupe.get(),
            numberOf = { it.chapterNumber },
            idOf = { it.id },
            originOf = { it.scanlator },
            ownerOf = { it.mangaId },
        )
    }

    // RK: which of [chapters] are on disk, each asked of its own merged source
    private fun onDiskIds(chapters: List<Chapter>): Set<Long> =
        chapters.filterDownloaded(downloadCache) { mangaForChapterId(it.mangaId) }.mapTo(HashSet()) { it.id }

    /** RK: read, bookmarked and on disk as the group answers them, for the rows in [shown]. Resolved
     *  against [shown] rather than reused from the group, whose own set names the rows the merged list
     *  shows: in source scope those are not the rows here, and the answer came back empty. [pooled]
     *  defaults to the chapters the group loaded, which a caller that must see this session's own
     *  marks replaces with a fresh read. */
    private fun groupFlags(
        shown: List<Chapter>,
        pooled: List<Chapter> = mergedGroup?.pooledChapters ?: shown,
    ): GroupChapterFlags<Chapter> = GroupChapterFlags(
        scope = mergeScope,
        pooled = pooled,
        shown = shown,
        stitch = mergedGroup?.stitch.orEmpty(),
        id = { it.id },
        read = { it.read },
        bookmark = { it.bookmark },
    ) { downloadManager.downloadedChapterIds(pooled) { mangaForChapterId(it.mangaId) } }

    /** RK: whether a forward step may land on each of [chapters], asked of the whole group through the one
     *  eligibility rule the novel readers share. The filters are the group's shared chapter settings, as
     *  the details list shows them. On disk is probed only behind a downloaded filter. */
    private fun forwardEligibility(chapters: List<Chapter>): (Chapter) -> Boolean {
        val skipRead = readerPreferences.skipRead.get()
        val skipFiltered = readerPreferences.skipFiltered.get()
        if (!skipRead && !skipFiltered) return { true }
        val flags = groupFlags(chapters)
        val filters = chapterSettings!!.readerChapterFilters()
        return { flags.isForwardEligible(it, skipRead, skipFiltered, filters) }
    }

    // RK: per-source "is this chapter downloaded" check for the transition card. Resolves the
    // chapter's OWN merged source, and uses the in-memory download cache (skipCache = false) instead
    // of a storage-access probe, so the card binds once with the right value: no main-thread stutter
    // and no late layout reflow (an icon popping in) when crossing into another merged source.
    fun isChapterDownloaded(readerChapter: ReaderChapter?): Boolean {
        val chapter = readerChapter?.chapter ?: return false
        val mangaForChapter = mangaForChapterId(chapter.mangaId)
        if (mangaForChapter.isLocal()) return true
        return downloadManager.isChapterDownloaded(
            chapter.name,
            chapter.scanlator,
            chapter.url,
            mangaForChapter.title,
            mangaForChapter.source,
        )
    }

    // RK: when the chapter was started reading, on the clock novels share (reikai.domain.reader)
    private val chapterReadSession = ReadSessionClock()

    // RK: serializes the progress writes, which outlive the reader, through the rule the novel reader shares
    private val progressWrites = ReaderProgressWrites(appScope)

    // RK: held back until the reader settles on a page, by the rule and delay the novel reader shares
    private val progressSave = ReaderProgressSave<Long, ReaderPage>(viewModelScope) { _, page ->
        progressWrites.launch { updateChapterProgress(page.chapter, page) }
    }

    // RK: the chapter's last page, counting one still held back, which its lastPageRead lags until written
    private fun ReaderChapter.lastSelectedPage(): Int =
        progressSave.heldFor(chapter.id)?.index ?: chapter.lastPageRead

    private var chapterToDownload: Download? = null

    // RK: which overlapping chapter switch may land. Main thread only.
    private val chapterSwitches = ChapterSwitches()

    private var unfilteredChapterList: List<Chapter>? = null

    private suspend fun getUnfilteredChapterList(): List<Chapter> {
        return unfilteredChapterList
            ?: groupChapters().also { unfilteredChapterList = it } // RK
    }

    // RK: every chapter of the whole merge group as stored now, so the duplicate-read pass reaches the
    // stitch's copies on sibling sources too; for an unmerged manga this is just its own chapters.
    private suspend fun groupChapters(): List<Chapter> =
        (mergedGroup?.mangaById?.keys ?: setOf(manga!!.id))
            .flatMap { getChaptersByMangaId.await(it, applyScanlatorFilter = false) }

    /**
     * Chapter list for the active manga. Loaded by [init].
     */
    // RK: the skip-filtered set. It is no longer what the reader pages over (see [fullChapterList]); it
    // only says which chapters a FORWARD step is allowed to land on.
    private lateinit var chapterList: List<ReaderChapter>

    // RK: every chapter, and the list the reader actually navigates and the chapter sheet renders.
    // "Skip chapters marked read" means do not stop on a read chapter as you move forward, not make it
    // unreachable, so going back must always reach the chapter you just finished. Sharing
    // [buildChapterList] keeps this the same deduplicated cross-source list, so the sheet, the reader and
    // the merged details list all agree on what exists.
    private lateinit var fullChapterList: List<ReaderChapter>

    // RK: completed once [init] has built both lists, which the chapter sheet and the lookups below wait for
    private val chapterListsLoaded = CompletableDeferred<Unit>()

    // RK: whether both lists are built, for a lookup made before the first open lands
    private val chapterListsReady: Boolean get() = chapterListsLoaded.isCompleted

    // RK: suspends until [init] has built both lists, so the chapter sheet never builds its rows from none
    suspend fun awaitChapterLists() = chapterListsLoaded.await()

    // RK: the group's merged list with each chapter as the copy an open reads, the list paging walks.
    private fun groupAsOpened(group: MergedChapterProvider.Group): List<Chapter> =
        copiesToOpen?.inPlaceOf(group.chapters) { copy, row -> copy.copy(sourceOrder = row.sourceOrder) }
            ?: group.chapters

    private suspend fun buildChapterList(applyReadFilter: Boolean): List<ReaderChapter> {
        val manga = manga!!
        // RK: source scope shows only the opened source's own chapters; group scope (default) shows
        // the unified cross-source list resolved in init (falling back to the single-source list if
        // accessed before init). A group-scoped chapter opened from outside the merged view (history)
        // can be deduped out of the unified list, and in either scope a chapter whose scanlator is
        // excluded is filtered out of it (mihon 2b4b8472b); both are put back through
        // withOpenedChapter, the kernel the novel readers share, looked up in the unfiltered list.
        val merged = if (sourceScoped) {
            getChaptersByMangaId.await(manga.id, applyScanlatorFilter = true)
        } else {
            // RK --> each merged chapter as the copy an open reads, so paging lands on the one on disk
            mergedGroup?.let(::groupAsOpened)
                ?: getChaptersByMangaId.await(manga.id, applyScanlatorFilter = true)
            // RK <--
        }
        val chapters = withOpenedChapter(
            unified = merged,
            opened = merged.find { it.id == chapterId } ?: getUnfilteredChapterList().find { it.id == chapterId },
            stitch = mergedGroup?.stitch.orEmpty(),
            id = { it.id },
            // A merged manga list runs newest-first, as its sources' own lists do.
            byNumber = compareByDescending { it.chapterNumber },
            restamp = { chapter, order -> chapter.copy(sourceOrder = order) },
        )

        val selectedChapter = chapters.find { it.id == chapterId }
            ?: error("Requested chapter of id $chapterId not found in chapter list")
        // RK: before the skip filters and the hidden pass, which drop chapters the series still has
        val gapPresent = chapters.gapPresent()

        // RK: asked once, so the skip filters and the duplicate pass below keep the same copy of a chapter
        val isForwardEligible = forwardEligibility(chapters)
        val chaptersForReader = when {
            // RK: only the skip-filtered list applies the skip filters
            applyReadFilter &&
                (readerPreferences.skipRead.get() || readerPreferences.skipFiltered.get()) -> {
                val filteredChapters = chapters.filter(isForwardEligible) // RK

                if (filteredChapters.any { it.id == chapterId }) {
                    filteredChapters
                } else {
                    filteredChapters + listOf(selectedChapter)
                }
            }
            else -> chapters
        }

        // RK: returned, since buildChapterList serves both chapter lists
        return chaptersForReader
            // RK: the one reading order, which the library's "download next" and Recents walk too.
            .inReadingOrder(chapterSettings!!)
            // RK --> user-hidden chapters and, with skip-duplicate on, duplicates, by the kernel the novel
            // reader runs too. The opened chapter is kept, so opening a hidden one directly still resolves.
            .let { sorted -> navigable(sorted, selectedChapter, isForwardEligible) }
            // RK <--
            .run {
                if (basePreferences.downloadedOnly.get()) {
                    // RK: by the rule the novel reader shares, which keeps the chapter being read
                    downloadedOrCurrent(selectedChapter, { it.id }, onDiskIds(this))
                } else {
                    this
                }
            }
            .map { ReaderChapter(it).also { chapter -> chapter.gapPresent = gapPresent } } // RK
    }

    // RK --> asked per chapter written, from its own source: a merged group's members can differ, and
    // upstream's one flag read at load came from the series the reader was opened from.
    private val incognito = ChapterIncognito(
        sourceOf = { owner -> SourceKey.Manga(mangaForChapterId(owner).source) },
        isIncognito = getIncognitoState::await,
    )

    // RK <--
    private val downloadAheadAmount = downloadPreferences.autoDownloadWhileReading.get()

    init {
        // RK: this collector has to be subscribed before the first chapter arrives, because the
        // three image viewers read currChapter.requestedPage directly the moment setChapters reaches
        // them. A page stamped after that is a page they never see, and the chapter silently opens at
        // its start instead of where the reader left it. It wins today only because launchIn runs
        // here during construction, ahead of the init() call below, so do not move it behind a lazy
        // or WhileSubscribed flow.
        // To save state
        state.map { it.viewerChapters?.currChapter }
            .distinctUntilChanged()
            .filterNotNull()
            .onEach { currentChapter ->
                // RK: each source of an opening page is consumed at most once, so only the chapter it
                // was meant for takes it and every later one resumes from its own stored page.
                val launchPage = launchPageIndex
                when {
                    launchPage != null -> {
                        currentChapter.requestedPage = launchPage
                        launchPageIndex = null
                    }
                    restorePending -> {
                        // Restore from SavedState
                        currentChapter.requestedPage = chapterPageIndex
                        restorePending = false
                    }
                    ReaderResume.keepsPosition(
                        currentChapter.chapter.read,
                        readerPreferences.preserveReadingPosition.get(),
                    ) -> {
                        currentChapter.requestedPage = currentChapter.lastSelectedPage() // RK
                    }
                    // A finished chapter is left alone, as upstream does: paging back into one keeps the
                    // page on screen. Opening one at its start is loadAdjacent's, a pick or a step.
                }
                chapterId = currentChapter.chapter.id
            }
            .launchIn(viewModelScope)

        if (hasValidArgs) {
            viewModelScope.launch { init() }
        }
    }

    override fun onCleared() {
        val currentChapters = state.value.viewerChapters
        if (currentChapters != null) {
            currentChapters.unref()
            chapterToDownload?.let {
                // viewModelScope is already cancelled here
                appScope.launch { downloadManager.addDownloadsToStartOfQueue(listOf(it)) }
            }
        }
    }

    /**
     * Called when the user pressed the back button and is going to leave the reader. Used to
     * trigger deletion of the downloaded chapters.
     */
    fun onActivityFinish() {
        deletePendingChapters()
    }

    /**
     * Initializes this presenter with the [mangaId] and [initialChapterId] the reader was launched
     * with. This method will fetch the manga from the database and initialize the initial chapter.
     * Failures are reported through [State.initError].
     */
    private suspend fun init() {
        withIOContext {
            try {
                val manga = getManga.await(mangaId) ?: error("Requested manga of id $mangaId not found")
                // RK: resolve the Edit info overrides before the state update below builds the
                // viewer, since auto-webtoon classifies off them.
                customInfo = getCustomMangaInfo.subscribe(mangaId).first()
                if (chapterId == -1L) chapterId = initialChapterId

                // RK --> resolve the merge group up front so the chapter list spans every grouped
                // source and the loader can route each chapter to its own source. Resolved BEFORE
                // the state update below, because that update is what makes ReaderActivity build
                // the viewer, and auto-webtoon has to see every merged member to classify.
                val group = mergedChapterProvider.load(manga)
                mergedGroup = group
                val installed = group.mangaById.values
                    .filter { sourceManager.isInstalled(it.source) }
                    .mapTo(HashSet()) { it.id }
                fetchableChapterIds = group.pooledChapters.filter { it.mangaId in installed }.mapTo(HashSet()) { it.id }
                if (mergeScope.copiesIn(group.stitch).isNotEmpty()) {
                    val pooled = group.pooledChapters
                    val onDisk = downloadManager.downloadedChapterIds(pooled) { group.mangaById.getValue(it.mangaId) }
                    downloadTargets = DownloadTargets.of(mergeScope, pooled, pooled, group.stitch, { it.id }) {
                        it.mangaId in installed
                    }
                    val copies = CopyToOpen(mergeScope, pooled, group.stitch, { it.id }, onDisk, downloadTargets)
                    copiesToOpen = copies
                    chapterId = copies.idOf(chapterId)
                }
                loader = MergedChapterLoader(
                    context,
                    downloadManager,
                    downloadProvider,
                    chapterCache,
                    appScope,
                    ::canLoadAhead,
                    readerPreferences,
                    group.mangaById,
                    sourceManager,
                )
                // RK <--

                memberSources = group.mangaById.values
                    .map { it.source }
                    .distinct()
                    .associateWith { sourceManager.getOrStub(it) }

                val source = sourceManager.getOrStub(manga.source)
                mutableState.update { it.copy(manga = manga, source = source) }

                chapterList = buildChapterList(applyReadFilter = true)
                fullChapterList = buildChapterList(applyReadFilter = false) // RK
                chapterListsLoaded.complete(Unit) // RK
                // RK: from the full list, so the reader pages within one instance space (prev/next
                //     are resolved there too) rather than mixing it with the skip-filtered set.
                loadChapter(loader!!, fullChapterList.first { chapterId == it.chapter.id })
            } catch (e: Throwable) {
                if (e is CancellationException) {
                    throw e
                }
                mutableState.update { it.copy(initError = e) }
            }
        }
    }

    /**
     * Loads the given [chapter] with this [loader] and updates the currently active chapters.
     * Callers must handle errors.
     */
    private suspend fun loadChapter(
        loader: MergedChapterLoader, // RK: loads each merged chapter from its own source
        chapter: ReaderChapter,
        // RK: the token of the page switch this load serves, or null for a pick, a step or the first open.
        pageSwitch: Long? = null,
    ): ViewerChapters {
        loader.loadChapter(chapter)

        // RK: page over the full list so BACK always reaches the chapter just finished, even once it is
        // marked read. Forward still honours the reader's skip filters by stepping to the next chapter
        // that survived them, so "skip chapters marked read" keeps working in the direction it means.
        // Matched by id, not instance: the two lists are built separately and hold different objects.
        val chapterPos = fullChapterList.indexOfFirst { it.chapter.id == chapter.chapter.id }
        val forwardIds = chapterList.mapTo(HashSet()) { it.chapter.id }
        val eligible = { candidate: ReaderChapter -> candidate.chapter.id in forwardIds }
        // RK: a page switch keeps the chapter the strip still shows beside this one, see neighbourChapters
        val departed = pageSwitch?.let { state.value.viewerChapters?.currChapter }
        val (previous, next) = fullChapterList.neighbourChapters(chapterPos, departed, eligible)
        val newChapters = ViewerChapters(chapter, previous, next)
        // RK: the bar shows the group's answer, as the details list does: a bookmark on another
        // source's copy of this chapter counts.
        val bookmarked = isBookmarkedInGroup(chapter.chapter.id)

        withUIContext {
            // RK --> a page switch something newer overtook does not land: it would put the reader back
            // on the chapter just left. It was never referenced, and the chapter it loaded is already a
            // neighbour of the live set. The references and the queue cancel run out here, once, since
            // update re-runs its block whenever a background write lands first.
            if (pageSwitch != null && !chapterSwitches.isLatest(pageSwitch)) return@withUIContext
            // Past the check, so an overtaken switch can't cancel the live chapter's preload for its own
            preloadNextChapterAfter(newChapters)
            // Add new references first to avoid unnecessary recycling
            newChapters.ref()
            state.value.viewerChapters?.unref()

            chapterToDownload = cancelQueuedDownloads(newChapters.currChapter)
            mutableState.update {
                it.copy(
                    viewerChapters = newChapters,
                    bookmarked = bookmarked,
                )
            }
            // RK <--
        }
        return newChapters
    }

    /**
     * Called when the user changed to the given [chapter] when changing pages from the viewer.
     * It's used only to set this chapter as active.
     */
    private fun loadNewChapter(chapter: ReaderChapter, pageSwitch: Long) { // RK: ChapterSwitches token
        val loader = loader ?: return chapterSwitches.finish(pageSwitch)

        viewModelScope.launchIO {
            logcat { "Loading ${chapter.chapter.url}" }

            updateHistory()
            restartReadTimer()

            try {
                loadChapter(loader, chapter, pageSwitch) // RK: ChapterSwitches token
            } catch (e: Throwable) {
                if (e is CancellationException) {
                    throw e
                }
                logcat(LogPriority.ERROR, e)
            }
            // RK: ended either way, so a failed switch is asked for again by the next page in that chapter.
            withUIContext { chapterSwitches.finish(pageSwitch) }
        }
    }

    /**
     * Called when the user is going to load the prev/next chapter through the toolbar buttons.
     */
    private suspend fun loadAdjacent(chapter: ReaderChapter) {
        val loader = loader ?: return

        logcat { "Loading adjacent ${chapter.chapter.url}" }

        // RK -->
        reportExplicitLoad(chapter.chapter.id, fromSource = false) {
            // A pick or a step outranks any switch a page asked for before it, see ChapterSwitches.
            withUIContext { chapterSwitches.beginExplicit() }
            // A finished chapter opens at its start, the gate ChapterLoader applies when it loads one.
            // Reset because the same instance serves the whole session, so the field still holds the
            // page it was last on. Only here: paging back into it must keep the page on screen.
            if (!ReaderResume.keepsPosition(chapter.chapter.read, readerPreferences.preserveReadingPosition.get())) {
                chapter.requestedPage = 0
            }
            withIOContext {
                loadChapter(loader, chapter)
            }
        }
        // RK <--
    }

    // RK --> a load the reader asked for by a step, a pick or a reload. The failure is kept, not only
    // logged, so the engine ends a pick that failed and the host says so. It is cleared as each load
    // starts, so a success goes back to idle and a repeat of the same failure is reported again.
    private suspend fun reportExplicitLoad(chapterId: Long, fromSource: Boolean, load: suspend () -> Unit) {
        // Asked for afresh, so no earlier preload failure keeps a neighbour waiting.
        preloadFailures.clear()
        mutableState.update { it.copy(isLoadingAdjacentChapter = true, adjacentLoadFailure = null) }
        try {
            load()
        } catch (e: Throwable) {
            if (e is CancellationException) {
                throw e
            }
            logcat(LogPriority.ERROR, e)
            val failure = AdjacentLoadFailure(chapterId, e.message, fromSource)
            mutableState.update { it.copy(adjacentLoadFailure = failure) }
        } finally {
            mutableState.update { it.copy(isLoadingAdjacentChapter = false) }
        }
    }
    // RK <--

    private var nextChapterPreloadJob: Job? = null

    /**
     * Preloads the next chapter of [chapters] once the current one has loaded, so it doesn't take bandwidth from the
     * pages being read. Skipped when loading ahead isn't allowed on this network.
     */
    private fun preloadNextChapterAfter(chapters: ViewerChapters) {
        val nextChapter = chapters.nextChapter ?: return
        nextChapterPreloadJob?.cancel()
        // RK: coroutine helper in place of upstream's raw dispatcher
        nextChapterPreloadJob = viewModelScope.launchIO {
            if (!canLoadAhead()) return@launchIO // RK
            val current = chapters.currChapter
            // Only online pages load in the background; other loaders load a page once it's shown
            if (current.pageLoader is HttpPageLoader) {
                current.pages.orEmpty().forEach { page ->
                    page.statusFlow.first { it == Page.State.Ready || it is Page.State.Error }
                }
            }
            preload(nextChapter)
        }
    }

    /**
     * Whether online chapters may load past the pages near the one being read.
     */
    private fun canLoadAhead(): Boolean =
        !readerPreferences.loadAheadOnlyOverWifi.get() || context.activeNetworkState().isWifi

    /**
     * Called when the viewers decide it's a good time to preload a [chapter] and improve the UX so
     * that the user doesn't have to wait too long to continue reading.
     */
    suspend fun preload(chapter: ReaderChapter, userAsked: Boolean = false) { // RK: a Retry tap skips the wait
        if (chapter.state is ReaderChapter.State.Loaded || chapter.state == ReaderChapter.State.Loading) {
            return
        }

        if (chapter.pageLoader?.isLocal == false) {
            // RK --> the guard only: the chapter's own manga is read below
            manga ?: return
            val stored = chapter.chapter
            // RK: probe the chapter's own source's download folder, not the opened manga's.
            val chapterManga = mangaForChapterId(stored.mangaId)
            val isDownloaded = downloadManager.isChapterDownloadedOnDisk(
                stored.name,
                stored.scanlator,
                stored.url,
                chapterManga.title,
                sourceManager.getOrStub(chapterManga.source),
            )
            // RK <--
            if (isDownloaded) {
                chapter.state = ReaderChapter.State.Wait
            }
        }

        if (chapter.state != ReaderChapter.State.Wait && chapter.state !is ReaderChapter.State.Error) {
            return
        }

        // RK --> the viewers ask on every page turn, so a chapter that just failed waits out the rule the
        // novel window keeps too; the user's Retry ends the wait.
        val chapterId = chapter.chapter.id
        if (userAsked) preloadFailures.remove(chapterId)
        if (!preloadFailures.mayRetryUnprompted(chapterId, SystemClock.elapsedRealtime())) return
        // RK <--
        val loader = loader ?: return
        try {
            logcat { "Preloading ${chapter.chapter.url}" }
            loader.loadChapter(chapter)
        } catch (e: Throwable) {
            if (e is CancellationException) {
                throw e
            }
            preloadFailures.record(chapterId, SystemClock.elapsedRealtime(), e.message) // RK
            return
        }
        preloadFailures.remove(chapterId) // RK
        eventChannel.trySend(Event.ReloadViewerChapters)
    }

    /**
     * Called every time a page changes on the reader. Used to mark the flag of chapters being
     * read, update tracking services, enqueue downloaded chapter deletion, and updating the active chapter if this
     * [page]'s chapter is different from the currently active.
     */
    fun onPageSelected(page: ReaderPage) {
        // InsertPage doesn't change page progress
        if (page is InsertPage) {
            return
        }

        val selectedChapter = page.chapter
        val pages = selectedChapter.pages ?: return

        // RK --> the in-memory position is written here, on the main thread and so in page order. From
        // the background save below, two pages could land out of order and the chrome show the earlier.
        mutableState.update {
            it.copy(position = ReaderPosition(selectedChapter.chapter.id, pageProgress(selectedChapter, page)))
        }
        selectedChapter.requestedPage = page.index
        chapterPageIndex = page.index
        // RK <--

        // Save last page read and mark as read if needed. The database calls are main-safe.
        // RK: finishing the chapter is saved at once, any other page once the reader settles on it
        progressSave.report(
            selectedChapter.chapter.id,
            page,
            now = pageProgress(selectedChapter, page).isChapterComplete,
        )

        // RK: compared with a switch still loading too, not only the active chapter, see ChapterSwitches.
        chapterSwitches.requestFromPage(selectedChapter.chapter.id, getCurrentChapter()?.chapter?.id)?.let {
            logcat { "Setting ${selectedChapter.chapter.url} as active" }
            loadNewChapter(selectedChapter, it)
        }

        val inDownloadRange = page.number.toDouble() / pages.size > 0.25
        if (inDownloadRange) {
            downloadNextChapters()
        }

        eventChannel.trySend(Event.PageChanged)
    }

    private fun downloadNextChapters() {
        if (downloadAheadAmount == 0) return
        val manga = manga ?: return

        // Only download ahead if current + next chapter is already downloaded too to avoid jank
        if (getCurrentChapter()?.pageLoader !is DownloadPageLoader) return
        val nextChapter = state.value.viewerChapters?.nextChapter?.chapter ?: return

        viewModelScope.launchIO {
            // RK --> download-ahead follows the next chapter's OWN source across a merge boundary.
            val nextChapterManga = mangaForChapterId(nextChapter.mangaId)
            val isNextChapterDownloaded = downloadManager.isChapterDownloaded(
                nextChapter.name,
                nextChapter.scanlator,
                nextChapter.url,
                nextChapterManga.title,
                nextChapterManga.source,
            )
            // RK <--
            if (!isNextChapterDownloaded) return@launchIO

            // RK: on a merged series the chapters ahead belong to whichever sources carry them, so the
            // target is the group's own cross-source list rather than the next chapter's source alone.
            // The group's list, not the reader's: that one is narrowed by the display filters, and in
            // downloaded-only mode it holds nothing left to fetch.
            // Only a group-scoped session reads that list. A source-scoped one pages over its own
            // source's rows, so walking the group's would queue a sibling's chapters it never stops on
            // and leave its own next ones out. And the list can only answer for a chapter it kept.
            // Walked as opened, so a chapter whose sibling copy is on disk is that copy, not one to fetch.
            val opened = mergedGroup?.takeIf { !sourceScoped && it.isMerged }?.let(::groupAsOpened)
                ?.takeIf { chapters -> chapters.any { c -> c.id == nextChapter.id } }
            val chaptersToDownload = if (opened != null) {
                // Sorted the way the reader itself pages, not by stitch position: a group sorted by
                // upload date or by name otherwise queues chapters the reader never steps into next.
                // RK: through the reader's own rule, or it queued chapters the reader never stops on.
                val ahead = opened.inReadingOrder(mergedGroup.chapterSettingsOf(manga))
                    .let { sorted -> navigable(sorted, nextChapter, forwardEligibility(sorted)) }
                chaptersToDownloadAhead(
                    ahead,
                    from = ahead.indexOfFirst { it.id == nextChapter.id },
                    count = downloadAheadAmount,
                    isRead = groupFlags(opened)::isRead,
                )
            } else {
                // RK --> ordered by the group's shared chapter settings, the settings owner's, as the reader pages, not
                // the member's own sort GetNextChapters reads. From the next chapter on, as upstream slices.
                getNextChapters.await(nextChapterManga.id)
                    .inReadingOrder(mergedGroup.chapterSettingsOf(nextChapterManga))
                    .let { unread -> unread.drop(unread.indexOfFirst { it.id == nextChapter.id }.coerceAtLeast(0)) }
                    // RK <--
                    .let { next -> navigable(next, nextChapter, forwardEligibility(next)) }
                    // RK: a source-scoped session on a merged series skips what another source read too.
                    .let { own -> chaptersToDownloadAhead(own, 0, downloadAheadAmount, groupFlags(own)::isRead) }
            }

            // RK: a chapter whose source is gone fetches an installed source's copy, or nothing
            downloadCopiesOf(chaptersToDownload).groupBy { it.mangaId }.forEach { (ownerId, owned) ->
                downloadManager.downloadChapters(mangaForChapterId(ownerId), owned)
            }
        }
    }

    /**
     * Removes [currentChapter] from download queue
     * if setting is enabled and [currentChapter] is queued for download
     */
    private suspend fun cancelQueuedDownloads(currentChapter: ReaderChapter): Download? {
        return downloadManager.getQueuedDownloadOrNull(currentChapter.chapter.id)?.also {
            downloadManager.cancelQueuedDownloads(listOf(it))
        }
    }

    /**
     * Determines if deleting option is enabled and nth to last chapter actually exists.
     * If both conditions are satisfied enqueues chapter for delete
     * @param currentChapter current chapter, which is going to be marked as read.
     */
    private suspend fun deleteChapterIfNeeded(currentChapter: ReaderChapter) {
        val removeAfterReadSlots = downloadPreferences.removeAfterReadSlots.get()
        if (removeAfterReadSlots == -1) return

        // Determine which chapter should be deleted and enqueue
        // RK: by the slot rule the novel reader shares, positioned in the full list by id. The reader pages
        //     over that list, so indexing the skip-filtered one would not find the current chapter at all.
        val chapterToDelete = fullChapterList.chapterToDeleteBehind(currentChapter.chapter.id, removeAfterReadSlots) {
            it.chapter.id
        }

        // If chapter is completely read, no need to download it
        chapterToDownload = null

        if (chapterToDelete == null || !chapterToDelete.chapter.read) return

        val markDuplicateAsRead = libraryPreferences.markDuplicateReadChapterAsRead.get()
            .contains(LibraryPreferences.MARK_DUPLICATE_CHAPTER_READ_EXISTING)
        enqueueDeleteChapters(chapterToDelete.chapter, markDuplicateAsRead) // RK
    }

    // RK: the position names its own chapter, so the chrome cannot pair this page number with a
    // different chapter's total while the model is still swapping the active chapter across a seam.
    private fun pageProgress(readerChapter: ReaderChapter, page: Page) = ChapterProgress.Pages(
        lastPageRead = page.index,
        pageCount = readerChapter.pages?.size ?: 0,
    )

    /**
     * Saves the chapter progress (last read page and whether it's read)
     * if incognito mode isn't on.
     */
    private suspend fun updateChapterProgress(readerChapter: ReaderChapter, page: Page) {
        val pageIndex = page.index
        // RK: the in-memory position, requestedPage and chapterPageIndex are written by onPageSelected.
        val progress = pageProgress(readerChapter, page)

        // RK: asked under the lock ReaderProgressWrites takes, which the order of the writes rests on
        if (!incognito.of(readerChapter.chapter.mangaId) && page.status !is Page.State.Error) { // RK
            val wasRead = readerChapter.chapter.read // RK: the finish below reports only a newly read chapter
            val read = wasRead || progress.isChapterComplete // RK: the kernel's completion rule
            readerChapter.update { it.copy(lastPageRead = pageIndex, read = read) }

            if (progress.isChapterComplete) {
                updateChapterProgressOnComplete(readerChapter, wasRead) // RK: wasRead
            }

            updateChapter.await(
                ChapterUpdate(readerChapter.chapter.id) {
                    this.read = read
                    lastPageRead = pageIndex
                    // RK: the reader is the only thing that ever knows this, so it is written on every
                    // save rather than once, which is also what re-heals a total left stale by a source
                    // re-paginating the chapter.
                    pageCount = readerChapter.pages?.size
                },
            )
        }
    }

    private suspend fun updateChapterProgressOnComplete(readerChapter: ReaderChapter, wasRead: Boolean) { // RK
        // RK --> a finish of a chapter already read, which every save on its last page is, tells the
        // source's own site nothing, as a novel finish does
        val newlyRead = readerChapter.chapter.takeUnless { wasRead }
            ?.let { ChapterWrite(EntryId.Manga(it.mangaId), it.id, wasRead = false) }
        // RK <--
        updateTrackChapterRead(readerChapter)
        deleteChapterIfNeeded(readerChapter)

        val markDuplicateAsRead = libraryPreferences.markDuplicateReadChapterAsRead.get()
            .contains(LibraryPreferences.MARK_DUPLICATE_CHAPTER_READ_EXISTING)
        if (!markDuplicateAsRead) return sourceTracker.readStateWritten(true, listOfNotNull(newlyRead)) // RK

        // RK: upstream's same-number match, kept inside the chapter's own entry, plus the group's copies
        // the stored stitch places with it. A number match across sources marked a chapter several
        // along on the sibling, since two sources of one series count differently. Read fresh, as the
        // novel finish does: the chapters loaded at open miss a copy unmarked or fetched since.
        val readChapter = readerChapter.chapter
        val duplicateUnreadChapters = groupChapters()
            .duplicatesOfRead(
                readChapter,
                groupCopyIds(readChapter.id).toSet(),
                numberOf = { it.chapterNumber },
                idOf = { it.id },
                ownerOf = { it.mangaId },
            )
            // RK: a copy on a source in incognito is not written, as its own chapter would not be
            .filterNot { it.read || incognito.of(it.mangaId) }
        // RK --> kept as chapters until written, so the source's own tracker hears which entry each is in
        updateChapter.awaitAll(duplicateUnreadChapters.map { ChapterUpdate(it.id) { read = true } })
        sourceTracker.readStateWritten(
            true,
            listOfNotNull(newlyRead) +
                duplicateUnreadChapters.map { ChapterWrite(EntryId.Manga(it.mangaId), it.id, wasRead = false) },
        )
        // RK <--
    }

    // RK --> mark-read-on-skip: mark the chapter the user skipped past (forward only) as read, opt-in.
    // Reuses updateChapterProgressOnComplete for tracker sync / delete-on-read / duplicates;
    // it does not persist the read flag itself (its normal caller does during a page-progress
    // save, which a forward skip never reaches), so we persist it here. Twin of
    // NovelReaderViewModel.markReadOnSkip, pinned by MarkReadOnSkipConformanceTest.
    fun markChapterReadOnSkip(readerChapter: ReaderChapter) {
        if (readerChapter.chapter.read || !readerPreferences.markReadOnSkip.get()) return
        progressWrites.launch {
            if (incognito.of(readerChapter.chapter.mangaId)) return@launch
            readerChapter.update { it.copy(read = true) }
            updateChapterProgressOnComplete(readerChapter, wasRead = false)
            updateChapter.await(
                ChapterUpdate(readerChapter.chapter.id) { read = true },
            )
        }
    }
    // RK <--

    fun restartReadTimer() {
        chapterReadSession.start(Clock.System.now()) // RK
    }

    // RK: the host calls this on pause, so a kill cannot drop the page the reader settled on
    fun flushProgress() = progressSave.flush()

    /**
     * Saves the chapter last read history on the app scope, so it's written even when the reader is closing.
     */
    fun saveHistory() {
        // Immediate so the read time is taken when the reader pauses
        appScope.launch(Dispatchers.Main.immediate) { updateHistory() }
    }

    /**
     * Saves the chapter last read history if incognito mode isn't on.
     */
    private suspend fun updateHistory() {
        getCurrentChapter()?.let { readerChapter ->
            if (incognito.of(readerChapter.chapter.mangaId)) return@let // RK

            val chapterId = readerChapter.chapter.id
            val endTime = Clock.System.now()
            // RK: read and cleared in one step before the save awaits (mihon 553762fae)
            val sessionReadDuration = chapterReadSession.take(endTime)

            upsertHistory.await(HistoryUpdate(chapterId, endTime, sessionReadDuration))
            readingLog.record(ContentType.MANGA, chapterId, endTime, sessionReadDuration) // RK
        }
    }

    /**
     * Called from the activity to load and set the next chapter as active.
     */
    suspend fun loadNextChapter(): Boolean { // RK: whether the reader moved
        val nextChapter = state.value.viewerChapters?.nextChapter ?: return false // RK
        // RK --> mark-read-on-skip: the chapter being left behind, marked only once the step landed,
        // since a failed load leaves the reader in it
        val departedChapter = getCurrentChapter()
        loadAdjacent(nextChapter)
        val moved = getCurrentChapter()?.chapter?.id == nextChapter.chapter.id
        if (moved) departedChapter?.let { markChapterReadOnSkip(it) }
        return moved
        // RK <--
    }

    /**
     * Called from the activity to load and set the previous chapter as active.
     */
    suspend fun loadPreviousChapter(): Boolean { // RK: whether the reader moved
        val prevChapter = state.value.viewerChapters?.prevChapter ?: return false // RK
        // RK -->
        loadAdjacent(prevChapter)
        return getCurrentChapter()?.chapter?.id == prevChapter.chapter.id
        // RK <--
    }

    /**
     * Returns the currently active chapter.
     */
    private fun getCurrentChapter(): ReaderChapter? {
        return state.value.currentChapter
    }

    // RK: the source of the chapter being read, not of the manga the reader was opened from. In a
    // merged group the current chapter often comes from a sibling site, and the web, browser and share
    // actions (plus the WebView's header source) have to resolve there. Falls back to the opened
    // manga's source before the first chapter arrives and for an unmerged series.
    fun getSource(): HttpSource? {
        val chapter = getCurrentChapter()?.chapter ?: return state.value.source as? HttpSource
        return sourceOf(chapter)
    }

    // RK --> by id, for a chapter that failed to open and so is not the current one
    fun getSource(chapterId: Long): HttpSource? = chapterById(chapterId)?.let(::sourceOf) ?: getSource()

    fun getChapterUrl(chapterId: Long): String? {
        val chapter = chapterById(chapterId) ?: return null
        val source = sourceOf(chapter) ?: return null
        return try {
            source.getChapterUrl(chapter.toSChapter())
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            null
        }
    }

    // From every chapter: the skip-filtered list leaves out a read one, which can fail to load as well.
    // The readiness check comes first: a first open can fail before the lateinit list is assigned.
    private fun chapterById(chapterId: Long) = if (!chapterListsReady) {
        null
    } else {
        fullChapterList.firstOrNull { it.chapter.id == chapterId }?.chapter
    }

    /** The chapter the reader is opening, which a first open that failed is about. */
    val openingChapterId: Long get() = if (chapterId == -1L) initialChapterId else chapterId

    /** Runs a first open that failed again, from the reader's failure dialog. */
    fun retryInit() {
        if (state.value.initError == null) return
        mutableState.update { it.copy(initError = null) }
        viewModelScope.launch { init() }
    }

    private fun sourceOf(chapter: Chapter): HttpSource? =
        (memberSources[mangaForChapterId(chapter.mangaId).source] ?: state.value.source) as? HttpSource
    // RK <--

    // RK: getChapterUrl() removed; the reader builds a chapter's URL by id through getChapterUrl(chapterId)

    /**
     * Bookmarks the currently active chapter.
     */
    fun toggleChapterBookmark() {
        val readerChapter = getCurrentChapter() ?: return
        // RK --> flipped from the group's answer the bar shows, and written to every source's copy
        // through the chapter sheet's verb, which also moves the bar.
        val bookmarked = !state.value.bookmarked
        toggleBookmark(readerChapter.chapter.id, bookmarked)
        // RK <--
    }

    /**
     * Returns the viewer position used by this manga or the default one.
     */
    fun getMangaReadingMode(resolveDefault: Boolean = true): Int {
        val default = readerPreferences.defaultReadingMode.get()
        val readingMode = ReadingMode.fromPreference(manga?.readingMode?.toInt())
        return when {
            // RK: auto-webtoon only fills in for a series the user never chose a mode for, so it
            // rides the existing DEFAULT branch and inherits its resolveDefault guard.
            resolveDefault && readingMode == ReadingMode.DEFAULT -> autoWebtoonMode() ?: default
            else -> manga?.readingMode?.toInt() ?: default
        }
    }

    // RK -->
    private var autoWebtoonMemo: Pair<Set<Long>, Int?>? = null

    // RK: the source behind every merged member, resolved once in init and keyed by source id.
    // Resolving one suspends, and both the auto-webtoon classifier (on every app-bar recomposition)
    // and the web actions ask for one off the render path.
    private var memberSources: Map<Long, Source> = emptyMap()

    /**
     * The user's Edit info overrides, snapshotted in [init]. Editing needs the details screen, so
     * these cannot change while the reader is open.
     */
    private var customInfo: CustomMangaInfo? = null

    /**
     * The mode auto-webtoon picks for this series, or null when it does not apply: the preference
     * is off, the user picked a mode for this series, or no merged source calls it long strip.
     *
     * The toast reads this too, so both it and the viewer resolve from one predicate and cannot
     * disagree. Memoized on the member ids, because [getMangaReadingMode] runs on every app-bar
     * recomposition while a group's genres and sources cannot change mid-session.
     */
    fun autoWebtoonMode(): Int? {
        if (!readerPreferences.autoWebtoonMode.get()) return null
        val manga = manga ?: return null
        if (ReadingMode.fromPreference(manga.readingMode.toInt()) != ReadingMode.DEFAULT) return null
        val members = mergedGroup?.mangaById?.values?.takeIf { it.isNotEmpty() } ?: listOf(manga)
        val memberIds = members.mapTo(mutableSetOf()) { it.id }
        autoWebtoonMemo?.takeIf { it.first == memberIds }?.let { return it.second }
        // Edited genres win over the source's, so a source that never tags its series type can be
        // fixed by hand in Edit info. They belong to the opened entry, not to its siblings.
        val entries = members.map { if (it.id == manga.id) it.withCustomInfo(customInfo) else it }
        return defaultReaderType(entries) { memberSources[it.source]?.name }
            .also { autoWebtoonMemo = memberIds to it }
    }
    // RK <--

    /**
     * Updates the viewer position for the open manga.
     */
    private var readingModeJob: Job? = null

    fun setMangaReadingMode(readingMode: ReadingMode) {
        val manga = manga ?: return
        // Only the latest pick matters, and letting an older one finish last would restore its choice
        readingModeJob?.cancel()
        readingModeJob = viewModelScope.launchIO {
            setMangaViewerFlags.awaitSetReadingMode(manga.id, readingMode.flagValue.toLong())
            val updatedManga = getManga.await(manga.id)
            val currChapters = state.value.viewerChapters
            if (currChapters != null) {
                // RK: the update below re-emits this same ViewerChapters instance, which the
                // distinctUntilChanged in front of both the stamping collector above and the
                // activity's viewerChapters collector swallows. So the event sent afterwards is the
                // only thing that reaches the viewer, and the page has to be stamped by hand here
                // because the collector that normally does it never runs.
                // Save current page
                val currChapter = currChapters.currChapter
                currChapter.requestedPage = currChapter.lastSelectedPage() // RK

                mutableState.update { it.copy(manga = updatedManga) }
                eventChannel.send(Event.ReloadViewerChapters)
            }
        }
    }

    /**
     * Returns the orientation type used by this manga or the default one.
     */
    fun getMangaOrientation(resolveDefault: Boolean = true): Int {
        val default = readerPreferences.defaultOrientationType.get()
        val orientation = ReaderOrientation.fromPreference(manga?.readerOrientation?.toInt())
        return when {
            resolveDefault && orientation == ReaderOrientation.DEFAULT -> default
            else -> manga?.readerOrientation?.toInt() ?: default
        }
    }

    /**
     * Updates the orientation type for the open manga.
     */
    private var orientationJob: Job? = null

    fun setMangaOrientationType(orientation: ReaderOrientation) {
        val manga = manga ?: return
        // Only the latest pick matters, and letting an older one finish last would restore its choice
        orientationJob?.cancel()
        orientationJob = viewModelScope.launchIO {
            setMangaViewerFlags.awaitSetOrientation(manga.id, orientation.flagValue.toLong())
            val updatedManga = getManga.await(manga.id)
            val currChapters = state.value.viewerChapters
            if (currChapters != null) {
                // RK: same re-emit and hand-stamp as setMangaReadingMode, for the same reason.
                // Save current page
                val currChapter = currChapters.currChapter
                currChapter.requestedPage = currChapter.lastSelectedPage() // RK

                mutableState.update { it.copy(manga = updatedManga) }
                eventChannel.send(Event.SetOrientation(getMangaOrientation()))
                eventChannel.send(Event.ReloadViewerChapters)
            }
        }
    }

    fun toggleCropBorders(): Boolean {
        val isPagerType = ReadingMode.isPagerType(getMangaReadingMode())
        return if (isPagerType) {
            readerPreferences.cropBorders.toggle()
        } else {
            readerPreferences.cropBordersWebtoon.toggle()
        }
    }

    /**
     * Generate a filename for the given [manga] and [page]
     */
    private fun generateFilename(
        manga: Manga,
        page: ReaderPage,
    ): String {
        val chapter = page.chapter.chapter
        val filenameSuffix = " - ${page.number}"
        return DiskUtil.buildValidFilename(
            "${manga.title} - ${chapter.name}",
            DiskUtil.MAX_FILE_NAME_BYTES - filenameSuffix.byteSize(),
        ) + filenameSuffix
    }

    fun showMenus(visible: Boolean) {
        mutableState.update { it.copy(menuVisible = visible) }
    }

    // RK --> the in-reader chapter dialog's list

    /** Snapshot of the reader's chapter list for the in-reader chapter dialog. */
    fun getChapters(): List<ReaderChapterItem> {
        if (manga == null || !chapterListsReady) return emptyList()
        // RK: in a merged group each row carries its source's name as a label, by the kernel every
        // chapter list shares; an unmerged manga gets none. Its download state is the group's, from sheetFlags.
        val sourceNames = mergeSourceLabels(mergedGroup?.sourceNameByMangaId.orEmpty())
        // RK: the sheet lists every chapter, including ones the reader's skip-read navigation steps
        // over. Filtering them out here made already-read chapters look like they did not exist.
        return fullChapterList.map {
            ReaderChapterItem(
                chapter = it.chapter,
                sourceName = sourceNames[it.chapter.mangaId],
            )
        }
        // RK <--
    }

    // RK --> the open chapter loaded again at the page on screen. From the source it skips a downloaded
    // copy and drops the cached page list and images, which the page loader would otherwise serve again.
    fun reloadChapter(fromSource: Boolean) {
        val loader = loader ?: return
        val chapter = state.value.viewerChapters?.currChapter ?: return
        viewModelScope.launchIO {
            reportExplicitLoad(chapter.chapter.id, fromSource) {
                chapter.unloadForReload(fromSource, chapterCache)
                loader.loadChapter(chapter, fromSource)
                val lastPage = chapter.pages?.lastIndex ?: 0
                chapter.requestedPage = chapterPageIndex.coerceIn(0, maxOf(0, lastPage))
                eventChannel.send(Event.ReloadedChapter)
            }
        }
    }
    // RK <--

    /** Jump to an arbitrary chapter chosen in the chapter dialog. */
    fun loadNewChapterFromDialog(chapter: Chapter) {
        viewModelScope.launchIO {
            // RK: resolve against the sheet's list, so tapping an already-read chapter actually opens it
            // instead of silently doing nothing when skip-read has removed it from the navigation list.
            val newChapter = fullChapterList.firstOrNull { it.chapter.id == chapter.id }
                ?: return@launchIO mutableState.update {
                    val failure = AdjacentLoadFailure(chapter.id, null, false)
                    it.copy(adjacentLoadFailure = failure)
                }
            loadAdjacent(newChapter)
        }
    }

    // RK: the chapter-list swipe actions, read through the kernel every chapter list shares.
    val chapterSwipeStartAction = libraryPreferences.chapterSwipeActions().start
    val chapterSwipeEndAction = libraryPreferences.chapterSwipeActions().end

    // RK: the sheet's list and the navigation list are built separately, so they hold different chapter
    // instances for the same row, and a chapter the navigation list skipped (read, with skip-read on)
    // is only in the sheet's. A dialog action therefore has to update every copy it finds, or the change
    // shows in one list and not the other, or silently does nothing for a read chapter.
    private fun chapterCopies(chapterId: Long) = if (!chapterListsReady) {
        emptyList()
    } else {
        listOfNotNull(
            chapterList.find { it.chapter.id == chapterId },
            fullChapterList.find { it.chapter.id == chapterId },
        )
    }

    // RK: every source's copy of a chapter within the merge group, so a read or bookmark from the chapter
    // sheet applies group-wide like the details screen's does, instead of only to the source the row came
    // from. Reads the stored stitch, which is the one place that decides what "the same chapter" means:
    // matching on the number instead reached a chapter several along on a sibling source, and collapsed
    // every gallery source's chapter 1 into one. Falls back to the chapter alone when unmerged.
    private fun groupCopyIds(chapterId: Long): List<Long> {
        val stitch = mergedGroup?.takeIf { it.isMerged }?.stitch ?: return listOf(chapterId)
        return expandToUnits(setOf(chapterId), stitch).toList().ifEmpty { listOf(chapterId) }
    }

    /** Toggle the bookmark of an arbitrary chapter from the chapter dialog. */
    fun toggleBookmark(chapterId: Long, bookmarked: Boolean) {
        val ids = groupCopyIds(chapterId)
        val copies = ids.flatMap { chapterCopies(it) }
        if (copies.isEmpty()) return
        copies.forEach { copy -> copy.update { it.copy(bookmark = bookmarked) } }
        // Kept in step so the sheet and the app bar cannot disagree about the chapter being read.
        if (getCurrentChapter()?.chapter?.id in ids) mutableState.update { it.copy(bookmarked = bookmarked) }
        // On Main so quick toggles are saved in order
        viewModelScope.launch {
            updateChapter.awaitAll(ids.map { ChapterUpdate(it) { bookmark = bookmarked } })
        }
    }

    /** Whether any source's copy of [chapterId] is bookmarked, read fresh: the copies the reader does
     *  not list live only in the database. */
    private suspend fun isBookmarkedInGroup(chapterId: Long): Boolean =
        groupCopyIds(chapterId).any { getChapter.await(it)?.bookmark == true }

    /**
     * The group's read, bookmarked and on-disk answer for the chapter sheet's [rows]. The group's
     * chapters are read fresh, because the ones loaded at open do not know what this session has
     * marked since, and a copy the sheet does not list is only in the database.
     */
    suspend fun sheetFlags(rows: List<Chapter>): GroupChapterFlags<Chapter> {
        val members = mergedGroup?.takeIf { it.isMerged }?.mangaById?.keys
            ?: return groupFlags(rows, pooled = rows)
        return groupFlags(
            rows,
            pooled = members.flatMap {
                getChaptersByMangaId.await(it, applyScanlatorFilter = true)
            },
        )
    }

    // RK --> each sheet row's stored page count, read fresh because the chapters the reader holds carry the
    // count as it stood at open, so the sheet can say "Page 6/38" as the details list does.
    suspend fun sheetPageCounts(): Map<Long, Int> =
        (mergedGroup?.mangaById?.keys ?: listOfNotNull(manga?.id))
            .flatMap { getChaptersByMangaId.await(it) }
            .associate { it.id to it.pageCount }
    // RK <--

    // RK -->

    /** Set the read state of an arbitrary chapter from the chapter dialog. Uses SetReadStatus so tracker
     *  sync + delete-after-read fire like the details "mark as read", not just a raw read-flag write.
     *  The targets are read fresh: SetReadStatus skips a chapter already in the state asked for, and
     *  the chapters loaded at open do not know what this session has marked since. */
    fun setChapterReadStatus(chapter: Chapter, read: Boolean) {
        val ids = groupCopyIds(chapter.id)
        ids.forEach { id -> chapterCopies(id).forEach { copy -> copy.update { it.copy(read = read) } } }
        appScope.launch {
            val targets = ids.mapNotNull { getChapter.await(it) }.ifEmpty { listOf(chapter) }
            setReadStatus.await(read, *targets.toTypedArray())
        }
    }

    /** Start/cancel/delete a chapter download from the chapter dialog, each from its own source's folder. */
    fun handleChapterDownload(chapter: Chapter, action: ChapterDownloadAction) {
        manga ?: return
        // The queue edit or delete outlives the reader, which is often closed straight after the tap
        appScope.launch {
            val chapters = rowDownloadChapters(action, listOf(chapter), downloadCopiesOf(listOf(chapter))) { it.id }
            chapterDownloadActions.run(action, chapters) {
                // The copies this session's rows count as downloaded: every source's in group scope,
                // the chapter's own in source scope.
                val copies = mergeScope.copiesOf(setOf(chapter.id), mergedGroup?.stitch.orEmpty())
                getUnfilteredChapterList().filter { it.id in copies }.ifEmpty { listOf(chapter) }
            }
        }
    }

    /** [chapters] as the copies a download fetches ([downloadTargets]), out of the group's chapters. */
    private fun downloadCopiesOf(chapters: List<Chapter>): List<Chapter> =
        downloadTargets.of(chapters, mergedGroup?.pooledChapters.orEmpty() + chapters) { it.id }
    // RK <--

    fun setBrightnessOverlayValue(value: Int) {
        mutableState.update { it.copy(brightnessOverlayValue = value) }
    }

    /**
     * Saves the image of the selected page on the pictures directory and notifies the UI of the result.
     * There's also a notification to allow sharing the image somewhere else or deleting it.
     */
    fun saveImage(page: ReaderPage) { // RK: page from MangaReaderProvider
        if (page.status != Page.State.Ready) return
        val manga = manga ?: return

        val notifier = SaveImageNotifier(context)
        notifier.onClear()

        val filename = generateFilename(manga, page)

        // Pictures directory.
        val relativePath = if (readerPreferences.folderPerManga.get()) {
            DiskUtil.buildValidFilename(
                manga.title,
            )
        } else {
            ""
        }

        // The save and its notification outlive the reader, the result event doesn't
        viewModelScope.launch {
            val result = appScope.async {
                try {
                    val uri = imageSaver.save(
                        image = Image.Page(
                            inputStream = page.stream!!,
                            name = filename,
                            location = Location.Pictures.create(relativePath),
                        ),
                    )
                    withUIContext { notifier.onComplete(uri) }
                    SaveImageResult.Success(uri)
                } catch (e: Throwable) {
                    notifier.onError(e.message)
                    SaveImageResult.Error(e)
                }
            }.await()
            eventChannel.send(Event.SavedImage(result))
        }
    }

    /**
     * Shares the image of the selected page and notifies the UI with the path of the file to share.
     * The image must be first copied to the internal partition because there are many possible
     * formats it can come from, like a zipped chapter, in which case it's not possible to directly
     * get a path to the file and it has to be decompressed somewhere first. Only the last shared
     * image will be kept so it won't be taking lots of internal disk space.
     */
    fun shareImage(page: ReaderPage, copyToClipboard: Boolean) { // RK: page from MangaReaderProvider
        if (page.status != Page.State.Ready) return
        val manga = manga ?: return

        val destDir = context.cacheImageDir

        val filename = generateFilename(manga, page)

        viewModelScope.launchIO {
            try {
                destDir.deleteRecursively()
                val uri = imageSaver.save(
                    image = Image.Page(
                        inputStream = page.stream!!,
                        name = filename,
                        location = Location.Cache,
                    ),
                )
                eventChannel.send(if (copyToClipboard) Event.CopyImage(uri) else Event.ShareImage(uri, page))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logcat(LogPriority.ERROR, e)
            }
        }
    }

    /**
     * Sets the image of the selected page as cover and notifies the UI of the result.
     */
    fun setAsCover(page: ReaderPage) { // RK: page from MangaReaderProvider
        if (page.status != Page.State.Ready) return
        val manga = manga ?: return
        val stream = page.stream ?: return

        viewModelScope.launch {
            val result = appScope.async {
                try {
                    manga.editCover(coverManager, stream(), updateManga, coverCache)
                    if (manga.isLocal() || manga.favorite) {
                        SetAsCoverResult.Success
                    } else {
                        SetAsCoverResult.AddToLibraryFirst
                    }
                } catch (e: Exception) {
                    SetAsCoverResult.Error
                }
            }.await()
            eventChannel.send(Event.SetCoverResult(result))
        }
    }

    enum class SetAsCoverResult {
        Success,
        AddToLibraryFirst,
        Error,
    }

    sealed interface SaveImageResult {
        class Success(val uri: Uri) : SaveImageResult
        class Error(val error: Throwable) : SaveImageResult
    }

    /**
     * Starts the service that updates the last chapter read in sync services. This operation
     * will run in a background thread and errors are ignored.
     */
    private suspend fun updateTrackChapterRead(readerChapter: ReaderChapter) {
        if (incognito.of(readerChapter.chapter.mangaId)) return // RK
        if (!trackPreferences.autoUpdateTrack.get()) return

        manga ?: return
        // RK: sync the tracker of the chapter's OWN source (per-source; trackers are propagated across
        // the merge group anyway, so this stays consistent after an unmerge).
        val chapterManga = mangaForChapterId(readerChapter.chapter.mangaId)

        // Reading the last page and leaving right away is common, so it outlives the reader
        appScope.launch {
            // RK: the number a merged series' trackers count by, the trunk's copy's (TrackedChapterNumber)
            val chapterNumber = trackedChapterNumber.ofManga(listOf(readerChapter.chapter)).single()
            trackChapter.await(context, chapterManga.id, chapterNumber)
        }
    }

    // RK --> [chapter] and, with [withDuplicates], the copies the shared duplicatesOfRead rule marks read
    // with it, in place of upstream's number match over the whole list, which across a merge group
    // reaches chapters of other numbers. Read fresh, as the mark is, and each copy deleted from its own
    // source's downloads. DeleteDuplicatesBehindReaderConformanceTest pins it to the novel trim.

    /**
     * Enqueues these chapters to be deleted when [deletePendingChapters] is called. The download
     * manager handles persisting them across process deaths.
     */
    private suspend fun enqueueDeleteChapters(chapter: Chapter, withDuplicates: Boolean) {
        manga ?: return
        val duplicates = if (!withDuplicates) {
            emptyList()
        } else {
            groupChapters()
                .duplicatesOfRead(
                    chapter,
                    groupCopyIds(chapter.id).toSet(),
                    numberOf = { it.chapterNumber },
                    idOf = { it.id },
                    ownerOf = { it.mangaId },
                )
                // Finishing a chapter trims before its own copies are marked read, so they may not be yet
                .map { it.copy(read = true) }
        }
        (listOf(chapter) + duplicates).groupBy { it.mangaId }.forEach { (ownerId, owned) ->
            downloadManager.enqueueChaptersToDelete(owned, mangaForChapterId(ownerId))
        }
    }
    // RK <--

    /**
     * Deletes all the pending chapters. This operation will run in a background thread and errors
     * are ignored.
     */
    private fun deletePendingChapters() {
        appScope.launch {
            // Progress writes started before leaving may still enqueue the chapter just finished
            progressWrites.awaitLanded() // RK: the shared ordering's
            downloadManager.deletePendingChapters()
        }
    }

    // RK: a chapter the reader asked for by a step, a pick or a reload that did not open, and why.
    // [fromSource] marks a reload from the source, which a retry repeats as one; [attempt] is
    // ReaderLoadState.Failed's, so a repeat of the same failure still reads as new.
    data class AdjacentLoadFailure(
        val chapterId: Long,
        val message: String?,
        val fromSource: Boolean,
        // RK: stamped here, so two identical failures still read as two
        val attempt: Long = ReaderLoadState.Failed.nextAttempt(),
    )

    @Immutable
    data class State(
        val manga: Manga? = null,
        val source: Source? = null,
        val initError: Throwable? = null,
        val viewerChapters: ViewerChapters? = null,
        val bookmarked: Boolean = false,
        val isLoadingAdjacentChapter: Boolean = false,
        // RK -->
        val position: ReaderPosition? = null,
        /** The step, pick or reload that could not open, kept until the next one starts. */
        val adjacentLoadFailure: AdjacentLoadFailure? = null,
        // RK <--

        val menuVisible: Boolean = false,
        @IntRange(from = -100, to = 100) val brightnessOverlayValue: Int = 0,
    ) {
        val currentChapter: ReaderChapter?
            get() = viewerChapters?.currChapter

        // RK -->

        /**
         * The chapter the chrome describes, which is not always [currentChapter]: the viewer crosses
         * into the next chapter before the model swaps that asynchronously, so reading the title from
         * it would pair one chapter's page number with another's title and total.
         */
        val visibleChapter: ReaderChapter?
            get() {
                val id = position?.chapterId
                val chapters = viewerChapters ?: return null
                return when (id) {
                    chapters.currChapter.chapter.id -> chapters.currChapter
                    chapters.prevChapter?.chapter?.id -> chapters.prevChapter
                    chapters.nextChapter?.chapter?.id -> chapters.nextChapter
                    else -> null
                } ?: currentChapter
            }
        // RK <--
    }

    sealed interface Event {
        data object ReloadViewerChapters : Event

        // RK: the open chapter's pages were replaced; the viewer lands a first layout only, so it is moved
        data object ReloadedChapter : Event
        data object PageChanged : Event
        data class SetOrientation(val orientation: Int) : Event
        data class SetCoverResult(val result: SetAsCoverResult) : Event

        data class SavedImage(val result: SaveImageResult) : Event
        data class ShareImage(val uri: Uri, val page: ReaderPage) : Event
        data class CopyImage(val uri: Uri) : Event
    }
}
