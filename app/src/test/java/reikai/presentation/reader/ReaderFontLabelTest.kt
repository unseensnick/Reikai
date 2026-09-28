package reikai.presentation.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/** The sheet, the settings screen and the fonts screen name and order the fonts from one place. */
class ReaderFontLabelTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("labels")
    fun `a font is named the same wherever it is shown`(family: String, expected: String) {
        readerFontLabel(family, "Default") shouldBe expected
    }

    @Test
    fun `the source's own font comes first, then the families every device has`() {
        builtInReaderFonts.map { it.family }.take(4) shouldBe listOf("", "sans-serif", "serif", "monospace")
    }

    companion object {
        @JvmStatic
        fun labels() = listOf(
            Arguments.of("", "Default"),
            Arguments.of("serif", "Serif"),
            Arguments.of("sans-serif", "Sans serif"),
            Arguments.of("lora", "Lora"),
            Arguments.of("My_Font.ttf", "My Font"),
        )
    }
}
