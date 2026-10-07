package reikai.presentation.details

import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** What the number dialog accepts: a chapter's number, written with a point or a comma. */
class ChapterNumberDialogTest {

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @CsvSource(
        "'12', 12.0",
        "' 12.5 ', 12.5",
        "'12,5', 12.5",
        "'0', 0.0",
        "'-1',",
        "'',",
        "'twelve',",
        "'Infinity',",
    )
    fun `a typed number is read as a chapter's number or refused`(text: String, expected: Double?) {
        parsedChapterNumber(text) shouldBe expected
    }
}
