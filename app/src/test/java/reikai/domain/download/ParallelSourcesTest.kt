package reikai.domain.download

import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** A restored backup writes the parallel settings unchecked, so both read back within what the downloaders take. */
class ParallelSourcesTest {

    @ParameterizedTest(name = "{0} sources -> {1}")
    @CsvSource("0, 1", "3, 3", "10, 10", "99, 10")
    fun `a stored source limit is held within one and the most offered`(limit: Int, sources: Int) {
        parallelSourcesFor(limit) shouldBe sources
    }

    @ParameterizedTest(name = "{0} pages -> {1}")
    @CsvSource("-4, 1", "6, 6", "15, 15", "40, 15")
    fun `a stored page limit is held within one and the most offered`(limit: Int, pages: Int) {
        parallelPagesFor(limit) shouldBe pages
    }
}
