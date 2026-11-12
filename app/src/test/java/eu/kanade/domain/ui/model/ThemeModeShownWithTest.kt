package eu.kanade.domain.ui.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/** Doki has no light palette, so the system bars and the reader follow it as dark under any mode setting. */
class ThemeModeShownWithTest {

    @ParameterizedTest
    @EnumSource(ThemeMode::class)
    fun `Doki shows as dark under every mode`(mode: ThemeMode) {
        mode.shownWith(AppTheme.DOKI) shouldBe ThemeMode.DARK
    }

    @ParameterizedTest
    @EnumSource(ThemeMode::class)
    fun `a theme with both palettes keeps the mode the user set`(mode: ThemeMode) {
        mode.shownWith(AppTheme.DEFAULT) shouldBe mode
    }
}
