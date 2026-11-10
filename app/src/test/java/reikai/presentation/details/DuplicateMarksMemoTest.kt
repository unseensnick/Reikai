package reikai.presentation.details

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.mockk
import org.junit.jupiter.api.Test

/** A selection tap re-maps the same chapters, which must not run the duplicate rule over them again. */
class DuplicateMarksMemoTest {

    private val memo = DuplicateMarksMemo<String>()
    private val first = mockk<DuplicateMarks>()

    @Test
    fun `the same chapters and marks reuse the last answer`() {
        memo.get(listOf("a", "b"), emptyMap()) { first }

        memo.get(listOf("a", "b"), emptyMap()) { error("ran the rule again") } shouldBeSameInstanceAs first
    }

    @Test
    fun `a changed chapter list runs the rule again`() {
        val second = mockk<DuplicateMarks>()
        memo.get(listOf("a", "b"), emptyMap()) { first }

        memo.get(listOf("a", "c"), emptyMap()) { second } shouldBe second
    }
}
