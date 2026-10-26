package reikai.presentation.reader

import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter

class WebGpuShownPageTest {

    private val previous = loaded(106L)
    private val next = loaded(107L)
    private val later = loaded(108L)

    private val previousLast = previous.pages!!.last()
    private val nextFirst = next.pages!!.first()

    /** Scrolling down: the new chapter's first page was reported while the old one's last is still on top. */
    @Test
    fun `scrolling down into the next chapter keeps the page on screen`() {
        keepsShownPage(previousLast, ViewerChapters(next, previous, later), readerAt = 107L) shouldBe true
    }

    /**
     * Scrolling up: the previous chapter's last page was reported while the next one's first is on top.
     * A neighbour finishing its preload delivers the same window, and keeps the page the same way.
     */
    @Test
    fun `scrolling up into the previous chapter keeps the page on screen`() {
        keepsShownPage(nextFirst, ViewerChapters(previous, null, next), readerAt = 106L) shouldBe true
    }

    @Test
    fun `picking the next chapter lands on its requested page`() {
        keepsShownPage(previousLast, ViewerChapters(next, previous, later), readerAt = 106L) shouldBe false
    }

    @Test
    fun `picking a chapter beyond the loaded ones lands on its requested page`() {
        val far = loaded(300L)

        keepsShownPage(previousLast, ViewerChapters(far, null, null), readerAt = 300L) shouldBe false
    }

    @Test
    fun `a page of the current chapter is kept`() {
        keepsShownPage(nextFirst, ViewerChapters(next, previous, later), readerAt = 106L) shouldBe true
    }

    @Test
    fun `a page a reload replaced lands on the requested page`() {
        val stale = nextFirst
        next.state = ReaderChapter.State.Loaded(pagesOf(next))

        keepsShownPage(stale, ViewerChapters(next, previous, later), readerAt = 107L) shouldBe false
    }

    private fun loaded(id: Long) = ReaderChapter(Chapter.create().copy(id = id)).also {
        it.state = ReaderChapter.State.Loaded(pagesOf(it))
    }

    private fun pagesOf(chapter: ReaderChapter) = List(3) { index ->
        ReaderPage(index).also { it.chapter = chapter }
    }
}
