package eu.kanade.presentation.theme.colorscheme

import androidx.compose.ui.graphics.Color
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import com.materialkolor.dynamiccolor.ColorSpec

/**
 * The Custom theme: a full scheme generated from the seed color the user picked.
 * From Kuukiyomi via Komikku (Apache 2.0).
 */
internal class CustomColorScheme(
    seed: Color,
    style: PaletteStyle,
) : BaseColorScheme() {
    override val lightScheme =
        dynamicColorScheme(seed, isDark = false, isAmoled = false, style = style, specVersion = SPEC)
    override val darkScheme =
        dynamicColorScheme(seed, isDark = true, isAmoled = false, style = style, specVersion = SPEC)

    private companion object {
        val SPEC = ColorSpec.SpecVersion.SPEC_2025
    }
}
