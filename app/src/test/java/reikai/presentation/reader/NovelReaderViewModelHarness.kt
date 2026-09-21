package reikai.presentation.reader

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.track.service.TrackPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import reikai.data.merge.MergeGroupRepositoryImpl
import reikai.data.merge.MergedChapterUnitRepositoryImpl
import reikai.data.novel.NovelChapterRepositoryImpl
import reikai.data.novel.NovelHistoryRepositoryImpl
import reikai.data.novel.NovelRepositoryImpl
import reikai.domain.category.GetNovelCategories
import reikai.domain.library.ContentType
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.merge.ReconcileMergedChapters
import reikai.domain.novel.NovelGroupStitcher
import reikai.domain.novel.NovelMergeManager
import reikai.domain.novel.NovelMergedChapterProvider
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.interactor.DeleteNovelChaptersAfterRead
import reikai.domain.novel.interactor.DeleteNovelChaptersBehindReader
import reikai.domain.novel.interactor.SetNovelReadStatus
import reikai.domain.novel.interactor.SetNovelViewerFlags
import reikai.domain.novel.interactor.UpsertNovelHistory
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
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
import reikai.presentation.recents.EmittingPreferenceStore
import tachiyomi.core.common.preference.Preference
import tachiyomi.data.Chapters
import tachiyomi.data.Custom_manga_info
import tachiyomi.data.Custom_novel_info
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.Mangas
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.Novels
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.data.category.CategoryRepositoryImpl
import tachiyomi.domain.library.service.LibraryPreferences
import java.io.IOException

/**
 * A real [NovelReaderViewModel] over an in-memory database, the real repositories, interactors and
 * merge machinery, and preferences that emit when written. Faked only where the app leaves the
 * process: the plugin host ([FakeNovelSource]), the download manager's disk, the tracker network and
 * the clock. Every launch runs on [dispatcher], so `advanceUntilIdle` settles the whole session.
 *
 * [create] it with the test's scheduler, seed rows, [open] a model, drive it, and [close] it afterwards.
 */
