package reikai.presentation.reader

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ChapterSwitchesTest {

    private val switches = ChapterSwitches()

    @Test
    fun `a page in the active chapter asks for no switch`() {
        switches.requestFromPage(chapterId = 4L, activeChapterId = 4L).shouldBeNull()
    }

    @Test
    fun `a page in a chapter already on its way asks for no second switch`() {
        switches.requestFromPage(5L, activeChapterId = 4L)

        switches.requestFromPage(5L, activeChapterId = 4L).shouldBeNull()
    }

    @Test
    fun `scrolling back into the active chapter before a switch lands asks for it again`() {
        switches.requestFromPage(5L, activeChapterId = 4L)

        switches.requestFromPage(4L, activeChapterId = 4L).shouldNotBeNull()
    }

    @Test
    fun `a switch overtaken by a later one does not land`() {
        val toNext = switches.requestFromPage(5L, activeChapterId = 4L)!!
        switches.requestFromPage(4L, activeChapterId = 4L)

        switches.isLatest(toNext) shouldBe false
    }

    @Test
    fun `the switch nothing overtook lands`() {
        val toNext = switches.requestFromPage(5L, activeChapterId = 4L)!!

        switches.isLatest(toNext) shouldBe true
    }

    @Test
    fun `a pick outranks a switch a page asked for before it`() {
        val toNext = switches.requestFromPage(5L, activeChapterId = 4L)!!
        switches.beginExplicit()

        switches.isLatest(toNext) shouldBe false
    }

    @Test
    fun `after a pick a page in the active chapter asks for no switch`() {
        switches.requestFromPage(5L, activeChapterId = 4L)
        switches.beginExplicit()

        switches.requestFromPage(4L, activeChapterId = 4L).shouldBeNull()
    }

    @Test
    fun `a switch that failed lets the next page in that chapter ask again`() {
        val toNext = switches.requestFromPage(5L, activeChapterId = 4L)!!
        switches.finish(toNext)

        switches.requestFromPage(5L, activeChapterId = 4L).shouldNotBeNull()
    }

    @Test
    fun `an overtaken switch ending leaves the newer one on its way`() {
        val toNext = switches.requestFromPage(5L, activeChapterId = 4L)!!
        switches.requestFromPage(6L, activeChapterId = 4L)
        switches.finish(toNext)

        switches.requestFromPage(6L, activeChapterId = 4L).shouldBeNull()
    }
}
