package reikai.presentation.novel.reader

/**
 * Resolved reader display settings fed to the WebView (the LNReader `ChapterReaderSettings` subset
 * the web layer reads). [followSystemTheme] is carried so the settings sheet can show the "Auto"
 * state; [NovelReaderScreen] resolves it into the effective [backgroundColor]/[textColor] before render.
 */
data class NovelReaderSettings(
    val fontSize: Int,
    val lineHeight: Float,
    val textAlign: String,
    val padding: Int,
    val fontFamily: String,
    val followSystemTheme: Boolean,
    val backgroundColor: String,
    val textColor: String,
    val keepScreenOn: Boolean,
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

/** A selectable reader font. [family] is empty for the source's original font and otherwise matches
 *  a bundled `assets/fonts/<family>.ttf` (the path the LNReader web layer loads). */
data class ReaderFont(val family: String, val name: String)

/** Bundled fonts from LNReader (Original + 9 families shipped under assets/fonts/). */
val readerFonts = listOf(
    ReaderFont("", "Original"),
    ReaderFont("lora", "Lora"),
    ReaderFont("nunito", "Nunito"),
    ReaderFont("noto-sans", "Noto Sans"),
    ReaderFont("open-sans", "Open Sans"),
    ReaderFont("arbutus-slab", "Arbutus Slab"),
    ReaderFont("domine", "Domine"),
    ReaderFont("lato", "Lato"),
    ReaderFont("pt-serif", "PT Serif"),
    ReaderFont("OpenDyslexic3-Regular", "OpenDyslexic"),
)
