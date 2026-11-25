package reikai.presentation.components

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.util.Locale

/** What the settings sheet accepts when a value or a colour is typed rather than stepped or slid. */
class ReaderSettingsInputTest {

    @Test
    fun `a colour is written as six hex digits whatever its alpha`() {
        0x80123ABC.toInt().toHexRgb() shouldBe "#123ABC"
    }

    @Test
    fun `typed hex with a hash reads as the opaque colour`() {
        parseHexRgb("#123abc") shouldBe 0xFF123ABC.toInt()
    }

    @Test
    fun `typed hex without a hash reads the same`() {
        parseHexRgb("123ABC") shouldBe 0xFF123ABC.toInt()
    }

    /** A sign is not a digit, though Kotlin's own radix parse would take it. */
    @ParameterizedTest
    @ValueSource(strings = ["12345", "1234567", "-12345", "12G45A", "#FFFFFFB3", ""])
    fun `anything but six hex digits is not a colour`(text: String) {
        parseHexRgb(text) shouldBe null
    }

    @Test
    fun `a decimal typed for a tenths setting is stored in tenths`() {
        parseStepperInput("1.5", scale = 10, range = 8..50) shouldBe 15
    }

    @Test
    fun `a whole number typed for a tenths setting means that many`() {
        parseStepperInput("2", scale = 10, range = 8..50) shouldBe 20
    }

    @Test
    fun `a typed value outside the range is refused`() {
        parseStepperInput("0.5", scale = 10, range = 8..50) shouldBe null
    }

    /** A comma-decimal keyboard types the value the row shows, which is formatted for the locale. */
    @ParameterizedTest
    @CsvSource(value = ["'0,5', 0, 100, 5", "'1,5', 8, 50, 15"])
    fun `a comma decimal typed for a tenths setting keeps its decimal`(
        text: String,
        first: Int,
        last: Int,
        tenths: Int,
    ) {
        parseStepperInput(stepperInputText(text, scale = 10), scale = 10, range = first..last) shouldBe tenths
    }

    @Test
    fun `a whole-number stepper keeps digits only`() {
        stepperInputText("1,5.", scale = 1) shouldBe "15"
    }

    /** The dialog's title and starting text match the row, which writes the locale's decimal mark. */
    @Test
    fun `a tenths value is written with the locale's decimal mark`() {
        val before = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
        try {
            unscaled(15, scale = 10) shouldBe "1,5"
        } finally {
            Locale.setDefault(before)
        }
    }
}
