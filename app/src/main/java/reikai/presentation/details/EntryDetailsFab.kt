package reikai.presentation.details

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.FabPosition
import androidx.compose.material3.Icon
import androidx.compose.material3.SmallExtendedFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.animateFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.roundedfilled.PlayArrow
import reikai.domain.details.ReadButtonSide
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.shouldExpandFAB
import kotlin.math.roundToInt

/** The resume/start FAB both details shells (phone and two-pane) put in their Scaffold. It collapses to
 *  its icon while [chapterListState] scrolls, and dragged sideways it docks to the nearer half's edge. */
@Composable
internal fun EntryDetailsFab(
    chapterListState: LazyListState,
    visible: Boolean,
    isResume: Boolean,
    onClick: () -> Unit,
    onDocked: (ReadButtonSide) -> Unit,
) {
    var dragX by remember { mutableFloatStateOf(0f) }
    var centerX by remember { mutableFloatStateOf(0f) }
    var rootWidth by remember { mutableIntStateOf(0) }
    val currentOnDocked by rememberUpdatedState(onDocked)
    SmallExtendedFloatingActionButton(
        text = {
            Text(text = stringResource(if (isResume) MR.strings.action_resume else MR.strings.action_start))
        },
        icon = { Icon(imageVector = MaterialSymbols.RoundedFilled.PlayArrow, contentDescription = null) },
        onClick = onClick,
        expanded = chapterListState.shouldExpandFAB(),
        modifier = Modifier
            .animateFloatingActionButton(visible = visible, alignment = Alignment.BottomEnd)
            // Absolute, since the drag is in screen pixels and offset() mirrors in a right-to-left layout.
            .absoluteOffset { IntOffset(dragX.roundToInt(), 0) }
            .onGloballyPositioned {
                centerX = it.positionInRoot().x + it.size.width / 2f
                rootWidth = it.findRootCoordinates().size.width
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        currentOnDocked(dockedSide(centerX, rootWidth))
                        dragX = 0f
                    },
                    onDragCancel = { dragX = 0f },
                ) { change, amount ->
                    change.consume()
                    dragX += amount
                }
            },
    )
}

/** The side a button let go with its centre at [centerX] docks to, on a window [width] wide. */
internal fun dockedSide(centerX: Float, width: Int): ReadButtonSide =
    if (centerX < width / 2f) ReadButtonSide.LEFT else ReadButtonSide.RIGHT

/** The scaffold slot for this screen edge: Start is the right edge in a right-to-left layout. */
@Composable
internal fun ReadButtonSide.fabPosition(): FabPosition {
    val leftToRight = LocalLayoutDirection.current == LayoutDirection.Ltr
    return if ((this == ReadButtonSide.LEFT) == leftToRight) FabPosition.Start else FabPosition.End
}
