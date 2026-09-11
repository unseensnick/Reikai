package reikai.presentation.reader.text

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AbstractComposeView
import androidx.core.view.isVisible
import eu.kanade.presentation.reader.TransitionChapter
import eu.kanade.presentation.reader.TransitionText
import eu.kanade.presentation.theme.TachiyomiTheme
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * The marker between two chapters the reader runs straight through, drawn by the same composable the
 * manga viewers use so a seam reads the same in either. Without it a chapter simply becomes the next
 * one mid-scroll, which is what it looked like before.
 *
 * [seam] is what it says, or null to hide it; one with no next chapter is the end-of-novel marker.
 * Fixed at construction, because the viewport swaps in a new view rather than re-binding one.
 */
class NovelChapterSeamView(context: Context, val seam: NovelSeam?) : AbstractComposeView(context) {

    init {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        val density = resources.displayMetrics.density
        val vertical = (PADDING_VERTICAL_DP * density).toInt()
        val horizontal = (PADDING_HORIZONTAL_DP * density).toInt()
        setPadding(horizontal, vertical, horizontal, vertical)
        isVisible = seam != null
    }

    @Composable
    override fun Content() {
        val shown = seam ?: return
        TachiyomiTheme {
            CompositionLocalProvider(
                // ChapterTransition's own style, which the manga viewers draw this with.
                LocalTextStyle provides MaterialTheme.typography.bodyMedium,
                LocalContentColor provides MaterialTheme.colorScheme.onBackground,
            ) {
                // Centred the way the webtoon holder's gravity centres it, on a screen wider than
                // the column's cap.
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TransitionText(
                        topLabel = stringResource(MR.strings.transition_finished),
                        topChapter = TransitionChapter(shown.finishedTitle, subtitle = null),
                        topChapterDownloaded = shown.finishedDownloaded,
                        bottomLabel = stringResource(MR.strings.transition_next),
                        bottomChapter = shown.nextTitle?.let { TransitionChapter(it, subtitle = null) },
                        bottomChapterDownloaded = shown.nextDownloaded,
                        // Drawn in place of the next chapter by the end marker, which has none.
                        fallbackLabel = stringResource(MR.strings.transition_no_next),
                        chapterGap = shown.missingChapters,
                    )
                }
            }
        }
    }

    companion object {
        /** WebtoonTransitionHolder's own padding, so a novel boundary is as tall as a manga one. The
         *  WebView page takes the same two numbers from here (NovelWebDocument). */
        const val PADDING_VERTICAL_DP = 128
        const val PADDING_HORIZONTAL_DP = 32
    }
}
