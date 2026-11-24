package reikai.novel.host

import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

/**
 * Cleanup of plugin-returned text. Covers entity decoding for display / folder names and the removal
 * of characters that would corrupt a file name or the saved chapter, while the legal whitespace and
 * ordinary text are preserved.
 */
class NovelTextSanitizerTest {

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        quoteCharacter = '"',
        value = [
            "Tom &amp; Jerry|Tom & Jerry",
            "It&#39;s here|It's here",
            "Chapter 12: The Long Road|Chapter 12: The Long Road",
        ],
    )
    fun `decodeEntities turns escaped text back into text`(input: String, decoded: String) {
        NovelTextSanitizer.decodeEntities(input) shouldBe decoded
    }

    @ParameterizedTest
    @ValueSource(ints = [0x00, 0x0B, 0x1F, 0xFFFE, 0xFFFF])
    fun `stripInvalidChars drops control codes and the two non-characters`(code: Int) {
        NovelTextSanitizer.stripInvalidChars("a" + Char(code) + "b") shouldBe "ab"
    }

    // A plain-text chapter with carriage-return-only line breaks keeps them.
    @ParameterizedTest
    @ValueSource(ints = [0x09, 0x0A, 0x0D])
    fun `stripInvalidChars keeps the legal whitespace`(code: Int) {
        val input = "a" + Char(code) + "b"
        NovelTextSanitizer.stripInvalidChars(input) shouldBe input
    }
}
