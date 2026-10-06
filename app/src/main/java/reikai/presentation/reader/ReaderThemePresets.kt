package reikai.presentation.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb

/**
 * A reader background + text colour pairing. Choosing one writes both.
 *
 * Six hex digits, no alpha. Black's text came from LNReader as `#FFFFFFB3`, the flat equivalent over its
 * own background now; an install still storing the old value draws it through [readerColorOrNull],
 * which reads eight digits as CSS does in both renderers.
 */
data class ReaderThemePreset(val name: String, val background: String, val textColor: String) {

    /**
     * Whether the page shows this preset: both stored colours compared as drawn, so a case or `#rgb`
     * difference still matches, and so does Black's old translucent text over its own background.
     */
    fun isPickedBy(followSystem: Boolean, background: String, textColor: String): Boolean {
        if (followSystem) return false
        val page = readerColorOrNull(background) ?: return false
        val text = readerColorOrNull(textColor) ?: return false
        return page == readerColorOrNull(this.background) &&
            Color(text).compositeOver(Color(page)).toArgb() == readerColorOrNull(this.textColor)
    }
}

/** LNReader's five presets, its dark one named Grey as tsundoku names it, plus tsundoku's Dark. */
val readerThemePresets = listOf(
    ReaderThemePreset("Light", "#f5f5fa", "#111111"),
    ReaderThemePreset("Sepia", "#F7DFC6", "#593100"),
    ReaderThemePreset("Mint", "#dce5e2", "#000000"),
    ReaderThemePreset("Grey", "#292832", "#CCCCCC"),
    ReaderThemePreset("Dark", "#121212", "#E0E0E0"),
    ReaderThemePreset("Black", "#000000", "#B3B3B3"),
)

/**
 * Presets the "Auto" (follow-system) option resolves to for light and dark system modes. Dark mode keeps
 * Grey, which it resolved to before Dark was added, so nobody's page changes shade on update.
 */
val readerLightPreset = readerThemePresets.first { it.name == "Light" }
val readerDarkPreset = readerThemePresets.first { it.name == "Grey" }
