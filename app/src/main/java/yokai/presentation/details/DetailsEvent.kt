package yokai.presentation.details

import java.io.File

/**
 * One-shot screen effects a details ScreenModel can't express through state: transient messages (with
 * an optional undo action) and navigation. Shared by the manga and novel details screens; collected by
 * [HandleDetailsEvents]. The action/dismiss callbacks back the deferred-commit undo pattern (mark-read,
 * merge split/remove).
 */
sealed interface DetailsEvent {
    data class Snackbar(
        val message: String,
        val actionLabel: String? = null,
        val onAction: (() -> Unit)? = null,
        val onDismiss: (() -> Unit)? = null,
    ) : DetailsEvent
    /** Replace this screen with a sibling source (after splitting away the currently-viewed one). */
    data class NavigateToSibling(val id: Long) : DetailsEvent
    /** Hand a saved image file to the screen so it can launch a share chooser (needs a Context). */
    data class ShareImage(val file: File) : DetailsEvent
}
