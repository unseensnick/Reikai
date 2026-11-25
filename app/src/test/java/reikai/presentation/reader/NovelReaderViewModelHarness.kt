package reikai.presentation.reader

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.network.interceptor.CloudflareBypassIOException
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mihon.domain.extension.model.ContentWarning
import org.jsoup.Jsoup
import reikai.data.chapter.ChapterNumberOverrideRepositoryImpl
import reikai.data.coil.NovelChapterPictureCache
import reikai.data.merge.MergeGroupRepositoryImpl
import reikai.data.merge.MergedChapterUnitRepositoryImpl
import reikai.data.novel.NovelChapterRepositoryImpl
import reikai.data.novel.NovelChapterSyncRules
import reikai.data.novel.NovelHistoryRepositoryImpl
import reikai.data.novel.NovelRepositoryImpl
import reikai.data.novel.toNovel
import reikai.domain.category.GetNovelCategories
import reikai.domain.chapter.ChapterCopyMarkRepository
import reikai.domain.chapter.CopyMark
import reikai.domain.chapter.EditChapterNumber
import reikai.domain.chapter.InMemoryChapterCopyMarks
import reikai.domain.chapter.NoChapterTombstones
import reikai.domain.chapter.NoLocalVolumes
import reikai.domain.download.NovelRemovableDownloads
import reikai.domain.download.SourceContacts
import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.merge.ReconcileMergedChapters
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelChapterSettings
import reikai.domain.novel.NovelCompletionLatch
import reikai.domain.novel.NovelGroupStitcher
import reikai.domain.novel.NovelMergeManager
import reikai.domain.novel.NovelMergedChapterProvider
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.interactor.DeleteNovelChaptersAfterRead
import reikai.domain.novel.interactor.DeleteNovelChaptersBehindReader
import reikai.domain.novel.interactor.FilterNovelChaptersForDownload
import reikai.domain.novel.interactor.GetNextNovelChapter
import reikai.domain.novel.interactor.RemoveNovelsFromLibrary
import reikai.domain.novel.interactor.SetNovelChapterFlags
import reikai.domain.novel.interactor.SetNovelCustomCover
import reikai.domain.novel.interactor.SetNovelReadStatus
import reikai.domain.novel.interactor.SetNovelViewerFlags
import reikai.domain.novel.interactor.UpdateNovel
import reikai.domain.novel.interactor.UpsertNovelHistory
import reikai.domain.novel.model.CustomNovelInfo
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.domain.novel.model.NovelUpdate
import reikai.domain.novel.track.TrackNovelChapter
import reikai.domain.reader.ChapterIncognito
import reikai.domain.readinglog.NoReadingLog
import reikai.domain.readinglog.ReadingPaceRepository
import reikai.domain.source.SourceKey
import reikai.domain.track.ownChapterNumbers
import reikai.novel.download.NovelDownloadCache
import reikai.novel.download.NovelDownloadManager
import reikai.novel.download.NovelDownloadedTexts
import reikai.novel.host.ChapterItem
import reikai.novel.host.NovelItem
import reikai.novel.host.SourceNovel
import reikai.novel.install.LnPluginInstaller
import reikai.novel.install.LnPluginLoadFailure
import reikai.novel.source.NovelExtensionFormat
import reikai.novel.source.NovelFilterState
import reikai.novel.source.NovelItemsPage
import reikai.novel.source.NovelListing
import reikai.novel.source.NovelSource
import reikai.novel.source.NovelSourceManager
import reikai.novel.source.testChapterCache
import reikai.novel.source.testChapterTextCache
import reikai.presentation.novel.details.NovelDetailsViewModel
import reikai.presentation.reader.text.NovelChapterFinish
import reikai.presentation.recents.EmittingPreferenceStore
import reikai.util.restubDefaultDispatcher
import tachiyomi.core.common.preference.Preference
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.category.CategoryRepositoryImpl
import tachiyomi.domain.library.service.LibraryPreferences
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * A real [NovelReaderViewModel] over an in-memory database, the real repositories, interactors and
 * merge machinery, and preferences that emit when written. Faked only where the app leaves the
 * process: the plugin host ([FakeNovelSource]), the download manager's disk, the tracker network and
 * the clock. Every launch runs on [dispatcher], so `advanceUntilIdle` settles the whole session.
 *
 * [create] it with the test's scheduler and [use] it: seed rows, [open] a model and drive it.
 */
