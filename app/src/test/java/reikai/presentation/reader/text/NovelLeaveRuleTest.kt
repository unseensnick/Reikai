package reikai.presentation.reader.text

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class NovelLeaveRuleTest {

    private val window = listOf(1L, 2L, 3L)

    @Test
    fun `scrolling into the next chapter leaves the one before it behind`() {
        NovelLeaveRule.passedGoingForward(window, from = 1L, to = 2L) shouldBe listOf(1L)
    }

    @Test
    fun `a fling over a whole chapter leaves that chapter behind too`() {
        NovelLeaveRule.passedGoingForward(window, from = 1L, to = 3L) shouldBe listOf(1L, 2L)
    }

    @Test
    fun `scrolling back leaves nothing behind`() {
        NovelLeaveRule.passedGoingForward(window, from = 3L, to = 2L) shouldBe emptyList()
    }

    @Test
    fun `a chapter the window no longer holds leaves nothing behind`() {
        NovelLeaveRule.passedGoingForward(window, from = 9L, to = 2L) shouldBe emptyList()
    }

    @Test
    fun `reaching the end of the last chapter reads it`() {
        NovelLeaveRule.readsOnReachingEnd(hasNext = false, html = "<p>The end.</p>") shouldBe true
    }

    @Test
    fun `reaching the end of a chapter with one after it does not`() {
        NovelLeaveRule.readsOnReachingEnd(hasNext = true, html = "<p>To be continued.</p>") shouldBe false
    }

    @Test
    fun `an empty last chapter is never read by reaching its end`() {
        NovelLeaveRule.readsOnReachingEnd(hasNext = false, html = "<p> </p>") shouldBe false
    }

    @Test
    fun `a last chapter that is only a picture is read by reaching its end`() {
        NovelLeaveRule.readsOnReachingEnd(hasNext = false, html = "<img src=\"afterword.png\">") shouldBe true
    }
}
