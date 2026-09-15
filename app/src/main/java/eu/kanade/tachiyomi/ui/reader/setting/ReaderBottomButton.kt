package eu.kanade.tachiyomi.ui.reader.setting

import dev.icerock.moko.resources.StringResource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import tachiyomi.core.common.preference.Preference
import tachiyomi.i18n.MR

/**
 * Reikai (R-feature): user-selectable reader bottom-bar buttons, shared by the manga and novel readers.
 * Ported from Komikku, trimmed to the buttons backed by existing reader actions. The two-page-spread
 * buttons (page layout, shift double page) are deliberately omitted; that feature is not ported.
 *
 * [scope] gates which content type may offer a button: [Scope.Manga]-only, [Scope.Novel]-only, or [Scope.Both].
 * [arranged] filters to it for the settings pickers and the bar alike, and is the only guard: the action row
 * draws every button it is handed, so a mis-scoped value reaching it would show.
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
    ReadAloud("ra", MR.strings.pref_category_read_aloud, Scope.Novel),
    ScrollToTop("top", MR.strings.action_scroll_to_top, Scope.Both),
    ;

    enum class Scope { Manga, Novel, Both }

    companion object {
        /** Buttons a given reader is allowed to offer (its own scope plus the shared [Scope.Both]). */
        fun offeredIn(scope: Scope) = entries.filter { it.scope == scope || it.scope == Scope.Both }

        /**
         * The buttons a reader draws: those [selected] that [scope] offers, in the stored [order]. Order and
         * selection are separate preferences so the selection's stored set, and the carries that add to
         * it, stay as they were; a selected button the order does not name follows in declaration order,
         * which is also the order the bar drew before it could be arranged.
         */
        fun ordered(selected: Set<String>, order: List<String>, scope: Scope): List<ReaderBottomButton> =
            arranged(order, scope).filter { it.value in selected }

        /** Every button [scope] offers, in the stored [order], so a button switched off keeps its place. */
        fun arranged(order: List<String>, scope: Scope): List<ReaderBottomButton> {
            val offered = offeredIn(scope)
            val byValue = offered.associateBy { it.value }
            val placed = order.distinct().mapNotNull(byValue::get)
            return placed + (offered - placed.toSet())
        }

        /** [ordered], kept current as either preference changes. Both readers draw their bar through it. */
        fun orderedChanges(
            selected: Preference<Set<String>>,
            order: Preference<List<String>>,
            scope: Scope,
        ): Flow<List<ReaderBottomButton>> =
            combine(selected.changes(), order.changes()) { buttons, arranged -> ordered(buttons, arranged, scope) }

        /** Manga reader defaults. */
        val BUTTONS_DEFAULTS = setOf(
            ViewChapters,
            ReadingMode,
            Rotation,
            CropBorders,
        ).map { it.value }.toSet()

        /**
         * Novel reader defaults (the Settings gear is always shown, so it is not listed here). Text
         * size and theme are on because they are the quickest way to change either while reading.
         */
        val NOVEL_BUTTONS_DEFAULTS = setOf(
            ViewChapters,
            Rotation,
            TextSize,
            Theme,
            ReadAloud,
        ).map { it.value }.toSet()
    }
}
