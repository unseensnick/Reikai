package reikai.domain.novel

import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.MR

/**
 * How a novel chapter's text lines up. Stored as [value], the strings the preference has always held,
 * which is also the CSS keyword the web document writes; anything else under the key (a hand-edited
 * backup) reads as [LEFT], so nothing but these four reaches the page's style block.
 */
enum class NovelTextAlign(val value: String, val titleRes: StringResource) {
    LEFT("left", MR.strings.pref_novel_text_align_left),
    CENTER("center", MR.strings.pref_novel_text_align_center),
    JUSTIFY("justify", MR.strings.pref_novel_text_align_justify),
    RIGHT("right", MR.strings.pref_novel_text_align_right),
    ;

    companion object {
        fun of(stored: String): NovelTextAlign = entries.find { it.value == stored } ?: LEFT
    }
}
