package yokai.presentation.details

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import eu.kanade.tachiyomi.util.storage.getUriCompat
import kotlinx.coroutines.flow.SharedFlow

/**
 * Collects a details screen's [DetailsEvent] stream: shows undo snackbars (Short duration), launches a
 * share chooser for a saved image, and delegates sibling navigation to [onNavigateToSibling] (which
 * differs between the manga and novel screens). Shared by both details screens.
 */
@Composable
fun HandleDetailsEvents(
    events: SharedFlow<DetailsEvent>,
    snackbarHostState: SnackbarHostState,
    context: Context,
    onNavigateToSibling: (Long) -> Unit,
) {
    LaunchedEffect(Unit) {
        events.collect { event ->
            when (event) {
                is DetailsEvent.Snackbar -> {
                    val result = snackbarHostState.showSnackbar(
                        message = event.message,
                        actionLabel = event.actionLabel,
                        duration = SnackbarDuration.Short,
                    )
                    if (result == SnackbarResult.ActionPerformed) event.onAction?.invoke() else event.onDismiss?.invoke()
                }
                is DetailsEvent.NavigateToSibling -> onNavigateToSibling(event.id)
                is DetailsEvent.ShareImage -> {
                    val uri = event.file.getUriCompat(context)
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "image/*"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                        clipData = ClipData.newRawUri(null, uri)
                    }
                    context.startActivity(Intent.createChooser(intent, null))
                }
            }
        }
    }
}
