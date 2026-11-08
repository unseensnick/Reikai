package reikai.presentation.reader

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import eu.kanade.tachiyomi.util.system.toShareIntent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.app.di.appGraph
import reikai.presentation.components.PictureViewerDialog
import reikai.presentation.components.savePicture
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR

/** A novel chapter's picture full screen, with a turn for a wide one, and Share and Save as a cover has. */
@Composable
fun ReaderPictureDialog(picture: ReaderDialog.Picture, onDismissRequest: () -> Unit) {
    val context = LocalContext.current
    val imageSaver = remember { context.appGraph.imageSaver }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun save(forSharing: Boolean) = scope.launch {
        val uri = try {
            savePicture(context, imageSaver, picture.model, picture.name, forSharing)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logcat(LogPriority.ERROR, e)
            null
        }
        when {
            uri == null -> snackbarHostState.showSnackbar(context.stringResource(MR.strings.error_saving_picture))
            forSharing -> context.startActivity(uri.toShareIntent(context))
            else -> snackbarHostState.showSnackbar(context.stringResource(MR.strings.picture_saved))
        }
    }
    PictureViewerDialog(
        picture = picture.model,
        snackbarHostState = snackbarHostState,
        onShareClick = { save(forSharing = true) },
        onSaveClick = { save(forSharing = false) },
        onDismissRequest = onDismissRequest,
        canRotate = true,
    )
}
