package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import mihon.data.cache.ChapterCache
import okhttp3.Response
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.util.resetVirtualDispatchers
import reikai.util.restubDefaultDispatcher
import reikai.util.virtualDispatchers
import tachiyomi.domain.chapter.model.Chapter
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class HttpPageLoaderTest {

    private val source = mockk<HttpSource>()
    private val cachedPageLists = mutableListOf<Pair<Chapter, List<Page>>>()
    private val chapterCache = mockk<ChapterCache>(relaxed = true) {
        coEvery { getImageFile(any()) } returns null
        // Counted here rather than with coVerify, which records the stubbed Dispatchers getters with the call.
        coEvery { putPageList(any(), any()) } answers { cachedPageLists += firstArg<Chapter>() to secondArg() }
    }

    // The loader works on its own IO scope and caches on the app scope's IO, both on the test scheduler here.
    @BeforeEach
    fun setUp() {
        virtualDispatchers()
    }

    @AfterEach
    fun tearDown() = resetVirtualDispatchers()

    private fun loader(chapter: ReaderChapter, canLoadAhead: Boolean, count: Int = 4): HttpPageLoader {
        val readerPreferences = mockk<ReaderPreferences> { every { preloadSize.get() } returns count }
        // The tests stub with coEvery, which leaves Default answering a bare mock.
        restubDefaultDispatcher()
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        return HttpPageLoader(chapter, source, chapterCache, appScope, { canLoadAhead }, readerPreferences)
    }

    @Test
    fun `retry reloads a ready page after an image decoding failure`() = runTest {
        val chapter = mockk<ReaderChapter>(relaxed = true)
        val page = ReaderPage(index = 0, imageUrl = "https://example.com/page.jpg").apply {
            status = Page.State.Ready
        }
        // The image that failed to decode is the one in the cache, so a retry must not read it back.
        coEvery { chapterCache.getImageFile(any()) } returns mockk<File>(relaxed = true)
        val fetches = AtomicInteger()
        coEvery { source.getImage(page) } answers {
            fetches.incrementAndGet()
            mockk<Response>(relaxed = true)
        }
        val loader = loader(chapter, canLoadAhead = true)

        try {
            loader.retryPage(page)
            advanceUntilIdle()

            fetches.get() shouldBe 1
        } finally {
            loader.recycle()
        }
    }

    /** Ready to Ready emits nothing, so a viewer waiting on the refetch would never hear of it. */
    @Test
    fun `retry takes a ready page back through the queue`() = runTest {
        val chapter = mockk<ReaderChapter>(relaxed = true)
        val page = ReaderPage(index = 0, imageUrl = "https://example.com/page.jpg").apply {
            status = Page.State.Ready
        }
        // Holds the refetch before it moves the status itself, so only the retry's own step is read.
        val held = CompletableDeferred<Unit>()
        coEvery { chapterCache.getImageFile(any()) } coAnswers {
            held.await()
            null
        }
        coEvery { source.getImage(page) } coAnswers { awaitCancellation() }
        val loader = loader(chapter, canLoadAhead = true)

        try {
            loader.retryPage(page)
            advanceUntilIdle()
            val status = page.status
            held.complete(Unit)

            status shouldNotBe Page.State.Ready
        } finally {
            loader.recycle()
        }
    }

    @Test
    fun `off Wi-Fi only the user-set count of pages loads ahead`() = runTest {
        loadedFromFirstPage(canLoadAhead = false, preloadSize = 2) shouldContainExactly listOf(0, 1, 2)
    }

    @Test
    fun `when loading ahead is allowed the whole chapter loads`() = runTest {
        loadedFromFirstPage(canLoadAhead = true, preloadSize = 2) shouldContainExactly (0..9).toList()
    }

    /** A write replaces the entry, so writing a cached list back would restart the hour that bounds its signed urls. */
    @Test
    fun `a page list served from the cache is not written back on close`() = runTest {
        coEvery { chapterCache.getPageList(any()) } returns listOf(Page(0, "/0", "https://example.com/0.jpg"))
        val chapter = mockk<ReaderChapter>(relaxed = true)
        every { chapter.pages } returns listOf(ReaderPage(0, imageUrl = "https://example.com/0.jpg"))
        val loader = loader(chapter, canLoadAhead = false)

        loader.getPages()
        loader.recycle()
        advanceUntilIdle()

        cachedPageLists shouldBe emptyList()
    }

    @Test
    fun `a page list fetched from the source is cached on close`() = runTest {
        coEvery { chapterCache.getPageList(any()) } returns null
        coEvery { source.getPageList(any()) } returns listOf(Page(0, "/0", "https://example.com/0.jpg"))
        val own = Chapter.create().copy(id = 7L)
        val chapter = mockk<ReaderChapter>(relaxed = true)
        every { chapter.chapter } returns own
        every { chapter.pages } returns listOf(ReaderPage(0, "/0", "https://example.com/0.jpg"))
        val loader = loader(chapter, canLoadAhead = false)

        loader.getPages()
        loader.recycle()
        advanceUntilIdle()

        cachedPageLists.map { (chapter, pages) ->
            chapter to pages.map { Triple(it.index, it.url, it.imageUrl) }
        } shouldBe
            listOf(own to listOf(Triple(0, "/0", "https://example.com/0.jpg")))
    }

    /** The indices a ten-page chapter fetches once its first page is asked for, once every load has settled. */
    private suspend fun TestScope.loadedFromFirstPage(canLoadAhead: Boolean, preloadSize: Int): List<Int> {
        val chapter = mockk<ReaderChapter>(relaxed = true)
        val pages = List(10) { ReaderPage(it, imageUrl = "https://example.com/$it.jpg") }
            .onEach { it.chapter = chapter }
        every { chapter.pages } returns pages
        val fetched = sortedSetOf<Int>()
        coEvery { source.getImage(any()) } answers {
            fetched += firstArg<ReaderPage>().index
            mockk<Response>(relaxed = true)
        }
        val loader = loader(chapter, canLoadAhead, count = preloadSize)
        try {
            loader.loadPage(pages.first())
            advanceUntilIdle()
            return fetched.toList()
        } finally {
            loader.recycle()
        }
    }
}
