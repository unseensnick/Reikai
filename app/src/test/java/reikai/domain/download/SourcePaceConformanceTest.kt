package reikai.domain.download

import android.app.NotificationManager
import android.content.Context
import androidx.lifecycle.viewModelScope
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.download.DownloadStore
import eu.kanade.tachiyomi.data.download.Downloader
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.system.NetworkState
import eu.kanade.tachiyomi.util.system.activeNetworkState
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.data.library.updateBySource
import reikai.domain.library.ContentType
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.manga.MangaPreferences
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.domain.source.ReikaiSourcePreferences
import reikai.novel.download.FakeSharedPreferences
import reikai.novel.download.NovelDownload
import reikai.novel.download.NovelDownloadManager
import reikai.novel.download.NovelDownloadWorker
import reikai.novel.source.NovelSource
import reikai.novel.source.NovelSourceManager
import reikai.presentation.recents.EmittingPreferenceStore
import reikai.presentation.settings.download.SourceDelaysViewModel
import reikai.util.mockIoDispatcher
import reikai.util.sourceContacts
import reikai.util.testAppScope
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import java.io.IOException
import kotlin.time.Instant

/**
 * Both downloaders pace a source by [SourcePace], over the real Downloader and the real novel drain: a back-off
 * lasts only while the source has more queued in the run, Off never waits, and a source's own delay, saved the
 * way the settings screen saves it, holds its next chapter. Every chapter fails, which is what backs a source off.
 * Both also wait out the delay from the last contact by [SourceContacts], an update's check or an earlier run.
 */
class SourcePaceConformanceTest {

    enum class Half { MANGA, NOVELS }

    private val store = EmittingPreferenceStore()
    private val mangaPreferences = MangaPreferences(store)
    private val novelPreferences = NovelPreferences(store)