class NovelReaderViewModelHarness private constructor(
    scheduler: TestCoroutineScheduler,
    private val driver: SerialSqliteDriver,
) {

    companion object {
        suspend fun create(scheduler: TestCoroutineScheduler): NovelReaderViewModelHarness {
            val driver = SerialSqliteDriver()
            Database.Schema.create(driver).await()
            // After the harness's own coEverys, as restubDefaultDispatcher says
            return NovelReaderViewModelHarness(scheduler, driver).also { restubDefaultDispatcher() }
        }
    }

    val dispatcher = StandardTestDispatcher(scheduler)

    // The models' app scope, on the test's scheduler, so a write that outlives a model lands by advanceUntilIdle
    private val appScope = CoroutineScope(SupervisorJob() + dispatcher)

    // On the same scheduler, or a chapter read waits on a thread advanceUntilIdle cannot see
    private val cacheScope = CoroutineScope(SupervisorJob() + dispatcher)
    private val chapterTextCache = testChapterTextCache(testChapterCache(cacheScope, dispatcher, Duration.ZERO))

    private val store = EmittingPreferenceStore()
    val novelPreferences = NovelPreferences(store)
    val readerPreferences = ReaderPreferences(store)
    val libraryPreferences = LibraryPreferences(store)
    val reikaiLibraryPreferences = ReikaiLibraryPreferences(store)

    private val database = DatabaseBindings.providesDatabase(driver)
    private val novelRepo = NovelRepositoryImpl(database)
    private val chapterRepo = NovelChapterRepositoryImpl(database)

    /** The Never skip and copy groups the reader reads; a test sets them before opening. */
    val copyMarks = InMemoryChapterCopyMarks()

    private val groups = MergeGroupRepositoryImpl(database)
    private val units = MergedChapterUnitRepositoryImpl(database)
    val numberOverrides = ChapterNumberOverrideRepositoryImpl(database)

    /** One permit per plugin host load, which a details screen asks for just before it names a group's chips. */
    val pluginLoads = Semaphore(0)

    /** Completed by a test to let plugin loads finish; already complete unless [holdPluginLoads] replaced it. */
    @Volatile
    private var pluginLoadGate = CompletableDeferred(Unit)

    /** Holds every plugin load until the returned gate completes, as a slow plugin host does on a device. */
    fun holdPluginLoads(): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { pluginLoadGate = it }

    // Plugins are fetched and evaluated by the installer, which is the network; a registered fake
    // source stands in for what it would have loaded.

    /** The installed plugins that threw while loading, by URL. */
    val pluginFailures = MutableStateFlow<Map<String, LnPluginLoadFailure>>(emptyMap())

    /** What a Retry on one plugin's failed load does, by plugin id; nothing loads unless a test says so. */
    var onPluginRetry: (pluginId: String) -> Unit = {}

    private val installer = mockk<LnPluginInstaller>(relaxed = true) {
        every { failures } returns pluginFailures
        coEvery { ensureLoaded() } coAnswers {
            pluginLoadGate.await()
            pluginLoads.release()
        }
        coEvery { retryLoad(any()) } answers { onPluginRetry(firstArg()) }
    }
    private val sourceManager = NovelSourceManager(
        // On the test scheduler, or a source lookup waits on a real thread advanceUntilIdle cannot see
        scope = appScope,
        installer = { installer },
        extensionManager = mockk { every { loadedNovelExtensionsFlow } returns flowOf(emptyList()) },
        prefs = novelPreferences,
    )

    /** Chapter id to the text its downloaded copy holds. */
    private val downloaded = mutableMapOf<Long, String>()

    /** The global Downloaded only switch. */
    val downloadedOnly = store.getBoolean(Preference.appStateKey("pref_downloaded_only"), false)
    val incognito = store.getBoolean(Preference.appStateKey("incognito_mode"), false)
    private val sourcePreferences = SourcePreferences(store)
    private val history = NovelHistoryRepositoryImpl(database)

    val downloadManager = mockk<NovelDownloadManager>(relaxed = true) {
        every { queueState } returns MutableStateFlow(emptyList())
        every { getChapterText(any(), any()) } answers { downloaded[secondArg<NovelChapter>().id] }
        every { deleteChapters(any()) } answers { deletedAfterRead += firstArg<List<NovelChapter>>().map { it.id } }
        coEvery { downloadChapters(any()) } answers { queued += firstArg<List<NovelChapter>>().map { it.id } }
        // A renamed novel's row is written through its folder move, as the real manager does.
        coEvery { renameNovel(any(), any(), any()) } coAnswers { thirdArg<suspend () -> Boolean>()() }
    }

    /** The chapters a mark read removed the download of, with "Remove after marked as read" on. */
    val deletedAfterRead: MutableList<Long> = java.util.Collections.synchronizedList(mutableListOf())

    /** The chapters handed to the download queue, in the order they were. */
    val queued: MutableList<Long> = java.util.Collections.synchronizedList(mutableListOf())

    /** What a details page's remove from library runs. */
    var removeNovelsFromLibrary = mockk<RemoveNovelsFromLibrary>(relaxed = true)

    /** What a details refresh asks of the new chapters it would download. */
    var filterChaptersForDownload = mockk<FilterNovelChaptersForDownload>(relaxed = true)

    /** When each source was last reached, as a details model opened after setting it sees it. */
    var sourceContacts = SourceContacts { 0L }

    /** What the in-app browser reports as a chapter saved from its page. */
    val pageSaves = MutableSharedFlow<Long>(extraBufferCapacity = 1)

    private val downloadCache = mockk<NovelDownloadCache> {
        every { changes } returns MutableStateFlow(Unit)
        every { isChapterDownloaded(any<Novel>(), any()) } answers { secondArg<NovelChapter>().id in downloaded }
        every { downloadedChapterIds(any<Novel>(), any()) } answers {
            diskProbeGate?.pass()
            secondArg<List<NovelChapter>>().mapTo(HashSet()) { it.id }.filterTo(HashSet()) { it in downloaded }
        }
    }

    @Volatile
    private var diskProbeGate: DiskProbeGate? = null

    /** Makes read aloud's reads of the reading order fail, as a database read that throws does. */
    @Volatile
    var failOrderReads = false

    /** Holds the next read of the reading order read aloud makes until the gate completes; later reads pass. */
    @Volatile
    var orderGate: CompletableDeferred<Unit>? = null

    /** Stops every later disk probe of a chapter list at the returned gate, freezing that list's rebuild. */
    fun holdDiskProbes(): DiskProbeGate = DiskProbeGate().also { diskProbeGate = it }

    private val viewModels = ViewModelStore()
    private val modelJobs = mutableListOf<Job>()

    init {
        // The warm cooldown reads the uptime clock, which an Android stub cannot answer on the JVM.
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } answers { scheduler.currentTime }
    }

    /** A source the plugin host would have loaded, registered under [id]. */
    fun source(id: String, name: String = id): FakeNovelSource =
        FakeNovelSource(id, name).also(sourceManager::register)

    suspend fun novel(
        source: FakeNovelSource,
        title: String = "Novel",
        inLibrary: Boolean = true,
        cover: String? = null,
        totalPages: Long = 1L,
    ): Long {
        val url = "/novel/${source.id}/$title"
        val favoriteAt = Instant.fromEpochMilliseconds(0L).takeIf { inLibrary }
        return novelRepo.insert(
            Novel.create().copy(
                source = source.id,
                url = url,
                title = title,
                favoriteAt = favoriteAt,
                thumbnailUrl = cover,
                totalPages = totalPages,
            ),
        )!!
    }

    /** The stored row of [url] on [sourceId], if opening it stored one. */
    suspend fun storedNovel(sourceId: String, url: String): Novel? = novelRepo.getByUrlAndSource(url, sourceId)

    /** Gives [novelId] a new cover, as an edit or a refresh that finds one does. */
    suspend fun changeCover(novelId: Long, url: String) {
        novelRepo.update(NovelUpdate(novelId) { thumbnailUrl = url })
    }

    suspend fun viewerFlagsOf(novelId: Long): Long = novelRepo.getById(novelId)!!.viewerFlags

    suspend fun chapterFlagsOf(novelId: Long): Long = novelRepo.getById(novelId)!!.chapterFlags

    /** Rewrites [novelId]'s stored row, as a refresh does. */
    suspend fun updateNovel(novelId: Long, block: NovelUpdate.() -> Unit) {
        novelRepo.update(NovelUpdate(novelId, block))
    }

    /**
     * [progressPercent] is where the reader last was in it, stored as the reader stores it. A second copy
     * of one [number] needs its own [url], and a [sourceOrder] to say which copy the source lists first.
     * A [name] with a title is what the stitch pairs sources on; a bare number pairs nothing.
     */
    suspend fun chapter(
        novelId: Long,
        number: Double,
        read: Boolean = false,
        progressPercent: Int = 0,
        bookmark: Boolean = false,
        page: String = "",
        url: String = "/chapter/$novelId/$number",
        sourceOrder: Long = number.toLong(),
        scanlator: String? = null,
        name: String = "Chapter $number",
    ): SeededChapter {
        val chapter = NovelChapter(
            id = -1L,
            novelId = novelId,
            url = url,
            name = name,
            read = read,
            bookmark = bookmark,
            lastTextProgress = progressPercent * 100L,
            chapterNumber = number,
            sourceOrder = sourceOrder,
            dateFetch = Instant.fromEpochMilliseconds(0L),
            dateUpload = null,
            page = page,
            scanlator = scanlator,
        )
        return SeededChapter(chapterRepo.insert(chapter)!!, url)
    }

    /** Turns incognito on for [source] alone, as its long-press in Browse does. */
    fun incognito(source: FakeNovelSource) {
        sourcePreferences.incognitoExtensions.set(setOf(SourceKey.Novel(source.id).serialize()))
    }

    /** The chapter the History tab shows for the novel read last. */
    suspend fun lastInHistory(): Long? = history.getLastNovelHistory()?.chapterId

    /** The chapters of [novelId] reading has put in history. */
    suspend fun historyOf(novelId: Long): List<Long> = history.getHistoryByNovelId(novelId).map { it.chapterId }

    /** The time read history holds for [chapter] of [novelId], in milliseconds. */
    suspend fun readTimeOf(novelId: Long, chapter: SeededChapter): Long? =
        history.getHistoryByNovelId(novelId).firstOrNull { it.chapterId == chapter.id }?.readDuration

    /** Where the reader stored its place in [chapter], in hundredths of a percent. */
    suspend fun progressOf(chapter: SeededChapter): Long? = chapterRepo.getById(chapter.id)?.lastTextProgress

    /** Whether [chapter] is marked read. */
    suspend fun isRead(chapter: SeededChapter): Boolean? = chapterRepo.getById(chapter.id)?.read

    /** Marks [chapter] read, or unread, as reading it elsewhere or the details screen does. */
    suspend fun markRead(chapter: SeededChapter, read: Boolean = true) {
        chapterRepo.setReadBulk(listOf(chapter.id), read)
    }

    /** Live collectors of the chapter queries details screens hold open while they are shown. */
    val chapterQueries = AtomicInteger()

    private val detailsChapters = object : NovelChapterRepository by chapterRepo {
        override fun getByNovelIdAsFlow(novelId: Long) = chapterRepo.getByNovelIdAsFlow(novelId).counted()

        override fun getByNovelIdAndPageAsFlow(novelId: Long, page: String) =
            chapterRepo.getByNovelIdAndPageAsFlow(novelId, page).counted()

        private fun <T> Flow<T>.counted() =
            onStart { chapterQueries.incrementAndGet() }.onCompletion { chapterQueries.decrementAndGet() }
    }

    /** Each novel's Edit info overlay, as details screens opened after a write read it. */
    val customInfo = mutableMapOf<Long, CustomNovelInfo>()

    private val novelRowQueries = ConcurrentHashMap<Long, AtomicInteger>()

    /** Live collectors of [novelId]'s row query that details screens hold open. */
    fun novelRowQueries(novelId: Long): Int = novelRowQueries[novelId]?.get() ?: 0

    private val detailsNovels = object : NovelRepository by novelRepo {
        override fun getByIdAsFlow(id: Long) = novelRepo.getByIdAsFlow(id).let { rows ->
            val count = novelRowQueries.getOrPut(id) { AtomicInteger() }
            rows.onStart { count.incrementAndGet() }.onCompletion { count.decrementAndGet() }
        }
    }

    /** Groups [novelIds] into one merged novel, as the merge dialog does. */
    suspend fun merge(vararg novelIds: Long) {
        groups.createGroup(ContentType.NOVELS, novelIds.toList())
    }

    /** Puts a copy of [chapter] on disk, holding [text]. */
    fun download(chapter: SeededChapter, text: String) {
        downloaded[chapter.id] = text
    }

    /** What the reader's trim behind it queued for deletion on leaving. */
    val pendingDeletes = mutableListOf<NovelChapter>()

    /** Whether a rotation write of a flag to a novel takes a moment, as a busy database's does. */
    @Volatile
    var slowFlagWrite: (novelId: Long, flag: Long) -> Boolean = { _, _ -> false }

    /** Runs [step] and waits for the app-scope writes it started, which outlive the model. */
    suspend fun settleAppWrites(step: suspend () -> Unit) {
        val running = appScope.coroutineContext.job.children.toSet()
        step()
        withTimeout(10_000) { (appScope.coroutineContext.job.children.toSet() - running).joinAll() }
    }

    /** Clears the open models, as leaving the reader does, with the database still open. */
    fun leave() = viewModels.clear()

    /** The picture caches a reload from source drops, which are the app's image loader's. */
    val pictureCache = mockk<NovelChapterPictureCache>(relaxed = true)

    /** The bytes written as each novel's own cover, by id. */
    val customCovers = java.util.Collections.synchronizedMap(mutableMapOf<Long, ByteArray>())

    private val removable =
        NovelRemovableDownloads(novelPreferences, GetNovelCategories(CategoryRepositoryImpl(database)))

    /** Marks read as the app does; only the source tracker and the unread push, both network, are faked. */
    private fun setNovelReadStatus() = SetNovelReadStatus(
        chapterRepo,
        DeleteNovelChaptersAfterRead(novelPreferences, removable, { downloadManager }),
        mockk(relaxed = true),
        mockk(relaxed = true),
        completionLatch,
    )

    /** The app's one latch, so a chapter finished by one reader is finished for every holder of it. */
    private val completionLatch = NovelCompletionLatch()

    /** What the tracker network was asked to push, which every finish reaches through one instance. */
    val trackNovelChapter = mockk<TrackNovelChapter>(relaxed = true)

    private val chapterFinish by lazy {
        NovelChapterFinish(
            chapterRepo = chapterRepo,
            setNovelReadStatus = setNovelReadStatus(),
            libraryPreferences = libraryPreferences,
            trackPreferences = TrackPreferences(store),
            trackNovelChapter = trackNovelChapter,
            trackedChapterNumber = ownChapterNumbers(),
            copyMarks = copyMarks,
            completion = completionLatch,
            scope = appScope,
            context = mockk(relaxed = true),
        )
    }

    private val deleteChaptersBehindReader by lazy {
        DeleteNovelChaptersBehindReader(
            novelPreferences,
            removable,
            { downloadManager },
            chapterRepo,
            mockk(relaxed = true) {
                every { addChapters(any()) } answers { pendingDeletes += firstArg<List<NovelChapter>>() }
                // What leaving drains, as the real store hands over and forgets its queue.
                every { takePendingChapterIds() } answers
                    { pendingDeletes.map { it.id }.also { pendingDeletes.clear() } }
            },
            libraryPreferences,
            copyMarks,
        )
    }

    /** The app's one progress slot, which every model opened here and read aloud save through. */
    val progressSlot by lazy {
        NovelProgressSlot(
            appScope,
            chapterRepo,
            novelPreferences,
            chapterFinish,
            deleteChaptersBehindReader,
            dispatcher,
        )
    }

    private val reikaiPreferences = ReikaiLibraryPreferences(store)
    private val mergeManager = NovelMergeManager(groups, reikaiPreferences, onMerged = {}) {}

    private val getNextNovelChapter by lazy {
        val stitcher = NovelGroupStitcher(groups, novelRepo, chapterRepo, mergeManager, reikaiPreferences)
        GetNextNovelChapter(
            chapterRepo,
            novelRepo,
            novelPreferences,
            mergeManager,
            NovelMergedChapterProvider(mergeManager, ReconcileMergedChapters(units, setOf(stitcher))),
            NovelChapterSettings(novelRepo),
        )
    }

    // BasePreferences builds an extension-installer preference whose default calls into
    // android.graphics, so only the incognito switch is carried over. The manga-extension
    // lookup is never reached for a novel source.
    private val getIncognitoState by lazy {
        GetIncognitoState(
            mockk<BasePreferences> { every { incognitoMode } returns this@NovelReaderViewModelHarness.incognito },
            sourcePreferences,
            mockk(),
        )
    }

    // Only the Downloaded only switch; BasePreferences itself cannot be built on the JVM.
    private val basePreferences by lazy {
        mockk<BasePreferences> { every { downloadedOnly } returns this@NovelReaderViewModelHarness.downloadedOnly }
    }

    /** Every voice read aloud built, the latest last; each starts once a test says so. */
    val engines = mutableListOf<HarnessTtsEngine>()

    /** The read aloud notification, as the service would show it. */
    val notification = HarnessNotification()

    /** What read aloud credited as listened, by chapter, in milliseconds. */
    val listened = java.util.concurrent.ConcurrentHashMap<Long, Long>()

    /** The lengths read aloud measured, by chapter, in words. */
    val lengths = java.util.concurrent.ConcurrentHashMap<Long, Long>()

    private val paceRepository = mockk<ReadingPaceRepository>(relaxed = true) {
        coEvery { addListenedTime(any(), any()) } answers {
            listened.merge(firstArg(), secondArg(), Long::plus)
        }
        coEvery { setLength(any(), any(), any()) } answers { lengths[secondArg()] = thirdArg() }
    }

    /** The app's one read aloud session, which every model opened here reads through. */
    private val readAloudSessionLazy = lazy {
        ReadAloudSession(
            appScope = appScope,
            context = mockk(relaxed = true),
            novelPreferences = novelPreferences,
            novelRepo = novelRepo,
            chapterRepo = chapterRepo,
            sourceManager = sourceManager,
            installer = installer,
            chapterTextCache = chapterTextCache,
            downloadManagerProvider = { downloadManager },
            orderResolver = NovelReadingOrderResolver(
                getNextNovelChapter,
                novelRepo,
                chapterRepo,
                object : ChapterCopyMarkRepository by copyMarks {
                    override suspend fun loadedMarks(type: ContentType): Map<Long, Map<String, CopyMark>> {
                        orderGate?.let { gate ->
                            orderGate = null
                            gate.await()
                        }
                        if (failOrderReads) throw java.io.IOException("unreadable")
                        return copyMarks.loadedMarks(type)
                    }
                },
                novelPreferences,
                basePreferences,
                downloadCache,
                sourceManager,
            ),
            progressSlot = progressSlot,
            chapterFinish = chapterFinish,
            upsertNovelHistory = UpsertNovelHistory(history),
            readingLog = NoReadingLog,
            paceRepository = paceRepository,
            getIncognitoState = getIncognitoState,
            adultChecker = mockk { coEvery { adultNovelIdsAmong(any()) } returns emptySet() },
            getNextNovelChapter = getNextNovelChapter,
            deleteChaptersBehindReader = deleteChaptersBehindReader,
            engines = { pkg, onInit -> HarnessTtsEngine(pkg, onInit).also { engines += it } },
            // The native renderer's count needs android.text; a paragraph is each block's text here.
            text = { html, _ -> Jsoup.parse(html).select("p").map { it.text() }.filter(String::isNotBlank) },
            notification = notification,
            timeSource = scheduler.timeSource,
        )
    }
    val readAloudSession by readAloudSessionLazy

    /** What read aloud writes beside [model]'s session, by the rules it saves by. */
    private fun spokenContext(model: NovelReaderViewModel) = NovelWriteContext(
        memberIds = model.memberIds,
        stitch = emptyList(),
        orderedIds = emptyList(),
        incognito = ChapterIncognito(
            sourceOf = { owner -> novelRepo.getById(owner)?.source?.let(SourceKey::Novel) },
            isIncognito = getIncognitoState::await,
        ),
    )

    /** Read aloud reaching [share] of [chapterId]'s words beside [model]. */
    fun saveSpoken(model: NovelReaderViewModel, chapterId: Long, share: Double) =
        progressSlot.reportSpoken(chapterId, share, spokenContext(model))

    /** Read aloud reaching [chapterId]'s end beside [model]. */
    fun finishSpoken(model: NovelReaderViewModel, chapterId: Long) =
        progressSlot.finishSpoken(chapterId, spokenContext(model))

    /** [savedState] is the host's, which a test hands a second model to reopen it as after a process kill. */
    fun open(
        novelId: Long,
        chapterId: Long,
        sourceScoped: Boolean = false,
        savedState: SavedStateHandle = SavedStateHandle(),
        followSession: Boolean = false,
    ): NovelReaderViewModel {
        val context = mockk<Context>(relaxed = true)
        return NovelReaderViewModel(
            appScope = appScope,
            launchNovelId = novelId,
            initialChapterId = chapterId,
            launchSourceScoped = sourceScoped,
            savedState = savedState,
            followSession = followSession,
            readAloudSession = readAloudSession,
            novelRepo = novelRepo,
            chapterRepo = chapterRepo,
            sourceManager = sourceManager,
            installer = installer,
            novelPreferences = novelPreferences,
            readerPreferences = readerPreferences,
            downloadManagerProvider = { downloadManager },
            upsertNovelHistory = UpsertNovelHistory(history),
            novelHistory = history,
            readingLog = NoReadingLog,
            copyMarks = copyMarks,
            localVolumes = NoLocalVolumes,
            setNovelReadStatus = setNovelReadStatus(),
            progressSlot = progressSlot,
            chapterFinish = chapterFinish,
            // BasePreferences builds an extension-installer preference whose default calls into
            // android.graphics, so only the incognito switch is carried over. The manga-extension
            // lookup is never reached for a novel source.
            getIncognitoState = getIncognitoState,

            setNovelViewerFlags = spyk(SetNovelViewerFlags(novelRepo)) {
                coEvery { awaitSetOrientation(any(), any()) } coAnswers {
                    if (slowFlagWrite(firstArg(), secondArg())) slowWrite()
                    callOriginal()
                }
            },
            novelDownloadCache = downloadCache,
            chapterTextCache = chapterTextCache,
            pageFetcher = mockk { every { chapterSaved } returns pageSaves },
            deleteChaptersBehindReader = deleteChaptersBehindReader,
            basePreferences = basePreferences,
            context = context,
            getNextNovelChapter = getNextNovelChapter,

            setNovelCustomCover = SetNovelCustomCover(
                novelRepo,
                UpdateNovel(novelRepo, mockk(relaxed = true)),
                // The cache's directory is the app's; what reaches it is what a test asks about.
                mockk {
                    every { setCustomCoverToCache(any<EntryId>(), any()) } answers {
                        customCovers[firstArg<EntryId.Novel>().rawId] = secondArg<java.io.InputStream>().readBytes()
                    }
                },
                mockk(),
            ),
            pictureCache = pictureCache,
            io = dispatcher,
        ).also {
            track(it)
            restubDefaultDispatcher()
        }
    }

    /** The details screen of [url] on [sourceId] opened from Browse, which stores its listing row first. */
    suspend fun openListing(sourceId: String, url: String): NovelDetailsViewModel =
        openDetails(novelRepo.insertOrGet(NovelItem("Listed", url, null).toNovel(sourceId))!!.id)

    /**
     * The novel details screen of [novelId], over the same database and merge machinery. It launches on
     * Dispatchers.IO, which a test points at its scheduler with `virtualDispatchers()`. While [shown],
     * its state is collected for as long as the model lives, as a screen showing it collects it.
     */
    fun openDetails(novelId: Long, shown: Boolean = true): NovelDetailsViewModel {
        val reikaiLibraryPreferences = ReikaiLibraryPreferences(store)
        val mergeManager = NovelMergeManager(groups, reikaiLibraryPreferences, onMerged = {}) {}
        val stitcher = NovelGroupStitcher(groups, novelRepo, chapterRepo, mergeManager, reikaiLibraryPreferences)
        val mergedChapterProvider =
            NovelMergedChapterProvider(mergeManager, ReconcileMergedChapters(units, setOf(stitcher)))
        return NovelDetailsViewModel(
            appScope = appScope,
            novelId = novelId,
            isFromSource = false,
            novelRepo = detailsNovels,
            coverCache = mockk(relaxed = true),
            resetEntryInfo = mockk(relaxed = true),
            setNovelChapterFlags = SetNovelChapterFlags(novelRepo, novelPreferences),
            chapterSettings = NovelChapterSettings(novelRepo),
            chapterRepo = detailsChapters,
            downloadManagerProvider = { downloadManager },
            novelDownloadCache = downloadCache,
            sourceManager = sourceManager,
            installer = installer,
            filterChaptersForDownload = filterChaptersForDownload,
            novelLibraryAdder = mockk(relaxed = true),
            setNovelReadStatus = setNovelReadStatus(),
            novelPreferences = novelPreferences,
            uiPreferences = mockk(relaxed = true) {
                every { themeCoverBased } returns store.getBoolean("theme_cover_based", false)
            },
            mergeManager = mergeManager,
            mergedChapterProvider = mergedChapterProvider,
            reikaiLibraryPreferences = reikaiLibraryPreferences,
            libraryPreferences = LibraryPreferences(store),
            syncRules = NovelChapterSyncRules(numberOverrides, copyMarks, NoChapterTombstones),
            // A remote novel's failed refresh offers no folders, as offerAfterFailure answers for one.
            localSeriesRelink = mockk { coEvery { offerAfterFailure(any(), any(), false) } returns null },
            editChapterNumber = EditChapterNumber(numberOverrides, ReconcileMergedChapters(units, setOf(stitcher))),
            context = mockk(relaxed = true),
            getCustomNovelInfo = mockk(relaxed = true) {
                every { subscribe(any()) } answers { flowOf(customInfo[firstArg()]) }
            },
            setCustomNovelInfo = mockk(relaxed = true),
            getNovelTracks = mockk(relaxed = true),
            refreshNovelTracks = mockk(relaxed = true),
            trackNovelChapter = mockk(relaxed = true),
            trackedChapterNumber = ownChapterNumbers(),
            trackerManager = mockk(relaxed = true),
            trackPreferences = TrackPreferences(store),
            basePreferences = mockk<BasePreferences>(relaxed = true) {
                every { downloadedOnly } returns this@NovelReaderViewModelHarness.downloadedOnly
            },
            removeNovelsFromLibrary = removeNovelsFromLibrary,
            combineLocalVolumes = mockk(relaxed = true),
            trackPorts = mockk(relaxed = true),
            autoBindTrackers = mockk(relaxed = true),
            downloadedTexts = NovelDownloadedTexts(
                GetNextNovelChapter(
                    detailsChapters,
                    novelRepo,
                    novelPreferences,
                    mergeManager,
                    mergedChapterProvider,
                    NovelChapterSettings(novelRepo),
                ),
                novelRepo,
                downloadCache,
                { downloadManager },
                mockk(),
                sourceManager,
            ),
            sourceContacts = sourceContacts,
        ).also { model ->
            track(model)
            restubDefaultDispatcher()
            if (shown) model.viewModelScope.launch { model.state.collect {} }
        }
    }

    private fun track(model: ViewModel) {
        viewModels.put("model-${modelJobs.size}", model)
        modelJobs += model.viewModelScope.coroutineContext.job
    }

    /** Runs [block] over this harness, then [close]s it. */
    suspend inline fun <T> use(block: (NovelReaderViewModelHarness) -> T): T = try {
        block(this)
    } finally {
        close()
    }

    /** Clears every model it opened, as leaving the screen does, and waits for their work to stop. */
    suspend fun leaveModels() {
        viewModels.clear()
        modelJobs.joinAll()
    }

    /**
     * Clears every model it opened, as [leaveModels] does, and waits for their work to stop before
     * releasing the database and clock. A details model's query still running against a closed
     * database would throw into whichever test is running by then.
     */
    suspend fun close() {
        cacheScope.cancel() // Its save loop waits for writes for as long as it lives
        diskProbeGate?.open()
        pluginLoadGate.complete(Unit)
        withContext(NonCancellable) {
            // A session runs on the app scope until stopped, as it outlives every reader.
            if (readAloudSessionLazy.isInitialized()) readAloudSession.stop()
            leaveModels()
            // After the models, since a model's last held position is written onto the app scope as it ends,
            // and until none is left, since a held write that lands launches the write behind it
            while (appScope.coroutineContext.job.children.any { it.isActive }) {
                appScope.coroutineContext.job.children.toList().joinAll()
            }
        }
        driver.close()
        unmockkStatic(SystemClock::class)
    }
}

