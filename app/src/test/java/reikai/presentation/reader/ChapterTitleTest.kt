package reikai.presentation.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.domain.reader.ChapterTitleFormat

class ChapterTitleTest {

    private fun ChapterTitleFormat.of(name: String, number: Double) =
        chapterTitle(name, number, EnglishChapterTitleWords)

    @Test
    fun `the name format shows the chapter's own name`() {
        ChapterTitleFormat.NAME.of("The Duel", 12.0) shouldBe "The Duel"
    }

    /** A whole number reads as one, not as 12.0. */
    @Test
    fun `the number format shows a whole number without a decimal`() {
        ChapterTitleFormat.NUMBER.of("The Duel", 12.0) shouldBe "Chapter 12"
    }

    @Test
    fun `a part chapter keeps its decimal`() {
        ChapterTitleFormat.NUMBER.of("The Duel", 12.5) shouldBe "Chapter 12.5"
    }

    @Test
    fun `the number and name format shows both`() {
        ChapterTitleFormat.NUMBER_AND_NAME.of("The Duel", 12.0) shouldBe "Ch. 12: The Duel"
    }

    /** Most sources put the number in the name, and showing it twice is the thing the format must not do. */
    @Test
    fun `a name that opens with its number is not numbered twice`() {
        ChapterTitleFormat.NUMBER_AND_NAME.of("Chapter 12: The Duel", 12.0) shouldBe "Ch. 12: The Duel"
    }

    @Test
    fun `a name that repeats its number is numbered once`() {
        ChapterTitleFormat.NUMBER_AND_NAME.of("Chapter 3 3: Primordial Chaos", 3.0) shouldBe
            "Ch. 3: Primordial Chaos"
    }

    @Test
    fun `a name that is only its number reads as the number`() {
        ChapterTitleFormat.NUMBER_AND_NAME.of("Chapter 12", 12.0) shouldBe "Chapter 12"
    }

    @Test
    fun `a number closed by a full stop comes off too`() {
        ChapterTitleFormat.NUMBER_AND_NAME.of("Chapter 7. The Road", 7.0) shouldBe "Ch. 7: The Road"
    }

    /** A part chapter's number is not the whole chapter's, so 12.5 does not lose a leading 12. */
    @Test
    fun `a part number does not strip the whole chapter's number`() {
        ChapterTitleFormat.NUMBER_AND_NAME.of("Chapter 12.5: Interlude", 12.0) shouldBe
            "Ch. 12: Chapter 12.5: Interlude"
    }

    @Test
    fun `a name that opens with a volume before its number is shown as it is`() {
        ChapterTitleFormat.NUMBER_AND_NAME.of("Vol.1 Ch.3 - The Duel", 3.0) shouldBe "Vol.1 Ch.3 - The Duel"
    }

    @Test
    fun `a part chapter's number comes off its own name`() {
        ChapterTitleFormat.NUMBER_AND_NAME.of("Chapter 1.5: Interlude", 1.5) shouldBe "Ch. 1.5: Interlude"
    }

    /** The dot in a part number is a dot, so chapter 1.5 keeps a name that opens with 125. */
    @Test
    fun `a part number matches only itself`() {
        ChapterTitleFormat.NUMBER_AND_NAME.of("125 Days", 1.5) shouldBe "Ch. 1.5: 125 Days"
    }

    /** Only the number being shown comes off, so chapter 1 keeps a name that opens with 12. */
    @Test
    fun `a name that opens with another number keeps it`() {
        ChapterTitleFormat.NUMBER_AND_NAME.of("12 Days Later", 1.0) shouldBe "Ch. 1: 12 Days Later"
    }

    @Test
    fun `a chapter the source did not number keeps its name`() {
        ChapterTitleFormat.NUMBER.of("Prologue", -1.0) shouldBe "Prologue"
    }
}
