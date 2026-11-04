package reikai.domain.download

import android.app.NotificationManager
import android.content.Context
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.Downloader
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.notification.Notifications
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.domain.source.ReikaiSourcePreferences
import reikai.novel.download.FakeSharedPreferences
import reikai.novel.download.NovelDownloadManager
import reikai.novel.download.NovelDownloadWorker
import reikai.util.mockIoDispatcher
import reikai.util.testAppScope
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.model.Manga
import kotlin.time.Instant

/**
 * A paused queue (for want of a network, offline or off Wi-Fi with Wi-Fi-only on, or by the user) that
 * the user empties has nothing left to resume, so its downloader ends, and with it its notifications. A
 * paused queue with chapters left keeps them, and stays paused.
 */
class EmptiedPausedQueueConformanceTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("halves")
    fun `cancelling the last chapter of a queue paused offline ends the downloader`(half: EmptiedQueueHalf) =
        runTest {
            half.removeFromPausedQueue(this, Pause.OFFLINE, Removal.CANCEL_CHAPTER).ended shouldBe true
        }

    @ParameterizedTest(name = "{0}")
    @MethodSource("halves")
    fun `cancelling the last chapter of a queue paused off Wi-Fi ends the downloader`(half: EmptiedQueueHalf) =
        runTest {
            half.removeFromPausedQueue(this, Pause.OFF_WIFI, Removal.CANCEL_CHAPTER).ended shouldBe true
        }

    @ParameterizedTest(name = "{0}")
    @MethodSource("halves")
    fun `deleting the only queued series of a paused queue ends the downloader`(half: EmptiedQueueHalf) = runTest {
        half.removeFromPausedQueue(this, Pause.OFFLINE, Removal.DELETE_SERIES).ended shouldBe true
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("halves")
    fun `renaming the only queued series of a paused queue ends the downloader`(half: EmptiedQueueHalf) = runTest {
        half.removeFromPausedQueue(this, Pause.OFFLINE, Removal.RENAME_SERIES).ended shouldBe true
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("halves")
    fun `cancelling one of two chapters of a queue paused offline keeps the downloader paused`(
        half: EmptiedQueueHalf,
    ) = runTest {
        half.removeFromPausedQueue(this, Pause.OFFLINE, Removal.CANCEL_CHAPTER, queued = 2).ended shouldBe false
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("halves")
    fun `cancelling one of two chapters of a queue paused offline keeps the other queued`(half: EmptiedQueueHalf) =
        runTest {
            half.removeFromPausedQueue(this, Pause.OFFLINE, Removal.CANCEL_CHAPTER, queued = 2).left shouldBe
                listOf(CHAPTER + 1)
        }

    @ParameterizedTest(name = "{0}")
    @MethodSource("halves")
    fun `deleting the last chapter of a queue the user paused dismisses the paused notice`(half: EmptiedQueueHalf) =
        runTest {
            half.removeFromPausedQueue(this, Pause.USER, Removal.DELETE_CHAPTER).noticeDismissed shouldBe true
        }

    @ParameterizedTest(name = "{0}")
    @MethodSource("halves")
    fun `deleting the only series of a queue the user paused dismisses the paused notice`(half: EmptiedQueueHalf) =
        runTest {
            half.removeFromPausedQueue(this, Pause.USER, Removal.DELETE_SERIES).noticeDismissed shouldBe true
        }

    enum class Pause { OFFLINE, OFF_WIFI, USER }

    enum class Removal { CANCEL_CHAPTER, DELETE_CHAPTER, DELETE_SERIES, RENAME_SERIES }

    /** Whether the downloader ended, the chapter ids still queued, and whether the paused notice went. */
    data class Outcome(val ended: Boolean, val left: List<Long>, val noticeDismissed: Boolean)

    companion object {
        const val ENTRY = 1L
        const val CHAPTER = 10L

        /** Chapter ids [CHAPTER], [CHAPTER] + 1, ...; the removal takes the first, or its whole series. */
        fun chapterIds(queued: Int) = List(queued) { CHAPTER + it }

        @JvmStatic
        fun halves() = listOf(MangaEmptiedQueueHalf(), NovelEmptiedQueueHalf())
    }
}

interface EmptiedQueueHalf {
    /** Pauses a queue of [queued] chapters by [pause], then removes the first one by [removal]. */
    suspend fun removeFromPausedQueue(
        test: TestScope,
        pause: EmptiedPausedQueueConformanceTest.Pause,
        removal: EmptiedPausedQueueConformanceTest.Removal,
        queued: Int = 1,
    ): EmptiedPausedQueueConformanceTest.Outcome
}

/**
 * Mihon's downloader, paused by a lost network or by the user, has stopped its job, and DownloadWorker waits
 * while it stays paused; only its stop() clears the pause, so stop() being asked is the manga answer. Every
 * pause kind is the same state, and its clearQueue() is what dismisses the paused notice.
 */
class MangaEmptiedQueueHalf : EmptiedQueueHalf {
    override fun toString() = "manga"

    override suspend fun removeFromPausedQueue(
        test: TestScope,
        pause: EmptiedPausedQueueConformanceTest.Pause,
        removal: EmptiedPausedQueueConformanceTest.Removal,
        queued: Int,
    ): EmptiedPausedQueueConformanceTest.Outcome {
        val manga = Manga.create().copy(id = EmptiedPausedQueueConformanceTest.ENTRY, source = 1L)
        val downloads = EmptiedPausedQueueConformanceTest.chapterIds(queued).map { id ->
            Download(mockk(relaxed = true), manga, Chapter.create().copy(id = id, mangaId = manga.id))
        }
        val queue = MutableStateFlow(downloads)
        var stopped = false
        var cleared = false
        val downloader = mockk<Downloader>(relaxed = true) {
            every { isRunning } returns false
            every { queueState } returns queue
            coEvery { dequeue(any<List<Chapter>>()) } answers {
                val gone = firstArg<List<Chapter>>().mapTo(HashSet()) { it.id }
                queue.value = queue.value.filter { it.chapter.id !in gone }
            }
            coEvery { stop(null) } answers { stopped = true }
            coEvery { clearQueue() } answers {
                cleared = true
                queue.value = emptyList()
            }
        }
        val manager = DownloadManager(
            context = mockk(relaxed = true),
            provider = mockk(relaxed = true) {
                coEvery { findMangaDir(any(), any()) } returns mockk(relaxed = true) {
                    every { name } returns "Old"
                    every { parentFile } returns null
                }
                every { getMangaDirName(any()) } returns "New"
                coEvery { findChapterDirs(any(), any(), any()) } returns (null to emptyList())
            },
            cache = mockk(relaxed = true),
            getCategories = mockk(),
            sourceManager = mockk { coEvery { getOrStub(manga.source) } returns mockk(relaxed = true) },
            downloadPreferences = DownloadPreferences(InMemoryPreferenceStore()),
            getManga = mockk(),
            getChapter = mockk(),
            downloader = downloader,
            pendingDeleter = mockk(),
            sourceTitles = mockk { coEvery { otherMangaTitles(any(), any()) } returns emptyList() },
        )

        val first = downloads.first()
        when (removal) {
            EmptiedPausedQueueConformanceTest.Removal.CANCEL_CHAPTER -> manager.cancelQueuedDownloads(listOf(first))
            EmptiedPausedQueueConformanceTest.Removal.DELETE_CHAPTER ->
                manager.deleteChapters(listOf(first.chapter), manga, mockk(relaxed = true))
            EmptiedPausedQueueConformanceTest.Removal.DELETE_SERIES -> manager.deleteManga(manga, mockk(relaxed = true))
            EmptiedPausedQueueConformanceTest.Removal.RENAME_SERIES -> manager.renameManga(manga, "New")
        }

        return EmptiedPausedQueueConformanceTest.Outcome(
            ended = stopped,
            left = queue.value.map { it.chapter.id },
            noticeDismissed = cleared,
        )
    }
}

/**
 * The novel drain waits out a missing network in a loop, so ending is that drain returning. A user pause
 * stops the worker instead and leaves its own paused notice, which the manager dismisses.
 */
class NovelEmptiedQueueHalf : EmptiedQueueHalf {
    override fun toString() = "novel"

    override suspend fun removeFromPausedQueue(
        test: TestScope,
        pause: EmptiedPausedQueueConformanceTest.Pause,
        removal: EmptiedPausedQueueConformanceTest.Removal,
        queued: Int,
    ): EmptiedPausedQueueConformanceTest.Outcome {
        val novel = Novel.create().copy(id = EmptiedPausedQueueConformanceTest.ENTRY, source = "src", title = "Novel")
        val chapters = EmptiedPausedQueueConformanceTest.chapterIds(queued).map { id -> chapter(id, novel.id) }
        val cancelled = mutableListOf<Int>()
        val notifications = mockk<NotificationManager>(relaxed = true) {
            every { cancel(any<Int>()) } answers { cancelled += firstArg<Int>() }
        }
        val context = mockk<Context>(relaxed = true) {
            every { getSharedPreferences(any(), any()) } returns FakeSharedPreferences()
            every { getSystemService(NotificationManager::class.java) } returns notifications
        }
        val downloadPreferences = DownloadPreferences(InMemoryPreferenceStore())
        downloadPreferences.downloadOnlyOverWifi.set(pause == EmptiedPausedQueueConformanceTest.Pause.OFF_WIFI)
        val online = pause != EmptiedPausedQueueConformanceTest.Pause.OFFLINE
        val onWifi = pause == EmptiedPausedQueueConformanceTest.Pause.USER
        mockkObject(NovelDownloadWorker.Companion)
        mockkStatic(Context::activeNetworkState)
        try {
            every { NovelDownloadWorker.start(any()) } just runs
            every { NovelDownloadWorker.stop(any()) } just runs
            every { any<Context>().activeNetworkState() } returns NetworkState(online, online, onWifi)
            mockIoDispatcher(StandardTestDispatcher(test.testScheduler))
            val manager = NovelDownloadManager(
                scope = testAppScope(),
                context = context,
                provider = mockk(relaxed = true) {
                    every { findNovelDir(novel) } returns mockk(relaxed = true) {
                        every { name } returns "Old"
                        every { parentFile } returns null
                    }
                    every { novelDirName("New") } returns "New"
                },
                cache = mockk(relaxed = true) { every { downloadedChapterIds(novel, any()) } returns emptySet() },
                chapterRepo = mockk { chapters.forEach { ch -> coEvery { getById(ch.id) } returns ch } },
                novelRepo = mockk<NovelRepository> { coEvery { getById(novel.id) } returns novel },
                sourceManager = mockk(),
                installer = mockk { coEvery { ensureLoaded() } just runs },
                downloadPreferences = downloadPreferences,
                sourcePreferences = ReikaiSourcePreferences(InMemoryPreferenceStore()),
                novelPreferences = NovelPreferences(InMemoryPreferenceStore()),
                saver = mockk(),
                securityPreferences = SecurityPreferences(InMemoryPreferenceStore()),
                adultChecker = mockk(),
                sourceTitles = mockk { coEvery { otherNovelTitles(any(), any()) } returns emptyList() },
                getEntryCustomInfo = mockk { coEvery { await(any()) } returns null },
            )
            manager.downloadChapters(chapters)
            val drain = if (pause == EmptiedPausedQueueConformanceTest.Pause.USER) {
                manager.pauseDownloads()
                null
            } else {
                test.backgroundScope.launch { manager.runQueue(onProgress = {}, onError = { _, _, _, _ -> }) }
            }
            test.advanceTimeBy(1)
            test.runCurrent()
            cancelled.clear()

            when (removal) {
                EmptiedPausedQueueConformanceTest.Removal.CANCEL_CHAPTER ->
                    manager.cancelDownloads(listOf(chapters.first().id))
                EmptiedPausedQueueConformanceTest.Removal.DELETE_CHAPTER -> manager.deleteChapters(chapters.take(1))
                EmptiedPausedQueueConformanceTest.Removal.DELETE_SERIES -> manager.awaitDeleteNovel(novel)
                EmptiedPausedQueueConformanceTest.Removal.RENAME_SERIES -> manager.renameNovel(novel, "New") { true }
            }
            test.advanceTimeBy(PAUSE_RECHECK_MS)
            test.runCurrent()

            return EmptiedPausedQueueConformanceTest.Outcome(
                ended = drain?.isCompleted == true,
                left = manager.queueState.value.map { it.chapterId },
                noticeDismissed = Notifications.ID_NOVEL_DOWNLOADER_PAUSED in cancelled,
            )
        } finally {
            unmockkObject(NovelDownloadWorker.Companion)
            unmockkStatic(Context::activeNetworkState)
            unmockkStatic(Dispatchers::class)
        }
    }

    private fun chapter(id: Long, novelId: Long) = NovelChapter(
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

    private companion object {
        // Longer than the drain's recheck interval while it waits for a network.
        const val PAUSE_RECHECK_MS = 10_000L
    }
}
