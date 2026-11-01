package reikai.presentation.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import eu.kanade.presentation.reader.ReaderError
import eu.kanade.presentation.util.formattedMessage
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * The transition page's error and Retry for a neighbour chapter that failed to load. Both states are
 * read, not only collected, so a Retry that starts loading drops the error and a new failure shows it.
 */
@Composable
internal fun NeighbourChapterRetry(
    prevChapter: ReaderChapter?,
    nextChapter: ReaderChapter?,
    onRetry: (ReaderChapter) -> Unit,
) {
    val prevState = prevChapter?.stateFlow?.collectAsState()?.value
    val nextState = nextChapter?.stateFlow?.collectAsState()?.value
    val failed = remember(prevState, nextState) { chapterToRetry(prevChapter, nextChapter) }
    val error = failed?.state as? ReaderChapter.State.Error ?: return
    val context = LocalContext.current
    ReaderError(
        message = stringResource(MR.strings.transition_pages_error, with(context) { error.error.formattedMessage }),
        onRetry = { onRetry(failed) },
        onOpenInWebView = null,
    )
}

/**
 * Holds the tallest height this has been. A WebGPU transition page measures to fit, and when the Retry
 * above clears the page shrinks, which the viewer draws cut off, and stays so until it is rebuilt.
 */
internal fun Modifier.keepTallestHeight(): Modifier = composed {
    val tallest = remember { intArrayOf(0) }
    layout { measurable, constraints ->
        val minHeight = maxOf(constraints.minHeight, tallest[0].coerceAtMost(constraints.maxHeight))
        val placeable = measurable.measure(constraints.copy(minHeight = minHeight))
        tallest[0] = maxOf(tallest[0], placeable.height)
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
}
