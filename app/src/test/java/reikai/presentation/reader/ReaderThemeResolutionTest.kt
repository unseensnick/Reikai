package reikai.presentation.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The colours the settings sheet shows and the page draws are one resolution, so the two cannot disagree. */
class ReaderThemeResolutionTest {

    @Test
    fun `following the system in dark mode shows the dark preset`() {
        readerThemeShown(followSystem = true, isDark = true, background = "#ffffff", textColor = "#000000") shouldBe
            readerDarkPreset
    }

    @Test
    fun `following the system in light mode shows the light preset`() {
        readerThemeShown(followSystem = true, isDark = false, background = "#000000", textColor = "#ffffff") shouldBe
            readerLightPreset
    }

    @Test
    fun `a manual theme shows the stored colours`() {
        readerThemeShown(followSystem = false, isDark = true, background = "#123456", textColor = "#abcdef") shouldBe
            ReaderThemePreset("", "#123456", "#abcdef")
    }
}
