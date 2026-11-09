package eu.kanade.tachiyomi.data.download

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.source.online.HttpSource
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import reikai.domain.manga.MangaPreferences
import reikai.novel.download.FakeSharedPreferences
import reikai.presentation.recents.EmittingPreferenceStore
import reikai.util.mockIoDispatcher
import reikai.util.sourceContacts
import reikai.util.testAppScope
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager

/**
 * The saved queue restores on IO as the downloader is built, so the live queue can change before it lands.
 * The novel twin is NovelDownloadManagerEnqueueTest. Only storage, the source and the clock are faked.
 */
class DownloaderQueueRestoreTest {

    private val manga = Manga.create().copy(id = 1L, source = 1L, title = "Manga")
    private val source = mockk<HttpSource>(relaxed = true) { every { id } returns manga.source }
    private val chapterGate = CompletableDeferred<Unit>()
    private var gateChapterLoad = false
    private val folder: UniFile = mockk(relaxed = true) {
        every { createDirectory(any()) } returns this
        every { findFile(any()) } returns null
        every { listFiles() } returns emptyArray()
    }
    private val context = mockk<Context>(relaxed = true) {
        every { getSharedPreferences(any(), any()) } returns FakeSharedPreferences()
    }
    private val store = DownloadStore(
        context,
        mockk<SourceManager> { coEvery { get(manga.source) } returns source },
        Json,
        mockk<GetManga> { coEvery { await(manga.id) } returns manga },
        mockk<GetChapter> {
            coEvery { await(any<Long>()) } coAnswers {
                if (gateChapterLoad) chapterGate.await()
                chapter(firstArg())
            }
        },
    )

    @AfterEach
    fun tearDown() {
        chapterGate.complete(Unit)
        Dispatchers.resetMain()
        unmockkStatic(Dispatchers::class)
    }

    @Test
    fun `a queue rewrite before the saved queue is read keeps it`() = runTest {
        store.addAll(listOf(Download(source, manga, chapter(3L))))
        val downloader = downloader()

        downloader.updateQueue { it }
        testScheduler.advanceUntilIdle()

        downloader.queueState.value.map { it.chapter.id } shouldBe listOf(3L)
    }

    @Test
    fun `a chapter queued while the saved queue loads keeps its saved row`() = runTest {
        store.addAll(listOf(Download(source, manga, chapter(3L))))
        gateChapterLoad = true
        val downloader = downloader()
        testScheduler.advanceUntilIdle()

        downloader.queueChapters(manga, listOf(chapter(4L)), autoStart = false)
        chapterGate.complete(Unit)
        testScheduler.advanceUntilIdle()

        store.persisted().map { it.chapterId }.toSet() shouldBe setOf(3L, 4L)
    }

    @Test
    fun `cancel all while the saved queue loads keeps it cleared`() = runTest {
        store.addAll(listOf(Download(source, manga, chapter(3L))))
        gateChapterLoad = true
        val downloader = downloader()
        testScheduler.advanceUntilIdle()

        downloader.clearQueue()
        chapterGate.complete(Unit)
        testScheduler.advanceUntilIdle()

        downloader.queueState.value shouldBe emptyList()
    }

    private fun TestScope.downloader(): Downloader {
        // The restore is launched on Main and reads on IO, both on the test's clock, so it waits to be run.
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        mockIoDispatcher(StandardTestDispatcher(testScheduler))
        return Downloader(
            scope = testAppScope(),
            context = context,
            provider = DownloadProvider(
                context,
                mockk<StorageManager> { every { getDownloadsDirectory() } returns folder },
                LibraryPreferences(InMemoryPreferenceStore()),
            ),
            cache = mockk(relaxed = true),
            sourceManager = mockk<SourceManager>().also { coEvery { it.get(manga.source) } returns source },
            chapterCache = mockk(relaxed = true),
            downloadPreferences = DownloadPreferences(EmittingPreferenceStore()),
            xml = mockk(),
            getCategories = mockk(),
            getTracks = mockk(),
            store = store,
            notifier = mockk(relaxed = true),
            mangaPreferences = MangaPreferences(EmittingPreferenceStore()),
            sourceContacts = testScheduler.sourceContacts(),
        )
    }

    private fun chapter(id: Long) =
        Chapter.create().copy(id = id, mangaId = manga.id, url = "u$id", name = "Ch $id", sourceOrder = -id)
}
