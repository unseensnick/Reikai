package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import io.kotest.matchers.collections.shouldContainExactly
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import mihon.data.cache.ChapterCache
import okhttp3.Response
import org.junit.jupiter.api.Test
import reikai.util.testAppScope
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

    @Test
    fun `off Wi-Fi only the user-set count of pages loads ahead`() = runTest {
        loadedFromFirstPage(canLoadAhead = false, preloadSize = 2) shouldContainExactly listOf(0, 1, 2)
    }

    @Test
    fun `when loading ahead is allowed the whole chapter loads`() = runTest {
        loadedFromFirstPage(canLoadAhead = true, preloadSize = 2) shouldContainExactly (0..9).toList()
    }

    /** The indices a ten-page chapter fetches once its first page is asked for. */
    private suspend fun loadedFromFirstPage(canLoadAhead: Boolean, preloadSize: Int): List<Int> {
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
            withContext(Dispatchers.Default) { delay(1_000) }
            return synchronized(fetched) { fetched.toList() }
        } finally {
            loader.recycle()
        }
    }
}
