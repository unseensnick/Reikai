package reikai.presentation.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ReaderTextSizeDialogTest {

    /**
     * M3 works a tick's value out in float, and the tick for 14 lands at 13.999999 on most screen
     * widths. Truncating stored 13, the recomposition snapped the thumb back to that tick, and 14
     * could not be picked at all.
     */
    @Test
    fun `a tick that lands a hair under its value still names that size`() {
        readerTextSizeOf(13.999999f) shouldBe 14
    }

    @Test
    fun `a tick that lands a hair over its value names the same size`() {
        readerTextSizeOf(14.000001f) shouldBe 14
    }

    @Test
    fun `an exact tick names itself`() {
        readerTextSizeOf(12f) shouldBe 12
    }
}
