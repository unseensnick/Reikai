package reikai.presentation.reader

import eu.kanade.domain.chapter.model.toDbChapter
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.ui.reader.loader.PageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter

class ReaderChapterReloadTest {

    private val cache = mockk<ChapterCache>(relaxed = true)

    private fun loadedChapter(): ReaderChapter =
        ReaderChapter(Chapter.create().copy(id = 1L, url = "/1").toDbChapter()).apply {
            state = ReaderChapter.State.Loaded(listOf(ReaderPage(0, "/1/0", "https://img/0")))
        }

    /**
     * An online loader caches the page list its chapter still holds when it is recycled, and does it
     * off-thread, so a reload from the source that recycled first was served the list it had dropped.
     */
    @Test
    fun `a recycled loader finds no page list left to cache again`() {
        val chapter = loadedChapter()
        val loader = PageListRecordingLoader(chapter)
        chapter.pageLoader = loader

        chapter.unloadForReload(fromSource = true, cache)

        loader.pagesOnRecycle shouldBe null
    }

    @Test
    fun `a reload from the source drops the old pages' images`() {
        val chapter = loadedChapter()

        chapter.unloadForReload(fromSource = true, cache)

        verify { cache.removeImage("https://img/0") }
    }

    @Test
    fun `a reload from a downloaded copy leaves the cached images alone`() {
        val chapter = loadedChapter()

        chapter.unloadForReload(fromSource = false, cache)

        verify(exactly = 0) { cache.removeImage(any()) }
    }
}

/** Reads its chapter's pages on recycle, which is what upstream's HttpPageLoader caches from. */
private class PageListRecordingLoader(private val chapter: ReaderChapter) : PageLoader() {
    override var isLocal = false

    var pagesOnRecycle: List<ReaderPage>? = emptyList()
        private set

    override suspend fun getPages(): List<ReaderPage> = emptyList()

    override fun recycle() {
        super.recycle()
        pagesOnRecycle = chapter.pages
    }
}
