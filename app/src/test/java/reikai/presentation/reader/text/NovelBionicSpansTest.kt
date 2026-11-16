package reikai.presentation.reader.text

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class NovelBionicSpansTest {

    @ParameterizedTest(name = "a {0}-letter word bolds {1}")
    @DisplayName("the bold run follows text-vide's fixation table")
    @CsvSource(
        "1, 0",
        "2, 1",
        "4, 3",
        "5, 3",
        "12, 10",
        "13, 10",
        "17, 14",
        "18, 14",
        "49, 40",
    )
    fun boldLengthFollowsTheFixationTable(wordLength: Int, expected: Int) {
        NovelBionicSpans.boldLengthFor(wordLength) shouldBe expected
    }

    @Test
    @DisplayName("a word is a run of letters and digits holding a letter")
    fun wordsHoldALetter() {
        NovelBionicSpans.words("Room 101b, 2024 x") shouldBe listOf(0..3, 5..8, 16..16)
    }

    /** Chapter text is a source's, and the pattern this replaced overflowed or ran quadratically on this. */
    @Test
    @DisplayName("a long run of digits is no word and is passed over")
    fun longDigitRunIsNoWord() {
        NovelBionicSpans.words("1".repeat(50_000)).shouldBeEmpty()
    }
}
