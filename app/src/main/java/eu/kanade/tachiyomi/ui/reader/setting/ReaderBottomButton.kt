package eu.kanade.tachiyomi.ui.reader.setting

import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.MR

/**
 * Reikai (R-feature): user-selectable reader bottom-bar buttons. Ported from Komikku, trimmed to the
 * buttons backed by existing reader actions. The two-page-spread buttons (page layout, shift double
 * page) are deliberately omitted; that feature is not ported.
 */
enum class ReaderBottomButton(val value: String, val stringRes: StringResource) {
    ViewChapters("vc", MR.strings.action_view_chapters),
    WebView("wb", MR.strings.action_open_in_web_view),
    Browser("br", MR.strings.action_open_in_browser),
    Share("sh", MR.strings.action_share),
    ReadingMode("rm", MR.strings.viewer),
    Rotation("rot", MR.strings.rotation_type),
    CropBorders("cb", MR.strings.pref_crop_borders),
    ;

    fun isIn(buttons: Collection<String>) = value in buttons

    companion object {
        val BUTTONS_DEFAULTS = setOf(
            ViewChapters,
            ReadingMode,
            Rotation,
            CropBorders,
        ).map { it.value }.toSet()
    }
}
