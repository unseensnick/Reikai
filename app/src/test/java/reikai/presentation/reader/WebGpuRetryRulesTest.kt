package reikai.presentation.reader

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.loader.PageLoader
import eu.kanade.tachiyomi.ui.reader.model.DownloadStream
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter

class WebGpuRetryRulesTest {

    private val failed = ReaderChapter.State.Error(RuntimeException("offline"))

    @Test
    fun `a page the loader fetched after failing no longer waits for its Retry`() {
        holdsForRetry(failedDecoding = false, Page.State.Ready) shouldBe false
    }

    @Test
    fun `a page that failed to load waits while it is still failed`() {
        holdsForRetry(failedDecoding = false, Page.State.Error(Exception("503"))) shouldBe true
    }

    @Test
    fun `a page that failed decoding waits though its file is there`() {
        holdsForRetry(failedDecoding = true, Page.State.Ready) shouldBe true
    }

    @Test
    fun `a neighbour still waiting is loaded on reaching its edge`() {
        shouldAutoPreload(ReaderChapter.State.Wait, isHeld = false) shouldBe true
    }

    @Test
    fun `a neighbour that failed is loaded again after a page turn`() {
        shouldAutoPreload(failed, isHeld = false) shouldBe true
    }

    @Test
    fun `a neighbour that failed is not loaded again until a page turn`() {
        shouldAutoPreload(failed, isHeld = true) shouldBe false
    }

    @Test
    fun `a loaded neighbour is not loaded again`() {
        shouldAutoPreload(ReaderChapter.State.Loaded(emptyList()), isHeld = false) shouldBe false
    }

    @Test
    fun `with transition pages off a failed neighbour still leads to one`() {
        showsTransition(alwaysShowChapterTransition = false, neighbour = failed) shouldBe true
    }

    @Test
    fun `with transition pages off a waiting neighbour leads straight to its pages`() {
        showsTransition(alwaysShowChapterTransition = false, neighbour = ReaderChapter.State.Wait) shouldBe false
    }

    @Test
    fun `with transition pages on a waiting neighbour still leads to one`() {
        showsTransition(alwaysShowChapterTransition = true, neighbour = ReaderChapter.State.Wait) shouldBe true
    }

    @Test
    fun `with both sides failed the next one is offered first`() {
        val next = chapter(2L, failed)

        chapterToRetry(chapter(1L, failed), next) shouldBe next
    }

    @Test
    fun `a transition page offers the side that failed`() {
        val previous = chapter(1L, failed)

        chapterToRetry(previous, chapter(2L, ReaderChapter.State.Loaded(emptyList()))) shouldBe previous
    }

    @Test
    fun `a transition page with nothing failed offers no retry`() {
        chapterToRetry(chapter(1L, ReaderChapter.State.Loading), chapter(2L, ReaderChapter.State.Wait)) shouldBe null
    }

    /** Without one, the forced refetch downloads out of sight and the page stays a ring until it ends. */
    @Test
    fun `a retried page reaches the loader already carrying the stream it renders from`() {
        val loader = RecordingLoader()

        requeueForRetry(ReaderPage(0), loader)

        loader.streamAtRetry shouldNotBe null
    }

    @Test
    fun `an error page that replaces the streaming preview takes the preview with it`() {
        val preview = Any()

        previewAfterError(preview, shown = preview) shouldBe null
    }

    private class RecordingLoader : PageLoader() {
        override var isLocal = false
        var streamAtRetry: DownloadStream? = null

        override suspend fun getPages(): List<ReaderPage> = emptyList()

        override fun retryPage(page: ReaderPage) {
            streamAtRetry = page.downloadStream
        }
    }

    private fun chapter(id: Long, state: ReaderChapter.State): ReaderChapter {
        return ReaderChapter(Chapter.create().copy(id = id)).also { it.state = state }
    }
}