data class SeededChapter(val id: Long, val url: String)

/**
 * A door the details list's disk probes queue at, so a test can hold one rebuild mid-flight while the
 * screen's other inputs move on, the interleaving a busy machine produces by chance.
 */
class DiskProbeGate {
    private val arrived = Semaphore(0)
    private val admitted = Semaphore(0)

    /** Called by a probe: reports in, then waits to be let through. Gives up rather than strand its thread. */
    fun pass() {
        arrived.release()
        admitted.tryAcquire(WAIT_SECONDS, TimeUnit.SECONDS)
    }

    /** Waits until [count] more probes have reached the gate. */
    fun awaitArrived(count: Int = 1) =
        check(arrived.tryAcquire(count, WAIT_SECONDS, TimeUnit.SECONDS)) { "No disk probe reached the gate" }

    fun admit(count: Int = 1) = admitted.release(count)

    /** Lets every probe through from here on. */
    fun open() = admitted.release(Int.MAX_VALUE / 2)

    private companion object {
        const val WAIT_SECONDS = 10L
    }
}

/**
 * The plugin host's side of a novel source: every chapter answers with text naming it, unless its
 * url is in [failing], where it throws as a dropped connection does.
 */
class FakeNovelSource(override val id: String, override val name: String) : NovelSource {

