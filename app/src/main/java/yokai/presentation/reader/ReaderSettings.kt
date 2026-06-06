package yokai.presentation.reader

/**
 * Resolved reader display settings fed to the WebView (the LNReader `ChapterReaderSettings` subset
 * the web layer reads). [followSystemTheme] is carried so the settings sheet can show the "Auto"
 * state; [ReaderScreen] resolves it into the effective [backgroundColor]/[textColor] before render.
 */
data class ReaderSettings(
    val fontSize: Int,
    val lineHeight: Float,
    val textAlign: String,
    val padding: Int,
    val fontFamily: String,
    val followSystemTheme: Boolean,
    val backgroundColor: String,
    val textColor: String,
)

/** A reader background + text color pairing. Tapping one in the settings sheet writes both. */
data class ReaderThemePreset(val name: String, val background: String, val textColor: String)

/** The five presets from LNReader (light, sepia, mint, dark, black). */
val readerThemePresets = listOf(
    ReaderThemePreset("Light", "#f5f5fa", "#111111"),
    ReaderThemePreset("Sepia", "#F7DFC6", "#593100"),
    ReaderThemePreset("Mint", "#dce5e2", "#000000"),
    ReaderThemePreset("Dark", "#292832", "#CCCCCC"),
    ReaderThemePreset("Black", "#000000", "#FFFFFFB3"),
)

/** Presets used by the "Auto" (follow-system) option for light and dark system modes. */
val readerLightPreset = readerThemePresets.first { it.name == "Light" }
val readerDarkPreset = readerThemePresets.first { it.name == "Dark" }
