package reikai.presentation.reader

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

/*
 * Shared reader chrome primitives (scrim color + top/bottom bar show-hide animation) used by both the
 * manga reader ([eu.kanade.presentation.reader.appbars.ReaderAppBars]) and the novel reader, so the two
 * readers' bars tint and animate identically instead of drifting. The navigator arrangement (the manga
 * page-index slider vs the novel scroll-percent rail) stays per-type and is not covered here.
 */

/** Slide timing for the bars. */
val ReaderBarsSlideSpec = tween<IntOffset>(200)

/** Fade timing for the bars. */
val ReaderBarsFadeSpec = tween<Float>(150)

/** Translucent toolbar / scrim color behind both readers' bars. */
@Composable
fun readerChromeColor(): Color = MaterialTheme.colorScheme
    .surfaceColorAtElevation(3.dp)
    .copy(alpha = if (isSystemInDarkTheme()) 0.9f else 0.95f)

/** Enter transition for a top ([fromBottom] = false) or bottom ([fromBottom] = true) reader bar. */
fun readerBarEnter(fromBottom: Boolean): EnterTransition =
    slideInVertically(ReaderBarsSlideSpec) { if (fromBottom) it else -it } + fadeIn(ReaderBarsFadeSpec)

/** Exit transition mirroring [readerBarEnter]. */
fun readerBarExit(fromBottom: Boolean): ExitTransition =
    slideOutVertically(ReaderBarsSlideSpec) { if (fromBottom) it else -it } + fadeOut(ReaderBarsFadeSpec)