class NovelReaderViewModelHarness private constructor(
    scheduler: TestCoroutineScheduler,
    private val driver: JdbcSqliteDriver,
) : AutoCloseable {

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

    private val database = Database(
        driver = driver,
        historyAdapter = History.Adapter(last_readAdapter = DateColumnAdapter),
        mangasAdapter = Mangas.Adapter(
            genreAdapter = StringListColumnAdapter,
            update_strategyAdapter = UpdateStrategyColumnAdapter,
            memoAdapter = MemoColumnAdapter,
        ),
        chaptersAdapter = Chapters.Adapter(memoAdapter = MemoColumnAdapter),
        novelsAdapter = Novels.Adapter(
            genreAdapter = StringListColumnAdapter,
            update_strategyAdapter = UpdateStrategyColumnAdapter,
        ),
        custom_manga_infoAdapter = Custom_manga_info.Adapter(genreAdapter = StringListColumnAdapter),
        custom_novel_infoAdapter = Custom_novel_info.Adapter(genreAdapter = StringListColumnAdapter),
    )
    private val novelRepo = NovelRepositoryImpl(database)
    private val chapterRepo = NovelChapterRepositoryImpl(database)
    private val groups = MergeGroupRepositoryImpl(database)
    private val units = MergedChapterUnitRepositoryImpl(database)

    // Plugins are fetched and evaluated by the installer, which is the network; a registered fake
    // source stands in for what it would have loaded.
    private val installer = mockk<LnPluginInstaller>(relaxed = true)
    private val sourceManager = NovelSourceManager(
        installer = { installer },
        extensionManager = mockk { every { loadedNovelExtensionsFlow } returns flowOf(emptyList()) },
        prefs = mockk(relaxed = true),
    )

    /** Chapter id to the text its downloaded copy holds. */
    private val downloaded = mutableMapOf<Long, String>()

    /** The global Downloaded only switch. */
    val downloadedOnly = store.getBoolean(Preference.appStateKey("pref_downloaded_only"), false)

    val downloadManager = mockk<NovelDownloadManager>(relaxed = true) {
        every { queueState } returns MutableStateFlow(emptyList())
        every { getChapterText(any(), any()) } answers { downloaded[secondArg<NovelChapter>().id] }
        every { isChapterDownloaded(any(), any()) } answers { secondArg<NovelChapter>().id in downloaded }
    }
    private val downloadCache = mockk<NovelDownloadCache> {
        every { isChapterDownloaded(any<Novel>(), any()) } answers { secondArg<NovelChapter>().id in downloaded }
        every { downloadedChapterIds(any(), any()) } answers {
            secondArg<List<NovelChapter>>().mapTo(HashSet()) { it.id }.filterTo(HashSet()) { it in downloaded }
        }
    }

    private val viewModels = ViewModelStore()

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
        return novelRepo.insert(Novel.create().copy(source = source.id, url = url, title = title, favorite = true))!!
    }

    /** [progressPercent] is where the reader last was in it, stored as the reader stores it. */
    suspend fun chapter(
        novelId: Long,
        number: Double,
        read: Boolean = false,
        progressPercent: Int = 0,
    ): SeededChapter {
        val url = "/chapter/$novelId/$number"
        val chapter = NovelChapter(
            id = -1L,
            novelId = novelId,
            url = url,
            name = "Chapter $number",
            read = read,
            bookmark = false,
            lastTextProgress = progressPercent * 100L,
            chapterNumber = number,
            sourceOrder = number.toLong(),
            dateFetch = 0L,
            dateUpload = 0L,
            page = "",
        )
        return SeededChapter(chapterRepo.insert(chapter)!!, url)
    }

    /** Groups [novelIds] into one merged novel, as the merge dialog does. */
    suspend fun merge(vararg novelIds: Long) {
        groups.createGroup(ContentType.NOVELS, novelIds.toList())
    }

    /** Puts a copy of [chapter] on disk, holding [text]. */
    fun download(chapter: SeededChapter, text: String) {
        downloaded[chapter.id] = text
    }

    fun open(novelId: Long, chapterId: Long, sourceScoped: Boolean = false): NovelReaderViewModel {
        val context = mockk<Context>(relaxed = true)
        val reikaiLibraryPreferences = ReikaiLibraryPreferences(store)
        val mergeManager = NovelMergeManager(groups, reikaiLibraryPreferences) {}
        val categories = GetNovelCategories(CategoryRepositoryImpl(database))
        val stitcher = NovelGroupStitcher(groups, novelRepo, chapterRepo, mergeManager, reikaiLibraryPreferences)
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
            upsertNovelHistory = UpsertNovelHistory(NovelHistoryRepositoryImpl(database)),
            setNovelReadStatus = SetNovelReadStatus(
                chapterRepo,
                DeleteNovelChaptersAfterRead(novelPreferences, categories, { downloadManager }, novelRepo),
                mockk(relaxed = true),
            ),
            mergeManager = mergeManager,
            mergedChapterProvider = NovelMergedChapterProvider(
                mergeManager,
                units,
                ReconcileMergedChapters(units, setOf(stitcher)),
            ),
            libraryPreferences = LibraryPreferences(store),
            // The tracker network.
            trackNovelChapter = mockk(relaxed = true),
            trackPreferences = TrackPreferences(store),
            // BasePreferences builds an extension-installer preference whose default calls into
            // android.graphics, so only the incognito switch is carried over. The manga-extension
            // lookup is never reached for a novel source.
            getIncognitoState = GetIncognitoState(
                mockk<BasePreferences> {
                    every { incognitoMode } returns store.getBoolean(Preference.appStateKey("incognito_mode"), false)
                },
                SourcePreferences(store),
                mockk(),
            ),
            setNovelViewerFlags = SetNovelViewerFlags(novelRepo),
            novelDownloadCache = downloadCache,
            deleteChaptersBehindReader = DeleteNovelChaptersBehindReader(
                novelPreferences,
                categories,
                { downloadManager },
                chapterRepo,
            ),
            // Only the Downloaded only switch; BasePreferences itself cannot be built on the JVM.
            basePreferences = mockk<BasePreferences> {
                every { downloadedOnly } returns this@NovelReaderViewModelHarness.downloadedOnly
            },
            context = context,
            io = dispatcher,
        ).also { viewModels.put("novel-$novelId-$chapterId-${viewModels.keys().size}", it) }
    }

    /** Clears every model it opened, as leaving the reader does, then releases the database and clock. */
    override fun close() {
        viewModels.clear()
        driver.close()
        unmockkStatic(SystemClock::class)
    }
}

data class SeededChapter(val id: Long, val url: String)

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
