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
import kotlinx.coroutines.flow.first
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
import reikai.util.mockIoDispatcher
import reikai.util.testAppScope
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.InMemoryPreferenceStore.InMemoryPreference
import tachiyomi.domain.download.service.DownloadPreferences
import kotlin.time.Instant

/**
 * The drain against everything else that edits the queue while it runs: a Resume while a paused drain
 * still unwinds a blocking save, and a reorder computed from a copy of the queue the drain has since
 * changed. The manager's own scope runs on the test scheduler, so its store writes finish under
 * advanceUntilIdle.
 */
class NovelDownloadManagerDrainTest {

    private val novel = Novel.create().copy(id = 1L, source = "src", title = "Novel")
    private val otherNovel = Novel.create().copy(id = 2L, source = "other", title = "Other")
    private val chapters = listOf(10L, 11L).map { chapter(it, novelId = 1L) }
    private val otherChapter = chapter(20L, novelId = 2L)

    private fun chapter(id: Long, novelId: Long) =
        NovelChapter(
            id = id,
            novelId = novelId,
            url = "u$id",
            name = "Ch $id",
            read = false,
            bookmark = false,
            lastTextProgress = 0L,
            chapterNumber = id.toDouble(),
            sourceOrder = id,
            dateFetch = Instant.fromEpochMilliseconds(0L),
            dateUpload = null,
            page = "",
        )

    /** Holds the first chapter's fetch the way a blocking write holds a cancelled worker. */
    private val firstFetch = CompletableDeferred<Unit>()

    private val io = StandardTestDispatcher()

    private val prefs = FakeSharedPreferences()
    private val context = mockk<Context>(relaxed = true) {
        every { getSharedPreferences(any(), any()) } returns prefs
        every { getSystemService(NotificationManager::class.java) } returns mockk<NotificationManager>(relaxed = true)
    }
    private val chapterRepo = mockk<NovelChapterRepository> {
        (chapters + otherChapter).forEach { ch -> coEvery { getById(ch.id) } returns ch }
    }
    private val source = mockk<NovelSource> {
        every { minimumRequestDelayMs } returns 0L
        coEvery { parseChapter("u10") } coAnswers {
            withContext(NonCancellable) { firstFetch.await() }
            "text"
        }
        coEvery { parseChapter("u11") } returns "text"
    }
    private val otherSource = mockk<NovelSource> {
        every { minimumRequestDelayMs } returns 0L
        coEvery { parseChapter("u20") } returns "text"
    }
    private val sourceManager = mockk<NovelSourceManager>().also {
        coEvery { it.get("src") } returns source
        coEvery { it.get("other") } returns otherSource
    }

    private val written = mutableListOf<Long>()
    private val saver = mockk<NovelChapterSaver> {
        coEvery { save(any(), any(), any(), any()) } answers {
            written += secondArg<NovelChapter>().id
            NovelChapterSaver.SaveResult.SAVED
        }
    }

    private lateinit var manager: NovelDownloadManager

    @BeforeEach
    fun setUp() {
        mockkObject(NovelDownloadWorker.Companion)
        every { NovelDownloadWorker.start(any()) } just runs
        mockkStatic(Context::activeNetworkState)
        every { any<Context>().activeNetworkState() } returns NetworkState(true, true, true)
        mockIoDispatcher(io)
        manager = newManager(sourceLimit = 5)
    }

    private fun newManager(sourceLimit: Int) =
        NovelDownloadManager(
            scope = testAppScope(),
            context = context,
            provider = mockk(relaxed = true) { every { availableSpace() } returns -1L },
            cache = mockk(relaxed = true) { every { downloadedChapterIds(any(), any()) } returns emptySet() },
            chapterRepo = chapterRepo,
            novelRepo = mockk<NovelRepository> {
                coEvery { getById(1L) } returns novel
                coEvery { getById(2L) } returns otherNovel
            },
            sourceManager = sourceManager,
            installer = mockk { coEvery { ensureLoaded() } just runs },
            downloadPreferences = DownloadPreferences(
                InMemoryPreferenceStore(
                    sequenceOf(InMemoryPreference("download_parallel_source_limit", sourceLimit, sourceLimit)),
                ),
            ),
            sourcePreferences = ReikaiSourcePreferences(InMemoryPreferenceStore()),
            novelPreferences = NovelPreferences(InMemoryPreferenceStore()),
            saver = saver,
            securityPreferences = SecurityPreferences(InMemoryPreferenceStore()),
            adultChecker = mockk { coEvery { adultNovelIdsAmong(any()) } returns emptySet() },
            sourceTitles = mockk(),
            getEntryCustomInfo = mockk { coEvery { await(any()) } returns null },
        )

