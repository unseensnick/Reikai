package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import mihon.data.cache.ChapterCache
import okhttp3.Response
import org.junit.jupiter.api.Test
import reikai.util.testAppScope
import tachiyomi.domain.chapter.model.Chapter
import java.util.Collections

class HttpPageLoaderTest {

    private val source = mockk<HttpSource>()
    private val chapterCache = mockk<ChapterCache>(relaxed = true) {
        coEvery { getImageFile(any()) } returns null
    }

    private fun loader(chapter: ReaderChapter, canLoadAhead: Boolean, count: Int = 4): HttpPageLoader {
        val readerPreferences = mockk<ReaderPreferences> { every { preloadSize.get() } returns count }
        return HttpPageLoader(chapter, source, chapterCache, testAppScope(), { canLoadAhead }, readerPreferences)
    }

    @Test
    fun `retry reloads a ready page after an image decoding failure`() {
        val chapter = mockk<ReaderChapter>(relaxed = true)
        val page = ReaderPage(index = 0, imageUrl = "https://example.com/page.jpg").apply {
            status = Page.State.Ready
        }
        val loader = loader(chapter, canLoadAhead = true)
        coEvery { source.getImage(page) } returns mockk<Response>(relaxed = true)

        try {
            loader.retryPage(page)

            coVerify(timeout = 5_000, exactly = 1) { source.getImage(page) }
        } finally {
            loader.recycle()
        }
    }

    /** Ready to Ready emits nothing, so a viewer waiting on the refetch would never hear of it. */
    @Test
    fun `retry takes a ready page back through the queue`() {
        val chapter = mockk<ReaderChapter>(relaxed = true)
        val page = ReaderPage(index = 0, imageUrl = "https://example.com/page.jpg").apply {
            status = Page.State.Ready
        }
        val loader = loader(chapter, canLoadAhead = true)
        // Holds the refetch before it moves the status itself, so only the retry's own step is read.
        val held = CompletableDeferred<Unit>()
        coEvery { chapterCache.getImageFile(any()) } coAnswers {
            held.await()
            null
        }
        coEvery { source.getImage(page) } coAnswers { awaitCancellation() }

        try {
            loader.retryPage(page)
            val status = page.status
            held.complete(Unit)

            status shouldNotBe Page.State.Ready
        } finally {
            loader.recycle()
        }
    }

    @Test
    fun `off Wi-Fi only the user-set count of pages loads ahead`() = runTest {
        loadedFromFirstPage(canLoadAhead = false, preloadSize = 2, expected = 3) shouldContainExactly listOf(0, 1, 2)
    }

    @Test
    fun `when loading ahead is allowed the whole chapter loads`() = runTest {
        loadedFromFirstPage(canLoadAhead = true, preloadSize = 2, expected = 10) shouldContainExactly (0..9).toList()
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
        withContext(Dispatchers.Default) { delay(200) }

        coVerify(exactly = 0) { chapterCache.putPageList(any(), any()) }
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

        coVerify(timeout = 5_000) {
            chapterCache.putPageList(
                own,
                match { pages ->
                    pages.map { Triple(it.index, it.url, it.imageUrl) } ==
                        listOf(Triple(0, "/0", "https://example.com/0.jpg"))
                },
            )
        }
    }

    /** The indices a ten-page chapter fetches once its first page is asked for, read once [expected] have landed. */
    private suspend fun loadedFromFirstPage(canLoadAhead: Boolean, preloadSize: Int, expected: Int): List<Int> {
        val chapter = mockk<ReaderChapter>(relaxed = true)
        val pages = List(10) { ReaderPage(it, imageUrl = "https://example.com/$it.jpg") }
            .onEach { it.chapter = chapter }
        every { chapter.pages } returns pages
        val fetched = Collections.synchronizedSet(sortedSetOf<Int>())
        coEvery { source.getImage(any()) } answers {
            fetched += firstArg<ReaderPage>().index
            mockk<Response>(relaxed = true)
        }
        val loader = loader(chapter, canLoadAhead, count = preloadSize)
        try {
            loader.loadPage(pages.first())
            // The loads run on the loader's own IO scope, out of virtual time's reach
            withContext(Dispatchers.Default) {
                withTimeoutOrNull(10_000) { while (fetched.size < expected) delay(10) }
                // Long enough for a page past the expected count to show up.
                delay(200)
            }
            return synchronized(fetched) { fetched.toList() }
        } finally {
            loader.recycle()
        }
    }
}
