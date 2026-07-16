package reikai.novel.host

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Cleanup of plugin-returned text. Covers entity decoding for display / folder names and the removal
 * of characters that would corrupt a file name or the saved chapter, while the legal whitespace and
 * ordinary text are preserved.
 */
class NovelTextSanitizerTest {

    @Test
    fun `decodeEntities turns an escaped ampersand back into text`() {
        NovelTextSanitizer.decodeEntities("Tom &amp; Jerry") shouldBe "Tom & Jerry"
    }

    @Test
    fun `decodeEntities resolves a numeric entity`() {
        NovelTextSanitizer.decodeEntities("It&#39;s here") shouldBe "It's here"
    }

    @Test
    fun `stripInvalidChars drops control codes`() {
        val input = "a" + Char(0x00) + "b" + Char(0x0B) + "c"
        NovelTextSanitizer.stripInvalidChars(input) shouldBe "abc"
    }

    @Test
    fun `stripInvalidChars keeps tab and newline`() {
        val input = "a" + Char(0x09) + "b" + Char(0x0A) + "c"
        NovelTextSanitizer.stripInvalidChars(input) shouldBe input
    }

    @Test
    fun `ordinary text passes through unchanged`() {
        NovelTextSanitizer.decodeEntities("Chapter 12: The Long Road") shouldBe "Chapter 12: The Long Road"
    }
}
