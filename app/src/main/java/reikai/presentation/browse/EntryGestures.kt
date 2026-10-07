package reikai.presentation.browse

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * What a tap and a long press do on a browse result, on every surface that lists them. Choosing
 * (selecting, or picking a migration target), a tap chooses and a long press previews without a buzz,
 * since the add flow would favourite something the reader is only inspecting. Browsing, a tap opens
 * and a long press adds, with upstream's buzz.
 */
@Stable
class EntryGestures<R> internal constructor(
    val onClick: (R) -> Unit,
    val onLongClick: (R) -> Unit,
    private val isChoosing: () -> Boolean,
) {
    /** Read in composition, so a cell provider follows the surface in and out of choosing. */
    internal val longPressBuzzes: Boolean get() = !isChoosing()
}

/**
 * Gives the result cells in [content] the long-press buzz [gestures] allow. combinedClickable buzzes on
 * every long press by itself, so without this a silent preview while choosing still buzzes once.
 */
@Composable
fun EntryCellHaptics(gestures: EntryGestures<*>, content: @Composable () -> Unit) {
    // One provider either way: branching around content would rebuild the list, and its scroll, on a mode flip.
    val haptics = if (gestures.longPressBuzzes) LocalHapticFeedback.current else NoHaptics
    CompositionLocalProvider(LocalHapticFeedback provides haptics, content = content)
}

private object NoHaptics : HapticFeedback {
    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) = Unit
}

/**
 * [EntryGestures] that stay the same instance across recompositions and read the latest inputs when a
 * gesture lands, so a cell holding them is not redrawn because a lambda around it was rebuilt.
 * A non-null [choose] means the surface is choosing.
 */
@Composable
fun <R> rememberEntryGestures(
    choose: ((R) -> Unit)?,
    open: (R) -> Unit,
    add: (R) -> Unit,
): EntryGestures<R> {
    val haptic = LocalHapticFeedback.current
    val latestChoose by rememberUpdatedState(choose)
    val latestOpen by rememberUpdatedState(open)
    val latestAdd by rememberUpdatedState(add)
    return remember(haptic) {
        EntryGestures(
            onClick = { row ->
                val choose = latestChoose
                if (choose != null) choose(row) else latestOpen(row)
            },
            onLongClick = { row ->
                if (latestChoose != null) {
                    latestOpen(row)
                } else {
                    latestAdd(row)
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }
            },
            isChoosing = { latestChoose != null },
        )
    }
}
