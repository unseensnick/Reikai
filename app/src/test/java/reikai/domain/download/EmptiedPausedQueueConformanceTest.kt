package reikai.domain.download

import android.app.NotificationManager
import android.content.Context
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.Downloader
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
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import kotlinx.coroutines.CompletableDeferred
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
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.model.Manga

/**
 * A queue paused for want of a network (offline, or off Wi-Fi with Wi-Fi-only on) that the user empties
 * has nothing left to resume, so its downloader ends, and with it the foreground notification.
 */
class EmptiedPausedQueueConformanceTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("halves")
    fun `cancelling the last chapter of a queue paused offline ends the downloader`(half: EmptiedQueueHalf) =
        runTest {
            half.emptyPausedQueue(this, Pause.OFFLINE, Emptying.CANCEL_LAST_CHAPTER) shouldBe true
        }

    @ParameterizedTest(name = "{0}")
    @MethodSource("halves")
    fun `cancelling the last chapter of a queue paused off Wi-Fi ends the downloader`(half: EmptiedQueueHalf) =
        runTest {
            half.emptyPausedQueue(this, Pause.OFF_WIFI, Emptying.CANCEL_LAST_CHAPTER) shouldBe true
        }

    @ParameterizedTest(name = "{0}")
    @MethodSource("halves")
    fun `deleting the only queued series of a paused queue ends the downloader`(half: EmptiedQueueHalf) = runTest {
        half.emptyPausedQueue(this, Pause.OFFLINE, Emptying.DELETE_SERIES) shouldBe true
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("halves")
    fun `renaming the only queued series of a paused queue ends the downloader`(half: EmptiedQueueHalf) = runTest {
        half.emptyPausedQueue(this, Pause.OFFLINE, Emptying.RENAME_SERIES) shouldBe true
    }

    enum class Pause { OFFLINE, OFF_WIFI }

    enum class Emptying { CANCEL_LAST_CHAPTER, DELETE_SERIES, RENAME_SERIES }

    companion object {
        const val ENTRY = 1L
        const val CHAPTER = 10L

        @JvmStatic
        fun halves() = listOf(MangaEmptiedQueueHalf(), NovelEmptiedQueueHalf())
    }
}

interface EmptiedQueueHalf {
    /** Pauses a one-chapter queue by [pause], empties it by [emptying]; whether the downloader ended. */
    suspend fun emptyPausedQueue(
        test: TestScope,
        pause: EmptiedPausedQueueConformanceTest.Pause,
        emptying: EmptiedPausedQueueConformanceTest.Emptying,
    ): Boolean
}

/**
 * Mihon's downloader, paused by a lost network, has stopped its job, and DownloadWorker waits while it stays
 * paused; only its stop() clears the pause, so stop() being asked is the manga answer. The pause kind is
 * the same state.
 */
class MangaEmptiedQueueHalf : EmptiedQueueHalf {
    override fun toString() = "manga"

    override suspend fun emptyPausedQueue(
        test: TestScope,
        pause: EmptiedPausedQueueConformanceTest.Pause,
        emptying: EmptiedPausedQueueConformanceTest.Emptying,
    ): Boolean {
        val manga = Manga.create().copy(id = EmptiedPausedQueueConformanceTest.ENTRY, source = 1L)
        val chapter = Chapter.create().copy(id = EmptiedPausedQueueConformanceTest.CHAPTER, mangaId = manga.id)
        val download = Download(mockk(relaxed = true), manga, chapter)
        val queue = MutableStateFlow(listOf(download))
        val stopped = CompletableDeferred<Unit>()
        val downloader = mockk<Downloader>(relaxed = true) {
            every { isRunning } returns false
            every { queueState } returns queue
            every { removeFromQueue(any<List<Chapter>>()) } answers { queue.value = emptyList() }
            every { stop(null) } answers { stopped.complete(Unit) }
        }
        val manager = DownloadManager(
            context = mockk(relaxed = true),
            provider = mockk(relaxed = true) {
                every { findMangaDir(any(), any()) } returns mockk(relaxed = true) {
                    every { name } returns "Old"
                    every { parentFile } returns null
                }
                every { getMangaDirName(any()) } returns "New"
            },
            cache = mockk(relaxed = true),
            getCategories = mockk(),
            sourceManager = mockk { coEvery { getOrStub(manga.source) } returns mockk(relaxed = true) },
            downloadPreferences = mockk(),
            getManga = mockk(),
            getChapter = mockk(),
            downloader = downloader,
            pendingDeleter = mockk(),
            sourceTitles = mockk { coEvery { otherMangaTitles(any(), any()) } returns emptyList() },
        )

        when (emptying) {
            EmptiedPausedQueueConformanceTest.Emptying.CANCEL_LAST_CHAPTER ->
                manager.cancelQueuedDownloads(listOf(download))
            EmptiedPausedQueueConformanceTest.Emptying.DELETE_SERIES -> manager.deleteManga(
                manga,
                mockk(relaxed = true),
            )
            EmptiedPausedQueueConformanceTest.Emptying.RENAME_SERIES -> manager.renameManga(manga, "New")
        }

        return ManualDeleteConformanceTest.awaitOnRealThreads(stopped) != null
    }
}

