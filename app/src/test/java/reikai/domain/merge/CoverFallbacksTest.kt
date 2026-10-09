package reikai.domain.merge

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import org.junit.jupiter.api.Test

/** Which members a merged series' cover falls back to, read by the library rows, details and the duplicate cards. */
class CoverFallbacksTest {

    @Test
    fun `the other members follow in the given order, the shown one left out`() {
        coverFallbacks(shown = 2L, members = listOf(3L, 2L, 1L)) { it } shouldContainExactly listOf(3L, 1L)
    }

    @Test
    fun `a lone entry has nothing to fall back to`() {
        coverFallbacks(shown = 1L, members = listOf(1L)) { it }.shouldBeEmpty()
    }
}
