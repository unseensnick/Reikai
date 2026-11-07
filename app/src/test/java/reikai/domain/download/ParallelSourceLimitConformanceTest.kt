package reikai.domain.download

import android.app.NotificationManager
import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.download.DownloadStore
import eu.kanade.tachiyomi.data.download.Downloader
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
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.manga.MangaPreferences
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.domain.source.ReikaiSourcePreferences
import reikai.novel.download.FakeSharedPreferences
import reikai.novel.download.NovelDownloadManager
import reikai.novel.download.NovelDownloadWorker
import reikai.novel.source.NovelSource
import reikai.novel.source.NovelSourceManager
import reikai.presentation.recents.EmittingPreferenceStore
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
import kotlin.time.Instant

/**
 * Both downloaders run as many of a queue's sources at once as the parallel source limit says, out-of-range values
 * from a restored backup included, over the real Downloader and the real novel drain. How each reacts to a reorder
 * mid-chapter differs on purpose: a novel lane finishes its chapter, so the pin is the count.
 */
class ParallelSourceLimitConformanceTest {

    private val preferences = DownloadPreferences(EmittingPreferenceStore())

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkObject(NovelDownloadWorker.Companion)
        unmockkStatic(Context::activeNetworkState)
        unmockkStatic(Dispatchers::class)
    }

    @ParameterizedTest(name = "{0} at limit {1}")
    @MethodSource("cases")
    fun `sources run side by side up to the limit`(half: Half, limit: Int, expected: Int) = runTest {
        preferences.parallelSourceLimit.set(limit)

        val started = when (half) {
            Half.MANGA -> sourcesStartedByManga()
            Half.NOVELS -> sourcesStartedByNovels()
        }

        started shouldBe expected
    }

    enum class Half { MANGA, NOVELS }

    private suspend fun TestScope.sourcesStartedByManga(): Int {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        mockIoDispatcher(StandardTestDispatcher(testScheduler))
        val started = mutableSetOf<Long>()
        val mangas = SOURCES.map { Manga.create().copy(id = it, source = it, title = "M$it") }
        val sources = mangas.associate { manga ->
            manga.source to mockk<HttpSource> {
                every { id } returns manga.source
                every { name } returns "S${manga.source}"
                coEvery { getPageList(any()) } coAnswers {
                    started += manga.source
                    awaitCancellation()
                }
            }
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
            sourceManager = mockk<SourceManager>().also { manager ->
                sources.forEach { (id, source) -> coEvery { manager.get(id) } returns source }
            },
            chapterCache = mockk(relaxed = true) { coEvery { openImage(any()) } returns null },
            downloadPreferences = preferences,
            xml = mockk(),
            getCategories = mockk(),
            getTracks = mockk(),
            store = mockk<DownloadStore>(relaxed = true) { coEvery { restore() } returns emptyList() },
            notifier = mockk(relaxed = true),
            mangaPreferences = MangaPreferences(InMemoryPreferenceStore()),
            sourceContacts = testScheduler.sourceContacts(),
        )
        mangas.forEach { manga ->
            val chapter = Chapter.create().copy(
                id = manga.id * 10,
                mangaId = manga.id,
                url = "u${manga.id}",
                name = "Ch",
            )
            downloader.queueChapters(manga, listOf(chapter), autoStart = false)
        }
        downloader.start()
        advanceUntilIdle()
        downloader.stop()
        advanceUntilIdle()
        return started.size
    }

    private suspend fun TestScope.sourcesStartedByNovels(): Int {
        mockkObject(NovelDownloadWorker.Companion)
        every { NovelDownloadWorker.start(any()) } just runs
        mockkStatic(Context::activeNetworkState)
        every { any<Context>().activeNetworkState() } returns NetworkState(true, true, true)
        mockIoDispatcher(StandardTestDispatcher(testScheduler))
        val started = mutableSetOf<String>()
        val novels = SOURCES.map { Novel.create().copy(id = it, source = "s$it", title = "N$it") }
        val chapters = novels.map { novel ->
            NovelChapter(
                id = novel.id * 10, novelId = novel.id, url = "u${novel.id}", name = "Ch", read = false,
                bookmark = false,
                lastTextProgress = 0L, chapterNumber = 1.0, sourceOrder = 1L,
                dateFetch = Instant.fromEpochMilliseconds(0L), dateUpload = null, page = "",
            )
        }
        val sourceManager = mockk<NovelSourceManager>()
        novels.forEach { novel ->
            coEvery { sourceManager.get(novel.source) } returns mockk<NovelSource> {
                every { minimumRequestDelayMs } returns 0L
                coEvery { parseChapter(any()) } coAnswers {
                    started += novel.source
                    awaitCancellation()
                }
            }
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
            chapterRepo = mockk { chapters.forEach { coEvery { getById(it.id) } returns it } },
            novelRepo = mockk<NovelRepository> { novels.forEach { coEvery { getById(it.id) } returns it } },
            sourceManager = sourceManager,
            installer = mockk { coEvery { ensureLoaded() } just runs },
            downloadPreferences = preferences,
            sourcePreferences = ReikaiSourcePreferences(InMemoryPreferenceStore()),
            novelPreferences = NovelPreferences(InMemoryPreferenceStore()),
            saver = mockk(),
            securityPreferences = SecurityPreferences(InMemoryPreferenceStore()),
            adultChecker = mockk { coEvery { adultNovelIdsAmong(any()) } returns emptySet() },
            sourceTitles = mockk(),
            getEntryCustomInfo = mockk { coEvery { await(any()) } returns null },
            sourceContacts = testScheduler.sourceContacts(),
        )
        manager.downloadChapters(chapters)
        val drain = launch { manager.runQueue(onProgress = {}, onError = { _, _, _, _ -> }) }
        advanceUntilIdle()
        drain.cancel()
        return started.size
    }

    companion object {
        private val SOURCES = listOf(1L, 2L, 3L)

        @JvmStatic
        fun cases() = Half.entries.flatMap { half ->
            listOf(1 to 1, 2 to 2, 99 to 3, 0 to 1, -1 to 1).map { (limit, expected) ->
                Arguments.of(half, limit, expected)
            }
        }
    }
}
