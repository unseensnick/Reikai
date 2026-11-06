package reikai.presentation.reader

import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The e-ink flash's length slider, which both readers' sheet and Settings draw. */
class ReaderFlashRangeTest {

    private val stops = ReaderRanges.flashDurationSteps.map { it * ReaderRanges.FLASH_STEP_MILLIS }

    /** A length the earlier 100 ms slider saved must not move when this one opens. */
    @Test
    fun `every length the earlier slider could save is still a stop`() {
        (100..1500 step 100).filterNot { it in stops } shouldBe emptyList()
    }

    @Test
    fun `the default is a stop`() {
        (ReaderPreferences.MILLI_CONVERSION in stops) shouldBe true
    }

    @Test
    fun `the flash can be shorter than the default`() {
        stops.first() shouldBe 20
    }
}
