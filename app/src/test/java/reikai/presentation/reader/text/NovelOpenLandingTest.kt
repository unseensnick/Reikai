package reikai.presentation.reader.text

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class NovelOpenLandingTest {

    // Chapter 2 was opened; 1 is above it and 3 below.
    private val landing = NovelOpenLanding(openedId = 2L, isEarlier = { it < 2L })

    @Test
    fun `an earlier chapter the open landed on may not be read`() {
        landing.counts(1L, 100)
        landing.mayRead(1L) shouldBe false
    }

    @Test
    fun `an earlier chapter's first position is the landing's rather than the reader's`() {
        landing.counts(1L, 100) shouldBe false
    }

    @Test
    fun `the same position again is still the landing`() {
        landing.counts(1L, 100)
        landing.counts(1L, 100) shouldBe false
    }

    @Test
    fun `an earlier chapter moving from where it landed is the reader`() {
        landing.counts(1L, 100)
        landing.counts(1L, 99) shouldBe true
    }

    @Test
    fun `an earlier chapter the reader has moved in may be read`() {
        landing.counts(1L, 100)
        landing.counts(1L, 99)
        landing.mayRead(1L) shouldBe true
    }

    @Test
    fun `an earlier chapter may be read once the reader has moved the page into it`() {
        landing.counts(2L, 0)
        landing.readerMoved()
        landing.counts(1L, 100)
        landing.mayRead(1L) shouldBe true
    }

    @Test
    fun `an earlier chapter's position counts once the reader has moved the page into it`() {
        landing.counts(2L, 0)
        landing.readerMoved()
        landing.counts(1L, 100) shouldBe true
    }

    @Test
    fun `moving the page without scrolling it leaves an earlier chapter unread`() {
        // A drag at the end of the list: nothing scrolls, so the renderer reports where it already was.
        landing.counts(1L, 100)
        landing.readerMoved()
        landing.counts(1L, 100)
        landing.mayRead(1L) shouldBe false
    }

    @Test
    fun `moving the page without scrolling it keeps an earlier chapter's position the landing's`() {
        landing.counts(1L, 100)
        landing.readerMoved()
        landing.counts(1L, 100) shouldBe false
    }

    @Test
    fun `moving the page before anything is reported leaves an earlier chapter unread`() {
        landing.readerMoved()
        landing.mayRead(1L) shouldBe false
    }

    @Test
    fun `the opened chapter moving lets an earlier chapter's position count`() {
        landing.counts(2L, 40)
        landing.counts(2L, 41)
        landing.counts(1L, 100) shouldBe true
    }

    @Test
    fun `the opened chapter's own position always counts`() {
        landing.counts(2L, 0) shouldBe true
    }

    @Test
    fun `a later chapter may always be read`() {
        landing.mayRead(3L) shouldBe true
    }

    @Test
    fun `a later chapter's position counts`() {
        landing.counts(3L, 0) shouldBe true
    }
}
