package reikai.domain.novel.tts

import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.MR

/**
 * How the paragraph being read aloud is marked. Persisted by name through `getEnum`, so the constant
 * names are load-bearing.
 */
enum class TtsHighlightStyle(val titleRes: StringResource) {
    BACKGROUND(MR.strings.tts_highlight_background),
    UNDERLINE(MR.strings.tts_highlight_underline),
    OUTLINE(MR.strings.tts_highlight_outline),
}
