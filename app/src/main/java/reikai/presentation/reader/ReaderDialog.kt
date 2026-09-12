package reikai.presentation.reader

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable

/**
 * The dialogs the reader engine dispatches over one slot. One slot rather than one flag each, so a
 * loading dialog and a user-opened one stay mutually exclusive the way the host's single state
 * field made them.
 */
@Immutable
sealed interface ReaderDialog {

    /** Raised by the engine while a chapter loads, off the session's own load state. */
    data object Loading : ReaderDialog

    /** A chapter that could not be loaded, offering another attempt. Dismissed with nothing on
     *  screen, it closes the reader rather than leave a blank page with no retry left. */
    data class LoadFailed(val message: String?, val canKeepReading: Boolean) : ReaderDialog

    data object Settings : ReaderDialog

    data object ReadingModeSelect : ReaderDialog

    data object OrientationSelect : ReaderDialog

    data object ChapterList : ReaderDialog

    /**
     * Carries the capability the provider built for the long-pressed page rather than the page
     * itself, which is what keeps a manga type out of this vocabulary. It is also why the actions
     * take no argument: each instance is already bound to its page.
     */
    data class PageActions(val actions: ReaderPageActions) : ReaderDialog

    /** Both carry the typography capability for the same reason: a type that builds none cannot
     *  raise them, so the buttons are absent rather than opening a picker over nothing. */
    data class TextSize(val settings: ReaderTextSettings) : ReaderDialog

    data class ThemeSelect(val settings: ReaderTextSettings) : ReaderDialog
}

/**
 * Acting on one already-identified page. A content type with no page to act on never builds one, so
 * the dialog is unreachable for it rather than shown with dead buttons.
 */
@Stable
interface ReaderPageActions {
    fun save()

    fun share(copyToClipboard: Boolean)

    fun setAsCover()
}
