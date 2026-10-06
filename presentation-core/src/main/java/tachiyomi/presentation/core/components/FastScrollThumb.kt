package tachiyomi.presentation.core.components

import android.view.ViewConfiguration
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.sample
import kotlin.time.Duration.Companion.seconds

/** The thumb's length along the track, which a scroller subtracts from its height to get the travel. */
val FastScrollThumbLength = 48.dp

/**
 * The fast-scroll thumb every scroller draws: Mihon's two and the library's own. [modifier] carries
 * the caller's offset, drag and gesture-exclusion modifiers, which differ per scroller on purpose.
 * The touch target is wider than the visible 12.dp thumb so it is easy to grab.
 */
@Composable
fun FastScrollThumb(
    alpha: Float,
    color: Color,
    endContentPadding: Dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(FastScrollThumbLength)
            .padding(end = endContentPadding)
            .width(ThumbTouchThickness)
            .alpha(alpha),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Box(
            Modifier
                .height(FastScrollThumbLength)
                .width(ThumbThickness)
                .background(color = color, shape = ThumbShape),
        )
    }
}

/** Shows the thumb on each [scrolled] emission, then fades it out after a pause. */
@Composable
fun rememberFastScrollThumbAlpha(
    scrolled: Flow<Unit>,
    thumbAllowed: () -> Boolean,
): Animatable<Float, AnimationVector1D> {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(scrolled, alpha) {
        scrolled
            .sample(0.1.seconds)
            .collectLatest {
                if (thumbAllowed()) {
                    alpha.snapTo(1f)
                    delay(ScrollBarVisibilityDuration)
                    alpha.animateTo(0f, animationSpec = ImmediateFadeOutAnimationSpec)
                } else {
                    alpha.animateTo(0f, animationSpec = ImmediateFadeOutAnimationSpec)
                }
            }
    }
    return alpha
}

private val ThumbThickness = 12.dp
private val ThumbTouchThickness = 32.dp
private val ThumbShape = RoundedCornerShape(ThumbThickness / 2)
private val ScrollBarVisibilityDuration = 2.seconds
private val ImmediateFadeOutAnimationSpec = tween<Float>(
    durationMillis = ViewConfiguration.getScrollBarFadeDuration(),
)
