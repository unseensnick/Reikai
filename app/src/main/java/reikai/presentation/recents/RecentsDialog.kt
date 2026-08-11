package reikai.presentation.recents

import androidx.compose.runtime.Immutable
import reikai.domain.entry.EntryId
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.domain.category.model.Category

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

    /** Delete the downloaded files of a selection that can span both content types. */
    data class DeleteDownloads(val chapters: Set<ChapterRef>) : RecentsDialog

    /** Ask before adding [entry], which looks like something the library already holds. */
    data class Duplicate(val entry: EntryId, val duplicates: RecentsDuplicates) : RecentsDialog

    /** Where a new add should be filed, when there is no usable default to file it into. */
    data class ChangeCategory(
        val entry: EntryId,
        val initialSelection: List<CheckboxState.State<Category>>,
    ) : RecentsDialog

    /** Migrate [current], a duplicate already in the library, onto the [target] being added. */
    data class Migrate(val current: EntryId, val target: EntryId) : RecentsDialog
}
