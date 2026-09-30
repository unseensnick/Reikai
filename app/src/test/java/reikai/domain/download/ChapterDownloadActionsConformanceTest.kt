package reikai.domain.download

import android.app.NotificationManager
import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.download.DownloadJob
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadNotifier
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.download.DownloadStore
import eu.kanade.tachiyomi.data.download.Downloader
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.online.HttpSource
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
import io.mockk.spyk
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.domain.source.ReikaiSourcePreferences
import reikai.novel.download.FakeSharedPreferences
import reikai.novel.download.NovelDownload
import reikai.novel.download.NovelDownloadJob
import reikai.novel.download.NovelDownloadManager
import reikai.novel.source.NovelSource
import reikai.novel.source.NovelSourceManager
import reikai.presentation.recents.EmittingPreferenceStore
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

/**
 * A chapter row's download control over both engines, each real: the manga half through
 * [MangaChapterDownloadActions] over Mihon's [DownloadManager] and [Downloader], the novel half through
 * [runChapterAction] over a [NovelDownloadManager]. Only the worker, the disk and the source are faked.
 */
class ChapterDownloadActionsConformanceTest {

    @BeforeEach
    fun setUp() {
        // Starting either worker needs WorkManager, which is not under test; each half records the asks.
        mockkObject(NovelDownloadJob.Companion)
        mockkObject(DownloadJob.Companion)
        every { DownloadJob.isRunning(any()) } returns false
        mockkStatic(Context::activeNetworkState)
        every { any<Context>().activeNetworkState() } returns NetworkState(true, true, true)
        // Mihon's Downloader restores its saved queue on the main dispatcher as it is built.
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkObject(NovelDownloadJob.Companion)
        unmockkObject(DownloadJob.Companion)
        unmockkStatic(Context::activeNetworkState)
    }

    private fun conformance(half: DownloadActionsHalf, body: suspend TestScope.() -> Unit) = runTest {
        try {
            body()
        } finally {
            half.close()
        }
    }

