package reikai.presentation.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AdaptiveSheet
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Slider
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Text-size picker for the novel reader's bottom-bar text-size button: the same font size the settings
 * sheet's Reading tab steps, reachable in one tap. Applies live (the caller's model persists it and
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
            // The shared Int slider, which rounds a tick M3 hands over a hair under its size.
            Slider(value = fontSize, onValueChange = onFontSize, valueRange = NovelTextRanges.fontSize)
        }
    }
}
