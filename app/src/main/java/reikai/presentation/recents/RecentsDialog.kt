package reikai.presentation.recents

import androidx.compose.runtime.Immutable
import reikai.domain.merge.MergeScope

/**
 * A prompt the recents surface is showing. One slot on the engine rather than one per content type,
 * which is what makes a clear-all under the All chip a single confirmation: the two replaced screens
 * each set their own model's dialog, so the user confirmed twice and got two snackbars.
 */
@Immutable
sealed interface RecentsDialog {
    /** Wipe every read record on the surface, for whichever content types the chip is showing. */
    data object ClearHistory : RecentsDialog

    /**
     * Drop read records for the row [item] stands for. Carries the item, not just its entry, because
     * the prompt offers both this one record and every record the entry has, and only the item can
     * name the first.
     */
    data class RemoveHistory(val item: RecentsItem) : RecentsDialog

    /** Delete the downloaded files of a selection that can span both content types, each row's chapters
     *  under the merge scope of the lane it was shown in. */
    data class DeleteDownloads(val chapters: Map<MergeScope, Set<ChapterRef>>) : RecentsDialog
}