    @AfterEach
    fun tearDown() {
        unmockkObject(NovelDownloadWorker.Companion)
        unmockkStatic(Context::activeNetworkState)
        unmockkStatic(Dispatchers::class)
    }

    @Test
    fun `a slow source does not hold up another source`() = runTest(io) {
        whileFirstFetchHeld(chapters.take(1) + otherChapter) {
            manager.queueState.value.map { it.chapterId } shouldBe listOf(10L)
        }
    }

    @Test
    fun `a source fetches one chapter at a time`() = runTest(io) {
        whileFirstFetchHeld(chapters) {
            manager.queueState.value.map { it.chapterId } shouldBe listOf(10L, 11L)
        }
    }

    @Test
    fun `the parallel source limit keeps a source waiting`() = runTest(io) {
        manager = newManager(sourceLimit = 1)

        whileFirstFetchHeld(chapters.take(1) + otherChapter) {
            manager.queueState.value.map { it.chapterId } shouldBe listOf(10L, 20L)
        }
    }

    @Test
    fun `every novel with a fetch running shows as downloading`() = runTest(io) {
        val otherFetch = CompletableDeferred<Unit>()
        coEvery { otherSource.parseChapter("u20") } coAnswers {
            otherFetch.await()
            "text"
        }

        whileFirstFetchHeld(chapters.take(1) + otherChapter) {
            manager.downloadingNovelIds.first() shouldBe setOf(1L, 2L)
            otherFetch.complete(Unit)
        }
    }

    @Test
    fun `a source's pacing holds back its next chapter and nothing else`() = runTest(io) {
        every { source.minimumRequestDelayMs } returns 1_000L
        firstFetch.complete(Unit)
        manager.downloadChapters(chapters + otherChapter)

        launch { manager.runQueue(onProgress = {}, onError = { _, _, _, _ -> }) }
        runCurrent()

        manager.queueState.value.map { it.chapterId } shouldBe listOf(11L)
    }

    @Test
    fun `a drain ends once every source is done`() = runTest(io) {
        firstFetch.complete(Unit)
        manager.downloadChapters(chapters + otherChapter)

        val drain = launch { manager.runQueue(onProgress = {}, onError = { _, _, _, _ -> }) }
        advanceUntilIdle()
        val ended = drain.isCompleted
        drain.cancel()

        (ended to manager.queueState.value) shouldBe (true to emptyList())
    }

    @Test
    fun `a chapter queued mid-drain reaches a free lane`() = runTest(io) {
        whileFirstFetchHeld(chapters.take(1)) {
            manager.downloadChapters(listOf(otherChapter))
            runCurrent()

            manager.queueState.value.map { it.chapterId } shouldBe listOf(10L)
        }
    }

    /** Runs the drain over [queued] and [checks] it with chapter 10's fetch held, then lets it finish. */
    private suspend fun TestScope.whileFirstFetchHeld(queued: List<NovelChapter>, checks: suspend () -> Unit) {
        manager.downloadChapters(queued)
        launch { manager.runQueue(onProgress = {}, onError = { _, _, _, _ -> }) }
        runCurrent()
        try {
            checks()
        } finally {
            firstFetch.complete(Unit)
        }
        advanceUntilIdle()
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

    @Test
    fun `a chapter cancelled while its fetch runs is not written`() = runTest(io) {
        removeMidFetch { manager.cancelDownloads(listOf(10L)) }

        written shouldBe emptyList()
    }

    @Test
    fun `a novel deleted while its chapter's fetch runs does not get the chapter back`() = runTest(io) {
        removeMidFetch { manager.awaitDeleteNovel(novel) }

        written shouldBe emptyList()
    }

    @Test
    fun `a chapter deleted while its fetch runs is not written`() = runTest(io) {
        removeMidFetch { manager.deleteChapters(chapters.take(1)) }

        written shouldBe emptyList()
    }

    /** Starts the drain on the first chapter, runs [remove] while its fetch is held, then lets the fetch finish. */
    private suspend fun TestScope.removeMidFetch(remove: suspend () -> Unit) {
        manager.downloadChapters(chapters.take(1))
        launch { manager.runQueue(onProgress = {}, onError = { _, _, _, _ -> }) }
        runCurrent()
        remove()
        runCurrent()
        firstFetch.complete(Unit)
        advanceUntilIdle()
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
