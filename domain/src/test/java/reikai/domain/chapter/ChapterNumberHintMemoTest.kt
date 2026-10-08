package reikai.domain.chapter

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ChapterNumberHintMemoTest {

    private val first = mapOf(1L to ChapterNumberHint.Hint(2.0))
    private val second = mapOf(9L to ChapterNumberHint.Hint(null))

    @Test
    fun `equal rows and hidden set reuse the last answer`() {
        val memo = ChapterNumberHint.Memo<Int>()
        memo.get(listOf(1, 2), setOf("a")) { first }

        memo.get(listOf(1, 2), setOf("a")) { second } shouldBe first
    }

    @Test
    fun `a changed hidden set computes again`() {
        val memo = ChapterNumberHint.Memo<Int>()
        memo.get(listOf(1, 2), setOf("a")) { first }

        memo.get(listOf(1, 2), setOf("a", "b")) { second } shouldBe second
    }

    @Test
    fun `changed rows compute again`() {
        val memo = ChapterNumberHint.Memo<Int>()
        memo.get(listOf(1, 2), setOf("a")) { first }

        memo.get(listOf(1, 3), setOf("a")) { second } shouldBe second
    }
}
