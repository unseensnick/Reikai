package reikai.presentation.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource

class ReaderColorTest {

    /** The WebView page reads a stored colour as CSS, so the native renderer has to read it the same way. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("cssForms")
    fun `a stored colour reads as the page's CSS reads it`(stored: String, argb: Long) {
        readerColorOrNull(stored) shouldBe argb.toInt()
    }

    /** CSS drops these, so the reader falls back rather than drawing a colour the page never shows. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["#12345", "#1234567", "#ggg", "red", "", "#"])
    fun `a value CSS does not read is no colour`(stored: String) {
        readerColorOrNull(stored) shouldBe null
    }

    /** The settings sheet shows these too, so its swatch is the colour the page draws. */
    @Test
    fun `an unreadable stored background draws the dark default`() {
        readerBackgroundColorInt("#12345") shouldBe readerColorOrNull(readerDarkPreset.background)
    }

    @Test
    fun `an unreadable stored text colour draws the dark default`() {
        readerTextColorInt("#12345") shouldBe readerColorOrNull(readerDarkPreset.textColor)
    }

    companion object {
        @JvmStatic
        fun cssForms() = listOf(
            // Black's text as LNReader stored it: eight digits are `#rrggbbaa` to CSS.
            Arguments.of("#FFFFFFB3", 0xB3FFFFFF),
            Arguments.of("#292832", 0xFF292832),
            Arguments.of("#abc", 0xFFAABBCC),
            Arguments.of("#abcd", 0xDDAABBCC),
        )
    }
}