    val failing = mutableSetOf<String>()

    /** Chapter paths a site challenge stops, by the page it blocked, as the bypass reports one it gave up on. */
    val blocked: MutableMap<String, String> = java.util.concurrent.ConcurrentHashMap()

    /** Chapter paths whose fetch waits on their gate, as a slow connection does. */
    val holding: MutableMap<String, CompletableDeferred<Unit>> = java.util.concurrent.ConcurrentHashMap()

    /** What asking for a novel's details does; by default it throws, as for a source no test meant to ask. */
    @Volatile
    var details: suspend () -> SourceNovel = { unused() }

    val detailsAsked = AtomicInteger()

    /** What asking for a path's web address does; by default the source defines no rule of its own. */
    @Volatile
    var resolve: suspend (String) -> String? = { null }

    /** Every chapter path asked for, in order. */
    val chaptersAsked: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    /** Every page key asked for, in order. A page answers with no chapters. */
    val pagesAsked: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    /** Page keys whose fetch throws, as a source with no connection does. */
    val failingPages: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Chapter paths that answer with their own text rather than the default naming them. */
    val texts: MutableMap<String, String> = java.util.concurrent.ConcurrentHashMap()

    override val version = "1.0.0"
    override val site = "https://$id.example"
    override val lang = "en"
    override val iconUrl: String? = null
    override val format = NovelExtensionFormat.JS
    override val extensionName = name
    override val contentWarning = ContentWarning.SAFE

