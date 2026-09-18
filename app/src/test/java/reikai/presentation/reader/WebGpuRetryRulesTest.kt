package reikai.presentation.reader

import eu.kanade.tachiyomi.data.database.models.ChapterImpl
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class WebGpuRetryRulesTest {

    private val failed = ReaderChapter.State.Error(RuntimeException("offline"))

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

    private fun chapter(id: Long, state: ReaderChapter.State): ReaderChapter {
        val chapter = ChapterImpl()
        chapter.id = id
        chapter.url = ""
        chapter.name = ""
        return ReaderChapter(chapter).also { it.state = state }
    }
}
