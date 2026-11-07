package reikai.presentation.reader.text

import android.content.Context
import androidx.annotation.ColorInt
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.unit.Constraints
import androidx.core.view.isVisible
import eu.kanade.presentation.reader.TransitionChapter
import eu.kanade.presentation.reader.TransitionText
import eu.kanade.presentation.theme.TachiyomiTheme
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.math.ceil

/**
 * The marker between two chapters the reader runs straight through, drawn by the same composable the
 * manga viewers use so a seam reads the same in either.
 *
 * [seam] is what it says, or null to hide it; one with no next chapter is the end-of-novel marker.
 * [textColor] is the reader's background's text colour. [pageHeightPx] is the page it fits on whole when
 * paged (NovelSeam.fitScale), null while scrolling. All fixed: the viewport swaps in a new view instead.
 */
class NovelChapterSeamView(
    context: Context,
    val seam: NovelSeam?,
    @ColorInt val textColor: Int,
    val pageHeightPx: Int? = null,
) : AbstractComposeView(context) {

    private val maxPaddingPx = (PADDING_VERTICAL_DP * resources.displayMetrics.density).toInt()

    init {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        val horizontal = (PADDING_HORIZONTAL_DP * resources.displayMetrics.density).toInt()
        // A page's marker takes its padding inside the fit instead, which gives it up first.
        val vertical = if (pageHeightPx == null) maxPaddingPx else 0
        setPadding(horizontal, vertical, horizontal, vertical)
        isVisible = seam != null
    }

    /** Whether this view already fits a page [page] tall, which a hidden one does any page. */
    fun fits(page: Int?): Boolean = seam == null || page == pageHeightPx

    @Composable
    override fun Content() {
        val shown = seam ?: return
        TachiyomiTheme {
            val page = pageHeightPx
            val color = Color(textColor)
            if (page == null) {
                NovelSeamCard(shown, color)
            } else {
                FitOnPage(page, maxPaddingPx) { NovelSeamCard(shown, color) }
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

/**
 * The card in the reader's [textColor]. TransitionText's warning card draws in the theme's onSurface, the app's
 * colour rather than the reader page's, so the scheme takes the reader's colour for it as the WebView's does.
 */
@Composable
internal fun NovelSeamCard(shown: NovelSeam, textColor: Color) {
    MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(onSurface = textColor)) {
        CompositionLocalProvider(
            // ChapterTransition's own style, which the manga viewers draw this with.
            LocalTextStyle provides MaterialTheme.typography.bodyMedium,
            LocalContentColor provides textColor,
        ) {
            // Centred the way the webtoon holder's gravity centres it, on a screen wider than the column's cap.
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

/**
 * [card] fitted to a [page] tall: measured as it is, then laid out wider by the scale and drawn scaled back, which is
 * a whole-card zoom that rewraps as the WebView page's CSS zoom does. Composed twice, since intrinsic heights miss
 * TransitionText's width cap and came out short on a wide screen.
 */
@Composable
private fun FitOnPage(page: Int, maxPadding: Int, card: @Composable () -> Unit) {
    SubcomposeLayout { constraints ->
        val width = constraints.maxWidth
        val natural = subcompose(FitSlot.NATURAL, card).single().measure(Constraints.fixedWidth(width))
        val scale = NovelSeam.fitScale(natural.height.toFloat(), page.toFloat())
        val fitted = if (scale == 1f) {
            natural
        } else {
            subcompose(FitSlot.FITTED, card).single().measure(Constraints.fixedWidth((width / scale).toInt()))
        }
        val shown = ceil(fitted.height * scale).toInt()
        val padding = NovelSeam.fitPadding(shown.toFloat(), page.toFloat(), maxPadding.toFloat()).toInt()
        layout(width, shown + 2 * padding) {
            fitted.placeWithLayer(0, padding) {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0f)
            }
        }
    }
}

private enum class FitSlot { NATURAL, FITTED }
