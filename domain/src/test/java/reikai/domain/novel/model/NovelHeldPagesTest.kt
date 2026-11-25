package reikai.domain.novel.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** A page count a source or a backup sends, as it is stored. */
class NovelHeldPagesTest {

    @ParameterizedTest
    @CsvSource("-1, 0", "5, 5", "100001, 100000")
    fun `a page count is held between none and the most pages`(sent: Long, stored: Long) {
        Novel.heldPages(sent) shouldBe stored
    }
}
