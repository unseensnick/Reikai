package reikai.presentation.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import reikai.domain.reader.ChapterTitleFormat

class ChapterTitleTest {

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        delimiter = '|',
        value = [
            "the name format shows the chapter's own name | NAME | The Duel | 12.0 | The Duel",
            // A whole number reads as one, not as 12.0.
            "the number format shows a whole number without a decimal | NUMBER | The Duel | 12.0 | Chapter 12",
            "a part chapter keeps its decimal | NUMBER | The Duel | 12.5 | Chapter 12.5",
            "a chapter the source did not number keeps its name | NUMBER | Prologue | -1.0 | Prologue",
            "the number and name format shows both | NUMBER_AND_NAME | The Duel | 12.0 | Ch. 12: The Duel",
            // Most sources put the number in the name, and showing it twice is what the format must not do.
            "a name that opens with its number is not numbered twice | NUMBER_AND_NAME | Chapter 12: The Duel | 12.0 | Ch. 12: The Duel",
            "a name that repeats its number is numbered once | NUMBER_AND_NAME | Chapter 3 3: Primordial Chaos | 3.0 | Ch. 3: Primordial Chaos",
            "a name that is only its number reads as the number | NUMBER_AND_NAME | Chapter 12 | 12.0 | Chapter 12",
            "a number closed by a full stop comes off too | NUMBER_AND_NAME | Chapter 7. The Road | 7.0 | Ch. 7: The Road",
            // A part chapter's number is not the whole chapter's, so 12.5 does not lose a leading 12.
            "a part number does not strip the whole chapter's number | NUMBER_AND_NAME | Chapter 12.5: Interlude | 12.0 | Ch. 12: Chapter 12.5: Interlude",
            "a name that opens with a volume before its number is shown as it is | NUMBER_AND_NAME | Vol.1 Ch.3 - The Duel | 3.0 | Vol.1 Ch.3 - The Duel",
            // "Vol.13" is a volume, so a chapter 3 whose name opens with it still gets its number.
            "a volume number does not read as the chapter number | NUMBER_AND_NAME | Vol.13 The Duel | 3.0 | Ch. 3: Vol.13 The Duel",
            "a spelled out volume number does not read as the chapter number | NUMBER_AND_NAME | Volume 12: Side Story | 2.0 | Ch. 2: Volume 12: Side Story",
            "a part volume number does not read as the chapter number | NUMBER_AND_NAME | Vol.2.5 Extra | 5.0 | Ch. 5: Vol.2.5 Extra",
            "a part chapter's number comes off its own name | NUMBER_AND_NAME | Chapter 1.5: Interlude | 1.5 | Ch. 1.5: Interlude",
            // The dot in a part number is a dot, so chapter 1.5 keeps a name that opens with 125.
            "a part number matches only itself | NUMBER_AND_NAME | 125 Days | 1.5 | Ch. 1.5: 125 Days",
            // Only the number being shown comes off, so chapter 1 keeps a name that opens with 12.
            "a name that opens with another number keeps it | NUMBER_AND_NAME | 12 Days Later | 1.0 | Ch. 1: 12 Days Later",
            // Once "Chapter 3:" comes off, the 3 that opens "3 Days Later" is the title's own, not a repeat.
            "a title that opens with the chapter's own number keeps it | NUMBER_AND_NAME | Chapter 3: 3 Days Later | 3.0 | Ch. 3: 3 Days Later",
            "a title after a dash that opens with the chapter's number keeps it | NUMBER_AND_NAME | Chapter 1 - 1 Year Later | 1.0 | Ch. 1: 1 Year Later",
            // With no label word, a separator is what says the number is the chapter's.
            "a bare number closed by a separator comes off | NUMBER_AND_NAME | 3. The Duel | 3.0 | Ch. 3: The Duel",
        ],
    )
    fun `a chapter title shows its number at most once`(
        name: String,
        format: ChapterTitleFormat,
        chapterName: String,
        number: Double,
        expected: String,
    ) {
        format.chapterTitle(chapterName, number, EnglishChapterTitleWords) shouldBe expected
    }
}
