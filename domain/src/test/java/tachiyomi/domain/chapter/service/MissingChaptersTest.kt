package tachiyomi.domain.chapter.service

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Execution(ExecutionMode.CONCURRENT)
class MissingChaptersTest {

    @Test
    fun `missingChaptersCount returns 0 when empty list`() {
        emptyList<Double>().missingChaptersCount() shouldBe 0
    }

    @Test
    fun `missingChaptersCount returns 0 when all unknown chapter numbers`() {
        listOf(-1.0, -1.0, -1.0).missingChaptersCount() shouldBe 0
    }

    @Test
    fun `missingChaptersCount handles repeated base chapter numbers`() {
        listOf(1.0, 1.0, 1.1, 1.5, 1.6, 1.99).missingChaptersCount() shouldBe 0
    }

    @Test
    fun `missingChaptersCount returns number of missing chapters`() {
        listOf(-1.0, 1.0, 2.0, 2.2, 4.0, 6.0, 10.0, 11.0).missingChaptersCount() shouldBe 5
    }
}
