package yokai.domain.chapter.services

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
    fun `missingChaptersCount ignores decimal sub-chapters of the same base`() {
        listOf(1.0, 1.0, 1.1, 1.5, 1.6, 1.99).missingChaptersCount() shouldBe 0
    }

    @Test
    fun `missingChaptersCount counts whole missing chapters`() {
        listOf(-1.0, 1.0, 2.0, 2.2, 4.0, 6.0, 10.0, 11.0).missingChaptersCount() shouldBe 5
    }

    @Test
    fun `calculateChapterGap returns whole chapters skipped between two numbers`() {
        calculateChapterGap(10.0, 9.0) shouldBe 0
        calculateChapterGap(10.0, 8.0) shouldBe 1
        calculateChapterGap(10.0, 8.5) shouldBe 1
        calculateChapterGap(10.0, 1.1) shouldBe 8
    }

    @Test
    fun `calculateChapterGap returns 0 when either number is unknown`() {
        calculateChapterGap(-1.0, 10.0) shouldBe 0
        calculateChapterGap(99.0, -1.0) shouldBe 0
    }
}
