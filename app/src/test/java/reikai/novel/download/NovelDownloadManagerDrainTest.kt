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
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
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
import reikai.presentation.recents.EmittingPreferenceStore
import reikai.util.mockIoDispatcher
import reikai.util.testAppScope
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.download.service.DownloadPreferences
import kotlin.time.Instant

/**
 * The drain against everything else that edits the queue while it runs: a Resume while a paused drain
 * still unwinds a blocking save, and a reorder computed from a copy of the queue the drain has since
 * changed. The manager's own scope runs on the test scheduler, so its store writes finish under
 * advanceUntilIdle.
 */
@OptIn(ExperimentalStdlibApi::class)
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
    private val writtenNames = mutableListOf<String>()
    private var writeDispatcher: CoroutineDispatcher? = null
    private val saver = mockk<NovelChapterSaver> {
        coEvery { prepare(any(), any()) } answers { secondArg() }
        coEvery { write(any(), any(), any()) } coAnswers {
            writeDispatcher = currentCoroutineContext()[CoroutineDispatcher]
            written += secondArg<NovelChapter>().id
            writtenNames += "${firstArg<Novel>().title}/${secondArg<NovelChapter>().name}"
            NovelChapterSaver.SaveResult.SAVED
        }
    }

    private val provider = mockk<NovelDownloadProvider>(relaxed = true) { every { availableSpace() } returns -1L }
    private val novelRepo = mockk<NovelRepository> {
        coEvery { getById(1L) } returns novel
        coEvery { getById(2L) } returns otherNovel
    }
    private val downloadPreferences = DownloadPreferences(EmittingPreferenceStore())

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

    private fun newManager(sourceLimit: Int): NovelDownloadManager {
        downloadPreferences.parallelSourceLimit.set(sourceLimit)
        return NovelDownloadManager(
            scope = testAppScope(),
            context = context,
            provider = provider,
            cache = mockk(relaxed = true) { every { downloadedChapterIds(any(), any()) } returns emptySet() },
            chapterRepo = chapterRepo,
            novelRepo = novelRepo,
            sourceManager = sourceManager,
            installer = mockk { coEvery { ensureLoaded() } just runs },
            downloadPreferences = downloadPreferences,
            sourcePreferences = ReikaiSourcePreferences(InMemoryPreferenceStore()),
            novelPreferences = NovelPreferences(InMemoryPreferenceStore()),
            saver = saver,
            securityPreferences = SecurityPreferences(InMemoryPreferenceStore()),
            adultChecker = mockk { coEvery { adultNovelIdsAmong(any()) } returns emptySet() },
            sourceTitles = mockk(),
            getEntryCustomInfo = mockk { coEvery { await(any()) } returns null },
        )
    }

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

    @Test
    fun `a limit raised mid-drain reaches a waiting source`() = runTest(io) {
        manager = newManager(sourceLimit = 1)

        whileFirstFetchHeld(chapters.take(1) + otherChapter) {
            downloadPreferences.parallelSourceLimit.set(2)
            runCurrent()

            written shouldBe listOf(20L)
        }
    }

    @Test
    fun `a source with nothing left frees its slot without waiting out its pace`() = runTest(io) {
        every { source.minimumRequestDelayMs } returns 1_000L
        firstFetch.complete(Unit)
        manager = newManager(sourceLimit = 1)
        manager.downloadChapters(chapters.take(1) + otherChapter)

        launch { manager.runQueue(onProgress = {}, onError = { _, _, _, _ -> }) }
        runCurrent()

        written shouldBe listOf(10L, 20L)
    }

    @Test
    fun `a slow chapter's images hold up no other source's write`() = runTest(io) {
        val images = CompletableDeferred<Unit>()
        coEvery { saver.prepare(source, any()) } coAnswers {
            images.await()
            secondArg()
        }
        firstFetch.complete(Unit)
        manager.downloadChapters(chapters.take(1) + otherChapter)

        launch { manager.runQueue(onProgress = {}, onError = { _, _, _, _ -> }) }
        runCurrent()
        val writtenWhileHeld = written.toList()
        images.complete(Unit)
        advanceUntilIdle()

        writtenWhileHeld shouldBe listOf(20L)
    }

    @Test
    fun `a chapter renamed while its fetch runs is saved under its new name`() = runTest(io) {
        whileFirstFetchHeld(chapters.take(1)) {
            coEvery { chapterRepo.getById(10L) } returns chapters[0].copy(name = "New")
        }

        writtenNames shouldBe listOf("Novel/New")
    }

    @Test
    fun `a novel renamed while its first chapter's fetch runs is saved under its new title`() = runTest(io) {
        whileFirstFetchHeld(chapters.take(1)) {
            coEvery { novelRepo.getById(1L) } returns novel.copy(title = "New")
        }

        writtenNames shouldBe listOf("New/Ch 10")
    }

    /** Held while a save writes, so a rename can never miss the file or folder that save is making. */
    @Test
    fun `a chapter rename waits for a write in progress`() = runTest(io) {
        whileWriteHeld {
            launch { manager.renameChapter(novel, chapters[0], chapters[0].copy(name = "New")) }
            runCurrent()

            verify(exactly = 0) { provider.renameChapter(any(), any(), any()) }
        }
    }

    @Test
    fun `a novel rename waits for a write in progress before it looks for the folder`() = runTest(io) {
        whileWriteHeld {
            launch { manager.renameNovel(novel, "New") }
            runCurrent()

            verify(exactly = 0) { provider.findNovelDir(any()) }
        }
    }

    private suspend fun TestScope.whileWriteHeld(checks: suspend () -> Unit) {
        val write = CompletableDeferred<Unit>()
        coEvery { saver.write(any(), any(), any()) } coAnswers {
            write.await()
            NovelChapterSaver.SaveResult.SAVED
        }
        firstFetch.complete(Unit)
        manager.downloadChapters(chapters.take(1))
        launch { manager.runQueue(onProgress = {}, onError = { _, _, _, _ -> }) }
        runCurrent()
        try {
            checks()
        } finally {
            write.complete(Unit)
        }
        advanceUntilIdle()
    }

    @Test
    fun `a drain runs on the IO dispatcher whatever started it`() = runTest(io) {
        firstFetch.complete(Unit)
        manager.downloadChapters(chapters.take(1))

        launch(StandardTestDispatcher(testScheduler)) { manager.runQueue(onProgress = {}, onError = { _, _, _, _ -> }) }
        advanceUntilIdle()

        writeDispatcher shouldBe io
    }

    @Test
    fun `a drain paused for the network reports once each check, not once per lane`() = runTest(io) {
        every { any<Context>().activeNetworkState() } returns NetworkState(false, false, false)
        manager.downloadChapters(chapters.take(1))
        var reports = 0

        val drain = launch { manager.runQueue(onProgress = { reports++ }, onError = { _, _, _, _ -> }) }
        runCurrent()
        val firstCheck = reports
        advanceTimeBy(5_001L)
        drain.cancel()

        (firstCheck to reports) shouldBe (1 to 2)
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
    fun `a drain started while a paused one is unwinding leaves its chapter alone until then`() = runTest(io) {
        manager.downloadChapters(chapters)
        val paused = launch { manager.runQueue(onProgress = {}, onError = { _, _, _, _ -> }) }
        runCurrent()
        paused.cancel()

        launch { manager.runQueue(onProgress = {}, onError = { _, _, _, _ -> }) }
        runCurrent()
        val state = manager.queueState.value.first { it.chapterId == 10L }.state
        firstFetch.complete(Unit)
        advanceUntilIdle()

        state shouldBe NovelDownload.State.DOWNLOADING
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
