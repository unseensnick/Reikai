package reikai.presentation.reader.text

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AbstractComposeView
import eu.kanade.presentation.reader.TransitionChapter
import eu.kanade.presentation.reader.TransitionText
import eu.kanade.presentation.theme.TachiyomiTheme
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * The marker between two chapters the reader runs straight through, drawn by the same composable the
 * manga viewers use so a seam reads the same in either. Without it a chapter simply becomes the next
 * one mid-scroll, which is what it looked like before.
 */
class NovelChapterSeamView(context: Context) : AbstractComposeView(context) {

    private var seam: Seam? by mutableStateOf(null)

    init {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        // WebtoonTransitionHolder's own padding, so a novel boundary is as tall as a manga one.
        val density = resources.displayMetrics.density
        setPadding((32 * density).toInt(), (128 * density).toInt(), (32 * density).toInt(), (128 * density).toInt())
    }

    fun bind(finishedTitle: String, nextTitle: String) {
        seam = Seam(finishedTitle, nextTitle)
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
                        topChapterDownloaded = false,
                        bottomLabel = stringResource(MR.strings.transition_next),
                        bottomChapter = TransitionChapter(shown.nextTitle, subtitle = null),
                        bottomChapterDownloaded = false,
                        // Both chapters are present, so the fallback is unreachable here.
                        fallbackLabel = "",
                        // The reading order already skips what a gap would warn about, so there is none
                        // to report: a novel's neighbour is whatever the order says comes next.
                        chapterGap = 0,
                    )
                }
            }
        }
    }

    private data class Seam(val finishedTitle: String, val nextTitle: String)
}
