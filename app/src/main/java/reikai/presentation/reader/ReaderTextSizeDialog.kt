package reikai.presentation.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AdaptiveSheet
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.math.roundToInt

/**
 * The size a slider position names. M3 works a tick's value out in float, so on most screen widths the
 * tick for 14 arrives as 13.999999 and truncating it stores 13, which then snaps the thumb back a tick
 * and leaves that size unpickable. Rounding is what makes every tick reachable, as `ChapterNavigator`'s
 * own seek already does.
 */
internal fun readerTextSizeOf(sliderValue: Float): Int = sliderValue.roundToInt()

/**
 * Text-size picker for the novel reader's bottom-bar text-size button: the same font-size slider as the
 * settings sheet's Display tab, reachable in one tap. Applies live (the caller's model persists it and
 * the reader reflows the text in place).
 */
@Composable
fun ReaderTextSizeDialog(
    fontSize: Int,
    onFontSize: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AdaptiveSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
            Text(
                "${stringResource(MR.strings.pref_reader_text_size)}: $fontSize",
                style = MaterialTheme.typography.titleSmall,
            )
            Slider(
                value = fontSize.toFloat(),
                onValueChange = { onFontSize(readerTextSizeOf(it)) },
                valueRange = 12f..32f,
                steps = 19,
            )
        }
    }
}
