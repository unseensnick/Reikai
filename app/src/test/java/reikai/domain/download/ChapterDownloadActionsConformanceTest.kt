package reikai.domain.download

import android.app.NotificationManager
import android.content.Context
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.model.Download
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
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
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
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.model.Manga
import java.io.IOException

/**
 * A chapter row's download control over both engines: the manga half through [MangaChapterDownloadActions]
 * over a queue that behaves as Mihon's Downloader does, the novel half through [runChapterAction] over a
 * real [NovelDownloadManager].
 */
class ChapterDownloadActionsConformanceTest {

    @BeforeEach
    fun setUp() {
        // Starting the drain needs WorkManager, which is not under test.
        mockkObject(NovelDownloadJob.Companion)
        every { NovelDownloadJob.start(any()) } just runs
        mockkStatic(Context::activeNetworkState)
        every { any<Context>().activeNetworkState() } returns NetworkState(true, true, true)
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(NovelDownloadJob.Companion)
        unmockkStatic(Context::activeNetworkState)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("halves")
    fun `starting a failed chapter again queues it to run`(half: DownloadActionsHalf) = runTest {
        half.queueFailed(A)

        half.run(ChapterDownloadAction.START, listOf(A))

        half.failed() shouldBe emptySet()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("halves")
    fun `downloading now puts the chapter first`(half: DownloadActionsHalf) = runTest {
        half.queue(listOf(A, B))

        half.run(ChapterDownloadAction.START_NOW, listOf(B))

        half.queued().first() shouldBe B
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("halves")
    fun `cancelling drops only that chapter`(half: DownloadActionsHalf) = runTest {
        half.queue(listOf(A, B))

        half.run(ChapterDownloadAction.CANCEL, listOf(A))

        half.queued() shouldBe listOf(B)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("halves")
    fun `deleting reaches every copy the caller names`(half: DownloadActionsHalf) = runTest {
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

    /** Queues [id] and lets its download fail. */
    suspend fun queueFailed(id: Long)

    suspend fun run(action: ChapterDownloadAction, ids: List<Long>, deleteTargets: List<Long> = ids)

    /** The queue, the chapter to download first at the head. */
    fun queued(): List<Long>

    /** Queued chapters that stay failed until something starts them again. */
    fun failed(): Set<Long>

    val deleted: Set<Long>
}

/** Manga's queue faked as Mihon's Downloader behaves: queueing a queued chapter adds nothing, and only a
 *  start turns its failures back into queued downloads. */
class MangaDownloadActionsHalf : DownloadActionsHalf {
    override fun toString() = "manga"

    private val manga = Manga.create().copy(id = 1L, source = 1L, title = "Manga")
    private fun chapter(id: Long) = Chapter.create().copy(id = id, mangaId = manga.id)

    private val queue = mutableListOf<Download>()
    override val deleted = mutableSetOf<Long>()

    private val downloadManager = mockk<DownloadManager> {
        every { getQueuedDownloadOrNull(any()) } answers { queue.find { it.chapter.id == firstArg() } }
        coEvery { downloadChapters(any(), any(), any()) } answers {
            secondArg<List<Chapter>>().filter { chapter -> queue.none { it.chapter.id == chapter.id } }
                .forEach { queue += Download(mockk(), firstArg<Manga>(), it).apply { status = Download.State.QUEUE } }
        }
        every { startDownloads() } answers {
            queue.filter { it.status == Download.State.ERROR }.forEach { it.status = Download.State.QUEUE }
        }
        every { startDownloadNow(any()) } answers {
            val download = queue.first { it.chapter.id == firstArg() }
            queue.remove(download)
            queue.add(0, download)
        }
        every { cancelQueuedDownloads(any()) } answers {
            queue.removeAll(firstArg<List<Download>>())
            Unit
        }
        every { deleteChapters(any(), any(), any()) } answers { deleted += firstArg<List<Chapter>>().map { it.id } }
    }

    private val actions = MangaChapterDownloadActions(
        downloadManager = downloadManager,
        getManga = mockk { coEvery { await(manga.id) } returns manga },
        sourceManager = mockk { coEvery { getOrStub(manga.source) } returns mockk() },
    )

    override suspend fun queue(ids: List<Long>) = downloadManager.downloadChapters(manga, ids.map(::chapter))

    override suspend fun queueFailed(id: Long) {
        queue(listOf(id))
        queue.single { it.chapter.id == id }.status = Download.State.ERROR
    }

    override suspend fun run(action: ChapterDownloadAction, ids: List<Long>, deleteTargets: List<Long>) =
        actions.run(action, ids.map(::chapter)) { deleteTargets.map(::chapter) }

    override fun queued() = queue.map { it.chapter.id }

    override fun failed() = queue.filter { it.status == Download.State.ERROR }.mapTo(HashSet()) { it.chapter.id }
}

/** A real [NovelDownloadManager] whose source always fails, so a drained chapter ends up failed. Only its
 *  delete is recorded rather than run, since that writes to disk. */
class NovelDownloadActionsHalf : DownloadActionsHalf {
    override fun toString() = "novel"

    private val novel = Novel.create().copy(id = 1L, source = "src", title = "Novel")
    private fun chapter(id: Long) = NovelChapter(
        id = id, novelId = novel.id, url = "u$id", name = "Ch $id", read = false, bookmark = false,
        lastTextProgress = 0L, chapterNumber = id.toDouble(), sourceOrder = id, dateFetch = 0L,
        dateUpload = 0L, page = "",
    )

    override val deleted = mutableSetOf<Long>()

    private val context = mockk<Context>(relaxed = true) {
        every { getSharedPreferences(any(), any()) } returns FakeSharedPreferences()
        every { getSystemService(NotificationManager::class.java) } returns mockk<NotificationManager>(relaxed = true)
    }
    private val chapterRepo =
        mockk<NovelChapterRepository> { coEvery { getById(any()) } answers { chapter(firstArg()) } }
    private val failingSource = mockk<NovelSource> {
        every { minimumRequestDelayMs } returns 0L
        coEvery { parseChapter(any()) } throws IOException("source down")
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
            getNovelCategories = mockk(),
        ),
    ).also { spy ->
        every { spy.deleteChapters(any()) } answers { deleted += firstArg<List<NovelChapter>>().map { it.id } }
    }

    override suspend fun queue(ids: List<Long>) = manager.downloadChapters(ids.map(::chapter))

    override suspend fun queueFailed(id: Long) {
        queue(listOf(id))
        manager.runQueue(onProgress = {}, onError = { _, _, _, _ -> })
    }

    override suspend fun run(action: ChapterDownloadAction, ids: List<Long>, deleteTargets: List<Long>) =
        manager.runChapterAction(action, ids.map(::chapter)) { deleteTargets.map(::chapter) }

    override fun queued() = manager.queueState.value.map { it.chapterId }

    override fun failed() =
        manager.queueState.value.filter { it.state == NovelDownload.State.ERROR }.mapTo(HashSet()) { it.chapterId }
}
