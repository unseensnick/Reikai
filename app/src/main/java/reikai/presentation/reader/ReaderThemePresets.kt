package reikai.presentation.reader

/**
 * A reader background + text colour pairing. Choosing one writes both.
 *
 * Six hex digits, no alpha: the WebView page reads these as CSS, where eight digits mean `#rrggbbaa`,
 * while the native renderer and the swatch read them through `android.graphics.Color`, where eight
 * mean `#aarrggbb`. Black's text came from LNReader as `#FFFFFFB3` and so drew dimmed white in one
 * renderer and opaque pale yellow in the other; it is the flat equivalent over its own background now.
 */
data class ReaderThemePreset(val name: String, val background: String, val textColor: String)

/** The five presets from LNReader (light, sepia, mint, dark, black). */
val readerThemePresets = listOf(
    ReaderThemePreset("Light", "#f5f5fa", "#111111"),
    ReaderThemePreset("Sepia", "#F7DFC6", "#593100"),
    ReaderThemePreset("Mint", "#dce5e2", "#000000"),
    ReaderThemePreset("Dark", "#292832", "#CCCCCC"),
    ReaderThemePreset("Black", "#000000", "#B3B3B3"),
)

/** Presets the "Auto" (follow-system) option resolves to for light and dark system modes. */
val readerLightPreset = readerThemePresets.first { it.name == "Light" }
val readerDarkPreset = readerThemePresets.first { it.name == "Dark" }
