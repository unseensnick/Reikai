package reikai.domain.novel.tts

import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.MR

/** A named colour offered for the read-aloud highlight, stored as packed ARGB. */
data class TtsColorPreset(val argb: Int, val nameRes: StringResource)

/**
 * The colours the settings offer, since the app has no colour picker. The mark colours are light
 * tints so the default dark text stays readable over a background mark.
 */
object TtsHighlightColors {
    const val DEFAULT_HIGHLIGHT = 0xFFFFD54F.toInt()
    const val DEFAULT_TEXT = 0xFF1A1A1A.toInt()

    val highlight = listOf(
        TtsColorPreset(DEFAULT_HIGHLIGHT, MR.strings.tts_color_amber),
        TtsColorPreset(0xFFA5D6A7.toInt(), MR.strings.tts_color_green),
        TtsColorPreset(0xFF90CAF9.toInt(), MR.strings.tts_color_blue),
        TtsColorPreset(0xFFF48FB1.toInt(), MR.strings.tts_color_pink),
        TtsColorPreset(0xFFCE93D8.toInt(), MR.strings.tts_color_purple),
        TtsColorPreset(0xFFBDBDBD.toInt(), MR.strings.tts_color_gray),
    )

    val text = listOf(
        TtsColorPreset(DEFAULT_TEXT, MR.strings.tts_color_dark),
        TtsColorPreset(0xFFFAFAFA.toInt(), MR.strings.tts_color_light),
    )
}
