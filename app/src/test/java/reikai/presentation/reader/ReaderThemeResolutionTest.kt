package reikai.presentation.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.domain.novel.NovelPreferences
import tachiyomi.core.common.preference.InMemoryPreferenceStore

/** The colours the settings sheet shows and the page draws are one resolution, so the two cannot disagree. */
class ReaderThemeResolutionTest {

    private val sepia = readerThemePresets.first { it.name == "Sepia" }

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

    @Test
    fun `a hand-picked text colour over a preset's background picks no swatch`() {
        sepia.isPickedBy(followSystem = false, background = sepia.background, textColor = "#123456") shouldBe false
    }

    @Test
    fun `a preset's colours in another case pick that swatch`() {
        sepia.isPickedBy(
            followSystem = false,
            background = sepia.background.lowercase(),
            textColor = sepia.textColor.lowercase(),
        ) shouldBe true
    }

    @Test
    fun `Black's old translucent text over its own background still picks Black`() {
        readerThemePresets.first { it.name == "Black" }
            .isPickedBy(followSystem = false, background = "#000000", textColor = "#FFFFFFB3") shouldBe true
    }

    @Test
    fun `following the system picks no swatch`() {
        readerDarkPreset.isPickedBy(
            followSystem = true,
            background = readerDarkPreset.background,
            textColor = readerDarkPreset.textColor,
        ) shouldBe false
    }

    @Test
    fun `the novel page colours default to the dark preset`() {
        val preferences = NovelPreferences(InMemoryPreferenceStore())
        ReaderThemePreset(
            "Grey",
            preferences.readerBackgroundColor().defaultValue(),
            preferences.readerTextColor().defaultValue(),
        ) shouldBe readerDarkPreset
    }
}
