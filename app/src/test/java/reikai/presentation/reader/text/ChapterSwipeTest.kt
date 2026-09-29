package reikai.presentation.reader.text

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The chapter-swipe rule the native renderer runs and the WebView page restates (reader.js). */
class ChapterSwipeTest {

    private val width = 1000f
    private val minimum = 180f

    @Test
    fun `a long swipe leftwards from the right half steps forward`() {
        chapterSwipeStep(dx = -300f, dy = 0f, startX = 900f, width = width, minimum = minimum) shouldBe true
    }

    @Test
    fun `a long swipe rightwards from the left half steps back`() {
        chapterSwipeStep(dx = 300f, dy = 0f, startX = 100f, width = width, minimum = minimum) shouldBe false
    }

    @Test
    fun `a swipe of exactly the minimum does not step`() {
        chapterSwipeStep(dx = -180f, dy = 0f, startX = 900f, width = width, minimum = minimum) shouldBe null
    }

    @Test
    fun `a swipe exactly twice as wide as it is tall does not step`() {
        chapterSwipeStep(dx = -300f, dy = 150f, startX = 900f, width = width, minimum = minimum) shouldBe null
    }

    @Test
    fun `a swipe started on the half it moves towards does not step`() {
        chapterSwipeStep(dx = 300f, dy = 0f, startX = 600f, width = width, minimum = minimum) shouldBe null
    }
}
