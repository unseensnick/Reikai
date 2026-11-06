package eu.kanade.presentation.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import reikai.domain.reader.ChapterProgress
import reikai.domain.reader.ReaderProgressLook
import reikai.domain.reader.ReaderTimeLeft
import reikai.domain.reader.leadingLabel
import reikai.domain.reader.trailingLabel
import reikai.presentation.reader.readerTimeLeftLabel

@Composable
fun ReaderPageIndicator(
    // RK: a typed chapter position both readers answer, in place of page ints
    progress: ChapterProgress?,
    modifier: Modifier = Modifier,
    // RK: the chapter's time left, beside the position or alone when the position is off
    timeLeft: ReaderTimeLeft? = null,
    // RK -->
    look: ReaderProgressLook = ReaderProgressLook(),
    // The page's own colour, which a backed line sits on
    pageColor: Color? = null,
    // RK <--
) {
    // RK -->
    // Upstream's `totalPages <= 0` guard, kept through the retyping. A chapter whose pages have not
    // loaded reports a count of zero, and "1 / 0" is worse than showing nothing. The labels come from
    // the position kernel, so this reads a page count for manga and a percentage for a continuously
    // scrolled chapter without knowing which it has.
    val position = progress?.takeUnless { it is ChapterProgress.Pages && it.pageCount <= 0 }
        ?.let { "${it.leadingLabel} / ${it.trailingLabel}" }
    val text = listOfNotNull(position, timeLeft?.let { readerTimeLeftLabel(it) })
        .joinToString("  ·  ")
        .ifEmpty { return }
    val scale = look.size.scale
    val backing = pageColor?.takeIf { look.isBacked }
    // RK <--

    val style = TextStyle(
        // RK: on a strip the text takes the colour that reads on it, and needs no outline
        color = if (backing != null && backing.luminance() > 0.5f) Color(45, 45, 45) else Color(235, 235, 235),
        fontSize = MaterialTheme.typography.bodySmall.fontSize * scale, // RK
        // RK: the line height Text would inherit, grown with the font, or a large size overflows its line
        lineHeight = LocalTextStyle.current.lineHeight * scale,
        // RK: a large size can wrap, and each line stays centred under the page
        textAlign = TextAlign.Center,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
    )
    val strokeStyle = style.copy(
        color = Color(45, 45, 45),
        drawStyle = Stroke(width = 4f * scale), // RK
    )

    Box(
        contentAlignment = Alignment.Center,
        // RK: a strip spans the screen and fills the system bar's padding too, as the page does
        modifier = (backing?.let { Modifier.fillMaxWidth().background(it) } ?: Modifier).then(modifier),
    ) {
        if (backing == null) { // RK
            Text(
                text = text,
                style = strokeStyle,
            )
        } // RK
        Text(
            text = text,
            style = style,
        )
    }
}

@PreviewLightDark
@Composable
private fun ReaderPageIndicatorPreview() {
    TachiyomiPreviewTheme {
        Surface {
            // RK: typed chapter position
            ReaderPageIndicator(ChapterProgress.Pages(lastPageRead = 9, pageCount = 69))
        }
    }
}