/** The novel drain waits out a missing network in a loop; the answer is whether that drain returned. */
class NovelEmptiedQueueHalf : EmptiedQueueHalf {
    override fun toString() = "novel"

    override suspend fun emptyPausedQueue(
        test: TestScope,
        pause: EmptiedPausedQueueConformanceTest.Pause,
        emptying: EmptiedPausedQueueConformanceTest.Emptying,
    ): Boolean {
        val novel = Novel.create().copy(id = EmptiedPausedQueueConformanceTest.ENTRY, source = "src", title = "Novel")
        val chapter = NovelChapter(
            id = EmptiedPausedQueueConformanceTest.CHAPTER,
            novelId = novel.id,
            url = "u",
            name = "Ch",
            read = false,
            bookmark = false,
            lastTextProgress = 0L,
            chapterNumber = 1.0,
            sourceOrder = 1L,
            dateFetch = 0L,
            dateUpload = 0L,
            page = "",
        )
        val context = mockk<Context>(relaxed = true) {
            every { getSharedPreferences(any(), any()) } returns FakeSharedPreferences()
            every { getSystemService(NotificationManager::class.java) } returns
                mockk<NotificationManager>(relaxed = true)
        }
        val downloadPreferences = DownloadPreferences(InMemoryPreferenceStore())
        downloadPreferences.downloadOnlyOverWifi.set(pause == EmptiedPausedQueueConformanceTest.Pause.OFF_WIFI)
        val online = pause == EmptiedPausedQueueConformanceTest.Pause.OFF_WIFI
        mockkObject(NovelDownloadWorker.Companion)
        mockkStatic(Context::activeNetworkState)
        mockkStatic(Dispatchers::class)
        try {
            every { NovelDownloadWorker.start(any()) } just runs
            every { any<Context>().activeNetworkState() } returns NetworkState(online, online, false)
            every { Dispatchers.IO } returns StandardTestDispatcher(test.testScheduler)
            val manager = NovelDownloadManager(
                context = context,
                provider = mockk(relaxed = true) {
                    every { findNovelDir(novel) } returns mockk(relaxed = true) {
                        every { name } returns "Old"
                        every { parentFile } returns null
                    }
                    every { novelDirName("New") } returns "New"
                },
                cache = mockk(relaxed = true) { every { downloadedChapterIds(novel, any()) } returns emptySet() },
                chapterRepo = mockk { coEvery { getById(chapter.id) } returns chapter },
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
            manager.downloadChapters(listOf(chapter))
            val drain = test.backgroundScope.launch { manager.runQueue(onProgress = {}, onError = { _, _, _, _ -> }) }
            test.advanceTimeBy(1)
            test.runCurrent()

            when (emptying) {
                EmptiedPausedQueueConformanceTest.Emptying.CANCEL_LAST_CHAPTER ->
                    manager.cancelDownloads(listOf(chapter.id))
                EmptiedPausedQueueConformanceTest.Emptying.DELETE_SERIES -> manager.awaitDeleteNovel(novel)
                EmptiedPausedQueueConformanceTest.Emptying.RENAME_SERIES -> manager.renameNovel(novel, "New")
            }
            test.advanceTimeBy(PAUSE_RECHECK_MS)
            test.runCurrent()

            return drain.isCompleted
        } finally {
            unmockkObject(NovelDownloadWorker.Companion)
            unmockkStatic(Context::activeNetworkState)
            unmockkStatic(Dispatchers::class)
        }
    }

    private companion object {
        // Longer than the drain's recheck interval while it waits for a network.
        const val PAUSE_RECHECK_MS = 10_000L
    }
}
