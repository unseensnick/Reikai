package reikai.presentation.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The hopper over both library views, and the jump-to-category picker it opens. The jump itself
 * stays with the caller, which owns the pager and the single list it scrolls. The long-press
 * values are the ones `hopperLongPressActions` in ReikaiLibrarySettings.kt offers.
 */
@Composable
fun BoxScope.ReikaiLibraryHopperOverlay(
    settings: ReikaiLibraryState,
    buckets: List<LibraryBucket>,
    getItemCount: (LibraryBucket) -> Int?,
    showItemCounts: Boolean,
    isListScrolling: () -> Boolean,
    bottomPadding: Dp,
    currentIndex: () -> Int,
    onJumpBy: (Int) -> Unit,
    onJumpTo: (Int) -> Unit,
    onGravityChange: (Int) -> Unit,
    onSearch: () -> Unit,
    onToggleAllCollapsed: () -> Unit,
    onOpenSettings: (initialTab: Int) -> Unit,
    onOpenRandom: (inCurrentCategory: Boolean) -> Unit,
) {
    var pickerOpen by remember { mutableStateOf(false) }
    var dragAccum by remember { mutableFloatStateOf(0f) }

    if (!settings.hideHopper && buckets.isNotEmpty()) {
        val alignment = when (settings.hopperGravity) {
            0 -> Alignment.BottomStart
            2 -> Alignment.BottomEnd
            else -> Alignment.BottomCenter
        }
        // Autohide reads the single list's scroll only, so in the pager the hopper stays put. A
        // lambda, so a scroll starting or stopping recomposes this overlay rather than the library.
        AnimatedVisibility(
            visible = !settings.autohideHopper || !isListScrolling(),
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(alignment)
                .padding(horizontal = 12.dp)
                .padding(bottom = bottomPadding + 12.dp),
        ) {
            ReikaiCategoryHopper(
                modifier = Modifier
                    // Drag the hopper left/right to move it between start / center / end.
                    .pointerInput(settings.hopperGravity) {
                        val gravity = settings.hopperGravity
                        detectHorizontalDragGestures(
                            onDragStart = { dragAccum = 0f },
                            onDragEnd = {
                                val next = when {
                                    dragAccum > 48f -> (gravity + 1).coerceAtMost(2)
                                    dragAccum < -48f -> (gravity - 1).coerceAtLeast(0)
                                    else -> gravity
                                }
                                if (next != gravity) onGravityChange(next)
                            },
                        ) { change, dragAmount ->
                            change.consume()
                            dragAccum += dragAmount
                        }
                    },
                onUpClick = { onJumpBy(-1) },
                onCenterClick = { pickerOpen = true },
                onCenterLongClick = {
                    when (settings.hopperLongPressAction) {
                        0 -> onSearch()
                        1 -> onToggleAllCollapsed()
                        2 -> onOpenSettings(2)
                        3 -> onOpenSettings(3)
                        4 -> onOpenRandom(true)
                        5 -> onOpenRandom(false)
                    }
                },
                onDownClick = { onJumpBy(1) },
            )
        }
    }

    if (pickerOpen) {
        ReikaiCategoryPickerSheet(
            buckets = buckets,
            getItemCount = getItemCount,
            showItemCounts = showItemCounts,
            activeIndex = currentIndex(),
            onSelect = { index ->
                onJumpTo(index)
                pickerOpen = false
            },
            onDismiss = { pickerOpen = false },
        )
    }
}
