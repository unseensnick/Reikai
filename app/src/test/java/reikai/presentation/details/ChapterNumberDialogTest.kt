package reikai.presentation.details

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import reikai.domain.chapter.ChapterNumberEdit
import reikai.domain.library.ContentType

/** What the number dialog accepts, a chapter's number written with a point or a comma, and what it opens on. */
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

    @Test
    fun `a chapter its hint marks opens on the suggestion`() {
        edit(number = 1335.0, suggestion = 1135.0).startingText() shouldBe "1135"
    }

    @Test
    fun `a chapter with no suggestion opens on its own number`() {
        edit(number = 497.0, suggestion = null).startingText() shouldBe "497"
    }

    private fun edit(number: Double, suggestion: Double?) =
        ChapterNumberEdit(ContentType.NOVELS, 1L, "/c", "Chapter", number, sourceNumber = null, suggestion = suggestion)
}
