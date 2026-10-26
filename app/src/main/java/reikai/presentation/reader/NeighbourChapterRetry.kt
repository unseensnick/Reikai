package reikai.presentation.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import eu.kanade.presentation.util.formattedMessage
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.ReaderError
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
