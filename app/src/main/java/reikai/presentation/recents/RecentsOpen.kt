package reikai.presentation.recents

import android.content.Context
import android.content.Intent

/**
 * Opens the reader a provider resolved. One definition because a row's tap and a tab's reselect both
 * land here, and both say so when there is nothing left to open.
 */
internal suspend fun Intent?.launch(
    context: Context,
    onNothingToOpen: suspend () -> Unit,
) {
    if (this == null) onNothingToOpen() else context.startActivity(this)
}

/**
 * Whether a launch confines the reader to the row's own source. Only the updated lane resolves its
 * target there; the other two resolve over the merge group, where the target can belong to a sibling
 * source. Asking for source scope on one of those opens a reader whose chapter list cannot contain
 * the chapter: manga throws, novels lose prev/next silently. Keep this paired with how each lane
 * resolves its target in the two adapters.
 */
val RecentsLane.sourceScoped: Boolean
    get() = this is RecentsLane.Updated
