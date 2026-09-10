package reikai.presentation.reader.text

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.theme.TachiyomiTheme
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Drawn at the edge of the window where the chapter beyond it would not load, so the text stops with
 * a reason and a way out rather than simply ending. The manga viewers put the same thing in their
 * chapter transition; this is that shape for a reader whose seams are not separate items.
 */
class NovelBoundaryFailureView(context: Context) : AbstractComposeView(context) {

    private var failure: Failure? by mutableStateOf(null)

    /** Held here rather than in the window, because the window's answer to "is it still failing" only
     *  arrives once the retry has finished, and the reader needs an answer the moment they tap. */
    private var retrying by mutableStateOf(false)

    init {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
    }

    fun bind(message: String?, onRetry: () -> Unit) {
        failure = Failure(message, onRetry)
        retrying = false
    }

    @Composable
    override fun Content() {
        val shown = failure ?: return
        TachiyomiTheme {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(MR.strings.chapter_load_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                    shown.message?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                    }
                    if (retrying) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    } else {
                        TextButton(
                            onClick = {
                                retrying = true
                                shown.onRetry()
                            },
                        ) {
                            Text(stringResource(MR.strings.action_retry))
                        }
                    }
                }
            }
        }
    }

    private data class Failure(val message: String?, val onRetry: () -> Unit)
}
