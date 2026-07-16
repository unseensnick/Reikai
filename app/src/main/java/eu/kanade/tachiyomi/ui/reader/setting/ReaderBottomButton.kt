package eu.kanade.tachiyomi.ui.reader.setting

import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.MR

/**
 * Reikai (R-feature): user-selectable reader bottom-bar buttons, shared by the manga and novel readers.
 * Ported from Komikku, trimmed to the buttons backed by existing reader actions. The two-page-spread
 * buttons (page layout, shift double page) are deliberately omitted; that feature is not ported.
 *
 * [scope] gates which reader may offer a button: [Scope.Manga]-only, [Scope.Novel]-only, or [Scope.Both].
 * The manga and novel settings pickers each filter to their scope, and the shared action row renders a
 * button only when the caller supplies its callback, so a mis-scoped value is inert either way.
 */
enum class ReaderBottomButton(val value: String, val stringRes: StringResource, val scope: Scope) {
    ViewChapters("vc", MR.strings.action_view_chapters, Scope.Both),
    WebView("wb", MR.strings.action_open_in_web_view, Scope.Both),
    Browser("br", MR.strings.action_open_in_browser, Scope.Both),
    Share("sh", MR.strings.action_share, Scope.Both),
    ReadingMode("rm", MR.strings.viewer, Scope.Manga),
    Rotation("rot", MR.strings.rotation_type, Scope.Both),
    CropBorders("cb", MR.strings.pref_crop_borders, Scope.Manga),
    Autoscroll("as", MR.strings.pref_auto_scroll, Scope.Novel),
    KeepScreenOn("ks", MR.strings.pref_keep_screen_on, Scope.Novel),
    BionicReading("bi", MR.strings.pref_bionic_reading, Scope.Novel),
    Theme("th", MR.strings.pref_category_theme, Scope.Novel),
    TextSize("ts", MR.strings.pref_reader_text_size, Scope.Novel),
    ;

    enum class Scope { Manga, Novel, Both }

    fun isIn(buttons: Collection<String>) = value in buttons

    companion object {
        /** Buttons a given reader is allowed to offer (its own scope plus the shared [Scope.Both]). */
        fun offeredIn(scope: Scope) = entries.filter { it.scope == scope || it.scope == Scope.Both }

        /** Manga reader defaults. */
        val BUTTONS_DEFAULTS = setOf(
            ViewChapters,
            ReadingMode,
            Rotation,
            CropBorders,
        ).map { it.value }.toSet()

        /** Novel reader defaults (the Settings gear is always shown, so it is not listed here). */
        val NOVEL_BUTTONS_DEFAULTS = setOf(
            ViewChapters,
            Rotation,
        ).map { it.value }.toSet()
    }
}
