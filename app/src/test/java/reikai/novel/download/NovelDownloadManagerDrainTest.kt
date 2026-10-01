package reikai.novel.download

import android.app.NotificationManager
import android.content.Context
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.util.system.NetworkState
import eu.kanade.tachiyomi.util.system.activeNetworkState
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.domain.source.ReikaiSourcePreferences
import reikai.novel.source.NovelSource
import reikai.novel.source.NovelSourceManager
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.download.service.DownloadPreferences

/**
 * The drain against everything else that edits the queue while it runs: a Resume while a paused drain
 * still unwinds a blocking save, and a reorder computed from a copy of the queue the drain has since
 * changed. The manager's own scope runs on the test scheduler, so its store writes finish under
 * advanceUntilIdle.
 */
class NovelDownloadManagerDrainTest {

    private val novel = Novel.create().copy(id = 1L, source = "src", title = "Novel")
    private val chapters = listOf(10L, 11L).map { id ->
        NovelChapter(
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

    /** Holds the first chapter's fetch the way a blocking write holds a cancelled worker. */
    private val firstFetch = CompletableDeferred<Unit>()

    private val io = StandardTestDispatcher()

    private val prefs = FakeSharedPreferences()
    private val context = mockk<Context>(relaxed = true) {
        every { getSharedPreferences(any(), any()) } returns prefs
        every { getSystemService(NotificationManager::class.java) } returns mockk<NotificationManager>(relaxed = true)
    }
    private val chapterRepo = mockk<NovelChapterRepository> {
        chapters.forEach { ch -> coEvery { getById(ch.id) } returns ch }
    }
    private val source = mockk<NovelSource> {
        every { minimumRequestDelayMs } returns 0L
        coEvery { parseChapter("u10") } coAnswers {
            withContext(NonCancellable) { firstFetch.await() }
            "text"
        }
        coEvery { parseChapter("u11") } returns "text"
    }
    private val sourceManager = mockk<NovelSourceManager>().also { coEvery { it.get("src") } returns source }

    private lateinit var manager: NovelDownloadManager

    @BeforeEach
    fun setUp() {
        mockkObject(NovelDownloadJob.Companion)
        every { NovelDownloadJob.start(any()) } just runs
        mockkStatic(Context::activeNetworkState)
        every { any<Context>().activeNetworkState() } returns NetworkState(true, true, true)
        mockkStatic(Dispatchers::class)
        every { Dispatchers.IO } returns io
        manager = NovelDownloadManager(
            context = context,
            provider = mockk { every { availableSpace() } returns -1L },
            cache = mockk { every { downloadedChapterIds(novel, any()) } returns emptySet() },
            chapterRepo = chapterRepo,
            novelRepo = mockk<NovelRepository> { coEvery { getById(1L) } returns novel },
            sourceManager = sourceManager,
            installer = mockk { coEvery { ensureLoaded() } just runs },
            downloadPreferences = DownloadPreferences(InMemoryPreferenceStore()),
            sourcePreferences = ReikaiSourcePreferences(InMemoryPreferenceStore()),
            novelPreferences = NovelPreferences(InMemoryPreferenceStore()),
            saver = mockk { coEvery { save(any(), any(), any(), any()) } returns true },
            securityPreferences = SecurityPreferences(InMemoryPreferenceStore()),
            adultChecker = mockk { coEvery { adultNovelIdsAmong(any()) } returns emptySet() },
            sourceTitles = mockk(),
        )
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(NovelDownloadJob.Companion)
        unmockkStatic(Context::activeNetworkState)
        unmockkStatic(Dispatchers::class)
    }

    @Test
    fun `a drain started while a paused one is still unwinding finishes the queue`() = runTest(io) {
        manager.downloadChapters(chapters)
        val paused = launch { manager.runQueue(onProgress = {}, onError = { _, _, _, _ -> }) }
        runCurrent()
        paused.cancel()

        launch { manager.runQueue(onProgress = {}, onError = { _, _, _, _ -> }) }
        runCurrent()
        firstFetch.complete(Unit)
        advanceUntilIdle()

        manager.queueState.value shouldBe emptyList()
    }

    @Test
    fun `a sort computed before the drain finished does not queue the finished chapters again`() = runTest(io) {
        val sorted = drainAfterSnapshot()

        manager.reorderQueue(sorted)
        advanceUntilIdle()

        manager.queueState.value shouldBe emptyList()
    }

    @Test
    fun `a sort computed before the drain finished does not save the finished chapters again`() = runTest(io) {
        val sorted = drainAfterSnapshot()

        manager.reorderQueue(sorted)
        advanceUntilIdle()

        savedChapterIds() shouldBe emptyList()
    }

    @Test
    fun `a chapter queued after the sort read the queue stays queued`() = runTest(io) {
        val sorted = queueAfterSnapshot()

        manager.reorderQueue(sorted)
        advanceUntilIdle()

        manager.queueState.value.map { it.chapterId } shouldBe listOf(10L, 11L)
    }

    @Test
    fun `a chapter queued after the sort read the queue stays saved`() = runTest(io) {
        val sorted = queueAfterSnapshot()

        manager.reorderQueue(sorted)
        advanceUntilIdle()

        savedChapterIds() shouldBe listOf(10L, 11L)
    }

    /** Queues both chapters, copies the queue reversed as a sort would, then lets the drain finish both. */
    private suspend fun TestScope.drainAfterSnapshot(): List<NovelDownload> {
        manager.downloadChapters(chapters)
        val sorted = manager.queueState.value.reversed()
        firstFetch.complete(Unit)
        launch { manager.runQueue(onProgress = {}, onError = { _, _, _, _ -> }) }
        advanceUntilIdle()
        return sorted
    }

    /** Queues the first chapter, copies the queue as a sort would, then queues the second. */
    private suspend fun queueAfterSnapshot(): List<NovelDownload> {
        manager.downloadChapters(chapters.take(1))
        val sorted = manager.queueState.value.toList()
        manager.downloadChapters(chapters.drop(1))
        return sorted
    }

    private suspend fun savedChapterIds() = NovelDownloadStore(context, chapterRepo).restore().map { it.chapterId }
}
