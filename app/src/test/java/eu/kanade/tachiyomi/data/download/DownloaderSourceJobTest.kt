package eu.kanade.tachiyomi.data.download

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.online.HttpSource
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import reikai.presentation.recents.EmittingPreferenceStore
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import java.io.IOException

/** How Mihon's downloader runs a source's chapters; only the source, the disk and the clock are faked. */
class DownloaderSourceJobTest {

    private val manga = Manga.create().copy(id = 1L, source = 1L, title = "Manga")
    private val first = chapter(10L)
    private val second = chapter(11L)

    private val fetched = mutableListOf<String>()
    private var fetching = 0
    private var mostFetchingAtOnce = 0

    // A fetch that ignores cancellation stands in for a page write, which blocks.
    private val blockingFetch = CompletableDeferred<Unit>()
    private var fetch: suspend (String) -> List<Page> = { url ->
        withContext(NonCancellable) { blockingFetch.await() }
        throw IOException("$url gone")
    }

    private val source = mockk<HttpSource> {
        every { id } returns manga.source
        coEvery { getPageList(any()) } coAnswers {
            val url = firstArg<SChapter>().url
            fetched += url
            mostFetchingAtOnce = maxOf(mostFetchingAtOnce, ++fetching)
            try {
                fetch(url)
            } finally {
                fetching--
            }
        }
        // A page image never finishes arriving.
        coEvery { getImage(any(), any()) } coAnswers { awaitCancellation() }
    }
    private val folder: UniFile = mockk(relaxed = true) {
        every { createDirectory(any()) } returns this
        every { findFile(any()) } returns null
        every { listFiles() } returns emptyArray()
    }
    private val context = mockk<Context>(relaxed = true)

    @AfterEach
    fun tearDown() {
        blockingFetch.complete(Unit)
        Dispatchers.resetMain()
        unmockkStatic(Dispatchers::class)
    }

    @Test
    fun `a restarted downloader waits for the canceled one before fetching again`() = runTest {
        val downloader = downloader()
        downloader.queueChapters(manga, listOf(first), autoStart = false)
        downloader.start()
        testScheduler.advanceUntilIdle()

        downloader.pause()
        downloader.start()
        testScheduler.advanceUntilIdle()

        mostFetchingAtOnce shouldBe 1
    }

    @Test
    fun `a source's next chapter starts while the last pages of the current one download`() = runTest {
        fetch = { listOf(Page(0, imageUrl = "$it/0")) }
        val downloader = downloader()
        downloader.queueChapters(manga, listOf(first, second), autoStart = false)
        downloader.start()
        testScheduler.advanceUntilIdle()

        fetched shouldContain second.url
    }

    private fun TestScope.downloader(): Downloader {
        // The downloader restores its saved queue on Main as it is built and downloads on IO, both the test's clock.
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        mockkStatic(Dispatchers::class)
        every { Dispatchers.IO } returns StandardTestDispatcher(testScheduler)
        return Downloader(
            context = context,
            provider = DownloadProvider(
                context,
                mockk<StorageManager> { every { getDownloadsDirectory() } returns folder },
                LibraryPreferences(InMemoryPreferenceStore()),
            ),
            cache = mockk(relaxed = true),
            sourceManager = mockk<SourceManager>().also { coEvery { it.get(manga.source) } returns source },
            chapterCache = mockk(relaxed = true) { every { isImageInCache(any()) } returns false },
            // The downloader waits on its parallel-source setting, which an in-memory preference never emits.
            downloadPreferences = DownloadPreferences(EmittingPreferenceStore()),
            xml = mockk(),
            getCategories = mockk(),
            getTracks = mockk(),
            store = mockk<DownloadStore>(relaxed = true) { coEvery { restore() } returns emptyList() },
            notifier = mockk(relaxed = true),
        )
    }

    // Mihon queues the highest source order first.
    private fun chapter(id: Long) =
        Chapter.create().copy(id = id, mangaId = manga.id, url = "u$id", name = "Ch $id", sourceOrder = -id)
}