    // Each chapter's fetch, by chapter id, at the test clock's time.
    private val fetches = mutableListOf<Pair<Long, Long>>()

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkObject(NovelDownloadWorker.Companion)
        unmockkStatic(Context::activeNetworkState)
        unmockkStatic(Dispatchers::class)
    }

    @ParameterizedTest
    @EnumSource(Half::class)
    fun `a delay turned Off waits no more, even after a back-off`(half: Half) = runTest {
        globalDelay(half).set(3_000L)
        val engine = engine(half)
        engine.queue(1L, 2L, 3L)
        engine.run()
        advanceTimeBy(1L)

        globalDelay(half).set(0L)
        advanceUntilIdle()

        gapBefore(3L, after = 2L) shouldBe 0L
    }

    /** The second run fetches the failed chapter again, then the new one, from the floor's first doubling. */
    @ParameterizedTest
    @EnumSource(Half::class)
    fun `a back-off is forgotten once the source has nothing more queued`(half: Half) = runTest {
        globalDelay(half).set(3_000L)
        val engine = engine(half)
        engine.queue(1L)
        engine.run()
        advanceUntilIdle()

        engine.queue(2L)
        engine.run()
        advanceUntilIdle()

        gapBefore(2L, after = 1L) shouldBeLessThanOrEqual 7_500L
    }

    @ParameterizedTest
    @EnumSource(Half::class)
    fun `a source's own delay holds its next chapter`(half: Half) = runTest {
        globalDelay(half).set(0L)
        ownDelay(half, 3_000L)
        val engine = engine(half)
        engine.queue(1L, 2L)
        engine.run()
        advanceUntilIdle()

        gapBefore(2L, after = 1L) shouldBeGreaterThanOrEqual 3_000L
    }

    @ParameterizedTest
    @EnumSource(Half::class)
    fun `the first chapter after an update's check waits out its source's delay`(half: Half) = runTest {
        globalDelay(half).set(3_000L)
        val engine = engine(half)
        engine.checkForUpdates()
        engine.queue(1L)
        engine.run()
        advanceUntilIdle()

        fetches.first().second shouldBe 3_000L
    }

    @ParameterizedTest
    @EnumSource(Half::class)
    fun `a run started after another waits from that run's last fetch`(half: Half) = runTest {
        globalDelay(half).set(3_000L)
        val engine = engine(half)
        engine.queue(1L)
        engine.run()
        advanceUntilIdle()

        engine.queue(2L)
        engine.run()
        advanceUntilIdle()

        fetches[1].second - fetches[0].second shouldBeGreaterThanOrEqual 3_000L
    }

    /** Manga's removal restarts the downloader, whose new source job waits only what is left. */
    @ParameterizedTest
    @EnumSource(Half::class)
    fun `a chapter removed while its source waits is never fetched`(half: Half) = runTest {
        globalDelay(half).set(3_000L)
        val engine = engine(half)
        engine.checkForUpdates()
        engine.queue(1L, 2L)
        engine.run()
        advanceTimeBy(1L)

        engine.remove(1L)
        advanceUntilIdle()

        fetches shouldBe listOf(2L to 3_000L)
    }

    @ParameterizedTest
    @EnumSource(Half::class)
    fun `a chapter waiting out its source's delay reads as queued`(half: Half) = runTest {
        globalDelay(half).set(3_000L)
        val engine = engine(half)
        engine.checkForUpdates()
        engine.queue(1L)
        engine.run()
        advanceTimeBy(1L)

        engine.isQueued(1L) shouldBe true
    }

    @ParameterizedTest
    @EnumSource(Half::class)
    fun `a pause while a source waits out its delay leaves its chapter queued and unfetched`(half: Half) = runTest {
        globalDelay(half).set(3_000L)
        val engine = engine(half)
        engine.checkForUpdates()
        engine.queue(1L)
        engine.run()
        advanceTimeBy(1L)

        engine.pause()
        advanceUntilIdle()

        (fetches to engine.isQueued(1L)) shouldBe (emptyList<Pair<Long, Long>>() to true)
    }

    @ParameterizedTest
    @EnumSource(Half::class)
    fun `at Off the first chapter after an update's check starts at once`(half: Half) = runTest {
        globalDelay(half).set(0L)
        val engine = engine(half)
        engine.checkForUpdates()
        engine.queue(1L)
        engine.run()
        advanceUntilIdle()

        fetches.first().second shouldBe 0L
    }

    private interface Engine {
        suspend fun queue(vararg chapterIds: Long)
        suspend fun run()

        /** One entry's update check, through the update jobs' own kernel. */
        suspend fun checkForUpdates()
        suspend fun remove(chapterId: Long)

        /** The user's pause, through each engine's own verb. */
        suspend fun pause()
        fun isQueued(chapterId: Long): Boolean
    }

    private lateinit var contacts: SourceContacts

    private fun globalDelay(half: Half) = when (half) {
        Half.MANGA -> mangaPreferences.downloadChapterDelayMs()
        Half.NOVELS -> novelPreferences.downloadChapterDelayMs()
    }

    private fun gapBefore(chapterId: Long, after: Long): Long {
        val index = fetches.indexOfFirst { it.first == chapterId }
        val previous = fetches.subList(0, index).last { it.first == after }
        return fetches[index].second - previous.second
    }

    // Through the settings screen's own save, so the key it writes is the one the engine reads.
    private suspend fun TestScope.ownDelay(half: Half, delayMs: Long) {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val model = SourceDelaysViewModel(
            sourceManager = mockk { every { sources } returns MutableStateFlow(listOf(httpSource)) },
            novelSourceManager = mockk<NovelSourceManager> {
                every { loadedSources() } returns MutableStateFlow(listOf(novelSource))
            },
            reikaiLibraryPreferences = ReikaiLibraryPreferences(store),
            mangaPreferences = mangaPreferences,
            novelPreferences = novelPreferences,
        )
        val sections = model.state.first { it.sections != null }.sections!!
        val row = if (half == Half.MANGA) sections.manga.single() else sections.novels.single()
        model.setDelay(row, delayMs)
        model.viewModelScope.cancel()
    }

    private fun TestScope.engine(half: Half): Engine {
        contacts = testScheduler.sourceContacts()
        return when (half) {
            Half.MANGA -> mangaEngine()
            Half.NOVELS -> novelEngine()
        }
    }

    private val manga = Manga.create().copy(id = 1L, source = 1L, title = "Manga")
    private val httpSource = mockk<HttpSource> {
        every { id } returns manga.source
        every { name } returns "Manga source"
        every { lang } returns "en"
    }

    private fun TestScope.mangaEngine(): Engine {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        mockIoDispatcher(StandardTestDispatcher(testScheduler))
        coEvery { httpSource.getPageList(any()) } coAnswers {
            fetches += firstArg<SChapter>().url.removePrefix("u").toLong() to testScheduler.currentTime
            throw IOException("gone")
        }
        val folder = mockk<UniFile>(relaxed = true) {
            every { createDirectory(any()) } returns this
            every { findFile(any()) } returns null
            every { listFiles() } returns emptyArray()
        }
        val context = mockk<Context>(relaxed = true)
        val downloader = Downloader(
            scope = testAppScope(),
            context = context,
            provider = DownloadProvider(
                context,
                mockk<StorageManager> { every { getDownloadsDirectory() } returns folder },
                LibraryPreferences(InMemoryPreferenceStore()),
            ),
            cache = mockk(relaxed = true),
            sourceManager = mockk<SourceManager>().also { coEvery { it.get(manga.source) } returns httpSource },
            chapterCache = mockk(relaxed = true) { coEvery { openImage(any()) } returns null },
            downloadPreferences = DownloadPreferences(EmittingPreferenceStore()),
            xml = mockk(),
            getCategories = mockk(),
            getTracks = mockk(),
            store = mockk<DownloadStore>(relaxed = true) { coEvery { restore() } returns emptyList() },
            notifier = mockk(relaxed = true),
            mangaPreferences = mangaPreferences,
            sourceContacts = contacts,
        )
        fun chapter(id: Long) = Chapter.create().copy(id = id, mangaId = manga.id, url = "u$id", sourceOrder = -id)
        return object : Engine {
            // Mihon queues the highest source order first.
            override suspend fun queue(vararg chapterIds: Long) {
                downloader.queueChapters(manga, chapterIds.map(::chapter), autoStart = false)
            }

            override suspend fun run() {
                downloader.start()
            }

            override suspend fun checkForUpdates() = updateBySource(listOf(manga), sourceOf = {
                it.source
            }, contacts) {}

            override suspend fun remove(chapterId: Long) = downloader.dequeue(listOf(chapter(chapterId)))

            override suspend fun pause() = downloader.pause()

            override fun isQueued(chapterId: Long) =
                downloader.queueState.value.single { it.chapter.id == chapterId }.status == Download.State.QUEUE
        }
    }

    private val novel = Novel.create().copy(id = 1L, source = "novels", title = "Novel")
    private val novelSource = mockk<NovelSource> {
        every { id } returns novel.source
        every { name } returns "Novel source"
        every { lang } returns "en"
        every { minimumRequestDelayMs } returns 0L
    }

    private fun TestScope.novelEngine(): Engine {
        mockkObject(NovelDownloadWorker.Companion)
        every { NovelDownloadWorker.start(any()) } just runs
        mockkStatic(Context::activeNetworkState)
        every { any<Context>().activeNetworkState() } returns NetworkState(true, true, true)
        mockIoDispatcher(StandardTestDispatcher(testScheduler))
        // An empty chapter fails without retries, so each fetch is one chapter's whole attempt.
        coEvery { novelSource.parseChapter(any()) } coAnswers {
            fetches += firstArg<String>().removePrefix("u").toLong() to testScheduler.currentTime
            ""
        }
        val chapters = (1L..3L).associateWith { id ->
            NovelChapter(
                id = id, novelId = novel.id, url = "u$id", name = "Ch $id", read = false, bookmark = false,
                lastTextProgress = 0L, chapterNumber = id.toDouble(), sourceOrder = id,
                dateFetch = Instant.fromEpochMilliseconds(0L), dateUpload = null, page = "",
            )
        }
        val manager = NovelDownloadManager(
            scope = testAppScope(),
            context = mockk<Context>(relaxed = true) {
                every { getSharedPreferences(any(), any()) } returns FakeSharedPreferences()
                every { getSystemService(NotificationManager::class.java) } returns
                    mockk<NotificationManager>(relaxed = true)
            },
            provider = mockk { every { availableSpace() } returns -1L },
            cache = mockk { every { downloadedChapterIds(any(), any()) } returns emptySet() },
            chapterRepo = mockk { chapters.values.forEach { coEvery { getById(it.id) } returns it } },
            novelRepo = mockk<NovelRepository> { coEvery { getById(novel.id) } returns novel },
            sourceManager = mockk<NovelSourceManager>().also { coEvery { it.get(novel.source) } returns novelSource },
            installer = mockk { coEvery { ensureLoaded() } just runs },
            downloadPreferences = DownloadPreferences(EmittingPreferenceStore()),
            sourcePreferences = ReikaiSourcePreferences(InMemoryPreferenceStore()),
            novelPreferences = novelPreferences,
            saver = mockk(),
            securityPreferences = SecurityPreferences(InMemoryPreferenceStore()),
            adultChecker = mockk { coEvery { adultNovelIdsAmong(any()) } returns emptySet() },
            sourceTitles = mockk(),
            getEntryCustomInfo = mockk { coEvery { await(any()) } returns null },
            sourceContacts = contacts,
        )
        val scope = this
        // A pause stops the worker, and stopping the worker cancels the drain it runs.
        var drain: Job? = null
        every { NovelDownloadWorker.stop(any()) } answers { drain?.cancel() }
        return object : Engine {
            override suspend fun queue(vararg chapterIds: Long) =
                manager.downloadChapters(chapterIds.map { chapters.getValue(it) })

            override suspend fun run() {
                drain = scope.launch { manager.runQueue(onProgress = {}, onError = { _, _, _, _ -> }) }
            }

            override suspend fun pause() = manager.pauseDownloads()

            override suspend fun checkForUpdates() = updateBySource(listOf(novel), sourceOf = Novel::source, contacts) {
            }

            override suspend fun remove(chapterId: Long) = manager.cancelDownloads(listOf(chapterId))

            override fun isQueued(chapterId: Long) =
                manager.queueState.value.single { it.chapterId == chapterId }.state == NovelDownload.State.QUEUE
        }
    }
}
