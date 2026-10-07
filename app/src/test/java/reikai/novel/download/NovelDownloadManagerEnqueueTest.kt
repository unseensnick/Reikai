package reikai.novel.download

import android.app.NotificationManager
import android.content.Context
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.domain.source.ReikaiSourcePreferences
import tachiyomi.core.common.preference.InMemoryPreferenceStore

/**
 * Enqueueing a novel chapter already on disk queues nothing, whichever caller asked, as Mihon's
 * Downloader.queueChapters drops a chapter whose folder exists. Download selected on a mixed selection
 * used to fetch the downloaded chapters again. A chapter queued before the saved queue is restored at
 * launch joins it rather than replacing it, as Mihon's Downloader appends its restored queue.
 */
class NovelDownloadManagerEnqueueTest {

    private val novel = Novel.create().copy(id = 1L, source = "src", title = "Novel")
    private val onDisk = chapter(10L)
    private val missing = chapter(11L)
    private val saved = chapter(12L)

    /** Holds the launch restore where it reads the saved chapter's row. */
    private val restoreGate = CompletableDeferred<Unit>()

    private val io = StandardTestDispatcher()

    private val cache = mockk<NovelDownloadCache> {
        every { downloadedChapterIds(novel, any()) } answers {
            secondArg<List<NovelChapter>>().filter { it == onDisk }.mapTo(HashSet()) { it.id }
        }
    }
    private val novelRepository = mockk<NovelRepository> { coEvery { getById(1L) } returns novel }
    private val chapterRepo = mockk<NovelChapterRepository> {
        coEvery { getById(missing.id) } returns missing
        coEvery { getById(saved.id) } coAnswers {
            restoreGate.await()
            saved
        }
    }
    private val prefs = FakeSharedPreferences()
    private val context = mockk<Context>(relaxed = true) {
        every { getSharedPreferences(any(), any()) } returns prefs
        every { getSystemService(NotificationManager::class.java) } returns mockk<NotificationManager>(relaxed = true)
    }
    private val store = NovelDownloadStore(context, chapterRepo)

    private lateinit var manager: NovelDownloadManager

    @BeforeEach
    fun setUp() {
        // Starting the drain needs WorkManager, which is not under test.
        mockkObject(NovelDownloadWorker.Companion)
        every { NovelDownloadWorker.start(any()) } just runs
        every { NovelDownloadWorker.stop(any()) } just runs
        mockkStatic(Dispatchers::class)
        every { Dispatchers.IO } returns io
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(NovelDownloadWorker.Companion)
        unmockkStatic(Dispatchers::class)
    }

    @Test
    fun `a chapter already on disk is not queued`() = runTest(io) {
        launchManager()
        manager.downloadChapters(listOf(onDisk, missing))

        manager.queueState.value.map { it.chapterId } shouldBe listOf(11L)
    }

    @Test
    fun `a selection wholly on disk queues nothing`() = runTest(io) {
        launchManager()
        manager.downloadChapters(listOf(onDisk))

        manager.queueState.value shouldBe emptyList()
    }

    @Test
    fun `a chapter queued before the saved queue is restored joins it`() = runTest(io) {
        store.addAll(listOf(download(saved)))
        launchManager()

        manager.downloadChapters(listOf(missing))
        restoreGate.complete(Unit)
        advanceUntilIdle()

        manager.queueState.value.map { it.chapterId } shouldBe listOf(11L, 12L)
    }

    @Test
    fun `a reorder before the saved queue is restored keeps the saved chapters saved`() = runTest(io) {
        store.addAll(listOf(download(saved)))
        launchManager()

        manager.downloadChapters(listOf(missing))
        manager.reorderQueue(manager.queueState.value)
        advanceUntilIdle()

        store.persisted().map { it.chapterId }.sorted() shouldBe listOf(11L, 12L)
        restoreGate.complete(Unit)
    }

    @Test
    fun `cancelling everything while the saved queue is restored leaves the queue empty`() = runTest(io) {
        store.addAll(listOf(download(saved)))
        launchManager()
        advanceUntilIdle()

        manager.cancelAllDownloads()
        restoreGate.complete(Unit)
        advanceUntilIdle()

        manager.queueState.value shouldBe emptyList()
    }

    private fun launchManager() {
        manager = NovelDownloadManager(
            context = context,
            provider = mockk(),
            cache = cache,
            chapterRepo = chapterRepo,
            novelRepo = novelRepository,
            sourceManager = mockk(),
            installer = mockk(),
            downloadPreferences = mockk(),
            sourcePreferences = ReikaiSourcePreferences(InMemoryPreferenceStore()),
            novelPreferences = mockk(),
            saver = mockk(),
            securityPreferences = mockk(),
            adultChecker = mockk(),
            sourceTitles = mockk(),
            getEntryCustomInfo = mockk { coEvery { await(any()) } returns null },
        )
    }

    private fun download(chapter: NovelChapter) = NovelDownload(chapter.novelId, chapter.id, chapter.url)

    private fun chapter(id: Long) = NovelChapter(
        id = id,
        novelId = 1L,
        url = "u$id",
        name = "Ch $id",
        read = false,
        bookmark = false,
        lastTextProgress = 0L,
        chapterNumber = id.toDouble(),
        sourceOrder = id,
        dateFetch = 0L,
        dateUpload = 0L,
        page = "",
    )
}
