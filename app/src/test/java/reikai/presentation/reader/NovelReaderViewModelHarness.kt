package reikai.presentation.reader

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.track.service.TrackPreferences
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.withContext
import mihon.domain.extension.model.ContentWarning
import reikai.data.merge.MergeGroupRepositoryImpl
import reikai.data.merge.MergedChapterUnitRepositoryImpl
import reikai.data.novel.NovelChapterRepositoryImpl
import reikai.data.novel.NovelHistoryRepositoryImpl
import reikai.data.novel.NovelRepositoryImpl
import reikai.domain.category.GetNovelCategories
import reikai.domain.download.NovelRemovableDownloads
import reikai.domain.library.ContentType
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.merge.ReconcileMergedChapters
import reikai.domain.novel.NovelGroupStitcher
import reikai.domain.novel.NovelMergeManager
import reikai.domain.novel.NovelMergedChapterProvider
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.interactor.DeleteNovelChaptersAfterRead
import reikai.domain.novel.interactor.DeleteNovelChaptersBehindReader
import reikai.domain.novel.interactor.GetNextNovelChapter
import reikai.domain.novel.interactor.SetNovelReadStatus
import reikai.domain.novel.interactor.SetNovelViewerFlags
import reikai.domain.novel.interactor.UpsertNovelHistory
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.domain.source.SourceKey
import reikai.novel.download.NovelDownloadCache
import reikai.novel.download.NovelDownloadManager
import reikai.novel.host.NovelItem
import reikai.novel.host.SourceNovel
import reikai.novel.install.LnPluginInstaller
import reikai.novel.source.NovelExtensionFormat
import reikai.novel.source.NovelFilterState
import reikai.novel.source.NovelItemsPage
import reikai.novel.source.NovelListing
import reikai.novel.source.NovelSource
import reikai.novel.source.NovelSourceManager
import reikai.presentation.novel.details.NovelDetailsViewModel
import reikai.presentation.recents.EmittingPreferenceStore
import tachiyomi.core.common.preference.Preference
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.category.CategoryRepositoryImpl
import tachiyomi.domain.library.service.LibraryPreferences
import java.io.IOException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

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
    private val driver: JdbcSqliteDriver,
) {

    companion object {
        suspend fun create(scheduler: TestCoroutineScheduler): NovelReaderViewModelHarness {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            Database.Schema.create(driver).await()
            return NovelReaderViewModelHarness(scheduler, driver)
        }
    }

    val dispatcher = StandardTestDispatcher(scheduler)

    private val store = EmittingPreferenceStore()
    val novelPreferences = NovelPreferences(store)

    private val database = DatabaseBindings.providesDatabase(driver)
    private val novelRepo = NovelRepositoryImpl(database)
    private val chapterRepo = NovelChapterRepositoryImpl(database)
    private val groups = MergeGroupRepositoryImpl(database)
    private val units = MergedChapterUnitRepositoryImpl(database)

    /** One permit per plugin host load, which a details screen asks for just before it names a group's chips. */
    val pluginLoads = Semaphore(0)

    // Plugins are fetched and evaluated by the installer, which is the network; a registered fake
    // source stands in for what it would have loaded.
    private val installer = mockk<LnPluginInstaller>(relaxed = true) {
        coEvery { ensureLoaded() } answers { pluginLoads.release() }
    }
    private val sourceManager = NovelSourceManager(
        installer = { installer },
        extensionManager = mockk { every { loadedNovelExtensionsFlow } returns flowOf(emptyList()) },
        prefs = mockk(relaxed = true),
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
    }

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

    suspend fun novel(source: FakeNovelSource, title: String = "Novel"): Long {
        val url = "/novel/${source.id}/$title"
        return novelRepo.insert(Novel.create().copy(source = source.id, url = url, title = title, favoriteAt = 0L))!!
    }

    /** [progressPercent] is where the reader last was in it, stored as the reader stores it. */
    suspend fun chapter(
        novelId: Long,
        number: Double,
        read: Boolean = false,
        progressPercent: Int = 0,
        bookmark: Boolean = false,
        page: String = "",
    ): SeededChapter {
        val url = "/chapter/$novelId/$number"
        val chapter = NovelChapter(
            id = -1L,
            novelId = novelId,
            url = url,
            name = "Chapter $number",
            read = read,
            bookmark = bookmark,
            lastTextProgress = progressPercent * 100L,
            chapterNumber = number,
            sourceOrder = number.toLong(),
            dateFetch = 0L,
            dateUpload = 0L,
            page = page,
        )
        return SeededChapter(chapterRepo.insert(chapter)!!, url)
    }

    /** Turns incognito on for [source] alone, as its long-press in Browse does. */
    fun incognito(source: FakeNovelSource) {
        sourcePreferences.incognitoExtensions.set(setOf(SourceKey.Novel(source.id).serialize()))
    }

    /** The chapters of [novelId] reading has put in history. */
    suspend fun historyOf(novelId: Long): List<Long> = history.getHistoryByNovelId(novelId).map { it.chapterId }

    /** Where the reader stored its place in [chapter], in hundredths of a percent. */
    suspend fun progressOf(chapter: SeededChapter): Long? = chapterRepo.getById(chapter.id)?.lastTextProgress

    /** Whether [chapter] is marked read. */
    suspend fun isRead(chapter: SeededChapter): Boolean? = chapterRepo.getById(chapter.id)?.read

    /** Groups [novelIds] into one merged novel, as the merge dialog does. */
    suspend fun merge(vararg novelIds: Long) {
        groups.createGroup(ContentType.NOVELS, novelIds.toList())
    }

    /** Puts a copy of [chapter] on disk, holding [text]. */
    fun download(chapter: SeededChapter, text: String) {
        downloaded[chapter.id] = text
    }

    private val removable =
        NovelRemovableDownloads(novelPreferences, GetNovelCategories(CategoryRepositoryImpl(database)))

    /** Marks read as the app does; only the source tracker and the unread push, both network, are faked. */
    private fun setNovelReadStatus() = SetNovelReadStatus(
        chapterRepo,
        DeleteNovelChaptersAfterRead(novelPreferences, removable, { downloadManager }),
        mockk(relaxed = true),
        mockk(relaxed = true),
    )

    fun open(novelId: Long, chapterId: Long, sourceScoped: Boolean = false): NovelReaderViewModel {
        val context = mockk<Context>(relaxed = true)
        val reikaiLibraryPreferences = ReikaiLibraryPreferences(store)
        val mergeManager = NovelMergeManager(groups, reikaiLibraryPreferences) {}
        val stitcher = NovelGroupStitcher(groups, novelRepo, chapterRepo, mergeManager, reikaiLibraryPreferences)
        val mergedChapterProvider = NovelMergedChapterProvider(
            mergeManager,
            ReconcileMergedChapters(units, setOf(stitcher)),
        )
        return NovelReaderViewModel(
            novelId = novelId,
            initialChapterId = chapterId,
            sourceScoped = sourceScoped,
            savedState = SavedStateHandle(),
            novelRepo = novelRepo,
            chapterRepo = chapterRepo,
            sourceManager = sourceManager,
            installer = installer,
            novelPreferences = novelPreferences,
            downloadManagerProvider = { downloadManager },
            upsertNovelHistory = UpsertNovelHistory(history),
            setNovelReadStatus = setNovelReadStatus(),
            mergeManager = mergeManager,
            mergedChapterProvider = mergedChapterProvider,
            libraryPreferences = LibraryPreferences(store),
            // The tracker network.
            trackNovelChapter = mockk(relaxed = true),
            trackPreferences = TrackPreferences(store),
            // BasePreferences builds an extension-installer preference whose default calls into
            // android.graphics, so only the incognito switch is carried over. The manga-extension
            // lookup is never reached for a novel source.
            getIncognitoState = GetIncognitoState(
                mockk<BasePreferences> {
                    every { incognitoMode } returns this@NovelReaderViewModelHarness.incognito
                },
                sourcePreferences,
                mockk(),
            ),
            setNovelViewerFlags = SetNovelViewerFlags(novelRepo),
            novelDownloadCache = downloadCache,
            pageFetcher = mockk { every { chapterSaved } returns pageSaves },
            deleteChaptersBehindReader = DeleteNovelChaptersBehindReader(
                novelPreferences,
                removable,
                { downloadManager },
                chapterRepo,
                mockk(relaxed = true),
            ),
            // Only the Downloaded only switch; BasePreferences itself cannot be built on the JVM.
            basePreferences = mockk<BasePreferences> {
                every { downloadedOnly } returns this@NovelReaderViewModelHarness.downloadedOnly
            },
            context = context,
            adultChecker = mockk { coEvery { adultNovelIdsAmong(any()) } returns emptySet() },
            getNextNovelChapter = GetNextNovelChapter(
                chapterRepo,
                novelRepo,
                novelPreferences,
                mergeManager,
                mergedChapterProvider,
            ),
            io = dispatcher,
        ).also(::track)
    }

    /**
     * The novel details screen of [novelId], over the same database and merge machinery. It launches on
     * the real IO dispatcher rather than [dispatcher], so a test waits on its state in real time.
     */
    suspend fun openDetails(novelId: Long): NovelDetailsViewModel {
        val novel = novelRepo.getById(novelId)!!
        val reikaiLibraryPreferences = ReikaiLibraryPreferences(store)
        val mergeManager = NovelMergeManager(groups, reikaiLibraryPreferences) {}
        val stitcher = NovelGroupStitcher(groups, novelRepo, chapterRepo, mergeManager, reikaiLibraryPreferences)
        return NovelDetailsViewModel(
            sourceId = novel.source,
            novelUrl = novel.url,
            listingCover = null,
            isFromSource = false,
            novelRepo = novelRepo,
            updateNovel = mockk(relaxed = true),
            sourceTracker = mockk(relaxed = true),
            coverCache = mockk(relaxed = true),
            setNovelChapterFlags = mockk(relaxed = true),
            chapterRepo = chapterRepo,
            downloadManagerProvider = { downloadManager },
            novelDownloadCache = downloadCache,
            sourceManager = sourceManager,
            installer = installer,
            filterChaptersForDownload = mockk(relaxed = true),
            novelLibraryAdder = mockk(relaxed = true),
            setNovelReadStatus = setNovelReadStatus(),
            novelPreferences = novelPreferences,
            uiPreferences = mockk(relaxed = true) {
                every { themeCoverBased } returns store.getBoolean("theme_cover_based", false)
            },
            mergeManager = mergeManager,
            mergedChapterProvider = NovelMergedChapterProvider(
                mergeManager,
                ReconcileMergedChapters(units, setOf(stitcher)),
            ),
            reikaiLibraryPreferences = reikaiLibraryPreferences,
            libraryPreferences = LibraryPreferences(store),
            context = mockk(relaxed = true),
            getCustomNovelInfo = mockk(relaxed = true) { every { subscribe(any()) } returns flowOf(null) },
            setCustomNovelInfo = mockk(relaxed = true),
            getNovelTracks = mockk(relaxed = true),
            refreshNovelTracks = mockk(relaxed = true),
            trackNovelChapter = mockk(relaxed = true),
            trackerManager = mockk(relaxed = true),
            trackPreferences = TrackPreferences(store),
            basePreferences = mockk<BasePreferences>(relaxed = true) {
                every { downloadedOnly } returns this@NovelReaderViewModelHarness.downloadedOnly
            },
            removeNovelsFromLibrary = mockk(relaxed = true),
            trackPorts = mockk(relaxed = true),
            autoBindTrackers = mockk(relaxed = true),
        ).also(::track)
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

    /**
     * Clears every model it opened, as leaving the screen does, and waits for their work to stop before
     * releasing the database and clock. A details model works on the real IO dispatcher, and a query
     * of its still running against a closed database throws into whichever test is running by then.
     */
    suspend fun close() {
        diskProbeGate?.open()
        viewModels.clear()
        withContext(NonCancellable) { modelJobs.joinAll() }
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

    override val version = "1.0.0"
    override val site = "https://$id.example"
    override val lang = "en"
    override val iconUrl: String? = null
    override val format = NovelExtensionFormat.JS
    override val extensionName = name
    override val contentWarning = ContentWarning.SAFE

    override suspend fun parseChapter(chapterPath: String): String {
        if (chapterPath in failing) throw IOException("no connection")
        return "<p>From the source: $chapterPath</p>"
    }

    override suspend fun browse(listing: NovelListing, page: Int, filters: NovelFilterState?): NovelItemsPage =
        unused()

    override suspend fun search(query: String, page: Int, filters: NovelFilterState?): NovelItemsPage = unused()

    override suspend fun parseNovel(novelPath: String): SourceNovel = unused()

    private fun unused(): Nothing = throw UnsupportedOperationException("Not part of reading a chapter")
}
