package reikai.presentation.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** What a long strip's read-through reports mark read, around an open or a jump. */
class StripReadThroughTest {

    private val above = "page above the top one"
    private val top = "top page"
    private val last = "last page, fully on screen"

    @Test
    fun `a landing marks nothing read while the strip has not moved`() {
        StripReadThrough<String>().report(last, fallback = above, moved = false) shouldBe null
    }

    @Test
    fun `the first move marks read what the landing already read through`() {
        val strip = StripReadThrough<String>()
        strip.report(last, fallback = above, moved = false)
        strip.report(last, fallback = above, moved = true) shouldBe last
    }

    @Test
    fun `the first move does not mark the page above the landing read`() {
        val strip = StripReadThrough<String>()
        strip.report(above, fallback = above, moved = false)
        strip.report(above, fallback = above, moved = true) shouldBe null
    }

    @Test
    fun `a jump lands again, so the page above where it lands is not read`() {
        val strip = StripReadThrough<String>()
        strip.report(top, fallback = above, moved = true)
        strip.land()
        strip.report(above, fallback = above, moved = false) shouldBe null
    }

    @Test
    fun `once moved, only a change of page is read`() {
        val strip = StripReadThrough<String>()
        strip.report(top, fallback = above, moved = true)
        strip.report(top, fallback = above, moved = false) shouldBe null
    }

    @Test
    fun `once moved, a new page read through is read`() {
        val strip = StripReadThrough<String>()
        strip.report(top, fallback = above, moved = true)
        strip.report(last, fallback = above, moved = false) shouldBe last
    }
}
