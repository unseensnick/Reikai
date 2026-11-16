package reikai.domain.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** A stored, restored or reported share is held within a chapter at the one place that says how. */
class ChapterProgressTest {

    @ParameterizedTest(name = "{0} is held at {1}")
    @CsvSource("-5, 0", "0, 0", "5000, 5000", "10000, 10000", "12000, 10000")
    fun `a share from outside is held within a chapter`(hundredths: Long, held: Long) {
        ChapterProgress.Percent.clamped(hundredths) shouldBe ChapterProgress.Percent(held)
    }
}
