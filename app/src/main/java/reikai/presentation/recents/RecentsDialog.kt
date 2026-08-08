package reikai.presentation.recents

import androidx.compose.runtime.Immutable
import reikai.domain.entry.EntryId

/**
 * A prompt the recents surface is showing. One slot on the engine rather than one per content type,
 * which is what makes a clear-all under the All chip a single confirmation: the two replaced screens
 * each set their own model's dialog, so the user confirmed twice and got two snackbars.
 */
@Immutable
sealed interface RecentsDialog {
    /** Wipe every read record on the surface, for whichever content types the chip is showing. */
    data object ClearHistory : RecentsDialog

    /** Drop one entry's read records, from a row's own action. */
    data class RemoveHistory(val entry: EntryId) : RecentsDialog

    /** Delete the downloaded files of a selection that can span both content types. */
    data class DeleteDownloads(val chapters: Set<ChapterRef>) : RecentsDialog
}