    /** Otherwise nothing can start it again: a start is refused while the downloader says it runs. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("halves")
    fun `a queue left with only failures stops the downloader`(half: DownloadActionsHalf) = conformance(half) {
        half.queueFailed(A, scope = backgroundScope)

        half.isRunning() shouldBe false
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("halves")
    fun `starting a failed chapter again queues it to run`(half: DownloadActionsHalf) = conformance(half) {
        half.queueFailed(A, scope = backgroundScope)

        half.run(ChapterDownloadAction.START, listOf(A))

        half.failed() shouldBe emptySet()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("halves")
    fun `starting a failed chapter again starts a stopped downloader`(half: DownloadActionsHalf) = conformance(half) {
        half.queueFailed(A, scope = backgroundScope)

        half.run(ChapterDownloadAction.START, listOf(A))

        half.startAsked shouldBe true
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("halves")
    fun `starting a failed chapter again queues it while another downloads`(half: DownloadActionsHalf) =
        conformance(half) {
            half.queueFailed(A, busy = B, scope = backgroundScope)

            half.run(ChapterDownloadAction.START, listOf(A))

            half.failed() shouldBe emptySet()
        }

    /** Manga only: novels download one chapter at a time, so theirs waits behind the one in flight. */
    @Test
    fun `a manga chapter started again is fetched without waiting for the one in flight`() {
        val half = MangaDownloadActionsHalf()
        conformance(half) {
            half.queueFailed(A, busy = B, scope = backgroundScope)

            half.run(ChapterDownloadAction.START, listOf(A))

            half.awaitFetches(A, times = 2) shouldBe true
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("halves")
    fun `downloading now puts the chapter first`(half: DownloadActionsHalf) = conformance(half) {
        half.queue(listOf(A, B))

        half.run(ChapterDownloadAction.START_NOW, listOf(B))

        half.queued().first() shouldBe B
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("halves")
    fun `cancelling drops only that chapter`(half: DownloadActionsHalf) = conformance(half) {
        half.queue(listOf(A, B))

        half.run(ChapterDownloadAction.CANCEL, listOf(A))

        half.queued() shouldBe listOf(B)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("halves")
    fun `deleting reaches every copy the caller names`(half: DownloadActionsHalf) = conformance(half) {
        half.run(ChapterDownloadAction.DELETE, listOf(A), deleteTargets = listOf(A, COPY))

        half.deleted shouldBe setOf(A, COPY)
    }

    companion object {
        const val A = 10L
        const val B = 11L

        /** Chapter [A] on another source of a merged series. */
        const val COPY = 20L

        @JvmStatic
        fun halves() = listOf(MangaDownloadActionsHalf(), NovelDownloadActionsHalf())
    }
}

interface DownloadActionsHalf {
    /** Queues [ids] in that order. */
    suspend fun queue(ids: List<Long>)

    /**
     * Queues [id] and lets the downloader fail it. With [busy] queued behind it, returns once the
     * downloader is on that one, which never finishes; [scope] carries a drain left running that way.
     */
    suspend fun queueFailed(id: Long, busy: Long? = null, scope: CoroutineScope)

    suspend fun run(action: ChapterDownloadAction, ids: List<Long>, deleteTargets: List<Long> = ids)

    /** The queue, the chapter to download first at the head. */
    fun queued(): List<Long>

    /** Queued chapters that stay failed until something starts them again. */
    fun failed(): Set<Long>

    fun isRunning(): Boolean

    /** Whether the worker was asked to start since the last [queueFailed]. */
    val startAsked: Boolean

    val deleted: Set<Long>

    fun close()
}

/**
 * Mihon's [DownloadManager] over its [Downloader], with a source that fails the chapters in [failing] and
 * never answers for the rest. Only the delete is recorded rather than run, since that reads the disk.
 */
class MangaDownloadActionsHalf : DownloadActionsHalf {
    override fun toString() = "manga"

    private val manga = Manga.create().copy(id = 1L, source = 1L, title = "Manga")

    // Mihon queues the highest source order first.
    private fun chapter(id: Long) =
        Chapter.create().copy(id = id, mangaId = manga.id, url = "u$id", name = "Ch $id", sourceOrder = -id)

    private val failing = ConcurrentHashMap.newKeySet<String>()
    private val fetches = MutableStateFlow(emptyList<String>())
    private val stopped = CompletableDeferred<Unit>()

    @Volatile
    override var startAsked = false
        private set
    override val deleted = mutableSetOf<Long>()

    private val source = mockk<HttpSource> {
        every { id } returns manga.source
        coEvery { getPageList(any()) } coAnswers {
            val url = firstArg<SChapter>().url
            fetches.update { it + url }
            if (url in failing) throw IOException("source down") else awaitCancellation()
        }
    }
    private val context = mockk<Context>(relaxed = true)
    private val folder: UniFile = mockk(relaxed = true) {
        every { createDirectory(any()) } returns this
        every { findFile(any()) } returns null
    }
    private val provider = DownloadProvider(
        context,
        mockk<StorageManager> { every { getDownloadsDirectory() } returns folder },
        LibraryPreferences(InMemoryPreferenceStore()),
    )

    // The downloader waits on its parallel-source setting, which an in-memory preference never emits.
    private val downloadPreferences = DownloadPreferences(EmittingPreferenceStore())

    // Built inside the test: its constructor launches on the main dispatcher the test installs.
    private val downloader by lazy {
        every { DownloadJob.start(any()) } answers { startAsked = true }
        every { DownloadJob.stop(any()) } answers { stopped.complete(Unit) }
        Downloader(
            context = context,
            provider = provider,
            cache = mockk(relaxed = true),
            // Stubbed outside a mockk block, where a bare get binds to MockK's own.
            sourceManager = mockk<SourceManager>().also { coEvery { it.get(manga.source) } returns source },
            chapterCache = mockk(),
            downloadPreferences = downloadPreferences,
            xml = mockk(),
            getCategories = mockk(),
            getTracks = mockk(),
            store = mockk<DownloadStore>(relaxed = true) { coEvery { restore() } returns emptyList() },
            notifier = mockk<DownloadNotifier>(relaxed = true) {
                // The real notice suspends for the adult verdict before it posts.
                coEvery { onError(any(), any(), any()) } coAnswers { delay(ERROR_NOTICE_MS) }
            },
        )
    }
    private val downloadManager by lazy {
        spyk(
            DownloadManager(
                context = context,
                provider = provider,
                cache = mockk(relaxed = true),
                getCategories = mockk(),
                sourceManager = mockk(),
                downloadPreferences = downloadPreferences,
                getManga = mockk(),
                getChapter = mockk(),
                downloader = downloader,
                pendingDeleter = mockk(),
            ),
        ).also { spy ->
            every { spy.deleteChapters(any(), any(), any()) } answers
                { deleted += firstArg<List<Chapter>>().map { it.id } }
        }
    }
    private val actions by lazy {
        MangaChapterDownloadActions(
            downloadManager = downloadManager,
            getManga = mockk { coEvery { await(manga.id) } returns manga },
            sourceManager = mockk { coEvery { getOrStub(manga.source) } returns mockk() },
        )
    }

    override suspend fun queue(ids: List<Long>) = downloadManager.downloadChapters(manga, ids.map(::chapter))

    override suspend fun queueFailed(id: Long, busy: Long?, scope: CoroutineScope) {
        failing += "u$id"
        queue(listOfNotNull(id, busy))
        // The worker's part, which WorkManager would run.
        downloader.start()
        val download = downloadManager.queueState.value.first { it.chapter.id == id }
        awaitOnRealThreads { download.statusFlow.first { it == Download.State.ERROR } }
        if (busy == null) awaitOnRealThreads { stopped.await() } else awaitFetches(busy, times = 1)
        failing -= "u$id"
        startAsked = false
    }

    /** Whether the source was asked for chapter [id]'s pages [times] times, waiting for it if need be. */
    suspend fun awaitFetches(id: Long, times: Int): Boolean {
        awaitOnRealThreads { fetches.first { urls -> urls.count { it == "u$id" } >= times } }
        return fetches.value.count { it == "u$id" } == times
    }

    override suspend fun run(action: ChapterDownloadAction, ids: List<Long>, deleteTargets: List<Long>) =
        actions.run(action, ids.map(::chapter)) { deleteTargets.map(::chapter) }

    override fun queued() = downloadManager.queueState.value.map { it.chapter.id }

    override fun failed() = downloadManager.queueState.value
        .filter { it.status == Download.State.ERROR }
        .mapTo(HashSet()) { it.chapter.id }

    override fun isRunning() = downloader.isRunning

    override fun close() = downloader.pause()

    /** The downloader runs on its own IO threads, so its progress is awaited in real time, bounded. */
    private suspend fun awaitOnRealThreads(block: suspend () -> Unit) {
        withContext(Dispatchers.Default) { withTimeoutOrNull(WAIT) { block() } }
    }

    private companion object {
        const val ERROR_NOTICE_MS = 200L
        val WAIT = 5.seconds
    }
}

/**
 * A [NovelDownloadManager] whose source fails every chapter but the ones in [hanging], which it never
 * answers for. Only its delete is recorded rather than run, since that writes to disk.
 */
class NovelDownloadActionsHalf : DownloadActionsHalf {
    override fun toString() = "novel"

    private val novel = Novel.create().copy(id = 1L, source = "src", title = "Novel")
    private fun chapter(id: Long) = NovelChapter(
        id = id, novelId = novel.id, url = "u$id", name = "Ch $id", read = false, bookmark = false,
        lastTextProgress = 0L, chapterNumber = id.toDouble(), sourceOrder = id, dateFetch = 0L,
        dateUpload = 0L, page = "",
    )

    private val hanging = mutableSetOf<String>()
    private var draining = false

    override var startAsked = false
        private set
    override val deleted = mutableSetOf<Long>()

    private val context = mockk<Context>(relaxed = true) {
        every { getSharedPreferences(any(), any()) } returns FakeSharedPreferences()
        every { getSystemService(NotificationManager::class.java) } returns mockk<NotificationManager>(relaxed = true)
    }
    private val chapterRepo =
        mockk<NovelChapterRepository> { coEvery { getById(any()) } answers { chapter(firstArg()) } }
    private val failingSource = mockk<NovelSource> {
        every { minimumRequestDelayMs } returns 0L
        coEvery { parseChapter(any()) } coAnswers {
            if (firstArg<String>() in hanging) awaitCancellation() else throw IOException("source down")
        }
    }
    private val sourceManager = mockk<NovelSourceManager>().also { coEvery { it.get("src") } returns failingSource }

    private val manager = spyk(
        NovelDownloadManager(
            context = context,
            provider = mockk { every { availableSpace() } returns -1L },
            cache = mockk { every { downloadedChapterIds(novel, any()) } returns emptySet() },
            chapterRepo = chapterRepo,
            novelRepo = mockk<NovelRepository> { coEvery { getById(novel.id) } returns novel },
            sourceManager = sourceManager,
            installer = mockk { coEvery { ensureLoaded() } just runs },
            downloadPreferences = DownloadPreferences(InMemoryPreferenceStore()),
            sourcePreferences = ReikaiSourcePreferences(InMemoryPreferenceStore()),
            novelPreferences = NovelPreferences(InMemoryPreferenceStore()),
            saver = mockk(),
            securityPreferences = SecurityPreferences(InMemoryPreferenceStore()),
            adultChecker = mockk { coEvery { adultNovelIdsAmong(any()) } returns emptySet() },
            removableDownloads = mockk(),
        ),
    ).also { spy ->
        every { spy.deleteChapters(any()) } answers { deleted += firstArg<List<NovelChapter>>().map { it.id } }
    }

    override suspend fun queue(ids: List<Long>) {
        every { NovelDownloadJob.start(any()) } answers { startAsked = true }
        manager.downloadChapters(ids.map(::chapter))
    }

    override suspend fun queueFailed(id: Long, busy: Long?, scope: CoroutineScope) {
        queue(listOfNotNull(id, busy))
        if (busy == null) {
            drain()
        } else {
            hanging += "u$busy"
            scope.launch { drain() }
            manager.queueState.first { queue ->
                queue.any { it.chapterId == busy && it.state == NovelDownload.State.DOWNLOADING }
            }
        }
        startAsked = false
    }

    /** The worker's part, which WorkManager would run. */
    private suspend fun drain() {
        draining = true
        try {
            manager.runQueue(onProgress = {}, onError = { _, _, _, _ -> })
        } finally {
            draining = false
        }
    }

    override suspend fun run(action: ChapterDownloadAction, ids: List<Long>, deleteTargets: List<Long>) =
        manager.runChapterAction(action, ids.map(::chapter)) { deleteTargets.map(::chapter) }

    override fun queued() = manager.queueState.value.map { it.chapterId }

    override fun failed() =
        manager.queueState.value.filter { it.state == NovelDownload.State.ERROR }.mapTo(HashSet()) { it.chapterId }

    override fun isRunning() = draining

    override fun close() = Unit
}
