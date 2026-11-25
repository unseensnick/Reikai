package reikai.domain.chapter

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** The memo both details screens' chapter number hints and duplicate marks keep, by its two inputs. */
class LastAnswerTest {

    private val memo = LastAnswer<List<Int>, Set<String>, String>()

    @Test
    fun `equal inputs reuse the last answer without computing`() {
        memo.get(listOf(1, 2), setOf("a")) { "first" }

        memo.get(listOf(1, 2), setOf("a")) { error("computed again") } shouldBe "first"
    }

    @ParameterizedTest(name = "rows {0}, set {1}")
    @CsvSource("3, a", "2, b")
    fun `a change in either input computes again`(lastRow: Int, member: String) {
        memo.get(listOf(1, 2), setOf("a")) { "first" }

        memo.get(listOf(1, lastRow), setOf(member)) { "second" } shouldBe "second"
    }
}
