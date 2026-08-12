package reikai.presentation.recents

import android.content.Context
import android.content.Intent
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.Navigator

/**
 * How a row's tap reaches a reader. The two engines launch differently, so the seam answers what to
 * open and the caller does the opening; a neutral `Screen` cannot carry the manga side, whose reader
 * is an Activity.
 */
sealed interface RecentsOpen {
    data class ReaderIntent(val intent: Intent) : RecentsOpen

    data class ReaderScreen(val screen: Screen) : RecentsOpen
}

/**
 * Opens whatever the seam resolved. One definition because a row's tap and a tab's reselect both land
 * here, and the two cases are not interchangeable: manga reads in an Activity, novels in a screen.
 */
internal suspend fun RecentsOpen?.launch(
    context: Context,
    navigator: Navigator,
    onNothingToOpen: suspend () -> Unit,
) {
    when (this) {
        null -> onNothingToOpen()
        is RecentsOpen.ReaderIntent -> context.startActivity(intent)
        is RecentsOpen.ReaderScreen -> navigator.push(screen)
    }
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
