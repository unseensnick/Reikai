package reikai.presentation.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.graphics.ExperimentalAnimationGraphicsApi
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.runtime.Composable
import java.util.concurrent.ConcurrentHashMap

private val parsed = ConcurrentHashMap<Int, AnimatedImageVector>()

/**
 * [AnimatedImageVector.Companion.animatedVectorResource], parsed once per drawable while the app runs. The
 * library's own cache is a `remember`, which goes with the screen, so each return to the tabs parsed every
 * bar icon again. Safe because these drawables read no theme attribute; one that does must not come here.
 */
@OptIn(ExperimentalAnimationGraphicsApi::class)
@Composable
fun cachedAnimatedVector(@DrawableRes id: Int): AnimatedImageVector =
    parsed[id] ?: AnimatedImageVector.animatedVectorResource(id).also { parsed[id] = it }