    override suspend fun parseChapter(chapterPath: String): String {
        chaptersAsked += chapterPath
        holding[chapterPath]?.await()
        if (chapterPath in failing) throw IOException("no connection")
        blocked[chapterPath]?.let { throw CloudflareBypassIOException("blocked", it, null) }
        return texts[chapterPath] ?: "<p>From the source: $chapterPath</p>"
    }

    override suspend fun browse(listing: NovelListing, page: Int, filters: NovelFilterState?): NovelItemsPage =
        unused()

    override suspend fun search(query: String, page: Int, filters: NovelFilterState?): NovelItemsPage = unused()

    override suspend fun parseNovel(novelPath: String): SourceNovel {
        detailsAsked.incrementAndGet()
        return details()
    }

    override suspend fun resolveUrl(path: String, isNovel: Boolean): String? = resolve(path)

    /** The chapters a page key answers with; none by default. */
    val pageChapters: MutableMap<String, List<ChapterItem>> = java.util.concurrent.ConcurrentHashMap()

    override suspend fun parsePage(novelPath: String, page: String): SourceNovel? {
        pagesAsked += page
        if (page in failingPages) throw IOException("no connection")
        return pageChapters[page]?.let { SourceNovel(path = novelPath, chapters = it) }
    }

    private fun unused(): Nothing = throw UnsupportedOperationException("Not part of reading a chapter")
}
