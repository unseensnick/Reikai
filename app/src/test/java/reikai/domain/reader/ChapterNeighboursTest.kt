package reikai.domain.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The prev/next rule both readers now run. Its asymmetry is the point: the manga reader has always
 * paged back over the whole list so the chapter just finished stays reachable once it is marked read,
 * while paging forward honours the skip settings. The novel reader stepped symmetrically and had no
 * skip settings at all, so a novel could not skip read chapters going forward.
 */
class ChapterNeighboursTest {

    private val chapters = listOf("a", "b", "c", "d", "e")

    // Only "a", "c" and "e" survive the reader's forward filters.
    private val eligible: (String) -> Boolean = { it in setOf("a", "c", "e") }

    private fun next(from: Int) = chapters.neighbourChapter(from, forward = true, eligible)
    private fun prev(from: Int) = chapters.neighbourChapter(from, forward = false, eligible)

    @Test
    fun `going forward skips a chapter the reader is set to skip`() {
        next(from = 0) shouldBe "c"
    }

    @Test
    fun `going forward stops on the first eligible chapter, not the last`() {
        next(from = 2) shouldBe "e"
    }

    @Test
    fun `going forward from the last chapter reaches nothing`() {
        next(from = 4) shouldBe null
    }

    @Test
    fun `going forward past only skipped chapters reaches nothing`() {
        listOf("a", "b").neighbourChapter(0, forward = true, eligible) shouldBe null
    }

    @Test
    fun `going back reaches a skipped chapter, so the one just finished stays reachable`() {
        prev(from = 2) shouldBe "b"
    }

    @Test
    fun `going back from the first chapter reaches nothing`() {
        prev(from = 0) shouldBe null
    }

    @Test
    fun `a chapter not in the list resolves to nothing rather than the end of it`() {
        chapters.neighbourChapter(-1, forward = true, eligible) shouldBe null
    }

    @Test
    fun `with everything eligible, going forward is a single step like going back`() {
        chapters.neighbourChapter(1, forward = true) { true } shouldBe "c"
    }
}
