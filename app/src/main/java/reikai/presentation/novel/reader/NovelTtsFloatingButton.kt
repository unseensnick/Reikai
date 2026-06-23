package reikai.presentation.novel.reader

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

private val PuckSize = 44.dp
private val EdgeMargin = 12.dp

/**
 * Floating read-aloud control for the novel reader: a small puck you can drop anywhere (finer than
 * the library hopper's three snap points). Tap toggles play/pause, long-press stops. Position is
 * free-dragged, clamped to the reader bounds, and persisted in px via [onMoved].
 *
 * Shown only when TTS is enabled, and it stays put during immersive reading (its whole point is to
 * control playback without summoning the full chrome).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NovelTtsFloatingButton(
    playing: Boolean,
    initialX: Int,
    initialY: Int,
    onToggle: () -> Unit,
    onStop: () -> Unit,
    onMoved: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxSize()) {
        val maxX = with(density) { (maxWidth - PuckSize).toPx() }.coerceAtLeast(0f)
        val maxY = with(density) { (maxHeight - PuckSize).toPx() }.coerceAtLeast(0f)
        val margin = with(density) { EdgeMargin.toPx() }

        // Unset (Int.MIN_VALUE) anchors to the right edge, vertically centered; else restore + clamp.
        var offsetX by remember {
            mutableFloatStateOf(if (initialX == Int.MIN_VALUE) (maxX - margin).coerceAtLeast(0f) else initialX.toFloat().coerceIn(0f, maxX))
        }
        var offsetY by remember {
            mutableFloatStateOf(if (initialY == Int.MIN_VALUE) maxY / 2f else initialY.toFloat().coerceIn(0f, maxY))
        }

        // Fade while playing so the text behind stays readable; full when idle so it's easy to find.
        val alpha by animateFloatAsState(if (playing) 0.4f else 0.9f, label = "ttsPuckAlpha")

        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            tonalElevation = 4.dp,
            shadowElevation = 4.dp,
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                .size(PuckSize)
                .alpha(alpha)
                .pointerInput(maxX, maxY) {
                    detectDragGestures(
                        onDragEnd = { onMoved(offsetX.roundToInt(), offsetY.roundToInt()) },
                    ) { change, drag ->
                        change.consume()
                        offsetX = (offsetX + drag.x).coerceIn(0f, maxX)
                        offsetY = (offsetY + drag.y).coerceIn(0f, maxY)
                    }
                }
                .clip(CircleShape)
                .combinedClickable(onClick = onToggle, onLongClick = onStop),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (playing) "Pause read-aloud" else "Read aloud",
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}
